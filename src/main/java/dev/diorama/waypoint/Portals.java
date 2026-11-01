package dev.diorama.waypoint;

import dev.diorama.bake.ChunkSource;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.render.WaypointIcons;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a pin on each lit portal the map comes across: Nether portals, End portals and End gateways.
 * Nothing but a player lights one, so unlit frames and ruined portals don't count. Every chunk the
 * map takes in, live or from the save, is looked through here, which is cheap: a section's palette
 * lists every block in it, and only a section with a portal in its palette is read block by block.
 * Pins are kept with the setting off too, hidden ({@link WaypointStore#hidden}), so turning it on
 * shows the portals the map has already passed.
 */
public final class Portals {
	/**
	 * Portals of a kind this close, in blocks across the map, share a pin. It takes in the widest
	 * Nether portal, 21 blocks plus its frame, from any block of it, and a pile of portals side by
	 * side, which would be a pile of pins.
	 */
	private static final int SAME_PORTAL = 24;
	private static final int NETHER_COLOR = 0xFF8E4EC6;
	private static final int END_COLOR = 0xFF12A594;
	private static final Predicate<BlockState> PORTAL = Portals::isPortal;

	/** One kind of portal in a chunk, at a block of it near the middle. */
	public record Spot(Waypoint.Kind kind, int x, int y, int z) {
	}

	private Portals() {
	}

	public static boolean isPortal(BlockState state) {
		return kind(state.getBlock()) != null;
	}

	private static Waypoint.@Nullable Kind kind(Block block) {
		if (block == Blocks.NETHER_PORTAL) {
			return Waypoint.Kind.NETHER_PORTAL;
		}
		if (block == Blocks.END_PORTAL) {
			return Waypoint.Kind.END_PORTAL;
		}
		return block == Blocks.END_GATEWAY ? Waypoint.Kind.END_GATEWAY : null;
	}

	/** Where the portals in a chunk are, one spot per kind. Any thread. */
	public static List<Spot> find(ChunkSnapshot chunk) {
		Map<Waypoint.Kind, List<int[]>> blocks = null;
		for (int i = 0; i < chunk.sectionCount(); i++) {
			PalettedContainer<BlockState> section = chunk.section(i);
			if (section == null || !section.maybeHas(PORTAL)) {
				continue;
			}
			int baseY = (chunk.minSectionY + i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						Waypoint.Kind kind = kind(section.get(x, y, z).getBlock());
						if (kind != null) {
							if (blocks == null) {
								blocks = new EnumMap<>(Waypoint.Kind.class);
							}
							blocks.computeIfAbsent(kind, k -> new ArrayList<>()).add(new int[] {(chunk.chunkX << 4) + x, baseY + y, (chunk.chunkZ << 4) + z});
						}
					}
				}
			}
		}
		if (blocks == null) {
			return List.of();
		}
		List<Spot> spots = new ArrayList<>();
		for (Map.Entry<Waypoint.Kind, List<int[]>> entry : blocks.entrySet()) {
			int[] middle = entry.getValue().get(entry.getValue().size() / 2);
			spots.add(new Spot(entry.getKey(), middle[0], middle[1], middle[2]));
		}
		return spots;
	}

	/**
	 * A chunk of the live world came in: pins any new portal, and takes pins off portals that are
	 * gone. A pin the player renamed stays, as it's theirs now; a language change counts as that too.
	 * A pin can have been standing for portals nearby as well, so those are looked at again in
	 * {@code around}, the chunks the map has.
	 */
	public static void chunkSeen(ChunkSnapshot chunk, String dimension, WaypointStore store, ChunkSource around) {
		List<Spot> spots = find(chunk);
		add(spots, dimension, store);
		for (Waypoint pin : store.withHidden(dimension)) {
			if (pin.kind.isPortal() && pin.x >> 4 == chunk.chunkX && pin.z >> 4 == chunk.chunkZ
				&& spots.stream().noneMatch(spot -> spot.kind() == pin.kind) && (pin.deleted || pin.name.equals(name(pin.kind)))) {
				store.forget(pin);
				int reach = (SAME_PORTAL + 15) >> 4;
				for (int dx = -reach; dx <= reach; dx++) {
					for (int dz = -reach; dz <= reach; dz++) {
						ChunkSnapshot near = around.chunk(chunk.chunkX + dx, chunk.chunkZ + dz);
						if (near != null) {
							add(find(near), dimension, store);
						}
					}
				}
			}
		}
	}

	/** Pins the spots no pin covers yet. A waypoint the player gave the portal icon counts. Client thread. */
	public static void add(List<Spot> spots, String dimension, WaypointStore store) {
		for (Spot spot : spots) {
			boolean pinned = false;
			for (Waypoint waypoint : store.withHidden(dimension)) {
				boolean marksPortals = waypoint.kind == spot.kind() || !waypoint.kind.isPortal() && waypoint.icon.equals(WaypointIcons.PORTAL);
				pinned |= marksPortals && Math.abs(waypoint.x - spot.x()) <= SAME_PORTAL && Math.abs(waypoint.z - spot.z()) <= SAME_PORTAL;
			}
			if (!pinned) {
				int color = spot.kind() == Waypoint.Kind.NETHER_PORTAL ? NETHER_COLOR : END_COLOR;
				Waypoint pin = new Waypoint(name(spot.kind()), spot.x(), spot.y(), spot.z(), dimension, color);
				pin.kind = spot.kind();
				pin.icon = WaypointIcons.PORTAL;
				store.add(pin);
			}
		}
	}

	/** The game's own name for the block, in the player's language. */
	private static String name(Waypoint.Kind kind) {
		Block block = kind == Waypoint.Kind.NETHER_PORTAL ? Blocks.NETHER_PORTAL : kind == Waypoint.Kind.END_PORTAL ? Blocks.END_PORTAL : Blocks.END_GATEWAY;
		return block.getName().getString();
	}
}
