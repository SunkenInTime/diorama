package dev.diorama;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.compat.Contraptions;
import dev.diorama.compat.SubLevels;
import dev.diorama.craft.Crafts;
//? if >=1.21.6 {
import dev.diorama.render.DioramaPipelines;
//?}
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.Waypoints;
import dev.diorama.waypoint.Waystones;
import dev.diorama.waypoint.XaeroNotice;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * What Diorama does on the client, whichever mod loader runs it: its key bindings, and what it does
 * each tick. The loader's entrypoint registers these and hooks up the rest of the mod to the
 * loader's events: {@code dev.diorama.fabric.DioramaFabric} on Fabric, {@code
 * dev.diorama.neoforge.DioramaNeoForge} on NeoForge.
 */
public final class DioramaClient {
	//? if >=1.21.9 && fabric {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Diorama.id("map"));
	//?} else if >=1.21.9 {
	/*// NeoForge constructs mods in parallel and vanilla's category list isn't thread-safe, so there
	// DioramaNeoForge registers it through RegisterKeyMappingsEvent instead.
	public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Diorama.id("map"));
	*///?} else {
	/*// Key categories were bare translation keys; this is the one Category.register makes.
	public static final String CATEGORY = "key.category.diorama.map";
	*///?}
	public static final KeyMapping OPEN_MAP = new KeyMapping("key.diorama.open_map", InputConstants.KEY_M, CATEGORY);
	public static final KeyMapping NEW_WAYPOINT = new KeyMapping("key.diorama.new_waypoint", InputConstants.KEY_B, CATEGORY);
	public static final KeyMapping WAYPOINT_LIST = new KeyMapping("key.diorama.waypoints", InputConstants.KEY_U, CATEGORY);
	public static final KeyMapping ZOOM_IN = new KeyMapping("key.diorama.zoom_in", InputConstants.KEY_EQUALS, CATEGORY);
	public static final KeyMapping ZOOM_OUT = new KeyMapping("key.diorama.zoom_out", InputConstants.KEY_MINUS, CATEGORY);
	public static final KeyMapping SETTINGS = new KeyMapping("key.diorama.settings", InputConstants.UNKNOWN.getValue(), CATEGORY);
	public static final KeyMapping TOGGLE_MINIMAP = new KeyMapping("key.diorama.toggle_minimap", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/**
	 * Held, the minimap shows at its enlarged size; with {@link Settings#minimapEnlargeToggle}, each press
	 * switches it. Unbound to start with: zoom mods and others claim the free letters.
	 */
	public static final KeyMapping ENLARGE_MINIMAP = new KeyMapping("key.diorama.enlarge_minimap", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/** Works on the map, as its dimension button does; unbound, like Xaero's World Map's. */
	public static final KeyMapping TOGGLE_DIMENSION = new KeyMapping("key.diorama.toggle_dimension", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/** Switches the Waypoints tab's "In the world", pins, labels and beams together; unbound, like Xaero's "Toggle In-World Waypoints". */
	public static final KeyMapping TOGGLE_WAYPOINTS_WORLD = new KeyMapping("key.diorama.toggle_waypoints_world", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/**
	 * Turns the map a quarter, as the facing button on the map does, there and in the world, where the
	 * minimap turns with it. Unbound; Xaero's maps don't turn, so there is no key of theirs to match.
	 */
	public static final KeyMapping TURN_MAP = new KeyMapping("key.diorama.turn_map", InputConstants.UNKNOWN.getValue(), CATEGORY);
	/** Every key binding, for the loader to register. */
	public static final List<KeyMapping> KEY_MAPPINGS = List.of(OPEN_MAP, NEW_WAYPOINT, WAYPOINT_LIST, ZOOM_IN, ZOOM_OUT, SETTINGS, TOGGLE_MINIMAP, ENLARGE_MINIMAP, TOGGLE_DIMENSION, TOGGLE_WAYPOINTS_WORLD, TURN_MAP);

	/** Switched by the enlarge key while {@link Settings#minimapEnlargeToggle} is on; off outside a world, as Xaero's, or with the setting off. */
	private static boolean enlargeToggled;
	private static boolean enlargeWasDown;
	private static boolean waypointsKeyWasDown;
	private static boolean turnKeyWasDown;

	private DioramaClient() {
	}

	/** Whether the minimap shows at its enlarged size: the enlarge key held, or switched on. */
	public static boolean minimapEnlarged() {
		return Settings.get().minimapEnlargeToggle ? enlargeToggled : ENLARGE_MINIMAP.isDown();
	}

	private static void changeZoom(int step) {
		Settings s = Settings.get();
		int next = Math.max(0, Math.min(Settings.ZOOMS.length - 1, s.zoomStep() + step));
		if (next != s.zoomStep()) {
			s.zoomStep(next);
			s.save();
		}
	}

	/** Called once, from the loader's entrypoint. */
	public static void init() {
		Crafts.register(Contraptions::bodies);
		Crafts.register(SubLevels::bodies);
		//? if >=1.21.6 {
		DioramaPipelines.init();
		//?}
		//? if fabric {
		// The settings file sits in the game directory. Fabric calls this from inside Minecraft's
		// constructor; NeoForge constructs mods before Minecraft exists, so DioramaNeoForge loads them
		// at client setup instead.
		Settings.get();
		//?}
	}

	/** Called at the end of every client tick. */
	public static void tick(Minecraft mc) {
		// On the first tick every mod is set up, Waystones and its event library included.
		Waystones.tick();
		dev.diorama.addon.Addons.tick(mc);
		MapSession.tick(mc);
		Waypoints.watchSleep(mc);
		Waypoints.watchDeaths(mc);
		Claims.tick(mc);
		XaeroNotice.tick(mc);
		dev.diorama.web.ClientWebMap.tick(mc);
		while (OPEN_MAP.consumeClick()) {
			if (mc.gui.screen() == null && mc.level != null && !ServerRules.sayMapOffHere(mc) && ServerRules.minimapAllowed()) {
				mc.gui.setScreen(new MapScreen());
			}
		}
		while (NEW_WAYPOINT.consumeClick()) {
			Waypoint waypoint = Waypoints.atPlayer();
			if (mc.gui.screen() == null && waypoint != null && !ServerRules.sayMapOffHere(mc)) {
				mc.gui.setScreen(new WaypointEditScreen(null, waypoint, true));
			}
		}
		while (ZOOM_IN.consumeClick()) {
			changeZoom(1);
		}
		while (ZOOM_OUT.consumeClick()) {
			changeZoom(-1);
		}
		while (SETTINGS.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new SettingsScreen(null));
			}
		}
		while (TOGGLE_MINIMAP.consumeClick()) {
			Settings s = Settings.get();
			s.minimap = !s.minimap;
			s.save();
		}
		// Held past the key repeat delay the key keeps clicking: only a press that starts it counts.
		boolean waypointsPressed = false;
		while (TOGGLE_WAYPOINTS_WORLD.consumeClick()) {
			waypointsPressed = true;
		}
		if (waypointsPressed && !waypointsKeyWasDown) {
			Settings s = Settings.get();
			s.waypointsInWorld = !s.waypointsInWorld;
			s.save();
		}
		waypointsKeyWasDown = TOGGLE_WAYPOINTS_WORLD.isDown();
		// A turn redraws the whole map, so a held key turns it once.
		boolean turnPressed = false;
		while (TURN_MAP.consumeClick()) {
			turnPressed = true;
		}
		if (turnPressed && !turnKeyWasDown && mc.gui.screen() == null && mc.level != null && !ServerRules.sayMapOffHere(mc) && ServerRules.minimapAllowed()
			&& MapSession.turn(1)) {
			// The minimap may be hidden: say which way the map now looks.
			Component facing = Component.translatable("diorama.map.facing." + Projection.facing().id());
			//? if >=26.1 {
			mc.gui.hud.setOverlayMessage(facing, false);
			//?} else {
			/*mc.gui.setOverlayMessage(facing, false);
			*///?}
		}
		turnKeyWasDown = TURN_MAP.isDown();
		// Presses are taken with the setting off too, so ones made while holding don't count once it goes on.
		// Held past the key repeat delay the key keeps clicking: only a press that starts it counts.
		boolean pressed = false;
		while (ENLARGE_MINIMAP.consumeClick()) {
			pressed = true;
		}
		if (pressed && !enlargeWasDown) {
			enlargeToggled = !enlargeToggled;
		}
		enlargeWasDown = ENLARGE_MINIMAP.isDown();
		if (mc.level == null || !Settings.get().minimapEnlargeToggle) {
			enlargeToggled = false;
		}
		while (WAYPOINT_LIST.consumeClick()) {
			if (mc.gui.screen() == null && mc.level != null && !ServerRules.sayMapOffHere(mc)) {
				mc.gui.setScreen(new WaypointListScreen(null));
			}
		}
	}
}
