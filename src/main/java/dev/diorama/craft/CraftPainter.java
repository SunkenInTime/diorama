package dev.diorama.craft;

import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.CraftBaker;
import dev.diorama.bake.CraftBlocks;
import dev.diorama.bake.CraftShadow;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.SurfaceArea;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.render.Circle;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Draws the crafts over a view of the map: their shadows on the ground first, then the crafts.
 * Craft images and shadows are made on the baker thread; until a new one is ready the last
 * stands in, moved along with the craft.
 *
 * <p>Layering is per pixel. Each craft image keeps its pixels' depths, and each surface tile can
 * say where the camera meets the ground at each of its pixels ({@link MapLevel#surface}). A craft
 * pixel the ground is nearer than (a train under a building's overhang, a windmill behind a
 * cliff) is cut out, and where two crafts overlap the bigger is
 * drawn first and the smaller cut out where the bigger is nearer.
 *
 * <p>All are drawn each frame from the craft's live pose, so whatever its parts are built from,
 * they stay together as the game moves them.
 */
final class CraftPainter {
	/** How far (map units) the image may miss the craft's corners before it bakes again: half a pixel, as its shadow does. */
	private static final double REBAKE_MISS = 0.5 / Projection.PIXELS_PER_UNIT;
	/** Blocks a craft climbs or sinks before it bakes again, for the style's shading with height. */
	private static final double REBAKE_CLIMB = 4;
	/** Blocks a craft moves before its shadow is made again: half a pixel. */
	private static final double SHADOW_STEP = 0.5 / Projection.PIXELS_PER_UNIT;
	/** Fewest nanoseconds between remaking a shadow: a craft only moves on the server's ticks. */
	private static final long SHADOW_GAP = 50_000_000;
	/** Time a craft's image or shadow waits for each unit of time the last one took: at most a fifth of the baker thread each. */
	private static final long IDLE_PER_WORK = 4;
	/** Nanoseconds a craft keeps the area its shadow may cover before looking at the ground again. */
	private static final long AREA_KEPT = 1_000_000_000;
	/** Blocks a craft's middle moves before that area is found again: within the area's margin. */
	private static final double AREA_MOVE = 1;
	/** Nanoseconds a frame may spend on new textures; what doesn't fit waits for the next frame. */
	private static final long UPLOAD_NANOS = 1_500_000;
	/** Nanoseconds a cut of the same image may go out of date when a frame has no time for it. */
	private static final long CUT_KEPT = 250_000_000;
	/** How much nearer (blocks) one craft's pixel must be to hide another's: less than a block's depth. */
	private static final float HIDES = 0.25F;
	/** How much nearer (blocks) the ground must be to hide a craft's pixel, so a train isn't hidden by the rails it stands in. */
	private static final float GROUND_HIDES = 0.3F;

	static final ExecutorService BAKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-craft-baker");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});

	private final MapLevel map;
	private long uploadsUntil;

	CraftPainter(MapLevel map) {
		this.map = map;
	}

	/** A craft to draw, at a place on the map's pixels. */
	private record Drawn(Craft craft, CraftBaker.Image image, int pu0, int pv0, double depth, double[] place) {
		int pu1() {
			return this.pu0 + this.image.width();
		}

		int pv1() {
			return this.pv0 + this.image.height();
		}

		long area() {
			return (long) this.image.width() * this.image.height();
		}

		boolean overlaps(Drawn other) {
			return this.pu0 < other.pu1() && other.pu0 < this.pu1() && this.pv0 < other.pv1() && other.pv0 < this.pv1();
		}
	}

	/** A shadow to draw, shifted from where it was made to where the craft is now. */
	private record Shade(Craft craft, double u0, double v0) {
	}

	void draw(GuiGraphicsExtractor graphics, MapViewport.View view, Collection<Craft> crafts, float partialTick, float opacity) {
		BlockLooks looks = BlockLooks.get();
		if (looks == null || crafts.isEmpty()) {
			return;
		}
		this.uploadsUntil = System.nanoTime() + UPLOAD_NANOS;
		MapStyle style = MapStyle.get();
		double sin = Projection.sin();
		double cos = Projection.cos();
		int ppu = Projection.PIXELS_PER_UNIT;
		boolean surface = view.layer() == TileKey.SURFACE;
		// Under fog of war a craft mustn't give away tunnels not seen yet.
		if (!surface && this.map.fogOfWar()) {
			return;
		}
		List<Drawn> drawn = new ArrayList<>();
		List<Shade> shades = new ArrayList<>();
		for (Craft craft : crafts) {
			Body.Blocks blocks = craft.blocks;
			if (blocks == null || !craft.posed) {
				continue;
			}
			double[] pose = craft.framePose;
			if (craft.body == null || !craft.body.pose(blocks.min(), partialTick, pose)) {
				System.arraycopy(craft.pose, 0, pose, 0, 12);
			}
			CraftBlocks b = blocks.blocks();
			double[] box = b.box(pose);
			if (!surface && !(box[1] <= view.layer() && box[4] >= view.layer() - TileBaker.CAVE_DEPTH)) {
				continue;
			}
			Craft.Inputs inputs = new Craft.Inputs(b, style, looks);
			if (surface) {
				Shade shade = this.shadow(craft, inputs, pose, view);
				if (shade != null) {
					shades.add(shade);
				}
			}
			// The box's corners bound where the craft can show on the map.
			if (!inView(view, box[0], box[2] * sin - box[4] * cos, box[3], box[5] * sin - box[1] * cos)) {
				continue;
			}
			double[] c = craft.center(pose);
			double cu = c[0];
			double cv = Projection.v(c[1], c[2]);
			this.bake(craft, inputs, pose);
			CraftBaker.Image image = craft.image;
			if (image != null && craft.texture != null) {
				// On the map's pixel grid, as the tiles and shadows are.
				int pu0 = (int) Math.round((cu + image.offsetU()) * ppu);
				int pv0 = (int) Math.round((cv + image.offsetV()) * ppu);
				double[] place = {(double) pu0 / ppu, (double) pv0 / ppu, 1.0 / ppu, 0, 0, 1.0 / ppu};
				drawn.add(new Drawn(craft, image, pu0, pv0, -(sin * c[1] + cos * c[2]), place));
			}
		}
		if (!drawn.isEmpty() || !shades.isEmpty()) {
			this.paint(graphics, view, drawn, shades, surface, opacity);
		}
	}

	/** Whether the map area from (u0, v0) to (u1, v1) shows in the view. */
	private static boolean inView(MapViewport.View view, double u0, double v0, double u1, double v1) {
		return u1 > view.minU() && u0 < view.minU() + view.width() / view.zoom() && v1 > view.minV() && v0 < view.minV() + view.height() / view.zoom();
	}

	private void paint(GuiGraphicsExtractor graphics, MapViewport.View view, List<Drawn> drawn, List<Shade> shades, boolean surface, float opacity) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		MapCanvas canvas = MapCanvas.of(graphics);
		Circle circle = view.round() ? Circle.in(view) : null;
		int tint = Math.round(Math.max(0, Math.min(1, opacity)) * 255) << 24 | 0xFFFFFF;
		// Sampled as the tiles are, so the crafts match the ground.
		Sampling sampling = MapViewport.sampling(view);
		// Every shadow under every craft: a craft flying over another's shadow covers it.
		for (Shade s : shades) {
			CraftShadow.Image shadow = s.craft().shadow;
			double[] place = {s.u0(), s.v0(), 1.0 / Projection.PIXELS_PER_UNIT, 0, 0, 1.0 / Projection.PIXELS_PER_UNIT};
			quad(canvas, circle, view, s.craft().shadowTexture, sampling, place, shadow.width(), shadow.height(), shadow.edge(), tint);
		}
		// Biggest first, then each smaller one cut out where a bigger one in its way is nearer.
		drawn.sort((a, b) -> a.area() != b.area() ? Long.compare(b.area(), a.area()) : Double.compare(b.depth(), a.depth()));
		for (int i = 0; i < drawn.size(); i++) {
			Drawn d = drawn.get(i);
			List<Drawn> under = new ArrayList<>();
			for (int j = 0; j < i; j++) {
				if (drawn.get(j).overlaps(d)) {
					under.add(drawn.get(j));
				}
			}
			boolean ground = surface && this.mayBeCovered(d.craft());
			SunMask sun = surface ? this.sunMask(d) : null;
			MapTexture texture = under.isEmpty() && !ground && sun == null ? d.craft().texture : this.cut(d, under, ground, sun);
			quad(canvas, circle, view, texture, sampling, d.place(), d.image().width(), d.image().height(), d.image().edge(), tint);
		}
		graphics.disableScissor();
	}

	/**
	 * An image's {@code width} by {@code height} pixels from the corner of its {@code edge}-square
	 * texture, placed on the map by {u0, v0, du/dx, du/dy, dv/dx, dv/dy} in map units.
	 */
	private static void quad(
		MapCanvas canvas, @Nullable Circle circle, MapViewport.View view, MapTexture texture, Sampling sampling,
		double[] place, int width, int height, int edge, int tint
	) {
		float[] corners = new float[8];
		float[] uvs = new float[8];
		int[][] at = {{0, 0}, {0, height}, {width, height}, {width, 0}};
		for (int i = 0; i < 4; i++) {
			corners[i * 2] = view.screenX(place[0] + place[2] * at[i][0] + place[3] * at[i][1]);
			corners[i * 2 + 1] = view.screenY(place[1] + place[4] * at[i][0] + place[5] * at[i][1]);
			uvs[i * 2] = (float) at[i][0] / edge;
			uvs[i * 2 + 1] = (float) at[i][1] / edge;
		}
		if (circle == null) {
			canvas.quad(texture, sampling, corners, uvs, tint);
			return;
		}
		float[] inside = circle.clip(corners, 4);
		if (inside == null) {
			return;
		}
		// Carries the texture coordinates over to the clipped corners, from the quad's two edges.
		float ax = corners[6] - corners[0];
		float ay = corners[7] - corners[1];
		float bx = corners[2] - corners[0];
		float by = corners[3] - corners[1];
		float det = ax * by - ay * bx;
		if (det == 0) {
			return;
		}
		float[] clippedUvs = new float[inside.length];
		for (int i = 0; i < inside.length; i += 2) {
			float dx = inside[i] - corners[0];
			float dy = inside[i + 1] - corners[1];
			clippedUvs[i] = (dx * by - dy * bx) / det * width / edge;
			clippedUvs[i + 1] = (ax * dy - ay * dx) / det * height / edge;
		}
		canvas.quad(texture, sampling, inside, clippedUvs, tint);
	}

	/**
	 * The craft's image with every pixel cut out that something nearer the camera covers: a
	 * bigger craft drawn before it, or the ground. Made again only when the images, where they
	 * sit, or the ground under them change. The craft's own texture when nothing covers it.
	 */
	private MapTexture cut(Drawn d, List<Drawn> under, boolean ground, @Nullable SunMask sun) {
		List<Object> key = new ArrayList<>();
		key.add(d.image());
		key.add(sun);
		for (Drawn u : under) {
			key.add(u.image());
			key.add(d.pu0() - u.pu0());
			key.add(d.pv0() - u.pv0());
			key.add(Math.round((u.depth() - d.depth()) * 16));
		}
		SurfaceArea surfaces = null;
		if (ground) {
			surfaces = this.map.surfaces(TileKey.SURFACE, d.pu0(), d.pv0(), d.pu1(), d.pv1());
			key.add(d.pu0());
			key.add(d.pv0());
			key.add(Math.round(d.depth() * 16));
			key.add(Arrays.asList(surfaces.tiles()));
		}
		Craft craft = d.craft();
		if (key.equals(craft.cutFor)) {
			return craft.cut != null ? craft.cut : craft.texture;
		}
		// A rotor's overlap with its hull changes constantly, and a slightly old cut can't be seen.
		long now = System.nanoTime();
		if (now >= this.uploadsUntil && now - craft.cutAt < CUT_KEPT && craft.cutFor instanceof List<?> old && old.get(0) == d.image()) {
			return craft.cut != null ? craft.cut : craft.texture;
		}
		craft.cutAt = now;
		craft.cutFor = key;
		CraftBaker.Image image = d.image();
		int edge = image.edge();
		int width = image.width();
		int[] pixels = image.mips()[0].clone();
		int hidden = 0;
		if (sun != null) {
			for (int i = sun.shaded().nextSetBit(0); i >= 0; i = sun.shaded().nextSetBit(i + 1)) {
				int at = i / width * edge + i % width;
				if (pixels[at] != 0) {
					pixels[at] = pixels[at] & 0xFF000000 | image.sunless()[i] & 0xFFFFFF;
					hidden++;
				}
			}
		}
		for (Drawn u : under) {
			CraftBaker.Image other = u.image();
			int dx = d.pu0() - u.pu0();
			int dy = d.pv0() - u.pv0();
			float shift = (float) (u.depth() - d.depth());
			for (int y = Math.max(0, -dy); y < Math.min(image.height(), other.height() - dy); y++) {
				for (int x = Math.max(0, -dx); x < Math.min(width, other.width() - dx); x++) {
					int i = y * edge + x;
					float there = other.depth()[(y + dy) * other.width() + x + dx];
					if (pixels[i] == 0 || Float.isNaN(there)) {
						continue;
					}
					float here = image.depth()[y * width + x];
					// A pixel with no solid hit (only glass or plants) is behind anything solid.
					if (Float.isNaN(here) || there + shift < here - HIDES) {
						pixels[i] = 0;
						hidden++;
					}
				}
			}
		}
		if (surfaces != null) {
			hidden += hideBehindGround(d, pixels, surfaces);
		}
		if (hidden == 0) {
			if (craft.cut != null) {
				craft.cut.close();
				craft.cut = null;
			}
			return craft.texture;
		}
		craft.cut = Craft.put(craft.cut, craft.cutEdge == edge, "diorama craft cut " + craft.id, edge, Mipmaps.build(pixels, edge));
		craft.cutEdge = edge;
		return craft.cut;
	}

	/** Which pixels of an image, width first, the world's blocks shade from the sun. */
	record SunMask(int width, int height, java.util.BitSet shaded) {
	}

	/**
	 * What the world shades of a craft where it is now, or null where nothing. Worked out on the
	 * baker thread when the craft has moved a pixel, as its picture doesn't know where it is.
	 */
	private @Nullable SunMask sunMask(Drawn d) {
		Craft craft = d.craft();
		if (craft.sunPending != null && craft.sunPending.isDone()) {
			craft.sunMask = craft.sunPending.getNow(null);
			craft.sunPending = null;
		}
		CraftBaker.Image image = d.image();
		// The chunks whose blocks can shade it, as a snapshot is replaced when its blocks change.
		List<Object> key = new ArrayList<>(List.of(image, d.pu0(), d.pv0(), Math.round(d.depth() * 16)));
		int[] box = craft.box(craft.framePose);
		for (int cx = box[0] - TileBaker.SHADOW_REACH_X >> 4; cx <= box[3] + TileBaker.SHADOW_REACH_X >> 4; cx++) {
			for (int cz = box[2] - TileBaker.SHADOW_REACH_Z >> 4; cz <= box[5] + TileBaker.SHADOW_REACH_Z >> 4; cz++) {
				key.add(System.identityHashCode(this.map.chunk(cx, cz)));
			}
		}
		long nanos = System.nanoTime();
		BlockLooks looks = BlockLooks.get();
		if (craft.sunPending == null && looks != null && !key.equals(craft.sunFor) && nanos - craft.sunAt >= Math.max(SHADOW_GAP, craft.sunCost * IDLE_PER_WORK)) {
			craft.sunFor = key;
			craft.sunAt = nanos;
			int pu0 = d.pu0();
			int pv0 = d.pv0();
			double depth0 = d.depth();
			craft.sunPending = later(() -> {
				long start = System.nanoTime();
				TileBaker baker = TileBaker.forSunChecks(this.map, looks);
				int ppu = Projection.PIXELS_PER_UNIT;
				double sin = Projection.sin();
				double cos = Projection.cos();
				java.util.BitSet shaded = new java.util.BitSet();
				for (int y = 0; y < image.height(); y++) {
					double v = (pv0 + y + 0.5) / ppu;
					for (int x = 0; x < image.width(); x++) {
						int i = y * image.width() + x;
						float depth = image.depth()[i];
						if (Float.isNaN(depth)) {
							continue;
						}
						double world = depth0 + depth;
						if (baker.sunBlocked((pu0 + x + 0.5) / ppu, -v * cos - world * sin, v * sin - world * cos)) {
							shaded.set(i);
						}
					}
				}
				craft.sunCost = System.nanoTime() - start;
				return shaded.isEmpty() ? null : new SunMask(image.width(), image.height(), shaded);
			}, "work out what shades a craft");
		}
		SunMask mask = craft.sunMask;
		return mask != null && mask.width() == image.width() && mask.height() == image.height() ? mask : null;
	}

	/**
	 * Whether any ground could stand between the camera and the craft: false when all of it is
	 * above the ground there, as a tall windmill's sails are, and no pixel needs looking at. The
	 * camera looks north and down, so what covers a craft stands south of it.
	 */
	private boolean mayBeCovered(Craft craft) {
		int[] box = craft.box(craft.framePose);
		double run = Math.min(96, Math.max(0, this.map.topY() - box[1])) * Projection.cos() / Projection.sin();
		for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
			for (int cz = box[2] >> 4; cz <= (int) Math.floor(box[5] + run) >> 4; cz++) {
				ChunkSnapshot chunk = this.map.chunk(cx, cz);
				if (chunk != null && chunk.maxY >= box[1]) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Cuts out the craft's pixels the ground the tiles show is nearer the camera than. Ground a
	 * tile doesn't know yet hides nothing. Returns how many it cut.
	 */
	private static int hideBehindGround(Drawn d, int[] pixels, SurfaceArea ground) {
		CraftBaker.Image image = d.image();
		int hidden = 0;
		for (int y = 0; y < image.height(); y++) {
			for (int x = 0; x < image.width(); x++) {
				int i = y * image.edge() + x;
				float depth = image.depth()[y * image.width() + x];
				float near = ground.near(d.pu0() + x, d.pv0() + y);
				if (pixels[i] == 0 || Float.isNaN(depth) || Float.isNaN(near)) {
					continue;
				}
				if (near < d.depth() + depth - GROUND_HIDES) {
					pixels[i] = 0;
					hidden++;
				}
			}
		}
		return hidden;
	}

	/** Runs a job on the baker thread; null where it fails. */
	private static <T> CompletableFuture<@Nullable T> later(Supplier<@Nullable T> job, String what) {
		return CompletableFuture.supplyAsync(job, BAKER).exceptionally(e -> {
			Diorama.LOGGER.warn("Couldn't {} for the map", what, e);
			return null;
		});
	}

	/** Takes a finished bake, and starts another if the craft has turned or changed since. */
	private void bake(Craft craft, Craft.Inputs inputs, double[] pose) {
		if (craft.pending != null && craft.pending.isDone() && System.nanoTime() < this.uploadsUntil) {
			CraftBaker.Image image = craft.pending.getNow(null);
			craft.pending = null;
			if (image != null) {
				craft.texture = Craft.put(craft.texture, craft.image != null && craft.image.edge() == image.edge(), "diorama craft " + craft.id, image.edge(), image.mips());
				craft.image = image;
			}
		}
		if (craft.pending != null) {
			return;
		}
		CraftBaker.Image image = craft.image;
		CraftBlocks b = inputs.blocks();
		double[] linear = Arrays.copyOfRange(pose, 3, 12);
		double y = pose[1];
		// Height shading changes too little over a few blocks to see.
		boolean turned = image != null && (image.pitchSin() != Projection.sin() || Math.abs(craft.imageY - y) > REBAKE_CLIMB || stretched(image.linear(), linear, b));
		// A craft too big to bake isn't tried again until it changes.
		if (inputs.equals(craft.imageFrom) && (!turned || System.nanoTime() - craft.imageAt < craft.imageCost * IDLE_PER_WORK)) {
			return;
		}
		craft.imageAt = System.nanoTime();
		craft.imageFrom = inputs;
		craft.imageY = y;
		boolean fast = TileBaker.fast();
		craft.pending = later(() -> {
			long start = System.nanoTime();
			CraftBaker.Image baked = CraftBaker.bake(b, linear, y, inputs.looks(), inputs.style(), fast);
			craft.imageCost = System.nanoTime() - start;
			return baked;
		}, "draw a craft");
	}

	/** Whether turning from {@code from} to {@code to} moves any of the craft's corners far enough to show on the map. */
	private static boolean stretched(double[] from, double[] to, CraftBlocks b) {
		double[] w = new double[4];
		double missed = CraftBaker.warp(from, to, b.sizeX, b.sizeY, b.sizeZ, w);
		double radius = 0.5 * Math.sqrt(b.sizeX * b.sizeX + b.sizeY * b.sizeY + b.sizeZ * b.sizeZ);
		return missed + radius * (Math.abs(w[0] - 1) + Math.abs(w[1]) + Math.abs(w[2]) + Math.abs(w[3] - 1)) > REBAKE_MISS;
	}

	/**
	 * Takes a finished shadow, and starts another once the craft has moved or turned, or the
	 * ground under it is better known. Null while there's none to draw, or it's out of the view.
	 */
	private @Nullable Shade shadow(Craft craft, Craft.Inputs inputs, double[] pose, MapViewport.View view) {
		if (craft.shadowPending != null && craft.shadowPending.isDone() && System.nanoTime() < this.uploadsUntil) {
			CraftShadow.Image made = craft.shadowPending.getNow(null);
			craft.shadowPending = null;
			if (made != null) {
				if (made.mips() != null) {
					craft.shadowTexture = Craft.put(craft.shadowTexture, craft.shadowTextureEdge == made.edge(),
						"diorama craft shadow " + craft.id, made.edge(), made.mips());
					craft.shadowTextureEdge = made.edge();
				}
				craft.shadow = made;
			}
		}
		long nanos = System.nanoTime();
		// By its middle, as a spinning rotor's corners move every frame while it stays put.
		double[] middle = Craft.center(pose, inputs.blocks());
		double[] was = craft.shadowAreaMiddle;
		if (nanos - craft.shadowAreaAt > AREA_KEPT || was == null
			|| Math.abs(was[0] - middle[0]) + Math.abs(was[1] - middle[1]) + Math.abs(was[2] - middle[2]) > AREA_MOVE) {
			craft.shadowArea = this.shadowArea(inputs.blocks(), pose, inputs.style());
			craft.shadowAreaMiddle = middle;
			craft.shadowAreaAt = nanos;
		}
		int[] area = craft.shadowArea;
		int ppu = Projection.PIXELS_PER_UNIT;
		if (area == null || !inView(view, (double) area[0] / ppu, (double) area[1] / ppu, (double) area[2] / ppu, (double) area[3] / ppu)) {
			return null;
		}
		SurfaceArea ground = this.map.surfaces(TileKey.SURFACE, area[0], area[1], area[2], area[3]);
		CraftShadow.Image shadow = craft.shadow;
		CraftBlocks b = inputs.blocks();
		double[] linear = Arrays.copyOfRange(pose, 3, 12);
		boolean turned = shadow == null || !inputs.equals(craft.shadowFrom) || shadow.pitchSin() != Projection.sin()
			|| shadow.silhouette() == null || CraftShadow.stale(shadow.silhouette(), linear, b);
		boolean moved = turned || !shadow.ground().same(ground)
			|| Math.abs(shadow.pose()[0] - pose[0]) + Math.abs(shadow.pose()[1] - pose[1]) + Math.abs(shadow.pose()[2] - pose[2]) > SHADOW_STEP;
		if (moved && craft.shadowPending == null && nanos - craft.shadowAt >= Math.max(SHADOW_GAP, craft.shadowCost * IDLE_PER_WORK)) {
			craft.shadowAt = nanos;
			// The craft as the sun sees it only changes when it turns.
			CraftShadow.Silhouette kept = turned ? null : shadow.silhouette();
			double[] at = pose.clone();
			craft.shadowFrom = inputs;
			craft.shadowPending = later(() -> {
				long start = System.nanoTime();
				CraftShadow.Silhouette silhouette = kept != null ? kept : CraftShadow.silhouette(b, linear, inputs.looks(), inputs.style());
				CraftShadow.Image cast = CraftShadow.cast(silhouette, at, area[0], area[1], area[2] - area[0], area[3] - area[1], ground);
				craft.shadowCost = System.nanoTime() - start;
				return cast;
			}, "make a craft's shadow");
		}
		if (shadow == null || shadow.mips() == null || craft.shadowTexture == null) {
			return null;
		}
		// Moved since it was made: it slides across the ground with the craft, and away from the
		// sun as it climbs, until the next one is ready.
		double[] now = craft.center(pose);
		double[] then = craft.center(shadow.pose());
		double run = (now[1] - then[1]) / Math.tan(Math.toRadians(inputs.style().sunElevation()));
		double dx = now[0] - then[0] - TileBaker.SUN_AZIMUTH_X * run;
		double dz = now[2] - then[2] - TileBaker.SUN_AZIMUTH_Z * run;
		return new Shade(craft, (double) shadow.pu0() / ppu + dx, (double) shadow.pv0() / ppu + dz * Projection.sin());
	}

	/**
	 * The map pixels a craft's shadow may cover, as {pu0, pv0, pu1, pv1}, or null where the map
	 * knows no ground. How far the shadow reaches depends on how low the ground is, so that's
	 * found from the chunks first, around where the shadow would fall.
	 */
	private int @Nullable [] shadowArea(CraftBlocks blocks, double[] pose, MapStyle style) {
		double[] c = Craft.center(pose, blocks);
		int middle = this.surface((int) Math.floor(c[0]), (int) Math.floor(c[2]));
		if (middle == Integer.MIN_VALUE) {
			return null;
		}
		int[] reach = CraftShadow.reach(blocks, pose, middle, style);
		int lowest = middle;
		for (int z = reach[1]; z <= reach[3]; z += 2) {
			for (int x = reach[0]; x <= reach[2]; x += 2) {
				int top = this.surface(x, z);
				if (top != Integer.MIN_VALUE) {
					lowest = Math.min(lowest, top);
				}
			}
		}
		if (lowest < middle) {
			reach = CraftShadow.reach(blocks, pose, lowest, style);
		}
		int ppu = Projection.PIXELS_PER_UNIT;
		double sin = Projection.sin();
		double cos = Projection.cos();
		double top = blocks.box(pose)[4];
		int[] area = {
			reach[0] * ppu,
			(int) Math.floor((reach[1] * sin - top * cos) * ppu),
			(reach[2] + 1) * ppu,
			(int) Math.ceil(((reach[3] + 1) * sin - lowest * cos) * ppu),
		};
		return area[2] - area[0] > CraftBaker.MAX_PIXELS || area[3] - area[1] > CraftBaker.MAX_PIXELS ? null : area;
	}

	private int surface(int x, int z) {
		ChunkSnapshot chunk = this.map.chunk(x >> 4, z >> 4);
		return chunk == null ? Integer.MIN_VALUE : chunk.surface(x, z);
	}
}
