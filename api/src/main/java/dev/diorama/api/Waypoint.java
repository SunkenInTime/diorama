package dev.diorama.api;

import java.util.Optional;
import java.util.UUID;

/**
 * One waypoint as it was when it was read: it never changes. A waypoint that changes gets a new
 * {@code Waypoint} with the same {@link #id}; one that doesn't keeps the same instance, so
 * comparing instances is a quick way to tell. Diorama implements this; don't.
 */
public interface Waypoint {
	/**
	 * What made a waypoint. More kinds may be added; Diorama reports a kind this version of the
	 * API has no name for as {@link #OTHER}.
	 */
	enum Kind {
		/** Made by the player, in the waypoint list or on the map, or by an addon. The only kind addons can change or remove. */
		MANUAL,
		/** Where the player died. Diorama keeps the last few and removes one the player comes back to. */
		DEATH,
		/** The bed the player last slept in, kept up to date by Diorama: one per dimension. */
		BED,
		/** Set from the map's menu and never saved: it goes when the player leaves the world or switches the saved map. */
		TEMPORARY,
		/** A waystone the Waystones mod told the player about. Waystones names and places it. */
		WAYSTONE,
		/** A lit Nether portal Diorama found. */
		NETHER_PORTAL,
		/** A lit End portal Diorama found. */
		END_PORTAL,
		/** An End gateway Diorama found. */
		END_GATEWAY,
		/** A kind this version of the API has no name for. */
		OTHER,
	}

	/** {@return its ID, the same for as long as the waypoint exists, across saves and restarts} */
	UUID id();

	/**
	 * {@return its name as saved} Diorama shows some automatic pins under a name in the player's
	 * language instead, until the player renames them.
	 */
	String name();

	/**
	 * {@return the letters its pin shows, as saved: up to four, or empty for the first letters of
	 * its name's first two words}
	 */
	String initials();

	/**
	 * {@return the icon its pin shows instead of initials, as a resource ID such as
	 * {@code diorama:home}, or empty for none}
	 */
	String icon();

	/** {@return the block it marks} */
	BlockCoord pos();

	/** {@return the dimension it is in} */
	DimensionId dimension();

	/** {@return its colour, as ARGB} */
	int color();

	/**
	 * {@return whether the player has it shown} This is the waypoint's own switch; a folder the
	 * player hid hides it too, which this doesn't say.
	 */
	boolean visible();

	/** {@return the name of the player's folder it is in, or empty for its dimension's own folder} */
	Optional<String> folder();

	/** {@return the ID of the saved map it was made on, as {@link MapWorld#mapId(DimensionId)} gives it} */
	String mapId();

	/** {@return what made it} */
	Kind kind();

	/** {@return the mod ID of the addon that added it, or empty if Diorama or the player did} */
	Optional<String> addedBy();
}
