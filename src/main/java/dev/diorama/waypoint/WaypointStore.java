package dev.diorama.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.diorama.Diorama;
import dev.diorama.Maps;
import dev.diorama.Settings;
import dev.diorama.WorldKey;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

/**
 * The waypoints of one world, saved as JSON in {@code .minecraft/diorama/<world>/waypoints.json}.
 * Each was made on one of its dimension's maps ({@link Maps}), and only those of the map in use in
 * their dimension are listed and shown; the rest are kept aside and saved with them. Temporary
 * waypoints stay in memory only. Waystones' waystones ({@link Waystones}) aren't kept
 * here, but show with this world's waypoints, and which of them are hidden is saved with the folders.
 * Client thread only.
 */
public final class WaypointStore {
	/** How many death markers to keep; older ones are dropped as new deaths come in. */
	public static final int DEATHS_KEPT = 3;

	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.registerTypeAdapter(UUID.class, new TypeAdapter<UUID>() {
			@Override
			public void write(JsonWriter out, UUID value) throws IOException {
				out.value(value.toString());
			}

			@Override
			public UUID read(JsonReader in) throws IOException {
				return UUID.fromString(in.nextString());
			}
		})
		.create();

	private static @Nullable WaypointStore current;

	private final String worldKey;
	private final Path file;
	/** The waypoints of the maps in use. */
	private final List<Waypoint> waypoints = new ArrayList<>();
	/** The waypoints of the dimensions' other maps, saved with the rest but not shown. */
	private final List<Waypoint> elsewhere = new ArrayList<>();
	private final Path foldersFile;
	private Folders folders = new Folders();
	/**
	 * Which dimensions' waypoints the player picked in the waypoint list to show in another
	 * dimension too. Kept until they leave the world, as Xaero's Minimap keeps the dimension its
	 * waypoint list was switched to, so nothing about it is saved.
	 */
	private final Set<ShownIn> shownIn = new LinkedHashSet<>();
	/** Each dimension's coordinate scale, once known: 8 for the Nether, where a block is eight of the Overworld's. */
	private final Map<String, Double> scales = new HashMap<>();

	/** The copies {@link #shownIn} made last for each dimension, by the waypoint each stands in for, to be made again in place. */
	private final Map<String, Map<Waypoint, Waypoint>> copies = new HashMap<>();

	/** {@code from}'s waypoints shown in {@code in}. */
	private record ShownIn(String in, String from) {
	}

	/**
	 * The player's own folders, which folders are hidden and the waypoint list's sort order, saved
	 * beside the waypoints in {@code folders.json}. Every dimension has a folder of its own without
	 * being listed here.
	 */
	private static final class Folders {
		List<String> custom = new ArrayList<>();
		/** Folder keys, as {@link #folderKey} makes them. */
		Set<String> hidden = new LinkedHashSet<>();
		/** A {@link WaypointSort} by name; none is Distance. Every folder of the world is sorted the same, as Xaero's sorts every set. */
		@Nullable String sort;
		boolean sortReversed;
	}

	private WaypointStore(String worldKey) {
		this.worldKey = worldKey;
		this.file = WorldKey.folder(worldKey).resolve("waypoints.json");
		this.foldersFile = WorldKey.folder(worldKey).resolve("folders.json");
		this.load();
		this.loadFolders();
	}

	/**
	 * The store for the world the client is in, loading it when the world changes. Null while the
	 * world isn't known, which includes a dimension change waiting for the server to name its
	 * level: the loaded store is kept through that, or its temporary waypoints would go.
	 */
	public static @Nullable WaypointStore get() {
		String key = WorldKey.current();
		if (key == null) {
			return null;
		}
		if (current == null || !current.worldKey.equals(key)) {
			current = new WaypointStore(key);
		}
		return current;
	}

	/**
	 * Called on leaving a world, and on joining one, since a network moves the player to another of
	 * its servers by joining again without leaving. Everything but temporary waypoints is already
	 * on disk, so dropping the loaded store is what makes those go: the next world reads its file.
	 */
	public static void onLeave() {
		current = null;
	}

	/** Opens the store saved under a world key, outside the usual current-world lookup. For tests and tools. */
	public static WaypointStore open(String worldKey) {
		return new WaypointStore(worldKey);
	}

