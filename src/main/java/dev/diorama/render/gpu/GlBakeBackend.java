package dev.diorama.render.gpu;

import static dev.diorama.render.gpu.GpuTileBaker.FACE_BYTES;
import static dev.diorama.render.gpu.GpuTileBaker.CASTER_BYTES;
import static dev.diorama.render.gpu.GpuTileBaker.SCENE;
import static dev.diorama.render.gpu.GpuTileBaker.SHADOW_SIZE;
import static dev.diorama.render.gpu.GpuTileBaker.SKY_HEIGHT;
import static dev.diorama.render.gpu.GpuTileBaker.SKY_WIDTH;
import static dev.diorama.render.gpu.GpuTileBaker.VOXEL_HEIGHT;
import static dev.diorama.render.gpu.GpuTileBaker.VOXEL_WIDTH;
import static org.lwjgl.opengl.GL33C.*;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

/**
 * Bakes with OpenGL 3.3 directly, for the versions whose own GPU layer can't express a bake (no
 * second colour target, half-float targets or per-instance data before 26.2). It owns
 * everything it touches: textures, framebuffers, vertex arrays, buffers and queries, and the
 * same shaders, compiled here rather than through vanilla's shader loading.
 *
 * <p>Vanilla keeps a copy of some GL state and skips calls its copy says are done, so that
 * state changes only through {@link GlState}; the rest is saved before a bake and put back
 * after. Pixels come back through pixel buffers with a fence, picked up by {@link #poll}.
 */
final class GlBakeBackend implements BakeBackend {
	private static final String[] ATTRIBUTES = {"Corner", "Cell", "Color", "Occlusion", "FaceLight", "SkyIndex", "Box", "Haze"};
	/** Well clear of the binding points vanilla uses. */
	private static final int UBO_BINDING = 30;
	/** Texture units 0..5; vanilla's copy covers units 0..11. */
	private static final int UNITS = 6;
	// Also the LOC(n) macro 26.3's shaders define, which is spelled with a letter.
	private static final Pattern LOCATION = Pattern.compile("layout\\(location = \\w+\\) ?");
	private static final Pattern OUTPUT = Pattern.compile("layout\\(location = (\\d+)\\) out \\w+ (\\w+);");

	/** Null until tried; then whether this GL can bake. */
	private @Nullable Boolean usable;
	private int skyProgram;
	private int shadowProgram;
	private int opaqueProgram;
	private int tintedProgram;
	private int waterProgram;
	private int compositeProgram;
	private int shadowTexture;
	private int sceneColor;
	private int sceneAux;
	private int sceneDepth;
	private int tint;
	private int waterColor;
	private int waterDepth;
	private int sky;
	private int voxels;
	private int output;
	private int outputInfo;
	private int skyFbo;
	private int shadowFbo;
	private int sceneFbo;
	private int tintFbo;
	private int waterFbo;
	private int outputFbo;
	private int faceVao;
	private int casterVao;
	private int emptyVao;
	private int cornerVbo;
	private int indexBuffer;
	private int faceVbo;
	private int casterVbo;
	private int ubo;
	private boolean timestamps;
	private @Nullable String device;
	/**
	 * Up to 1.21.8 the game asks for OpenGL 3.2, so what the bake needs from 3.3 is looked for one
	 * by one: drivers usually offer it anyway, or the extension it came from.
	 */
	private static boolean coreDivisor;
	private static boolean samplerObjects;
	private final ArrayDeque<Readback> readbacks = new ArrayDeque<>();

	/** A bake's pixels on their way back. */
	private record Readback(GpuTileBaker.Job job, int colors, int info, long fence, int startQuery, int endQuery, long recordNanos) {
	}

