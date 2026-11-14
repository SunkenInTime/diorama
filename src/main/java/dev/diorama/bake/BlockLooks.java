package dev.diorama.bake;

import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.NaturalSpawner;
import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import dev.diorama.data.Copycats;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
//? if >=26.1 {
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
//?} else if >=1.21.5 {
/*import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
*///?} else {
/*import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
*///?}
//? if <26.1 && fabric {
/*import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.loader.api.FabricLoader;
*///?} else if <26.1 && >=1.21.9 {
/*import net.minecraft.data.AtlasIds;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
*///?} else if <26.1 {
/*import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
*///?}
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if >=26.1 {
import net.minecraft.client.resources.model.geometry.BakedQuad;
//?} else {
/*import net.minecraft.client.renderer.block.model.BakedQuad;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * What each block state looks like on the map: how rays treat it, and its average top and
 * front colors taken from the game's own textures. A state's look is worked out the first time
 * anything asks for its id through {@link #ensure} (or {@link #lookId}, {@link #bakeLookId},
 * {@link #copycat}, {@link #sameLook}), on whichever thread asks, as Xaero's World Map works a
 * block's colour out when its map writer first meets the block. A big modpack has hundreds of
 * thousands of states and a world uses a few thousand of them; asking every state's model up
 * front cost players minutes on each world load and resource reload, most of all under
 * ModernFix's dynamic resources, which bakes a model only when it's asked for. Once worked out,
 * a look never changes, so bake threads read it freely.
 *
 * <p>When a mod with copycats is installed, ids past the block state registry are looks for
 * filled copycats ({@link #copycat}), added by bake threads as they first meet them. Each is
 * written before its id is handed out, and never changes after.
 */
public final class BlockLooks extends Looks {
	/** Held while a look is worked out: one at a time, across tables, since they share mods' code. */
	private static final Object MAKING = new Object();
	/**
	 * Held only while a texture's pixels are read, and by a texture starting to free them, so a
	 * reload never waits on mods' model or tint code. Taken inside {@link #MAKING}, never around it.
	 */
	private static final Object IMAGES = new Object();

	private static volatile BlockLooks current;
	private static int builds;
	/** The models the current table was built from; a resource reload replaces them. */
	private static Object builtFrom;

	// What working a look out reads, all of it taken on the client thread as the table is made and
	// used under MAKING only.
	//? if >=26.1 {
	private final BlockStateModelSet models;
	//?} else {
	/*private final BlockModelShaper models;
	*///?}
	private final BlockColors colors;
	/** The block atlas as the table was made, for textures a reload freed (see {@link #currentSprite}). */
	private final @Nullable TextureAtlas atlas;
	private final Map<TextureAtlasSprite, float[]> spriteCache = new IdentityHashMap<>();
	private final RandomSource random = RandomSource.create(42L);
	private final TintProbe probe = new TintProbe();
	private final Map<Fluid, FluidLook> fluidLooks = new IdentityHashMap<>();
	//? if >=26.1 {
	private final List<BlockStateModelPart> parts = new ArrayList<>();
	//?} else if >=1.21.5 {
	/*private final List<BlockModelPart> parts = new ArrayList<>();
	*///?}
	private boolean warnedModel;
	/** Whether the look being made may read models and textures (not while they're reloading). */
	private boolean withModels;
	/**
	 * Set when a look was worked out while resources were reloading, without the models and
	 * textures the reload is replacing: {@link #isStale} then has the table made again.
	 */
	private volatile boolean provisional;
	/** How many looks have been worked out, and the time it took, for the log. */
	private int made;
	private long makingNanos;

	//? if >=26.1 {
	private BlockLooks(int registrySize, int size, BlockStateModelSet models, BlockColors colors, @Nullable TextureAtlas atlas) {
	//?} else {
	/*private BlockLooks(int registrySize, int size, BlockModelShaper models, BlockColors colors, @Nullable TextureAtlas atlas) {
	*///?}
		super(registrySize, size);
		this.models = models;
		this.colors = colors;
		this.atlas = atlas;
	}

	public static BlockLooks get() {
		return current;
	}

	/**
	 * {@link Looks#bakeId} against the map's current table, for a reader without one of its own.
	 */
	public static int bakeId(BlockState state) {
		return bakeId(state, current);
	}

	/** For tests: how many times the table has been built. */
	public static int builds() {
		return builds;
	}

	public static void invalidate() {
		current = null;
	}

	/**
	 * True when resource packs were reloaded since the table was built, or a look in it was worked
	 * out while they were reloading.
	 */
	public static boolean isStale() {
		BlockLooks looks = current;
		if (looks == null) {
			return false;
		}
		if (looks.provisional) {
			return true;
		}
		//? if >=26.1 {
		return builtFrom != Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		//?} else {
		/*return builtFrom != Minecraft.getInstance().getModelManager().getMissingBlockStateModel();
		*///?}
	}

	/**
	 * Makes a new, empty table; looks are worked out as they're asked for. Must run on the client
	 * thread, after models are loaded.
	 */
	public static BlockLooks build() {
		long start = System.nanoTime();
		BlockLooks last = current;
		if (last != null) {
			Diorama.LOGGER.info("The last block color table worked out {}", last.madeSoFar());
		}
		BlockLooks looks = detached();
		current = looks;
		builds++;
		//? if >=26.1 {
		builtFrom = looks.models;
		//?} else {
		/*// The model shaper outlives resource reloads; the missing model is baked anew with each.
		builtFrom = Minecraft.getInstance().getModelManager().getMissingBlockStateModel();
		*///?}
		Diorama.LOGGER.info("Block color table for {} states set up in {} ms; each state's colors are worked out when the map first meets it",
			looks.registrySize, (System.nanoTime() - start) / 1_000_000);
		return looks;
	}

	/** A new, empty table that isn't the map's: {@link #build} without making it current. Client thread. */
	public static BlockLooks detached() {
		Minecraft mc = Minecraft.getInstance();
		//? if >=26.1 {
		BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
		//?} else {
		/*BlockModelShaper models = mc.getModelManager().getBlockModelShaper();
		*///?}
		int size = Block.BLOCK_STATE_REGISTRY.size();
		TextureAtlas atlas = null;
		try {
			// Found from a texture of a block every version has, so no version's atlas id is needed.
			//? if >=26.1 {
			TextureAtlasSprite stone = particle(models.get(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
			//?} else {
			/*TextureAtlasSprite stone = particle(models.getBlockModel(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
			*///?}
			if (stone != null && mc.getTextureManager().getTexture(stone.atlasLocation()) instanceof TextureAtlas blocks) {
				atlas = blocks;
			}
		} catch (RuntimeException | Error e) {
			// Without it, a texture a reload freed adds no colour.
		}
		BlockLooks looks = new BlockLooks(size, Copycats.INSTALLED ? size + COPYCAT_LOOKS : size, models, mc.getBlockColors(), atlas);
		warnedFreed = false;
		synchronized (MAKING) {
			// Before any block: those tinted like water ask for the same colour as water does. The
			// rest are the game's as they are now, which a mod may have swapped for its own.
			looks.probe.reset();
			BiomeTints.setGame(
				BiomeColors.GRASS_COLOR_RESOLVER,
				BiomeColors.FOLIAGE_COLOR_RESOLVER,
				//? if >=1.21.5 {
				BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER,
				//?} else {
				/*// Dry foliage (leaf litter) came in 1.21.5; nothing reads this slot before.
				BiomeColors.FOLIAGE_COLOR_RESOLVER,
				*///?}
				waterResolver(looks.probe)
			);
		}
		// What a bake reads where a section's palette has no entry.
		looks.ensure(VoxelReader.AIR_ID);
		return looks;
	}

	/** For the log: how many looks this table has worked out, and the milliseconds they took. */
	public String madeSoFar() {
		synchronized (MAKING) {
			return (this.made == 1 ? "1 look" : this.made + " looks") + " in " + this.makingNanos / 1_000_000 + " ms";
		}
	}

	/** Works out one state's look, unless another thread just did. */
	@Override
	protected void make(int id) {
		synchronized (MAKING) {
			if (this.kind[id] != NOT_YET) {
				return;
			}
			if (Block.BLOCK_STATE_REGISTRY.size() != this.registrySize) {
				// NeoForge or Forge are numbering states again (see bakeId), and this id may name
				// another state now: leave the look to be worked out by the table made after.
				return;
			}
			long start = System.nanoTime();
			BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
			// While a reload is swapping the models and textures, a look takes neither, and the table
			// is made again once the reload is over. (Textures it frees are kept from a look being
			// made by closing, which see.)
			boolean reloading = Minecraft.getInstance().gui.overlay() != null;
			this.withModels = !reloading;
			byte kind = state == null ? AIR : this.work(id, state);
			if (reloading) {
				this.provisional = true;
			}
			KINDS.setRelease(this.kind, id, kind);
			this.made++;
			this.makingNanos += System.nanoTime() - start;
		}
	}

	/** The state's colours from its model's textures, or its map colour while models reload. Under {@link #MAKING}. */
	@Override
	protected byte colours(int id, BlockState state, byte kind) {
		if (!this.withModels) {
			return this.flatColours(id, state, kind);
		}
		kind = this.modelColours(id, state, kind);
		if (kind == AIR) {
			return AIR;
		}
		// A fluid's block has no model of its own, so every cell holding a fluid and nothing solid
		// (kelp and seagrass stand in water) draws as the fluid's still texture, tinted the way the
		// fluid's own tint code asks: water by the biome.
		if (kind == WATER) {
			FluidState fluid = state.getFluidState().getType().defaultFluidState();
			FluidLook look = this.fluidLooks.computeIfAbsent(fluid.getType(), type -> this.fluidLook(fluid));
			// With no texture to be found, the block's particle texture's colour stays.
			if (look.color() >= 0) {
				this.top[id] = look.color();
				this.topTinted[id] = look.color();
				this.topTintWeight[id] = 1;
				this.side[id] = look.color();
				this.sideTinted[id] = look.color();
				this.sideTintWeight[id] = 1;
				this.tint[id] = look.tint().kind();
				this.constantTint[id] = look.tint().constant();
			}
		}
		return kind;
	}

	/** The state's colours from its model's textures; returns its kind, which they can change. */
	private byte modelColours(int id, BlockState state, byte kind) {
		//? if >=26.1 {
		BlockStateModel model = this.models.get(state);
		//?} else if >=1.21.5 {
		/*BlockStateModel model = this.models.getBlockModel(state);
		*///?} else {
		/*BakedModel model = this.models.getBlockModel(state);
		*///?}
		FaceColor topFace = new FaceColor();
		FaceColor sideFace = new FaceColor();
		// A mod's model can fail when asked for its faces without its block entity's data, as
		// Immersive Engineering's mirrored multiblock parts do, or throw an Error rather than an
		// exception. Such a block falls back below like one with no faces, instead of the failure
		// crashing the game.
		try {
			//? if >=1.21.5 {
			this.parts.clear();
			this.random.setSeed(42L);
			model.collectParts(this.random, this.parts);
			//?} else {
			/*List<LegacyPart> parts = List.of(new LegacyPart(model, state, this.random));
			*///?}
			//? if >=26.1 {
			for (BlockStateModelPart part : this.parts) {
			//?} else if >=1.21.5 {
			/*for (BlockModelPart part : this.parts) {
			*///?} else {
			/*for (LegacyPart part : parts) {
			*///?}
				this.accumulate(topFace, part.getQuads(Direction.UP));
				this.accumulate(sideFace, part.getQuads(Direction.SOUTH));
				if (sideFace.isEmpty()) {
					this.accumulate(sideFace, part.getQuads(Direction.NORTH));
				}
				// Cross-shaped plants and other unculled geometry.
				List<BakedQuad> loose = part.getQuads(null);
				if (!loose.isEmpty()) {
					this.accumulateLoose(topFace, sideFace, loose);
				}
			}
		} catch (RuntimeException | Error e) {
			topFace = new FaceColor();
			sideFace = new FaceColor();
			if (!this.warnedModel) {
				this.warnedModel = true;
				Diorama.LOGGER.warn("The model of {} failed when asked for its faces ({}); such blocks take their particle texture or map colour", state, e.toString());
			}
		}
		if (topFace.isEmpty() && sideFace.isEmpty()) {
			// Block entities (chests, signs, beds) have no block model; use the particle texture.
			TextureAtlasSprite particle = particle(model);
			if (particle != null && !isMissing(particle)) {
				float[] avg = this.spriteAverage(particle);
				topFace.add(avg, -1);
				sideFace.add(avg, -1);
			}
		}
		if (topFace.isEmpty() && sideFace.isEmpty()) {
			// No texture to go by: a model that draws only from its block entity's data, which this
			// table can't see (Copycats+ copycats take the look of the block they're filled with), a
			// texture the game can't find, or one a reload freed with nothing in its place.
			return this.flatColours(id, state, kind);
		}
		if (topFace.isEmpty()) {
			topFace = sideFace;
		}
		if (sideFace.isEmpty()) {
			sideFace = topFace;
		}
		this.setColours(id, topFace, sideFace);

		if (kind == SOLID && !state.is(BlockTags.LEAVES) && topFace.coverage() < 0.55F) {
			kind = GLASS;
		}

		// The tint layer the model's quads use, asked of the block's own tint code.
		int tintIndex = topFace.tintIndex() >= 0 ? topFace.tintIndex() : sideFace.tintIndex();
		if (tintIndex >= 0) {
			Tint tint = blockTint(this.colors, state, tintIndex, this.probe);
			this.tint[id] = tint.kind();
			this.constantTint[id] = tint.constant();
		}
		return kind;
	}

	//? if >=26.1 {
	private static @Nullable TextureAtlasSprite particle(BlockStateModel model) {
	//?} else if >=1.21.5 {
	/*private static @Nullable TextureAtlasSprite particle(BlockStateModel model) {
	*///?} else {
	/*private static @Nullable TextureAtlasSprite particle(BakedModel model) {
	*///?}
		try {
			//? if >=26.1 {
			return model.particleMaterial().sprite();
			//?} else if >=1.21.5 || fabric {
			/*return model.particleIcon();
			*///?} else {
			/*// Asked the loader's way, as the faces are (legacyQuads).
			return model.getParticleIcon(net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
			*///?}
		} catch (RuntimeException | Error e) {
			return null;
		}
	}

	//? if <1.21.5 {
	/*// Before 1.21.5 a block's model gave its quads itself, for one random seed, rather than
	// through parts. This stands in for the one part, asked the same way collectParts would.
	private record LegacyPart(BakedModel model, BlockState state, RandomSource random) {
		List<BakedQuad> getQuads(@Nullable Direction direction) {
			this.random.setSeed(42L);
			return legacyQuads(this.model, this.state, direction, this.random);
		}
	}

	*///?}
	//? if <1.21.5 && fabric {
	/*private static List<BakedQuad> legacyQuads(BakedModel model, BlockState state, @Nullable Direction direction, RandomSource random) {
		return model.getQuads(state, direction, random);
	}

	*///?} else if <1.21.5 {
	/*// NeoForge and Forge draw a block's faces through their own form of the call, given the block
	// entity's model data and a render layer, and a mod's model may throw when asked the vanilla way
	// (Immortalers Delight's spike trap does). Asked as Xaero's maps ask: no data, every layer.
	private static List<BakedQuad> legacyQuads(BakedModel model, BlockState state, @Nullable Direction direction, RandomSource random) {
		return model.getQuads(state, direction, random, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
	}

	*///?}
	/** A fluid's colour as 0xRRGGBB, or -1 if its texture can't be found, and its tint. */
	private record FluidLook(int color, Tint tint) {
	}

	/** Looks up the still texture and the tint the game draws a fluid with. */
	private FluidLook fluidLook(FluidState fluid) {
		TextureAtlasSprite sprite;
		try {
			//? if >=26.1 {
			sprite = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid).stillMaterial().sprite();
			//?} else if fabric {
			/*FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
			sprite = handler == null ? null : handler.getFluidSprites(null, null, fluid)[0];
			*///?} else if >=1.21.9 {
			/*sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS)
				.getSprite(IClientFluidTypeExtensions.of(fluid).getStillTexture());
			*///?} else {
			/*sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
				.apply(IClientFluidTypeExtensions.of(fluid).getStillTexture());
			*///?}
		} catch (RuntimeException | Error e) {
			sprite = null;
		}
		if (sprite != null && isMissing(sprite)) {
			// An unregistered fluid or a missing texture: better the block's particle colour than purple.
			sprite = null;
		}
		float[] avg = sprite == null ? null : this.spriteAverage(sprite);
		int color = avg == null || avg[3] <= 0 ? -1 : FaceColor.pack(avg[0], avg[1], avg[2]);
		return new FluidLook(color, fluidTint(fluid, this.probe));
	}

	/** How a block or fluid is tinted: by a biome colour ({@code TINT_*}) or a fixed colour, -1 for none. */
	private record Tint(byte kind, int constant) {
		static final Tint NONE = new Tint(TINT_NONE, -1);

		/**
		 * Asks tint code for its colour in the probe's world. For a biome colour the map doesn't
		 * blend itself (a mod's own), or code that fails in the stand-in world, it takes the code's
		 * colour out of the world instead, as the map did before it asked in a world at all.
		 */
		static Tint ask(TintProbe probe, IntSupplier inWorld, IntSupplier outOfWorld) {
			probe.reset();
			int color;
			// Here and below, an Error too: a mod's code can name a class from a mod that isn't
			// installed, which fails only when it runs, or fail an assertion of its own, and the map
			// runs every block's code at once.
			try {
				color = inWorld.getAsInt();
			} catch (RuntimeException | Error e) {
				color = -1;
			}
			byte kind = probe.kind();
			if (kind != TINT_NONE) {
				return new Tint(kind, -1);
			}
			if (probe.askedForBiome() || color == -1) {
				try {
					color = outOfWorld.getAsInt();
				} catch (RuntimeException | Error e) {
					return NONE;
				}
			}
			// -1 (no tint code) and white both leave the texture as it is.
			return (color & 0xFFFFFF) == 0xFFFFFF ? NONE : new Tint(TINT_NONE, color & 0xFFFFFF);
		}
	}

	/** Asks a block's tint code how one tint layer is coloured. */
	private static Tint blockTint(BlockColors colors, BlockState state, int index, TintProbe probe) {
		//? if >=26.1 {
		BlockTintSource source = colors.getTintSource(state, index);
		return source == null ? Tint.NONE : Tint.ask(probe, () -> source.colorInWorld(state, probe, BlockPos.ZERO), () -> source.color(state));
		//?} else {
		/*return Tint.ask(probe, () -> colors.getColor(state, probe, BlockPos.ZERO, index), () -> colors.getColor(state, null, null, index));
		*///?}
	}

	/** The biome colour water's tint code asks for, the game's own if it asks for none. */
	private static ColorResolver waterResolver(TintProbe probe) {
		probe.reset();
		fluidTint(Fluids.WATER.defaultFluidState(), probe);
		ColorResolver asked = probe.asked();
		return asked != null ? asked : BiomeColors.WATER_COLOR_RESOLVER;
	}

	/** The same for a fluid's tint code. */
	private static Tint fluidTint(FluidState fluid, TintProbe probe) {
		try {
			//? if >=26.1 {
			BlockTintSource source = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid).tintSource();
			BlockState block = fluid.createLegacyBlock();
			return source == null ? Tint.NONE : Tint.ask(probe, () -> source.colorInWorld(block, probe, BlockPos.ZERO), () -> source.color(block));
			//?} else if fabric {
			/*FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
			return handler == null ? Tint.NONE
				: Tint.ask(probe, () -> handler.getFluidColor(probe, BlockPos.ZERO, fluid), () -> handler.getFluidColor(null, null, fluid));
			*///?} else {
			/*IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
			return Tint.ask(probe, () -> extensions.getTintColor(fluid, probe, BlockPos.ZERO), extensions::getTintColor);
			*///?}
		} catch (RuntimeException | Error e) {
			return Tint.NONE;
		}
	}

	private void accumulate(FaceColor face, List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			//? if >=26.1 {
			TextureAtlasSprite sprite = quad.materialInfo().sprite();
			int tintIndex = quad.materialInfo().tintIndex();
			//?} else {
			/*TextureAtlasSprite sprite = quad.sprite();
			int tintIndex = quad.tintIndex();
			*///?}
			if (!isMissing(sprite)) {
				face.add(this.spriteAverage(sprite), tintIndex);
			}
		}
	}

	private void accumulateLoose(FaceColor top, FaceColor side, List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			//? if >=26.1 {
			TextureAtlasSprite sprite = quad.materialInfo().sprite();
			int tintIndex = quad.materialInfo().tintIndex();
			//?} else {
			/*TextureAtlasSprite sprite = quad.sprite();
			int tintIndex = quad.tintIndex();
			*///?}
			if (isMissing(sprite)) {
				continue;
			}
			float[] avg = this.spriteAverage(sprite);
			if (quad.direction() == Direction.UP || quad.direction() == Direction.DOWN) {
				top.add(avg, tintIndex);
			} else {
				top.add(avg, tintIndex);
				side.add(avg, tintIndex);
			}
		}
	}

	/** The game's magenta and black stand-in for a texture it can't find. */
	private static boolean isMissing(TextureAtlasSprite sprite) {
		return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation());
	}

	private static final Field ORIGINAL_IMAGE;

	static {
		Field field;
		try {
			//? if >=26.1 || neoforge {
			field = SpriteContents.class.getDeclaredField("originalImage");
			//?} else if forge {
			/*// Forge runs the game with SRG names outside the dev environment, so look the field up by
			// its SRG name, mapped to whatever names this run uses.
			field = SpriteContents.class.getDeclaredField(net.minecraftforge.fml.util.ObfuscationReflectionHelper.remapName(
				cpw.mods.modlauncher.api.INameMappingService.Domain.FIELD, "f_243904_"));
			*///?} else {
			/*// Fabric runs the game with intermediary names outside the dev environment (NeoForge
			// doesn't), so look the field up by its intermediary name (SpriteContents.originalImage, a
			// NativeImage) mapped to whatever names this run uses.
			field = SpriteContents.class.getDeclaredField(FabricLoader.getInstance().getMappingResolver()
				.mapFieldName("intermediary", "net.minecraft.class_7764", "field_40539", "Lnet/minecraft/class_1011;"));
			*///?}
			field.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			field = null;
		}
		ORIGINAL_IMAGE = field;
	}

	/** Returns {r, g, b, coverage} averaged over the sprite's visible pixels, colors in 0..1. */
	private float[] spriteAverage(TextureAtlasSprite sprite) {
		return this.spriteCache.computeIfAbsent(sprite, s -> {
			float[] avg = average(s);
			if (avg != null) {
				return avg;
			}
			// The sprite is from the atlas before the last resource reload, which freed its image: a
			// model that outlived the reload still points at it (issue 94, a big NeoForge pack). Read
			// the texture of that name in the atlas now; if the atlas has none, it adds no colour.
			TextureAtlasSprite current = this.currentSprite(s);
			avg = current == null ? null : average(current);
			if (!warnedFreed) {
				warnedFreed = true;
				Diorama.LOGGER.warn("A block model holds texture {} from before the last resource reload; {}", s.contents().name(),
					avg != null ? "using the reloaded texture" : "it adds no colour");
			}
			return avg != null ? avg : new float[] {0.0F, 0.0F, 0.0F, 0.0F};
		});
	}

	/**
	 * Called by a texture as it starts to free its images (a resource reload frees the last atlas's
	 * textures). It waits for a look reading those images to finish, which takes microseconds, and
	 * marks the texture so no look reads it after.
	 */
	public static void closing(ClosingTexture texture) {
		synchronized (IMAGES) {
			texture.diorama$markClosed();
		}
	}

	/** A texture's contents, told when they free their images. */
	public interface ClosingTexture {
		void diorama$markClosed();

		boolean diorama$closed();
	}

	/** Set once per build, so a mod that keeps many old models is named once in the log. */
	private static boolean warnedFreed;

	/** The sprite with the same name in its atlas as the atlas is now, or null if there's none. */
	private @Nullable TextureAtlasSprite currentSprite(TextureAtlasSprite old) {
		try {
			// The block atlas, as found on the client thread when the table was made: the texture
			// manager's own map is only safe to read there. Atlases are registered once at startup and
			// filled again by each reload, so this is the atlas as it is now.
			TextureAtlas atlas = this.atlas;
			if (atlas == null || !old.atlasLocation().equals(atlas.location())) {
				if (!Minecraft.getInstance().isSameThread()) {
					return null;
				}
				atlas = Minecraft.getInstance().getTextureManager().getTexture(old.atlasLocation()) instanceof TextureAtlas found ? found : null;
				if (atlas == null) {
					return null;
				}
			}
			TextureAtlasSprite sprite = atlas.getSprite(old.contents().name());
			return sprite == old || sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation()) ? null : sprite;
		} catch (RuntimeException | Error e) {
			// The atlas isn't filled, or a mod's atlas isn't a TextureAtlas.
		}
		return null;
	}

	/** {@link #spriteAverage} for one sprite, or null if its image has been freed. */
	private static float @Nullable [] average(TextureAtlasSprite s) {
		// Held while the pixels are read, so a texture can't free its images meanwhile (see closing).
		synchronized (IMAGES) {
			SpriteContents contents = s.contents();
			if (contents instanceof ClosingTexture closing && closing.diorama$closed()) {
				return null;
			}
			return average(contents);
		}
	}

	private static float @Nullable [] average(SpriteContents contents) {
		NativeImage image = null;
		try {
			image = ORIGINAL_IMAGE == null ? null : (NativeImage) ORIGINAL_IMAGE.get(contents);
		} catch (IllegalAccessException ignored) {
		}
		if (image == null) {
			return new float[] {1.0F, 0.0F, 1.0F, 1.0F};
		}
		int w = Math.min(contents.width(), image.getWidth());
		int h = Math.min(contents.height(), image.getHeight());
		double r = 0;
		double g = 0;
		double b = 0;
		double weight = 0;
		int visible = 0;
		try {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					//? if >=1.21.2 {
					int argb = image.getPixel(x, y);
					//?} else {
					/*// Only the ABGR getter existed; swap red and blue.
					int abgr = image.getPixelRGBA(x, y);
					int argb = (abgr & 0xFF00FF00) | (abgr >> 16 & 0xFF) | (abgr & 0xFF) << 16;
					*///?}
					int a = argb >>> 24;
					if (a < 26) {
						continue;
					}
					double wa = a / 255.0;
					// Average the stored (sRGB) values, as Xaero's World Map and JourneyMap do. In linear
					// light the bright pixels outweigh the dark ones: a casing that is a dark panel in a
					// brass frame came out the frame's colour, and an enchanting table's red top grey.
					r += ((argb >> 16) & 0xFF) * wa;
					g += ((argb >> 8) & 0xFF) * wa;
					b += (argb & 0xFF) * wa;
					weight += wa;
					visible++;
				}
			}
		} catch (IllegalStateException freed) {
			// "Image is not allocated": NativeImage checks before every read.
			return null;
		}
		if (weight == 0) {
			return new float[] {0.0F, 0.0F, 0.0F, 0.0F};
		}
		return new float[] {
			(float) (r / weight / 255), (float) (g / weight / 255), (float) (b / weight / 255),
			(float) visible / (w * h)
		};
	}
}
