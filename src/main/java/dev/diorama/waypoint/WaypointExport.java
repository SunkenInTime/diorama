package dev.diorama.waypoint;

import dev.diorama.Diorama;
import dev.diorama.Maps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Saves a world's waypoints as files, in Xaero's Minimap's own format and folders ({@link XaeroImport}
 * describes both), so they can go into Xaero's Minimap, back into Diorama through its Xaero's import,
 * or be kept as a copy. Each export is a folder of its own in {@code .minecraft/diorama/exports},
 * holding what Xaero's keeps in {@code .minecraft/xaero/minimap} for this world: the world's folder,
 * a folder per dimension in it, and a file per map.
 *
 * <p>In singleplayer the map in use is {@code waypoints.txt}, the file Xaero's opens. Each other map
 * is a world folder of its own in {@code Other maps}, with its waypoints as that world's
 * {@code waypoints.txt}: beside a {@code waypoints.txt}, Xaero's reads a world it lists after it into
 * that same world, so two maps never share a dimension's folder. On a server every map is a world of
 * the kind Xaero's lets the player add ({@code cm$<id>_<name>.txt}), since which of a server's worlds
 * Xaero's opens depends on what it saw there.
 *
 * <p>Folders are Xaero's sets. Xaero's can't hide a set, so a waypoint in a hidden folder is written
 * disabled, as one hidden on its own is. Temporary waypoints stay out, as Xaero's keeps its own out
 * of its files; waystones belong to the Waystones mod, and Diorama doesn't keep them either. Icons and
 * the time a pin was made have no place in the format.
 */
public final class WaypointExport {
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
	/** The set every waypoint is in until put in another: the name Xaero's gives it. */
	static final String DEFAULT_SET = "gui.xaero_default";
	/** What Xaero's writes between the sets and the waypoints. */
	private static final String HEADER = "#\n#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination\n#\n";
	/** Xaero's waypoint types: an ordinary one, the latest death and older deaths. */
	static final int NORMAL = 0;
	static final int DEATH = 1;
	static final int OLD_DEATH = 2;
	/** The folder in a singleplayer export with a world folder for each map not in use. */
	private static final String OTHER_MAPS = "Other maps";

	/** An export written: the folder it's in, and how many waypoints it holds. */
	public record Result(Path folder, int count) {
	}

	/** What the player was told about an export, and whether it was written. */
	public record Told(Component message, boolean done) {
	}

	private WaypointExport() {
	}

	/** Whether the world the client is in has waypoints to export. */
	public static boolean available() {
		WaypointStore store = WaypointStore.get();
		return store != null && !store.kept().isEmpty();
	}

	/**
	 * Exports the waypoints of the world the client is in, and says how it went in chat, with a link
	 * that opens the folder. Returns the same for the screen asking, without the link.
	 */
	public static Told run() {
		return run(null, Minecraft.getInstance().getSingleplayerServer() != null);
	}

	/**
	 * {@link #run()}, with {@code world} for the name Xaero's gives this world's folder unless it's
	 * null, and the layout for singleplayer or a server: for tests, which can't name their world.
	 */
	public static Told run(@Nullable String world, boolean singleplayer) {
		Minecraft mc = Minecraft.getInstance();
		Component chat;
		Component screen;
		boolean done = false;
		try {
			Result result = export(world, singleplayer);
			Component count = count(result.count());
			Path absolute = result.folder().toAbsolutePath();
			Component link = Component.literal(absolute.getFileName().toString())
				.withStyle(ChatFormatting.UNDERLINE)
				.withStyle(style -> style.withClickEvent(new ClickEvent.OpenFile(absolute.toString())));
			chat = Component.translatable("diorama.waypoints.exported", count, link);
			String shown = mc.gameDirectory.toPath().toAbsolutePath().relativize(absolute).toString().replace('\\', '/');
			screen = Component.translatable("diorama.waypoints.exported", count, shown);
			Diorama.LOGGER.info("Exported {} waypoints to {}", result.count(), absolute);
			done = true;
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.error("Couldn't export waypoints", e);
			chat = Component.translatable("diorama.waypoints.export_failed", String.valueOf(e.getMessage()));
			screen = chat;
		}
		//? if >=26.1 {
		mc.gui.hud.getChat().addClientSystemMessage(chat);
		//?} else {
		/*mc.gui.getChat().addMessage(chat);
		*///?}
		return new Told(screen, done);
	}

	private static Component count(int count) {
		return count == 1 ? Component.translatable("diorama.xaero.count_one") : Component.translatable("diorama.xaero.count", count);
	}

