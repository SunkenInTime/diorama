package dev.diorama.bake;

import it.unimi.dsi.fastutil.ints.Int2IntMap;

/**
 * The blocks of one moving craft, copied off the client thread's world so a bake can read them
 * anywhere. A grid {@code sizeX} by {@code sizeY} by {@code sizeZ} of block state ids, x fastest
 * then z then y; 0 is air.
 */
public final class CraftBlocks {
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	final int[] states;
	/** For filled copycats, the material's state id by cell index. */
	final Int2IntMap copycats;

	public CraftBlocks(int sizeX, int sizeY, int sizeZ, int[] states, Int2IntMap copycats) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.states = states;
		this.copycats = copycats;
	}

	/** A grid with nothing in it yet. */
	public static int[] empty(int sizeX, int sizeY, int sizeZ) {
		return new int[sizeX * sizeY * sizeZ];
	}

	public static int index(int sizeX, int sizeZ, int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	public int state(int index) {
		return this.states[index];
	}

	public int copycat(int index) {
		return this.copycats.getOrDefault(index, 0);
	}

	public int volume() {
		return this.states.length;
	}

	/** The look to draw a cell with: its state, or a filled copycat's look. */
	int look(BlockLooks looks, int index) {
		int id = this.states[index];
		if (id == 0 || this.copycats.isEmpty()) {
			return id;
		}
		int material = this.copycats.getOrDefault(index, 0);
		return material == 0 ? id : looks.copycat(id, material);
	}

	/**
	 * The world box the grid fills placed by {@code pose} (see {@code Body#pose}): min x y z,
	 * then max x y z.
	 */
	public double[] box(double[] pose) {
		double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (int c = 0; c < 8; c++) {
			double x = (c & 1) * this.sizeX;
			double y = (c >> 1 & 1) * this.sizeY;
			double z = (c >> 2 & 1) * this.sizeZ;
			for (int a = 0; a < 3; a++) {
				double w = pose[a] + pose[3 + a] * x + pose[6 + a] * y + pose[9 + a] * z;
				box[a] = Math.min(box[a], w);
				box[3 + a] = Math.max(box[3 + a], w);
			}
		}
		return box;
	}

	/** Whether it would draw the same as {@code other}: states that differ only in ways that don't show are the same here. */
	public boolean looksLike(CraftBlocks other, BlockLooks looks) {
		if (this.sizeX != other.sizeX || this.sizeY != other.sizeY || this.sizeZ != other.sizeZ || !this.copycats.equals(other.copycats)) {
			return false;
		}
		for (int i = 0; i < this.states.length; i++) {
			if (this.states[i] != other.states[i] && !looks.sameLook(this.states[i], other.states[i])) {
				return false;
			}
		}
		return true;
	}
}