	@Override
	public boolean draw(GpuTileBaker.Job job) {
		if (!this.ready()) {
			return false;
		}
		long start = System.nanoTime();
		Saved saved = Saved.take();
		// Made here and handed to the readback; deleted here if the draw fails before that.
		int startQuery = 0;
		int colors = 0;
		int info = 0;
		int endQuery = 0;
		boolean queued = false;
		try {
			startQuery = this.timestamps ? glGenQueries() : 0;
			if (startQuery != 0) {
				glQueryCounter(startQuery, GL_TIMESTAMP);
			}
			GlState.noCullNoScissor();
			GlState.colorMaskAll();
			GlState.blend(false);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

			glBindBuffer(GL_UNIFORM_BUFFER, this.ubo);
			glBufferData(GL_UNIFORM_BUFFER, job.uniforms, GL_STREAM_DRAW);
			glBindBufferBase(GL_UNIFORM_BUFFER, UBO_BINDING, this.ubo);
			glBindBuffer(GL_ARRAY_BUFFER, this.faceVbo);
			glBufferData(GL_ARRAY_BUFFER, job.faces, GL_STREAM_DRAW);
			glBindBuffer(GL_ARRAY_BUFFER, this.casterVbo);
			glBufferData(GL_ARRAY_BUFFER, job.casters, GL_STREAM_DRAW);
			GlState.forgetBuffers();

			int skyFaces = job.opaqueCount + job.waterCount;
			if (job.voxels != null && skyFaces > 0) {
				this.bindTextures(this.voxels);
				Saved.plainPixelStore();
				glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, VOXEL_WIDTH, job.voxelRows, GL_RED_INTEGER, GL_UNSIGNED_INT, job.voxels);
				this.target(this.skyFbo, SKY_WIDTH, SKY_HEIGHT);
				GlState.depthTest(false);
				GlState.useProgram(this.skyProgram);
				GlState.bindVertexArray(this.faceVao);
				if (job.opaqueCount > 0) {
					this.pointFaces(0);
					glDrawArraysInstanced(GL_POINTS, 0, 4, job.opaqueCount);
				}
				if (job.waterCount > 0) {
					this.pointFaces(job.opaqueCount + job.tintedCount);
					glDrawArraysInstanced(GL_POINTS, 0, 4, job.waterCount);
				}
			}

			this.target(this.shadowFbo, SHADOW_SIZE, SHADOW_SIZE);
			this.clearDepth();
			if (job.casterCount > 0) {
				this.depth(true);
				GlState.useProgram(this.shadowProgram);
				GlState.bindVertexArray(this.casterVao);
				glDrawElementsInstanced(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0, job.casterCount);
			}

			this.target(this.sceneFbo, SCENE, SCENE);
			this.clearColor(0, 0.01F, 0.01F, 0.015F, 1);
			this.clearColor(1, 0, 0, 0, 0);
			this.clearDepth();
			this.bindTextures(this.shadowTexture, this.sky, this.voxels);
			GlState.bindVertexArray(this.faceVao);
			if (job.opaqueCount > 0) {
				this.depth(true);
				GlState.useProgram(this.opaqueProgram);
				this.pointFaces(0);
				glDrawElementsInstanced(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0, job.opaqueCount);
			}

			// Plants and glass, near to far: each adds its colour times what the ones in front let
			// through (the target's alpha), and lets through less behind it.
			this.target(this.tintFbo, SCENE, SCENE);
			this.clearColor(0, 0, 0, 0, 1);
			if (job.tintedCount > 0) {
				this.depth(false);
				GlState.blend(true);
				GlState.blendFuncSeparate(GL_DST_ALPHA, GL_ONE, GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
				GlState.useProgram(this.tintedProgram);
				this.pointFaces(job.opaqueCount);
				glDrawElementsInstanced(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0, job.tintedCount);
				GlState.blend(false);
			}

			this.target(this.waterFbo, SCENE, SCENE);
			this.clearColor(0, 0, 0, 0, 0);
			this.clearDepth();
			if (job.waterCount > 0) {
				this.depth(true);
				GlState.useProgram(this.waterProgram);
				this.pointFaces(job.opaqueCount + job.tintedCount);
				glDrawElementsInstanced(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0, job.waterCount);
			}

			int size = Projection.TILE_PIXELS;
			this.target(this.outputFbo, size, size);
			GlState.depthTest(false);
			GlState.useProgram(this.compositeProgram);
			this.bindTextures(this.sceneColor, this.sceneDepth, this.sceneAux, this.tint, this.waterColor, this.waterDepth);
			GlState.bindVertexArray(this.emptyVao);
			glDrawArrays(GL_TRIANGLES, 0, 3);

			// Into pixel buffers; the fence says when they're there.
			GlState.bindFramebuffer(GL_READ_FRAMEBUFFER, this.outputFbo);
			colors = glGenBuffers();
			glBindBuffer(GL_PIXEL_PACK_BUFFER, colors);
			glBufferData(GL_PIXEL_PACK_BUFFER, (long) size * size * 4, GL_STREAM_READ);
			Saved.plainPixelStore();
			glReadBuffer(GL_COLOR_ATTACHMENT0);
			glReadPixels(0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
			if (job.mesh.overlays != null) {
				info = glGenBuffers();
				glBindBuffer(GL_PIXEL_PACK_BUFFER, info);
				glBufferData(GL_PIXEL_PACK_BUFFER, (long) size * size, GL_STREAM_READ);
				glPixelStorei(GL_PACK_ALIGNMENT, 1);
				glReadBuffer(GL_COLOR_ATTACHMENT1);
				glReadPixels(0, 0, size, size, GL_RED, GL_UNSIGNED_BYTE, 0L);
				glReadBuffer(GL_COLOR_ATTACHMENT0);
			}
			if (startQuery != 0) {
				endQuery = glGenQueries();
				glQueryCounter(endQuery, GL_TIMESTAMP);
			}
			long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
			long recordNanos = System.nanoTime() - start;
			this.readbacks.add(new Readback(job, colors, info, fence, startQuery, endQuery, recordNanos));
			queued = true;
			job.pending();
			GpuTileBaker.recorded(recordNanos);
		} finally {
			if (!queued) {
				for (int buffer : new int[] {colors, info}) {
					if (buffer != 0) {
						glDeleteBuffers(buffer);
					}
				}
				for (int query : new int[] {startQuery, endQuery}) {
					if (query != 0) {
						glDeleteQueries(query);
					}
				}
			}
			saved.restore();
			job.free();
		}
		return true;
	}

	@Override
	public @Nullable String device() {
		if (this.device == null) {
			this.device = glGetString(GL_RENDERER) + " (" + glGetString(GL_VENDOR) + ", OpenGL " + glGetString(GL_VERSION) + ")";
		}
		return this.device;
	}

	@Override
	public void poll() {
		if (this.readbacks.isEmpty()) {
			return;
		}
		int pack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
		try {
			while (!this.readbacks.isEmpty()) {
				Readback r = this.readbacks.peek();
				int status = glClientWaitSync(r.fence, 0, 0);
				if (status == GL_TIMEOUT_EXPIRED) {
					return;
				}
				this.readbacks.poll();
				glDeleteSync(r.fence);
				if (status == GL_WAIT_FAILED) {
					this.free(r);
					r.job.fail();
					continue;
				}
				try {
					int size = Projection.TILE_PIXELS;
					int[] pixels = new int[size * size];
					glBindBuffer(GL_PIXEL_PACK_BUFFER, r.colors);
					ByteBuffer mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (long) size * size * 4, GL_MAP_READ_BIT);
					if (mapped == null) {
						throw new IllegalStateException("couldn't map the tile's pixels");
					}
					mapped.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
					glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
					byte[] info = null;
					if (r.info != 0) {
						glBindBuffer(GL_PIXEL_PACK_BUFFER, r.info);
						ByteBuffer bits = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (long) size * size, GL_MAP_READ_BIT);
						if (bits == null) {
							throw new IllegalStateException("couldn't map the tile's overlay bits");
						}
						info = new byte[size * size];
						bits.get(info);
						glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
					}
					long gpu = -1;
					if (r.startQuery != 0 && glGetQueryObjecti(r.endQuery, GL_QUERY_RESULT_AVAILABLE) != 0) {
						gpu = glGetQueryObjecti64(r.endQuery, GL_QUERY_RESULT) - glGetQueryObjecti64(r.startQuery, GL_QUERY_RESULT);
					}
					r.job.done(pixels, info, r.recordNanos, gpu);
				} catch (Throwable t) {
					Diorama.LOGGER.error("Reading back GPU tile {} failed", r.job.mesh.key, t);
					r.job.fail();
				} finally {
					this.free(r);
				}
			}
		} finally {
			glBindBuffer(GL_PIXEL_PACK_BUFFER, pack);
		}
	}

	private void free(Readback r) {
		glDeleteBuffers(r.colors);
		if (r.info != 0) {
			glDeleteBuffers(r.info);
		}
		if (r.startQuery != 0) {
			glDeleteQueries(r.startQuery);
			glDeleteQueries(r.endQuery);
		}
	}

	/** Points the face instance attributes at the faces from {@code first} on. */
	private void pointFaces(int first) {
		long offset = (long) first * FACE_BYTES;
		glBindBuffer(GL_ARRAY_BUFFER, this.faceVbo);
		glVertexAttribIPointer(1, 4, GL_SHORT, FACE_BYTES, offset);
		glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, FACE_BYTES, offset + 8);
		glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, true, FACE_BYTES, offset + 12);
		glVertexAttribPointer(4, 4, GL_UNSIGNED_BYTE, true, FACE_BYTES, offset + 16);
		glVertexAttribIPointer(5, 1, GL_INT, FACE_BYTES, offset + 20);
		glVertexAttribIPointer(6, 2, GL_INT, FACE_BYTES, offset + 24);
		glVertexAttribIPointer(7, 4, GL_INT, FACE_BYTES, offset + 32);
	}

	private void target(int fbo, int width, int height) {
		GlState.bindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
		GlState.viewport(width, height);
	}

	/** Depth tested the way vanilla does, reversed: nearer is larger. */
	private void depth(boolean write) {
		GlState.depthTest(true);
		GlState.depthFunc(GL_GEQUAL);
		GlState.depthMask(write);
	}

	private void clearDepth() {
		GlState.depthMask(true);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			glClearBufferfv(GL_DEPTH, 0, stack.floats(0));
		}
	}

	private void clearColor(int attachment, float r, float g, float b, float a) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			glClearBufferfv(GL_COLOR, attachment, stack.floats(r, g, b, a));
		}
	}

	/** Binds textures to units 0, 1, ... in order, with no sampler object overriding them. */
	private void bindTextures(int... textures) {
		for (int i = 0; i < textures.length; i++) {
			GlState.activeTexture(i);
			GlState.bindTexture(textures[i]);
			noSampler(i);
		}
	}

	private boolean ready() {
		if (this.usable == null) {
			this.usable = false;
			try {
				GLCapabilities caps = GL.getCapabilities();
				coreDivisor = caps.glVertexAttribDivisor != 0;
				if (!coreDivisor && !caps.GL_ARB_instanced_arrays) {
					Diorama.LOGGER.warn("GPU baking needs instanced vertex attributes (OpenGL 3.3); tracing tiles instead");
					return false;
				}
				samplerObjects = caps.glBindSampler != 0;
				this.timestamps = caps.glQueryCounter != 0 && caps.glGetQueryObjecti64v != 0;
				Diorama.LOGGER.info("GPU baking on {}", this.device());
				// An error stays flagged until someone reads it, and on Windows nothing in the game
				// does, so one another mod raised minutes ago would be blamed on this setup. Read
				// them away first; GL keeps one per kind, so a few reads clear them all.
				int error;
				for (int i = 0; i < 16 && (error = glGetError()) != GL_NO_ERROR; i++) {
					Diorama.LOGGER.debug("OpenGL error {} was flagged before GPU baking set up; not counting it", error);
				}
				Saved saved = Saved.take();
				try {
					this.create(caps);
				} finally {
					saved.restore();
				}
				this.usable = true;
			} catch (Throwable t) {
				Diorama.LOGGER.error("Couldn't set up GPU baking; tracing tiles instead", t);
			}
		}
		return this.usable;
	}

	private void create(GLCapabilities caps) throws IOException {
		// The game may run OpenGL with depth from 0 to 1, as 26.3 does; the shaders need to know.
		boolean zeroToOne = (caps.OpenGL45 || caps.GL_ARB_clip_control) && glGetInteger(0x935D) == 0x935F;
		String depth = zeroToOne ? "DIORAMA_DEPTH_ZERO_TO_ONE" : null;
		String vsh = shader("core/bake.vsh");
		String fsh = shader("core/bake.fsh");
		this.skyProgram = program(vsh, fsh, Map.of("Voxels", 0), "SKY_PASS", depth);
		this.shadowProgram = program(vsh, fsh, Map.of(), "SHADOW", depth);
		Map<String, Integer> faces = Map.of("ShadowMap", 0, "Sky", 1);
		// Under a roof the voxels too, for the shadows the shadow map misses.
		this.opaqueProgram = program(vsh, fsh, Map.of("ShadowMap", 0, "Sky", 1, "Voxels", 2), null, depth);
		this.tintedProgram = program(vsh, fsh, faces, "TINTED", depth);
		this.waterProgram = program(vsh, fsh, faces, "WATER", depth);
		Map<String, Integer> composite = new LinkedHashMap<>();
		String[] names = {"SceneColor", "SceneDepth", "SceneAux", "Tint", "WaterColor", "WaterDepth"};
		for (int i = 0; i < names.length; i++) {
			composite.put(names[i], i);
		}
		this.compositeProgram = program(shader("core/bake_composite.vsh"), shader("core/bake_composite.fsh"), composite, null, depth);

		int size = Projection.TILE_PIXELS;
		this.shadowTexture = texture(GL_DEPTH_COMPONENT32F, SHADOW_SIZE, SHADOW_SIZE, GL_DEPTH_COMPONENT, GL_FLOAT);
		this.sceneColor = texture(GL_RGBA16F, SCENE, SCENE, GL_RGBA, GL_HALF_FLOAT);
		this.sceneAux = texture(GL_RGBA16F, SCENE, SCENE, GL_RGBA, GL_HALF_FLOAT);
		this.sceneDepth = texture(GL_DEPTH_COMPONENT32F, SCENE, SCENE, GL_DEPTH_COMPONENT, GL_FLOAT);
		this.tint = texture(GL_RGBA16F, SCENE, SCENE, GL_RGBA, GL_HALF_FLOAT);
		this.waterColor = texture(GL_RGBA16F, SCENE, SCENE, GL_RGBA, GL_HALF_FLOAT);
		this.waterDepth = texture(GL_DEPTH_COMPONENT32F, SCENE, SCENE, GL_DEPTH_COMPONENT, GL_FLOAT);
		this.sky = texture(GL_R8, SKY_WIDTH, SKY_HEIGHT, GL_RED, GL_UNSIGNED_BYTE);
		this.voxels = texture(GL_R32UI, VOXEL_WIDTH, VOXEL_HEIGHT, GL_RED_INTEGER, GL_UNSIGNED_INT);
		this.output = texture(GL_RGBA8, size, size, GL_RGBA, GL_UNSIGNED_BYTE);
		this.outputInfo = texture(GL_R8, size, size, GL_RED, GL_UNSIGNED_BYTE);

		this.skyFbo = framebuffer(0, this.sky);
		this.shadowFbo = framebuffer(this.shadowTexture);
		this.sceneFbo = framebuffer(this.sceneDepth, this.sceneColor, this.sceneAux);
		this.tintFbo = framebuffer(this.sceneDepth, this.tint);
		this.waterFbo = framebuffer(this.waterDepth, this.waterColor);
		this.outputFbo = framebuffer(0, this.output, this.outputInfo);

		this.ubo = glGenBuffers();
		this.faceVbo = glGenBuffers();
		this.casterVbo = glGenBuffers();
		this.cornerVbo = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, this.cornerVbo);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// (0,0), (1,0), (1,1), (0,1): round the quad, as the indices below expect.
			glBufferData(GL_ARRAY_BUFFER, stack.bytes((byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 255, (byte) 0, (byte) 0, (byte) 0,
				(byte) 255, (byte) 255, (byte) 0, (byte) 0, (byte) 0, (byte) 255, (byte) 0, (byte) 0), GL_STATIC_DRAW);
			this.indexBuffer = glGenBuffers();
			this.faceVao = glGenVertexArrays();
			GlState.bindVertexArray(this.faceVao);
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, this.indexBuffer);
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, stack.shorts((short) 0, (short) 1, (short) 2, (short) 2, (short) 3, (short) 0), GL_STATIC_DRAW);
		}
		this.corners();
		for (int a = 1; a < ATTRIBUTES.length; a++) {
			glEnableVertexAttribArray(a);
			divisor(a, 1);
		}
		this.pointFaces(0);
		this.casterVao = glGenVertexArrays();
		GlState.bindVertexArray(this.casterVao);
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, this.indexBuffer);
		this.corners();
		glBindBuffer(GL_ARRAY_BUFFER, this.casterVbo);
		glEnableVertexAttribArray(1);
		divisor(1, 1);
		glVertexAttribIPointer(1, 4, GL_SHORT, CASTER_BYTES, 0);
		glEnableVertexAttribArray(6);
		divisor(6, 1);
		glVertexAttribIPointer(6, 2, GL_INT, CASTER_BYTES, 8);
		this.emptyVao = glGenVertexArrays();
		GlState.forgetBuffers();
		int error = glGetError();
		if (error != GL_NO_ERROR) {
			throw new IllegalStateException("OpenGL error " + error + " setting up GPU baking");
		}
	}

	private static void divisor(int attribute, int divisor) {
		if (coreDivisor) {
			glVertexAttribDivisor(attribute, divisor);
		} else {
			org.lwjgl.opengl.ARBInstancedArrays.glVertexAttribDivisorARB(attribute, divisor);
		}
	}

	/** Leaves the unit's texture to sample with its own settings; vanilla binds sampler objects from 1.21.11. */
	private static void noSampler(int unit) {
		if (samplerObjects) {
			glBindSampler(unit, 0);
		}
	}

	/** Attribute 0 of the bound vertex array: the quad corner, from the corner buffer. */
	private void corners() {
		glBindBuffer(GL_ARRAY_BUFFER, this.cornerVbo);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 4, GL_UNSIGNED_BYTE, true, 4, 0);
		divisor(0, 0);
	}

	private static int texture(int internalFormat, int width, int height, int format, int type) {
		int texture = glGenTextures();
		GlState.activeTexture(0);
		GlState.bindTexture(texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
		// Depth textures are read as plain values, not compared.
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
		glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
		return texture;
	}

	/** A framebuffer over a depth texture (0 for none) and colour textures in attachment order. */
	private static int framebuffer(int depth, int... colors) {
		int fbo = glGenFramebuffers();
		GlState.bindFramebuffer(GL_FRAMEBUFFER, fbo);
		if (depth != 0) {
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
		}
		int[] buffers = new int[colors.length];
		for (int i = 0; i < colors.length; i++) {
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, colors[i], 0);
			buffers[i] = GL_COLOR_ATTACHMENT0 + i;
		}
		if (colors.length == 0) {
			glDrawBuffer(GL_NONE);
			glReadBuffer(GL_NONE);
		} else {
			glDrawBuffers(buffers);
		}
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("GPU bake framebuffer incomplete: " + status);
		}
		return fbo;
	}

	/**
	 * A shader from the mod's jar with its include put in place. Block comments go first: the
	 * other version's lines can sit in one, and so can an include line that mustn't be expanded.
	 * A line commented out with {@code //} stays put, as only a directive starting its line counts.
	 */
	private static String shader(String name) throws IOException {
		String text = BLOCK_COMMENT.matcher(read("/assets/diorama/shaders/" + name)).replaceAll("");
		String include = java.util.regex.Matcher.quoteReplacement(BLOCK_COMMENT.matcher(read("/assets/diorama/shaders/include/bake.glsl")).replaceAll(""));
		return INCLUDE.matcher(text).replaceAll(include);
	}

	private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern INCLUDE = Pattern.compile("(?m)^#(include|moj_import) <diorama:bake\\.glsl>$");

	private static String read(String path) throws IOException {
		try (InputStream in = GlBakeBackend.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IOException("missing " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Compiles and links a program. Inputs and outputs are bound by name here, so the explicit
	 * locations 26.3's shaders carry are taken out; the uniform block and samplers get their
	 * binding and units.
	 */
	private static int program(String vsh, String fsh, Map<String, Integer> samplers, @Nullable String define, @Nullable String depth) {
		Map<String, Integer> outputs = new LinkedHashMap<>();
		Matcher m = OUTPUT.matcher(fsh);
		while (m.find()) {
			outputs.put(m.group(2), Integer.parseInt(m.group(1)));
		}
		String defines = (define == null ? "" : "#define " + define + "\n") + (depth == null ? "" : "#define " + depth + "\n");
		int vs = compile(GL_VERTEX_SHADER, prepare(vsh, defines));
		int fs = compile(GL_FRAGMENT_SHADER, prepare(fsh, defines));
		int program = glCreateProgram();
		glAttachShader(program, vs);
		glAttachShader(program, fs);
		for (int a = 0; a < ATTRIBUTES.length; a++) {
			glBindAttribLocation(program, a, ATTRIBUTES[a]);
		}
		for (Map.Entry<String, Integer> output : outputs.entrySet()) {
			glBindFragDataLocation(program, output.getValue(), output.getKey());
		}
		glLinkProgram(program);
		glDetachShader(program, vs);
		glDetachShader(program, fs);
		glDeleteShader(vs);
		glDeleteShader(fs);
		if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
			String log = glGetProgramInfoLog(program);
			glDeleteProgram(program);
			throw new IllegalStateException("GPU bake shader didn't link (" + define + "): " + log);
		}
		int block = glGetUniformBlockIndex(program, "DioramaBake");
		if (block != GL_INVALID_INDEX) {
			glUniformBlockBinding(program, block, UBO_BINDING);
		}
		GlState.useProgram(program);
		for (Map.Entry<String, Integer> sampler : samplers.entrySet()) {
			int location = glGetUniformLocation(program, sampler.getKey());
			if (location >= 0) {
				glUniform1i(location, sampler.getValue());
			}
		}
		return program;
	}

	/**
	 * The pass's defines after the version line; no explicit locations; the vertex index under its GL name.
	 * GLSL 1.50, what OpenGL 3.2 promises: up to 1.21.8 the game asks for no more, and with explicit
	 * locations gone the shaders need nothing newer.
	 */
	private static String prepare(String source, String defines) {
		String text = LOCATION.matcher(source).replaceAll("")
			.replace("#version 330", "#version 150")
			.replace("#extension GL_ARB_separate_shader_objects : require", "")
			.replace("gl_VertexIndex", "gl_VertexID");
		int line = text.indexOf('\n');
		return text.substring(0, line + 1) + defines + text.substring(line + 1);
	}

	private static int compile(int type, String source) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
			String log = glGetShaderInfoLog(shader);
			glDeleteShader(shader);
			throw new IllegalStateException("GPU bake shader didn't compile: " + log);
		}
		return shader;
	}

	/**
	 * The GL state a bake changes, taken before and put back after. What vanilla keeps a copy of
	 * goes back through {@link GlState}, so the copy stays true too: vanilla can rely on state it
	 * set before the frame hook (culling, before 1.21.5) without setting it again.
	 */
	private record Saved(
		int program, int vao, int arrayBuffer, int uniformBuffer, int uboBinding, int pack, int unpack, int drawFbo, int readFbo,
		int[] viewport, int activeTexture, int[] textures, int[] samplers, int[] pixelStore,
		boolean cull, boolean scissor, boolean depthTest, int depthFunc, boolean depthMask, boolean blend0, boolean blend1, int[] blendFunc, boolean[] colorMask
	) {
		/** Pixel store settings a bake sets: pack and unpack alignment, row length and skips. */
		private static final int[] PIXEL_STORE = {
			GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_PIXELS, GL_PACK_SKIP_ROWS,
			GL_UNPACK_ALIGNMENT, GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS
		};

		static Saved take() {
			int[] viewport = new int[4];
			glGetIntegerv(GL_VIEWPORT, viewport);
			int active = glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0;
			int[] textures = new int[UNITS];
			int[] samplers = new int[UNITS];
			for (int u = 0; u < UNITS; u++) {
				GlState.activeTexture(u);
				textures[u] = glGetInteger(GL_TEXTURE_BINDING_2D);
				samplers[u] = samplerObjects ? glGetInteger(GL_SAMPLER_BINDING) : 0;
			}
			GlState.activeTexture(active);
			int[] pixelStore = new int[PIXEL_STORE.length];
			for (int i = 0; i < PIXEL_STORE.length; i++) {
				pixelStore[i] = glGetInteger(PIXEL_STORE[i]);
			}
			boolean[] colorMask = new boolean[4];
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer mask = stack.malloc(4);
				glGetBooleanv(GL_COLOR_WRITEMASK, mask);
				for (int i = 0; i < 4; i++) {
					colorMask[i] = mask.get(i) != 0;
				}
			}
			int[] blendFunc = {
				glGetInteger(GL_BLEND_SRC_RGB), glGetInteger(GL_BLEND_DST_RGB), glGetInteger(GL_BLEND_SRC_ALPHA), glGetInteger(GL_BLEND_DST_ALPHA)
			};
			return new Saved(
				glGetInteger(GL_CURRENT_PROGRAM), glGetInteger(GL_VERTEX_ARRAY_BINDING), glGetInteger(GL_ARRAY_BUFFER_BINDING),
				glGetInteger(GL_UNIFORM_BUFFER_BINDING), glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, UBO_BINDING),
				glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING), glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),
				glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), glGetInteger(GL_READ_FRAMEBUFFER_BINDING), viewport, active, textures, samplers, pixelStore,
				glIsEnabled(GL_CULL_FACE), glIsEnabled(GL_SCISSOR_TEST), glIsEnabled(GL_DEPTH_TEST), glGetInteger(GL_DEPTH_FUNC),
				glGetBoolean(GL_DEPTH_WRITEMASK), glIsEnabledi(GL_BLEND, 0), glIsEnabledi(GL_BLEND, 1), blendFunc, colorMask
			);
		}

		/** Packed and unpacked rows start at the buffer's start and run on unbroken: what a bake's buffers are. */
		static void plainPixelStore() {
			for (int i = 0; i < PIXEL_STORE.length; i++) {
				int name = PIXEL_STORE[i];
				glPixelStorei(name, name == GL_PACK_ALIGNMENT || name == GL_UNPACK_ALIGNMENT ? 4 : 0);
			}
		}

		void restore() {
			for (int u = 0; u < UNITS; u++) {
				GlState.activeTexture(u);
				GlState.bindTexture(this.textures[u]);
				if (samplerObjects) {
					glBindSampler(u, this.samplers[u]);
				}
			}
			GlState.activeTexture(this.activeTexture);
			GlState.useProgram(this.program);
			GlState.bindVertexArray(this.vao);
			glBindBuffer(GL_ARRAY_BUFFER, this.arrayBuffer);
			glBindBufferBase(GL_UNIFORM_BUFFER, UBO_BINDING, this.uboBinding);
			glBindBuffer(GL_UNIFORM_BUFFER, this.uniformBuffer);
			glBindBuffer(GL_PIXEL_PACK_BUFFER, this.pack);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, this.unpack);
			GlState.bindFramebuffer(GL_DRAW_FRAMEBUFFER, this.drawFbo);
			GlState.bindFramebuffer(GL_READ_FRAMEBUFFER, this.readFbo);
			GlState.viewportAt(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
			for (int i = 0; i < PIXEL_STORE.length; i++) {
				glPixelStorei(PIXEL_STORE[i], this.pixelStore[i]);
			}
			GlState.cull(this.cull);
			GlState.scissor(this.scissor);
			GlState.depthTest(this.depthTest);
			GlState.depthFunc(this.depthFunc);
			GlState.depthMask(this.depthMask);
			GlState.blendFuncSeparate(this.blendFunc[0], this.blendFunc[1], this.blendFunc[2], this.blendFunc[3]);
			GlState.restoreBlend(this.blend0, this.blend1);
			GlState.colorMask(this.colorMask[0], this.colorMask[1], this.colorMask[2], this.colorMask[3]);
			GlState.forgetBuffers();
		}
	}
}