	/** Writes the current world's waypoints into a new folder in {@code .minecraft/diorama/exports}. */
	private static Result export(@Nullable String world, boolean singleplayer) throws IOException {
		Minecraft mc = Minecraft.getInstance();
		WaypointStore store = WaypointStore.get();
		Maps maps = Maps.get();
		if (store == null || maps == null) {
			throw new IOException("not in a world");
		}
		if (world == null) {
			List<String> names = XaeroImport.worldFolders(mc);
			// Xaero's names a Realm's folder by its owner and id; Diorama notes only the id, so its own name will do.
			world = names != null ? names.get(0) : maps.worldKey();
		}
		Path exports = mc.gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve("exports");
		Files.createDirectories(exports);
		String stamp = LocalDateTime.now().format(NAME);
		// Written beside its folder and moved into place once every file is there, so an export
		// that fails partway (a full disk, a name too long for a file) leaves nothing that looks
		// whole. Made here, so another export in the same second takes the next name.
		Path folder;
		Path partial;
		for (int i = 0; ; i++) {
			folder = exports.resolve("waypoints_" + stamp + (i == 0 ? "" : "_" + i));
			partial = partial(folder);
			if (!Files.exists(folder)) {
				try {
					Files.createDirectory(partial);
					break;
				} catch (FileAlreadyExistsException taken) {
					// The next name.
				}
			}
		}
		try {
			Result written = write(store, maps, partial.resolve(world), singleplayer);
			Files.move(partial, folder, StandardCopyOption.ATOMIC_MOVE);
			return new Result(folder, written.count());
		} catch (IOException | RuntimeException e) {
			deleteQuietly(partial);
			throw e;
		}
	}

	/** Where an export is written before it's complete. */
	private static Path partial(Path folder) {
		return folder.resolveSibling(folder.getFileName() + ".partial");
	}

