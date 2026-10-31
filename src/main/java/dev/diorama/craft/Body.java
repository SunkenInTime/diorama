package dev.diorama.craft;

import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.CraftBlocks;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One moving structure the client knows about this tick, from a {@link Crafts.Source}. Client thread. */
public interface Body {
	/** The same from session to session, so a parked craft is still known when it comes back. */
	UUID id();

	/** Its blocks as they are now, or null if they can't be read. */
	@Nullable Blocks read(BlockLooks looks);

	/**
	 * Where it is this frame: writes the world position of the grid corner {@code min} (the
	 * {@link Blocks#min} of a read), then the world offsets of one step along the grid's x, y and z.
	 */
	boolean pose(int[] min, float partialTick, double[] out);

	/** Blocks read off a body, with the body's own coordinates of grid cell 0, 0, 0. */
	record Blocks(CraftBlocks blocks, int[] min) {
	}
}
