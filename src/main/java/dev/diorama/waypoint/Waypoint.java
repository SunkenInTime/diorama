package dev.diorama.waypoint;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * A marked place. Mutable so the edit screen can change it in place; {@link WaypointStore} saves
 * after every change.
 */
public final class Waypoint {
	public enum Kind {
		MANUAL,
		/** Added automatically where the player died. */
		DEATH,
		/** Set from the map's menu; never saved, so it's gone once the player leaves the world. */
		TEMPORARY,
		/** A waystone the Waystones mod told the client about ({@link Waystones}). Never saved, and Waystones names it. */
		WAYSTONE,
		/** The bed the player last slept in, kept up to date: one per dimension. */
		BED,
		/** A lit portal the map found ({@link Portals}). */
		NETHER_PORTAL,
		/** A lit portal the map found ({@link Portals}). */
		END_PORTAL,
		/** A lit portal the map found ({@link Portals}). */
		END_GATEWAY;

		public boolean isPortal() {
			return this == NETHER_PORTAL || this == END_PORTAL || this == END_GATEWAY;
		}
	}

	/** Colours a waypoint can take, picked to stay readable on both grass and water. */
	public static final int[] PALETTE = {
		0xFFE5484D, 0xFFF76B15, 0xFFFFC53D, 0xFF46A758, 0xFF12A594, 0xFF0090FF, 0xFF3E63DD, 0xFF8E4EC6,
		0xFFD6409F, 0xFFFFFFFF, 0xFFB0B4BA, 0xFF60646C, 0xFF8D6B4B, 0xFF99D52A, 0xFF7CE2FE, 0xFFF0C0D8
	};

