//? if <1.21.6 {
/*package dev.diorama.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
//? if >=1.21.5 {
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
//?}

// The MapCanvas before 1.21.6, whose GUI draws straight away (or in batches it can flush) rather
// than through render state: each shape is a small mesh drawn at once through LegacyShaders, with
// its textures bound by hand. The GUI's scissor is already applied to such draws.
final class ImmediateCanvas implements MapCanvas {
	private final GuiGraphics graphics;

	ImmediateCanvas(GuiGraphics graphics) {
		this.graphics = graphics;
	}

	@Override
	public void quad(MapTexture texture, Sampling sampling, float[] corners, float[] uvs, int argb) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		Matrix4f pose = this.graphics.pose().last().pose();
		for (int i = 0; i < corners.length / 2; i++) {
			//? if >=1.21 {
			mesh.addVertex(pose, corners[i * 2], corners[i * 2 + 1], 0).setUv(uvs[i * 2], uvs[i * 2 + 1]).setColor(argb);
			//?} else {
			// Before 1.21 the vertex setters had other names, and each vertex was ended by hand.
			mesh.vertex(pose, corners[i * 2], corners[i * 2 + 1], 0).uv(uvs[i * 2], uvs[i * 2 + 1]).color(argb).endVertex();
			//?}
		}
		((LegacyTexture) texture).bind(0, sampling);
		// Without the shader the tile is sampled the way it's bound, a little softer.
		if (sampling == Sampling.TILE_AREA && LegacyShaders.tileReady()) {
			LegacyShaders.drawTile(mesh.buildOrThrow());
		} else {
			LegacyShaders.draw(LegacyShaders.TEXTURED, mesh.buildOrThrow());
		}
		unbind(0);
	}

	@Override
	public void nightTile(MapTexture tile, Sampling sampling, MapTexture light, float[] corners, float[] uvs, int params) {
		if (!LegacyShaders.tileNightReady()) {
			this.quad(tile, sampling, corners, uvs, dev.diorama.render.NightLook.tintOf(params));
			return;
		}
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		Matrix4f pose = this.graphics.pose().last().pose();
		for (int i = 0; i < corners.length / 2; i++) {
			//? if >=1.21 {
			mesh.addVertex(pose, corners[i * 2], corners[i * 2 + 1], 0).setUv(uvs[i * 2], uvs[i * 2 + 1]).setColor(params);
			//?} else {
			mesh.vertex(pose, corners[i * 2], corners[i * 2 + 1], 0).uv(uvs[i * 2], uvs[i * 2 + 1]).color(params).endVertex();
			//?}
		}
		((LegacyTexture) tile).bind(0, sampling);
		((LegacyTexture) light).bind(1, Sampling.TILE_SMOOTH);
		LegacyShaders.drawTileNight(mesh.buildOrThrow());
		unbind(0);
		unbind(1);
	}

	@Override
	public void solid(float[] corners, int argb) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_COLOR);
		Matrix4f pose = this.graphics.pose().last().pose();
		for (int i = 0; i < corners.length / 2; i++) {
			//? if >=1.21 {
			mesh.addVertex(pose, corners[i * 2], corners[i * 2 + 1], 0).setColor(argb);
			//?} else {
			mesh.vertex(pose, corners[i * 2], corners[i * 2 + 1], 0).color(argb).endVertex();
			//?}
		}
		LegacyShaders.draw(LegacyShaders.SOLID, mesh.buildOrThrow());
	}

	@Override
	public void blit(ResourceLocation texture, int x, int y, int u, int v, int w, int h, int texW, int texH, int argb) {
		this.gameRect(texture, x, y, x + w, y + h, (float) u / texW, (float) v / texH, (float) (u + w) / texW, (float) (v + h) / texH, argb);
	}

	@Override
	public void solids(float[] xyz, int[] colors, int quads) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_COLOR);
		for (int i = 0; i < quads * 4; i++) {
			//? if >=1.21 {
			mesh.addVertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).setColor(colors[i / 4]);
			//?} else {
			mesh.vertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).color(colors[i / 4]).endVertex();
			//?}
		}
		LegacyShaders.draw(LegacyShaders.SOLID, mesh.buildOrThrow());
	}

	@Override
	public void blits(ResourceLocation texture, float[] xyz, float[] uvs, int[] colors, int quads) {
		BufferBuilder mesh = this.mesh(xyz, uvs, colors, quads);
		//? if >=1.21.5 {
		RenderSystem.setShaderTexture(0, Minecraft.getInstance().getTextureManager().getTexture(texture).getTexture());
		//?} else {
		RenderSystem.setShaderTexture(0, texture);
		//?}
		LegacyShaders.draw(LegacyShaders.TEXTURED, mesh.buildOrThrow());
		unbind(0);
	}

	@Override
	public void rects(MapTexture texture, Sampling sampling, float[] xyz, float[] uvs, int[] colors, int quads) {
		BufferBuilder mesh = this.mesh(xyz, uvs, colors, quads);
		((LegacyTexture) texture).bind(0, sampling);
		LegacyShaders.draw(LegacyShaders.TEXTURED, mesh.buildOrThrow());
		unbind(0);
	}

	// Textured quads whose corners are already in GUI coordinates.
	private BufferBuilder mesh(float[] xyz, float[] uvs, int[] colors, int quads) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		for (int i = 0; i < quads * 4; i++) {
			//? if >=1.21 {
			mesh.addVertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).setUv(uvs[i * 2], uvs[i * 2 + 1]).setColor(colors[i / 4]);
			//?} else {
			mesh.vertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).uv(uvs[i * 2], uvs[i * 2 + 1]).color(colors[i / 4]).endVertex();
			//?}
		}
		return mesh;
	}

	@Override
	public void playerFace(AbstractClientPlayer player, int x, int y, int size, int argb) {
		// The face and the hat layer over it, as vanilla's PlayerFaceRenderer cuts them from a skin.
		ResourceLocation skin = player.getSkin().texture();
		this.gameRect(skin, x, y, x + size, y + size, 8 / 64.0F, 8 / 64.0F, 16 / 64.0F, 16 / 64.0F, argb);
		this.gameRect(skin, x, y, x + size, y + size, 40 / 64.0F, 8 / 64.0F, 48 / 64.0F, 16 / 64.0F, argb);
	}

	@Override
	public void fog(MapTexture mask, MapTexture noise, MapTexture detail, float[] corners, float[] uvs, int x, int y, int width, int height, int params) {
		if (!LegacyShaders.fogReady()) {
			return;
		}
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		Matrix4f pose = this.graphics.pose().last().pose();
		for (int i = 0; i < corners.length / 2; i++) {
			//? if >=1.21 {
			mesh.addVertex(pose, corners[i * 2], corners[i * 2 + 1], 0).setUv(uvs[i * 2], uvs[i * 2 + 1]).setColor(params);
			//?} else {
			mesh.vertex(pose, corners[i * 2], corners[i * 2 + 1], 0).uv(uvs[i * 2], uvs[i * 2 + 1]).color(params).endVertex();
			//?}
		}
		((LegacyTexture) mask).bind(0, Sampling.CLAMP_LINEAR);
		((LegacyTexture) noise).bind(1, Sampling.REPEAT_LINEAR);
		((LegacyTexture) detail).bind(2, Sampling.CLAMP_LINEAR);
		LegacyShaders.drawFog(mesh.buildOrThrow());
		unbind(0);
		unbind(1);
		unbind(2);
	}

	// FogLayer before 1.21.6: the fog drawn into the layer's texture ({@code size} on a side) rather
	// than the screen, between this canvas's other draws. False if the shader isn't ready.
	boolean fogLayer(MapTexture target, int size, MapTexture mask, MapTexture noise, MapTexture detail, int width, int height, float u0, float v0, float u1, float v1, int params) {
		if (!LegacyShaders.fogReady() || !LegacyShaders.fogLayerReady()) {
			return false;
		}
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		// Counter-clockwise with y up: the quad's top edge (v0) lands in the texture's first row,
		// where the map reads v 0.
		//? if >=1.21 {
		mesh.addVertex(0, 0, 0).setUv(u0, v0).setColor(params);
		mesh.addVertex(width, 0, 0).setUv(u1, v0).setColor(params);
		mesh.addVertex(width, height, 0).setUv(u1, v1).setColor(params);
		mesh.addVertex(0, height, 0).setUv(u0, v1).setColor(params);
		//?} else {
		mesh.vertex(0, 0, 0).uv(u0, v0).color(params).endVertex();
		mesh.vertex(width, 0, 0).uv(u1, v0).color(params).endVertex();
		mesh.vertex(width, height, 0).uv(u1, v1).color(params).endVertex();
		mesh.vertex(0, height, 0).uv(u0, v1).color(params).endVertex();
		//?}
		// The layer's own depth as well as its own projection. The GUI's model view moves everything
		// back by an amount that isn't fixed: NeoForge 1.21.1 to 1.21.4 adds 200 for every HUD layer
		// mods register, and in a big pack that put the quad past any range chosen here, so nothing
		// was drawn and the map showed no fog (#383).
		Matrix4f ortho = new Matrix4f().setOrtho(0, size, 0, size, 1000, 3000);
		//? if >=1.21 {
		RenderSystem.getModelViewStack().pushMatrix();
		RenderSystem.getModelViewStack().translation(0, 0, -2000);
		//?} else {
		RenderSystem.getModelViewStack().pushPose();
		RenderSystem.getModelViewStack().setIdentity();
		RenderSystem.getModelViewStack().translate(0, 0, -2000);
		//?}
		//? if <1.21.2 {
		RenderSystem.applyModelViewMatrix();
		//?}
		RenderSystem.backupProjectionMatrix();
		//? if >=1.21.2 {
		RenderSystem.setProjectionMatrix(ortho, com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
		//?} else {
		RenderSystem.setProjectionMatrix(ortho, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
		//?}
		((LegacyTexture) mask).bind(0, Sampling.CLAMP_LINEAR);
		((LegacyTexture) noise).bind(1, Sampling.REPEAT_LINEAR);
		((LegacyTexture) detail).bind(2, Sampling.CLAMP_LINEAR);
		//? if >=1.21.5 {
		LegacyShaders.drawFogLayer(mesh.buildOrThrow(), ((BlazeTexture) target).texture());
		//?} else {
		// The GUI's scissor is in the screen's pixels: off while drawing into the layer.
		boolean scissor = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
		org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
		int framebuffer = ((GlIdTexture) target).framebuffer();
		// Binds the layer's framebuffer, then the caller's again.
		LegacyShaders.drawFogLayer(mesh.buildOrThrow(), framebuffer, size);
		if (scissor) {
			org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
		}
		//?}
		unbind(0);
		unbind(1);
		unbind(2);
		RenderSystem.restoreProjectionMatrix();
		//? if >=1.21 {
		RenderSystem.getModelViewStack().popMatrix();
		//?} else {
		RenderSystem.getModelViewStack().popPose();
		//?}
		//? if <1.21.2 {
		RenderSystem.applyModelViewMatrix();
		//?}
		return true;
	}

	// A rectangle of a game texture (a skin), sampled the way the game set that texture up.
	private void gameRect(ResourceLocation texture, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		this.rect(mesh, x0, y0, x1, y1, u0, v0, u1, v1, argb);
		//? if >=1.21.5 {
		RenderSystem.setShaderTexture(0, Minecraft.getInstance().getTextureManager().getTexture(texture).getTexture());
		//?} else {
		RenderSystem.setShaderTexture(0, texture);
		//?}
		LegacyShaders.draw(LegacyShaders.TEXTURED, mesh.buildOrThrow());
		unbind(0);
	}

	private void rect(BufferBuilder mesh, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		Matrix4f pose = this.graphics.pose().last().pose();
		//? if >=1.21 {
		mesh.addVertex(pose, x0, y0, 0).setUv(u0, v0).setColor(argb);
		mesh.addVertex(pose, x0, y1, 0).setUv(u0, v1).setColor(argb);
		mesh.addVertex(pose, x1, y1, 0).setUv(u1, v1).setColor(argb);
		mesh.addVertex(pose, x1, y0, 0).setUv(u1, v0).setColor(argb);
		//?} else {
		mesh.vertex(pose, x0, y0, 0).uv(u0, v0).color(argb).endVertex();
		mesh.vertex(pose, x0, y1, 0).uv(u0, v1).color(argb).endVertex();
		mesh.vertex(pose, x1, y1, 0).uv(u1, v1).color(argb).endVertex();
		mesh.vertex(pose, x1, y0, 0).uv(u1, v0).color(argb).endVertex();
		//?}
	}

	private BufferBuilder begin(VertexFormat format) {
		// Draws the fills and text the GUI has batched so far first, so they stay underneath.
		this.graphics.flush();
		//? if >=1.21 {
		return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, format);
		//?} else {
		// Before 1.21 the tesselator had one builder, begun again for each mesh.
		BufferBuilder mesh = Tesselator.getInstance().getBuilder();
		mesh.begin(VertexFormat.Mode.QUADS, format);
		return mesh;
		//?}
	}

	// Clears a texture slot after a draw, so a later vanilla draw doesn't pick up our texture.
	private static void unbind(int slot) {
		//? if >=1.21.5 {
		RenderSystem.setShaderTexture(slot, (GpuTexture) null);
		//?} else {
		RenderSystem.setShaderTexture(slot, 0);
		//?}
	}
}
*///?}
