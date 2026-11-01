package dev.diorama.bake;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Ray traces one map tile through the block data.
 *
 * <p>Every texture pixel fires four rays along the camera direction. Each ray walks the blocks in
 * its column (the camera faces due north, so a ray never changes x) until it hits something solid.
 * That hit is then lit: in the surface view by the sun, with a second ray toward it for shadows;
 * in a cave slice by the game's own block and sky light. Under a roof (the Nether) it is lit from
 * above as if the rock over the first open space were lifted off, and fades into the biome's fog
 * the lower it lies. Corner ambient occlusion and partial colour from plants, glass and water come on top.
 */
public final class TileBaker {
	/** Rotated-grid sample offsets inside a pixel. */
	private static final double[][] SAMPLES = {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}};
	/** One sample in the middle of the pixel: a quarter of the work, with jaggier block edges. */
	private static final double[][] FAST_SAMPLES = {{0.5, 0.5}};
	private static volatile boolean fast;
	/** Under a roof: light filling shadows, direct light from above, and the block light glow. */
	public static final float LID_AMBIENT = 0.26F;
	public static final float LID_KEY = 0.52F;
	public static final float[] LID_KEY_TINT = {1.1F, 1.0F, 0.88F};
	public static final float[] LID_FILL_TINT = {1.0F, 0.86F, 0.84F};
	public static final float LID_GLOW = 0.7F;
	/**
	 * The Nether's fog starts this far below the roof, so high ground keeps its own colour, the
	 * way ground near you does in the game, and the same height looks the same in every view.
	 */
	public static final int HAZE_START = 30;
	/** How far below where it starts the fog has covered about two thirds of a surface. */
	public static final double HAZE_DEPTH = 55;
	public static final float HAZE_MAX = 0.72F;
	/** The fog is lit a little brighter than its colour in the game, which is what the far distance fades to. */
	public static final float HAZE_LIGHT = 1.6F;
	/** The brightest a biome's fog colour may be before {@link #HAZE_LIGHT}, in linear luminance. */
	public static final float HAZE_MAX_LUMINANCE = 0.025F;

	// The sun is in the west, slightly south, so shadows fall to the east; how high is per style.
	public static final double SUN_AZIMUTH_X = -0.878;
	public static final double SUN_AZIMUTH_Z = 0.479;
	/** Small per-sample nudges of the sun direction, which soften shadow edges. */
	public static final double[][] SUN_JITTER = {{0.03, 0.0, -0.02}, {-0.02, 0.0, 0.03}, {0.02, 0.0, 0.02}, {-0.03, 0.0, -0.03}};


	/**
	 * Directions for sky occlusion: two rings round the sky and one straight up, weighted by how
	 * much light each part of the sky sends onto flat ground.
	 */
	public static final double[][] SKY_RAYS;
	public static final double[] SKY_WEIGHTS;
	/** How far sky occlusion looks for something overhead, in blocks. */
	public static final int SKY_REACH = 12;

	static {
		int ring = 6;
		SKY_RAYS = new double[ring * 2 + 1][];
		SKY_WEIGHTS = new double[ring * 2 + 1];
		double[] elevations = {Math.toRadians(25), Math.toRadians(55)};
		for (int r = 0; r < 2; r++) {
			for (int i = 0; i < ring; i++) {
				double azimuth = (i + r * 0.5) * 2 * Math.PI / ring;
				double e = elevations[r];
				SKY_RAYS[r * ring + i] = new double[] {Math.cos(e) * Math.cos(azimuth), Math.sin(e), Math.cos(e) * Math.sin(azimuth)};
				SKY_WEIGHTS[r * ring + i] = Math.sin(e);
			}
		}
		SKY_RAYS[ring * 2] = new double[] {0, 1, 0};
		SKY_WEIGHTS[ring * 2] = 1.5;
	}
	private static final float[] AO_LEVELS = {0.6F, 0.74F, 0.87F, 1.0F};
	/** Faces that aren't up read darker, the way Minecraft shades its own block sides. */
	static final float SIDE = 0.82F;
	/** How much nearer a neighbouring pixel has to be, in blocks, before an outline is drawn. */
	private static final float OUTLINE_DEPTH = 1.5F;
	/** How far below the cut a cave slice looks. */
	public static final int CAVE_DEPTH = 48;
	private static final int SHADOW_STEPS = 96;
	/** Longest shadow, in blocks along the sun direction. Bounds how far an edit can reach. */
	public static final double SHADOW_DISTANCE = 40;
	/**
	 * How far east, north and down from a block its shadow can land, for the lowest and highest
	 * sun a style can have.
	 */
	public static final int SHADOW_REACH_X = (int) Math.ceil(SHADOW_DISTANCE * Math.cos(Math.toRadians(dev.diorama.MapStyle.MIN_SUN_ELEVATION)) * -SUN_AZIMUTH_X) + 1;
	public static final int SHADOW_REACH_Z = (int) Math.ceil(SHADOW_DISTANCE * Math.cos(Math.toRadians(dev.diorama.MapStyle.MIN_SUN_ELEVATION)) * SUN_AZIMUTH_Z) + 1;
	public static final int SHADOW_REACH_Y = (int) Math.ceil(SHADOW_DISTANCE * Math.sin(Math.toRadians(dev.diorama.MapStyle.MAX_SUN_ELEVATION))) + 1;

	static final float[] SRGB_TO_LINEAR = new float[256];
	private static final int[] LINEAR_TO_SRGB = new int[4096];

	static {
		for (int i = 0; i < 256; i++) {
			double v = i / 255.0;
			SRGB_TO_LINEAR[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
		}
		for (int i = 0; i < 4096; i++) {
			double v = i / 4095.0;
			double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
			LINEAR_TO_SRGB[i] = (int) Math.round(Math.max(0, Math.min(1, s)) * 255);
		}
	}

	private final TileKey key;
	private final BlockLooks looks;
	final VoxelReader reader;
	final boolean cave;
	/** The sky view of a dimension with a roof: it starts under the roof and skips rock. */
	final boolean roofless;
	/** Starts inside rock, which it skips, and isn't lit by the sun: caves, and under a roof. */
	final boolean lit;
	final int startY;
	final int limitY;
	/** One above the tallest block a camera ray of this tile can meet; camera rays start here. */
	final int topY;
	/** The same for shadow and sky rays, which reach further north. */
	final int rayTop;
	/** Builds above an ordinary world's top, for rays that start up there; null when no ray does. */
	private final @Nullable TallChunks tall;
	/** Cave slice that may only show blocks the player has seen. */
	final boolean fogOfWar;
	/** What to record for the overlays, or null to record nothing. */
	private final @Nullable Overlays overlays;
	private final java.util.Map<Long, Boolean> slimeChunks = new java.util.HashMap<>();
	private final ChunkSource source;
	private final MapStyle style;
	private final Lighting lighting;
	private final float[] light = new float[3];
	private final float[] unlit = new float[3];
	// The camera angle when this bake started; a change mid-bake makes the result stale anyway.
	private final double sin;
	private final double cos;
	private final double invSin;
	private final double invCos;
	// Direction toward the sun for this style.
	private final double sunX;
	private final double sunY;
	private final double sunZ;
	/**
	 * In a dimension with a roof, the sky view and cave slices alike: light falls in from above as
	 * if the rock over the first open space were lifted off, and the biome's fog thickens the lower
	 * the ground lies.
	 */
	final boolean lifted;
	/** Per column, the lowest y of the lifted rock; see {@link #lid}. */
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lidAt = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	/** The fog colour per biome cell (quart), in sRGB. */
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap fogAt = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	/** Sky visibility at block corners, 0..1, worked out once per bake. */
	private final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap skyAtCorner = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();

	// Result of the last trace, in linear light.
	private float outR;
	private float outG;
	private float outB;
	/** Camera depth of the last hit relative to {@link #depthOrigin}; larger is farther. */
	private float outDepth;
	// The block the last trace stopped on, when it was solid, for the overlays.
	private boolean hitSolid;
	private boolean hitTop;
	private int hitId;
	private int hitX;
	private int hitY;
	private int hitZ;
	/** Where across the block the ray landed, 0..1: east-west, and north-south on a top face. */
	private double hitFx;
	private double hitFz;
	/**
	 * Camera depth of this tile's top-left ray start. Depths are kept relative to it so they stay
	 * small enough for float precision anywhere in the world.
	 */
	private final double depthOrigin;

	/** Four samples per pixel even when the Quality setting asks for one: see {@link #reference}. */
	private boolean fourSamples;

	/** Finds where rays land without lighting them, for {@link #surface}. */
	private boolean surfaceOnly;
	// Where the last surface-only trace landed, NaN for nowhere, and whether on a top face.
	private float surfaceY;
	private boolean surfaceTop;
	/** What lit the last surface a cave trace landed on; see {@link TileSurface#caveLight}. */
	private int surfaceLight;

	private TileBaker(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar, @Nullable Overlays overlays) {
		this.key = key;
		this.overlays = overlays;
		this.looks = looks;
		this.reader = new VoxelReader(source, looks, true);
		this.cave = key.isCave();
		this.roofless = !this.cave && source.roof() != ChunkSource.NO_ROOF;
		this.lit = this.cave || this.roofless;
		this.source = source;
		this.fogOfWar = this.lit && fogOfWar;
		this.style = MapStyle.get();
		this.lighting = new Lighting(this.style);
		double elevation = Math.toRadians(this.style.sunElevation());
		this.sunX = Math.cos(elevation) * SUN_AZIMUTH_X;
		this.sunY = Math.sin(elevation);
		this.sunZ = Math.cos(elevation) * SUN_AZIMUTH_Z;
		this.sin = Projection.sin();
		this.cos = Projection.cos();
		this.invSin = 1 / this.sin;
		this.invCos = 1 / this.cos;
		this.topY = localTop(key, source, 0);
		this.rayTop = localTop(key, source, SKY_REACH);
		this.startY = this.cave ? Math.min(key.layer(), source.maxY()) : this.roofless ? Math.min(source.roof(), source.maxY()) : this.topY;
		this.tall = this.lit ? null : TallChunks.around(key, source, this.startY);
		double originZ = (key.minV() + this.startY * this.cos) / this.sin;
		this.depthOrigin = -(this.sin * this.startY + this.cos * originZ);
		this.limitY = this.cave ? Math.max(source.minY(), this.startY - CAVE_DEPTH) : source.minY();
		this.lifted = this.lit && source.roof() != ChunkSource.NO_ROOF;
		this.lidAt.defaultReturnValue(Integer.MIN_VALUE);
		this.fogAt.defaultReturnValue(-1);
	}

	/**
	 * The lowest y of the rock lifted off this column: the solid blocks from the cut down to the
	 * first open space that isn't a {@link VoxelReader#pocket}. Light rays treat it as open.
	 */
	int lid(int x, int z) {
		long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
		int lid = this.lidAt.get(key);
		if (lid == Integer.MIN_VALUE) {
			lid = this.reader.lid(x, z, this.startY, this.limitY);
			this.lidAt.put(key, lid);
		}
		return lid;
	}

	/** Whether a ray at this height goes on through a chunk the map has no data for; see {@link TallChunks}. */
	boolean passesMissing(int y) {
		return this.tall != null && y >= this.tall.missingTop;
	}

	float hazeR;
	float hazeG;
	float hazeB;

	/** Sets {@code haze*} to the Nether's fog colour at a point, linear, blended between biome cells so borders don't step. */
	private void hazeColor(double x, double y, double z) {
		this.fogBlend(x, y, z);
		// The soul sand valley's and basalt deltas' fogs are several times brighter than the
		// rest and wash those biomes out pale; each fog keeps its hue but no more than this light.
		float scale = HAZE_LIGHT * Math.min(1, HAZE_MAX_LUMINANCE / Math.max(1e-6F, luminance(this.hazeR, this.hazeG, this.hazeB)));
		this.hazeR *= scale;
		this.hazeG *= scale;
		this.hazeB *= scale;
	}

	/**
	 * Sets {@code haze*} to the biome fog colours around a point, blended, before {@link #hazeColor}
	 * limits their light: the GPU baker blends these from a face's corners and limits each pixel.
	 */
	void fogBlend(double x, double y, double z) {
		double qx = x / 4 - 0.5;
		double qz = z / 4 - 0.5;
		int x0 = (int) Math.floor(qx);
		int z0 = (int) Math.floor(qz);
		int qy = (int) Math.floor(y) >> 2;
		double fx = qx - x0;
		double fz = qz - z0;
		float r = 0;
		float g = 0;
		float b = 0;
		for (int i = 0; i < 4; i++) {
			int cx = x0 + (i & 1);
			int cz = z0 + (i >> 1);
			double w = ((i & 1) == 0 ? 1 - fx : fx) * ((i >> 1) == 0 ? 1 - fz : fz);
			int c = this.fogCell(cx, qy, cz);
			r += (float) (w * SRGB_TO_LINEAR[(c >> 16) & 0xFF]);
			g += (float) (w * SRGB_TO_LINEAR[(c >> 8) & 0xFF]);
			b += (float) (w * SRGB_TO_LINEAR[c & 0xFF]);
		}
		this.hazeR = r;
		this.hazeG = g;
		this.hazeB = b;
	}

	private int fogCell(int qx, int qy, int qz) {
		long key = ((long) (qx & 0x3FFFFFF) << 38) | ((long) (qz & 0x3FFFFFF) << 12) | ((qy + 2048) & 0xFFF);
		int color = this.fogAt.get(key);
		if (color < 0) {
			ChunkSnapshot snapshot = this.reader.snapshot(qx << 2, qz << 2);
			color = snapshot == null ? NETHER_WASTES_FOG : fogColor(snapshot.noiseBiome(qx, qy, qz));
			this.fogAt.put(key, color);
		}
		return color;
	}

	// The Nether biomes' fog colours in the game.
	private static final int NETHER_WASTES_FOG = 0x330808;

	private static int fogColor(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome) {
		if (biome.is(net.minecraft.world.level.biome.Biomes.CRIMSON_FOREST)) {
			return 0x330303;
		}
		if (biome.is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST)) {
			return 0x1A051A;
		}
		if (biome.is(net.minecraft.world.level.biome.Biomes.SOUL_SAND_VALLEY)) {
			return 0x1B4745;
		}
		if (biome.is(net.minecraft.world.level.biome.Biomes.BASALT_DELTAS)) {
			return 0x685F70;
		}
		return NETHER_WASTES_FOG;
	}

	/** A baker that only answers colour and occlusion questions, for {@link GpuMesher}. */
	static TileBaker forMeshing(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar) {
		return new TileBaker(key, source, looks, fogOfWar, null);
	}

	/**
	 * What each pixel of tile pixels (x0, y0) to (x0 + width, y0 + height) of a cave slice shows, for lights drawn over it: where its camera ray
	 * lands, which way the face there points, the pixel as baked with its light taken out, and
	 * what lit it. Water counts as ground at its surface. With {@code fogOfWar}, ground not seen
	 * yet has none, as the tile shows none. Any thread.
	 */
	public static TileSurface surface(TileKey key, int revision, ChunkSource source, BlockLooks looks, boolean fogOfWar, int x0, int y0, int width, int height) {
		TileBaker baker = new TileBaker(key, source, looks, fogOfWar, null);
		int count = width * height;
		TileSurface surface = new TileSurface(revision, x0, y0, width, height, new float[count], new boolean[count], new int[count], new int[count], new boolean[count]);
		java.util.Arrays.fill(surface.y(), Float.NaN);
		// Only cave slices, and not under a roof, where the light is lifted off the rock above.
		if (!baker.cave || baker.startY <= baker.limitY || baker.lifted) {
			return surface;
		}
		int[] pixels = new int[count];
		float[] linear = new float[count * 4];
		baker.bakePixels(x0, y0, width, height, pixels, null, linear);
		double unitsPerPixel = 1.0 / Projection.PIXELS_PER_UNIT;
		baker.surfaceOnly = true;
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				int i = py * width + px;
				baker.surfaceY = Float.NaN;
				baker.surfaceLight = 0;
				if (pixels[i] == 0) {
					continue;
				}
				// The middle of the pixel first; at an edge it can miss what the pixel's samples hit.
				double u = key.minU() + (x0 + px + 0.5) * unitsPerPixel;
				double v = key.minV() + (y0 + py + 0.5) * unitsPerPixel;
				boolean hit = baker.trace(u, v, 0) && !Float.isNaN(baker.surfaceY);
				for (int s = 0; s < SAMPLES.length && !hit; s++) {
					baker.surfaceY = Float.NaN;
					baker.surfaceLight = 0;
					hit = baker.trace(key.minU() + (x0 + px + SAMPLES[s][0]) * unitsPerPixel, key.minV() + (y0 + py + SAMPLES[s][1]) * unitsPerPixel, s) && !Float.isNaN(baker.surfaceY);
				}
				if (!hit) {
					continue;
				}
				surface.y()[i] = baker.surfaceY;
				surface.top()[i] = baker.surfaceTop;
				surface.light()[i] = baker.surfaceLight;
				surface.outlined()[i] = linear[i * 4 + 3] > 0;
				// A light relights what the pixel shows, water and all: the baked pixel with the
				// light that lit it taken out.
				surface.albedo()[i] = pixels[i] & 0xFF000000 | srgb(linear[i * 4], linear[i * 4 + 1], linear[i * 4 + 2]);
			}
		}
		return surface;
	}

	/** Records a surface-only trace landing on a block, lit as a traced one would be. */
	private void landed(int id, int x, int by, int bz, double fx, double hitY, double hitZ, boolean top, boolean partial) {
		this.shade(id, x, by, bz, fx, hitY, hitZ, top, partial, 0);
		this.surfaceY = (float) hitY;
		this.surfaceTop = top;
	}

	/** Records a surface-only trace landing on water at height {@code y}. */
	private void landedOnWater(int waterId, int x, int by, int bz, double y) {
		this.albedo(waterId, x, bz, true);
		this.surfaceLight = this.waterLight(x, by, bz, y);
		this.surfaceY = (float) y;
		this.surfaceTop = true;
	}

	/**
	 * What lights water block (x, by, bz), seen at height {@code y}, in a cave slice: the light
	 * above it, as {@link #mixWater} lights it; see {@link TileSurface#caveLight}.
	 */
	private int waterLight(int x, int by, int bz, double y) {
		float fade = 1 - Math.min(0.5F, (float) (this.startY - y) / 60.0F);
		return TileSurface.caveLight(this.reader.blockLight(x, by + 1, bz), this.reader.skyLight(x, by + 1, bz), 1, fade, 0) | TileSurface.WATER;
	}

	private static int srgb(float r, float g, float b) {
		return LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (r * 4095)))] << 16
			| LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (g * 4095)))] << 8
			| LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (b * 4095)))];
	}

	/**
	 * One above the tallest block any ray or shadow ray of this tile can touch. Starting rays here
	 * instead of at the tallest block in the whole dimension skips a lot of empty sky.
	 */
	static int localTop(TileKey key, ChunkSource source, int moreNorth) {
		return localTop(key, source, moreNorth, Math.min(source.maxY(), source.topY()));
	}

	/** The same, for only what stands below {@code ceiling}, which it never goes above. */
	static int localTop(TileKey key, ChunkSource source, int moreNorth, int ceiling) {
		double minU = key.minU();
		double minV = key.minV();
		int x0 = Math.floorDiv((int) Math.floor(minU) - 1, 16);
		int x1 = Math.floorDiv((int) Math.ceil(minU + Projection.TILE_UNITS) + SHADOW_REACH_X + 1, 16);
		int z0 = Math.floorDiv((int) Math.floor(Projection.zAt(minV, source.minY())) - 1 - moreNorth, 16);
		int z1 = Math.floorDiv((int) Math.ceil(Projection.zAt(minV + Projection.TILE_UNITS, ceiling)) + SHADOW_REACH_Z + 1, 16);
		int top = Integer.MIN_VALUE;
		for (int cx = x0 - 2; cx <= x1; cx++) {
			for (int cz = z0; cz <= z1 + 1; cz++) {
				top = Math.max(top, source.topBelow(cx, cz, ceiling));
			}
		}
		return top == Integer.MIN_VALUE ? ceiling : Math.min(ceiling, top);
	}

	/**
	 * Bakes the whole tile, or only the {@link TileImage#BLOCK}-pixel blocks set in {@code blocks}.
	 * {@code fogOfWar} is the cave mode when the bake was scheduled, so it matches its generation.
	 */
	/** Whether bakes take one sample per pixel instead of four. Set from the Quality setting. */
	public static boolean fast() {
		return fast;
	}

	public static void setFast(boolean value) {
		fast = value;
	}

	public static TileImage bake(TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks, boolean fogOfWar) {
		return bake(key, source, looks, blocks, fogOfWar, null);
	}

	/**
	 * Same, merging in {@code saved}, the tile as last saved to disk: wherever this bake has no
	 * data for a pixel, the saved pixel stays. That shows places whose chunks aren't loaded, and
	 * keeps a bake at the edge of loaded chunks from erasing what was saved.
	 */
	public static TileImage bake(TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks, boolean fogOfWar, int @Nullable [] saved) {
		return bake(key, source, looks, blocks, fogOfWar, saved, null);
	}

	/** Same, also recording the {@link Overlays} bits of every pixel when {@code overlays} isn't null. */
	public static TileImage bake(
		TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks, boolean fogOfWar, int @Nullable [] saved, @Nullable Overlays overlays
	) {
		TileBaker baker = new TileBaker(key, source, looks, fogOfWar, overlays);
		int size = Projection.TILE_PIXELS;
		if (blocks == null) {
			int[] pixels = new int[size * size];
			byte[] info = overlays == null ? null : new byte[size * size];
			baker.bakePixels(0, 0, size, size, pixels, info, null);
			merge(pixels, saved, 0, 0, size);
			return TileImage.full(key, Mipmaps.build(pixels, size), coverage(pixels, size, size), info);
		}
		int perRow = size / TileImage.BLOCK;
		List<TileImage.Patch> patches = new ArrayList<>();
		for (int i = blocks.nextSetBit(0); i >= 0; i = blocks.nextSetBit(i + 1)) {
			int bx = i % perRow;
			int by = i / perRow;
			int[] pixels = new int[TileImage.BLOCK * TileImage.BLOCK];
			byte[] info = overlays == null ? null : new byte[TileImage.BLOCK * TileImage.BLOCK];
			baker.bakePixels(bx * TileImage.BLOCK, by * TileImage.BLOCK, TileImage.BLOCK, TileImage.BLOCK, pixels, info, null);
			merge(pixels, saved, bx * TileImage.BLOCK, by * TileImage.BLOCK, TileImage.BLOCK);
			patches.add(new TileImage.Patch(bx, by, Mipmaps.build(pixels, TileImage.BLOCK), coverage(pixels, TileImage.BLOCK, TileImage.BLOCK), info));
		}
		if (VoxelReader.DEBUG && !baker.reader.missing.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (long m : baker.reader.missing) {
				int cx = (int) (m >> 32);
				int cz = (int) m;
				sb.append(' ').append(cx).append(',').append(cz).append(source.chunk(cx, cz) == null ? "(still missing)" : "(present now)");
			}
			dev.diorama.Diorama.LOGGER.info("Partial bake of {} ({} blocks) hit unknown chunks:{}", key, blocks.cardinality(), sb);
		}
		return TileImage.partial(key, patches);
	}

	/**
	 * The whole tile traced the way the GPU bakes it, four samples per pixel whatever the Quality
	 * setting, and not merged with anything saved: what a GPU bake of it should look like.
	 * {@code info} gets the overlay bits when {@code overlays} asks for them.
	 */
	public static int[] reference(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar, @Nullable Overlays overlays, byte @Nullable [] info) {
		TileBaker baker = new TileBaker(key, source, looks, fogOfWar, overlays);
		baker.fourSamples = true;
		int size = Projection.TILE_PIXELS;
		int[] pixels = new int[size * size];
		baker.bakePixels(0, 0, size, size, pixels, info, null);
		return pixels;
	}

	/** A tile drawn on the GPU, merged with what was saved the same way a traced bake is. */
	public static TileImage gpuImage(TileKey key, int[] pixels, int @Nullable [] saved, byte @Nullable [] info) {
		merge(pixels, saved, 0, 0, Projection.TILE_PIXELS);
		int size = Projection.TILE_PIXELS;
		return TileImage.full(key, Mipmaps.build(pixels, size), coverage(pixels, size, size), info);
	}

	/** A full tile image from level-0 pixels: mips and fog coverage. */
	public static TileImage image(TileKey key, int[] pixels) {
		int size = Projection.TILE_PIXELS;
		return TileImage.full(key, Mipmaps.build(pixels, size), coverage(pixels, size, size));
	}

	/** Keeps the saved pixel wherever the fresh one is less known; square areas only. */
	private static void merge(int[] pixels, int @Nullable [] saved, int px0, int py0, int edge) {
		if (saved == null) {
			return;
		}
		int size = Projection.TILE_PIXELS;
		for (int y = 0; y < edge; y++) {
			for (int x = 0; x < edge; x++) {
				int i = y * edge + x;
				int old = saved[(py0 + y) * size + px0 + x];
				if ((old >>> 24) > (pixels[i] >>> 24)) {
					pixels[i] = old;
				}
			}
		}
	}

	/**
	 * Traces pixels into {@code out}, and when {@code linear} isn't null, four floats per pixel
	 * into it: the pixel's colour in linear light with each sample's own cave light taken out
	 * (all but a side's darkening), then 1 where an outline darkens it.
	 */
	private void bakePixels(int px0, int py0, int width, int height, int[] out, byte @Nullable [] info, float @Nullable [] linear) {
		if (this.startY <= this.limitY) {
			return;
		}
		// Outlines compare each pixel with its neighbours, so bake a one-pixel border around the area.
		int border = this.style.outline() > 0 ? 1 : 0;
		int w = width + 2 * border;
		int h = height + 2 * border;
		int[] pixels = border == 0 ? out : new int[w * h];
		float[] depth = border == 0 ? null : new float[w * h];
		double minU = this.key.minU();
		double minV = this.key.minV();
		double unitsPerPixel = 1.0 / Projection.PIXELS_PER_UNIT;
		double[][] samples = fast && !this.fourSamples ? FAST_SAMPLES : SAMPLES;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float r = 0;
				float g = 0;
				float b = 0;
				float d = 0;
				float ur = 0;
				float ug = 0;
				float ub = 0;
				int known = 0;
				for (int s = 0; s < samples.length; s++) {
					double u = minU + (px0 + x - border + samples[s][0]) * unitsPerPixel;
					double v = minV + (py0 + y - border + samples[s][1]) * unitsPerPixel;
					this.surfaceLight = -1;
					if (this.trace(u, v, s)) {
						if (linear != null) {
							// Samples on faces lit differently are each unlit by their own light; ones
							// lit by none of it, like the cut face, are kept as they are.
							float[] lit = this.unlit;
							if (this.surfaceLight == -1) {
								java.util.Arrays.fill(lit, 1);
							} else {
								TileSurface.caveLightOf(this.surfaceLight, this.surfaceLight & 15, lit);
							}
							ur += this.outR / Math.max(1e-3F, lit[0]);
							ug += this.outG / Math.max(1e-3F, lit[1]);
							ub += this.outB / Math.max(1e-3F, lit[2]);
						}
						if (s == 0 && info != null && this.hitSolid) {
							int ix = x - border;
							int iy = y - border;
							if (ix >= 0 && iy >= 0 && ix < width && iy < height) {
								info[iy * width + ix] = (byte) this.overlayBits();
							}
						}
						r += this.outR;
						g += this.outG;
						b += this.outB;
						d += this.outDepth;
						known++;
					}
				}
				if (known > 0) {
					pixels[y * w + x] = this.abgr(r / known, g / known, b / known, known * 255 / samples.length);
					int ix = x - border;
					int iy = y - border;
					if (linear != null && ix >= 0 && iy >= 0 && ix < width && iy < height) {
						int i = (iy * width + ix) * 4;
						linear[i] = ur / known;
						linear[i + 1] = ug / known;
						linear[i + 2] = ub / known;
					}
				}
				if (depth != null) {
					depth[y * w + x] = known > 0 ? d / known : Float.NaN;
				}
			}
		}
		if (border == 0) {
			return;
		}
		float keep = 1 - this.style.outline();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = (y + 1) * w + x + 1;
				int pixel = pixels[i];
				float here = depth[i];
				if (pixel != 0 && (nearer(depth[i - 1], here) || nearer(depth[i + 1], here) || nearer(depth[i - w], here) || nearer(depth[i + w], here))) {
					pixel = darken(pixel, keep);
					if (linear != null) {
						linear[(y * width + x) * 4 + 3] = 1;
					}
				}
				out[y * width + x] = pixel;
			}
		}
	}

	/** The {@link Overlays} bits for the block the last trace stopped on. */
	private int overlayBits() {
		int bits = 0;
		// Lines one pixel wide: a pixel spans a quarter block across, and less of a top face down
		// the screen, since top faces are foreshortened.
		if ((this.hitX & 15) == 0 && this.hitFx < 1.0 / Projection.PIXELS_PER_UNIT) {
			bits |= Overlays.CHUNK_WEST;
		}
		if (this.hitTop && (this.hitZ & 15) == 0 && this.hitFz < 1.0 / (Projection.PIXELS_PER_UNIT * this.sin)) {
			bits |= Overlays.CHUNK_NORTH;
		}
		Long seed = this.overlays.slimeSeed();
		if (seed != null) {
			int cx = this.hitX >> 4;
			int cz = this.hitZ >> 4;
			if (this.slimeChunks.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFFFFFFL), k -> Overlays.slimeChunk(seed, cx, cz))) {
				bits |= Overlays.SLIME;
			}
		}
		// A dot in the middle of each top face a monster could spawn on: a block it can stand on,
		// two blocks of room above, and no block light, the rule for most monsters since 1.18.
		// By day the sky light there must be low too; at night any sky light is dark enough.
		if (this.overlays.spawnSpots() && this.hitTop && this.looks.spawnOn[this.hitId]
			&& this.hitFx >= 0.3 && this.hitFx < 0.7 && this.hitFz >= 0.3 && this.hitFz < 0.7) {
			int feet = this.reader.state(this.hitX, this.hitY + 1, this.hitZ);
			int head = this.reader.state(this.hitX, this.hitY + 2, this.hitZ);
			if (feet != VoxelReader.UNKNOWN && head != VoxelReader.UNKNOWN && this.looks.spawnIn[feet] && this.looks.spawnIn[head]
				&& this.reader.blockLight(this.hitX, this.hitY + 1, this.hitZ) == 0) {
				int sky = this.reader.skyLight(this.hitX, this.hitY + 1, this.hitZ);
				bits |= sky <= Overlays.DAYLIGHT_SPAWN_LIMIT ? Overlays.SPAWN_ANY_TIME : Overlays.SPAWN_AT_NIGHT;
			}
		}
		return bits;
	}

	/** True if the neighbour is a much nearer surface, so this pixel sits just behind an edge. */
	private static boolean nearer(float neighbour, float here) {
		return here - neighbour > OUTLINE_DEPTH;
	}

	static int darken(int abgr, float keep) {
		int r = Math.round((abgr & 0xFF) * keep);
		int g = Math.round(((abgr >> 8) & 0xFF) * keep);
		int b = Math.round(((abgr >> 16) & 0xFF) * keep);
		return (abgr & 0xFF000000) | (b << 16) | (g << 8) | r;
	}

	/** Traces one ray. Returns false when it ran into a chunk the map has no data for. */
	private boolean trace(double u, double v, int sample) {
		final double sin = this.sin;
		final double cos = this.cos;
		final int x = (int) Math.floor(u);
		final double fx = u - x;
		final int top = this.tall == null ? this.startY : this.tall.rayStart(x, v, sin, cos);
		final double y0 = top;
		final double z0 = (v + y0 * cos) / sin;

		int by = top - 1;
		int bz = (int) Math.floor(z0);
		double tMaxY = (y0 - by) / sin;
		double tMaxZ = (z0 - bz) / cos;
		final double tDeltaY = 1.0 / sin;
		final double tDeltaZ = 1.0 / cos;
		double t = 0;
		boolean enteredTop = true;

		boolean skippingRock = this.lit;
		this.hitSolid = false;
		// Colour picked up from plants and glass on the way down, applied to the surface below.
		float overR = 0;
		float overG = 0;
		float overB = 0;
		float through = 1;
		// Water the ray passed through.
		int waterId = -1;
		double waterT = 0;
		int waterBy = 0;
		int waterBz = 0;

		while (by >= this.limitY) {
			if (skippingRock) {
				int solid = this.reader.solid(x, by, bz);
				if (solid == VoxelReader.UNKNOWN) {
					return false;
				}
				if (solid > 0 || this.lifted && by + 1 < this.startY && this.reader.pocket(x, by, bz)) {
					// Still inside the rock above the first open space below the cut.
					if (tMaxY < tMaxZ) {
						by--;
						t = tMaxY;
						tMaxY += tDeltaY;
						enteredTop = true;
					} else {
						bz--;
						t = tMaxZ;
						tMaxZ += tDeltaZ;
						enteredTop = false;
					}
					continue;
				}
				skippingRock = false;
			}
			int id = this.reader.state(x, by, bz);
			if (id == VoxelReader.UNKNOWN) {
				if (!this.passesMissing(by)) {
					return false;
				}
				id = VoxelReader.AIR_ID;
			}
			byte kind = this.looks.kind[id];
			// Only solid blocks (and the see-through ones among them) have shapes.
			int shape = kind == BlockLooks.SOLID || kind == BlockLooks.GLASS ? this.looks.shape[id] : 0;
			boolean partial = shape != 0;
			if (partial) {
				double tIn = t;
				boolean hit = this.enterShape(this.looks.shapes[shape], fx, y0, z0, by, bz, t, Math.min(tMaxY, tMaxZ), enteredTop);
				int wet = this.looks.wetShape[id];
				// Waterlogged: the ray is in water from where it came into the cell, unless it met
				// the block right there, as on a fence top level with the water.
				if (wet >= 0 && waterId < 0 && (!hit || this.shapeT > tIn)) {
					waterId = wet;
					waterT = tIn;
					waterBy = by;
					waterBz = bz;
				}
				if (hit) {
					t = this.shapeT;
					enteredTop = this.shapeTop;
				} else {
					// The ray passes beside the block's boxes, on into the next cell.
					kind = BlockLooks.AIR;
				}
			}

			switch (kind) {
				case BlockLooks.SOLID, BlockLooks.LAVA -> {
					if (this.lifted && kind == BlockLooks.SOLID && this.reader.ceilingUnderside(x, by, bz, this.lid(x, bz), this.limitY)) {
						// A ray that left the rock near a step in a stepped ceiling would clip the
						// step below it and draw a piece of ceiling floating over the floor.
						break;
					}
					if (this.fogOfWar && !this.source.isSeen(x, by, bz)
						&& !(enteredTop ? this.source.isSeen(x, by + 1, bz) : this.source.isSeen(x, by, bz + 1))) {
						return false;
					}
					if (this.surfaceOnly) {
						// Water over the block is the ground there.
						if (waterId >= 0) {
							this.landedOnWater(waterId, x, waterBy, waterBz, y0 - waterT * sin);
						} else {
							this.landed(id, x, by, bz, fx, y0 - t * sin, z0 - t * cos, enteredTop, partial);
						}
						return true;
					}
					double hitY = y0 - t * sin;
					double hitZ = z0 - t * cos;
					this.hitSolid = kind == BlockLooks.SOLID;
					this.hitId = id;
					this.hitTop = enteredTop;
					this.hitX = x;
					this.hitY = by;
					this.hitZ = bz;
					this.hitFx = fx;
					this.hitFz = hitZ - bz;
					this.outDepth = (float) (-(sin * hitY + cos * hitZ) - this.depthOrigin);
					this.shade(id, x, by, bz, fx, hitY, hitZ, enteredTop, partial, sample);
					if (through < 1) {
						// The overlay tints the albedo; approximate by mixing it in after lighting.
						float light = luminance(this.outR, this.outG, this.outB) / Math.max(0.05F, luminance(this.albedoR, this.albedoG, this.albedoB));
						this.outR = this.outR * through + overR * light;
						this.outG = this.outG * through + overG * light;
						this.outB = this.outB * through + overB * light;
					}
					if (waterId >= 0) {
						this.mixWater(waterId, x, waterBy, waterBz, u, y0, z0, waterT, t, sample);
						if (this.cave) {
							// Lit as water, as a surface trace records it.
							this.surfaceLight = this.waterLight(x, waterBy, waterBz, y0 - waterT * sin);
						}
					}
					return true;
				}
				case BlockLooks.WATER -> {
					if (waterId < 0) {
						waterId = id;
						waterT = t;
						waterBy = by;
						waterBz = bz;
					}
				}
				case BlockLooks.PLANT, BlockLooks.GLASS -> {
					if (this.surfaceOnly) {
						break;
					}
					float w = kind == BlockLooks.PLANT ? 0.4F : 0.25F;
					this.albedo(id, x, bz, true);
					overR += through * w * this.albedoR;
					overG += through * w * this.albedoG;
					overB += through * w * this.albedoB;
					through *= 1 - w;
				}
				default -> {
				}
			}

			if (tMaxY < tMaxZ) {
				by--;
				t = tMaxY;
				tMaxY += tDeltaY;
				enteredTop = true;
			} else {
				bz--;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
				enteredTop = false;
			}
		}

		if (this.fogOfWar && (skippingRock || waterId < 0 || !this.source.isSeen(x, waterBy, waterBz))) {
			return false;
		}
		if (this.surfaceOnly) {
			if (waterId >= 0) {
				this.landedOnWater(waterId, x, waterBy, waterBz, y0 - waterT * sin);
			}
			return true;
		}
		this.outDepth = (float) (-(sin * (y0 - t * sin) + cos * (z0 - t * cos)) - this.depthOrigin);
		if (skippingRock) {
			// Solid rock all the way down the slice: draw the cut face.
			this.outR = 0.0065F;
			this.outG = 0.0068F;
			this.outB = 0.0085F;
		} else if (waterId >= 0) {
			this.outR = 0;
			this.outG = 0;
			this.outB = 0;
			this.mixWater(waterId, x, waterBy, waterBz, u, y0, z0, waterT, waterT + 64, sample);
			if (this.cave) {
				this.surfaceLight = this.waterLight(x, waterBy, waterBz, y0 - waterT * sin);
			}
		} else {
			this.outR = 0.01F;
			this.outG = 0.01F;
			this.outB = 0.015F;
		}
		return true;
	}

	float albedoR;
	float albedoG;
	float albedoB;

	/** Sets {@code albedo*} to the block's linear colour for its top or front face at this column. */
	void albedo(int id, int x, int z, boolean top) {
		BlockLooks l = this.looks;
		int base = top ? l.top[id] : l.side[id];
		int tinted = top ? l.topTinted[id] : l.sideTinted[id];
		float w = top ? l.topTintWeight[id] : l.sideTintWeight[id];
		int tint = this.tint(id, x, z);
		float bw = 1 - w;
		this.albedoR = SRGB_TO_LINEAR[(base >> 16) & 0xFF] * bw + SRGB_TO_LINEAR[(tinted >> 16) & 0xFF] * SRGB_TO_LINEAR[(tint >> 16) & 0xFF] * w;
		this.albedoG = SRGB_TO_LINEAR[(base >> 8) & 0xFF] * bw + SRGB_TO_LINEAR[(tinted >> 8) & 0xFF] * SRGB_TO_LINEAR[(tint >> 8) & 0xFF] * w;
		this.albedoB = SRGB_TO_LINEAR[base & 0xFF] * bw + SRGB_TO_LINEAR[tinted & 0xFF] * SRGB_TO_LINEAR[tint & 0xFF] * w;
	}

	private int tint(int id, int x, int z) {
		byte kind = this.looks.tint[id];
		if (kind == BlockLooks.TINT_NONE) {
			int constant = this.looks.constantTint[id];
			return constant < 0 ? 0xFFFFFF : constant;
		}
		ChunkSnapshot snapshot = this.reader.snapshot(x, z);
		if (snapshot == null) {
			return 0xFFFFFF;
		}
		int i = ((z & 15) << 4) | (x & 15);
		ChunkSnapshot.Tints tints = BiomeTints.of(snapshot);
		return switch (kind) {
			case BlockLooks.TINT_GRASS -> tints.grass()[i];
			case BlockLooks.TINT_FOLIAGE -> tints.foliage()[i];
			case BlockLooks.TINT_DRY_FOLIAGE -> tints.dryFoliage()[i];
			case BlockLooks.TINT_WATER -> tints.water()[i];
			default -> 0xFFFFFF;
		};
	}

	private void shade(int id, int x, int by, int bz, double fx, double hitY, double hitZ, boolean top, boolean partial, int sample) {
		this.albedo(id, x, bz, top);
		float variation = this.style.blockVariation();
		if (variation > 0) {
			// A fixed per-block nudge in brightness, so a field of one block isn't a flat sheet.
			float nudge = 1 + variation * blockNoise(x, by, bz);
			this.albedoR *= nudge;
			this.albedoG *= nudge;
			this.albedoB *= nudge;
		}
		// The open cell in front of the face the ray hit; for a face inset in a smaller block,
		// the cell in front of where a full cube's face would be.
		int frontY = top ? by + 1 : by;
		int frontZ = top ? bz : bz + 1;
		float ao = top
			? this.ambientOcclusionTop(x, by + 1, bz, fx, hitZ - bz)
			: this.ambientOcclusionSouth(x, by, bz + 1, fx, hitY - by);
		float emission = this.looks.emission[id] / 15.0F;
		if (this.looks.kind[id] == BlockLooks.LAVA) {
			// In the Nether's fog a lava sea reads as one glow rather than a field of speckles.
			float crust = 1 + (this.lifted ? 0.1F : 0.3F) * blockNoise(x, by, bz);
			this.albedoR *= crust;
			this.albedoG *= crust * crust;
			this.albedoB *= crust;
		}

		// Shade in corners and creases dims the sunlight too as far as the style asks, and the sky
		// light by at least as much as always.
		float aoSun = Math.max(0, 1 - this.style.cornerOcclusion() * (1 - ao));
		float aoSky = Math.min(ao, aoSun);
		float lr;
		float lg;
		float lb;
		if (this.lifted) {
			// Lit like the surface with the lid off: direct light from above casts shadows under
			// ledges and down the faces of pillars, and spots that see little of the open space
			// above get less of the fill. Block light glows on top.
			double[] j = SUN_JITTER[sample];
			double sx = this.sunX + j[0];
			double sy = this.sunY + j[1];
			double sz = this.sunZ + j[2];
			double lambert = top ? 1 : Math.max(0, sz) / sy;
			float direct = 0;
			if (lambert > 0) {
				double ox = x + fx;
				double oy = top ? by + 1 + 1e-4 : hitY;
				double oz = top ? hitZ : bz + 1 + 1e-4;
				direct = this.inShadow(ox, oy, oz, sx, sy, sz) ? 0 : (float) (LID_KEY * lambert) * aoSun;
			}
			float sky = top ? this.skyTop(x, by + 1, bz, fx, hitZ - bz) : this.skySouth(x, by, bz + 1, fx, hitY - by);
			float ambient = LID_AMBIENT * aoSky * (1 - this.style.skyOcclusion() * (1 - (0.15F + 0.85F * sky)));
			float warm = (float) Math.pow(this.reader.blockLight(x, frontY, frontZ) / 15.0F, 1.5) * LID_GLOW;
			lr = ambient * LID_FILL_TINT[0] + direct * LID_KEY_TINT[0] + warm * 0.9F * ao;
			lg = ambient * LID_FILL_TINT[1] + direct * LID_KEY_TINT[1] + warm * 0.75F * ao;
			lb = ambient * LID_FILL_TINT[2] + direct * LID_KEY_TINT[2] + warm * 0.5F * ao;
		} else if (this.lit) {
			// A cave slice (under a roof the light is lifted, above). Deeper below the cut reads darker.
			int blockLight = this.reader.blockLight(x, frontY, frontZ);
			int skyLight = this.reader.skyLight(x, frontY, frontZ);
			float fade = 1 - Math.min(0.5F, (float) (this.startY - hitY) / 60.0F);
			Lighting.cave(blockLight, skyLight, ao, fade, emission, top ? 1 : SIDE, this.light);
			this.outR = this.albedoR * this.light[0];
			this.outG = this.albedoG * this.light[1];
			this.outB = this.albedoB * this.light[2];
			// Kept for moving lights: what lit this surface besides a held torch.
			this.surfaceLight = TileSurface.caveLight(blockLight, skyLight, ao, fade, this.looks.emission[id]);
			return;
		} else {
			double[] j = SUN_JITTER[sample];
			double sx = this.sunX + j[0];
			double sy = this.sunY + j[1];
			double sz = this.sunZ + j[2];
			// Relative to a top face, so tops stay as bright whatever the sun's height.
			double lambert = top ? 1 : Math.max(0, sz) / sy;
			// A partial block's face can sit inside its cell; the shadow ray starts on the face.
			boolean shadowed = lambert > 0 && this.inShadow(
				x + fx, top ? (partial ? hitY : by + 1) + 1e-4 : hitY, top ? hitZ : (partial ? hitZ : bz + 1) + 1e-4, sx, sy, sz
			);
			float sky = this.style.skyOcclusion() > 0 ? (top ? this.skyTop(x, by + 1, bz, fx, hitZ - bz) : this.skySouth(x, by, bz + 1, fx, hitY - by)) : 1;
			this.lighting.surface(lambert, shadowed, ao, sky, emission, top ? 1 : SIDE, hitY, this.light);
			this.outR = this.albedoR * this.light[0];
			this.outG = this.albedoG * this.light[1];
			this.outB = this.albedoB * this.light[2];
			return;
		}
		if (emission > 0) {
			// In the fog, small lights like shroomlights and glowstone glow softer, so they don't
			// speckle the view; lava keeps its full glow.
			float e = this.lifted && this.looks.kind[id] != BlockLooks.LAVA ? 0.3F + emission * 0.45F : 0.35F + emission * 0.85F;
			lr = Math.max(lr, e);
			lg = Math.max(lg, e);
			lb = Math.max(lb, e);
		}
		if (!top) {
			lr *= SIDE;
			lg *= SIDE;
			lb *= SIDE;
		}
		this.outR = this.albedoR * lr;
		this.outG = this.albedoG * lg;
		this.outB = this.albedoB * lb;
		if (this.lifted) {
			// The lower the ground, the more of the biome's fog lies in front: ledges high under the
			// roof stand out over the floor and the lava sea far below.
			double below = this.source.roof() - HAZE_START - hitY;
			float f = below <= 0 ? 0 : (float) Math.min(HAZE_MAX, 1 - Math.exp(-below / HAZE_DEPTH));
			this.hazeColor(x + fx, hitY, hitZ);
			this.outR += (this.hazeR - this.outR) * f;
			this.outG += (this.hazeG - this.outG) * f;
			this.outB += (this.hazeB - this.outB) * f;
		}
	}

	private void mixWater(int waterId, int x, int by, int bz, double u, double y0, double z0, double tEntry, double tHit, int sample) {
		this.albedo(waterId, x, bz, true);
		float wr = this.albedoR;
		float wg = this.albedoG;
		float wb = this.albedoB;
		float light;
		if (this.lit) {
			light = Lighting.caveWater(this.reader.blockLight(x, by + 1, bz), this.reader.skyLight(x, by + 1, bz));
		} else {
			double sy = this.sunY + SUN_JITTER[sample][1];
			double ox = u;
			double oy = y0 - tEntry * this.sin + 1e-4;
			double oz = z0 - tEntry * this.cos;
			boolean shadow = this.inShadow(ox, oy, oz, this.sunX, sy, this.sunZ);
			// Lit like the ground around it: the style's light, sky occlusion and height shade.
			float ambient = this.style.ambient();
			float occlusion = this.style.skyOcclusion();
			if (occlusion > 0) {
				float sky = this.skyTop(x, by + 1, bz, u - x, oz - bz);
				ambient *= 1 - occlusion * (1 - (0.15F + 0.85F * sky));
			}
			light = ambient + (shadow ? 0 : this.style.sunStrength());
			float heightShade = this.style.heightShade();
			if (heightShade != 0) {
				light *= 1 + heightShade * (float) Math.max(-1, Math.min(1, (by + 1 - 64) / 64.0));
			}
		}
		double depth = Math.max(0, tHit - tEntry);
		if (this.style.clearWater()) {
			// Light through water loses the colours the water doesn't have: red goes first, so
			// shallows over sand turn turquoise and deep water ends up the water's own blue.
			float peak = Math.max(wr, Math.max(wg, wb));
			float tr = (float) Math.exp(-depth * 0.45 * (1 - wr / peak) - depth * 0.04);
			float tg = (float) Math.exp(-depth * 0.45 * (1 - wg / peak) - depth * 0.04);
			float tb = (float) Math.exp(-depth * 0.45 * (1 - wb / peak) - depth * 0.04);
			float deepR = wr * 0.55F * light;
			float deepG = wg * 0.55F * light;
			float deepB = wb * 0.55F * light;
			// A little sky reflected off the surface.
			float sheen = 0.05F * light;
			this.outR = this.outR * tr + deepR * (1 - tr) + sheen;
			this.outG = this.outG * tg + deepG * (1 - tg) + sheen;
			this.outB = this.outB * tb + deepB * (1 - tb) + sheen * 1.2F;
			return;
		}
		float a = (float) Math.max(0.5, Math.min(0.94, 1 - Math.exp(-0.2 * depth)));
		this.outR = this.outR * (1 - a) + wr * light * a;
		this.outG = this.outG * (1 - a) + wg * light * a;
		this.outB = this.outB * (1 - a) + wb * light * a;
	}

	/** Walks from a point toward the sun; true if a solid block is in the way. */
	private boolean inShadow(double ox, double oy, double oz, double dx, double dy, double dz) {
		return this.blocked(ox, oy, oz, dx, dy, dz, SHADOW_DISTANCE, SHADOW_STEPS, true);
	}

	/** Sky visibility on a top face at {@code y}, blended from its four corners. */
	private float skyTop(int x, int y, int z, double fx, double fz) {
		return bilinear(this.skyAt(x, y, z), this.skyAt(x + 1, y, z), this.skyAt(x, y, z + 1), this.skyAt(x + 1, y, z + 1), fx, fz);
	}

	/** Sky visibility on a south face at {@code z}, blended from its four corners; {@code fy} runs bottom to top. */
	private float skySouth(int x, int y, int z, double fx, double fy) {
		return bilinear(this.skyAt(x, y + 1, z), this.skyAt(x + 1, y + 1, z), this.skyAt(x, y, z), this.skyAt(x + 1, y, z), fx, 1 - fy);
	}

	/** Share of the sky seen from a block corner, 0..1, from a few short rays upward. */
	float skyAt(int x, int y, int z) {
		long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
		float cached = this.skyAtCorner.getOrDefault(key, -1F);
		if (cached >= 0) {
			return cached;
		}
		double open = 0;
		double total = 0;
		for (int i = 0; i < SKY_RAYS.length; i++) {
			double[] d = SKY_RAYS[i];
			// Start a hair along the ray, so it begins in the block it heads into, not on a boundary.
			// Sky light is soft and sampled at cube corners anyway: a smaller block blocks it like a cube.
			if (!this.blocked(x + d[0] * 1e-3, y + d[1] * 1e-3, z + d[2] * 1e-3, d[0], d[1], d[2], SKY_REACH, SKY_REACH * 3, false)) {
				open += SKY_WEIGHTS[i];
			}
			total += SKY_WEIGHTS[i];
		}
		float sky = (float) (open / total);
		this.skyAtCorner.put(key, sky);
		return sky;
	}

	/**
	 * Walks from a point along a direction for up to {@code distance} blocks; true if a solid block
	 * is in the way. Without {@code shapes}, a block smaller than a cube blocks the way like a cube.
	 */
	private boolean blocked(double ox, double oy, double oz, double dx, double dy, double dz, double distance, int steps, boolean shapes) {
		double ix = 1 / dx;
		double iy = 1 / dy;
		double iz = 1 / dz;
		int cx = (int) Math.floor(ox);
		int cy = (int) Math.floor(oy);
		int cz = (int) Math.floor(oz);
		// Under a lifted lid its rock doesn't block light.
		int start = cy < this.rayTop && !(this.lifted && cy >= this.lid(cx, cz)) ? this.reader.solid(cx, cy, cz) : 0;
		if (start == 1 || start == 2 && (!shapes || this.meetsShape(cx, cy, cz, ox, oy, oz, dx, dy, dz, ix, iy, iz))) {
			return true;
		}
		// Under a lifted lid nothing above the cut is left to block light.
		int top = this.lifted ? Math.min(this.rayTop, this.startY) : this.rayTop;
		if (dy > 0) {
			// A ray that only climbs can't be blocked once it is above every solid block in the
			// chunks it can reach, so it stops there instead of walking on through open sky.
			double ex = ox + dx * distance;
			double ez = oz + dz * distance;
			top = Math.min(top, this.reader.solidTop(
				((int) Math.floor(Math.min(ox, ex)) - 1) >> 4, ((int) Math.floor(Math.min(oz, ez)) - 1) >> 4,
				((int) Math.floor(Math.max(ox, ex)) + 1) >> 4, ((int) Math.floor(Math.max(oz, ez)) + 1) >> 4
			));
		}
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(ix);
		double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(iy);
		double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(iz);
		double tMaxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? cx + 1 - ox : ox - cx) * tDeltaX;
		double tMaxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? cy + 1 - oy : oy - cy) * tDeltaY;
		double tMaxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? cz + 1 - oz : oz - cz) * tDeltaZ;
		for (int i = 0; i < steps; i++) {
			if (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) > distance) {
				return false;
			}
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				cx += stepX;
				tMaxX += tDeltaX;
			} else if (tMaxY < tMaxZ) {
				cy += stepY;
				tMaxY += tDeltaY;
			} else {
				cz += stepZ;
				tMaxZ += tDeltaZ;
			}
			if (cy >= top) {
				return false;
			}
			int solid = this.reader.solid(cx, cy, cz);
			if (solid == VoxelReader.UNKNOWN) {
				return false;
			}
			if (this.lifted && solid > 0 && cy >= this.lid(cx, cz)) {
				continue;
			}
			if (solid == 1 || solid == 2 && (!shapes || this.meetsShape(cx, cy, cz, ox, oy, oz, dx, dy, dz, ix, iy, iz))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a ray from (ox, oy, oz) along (dx, dy, dz) meets the solid block in this cell: always
	 * for a full cube, and for a smaller block only if it crosses one of its boxes.
	 */
	private boolean meetsShape(int cx, int cy, int cz, double ox, double oy, double oz, double dx, double dy, double dz, double ix, double iy, double iz) {
		int id = this.reader.state(cx, cy, cz);
		int shape = id < 0 ? 0 : this.looks.shape[id];
		if (shape == 0) {
			return true;
		}
		float[] boxes = this.looks.shapes[shape];
		double px = ox - cx;
		double py = oy - cy;
		double pz = oz - cz;
		for (int i = 0; i < boxes.length; i += 6) {
			double near = 0;
			double far = Double.MAX_VALUE;
			for (int axis = 0; axis < 3 && near <= far; axis++) {
				double p = axis == 0 ? px : axis == 1 ? py : pz;
				double d = axis == 0 ? dx : axis == 1 ? dy : dz;
				double inv = axis == 0 ? ix : axis == 1 ? iy : iz;
				double min = boxes[i + axis];
				double max = boxes[i + 3 + axis];
				if (d == 0) {
					if (p < min || p > max) {
						far = -1;
					}
				} else {
					double t0 = (min - p) * inv;
					double t1 = (max - p) * inv;
					near = Math.max(near, Math.min(t0, t1));
					far = Math.min(far, Math.max(t0, t1));
				}
			}
			if (near <= far) {
				return true;
			}
		}
		return false;
	}

	// Where the last enterShape call met the boxes: the ray distance, and whether through a top face.
	private double shapeT;
	private boolean shapeTop;

	/**
	 * Whether a camera ray crossing cell ({@code by}, {@code bz}) between {@code tIn} and
	 * {@code tOut} meets one of a block's boxes, and where first. The ray runs {@code fx} across the
	 * cell, heading down and north; {@code inTop} says whether it came into the cell from above.
	 */
	private boolean enterShape(float[] boxes, double fx, double y0, double z0, int by, int bz, double tIn, double tOut, boolean inTop) {
		double best = Double.MAX_VALUE;
		for (int i = 0; i < boxes.length; i += 6) {
			if (fx < boxes[i] || fx >= boxes[i + 3]) {
				continue;
			}
			double tTop = (y0 - by - boxes[i + 4]) * this.invSin;
			double tSouth = (z0 - bz - boxes[i + 5]) * this.invCos;
			double enter = Math.max(tIn, Math.max(tTop, tSouth));
			double exit = Math.min(tOut, Math.min((y0 - by - boxes[i + 1]) * this.invSin, (z0 - bz - boxes[i + 2]) * this.invCos));
			if (enter < exit && enter < best) {
				best = enter;
				// A box flush with the cell's side is met where the ray came into the cell.
				this.shapeTop = enter == tIn ? inTop : tTop >= tSouth;
			}
		}
		this.shapeT = best;
		return best != Double.MAX_VALUE;
	}

	boolean occludes(int x, int y, int z) {
		return this.reader.solid(x, y, z) > 0;
	}

	/** Smooth corner occlusion on a top face, from the eight blocks around the cell above it. */
	private float ambientOcclusionTop(int x, int y, int z, double fx, double fz) {
		boolean n = this.occludes(x, y, z - 1);
		boolean s = this.occludes(x, y, z + 1);
		boolean w = this.occludes(x - 1, y, z);
		boolean e = this.occludes(x + 1, y, z);
		float nw = corner(w, n, this.occludes(x - 1, y, z - 1));
		float ne = corner(e, n, this.occludes(x + 1, y, z - 1));
		float sw = corner(w, s, this.occludes(x - 1, y, z + 1));
		float se = corner(e, s, this.occludes(x + 1, y, z + 1));
		return bilinear(nw, ne, sw, se, fx, fz);
	}

	/** Same for a south face; {@code z} is the open layer in front of it, {@code fy} runs bottom to top. */
	private float ambientOcclusionSouth(int x, int y, int z, double fx, double fy) {
		boolean up = this.occludes(x, y + 1, z);
		boolean down = this.occludes(x, y - 1, z);
		boolean w = this.occludes(x - 1, y, z);
		boolean e = this.occludes(x + 1, y, z);
		float tw = corner(w, up, this.occludes(x - 1, y + 1, z));
		float te = corner(e, up, this.occludes(x + 1, y + 1, z));
		float bw = corner(w, down, this.occludes(x - 1, y - 1, z));
		float be = corner(e, down, this.occludes(x + 1, y - 1, z));
		return bilinear(tw, te, bw, be, fx, 1 - fy);
	}

	static float corner(boolean side1, boolean side2, boolean diagonal) {
		if (side1 && side2) {
			return AO_LEVELS[0];
		}
		return AO_LEVELS[3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (diagonal ? 1 : 0)];
	}

	/** {@code a b} on the first row, {@code c d} on the second. */
	private static float bilinear(float a, float b, float c, float d, double fx, double fy) {
		float top = (float) (a + (b - a) * fx);
		float bottom = (float) (c + (d - c) * fx);
		return (float) (top + (bottom - top) * fy);
	}

	private static float luminance(float r, float g, float b) {
		return 0.2126F * r + 0.7152F * g + 0.0722F * b;
	}

	/** -1..1, fixed per block position. */
	static float blockNoise(int x, int y, int z) {
		int h = x * 73_856_093 ^ y * 19_349_663 ^ z * 83_492_791;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		h ^= h >>> 15;
		return ((h & 0xFFFF) / 32767.5F) - 1;
	}

	private int abgr(float r, float g, float b, int alpha) {
		return abgr(this.style, r, g, b, alpha);
	}

	/** Linear light graded by the style, as an sRGB ABGR pixel. */
	static int abgr(MapStyle style, float r, float g, float b, int alpha) {
		float saturation = style.saturation();
		float exposure = style.exposure();
		float l = luminance(r, g, b);
		r = (l + (r - l) * saturation) * exposure;
		g = (l + (g - l) * saturation) * exposure;
		b = (l + (b - l) * saturation) * exposure;
		int ri = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (r * 4095)))];
		int gi = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (g * 4095)))];
		int bi = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (b * 4095)))];
		return (alpha << 24) | (bi << 16) | (gi << 8) | ri;
	}

	private static byte[] coverage(int[] pixels, int width, int height) {
		int cellPixels = Projection.TILE_PIXELS / Projection.COVERAGE_CELLS;
		int cellsX = width / cellPixels;
		int cellsY = height / cellPixels;
		byte[] out = new byte[cellsX * cellsY];
		for (int cy = 0; cy < cellsY; cy++) {
			for (int cx = 0; cx < cellsX; cx++) {
				int sum = 0;
				for (int y = 0; y < cellPixels; y++) {
					int row = (cy * cellPixels + y) * width + cx * cellPixels;
					for (int x = 0; x < cellPixels; x++) {
						sum += pixels[row + x] >>> 24;
					}
				}
				out[cy * cellsX + cx] = (byte) (sum / (cellPixels * cellPixels));
			}
		}
		return out;
	}
}
