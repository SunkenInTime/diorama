//? if >=1.21.6 {
package dev.diorama.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Arrays;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jspecify.annotations.Nullable;

// Many GUI quads as one piece of GUI render state (1.21.6 on), so they draw in one go: corners
// already in GUI coordinates, x y z per corner (z unused), four corners per quad, one colour per
// quad, optional UVs. For the radar's icons (IconBatch); one element per icon piece made the GUI's
// layering search through every earlier piece, and drew each piece on its own.
public record QuadsElement(
	RenderPipeline pipeline,
	TextureSetup textureSetup,
	float[] xyz,
	float @Nullable [] uvs,
	int[] colors,
	int quads,
	@Nullable ScreenRectangle scissorArea,
	@Nullable ScreenRectangle bounds
) implements GuiElementRenderState {
	public static QuadsElement of(
		RenderPipeline pipeline, TextureSetup textures, float[] xyz, float @Nullable [] uvs, int[] colors, int quads, @Nullable ScreenRectangle scissor
	) {
		return make(pipeline, textures, xyz, uvs, colors, quads, scissor, true);
	}

	/**
	 * {@link #of}, holding on to the arrays instead of copying them: for a caller that leaves them
	 * alone until the frame has been drawn, so that drawing many quads a frame copies nothing.
	 */
	public static QuadsElement kept(
		RenderPipeline pipeline, TextureSetup textures, float[] xyz, float @Nullable [] uvs, int[] colors, int quads, @Nullable ScreenRectangle scissor
	) {
		return make(pipeline, textures, xyz, uvs, colors, quads, scissor, false);
	}

	private static QuadsElement make(
		RenderPipeline pipeline, TextureSetup textures, float[] xyz, float @Nullable [] uvs, int[] colors, int quads, @Nullable ScreenRectangle scissor, boolean copy
	) {
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int i = 0; i < quads * 12; i += 3) {
			minX = Math.min(minX, xyz[i]);
			maxX = Math.max(maxX, xyz[i]);
			minY = Math.min(minY, xyz[i + 1]);
			maxY = Math.max(maxY, xyz[i + 1]);
		}
		int x0 = (int) Math.floor(minX);
		int y0 = (int) Math.floor(minY);
		ScreenRectangle bounds = new ScreenRectangle(x0, y0, (int) Math.ceil(maxX) - x0, (int) Math.ceil(maxY) - y0);
		if (scissor != null) {
			bounds = scissor.intersection(bounds);
		}
		if (!copy) {
			return new QuadsElement(pipeline, textures, xyz, uvs, colors, quads, scissor, bounds);
		}
		return new QuadsElement(
			pipeline, textures, Arrays.copyOf(xyz, quads * 12), uvs == null ? null : Arrays.copyOf(uvs, quads * 8),
			Arrays.copyOf(colors, quads), quads, scissor, bounds
		);
	}

	//? if >=1.21.9 {
	@Override
	public void buildVertices(VertexConsumer consumer) {
		for (int i = 0; i < this.quads * 4; i++) {
			this.vertex(consumer.addVertex(this.xyz[i * 3], this.xyz[i * 3 + 1], 0), i);
		}
	}
	//?} else {
	/*// GUI elements were drawn at a depth the renderer handed in.
	@Override
	public void buildVertices(VertexConsumer consumer, float z) {
		for (int i = 0; i < this.quads * 4; i++) {
			this.vertex(consumer.addVertex(this.xyz[i * 3], this.xyz[i * 3 + 1], z), i);
		}
	}
	*///?}

	private void vertex(VertexConsumer vertex, int i) {
		if (this.uvs != null) {
			vertex.setUv(this.uvs[i * 2], this.uvs[i * 2 + 1]);
		}
		vertex.setColor(this.colors[i / 4]);
	}
}
//?}
