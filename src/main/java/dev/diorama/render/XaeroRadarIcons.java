package dev.diorama.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import dev.diorama.compat.Pixelmon;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Radar icons from Xaero's Minimap's icon files, so the icons mod authors and pack makers already
 * made for Xaero's show on Diorama's radar too. The file for the mob {@code <namespace>:<name>} is
 * {@code assets/xaerominimap/entity/icon/definition/<namespace>/<name>.json} in any resource pack or
 * mod. Its {@code variants} map a variant ID, or {@code default}, to an icon:
 * <ul>
 * <li>{@code dot}: the radar's coloured dot.
 * <li>{@code normal_sprite:<path>} and {@code outlined_sprite:<path>}: the image at
 * {@code assets/xaerominimap/entity/icon/sprite/<path>}, cropped to what isn't see-through and drawn
 * on the pixel grid like the faces; the outlined one gets a one-pixel outline around its shape.
 * <li>{@code item:<item id>}: that item.
 * <li>{@code model}, {@code model:<n>} and {@code item} on its own (Xaero's renderers pick a part of
 * the model, or an item): Diorama's own face for the mob, or its dot if it has none.
 * </ul>
 * Xaero builds variant IDs with its own code; the one Diorama can match is the mob's texture
 * ({@code minecraft:textures/entity/fox/fox.png}), which is Xaero's ID for most mobs. The files are
 * read again on every resource reload (F3+T): each loader's entrypoint registers {@link #LISTENER}
 * with its own event. Anything else in a file is ignored, so files written for Xaero's work as they are.
 */
public final class XaeroRadarIcons {
	public static final Identifier ID = Diorama.id("xaero_radar_icons");
	public static final ResourceManagerReloadListener LISTENER = XaeroRadarIcons::reload;
	private static final String NAMESPACE = "xaerominimap";
	private static final String DEFINITIONS = "entity/icon/definition/";
	private static final String SPRITES = "entity/icon/sprite/";
	/** Xaero's outline and its icons' edges skip pixels under 5% opaque; so does the crop. */
	private static final int SOLID = 13;

	/** What the radar draws for a mob, from its file. */
	sealed interface Icon permits Basic, Sprite, ItemIcon {
	}

	enum Basic implements Icon {
		/** Diorama's own face, or its dot if it has none: Xaero's model renderer, or no usable entry. */
		OWN_FACE,
		DOT
	}

	record ItemIcon(ItemStack stack) implements Icon {
	}

	/** One mob's file: icons for the variant IDs Diorama can match (textures), and the default. */
	private record Definition(Map<String, Icon> byTexture, Icon fallback) {
	}

	private static @Nullable Map<EntityType<?>, Definition> definitions;
	/** Sprites by location, shared by every file that names them; their textures are made when first drawn. */
	private static final Map<SpriteKey, Sprite> sprites = new HashMap<>();
	/** Sprites a mod named that can't be drawn, so they're tried once per resource load, not every frame. */
	private static final Set<Identifier> unreadable = new HashSet<>();

	private record SpriteKey(Identifier location, boolean outlined, boolean flipped) {
	}

	private XaeroRadarIcons() {
	}

	/** The icon a file gives this entity, or null for Diorama's own face (or dot). */
	static @Nullable Icon of(Entity entity) {
		Map<EntityType<?>, Definition> loaded = definitions;
		if (loaded == null) {
			// The listener runs in the game's first resource load, so this is only a fallback.
			load(Minecraft.getInstance().getResourceManager());
			loaded = definitions;
		}
		Definition definition = loaded.get(entity.getType());
		Icon icon = null;
		if (definition != null) {
			if (!definition.byTexture().isEmpty()) {
				Identifier texture = MobFaces.texture(entity);
				icon = texture == null ? null : definition.byTexture().get(texture.toString());
			}
			if (icon == null) {
				icon = definition.fallback();
			}
		}
		return icon == null || icon == Basic.OWN_FACE ? pokemon(entity) : icon;
	}

	/** A Pixelmon Pokémon's sprite, outlined to stand out from the map like a face. A pack's file for the mob comes first. */
	private static @Nullable Icon pokemon(Entity entity) {
		Identifier location = Pixelmon.sprite(entity);
		if (location == null || unreadable.contains(location)) {
			return null;
		}
		ResourceManager manager = Minecraft.getInstance().getResourceManager();
		List<String> problems = new ArrayList<>();
		Sprite sprite = sprite(location, true, false, manager, problems);
		if (sprite == null) {
			unreadable.add(location);
			Diorama.LOGGER.warn("Radar icon for {}: {}", entity.getType(), String.join("; ", problems));
		}
		return sprite;
	}

