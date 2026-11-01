package dev.diorama.render.gpu;

import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.GpuMesher;
import dev.diorama.bake.TileBaker;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws map tiles on the GPU from {@link GpuMesher} meshes instead of tracing them: an
 * experiment, on with {@code DIORAMA_GPU_BAKE=1}.
 *
 * <p>A bake is a few passes on the render thread:
 * <ol>
 *   <li>sky occlusion at every face corner, into a texture: the tracer's rays against the solid
 *   blocks around the tile, which the mesher packs;</li>
 *   <li>a shadow map from the sun;</li>
 *   <li>the solid faces into a colour and depth target twice the tile's size, so each pixel gets
 *   four samples like the tracer's, with a second target for overlay bits and what the
 *   composite needs to know about each sample's surface;</li>
 *   <li>plants and glass added up near to far into their own target;</li>
 *   <li>the water's surface into its own target;</li>
 *   <li>a composite that tints by the plants and glass, mixes water by depth, averages the
 *   samples, draws outlines and grades the colour into the tile's pixels.</li>
 * </ol>
 * Those are copied back to the CPU, which takes a frame or two, and handed to the caller as the
 * same ABGR ints a traced bake makes, with the overlay bits when asked for. This class holds what
 * every Minecraft version shares: the queue, the frame budget and the packing; a
 * {@link BakeBackend} does the drawing.
 */
public final class GpuTileBaker {
	/**
	 * The share of frame time bakes may take, on the render thread and on the GPU each. Bakes are
	 * paid for out of a budget that grows by this share of every frame's length, at what bakes
	 * have been measured to cost, so a backlog spreads over frames instead of landing on a few.
	 */
	private static final double SHARE = Math.max(0.01, Math.min(1, Double.parseDouble(System.getProperty("diorama.gpuBakeShare", "0.1"))));
	/**
	 * The same for background tiles, which nobody is waiting for: they only draw with no other tile
	 * queued, out of a smaller budget of their own, so a long fill doesn't weigh on the game.
	 */
	private static final double BACKGROUND_SHARE = Math.max(0.001, Math.min(1, Double.parseDouble(System.getProperty("diorama.gpuBackgroundShare", "0.03"))));
	/** About what the tracer takes over a tile. */
	private static final double TRACE_NANOS = 1e9;
	/** The tile plus a pixel all round, which outlines compare against, at two samples each way. */
	static final int SCENE = (Projection.TILE_PIXELS + 2) * 2;
	static final int SHADOW_SIZE = 2048;
	/** The sky texture holds a slot per face corner; see {@code bake.vsh}. */
	static final int SKY_WIDTH = 2048;
	static final int SKY_HEIGHT = 1024;
	/** The voxel texture holds the mesher's packed solid bits, a word per texel. */
	static final int VOXEL_WIDTH = 4096;
	static final int VOXEL_HEIGHT = 512;
	/** A face: cell and face (4 shorts), colour, occlusion, light (4 bytes each), sky slot, box (2 ints), haze (4 ints). */
	static final int FACE_BYTES = 48;
	/** A shadow caster: cell and face (4 shorts), box (2 ints). */
	static final int CASTER_BYTES = 16;
	/** The uniform block, {@code include/bake.glsl}: eleven vec4s, four jitters, the mode, two ivec4s, 13 sky rays, two vec4s for under a roof. */
	static final int UBO_SIZE = (11 + 4 + 1 + 2 + 13 + 2) * 16;

	/** Set when the first tile is queued: until then frames don't touch the GPU code at all. */
	private static volatile boolean used;

	/** Made on the first frame after a tile is queued, so a session without GPU baking never sets one up. */
	private static final class Backend {
		//? if >=26.2 {
		// Raw OpenGL can be picked here too, on the OpenGL backend, to try it where the game is quick to run.
		static final BakeBackend INSTANCE = "gl".equals(System.getProperty("diorama.gpuBakeBackend", System.getenv("DIORAMA_GPU_BAKE_BACKEND")))
			? new GlBakeBackend()
			: new BlazeBakeBackend();
		//?} else {
		/*static final BakeBackend INSTANCE = new GlBakeBackend();
		*///?}
	}

