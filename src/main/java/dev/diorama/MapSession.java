package dev.diorama;

import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.Copycats;
import dev.diorama.light.Lights;
import dev.diorama.render.MapLevel;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/**
 * The map state for the world the client is in: which dimension's {@link MapLevel} is live,
 * which chunks are waiting to be snapshotted, and whether the player is underground.
 */
public final class MapSession {
	/** Chunk light usually lands a moment after the blocks, so new chunks wait this long. */
	private static final long LOAD_DELAY_MS = 750;
	private static final long CHANGE_DELAY_MS = 250;
	private static final int SNAPSHOTS_PER_TICK = 24;

	private static @Nullable MapSession current;
	private static int started;
	private static final boolean DEBUG_CHANGES = Diorama.DEBUG;
	private static final Map<String, Integer> CHANGE_TALLY = new java.util.TreeMap<>();
	private static long lastTallyLog;

	private final ClientLevel level;
	private final MapLevel map;
	private final Lights lights;
	private final Map<Long, Pending> pending = new LinkedHashMap<>();
	private boolean underground;
	private MapStyle style = MapStyle.get();

	private MapSession(ClientLevel level, @Nullable String key) {
		this.level = level;
		// The Nether's roof: its sky view looks down from just under it.
		int roof = level.dimensionType().hasCeiling() ? level.getMinY() + level.dimensionType().logicalHeight() : MapLevel.NO_ROOF;
		this.map = new MapLevel(level.getMinY(), level.getMaxY() + 1, roof, mapFolder(level, key));
		this.lights = new Lights(level, this.map);
	}

	private void end() {
		this.lights.close();
		this.map.close();
	}

	/** The folder of the dimension's map in use ({@link Maps}), or null outside a world. */
	private static @Nullable Path mapFolder(ClientLevel level, @Nullable String key) {
		if (key == null) {
			return null;
		}
		Maps maps = Maps.of(key);
		String dimension = level.dimension().identifier().toString();
		return maps.tileFolder(dimension, maps.active(dimension));
	}

	/**
	 * The player switched a dimension to another of its maps. If it's this one, the map is saved and
	 * closed as on a dimension change, and the next tick starts one on the other map; its sweep for
	 * loaded chunks maps what's around the player again.
	 */
	public static void mapChanged(String dimension) {
		if (current != null && current.level.dimension().identifier().toString().equals(dimension)) {
			current.map.close();
			current = null;
		}
	}

	public static @Nullable MapSession get() {
		return current;
	}

	/** For tests: how many sessions have started, one each time the player's level changes. */
	public static int started() {
		return started;
	}

