package dev.diorama.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//?}
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Mob faces for the radar, cut from each mob's own texture, so variants and resource packs show.
 * Vanilla mobs whose face is one clean rectangle of their texture are listed here; every other
 * mob, modded ones included, gets the face {@link ModelFaces} reads off its model, or stays a dot.
 * Rectangles are read from the 26.3 models: a head box at texOffs(u, v) with size w x h x d has
 * its front face at (u + d, v + d), w x h.
 */
public final class MobFaces {
	/**
	 * A face {@code w} x {@code h} pixels, drawn as rectangles of a texture of the given size, in
	 * order, each at {@code (dx, dy)} in face pixels.
	 */
	record Face(int texW, int texH, int w, int h, List<Piece> pieces, boolean whole) {
		/** {@code whole} for a mob with no head to read, pictured whole (see {@link ModelFaces}). */
		Face(int texW, int texH, int w, int h, List<Piece> pieces) {
			this(texW, texH, w, h, pieces, false);
		}

		Face(int texW, int texH, int u, int v, int w, int h) {
			this(texW, texH, u, v, w, h, null, null);
		}

		/**
		 * A face that's one rectangle at (u, v), with a {@code feature} that sticks out of it (a nose
		 * or snout) and over both a {@code hat}, an outer layer (zombie and skeleton hats, villager hoods).
		 */
		Face(int texW, int texH, int u, int v, int w, int h, @Nullable Piece hat, @Nullable Piece feature) {
			this(texW, texH, w, h, layers(new Piece(u, v, w, h, 0, 0), feature, hat));
		}

		private static List<Piece> layers(Piece face, @Nullable Piece feature, @Nullable Piece hat) {
			List<Piece> pieces = new java.util.ArrayList<>(List.of(face));
			if (feature != null) {
				pieces.add(feature);
			}
			if (hat != null) {
				pieces.add(hat);
			}
			return List.copyOf(pieces);
		}
	}

	/** {@code mirrored} for a rectangle drawn flipped left to right: the far side of a fish's fin. */
	record Piece(int u, int v, int w, int h, int dx, int dy, boolean mirrored) {
		Piece(int u, int v, int w, int h, int dx, int dy) {
			this(u, v, w, h, dx, dy, false);
		}
	}

	/** An adult face, and a baby's when babies have their own texture layout (or null for a dot). */
	private record Faces(Face adult, @Nullable Face baby) {
	}

	private static final Map<EntityType<?>, Faces> FACES = new HashMap<>();
	/**
	 * The texture each entity was last drawn with, its age then, and whether it was a baby.
	 * Working it out builds a render state, so it's redone every two seconds of the entity's own
	 * life, often enough for a wolf turning angry, and straight away when a baby grows up, since
	 * the face layout changes with it. Keyed by the entity object itself (entities compare equal
	 * by id, which another world can reuse), and dropped once the entity is gone.
	 */
	private static final Map<Entity, Looked> TEXTURES = new com.google.common.collect.MapMaker().weakKeys().makeMap();
	private static final int REFRESH_TICKS = 40;

	/** {@code modelFace} is the face {@link ModelFaces} read off the model the entity is drawn with. */
	private record Looked(@Nullable Identifier texture, MobFaces.@Nullable Face modelFace, int age, boolean baby) {
	}

	private static final Piece HUMANOID_HAT = new Piece(40, 8, 8, 8, 0, 0);
	private static final Piece BABY_ZOMBIE_HAT = new Piece(41, 9, 6, 6, 0, 0);
	private static final Piece VILLAGER_HAT = new Piece(40, 8, 8, 10, 0, 0);
	/** Villager-style noses stick out from the lower middle of a 10-pixel-tall face. */
	private static final Piece VILLAGER_NOSE = new Piece(26, 2, 2, 3, 3, 7);
	//? if >=1.21.5 {
	private static final Face COW = new Face(64, 64, 6, 6, 8, 8, null, new Piece(2, 34, 6, 3, 1, 5));
	//?} else {
	/*// The cow model before 1.21.5: head box at texOffs(0, 0), 8 x 8 x 6, no muzzle, in a 64 x 32 texture.
	private static final Face COW = new Face(64, 32, 6, 6, 8, 8);
	*///?}
	/** Cold cows keep their muzzle elsewhere in the texture: their model moves its texture origin. */
	private static final Face COLD_COW = new Face(64, 64, 6, 6, 8, 8, null, new Piece(10, 34, 6, 3, 1, 5));

