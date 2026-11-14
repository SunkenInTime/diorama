package dev.diorama.waypoint;

import dev.diorama.bake.ChunkSource;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.render.WaypointIcons;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a pin on each lit portal the map comes across: Nether portals, End portals and End gateways.
 * Nothing but a player lights one, so unlit obsidian frames and ruined portals don't count. Every
 * chunk the map takes in, live or from the save, is looked through here, which is cheap: a section's
 * palette lists every block in it, and only a section with a portal in its palette is read block by
 * block. Pins are kept with the setting off too, hidden ({@link WaypointStore#hidden}), so turning
 * it on shows the portals the map has already passed.
 *
 * <p>A stronghold's End portal is the one worth a pin before it's lit: finding it is the hard part
 * (issue #384). The game makes those, though, so a pin on every one in loaded chunks would point the
 * way to strongholds nobody has found. An unlit ring of frames gets the End portal's pin once the
 * player has come up to it ({@link #playerAt}), at its centre, where the portal's middle block is
 * once it's lit: the same pin then stands on the lit portal.
 *
 * <p>A pin is named after its block only when the portal stands as the game builds it: a Nether
 * portal in obsidian, an End portal in its frame or the End's bedrock fountain, a gateway between
 * bedrock. Server plugins such as AdvancedPortals make portals to anywhere out of the same blocks in
 * frames of their own, and "End Portal" on a hub's server selector is wrong; those are named
 * {@link #PLAIN_NAME}. The client can't know where one leads, so it doesn't guess further.
 */
public final class Portals {
	/**
	 * Portals of a kind this close, in blocks across the map, share a pin. It takes in the widest
	 * Nether portal, 21 blocks plus its frame, from any block of it, and a pile of portals side by
	 * side, which would be a pile of pins. Not through a roof: on the Nether roof and under it are
	 * different places (see {@link #add}).
	 */
	private static final int SAME_PORTAL = 24;
	private static final int NETHER_COLOR = 0xFF8E4EC6;
	private static final int END_COLOR = 0xFF12A594;
	private static final Predicate<BlockState> PORTAL = Portals::isPortal;
	private static final Predicate<BlockState> END_FRAME = state -> state.getBlock() == Blocks.END_PORTAL_FRAME;
	/**
	 * How near an unlit End portal the player has to come for its pin, in blocks from its centre: the
	 * stronghold's portal room, whose door is 10 blocks from there, and a few blocks around it, not
	 * the rest of the stronghold or the ground above.
	 */
	private static final int FOUND = 16;

	/** The name saved for a portal that isn't built the game's way, shown as the portal icon's name. */
	static final String PLAIN_NAME = "Portal";

	/**
	 * One kind of portal in a chunk, at a block of it near the middle. {@code framed}: one of them
	 * stands as the game builds it (see {@link Portals}).
	 */
	public record Spot(Waypoint.Kind kind, int x, int y, int z, boolean framed) {
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

	/**
	 * Where the portals in a chunk are, one spot per kind, and per side of the dimension's
	 * {@code roof} ({@link ChunkSource#roof}): a portal on the Nether roof is often straight above one
	 * under it. Any thread.
	 */
	public static List<Spot> find(ChunkSnapshot chunk, int roof) {
		List<Map<Waypoint.Kind, List<int[]>>> blocks = null;
		// Per side of the roof, the kinds with a portal built the game's way.
		List<java.util.Set<Waypoint.Kind>> framed = List.of(java.util.EnumSet.noneOf(Waypoint.Kind.class), java.util.EnumSet.noneOf(Waypoint.Kind.class));
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
								blocks = List.of(new EnumMap<>(Waypoint.Kind.class), new EnumMap<>(Waypoint.Kind.class));
							}
							int side = roof != ChunkSource.NO_ROOF && baseY + y >= roof ? 1 : 0;
							if (!framed.get(side).contains(kind) && framed(chunk, kind, x, baseY + y, z)) {
								framed.get(side).add(kind);
							}
							blocks.get(side).computeIfAbsent(kind, k -> new ArrayList<>()).add(new int[] {(chunk.chunkX << 4) + x, baseY + y, (chunk.chunkZ << 4) + z});
						}
					}
				}
			}
		}
		if (blocks == null) {
			return List.of();
		}
		List<Spot> spots = new ArrayList<>();
		for (int side = 0; side < 2; side++) {
			for (Map.Entry<Waypoint.Kind, List<int[]>> entry : blocks.get(side).entrySet()) {
				int[] middle = entry.getValue().get(entry.getValue().size() / 2);
				spots.add(new Spot(entry.getKey(), middle[0], middle[1], middle[2], framed.get(side).contains(entry.getKey())));
			}
		}
		return spots;
	}

	/**
	 * Whether a portal block has beside it what the game puts there, looking no further than its
	 * chunk. That's enough: every lit Nether portal block has the frame's obsidian at the top or the
	 * bottom of its own column, and an End portal is three blocks wide (five in the End's fountain),
	 * so the part of one in any chunk has its outer edge there too.
	 */
	private static boolean framed(ChunkSnapshot chunk, Waypoint.Kind kind, int x, int y, int z) {
		return switch (kind) {
			case NETHER_PORTAL -> {
				int top = y;
				while (block(chunk, x, top + 1, z) == Blocks.NETHER_PORTAL) {
					top++;
				}
				int bottom = y;
				while (block(chunk, x, bottom - 1, z) == Blocks.NETHER_PORTAL) {
					bottom--;
				}
				yield block(chunk, x, top + 1, z) == Blocks.OBSIDIAN || block(chunk, x, bottom - 1, z) == Blocks.OBSIDIAN;
			}
			case END_PORTAL -> endFrame(block(chunk, x - 1, y, z)) || endFrame(block(chunk, x + 1, y, z))
				|| endFrame(block(chunk, x, y, z - 1)) || endFrame(block(chunk, x, y, z + 1));
			// The game's gateways, around the End's main island and out on the far ones, are capped with bedrock.
			default -> block(chunk, x, y + 1, z) == Blocks.BEDROCK || block(chunk, x, y - 1, z) == Blocks.BEDROCK;
		};
	}

	/** A stronghold's frame, or the bedrock around and inside the End's fountain. */
	private static boolean endFrame(@Nullable Block block) {
		return block == Blocks.END_PORTAL_FRAME || block == Blocks.BEDROCK;
	}

	/** The block at a place in the chunk, {@code x} and {@code z} within it; null outside it. */
	private static @Nullable Block block(ChunkSnapshot chunk, int x, int y, int z) {
		BlockState state = state(chunk, x, y, z);
		return state == null ? null : state.getBlock();
	}

	private static @Nullable BlockState state(ChunkSnapshot chunk, int x, int y, int z) {
		int index = (y >> 4) - chunk.minSectionY;
		if (x < 0 || x > 15 || z < 0 || z > 15 || index < 0 || index >= chunk.sectionCount()) {
			return null;
		}
		PalettedContainer<BlockState> section = chunk.section(index);
		return section != null ? section.get(x, y & 15, z) : chunk.uniformSection(index);
	}

	/**
	 * The unlit End portals whose centre is in a chunk, as spots at their centres: a centre is in one
	 * chunk only, so a ring over a chunk border is still one spot. Any thread.
	 */
	static List<Spot> unlit(ChunkSnapshot chunk) {
		List<Spot> spots = new ArrayList<>();
		for (int i = 0; i < chunk.sectionCount(); i++) {
			PalettedContainer<BlockState> section = chunk.section(i);
			if (section == null || !section.maybeHas(END_FRAME)) {
				continue;
			}
			int baseY = (chunk.minSectionY + i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.get(x, y, z);
						if (state.getBlock() != Blocks.END_PORTAL_FRAME) {
							continue;
						}
						// A frame faces the ring's centre, two blocks on and up to one to either side.
						Direction in = state.getValue(EndPortalFrameBlock.FACING);
						for (int side = -1; side <= 1; side++) {
							int cx = x + 2 * in.getStepX() + side * in.getStepZ();
							int cz = z + 2 * in.getStepZ() + side * in.getStepX();
							Spot spot = new Spot(Waypoint.Kind.END_PORTAL, (chunk.chunkX << 4) + cx, baseY + y, (chunk.chunkZ << 4) + cz, true);
							if (!spots.contains(spot) && unlitAround(chunk, cx, baseY + y, cz)) {
								spots.add(spot);
							}
						}
					}
				}
			}
		}
		return spots;
	}

	/**
	 * Whether a place in the chunk is the centre of an End portal waiting for its eyes: the twelve
	 * frames the game lights a portal in, three to a side, each facing the centre, as a stronghold's
	 * stand and as a player has to place them. One frame, or a row of them in somebody's build, isn't
	 * that. It looks no further than the chunk: of a ring over a chunk border, the frames in the
	 * centre's chunk are checked, which are four at least.
	 */
	private static boolean unlitAround(ChunkSnapshot chunk, int x, int y, int z) {
		if (x < 0 || x > 15 || z < 0 || z > 15 || block(chunk, x, y, z) == Blocks.END_PORTAL) {
			return false;
		}
		for (Direction out : Direction.Plane.HORIZONTAL) {
			for (int side = -1; side <= 1; side++) {
				int fx = x + 2 * out.getStepX() + side * out.getStepZ();
				int fz = z + 2 * out.getStepZ() + side * out.getStepX();
				if (fx < 0 || fx > 15 || fz < 0 || fz > 15) {
					continue;
				}
				BlockState frame = state(chunk, fx, y, fz);
				if (frame == null || frame.getBlock() != Blocks.END_PORTAL_FRAME || frame.getValue(EndPortalFrameBlock.FACING) != out.getOpposite()) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Pins the unlit End portals the player has come up to, from the chunks the map has. Client
	 * thread, now and then: walking into a portal room is no news from its chunk.
	 */
	public static void playerAt(int x, int y, int z, String dimension, WaypointStore store, ChunkSource around) {
		List<Spot> found = new ArrayList<>();
		for (int chunkX = (x - FOUND) >> 4; chunkX <= (x + FOUND) >> 4; chunkX++) {
			for (int chunkZ = (z - FOUND) >> 4; chunkZ <= (z + FOUND) >> 4; chunkZ++) {
				ChunkSnapshot chunk = around.chunk(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (Spot spot : unlit(chunk)) {
					long dx = spot.x() - x;
					long dy = spot.y() - y;
					long dz = spot.z() - z;
					if (dx * dx + dy * dy + dz * dz <= FOUND * FOUND) {
						found.add(spot);
					}
				}
			}
		}
		add(found, dimension, store, around.roof());
	}

	/**
	 * A chunk of the live world came in: pins any new portal, and takes pins off portals that are
	 * gone. A pin belongs to the portal under it. The others it stood for get pins of their own when
	 * it goes, as new portals, from {@code around}, the chunks the map has. One the player renamed or
	 * moved is theirs, and stays.
	 */
	public static void chunkSeen(ChunkSnapshot chunk, String dimension, WaypointStore store, ChunkSource around) {
		int roof = around.roof();
		List<Spot> spots = find(chunk, roof);
		add(spots, dimension, store, roof);
		boolean renamed = false;
		for (Waypoint pin : store.withHidden(dimension)) {
			if (!pin.kind.isPortal() || pin.edited || pin.x >> 4 != chunk.chunkX || pin.z >> 4 != chunk.chunkZ) {
				continue;
			}
			if (onPortal(pin, chunk)) {
				// Its name follows the portal: one pinned before frames counted, or whose frame changed.
				for (Spot spot : spots) {
					boolean sameSide = roof == ChunkSource.NO_ROOF || (pin.y >= roof) == (spot.y() >= roof);
					if (spot.kind() == pin.kind && sameSide && !pin.name.equals(savedName(spot)) && automatic(pin)) {
						pin.name = savedName(spot);
						renamed = true;
					}
				}
			} else {
				store.forget(pin);
				int reach = (SAME_PORTAL + 15) >> 4;
				for (int dx = -reach; dx <= reach; dx++) {
					for (int dz = -reach; dz <= reach; dz++) {
						ChunkSnapshot near = around.chunk(chunk.chunkX + dx, chunk.chunkZ + dz);
						if (near != null) {
							add(find(near, roof), dimension, store, roof);
						}
					}
				}
			}
		}
		if (renamed) {
			store.changed();
		}
	}

	/**
	 * Whether the block under a pin in this chunk is still its kind of portal, or the pin stands in
	 * the middle of an End portal not lit yet.
	 */
	private static boolean onPortal(Waypoint pin, ChunkSnapshot chunk) {
		int index = (pin.y >> 4) - chunk.minSectionY;
		PalettedContainer<BlockState> section = index < 0 || index >= chunk.sectionCount() ? null : chunk.section(index);
		if (section != null && kind(section.get(pin.x & 15, pin.y & 15, pin.z & 15).getBlock()) == pin.kind) {
			return true;
		}
		return pin.kind == Waypoint.Kind.END_PORTAL && unlitAround(chunk, pin.x & 15, pin.y, pin.z & 15);
	}

	/**
	 * Pins the spots no pin covers yet, with one save. A waypoint the player gave the portal icon
	 * counts. In a dimension with a {@code roof} ({@link ChunkSource#roof}), a pin only covers its own
	 * side of it: a portal on the Nether roof is often built a few blocks from one under it, and no
	 * portal reaches through the bedrock. Client thread.
	 */
	public static void add(List<Spot> spots, String dimension, WaypointStore store, int roof) {
		if (spots.isEmpty()) {
			return;
		}
		List<Waypoint> known = new ArrayList<>(store.withHidden(dimension));
		List<Waypoint> pins = new ArrayList<>();
		for (Spot spot : spots) {
			boolean pinned = false;
			for (Waypoint waypoint : known) {
				boolean marksPortals = waypoint.kind == spot.kind() || !waypoint.kind.isPortal() && waypoint.icon.equals(WaypointIcons.PORTAL);
				boolean sameSide = roof == ChunkSource.NO_ROOF || (waypoint.y >= roof) == (spot.y() >= roof);
				pinned |= marksPortals && sameSide && Math.abs(waypoint.x - spot.x()) <= SAME_PORTAL && Math.abs(waypoint.z - spot.z()) <= SAME_PORTAL;
			}
			if (!pinned) {
				int color = spot.kind() == Waypoint.Kind.NETHER_PORTAL ? NETHER_COLOR : END_COLOR;
				Waypoint pin = new Waypoint(savedName(spot), spot.x(), spot.y(), spot.z(), dimension, color);
				pin.kind = spot.kind();
				pin.icon = WaypointIcons.PORTAL;
				known.add(pin);
				pins.add(pin);
			}
		}
		store.addAll(pins);
	}

	/** The block a portal pin marks; {@link Waypoint#shownName} shows its name in the player's language. */
	static Block block(Waypoint.Kind kind) {
		return kind == Waypoint.Kind.NETHER_PORTAL ? Blocks.NETHER_PORTAL : kind == Waypoint.Kind.END_PORTAL ? Blocks.END_PORTAL : Blocks.END_GATEWAY;
	}

	/** The block's English name, which a new pin is saved under (see {@link Waypoint#BED_NAME}). */
	static String savedName(Waypoint.Kind kind) {
		return kind == Waypoint.Kind.NETHER_PORTAL ? "Nether Portal" : kind == Waypoint.Kind.END_PORTAL ? "End Portal" : "End Gateway";
	}

	private static String savedName(Spot spot) {
		return spot.framed() ? savedName(spot.kind()) : PLAIN_NAME;
	}

	/** Whether the pin still has a name it was given here, which {@link Waypoint#shownName} translates. */
	private static boolean automatic(Waypoint pin) {
		return pin.name.equals(PLAIN_NAME) || pin.name.equals(savedName(pin.kind));
	}
}
