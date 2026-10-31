package dev.diorama;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.waypoint.WaypointStore;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The maps each dimension of a world has, and which one is in use. A server can rebuild its world
 * at the same address on the same seed, and nothing the client is sent tells the new world from the
 * old one, so the player starts a new map themselves and can go back to the old one, as with Xaero's
 * World Map. Like Xaero's, each dimension has maps of its own: a new Overworld map leaves the Nether's
 * as it was.
 *
 * <p>Every dimension starts with its main map, the one Diorama has always kept, in
 * {@code dim-<dimension>} inside the world's folder. Maps the player makes are listed in
 * {@code maps.json} beside it and keep their tiles in {@code dim-<dimension>-map-<id>}. A waypoint or
 * drawing notes the map it was made on ({@code map}, empty for the main map), and only those of the
 * map in use show. The map in use is saved with the list, so the world opens on it next time.
 *
 * <p>Client thread only.
 */
public final class Maps {
	/** The main map's ID, also what a waypoint or drawing made on it notes. */
	public static final String MAIN = "";
	public static final int MAX_NAME = 32;
	private static final String FILE = "maps.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static @Nullable Maps current;

	private final String worldKey;
	private final Path file;
	private Saved saved = new Saved();

	/** What {@code maps.json} holds: for each dimension that has more than its main map, by ID. */
	private static final class Saved {
		Map<String, Dimension> dimensions = new LinkedHashMap<>();
	}

	private static final class Dimension {
		/** The map in use; {@link #MAIN} for the main one. */
		String active = MAIN;
		/** The main map's name, once the player renames it. */
		@Nullable String mainName;
		/** The player deleted the main map: it's no longer listed, and its folder is gone. */
		boolean mainDeleted;
		List<Entry> maps = new ArrayList<>();
	}

	private static final class Entry {
		String id = "";
		String name = "";
		long created;
	}

	/** A map as the Maps screen lists it. {@code created} is 0 when it isn't known (an old main map). */
	public record Info(String id, String name, long created, boolean active, Path folder) {
	}

	private Maps(String worldKey) {
		this.worldKey = worldKey;
		this.file = WorldKey.folder(worldKey).resolve(FILE);
		this.load();
	}

	/** The maps of the world the client is in, or null outside one or while its name isn't known yet. */
	public static @Nullable Maps get() {
		String key = WorldKey.current();
		return key == null ? null : of(key);
	}

	/** The maps of a world by its key, read again whenever another world's were last asked for. */
	public static Maps of(String worldKey) {
		Maps maps = current;
		if (maps == null || !maps.worldKey.equals(worldKey)) {
			maps = new Maps(worldKey);
			current = maps;
		}
		return maps;
	}

	/** Called on leaving a world: the next one reads its own list, and a test can edit this one's file between. */
	public static void onLeave() {
		current = null;
	}

	public String worldKey() {
		return this.worldKey;
	}

	/** The dimensions that have a map other than their main one, or had one. */
	public List<String> dimensions() {
		return List.copyOf(this.saved.dimensions.keySet());
	}

	/**
	 * The dimensions the map can be switched to, from the one the player is in: the Overworld, the
	 * Nether and the End, then the rest the server has by name, those with a map saved, even if the
	 * one in use there is still empty. A dimension has only a hashed folder, so its ID can't be read
	 * back from the disk.
	 */
	public List<String> saved(String here, Collection<String> serverLevels) {
		LinkedHashSet<String> known = new LinkedHashSet<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
		serverLevels.stream().sorted().forEach(known::add);
		known.addAll(this.dimensions());
		known.add(here);
		return known.stream().filter(dimension -> dimension.equals(here) || this.list(dimension).stream().anyMatch(map -> Files.isDirectory(map.folder()))).toList();
	}

	/** The ID of the map in use in a dimension. */
	public String active(String dimension) {
		Dimension saved = this.saved.dimensions.get(dimension);
		return saved == null ? MAIN : saved.active;
	}

	/** The map's ID a waypoint or drawing notes ({@code null} for the main map) as an ID. */
	public static String id(@Nullable String noted) {
		return noted == null ? MAIN : noted;
	}

	/** What a waypoint or drawing made on this map notes: null for the main map, so its file reads as before. */
	public static @Nullable String noted(String id) {
		return id.isEmpty() ? null : id;
	}

	/** The folder a map keeps its tiles in. */
	public Path tileFolder(String dimension, String id) {
		String folder = WorldKey.key("dim", dimension);
		return WorldKey.folder(this.worldKey).resolve(id.isEmpty() ? folder : folder + "-map-" + id);
	}

	/** The dimension's maps: the main one first unless it was deleted, then the others oldest first. */
	public List<Info> list(String dimension) {
		Dimension saved = this.saved.dimensions.get(dimension);
		String active = saved == null ? MAIN : saved.active;
		List<Info> out = new ArrayList<>();
		if (saved == null || !saved.mainDeleted) {
			out.add(new Info(MAIN, this.name(dimension, MAIN), mainCreated(this.tileFolder(dimension, MAIN)), active.isEmpty(), this.tileFolder(dimension, MAIN)));
		}
		if (saved != null) {
			for (Entry entry : saved.maps) {
				out.add(new Info(entry.id, entry.name, entry.created, entry.id.equals(active), this.tileFolder(dimension, entry.id)));
			}
		}
		return out;
	}

