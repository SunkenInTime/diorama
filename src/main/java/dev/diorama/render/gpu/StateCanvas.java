//? if >=1.21.6 {
package dev.diorama.render.gpu;

import dev.diorama.render.DioramaPipelines;
import dev.diorama.render.QuadElement;
import dev.diorama.render.QuadsElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1 {
import net.minecraft.client.gui.components.PlayerFaceExtractor;
//?} else {
/*import net.minecraft.client.gui.components.PlayerFaceRenderer;
*///?}
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

// The MapCanvas from 1.21.6 on: every shape is a GUI element submitted to the frame's render state.
final class StateCanvas implements MapCanvas {
	private final GuiGraphicsExtractor graphics;

	StateCanvas(GuiGraphicsExtractor graphics) {
		this.graphics = graphics;
	}

	@Override
	public void quad(MapTexture texture, Sampling sampling, float[] corners, float[] uvs, int argb) {
		this.graphics.guiRenderState.addGuiElement(QuadElement.of(
			sampling == Sampling.TILE_AREA ? DioramaPipelines.TILE : RenderPipelines.GUI_TEXTURED,
			((StateTexture) texture).single(sampling),
			new Matrix3x2f(this.graphics.pose()),
			corners,
			uvs,
			argb,
			this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void nightTile(MapTexture tile, Sampling sampling, MapTexture light, float[] corners, float[] uvs, int params) {
		this.graphics.guiRenderState.addGuiElement(QuadElement.of(
			DioramaPipelines.TILE_NIGHT,
			StateTexture.pair((StateTexture) tile, sampling, (StateTexture) light, Sampling.TILE_SMOOTH),
			new Matrix3x2f(this.graphics.pose()),
			corners,
			uvs,
			params,
			this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void solid(float[] corners, int argb) {
		this.graphics.guiRenderState.addGuiElement(QuadElement.of(
			RenderPipelines.GUI,
			TextureSetup.noTexture(),
			new Matrix3x2f(this.graphics.pose()),
			corners,
			null,
			argb,
			this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void blit(Identifier texture, int x, int y, int u, int v, int w, int h, int texW, int texH, int argb) {
		this.graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h, w, h, texW, texH, argb);
	}

	@Override
	public void solids(float[] xyz, int[] colors, int quads) {
		this.graphics.guiRenderState.addGuiElement(QuadsElement.of(
			RenderPipelines.GUI, TextureSetup.noTexture(), xyz, null, colors, quads, this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void blits(Identifier texture, float[] xyz, float[] uvs, int[] colors, int quads) {
		// Set up the way the GUI's own blit sets up a game texture.
		AbstractTexture gameTexture = Minecraft.getInstance().getTextureManager().getTexture(texture);
		//? if >=1.21.11 {
		TextureSetup textures = TextureSetup.singleTexture(gameTexture.getTextureView(), gameTexture.getSampler());
		//?} else {
		/*TextureSetup textures = TextureSetup.singleTexture(gameTexture.getTextureView());
		*///?}
		this.graphics.guiRenderState.addGuiElement(QuadsElement.of(
			RenderPipelines.GUI_TEXTURED, textures, xyz, uvs, colors, quads, this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void rects(MapTexture texture, Sampling sampling, float[] xyz, float[] uvs, int[] colors, int quads) {
		this.graphics.guiRenderState.addGuiElement(QuadsElement.of(
			RenderPipelines.GUI_TEXTURED, ((StateTexture) texture).single(sampling), xyz, uvs, colors, quads, this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void solidsKept(float[] xyz, int[] colors, int quads) {
		this.graphics.guiRenderState.addGuiElement(QuadsElement.kept(
			RenderPipelines.GUI, TextureSetup.noTexture(), xyz, null, colors, quads, this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void rectsKept(MapTexture texture, Sampling sampling, float[] xyz, float[] uvs, int[] colors, int quads) {
		this.graphics.guiRenderState.addGuiElement(QuadsElement.kept(
			RenderPipelines.GUI_TEXTURED, ((StateTexture) texture).single(sampling), xyz, uvs, colors, quads, this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void playerFace(AbstractClientPlayer player, int x, int y, int size, int argb) {
		//? if >=26.1 {
		PlayerFaceExtractor.extractRenderState(this.graphics, player.getSkin(), x, y, size, argb);
		//?} else {
		/*PlayerFaceRenderer.draw(this.graphics, player.getSkin(), x, y, size, argb);
		*///?}
	}

	@Override
	public void fog(MapTexture mask, MapTexture noise, MapTexture detail, float[] corners, float[] uvs, int x, int y, int width, int height, int params) {
		// Cut to the view and to the scissor on top, which is on the screen already: the view's
		// rectangle is put on the screen through the pose first, as vanilla does with what it draws,
		// so an addon that moves its pose keeps its fog where its view is.
		Matrix3x2f pose = new Matrix3x2f(this.graphics.pose());
		ScreenRectangle clip = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
		ScreenRectangle scissor = this.graphics.scissorStack.peek();
		if (scissor != null) {
			clip = clip.intersection(scissor);
			if (clip == null) {
				return;
			}
		}
		this.graphics.guiRenderState.addGuiElement(QuadElement.of(
			DioramaPipelines.FOG,
			StateTexture.triple((StateTexture) mask, Sampling.CLAMP_LINEAR, (StateTexture) noise, Sampling.REPEAT_LINEAR, (StateTexture) detail, Sampling.CLAMP_LINEAR),
			pose,
			corners,
			uvs,
			params,
			clip
		));
	}
}
//?}