	private static void deleteQuietly(Path folder) {
		if (!Files.exists(folder)) {
			return;
		}
		try (Stream<Path> files = Files.walk(folder)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(file);
			}
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't delete the unfinished export {}", folder, e);
		}
	}

	/**
	 * The file {@code file} in the dimension folder {@code folder} of {@code world}, refused unless
	 * it's exactly that: a hand-edited waypoint file can give a dimension or map any ID, and a
	 * {@code ..} or a separator in one mustn't write anywhere else.
	 */
	public static Path within(Path world, String folder, String file) throws IOException {
		Path root = world.toAbsolutePath().normalize();
		Path target = root.resolve(folder).resolve(file);
		if (target.getNameCount() != root.getNameCount() + 2 || !target.normalize().equals(target) || !target.startsWith(root)) {
			throw new IOException(folder + "/" + file + " would be outside the export's folder");
		}
		return target;
	}

	/**
	 * Writes {@code store}'s waypoints into {@code world}, the folder named as Xaero's names this
	 * world's, in Xaero's layout. Separate from {@link #export()} for tests and tools.
	 */
	public static Result write(WaypointStore store, Maps maps, Path world, boolean singleplayer) throws IOException {
		// By dimension, then by map, in the order they were made.
		Map<String, Map<String, List<Waypoint>>> files = new LinkedHashMap<>();
		for (Waypoint waypoint : store.kept()) {
			files.computeIfAbsent(waypoint.dimension, d -> new LinkedHashMap<>()).computeIfAbsent(Maps.id(waypoint.map), m -> new ArrayList<>()).add(waypoint);
		}
		List<String> sets = store.customFolders().stream().map(WaypointExport::setName).toList();
		// The Other maps folders taken so far, as Windows compares them.
		Set<String> taken = new HashSet<>();
		int count = 0;
		for (Map.Entry<String, Map<String, List<Waypoint>>> dimension : files.entrySet()) {
			String folder = dimensionFolder(dimension.getKey());
			for (Map.Entry<String, List<Waypoint>> map : dimension.getValue().entrySet()) {
				String name = maps.name(dimension.getKey(), map.getKey());
				Path target;
				if (!singleplayer) {
					target = within(world, folder, "cm$" + (map.getKey().isEmpty() ? "main" : map.getKey()) + "_" + fileName(name) + ".txt");
				} else if (map.getKey().equals(maps.active(dimension.getKey()))) {
					target = within(world, folder, "waypoints.txt");
				} else {
					String other = unused(plain(Waypoints.dimensionName(dimension.getKey()).getString() + " - " + name), taken);
					target = within(within(world.resolveSibling(OTHER_MAPS), other, world.getFileName().toString()), folder, "waypoints.txt");
				}
				Files.createDirectories(target.getParent());
				// Never over another map's file, should two names come out the same.
				Files.writeString(target, text(store, map.getValue(), sets), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				count += map.getValue().size();
			}
		}
		return new Result(world.getParent(), count);
	}

	/**
	 * {@code folder}, or with a number after it if another map took it first: two map names can
	 * differ only by what a folder can't hold ({@code Base:1} and {@code Base1}).
	 */
	private static String unused(String folder, Set<String> taken) {
		String name = folder;
		for (int i = 2; !taken.add(name.toLowerCase(Locale.ROOT)); i++) {
			name = folder + " (" + i + ")";
		}
		return name;
	}

	/** One of Xaero's files: its sets, the header, and a line per waypoint. */
	static String text(WaypointStore store, List<Waypoint> waypoints, List<String> sets) {
		StringBuilder out = new StringBuilder("sets:").append(DEFAULT_SET);
		for (String set : sets) {
			out.append(':').append(set);
		}
		out.append('\n').append(HEADER);
		// Xaero's has one latest death and older ones; Diorama keeps a few deaths alike.
		Waypoint latest = waypoints.stream().filter(w -> w.kind == Waypoint.Kind.DEATH).max(Comparator.comparingLong(w -> w.created)).orElse(null);
		for (Waypoint waypoint : waypoints) {
			int type = waypoint.kind != Waypoint.Kind.DEATH ? NORMAL : waypoint == latest ? DEATH : OLD_DEATH;
			String folder = store.folderName(waypoint.folder);
			out.append(line(waypoint, type, folder == null ? DEFAULT_SET : setName(folder), !store.shown(waypoint))).append('\n');
		}
		return out.toString();
	}

	/**
	 * A waypoint's line, {@code waypoint:name:initials:x:y:z:colour:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination},
	 * with the name the player sees: an automatic pin's in their language, a death's with its date,
	 * since the format has no other place for it. The rest are Xaero's defaults.
	 */
	static String line(Waypoint waypoint, int type, String set, boolean disabled) {
		String name = WaypointShare.chatSafe(waypoint.shownName()).trim();
		return "waypoint:" + safe(name.isEmpty() ? "Waypoint" : name) + ":" + safe(WaypointShare.chatSafe(waypoint.label())) + ":"
			+ waypoint.x + ":" + waypoint.y + ":" + waypoint.z + ":" + colorIndex(waypoint.color) + ":" + disabled + ":" + type + ":" + set
			+ ":false:0:0:false";
	}

	/** Xaero's escape for the colons that would split a field. */
	private static String safe(String text) {
		return text.replace(":", "§§");
	}

	/** A folder's name as a set's: Xaero's doesn't escape these, so a colon would end it. */
	static String setName(String folder) {
		return WaypointShare.chatSafe(folder).replace(':', '-');
	}

	/**
	 * Xaero's colour for a waypoint's: one that comes back as the same colour through the import
	 * ({@link XaeroImport#color}, then the nearest palette colour) when Xaero's has one, else the
	 * nearest. Xaero's has 21 colours to the palette's 16, so a few can't come back as they were.
	 */
	static int colorIndex(int argb) {
		int palette = WaypointShare.nearestPaletteColor(argb & 0xFFFFFF);
		int best = 0;
		boolean bestReturns = false;
		long bestDistance = Long.MAX_VALUE;
		for (int i = 0; i < XaeroImport.COLORS; i++) {
			int xaero = XaeroImport.color(i);
			boolean returns = WaypointShare.nearestPaletteColor(xaero) == palette;
			long distance = distance(argb, xaero);
			if (returns && !bestReturns || returns == bestReturns && distance < bestDistance) {
				best = i;
				bestReturns = returns;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static long distance(int a, int b) {
		long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
		long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
		long db = (a & 0xFF) - (b & 0xFF);
		return dr * dr + dg * dg + db * db;
	}

	/** Xaero's folder for a dimension; {@link XaeroImport#dimension} reads it back. */
	static String dimensionFolder(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "dim%0";
			case "minecraft:the_nether" -> "dim%-1";
			case "minecraft:the_end" -> "dim%1";
			default -> {
				String folder = "dim%" + dimension.replace(':', '$').replace('/', '%');
				yield folder.endsWith(".") ? folder.substring(0, folder.length() - 1) + "," : folder;
			}
		};
	}

	/**
	 * A map's name as the part of a file name Xaero's reads a world's name from, escaped as Xaero's
	 * escapes it ({@code _} as {@code %us%}, {@code :} as {@code §§}), less what no file name may hold.
	 */
	static String fileName(String name) {
		return ending(WaypointShare.chatSafe(name).replace("_", "%us%").replaceAll("[\\\\/*?\"<>|]", "").replace(":", "§§"));
	}

	/** A name as a file or folder can have it: without what Windows refuses, and not ending in a dot. */
	static String plain(String name) {
		return ending(WaypointShare.chatSafe(name).replaceAll("[\\\\/*?\"<>|:]", ""));
	}

	/** A cleaned name trimmed and not ending in a dot, which Windows drops; {@code Map} if nothing's left. */
	private static String ending(String name) {
		String cleaned = name.trim();
		while (cleaned.endsWith(".")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
		}
		return cleaned.isEmpty() ? "Map" : cleaned;
	}
}
