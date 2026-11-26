package dev.diorama.render;

import dev.diorama.bake.TileKey;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The tiles a fog mask build reads, each looked up in the map once a build. The mask is read a row
 * of texels at a time, and a tile lies under dozens of rows, so the build used to look each one up
 * again on every row it lay on, and for one not in memory every overview level above it as well,
 * with a new key each time: 200 KB to 2 MB a build, a build or more a second while the player
 * moves. Here the key for a tile is made the first time a build reads it and kept for the builds
 * after, and what the map has there is looked up once a build. Render thread.
 */
final class MaskTiles {
	/** Slots to start with, a power of two: a minimap's build reads up to about a hundred tiles. */
	private static final int FIRST_SLOTS = 512;
	/**
	 * The most slots, a power of two. The full map zoomed far out reads about 5,500 tiles a build
	 * (overviews, the levels above them, and the finer ones under them).
	 */
	private static final int MOST_SLOTS = 16384;

	private int[] layers = new int[FIRST_SLOTS];
	private int[] us = new int[FIRST_SLOTS];
	private int[] vs = new int[FIRST_SLOTS];
	private int[] lods = new int[FIRST_SLOTS];
	private @Nullable TileKey[] keys = new TileKey[FIRST_SLOTS];
	/** What the map had for each key in build {@link #readIn}; cleared at a build's end, so no tile is held after. */
	private @Nullable MapTile[] found = new MapTile[FIRST_SLOTS];
	private int[] readIn = new int[FIRST_SLOTS];
	private int count;
	private int build;
	private @Nullable MapLevel level;

	/** A build of the mask from {@code level} starts: every tile is looked up afresh. */
	void start(MapLevel level) {
		this.level = level;
		this.build++;
		if (this.build == 0) {
			// After four billion builds: no slot may look read in this one.
			Arrays.fill(this.readIn, 0);
			this.build = 1;
		}
	}

	/** The build is done: the tiles it found are let go. */
	void end() {
		this.level = null;
		Arrays.fill(this.found, null);
	}

	/** What {@link MapLevel#peek} gives for this tile, looked up once this build. */
	@Nullable MapTile get(int layer, int tileU, int tileV, int lod) {
		int mask = this.keys.length - 1;
		int slot = hash(layer, tileU, tileV, lod) & mask;
		while (this.keys[slot] != null) {
			if (this.us[slot] == tileU && this.vs[slot] == tileV && this.lods[slot] == lod && this.layers[slot] == layer) {
				if (this.readIn[slot] != this.build) {
					this.found[slot] = this.level.peek(this.keys[slot]);
					this.readIn[slot] = this.build;
				}
				return this.found[slot];
			}
			slot = slot + 1 & mask;
		}
		// Kept under three quarters full, so a slot is always found within a few steps.
		if (this.count >= this.keys.length * 3 / 4) {
			this.grow();
			slot = this.free(hash(layer, tileU, tileV, lod));
		}
		TileKey key = new TileKey(layer, tileU, tileV, lod);
		MapTile tile = this.level.peek(key);
		this.put(slot, key, tile, this.build);
		this.count++;
		return tile;
	}

	/** Twice the slots, up to {@link #MOST_SLOTS}; past that, every key is dropped and made again when read. */
	private void grow() {
		@Nullable TileKey[] oldKeys = this.keys;
		@Nullable MapTile[] oldFound = this.found;
		int[] oldReadIn = this.readIn;
		int slots = Math.min(MOST_SLOTS, oldKeys.length * 2);
		if (slots == oldKeys.length) {
			// Only what this build found goes with the keys: looked up again, it's the same.
			Arrays.fill(this.keys, null);
			Arrays.fill(this.found, null);
			this.count = 0;
			return;
		}
		this.layers = new int[slots];
		this.us = new int[slots];
		this.vs = new int[slots];
		this.lods = new int[slots];
		this.keys = new TileKey[slots];
		this.found = new MapTile[slots];
		this.readIn = new int[slots];
		for (int i = 0; i < oldKeys.length; i++) {
			TileKey key = oldKeys[i];
			if (key != null) {
				this.put(this.free(hash(key.layer(), key.tileU(), key.tileV(), key.lod())), key, oldFound[i], oldReadIn[i]);
			}
		}
	}

	/** The first empty slot from {@code hash} on. */
	private int free(int hash) {
		int mask = this.keys.length - 1;
		int slot = hash & mask;
		while (this.keys[slot] != null) {
			slot = slot + 1 & mask;
		}
		return slot;
	}

	private void put(int slot, TileKey key, @Nullable MapTile tile, int readIn) {
		this.keys[slot] = key;
		this.layers[slot] = key.layer();
		this.us[slot] = key.tileU();
		this.vs[slot] = key.tileV();
		this.lods[slot] = key.lod();
		this.found[slot] = tile;
		this.readIn[slot] = readIn;
	}

	private static int hash(int layer, int tileU, int tileV, int lod) {
		int h = tileU * 0x9E3779B1 + tileV * 0x85EBCA77 + lod * 0xC2B2AE3D + layer * 0x27D4EB2F;
		return h ^ h >>> 15;
	}
}
