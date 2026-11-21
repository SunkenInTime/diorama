package dev.diorama.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.diorama.Diorama;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * Modded blocks' colours on a server, which has no textures loaded: read from the mod jars' own
 * block states, models and textures, the way BlueMap draws modded blocks. Mods ship one jar for
 * both sides, so their assets are in the server's mods folder too.
 *
 * <p>Follows a state's variant (or the multipart cases that apply) to its model, and the model up
 * its parents, then averages the textures on the faces the client would: those culled on top for
 * the top colour, on the south (or north) for the side, and the uncull faces of crosses and the
 * like into both, as {@link BlockLooks} does with the baked model. Vanilla's own parent models
 * (cube_all, cube_column, cross, ...) are only in the client jar; their faces are written out
 * here. A block whose model a mod makes in code has no faces to read, and takes its map colour.
 *
 * <p>Tint code is client-only too: a tinted face counts as tinted by the biome's foliage colour on
 * leaves and by its grass colour on anything else.
 */
public final class JarModels {
	/** How deep a model's parents may go before it counts as a loop. */
	private static final int MAX_PARENTS = 32;

	/** Each asset path ("assets/ns/...") in the jars, and the jar it's in; the first jar listed wins. */
	private final Map<String, ZipFile> assets = new HashMap<>();
	private final List<ZipFile> jars = new ArrayList<>();
	/**
	 * Vanilla's block colours and the look of each state in them, for models that borrow
	 * Minecraft's own textures, which only the client jar has. Null until {@link #useVanilla}.
	 */
	private @Nullable VanillaTable vanilla;
	private int @Nullable [] vanillaLookOf;
	private final Map<String, @Nullable JsonObject> json = new HashMap<>();
	private final Map<String, float @Nullable []> textures = new HashMap<>();
	private boolean warned;

	private JarModels() {
	}

