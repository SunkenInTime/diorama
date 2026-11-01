//? if >=26.2 {
package dev.diorama.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.GpuMesher;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import static dev.diorama.render.gpu.GpuTileBaker.FACE_BYTES;
import static dev.diorama.render.gpu.GpuTileBaker.SCENE;
import static dev.diorama.render.gpu.GpuTileBaker.SHADOW_SIZE;
import static dev.diorama.render.gpu.GpuTileBaker.SKY_HEIGHT;
import static dev.diorama.render.gpu.GpuTileBaker.SKY_WIDTH;
import static dev.diorama.render.gpu.GpuTileBaker.UBO_SIZE;
import static dev.diorama.render.gpu.GpuTileBaker.VOXEL_HEIGHT;
import static dev.diorama.render.gpu.GpuTileBaker.VOXEL_WIDTH;

/**
 * Bakes through Minecraft's own GPU layer, which from 26.2 can express everything a bake needs
 * and runs on OpenGL and Vulkan alike. Pipelines aren't registered: they compile the first time
 * they're used, so a broken shader only sends tiles back to the tracer.
 */
final class BlazeBakeBackend implements BakeBackend {
	private @Nullable Targets targets;
	private long timestampSlot;

	private static final VertexFormat CORNER = VertexFormat.builder(0)
		.addAttribute("Corner", GpuFormat.RGBA8_UNORM)
		.build();
	private static final VertexFormat FACE = VertexFormat.builder(1)
		.addAttribute("Cell", GpuFormat.RGBA16_SINT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("Occlusion", GpuFormat.RGBA8_UNORM)
		.addAttribute("FaceLight", GpuFormat.RGBA8_UNORM)
		.addAttribute("SkyIndex", GpuFormat.R32_SINT)
		.addAttribute("Box", GpuFormat.RG32_SINT)
		.addAttribute("Haze", GpuFormat.RGBA32_SINT)
		.build();
	private static final VertexFormat CASTER = VertexFormat.builder(1)
		.addAttribute("Cell", GpuFormat.RGBA16_SINT)
		.addAttribute("Box", GpuFormat.RG32_SINT)
		.build();

	private static final BindGroupLayout BAKE = BindGroupLayout.builder().withUniform("DioramaBake", UniformType.UNIFORM_BUFFER).build();
	private static final BindGroupLayout SHADOW_MAP = samplers("ShadowMap");
	private static final BindGroupLayout SKY = samplers("Sky");
	private static final BindGroupLayout VOXELS = samplers("Voxels");
	private static final BindGroupLayout SCENE_SAMPLERS = samplers("SceneColor", "SceneDepth", "SceneAux", "Tint", "WaterColor", "WaterDepth");

	// The few calls 26.2 spells differently from 26.3.

	private static BindGroupLayout samplers(String... names) {
		BindGroupLayout.Builder builder = BindGroupLayout.builder();
		for (String name : names) {
			//? if >=26.3 {
			builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
			//?} else {
			/*builder.withSampler(name);
			*///?}
		}
		return builder.build();
	}

	private static void bind(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
		//? if >=26.3 {
		pass.setUniform(name, view, sampler);
		//?} else {
		/*pass.bindTexture(name, view, sampler);
		*///?}
	}

	/**
	 * 26.2 gives every pipeline and pass a colour target, and sizes a pass by its first one, so a
	 * depth-only pass there draws into a stand-in target it never writes.
	 */
	private static RenderPipeline.Builder depthOnly(RenderPipeline.Builder builder) {
		//? if <26.3 {
		/*builder.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_NONE));
		*///?}
		return builder;
	}

	/** Whether the pipeline compiled; compiles it the first time. */
	private static boolean ready(RenderPipeline pipeline) {
		//? if >=26.3 {
		return RenderSystem.getCompiledPipelineNullable(pipeline) != null;
		//?} else {
		/*return RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
		*///?}
	}

	private static void use(RenderPass pass, RenderPipeline pipeline) {
		//? if >=26.3 {
		pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
		//?} else {
		/*pass.setPipeline(pipeline);
		*///?}
	}

	/** A pass over several targets of one size. */
	private static final class Pass {
		private final java.util.function.Supplier<String> label;
		private final java.util.List<GpuTextureView> colors = new java.util.ArrayList<>();
		private final java.util.List<Optional<org.joml.Vector4fc>> clears = new java.util.ArrayList<>();
		private @Nullable GpuTextureView depth;
		private OptionalDouble depthClear = OptionalDouble.empty();

		Pass(java.util.function.Supplier<String> label) {
			this.label = label;
		}

		Pass color(GpuTextureView view, Optional<org.joml.Vector4fc> clear) {
			this.colors.add(view);
			this.clears.add(clear);
			return this;
		}

		Pass depth(GpuTextureView view, OptionalDouble clear) {
			this.depth = view;
			this.depthClear = clear;
			return this;
		}

		RenderPass open(CommandEncoder encoder, int size) {
			//? if >=26.3 {
			RenderPassDescriptor.Builder d = RenderPassDescriptor.builder(this.label);
			for (int i = 0; i < this.colors.size(); i++) {
				d.withColorAttachment(this.colors.get(i), this.clears.get(i));
			}
			if (this.depth != null) {
				d.withDepthAttachment(this.depth, this.depthClear);
			}
			return encoder.createRenderPass(d.build());
			//?} else {
			/*RenderPassDescriptor d = RenderPassDescriptor.create(this.label);
			for (int i = 0; i < this.colors.size(); i++) {
				d = d.withColorAttachment(this.colors.get(i), this.clears.get(i));
			}
			if (this.depth != null) {
				d = d.withDepthAttachment(this.depth, this.depthClear);
			}
			return encoder.createRenderPass(d.withRenderArea(new RenderPass.RenderArea(0, 0, size, size)));
			*///?}
		}
	}

	private static ColorTargetState target(GpuFormat format) {
		return new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL);
	}

