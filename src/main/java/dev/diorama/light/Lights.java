package dev.diorama.light;

import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.SurfaceArea;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.VoxelReader;
import dev.diorama.render.Circle;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Light carried around a cave slice: a torch or lantern in a player's hand lights the cave around
 * them on the map as it does in the game. The surface map is a daytime view, where it wouldn't
 * show, so only cave slices get it.
 *
 * <p>Each light is a level dimmer for each block of the shortest open path to it, diagonals
 * included, so it comes out round in the open and still goes round corners and stops at walls.
 * The pixels it then lights brighter than their tile shows are lit again from what the tile
 * recorded about them ({@link SurfaceArea#relit}), with the same lighting the tile was baked
 * with but the brighter block light, and laid over the tile. Render thread, but the overlays are
 * made on a thread of their own.
 */
public final class Lights {
	private final ClientLevel level;
	private final MapLevel map;
	/** The last overlay made for each group of lights, by its first light's player. */
	private final Map<UUID, Glow> glows = new HashMap<>();

	/** A group of lights' overlay: {@code width} by {@code height} map pixels from (pu0, pv0) in an {@code edge}-square texture. */
	private static final class Glow {
		@Nullable Object madeFor;
		@Nullable MapTexture texture;
		int edge;
		int layer;
		List<UUID> lights = List.of();
		List<Bounds> taken = List.of();
		int pu0;
		int pv0;
		int width;
		int height;
		boolean lit;
		@Nullable CompletableFuture<Made> pending;

		Bounds bounds() {
			return new Bounds(this.pu0, this.pv0, this.pu0 + this.width, this.pv0 + this.height);
		}

		/** Whether this and {@code other} share no pixels, one having left out the other's. */
		boolean apart(Glow other) {
			Bounds a = this.bounds();
			Bounds b = other.bounds();
			return !a.touches(b.pu0(), b.pv0(), b.pu1(), b.pv1()) || this.taken.contains(b) || other.taken.contains(a);
		}
	}

	/** Makes the overlays, off the render thread. */
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-held-light");
		thread.setDaemon(true);
		return thread;
	});

	public Lights(ClientLevel level, MapLevel map) {
		this.level = level;
		this.map = map;
	}

	/** The light level a player carries, 0..15: the brightest block light in either hand. */
	static int carried(Player player) {
		return Math.max(emission(player.getMainHandItem()), emission(player.getOffhandItem()));
	}

	private static int emission(ItemStack stack) {
		if (stack.is(Items.LAVA_BUCKET)) {
			return 15;
		}
		return stack.getItem() instanceof BlockItem item ? item.getBlock().defaultBlockState().getLightEmission() : 0;
	}

	/**
	 * A light carried at block (sx, sy, sz), and the map pixels (pu0, pv0) to (pu1, pv1) it can
	 * light: a block past the cube it spreads over, where smoothing still carries its last level.
	 */
	private record Held(UUID id, int sx, int sy, int sz, int carried, int pu0, int pv0, int pu1, int pv1) {
		static Held of(UUID id, int sx, int sy, int sz, int carried) {
			double sin = Projection.sin();
			double cos = Projection.cos();
			int ppu = Projection.PIXELS_PER_UNIT;
			return new Held(id, sx, sy, sz, carried, (sx - carried) * ppu, (int) Math.floor(((sz - carried) * sin - (sy + carried + 1) * cos) * ppu),
				(sx + carried + 1) * ppu, (int) Math.ceil(((sz + carried + 1) * sin - (sy - carried) * cos) * ppu));
		}

		boolean touches(Held other) {
			return this.pu0 < other.pu1 && other.pu0 < this.pu1 && this.pv0 < other.pv1 && other.pv0 < this.pv1;
		}
	}

	/** Map pixels (pu0, pv0) to (pu1, pv1). */
	private record Bounds(int pu0, int pv0, int pu1, int pv1) {
		boolean touches(int pu0, int pv0, int pu1, int pv1) {
			return this.pu0 < pu1 && pu0 < this.pu1 && this.pv0 < pv1 && pv0 < this.pv1;
		}

		boolean contains(int pu, int pv) {
			return pu >= this.pu0 && pu < this.pu1 && pv >= this.pv0 && pv < this.pv1;
		}
	}

	/**
	 * Draws the carried lights over a view of a cave slice. Other players' lights only where
	 * {@code radar} is on and it would show them: a light gives its holder away no more than
	 * their face would.
	 */
	public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, float partialTick, float opacity, boolean radar) {
		Player self = Minecraft.getInstance().player;
		if (view.layer() == TileKey.SURFACE || this.map.roofless() || self == null) {
			this.close();
			return;
		}
		double ppu = Projection.PIXELS_PER_UNIT;
		double vu0 = view.minU() * ppu;
		double vv0 = view.minV() * ppu;
		double vu1 = vu0 + view.width() / view.zoom() * ppu;
		double vv1 = vv0 + view.height() / view.zoom() * ppu;
		List<Held> held = new ArrayList<>();
		for (Player player : this.level.players()) {
			int carried = carried(player);
			if (carried <= 0 || player != self && !(radar && onRadar(player, self, partialTick))) {
				continue;
			}
			Vec3 eye = player.getEyePosition(partialTick);
			// Held at about shoulder height.
			int sy = (int) Math.floor(eye.y - 0.5);
			if (sy > view.layer()) {
				continue;
			}
			Held light = Held.of(player.getUUID(), (int) Math.floor(eye.x), sy, (int) Math.floor(eye.z), carried);
			// Only lights that reach into the view, so a group never spans more than the view and their reach.
			if (light.pu1() > vu0 && light.pu0() < vu1 && light.pv1() > vv0 && light.pv0() < vv1) {
				held.add(light);
			}
		}
		MapStyle style = MapStyle.get();
		Map<UUID, Glow> kept = new HashMap<>();
		List<Shown> drawn = new ArrayList<>();
		// In id order, so the groups come out the same every frame.
		held.sort(Comparator.comparing(Held::id));
		List<Bounds> before = new ArrayList<>();
		for (List<Held> group : groups(held)) {
			// Each group keeps the overlay one of its lights had, so it stays up until the next is made.
			Glow glow = null;
			for (Held light : group) {
				Glow old = this.glows.remove(light.id());
				if (glow == null) {
					glow = old;
				} else {
					drop(old);
				}
			}
			if (glow == null) {
				glow = new Glow();
			}
			kept.put(group.get(0).id(), glow);
			int pu0 = group.stream().mapToInt(Held::pu0).min().getAsInt();
			int pv0 = group.stream().mapToInt(Held::pv0).min().getAsInt();
			int pu1 = group.stream().mapToInt(Held::pu1).max().getAsInt();
			int pv1 = group.stream().mapToInt(Held::pv1).max().getAsInt();
			// Every light that reaches the overlay, but only pixels no earlier group's overlay has, so
			// groups split at MAX_SPAN meet without a seam or a pixel drawn twice.
			List<Held> lights = held.stream().filter(light -> light.pu0() < pu1 && pu0 < light.pu1() && light.pv0() < pv1 && pv0 < light.pv1()).toList();
			List<Bounds> taken = before.stream().filter(b -> b.touches(pu0, pv0, pu1, pv1)).toList();
			before.add(new Bounds(pu0, pv0, pu1, pv1));
			this.make(glow, lights, taken, pu0, pv0, pu1, pv1, view.layer(), style);
			// Not one made for another slice, or with a light no longer shown, while this one's is made.
			if (glow.lit && glow.texture != null && glow.layer == view.layer() && lights.stream().map(Held::id).toList().containsAll(glow.lights)) {
				// One still being remade after its group split differently can overlap another; only what's outside it is drawn until it's made.
				List<Bounds> pieces = List.of(glow.bounds());
				for (Shown other : drawn) {
					if (!glow.apart(other.glow())) {
						for (Bounds hole : other.pieces()) {
							pieces = cut(pieces, hole);
						}
					}
				}
				drawn.add(new Shown(glow, pieces));
			}
		}
		this.glows.values().forEach(Lights::drop);
		this.glows.clear();
		this.glows.putAll(kept);
		MapCanvas canvas = MapCanvas.of(graphics);
		Circle circle = view.round() ? Circle.in(view) : null;
		int tint = Math.round(Math.max(0, Math.min(1, opacity)) * 255) << 24 | 0xFFFFFF;
		for (Shown shown : drawn) {
			Glow glow = shown.glow();
			for (Bounds piece : shown.pieces()) {
				float x0 = view.screenX(piece.pu0() / ppu);
				float y0 = view.screenY(piece.pv0() / ppu);
				float x1 = view.screenX(piece.pu1() / ppu);
				float y1 = view.screenY(piece.pv1() / ppu);
				float tu0 = (float) (piece.pu0() - glow.pu0) / glow.edge;
				float tv0 = (float) (piece.pv0() - glow.pv0) / glow.edge;
				float tu1 = (float) (piece.pu1() - glow.pu0) / glow.edge;
				float tv1 = (float) (piece.pv1() - glow.pv0) / glow.edge;
				if (circle != null) {
					Circle.Quads inside = circle.clip(x0, y0, x1, y1, tu0, tv0, tu1, tv1);
					if (inside != null) {
						canvas.quad(glow.texture, MapViewport.sampling(view), inside.corners(), inside.uvs(), tint);
					}
				} else {
					canvas.rect(glow.texture, MapViewport.sampling(view), x0, y0, x1, y1, tu0, tv0, tu1, tv1, tint);
				}
			}
		}
	}

	/** An overlay to draw, as the parts of it no overlay drawn before it covers. */
	private record Shown(Glow glow, List<Bounds> pieces) {
	}

	/** {@code pieces} with {@code hole} taken out. */
	private static List<Bounds> cut(List<Bounds> pieces, Bounds hole) {
		List<Bounds> left = new ArrayList<>();
		for (Bounds p : pieces) {
			if (!p.touches(hole.pu0(), hole.pv0(), hole.pu1(), hole.pv1())) {
				left.add(p);
				continue;
			}
			int pv0 = Math.max(p.pv0(), hole.pv0());
			int pv1 = Math.min(p.pv1(), hole.pv1());
			if (p.pv0() < hole.pv0()) {
				left.add(new Bounds(p.pu0(), p.pv0(), p.pu1(), hole.pv0()));
			}
			if (hole.pv1() < p.pv1()) {
				left.add(new Bounds(p.pu0(), hole.pv1(), p.pu1(), p.pv1()));
			}
			if (p.pu0() < hole.pu0()) {
				left.add(new Bounds(p.pu0(), pv0, hole.pu0(), pv1));
			}
			if (hole.pu1() < p.pu1()) {
				left.add(new Bounds(hole.pu1(), pv0, p.pu1(), pv1));
			}
		}
		return left;
	}

	/** Whether the radar shows {@code player} to {@code self}, as it does other players. */
	private static boolean onRadar(Player player, Player self, float partialTick) {
		Settings settings = Settings.get();
		return settings.radarPlayers && player.isAlive() && !player.isInvisibleTo(self) && (!player.isSpectator() || self.isSpectator())
			&& Math.abs(player.getPosition(partialTick).y - self.getPosition(partialTick).y) <= settings.radarRange;
	}

	/**
	 * The lights in groups whose patches touch, each made as one overlay so that where they cross
	 * the brighter one shows, whatever order they're drawn in, up to {@link #MAX_SPAN} across.
	 * Each group is in id order.
	 */
	private static List<List<Held>> groups(List<Held> held) {
		List<List<Held>> groups = new ArrayList<>();
		for (Held light : held) {
			List<Held> joined = new ArrayList<>(List.of(light));
			for (Iterator<List<Held>> it = groups.iterator(); it.hasNext(); ) {
				List<Held> group = it.next();
				if (group.stream().anyMatch(light::touches) && span(joined, group) <= MAX_SPAN) {
					joined.addAll(group);
					it.remove();
				}
			}
			joined.sort(Comparator.comparing(Held::id));
			groups.add(joined);
		}
		return groups;
	}

	/** The widest a group's overlay gets, in map pixels; a chain of lights longer than this is drawn in more than one. */
	private static final int MAX_SPAN = 512;

	/** How wide or tall, whichever is more, an overlay for both groups of lights would be. */
	private static int span(List<Held> a, List<Held> b) {
		int pu0 = Integer.MAX_VALUE;
		int pv0 = Integer.MAX_VALUE;
		int pu1 = Integer.MIN_VALUE;
		int pv1 = Integer.MIN_VALUE;
		for (List<Held> group : List.of(a, b)) {
			for (Held light : group) {
				pu0 = Math.min(pu0, light.pu0());
				pv0 = Math.min(pv0, light.pv0());
				pu1 = Math.max(pu1, light.pu1());
				pv1 = Math.max(pv1, light.pv1());
			}
		}
		return Math.max(pu1 - pu0, pv1 - pv0);
	}

	/**
	 * Makes a group's overlay again if a light moved to another block, changed strength, or the
	 * ground under them is better known: the pixels they light brighter than their tile shows.
	 * Made on the light thread; the last one made stays up until the next is ready.
	 */
	private void make(Glow glow, List<Held> lights, List<Bounds> taken, int pu0, int pv0, int pu1, int pv1, int layer, MapStyle style) {
		if (glow.pending != null) {
			if (!glow.pending.isDone()) {
				return;
			}
			CompletableFuture<Made> pending = glow.pending;
			glow.pending = null;
			try {
				show(glow, pending.join());
			} catch (CompletionException | CancellationException e) {
				Diorama.LOGGER.warn("Couldn't light the map around a held light", e);
			}
		}
		SurfaceArea area = this.map.surfaces(layer, pu0, pv0, pu1, pv1);
		// Read once here, so the overlay's bounds and what it lights agree.
		double sin = Projection.sin();
		double cos = Projection.cos();
		List<Object> key = List.of(lights, taken, layer, style, sin, cos, java.util.Arrays.asList(area.tiles()));
		if (key.equals(glow.madeFor)) {
			return;
		}
		glow.madeFor = key;
		VoxelReader reader = new VoxelReader(this.map, BlockLooks.get());
		glow.pending = CompletableFuture.supplyAsync(() -> build(reader, area, lights, taken, layer, pu0, pv0, pu1 - pu0, pv1 - pv0, style, sin, cos), WORKER);
	}

	/** An overlay made: its mips, or null where it lights nothing brighter than the tiles. */
	private record Made(List<UUID> lights, List<Bounds> taken, int layer, int pu0, int pv0, int width, int height, int edge, int @Nullable [][] mips) {
	}

	private static Made build(
		VoxelReader reader, SurfaceArea area, List<Held> lights, List<Bounds> taken, int layer, int pu0, int pv0, int width, int height, MapStyle style, double sin, double cos
	) {
		int ppu = Projection.PIXELS_PER_UNIT;
		float[][] levels = new float[lights.size()][];
		for (int i = 0; i < levels.length; i++) {
			Held light = lights.get(i);
			levels[i] = spread(reader, light.sx(), light.sy(), light.sz(), light.carried(), light.carried() - 1, 2 * light.carried() - 1);
		}
		int step = 1 << (Mipmaps.LEVELS - 1);
		int edge = (Math.max(width, height) + step - 1) / step * step;
		int[] pixels = new int[edge * edge];
		float[] scratch = new float[4];
		boolean lit = false;
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				int pu = pu0 + px;
				int pv = pv0 + py;
				float y = area.y(pu, pv);
				if (Float.isNaN(y) || taken(taken, pu, pv)) {
					continue;
				}
				// Half a block out from the surface the camera sees here, into the open space in
				// front of it.
				boolean top = area.top(pu, pv);
				double wx = (pu + 0.5) / ppu;
				double wy = y + (top ? 0.5 : 0);
				double wz = ((pv + 0.5) / ppu + y * cos) / sin + (top ? 0 : 0.5);
				float light = 0;
				for (int i = 0; i < levels.length; i++) {
					Held held = lights.get(i);
					if (pu < held.pu0() || pu >= held.pu1() || pv < held.pv0() || pv >= held.pv1()) {
						continue;
					}
					int reach = held.carried() - 1;
					int x0 = held.sx() - reach;
					int y0 = held.sy() - reach;
					int z0 = held.sz() - reach;
					light = Math.max(light, smooth(levels[i], 2 * reach + 1, reader, x0, y0, z0, wx - x0, wy - y0, wz - z0));
				}
				int own = area.blockLight(pu, pv);
				if (light > own) {
					// Faded in over its first level, so the lit patch has no edge against the tile.
					int alpha = Math.round(Math.min(1, light - own) * 255);
					pixels[py * edge + px] = area.relit(pu, pv, light, alpha, style, scratch);
					lit = true;
				}
			}
		}
		return new Made(lights.stream().map(Held::id).toList(), taken, layer, pu0, pv0, width, height, edge, lit ? Mipmaps.build(pixels, edge) : null);
	}

	private static boolean taken(List<Bounds> taken, int pu, int pv) {
		for (Bounds bounds : taken) {
			if (bounds.contains(pu, pv)) {
				return true;
			}
		}
		return false;
	}

	/** Puts a made overlay up in place of the last. Render thread. */
	private static void show(Glow glow, Made made) {
		glow.lit = made.mips() != null;
		if (!glow.lit) {
			return;
		}
		if (glow.texture != null && glow.edge != made.edge()) {
			glow.texture.close();
			glow.texture = null;
		}
		if (glow.texture == null) {
			glow.texture = MapTexture.create("diorama held light", made.edge(), Mipmaps.LEVELS);
		}
		for (int level = 0; level < Mipmaps.LEVELS; level++) {
			glow.texture.write(level, 0, 0, made.edge() >> level, made.mips()[level]);
		}
		glow.edge = made.edge();
		glow.layer = made.layer();
		glow.lights = made.lights();
		glow.taken = made.taken();
		glow.pu0 = made.pu0();
		glow.pv0 = made.pv0();
		glow.width = made.width();
		glow.height = made.height();
	}

	/**
	 * Block light from a light of {@code carried} at (sx, sy, sz) over the cube {@code side} blocks
	 * across around it: {@code carried} less the length of the shortest path to each cell through
	 * blocks that aren't solid. Levels by cell, x fastest, then z, then y.
	 */
	private static float[] spread(VoxelReader reader, int sx, int sy, int sz, int carried, int reach, int side) {
		float[] levels = new float[side * side * side];
		// Per cell: 0 not looked at yet, 1 open, 2 closed.
		byte[] open = new byte[levels.length];
		PriorityQueue<Long> queue = new PriorityQueue<>();
		int start = (reach * side + reach) * side + reach;
		levels[start] = carried;
		queue.add((long) start);
		while (!queue.isEmpty()) {
			long entry = queue.poll();
			int cell = (int) entry;
			float distance = Float.intBitsToFloat((int) (entry >>> 32));
			if (carried - distance < levels[cell]) {
				continue;
			}
			int x = cell % side;
			int z = cell / side % side;
			int y = cell / (side * side);
			for (int[] step : STEPS) {
				int nx = x + step[0];
				int ny = y + step[1];
				int nz = z + step[2];
				if (nx < 0 || ny < 0 || nz < 0 || nx >= side || ny >= side || nz >= side) {
					continue;
				}
				int next = (ny * side + nz) * side + nx;
				float further = distance + STEP_LENGTHS[Math.abs(step[0]) + Math.abs(step[1]) + Math.abs(step[2])];
				if (carried - further <= levels[next] || !passes(reader, open, side, sx - reach, sy - reach, sz - reach, x, y, z, step)) {
					continue;
				}
				levels[next] = carried - further;
				queue.add((long) Float.floatToIntBits(further) << 32 | next);
			}
		}
		return levels;
	}

	/** Whether light can step from cell (x, y, z) by {@code step}: every cell the step crosses is open, so it can't squeeze between two blocks that touch at an edge. */
	private static boolean passes(VoxelReader reader, byte[] open, int side, int x0, int y0, int z0, int x, int y, int z, int[] step) {
		// Each non-empty subset of the step's axes is a cell it crosses.
		for (int c = 1; c < 8; c++) {
			int dx = (c & 1) == 0 ? 0 : step[0];
			int dy = (c & 2) == 0 ? 0 : step[1];
			int dz = (c & 4) == 0 ? 0 : step[2];
			if (c != ((dx != 0 ? 1 : 0) | (dy != 0 ? 2 : 0) | (dz != 0 ? 4 : 0))) {
				continue;
			}
			int cell = ((y + dy) * side + z + dz) * side + x + dx;
			if (open[cell] == 0) {
				// Full solid blocks stop it, and so do blocks the map doesn't know yet.
				int solid = reader.solid(x0 + x + dx, y0 + y + dy, z0 + z + dz);
				open[cell] = (byte) (solid == 1 || solid == VoxelReader.UNKNOWN ? 2 : 1);
			}
			if (open[cell] == 2) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The light at point (qx, qy, qz) of the cube, blended from the eight cells around it as the
	 * game's smooth lighting blends them: by how near each cell's middle is, leaving out solid
	 * blocks, so light fades smoothly across a block instead of stepping a level at a time.
	 */
	private static float smooth(float[] levels, int side, VoxelReader reader, int x0, int y0, int z0, double qx, double qy, double qz) {
		int bx = (int) Math.floor(qx - 0.5);
		int by = (int) Math.floor(qy - 0.5);
		int bz = (int) Math.floor(qz - 0.5);
		double fx = qx - 0.5 - bx;
		double fy = qy - 0.5 - by;
		double fz = qz - 0.5 - bz;
		double sum = 0;
		double weights = 0;
		for (int c = 0; c < 8; c++) {
			int x = bx + (c & 1);
			int y = by + (c >> 1 & 1);
			int z = bz + (c >> 2 & 1);
			if (x < 0 || y < 0 || z < 0 || x >= side || y >= side || z >= side || reader.solid(x0 + x, y0 + y, z0 + z) == 1) {
				continue;
			}
			double w = ((c & 1) == 0 ? 1 - fx : fx) * ((c >> 1 & 1) == 0 ? 1 - fy : fy) * ((c >> 2 & 1) == 0 ? 1 - fz : fz);
			sum += w * levels[(y * side + z) * side + x];
			weights += w;
		}
		return weights > 0 ? (float) (sum / weights) : 0;
	}

	/** Every step to a neighbouring cell, through a face, an edge or a corner. */
	private static final int[][] STEPS = steps();
	/** How long a step across one, two or three axes is. */
	private static final float[] STEP_LENGTHS = {0, 1, (float) Math.sqrt(2), (float) Math.sqrt(3)};

	private static int[][] steps() {
		List<int[]> steps = new ArrayList<>();
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (dx != 0 || dy != 0 || dz != 0) {
						steps.add(new int[] {dx, dy, dz});
					}
				}
			}
		}
		return steps.toArray(int[][]::new);
	}

	private static void drop(@Nullable Glow glow) {
		if (glow == null) {
			return;
		}
		if (glow.pending != null) {
			// A build not started yet is skipped.
			glow.pending.cancel(false);
		}
		if (glow.texture != null) {
			glow.texture.close();
		}
	}

	/** Frees the overlays. */
	public void close() {
		this.glows.values().forEach(Lights::drop);
		this.glows.clear();
	}
}
