package dev.diorama.craft;

import dev.diorama.Diorama;
import dev.diorama.bake.BlockLooks;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The moving crafts on one dimension's map: built structures that fly or roll around the world,
 * from whichever {@link Source}s are registered (trains, windmills, pistons), drawn over the
 * tiles as their own blocks, live as they move, each with its shadow on the ground.
 * {@link CraftPainter} draws them.
 *
 * <p>A craft that stops and then leaves the client's view, because the player flew off without
 * it, stays on the map where it was left, and is saved with the map so it's still there next
 * time. It's dropped once the player comes back near and it isn't there.
 */
public final class Crafts {
	/** Ticks between reading a live craft's blocks again, in case they changed. */
	private static final int READ_EVERY = 600;
	/** Ticks a craft has to stand still before it stays on the map once out of view. */
	private static final int PARK_AFTER = 100;
	/** Blocks a craft's origin has to move to count as moving. */
	private static final double STILL = 0.25;
	/** Within this many blocks (across) of a parked craft the client would see it, if it were there. */
	private static final double NEAR = 48;
	/** Ticks the player has to be near a parked craft that doesn't show up before it's dropped. */
	private static final int GONE_AFTER = 100;
	private static final int SAVE_EVERY = 1200;

	/** Where crafts come from: a mod that moves built structures around the world. */
	public interface Source {
		/** The crafts the client has in this level now. Client thread, every tick. */
		List<Body> bodies(ClientLevel level);
	}

	private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

	/** Adds a source of crafts, for every map from now on. */
	public static void register(Source source) {
		SOURCES.add(source);
	}

	private final ClientLevel level;
	/** Told the world box of the blocks a craft took out of the world or put back. */
	private final Consumer<int[]> worldChanged;
	private final @Nullable Path file;
	private final Map<UUID, Craft> crafts = new HashMap<>();
	private final CraftPainter painter;
	private @Nullable CompletableFuture<List<CraftFile.Saved>> loading;
	private boolean dirty;
	private long savedAt;
	/** A block changed in a craft that couldn't be told: read them all again. */

	/**
	 * {@code worldChanged} hears where a craft came out of the world or went back into it. A
	 * craft can be assembled from the world's blocks and leave them again when it's taken apart,
	 * and the map isn't always told of those block changes, so it looks again there.
	 */
	public Crafts(ClientLevel level, MapLevel ground, @Nullable Path folder, Consumer<int[]> worldChanged) {
		this.level = level;
		this.worldChanged = worldChanged;
		this.painter = new CraftPainter(ground);
		this.file = folder == null ? null : folder.resolve("crafts.bin");
		if (this.file != null) {
			Path from = this.file;
			this.loading = CompletableFuture.supplyAsync(() -> CraftFile.read(from), CraftPainter.BAKER);
		}
	}

