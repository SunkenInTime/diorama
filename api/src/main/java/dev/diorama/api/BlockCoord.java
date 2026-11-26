package dev.diorama.api;

/**
 * A block's position in its dimension: the block whose corner nearest negative infinity is at
 * {@code x, y, z}.
 *
 * @param x east is positive
 * @param y up is positive
 * @param z south is positive
 */
public record BlockCoord(int x, int y, int z) {
	/**
	 * The block at these coordinates.
	 *
	 * @param x east is positive
	 * @param y up is positive
	 * @param z south is positive
	 * @return the block's position
	 */
	public static BlockCoord of(int x, int y, int z) {
		return new BlockCoord(x, y, z);
	}
}
