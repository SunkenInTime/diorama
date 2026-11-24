package dev.diorama;

import dev.diorama.compat.FtbChunks;
import dev.diorama.compat.MineColonies;
import dev.diorama.compat.OpenPartiesAndClaims;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Land claims from FTB Chunks, Open Parties and Claims or MineColonies, for the maps to draw.
 * {@link FtbChunks} and {@link OpenPartiesAndClaims} hand over each chunk's owner as the claims mod
 * takes in the server's updates, in every dimension, from the full list sent on joining onwards;
 * {@link MineColonies} hands over the land of the colonies the server has sent. Once a tick,
 * changes in the player's dimension make a new {@link Snapshot}, which any thread can read.
 * Everything else here is client thread only.
 */
public final class Claims {
	/**
	 * Who owns a chunk, with their colour (RGB) and name, and whether the chunk is force loaded.
	 * {@code titled} means the name is the claim's whole title as its mod words it, such as Open
	 * Parties and Claims' "Base - Alex's Claim (Forceloadable)", not an owner to name.
	 */
	public record Claim(Owner owner, int rgb, String name, boolean forceLoaded, boolean titled) {
		public Claim asForceLoaded() {
			return new Claim(this.owner, this.rgb, this.name, true, this.titled);
		}

		Claim as(Claim team) {
			return this.forceLoaded ? team.asForceLoaded() : team;
		}
	}

	/**
	 * Whoever owns claimed land, as their claims mod knows them: an FTB team, one of an Open
	 * Parties and Claims player's claims, or a MineColonies colony. Equal owners own the same claim.
	 */
	public interface Owner {
		/**
		 * The owner's claim as it stands now, looked up in their claims mod: empty if they no
		 * longer exist, null if that can't be told now. Client thread.
		 */
		@Nullable Optional<Claim> look();
	}

	/** One of the claims mods' regions, 32 by 32 chunks. */
	private record Region(int x, int z) {
	}

	/** Chunks in a region, kept by {@link #index}. */
	public static final int REGION_CHUNKS = 32 * 32;
	/** Owners' colours and names are looked up again this often, in ticks, as changing them sends no claim update. */
	private static final int TEAM_CHECK_TICKS = 40;

	private static final Snapshot NONE = new Snapshot(null, Map.of());
	private static volatile Snapshot current = NONE;
	/** Every claim known, by dimension, then region; each region's chunks by {@link #index}. Never handed out. */
	private static final Map<ResourceKey<Level>, Map<Region, Claim[]>> known = new HashMap<>();
	/** Each owner's colour and name as last read. */
	private static final Map<Owner, Claim> owners = new HashMap<>();
	/** Regions changed since the last snapshot, by dimension. */
	private static final Map<ResourceKey<Level>, Set<Region>> changed = new HashMap<>();
	private static int ticks;
	/** Time spent on claims this tick, and the most in any tick since {@link #resetSlowestTick}. For tests. */
	private static long tickNanos;
	private static int tickUpdates;
	private static long slowestTick;
	/** For tests: what the slowest tick was made of. */
	private static String slowestTickParts = "";

	private Claims() {
	}

	/** The claims in the player's dimension as they stand, for any thread; none while they aren't shown. */
	public static Snapshot get() {
		return current;
	}

	/** Whether there's anything to read claims from, so settings about them are worth showing. */
	public static boolean supported() {
		return FtbChunks.available() || OpenPartiesAndClaims.available() || MineColonies.available();
	}

	/** Where a chunk's claim is kept in its region's array. */
	public static int index(int chunkX, int chunkZ) {
		return (chunkZ & 31) << 5 | chunkX & 31;
	}

	/**
	 * A chunk's owner as its claims mod has it now, null for no one, and whether it's force
	 * loaded. Like FTB Chunks, an owner that can't be looked up owns nothing. {@code source} is the
	 * claims mod's kind of owner: a mod's "no one" leaves another mod's claim alone, in a game with both.
	 */
	public static void updated(ResourceKey<Level> dimension, int chunkX, int chunkZ, Class<? extends Owner> source, @Nullable Owner owner, boolean forceLoaded) {
		Claim plain = owner == null ? null : owners.get(owner);
		if (plain == null && owner != null) {
			Optional<Claim> found = owner.look();
			if (found != null && found.isPresent()) {
				plain = found.get();
				owners.put(owner, plain);
			}
		}
		Claim claim = plain == null ? null : forceLoaded ? plain.asForceLoaded() : plain;
		Region region = new Region(chunkX >> 5, chunkZ >> 5);
		Map<Region, Claim[]> regions = known.computeIfAbsent(dimension, d -> new HashMap<>());
		Claim[] chunks = regions.get(region);
		if (chunks == null) {
			if (claim == null) {
				return;
			}
			chunks = new Claim[REGION_CHUNKS];
			regions.put(region, chunks);
		}
		int i = index(chunkX, chunkZ);
		if (claim == null && chunks[i] != null && chunks[i].owner().getClass() != source) {
			return;
		}
		if (!Objects.equals(chunks[i], claim)) {
			chunks[i] = claim;
			changed.computeIfAbsent(dimension, d -> new HashSet<>()).add(region);
		}
	}