	/** Keeps the crafts in step with the level. Client thread, every tick. */
	public void tick(@Nullable LocalPlayer player, long tick) {
		BlockLooks looks = BlockLooks.get();
		if (looks == null) {
			return;
		}
		this.restore(tick);
		Set<UUID> live = new HashSet<>();
		List<Body> bodies = new ArrayList<>();
		for (Source source : SOURCES) {
			bodies.addAll(source.bodies(this.level));
		}
		for (Body body : bodies) {
			live.add(body.id());
			Craft craft = this.crafts.computeIfAbsent(body.id(), Craft::new);
			if (craft.parked) {
				craft.parked = false;
				this.dirty = true;
			}
			craft.body = body;
			craft.nearSince = -1;
			// Spread the periodic reads out over ticks, so many crafts don't all copy at once.
			boolean due = (tick + (craft.id.hashCode() & 0x7FFFFFFF)) % READ_EVERY == 0;
			if (craft.blocks == null || due) {
				Body.Blocks read = body.read(looks);
				if (read != null && (craft.blocks == null || !read.blocks().looksLike(craft.blocks.blocks(), looks)
					|| !java.util.Arrays.equals(read.min(), craft.blocks.min()))) {
					craft.blocks = read;
				}
			}
			if (craft.blocks != null && body.pose(craft.blocks.min(), 1, craft.pose)) {
				if (!craft.posed || distance(craft.pose, craft.anchor) > STILL) {
					System.arraycopy(craft.pose, 0, craft.anchor, 0, 3);
					craft.movedAt = tick;
				}
				craft.posed = true;
				if (!craft.announced) {
					craft.announced = true;
					this.worldChanged.accept(craft.box(craft.pose));
				}
			}
		}
		Iterator<Craft> it = this.crafts.values().iterator();
		while (it.hasNext()) {
			Craft craft = it.next();
			if (live.contains(craft.id)) {
				continue;
			}
			if (craft.body != null) {
				// Just left the client's view: keep it where it stopped, or let it go if it was moving.
				craft.body = null;
				if (!craft.posed || craft.blocks == null || tick - craft.movedAt < PARK_AFTER) {
					if (craft.posed) {
						this.worldChanged.accept(craft.box(craft.pose));
					}
					craft.close();
					it.remove();
					continue;
				}
				craft.parked = true;
				craft.nearSince = -1;
				this.dirty = true;
			}
			if (craft.parked && player != null) {
				double[] c = craft.center(craft.pose);
				double dx = c[0] - player.getX();
				double dz = c[2] - player.getZ();
				if (dx * dx + dz * dz < NEAR * NEAR) {
					if (craft.nearSince < 0) {
						craft.nearSince = tick;
					} else if (tick - craft.nearSince > GONE_AFTER) {
						this.worldChanged.accept(craft.box(craft.pose));
						craft.close();
						it.remove();
						this.dirty = true;
					}
				} else {
					craft.nearSince = -1;
				}
			}
		}
		if (this.dirty && tick - this.savedAt >= SAVE_EVERY) {
			this.savedAt = tick;
			this.save(tick, false);
		}
	}

	private static double distance(double[] pose, double[] anchor) {
		double dx = pose[0] - anchor[0];
		double dy = pose[1] - anchor[1];
		double dz = pose[2] - anchor[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private void restore(long tick) {
		if (this.loading == null || !this.loading.isDone()) {
			return;
		}
		List<CraftFile.Saved> saved = this.loading.getNow(List.of());
		this.loading = null;
		for (CraftFile.Saved s : saved) {
			Body.Blocks blocks = s.decode(this.level);
			if (blocks == null || this.crafts.containsKey(s.id())) {
				continue;
			}
			Craft craft = new Craft(s.id());
			craft.blocks = blocks;
			System.arraycopy(s.pose(), 0, craft.pose, 0, 12);
			craft.posed = true;
			craft.parked = true;
			craft.movedAt = tick - PARK_AFTER;
			this.crafts.put(s.id(), craft);
		}
		if (!saved.isEmpty() && Diorama.DEBUG) {
			Diorama.LOGGER.info("Restored {} parked crafts", saved.size());
		}
	}

	/** Writes the crafts that would stay on the map if the player left now: on the baker thread, unless {@code now}. */
	private void save(long tick, boolean now) {
		if (this.file == null || this.loading != null) {
			return;
		}
		this.dirty = false;
		List<CraftFile.Saved> keep = new ArrayList<>();
		for (Craft craft : this.crafts.values()) {
			if (craft.blocks != null && craft.posed && (craft.parked || tick - craft.movedAt >= PARK_AFTER)) {
				keep.add(CraftFile.Saved.of(craft.id, craft.pose, craft.blocks));
			}
		}
		// On the baker thread either way, so two writes never overlap.
		Path file = this.file;
		CompletableFuture<Void> written = CompletableFuture.runAsync(() -> CraftFile.write(file, keep), CraftPainter.BAKER);
		if (now) {
			written.join();
		}
	}

	/** Saves and frees the textures. Client thread. */
	public void close(long tick) {
		this.save(tick, true);
		for (Craft craft : this.crafts.values()) {
			craft.close();
		}
		this.crafts.clear();
	}

	/** Draws the crafts and their shadows over a view of the map. Render thread. */
	public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, float partialTick, float opacity) {
		this.painter.draw(graphics, view, this.crafts.values(), partialTick, opacity);
	}
}
