package dev.diorama.render;

import dev.diorama.bake.TileKey;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The tiles a fog mask build reads, each looked up in the map once a build. The mask is read a row
 * of texels at a time, and a tile lies under dozens of rows, so the build used to look each one up
 * again on every row it lay on, and for one not in memory every overview level above it as well,
 * with a new key each time: 200 KB to 2 MB a build, a build or more a second while the player
 * moves. Here the key for a tile is made the first time a build reads it and kept while builds go on
 * reading it, and what the map has there is looked up once a build. Render thread.
 *
 * <p>The table grows to hold every key a build reads, so a view that reads thousands of tiles, the
 * full map on a big screen or a cave slice's export preview zoomed out, keeps them all from one
 * build to the next. Once it's five eighths full after a build, keys neither that build nor the one
 * before read are dropped, which keeps a minimap flying over new land to the tiles around it.
 *
 * <p>Memory: at most {@link #MOST_SLOTS} slots, 28 bytes each (7 MiB), and three quarters as many
 * kept keys, 32 bytes each (6 MiB): 13 MiB in all. A view only takes what its builds read, and the
 * table goes back to {@link #FIRST_SLOTS} slots (14 KB) when a map view closes ({@link #shrink}).
 * A build that alone reads more tiles than that holds has its keys made again as it reads, as
 * before this table: only a cave slice's export preview zoomed all the way out, at GUI scale 1 on a
 * window wider than 4K, reads that many.
 */
final class MaskTiles {
	/** Slots to start with, a power of two: a minimap's build reads up to about a hundred tiles. */
	private static final int FIRST_SLOTS = 512;
	/**
	 * The most slots, a power of two, three quarters of which hold keys: 196,608. Over unmapped land,
	 * the full map in a 5120 by 1440 window at GUI scale 1 reads up to about 22,000 tiles a build
	 * (its overview level, the levels above and the two finer ones under it); a cave slice's export
	 * preview zoomed all the way out reads 22,000 in a 1080p window at GUI scale 4, 87,000 in a
	 * 1600 by 900 one at scale 2, and 153,000 at scale 1.
	 */
	private static final int MOST_SLOTS = 1 << 18;
	/** How full, in eighths, the table may be after a build before the keys no recent build read are dropped. */
	private static final int SWEEP_AT = 5;

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
			// After four billion builds: no slot may look read in this one or the one before.
			Arrays.fill(this.readIn, 0);
			this.build = 2;
		}
	}

	/** The build is done, or stopped partway: the tiles it found are let go, and keys no recent build read are dropped if the table is filling. */
	void end() {
		this.level = null;
		Arrays.fill(this.found, null);
		if (this.count >= this.keys.length / 8 * SWEEP_AT) {
			this.drop(1);
			if (this.count >= this.keys.length / 2 && this.keys.length < MOST_SLOTS) {
				// The last two builds' keys alone nearly fill it: room for as many again, so the next
				// builds' new tiles don't call for this every build.
				this.resize(this.keys.length * 2, true);
			}
		}
	}

	/** Back to the table's first size, dropping every key: a view that read thousands of tiles has closed. */
	void shrink() {
		if (this.keys.length > FIRST_SLOTS) {
			this.resize(FIRST_SLOTS, false);
		}
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
		if (this.count >= this.keys.length / 4 * 3) {
			if (this.keys.length < MOST_SLOTS) {
				// Every key stays: the rest of this build, and the next, read most of them again.
				this.resize(this.keys.length * 2, true);
			} else {
				this.drop(0);
				if (this.count >= this.keys.length / 4 * 3) {
					// This build alone reads more than fit: start again from empty, in the same arrays.
					Arrays.fill(this.keys, null);
					Arrays.fill(this.found, null);
					this.count = 0;
				}
			}
			slot = this.free(hash(layer, tileU, tileV, lod));
		}
		TileKey key = new TileKey(layer, tileU, tileV, lod);
		MapTile tile = this.level.peek(key);
		this.put(slot, key, tile, this.build);
		this.count++;
		return tile;
	}

	/**
	 * Drops every key the last {@code builds} builds before this one and this one didn't read, in
	 * place: each key after a dropped one that would no longer be found from its hash moves back
	 * into the gap, as a linear-probing table's removal goes.
	 */
	private void drop(int builds) {
		int i = 0;
		while (i < this.keys.length) {
			if (this.keys[i] != null && this.build - this.readIn[i] > builds) {
				this.remove(i);
				this.count--;
				// Slot i may hold a key moved back from further on: look at it again.
			} else {
				i++;
			}
		}
	}

	private void remove(int gap) {
		int mask = this.keys.length - 1;
		int at = gap;
		while (true) {
			at = at + 1 & mask;
			if (this.keys[at] == null) {
				break;
			}
			int home = hash(this.layers[at], this.us[at], this.vs[at], this.lods[at]) & mask;
			// Found from its hash only past the gap if its home lies after the gap, up to where it is.
			boolean staysPut = gap <= at ? gap < home && home <= at : gap < home || home <= at;
			if (!staysPut) {
				this.put(gap, this.keys[at], this.found[at], this.readIn[at]);
				gap = at;
			}
		}
		this.keys[gap] = null;
		this.found[gap] = null;
	}

	/** The table at {@code slots} slots, with every key it has if {@code keep}, else none. */
	private void resize(int slots, boolean keep) {
		int[] oldLayers = this.layers;
		int[] oldUs = this.us;
		int[] oldVs = this.vs;
		int[] oldLods = this.lods;
		@Nullable TileKey[] oldKeys = this.keys;
		@Nullable MapTile[] oldFound = this.found;
		int[] oldReadIn = this.readIn;
		this.layers = new int[slots];
		this.us = new int[slots];
		this.vs = new int[slots];
		this.lods = new int[slots];
		this.keys = new TileKey[slots];
		this.found = new MapTile[slots];
		this.readIn = new int[slots];
		this.count = 0;
		if (!keep) {
			return;
		}
		for (int i = 0; i < oldKeys.length; i++) {
			TileKey key = oldKeys[i];
			if (key != null) {
				this.put(this.free(hash(oldLayers[i], oldUs[i], oldVs[i], oldLods[i])), key, oldFound[i], oldReadIn[i]);
				this.count++;
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
