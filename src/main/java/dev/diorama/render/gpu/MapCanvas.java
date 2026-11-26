package dev.diorama.render.gpu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;

/**
 * Draws the map's own shapes into a GUI frame: quads with float corners and the mod's textures,
 * the fog, mob and player faces. Vanilla GUI draws only take whole-pixel positions, and the map
 * pans smoothly. From 1.21.6 this submits GUI render state; before, it draws straight away. Get
 * one per draw call; it holds no GPU state. Positions are in the GUI's current pose.
 */
public interface MapCanvas {
	static MapCanvas of(GuiGraphicsExtractor graphics) {
		//? if >=1.21.6 {
		return new StateCanvas(graphics);
		//?} else {
		/*return new ImmediateCanvas(graphics);
		*///?}
	}

	/**
	 * A textured quad multiplied by {@code argb}, or several in one draw.
	 *
	 * @param corners x0 y0 x1 y1 x2 y2 x3 y3, counter-clockwise from the top left, then the next quad's
	 * @param uvs u0 v0 u1 v1 u2 v2 u3 v3, matching the corners
	 */
	void quad(MapTexture texture, Sampling sampling, float[] corners, float[] uvs, int argb);

	/** An axis-aligned textured rectangle multiplied by {@code argb}. */
	default void rect(
		MapTexture texture, Sampling sampling, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb
	) {
		this.quad(
			texture, sampling,
			new float[] {x0, y0, x0, y1, x1, y1, x1, y0},
			new float[] {u0, v0, u0, v1, u1, v1, u1, v0},
			argb
		);
	}

	/**
	 * A tile at night with its block light, as {@link #quad} draws it by day: {@code params} is
	 * {@link dev.diorama.render.NightLook#params}. Where the night shader can't be drawn, the tile
	 * is drawn dimmed without its light.
	 */
	void nightTile(MapTexture tile, Sampling sampling, MapTexture light, float[] corners, float[] uvs, int params);

	/** An untextured quad in one colour, corners counter-clockwise (the other winding is culled), or several, like {@link #quad}. */
	void solid(float[] corners, int argb);

	/**
	 * A {@code w} x {@code h} rectangle of a game texture (a mob's skin) at (u, v), drawn at (x, y)
	 * the same size, in a texture of {@code texW} x {@code texH} pixels.
	 */
	void blit(Identifier texture, int x, int y, int u, int v, int w, int h, int texW, int texH, int argb);

	/**
	 * Many untextured quads in one draw. Unlike the other calls these take their corners already
	 * in GUI coordinates, without the current pose: x y z for each of four corners per quad, in
	 * {@link #rect}'s order, and one colour per quad.
	 */
	void solids(float[] xyz, int[] colors, int quads);

	/** Many quads of one game texture in one draw, like {@link #solids}, with u v for each corner. */
	void blits(Identifier texture, float[] xyz, float[] uvs, int[] colors, int quads);

	/** Many quads of one of the mod's textures in one draw, like {@link #blits}. */
	void rects(MapTexture texture, Sampling sampling, float[] xyz, float[] uvs, int[] colors, int quads);

	/**
	 * {@link #solids}, for arrays the caller leaves alone until the frame has been drawn: from
	 * 1.21.6, where the GUI keeps what it is given until the end of the frame, they are kept rather
	 * than copied. Before, everything draws straight away, so it is {@link #solids}.
	 */
	default void solidsKept(float[] xyz, int[] colors, int quads) {
		this.solids(xyz, colors, quads);
	}

	/** {@link #rects}, for arrays the caller leaves alone until the frame has been drawn, like {@link #solidsKept}. */
	default void rectsKept(MapTexture texture, Sampling sampling, float[] xyz, float[] uvs, int[] colors, int quads) {
		this.rects(texture, sampling, xyz, uvs, colors, quads);
	}

	/** A player's face with its hat layer, {@code size} across, at (x, y). */
	void playerFace(AbstractClientPlayer player, int x, int y, int size, int argb);

	/**
	 * The fog over a view: the known-area mask in sampler 0 (clamped), the noise in sampler 1
	 * (repeating), what the shader would otherwise work out from the mask in sampler 2 (clamped),
	 * and the shader's parameters packed into the vertex colour. Clipped to the view.
	 */
	default void fog(MapTexture mask, MapTexture noise, MapTexture detail, int x, int y, int width, int height, float u0, float v0, float u1, float v1, int params) {
		this.fog(
			mask, noise, detail,
			new float[] {x, y, x, y + height, x + width, y + height, x + width, y},
			new float[] {u0, v0, u0, v1, u1, v1, u1, v0},
			x, y, width, height, params
		);
	}

	/** The same over quads in a view, like {@link #quad} (the round minimap's disc), still clipped to the view. */
	void fog(MapTexture mask, MapTexture noise, MapTexture detail, float[] corners, float[] uvs, int x, int y, int width, int height, int params);
}