	/**
	 * What a file makes the radar draw for this entity, for tests: {@code "dot"},
	 * {@code "item <id>"}, {@code "outlined_sprite <location> <w>x<h>"} (the cropped size), or
	 * {@code "normal_sprite ..."}; null for Diorama's own face.
	 */
	public static @Nullable String describe(Entity entity) {
		// Not a pattern switch: the 1.20.1 jar is built for Java 17.
		Icon icon = of(entity);
		if (icon instanceof Basic basic) {
			return basic.name().toLowerCase(java.util.Locale.ROOT);
		}
		if (icon instanceof ItemIcon item) {
			return "item " + BuiltInRegistries.ITEM.getKey(item.stack().getItem());
		}
		if (icon instanceof Sprite sprite) {
			return (sprite.outlined ? "outlined_sprite " : sprite.flipped ? "sprite " : "normal_sprite ") + sprite.location + " " + sprite.w + "x" + sprite.h;
		}
		return null;
	}

	/** Reads the files again; runs on the game thread at the end of each resource reload. */
	public static void reload(ResourceManager manager) {
		load(manager);
		// Waypoint icons and the minimap's frame are resources too; this listener is the one both loaders already register.
		WaypointIcons.reload();
		MinimapFrames.reload();
	}

	private static void load(ResourceManager manager) {
		for (Sprite sprite : sprites.values()) {
			sprite.close();
		}
		sprites.clear();
		unreadable.clear();
		Map<EntityType<?>, Definition> loaded = new HashMap<>();
		// Like Xaero's, one file per mob in the game: the top pack's file wins, and files for mobs
		// that aren't installed (Xaero's own example_mod, a modpack's leftovers) are never read.
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			Identifier mob = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			Identifier file = Identifier.fromNamespaceAndPath(NAMESPACE, DEFINITIONS + mob.getNamespace() + "/" + mob.getPath() + ".json");
			Optional<Resource> resource = manager.getResource(file);
			if (resource.isEmpty()) {
				continue;
			}
			List<String> problems = new ArrayList<>();
			try (Reader reader = resource.get().openAsReader()) {
				Definition definition = parse(JsonParser.parseReader(reader), manager, problems);
				if (definition != null) {
					loaded.put(type, definition);
				}
			} catch (IOException | RuntimeException e) {
				problems.add(String.valueOf(e.getMessage()));
			}
			if (!problems.isEmpty()) {
				Diorama.LOGGER.warn("Radar icon file {} (from {}): {}", file, resource.get().sourcePackId(), String.join("; ", problems));
			}
		}
		definitions = loaded;
		if (!loaded.isEmpty()) {
			Diorama.LOGGER.info("Radar icons from Xaero's Minimap icon files: {} loaded", loaded.size());
		}
	}

	/** A file's icons, or null if it sets nothing Diorama would draw differently. */
	private static @Nullable Definition parse(JsonElement json, ResourceManager manager, List<String> problems) {
		if (!json.isJsonObject()) {
			problems.add("not a JSON object");
			return null;
		}
		JsonElement variants = json.getAsJsonObject().get("variants");
		if (variants == null || variants.isJsonNull()) {
			return null;
		}
		if (!variants.isJsonObject()) {
			problems.add("\"variants\" isn't an object");
			return null;
		}
		Map<String, Icon> byTexture = new HashMap<>();
		Icon fallback = Basic.OWN_FACE;
		for (Map.Entry<String, JsonElement> entry : ((JsonObject) variants).entrySet()) {
			String key = entry.getKey();
			JsonElement value = entry.getValue();
			Icon icon = value.isJsonPrimitive() ? icon(value.getAsString(), manager, problems) : null;
			if (icon == null) {
				if (!value.isJsonPrimitive()) {
					problems.add("\"" + key + "\" isn't a string");
				}
				continue;
			}
			if (key.equals("default")) {
				fallback = icon;
			} else {
				byTexture.put(key, icon);
			}
		}
		if (fallback == Basic.OWN_FACE && byTexture.values().stream().allMatch(icon -> icon == Basic.OWN_FACE)) {
			return null;
		}
		return new Definition(Map.copyOf(byTexture), fallback);
	}

	/** One entry's icon, read the way Xaero reads it, or null for an entry Xaero would skip. */
	private static @Nullable Icon icon(String value, ResourceManager manager, List<String> problems) {
		// Xaero splits on every colon, so "item:minecraft:diamond" is three parts.
		String[] parts = value.split(":");
		if (parts.length == 0) {
			// Only colons: nothing Xaero's can read, so skip the entry, not the file.
			problems.add("\"" + value + "\" isn't a form Xaero's reads");
			return null;
		}
		switch (parts[0]) {
			case "dot":
				return Basic.DOT;
			case "model":
				if (parts.length > 2) {
					problems.add("\"" + value + "\" isn't a form Xaero's reads");
					return null;
				}
				return Basic.OWN_FACE;
			case "item":
				if (parts.length == 1) {
					// Xaero picks an item for the mob; Diorama shows its face.
					return Basic.OWN_FACE;
				}
				if (parts.length > 3) {
					problems.add("\"" + value + "\" isn't a form Xaero's reads");
					return null;
				}
				Identifier id = parts.length == 2 ? Identifier.tryParse(parts[1]) : Identifier.tryBuild(parts[1], parts[2]);
				Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
				if (item.isEmpty()) {
					// Xaero shows a dot when its item renderer fails.
					problems.add("no item " + value.substring("item:".length()));
					return Basic.DOT;
				}
				ItemStack stack = new ItemStack(item.get());
				return stack.isEmpty() ? Basic.DOT : new ItemIcon(stack);
			case "normal_sprite", "outlined_sprite", "sprite":
				// "sprite" is the form before normal_sprite. Xaero's still reads it, and draws its
				// image upside down compared with normal_sprite's.
				if (parts.length != 2) {
					problems.add("\"" + value + "\" isn't a form Xaero's reads");
					return null;
				}
				Identifier location = Identifier.tryBuild(NAMESPACE, SPRITES + parts[1]);
				if (location == null) {
					problems.add("\"" + parts[1] + "\" isn't a valid file name");
					return Basic.OWN_FACE;
				}
				Sprite sprite = sprite(location, parts[0].equals("outlined_sprite"), parts[0].equals("sprite"), manager, problems);
				return sprite == null ? Basic.OWN_FACE : sprite;
			default:
				problems.add("\"" + value + "\" isn't a form Xaero's reads");
				return null;
		}
	}

	private static @Nullable Sprite sprite(Identifier location, boolean outlined, boolean flipped, ResourceManager manager, List<String> problems) {
		SpriteKey key = new SpriteKey(location, outlined, flipped);
		Sprite cached = sprites.get(key);
		if (cached != null) {
			return cached;
		}
		Optional<Resource> resource = manager.getResource(location);
		if (resource.isEmpty()) {
			problems.add("no image " + location);
			return null;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			Sprite sprite = Sprite.cropped(location, image, outlined, flipped);
			if (sprite == null) {
				problems.add("image " + location + " is see-through");
				return null;
			}
			sprites.put(key, sprite);
			return sprite;
		} catch (IOException | RuntimeException e) {
			problems.add("can't read image " + location + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * An icon image, cropped to its opaque pixels. Drawn on the pixel grid like the faces: the image
	 * is shrunk by a whole factor when it would be much bigger than a face, so pixel art stays crisp,
	 * and each shrunk copy becomes a texture the first time it's drawn.
	 */
	static final class Sprite implements Icon {
		final Identifier location;
		final boolean outlined;
		/** Upside down: Xaero's old "sprite" form. The image is stored flipped. */
		final boolean flipped;
		final int w;
		final int h;
		/** The cropped image, ARGB, row by row. */
		private final int[] argb;
		private final Map<Integer, Baked> baked = new HashMap<>();

		/**
		 * One shrunk copy in a texture of {@code edge} pixels: the image with a pixel of space all
		 * round at (0, 0), {@code w + 2} x {@code h + 2}, and beside it at ({@code edge / 2}, 0) a white
		 * mask of the image grown by a pixel each way, the outline and shadow of outlined sprites.
		 */
		private record Baked(MapTexture texture, int edge, int w, int h) {
		}

		private Sprite(Identifier location, boolean outlined, boolean flipped, int w, int h, int[] argb) {
			this.location = location;
			this.outlined = outlined;
			this.flipped = flipped;
			this.w = w;
			this.h = h;
			this.argb = argb;
		}

		static @Nullable Sprite cropped(Identifier location, NativeImage image, boolean outlined, boolean flipped) {
			int width = image.getWidth();
			int height = image.getHeight();
			int[] all = new int[width * height];
			int x0 = width;
			int y0 = height;
			int x1 = -1;
			int y1 = -1;
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					//? if >=1.21.2 {
					int argb = image.getPixel(x, y);
					//?} else {
					/*// Only the ABGR getter existed; swap red and blue.
					int abgr = image.getPixelRGBA(x, y);
					int argb = (abgr & 0xFF00FF00) | (abgr >> 16 & 0xFF) | (abgr & 0xFF) << 16;
					*///?}
					all[(flipped ? height - 1 - y : y) * width + x] = argb;
					if (argb >>> 24 >= SOLID) {
						int row = flipped ? height - 1 - y : y;
						x0 = Math.min(x0, x);
						y0 = Math.min(y0, row);
						x1 = Math.max(x1, x);
						y1 = Math.max(y1, row);
					}
				}
			}
			if (x1 < 0) {
				return null;
			}
			int w = x1 - x0 + 1;
			int h = y1 - y0 + 1;
			int[] argb = new int[w * h];
			for (int y = 0; y < h; y++) {
				System.arraycopy(all, (y0 + y) * width + x0, argb, y * w, w);
			}
			return new Sprite(location, outlined, flipped, w, h, argb);
		}

		/**
		 * Draws the sprite as pixel art centred on (x, y), about as big as a face drawn {@code size}
		 * across: a drop shadow, the outline in {@code outline} if it's an outlined sprite, and the image.
		 */
		void drawPixel(IconBatch icons, float x, float y, float size, int color, int outline, int alpha) {
			int factor = this.factor(size);
			Baked b = this.baked(factor);
			float texel = Radar.texel(size, Math.max(b.w(), b.h()));
			float x0 = x - b.w() * texel / 2;
			float y0 = y - b.h() * texel / 2;
			GuiGraphicsExtractor graphics = icons.graphics();
			graphics.pose().pushMatrix();
			graphics.pose().translate(x0, y0);
			graphics.pose().scale(texel);
			int shadow = Radar.withAlpha(0x60000000, alpha);
			if (this.outlined) {
				this.mask(icons, b, 0, 0, shadow);
				this.mask(icons, b, -1, -1, outline);
			} else {
				// The image's own shape, darkened.
				this.image(icons, b, 0, 0, b.w() + 2, b.h() + 2, shadow);
			}
			this.image(icons, b, -1, -1, b.w() + 1, b.h() + 1, color);
			graphics.pose().popMatrix();
		}

		/** Draws the image fitted in a square {@code size} across centred on (x, y), with no outline: the framed style's box is its edge. */
		void drawFitted(IconBatch icons, float x, float y, float size, int color) {
			Baked b = this.baked(this.factor(size));
			float scale = size / Math.max(b.w(), b.h());
			float w = b.w() * scale;
			float h = b.h() * scale;
			GuiGraphicsExtractor graphics = icons.graphics();
			graphics.pose().pushMatrix();
			graphics.pose().translate(x - w / 2, y - h / 2);
			graphics.pose().scale(scale);
			this.image(icons, b, -1, -1, b.w() + 1, b.h() + 1, color);
			graphics.pose().popMatrix();
		}

		private void image(IconBatch icons, Baked b, float x0, float y0, float x1, float y1, int color) {
			float edge = b.edge();
			icons.rect(b.texture(), Sampling.CLAMP_CRISP, x0, y0, x1, y1, 0, 0, (b.w() + 2) / edge, (b.h() + 2) / edge, color);
		}

		private void mask(IconBatch icons, Baked b, float dx, float dy, int color) {
			float edge = b.edge();
			float u0 = (b.edge() / 2) / edge;
			icons.rect(b.texture(), Sampling.CLAMP_CRISP, dx, dy, dx + b.w() + 2, dy + b.h() + 2, u0, 0, u0 + (b.w() + 2) / edge, (b.h() + 2) / edge, color);
		}

		/**
		 * How much to shrink the image for an icon about {@code size} GUI pixels across: the least
		 * whole factor that keeps it within one and a half faces on the pixel grid (a face being
		 * 8 pixels), so it keeps as much of its detail as fits.
		 */
		private int factor(float size) {
			float most = Radar.texel(size, 8) * 8 * 1.5F;
			int longest = Math.max(this.w, this.h);
			for (int factor = 1; ; factor++) {
				int texels = (longest + factor - 1) / factor;
				if (texels == 1 || texels * Radar.texel(size, texels) <= most) {
					return factor;
				}
			}
		}

		private Baked baked(int factor) {
			Baked b = this.baked.get(factor);
			if (b == null) {
				b = this.bake(factor);
				this.baked.put(factor, b);
			}
			return b;
		}

		/**
		 * The image shrunk by {@code factor}, each pixel the average of the block it covers,
		 * see-through below half cover; or, if that leaves nothing (an image of thin lines), at any cover.
		 */
		private Baked bake(int factor) {
			Baked b = this.bake(factor, false);
			if (b != null) {
				return b;
			}
			b = this.bake(factor, true);
			// Never null: the image has an opaque pixel, so some block has some cover.
			return java.util.Objects.requireNonNull(b);
		}

		private @Nullable Baked bake(int factor, boolean anyCover) {
			int sw = (this.w + factor - 1) / factor;
			int sh = (this.h + factor - 1) / factor;
			int pw = sw + 2;
			int ph = sh + 2;
			int edge = 2 * Math.max(pw, ph);
			int[] abgr = new int[edge * edge];
			boolean[] solid = new boolean[pw * ph];
			boolean any = false;
			for (int sy = 0; sy < sh; sy++) {
				for (int sx = 0; sx < sw; sx++) {
					int pixel = factor == 1 ? this.argb[sy * this.w + sx] : this.average(sx * factor, sy * factor, factor, anyCover);
					int a = pixel >>> 24;
					if (a < SOLID) {
						continue;
					}
					// ARGB to ABGR (RGBA in memory).
					abgr[(sy + 1) * edge + sx + 1] = (pixel & 0xFF00FF00) | (pixel >> 16 & 0xFF) | (pixel & 0xFF) << 16;
					solid[(sy + 1) * pw + sx + 1] = true;
					any = true;
				}
			}
			if (!any) {
				return null;
			}
			// The mask: every pixel within one of the image, diagonals too, as Xaero's outline does.
			int maskX = edge / 2;
			for (int y = 0; y < ph; y++) {
				for (int x = 0; x < pw; x++) {
					boolean near = false;
					for (int dy = -1; dy <= 1 && !near; dy++) {
						for (int dx = -1; dx <= 1 && !near; dx++) {
							int nx = x + dx;
							int ny = y + dy;
							near = nx >= 0 && ny >= 0 && nx < pw && ny < ph && solid[ny * pw + nx];
						}
					}
					if (near) {
						abgr[y * edge + maskX + x] = 0xFFFFFFFF;
					}
				}
			}
			MapTexture texture = MapTexture.create("diorama radar icon " + this.location, edge, 1);
			texture.write(0, 0, 0, edge, abgr);
			return new Baked(texture, edge, sw, sh);
		}

		private int average(int x0, int y0, int factor, boolean anyCover) {
			double a = 0;
			double r = 0;
			double g = 0;
			double b = 0;
			int count = 0;
			for (int y = y0; y < Math.min(this.h, y0 + factor); y++) {
				for (int x = x0; x < Math.min(this.w, x0 + factor); x++) {
					int pixel = this.argb[y * this.w + x];
					double pa = (pixel >>> 24) / 255.0;
					a += pa;
					r += (pixel >> 16 & 0xFF) * pa;
					g += (pixel >> 8 & 0xFF) * pa;
					b += (pixel & 0xFF) * pa;
					count++;
				}
			}
			// Under half covered is see-through, so edges stay as sharp as the faces'.
			if (anyCover ? a * 255 < SOLID : a < count / 2.0) {
				return 0;
			}
			return 0xFF000000 | (int) Math.round(r / a) << 16 | (int) Math.round(g / a) << 8 | (int) Math.round(b / a);
		}

		private void close() {
			for (Baked b : this.baked.values()) {
				b.texture().close();
			}
			this.baked.clear();
		}
	}
}
