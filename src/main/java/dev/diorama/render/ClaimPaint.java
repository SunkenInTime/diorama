package dev.diorama.render;

import dev.diorama.Claims;
import dev.diorama.Settings;
import dev.diorama.Projection;
import dev.diorama.bake.TileChunks;
import dev.diorama.bake.TileKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Paints land claims over a tile, on the ground they cover: every pixel showing a claimed chunk is
 * tinted its owner's colour, more strongly along the edges where the owner changes, as Xaero's
 * maps do. A force-loaded chunk is a claim of its own kind, tinted a little more, so it gets an
 * edge where it meets the owner's other chunks.
 */
final class ClaimPaint {
	// Light enough that the ground still reads through a claim (Dara's pick, 2026-10-26): 20%, 32%
	// force loaded, 80% edges.
	static final int FILL_ALPHA = 0x33;
	static final int FORCE_LOADED_ALPHA = 0x52;
	static final int EDGE_ALPHA = 0xCC;

	private ClaimPaint() {
	}

	/**
	 * ABGR pixels for the tile's overlay, or null when it shows no claimed ground. {@code north}
	 * and {@code south} are the chunks of the tiles above and below, where saved, so an edge
	 * running along the tile's top or bottom shows.
	 */
	static int @Nullable [] paint(TileKey key, byte[] chunks, byte @Nullable [] north, byte @Nullable [] south, Claims.Snapshot claims, Settings.ClaimsStyle style) {
		if (style != Settings.ClaimsStyle.SURFACE) {
			return paintFlat(key, claims, style);
		}
		int size = Projection.TILE_PIXELS;
		dev.diorama.Facing facing = Projection.facing();
		int[] chunkX = new int[size];
		for (int px = 0; px < size; px++) {
			chunkX[px] = TileChunks.chunkX(key, px);
		}
		// Each pixel's claim as an index into owners, 0 for none.
		int[] ids = new int[size * size];
		List<Claims.Claim> owners = new ArrayList<>();
		owners.add(null);
		Map<Claims.Claim, Integer> idOf = new HashMap<>();
		boolean any = false;
		for (int py = 0; py < size; py++) {
			int base = TileChunks.base(key, py);
			int lastX = Integer.MIN_VALUE;
			int lastZ = Integer.MIN_VALUE;
			int lastId = 0;
			for (int px = 0; px < size; px++) {
				int i = py * size + px;
				byte value = chunks[i];
				if (value == TileChunks.NONE) {
					continue;
				}
				int cx = chunkX[px];
				int cz = base + value;
				if (cx != lastX || cz != lastZ) {
					lastX = cx;
					lastZ = cz;
					Claims.Claim claim = claims.at(facing.worldBlockX(cx, cz), facing.worldBlockZ(cx, cz));
					if (claim == null) {
						lastId = 0;
					} else {
						Integer id = idOf.get(claim);
						if (id == null) {
							id = owners.size();
							owners.add(claim);
							idOf.put(claim, id);
						}
						lastId = id;
					}
				}
				ids[i] = lastId;
				any |= lastId != 0;
			}
		}
		if (!any) {
			return null;
		}
		// Past the tile's east and west sides, which are chunk borders: the next chunk over, at the
		// row's ground. Its z can differ on a slope, but a claim's edge along a side still shows.
		int[] west = new int[size];
		int[] east = new int[size];
		int westX = TileChunks.chunkX(key, -1);
		int eastX = TileChunks.chunkX(key, size);
		for (int py = 0; py < size; py++) {
			int base = TileChunks.base(key, py);
			west[py] = side(chunks[py * size], ids[py * size], westX, base, claims, owners, idOf);
			east[py] = side(chunks[py * size + size - 1], ids[py * size + size - 1], eastX, base, claims, owners, idOf);
		}
		// Edges about half a block wide on detailed tiles, a pixel on overview tiles.
		int edge = key.lod() == 0 ? 2 : 1;
		// The rows past the top and bottom, nearest first; -1 where nothing is known there.
		int[][] above = new int[edge][];
		int[][] below = new int[edge][];
		TileKey northKey = new TileKey(key.layer(), key.tileU(), key.tileV() - 1, key.lod());
		TileKey southKey = new TileKey(key.layer(), key.tileU(), key.tileV() + 1, key.lod());
		for (int k = 0; k < edge; k++) {
			above[k] = row(north, northKey, size - 1 - k, chunkX, claims, owners, idOf);
			below[k] = row(south, southKey, k, chunkX, claims, owners, idOf);
		}
		int[] fill = new int[owners.size()];
		int[] forceFill = new int[owners.size()];
		int[] line = new int[owners.size()];
		boolean[] isForce = new boolean[owners.size()];
		for (int id = 1; id < owners.size(); id++) {
			Claims.Claim claim = owners.get(id);
			isForce[id] = claim.forceLoaded();
			fill[id] = abgr(FILL_ALPHA, claim.rgb());
			forceFill[id] = abgr(0x78, darken15Percent(claim.rgb()));
			line[id] = abgr(EDGE_ALPHA, claim.rgb());
		}
		int[] pixels = new int[size * size];
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				int i = py * size + px;
				int id = ids[i];
				if (id == 0) {
					continue;
				}
				if (onEdge(ids, west, east, above, below, size, px, py, id, edge)) {
					pixels[i] = line[id];
				} else if (isForce[id] && isCheckerCell(px, py)) {
					pixels[i] = forceFill[id];
				} else {
					pixels[i] = fill[id];
				}
			}
		}
		return pixels;
	}

	/**
	 * The claim beside a side pixel, in the chunk column {@code chunkX} past the side; the pixel's
	 * own where it shows nothing known.
	 */
	private static int side(byte value, int own, int chunkX, int base, Claims.Snapshot claims, List<Claims.Claim> owners, Map<Claims.Claim, Integer> idOf) {
		if (value == TileChunks.NONE) {
			return own;
		}
		// Turned space, as the tile's chunks are: claims are the world's.
		dev.diorama.Facing facing = Projection.facing();
		Claims.Claim claim = claims.at(facing.worldBlockX(chunkX, base + value), facing.worldBlockZ(chunkX, base + value));
		if (claim == null) {
			return 0;
		}
		Integer id = idOf.get(claim);
		if (id == null) {
			id = owners.size();
			owners.add(claim);
			idOf.put(claim, id);
		}
		return id;
	}

	/** The claims of one row of a neighbouring tile, as ids; -1 where it shows nothing known or wasn't saved. */
	private static int[] row(byte @Nullable [] chunks, TileKey key, int py, int[] chunkX, Claims.Snapshot claims, List<Claims.Claim> owners, Map<Claims.Claim, Integer> idOf) {
		int size = Projection.TILE_PIXELS;
		int[] row = new int[size];
		if (chunks == null) {
			Arrays.fill(row, -1);
			return row;
		}
		int base = TileChunks.base(key, py);
		for (int px = 0; px < size; px++) {
			byte value = chunks[py * size + px];
			row[px] = value == TileChunks.NONE ? -1 : side(value, -1, chunkX[px], base, claims, owners, idOf);
		}
		return row;
	}

	/**
	 * Whether a pixel of claim {@code id} is within {@code edge} pixels of another claim's, or of
	 * unclaimed ground: inside the tile, or past any of its sides. Past the top and bottom, ground
	 * nothing is known about counts as the same claim.
	 */
	private static boolean onEdge(int[] ids, int[] west, int[] east, int[][] above, int[][] below, int size, int px, int py, int id, int edge) {
		for (int d = 1; d <= edge; d++) {
			if ((px - d >= 0 ? ids[py * size + px - d] : west[py]) != id
				|| (px + d < size ? ids[py * size + px + d] : east[py]) != id
				|| differs(py - d >= 0 ? ids[(py - d) * size + px] : above[d - py - 1][px], id)
				|| differs(py + d < size ? ids[(py + d) * size + px] : below[py + d - size][px], id)) {
				return true;
			}
		}
		return false;
	}

	private static boolean differs(int other, int id) {
		return other != -1 && other != id;
	}

	
	/**
	 * Chunk-aligned claim overlay: each pixel maps to a world chunk at a fixed height, so borders
	 * stay square on the tilted map instead of following building faces.
	 */
	static int @Nullable [] paintFlat(TileKey key, Claims.Snapshot claims, Settings.ClaimsStyle style) {
		final int fillAlpha = style == Settings.ClaimsStyle.OUTLINE ? 0 : (style == Settings.ClaimsStyle.WALLS ? 0x28 : FILL_ALPHA);
		final int forceAlpha = style == Settings.ClaimsStyle.OUTLINE ? 0 : (style == Settings.ClaimsStyle.WALLS ? 0x44 : FORCE_LOADED_ALPHA);
		final int edgeAlpha = style == Settings.ClaimsStyle.WALLS ? 0xEE : EDGE_ALPHA;
		final int edge = style == Settings.ClaimsStyle.WALLS ? 3 : 2;
		int size = Projection.TILE_PIXELS;
		dev.diorama.Facing facing = Projection.facing();
		int y = key.isCave() ? key.layer() : 64;
		int[] ids = new int[size * size];
		java.util.List<Claims.Claim> owners = new java.util.ArrayList<>();
		owners.add(null);
		java.util.Map<Claims.Claim, Integer> idOf = new java.util.HashMap<>();
		boolean any = false;
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				double u = key.minU() + (px + 0.5) * key.units() / size;
				double v = key.minV() + (py + 0.5) * key.units() / size;
				double wz = Projection.zAt(v, y);
				double wx = facing.worldX(u, wz);
				double worldZ = facing.worldZ(u, wz);
				int cx = (int) Math.floor(wx) >> 4;
				int cz = (int) Math.floor(worldZ) >> 4;
				Claims.Claim claim = claims.at(cx, cz);
				int id = 0;
				if (claim != null) {
					any = true;
					Integer existing = idOf.get(claim);
					if (existing == null) {
						id = owners.size();
						owners.add(claim);
						idOf.put(claim, id);
					} else {
						id = existing;
					}
				}
				ids[py * size + px] = id;
			}
		}
		if (!any) {
			return null;
		}
		int[] fill = new int[owners.size()];
		int[] forceFill = new int[owners.size()];
		int[] line = new int[owners.size()];
		boolean[] isForce = new boolean[owners.size()];
		// An outline is only the border: force-loaded ground gets no checker inside it.
		final int checkerAlpha = style == Settings.ClaimsStyle.OUTLINE ? 0 : 0x78;
		for (int id = 1; id < owners.size(); id++) {
			Claims.Claim claim = owners.get(id);
			isForce[id] = claim.forceLoaded();
			fill[id] = abgr(fillAlpha, claim.rgb());
			forceFill[id] = abgr(checkerAlpha, darken15Percent(claim.rgb()));
			line[id] = abgr(edgeAlpha, claim.rgb());
		}
		int[] pixels = new int[size * size];
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				int i = py * size + px;
				int id = ids[i];
				if (id == 0) {
					continue;
				}
				boolean border = false;
				for (int d = 1; d <= edge && !border; d++) {
					if (px - d < 0 || ids[py * size + px - d] != id
						|| px + d >= size || ids[py * size + px + d] != id
						|| py - d < 0 || ids[(py - d) * size + px] != id
						|| py + d >= size || ids[(py + d) * size + px] != id) {
						border = true;
					}
				}
				if (border) {
					pixels[i] = line[id];
				} else if (isForce[id] && isCheckerCell(px, py)) {
					pixels[i] = forceFill[id];
				} else {
					pixels[i] = fill[id];
				}
			}
		}
		return pixels;
	}

	private static int darken15Percent(int rgb) {
		int r = Math.max(0, (int) Math.round(((rgb >> 16) & 0xFF) * 0.85));
		int g = Math.max(0, (int) Math.round(((rgb >> 8) & 0xFF) * 0.85));
		int b = Math.max(0, (int) Math.round((rgb & 0xFF) * 0.85));
		return (r << 16) | (g << 8) | b;
	}

	private static boolean isCheckerCell(int px, int py) {
		return (((px >> 1) + (py >> 1)) & 1) == 0;
	}

	private static int abgr(int alpha, int rgb) {
		return alpha << 24 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
	}
}
