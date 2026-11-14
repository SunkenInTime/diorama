package dev.diorama;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.diorama.bake.TileBaker;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Player settings, saved to {@code .minecraft/diorama/config.json}. Fields are public so Gson
 * can read and write them; call {@link #save()} after changing one.
 */
public final class Settings {
	public enum Corner {
		TOP_RIGHT,
		TOP_LEFT,
		BOTTOM_RIGHT,
		BOTTOM_LEFT
	}

	/** Minimap zoom steps, in GUI pixels per block. */
	public static final double[] ZOOMS = {0.1875, 0.25, 0.375, 0.5, 0.75, 1, 1.5, 2, 3, 4};
	/** Steps added before the ones {@link #minimapZoom} counts from. */
	private static final int STEPS_BEFORE_OLD_ZOOMS = 4;
	/** How many steps {@link #minimapZoom} counted. */
	private static final int OLD_ZOOMS = 6;
	/** Radar height ranges, in blocks. */
	public static final int[] RANGES = {16, 32, 64, 128};
	/** How near a waypoint has to be to show in the world and on the minimap, in blocks; 0 is any distance. */
	public static final int[] WAYPOINT_DISTANCES = {0, 64, 128, 256, 512, 1024, 2048};
	/** Minimap sizes, in GUI pixels: {@link #MIN_SIZE} to {@link #MAX_SIZE} in steps of {@link #SIZE_STEP}. */
	public static final int MIN_SIZE = 64;
	public static final int MAX_SIZE = 224;
	public static final int SIZE_STEP = 8;
	/** How much the enlarge key grows the minimap, in percent: {@link #MIN_ENLARGED} to {@link #MAX_ENLARGED} in steps of {@link #ENLARGED_STEP}. */
	public static final int MIN_ENLARGED = 125;
	public static final int MAX_ENLARGED = 400;
	public static final int ENLARGED_STEP = 25;
	/** Minimap opacity, in percent: {@link #MIN_OPACITY} to {@link #MAX_OPACITY} in steps of {@link #OPACITY_STEP}. */
	public static final int MIN_OPACITY = 20;
	public static final int MAX_OPACITY = 100;
	public static final int OPACITY_STEP = 5;

	public static final String DEFAULT_TELEPORT_COMMAND = "tp @s {x} {y} {z}";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Settings current;

	public boolean minimap = true;
	/** In GUI pixels. Saved as a number; files from before it was a slider say SMALL, MEDIUM or LARGE. */
	@JsonAdapter(SizeAdapter.class)
	public int minimapSize = 96;
	/** The corner whose edges the minimap is placed by, the nearest to where it was put. */
	public Corner minimapCorner = Corner.TOP_RIGHT;
	/**
	 * How far in from that corner's edges the minimap sits, in GUI pixels: from the side edge to
	 * the map, and from the top edge to the map or from the bottom edge to the lines under it.
	 * Files from before the position screen leave them where every corner put it.
	 */
	public int minimapOffsetX = MinimapHud.MARGIN;
	public int minimapOffsetY = MinimapHud.MARGIN;
	/** Midway across the top or bottom of the screen, as Xaero's Minimap's Centered; {@link #minimapOffsetX} waits for when it isn't. */
	public boolean minimapCentered = false;
	/** The minimap's size while the enlarge key is held, in percent of {@link #minimapSize}. */
	public int minimapEnlarged = 200;
	/** The enlarge key switches the enlarged size on and off instead of being held (Xaero's "Toggled Enlarged Minimap"). */
	public boolean minimapEnlargeToggle = false;
	/** One of {@link #ZOOMS}. Saved as the value, so adding a step doesn't move anyone's zoom. */
	public double minimapPixelsPerBlock = 2;
	/** Files from before {@link #minimapPixelsPerBlock} say this instead, a place in the steps as they were. Read once. */
	private @Nullable Integer minimapZoom;
	/** How opaque the minimap's map is, in percent. Files from before this setting leave it at 100. */
	public int minimapOpacity = MAX_OPACITY;
	public boolean compass = true;
	public boolean coordinates = true;
	public boolean biome = true;
	public GameTime gameTime = GameTime.OFF;
	/**
	 * How big the compass letters and the lines under the minimap are, in percent of the game's own
	 * text: {@link #MIN_TEXT_SIZE} to {@link #MAX_TEXT_SIZE}. {@code TextSize.scale} rounds it to
	 * whole screen pixels.
	 */
	public int minimapTextSize = 100;
	/** Whether the sky view is drawn by day, at night, or as the world's time has it; see {@code NightLook}. */
	public DayNight dayNight = DayNight.FOLLOW;
	public boolean autoCaves = true;
	/** Keep the surface view while only a roof is overhead; see {@link MapSession#autoLayer}. */
	public boolean preferSurface = true;
	/**
	 * Cave slices shaded by depth (Xaero's "Legible Cave Maps"): lower ground fades to slate and
	 * only the rock near the cut is seen through; see {@link dev.diorama.bake.ChunkSource#depthCaves}.
	 * Off, a slice sees through rock to every cave below it.
	 */
	public boolean legibleCaves = false;
	public Frame minimapFrame = Frame.CLASSIC;
	public Shape minimapShape = Shape.SQUARE;

	/** Which maps show the radar at all; what it shows is the same on both. */
	public boolean radarOnMinimap = true;
	public boolean radarOnMap = true;
	public boolean radarPlayers = true;
	public boolean radarHostile = true;
	public boolean radarNeutral = true;
	public boolean radarPassive = true;
	/** Items lying on the ground, as small dots. */
	public boolean radarItems = true;
	public int radarRange = 32;
	/** How players and mobs are drawn: their pixel-art heads, faces in framed boxes, or plain dots. */
	public RadarStyle radarStyle = RadarStyle.HEADS;
	/** Radar heads' size, in percent of the normal size: {@link #MIN_SCALE} to {@link #MAX_SCALE} in steps of {@link #SCALE_STEP}. */
	public int radarIconScale = 100;
	/** Outlines mob heads in red, yellow or green by how they treat you; off, they're outlined in black. */
	public boolean radarTypeOutline = true;
	/** The small arrow beside a player or mob well above or below you (Xaero's "Display Height: Direction"). Off, only the fade says so. */
	public boolean radarHeightArrows = true;
	public NameTags playerNames = NameTags.FULLSCREEN;
	/**
	 * The player's own radar categories, each a list of mobs with its own on/off, icon size and names,
	 * first match first; see {@link RadarCategories}.
	 */
	public java.util.List<RadarCategories.Category> radarCategories = new java.util.ArrayList<>();
	/** The icon size and names of the fixed categories, by {@code Radar.Kind}; one that changes nothing isn't here. */
	public java.util.Map<String, RadarCategories.KindLook> radarKinds = new java.util.TreeMap<>();
	/** Whether the Pokémon category was made, so deleting it lasts. */
	public boolean radarPokemonAdded = false;
	/** Index into {@link #ARROW_COLORS}. */
	public int arrowColor = 0;

	public boolean waypointsOnMap = true;
	public boolean waypointsInWorld = true;
	public boolean deathMarkers = true;
	/** Death markers go once the player is back within {@link dev.diorama.waypoint.Waypoints#DEATH_REACHED} blocks, as in Xaero's Minimap. */
	public boolean removeReachedDeaths = true;
	/** A pin on the bed the player last slept in ({@link dev.diorama.waypoint.Waypoints#watchSleep}). */
	public boolean bedMarkers = true;
	/** Pins on the lit portals the map has found ({@link dev.diorama.waypoint.Portals}). */
	public boolean portalMarkers = false;
	public WaypointLabels waypointLabels = WaypointLabels.AUTO;
	/** Which waypoints stand in a beam in the world, where they show there at all. */
	public Beams waypointBeams = Beams.TEMPORARY;
	/**
	 * Waypoints further than this many blocks away leave the world and the minimap; the map still
	 * shows them all. 0 shows them at any distance. One of {@link #WAYPOINT_DISTANCES}.
	 */
	public int waypointDistance = 0;
	/**
	 * How big pins are drawn in each view, in percent of the size they're drawn at one pixel per GUI
	 * pixel: {@link #MIN_WAYPOINT_SIZE} to {@link #MAX_SCALE}. {@code Pins.scale} rounds it to whole
	 * screen pixels, so 60 is drawn at 67% on GUI scales 3 and 6 and at 50% on 2 and 4.
	 */
	public int waypointMinimapSize = DEFAULT_WAYPOINT_SIZE;
	public int waypointMapSize = DEFAULT_WAYPOINT_SIZE;
	public int waypointWorldSize = DEFAULT_WAYPOINT_SIZE;
	/**
	 * How big the name and distance under a waypoint in the world are, in percent of the game's own
	 * text, like {@link #minimapTextSize}. Its own setting, as Xaero's Minimap and JourneyMap have:
	 * in 0.10.0 the text followed the pin's size, and players who wanted a bigger pin got bigger
	 * text with it (#386).
	 */
	public int waypointWorldTextSize = 100;
	/** The Waypoints and Settings buttons on the map. A modpack can hide them; their keys still open both. */
	public boolean mapWaypointsButton = true;
	public boolean mapSettingsButton = true;
	/** The Maps button on the map, where each dimension's maps are made and switched. */
	public boolean mapMapsButton = true;
	/** The square at the bottom left of the map that shows the next dimension's map. */
	public boolean mapDimensionButton = true;
	/** Add waypoint and Add temporary waypoint in the map's right-click menu. */
	public boolean mapAddWaypoints = true;
	/**
	 * The command sent to teleport, with {x}, {y} and {z} for the block. Servers that don't let
	 * players use /tp often have their own teleport command.
	 */
	public String teleportCommand = DEFAULT_TELEPORT_COMMAND;

	public boolean outlines = true;
	public boolean roundedFog = true;
	public boolean alwaysFogOfWar = false;
	/**
	 * Singleplayer: fills the map in from the chunks in the world's save, such as an area a
	 * pre-generation tool made, without walking there. Caves stay unknown until you've been there.
	 */
	public boolean mapSavedChunks = true;
	/**
	 * The IDs of the dimensions where the map is off: no minimap, no map, no waypoints in the
	 * world, and nothing mapped. For a dimension that's about finding the way without one.
	 */
	public java.util.Set<String> mapOffDimensions = new java.util.TreeSet<>();
	/**
	 * Fast is for slower computers: the map takes one sample per pixel instead of four, so it
	 * fills in about four times sooner, and the fog draws two layers instead of eight, without
	 * lighting them from the sun.
	 */
	public Quality quality = Quality.FANCY;
	/**
	 * Bakes map tiles on the graphics card. The first tiles are checked against the tracer, which
	 * takes over by itself if they don't match, the card is too slow or the GPU code fails.
	 */
	public boolean gpuBaking = true;
	/** How many of the old defaults {@link #load} has moved this file past. */
	public int defaultsMoved = 0;
	public boolean chunkGrid = false;
	public boolean slimeChunks = false;
	public SpawnSpots spawnSpots = SpawnSpots.OFF;
	/** Where land claims from FTB Chunks or Open Parties and Claims show, when one is installed. */
	public ClaimsShown claims = ClaimsShown.ALWAYS;

	/**
	 * The map in a browser, for a second screen ({@link dev.diorama.web.ClientWebMap}): off, for
	 * browsers on this computer, or for every device on its network.
	 */
	public WebMap webMap = WebMap.OFF;
	/** The port the web map is served on; the next free one after it if it's taken. Only in the file. */
	public int webMapPort = DEFAULT_WEB_MAP_PORT;

	public static final int DEFAULT_WEB_MAP_PORT = 8100;

	/** Who can open the web map: nobody, browsers on this computer, or any device on its network. */
	public enum WebMap {
		OFF,
		THIS_COMPUTER,
		NETWORK
	}

	/** Where land claims show: nowhere, on the fullscreen map, or on the minimap too. */
	public enum ClaimsShown {
		OFF,
		FULLSCREEN,
		ALWAYS
	}

	/** Where the player's drawings show. Notes stay off the minimap, where they'd cover it. */
	public DrawingsShown drawings = DrawingsShown.ALWAYS;

	/** Where drawings show: nowhere but draw mode, on the fullscreen map, or on the minimap too. */
	public enum DrawingsShown {
		OFF,
		FULLSCREEN,
		ALWAYS
	}

	/** Where the game's world border shows; see {@link WorldBorderOverlay}. */
	public WorldBorderShown worldBorder = WorldBorderShown.ALWAYS;

	/** Where the world border shows: nowhere, on the fullscreen map, or on the minimap too. */
	public enum WorldBorderShown {
		OFF,
		FULLSCREEN,
		ALWAYS
	}

	public enum Quality {
		FANCY,
		FAST
	}

	/** The game time under the minimap, in the formats Xaero's Minimap offers. */
	public enum GameTime {
		OFF,
		DAY_24H,
		DAY_12H,
		ONLY_24H,
		ONLY_12H
	}

	/** How dark the map is drawn: as the world's time of day, or always by day or at night. */
	public enum DayNight {
		FOLLOW,
		DAY,
		NIGHT
	}

	/** The minimap's border: the dark frame, a clean Minecraft edge, or none. */
	public enum Frame {
		CLASSIC,
		MINECRAFT,
		NONE
	}

	/** The minimap's outline: a square, or a circle like other minimap mods offer. */
	public enum Shape {
		SQUARE,
		ROUND
	}

	public enum RadarStyle {
		HEADS,
		FRAMED,
		DOTS
	}

	public static final int MIN_SCALE = 50;
	public static final int MAX_SCALE = 200;
	public static final int SCALE_STEP = 25;

	public static final int MIN_WAYPOINT_SIZE = 25;
	public static final int DEFAULT_WAYPOINT_SIZE = 60;

	public static final int MIN_TEXT_SIZE = 25;
	public static final int MAX_TEXT_SIZE = 400;

	private static int clampTextSize(int size) {
		return Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, size));
	}

	private static int clampWaypointSize(int size) {
		return Math.max(MIN_WAYPOINT_SIZE, Math.min(MAX_SCALE, size));
	}

	/** The nearest icon scale the settings screen can show. */
	public static int snapScale(int scale) {
		int clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
		return MIN_SCALE + Math.round((clamped - MIN_SCALE) / (float) SCALE_STEP) * SCALE_STEP;
	}

	/** Where other players' names show: nowhere, on the fullscreen map, or on the minimap too. */
	public enum NameTags {
		NEVER,
		FULLSCREEN,
		ALWAYS
	}

	/** What in-world waypoint labels say: name when near or aimed at, always the name, only the distance, or nothing. */
	public enum WaypointLabels {
		AUTO,
		ALWAYS,
		DISTANCE,
		NONE
	}

	/**
	 * Which in-world waypoints get a beam: none, the ones the player is heading for right now
	 * (temporary waypoints and death markers), or all of them.
	 */
	public enum Beams {
		OFF,
		TEMPORARY,
		ALL
	}

	/** Colours the player arrow can take, with their names' keys. */
	public static final int[] ARROW_COLORS = {0x3FA9F5, 0xEF4B4B, 0x62D96B, 0xFFC53D, 0xF76B15, 0x8E4EC6, 0xD6409F, 0xF2F2F2};
	public static final String[] ARROW_COLOR_NAMES = {"blue", "red", "green", "yellow", "orange", "purple", "pink", "white"};

	/** The player arrow's colour as RGB. */
	public int arrowRgb() {
		return ARROW_COLORS[Math.floorMod(this.arrowColor, ARROW_COLORS.length)];
	}

	/** Which spawn spots the map marks: none, those dark even by day, or also those dark only at night. */
	public enum SpawnSpots {
		OFF,
		ALWAYS_DARK,
		AT_NIGHT_TOO
	}

	public static Settings get() {
		if (current == null) {
			current = load();
		}
		return current;
	}

	/** Reads the file again. For tests and tools. */
	public static Settings reload() {
		current = load();
		return current;
	}

	public double zoom() {
		return ZOOMS[this.zoomStep()];
	}

	/** Which of {@link #ZOOMS} the minimap is on. */
	public int zoomStep() {
		int nearest = 0;
		for (int i = 1; i < ZOOMS.length; i++) {
			if (Math.abs(ZOOMS[i] - this.minimapPixelsPerBlock) < Math.abs(ZOOMS[nearest] - this.minimapPixelsPerBlock)) {
				nearest = i;
			}
		}
		return nearest;
	}

	public void zoomStep(int step) {
		this.minimapPixelsPerBlock = ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, step))];
	}

	/** How opaque the minimap's map is, 0 to 1. */
	public float opacity() {
		return snapOpacity(this.minimapOpacity) / 100.0F;
	}

	/** The map look these settings ask for. */
	public MapStyle style() {
		return MapStyle.DEFAULT.withName("Settings")
			.withOutline(this.outlines ? MapStyle.DEFAULT.outline() : 0)
			.withRoundedFog(this.roundedFog);
	}

	public void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling("config.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Diorama.LOGGER.error("Couldn't save settings to {}", file, e);
		}
		this.apply();
	}

	/** Pushes the settings that live elsewhere: the map look and fog of war. */
	public void apply() {
		// The map session re-bakes only if the tiles would look different.
		MapStyle.set(this.style());
		boolean fast = this.quality == Quality.FAST;
		if (fast != TileBaker.fast()) {
			TileBaker.setFast(fast);
			// Bake what's on screen again at the new quality.
			MapSession session = MapSession.get();
			if (session != null) {
				session.map().colorsChanged();
			}
		}
		ServerRules.setAlwaysFogOfWar(this.alwaysFogOfWar);
	}

	private static Settings load() {
		Path file = file();
		Settings settings = null;
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				settings = GSON.fromJson(reader, Settings.class);
			} catch (Exception e) {
				Diorama.LOGGER.error("Couldn't read settings from {}; using defaults", file, e);
			}
		}
		boolean read = settings != null;
		if (settings == null) {
			settings = new Settings();
		}
		// GPU baking used to be off unless turned on, and every file saved since says so, whether
		// the player chose that or not. It turns on once; off from here on is the player's choice.
		boolean moved = settings.defaultsMoved < 1;
		if (moved) {
			settings.gpuBaking = true;
			settings.defaultsMoved = 1;
		}
		if (settings.spawnSpots == null) {
			settings.spawnSpots = SpawnSpots.OFF;
		}
		if (settings.minimapCorner == null) {
			settings.minimapCorner = Corner.TOP_RIGHT;
		}
		settings.minimapOffsetX = Math.max(MinimapHud.MARGIN, settings.minimapOffsetX);
		settings.minimapOffsetY = Math.max(MinimapHud.MARGIN, settings.minimapOffsetY);
		if (settings.dayNight == null) {
			settings.dayNight = DayNight.FOLLOW;
		}
		if (settings.gameTime == null) {
			settings.gameTime = GameTime.OFF;
		}
		if (settings.quality == null) {
			settings.quality = Quality.FANCY;
		}
		if (settings.minimapFrame == null) {
			settings.minimapFrame = Frame.CLASSIC;
		}
		if (settings.minimapShape == null) {
			settings.minimapShape = Shape.SQUARE;
		}
		if (settings.radarStyle == null) {
			settings.radarStyle = RadarStyle.HEADS;
		}
		settings.radarIconScale = snapScale(settings.radarIconScale);
		if (settings.playerNames == null) {
			settings.playerNames = NameTags.FULLSCREEN;
		}
		if (settings.webMap == null) {
			settings.webMap = WebMap.OFF;
		}
		// Ports under 1024 need rights the game doesn't have; a few above it are tried when it's taken.
		if (settings.webMapPort < 1024 || settings.webMapPort > 65500) {
			settings.webMapPort = DEFAULT_WEB_MAP_PORT;
		}
		if (settings.claims == null) {
			settings.claims = ClaimsShown.ALWAYS;
		}
		if (settings.drawings == null) {
			settings.drawings = DrawingsShown.ALWAYS;
		}
		if (settings.worldBorder == null) {
			settings.worldBorder = WorldBorderShown.ALWAYS;
		}
		if (settings.waypointLabels == null) {
			settings.waypointLabels = WaypointLabels.AUTO;
		}
		if (settings.waypointBeams == null) {
			settings.waypointBeams = Beams.TEMPORARY;
		}
		RadarCategories.loaded(settings);
		settings.teleportCommand = cleanCommand(settings.teleportCommand);
		// Hand-edited IDs: trimmed, lower case, and Minecraft's own if they name no mod.
		java.util.Set<String> off = new java.util.TreeSet<>();
		if (settings.mapOffDimensions != null) {
			for (String dimension : settings.mapOffDimensions) {
				if (dimension != null) {
					off.addAll(dev.diorama.share.ServerLocks.dimensions(dimension));
				}
			}
		}
		settings.mapOffDimensions = off;
		settings.arrowColor = Math.floorMod(settings.arrowColor, ARROW_COLORS.length);
		// Hand-edited numbers: snap to a value the settings screen can show.
		if (settings.minimapZoom != null) {
			settings.zoomStep(STEPS_BEFORE_OLD_ZOOMS + Math.max(0, Math.min(OLD_ZOOMS - 1, settings.minimapZoom)));
			settings.minimapZoom = null;
		}
		settings.zoomStep(settings.zoomStep());
		settings.minimapSize = snapSize(settings.minimapSize);
		settings.minimapOpacity = snapOpacity(settings.minimapOpacity);
		settings.minimapEnlarged = snapEnlarged(settings.minimapEnlarged);
		settings.radarRange = nearest(RANGES, settings.radarRange);
		settings.waypointDistance = nearest(WAYPOINT_DISTANCES, settings.waypointDistance);
		settings.minimapTextSize = clampTextSize(settings.minimapTextSize);
		settings.waypointMinimapSize = clampWaypointSize(settings.waypointMinimapSize);
		settings.waypointMapSize = clampWaypointSize(settings.waypointMapSize);
		settings.waypointWorldSize = clampWaypointSize(settings.waypointWorldSize);
		settings.waypointWorldTextSize = clampTextSize(settings.waypointWorldTextSize);
		settings.apply();
		// Written now, so the file says it moved even if the player never changes a setting. One that
		// couldn't be read stays as it is, for the player to mend.
		if (moved && read) {
			settings.save();
		}
		return settings;
	}

	/**
	 * A typed teleport command without its slash, or the default for a blank one. Runs of spaces
	 * become one, as chat does before sending a command: the server's parser wants just one.
	 */
	public static String cleanCommand(@Nullable String command) {
		String trimmed = command == null ? "" : command.trim().replaceAll("\\s+", " ");
		while (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1).trim();
		}
		return trimmed.isEmpty() ? DEFAULT_TELEPORT_COMMAND : trimmed;
	}

	/** The value in {@code steps} nearest to {@code value}. */
	private static int nearest(int[] steps, int value) {
		int nearest = steps[0];
		for (int step : steps) {
			if (Math.abs((long) step - value) < Math.abs((long) nearest - value)) {
				nearest = step;
			}
		}
		return nearest;
	}

	/** The nearest size the settings screen can show. */
	public static int snapSize(int size) {
		int clamped = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
		return MIN_SIZE + Math.round((clamped - MIN_SIZE) / (float) SIZE_STEP) * SIZE_STEP;
	}

	/** The nearest enlarged size the settings screen can show. */
	public static int snapEnlarged(int percent) {
		int clamped = Math.max(MIN_ENLARGED, Math.min(MAX_ENLARGED, percent));
		return MIN_ENLARGED + Math.round((clamped - MIN_ENLARGED) / (float) ENLARGED_STEP) * ENLARGED_STEP;
	}

	/** The nearest opacity the settings screen can show. */
	public static int snapOpacity(int opacity) {
		int clamped = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, opacity));
		return MIN_OPACITY + Math.round((clamped - MIN_OPACITY) / (float) OPACITY_STEP) * OPACITY_STEP;
	}

	/** Reads the minimap size as a number, or as the Small, Medium and Large it was before. */
	public static final class SizeAdapter extends TypeAdapter<Integer> {
		@Override
		public void write(JsonWriter out, Integer value) throws IOException {
			out.value(value);
		}

		@Override
		public Integer read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NUMBER) {
				return in.nextInt();
			}
			String name = in.nextString();
			return switch (name) {
				case "SMALL" -> 96;
				case "LARGE" -> 176;
				default -> 128;
			};
		}
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve("config.json");
	}
}
