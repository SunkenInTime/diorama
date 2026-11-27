//? if >=1.21.6 {
package dev.diorama.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
//? if >=26.1 {
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
//?}
//? if >=26.2 {
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexFormat;
*///?}
//? if <26.1 {
/*import com.mojang.blaze3d.platform.DepthTestFunction;
*///?}
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.diorama.Diorama;
//? if >=26.2 {
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import net.minecraft.client.renderer.BindGroupLayouts;
//?}
import net.minecraft.client.renderer.RenderPipelines;

public final class DioramaPipelines {
	/** The fog over unknown map areas: known-area mask in sampler 0, noise in sampler 1, details in sampler 2. */
	//? if >=26.2 {
	public static final RenderPipeline FOG = mapPipeline("fog", BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2);
	//?} else {
	/*public static final RenderPipeline FOG = mapPipeline("fog", 3);
	*///?}
	/**
	 * The same fog into the half-resolution layer the map shows it from (FogLayer): written as it
	 * is, not blended, so the layer holds the fog's own colour and cover.
	 */
	//? if >=26.2 {
	public static final RenderPipeline FOG_LAYER = mapPipeline("fog", "fog_layer", BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2, false);
	//?} else {
	/*public static final RenderPipeline FOG_LAYER = mapPipeline("fog", "fog_layer", 3, false);
	*///?}
	/** A tile with its mip levels in sampler 0; see {@code tile.fsh}. */
	//? if >=26.2 {
	public static final RenderPipeline TILE = mapPipeline("tile", BindGroupLayouts.SAMPLER0);
	//?} else {
	/*public static final RenderPipeline TILE = mapPipeline("tile", 1);
	*///?}

	/** A tile at night with its block light: the tile in sampler 0, the light in sampler 1; see {@code tile_night.fsh}. */
	//? if >=26.2 {
	public static final RenderPipeline TILE_NIGHT = mapPipeline("tile_night", BindGroupLayouts.SAMPLER0_SAMPLER1);
	//?} else {
	/*public static final RenderPipeline TILE_NIGHT = mapPipeline("tile_night", 2);
	*///?}

	/**
	 * One of the map's own shaders, with the fog's vertex shader. Each version builds it like its
	 * own vanilla GUI_TEXTURED_SNIPPET, with the globals added.
	 */
	//? if >=26.2 {
	private static RenderPipeline mapPipeline(String name, BindGroupLayout samplers) {
		return mapPipeline(name, name, samplers, true);
	}
	//?} else {
	/*private static RenderPipeline mapPipeline(String name, int samplers) {
		return mapPipeline(name, name, samplers, true);
	}
	*///?}

	//? if >=26.3 {
	private static RenderPipeline mapPipeline(String name, String location, BindGroupLayout samplers, boolean blended) {
		return register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Diorama.id("pipeline/" + location))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withVertexShader(Diorama.id("core/fog"))
			.withFragmentShader(Diorama.id("core/" + name))
			.withBindGroupLayout(samplers)
			.withColorTargetState(blended ? new ColorTargetState(BlendFunction.TRANSLUCENT) : ColorTargetState.DEFAULT)
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.build());
	}
	//?} else if >=26.2 {
	/*private static RenderPipeline mapPipeline(String name, String location, BindGroupLayout samplers, boolean blended) {
		return register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Diorama.id("pipeline/" + location))
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withVertexShader(Diorama.id("core/fog"))
			.withFragmentShader(Diorama.id("core/" + name))
			.withBindGroupLayout(samplers)
			.withColorTargetState(blended ? new ColorTargetState(BlendFunction.TRANSLUCENT) : ColorTargetState.DEFAULT)
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.build());
	}
	*///?} else if >=26.1 {
	/*private static RenderPipeline mapPipeline(String name, String location, int samplers, boolean blended) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Diorama.id("pipeline/" + location))
			.withVertexShader(Diorama.id("core/fog"))
			.withFragmentShader(Diorama.id("core/" + name))
			.withSampler("Sampler0")
			.withColorTargetState(blended ? new ColorTargetState(BlendFunction.TRANSLUCENT) : ColorTargetState.DEFAULT)
			.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS);
		if (samplers > 1) {
			builder.withSampler("Sampler1");
		}
		if (samplers > 2) {
			builder.withSampler("Sampler2");
		}
		return register(builder.build());
	}
	*///?} else {
	/*private static RenderPipeline mapPipeline(String name, String location, int samplers, boolean blended) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Diorama.id("pipeline/" + location))
			.withVertexShader(Diorama.id("core/fog"))
			.withFragmentShader(Diorama.id("core/" + name))
			.withSampler("Sampler0")
			.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			// Nothing is written with the test off, but left at its default of on the pipeline counts
			// as wanting a depth texture: NeoForge's development runs then warn on every fog layer
			// draw, whose pass has none.
			.withDepthWrite(false);
		if (blended) {
			builder.withBlend(BlendFunction.TRANSLUCENT);
		} else {
			builder.withoutBlend();
		}
		if (samplers > 1) {
			builder.withSampler("Sampler1");
		}
		if (samplers > 2) {
			builder.withSampler("Sampler2");
		}
		return register(builder.build());
	}
	*///?}

	private DioramaPipelines() {
	}

	// Fabric: into vanilla's list, which shaders compile from. NeoForge constructs mods in parallel and
	// that list isn't thread-safe, so there DioramaNeoForge hands the pipeline to NeoForge's
	// RegisterRenderPipelinesEvent instead.
	private static RenderPipeline register(RenderPipeline pipeline) {
		//? if fabric {
		return RenderPipelines.register(pipeline);
		//?} else {
		/*return pipeline;
		*///?}
	}

	// Forces class loading so the pipeline is registered before shaders compile.
	public static void init() {
	}
}
//?}