	/** Whether the dimension has more than one map, so the map screen names the one in use. Asked every frame. */
	public boolean several(String dimension) {
		Dimension saved = this.saved.dimensions.get(dimension);
		return saved != null && saved.maps.size() + (saved.mainDeleted ? 0 : 1) > 1;
	}

	/** A map's name; the main map is "Main" until renamed. */
	public String name(String dimension, String id) {
		Dimension saved = this.saved.dimensions.get(dimension);
		if (id.isEmpty()) {
			return saved != null && saved.mainName != null ? saved.mainName : Component.translatable("diorama.maps.main").getString();
		}
		if (saved != null) {
			for (Entry entry : saved.maps) {
				if (entry.id.equals(id)) {
					return entry.name;
				}
			}
		}
		return id;
	}

	/** Whether another of the dimension's maps already has this name, in any case. */
	public boolean taken(String dimension, String name, @Nullable String except) {
		String wanted = name.trim();
		for (Info info : this.list(dimension)) {
			if (!info.id().equals(except) && info.name().equalsIgnoreCase(wanted)) {
				return true;
			}
		}
		return false;
	}

	/** A name for a new map no other of the dimension's has: Map 2, Map 3, ... */
	public String suggestedName(String dimension) {
		for (int n = this.list(dimension).size() + 1; ; n++) {
			String name = Component.translatable("diorama.maps.numbered", n).getString();
			if (!this.taken(dimension, name, null)) {
				return name;
			}
		}
	}

