package dev.diorama.waypoint;

import dev.diorama.Diorama;
import dev.diorama.WorldKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.loading.FMLPaths;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Brings a player's waypoints over from Xaero's Minimap. Xaero's keeps them as text, one folder
 * per world and one per dimension inside it:
 * {@code .minecraft/xaero/minimap/<world>/<dimension>/<multiworld>.txt}, with a line per waypoint:
 * {@code waypoint:name:initials:x:y:z:colour:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination}.
 * A singleplayer world's folder is its save folder and its file is {@code waypoints.txt}. A
 * server's folder is {@code Multiplayer_<address>}, and since one address can lead to several
 * worlds, Xaero's keeps a file for each it told apart: {@code mw<spawn>_<name>.txt} on plain
 * servers, {@code mw$<id>_<name>.txt} when the server or Xaero's World Map names the world.
 * Read only: nothing here writes to Xaero's files.
 */
public final class XaeroImport {
	/** Xaero's colour index (its colour list starts with the 16 chat colours) to the dye that index stands for. */
	private static final DyeColor[] EXTRA_COLORS = {DyeColor.MAGENTA, DyeColor.LIGHT_BLUE, DyeColor.LIME, DyeColor.PINK, DyeColor.BROWN};
	/** How many colours Xaero's has, by index from 0. */
	static final int COLORS = 16 + EXTRA_COLORS.length;
	/** Names Xaero's gives its own waypoints, stored as translation keys, in English for when Xaero's isn't installed. */
	private static final Map<String, String> XAERO_NAMES = Map.of(
		"gui.xaero_deathpoint", "Latest Death",
		"gui.xaero_deathpoint_old", "Old Death"
	);
	private static final int MAX_NAME = 32;
	private static final int MAX_HORIZONTAL = 30_000_000;
	private static final int MAX_VERTICAL = 4096;
	/** About 50,000 waypoints; anything bigger isn't a waypoint file. */
	private static final long MAX_FILE = 4 << 20;
	/** The folder Xaero's uses for every server when told not to tell them apart by address. */
	private static final String SHARED_FOLDER = "Multiplayer_Any Address";

	/**
	 * A waypoint read from Xaero's. {@code heightKnown} is false when Xaero's didn't store a
	 * height ({@code ~}); the waypoint then takes the height it was imported at.
	 */
	public record Entry(Waypoint waypoint, boolean heightKnown) {
	}

	/**
	 * One of Xaero's worlds for this world folder or server: {@code id} is the file name's first
	 * part ({@code waypoints}, {@code mw$default}, {@code mw2,1,-3}), {@code name} what Xaero's
	 * shows for it, if it has one. {@code dimension} is the one dimension its waypoints are from,
	 * or null for a world gathered from every dimension's file of that id. {@code likely} marks
	 * the world Xaero's own rule would open here, as far as its files tell.
	 */
	public record World(String id, @Nullable String name, @Nullable String dimension, List<Entry> entries, boolean likely) {
		public String label() {
			return this.name != null && !this.name.isBlank() ? this.name : this.id;
		}
	}

	/**
	 * What Xaero's has for the world the client is in. {@code chosen} is the world to import
	 * without asking, which only singleplayer has; otherwise the player picks from
	 * {@code worlds}, the likely one first.
	 */
	public record Found(List<World> worlds, @Nullable World chosen) {
	}

	private XaeroImport() {
	}

