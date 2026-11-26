package dev.diorama.render;

import dev.diorama.bake.TileKey;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The tiles a fog mask build reads, each looked up in the map once a build. The mask is read a row
 * of texels at a time, and a tile lies under dozens of rows, so the build used to look each one up
 * again on every row it lay on, and for one not in memory every overview level above it as well,
 * with a new key each time: about 280 KB a build, several builds a second while the player moves.
 * Here the key for a tile is made the first time a build reads it and kept for the builds after,
 * and what the map has there is looked up once a build. Render thread.
 */
final class MaskTiles {
	/** A power of two. A minimap's build reads a few dozen tiles; the full map's, up to a few hundred. */
	private static final int SLOTS = 4096;
	/** Past this many keys they're all dropped, so a slot is always found within a few steps. */
	private static final int MOST = SLOTS * 3 / 4;

	private final int[] layers = new int[SLOTS];
	private final int[] us = new int[SLOTS];
	private final int[] vs = new int[SLOTS];
	private final int[] lods = new int[SLOTS];
	private final @Nullable TileKey[] keys = new TileKey[SLOTS];
	/** What the map had for each key in build {@link #readIn}; cleared at a build's end, so no tile is held after. */
	private final @Nullable MapTile[] found = new MapTile[SLOTS];
	private final int[] readIn = new int[SLOTS];
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
		int mask = SLOTS - 1;
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
		if (this.count >= MOST) {
			// Only what this build found is dropped with the keys: looked up again, it's the same.
			Arrays.fill(this.keys, null);
			Arrays.fill(this.found, null);
			this.count = 0;
			slot = hash(layer, tileU, tileV, lod) & mask;
		}
		TileKey key = new TileKey(layer, tileU, tileV, lod);
		this.keys[slot] = key;
		this.layers[slot] = layer;
		this.us[slot] = tileU;
		this.vs[slot] = tileV;
		this.lods[slot] = lod;
		this.count++;
		MapTile tile = this.level.peek(key);
		this.found[slot] = tile;
		this.readIn[slot] = this.build;
		return tile;
	}

	private static int hash(int layer, int tileU, int tileV, int lod) {
		int h = tileU * 0x9E3779B1 + tileV * 0x85EBCA77 + lod * 0xC2B2AE3D + layer * 0x27D4EB2F;
		return h ^ h >>> 15;
	}
}