	/**
	 * Makes an empty map and switches the dimension to it. Returns its ID, or null for a name that
	 * won't do or a list that couldn't be saved.
	 */
	public @Nullable String create(String dimension, String name) {
		String trimmed = name.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME || this.taken(dimension, trimmed, null)) {
			return null;
		}
		String before = GSON.toJson(this.saved);
		Dimension saved = this.saved.dimensions.computeIfAbsent(dimension, d -> new Dimension());
		Entry entry = new Entry();
		// Random rather than counted, so a map made after one was deleted can't find anything it left.
		do {
			entry.id = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
		} while (Files.exists(this.tileFolder(dimension, entry.id)) || saved.maps.stream().anyMatch(e -> e.id.equals(entry.id)));
		entry.name = trimmed;
		entry.created = System.currentTimeMillis();
		saved.maps.add(entry);
		saved.active = entry.id;
		if (!this.saveOrUndo(before)) {
			return null;
		}
		this.changed(dimension);
		return entry.id;
	}

	/**
	 * Switches the dimension to another of its maps. The map in use is saved and closed first, as on
	 * a dimension change, and the waypoints and drawings shown become the other map's.
	 */
	public boolean use(String dimension, String id) {
		if (!this.exists(dimension, id)) {
			return false;
		}
		String before = GSON.toJson(this.saved);
		Dimension saved = this.saved.dimensions.computeIfAbsent(dimension, d -> new Dimension());
		boolean changed = !saved.active.equals(id);
		saved.active = id;
		if (!this.saveOrUndo(before)) {
			return false;
		}
		if (changed) {
			Diorama.LOGGER.info("{} of {} is now on map {}", dimension, this.worldKey, id.isEmpty() ? "main" : id);
			this.changed(dimension);
		}
		return true;
	}

	public boolean rename(String dimension, String id, String name) {
		String trimmed = name.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME || this.taken(dimension, trimmed, id) || !this.exists(dimension, id)) {
			return false;
		}
		String before = GSON.toJson(this.saved);
		Dimension saved = this.saved.dimensions.computeIfAbsent(dimension, d -> new Dimension());
		if (id.isEmpty()) {
			saved.mainName = trimmed;
		} else {
			saved.maps.stream().filter(e -> e.id.equals(id)).forEach(e -> e.name = trimmed);
		}
		return this.saveOrUndo(before);
	}

	/**
	 * Deletes a map that isn't in use: its tiles, and the waypoints and drawings made on it. The
	 * list is saved first, so a folder that can't be deleted completely is at least no longer a map;
	 * if the list can't be saved, nothing is deleted.
	 */
	public boolean delete(String dimension, String id) {
		Dimension saved = this.saved.dimensions.get(dimension);
		if (saved == null || saved.active.equals(id) || !this.exists(dimension, id)) {
			return false;
		}
		String before = GSON.toJson(this.saved);
		if (id.isEmpty()) {
			saved.mainDeleted = true;
		} else {
			saved.maps.removeIf(e -> e.id.equals(id));
		}
		if (!this.saveOrUndo(before)) {
			return false;
		}
		WaypointStore.mapDeleted(this.worldKey, dimension, id);
		DrawingStore.mapDeleted(this.worldKey, dimension, id);
		Path folder = this.tileFolder(dimension, id);
		try {
			deleteTree(folder);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't delete all of the map in {}", folder, e);
		}
		return true;
	}

	private boolean exists(String dimension, String id) {
		return this.list(dimension).stream().anyMatch(info -> info.id().equals(id));
	}

	/** The world's own loaded map and stores follow a switch, whichever copy of the list it was made through. */
	private void changed(String dimension) {
		if (!this.worldKey.equals(WorldKey.current())) {
			return;
		}
		MapSession.mapChanged(dimension);
		WaypointStore.mapChanged(dimension);
		DrawingStore.mapChanged(dimension);
	}

	/** When the main map's folder was made, if the file system knows. */
	private static long mainCreated(Path folder) {
		try {
			return Files.isDirectory(folder) ? Files.readAttributes(folder, java.nio.file.attribute.BasicFileAttributes.class).creationTime().toMillis() : 0;
		} catch (IOException e) {
			return 0;
		}
	}

	private static void deleteTree(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(folder)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	/** How many bytes a map's tiles take on disk. Walks the folder, so call it off the client thread. */
	public static long size(Path folder) {
		if (!Files.isDirectory(folder)) {
			return 0;
		}
		try (Stream<Path> paths = Files.walk(folder)) {
			return paths.filter(Files::isRegularFile).mapToLong(path -> {
				try {
					return Files.size(path);
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		} catch (IOException | java.io.UncheckedIOException e) {
			return 0;
		}
	}

	/** A size as players read it: 840 KB, 12.4 MB, 1.2 GB. */
	public static String readableSize(long bytes) {
		if (bytes < 1024 * 1024) {
			return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
		}
		double mb = bytes / (1024.0 * 1024.0);
		if (mb < 1024) {
			return String.format(Locale.ROOT, mb < 100 ? "%.1f MB" : "%.0f MB", mb);
		}
		return String.format(Locale.ROOT, "%.1f GB", mb / 1024);
	}

	private void load() {
		if (!Files.isRegularFile(this.file)) {
			return;
		}
		boolean damaged = false;
		try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			JsonElement dimensions = root.isJsonObject() ? root.getAsJsonObject().get("dimensions") : null;
			if (dimensions == null || !dimensions.isJsonObject()) {
				damaged = true;
				dimensions = new JsonObject();
			}
			// One dimension at a time, so a bad one costs only itself. Also dropped: what a hand edit
			// might have broken, like entries without an ID or a name, repeated IDs, and a map in use
			// that isn't there.
			for (Map.Entry<String, JsonElement> dimension : dimensions.getAsJsonObject().entrySet()) {
				Dimension in;
				try {
					in = GSON.fromJson(dimension.getValue(), Dimension.class);
				} catch (RuntimeException e) {
					damaged = true;
					Diorama.LOGGER.warn("Skipping the maps of {} in {}, which couldn't be read", dimension.getKey(), this.file, e);
					continue;
				}
				if (in == null) {
					continue;
				}
				Dimension clean = new Dimension();
				clean.mainName = in.mainName == null || in.mainName.isBlank() ? null : in.mainName.trim();
				clean.mainDeleted = in.mainDeleted;
				if (in.maps != null) {
					for (Entry entry : in.maps) {
						if (entry != null && entry.id != null && !entry.id.isEmpty() && entry.id.matches("[0-9a-f]+")
							&& entry.name != null && !entry.name.isBlank() && clean.maps.stream().noneMatch(e -> e.id.equals(entry.id))) {
							entry.name = entry.name.trim();
							clean.maps.add(entry);
						}
					}
				}
				String noted = in.active == null ? MAIN : in.active;
				boolean known = noted.isEmpty() ? !clean.mainDeleted : clean.maps.stream().anyMatch(e -> e.id.equals(noted));
				String active = noted;
				if (!known) {
					active = !clean.mainDeleted || clean.maps.isEmpty() ? MAIN : clean.maps.get(0).id;
					if (active.isEmpty()) {
						clean.mainDeleted = false;
					}
				}
				clean.active = active;
				this.saved.dimensions.put(dimension.getKey(), clean);
			}
		} catch (Exception e) {
			damaged = true;
			Diorama.LOGGER.error("Couldn't read this world's maps from {}; using each dimension's main map", this.file, e);
		}
		if (damaged) {
			// The next save rewrites the file, so what couldn't be read is kept beside it.
			Path backup = this.file.resolveSibling(FILE + ".unreadable-" + System.currentTimeMillis());
			try {
				Files.copy(this.file, backup);
				Diorama.LOGGER.warn("Kept a copy of the unreadable maps file at {}", backup);
			} catch (IOException e) {
				Diorama.LOGGER.error("Couldn't back up {}", this.file, e);
			}
		}
	}

	/** Saves the list; if that fails, puts back the list as it was before the change and says so. */
	private boolean saveOrUndo(String before) {
		if (this.save()) {
			return true;
		}
		this.saved = GSON.fromJson(before, Saved.class);
		return false;
	}

	private boolean save() {
		try {
			Files.createDirectories(this.file.getParent());
			Path temp = this.file.resolveSibling(FILE + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(this.saved, writer);
			}
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.error("Couldn't save this world's maps to {}", this.file, e);
			return false;
		}
	}
}
