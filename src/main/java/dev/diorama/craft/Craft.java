package dev.diorama.craft;

import dev.diorama.MapStyle;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.CraftBaker;
import dev.diorama.bake.CraftBlocks;
import dev.diorama.bake.CraftShadow;
import dev.diorama.bake.Mipmaps;
import dev.diorama.render.gpu.MapTexture;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** One craft on the map: live, or parked where it was left. Client and render thread. */
final class Craft {
	final UUID id;
	@Nullable Body body;
	Body.@Nullable Blocks blocks;
	/** The pose at the end of the last tick, or where it was parked. */
	final double[] pose = new double[12];
	boolean posed;
	/** The map has been told this craft came out of the world. */
	boolean announced;
	boolean parked;
	long movedAt;
	/** Whether it has stood still long enough to stay on the map since it last moved. */
	boolean settled;
	final double[] anchor = new double[3];
	long nearSince = -1;

	/** The pose this frame, filled by the painter. */
	final double[] framePose = new double[12];

	/** What an image or a shadow is made from, besides the pose: a change to any makes it again. */
	record Inputs(CraftBlocks blocks, MapStyle style, BlockLooks looks, boolean fast) {
	}

	CraftBaker.@Nullable Image image;
	@Nullable MapTexture texture;
	@Nullable CompletableFuture<CraftBaker.@Nullable Image> pending;
	/** What the image, or the bake in flight, is made from. */
	@Nullable Inputs imageFrom;
	/** The turn the last bake was asked for. */
	double @Nullable [] triedLinear;
	/** The height of the grid origin the image, or the bake in flight, is made at. */
	double imageY;

	CraftShadow.@Nullable Image shadow;
	@Nullable MapTexture shadowTexture;
	/** How long the last shadow and image took to make, written on the baker thread, and when the image was started. */
	volatile long shadowCost;
	volatile long imageCost;
	long imageAt;
	/** {@link #shadowTexture}'s size: the last shadow may have had nothing to show, so no texture of its own. */
	int shadowTextureEdge;
	@Nullable CompletableFuture<CraftShadow.@Nullable Image> shadowPending;
	@Nullable Inputs shadowFrom;
	long shadowAt;
	/** The map pixels its shadow may cover with its middle at {@link #shadowAreaMiddle}, found at {@link #shadowAreaAt}. */
	int @Nullable [] shadowArea;
	double @Nullable [] shadowAreaMiddle;
	long shadowAreaAt;

	/** The image with what nearer things hide cut out, and what it was cut for. */
	@Nullable MapTexture cut;
	@Nullable Object cutFor;
	/** Which pixels of an image the world's blocks shade from the sun, what that was worked out for, and when. */
	CraftPainter.@Nullable SunMask sunMask;
	java.util.concurrent.@Nullable CompletableFuture<CraftPainter.@Nullable SunMask> sunPending;
	@Nullable Object sunFor;
	long sunAt;
	volatile long sunCost;
	long cutAt;
	int cutEdge;

	Craft(UUID id) {
		this.id = id;
	}

	/** The world position of the middle of the grid at {@code pose}. Only for a craft with blocks. */
	double[] center(double[] pose) {
		return center(pose, this.blocks.blocks());
	}

	static double[] center(double[] p, CraftBlocks b) {
		double hx = b.sizeX / 2.0;
		double hy = b.sizeY / 2.0;
		double hz = b.sizeZ / 2.0;
		return new double[] {
			p[0] + p[3] * hx + p[6] * hy + p[9] * hz,
			p[1] + p[4] * hx + p[7] * hy + p[10] * hz,
			p[2] + p[5] * hx + p[8] * hy + p[11] * hz,
		};
	}

	/** The world box the grid fills at {@code pose}, in whole blocks: min x y z, then max x y z. Only for a craft with blocks. */
	int[] box(double[] pose) {
		double[] box = this.blocks.blocks().box(pose);
		int[] blocks = new int[6];
		for (int i = 0; i < 6; i++) {
			blocks[i] = (int) Math.floor(box[i]);
		}
		return blocks;
	}

	/**
	 * Writes a mip chain to a texture: {@code old} if it {@code fits}, else a new one of
	 * {@code edge} (closing {@code old}).
	 */
	static MapTexture put(@Nullable MapTexture old, boolean fits, String name, int edge, int[][] mips) {
		MapTexture texture = old;
		if (texture != null && !fits) {
			texture.close();
			texture = null;
		}
		if (texture == null) {
			texture = MapTexture.create(name, edge, Mipmaps.LEVELS);
		}
		for (int level = 0; level < Mipmaps.LEVELS; level++) {
			texture.write(level, 0, 0, edge >> level, mips[level]);
		}
		return texture;
	}

	void close() {
		for (MapTexture texture : new MapTexture[] {this.texture, this.shadowTexture, this.cut}) {
			if (texture != null) {
				texture.close();
			}
		}
		this.texture = null;
		this.shadowTexture = null;
		this.cut = null;
		this.image = null;
		this.shadow = null;
		this.cutFor = null;
	}
}