	/**
	 * Automatic pins are saved under English names and shown in the player's language, until the
	 * player renames one. Xaero's Minimap does the same with a translation key for its deathpoints;
	 * an English name keeps the file readable by older Diorama versions, which saved these names.
	 */
	public static final String BED_NAME = "Bed";
	private static final DateTimeFormatter DEATH_TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final Pattern DEATH_NAME = Pattern.compile("Death \\d\\d:\\d\\d");
	/** When a death pin shows it was made: date and time, the way the computer writes them. */
	private static final DateTimeFormatter DEATH_DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);

	/** The most letters a pin's initials can have; the pin's head widens to hold more than two. */
	public static final int MAX_INITIALS = 4;

	public UUID id = UUID.randomUUID();
	public String name = "";
	public String initials = "";
	/** A picture the pin shows instead of the initials, by {@link dev.diorama.render.WaypointIcons} id, or empty. */
	public String icon = "";
	public int x;
	public int y;
	public int z;
	/** Dimension id, e.g. {@code minecraft:overworld}. */
	public String dimension = "minecraft:overworld";
	public int color = PALETTE[0];
	/**
	 * Whether it shows at all: the list's Hide and Show, Xaero's Minimap's disabled flag, and what
	 * addons read as visible. Where it shows while it does is {@link #showInWorld},
	 * {@link #showOnMinimap} and {@link #showOnMap}.
	 */
	public boolean visible = true;
	/**
	 * Where it shows while it's {@link #visible}, each of the three places on its own. At least one
	 * is always on: unticking all three in the editor hides the waypoint instead
	 * ({@link #setShownOn}), so Show brings it back where it was. A file from before these were
	 * saved has none of them, which reads as all three on.
	 */
	public boolean showInWorld = true;
	public boolean showOnMinimap = true;
	public boolean showOnMap = true;
	/** The custom folder it's in, or empty for its dimension's own folder. See {@link WaypointStore#folderKey}. */
	public String folder = "";
	public Kind kind = Kind.MANUAL;
	public long created = System.currentTimeMillis();
	/** The dimension's map it was made on ({@link dev.diorama.Maps}); null for the main map. */
	public @org.jspecify.annotations.Nullable String map;
	/** A portal pin the player deleted: kept out of sight, so finding the portal again doesn't bring it back. */
	public boolean deleted;
	/** A portal pin the player renamed or moved: it's theirs now, and stays when the portal goes. */
	public boolean edited;
	/** The mod ID of the addon that added it ({@link dev.diorama.api.WaypointBatch.Builder#add}); null for Diorama's and the player's. */
	public @org.jspecify.annotations.Nullable String addedBy;
	/**
	 * Set on a copy standing in for this waypoint in another dimension, at that dimension's
	 * coordinates ({@link WaypointStore#shownIn}): the waypoint itself. Never saved.
	 */
	public transient @org.jspecify.annotations.Nullable Waypoint original;

	public Waypoint() {
	}

	public Waypoint(String name, int x, int y, int z, String dimension, int color) {
		this.name = name;
		this.initials = initialsFor(name);
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension;
		this.color = color;
	}

	/** The waypoint itself: for a copy shown in another dimension, the one it stands in for. */
	public Waypoint source() {
		return this.original != null ? this.original : this;
	}

	/**
	 * A copy of this waypoint for a dimension where one block is {@code divider} of this one's, as a
	 * portal would take the player: the Overworld's waypoints stand in the Nether at an eighth of
	 * their x and z. The height stays, as in Xaero's Minimap. Faded, so it doesn't pass for one of
	 * that dimension's own. Made in {@code reuse} when given: a new waypoint costs a random UUID,
	 * several microseconds, and the maps and the world ask for the copies every frame.
	 */
	Waypoint scaled(double divider, @org.jspecify.annotations.Nullable Waypoint reuse) {
		Waypoint copy = reuse != null ? reuse : new Waypoint();
		copy.id = this.id;
		copy.name = this.name;
		copy.initials = this.initials;
		copy.icon = this.icon;
		copy.x = (int) Math.floor(this.x / divider);
		copy.y = this.y;
		copy.z = (int) Math.floor(this.z / divider);
		copy.dimension = this.dimension;
		int alpha = this.color >>> 24 == 0 ? 0xFF : this.color >>> 24;
		copy.color = Math.max(1, alpha * 3 / 5) << 24 | this.color & 0xFFFFFF;
		copy.visible = this.visible;
		copy.showInWorld = this.showInWorld;
		copy.showOnMinimap = this.showOnMinimap;
		copy.showOnMap = this.showOnMap;
		copy.folder = this.folder;
		copy.kind = this.kind;
		copy.created = this.created;
		copy.map = this.map;
		copy.deleted = this.deleted;
		copy.edited = this.edited;
		copy.original = this;
		return copy;
	}

	/** The three places a waypoint can show: in the world, on the minimap, on the full map. */
	public enum Surface {
		WORLD,
		MINIMAP,
		MAP
	}

	/** Whether it's set to show on {@code surface} while it's {@link #visible}. */
	public boolean showsOn(Surface surface) {
		return switch (surface) {
			case WORLD -> this.showInWorld;
			case MINIMAP -> this.showOnMinimap;
			case MAP -> this.showOnMap;
		};
	}

	/**
	 * Where it shows, as the editor's three boxes set it: on the places ticked. With none ticked it's
	 * hidden, as Hide in the list hides it, and keeps the places it had, so Show puts it back there.
	 */
	public void setShownOn(boolean world, boolean minimap, boolean map) {
		if (world || minimap || map) {
			this.visible = true;
			this.showInWorld = world;
			this.showOnMinimap = minimap;
			this.showOnMap = map;
		} else {
			this.visible = false;
		}
	}

	/** Takes where new waypoints show from the settings ({@link dev.diorama.Settings#newWaypointInWorld} and the rest). */
	public void showAsNew() {
		dev.diorama.Settings settings = dev.diorama.Settings.get();
		this.showInWorld = true;
		this.showOnMinimap = true;
		this.showOnMap = true;
		this.setShownOn(settings.newWaypointInWorld, settings.newWaypointOnMinimap, settings.newWaypointOnMap);
	}

	/** A file edited by hand can turn all three places off: that's hidden, and Show brings it back everywhere. */
	void normaliseShownOn() {
		if (!this.showInWorld && !this.showOnMinimap && !this.showOnMap) {
			this.visible = false;
			this.showInWorld = true;
			this.showOnMinimap = true;
			this.showOnMap = true;
		}
	}

	/** Whether the player can edit or delete it here: everything but waystones, which Waystones owns. */
	public boolean editable() {
		return this.kind != Kind.WAYSTONE;
	}

	/**
	 * The saved name of a death pin made at {@code time}. It shows with the date as well
	 * ({@link #deathDate}), from {@link #created}; the saved name keeps only the time, as it always has.
	 */
	public static String deathName(LocalTime time) {
		return "Death " + time.format(DEATH_TIME);
	}

	/** The date and time a death pin made at {@code millis} shows in its name. */
	public static String deathDate(long millis) {
		String date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DEATH_DATE);
		// Java puts no-break spaces in some formats (before "PM", say), which the game's font may lack.
		return date.replace(' ', ' ').replace(' ', ' ');
	}

	/**
	 * The name to show: an automatic pin's own name in the player's language, otherwise the saved
	 * one. A copy shown in another dimension says whose it is: "Home (Overworld)".
	 */
	public String shownName() {
		if (this.original != null) {
			return Component.translatable("diorama.waypoint.from_dimension", this.original.shownName(), Waypoints.dimensionName(this.dimension)).getString();
		}
		switch (this.kind) {
			case BED -> {
				// The Bed icon's name, which every language already has.
				if (this.name.equals(BED_NAME)) {
					return WaypointIconScreen.name(dev.diorama.render.WaypointIcons.BED).getString();
				}
			}
			case DEATH -> {
				if (DEATH_NAME.matcher(this.name).matches()) {
					return Component.translatable("diorama.waypoint.death", deathDate(this.created)).getString();
				}
			}
			case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> {
				if (this.name.equals(Portals.savedName(this.kind))) {
					return Portals.block(this.kind).getName().getString();
				}
				// The Portal icon's name, which every language already has.
				if (this.name.equals(Portals.PLAIN_NAME)) {
					return WaypointIconScreen.name(dev.diorama.render.WaypointIcons.PORTAL).getString();
				}
			}
			default -> {
			}
		}
		return this.name;
	}

	public String label() {
		return this.initials.isEmpty() ? initialsFor(this.name) : this.initials;
	}

	public double distanceTo(double px, double py, double pz) {
		double dx = this.x + 0.5 - px;
		double dy = this.y - py;
		double dz = this.z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** First letter of up to two words: "Home" is H, "Iron Farm" is IF. */
	public static String initialsFor(String name) {
		StringBuilder out = new StringBuilder();
		for (String word : name.trim().split("\\s+")) {
			if (!word.isEmpty() && out.length() < 2) {
				out.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
			}
		}
		return out.isEmpty() ? "?" : out.toString();
	}
}