	// Not registered: compiled the first time they're used, so a broken shader only sends tiles back to the tracer.
	private static final RenderPipeline SKY_PASS = faces("bake_sky", "SKY_PASS", FACE)
		.withBindGroupLayout(VOXELS)
		.withColorTargetState(target(GpuFormat.R8_UNORM))
		.withPrimitiveTopology(PrimitiveTopology.POINTS)
		.build();
	private static final RenderPipeline SHADOW = depthOnly(faces("bake_shadow", "SHADOW", CASTER))
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build();
	private static final RenderPipeline OPAQUE = faces("bake_opaque", null, FACE)
		.withBindGroupLayout(SHADOW_MAP)
		.withBindGroupLayout(SKY)
		.withBindGroupLayout(VOXELS)
		.withColorTargetState(0, target(GpuFormat.RGBA16_FLOAT))
		.withColorTargetState(1, target(GpuFormat.RGBA16_FLOAT))
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build();
	// Near to far: each face adds its colour times what the ones in front let through (the
	// target's alpha), and lets through less behind it.
	private static final RenderPipeline TINTED = faces("bake_tinted", "TINTED", FACE)
		.withBindGroupLayout(SHADOW_MAP)
		.withBindGroupLayout(SKY)
		.withColorTargetState(new ColorTargetState(
			Optional.of(new BlendFunction(BlendFactor.DST_ALPHA, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA)),
			GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL
		))
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.build();
	private static final RenderPipeline WATER = faces("bake_water", "WATER", FACE)
		.withBindGroupLayout(SHADOW_MAP)
		.withBindGroupLayout(SKY)
		.withColorTargetState(target(GpuFormat.RGBA16_FLOAT))
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build();
	private static final RenderPipeline COMPOSITE = depthRange(RenderPipeline.builder())
		.withLocation(Diorama.id("pipeline/bake_composite"))
		.withVertexShader(Diorama.id("core/bake_composite"))
		.withFragmentShader(Diorama.id("core/bake_composite"))
		.withBindGroupLayout(BAKE)
		.withBindGroupLayout(SCENE_SAMPLERS)
		.withColorTargetState(0, ColorTargetState.DEFAULT)
		.withColorTargetState(1, target(GpuFormat.R8_UNORM))
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.build();