	private static final ConcurrentLinkedQueue<Job> QUEUE = new ConcurrentLinkedQueue<>();
	private static final ConcurrentLinkedQueue<Job> BACKGROUND = new ConcurrentLinkedQueue<>();
	/** Jobs drawn and waiting for their pixels to come back. */
	private static final java.util.Set<Job> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// The bake budget, in nanoseconds of frame time, and the measured cost of a bake.
	private static long lastFrame;
	private static double budget;
	private static double backgroundBudget;
	private static volatile double renderCost = 1e6;
	private static volatile double gpuCost = 1e6;

	private GpuTileBaker() {
	}

	/** What a bake cost, for the experiment's numbers. Times are nanoseconds, -1 where not known. */
	public record Timing(long meshNanos, long packNanos, long recordNanos, long gpuNanos, long latencyNanos, int faces, int casters, int bytes) {
	}

	/**
	 * {@code info} holds the {@code Overlays} bits per pixel when the mesh asked for them. Null
	 * pixels mean the GPU didn't draw it: it couldn't, or, when {@code declined}, it never tried,
	 * because the world was closing or the tile has more faces than its textures hold.
	 */
	public record Result(int @Nullable [] pixels, byte @Nullable [] info, Timing timing, boolean declined, MapStyle look) {
	}

	/**
	 * Packs a mesh for the GPU and queues it; any thread. {@code done} gets the pixels on the
	 * render thread, or null pixels if the GPU couldn't draw it.
	 */
	public static void submit(GpuMesher.Mesh mesh, Consumer<Result> done) {
		QUEUE.add(new Job(mesh, done));
		used = true;
	}

	/**
	 * The same for a tile nobody is waiting for; see {@link #BACKGROUND_SHARE}. The returned task
	 * takes it back if it hasn't been drawn yet, handing the caller a null result.
	 */
	public static Runnable submitBackground(GpuMesher.Mesh mesh, Consumer<Result> done) {
		Job job = new Job(mesh, done);
		BACKGROUND.add(job);
		used = true;
		return () -> {
			if (BACKGROUND.remove(job)) {
				job.cancel();
			}
		};
	}

	/**
	 * Whether this GPU's background budget gets through a tile about as soon as the tracer would.
	 * On a slow GPU it doesn't: a tile that costs the frames too much takes its budget many seconds
	 * to save up.
	 */
	public static boolean keepsUpInBackground() {
		return cost() / BACKGROUND_SHARE < TRACE_NANOS;
	}

	/**
	 * Fails every job not finished yet, queued or drawn, so their callers fall back at once. For
	 * waits on the render thread, which the GPU's results need to arrive. A result that still
	 * arrives later is dropped.
	 */
	public static void cancelAll() {
		Job job;
		while ((job = QUEUE.poll()) != null) {
			job.cancel();
		}
		while ((job = BACKGROUND.poll()) != null) {
			job.cancel();
		}
		for (Job pending : PENDING) {
			pending.cancel();
		}
	}

	/** The graphics card and driver the bakes run on, for the log. */
	public static String device() {
		String device = used ? Backend.INSTANCE.device() : null;
		return device == null ? "a GPU not set up yet" : device;
	}

