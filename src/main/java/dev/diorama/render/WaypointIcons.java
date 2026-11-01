package dev.diorama.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * The pictures a waypoint can show instead of its initials. An icon is named by an id:
 * {@code diorama:bed} is the image {@code assets/diorama/textures/waypoint_icons/bed.png}, so a
 * resource pack can redraw the set or add to it. Icons are pixel art on a square {@link #TILE},
 * one texel per GUI pixel, with their own dark outline; the pin adds a white edge round them.
 */
public final class WaypointIcons {
	/** Every icon sits on a square tile this many pixels across, so a wide bed and a small face come out the same size. */
	public static final int TILE = 11;
	/** Diorama's own icons, in the picker's order: kinds of places people mark. */
	public static final List<String> OFFERED = List.of(
		"diorama:home", "diorama:bed", "diorama:farm", "diorama:animals", "diorama:villagers",
		"diorama:mine", "diorama:storage", "diorama:portal", "diorama:danger", "diorama:treasure");
	/** What the bed waypoint shows. */
	public static final String BED = "diorama:bed";
	/** What a portal's pin shows, and what marks a waypoint of the player's as a portal's. */
	public static final String PORTAL = "diorama:portal";
	private static final String FOLDER = "textures/waypoint_icons/";
	private static final Map<String, Optional<PinArt.Image>> IMAGES = new HashMap<>();

	private WaypointIcons() {
	}

	/** The icon's pixels on its tile, or null if there's no such icon (the pin then shows initials). Render thread. */
	public static PinArt.@Nullable Image image(String id) {
		Optional<PinArt.Image> known = IMAGES.get(id);
		if (known == null) {
			PinArt.Image loaded = load(id, Minecraft.getInstance().getResourceManager());
			known = Optional.ofNullable(loaded == null ? null : tiled(loaded));
			IMAGES.put(id, known);
		}
		return known.orElse(null);
	}

	/** Forgets the icons and the pins drawn with them: a resource reload may have changed them. */
	public static void reload() {
		IMAGES.clear();
		Pins.forgetIcons();
	}

	/** Every icon there is: Diorama's own in order, then any a resource pack adds, by id. */
	public static List<String> all() {
		List<String> ids = new ArrayList<>(OFFERED);
		List<String> added = new ArrayList<>();
		for (Identifier file : Minecraft.getInstance().getResourceManager().listResources("textures/waypoint_icons", file -> file.getPath().endsWith(".png")).keySet()) {
			String id = file.getNamespace() + ":" + file.getPath().substring(FOLDER.length(), file.getPath().length() - ".png".length());
			if (!ids.contains(id)) {
				added.add(id);
			}
		}
		added.sort(null);
		ids.addAll(added);
		return ids;
	}

	private static PinArt.@Nullable Image load(String id, ResourceManager manager) {
		Identifier name = Identifier.tryParse(id);
		if (name == null) {
			return null;
		}
		Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), FOLDER + name.getPath() + ".png");
		Optional<Resource> resource = manager.getResource(file);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			return cropped(image);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** The image cut to its opaque pixels; null if it has none. */
	private static PinArt.@Nullable Image cropped(NativeImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int[] all = new int[width * height];
		int x0 = width;
		int y0 = height;
		int x1 = -1;
		int y1 = -1;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int argb = argb(image, x, y);
				all[y * width + x] = argb;
				if (argb >>> 24 >= 128) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
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
		return PinArt.fromArgb(w, h, argb);
	}

	/** An image's pixel as ARGB. */
	static int argb(NativeImage image, int x, int y) {
		//? if >=1.21.2 {
		return image.getPixel(x, y);
		//?} else {
		/*// Only the ABGR getter existed; swap red and blue.
		int abgr = image.getPixelRGBA(x, y);
		return (abgr & 0xFF00FF00) | (abgr >> 16 & 0xFF) | (abgr & 0xFF) << 16;
		*///?}
	}

	/** The icon centred on a {@link #TILE}, shrunk by a whole factor first if it's bigger, so every pin is the same size. */
	private static PinArt.Image tiled(PinArt.Image image) {
		int factor = (Math.max(image.width, image.height) + TILE - 1) / TILE;
		int w = image.width / factor;
		int h = image.height / factor;
		int x0 = (TILE - w) / 2;
		int y0 = (TILE - h) / 2;
		int[] argb = new int[TILE * TILE];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				argb[(y0 + y) * TILE + x0 + x] = image.argb(x * factor, y * factor);
			}
		}
		return PinArt.fromArgb(TILE, TILE, argb);
	}
}