	private static RenderPipeline.Builder faces(String name, @Nullable String define, VertexFormat instances) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Diorama.id("pipeline/" + name))
			.withVertexShader(Diorama.id("core/bake"))
			.withFragmentShader(Diorama.id("core/bake"))
			.withBindGroupLayout(BAKE)
			.withVertexBinding(0, CORNER)
			.withVertexBinding(1, instances)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withCull(false);
		if (define != null) {
			builder.withShaderDefine(define);
		}
		return depthRange(builder);
	}

	/** 26.3 tells shaders how its depth range runs; 26.2 leaves it to the pipeline. */
	private static RenderPipeline.Builder depthRange(RenderPipeline.Builder builder) {
		//? if <26.3 {
		/*if (RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()) {
			builder.withShaderDefine("DIORAMA_DEPTH_ZERO_TO_ONE");
		}
		*///?}
		return builder;
	}

	private @Nullable String device;

	@Override
	public @Nullable String device() {
		if (this.device == null) {
			var info = RenderSystem.getDevice().getDeviceInfo();
			this.device = info.name() + " (" + info.vendorName() + ", " + info.backendName() + " " + info.driverInfo() + ")";
			Diorama.LOGGER.info("GPU baking on {}", this.device);
		}
		return this.device;
	}

	@Override
	public boolean draw(GpuTileBaker.Job job) {
		long start = System.nanoTime();
		int skyFaces = job.opaqueCount + job.waterCount;
		if (!ready(SKY_PASS) || !ready(SHADOW) || !ready(OPAQUE) || !ready(TINTED) || !ready(WATER) || !ready(COMPOSITE)) {
			return false;
		}
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		Targets t = targets();
		int slot = (int) (timestampSlot++ % (Targets.TIMESTAMPS / 2)) * 2;
		encoder.writeTimestamp(t.timestamps, slot);
		encoder.writeToBuffer(t.ubo.slice(), job.uniforms);
		if (job.voxels != null && job.voxelRows > 0) {
			//? if >=26.3 {
			encoder.writeToTexture(t.voxels, job.voxels, 0, 0, 0, 0, VOXEL_WIDTH, job.voxelRows);
			//?} else {
			/*// 26.2's OpenGL writeToTexture sends every texture as bytes, which scrambles these 32-bit
			// words; a copy from a buffer sends the texture's own type.
			GpuBufferSlice upload = t.voxelUpload.slice(0, (long) job.voxelRows * VOXEL_WIDTH * 4);
			encoder.writeToBuffer(upload, job.voxels);
			encoder.copyBufferToTexture(upload, 0, 0, VOXEL_WIDTH, job.voxelRows, t.voxels, 0, 0, VOXEL_WIDTH, job.voxelRows, 0, 0);
			*///?}
		}

		GpuBuffer faceBuffer = null;
		GpuBuffer casterBuffer = null;
		try {
			faceBuffer = job.faceCount() == 0 ? null : device.createBuffer(() -> "Diorama bake faces", GpuBuffer.USAGE_VERTEX, job.faces);
			casterBuffer = job.casterCount == 0 ? null : device.createBuffer(() -> "Diorama bake casters", GpuBuffer.USAGE_VERTEX, job.casters);
			RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer indices = quads.getBuffer(6);
			IndexType indexType = quads.type();
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			long opaqueBytes = (long) job.opaqueCount * FACE_BYTES;
			long tintedBytes = (long) job.tintedCount * FACE_BYTES;
			long waterBytes = (long) job.waterCount * FACE_BYTES;

			if (job.voxels != null && faceBuffer != null && skyFaces > 0) {
				try (RenderPass pass = encoder.createRenderPass(() -> "Diorama bake sky", t.skyView, Optional.empty())) {
					use(pass, SKY_PASS);
					pass.setUniform("DioramaBake", t.ubo);
					bind(pass, "Voxels", t.voxelsView, t.voxelSampler);
					pass.setVertexBuffer(0, t.corners.slice());
					if (job.opaqueCount > 0) {
						pass.setVertexBuffer(1, faceBuffer.slice(0, opaqueBytes));
						pass.draw(4, job.opaqueCount, 0, 0);
					}
					if (job.waterCount > 0) {
						pass.setVertexBuffer(1, faceBuffer.slice(opaqueBytes + tintedBytes, waterBytes));
						pass.draw(4, job.waterCount, 0, 0);
					}
				}
			}
			Pass shadowPass = new Pass(() -> "Diorama bake shadow").depth(t.shadowView, OptionalDouble.of(0.0));
			//? if <26.3 {
			/*shadowPass.color(t.shadowStandInView, Optional.empty());
			*///?}
			try (RenderPass pass = shadowPass.open(encoder, SHADOW_SIZE)) {
				if (casterBuffer != null) {
					use(pass, SHADOW);
					pass.setUniform("DioramaBake", t.ubo);
					pass.setVertexBuffer(0, t.corners.slice());
					pass.setVertexBuffer(1, casterBuffer.slice());
					pass.setIndexBuffer(indices, indexType);
					pass.drawIndexed(6, job.casterCount, 0, 0, 0);
				}
			}
			try (RenderPass pass = new Pass(() -> "Diorama bake scene")
				.color(t.sceneColorView, Optional.of(VOID))
				.color(t.sceneAuxView, Optional.of(CLEAR))
				.depth(t.sceneDepthView, OptionalDouble.of(0.0))
				.open(encoder, SCENE)) {
				if (faceBuffer != null && job.opaqueCount > 0) {
					use(pass, OPAQUE);
					pass.setUniform("DioramaBake", t.ubo);
					bind(pass, "ShadowMap", t.shadowView, nearest);
					bind(pass, "Sky", t.skyView, nearest);
					// Under a roof, for the shadows the shadow map misses.
					bind(pass, "Voxels", t.voxelsView, t.voxelSampler);
					pass.setVertexBuffer(0, t.corners.slice());
					pass.setIndexBuffer(indices, indexType);
					pass.setVertexBuffer(1, faceBuffer.slice(0, opaqueBytes));
					pass.drawIndexed(6, job.opaqueCount, 0, 0, 0);
				}
			}
			// Plants and glass have their own pass: blending differently per target needs a
			// Vulkan feature Minecraft doesn't turn on.
			try (RenderPass pass = new Pass(() -> "Diorama bake tint")
				.color(t.tintView, Optional.of(NO_TINT))
				.depth(t.sceneDepthView, OptionalDouble.empty())
				.open(encoder, SCENE)) {
				if (faceBuffer != null && job.tintedCount > 0) {
					use(pass, TINTED);
					pass.setUniform("DioramaBake", t.ubo);
					bind(pass, "ShadowMap", t.shadowView, nearest);
					bind(pass, "Sky", t.skyView, nearest);
					pass.setVertexBuffer(0, t.corners.slice());
					pass.setIndexBuffer(indices, indexType);
					pass.setVertexBuffer(1, faceBuffer.slice(opaqueBytes, tintedBytes));
					pass.drawIndexed(6, job.tintedCount, 0, 0, 0);
				}
			}
			try (RenderPass pass = encoder.createRenderPass(
				() -> "Diorama bake water", t.waterColorView, Optional.of(CLEAR), t.waterDepthView, OptionalDouble.of(0.0)
			)) {
				if (faceBuffer != null && job.waterCount > 0) {
					use(pass, WATER);
					pass.setUniform("DioramaBake", t.ubo);
					bind(pass, "ShadowMap", t.shadowView, nearest);
					bind(pass, "Sky", t.skyView, nearest);
					pass.setVertexBuffer(0, t.corners.slice());
					pass.setIndexBuffer(indices, indexType);
					pass.setVertexBuffer(1, faceBuffer.slice(opaqueBytes + tintedBytes, waterBytes));
					pass.drawIndexed(6, job.waterCount, 0, 0, 0);
				}
			}
			try (RenderPass pass = new Pass(() -> "Diorama bake composite")
				.color(t.outputView, Optional.empty())
				.color(t.outputInfoView, Optional.empty())
				.open(encoder, Projection.TILE_PIXELS)) {
				use(pass, COMPOSITE);
				pass.setUniform("DioramaBake", t.ubo);
				bind(pass, "SceneColor", t.sceneColorView, nearest);
				bind(pass, "SceneDepth", t.sceneDepthView, nearest);
				bind(pass, "SceneAux", t.sceneAuxView, nearest);
				bind(pass, "Tint", t.tintView, nearest);
				bind(pass, "WaterColor", t.waterColorView, nearest);
				bind(pass, "WaterDepth", t.waterDepthView, nearest);
				pass.draw(3, 1, 0, 0);
			}

			int size = Projection.TILE_PIXELS;
			GpuBuffer readback = device.createBuffer(() -> "Diorama bake readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) size * size * 4);
			boolean wantsInfo = job.mesh.overlays != null;
			GpuBuffer infoReadback = wantsInfo ? device.createBuffer(() -> "Diorama bake info readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) size * size) : null;
			// The colour finishes the job; the overlay bits, read back first, ride along. Both
			// backends run these callbacks in the order the copies were recorded.
			byte[][] info = new byte[1][];
			GpuQueryPool timestamps = t.timestamps;
			long[] recorded = new long[1];
			job.pending();
			if (infoReadback != null) {
				encoder.copyTextureToBuffer(t.outputInfo, infoReadback, 0L, () -> {
					try (GpuBufferSlice.MappedView view = infoReadback.map(true, false)) {
						byte[] bits = new byte[size * size];
						view.data().get(bits);
						info[0] = bits;
					} catch (Throwable e) {
						Diorama.LOGGER.error("Reading back GPU tile overlays {} failed", job.mesh.key, e);
					} finally {
						infoReadback.close();
					}
				}, 0);
			}
			encoder.copyTextureToBuffer(t.output, readback, 0L, () -> {
				try {
					if (wantsInfo && info[0] == null) {
						// Overlays were asked for and didn't come back: let the tracer bake it.
						job.fail();
						return;
					}
					int[] pixels = new int[size * size];
					try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
						view.data().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
					}
					job.done(pixels, info[0], recorded[0], gpuNanos(timestamps, slot));
				} catch (Throwable e) {
					Diorama.LOGGER.error("Reading back GPU tile {} failed", job.mesh.key, e);
					job.fail();
				} finally {
					readback.close();
				}
			}, 0);
			// After the copies, so both clocks count them.
			encoder.writeTimestamp(t.timestamps, slot + 1);
			recorded[0] = System.nanoTime() - start;
			GpuTileBaker.recorded(recorded[0]);
		} finally {
			if (faceBuffer != null) {
				faceBuffer.close();
			}
			if (casterBuffer != null) {
				casterBuffer.close();
			}
			job.free();
		}
		return true;
	}

	private static long gpuNanos(GpuQueryPool pool, int slot) {
		OptionalLong[] values = pool.getValues(slot, 2);
		if (values[0].isEmpty() || values[1].isEmpty()) {
			return -1;
		}
		return (long) ((values[1].getAsLong() - values[0].getAsLong()) * (double) RenderSystem.getDevice().getDeviceInfo().timestampPeriod());
	}

	// What a ray that never meets a block shows: the tracer's near-black floor.
	private static final Vector4f VOID = new Vector4f(0.01F, 0.01F, 0.015F, 1.0F);
	private static final Vector4f CLEAR = new Vector4f(0, 0, 0, 0);
	/** Nothing tinted yet: no colour added, everything let through. */
	private static final Vector4f NO_TINT = new Vector4f(0, 0, 0, 1);

	private Targets targets() {
		if (this.targets == null) {
			this.targets = new Targets();
		}
		return this.targets;
	}

	/**
	 * A nearest sampler an integer texture can use. Minecraft's OpenGL samplers minify "nearest"
	 * with GL_NEAREST_MIPMAP_LINEAR, which OpenGL doesn't allow on integer textures: drivers that
	 * keep to that (Apple's, Mesa's) read the voxel table as zeros, and lose the sky occlusion.
	 */
	private static GpuSampler integerSampler(GpuDevice device) {
		GpuSampler sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
		//? if >=26.3 {
		int gl = sampler instanceof com.mojang.renderpearl.backend.opengl.GlSampler glSampler ? glSampler.getId() : 0;
		//?} else {
		/*int gl = sampler instanceof com.mojang.blaze3d.opengl.GlSampler glSampler ? glSampler.getId() : 0;
		*///?}
		if (gl != 0) {
			org.lwjgl.opengl.GL33C.glSamplerParameteri(gl, org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL33C.GL_NEAREST);
		}
		return sampler;
	}

	/** The textures and buffers every bake reuses. Bakes run one after another in stream order, so one set does. */
	private static final class Targets {
		static final int TIMESTAMPS = 128;
		final GpuTexture shadow;
		final GpuTextureView shadowView;
		//? if <26.3 {
		/*final GpuTexture shadowStandIn;
		final GpuTextureView shadowStandInView;
		*///?}
		final GpuTexture sceneColor;
		final GpuTextureView sceneColorView;
		final GpuTexture sceneAux;
		final GpuTextureView sceneAuxView;
		final GpuTexture tint;
		final GpuTextureView tintView;
		final GpuTexture sceneDepth;
		final GpuTextureView sceneDepthView;
		final GpuTexture waterColor;
		final GpuTextureView waterColorView;
		final GpuTexture waterDepth;
		final GpuTextureView waterDepthView;
		final GpuTexture sky;
		final GpuTextureView skyView;
		final GpuTexture voxels;
		final GpuTextureView voxelsView;
		final GpuSampler voxelSampler;
		//? if <26.3 {
		/*final GpuBuffer voxelUpload;
		*///?}
		final GpuTexture output;
		final GpuTextureView outputView;
		final GpuTexture outputInfo;
		final GpuTextureView outputInfoView;
		final GpuBuffer corners;
		final GpuBuffer ubo;
		final GpuQueryPool timestamps;

		Targets() {
			GpuDevice device = RenderSystem.getDevice();
			int sampled = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
			int read = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC;
			int size = Projection.TILE_PIXELS;
			this.shadow = device.createTexture("Diorama bake shadow", sampled, GpuFormat.D32_FLOAT, SHADOW_SIZE, SHADOW_SIZE, 1, 1);
			this.shadowView = device.createTextureView(this.shadow);
			//? if <26.3 {
			/*this.shadowStandIn = device.createTexture("Diorama bake shadow stand-in", GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.R8_UNORM, SHADOW_SIZE, SHADOW_SIZE, 1, 1);
			this.shadowStandInView = device.createTextureView(this.shadowStandIn);
			*///?}
			this.sceneColor = device.createTexture("Diorama bake scene", sampled, GpuFormat.RGBA16_FLOAT, SCENE, SCENE, 1, 1);
			this.sceneColorView = device.createTextureView(this.sceneColor);
			this.sceneAux = device.createTexture("Diorama bake scene aux", sampled, GpuFormat.RGBA16_FLOAT, SCENE, SCENE, 1, 1);
			this.sceneAuxView = device.createTextureView(this.sceneAux);
			this.tint = device.createTexture("Diorama bake tint", sampled, GpuFormat.RGBA16_FLOAT, SCENE, SCENE, 1, 1);
			this.tintView = device.createTextureView(this.tint);
			this.sceneDepth = device.createTexture("Diorama bake scene depth", sampled, GpuFormat.D32_FLOAT, SCENE, SCENE, 1, 1);
			this.sceneDepthView = device.createTextureView(this.sceneDepth);
			this.waterColor = device.createTexture("Diorama bake water", sampled, GpuFormat.RGBA16_FLOAT, SCENE, SCENE, 1, 1);
			this.waterColorView = device.createTextureView(this.waterColor);
			this.waterDepth = device.createTexture("Diorama bake water depth", sampled, GpuFormat.D32_FLOAT, SCENE, SCENE, 1, 1);
			this.waterDepthView = device.createTextureView(this.waterDepth);
			this.sky = device.createTexture("Diorama bake sky", sampled, GpuFormat.R8_UNORM, SKY_WIDTH, SKY_HEIGHT, 1, 1);
			this.skyView = device.createTextureView(this.sky);
			this.voxels = device.createTexture(
				"Diorama bake voxels", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R32_UINT, VOXEL_WIDTH, VOXEL_HEIGHT, 1, 1
			);
			this.voxelsView = device.createTextureView(this.voxels);
			this.voxelSampler = integerSampler(device);
			//? if <26.3 {
			/*this.voxelUpload = device.createBuffer(
				() -> "Diorama bake voxel upload", GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, (long) VOXEL_WIDTH * VOXEL_HEIGHT * 4
			);
			*///?}
			this.output = device.createTexture("Diorama bake output", read, GpuFormat.RGBA8_UNORM, size, size, 1, 1);
			this.outputView = device.createTextureView(this.output);
			this.outputInfo = device.createTexture("Diorama bake output overlays", read, GpuFormat.R8_UNORM, size, size, 1, 1);
			this.outputInfoView = device.createTextureView(this.outputInfo);
			ByteBuffer corners = MemoryUtil.memAlloc(16);
			try {
				// (0,0), (1,0), (1,1), (0,1): round the quad, as the shared quad indices expect.
				corners.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
				corners.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 0);
				corners.put((byte) 255).put((byte) 255).put((byte) 0).put((byte) 0);
				corners.put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 0);
				corners.flip();
				this.corners = device.createBuffer(() -> "Diorama bake corners", GpuBuffer.USAGE_VERTEX, corners);
			} finally {
				MemoryUtil.memFree(corners);
			}
			this.ubo = device.createBuffer(() -> "Diorama bake UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
			this.timestamps = device.createTimestampQueryPool(TIMESTAMPS);
		}
	}
}
//?}