	/**
	 * A dimension switched to another of its maps: its waypoints become that map's. Temporary ones
	 * were set on the map that's gone, so they go with it.
	 */
	public static void mapChanged(String dimension) {
		WaypointStore store = current;
		if (store == null) {
			return;
		}
		String active = Maps.of(store.worldKey).active(dimension);
		store.waypoints.removeIf(w -> w.dimension.equals(dimension) && w.kind == Waypoint.Kind.TEMPORARY);
		List<Waypoint> leaving = store.waypoints.stream().filter(w -> w.dimension.equals(dimension) && !Maps.id(w.map).equals(active)).toList();
		List<Waypoint> coming = store.elsewhere.stream().filter(w -> w.dimension.equals(dimension) && Maps.id(w.map).equals(active)).toList();
		store.waypoints.removeAll(leaving);
		store.elsewhere.removeAll(coming);
		store.elsewhere.addAll(leaving);
		store.waypoints.addAll(coming);
	}

	/** A map was deleted: the waypoints made on it go too. */
	public static void mapDeleted(String worldKey, String dimension, String map) {
		WaypointStore store = current != null && current.worldKey.equals(worldKey) ? current : new WaypointStore(worldKey);
		if (store.elsewhere.removeIf(w -> w.dimension.equals(dimension) && Maps.id(w.map).equals(map))) {
			store.save();
		}
	}

	/** How many waypoints a dimension's map has, for the Maps screen. */
	public int count(String dimension, String map) {
		int count = 0;
		for (List<Waypoint> list : List.of(this.waypoints, this.elsewhere)) {
			for (Waypoint waypoint : list) {
				if (waypoint.dimension.equals(dimension) && Maps.id(waypoint.map).equals(map) && waypoint.kind != Waypoint.Kind.TEMPORARY && !hidden(waypoint)) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * Whether this is a portal pin kept out of the player's sight: they deleted it, or portal pins
	 * are turned off. Those stay in the file ({@link Portals}), and nothing lists them.
	 */
	private static boolean hidden(Waypoint waypoint) {
		return waypoint.kind.isPortal() && (waypoint.deleted || !Settings.get().portalMarkers);
	}

	public List<Waypoint> all() {
		return this.waypoints.stream().filter(w -> !hidden(w)).toList();
	}

	/** The waypoints in a dimension on the map in use, hidden portal pins included. For {@link Portals}. */
	List<Waypoint> withHidden(String dimension) {
		return this.waypoints.stream().filter(w -> w.dimension.equals(dimension)).toList();
	}

	/** The waypoints in a dimension, its known waystones included. */
	public List<Waypoint> in(String dimension) {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint waypoint : this.waypoints) {
			if (waypoint.dimension.equals(dimension) && !hidden(waypoint)) {
				out.add(waypoint);
			}
		}
		for (Waypoint waystone : Waystones.in(dimension)) {
			out.add(this.withVisibility(waystone));
		}
		return out;
	}

	/**
	 * What a map or the world shows in a dimension: its waypoints ({@link #in}), then those of each
	 * dimension picked to show there too, as copies at this dimension's coordinates
	 * ({@link Waypoint#scaled}). A copy's {@link Waypoint#source} is the waypoint to act on.
	 */
	public List<Waypoint> shownIn(String dimension) {
		List<Waypoint> out = this.in(dimension);
		this.addShownIn(dimension, out);
		return out;
	}

	/**
	 * {@link #shownIn(String)} for a caller that asked {@link #in} for the dimension's own waypoints
	 * itself: {@code own} is that list, which another mod's hook on the call may have handed back
	 * shorter, or as a list that can't be added to (see {@link WaypointHud}).
	 */
	public List<Waypoint> shownIn(String dimension, List<Waypoint> own) {
		List<Waypoint> out = new ArrayList<>(own);
		this.addShownIn(dimension, out);
		return out;
	}

	/** Adds the copies of the waypoints picked to show in a dimension to its own, {@code out}. */
	private void addShownIn(String dimension, List<Waypoint> out) {
		// Asked every frame for the player's own dimension, which notes its scale while they're in it.
		double scale = this.coordinateScale(dimension);
		Map<Waypoint, Waypoint> previous = this.copies.getOrDefault(dimension, Map.of());
		Map<Waypoint, Waypoint> made = new IdentityHashMap<>();
		for (ShownIn pair : this.shownIn) {
			if (pair.in().equals(dimension)) {
				double divider = scale / this.coordinateScale(pair.from());
				for (Waypoint waypoint : this.in(pair.from())) {
					Waypoint copy = waypoint.scaled(divider, previous.get(waypoint));
					made.put(waypoint, copy);
					out.add(copy);
				}
			}
		}
		if (made.isEmpty()) {
			this.copies.remove(dimension);
		} else {
			this.copies.put(dimension, made);
		}
	}

	/** Whether {@code from}'s waypoints are shown in {@code in} too. */
	public boolean showsIn(String in, String from) {
		return this.shownIn.contains(new ShownIn(in, from));
	}

	/** Shows {@code from}'s waypoints in {@code in} too, or stops. */
	public void setShownIn(String in, String from, boolean shown) {
		if (shown) {
			this.shownIn.add(new ShownIn(in, from));
		} else {
			this.shownIn.remove(new ShownIn(in, from));
		}
	}

	/**
	 * A dimension's coordinate scale, as its dimension type gives it: the player's own dimension's
	 * from its level, another's from the singleplayer server, or from the dimension type of the
	 * same name, which the server sends and which the game's dimensions and most mods' have. 1 for
	 * one that can't be found, which leaves its waypoints where they are.
	 */
	public double coordinateScale(String dimension) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.level.dimension().identifier().toString().equals(dimension)) {
			double scale = mc.level.dimensionType().coordinateScale();
			this.scales.put(dimension, scale);
			return scale;
		}
		Double known = this.scales.get(dimension);
		if (known == null) {
			known = lookUpScale(mc, dimension);
			this.scales.put(dimension, known);
		}
		return known;
	}