	/** Whose a chunk is as last handed over, by any claims mod; null for no one's. Client thread. */
	public static @Nullable Owner owner(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
		Claim claim = claim(dimension, chunkX, chunkZ);
		return claim == null ? null : claim.owner();
	}

	/**
	 * A chunk's claim as last handed over, by any claims mod, with its owner and whether it's force
	 * loaded; null for no one's. Unlike {@link #get}, this is known whether or not claims are shown.
	 * Client thread.
	 */
	public static @Nullable Claim claim(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
		Map<Region, Claim[]> regions = known.get(dimension);
		Claim[] chunks = regions == null ? null : regions.get(new Region(chunkX >> 5, chunkZ >> 5));
		return chunks == null ? null : chunks[index(chunkX, chunkZ)];
	}

	/** Time spent taking in an update, counted toward this tick's. */
	public static void spent(long nanos) {
		tickNanos += nanos;
		tickUpdates++;
	}

	/** Called every client tick: makes a new snapshot when the claims in the player's dimension changed. */
	public static void tick(Minecraft mc) {
		long start = System.nanoTime();
		OpenPartiesAndClaims.tick();
		MineColonies.tick(mc);
		if (++ticks % TEAM_CHECK_TICKS == 0) {
			checkOwners();
		}
		boolean shown = mc.level != null && Settings.get().claims != Settings.ClaimsShown.OFF && supported();
		ResourceKey<Level> dimension = shown ? mc.level.dimension() : null;
		Snapshot snapshot = current;
		if (dimension == null) {
			if (snapshot != NONE) {
				current = NONE;
			}
		} else if (!dimension.equals(snapshot.dimension)) {
			current = new Snapshot(dimension, copyAll(dimension));
		} else if (changed.containsKey(dimension)) {
			Map<Region, Claim[]> regions = new HashMap<>(snapshot.regions);
			Map<Region, Claim[]> all = known.getOrDefault(dimension, Map.of());
			for (Region region : changed.get(dimension)) {
				Claim[] chunks = all.get(region);
				if (chunks == null || isEmpty(chunks)) {
					regions.remove(region);
				} else {
					regions.put(region, chunks.clone());
				}
			}
			current = new Snapshot(dimension, Map.copyOf(regions));
		}
		// Regions left with no claims aren't kept, in any dimension.
		for (Map.Entry<ResourceKey<Level>, Set<Region>> entry : changed.entrySet()) {
			Map<Region, Claim[]> all = known.get(entry.getKey());
			if (all != null) {
				for (Region region : entry.getValue()) {
					Claim[] chunks = all.get(region);
					if (chunks != null && isEmpty(chunks)) {
						all.remove(region);
					}
				}
			}
		}
		changed.clear();
		long own = System.nanoTime() - start;
		if (tickNanos + own > slowestTick) {
			slowestTick = tickNanos + own;
			slowestTickParts = tickUpdates + " updates in " + tickNanos / 1000 + " us, snapshot in " + own / 1000 + " us";
		}
		tickNanos = 0;
		tickUpdates = 0;
	}

	/**
	 * Owners whose colour or name changed get their claims redrawn, and owners their mod says no
	 * longer exist lose theirs: deleting an FTB server team sends no update for its chunks.
	 */
	private static void checkOwners() {
		Map<Owner, Claim> renamed = new HashMap<>();
		for (Map.Entry<Owner, Claim> entry : owners.entrySet()) {
			Optional<Claim> now = entry.getKey().look();
			if (now != null && !now.equals(Optional.of(entry.getValue()))) {
				renamed.put(entry.getKey(), now.orElse(null));
			}
		}
		if (renamed.isEmpty()) {
			return;
		}
		for (Map.Entry<Owner, Claim> entry : renamed.entrySet()) {
			if (entry.getValue() == null) {
				owners.remove(entry.getKey());
			} else {
				owners.put(entry.getKey(), entry.getValue());
			}
		}
		for (Map.Entry<ResourceKey<Level>, Map<Region, Claim[]>> dimension : known.entrySet()) {
			for (Map.Entry<Region, Claim[]> region : dimension.getValue().entrySet()) {
				Claim[] chunks = region.getValue();
				for (int i = 0; i < chunks.length; i++) {
					if (chunks[i] != null && renamed.containsKey(chunks[i].owner())) {
						Claim team = renamed.get(chunks[i].owner());
						chunks[i] = team == null ? null : chunks[i].as(team);
						changed.computeIfAbsent(dimension.getKey(), d -> new HashSet<>()).add(region.getKey());
					}
				}
			}
		}
	}