	static {
		Face zombie = new Face(64, 64, 8, 8, 8, 8, HUMANOID_HAT, null);
		Face babyZombie = new Face(64, 64, 9, 9, 6, 6, BABY_ZOMBIE_HAT, null);
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.DROWNED}) {
			FACES.put(type, new Faces(zombie, babyZombie));
		}
		FACES.put(EntityTypes.ZOMBIE_VILLAGER, new Faces(
			new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE),
			new Face(64, 64, 7, 7, 8, 8, new Piece(7, 38, 8, 8, 0, 0), null)));
		Face skeleton = new Face(64, 32, 8, 8, 8, 8, HUMANOID_HAT, null);
		//? if >=1.21 {
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.SKELETON, EntityTypes.STRAY, EntityTypes.WITHER_SKELETON, EntityTypes.BOGGED}) {
		//?} else {
		/*// The bogged came in 1.21.
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.SKELETON, EntityTypes.STRAY, EntityTypes.WITHER_SKELETON}) {
		*///?}
			FACES.put(type, new Faces(skeleton, null));
		}
		FACES.put(EntityTypes.CREEPER, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		Face spider = new Face(64, 32, 40, 12, 8, 8);
		FACES.put(EntityTypes.SPIDER, new Faces(spider, null));
		FACES.put(EntityTypes.CAVE_SPIDER, new Faces(spider, null));
		FACES.put(EntityTypes.ENDERMAN, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		FACES.put(EntityTypes.WITCH, new Faces(new Face(64, 128, 8, 8, 8, 10, null, VILLAGER_NOSE), null));
		FACES.put(EntityTypes.BLAZE, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		// Piglin snouts: a 4x4 box from the face's middle, rows 4 to 8 of 8.
		Face piglin = new Face(64, 64, 8, 8, 10, 8, null, new Piece(32, 2, 4, 4, 3, 4));
		Face babyPiglin = new Face(64, 64, 7, 7, 9, 6, null, new Piece(22, 31, 3, 3, 3, 3));
		FACES.put(EntityTypes.PIGLIN, new Faces(piglin, babyPiglin));
		FACES.put(EntityTypes.PIGLIN_BRUTE, new Faces(piglin, null));
		FACES.put(EntityTypes.ZOMBIFIED_PIGLIN, new Faces(piglin, babyPiglin));
		Face illager = new Face(64, 64, 8, 8, 8, 10, null, VILLAGER_NOSE);
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.EVOKER}) {
			FACES.put(type, new Faces(illager, null));
		}
		// The warden's head without the tendrils either side of it: with them the picture is 48 wide,
		// too big for ModelFaces to take as a face, and the head a third of it.
		FACES.put(EntityTypes.WARDEN, new Faces(new Face(128, 128, 10, 42, 16, 16), null));
		FACES.put(EntityTypes.RAVAGER, new Faces(new Face(128, 128, 16, 16, 16, 20), null));
		FACES.put(EntityTypes.VILLAGER, new Faces(
			new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE),
			new Face(64, 64, 7, 7, 8, 8, new Piece(7, 37, 8, 8, 0, 0), null)));
		FACES.put(EntityTypes.WANDERING_TRADER, new Faces(new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE), null));
		FACES.put(EntityTypes.IRON_GOLEM, new Faces(new Face(128, 128, 8, 8, 8, 10, null, VILLAGER_NOSE), null));
		// Cow muzzles sit under the eyes, rows 5 to 8 of 8; a calf's at rows 3 to 6 of 6.
		Faces cow = new Faces(COW, new Face(64, 64, 5, 23, 6, 6, null, new Piece(13, 30, 4, 3, 1, 3)));
		FACES.put(EntityTypes.COW, cow);
		FACES.put(EntityTypes.MOOSHROOM, cow);
		// The pig's snout covers the middle of its face, rows 4 to 7 of 8.
		//? if >=1.21.5 {
		FACES.put(EntityTypes.PIG, new Faces(new Face(64, 64, 8, 8, 8, 8, null, new Piece(17, 17, 4, 3, 2, 4)), new Face(32, 32, 6, 21, 7, 6)));
		//?} else {
		/*// The pig model before 1.21.5: the same head and snout boxes in a 64 x 32 texture.
		FACES.put(EntityTypes.PIG, new Faces(new Face(64, 32, 8, 8, 8, 8, null, new Piece(17, 17, 4, 3, 2, 4)), null));
		*///?}
		FACES.put(EntityTypes.SHEEP, new Faces(new Face(64, 32, 8, 8, 6, 6), new Face(64, 32, 5, 5, 5, 5)));
		// The chicken's beak is across rows 2 to 4 of its 6-pixel-tall face.
		FACES.put(EntityTypes.CHICKEN, new Faces(new Face(64, 32, 3, 3, 4, 6, null, new Piece(16, 2, 4, 2, 0, 2)), new Face(16, 16, 4, 4, 4, 4)));
		FACES.put(EntityTypes.WOLF, new Faces(new Face(64, 32, 4, 4, 6, 6), new Face(32, 32, 5, 17, 6, 5)));
		FACES.put(EntityTypes.CAT, new Faces(new Face(64, 32, 5, 5, 5, 4), new Face(32, 32, 4, 4, 5, 4)));
		FACES.put(EntityTypes.FOX, new Faces(new Face(48, 32, 7, 11, 8, 6, null, new Piece(9, 21, 4, 2, 2, 4)), new Face(32, 32, 5, 5, 6, 5)));
		//? if >=26.1 {
		FACES.put(EntityTypes.RABBIT, new Faces(new Face(64, 64, 5, 21, 5, 5), new Face(32, 32, 4, 4, 5, 4)));
		//?} else {
		/*// The rabbit model before 26.1: head box at texOffs(32, 0), 5 x 4 x 5, in a 64 x 32 texture.
		FACES.put(EntityType.RABBIT, new Faces(new Face(64, 32, 37, 5, 5, 4), null));
		*///?}
		FACES.put(EntityTypes.POLAR_BEAR, new Faces(new Face(128, 64, 7, 7, 7, 7), new Face(64, 64, 4, 4, 6, 5)));
		FACES.put(EntityTypes.PANDA, new Faces(new Face(64, 64, 9, 15, 13, 10, null, new Piece(47, 18, 7, 5, 3, 5)), new Face(64, 64, 5, 5, 7, 6)));
		FACES.put(EntityTypes.ALLAY, new Faces(new Face(32, 32, 5, 5, 5, 5), null));
		// The breeze came in 1.21.
		//? if >=1.21 {
		FACES.put(EntityTypes.BREEZE, new Faces(new Face(32, 32, 8, 8, 8, 8), null));
		//?}
		//? if >=1.21.2 {
		FACES.put(EntityTypes.CREAKING, new Faces(new Face(64, 64, 6, 6, 6, 10), null));
		//?}
		FACES.put(EntityTypes.DOLPHIN, new Faces(new Face(64, 64, 6, 6, 8, 7), new Face(64, 64, 4, 4, 6, 5)));
		FACES.put(EntityTypes.BEE, new Faces(new Face(64, 64, 10, 10, 7, 7), new Face(32, 32, 5, 5, 4, 4)));
		FACES.put(EntityTypes.AXOLOTL, new Faces(new Face(64, 64, 5, 6, 8, 5), new Face(32, 32, 4, 12, 6, 3)));
		FACES.put(EntityTypes.STRIDER, new Faces(new Face(64, 128, 16, 16, 16, 14), new Face(32, 32, 8, 8, 7, 7)));
	}

	private MobFaces() {
	}

	/** Whether this entity has a face icon; if not the radar draws a dot. */
	public static boolean has(Entity entity) {
		return face(entity) != null;
	}

	/** Whether this entity's face can be drawn now: it has one, and its renderer gives a texture. For tests. */
	public static boolean canDraw(Entity entity) {
		return face(entity) != null && looked(entity).texture() != null;
	}

	/**
	 * The face this entity is drawn with, as {@code "<texture width>x<texture height> <w>x<h>"} and
	 * then {@code " | u,v wxh at x,y"} for each rectangle, or null if it has none. For tests.
	 */
	public static @Nullable String describe(Entity entity) {
		return describe(face(entity));
	}

	/** The face {@link ModelFaces} reads off this entity's model, described as {@link #describe(Entity)} does. For tests. */
	public static @Nullable String describeFromModel(Entity entity) {
		return describe(looked(entity).modelFace());
	}

	private static @Nullable String describe(@Nullable Face face) {
		if (face == null) {
			return null;
		}
		StringBuilder out = new StringBuilder().append(face.texW()).append('x').append(face.texH()).append(' ').append(face.w()).append('x').append(face.h());
		for (Piece p : face.pieces()) {
			out.append(" | ").append(p.u()).append(',').append(p.v()).append(' ').append(p.w()).append('x').append(p.h())
				.append(" at ").append(p.dx()).append(',').append(p.dy()).append(p.mirrored() ? " mirrored" : "");
		}
		return out.toString();
	}

	/** Draws every mob with the face read off its model, the listed ones too, to compare them. For tests. */
	public static volatile boolean modelOnly;
	/** Off, mobs that aren't listed are dots, as before faces were read off models. For tests. */
	public static volatile boolean fromModels = true;

	private static @Nullable Face face(Entity entity) {
		Faces faces = FACES.get(entity.getType());
		if (faces == null || modelOnly) {
			return fromModels ? looked(entity).modelFace() : null;
		}
		//? if >=26.1 {
		Face face = entity instanceof LivingEntity living && living.isBaby() ? faces.baby() : faces.adult();
		//?} else {
		/*// Before 26.1 a baby is its adult model scaled down, drawn with the adult texture, so it
		// has the adult's face.
		Face face = faces.adult();
		*///?}
		return face == COW && coldCow(entity) ? COLD_COW : face;
	}

	/**
	 * Draws an entity's face centred on (x, y), fitted in a square {@code size} across, keeping
	 * the face's proportions. Returns false if it has no face icon.
	 */
	public static boolean draw(IconBatch icons, Entity entity, float x, float y, float size, int color) {
		Face face = face(entity);
		Identifier texture = face == null ? null : looked(entity).texture();
		if (face == null || texture == null) {
			return false;
		}
		float scale = size / Math.max(face.w(), face.h());
		float w = face.w() * scale;
		float h = face.h() * scale;
		GuiGraphicsExtractor graphics = icons.graphics();
		graphics.pose().pushMatrix();
		graphics.pose().translate(x - w / 2, y - h / 2);
		graphics.pose().scale(scale);
		blitFace(icons, texture, face, tinted(entity, color));
		graphics.pose().popMatrix();
		return true;
	}

	/**
	 * Draws an entity's face as pixel art centred on (x, y), about {@code size} GUI pixels across:
	 * each texel a whole number of screen pixels, with a one-texel outline and a drop shadow.
	 * Returns false if it has no face icon.
	 */
	public static boolean drawPixel(IconBatch icons, Entity entity, float x, float y, float size, int color, int outline, int alpha) {
		Face face = face(entity);
		Identifier texture = face == null ? null : looked(entity).texture();
		if (face == null || texture == null) {
			return false;
		}
		float texel = Radar.texel(size, Math.max(face.w(), face.h()));
		Radar.pixelFrame(icons, x, y, face.w(), face.h(), texel, outline, alpha);
		blitFace(icons, texture, face, tinted(entity, color));
		GuiGraphicsExtractor graphics = icons.graphics();
		graphics.pose().popMatrix();
		return true;
	}

	private static void blitFace(IconBatch icons, Identifier texture, Face face, int color) {
		for (Piece p : face.pieces()) {
			icons.blit(texture, p.dx(), p.dy(), p.u(), p.v(), p.w(), p.h(), face.texW(), face.texH(), color, p.mirrored());
		}
	}

	/**
	 * The colour to draw a face in: a tropical fish's texture is white, and the game colours it with
	 * the fish's own base colour. Its pattern, a layer of its own, isn't drawn.
	 */
	private static int tinted(Entity entity, int color) {
		if (!(entity instanceof net.minecraft.world.entity.animal.fish.TropicalFish fish)) {
			return color;
		}
		//? if >=1.21 {
		int tint = fish.getBaseColor().getTextureDiffuseColor();
		//?} else {
		/*float[] rgb = fish.getBaseColor().getTextureDiffuseColors();
		int tint = Math.round(rgb[0] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[2] * 255);
		*///?}
		int r = (color >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
		int g = (color >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
		int b = (color & 0xFF) * (tint & 0xFF) / 255;
		return color & 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** Cold cows use a different model, with the muzzle elsewhere in the texture. */
	private static boolean coldCow(Entity entity) {
		//? if >=1.21.5 {
		return entity instanceof net.minecraft.world.entity.animal.cow.Cow cow
			&& cow.getVariant().value().modelAndTexture().model() == net.minecraft.world.entity.animal.cow.CowVariant.ModelType.COLD;
		//?} else {
		/*// Cow variants came in 1.21.5.
		return false;
		*///?}
	}

	/**
	 * The texture the game draws this entity with, from its renderer. Also what Xaero's icon files
	 * name a mob's variants by ({@link XaeroRadarIcons}).
	 */
	static @Nullable Identifier texture(Entity entity) {
		return looked(entity).texture();
	}

	/** The texture the game draws this entity with, and the face read off its model. */
	private static Looked looked(Entity entity) {
		Looked looked = TEXTURES.get(entity);
		int age = entity.tickCount;
		boolean baby = entity instanceof LivingEntity living && living.isBaby();
		if (looked == null || age - looked.age() >= REFRESH_TICKS || age < looked.age() || baby != looked.baby()) {
			looked = lookUp(entity, age, baby);
			TEXTURES.put(entity, looked);
		}
		return looked;
	}

	//? if >=1.21.2 {
	/** AgeableMobRenderer's adult and baby models, or null if they can't be read. */
	private static java.lang.reflect.@Nullable Field @Nullable [] ageableModels;
	private static boolean ageableModelsLooked;

	/**
	 * The model an ageable mob's renderer draws it with. Renderers that keep an adult's and a
	 * baby's model only switch between them as they draw, so their current model is whichever
	 * they drew last.
	 */
	@SuppressWarnings("rawtypes")
	private static @Nullable Model ageableModel(LivingEntityRenderer renderer, boolean baby) {
		if (!ageableModelsLooked) {
			ageableModelsLooked = true;
			// Its two model fields, adult then baby as declared; their names depend on the mappings, their order doesn't.
			List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
			for (java.lang.reflect.Field field : net.minecraft.client.renderer.entity.AgeableMobRenderer.class.getDeclaredFields()) {
				if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && Model.class.isAssignableFrom(field.getType())) {
					fields.add(field);
				}
			}
			try {
				for (java.lang.reflect.Field field : fields) {
					field.setAccessible(true);
				}
				ageableModels = fields.size() == 2 ? fields.toArray(new java.lang.reflect.Field[0]) : null;
			} catch (RuntimeException e) {
				ageableModels = null;
			}
		}
		if (ageableModels == null) {
			// A face off the wrong age's model would be cut from the wrong places: a dot instead.
			return null;
		}
		try {
			return (Model) ageableModels[baby ? 1 : 0].get(renderer);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
	//?}

	/** Each renderer class's own model fields, beyond the one every living-entity renderer has. */
	private static final Map<Class<?>, List<java.lang.reflect.Field>> MODEL_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * The face read off the model a renderer draws a mob with. A renderer can keep other models it
	 * switches to as it draws (a sulfur cube's small one, a mod's baby model), and which one is this
	 * mob's isn't known until then. Those named for babies (baby, small, child) count for babies,
	 * the rest for adults, and the face is only used if all of this mob's age's models agree.
	 */
	@SuppressWarnings("rawtypes")
	private static @Nullable Face modelFace(LivingEntityRenderer renderer, Entity entity, boolean baby, @Nullable Object state) {
		Model fish = fishModel(renderer, entity);
		if (fish != null) {
			return ModelFaces.of(fish, state);
		}
		//? if >=1.21.2 {
		if (renderer instanceof net.minecraft.client.renderer.entity.AgeableMobRenderer) {
			Model model = ageableModel(renderer, baby);
			return model == null ? null : ModelFaces.of(model, state);
		}
		//?}
		Model current = renderer.getModel();
		List<Model> models = new java.util.ArrayList<>(List.of(current));
		List<Boolean> forBabies = new java.util.ArrayList<>(List.of(babyName(current.getClass().getSimpleName())));
		for (java.lang.reflect.Field field : MODEL_FIELDS.computeIfAbsent(renderer.getClass(), MobFaces::modelFields)) {
			Object other;
			try {
				other = field.get(renderer);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
			if (!(other instanceof Model model)) {
				continue;
			}
			boolean babyField = babyName(field.getName()) || babyName(model.getClass().getSimpleName());
			int at = models.indexOf(model);
			if (at >= 0) {
				forBabies.set(at, forBabies.get(at) || babyField);
			} else {
				models.add(model);
				forBabies.add(babyField);
			}
		}
		boolean sorted = forBabies.contains(baby);
		// A head read off one model beats a whole mob read off another, which has no head to read.
		Face head = null;
		Face whole = null;
		boolean headsDiffer = false;
		boolean wholesDiffer = false;
		for (int i = 0; i < models.size(); i++) {
			if (sorted && forBabies.get(i) != baby) {
				continue;
			}
			Face read = ModelFaces.of(models.get(i), state);
			if (read == null) {
				continue;
			}
			if (read.whole()) {
				wholesDiffer |= whole != null && !whole.equals(read);
				whole = read;
			} else {
				headsDiffer |= head != null && !head.equals(read);
				head = read;
			}
		}
		if (head != null) {
			return headsDiffer ? null : head;
		}
		return wholesDiffer ? null : whole;
	}

	/**
	 * The model a pufferfish or tropical fish is drawn with. Their renderers keep one model per puff
	 * size or body shape, with different faces, and switch to this fish's as they draw it, so the
	 * one they hold is the last fish's. Null for other renderers.
	 */
	@SuppressWarnings("rawtypes")
	private static @Nullable Model fishModel(LivingEntityRenderer renderer, Entity entity) {
		if (renderer instanceof dev.diorama.mixin.PufferfishRendererAccessor puffer && entity instanceof net.minecraft.world.entity.animal.fish.Pufferfish fish) {
			return switch (fish.getPuffState()) {
				case 0 -> puffer.diorama$small();
				case 1 -> puffer.diorama$mid();
				default -> puffer.diorama$big();
			};
		}
		if (renderer instanceof dev.diorama.mixin.TropicalFishRendererAccessor tropical && entity instanceof net.minecraft.world.entity.animal.fish.TropicalFish fish) {
			//? if >=1.21.5 {
			net.minecraft.world.entity.animal.fish.TropicalFish.Base shape = fish.getPattern().base();
			//?} else {
			/*net.minecraft.world.entity.animal.fish.TropicalFish.Base shape = fish.getVariant().base();
			*///?}
			return shape == net.minecraft.world.entity.animal.fish.TropicalFish.Base.SMALL ? tropical.diorama$small() : tropical.diorama$large();
		}
		return null;
	}

	private static boolean babyName(String name) {
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("baby") || lower.contains("small") || lower.contains("child");
	}

	private static List<java.lang.reflect.Field> modelFields(Class<?> type) {
		List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
		for (Class<?> c = type; c != null && c != LivingEntityRenderer.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Field field : c.getDeclaredFields()) {
				if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && Model.class.isAssignableFrom(field.getType())) {
					try {
						field.setAccessible(true);
						fields.add(field);
					} catch (RuntimeException e) {
						// Unreadable: left out.
					}
				}
			}
		}
		return List.copyOf(fields);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Looked lookUp(Entity entity, int age, boolean baby) {
		//? if >=1.21.2 {
		// Only living-entity renderers say which texture they use; others, such as GeckoLib's, don't.
		EntityRenderer<?, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		if (!(renderer instanceof LivingEntityRenderer living)) {
			return new Looked(null, null, age, baby);
		}
		try {
			LivingEntityRenderState state = (LivingEntityRenderState) ((LivingEntityRenderer) living).createRenderState((LivingEntity) entity, 1.0F);
			return new Looked(((LivingEntityRenderer) living).getTextureLocation(state), modelFace(living, entity, baby, state), age, baby);
		} catch (RuntimeException e) {
			return new Looked(null, null, age, baby);
		}
		//?} else {
		/*// Renderers had no render state yet, and every one, GeckoLib's too, said which texture it
		// draws an entity with. Only living-entity renderers have a model to read a face off.
		EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		try {
			Identifier texture = ((EntityRenderer) renderer).getTextureLocation(entity);
			Face face = renderer instanceof LivingEntityRenderer living ? modelFace(living, entity, baby, null) : null;
			return new Looked(texture, face, age, baby);
		} catch (RuntimeException e) {
			return new Looked(null, null, age, baby);
		}
		*///?}
	}
}