	/** The assets of every jar in {@code folder} (not its subfolders). */
	public static JarModels of(Path folder) {
		JarModels models = new JarModels();
		if (Files.isDirectory(folder)) {
			try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.jar")) {
				for (Path file : files) {
					models.add(file);
				}
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't list the mods in {} for their block models", folder, e);
			}
		}
		return models;
	}

	private void add(Path file) {
		try {
			ZipFile zip = new ZipFile(file.toFile());
			boolean any = false;
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.startsWith("assets/") && (name.contains("/blockstates/") || name.contains("/models/") || name.contains("/textures/"))
					&& !name.startsWith("assets/minecraft/")) {
					this.assets.putIfAbsent(name, zip);
					any = true;
				}
			}
			if (any) {
				this.jars.add(zip);
			} else {
				zip.close();
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read {} for its block models", file, e);
		}
	}

	/**
	 * Lets models that use Minecraft's own textures (a grass slab on {@code minecraft:block/grass_block_top})
	 * take those textures' colours from the vanilla table. {@code lookOf} is the table's look by state id.
	 */
	synchronized void useVanilla(VanillaTable table, int[] lookOf) {
		this.vanilla = table;
		this.vanillaLookOf = lookOf;
		this.textures.clear();
	}

	/** How many jars had block assets, for the log. */
	public int jarCount() {
		return this.jars.size();
	}

	/** Lets go of the jars. */
	public synchronized void close() {
		for (ZipFile zip : this.jars) {
			try {
				zip.close();
			} catch (IOException ignored) {
			}
		}
		this.jars.clear();
		this.assets.clear();
	}

	/** A state's faces, top then side, or null if its jar has no model for it with a texture. */
	synchronized FaceColor @Nullable [] faces(BlockState state) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		int colon = id.indexOf(':');
		String namespace = id.substring(0, colon);
		String path = id.substring(colon + 1);
		try {
			JsonObject blockstate = this.json("assets/" + namespace + "/blockstates/" + path + ".json");
			if (blockstate == null) {
				return null;
			}
			Map<String, String> values = values(state);
			List<JsonObject> chosen = new ArrayList<>();
			if (blockstate.has("variants")) {
				for (Map.Entry<String, JsonElement> variant : blockstate.getAsJsonObject("variants").entrySet()) {
					if (matchesVariant(variant.getKey(), values)) {
						chosen.add(first(variant.getValue()));
						break;
					}
				}
			} else if (blockstate.has("multipart")) {
				for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
					JsonObject object = part.getAsJsonObject();
					if (!object.has("when") || matchesWhen(object.get("when"), values)) {
						chosen.add(first(object.get("apply")));
					}
				}
			}
			FaceColor top = new FaceColor();
			FaceColor side = new FaceColor();
			String particle = null;
			for (JsonObject variant : chosen) {
				if (variant == null || !variant.has("model")) {
					continue;
				}
				int x = variant.has("x") ? Math.floorMod(variant.get("x").getAsInt(), 360) : 0;
				int y = variant.has("y") ? Math.floorMod(variant.get("y").getAsInt(), 360) : 0;
				// A model turned on its side (a log lying down) shows a side on top.
				boolean onSide = x == 90 || x == 270;
				Model model = this.model(variant.get("model").getAsString());
				if (model == null) {
					continue;
				}
				particle = particle != null ? particle : model.texture("#particle");
				this.add(model, top, side, onSide, y / 90);
			}
			if (top.isEmpty() && side.isEmpty() && particle != null) {
				// Like a block entity's model: only the particle texture to go by.
				float[] avg = this.texture(particle);
				if (avg != null) {
					top.add(avg, -1);
					side.add(avg, -1);
				}
			}
			if (top.isEmpty() && side.isEmpty()) {
				return null;
			}
			return new FaceColor[] {top.isEmpty() ? side : top, side.isEmpty() ? top : side};
		} catch (RuntimeException e) {
			if (!this.warned) {
				this.warned = true;
				Diorama.LOGGER.warn("Couldn't read the block model of {} ({}); such blocks take their map colour", state, e.toString());
			}
			return null;
		}
	}

	/** Which biome colour a tinted face of this block takes. */
	static byte tintOf(BlockState state) {
		return state.is(BlockTags.LEAVES) ? Looks.TINT_FOLIAGE : Looks.TINT_GRASS;
	}

	/** Adds the model's faces; {@code onSide} when the variant turns it over onto its side. */
	private void add(Model model, FaceColor top, FaceColor side, boolean onSide, int quarterTurns) {
		FaceColor up = onSide ? side : top;
		FaceColor front = onSide ? top : side;
		// Turned about the vertical, the face that ends up south is another of the model's.
		boolean cullsSouth = false;
		for (Face face : model.faces) {
			cullsSouth |= "south".equals(turn(face.cull, quarterTurns));
		}
		for (Face face : model.faces) {
			String cull = turn(face.cull, quarterTurns);
			float[] avg = this.texture(model.texture(face.texture));
			if (avg == null) {
				continue;
			}
			if (cull == null) {
				// Unculled, as BlockLooks's loose quads: flat ones count on top, upright ones on both.
				if (face.direction.equals("up") || face.direction.equals("down")) {
					up.add(avg, face.tint);
				} else {
					top.add(avg, face.tint);
					side.add(avg, face.tint);
				}
			} else if (cull.equals("up")) {
				up.add(avg, face.tint);
			} else if (cull.equals("south") || cull.equals("north") && !cullsSouth) {
				front.add(avg, face.tint);
			}
		}
	}

	/** A side turned clockwise, seen from above, by quarter turns, as a variant's {@code y} turns its model. */
	private static @Nullable String turn(@Nullable String side, int quarterTurns) {
		if (side == null || quarterTurns == 0) {
			return side;
		}
		String[] around = {"north", "east", "south", "west"};
		for (int i = 0; i < 4; i++) {
			if (around[i].equals(side)) {
				return around[(i + quarterTurns) & 3];
			}
		}
		return side;
	}

	private static @Nullable JsonObject first(JsonElement element) {
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			return array.isEmpty() ? null : array.get(0).getAsJsonObject();
		}
		return element.getAsJsonObject();
	}

	/** The state's properties by name, as their values are written in block state files. */
	private static Map<String, String> values(BlockState state) {
		Map<String, String> out = new HashMap<>();
		for (Property<?> property : state.getProperties()) {
			out.put(property.getName(), valueName(state, property));
		}
		return out;
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	/** A variants key: "" for every state, else "a=b,c=d", each of which must hold. */
	private static boolean matchesVariant(String key, Map<String, String> values) {
		if (key.isEmpty() || key.equals("normal")) {
			return true;
		}
		for (String pair : key.split(",")) {
			int eq = pair.indexOf('=');
			if (eq < 0 || !pair.substring(eq + 1).equals(values.get(pair.substring(0, eq)))) {
				return false;
			}
		}
		return true;
	}

	/** A multipart case's "when": every property listed holds one of its values ("a|b"), or OR/AND of those. */
	private static boolean matchesWhen(JsonElement when, Map<String, String> values) {
		JsonObject object = when.getAsJsonObject();
		if (object.has("OR")) {
			for (JsonElement any : object.getAsJsonArray("OR")) {
				if (matchesWhen(any, values)) {
					return true;
				}
			}
			return false;
		}
		if (object.has("AND")) {
			for (JsonElement all : object.getAsJsonArray("AND")) {
				if (!matchesWhen(all, values)) {
					return false;
				}
			}
			return true;
		}
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			String have = values.get(entry.getKey());
			boolean any = false;
			for (String want : entry.getValue().getAsString().split("\\|")) {
				any |= want.equals(have);
			}
			if (!any) {
				return false;
			}
		}
		return true;
	}

	/** One face of a model element: which way it faces, its culling side, texture name and tint layer. */
	private record Face(String direction, @Nullable String cull, String texture, int tint) {
	}

	/** A model with its parents worked in: its textures by name, and its elements' faces. */
	private static final class Model {
		final Map<String, String> textures = new HashMap<>();
		final List<Face> faces = new ArrayList<>();

		/** The texture a name resolves to ("#side" goes through the textures), or null. */
		@Nullable String texture(String name) {
			String value = name;
			for (int i = 0; i < 16 && value != null && value.startsWith("#"); i++) {
				value = this.textures.get(value.substring(1));
			}
			if (value == null && !name.startsWith("#")) {
				value = this.textures.get(name);
				for (int i = 0; i < 16 && value != null && value.startsWith("#"); i++) {
					value = this.textures.get(value.substring(1));
				}
			}
			return value;
		}
	}

	private @Nullable Model model(String name) {
		Model model = new Model();
		boolean haveFaces = false;
		String at = name;
		for (int depth = 0; at != null && depth < MAX_PARENTS; depth++) {
			ResourceName location = ResourceName.of(at);
			JsonObject json = location.namespace.equals("minecraft") ? null : this.json("assets/" + location.namespace + "/models/" + location.path + ".json");
			if (json == null) {
				// A vanilla parent (or a model this jar lacks): vanilla's are written out here.
				if (!haveFaces) {
					haveFaces = VanillaParents.faces(location.path, (direction, cull, texture, tint) -> model.faces.add(new Face(direction, cull, texture, tint)));
				}
				break;
			}
			if (json.has("textures")) {
				for (Map.Entry<String, JsonElement> texture : json.getAsJsonObject("textures").entrySet()) {
					if (texture.getValue().isJsonPrimitive()) {
						model.textures.putIfAbsent(texture.getKey(), texture.getValue().getAsString());
					}
				}
			}
			if (!haveFaces && json.has("elements")) {
				haveFaces = true;
				for (JsonElement element : json.getAsJsonArray("elements")) {
					JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
					if (faces == null) {
						continue;
					}
					for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
						JsonObject f = face.getValue().getAsJsonObject();
						if (!f.has("texture")) {
							continue;
						}
						String cull = f.has("cullface") ? f.get("cullface").getAsString() : null;
						model.faces.add(new Face(face.getKey(), cull, f.get("texture").getAsString(), f.has("tintindex") ? f.get("tintindex").getAsInt() : -1));
					}
				}
			}
			at = json.has("parent") ? json.get("parent").getAsString() : null;
		}
		return model;
	}

	/** A namespaced name, "minecraft" when none is given. */
	private record ResourceName(String namespace, String path) {
		static ResourceName of(String name) {
			int colon = name.indexOf(':');
			return colon < 0 ? new ResourceName("minecraft", name) : new ResourceName(name.substring(0, colon), name.substring(colon + 1));
		}
	}

	private @Nullable JsonObject json(String path) {
		if (this.json.containsKey(path)) {
			return this.json.get(path);
		}
		JsonObject parsed = null;
		ZipFile zip = this.assets.get(path);
		if (zip != null) {
			try (Reader reader = new InputStreamReader(zip.getInputStream(zip.getEntry(path)), StandardCharsets.UTF_8)) {
				JsonElement element = JsonParser.parseReader(reader);
				parsed = element.isJsonObject() ? element.getAsJsonObject() : null;
			} catch (IOException | RuntimeException e) {
				parsed = null;
			}
		}
		this.json.put(path, parsed);
		return parsed;
	}

	/**
	 * A texture's {r, g, b, coverage} as {@link BlockLooks} averages a sprite's: the stored (sRGB)
	 * values of the pixels at least 10% opaque, weighted by opacity; an animation's first frame.
	 * Null if no jar has it.
	 */
	private float @Nullable [] texture(@Nullable String name) {
		if (name == null) {
			return null;
		}
		if (this.textures.containsKey(name)) {
			return this.textures.get(name);
		}
		ResourceName location = ResourceName.of(name);
		String path = "assets/" + location.namespace + "/textures/" + location.path + ".png";
		float[] avg = null;
		ZipFile zip = this.assets.get(path);
		if (zip != null) {
			try (InputStream in = zip.getInputStream(zip.getEntry(path))) {
				BufferedImage image = ImageIO.read(in);
				avg = image == null ? null : average(image);
			} catch (IOException | RuntimeException e) {
				avg = null;
			}
		} else if (location.namespace.equals("minecraft")) {
			avg = this.vanillaTexture(location.path);
		}
		this.textures.put(name, avg);
		return avg;
	}

	/** Texture name endings for a block's top, and for its sides. */
	private static final String[] TOP_ENDINGS = {"_top", "_end", "_bottom"};
	private static final String[] SIDE_ENDINGS = {"_side", "_front"};

	/**
	 * A Minecraft texture's average, which no jar on a server has, from the vanilla table: the
	 * colour of the block the texture is named after. {@code block/dirt} is dirt's, {@code block/oak_log_top}
	 * the top of oak_log, {@code block/grass_block_side} the side of grass_block and
	 * {@code block/grass_block_side_overlay} the tinted part of that side. A face the game tints
	 * (grass on top) comes out grey, for the model's own tint index to colour again. Null for a
	 * texture no block is named after.
	 */
	private float @Nullable [] vanillaTexture(String path) {
		if (this.vanilla == null || !path.startsWith("block/")) {
			return null;
		}
		String name = path.substring("block/".length());
		boolean overlay = name.endsWith("_overlay");
		if (overlay) {
			name = name.substring(0, name.length() - "_overlay".length());
		}
		boolean side = false;
		VanillaTable.Look look = this.vanillaLook(name);
		for (int i = 0; look == null && i < TOP_ENDINGS.length + SIDE_ENDINGS.length; i++) {
			boolean sideEnding = i >= TOP_ENDINGS.length;
			String ending = sideEnding ? SIDE_ENDINGS[i - TOP_ENDINGS.length] : TOP_ENDINGS[i];
			if (name.endsWith(ending)) {
				look = this.vanillaLook(name.substring(0, name.length() - ending.length()));
				side = sideEnding;
			}
		}
		if (look == null || look.kind() == Looks.AIR) {
			return null;
		}
		int untinted = side ? look.side() : look.top();
		int tinted = side ? look.sideTinted() : look.topTinted();
		float weight = side ? look.sideTintWeight() : look.topTintWeight();
		if (overlay) {
			// Only the tinted part, as much of it as the table's weight says the overlay covers.
			return weight <= 0 ? null : rgba(tinted, Math.min(1.0F, weight / Math.max(0.001F, 1.0F - weight)));
		}
		return rgba(weight >= 0.5F ? tinted : untinted, 1.0F);
	}

	/** The vanilla table's look of the default state of {@code minecraft:<name>}, or null. */
	private VanillaTable.@Nullable Look vanillaLook(String name) {
		try {
			Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath("minecraft", name)).orElse(null);
			if (block == null || this.vanillaLookOf == null) {
				return null;
			}
			int id = Block.getId(block.defaultBlockState());
			int index = id < this.vanillaLookOf.length ? this.vanillaLookOf[id] : -1;
			return index < 0 ? null : this.vanilla.looks.get(index);
		} catch (RuntimeException e) {
			// Not a valid block name.
			return null;
		}
	}

	private static float[] rgba(int rgb, float alpha) {
		return new float[] {(rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, alpha};
	}

	static float[] average(BufferedImage image) {
		int w = image.getWidth();
		// An animated texture is its frames one under another; the first is what the map takes.
		int h = image.getHeight() > w && image.getHeight() % w == 0 ? w : image.getHeight();
		double r = 0;
		double g = 0;
		double b = 0;
		double weight = 0;
		int visible = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = image.getRGB(x, y);
				int a = argb >>> 24;
				if (a < 26) {
					continue;
				}
				double wa = a / 255.0;
				r += ((argb >> 16) & 0xFF) * wa;
				g += ((argb >> 8) & 0xFF) * wa;
				b += (argb & 0xFF) * wa;
				weight += wa;
				visible++;
			}
		}
		if (weight == 0) {
			return new float[] {0.0F, 0.0F, 0.0F, 0.0F};
		}
		return new float[] {(float) (r / weight / 255), (float) (g / weight / 255), (float) (b / weight / 255), (float) visible / (w * h)};
	}
}
