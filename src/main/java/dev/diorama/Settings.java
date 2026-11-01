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
	public static final double[] ZOOMS = {0.75, 1, 1.5, 2, 3, 4};
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
	public Corner minimapCorner = Corner.TOP_RIGHT;
	/** The minimap's size while the enlarge key is held, in percent of {@link #minimapSize}. */
	public int minimapEnlarged = 200;
	public int minimapZoom = 3;
	/** How opaque the minimap's map is, in percent. Files from before this setting leave it at 100. */
	public int minimapOpacity = MAX_OPACITY;
	public boolean compass = true;
	public boolean coordinates = true;
	public boolean biome = true;
	public GameTime gameTime = GameTime.OFF;
	public boolean autoCaves = true;
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
	public NameTags playerNames = NameTags.FULLSCREEN;
	/** Index into {@link #ARROW_COLORS}. */
	public int arrowColor = 0;

	public boolean waypointsOnMap = true;
	public boolean waypointsInWorld = true;
	public boolean deathMarkers = true;
	/** A pin on the bed the player last slept in ({@link dev.diorama.waypoint.Waypoints#watchSleep}). */
	public boolean bedMarkers = true;
	public WaypointLabels waypointLabels = WaypointLabels.AUTO;
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
	public int waypointMinimapSize = 60;
	public int waypointMapSize = 60;
	public int waypointWorldSize = 60;
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
	/** Where FTB Chunks' land claims show, when it's installed. */
	public ClaimsShown claims = ClaimsShown.ALWAYS;

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
		return ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, this.minimapZoom))];
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
		if (settings.claims == null) {
			settings.claims = ClaimsShown.ALWAYS;
		}
		if (settings.drawings == null) {
			settings.drawings = DrawingsShown.ALWAYS;
		}
		if (settings.waypointLabels == null) {
			settings.waypointLabels = WaypointLabels.AUTO;
		}
		settings.teleportCommand = cleanCommand(settings.teleportCommand);
		settings.arrowColor = Math.floorMod(settings.arrowColor, ARROW_COLORS.length);
		// Hand-edited numbers: snap to a value the settings screen can show.
		settings.minimapZoom = Math.max(0, Math.min(ZOOMS.length - 1, settings.minimapZoom));
		settings.minimapSize = snapSize(settings.minimapSize);
		settings.minimapOpacity = snapOpacity(settings.minimapOpacity);
		settings.minimapEnlarged = snapEnlarged(settings.minimapEnlarged);
		settings.radarRange = nearest(RANGES, settings.radarRange);
		settings.waypointDistance = nearest(WAYPOINT_DISTANCES, settings.waypointDistance);
		settings.waypointMinimapSize = clampWaypointSize(settings.waypointMinimapSize);
		settings.waypointMapSize = clampWaypointSize(settings.waypointMapSize);
		settings.waypointWorldSize = clampWaypointSize(settings.waypointWorldSize);
		settings.apply();
		// Written now, so the file says it moved even if the player never changes a setting.
		if (moved && Files.isRegularFile(file)) {
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