	/** Draws queued tiles. Call on the render thread at the start of a frame, outside any pass. */
	public static void frame() {
		if (!used) {
			return;
		}
		Backend.INSTANCE.poll();
		long now = System.nanoTime();
		if (lastFrame != 0) {
			// Never more than a few bakes' worth saved up, so a long pause doesn't buy a burst.
			budget = Math.min(budget + SHARE * (now - lastFrame), 3 * cost());
			backgroundBudget = Math.min(backgroundBudget + BACKGROUND_SHARE * (now - lastFrame), cost());
		}
		lastFrame = now;
		while (budget >= cost()) {
			Job job = QUEUE.poll();
			if (job == null) {
				break;
			}
			if (draw(job)) {
				// Only a drawn bake spends the budget, so tiles this GPU can't draw reach the tracer at once.
				budget -= cost();
			}
		}
		if (!QUEUE.isEmpty()) {
			return;
		}
		while (backgroundBudget >= cost()) {
			Job job = BACKGROUND.poll();
			if (job == null) {
				return;
			}
			if (draw(job)) {
				backgroundBudget -= cost();
			}
		}
	}

	/** Draws a job, or hands it back for tracing; returns whether the GPU drew anything. */
	private static boolean draw(Job job) {
		try {
			if (job.mesh.empty) {
				// Rays start below the view's limit: the tracer leaves every pixel unknown.
				job.done(new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS], job.mesh.overlays == null ? null : new byte[Projection.TILE_PIXELS * Projection.TILE_PIXELS], 0, 0);
				job.free();
			} else if (job.mesh.traceInstead
				|| job.voxels != null && (job.voxelRows > VOXEL_HEIGHT || (long) (job.opaqueCount + job.waterCount) * 4 > (long) SKY_WIDTH * SKY_HEIGHT)) {
				// Something the GPU doesn't draw as the tracer does, or more than the textures that
				// hold its sky occlusion take: trace it. Not the GPU's fault.
				job.cancel();
			} else if (Backend.INSTANCE.draw(job)) {
				return true;
			} else {
				// This GPU can't draw it: trace it.
				job.fail();
			}
		} catch (Throwable t) {
			Diorama.LOGGER.error("GPU bake of {} failed", job.mesh.key, t);
			job.fail();
		}
		return false;
	}

	/** What the next bake is expected to cost, in the dearer of render-thread and GPU time. */
	private static double cost() {
		return Math.max(renderCost, gpuCost);
	}

	/** A backend's word on how long recording a bake took on the render thread. */
	static void recorded(long nanos) {
		renderCost = renderCost * 0.9 + nanos * 0.1;
	}

	private static final AtomicLong STAT_COUNT = new AtomicLong();
	private static final AtomicLong STAT_MESH = new AtomicLong();
	private static final AtomicLong STAT_PACK = new AtomicLong();
	private static final AtomicLong STAT_RECORD = new AtomicLong();
	private static final AtomicLong STAT_GPU = new AtomicLong();
	private static final AtomicLong STAT_LATENCY = new AtomicLong();

	private static void log(Timing timing) {
		long n = STAT_COUNT.incrementAndGet();
		STAT_MESH.addAndGet(timing.meshNanos());
		STAT_PACK.addAndGet(timing.packNanos());
		STAT_RECORD.addAndGet(timing.recordNanos());
		STAT_GPU.addAndGet(Math.max(0, timing.gpuNanos()));
		STAT_LATENCY.addAndGet(timing.latencyNanos());
		if (n % 25 == 0) {
			Diorama.LOGGER.info(
				"GPU baked {} tiles; average ms: mesh {}, pack {}, render thread {}, GPU {}, submit to pixels {}",
				n, ms(STAT_MESH, n), ms(STAT_PACK, n), ms(STAT_RECORD, n), ms(STAT_GPU, n), ms(STAT_LATENCY, n)
			);
		}
	}

	private static String ms(AtomicLong total, long n) {
		return String.format(java.util.Locale.ROOT, "%.2f", total.get() / (double) n / 1e6);
	}

	/** One tile on its way: its packed faces and uniforms, made on the thread that submitted it. */
	static final class Job {
		final GpuMesher.Mesh mesh;
		final Consumer<Result> callback;
		/** The look the uniforms were built with, so a check can tell if it changed under it. */
		MapStyle look;
		final long submitted = System.nanoTime();
		/** Set by the first of done or fail; the other is then ignored. */
		final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
		final long packNanos;
		final int opaqueCount;
		final int tintedCount;
		final int waterCount;
		final int casterCount;
		/** Rows of the voxel texture this bake fills. */
		final int voxelRows;
		@Nullable ByteBuffer faces;
		@Nullable ByteBuffer casters;
		@Nullable ByteBuffer uniforms;
		/** Whole rows of the voxel texture, or null in views without sky occlusion. */
		@Nullable ByteBuffer voxels;

		Job(GpuMesher.Mesh mesh, Consumer<Result> callback) {
			this.mesh = mesh;
			this.callback = callback;
			long start = System.nanoTime();
			this.opaqueCount = mesh.opaque.count;
			this.tintedCount = mesh.tinted.count;
			this.waterCount = mesh.water.count;
			this.casterCount = mesh.casters.count;
			int originX = mesh.originX;
			int originZ = mesh.originZ;
			// Sky slots: the opaque faces first, then the water; plants and glass don't use them.
			boolean sky = mesh.voxels != null;
			ByteBuffer faces = MemoryUtil.memAlloc(Math.max(1, this.faceCount() * FACE_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
			putFaces(faces, mesh.opaque, originX, originZ, sky ? 0 : -1);
			putFaces(faces, mesh.tinted, originX, originZ, -1);
			putFaces(faces, mesh.water, originX, originZ, sky ? this.opaqueCount : -1);
			faces.flip();
			this.faces = faces;
			ByteBuffer casters = MemoryUtil.memAlloc(Math.max(1, this.casterCount * CASTER_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
			int[] d = mesh.casters.data;
			for (int q = 0; q < this.casterCount; q++) {
				int i = q * GpuMesher.STRIDE;
				casters.putShort((short) (d[i] - originX)).putShort((short) d[i + 1]).putShort((short) (d[i + 2] - originZ)).putShort((short) d[i + 3]);
				casters.putInt(d[i + 8]).putInt(d[i + 9]);
			}
			casters.flip();
			this.casters = casters;
			this.look = MapStyle.get();
			this.uniforms = uniforms(mesh, originX, originZ, this.look);
			if (sky) {
				// Whole rows, as the texture upload takes them.
				this.voxelRows = (mesh.voxelCount + VOXEL_WIDTH - 1) / VOXEL_WIDTH;
				ByteBuffer voxels = MemoryUtil.memCalloc(Math.max(1, this.voxelRows) * VOXEL_WIDTH * 4).order(ByteOrder.LITTLE_ENDIAN);
				voxels.asIntBuffer().put(mesh.voxels, 0, mesh.voxelCount);
				this.voxels = voxels;
			} else {
				this.voxelRows = 0;
			}
			this.packNanos = System.nanoTime() - start;
		}

		int faceCount() {
			return this.opaqueCount + this.tintedCount + this.waterCount;
		}

		/** {@code firstSky} is the first face's sky slot, the rest following on; -1 for none. */
		private static void putFaces(ByteBuffer out, GpuMesher.Quads quads, int originX, int originZ, int firstSky) {
			int[] d = quads.data;
			for (int q = 0; q < quads.count; q++) {
				int i = q * GpuMesher.STRIDE;
				out.putShort((short) (d[i] - originX)).putShort((short) d[i + 1]).putShort((short) (d[i + 2] - originZ)).putShort((short) (d[i + 3] | d[i + 7] << 4));
				int color = d[i + 4];
				out.put((byte) (color >> 16)).put((byte) (color >> 8)).put((byte) color).put((byte) (color >>> 24));
				int ao = d[i + 5];
				out.put((byte) (ao >>> 24)).put((byte) (ao >> 16)).put((byte) (ao >> 8)).put((byte) ao);
				int light = d[i + 6];
				out.put((byte) (light >>> 24)).put((byte) (light >> 16)).put((byte) (light >> 8)).put((byte) light);
				out.putInt(firstSky < 0 ? -1 : firstSky + q);
				out.putInt(d[i + 8]).putInt(d[i + 9]);
				out.putInt(d[i + 10]).putInt(d[i + 11]).putInt(d[i + 12]).putInt(d[i + 13]);
			}
		}

		/** The bake's uniform block: see {@code include/bake.glsl}. Every member is a 16-byte slot in std140. */
		private static ByteBuffer uniforms(GpuMesher.Mesh mesh, int originX, int originZ, MapStyle style) {
			double sin = mesh.sin;
			double cos = mesh.cos;
			double minV = mesh.key.minV();
			double maxV = minV + Projection.TILE_UNITS;
			int minY = mesh.minY;
			// Camera depth t = -(y + v cos) / sin: smallest at the top of the tile's south edge.
			double tNear = -(mesh.topY + 2 + maxV * cos) / sin;
			double tFar = -(minY - 2 + minV * cos) / sin;

			double elevation = Math.toRadians(style.sunElevation());
			double lx = Math.cos(elevation) * TileBaker.SUN_AZIMUTH_X;
			double ly = Math.sin(elevation);
			double lz = Math.cos(elevation) * TileBaker.SUN_AZIMUTH_Z;
			double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
			lx /= ll;
			ly /= ll;
			lz /= ll;
			// Axes across the sun: one level, one up its face.
			double ux = -lz;
			double uz = lx;
			double ul = Math.sqrt(ux * ux + uz * uz);
			ux /= ul;
			uz /= ul;
			// v = u x l, up the sun's face.
			double vx = -uz * ly;
			double vy = uz * lx - ux * lz;
			double vz = ux * ly;
			double[] uRange = {Double.MAX_VALUE, -Double.MAX_VALUE};
			double[] vRange = {Double.MAX_VALUE, -Double.MAX_VALUE};
			double[] dRange = {Double.MAX_VALUE, -Double.MAX_VALUE};
			int x0 = mesh.casterX0 - originX;
			int x1 = mesh.casterX1 - originX;
			int z0 = mesh.casterZ0 - originZ;
			int z1 = mesh.casterZ1 - originZ;
			int y0 = mesh.casterY0 - 1;
			int y1 = Math.max(mesh.casterY1, mesh.topY) + 1;
			for (int c = 0; c < 8; c++) {
				double px = (c & 1) == 0 ? x0 : x1;
				double py = (c & 2) == 0 ? y0 : y1;
				double pz = (c & 4) == 0 ? z0 : z1;
				extend(uRange, px * ux + pz * uz);
				extend(vRange, px * vx + py * vy + pz * vz);
				extend(dRange, px * lx + py * ly + pz * lz);
			}
			double uExtent = uRange[1] - uRange[0];
			double vExtent = vRange[1] - vRange[0];
			double texel = Math.max(uExtent, vExtent) / SHADOW_SIZE;

			ByteBuffer out = MemoryUtil.memAlloc(UBO_SIZE).order(ByteOrder.LITTLE_ENDIAN);
			vec4(out, sin, cos, originZ * sin - minV, -cos * originZ - tNear);
			vec4(out, 0, tFar - tNear, 0, 0);
			vec4(out, lx, ly, lz, TileBaker.SHADOW_DISTANCE);
			vec4(out, ux, 0, uz, -uRange[0]);
			vec4(out, vx, vy, vz, -vRange[0]);
			vec4(out, 2 / uExtent, 2 / vExtent, dRange[0] - 1, dRange[1] + 1);
			if (mesh.lifted) {
				// Under a roof the light from above is TileBaker's own, not the style's day.
				vec4(out, TileBaker.LID_AMBIENT, TileBaker.LID_KEY, style.skyOcclusion(), 0);
				vec4(out, TileBaker.LID_KEY_TINT[0], TileBaker.LID_KEY_TINT[1], TileBaker.LID_KEY_TINT[2], 0);
				vec4(out, TileBaker.LID_FILL_TINT[0], TileBaker.LID_FILL_TINT[1], TileBaker.LID_FILL_TINT[2], 0);
			} else {
				vec4(out, style.ambient(), style.sunStrength(), style.skyOcclusion(), style.heightShade());
				vec4(out, style.sunTint()[0], style.sunTint()[1], style.sunTint()[2], 0);
				vec4(out, style.shadowTint()[0], style.shadowTint()[1], style.shadowTint()[2], 0);
			}
			vec4(out, style.saturation(), style.exposure(), style.outline(), style.clearWater() ? 1 : 0);
			vec4(out, 1.5 * texel + 0.02, style.cornerOcclusion(), 0, 0);
			for (double[] j : TileBaker.SUN_JITTER) {
				vec4(out, j[0], j[1], j[2], 0);
			}
			vec4(out, mesh.lit ? 1 : 0, mesh.cave ? 1 : 0, mesh.topY, mesh.lifted ? 1 : 0);
			out.putInt(mesh.voxelOrigin[0]).putInt(mesh.voxelOrigin[1]).putInt(mesh.voxelOrigin[2]).putInt(mesh.rayTop);
			out.putInt(mesh.voxelSize[0]).putInt(mesh.voxelSize[1]).putInt(mesh.voxelSize[2]).putInt(0);
			for (int r = 0; r < TileBaker.SKY_RAYS.length; r++) {
				double[] d = TileBaker.SKY_RAYS[r];
				vec4(out, d[0], d[1], d[2], TileBaker.SKY_WEIGHTS[r]);
			}
			vec4(out, mesh.roof - TileBaker.HAZE_START, TileBaker.HAZE_DEPTH, TileBaker.HAZE_MAX, TileBaker.LID_GLOW);
			vec4(out, TileBaker.HAZE_LIGHT, TileBaker.HAZE_MAX_LUMINANCE, 0, 0);
			out.flip();
			return out;
		}

		private static void vec4(ByteBuffer out, double x, double y, double z, double w) {
			out.putFloat((float) x).putFloat((float) y).putFloat((float) z).putFloat((float) w);
		}

		private static void extend(double[] range, double value) {
			range[0] = Math.min(range[0], value);
			range[1] = Math.max(range[1], value);
		}

		/** Drawn: waiting for the pixels. */
		void pending() {
			PENDING.add(this);
		}

		void done(int[] pixels, byte @Nullable [] info, long recordNanos, long gpuNanos) {
			PENDING.remove(this);
			if (!this.completed.compareAndSet(false, true)) {
				return;
			}
			if (gpuNanos > 0) {
				gpuCost = gpuCost * 0.9 + gpuNanos * 0.1;
			}
			Timing timing = new Timing(
				this.mesh.buildNanos, this.packNanos, recordNanos, gpuNanos, System.nanoTime() - this.submitted,
				this.faceCount(), this.casterCount, this.faceCount() * FACE_BYTES + this.casterCount * CASTER_BYTES
			);
			log(timing);
			this.callback.accept(new Result(pixels, info, timing, false, this.look));
		}

		void fail() {
			this.giveBack(false);
		}

		/** Hands the job back undrawn because the world is closing or it's too big, not because the GPU failed. */
		void cancel() {
			this.giveBack(true);
		}

		private void giveBack(boolean declined) {
			PENDING.remove(this);
			if (!this.completed.compareAndSet(false, true)) {
				return;
			}
			this.free();
			this.callback.accept(new Result(null, null, new Timing(this.mesh.buildNanos, this.packNanos, -1, -1, -1, this.faceCount(), this.casterCount, 0), declined, this.look));
		}

		void free() {
			if (this.faces != null) {
				MemoryUtil.memFree(this.faces);
				this.faces = null;
			}
			if (this.casters != null) {
				MemoryUtil.memFree(this.casters);
				this.casters = null;
			}
			if (this.uniforms != null) {
				MemoryUtil.memFree(this.uniforms);
				this.uniforms = null;
			}
			if (this.voxels != null) {
				MemoryUtil.memFree(this.voxels);
				this.voxels = null;
			}
		}
	}
}
