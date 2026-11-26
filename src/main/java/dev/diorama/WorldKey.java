package dev.diorama;

import dev.diorama.mixin.BiomeManagerAccessor;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Names the world the client is in, so per-world data (waypoints, the saved map) lands in its
 * own folder: the save folder for singleplayer (see {@link #singleplayerKey}); for multiplayer,
 * the server address and the server's name for the world, since one address can lead to several
 * worlds (a network's lobby and survival servers, or a map reset). A realm is named by its ID instead of its address: one
 * that went to sleep wakes up at another address.
 *
 * <p>Servers built around Xaero's mods name their worlds with a level ID (see
 * {@link XaeroChannels}). Other servers name nothing, and the world is the hashed seed the
 * server sends with every level instead. The seed is the same in each dimension and stays put
 * when the spawn moves, but worlds that share one share a folder.
 *
 * <p>A level ID can come a moment after the level: Xaero's mods send it with the level,
 * MapModCompanion a second later. So on a server that may send one, the map and waypoints wait
 * for it a moment rather than open the seed's folder, which may hold another world. A server may
 * send one if it declared one of the channels that come with IDs, already sent one since the
 * player joined, or named a world before (there's a folder for it). Once a level has a folder, it
 * keeps it: an ID that comes later only names the levels after it. Moving a world's map and
 * waypoints while they're open loses what's being written or edited.
 *
 * <p>Maps saved under the seed before Diorama read level IDs stay there. Which of the server's
 * worlds they belong to can't be told: on a network whose servers share a seed, it's all of them
 * merged, and a server that sends no ID still uses the folder.
 */
public final class WorldKey {
	/** How long to wait for a level ID after joining a server that may send one. */
	private static final long FIRST_WAIT_MS = 3000;
	/**
	 * How long to wait after a dimension change. A server that named the last level names the
	 * next as it arrives, or not at all: some only send an ID when the player changes worlds.
	 */
	private static final long LATER_WAIT_MS = 1000;
	/**
	 * For tests: a level waits for its ID however long it takes, so a test about which ID lands on
	 * which level doesn't depend on how fast the machine runs the game.
	 */
	public static volatile boolean waitForIdsForever;
	/**
	 * For tests: no world is known, as while a server's level waits for its ID, without the wait.
	 * Singleplayer never waits, so this is the only way a test there gets one.
	 */
	public static volatile boolean unknownForTests;
	/** In a singleplayer save: the ID that tells it from a world saved under the same name before. */
	private static final String SAVE_ID = "diorama-world-id.txt";
	/** In a singleplayer world's folder: the ID of the save it was made for. */
	private static final String FOLDER_ID = "save-id.txt";

	/** What's known about a server's level. Kept per level object, which a respawn keeps. */
	private static final class Naming {
		/** The ID the server sent for this level. */
		@Nullable Integer id;
		final long since = System.currentTimeMillis();
		/** A folder was handed out for this level, so it doesn't start waiting later. */
		boolean decided;
		/** The ID the folder was named by when it was handed out, or null for the seed. */
		@Nullable Integer named;
	}

	private static final Map<ClientLevel, Naming> LEVELS = new WeakHashMap<>();
	/** The last ID this server sent since the player joined it, for levels it doesn't name. */
	private static @Nullable Integer lastId;
	/** Whether this server named a world on an earlier visit, once asked. See {@link #namedBefore}. */
	private static @Nullable Boolean namedBefore;
	/** This server let a wait run out without naming its world, so it isn't waited on again. */
	private static boolean silent;
	private static @Nullable Last last;
	/**
	 * The realm each realm connection's server entry is for, by ID. Realms makes a new entry for
	 * each connection, on a thread of its own.
	 */
	private static final Map<ServerData, Long> REALMS = Collections.synchronizedMap(new WeakHashMap<>());
	//? if <1.21 {
	/*// Server entries had no kind in 1.20.1, so the realm entries are noted here (see isRealm).
	private static final java.util.Set<ServerData> REALM_ENTRIES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	*///?}

	private WorldKey() {
	}

	/** The world the client is in, or null outside one. */
	public static @Nullable String current() {
		return current(Minecraft.getInstance().level);
	}

	/** The world a level belongs to, or null outside one or while waiting for its name. */
	public static @Nullable String current(@Nullable ClientLevel level) {
		if (unknownForTests) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			// An integrated server runs one save for its whole life.
			Last seen = last;
			if (seen != null && seen.matches(server, null, null, false, null)) {
				return seen.key;
			}
			String key = singleplayerKey(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
			last = new Last(new WeakReference<>(server), null, null, false, null, key);
			return key;
		}
		ServerData data = mc.getCurrentServer();
		if (data == null || level == null) {
			return null;
		}
		Naming naming = LEVELS.computeIfAbsent(level, l -> new Naming());
		if (!naming.decided) {
			if (naming.id == null && expectingId(naming, data)) {
				return null;
			}
			naming.decided = true;
			naming.named = naming.id != null ? naming.id : lastId;
		}
		Integer id = naming.named;
		// A server list entry can be edited in place, so its address and kind are checked too. A
		// level's seed never changes, and the ID its folder is named by is fixed once handed out.
		boolean realm = isRealm(data);
		Last seen = last;
		if (seen != null && seen.matches(data, level, data.ip, realm, id)) {
			return seen.key;
		}
		String address = address(data);
		String key = id != null ? idKey(address, id) : seedKey(address, level);
		if (REALMS.containsKey(data)) {
			String old = key("realms", data.ip);
			String oldKey = id != null ? idKey(old, id) : seedKey(old, level);
			if (!adoptAddressFolder(key, oldKey)) {
				// This connection stays on the old folder; the next one tries again.
				REALMS.remove(data);
				key = oldKey;
			}
		}
		last = new Last(new WeakReference<>(data), new WeakReference<>(level), data.ip, realm, id, key);
		return key;
	}

	/**
	 * A save by its folder name, unless the folder was made for another world. A world made after
	 * deleting one of the same name gets the same save folder, and would open the old world's map
	 * and waypoints. So each save keeps an ID of its own ({@link #SAVE_ID}), and the folder notes
	 * the ID it was made for ({@link #FOLDER_ID}). A world whose ID isn't the folder's gets a
	 * folder named by its ID as well; the old one stays as it was. Once that folder exists, the
	 * world goes there without looking at the old one, which may since have been deleted.
	 *
	 * <p>A folder from before IDs is taken by the first world to open it, since nothing tells
	 * whose it was, and nearly always it's the world's own. A backup from before then, restored
	 * after its world was opened, has no ID and so isn't that world: its map stays in the folder.
	 */
	private static String singleplayerKey(Path save) {
		String key = key("singleplayer", save.getFileName().toString());
		Path folder = folder(key);
		try {
			String id = saveId(save);
			String own = key + "-" + hash("save:" + id);
			if (Files.isDirectory(folder(own))) {
				return own;
			}
			Path claim = folder.resolve(FOLDER_ID);
			if (Files.isRegularFile(claim)) {
				String owner = Files.readString(claim, StandardCharsets.UTF_8).strip();
				if (owner.equals(id)) {
					return key;
				}
				Files.createDirectories(folder(own));
				return own;
			}
			Files.createDirectories(folder);
			Files.writeString(claim, id, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't tell whether this save's map folder {} was made for it; using it", key, e);
		}
		return key;
	}

	/** The save's ID, given one if it has none. */
	private static String saveId(Path save) throws IOException {
		Path file = save.resolve(SAVE_ID);
		if (Files.isRegularFile(file)) {
			String id = Files.readString(file, StandardCharsets.UTF_8).strip();
			if (!id.isEmpty()) {
				return id;
			}
		}
		String id = UUID.randomUUID().toString();
		Files.writeString(file, id, StandardCharsets.UTF_8);
		return id;
	}

	/**
	 * The last key handed out and what it was worked out from. The HUD and the map ask every
	 * frame, and working it out means a hash and a regex each time. The objects are held weakly
	 * so a world that was left can still be freed; one that was freed can't match a live one.
	 */
	private record Last(WeakReference<Object> source, @Nullable WeakReference<ClientLevel> level, @Nullable String ip, boolean realm,
		@Nullable Integer id, String key) {
		boolean matches(Object source, @Nullable ClientLevel level, @Nullable String ip, boolean realm, @Nullable Integer id) {
			return this.source.get() == source
				&& (this.level == null ? level == null : this.level.get() == level)
				&& Objects.equals(this.ip, ip)
				&& this.realm == realm
				&& Objects.equals(this.id, id);
		}
	}

	/** Whether {@link #current} last said to wait for this level's ID, without asking again. */
	public static boolean waiting(@Nullable ClientLevel level) {
		Naming naming = level == null ? null : LEVELS.get(level);
		return naming != null && !naming.decided;
	}

	private static boolean expectingId(Naming naming, ServerData data) {
		if (silent || lastId == null && !XaeroChannels.serverMayNameWorlds() && !namedBefore(data)) {
			return false;
		}
		if (waitForIdsForever || System.currentTimeMillis() - naming.since < (lastId == null ? FIRST_WAIT_MS : LATER_WAIT_MS)) {
			return true;
		}
		if (lastId == null) {
			silent = true;
			Diorama.LOGGER.info("The server sent no level ID, so its world is named by its seed");
		}
		return false;
	}

	/**
	 * Whether this address named one of its worlds on an earlier visit. Some servers send the ID
	 * without declaring a channel, so this is the only sign they will again.
	 */
	private static boolean namedBefore(ServerData data) {
		if (namedBefore == null) {
			String named = idPrefix(data);
			try (Stream<Path> worlds = Files.list(Minecraft.getInstance().gameDirectory.toPath().resolve(Diorama.MOD_ID))) {
				namedBefore = worlds.anyMatch(world -> world.getFileName().toString().startsWith(named));
			} catch (IOException e) {
				namedBefore = false;
			}
		}
		return namedBefore;
	}

	/** A realm by its ID; a realm whose ID wasn't seen, and any other server, by its address. */
	private static String address(ServerData data) {
		Long realm = REALMS.get(data);
		if (realm != null) {
			return key("realm", Long.toString(realm));
		}
		return key(isRealm(data) ? "realms" : "server", data.ip);
	}

	/**
	 * Before Diorama read a realm's ID, its folder was named by the address it ran at. The folder
	 * for the realm's address now holds what the player saw last time, so it becomes the realm's
	 * folder the first time the realm's own is asked for. The ones from earlier addresses stay.
	 * False if the move failed: then the realm's own folder mustn't be made empty in its place.
	 */
	private static boolean adoptAddressFolder(String key, String old) {
		Path target = folder(key);
		Path source = folder(old);
		if (Files.exists(target) || !Files.isDirectory(source)) {
			return true;
		}
		try {
			Files.move(source, target);
			Diorama.LOGGER.info("This realm's map moved from {} to {}, named by the realm instead of its address", old, key);
			return true;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't move this realm's map from {} to {}; using it where it is", old, key, e);
			return false;
		}
	}

	private static String idPrefix(ServerData data) {
		return address(data) + "-id-";
	}

	private static String idKey(String address, int id) {
		return address + "-id-" + HexFormat.of().toHexDigits(id);
	}

	/** The folder for a server's world before Diorama read level IDs. */
	private static String seedKey(String address, ClientLevel level) {
		return address + "-world-" + HexFormat.of().toHexDigits(((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed());
	}

	/**
	 * The server named the level the client is in. If the level already has a folder, it keeps
	 * it, and the ID names the levels after it. The ID's folder is made either way, so the next
	 * visit knows to wait for the ID (see {@link #namedBefore}).
	 */
	public static void serverLevelId(int id) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		ServerData data = mc.getCurrentServer();
		if (level == null || data == null || mc.getSingleplayerServer() != null) {
			return;
		}
		try {
			Files.createDirectories(folder(idKey(address(data), id)));
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't make the folder for this server's world {}", HexFormat.of().toHexDigits(id), e);
		}
		Naming naming = LEVELS.computeIfAbsent(level, l -> new Naming());
		lastId = id;
		silent = false;
		if (Integer.valueOf(id).equals(naming.id)) {
			return;
		}
		naming.id = id;
		if (!naming.decided) {
			Diorama.LOGGER.info("The server names this world {}", HexFormat.of().toHexDigits(id));
		} else if (!Integer.valueOf(id).equals(naming.named)) {
			Diorama.LOGGER.info("The server named this world {} after its map opened, so the name applies from the next world on", HexFormat.of().toHexDigits(id));
		}
	}

	/** Realms made this server entry to connect to the realm with this ID. */
	public static void realmEntry(ServerData data, long id) {
		REALMS.put(data, id);
		//? if <1.21 {
		/*REALM_ENTRIES.add(data);
		*///?}
	}

	/** Whether the server entry is for a realm rather than a server the player added or found on LAN. */
	public static boolean isRealm(ServerData data) {
		//? if >=1.21 {
		return data.isRealm();
		//?} else {
		/*// Realms made the entry (see realmEntry), and that is the only place realm entries come from.
		return REALM_ENTRIES.contains(data);
		*///?}
	}

	/** Left the server, or a network moved the player to another of its servers. */
	public static void onJoinOrLeave() {
		LEVELS.clear();
		lastId = null;
		namedBefore = null;
		silent = false;
	}

	/** {@code .minecraft/diorama/<world>}. */
	public static Path folder(String key) {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve(key);
	}

	/**
	 * A readable folder name plus a short hash of the exact identity, so worlds whose names clean
	 * up the same ("My World", "My_World") still get separate folders.
	 */
	public static String key(String kind, String identity) {
		return kind + "-" + sanitize(identity) + "-" + hash(kind + ":" + identity);
	}

	private static String hash(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 4);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String sanitize(String name) {
		String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
		return cleaned.isEmpty() ? "unnamed" : cleaned;
	}
}