	private static Map<Region, Claim[]> copyAll(ResourceKey<Level> dimension) {
		Map<Region, Claim[]> copy = new HashMap<>();
		for (Map.Entry<Region, Claim[]> region : known.getOrDefault(dimension, Map.of()).entrySet()) {
			if (!isEmpty(region.getValue())) {
				copy.put(region.getKey(), region.getValue().clone());
			}
		}
		return Map.copyOf(copy);
	}

	private static boolean isEmpty(Claim[] chunks) {
		for (Claim claim : chunks) {
			if (claim != null) {
				return false;
			}
		}
		return true;
	}

	/** Forgets one claims mod's claims in a dimension, {@code source} its kind of owner, for it to hand them all over again. */
	public static void forget(ResourceKey<Level> dimension, Class<? extends Owner> source) {
		Map<Region, Claim[]> regions = known.get(dimension);
		if (regions == null) {
			return;
		}
		for (Map.Entry<Region, Claim[]> region : regions.entrySet()) {
			Claim[] chunks = region.getValue();
			for (int i = 0; i < chunks.length; i++) {
				if (chunks[i] != null && chunks[i].owner().getClass() == source) {
					chunks[i] = null;
					changed.computeIfAbsent(dimension, d -> new HashSet<>()).add(region.getKey());
				}
			}
		}
	}

	/** Forgets every claim, on leaving a world or moving to another server: the next one sends its own. */
	public static void clear() {
		known.clear();
		owners.clear();
		changed.clear();
		current = NONE;
		OpenPartiesAndClaims.cleared();
		MineColonies.cleared();
	}

	/** For tests: the most time spent on claims in one tick since the last call, in nanoseconds, and starts again. */
	public static long resetSlowestTick() {
		long slowest = slowestTick;
		slowestTick = 0;
		return slowest;
	}

	/** For tests: what the slowest tick since the last reset was made of. */
	public static String slowestTickParts() {
		return slowestTickParts;
	}

	/** The claims in one dimension at one moment. Never modified. */
	public static final class Snapshot {
		private final @Nullable ResourceKey<Level> dimension;
		/** By region, each region's chunks by {@link #index}; null for none. */
		private final Map<Region, Claim[]> regions;

		Snapshot(@Nullable ResourceKey<Level> dimension, Map<Region, Claim[]> regions) {
			this.dimension = dimension;
			this.regions = regions;
		}

		public boolean isEmpty() {
			return this.regions.isEmpty();
		}

		/** Who owns a chunk, or null if no one the player can see does. */
		public @Nullable Claim at(int chunkX, int chunkZ) {
			Claim[] region = this.regions.get(new Region(chunkX >> 5, chunkZ >> 5));
			return region == null ? null : region[index(chunkX, chunkZ)];
		}

		/** Whether a claim may lie in this box of chunks (inclusive); answered by region, so it can say yes when there's none. */
		public boolean mayHaveAny(int chunkX0, int chunkZ0, int chunkX1, int chunkZ1) {
			if (this.regions.isEmpty()) {
				return false;
			}
			int rx0 = chunkX0 >> 5;
			int rz0 = chunkZ0 >> 5;
			int rx1 = chunkX1 >> 5;
			int rz1 = chunkZ1 >> 5;
			if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > this.regions.size()) {
				for (Region region : this.regions.keySet()) {
					if (region.x() >= rx0 && region.x() <= rx1 && region.z() >= rz0 && region.z() <= rz1) {
						return true;
					}
				}
				return false;
			}
			for (int rx = rx0; rx <= rx1; rx++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					if (this.regions.containsKey(new Region(rx, rz))) {
						return true;
					}
				}
			}
			return false;
		}

		/** Whether the claims in this box of chunks differ between two snapshots. */
		public boolean differsIn(Snapshot other, int chunkX0, int chunkZ0, int chunkX1, int chunkZ1) {
			if (other == this) {
				return false;
			}
			if (!Objects.equals(other.dimension, this.dimension)) {
				return true;
			}
			for (int rx = chunkX0 >> 5; rx <= chunkX1 >> 5; rx++) {
				for (int rz = chunkZ0 >> 5; rz <= chunkZ1 >> 5; rz++) {
					Region region = new Region(rx, rz);
					Claim[] mine = this.regions.get(region);
					Claim[] theirs = other.regions.get(region);
					if (mine != theirs && !Arrays.equals(mine, theirs)) {
						return true;
					}
				}
			}
			return false;
		}
	}
}