	/** Xaero's waypoints for the world the client is in, or null if Xaero's has none. Reads files: call when the player asks. */
	public static @Nullable Found find() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		Path base = mc.gameDirectory.toPath().resolve("xaero").resolve("minimap");
		if (!Files.isDirectory(base)) {
			// Before 2021 or so Xaero's kept the same folders here; it moves them on its next start.
			base = mc.gameDirectory.toPath().resolve("XaeroWaypoints");
		}
		try {
			List<String> roots = worldFolders(mc);
			if (roots == null) {
				return null;
			}
			//? if >=1.21.9 {
			BlockPos spawn = mc.level.getRespawnData().pos();
			//?} else {
			/*// The spawn had a getter of its own.
			BlockPos spawn = mc.level.getSharedSpawnPos();
			*///?}
			String spawnId = "mw" + (spawn.getX() >> 6) + "," + (spawn.getY() >> 6) + "," + (spawn.getZ() >> 6);
			int height = mc.player != null ? mc.player.getBlockY() : 64;
			return find(base, roots, mc.getSingleplayerServer() != null, spawnId, height);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read Xaero's Minimap waypoints from {}", base, e);
			return null;
		}
	}

	/**
	 * The names Xaero's may give the folder of the world the client is in, newest naming first; null
	 * in a Realm, which Xaero's names by its owner and id, which only the Realms screen knows.
	 */
	static @Nullable List<String> worldFolders(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		ServerData data = mc.getCurrentServer();
		if (server != null) {
			return singleplayerFolders(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
		}
		if (data != null && !WorldKey.isRealm(data)) {
			//? if fabric {
			Path config = FabricLoader.getInstance().getConfigDir();
			//?} else {
			/*Path config = FMLPaths.CONFIGDIR.get();
			*///?}
			return serverFolders(data.ip, byAddress(config.resolve("xaero").resolve("minimap").resolve("client.cfg")));
		}
		return null;
	}

	/**
	 * Xaero's worlds in the first of {@code roots} that exists under {@code base}, and which one
	 * Xaero's would likely open. {@code height} stands in for heights Xaero's didn't store.
	 * Separate from {@link #find()} for tests and tools.
	 */
	public static @Nullable Found find(Path base, List<String> roots, boolean singleplayer, String spawnId, int height) throws IOException {
		Path root = existing(base, roots);
		if (root == null) {
			return null;
		}
		// Each world id's files, one per dimension.
		Map<String, List<World>> worlds = new LinkedHashMap<>();
		try (Stream<Path> dimensions = Files.list(root)) {
			for (Path folder : dimensions.filter(Files::isDirectory).sorted().toList()) {
				String dimension = dimension(folder.getFileName().toString());
				if (dimension == null) {
					continue;
				}
				// A folder or file that can't be read is skipped, and the rest still imports.
				List<Path> files;
				try (Stream<Path> list = Files.list(folder)) {
					files = list.filter(Files::isRegularFile).sorted().toList();
				} catch (IOException | RuntimeException e) {
					Diorama.LOGGER.warn("Skipping Xaero's Minimap folder {}: can't read it", folder, e);
					continue;
				}
				for (Path file : files) {
					String fileName = file.getFileName().toString();
					if (!fileName.endsWith(".txt")) {
						continue;
					}
					String stem = fileName.substring(0, fileName.length() - ".txt".length());
					String id = stem;
					String name = null;
					if (!stem.equals("waypoints")) {
						// Xaero's own rule: other files are <id>_<name>, and it skips any without a name.
						String[] parts = stem.split("_");
						if (parts.length < 2) {
							continue;
						}
						id = parts[0];
						name = parts[1].replace("%us%", "_").replace("§§", ":");
					}
					List<String> lines;
					try {
						lines = read(file);
					} catch (IOException | RuntimeException e) {
						Diorama.LOGGER.warn("Skipping Xaero's Minimap file {}: can't read it", file, e);
						continue;
					}
					worlds.computeIfAbsent(id, key -> new ArrayList<>()).add(new World(id, name, dimension, parse(lines, dimension, height), false));
				}
			}
		}
		if (singleplayer && worlds.containsKey("waypoints")) {
			// Xaero's always opens "waypoints" in singleplayer. If it's empty, another world's
			// waypoints aren't this one's.
			World world = merge("waypoints", worlds.get("waypoints"), true);
			return world.entries().isEmpty() ? null : new Found(List.of(world), world);
		}
		// On a server Xaero's picks by what the server says and how World Map is set up, which
		// its files don't fully record, so the player confirms. World Map picks a world per
		// dimension, and the shared folder holds every server's files under the same ids, so in
		// either case each dimension's file is offered on its own.
		boolean shared = root.getFileName().toString().equalsIgnoreCase(SHARED_FOLDER);
		boolean perDimension = shared || worlds.keySet().stream().anyMatch(id -> id.startsWith("mw$") || id.startsWith("cm$"));
		String likely = singleplayer || shared || perDimension ? null : xaeroChoice(root.resolve("config.txt"), spawnId);
		List<World> candidates = new ArrayList<>();
		for (Map.Entry<String, List<World>> world : worlds.entrySet()) {
			if (perDimension) {
				candidates.addAll(world.getValue());
			} else {
				candidates.add(merge(world.getKey(), world.getValue(), world.getKey().equals(likely)));
			}
		}
		candidates.removeIf(world -> world.entries().isEmpty());
		candidates.sort(Comparator.comparing(world -> !world.likely()));
		return candidates.isEmpty() ? null : new Found(candidates, null);
	}

	/** One world from its files in every dimension: Xaero's uses the same id in each. */
	private static World merge(String id, List<World> files, boolean likely) {
		List<Entry> entries = files.stream().flatMap(world -> world.entries().stream()).toList();
		String name = files.stream().map(World::name).filter(n -> n != null).findFirst().orElse(null);
		return new World(id, name, null, entries, likely);
	}

	/**
	 * The world Xaero's own minimap opens on a server whose files carry no server-given names:
	 * the one its config pinned on the first visit (multiworld detection off, the default) or the
	 * one for the current spawn (detection on). Null if the config doesn't say or can't be read.
	 */
	private static @Nullable String xaeroChoice(Path config, String spawnId) {
		if (!Files.isRegularFile(config)) {
			return null;
		}
		List<String> lines;
		try {
			lines = read(config);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read Xaero's Minimap world config {}", config, e);
			return null;
		}
		boolean detection = false;
		String pinned = null;
		for (String line : lines) {
			String[] parts = line.split(":");
			if (parts.length >= 2 && parts[0].equals("usingMultiworldDetection")) {
				detection = parts[1].equals("true");
			} else if (parts.length >= 2 && parts[0].equals("defaultMultiworldId")) {
				pinned = parts[1];
			}
		}
		return detection ? spawnId : pinned;
	}

	/**
	 * Xaero's "differentiate by server address" option from its client config: true or false, or
	 * null when the file or the line isn't there (older Xaero's kept its options elsewhere) or the
	 * file can't be read.
	 */
	static @Nullable Boolean byAddress(Path clientConfig) {
		if (!Files.isRegularFile(clientConfig)) {
			return null;
		}
		List<String> lines;
		try {
			lines = read(clientConfig);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read Xaero's Minimap options {}", clientConfig, e);
			return null;
		}
		for (String line : lines) {
			String[] parts = line.split("=");
			if (parts.length == 2 && parts[0].trim().equals("differentiate_by_server_address")) {
				return parts[1].trim().equals("true");
			}
		}
		return null;
	}

	/** A file's lines, or none if it's too big to be one of Xaero's. */
	private static List<String> read(Path file) throws IOException {
		if (Files.size(file) > MAX_FILE) {
			Diorama.LOGGER.warn("Skipping {}: too big for a Xaero's Minimap file", file);
			return List.of();
		}
		return Files.readAllLines(file, StandardCharsets.UTF_8);
	}

	/** The first folder in {@code names} under {@code base}, matching case the way Xaero's does. */
	private static @Nullable Path existing(Path base, List<String> names) throws IOException {
		if (!Files.isDirectory(base)) {
			return null;
		}
		List<Path> folders;
		try (Stream<Path> list = Files.list(base)) {
			folders = list.filter(Files::isDirectory).toList();
		}
		for (String name : names) {
			for (Path folder : folders) {
				if (folder.getFileName().toString().equalsIgnoreCase(name)) {
					return folder;
				}
			}
		}
		return null;
	}

	/** Xaero's folder name for a save folder, newest naming first. */
	public static List<String> singleplayerFolders(String saveFolder) {
		String older = saveFolder.replace("_", "%us%").replace("/", "%fs%").replace("\\", "%bs%");
		String newer = older.replace("[", "%lb%").replace("]", "%rb%");
		return newer.equals(older) ? List.of(newer) : List.of(newer, older);
	}

	/**
	 * Xaero's folder names for a server address, newest naming first (Xaero's renames older ones
	 * when it starts, but a player who stopped updating it has those). {@code byAddress} is
	 * Xaero's option to tell servers apart by address: when it's off, every server shares one
	 * folder; when it isn't known, that folder comes last.
	 */
	public static List<String> serverFolders(String address, @Nullable Boolean byAddress) {
		if (Boolean.FALSE.equals(byAddress)) {
			return List.of(SHARED_FOLDER);
		}
		List<String> names = new ArrayList<>();
		for (int version = 4; version >= 0; version--) {
			String name = serverFolder(address, version);
			if (!names.contains(name)) {
				names.add(name);
			}
		}
		if (byAddress == null) {
			names.add(SHARED_FOLDER);
		}
		return names;
	}

	/** Xaero's {@code MinimapWorldStateUpdater.getAutoRootContainerPath}, for each of its folder-name versions. */
	private static String serverFolder(String address, int version) {
		String ip = address;
		int port = version >= 1 && ip.indexOf(':') != ip.lastIndexOf(':') ? ip.lastIndexOf("]:") + 1 : ip.indexOf(':');
		if (port > 0) {
			ip = ip.substring(0, port);
		}
		while (ip.endsWith(".")) {
			ip = ip.substring(0, ip.length() - 1);
		}
		if (version >= 2) {
			ip = ip.replace("[", "").replace("]", "");
		}
		ip = ip.replace(":", version < 3 ? "§" : ".").replace("_", "%us%").replace("/", "%fs%").replace("\\", "%bs%");
		if (version >= 4) {
			ip = ip.trim();
			int end = ip.length();
			while (end > 0 && ip.charAt(end - 1) == '.') {
				end--;
			}
			ip = ip.substring(0, end) + ",".repeat(ip.length() - end);
		}
		return "Multiplayer_" + (ip.isEmpty() ? "Empty Address" : ip);
	}

	/** Our dimension id for one of Xaero's dimension folders, or null if it isn't one we can read. */
	static @Nullable String dimension(String folder) {
		return switch (folder) {
			case "dim%0", "Overworld" -> "minecraft:overworld";
			case "dim%-1", "Nether" -> "minecraft:the_nether";
			case "dim%1", "The End" -> "minecraft:the_end";
			// dim%namespace$path, with / as % and a trailing . as ,
			default -> folder.startsWith("dim%") && folder.contains("$") ? WaypointShare.ourDimension(folder.replace(',', '.')) : null;
		};
	}

	/**
	 * The waypoints in one of Xaero's files. Lines that aren't waypoints (sets, comments, the
	 * slime chunk seed) and ones that can't be read are skipped, as Xaero's does. One-off
	 * destinations are skipped too: Xaero's removes them once reached. (Its temporary waypoints
	 * never reach the file.) The last field marks destinations, but Xaero's sets it on death
	 * points as well; only an ordinary waypoint carrying it is a one-off destination.
	 */
	static List<Entry> parse(List<String> lines, String dimension, int height) {
		List<Entry> entries = new ArrayList<>();
		for (String line : lines) {
			String[] args = line.split(":");
			if (args.length < 10 || !args[0].equalsIgnoreCase("waypoint")) {
				continue;
			}
			try {
				int x = Integer.parseInt(args[3]);
				boolean heightKnown = !args[4].equals("~");
				int y = heightKnown ? Integer.parseInt(args[4]) : height;
				int z = Integer.parseInt(args[5]);
				int color = Integer.parseInt(args[6]);
				int type = Integer.parseInt(args[8]);
				if (type == 3 || (type == 0 && args.length > 13 && args[13].equals("true"))) {
					continue;
				}
				if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL || Math.abs(y) > MAX_VERTICAL) {
					continue;
				}
				String name = WaypointShare.cut(WaypointShare.chatSafe(name(args[1].replace("§§", ":"))).trim(), MAX_NAME).trim();
				Waypoint waypoint = new Waypoint(name.isEmpty() ? "Waypoint" : name, x, y, z, dimension, WaypointShare.nearestPaletteColor(color(color)));
				String initials = WaypointShare.cut(WaypointShare.chatSafe(args[2].replace("§§", ":")).trim(), Waypoint.MAX_INITIALS);
				if (!initials.isEmpty()) {
					waypoint.initials = initials;
				}
				waypoint.visible = !args[7].equals("true");
				// Xaero's World Map visibility types keep it out of the world and off the minimap, on its map alone.
				if (args.length > 12 && (args[12].equals("2") || args[12].equals("3"))) {
					waypoint.showInWorld = false;
					waypoint.showOnMinimap = false;
				}
				if (type == 1 || type == 2) {
					waypoint.kind = Waypoint.Kind.DEATH;
				}
				entries.add(new Entry(waypoint, heightKnown));
			} catch (NumberFormatException e) {
				// Xaero's skips a line it can't read, and so do we.
			}
		}
		return entries;
	}

	/** Xaero's names its death points with translation keys; the name it shows for them. */
	private static String name(String stored) {
		String fallback = XAERO_NAMES.get(stored);
		return fallback == null ? stored : Language.getInstance().getOrDefault(stored, fallback);
	}

	/** The colour Xaero's draws for one of its colour indices. */
	static int color(int index) {
		int clamped = Math.max(0, Math.min(15 + EXTRA_COLORS.length, index));
		return clamped < 16 ? WaypointShare.chatColor(clamped) : EXTRA_COLORS[clamped - 16].getTextColor();
	}

	/**
	 * The waypoints of {@code world} that aren't in the store yet. A waypoint is already there if
	 * one has its name, dimension and position; for one Xaero's stored without a height, any
	 * height counts, since it took the player's height when it was imported. A death point is
	 * already there if a death marker has its dimension and position, whatever its name: the name
	 * comes from the game's language at import time, so it changes with the language.
	 */
	public static List<Waypoint> missing(World world, WaypointStore store) {
		Set<String> exact = new HashSet<>();
		Set<String> anyHeight = new HashSet<>();
		for (Waypoint w : store.all()) {
			exact.add(key(w, true));
			anyHeight.add(key(w, false));
		}
		List<Waypoint> out = new ArrayList<>();
		for (Entry entry : world.entries()) {
			Waypoint in = entry.waypoint();
			boolean present = entry.heightKnown() ? exact.contains(key(in, true)) : anyHeight.contains(key(in, false));
			if (!present) {
				out.add(in);
				exact.add(key(in, true));
				anyHeight.add(key(in, false));
			}
		}
		return out;
	}

	private static String key(Waypoint w, boolean height) {
		String what = w.kind == Waypoint.Kind.DEATH ? "death" : "name|" + w.name;
		return w.dimension + "|" + w.x + "|" + (height ? w.y : "") + "|" + w.z + "|" + what;
	}

	/** Adds the waypoints of {@code world} that aren't in the store yet and returns them. */
	public static List<Waypoint> importInto(World world, WaypointStore store) {
		// Copies, so a later edit to an imported waypoint can't reach back into the read file.
		List<Waypoint> added = missing(world, store).stream().map(XaeroImport::copy).toList();
		store.addAll(added);
		Diorama.LOGGER.info("Imported {} waypoints from Xaero's Minimap world {}", added.size(), world.id());
		return added;
	}

	private static Waypoint copy(Waypoint from) {
		Waypoint to = new Waypoint(from.name, from.x, from.y, from.z, from.dimension, from.color);
		to.initials = from.initials;
		to.visible = from.visible;
		to.showInWorld = from.showInWorld;
		to.showOnMinimap = from.showOnMinimap;
		to.showOnMap = from.showOnMap;
		to.kind = from.kind;
		return to;
	}
}