	private static double lookUpScale(Minecraft mc, String dimension) {
		var server = mc.getSingleplayerServer();
		if (server != null) {
			for (ServerLevel level : server.getAllLevels()) {
				if (level.dimension().identifier().toString().equals(dimension)) {
					return level.dimensionType().coordinateScale();
				}
			}
		}
		if (mc.level != null) {
			//? if >=1.21.2 {
			Registry<DimensionType> types = mc.level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
			//?} else {
			/*Registry<DimensionType> types = mc.level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
			*///?}
			for (Map.Entry<ResourceKey<DimensionType>, DimensionType> type : types.entrySet()) {
				if (type.getKey().identifier().toString().equals(dimension)) {
					return type.getValue().coordinateScale();
				}
			}
		}
		return 1;
	}

	/** The known waystones in every dimension, which sit in the Waystones folder. */
	public List<Waypoint> waystones() {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint waystone : Waystones.all()) {
			out.add(this.withVisibility(waystone));
		}
		return out;
	}

	/** Waystones arrive fresh from the server, so whether one is hidden comes from the folders file. */
	private Waypoint withVisibility(Waypoint waystone) {
		waystone.visible = !this.folders.hidden.contains(waystoneKey(waystone.id));
		return waystone;
	}

	/** Hides or shows one waypoint. */
	public void setVisible(Waypoint waypoint, boolean visible) {
		waypoint.visible = visible;
		if (waypoint.kind == Waypoint.Kind.WAYSTONE) {
			this.setFolderHidden(waystoneKey(waypoint.id), !visible);
		} else {
			this.save();
		}
	}

	/** The key of a dimension's own folder. */
	public static String dimensionKey(String dimension) {
		return "dim:" + dimension;
	}

	/** The key of the Waystones folder. */
	public static final String WAYSTONES_KEY = "waystones";

	/** The key a hidden waystone is saved under, with the hidden folders. */
	private static String waystoneKey(UUID id) {
		return "waystone:" + id;
	}

	/** The key of a custom folder. */
	public static String customKey(String name) {
		return "folder:" + name;
	}

	/** The folder a waypoint is in: Waystones for a waystone, else its custom folder if that still exists, else its dimension's. */
	public String folderKey(Waypoint waypoint) {
		if (waypoint.kind == Waypoint.Kind.WAYSTONE) {
			return WAYSTONES_KEY;
		}
		String folder = this.folderName(waypoint.folder);
		return folder != null ? customKey(folder) : dimensionKey(waypoint.dimension);
	}

	/** The custom folders, in the order they were made. */
	public List<String> customFolders() {
		return List.copyOf(this.folders.custom);
	}

	/** The custom folder with this name, any case, as it's spelled; null if there's none. */
	public @Nullable String folderName(@Nullable String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		for (String folder : this.folders.custom) {
			if (folder.equalsIgnoreCase(name.trim())) {
				return folder;
			}
		}
		return null;
	}

	/** Makes a custom folder; false if the name is blank or already taken. */
	public boolean addFolder(String name) {
		String trimmed = name.trim();
		if (trimmed.isEmpty() || this.folderName(trimmed) != null) {
			return false;
		}
		this.folders.custom.add(trimmed);
		this.saveFolders();
		return true;
	}

	/** Renames a custom folder, taking its waypoints and its hidden state along; false if the new name won't do. */
	public boolean renameFolder(String old, String name) {
		String trimmed = name.trim();
		int index = this.folders.custom.indexOf(old);
		String clash = this.folderName(trimmed);
		if (index < 0 || trimmed.isEmpty() || clash != null && !clash.equals(old)) {
			return false;
		}
		// Its waypoints first, while the old name still finds the folder however they spell it.
		for (Waypoint waypoint : this.everyWaypoint()) {
			if (old.equals(this.folderName(waypoint.folder))) {
				waypoint.folder = trimmed;
			}
		}
		this.folders.custom.set(index, trimmed);
		if (this.folders.hidden.remove(customKey(old))) {
			this.folders.hidden.add(customKey(trimmed));
		}
		this.saveFolders();
		this.save();
		return true;
	}

	/** Deletes a custom folder; its waypoints go back to their dimensions' folders. */
	public void deleteFolder(String name) {
		if (!this.folders.custom.contains(name)) {
			return;
		}
		for (Waypoint waypoint : this.everyWaypoint()) {
			if (name.equals(this.folderName(waypoint.folder))) {
				waypoint.folder = "";
			}
		}
		this.folders.custom.remove(name);
		this.folders.hidden.remove(customKey(name));
		this.saveFolders();
		this.save();
	}

	public boolean folderHidden(String key) {
		return this.folders.hidden.contains(key);
	}

	/** Hides or shows a whole folder on the maps and in the world. */
	public void setFolderHidden(String key, boolean hidden) {
		if (hidden ? this.folders.hidden.add(key) : this.folders.hidden.remove(key)) {
			this.saveFolders();
		}
	}

	/** The order the waypoint list shows this world's folders in. */
	public WaypointSort sort() {
		return WaypointSort.named(this.folders.sort);
	}

	public boolean sortReversed() {
		return this.folders.sortReversed;
	}

	public void setSort(WaypointSort sort, boolean reversed) {
		if (sort != this.sort() || reversed != this.folders.sortReversed) {
			this.folders.sort = sort.name();
			this.folders.sortReversed = reversed;
			this.saveFolders();
		}
	}

	/** Whether a waypoint shows on the maps and in the world: it's visible and its folder isn't hidden. */
	public boolean shown(Waypoint waypoint) {
		return waypoint.visible && !this.folderHidden(this.folderKey(waypoint));
	}

	/** {@link #shown} for the current world's store; a waypoint with no store loaded goes by its own setting. */
	public static boolean isShown(Waypoint waypoint) {
		WaypointStore store = get();
		return store == null ? waypoint.visible : store.shown(waypoint);
	}

	/**
	 * Whether a waypoint is near enough to the player to show in the world and on the minimap, by
	 * the waypoint distance setting; the map shows every waypoint. Deaths, temporary waypoints and
	 * the bed show at any distance: there are only a few, and they're the ones players head back to.
	 */
	public static boolean isNear(Waypoint waypoint, Entity player) {
		int limit = Settings.get().waypointDistance;
		if (limit <= 0 || waypoint.kind == Waypoint.Kind.DEATH || waypoint.kind == Waypoint.Kind.TEMPORARY || waypoint.kind == Waypoint.Kind.BED) {
			return true;
		}
		return waypoint.distanceTo(player.getX(), player.getY(), player.getZ()) <= limit;
	}

	/** Adds a waypoint to the map in use in its dimension. */
	public void add(Waypoint waypoint) {
		this.onMapInUse(waypoint);
		this.waypoints.add(waypoint);
		if (waypoint.kind == Waypoint.Kind.DEATH) {
			List<Waypoint> deaths = new ArrayList<>(this.waypoints.stream().filter(w -> w.kind == Waypoint.Kind.DEATH).toList());
			deaths.sort(Comparator.comparingLong(w -> w.created));
			for (int i = 0; i < deaths.size() - DEATHS_KEPT; i++) {
				this.waypoints.remove(deaths.get(i));
			}
		}
		this.save();
	}

	/**
	 * Adds several waypoints with one save, for an import. Death markers among them don't push
	 * older ones out; the next death trims them as usual.
	 */
	public void addAll(List<Waypoint> added) {
		if (!added.isEmpty()) {
			added.forEach(this::onMapInUse);
			this.waypoints.addAll(added);
			this.save();
		}
	}

	private void onMapInUse(Waypoint waypoint) {
		waypoint.map = Maps.noted(Maps.of(this.worldKey).active(waypoint.dimension));
	}

	/** The shown waypoints and the other maps' together, for changes that apply to all of them. */
	private List<Waypoint> everyWaypoint() {
		List<Waypoint> all = new ArrayList<>(this.waypoints);
		all.addAll(this.elsewhere);
		return all;
	}

	/** Deletes a waypoint. A portal pin stays behind, hidden, or the portal would get a new one. */
	public void remove(Waypoint waypoint) {
		if (waypoint.kind.isPortal()) {
			waypoint.deleted = true;
			this.save();
		} else {
			this.forget(waypoint);
		}
	}

	/** Drops a waypoint for good. */
	void forget(Waypoint waypoint) {
		this.waypoints.remove(waypoint);
		this.save();
	}

	/** Call after changing a waypoint in place. */
	public void changed() {
		this.save();
	}

	/**
	 * Reads the file one entry at a time, so a single bad entry costs only itself. If anything
	 * couldn't be read, the file is copied aside first: the next save rewrites it, and the copy
	 * keeps whatever was lost.
	 */
	private void load() {
		if (!Files.isRegularFile(this.file)) {
			return;
		}
		boolean damaged = false;
		Maps maps = Maps.of(this.worldKey);
		try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonArray()) {
				damaged = true;
			} else {
				for (JsonElement element : root.getAsJsonArray()) {
					try {
						Waypoint waypoint = normalise(GSON.fromJson(element, Waypoint.class));
						if (waypoint != null) {
							(Maps.id(waypoint.map).equals(maps.active(waypoint.dimension)) ? this.waypoints : this.elsewhere).add(waypoint);
						} else {
							damaged = true;
						}
					} catch (RuntimeException e) {
						damaged = true;
						Diorama.LOGGER.warn("Skipping a waypoint in {} that couldn't be read: {}", this.file, element, e);
					}
				}
			}
		} catch (Exception e) {
			damaged = true;
			Diorama.LOGGER.error("Couldn't read waypoints from {}", this.file, e);
		}
		if (damaged) {
			Path backup = this.file.resolveSibling("waypoints.json.unreadable-" + System.currentTimeMillis());
			try {
				Files.copy(this.file, backup);
				Diorama.LOGGER.warn("Kept a copy of the unreadable waypoints file at {}", backup);
			} catch (IOException e) {
				Diorama.LOGGER.error("Couldn't back up {}", this.file, e);
			}
		}
	}

	/** Fills in anything a hand-edited or older file left out; null if the entry is unusable. */
	private static @Nullable Waypoint normalise(@Nullable Waypoint waypoint) {
		if (waypoint == null || waypoint.dimension == null) {
			return null;
		}
		if (waypoint.id == null) {
			waypoint.id = UUID.randomUUID();
		}
		if (waypoint.name == null || waypoint.name.isBlank()) {
			waypoint.name = "Waypoint";
		}
		if (waypoint.initials == null) {
			waypoint.initials = "";
		}
		if (waypoint.icon == null) {
			waypoint.icon = "";
		}
		if (waypoint.kind == null) {
			waypoint.kind = Waypoint.Kind.MANUAL;
		}
		if (waypoint.folder == null) {
			waypoint.folder = "";
		}
		if (waypoint.map != null && waypoint.map.isEmpty()) {
			waypoint.map = null;
		}
		return waypoint;
	}

	private void loadFolders() {
		if (!Files.isRegularFile(this.foldersFile)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(this.foldersFile, StandardCharsets.UTF_8)) {
			Folders read = GSON.fromJson(reader, Folders.class);
			if (read != null) {
				// Drop blank and repeated names a hand edit might have left.
				Folders clean = new Folders();
				if (read.custom != null) {
					for (String name : read.custom) {
						if (name != null && !name.isBlank() && clean.custom.stream().noneMatch(n -> n.equalsIgnoreCase(name.trim()))) {
							clean.custom.add(name.trim());
						}
					}
				}
				if (read.hidden != null) {
					clean.hidden.addAll(read.hidden);
				}
				clean.sort = read.sort;
				clean.sortReversed = read.sortReversed;
				this.folders = clean;
			}
		} catch (Exception e) {
			Diorama.LOGGER.error("Couldn't read waypoint folders from {}", this.foldersFile, e);
		}
	}

	private void saveFolders() {
		try {
			Files.createDirectories(this.foldersFile.getParent());
			Path temp = this.foldersFile.resolveSibling("folders.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(this.folders, writer);
			}
			Files.move(temp, this.foldersFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.error("Couldn't save waypoint folders to {}", this.foldersFile, e);
		}
	}

	/** Writes to a temporary file and moves it over the old one, so a crash can't leave half a file. */
	private void save() {
		try {
			Files.createDirectories(this.file.getParent());
			Path temp = this.file.resolveSibling("waypoints.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(this.everyWaypoint().stream().filter(w -> w.kind != Waypoint.Kind.TEMPORARY).toList(), writer);
			}
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			// Gson wraps writer failures (a full disk, say) in unchecked exceptions.
			Diorama.LOGGER.error("Couldn't save waypoints to {}", this.file, e);
		}
	}
}
