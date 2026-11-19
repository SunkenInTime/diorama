package dev.diorama.render;

import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;

/**
 * A canvas that turns the shapes given by their corners about a point, for a
 * {@link MapViewport.View} with a turn: the tiles, the fog and what else lies on the ground. The
 * batched calls and the game's own textures pass through as they are, since those are icons, which
 * stay upright.
 */
final class TurnedCanvas implements MapCanvas {
	private final MapCanvas canvas;
	private final float cx;
	private final float cy;
	private final float sin;
	private final float cos;

	TurnedCanvas(MapCanvas canvas, float cx, float cy, float turn) {
		this.canvas = canvas;
		this.cx = cx;
		this.cy = cy;
		this.sin = (float) Math.sin(turn);
		this.cos = (float) Math.cos(turn);
	}

	private float[] turned(float[] corners) {
		float[] turned = new float[corners.length];
		for (int i = 0; i < corners.length; i += 2) {
			float dx = corners[i] - this.cx;
			float dy = corners[i + 1] - this.cy;
			turned[i] = this.cx + dx * this.cos - dy * this.sin;
			turned[i + 1] = this.cy + dx * this.sin + dy * this.cos;
		}
		return turned;
	}

	@Override
	public void quad(MapTexture texture, Sampling sampling, float[] corners, float[] uvs, int argb) {
		this.canvas.quad(texture, sampling, this.turned(corners), uvs, argb);
	}

	@Override
	public void nightTile(MapTexture tile, Sampling sampling, MapTexture light, float[] corners, float[] uvs, int params) {
		this.canvas.nightTile(tile, sampling, light, this.turned(corners), uvs, params);
	}

	@Override
	public void solid(float[] corners, int argb) {
		this.canvas.solid(this.turned(corners), argb);
	}

	@Override
	public void blit(Identifier texture, int x, int y, int u, int v, int w, int h, int texW, int texH, int argb) {
		this.canvas.blit(texture, x, y, u, v, w, h, texW, texH, argb);
	}

	@Override
	public void solids(float[] xyz, int[] colors, int quads) {
		this.canvas.solids(xyz, colors, quads);
	}

	@Override
	public void blits(Identifier texture, float[] xyz, float[] uvs, int[] colors, int quads) {
		this.canvas.blits(texture, xyz, uvs, colors, quads);
	}

	@Override
	public void rects(MapTexture texture, Sampling sampling, float[] xyz, float[] uvs, int[] colors, int quads) {
		this.canvas.rects(texture, sampling, xyz, uvs, colors, quads);
	}

	@Override
	public void playerFace(AbstractClientPlayer player, int x, int y, int size, int argb) {
		this.canvas.playerFace(player, x, y, size, argb);
	}

	@Override
	public void fog(MapTexture mask, MapTexture noise, MapTexture detail, float[] corners, float[] uvs, int x, int y, int width, int height, int params) {
		this.canvas.fog(mask, noise, detail, this.turned(corners), uvs, x, y, width, height, params);
	}
}
