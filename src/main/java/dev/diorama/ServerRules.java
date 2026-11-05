package dev.diorama;

import net.minecraft.network.chat.Component;

/**
 * What the server allows minimaps to show. Servers use the chat codes Xaero's Minimap defined,
 * which players and server owners already know, sent in a system message:
 * <ul>
 *   <li>{@link #FAIR_PLAY}: no cave maps of unseen caves and no entity radar. Diorama turns cave
 *       slices into fog of war instead of switching them off, and hides the radar and the
 *       structures found from a seed, which show places the player hasn't seen.</li>
 *   <li>{@link #NETHER_ALLOWED}: cave maps are fine in the Nether despite fair play.</li>
 *   <li>{@link #NO_MINIMAP}: no minimap at all.</li>
 *   <li>{@link #RESET}: clears all of the above.</li>
 * </ul>
 */
public final class ServerRules {
	static final String FAIR_PLAY = "§f§a§i§r§x§a§e§r§o";
	static final String RESET = "§r§e§s§e§t§x§a§e§r§o";
	static final String NETHER_ALLOWED = "§x§a§e§r§o§m§m§n§e§t§h§e§r§i§s§f§a§i§r";
	static final String NO_MINIMAP = "§n§o§m§i§n§i§m§a§p";

	private static boolean fairPlay;
	private static boolean netherAllowed;
	private static boolean noMinimap;
	/** Player setting: fog of war everywhere, even where the server allows full cave maps. */
	private static boolean alwaysFogOfWar = Boolean.getBoolean("diorama.fogOfWar");

	private ServerRules() {
	}

	public static void onServerMessage(Component message) {
		String text = message.getString();
		if (text.contains(RESET)) {
			fairPlay = false;
			netherAllowed = false;
			noMinimap = false;
		}
		if (text.contains(FAIR_PLAY)) {
			fairPlay = true;
			Diorama.LOGGER.info("Server asked for fair play: cave slices show only what you've seen, and the radar is off");
		}
		if (text.contains(NETHER_ALLOWED)) {
			netherAllowed = true;
		}
		if (text.contains(NO_MINIMAP)) {
			noMinimap = true;
			Diorama.LOGGER.info("Server disabled minimaps");
		}
	}

	public static void onDisconnect() {
		fairPlay = false;
		netherAllowed = false;
		noMinimap = false;
	}

	/** Whether cave slices in this dimension should only show blocks the player has seen. */
	public static boolean fogOfWar(boolean nether) {
		return alwaysFogOfWar || fairPlay && !(nether && netherAllowed);
	}

	/** Whether the map may show other players and mobs. */
	public static boolean radarAllowed() {
		return !fairPlay;
	}

	/** Whether the map may show structures worked out from the seed: places the player hasn't been to. */
	public static boolean structuresAllowed() {
		return !fairPlay;
	}

	/** Whether the server allows a minimap or map at all. */
	public static boolean minimapAllowed() {
		return !noMinimap;
	}

	public static void setAlwaysFogOfWar(boolean value) {
		alwaysFogOfWar = value;
	}

	public static boolean alwaysFogOfWar() {
		return alwaysFogOfWar;
	}
}