	/**
	 * The world seed, which places slime chunks. Only known in singleplayer, and slime chunks
	 * only exist in the overworld.
	 */
	public @Nullable Long slimeSeed() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || this.level.dimension() != Level.OVERWORLD) {
			return null;
		}
		return server.overworld().getSeed();
	}

	/** Saves and ends the session. For closing the game; leaving a world is noticed by {@link #tick}. */
	public static void close() {
		if (current != null) {
			current.end();
			current = null;
		}
	}

	/** Keeps the session in step with the client's level. Call every client tick. */
	public static void tick(Minecraft mc) {
		MapSession session = forLevel(mc.level);
		if (session == null) {
			return;
		}
		if (BlockLooks.get() == null) {
			BlockLooks.build();
		} else if (session.style != MapStyle.get()) {
			// Only a change the tiles show re-bakes them; the fog edge is drawn over them.
			boolean rebake = !MapStyle.get().looksLike(session.style);
			session.style = MapStyle.get();
			if (rebake) {
				session.map.colorsChanged();
			}
		} else if (BlockLooks.isStale() && mc.gui.overlay() == null) {
			// Not while the reload is still going: the models change a step before Fabric hands its
			// fluid handlers the new sprites, and the old ones' images are freed by then.
			BlockLooks.build();
			session.map.colorsChanged();
			// Snapshots hold biome tints from the old colormaps. Retake every loaded chunk;
			// chunks no longer loaded keep their old tints until you come back.
			session.resnapshotLoaded();
		}
		session.tickSession(mc);
	}

	/**
	 * The session for this level, starting a new one if the client moved to it. None while the
	 * server may still name the world: its chunks are mapped once the folder is known.
	 */
	private static @Nullable MapSession forLevel(@Nullable ClientLevel level) {
		if (current != null && current.level != level) {
			current.end();
			current = null;
		}
		if (level != null && current == null) {
			String key = WorldKey.current(level);
			if (key == null && WorldKey.waiting(level)) {
				return null;
			}
			current = new MapSession(level, key);
			started++;
		}
		return current;
	}

	public static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
		// Immersive Portals keeps the levels seen through its portals loaded too, and their chunks
		// arrive while the client is briefly switched to them. The map follows the player's level.
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || player.level() != level) {
			return;
		}
		// Chunks can arrive before the first client tick of a new level, so this may start the session.
		MapSession session = forLevel(level);
		if (session == null) {
			return;
		}
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		session.schedule(cx, cz, LOAD_DELAY_MS, null);
		// A neighbour snapshotted before this chunk loaded blended its tints along this edge against
		// the game's fallback biome. Snapshot it again and re-bake that strip, as the game itself
		// recolours its blocks there.
		int reach = ChunkSnapshot.BIOME_REACH;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkSnapshot around = session.map.chunk(cx + dx, cz + dz);
				if ((dx == 0 && dz == 0) || around == null || around.hasBiomesAround(-dx, -dz)) {
					continue;
				}
				int x0 = around.chunkX << 4;
				int z0 = around.chunkZ << 4;
				int[] strip = {
					dx < 0 ? x0 + 16 - reach : x0, level.getMinY(), dz < 0 ? z0 + 16 - reach : z0,
					dx > 0 ? x0 + reach - 1 : x0 + 15, level.getMaxY(), dz > 0 ? z0 + reach - 1 : z0 + 15,
					MapLevel.CHANGED_SURFACE
				};
				session.schedule(around.chunkX, around.chunkZ, CHANGE_DELAY_MS, strip);
			}
		}
	}

	public static void blockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (current == null || current.level != level) {
			return;
		}
		BlockLooks looks = BlockLooks.get();
		int oldId = Block.BLOCK_STATE_REGISTRY.getId(oldState);
		int newId = Block.BLOCK_STATE_REGISTRY.getId(newState);
		if (looks != null && oldId >= 0 && newId >= 0 && looks.sameLook(oldId, newId)) {
			return;
		}
		if (DEBUG_CHANGES) {
			CHANGE_TALLY.merge(oldState.getBlock() + " -> " + newState.getBlock(), 1, Integer::sum);
			long now = System.currentTimeMillis();
			if (now - lastTallyLog > 10_000) {
				lastTallyLog = now;
				Diorama.LOGGER.info("Visible block changes so far: {}", CHANGE_TALLY);
			}
		}
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		int surface = current.visibleFromSky(pos) ? MapLevel.CHANGED_SURFACE : 0;
		int[] box = {pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), surface};
		current.schedule(cx, cz, CHANGE_DELAY_MS, box);
		// A light source changing relights every neighbouring chunk, diagonals included. Other
		// edits only reach a neighbour through shading that reads across the border.
		boolean light = oldState.getLightEmission() != newState.getLightEmission();

		int lx = pos.getX() & 15;
		int lz = pos.getZ() & 15;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				boolean touches = (dx == 0 || dx == -1 && lx == 0 || dx == 1 && lx == 15)
					&& (dz == 0 || dz == -1 && lz == 0 || dz == 1 && lz == 15);
				if ((dx != 0 || dz != 0) && (light || touches)) {
					current.schedule(cx + dx, cz + dz, CHANGE_DELAY_MS, box);
				}
			}
		}
	}

	/**
	 * A block entity got new data from the server. Filling or emptying a copycat changes only its
	 * block entity, not its block, so it comes this way: re-bake there if the copycat's material
	 * isn't what the map last saw. A chunk the map hasn't seen yet gets it in its first snapshot.
	 */
	public static void blockEntityChanged(ClientLevel level, BlockPos pos) {
		if (!Copycats.INSTALLED || current == null || current.level != level) {
			return;
		}
		ChunkSnapshot snapshot = current.map.chunk(pos.getX() >> 4, pos.getZ() >> 4);
		if (snapshot == null || Copycats.material(level.getBlockEntity(pos)) == snapshot.copycatMaterial(pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		int surface = current.visibleFromSky(pos) ? MapLevel.CHANGED_SURFACE : 0;
		current.schedule(pos.getX() >> 4, pos.getZ() >> 4, CHANGE_DELAY_MS, new int[] {pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), surface});
	}

	/**
	 * The server sent new light for a chunk. Cave slices draw light, and so do spawn spots, so the
	 * surface re-bakes too while they're on.
	 */
	/**
	 * The client's light engine changed a section's light, whether from a server packet or its
	 * own work (a torch placed, a wall opened, a roof closed). Cave slices draw light, and so do
	 * spawn spots, so the surface re-bakes too while they're on.
	 */
	public static void lightSectionChanged(ClientChunkCache source, SectionPos section) {
		if (current != null && current.level.getChunkSource() == source) {
			int x0 = section.minBlockX();
			int y0 = section.minBlockY();
			int z0 = section.minBlockZ();
			int surface = current.map.spawnSpots() || current.map.roofless() ? MapLevel.CHANGED_SURFACE_LIGHT : 0;
			current.schedule(section.x(), section.z(), CHANGE_DELAY_MS, new int[] {x0, y0, z0, x0 + 15, y0 + 15, z0 + 15, surface});
		}
	}

	public static void lightUpdated(ClientLevel level, int chunkX, int chunkZ) {
		if (current != null && current.level == level) {
			int x0 = chunkX << 4;
			int z0 = chunkZ << 4;
			int surface = current.map.spawnSpots() || current.map.roofless() ? MapLevel.CHANGED_SURFACE_LIGHT : 0;
			current.schedule(chunkX, chunkZ, CHANGE_DELAY_MS, new int[] {x0, level.getMinY(), z0, x0 + 15, level.getMaxY(), z0 + 15, surface});
		}
	}

	/**
	 * Whether a changed block can affect the sky view. A camera ray only reaches a block through
	 * open air connected to the sky, and vanilla sky light already tracks exactly that space, so
	 * a block counts if it or any neighbour has sky light. This is deliberately loose: a few
	 * changes the camera can't see still count, but buried ones (water and lava settling in
	 * sealed caves) don't. It misses only blocks more than 15 blocks under an overhang.
	 */
	private boolean visibleFromSky(BlockPos pos) {
		if (!this.level.dimensionType().hasSkyLight()) {
			return true;
		}
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		if (this.level.getBrightness(LightLayer.SKY, pos) > 0) {
			return true;
		}
		for (Direction d : DIRECTIONS) {
			if (this.level.getBrightness(LightLayer.SKY, p.setWithOffset(pos, d)) > 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Queues a chunk for a fresh snapshot. {@code box} is the changed blocks as
	 * {x0, y0, z0, x1, y1, z1, visibleFromSky}; null means the whole chunk is new.
	 */
	private void schedule(int chunkX, int chunkZ, long delayMs, int @Nullable [] box) {
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		long due = System.currentTimeMillis() + delayMs;
		Pending p = this.pending.get(key);
		if (p == null) {
			this.pending.put(key, new Pending(due, box == null ? null : box.clone()));
			return;
		}
		p.due = Math.min(p.due, due);
		if (p.box == null || box == null) {
			p.box = null;
		} else {
			for (int i = 0; i < 3; i++) {
				p.box[i] = Math.min(p.box[i], box[i]);
				p.box[i + 3] = Math.max(p.box[i + 3], box[i + 3]);
			}
			p.box[6] |= box[6];
		}
	}

	private static final class Pending {
		long due;
		int @Nullable [] box;

		Pending(long due, int @Nullable [] box) {
			this.due = due;
			this.box = box;
		}
	}

	private int ticks;

	/** Queues any loaded chunk the map hasn't seen, in case its load event was missed. */
	private void sweepLoadedChunks(LocalPlayer player) {
		int radius = mc().options.renderDistance().get() + 2;
		int pcx = player.blockPosition().getX() >> 4;
		int pcz = player.blockPosition().getZ() >> 4;
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
				if (this.map.chunk(cx, cz) == null && !this.pending.containsKey(ChunkSnapshot.key(cx, cz))
					&& this.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
					this.schedule(cx, cz, 0, null);
				}
			}
		}
	}

	private void resnapshotLoaded() {
		LocalPlayer player = mc().player;
		if (player == null) {
			return;
		}
		int radius = mc().options.renderDistance().get() + 2;
		int pcx = player.blockPosition().getX() >> 4;
		int pcz = player.blockPosition().getZ() >> 4;
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
				if (this.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
					this.schedule(cx, cz, 0, null);
				}
			}
		}
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private void tickSession(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player != null && this.ticks++ % 40 == 0) {
			this.sweepLoadedChunks(player);
		}
		if (this.ticks % 600 == 0) {
			this.map.saveSeen();
		}
		int playerChunkX = player == null ? 0 : player.blockPosition().getX() >> 4;
		int playerChunkZ = player == null ? 0 : player.blockPosition().getZ() >> 4;
		long now = System.currentTimeMillis();
		int budget = SNAPSHOTS_PER_TICK;
		Iterator<Map.Entry<Long, Pending>> it = this.pending.entrySet().iterator();
		while (it.hasNext() && budget > 0) {
			Map.Entry<Long, Pending> entry = it.next();
			Pending p = entry.getValue();
			if (p.due > now) {
				continue;
			}
			it.remove();
			long key = entry.getKey();
			int cx = (int) (key >> 32);
			int cz = (int) key;
			// Through Object: where getChunk already returns a LevelChunk, Java 17 (the 1.20.1 jar's)
			// refuses an instanceof pattern that can't fail.
			Object loaded = this.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
			if (loaded instanceof LevelChunk chunk) {
				boolean known = this.map.chunk(cx, cz) != null;
				this.map.put(ChunkSnapshot.take(chunk, this.level), known ? p.box : null, playerChunkX, playerChunkZ);
				budget--;
			}
		}
		boolean fog = ServerRules.fogOfWar(this.level.dimension() == Level.NETHER);
		if (Diorama.DEBUG && fog != this.map.fogOfWar()) {
			Diorama.LOGGER.info("Fog of war {}; underground={}", fog, this.underground);
		}
		this.map.setFogOfWar(fog);
		Settings settings = Settings.get();
		boolean spawnSpots = settings.spawnSpots != Settings.SpawnSpots.OFF;
		boolean overlays = settings.chunkGrid || settings.slimeChunks || spawnSpots;
		this.map.setOverlays(overlays ? new Overlays(this.slimeSeed(), spawnSpots) : null);
		this.map.update();
		if (player != null) {
			this.updateUnderground(player);
			if (this.underground) {
				this.updateSight(player);
			}
		}
	}

	private @Nullable BlockPos lastSightOrigin;
	private int sightCooldown;
	private @Nullable CaveSight sight;

	/** Marks the blocks the player can see (see {@link CaveSight}) when their eyes move to a new block. */
	private void updateSight(LocalPlayer player) {
		BlockLooks looks = BlockLooks.get();
		BlockPos eye = BlockPos.containing(player.getEyePosition());
		if (looks == null || eye.equals(this.lastSightOrigin) && this.sightCooldown-- > 0) {
			return;
		}
		this.lastSightOrigin = eye;
		this.sightCooldown = 40;
		if (this.sight == null) {
			this.sight = new CaveSight(this.level);
		}
		int[] box = this.sight.spread(looks, player.getEyePosition(), this.map.seen());
		if (Diorama.DEBUG) {
			Diorama.LOGGER.info("Sight from {}: visited {}, newly seen box {}", eye, this.sight.visited(), java.util.Arrays.toString(box));
		}
		if (box[0] != Integer.MAX_VALUE) {
			this.map.revealed(box[0], box[1], box[2], box[3], box[4], box[5]);
		}
	}

	private static final Direction[] DIRECTIONS = Direction.values();

	private void updateUnderground(LocalPlayer player) {
		if (this.level.dimensionType().hasCeiling()) {
			this.underground = true;
			return;
		}
		BlockPos eye = BlockPos.containing(player.getEyePosition());
		int sky = this.level.getBrightness(LightLayer.SKY, eye);
		boolean covered = !this.level.canSeeSky(eye);
		// Separate thresholds so walking along a cave mouth doesn't flip the map back and forth.
		if (this.underground) {
			this.underground = covered && sky < 10;
		} else {
			this.underground = covered && sky < 5;
		}
	}

	public MapLevel map() {
		return this.map;
	}

	public Lights lights() {
		return this.lights;
	}

	public boolean isUnderground() {
		return this.underground;
	}

	/** The cave slice for a player standing at {@code feetY}: a few blocks above their head. */
	public static int caveLayerFor(double feetY) {
		return Math.floorDiv(Mth.floor(feetY) + 5, 4) * 4;
	}

	/** The layer the minimap should show right now. */
	public int autoLayer(LocalPlayer player) {
		return this.underground ? caveLayerFor(player.getY()) : TileKey.SURFACE;
	}
}
