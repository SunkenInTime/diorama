package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.addon.AddonLayers;
import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.api.MapLine;
import dev.diorama.api.Marker;
import dev.diorama.api.ResourceId;
import dev.diorama.render.AddonOverlay;
import dev.diorama.render.MapViewport;
import dev.diorama.render.MarkerAtlas;
import dev.diorama.render.Radar;
import dev.diorama.render.WaypointIcons;
import dev.diorama.testaddon.TestAddon;
import dev.diorama.testaddon.broken.BrokenAddon;
import dev.diorama.testaddon.markers.MapMarkers;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Markers and lines addons put on Diorama's maps ({@code addon-api}, in the reopened world, after
 * {@link AddonApiTest}, then in a new world): through the test addon, which only knows the API.
 * Each check says what it saw when it fails. What was drawn is read from what the overlay handed
 * the GUI (the quads' corners and colours), and from screenshots.
 * <ul>
 *   <li>Limits are refused while the content is built, in the addon's own code, and so are bad
 *       values: coordinates past where the game stops a player, icon paths with empty parts or
 *       {@code ..}; labels lose formatting codes. Layers are limited per addon, and a name can't
 *       be open twice.</li>
 *   <li>Calls from another thread throw.</li>
 *   <li>Positions: a marker at a block's middle lands where Diorama puts a waypoint's point for
 *       that block, and a marker at a mob lands where the radar puts the mob, to a hundredth of a
 *       pixel, on the minimap facing north, on the minimap turned with the player, and on the full
 *       map; a quad of the line's colour covers the point half way along it; and the screenshot
 *       has the marker's colour there, and the line's.</li>
 *   <li>A marker in the Nether isn't drawn on the Overworld's maps; the full map showing the
 *       Nether draws it, and none of the Overworld's.</li>
 *   <li>Replacing moves what's drawn; the Addons tab switch takes it all off and puts it back
 *       with the layer still open; closing takes it off for good; the broken addon's layer was
 *       closed when it failed and never drawn.</li>
 *   <li>The round minimap: a line through it with both ends far outside is drawn across it and
 *       cut to its circle; at GUI scale 1, small icons near the rim are drawn only where their
 *       whole 11-pixel tile is inside the circle.</li>
 *   <li>Coordinates at the ends of the world give corners near the view and no NaN or infinity,
 *       on both maps.</li>
 *   <li>The budget: a full layer of lines fills a map's pieces, and the line after it isn't
 *       drawn; 8,192 markers are, and the one after isn't; the same each frame.</li>
 *   <li>Icons: one on screen through F3+T comes back as an icon; 300 different icons shown over
 *       time all get a tile though the texture holds 210; a thousand missing IDs leave the caches
 *       bounded.</li>
 *   <li>The test addon's own example ({@link MapMarkers}): a few hundred carts replaced every
 *       tick, icons with labels (one from its own jar, one missing), and roads, on the minimap and
 *       the full map, in screenshots.</li>
 *   <li>A new world ({@link #afterReopened}): leaving empties every layer; in another world the
 *       old content isn't drawn, and the example, which follows the world, shows its places and
 *       roads again.</li>
 * </ul>
 * Everything it opens it closes; the settings it changes it puts back. Bytes a frame are measured
 * in the new world ({@link MarkersMeasure}), where killing every mob costs no later section.
 */
final class AddonMarkersTest {
	private static final String MOD = "diorama_test_addon";
	private static final String BROKEN = "diorama_test_addon_broken";
	/** The probe layer's colours: none of them a block's. */
	private static final int SPOT_RGB = 0xFFFF00;
	private static final int MOB_RGB = 0x00FFFF;
	private static final int LINE_RGB = 0xFF8000;
	private static final int NETHER_RGB = 0x8000FF;
	private static final int ROUND_RGB = 0x00FF80;
	private static final int SIZE = 6;
	/** How far a marker may be from where Diorama puts its own, in GUI pixels. */
	private static final float CLOSE = 0.01F;

	/** Kept open from the reopened world into the next, for {@link #afterReopened}. */
	private static MapLayer worldChange;
	private static MapMarkers example;
	private static Scene lastScene;

	private AddonMarkersTest() {
	}

	/** One view's frame as drawn: markers (x, y, tile) by layer and index, lines that gave quads, and the quads themselves. */
	record Frame(Map<String, float[]> markers, List<String> lines, float[] lineXyz, int[] lineColors, int lineQuads,
		float[] markerXyz, int[] markerColors, int markerQuads, MapViewport.View view) {
	}

	/** Each frame's markers and lines, as AddonOverlay placed them, for the minimap [0] and the full map [1]. */
	static final class Recorder implements AddonOverlay.Probe {
		final List<Map<String, float[]>> markers = List.of(new HashMap<>(), new HashMap<>());
		final List<List<String>> lines = List.of(new ArrayList<>(), new ArrayList<>());
		final MapViewport.View[] views = new MapViewport.View[2];
		final float[][] lineXyz = {new float[0], new float[0]};
		final int[][] lineColors = {new int[0], new int[0]};
		final int[] lineQuads = new int[2];
		final float[][] markerXyz = {new float[0], new float[0]};
		final int[][] markerColors = {new int[0], new int[0]};
		final int[] markerQuads = new int[2];
		final int[] frames = new int[2];
		/** Anything of the broken addon's, ever. */
		boolean brokenSeen;

		@Override
		public void frame(boolean fullscreen) {
			int i = fullscreen ? 1 : 0;
			this.markers.get(i).clear();
			this.lines.get(i).clear();
			this.lineQuads[i] = 0;
			this.markerQuads[i] = 0;
			this.frames[i]++;
		}

		@Override
		public void view(boolean fullscreen, MapViewport.View view) {
			this.views[fullscreen ? 1 : 0] = view;
		}

		@Override
		public void marker(boolean fullscreen, AddonLayers.Layer layer, int index, float x, float y, int tile) {
			this.brokenSeen |= layer.modId().equals(BROKEN);
			this.markers.get(fullscreen ? 1 : 0).put(layer.modId() + "/" + layer.layerName() + "/" + index, new float[] {x, y, tile});
		}

		@Override
		public void line(boolean fullscreen, AddonLayers.Layer layer, int index) {
			this.brokenSeen |= layer.modId().equals(BROKEN);
			this.lines.get(fullscreen ? 1 : 0).add(layer.modId() + "/" + layer.layerName() + "/" + index);
		}

		@Override
		public void drawn(boolean fullscreen, boolean lines, float[] xyz, int[] colors, int quads) {
			int i = fullscreen ? 1 : 0;
			if (lines) {
				this.lineXyz[i] = Arrays.copyOf(xyz, quads * 12);
				this.lineColors[i] = Arrays.copyOf(colors, quads);
				this.lineQuads[i] = quads;
			} else {
				this.markerXyz[i] = Arrays.copyOf(xyz, quads * 12);
				this.markerColors[i] = Arrays.copyOf(colors, quads);
				this.markerQuads[i] = quads;
			}
		}
	}

	/** Where the probe layer's things are, worked out once the player stands still. */
	record Scene(DimensionId overworld, double spotX, double spotY, double spotZ, int blockX, int blockY, int blockZ,
		double mobX, double mobY, double mobZ, int mobId, double netherX, double netherZ, double playerX, double playerY, double playerZ) {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		List<String> wrong = new ArrayList<>();
		Recorder recorder = new Recorder();
		boolean lockNorth = context.computeOnClient(mc -> Settings.get().minimapLockNorth);
		boolean passive = context.computeOnClient(mc -> Settings.get().radarPassive);
		Settings.Shape shape = context.computeOnClient(mc -> Settings.get().minimapShape);
		List<MapLayer> opened = new ArrayList<>();
		context.runOnClient(mc -> {
			Settings.get().radarPassive = true;
			Settings.get().minimapLockNorth = true;
			Settings.get().minimapShape = Settings.Shape.SQUARE;
			AddonOverlay.probe = recorder;
		});
		try {
			world.getServer().runCommand("time set noon");
			context.setScreen(() -> null);
			context.waitFor(mc -> mc.player.onGround(), 600);
			BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
			world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 0");
			// A pig that stands still at a place that isn't a block's middle, for the radar to place.
			world.getServer().runCommand("summon pig " + (at.getX() + 7.3) + " " + at.getY() + " " + (at.getZ() - 10.7)
				+ " {NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,Tags:[\"diorama_marker_probe\"]}");
			context.waitTicks(40);
			Scene scene = scene(context, at);
			lastScene = scene;

			limits(context, wrong);
			threads(context, wrong);
			failed(context, wrong);

			MapLayer probe = context.computeOnClient(mc -> api().openLayer("probe"));
			opened.add(probe);
			context.runOnClient(mc -> probe.replace(probeContent(scene, 0)));
			context.waitTicks(3);
			positions(context, scene, false, "facing north", "addon-markers-01-minimap", wrong);

			// The minimap turns with the player.
			context.runOnClient(mc -> Settings.get().minimapLockNorth = false);
			world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 210 0");
			context.waitTicks(5);
			positions(context, scene, false, "turned with the player", "addon-markers-02-minimap-turned", wrong);
			context.runOnClient(mc -> Settings.get().minimapLockNorth = true);
			world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 0");

			openMap(context);
			positions(context, scene, true, "on the full map", "addon-markers-03-map", wrong);
			nether(context, scene, wrong);
			context.setScreen(() -> null);
			context.waitTicks(3);

			replaced(context, scene, probe, wrong);
			switchedOff(context, scene, probe, wrong);
			iconThroughReload(context, scene, probe, wrong);
			closed(context, scene, probe, wrong);

			roundLine(context, scene, wrong);
			rimIcons(context, scene, wrong);
			extremes(context, scene, wrong);
			budget(context, scene, wrong);
			manyIcons(context, scene, wrong);

			showcase(context, scene, opened, wrong);
			// Kept open into the next world, where afterReopened checks the change emptied them.
			MapLayer kept = context.computeOnClient(mc -> api().openLayer("world-change"));
			context.runOnClient(mc -> kept.replace(LayerContent.builder()
				.add(Marker.builder(scene.overworld(), scene.playerX(), scene.playerY(), scene.playerZ()).rgb(SPOT_RGB).size(SIZE).build())
				.add(MapLine.builder(scene.overworld()).point(scene.playerX() - 20, scene.playerY(), scene.playerZ()).point(scene.playerX() + 20, scene.playerY(), scene.playerZ()).rgb(LINE_RGB).build())
				.build()));
			worldChange = kept;
			Frame before = snapshot(context, recorder, false);
			if (!before.markers().containsKey(MOD + "/world-change/0")) {
				wrong.add("The layer kept for the next world isn't drawn in this one: " + before.markers().keySet());
			}
		} finally {
			context.runOnClient(mc -> {
				for (MapLayer layer : opened) {
					if (example == null || layer != example.carts() && layer != example.places() && layer != example.roads()) {
						layer.close();
					}
				}
				AddonOverlay.probe = null;
				Settings.get().minimapLockNorth = lockNorth;
				Settings.get().radarPassive = passive;
				Settings.get().minimapShape = shape;
			});
			context.setScreen(() -> null);
			world.getServer().runCommand("kill @e[type=pig,tag=diorama_marker_probe]");
		}
		if (recorder.brokenSeen) {
			wrong.add("Something of the broken addon's layer was drawn");
		}
		int left = context.computeOnClient(mc -> AddonLayers.open().size());
		// The example's three layers and the world-change one stay open for afterReopened.
		if (left != 4) {
			wrong.add(left + " layers still open after the section closed its own; 4 are kept for the next world");
		}
		if (!wrong.isEmpty()) {
			context.runOnClient(mc -> closeKept());
			throw new AssertionError("Addon markers and lines:\n  " + String.join("\n  ", wrong));
		}
		Diorama.LOGGER.info("addon-api markers: every check in the reopened world passed");
	}

	private static void closeKept() {
		if (worldChange != null) {
			worldChange.close();
			worldChange = null;
		}
		if (example != null) {
			example.close();
			example = null;
		}
	}

	/**
	 * After the reopened world is left: every layer was emptied; in a new world, at the place the
	 * old content was, none of it is drawn, and the example, which watches the world, shows its
	 * places and roads again. Then, on 26.x Fabric, the bytes a frame ({@link MarkersMeasure}),
	 * in this world of its own.
	 */
	static void afterReopened(ClientGameTestContext context) {
		List<String> wrong = new ArrayList<>();
		Recorder recorder = new Recorder();
		Scene scene = lastScene;
		if (worldChange == null || example == null || scene == null) {
			throw new AssertionError("Addon markers: the layers kept from the reopened world are missing; did run() pass?");
		}
		try {
			context.waitTicks(5);
			List<String> full = context.computeOnClient(mc -> {
				List<String> held = new ArrayList<>();
				for (AddonLayers.Layer layer : AddonLayers.open()) {
					LayerContent content = layer.content();
					if (!content.markers().isEmpty() || !content.lines().isEmpty()) {
						held.add(layer.modId() + "/" + layer.layerName() + " " + content);
					}
				}
				return held;
			});
			if (!full.isEmpty()) {
				wrong.add("At the title screen after leaving the world, layers still hold content: " + full);
			}
			try (TestSingleplayerContext other = context.worldBuilder().create()) {
				context.runOnClient(mc -> AddonOverlay.probe = recorder);
				other.getServer().runCommand("time set noon");
				context.waitFor(mc -> mc.player != null && mc.player.onGround(), 1200);
				BlockPos spawn = context.computeOnClient(mc -> mc.player.blockPosition());
				// The new world's ground at the place the old content was.
				other.getServer().runCommand("spreadplayers " + scene.playerX() + " " + scene.playerZ() + " 0 1 false @a");
				context.waitFor(mc -> mc.player != null && mc.player.onGround(), 1200);
				context.waitTicks(20);
				// The example sees the new world on its next tick, as a real addon would.
				for (int i = 0; i < 3; i++) {
					context.runOnClient(mc -> example.tick());
					context.waitTicks(1);
				}
				Frame minimap = snapshot(context, recorder, false);
				boolean oldDrawn = minimap.markers().keySet().stream().anyMatch(key -> key.startsWith(MOD + "/world-change/"))
					|| minimap.lines().stream().anyMatch(key -> key.startsWith(MOD + "/world-change/"));
				boolean oldHeld = context.computeOnClient(mc -> !((AddonLayers.Layer) worldChange).content().markers().isEmpty());
				openMap(context);
				Frame map = snapshot(context, recorder, true);
				context.takeScreenshot("addon-markers-13-new-world-map");
				context.setScreen(() -> null);
				long places = map.markers().keySet().stream().filter(key -> key.startsWith(MOD + "/places/")).count();
				long roads = map.lines().stream().filter(key -> key.startsWith(MOD + "/roads/")).count();
				if (oldDrawn || oldHeld || places != 6 || roads != 3) {
					wrong.add("In a new world at the same place: the old world's marker and line " + (oldDrawn ? "drawn" : "not drawn") + ", its layer "
						+ (oldHeld ? "still holds them" : "empty") + "; the example, handed over again, shows " + places + " of 6 places and " + roads + " of 3 roads");
				} else {
					Diorama.LOGGER.info("addon-api markers: a new world: every layer emptied on leaving, the old content not drawn, the example's {} places and {} roads shown again", places, roads);
				}
				context.runOnClient(mc -> {
					AddonOverlay.probe = null;
					closeKept();
				});
				//? if >=26.1 && fabric {
				// Back where the world started the player, as the run measured against does.
				other.getServer().runCommand("tp @a " + (spawn.getX() + 0.5) + " " + spawn.getY() + " " + (spawn.getZ() + 0.5));
				context.waitFor(mc -> mc.player.onGround(), 1200);
				context.waitTicks(20);
				MarkersMeasure.run(context, other);
				//?}
			}
		} finally {
			context.runOnClient(mc -> {
				AddonOverlay.probe = null;
				closeKept();
			});
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("Addon markers and lines in a new world:\n  " + String.join("\n  ", wrong));
		}
	}

	private static DioramaApi api() {
		return TestAddon.api();
	}

	private static Scene scene(ClientGameTestContext context, BlockPos at) {
		return context.computeOnClient(mc -> {
			Entity pig = null;
			double best = 0.5;
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (!EntityType.getKey(entity.getType()).getPath().equals("pig")) {
					continue;
				}
				double d = Math.hypot(entity.getX() - (at.getX() + 7.3), entity.getZ() - (at.getZ() - 10.7));
				if (d < best) {
					best = d;
					pig = entity;
				}
			}
			if (pig == null) {
				throw new AssertionError("The pig the test summoned isn't on the client");
			}
			int bx = at.getX() - 6;
			int bz = at.getZ() - 12;
			return new Scene(DimensionId.OVERWORLD, bx + 0.5, at.getY(), bz + 0.5, bx, at.getY(), bz,
				pig.getX(), pig.getY(), pig.getZ(), pig.getId(), (at.getX() + 0.5) / 8, (at.getZ() + 0.5) / 8,
				at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
		});
	}

	/**
	 * The probe layer: a line from a block's middle to the pig, then markers at the block's middle
	 * (0) and at the pig (1), one in the Nether at the same place as the block's (2), which the
	 * Overworld's maps mustn't draw, and one in the Nether where the full map showing the Nether
	 * looks (3).
	 */
	private static LayerContent probeContent(Scene scene, double eastBy) {
		return LayerContent.builder()
			.add(MapLine.builder(scene.overworld()).point(scene.spotX() + eastBy, scene.spotY(), scene.spotZ()).point(scene.mobX(), scene.mobY(), scene.mobZ())
				.rgb(LINE_RGB).width(3).build())
			.add(Marker.builder(scene.overworld(), scene.spotX() + eastBy, scene.spotY(), scene.spotZ()).rgb(SPOT_RGB).size(SIZE).build())
			.add(Marker.builder(scene.overworld(), scene.mobX(), scene.mobY(), scene.mobZ()).rgb(MOB_RGB).size(SIZE).build())
			.add(Marker.builder(DimensionId.NETHER, scene.spotX() + eastBy, scene.spotY(), scene.spotZ()).rgb(NETHER_RGB).size(SIZE).build())
			.add(Marker.builder(DimensionId.NETHER, scene.netherX(), 70, scene.netherZ()).rgb(NETHER_RGB).size(SIZE).build())
			.build();
	}

	/** Opens the full map with the cursor on its bottom bar, where no tooltip covers the map. */
	static void openMap(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		double[] bar = context.computeOnClient(mc -> new double[] {mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() - 5});
		context.getInput().setCursorPos(bar[0], bar[1]);
		context.waitTicks(10);
	}

	/** Waits for a frame of the view drawn after now, and returns a copy of its markers. */
	private static Map<String, float[]> frame(ClientGameTestContext context, Recorder recorder, boolean fullscreen) {
		return snapshot(context, recorder, fullscreen).markers();
	}

	/** Waits for a frame of the view drawn after now, and returns a copy of all of it. */
	private static Frame snapshot(ClientGameTestContext context, Recorder recorder, boolean fullscreen) {
		return snapshot(context, recorder, fullscreen, 2);
	}

	/** Waits until the view has drawn {@code frames} frames from now, and returns a copy of the last. */
	private static Frame snapshot(ClientGameTestContext context, Recorder recorder, boolean fullscreen, int frames) {
		int i = fullscreen ? 1 : 0;
		int from = context.computeOnClient(mc -> recorder.frames[i]);
		context.waitFor(mc -> recorder.frames[i] >= from + frames, 600);
		return context.computeOnClient(mc -> new Frame(new HashMap<>(recorder.markers.get(i)), new ArrayList<>(recorder.lines.get(i)),
			recorder.lineXyz[i], recorder.lineColors[i], recorder.lineQuads[i], recorder.markerXyz[i], recorder.markerColors[i], recorder.markerQuads[i], recorder.views[i]));
	}

	private static Recorder recorder() {
		return (Recorder) AddonOverlay.probe;
	}

	/** Whether a quad of colour {@code argb} (any, for 0) among the first {@code quads} covers (x, y). */
	static boolean covered(float[] xyz, int[] colors, int quads, double x, double y, int argb) {
		for (int q = 0; q < quads; q++) {
			if (argb != 0 && colors[q] != argb) {
				continue;
			}
			int p = q * 12;
			if (inTriangle(x, y, xyz[p], xyz[p + 1], xyz[p + 3], xyz[p + 4], xyz[p + 6], xyz[p + 7])
				|| inTriangle(x, y, xyz[p], xyz[p + 1], xyz[p + 6], xyz[p + 7], xyz[p + 9], xyz[p + 10])) {
				return true;
			}
		}
		return false;
	}

	private static boolean inTriangle(double x, double y, double ax, double ay, double bx, double by, double cx, double cy) {
		double d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by);
		double d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy);
		double d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay);
		boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
		boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
		return !(negative && positive);
	}

	/**
	 * The probe layer's markers on one view are where Diorama puts its own: the block's middle
	 * where a waypoint on that block has its point, the pig's marker where the radar draws the
	 * pig; the Nether's isn't drawn; a quad of the line's colour that was drawn covers the point
	 * half way between them; and the screenshot shows each colour there.
	 */
	private static void positions(ClientGameTestContext context, Scene scene, boolean fullscreen, String where, String shot, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		Frame drawn = snapshot(context, recorder, fullscreen);
		Map<String, float[]> placed = drawn.markers();
		float[][] expected = context.computeOnClient(mc -> {
			MapViewport.View view = recorder.views[fullscreen ? 1 : 0];
			Waypoint pin = new Waypoint("Probe", scene.blockX(), scene.blockY(), scene.blockZ(), "minecraft:overworld", 0xFF000000 | SPOT_RGB);
			List<WaypointOverlay.Pin> pins = WaypointOverlay.layout(view, List.of(pin), fullscreen);
			float[] spot = pins.isEmpty() || pins.get(0).clamped() ? null : new float[] {pins.get(0).x(), pins.get(0).y()};
			float[] mob = null;
			for (Radar.Blip blip : Radar.collect(view, 1, fullscreen)) {
				if (blip.entity().getId() == scene.mobId()) {
					mob = new float[] {blip.x(), blip.y()};
				}
			}
			return new float[][] {spot, mob};
		});
		if (expected[0] == null || expected[1] == null) {
			wrong.add(where + ": Diorama didn't place its own waypoint (" + (expected[0] != null) + ") or the pig (" + (expected[1] != null) + ") in the view to compare with");
			return;
		}
		float[] spot = placed.get(MOD + "/probe/0");
		float[] mob = placed.get(MOD + "/probe/1");
		boolean nether = placed.containsKey(MOD + "/probe/2") || placed.containsKey(MOD + "/probe/3");
		boolean spotRight = spot != null && Math.abs(spot[0] - expected[0][0]) <= CLOSE && Math.abs(spot[1] - expected[0][1]) <= CLOSE;
		boolean mobRight = mob != null && Math.abs(mob[0] - expected[1][0]) <= CLOSE && Math.abs(mob[1] - expected[1][1]) <= CLOSE;
		if (!spotRight || !mobRight || nether) {
			wrong.add(where + ": the block's marker at " + text(spot) + ", its waypoint's point at " + text(expected[0]) + "; the pig's marker at " + text(mob)
				+ ", the radar's pig at " + text(expected[1]) + "; the Nether's markers " + (nether ? "drawn" : "not drawn"));
			return;
		}
		double midX = (spot[0] + mob[0]) / 2.0;
		double midY = (spot[1] + mob[1]) / 2.0;
		if (!covered(drawn.lineXyz(), drawn.lineColors(), drawn.lineQuads(), midX, midY, 0xFF000000 | LINE_RGB)) {
			wrong.add(where + ": no quad of the line's colour that was drawn covers the point half way between its ends " + text(new float[] {(float) midX, (float) midY})
				+ "; " + drawn.lineQuads() + " line quads drawn");
			return;
		}
		BufferedImage image = read(context.takeScreenshot(shot));
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		boolean spotSeen = near(image, spot[0] * scale, spot[1] * scale, 1, SPOT_RGB);
		boolean mobSeen = near(image, mob[0] * scale, mob[1] * scale, 1, MOB_RGB);
		boolean lineSeen = near(image, midX * scale, midY * scale, 1, LINE_RGB);
		if (!spotSeen || !mobSeen || !lineSeen) {
			wrong.add(where + ": in " + shot + ".png, the block's marker's colour " + (spotSeen ? "is" : "isn't") + " at (" + Math.round(spot[0] * scale) + ", " + Math.round(spot[1] * scale)
				+ "), the pig's " + (mobSeen ? "is" : "isn't") + " at (" + Math.round(mob[0] * scale) + ", " + Math.round(mob[1] * scale) + "), the line's " + (lineSeen ? "is" : "isn't") + " half way");
			return;
		}
		Diorama.LOGGER.info("addon-api markers: {}: block marker at {} = waypoint point {}, pig marker at {} = radar {}; the line's quads cover its middle; colours seen in {}",
			where, text(spot), text(expected[0]), text(mob), text(expected[1]), shot);
	}

	/** The full map showing the Nether from the Overworld draws the Nether's marker, and none of the Overworld's. */
	private static void nether(ClientGameTestContext context, Scene scene, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		otherDimension(context, "minecraft:the_nether");
		context.waitTicks(5);
		Map<String, float[]> placed = frame(context, recorder, true);
		context.takeScreenshot("addon-markers-04-map-nether");
		otherDimension(context, null);
		context.waitTicks(3);
		if (!placed.containsKey(MOD + "/probe/3") || placed.containsKey(MOD + "/probe/0") || placed.containsKey(MOD + "/probe/1")) {
			wrong.add("The full map showing the Nether drew " + placed.keySet() + "; expected the Nether's marker " + MOD + "/probe/3 and none of the Overworld's (0, 1)");
			return;
		}
		Diorama.LOGGER.info("addon-api markers: the full map showing the Nether drew its marker at {}, and none of the Overworld's", text(placed.get(MOD + "/probe/3")));
	}

	private static void otherDimension(ClientGameTestContext context, String show) {
		context.runOnClient(mc -> {
			try {
				java.lang.reflect.Field field = MapScreen.class.getDeclaredField("otherDimension");
				field.setAccessible(true);
				field.set(mc.gui.screen(), show);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	/** Replacing the content moves the block's marker three blocks east, where a waypoint three blocks east has its point. */
	private static void replaced(ClientGameTestContext context, Scene scene, MapLayer probe, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		Map<String, float[]> before = frame(context, recorder, false);
		context.runOnClient(mc -> probe.replace(probeContent(scene, 3)));
		Map<String, float[]> after = frame(context, recorder, false);
		float[] expected = context.computeOnClient(mc -> {
			Waypoint pin = new Waypoint("Probe", scene.blockX() + 3, scene.blockY(), scene.blockZ(), "minecraft:overworld", 0xFF000000 | SPOT_RGB);
			List<WaypointOverlay.Pin> pins = WaypointOverlay.layout(recorder.views[0], List.of(pin), false);
			return pins.isEmpty() ? null : new float[] {pins.get(0).x(), pins.get(0).y()};
		});
		float[] moved = after.get(MOD + "/probe/0");
		float[] was = before.get(MOD + "/probe/0");
		if (expected == null || moved == null || was == null || Math.abs(moved[0] - expected[0]) > CLOSE || Math.abs(moved[1] - expected[1]) > CLOSE
			|| Math.abs(moved[0] - was[0]) < 1) {
			wrong.add("Replaced with the block's marker three blocks east: drawn at " + text(moved) + " (was " + text(was) + "), a waypoint there has its point at " + text(expected));
			return;
		}
		context.runOnClient(mc -> probe.replace(probeContent(scene, 0)));
		Diorama.LOGGER.info("addon-api markers: replaced, the block's marker moved from {} to {}", text(was), text(moved));
	}

	/**
	 * Unticking the test addon in the Addons tab draws none of its markers or lines, with its layer
	 * still open; ticking it again draws them again. As the tab does it: the setting, then apply.
	 */
	private static void switchedOff(ClientGameTestContext context, Scene scene, MapLayer probe, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		context.runOnClient(mc -> {
			Settings.get().addonHidesOff.add(MOD);
			Settings.get().apply();
		});
		try {
			Map<String, float[]> off = frame(context, recorder, false);
			List<String> lines = context.computeOnClient(mc -> new ArrayList<>(recorder.lines.get(0)));
			boolean open = context.computeOnClient(mc -> probe.isOpen());
			BufferedImage image = read(context.takeScreenshot("addon-markers-05-switched-off"));
			double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
			float[] spot = context.computeOnClient(mc -> spotOnMinimap(scene));
			boolean seen = spot != null && near(image, spot[0] * scale, spot[1] * scale, 1, SPOT_RGB);
			if (!off.isEmpty() || !lines.isEmpty() || !open || seen) {
				wrong.add("With the test addon unticked in the Addons tab: drew markers " + off.keySet() + " and lines " + lines + ", layer " + (open ? "open" : "closed")
					+ ", the block's marker's colour " + (seen ? "on screen" : "not on screen"));
			}
		} finally {
			context.runOnClient(mc -> {
				Settings.get().addonHidesOff.remove(MOD);
				Settings.get().apply();
			});
		}
		Map<String, float[]> on = frame(context, recorder, false);
		if (!on.containsKey(MOD + "/probe/0")) {
			wrong.add("Ticked again in the Addons tab, the test addon's markers aren't drawn: " + on.keySet());
			return;
		}
		Diorama.LOGGER.info("addon-api markers: unticked in the Addons tab, nothing drawn with the layer open; ticked again, drawn again");
	}

	/** Where a waypoint on the probe block has its point on the minimap, or null. Client thread. */
	private static float[] spotOnMinimap(Scene scene) {
		MapViewport.View view = recorder().views[0];
		Waypoint pin = new Waypoint("Probe", scene.blockX(), scene.blockY(), scene.blockZ(), "minecraft:overworld", 0xFF000000 | SPOT_RGB);
		List<WaypointOverlay.Pin> pins = WaypointOverlay.layout(view, List.of(pin), false);
		return pins.isEmpty() ? null : new float[] {pins.get(0).x(), pins.get(0).y()};
	}

	/**
	 * An icon on screen through F3+T: drawn as an icon before, and as an icon again after the
	 * reload dropped the marker texture, read from what was drawn (the tile each marker got).
	 */
	private static void iconThroughReload(ClientGameTestContext context, Scene scene, MapLayer probe, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		context.runOnClient(mc -> probe.replace(LayerContent.builder()
			.add(Marker.builder(scene.overworld(), scene.spotX(), scene.spotY(), scene.spotZ()).icon(MapMarkers.OWN_ICON).build())
			.build()));
		float[] before = frame(context, recorder, false).get(MOD + "/probe/0");
		DioramaClientTest.reloadPacks(context, mc -> {
		});
		float[] after = frame(context, recorder, false).get(MOD + "/probe/0");
		context.takeScreenshot("addon-markers-06-icon-after-reload");
		context.runOnClient(mc -> probe.replace(probeContent(scene, 0)));
		if (before == null || after == null || before[2] < 0 || after[2] < 0) {
			wrong.add("An icon through a resource reload: before, " + tileText(before) + "; after, " + tileText(after));
			return;
		}
		Diorama.LOGGER.info("addon-api markers: the jar's icon drawn on tile {} before a resource reload and tile {} after", (int) before[2], (int) after[2]);
	}

	private static String tileText(float[] marker) {
		return marker == null ? "not drawn" : marker[2] < 0 ? "drawn as a dot" : "drawn on tile " + (int) marker[2];
	}

	/**
	 * Closing draws nothing of it from the next frame, replace() on it after does nothing, and its
	 * name can be opened again. An empty layer stays open meanwhile, so the maps go on drawing
	 * layers, and telling the test about it.
	 */
	private static void closed(ClientGameTestContext context, Scene scene, MapLayer probe, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		MapLayer empty = context.computeOnClient(mc -> api().openLayer("empty"));
		context.runOnClient(mc -> {
			probe.close();
			probe.close();
			probe.replace(probeContent(scene, 0));
		});
		Map<String, float[]> after;
		try {
			after = frame(context, recorder, false);
		} finally {
			context.runOnClient(mc -> empty.close());
		}
		List<String> lines = context.computeOnClient(mc -> new ArrayList<>(recorder.lines.get(0)));
		boolean open = context.computeOnClient(mc -> probe.isOpen());
		boolean listed = context.computeOnClient(mc -> AddonLayers.open().contains(probe));
		context.waitTicks(2);
		BufferedImage image = read(context.takeScreenshot("addon-markers-07-closed"));
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		float[] spot = context.computeOnClient(mc -> spotOnMinimap(scene));
		boolean seen = spot != null && near(image, spot[0] * scale, spot[1] * scale, 1, SPOT_RGB);
		if (!after.isEmpty() || !lines.isEmpty() || open || listed || seen) {
			wrong.add("After closing the probe layer (twice, then replace()): drew markers " + after.keySet() + " and lines " + lines + ", " + (open ? "open" : "closed")
				+ ", " + (listed ? "still" : "not") + " among the open layers, its colour " + (seen ? "on screen" : "not on screen"));
			return;
		}
		boolean again = context.computeOnClient(mc -> {
			MapLayer reopened = api().openLayer("probe");
			boolean ok = reopened.isOpen();
			reopened.close();
			return ok;
		});
		if (!again) {
			wrong.add("A layer named like one closed couldn't be opened again");
			return;
		}
		Diorama.LOGGER.info("addon-api markers: closed, nothing drawn, replace() after close did nothing, the name opened again");
	}

	/** The circle a round minimap view is cut to: its middle and radius. */
	private static double[] circle(MapViewport.View view) {
		return new double[] {view.x() + view.width() / 2.0, view.y() + view.height() / 2.0, Math.min(view.width(), view.height()) / 2.0};
	}

	/** How many GUI pixels one block east moves a marker on this view. */
	private static double pixelsPerBlock(MapViewport.View view, double y) {
		double du = Projection.u(1, 0) - Projection.u(0, 0);
		double dv = Projection.v(1, y, 0) - Projection.v(0, y, 0);
		return view.zoom() * Math.hypot(du, dv);
	}

	/**
	 * On the round minimap, a line through the middle with both ends some 2,000 blocks outside the
	 * square around the circle: its quads reach across the circle, and none has a corner outside it.
	 */
	private static void roundLine(ClientGameTestContext context, Scene scene, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		MapLayer layer = context.computeOnClient(mc -> api().openLayer("round-line"));
		context.runOnClient(mc -> {
			Settings.get().minimapShape = Settings.Shape.ROUND;
			layer.replace(LayerContent.builder()
				.add(MapLine.builder(scene.overworld()).point(scene.playerX() - 2000, scene.playerY(), scene.playerZ())
					.point(scene.playerX() + 2000, scene.playerY(), scene.playerZ()).rgb(ROUND_RGB).width(3).build())
				.build());
		});
		try {
			context.waitTicks(3);
			Frame drawn = snapshot(context, recorder, false);
			context.takeScreenshot("addon-markers-08-round-line");
			double[] c = circle(drawn.view());
			int core = 0xFF000000 | ROUND_RGB;
			double minX = Double.MAX_VALUE;
			double maxX = -Double.MAX_VALUE;
			double outside = 0;
			int quads = 0;
			for (int q = 0; q < drawn.lineQuads(); q++) {
				if (drawn.lineColors()[q] != core) {
					continue;
				}
				quads++;
				for (int k = 0; k < 4; k++) {
					double x = drawn.lineXyz()[q * 12 + k * 3];
					double y = drawn.lineXyz()[q * 12 + k * 3 + 1];
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					outside = Math.max(outside, Math.hypot(x - c[0], y - c[1]) - c[2]);
				}
			}
			if (quads == 0 || minX > c[0] - c[2] * 0.95 || maxX < c[0] + c[2] * 0.95 || outside > 0.05) {
				wrong.add("A line across the round minimap (circle at " + text(new float[] {(float) c[0], (float) c[1]}) + ", radius " + c[2] + ") with both ends 2,000 blocks out: "
					+ quads + " quads of its colour, from x " + minX + " to " + maxX + ", a corner up to " + outside + " pixels outside the circle");
				return;
			}
			Diorama.LOGGER.info("addon-api markers: a line across the round minimap with both ends far outside: {} quads from x {} to {}, none outside the circle", quads, minX, maxX);
		} finally {
			context.runOnClient(mc -> {
				layer.close();
				Settings.get().minimapShape = Settings.Shape.SQUARE;
			});
		}
	}

	/**
	 * At GUI scale 1, size-2 icons along two rays out of the round minimap (east, and the corner
	 * between south and east): each is an 11-pixel tile, so every corner drawn must be inside the
	 * circle, and the ones drawn must reach close to its edge.
	 */
	private static void rimIcons(ClientGameTestContext context, Scene scene, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		MapLayer layer = context.computeOnClient(mc -> api().openLayer("rim"));
		try {
			context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
			DioramaClientTest.setGuiScale(context, 1);
			context.waitTicks(3);
			Frame first = snapshot(context, recorder, false);
			double[] c = circle(first.view());
			double reach = c[2] / pixelsPerBlock(first.view(), scene.playerY());
			context.runOnClient(mc -> {
				LayerContent.Builder content = LayerContent.builder();
				for (int ray = 0; ray < 2; ray++) {
					double ax = ray == 0 ? 1 : Math.sqrt(0.5);
					double az = ray == 0 ? 0 : Math.sqrt(0.5);
					for (int k = 0; k <= 400; k++) {
						double d = reach * (0.6 + 0.6 * k / 400);
						content.add(Marker.builder(scene.overworld(), scene.playerX() + ax * d, scene.playerY(), scene.playerZ() + az * d)
							.icon(ResourceId.of("diorama:home")).size(Marker.MIN_SIZE).build());
					}
				}
				layer.replace(content.build());
			});
			Frame drawn = snapshot(context, recorder, false);
			context.takeScreenshot("addon-markers-09-rim-icons-gui-scale-1");
			double outside = 0;
			double furthest = 0;
			int icons = 0;
			for (int q = 0; q < drawn.markerQuads(); q++) {
				for (int k = 0; k < 4; k++) {
					double x = drawn.markerXyz()[q * 12 + k * 3];
					double y = drawn.markerXyz()[q * 12 + k * 3 + 1];
					double d = Math.hypot(x - c[0], y - c[1]);
					outside = Math.max(outside, d - c[2]);
					furthest = Math.max(furthest, d);
				}
			}
			for (Map.Entry<String, float[]> each : drawn.markers().entrySet()) {
				if (each.getKey().startsWith(MOD + "/rim/") && each.getValue()[2] >= 0) {
					icons++;
				}
			}
			if (icons == 0 || outside > 0.05 || furthest < c[2] - 3) {
				wrong.add("Size-2 icons near the round minimap's rim at GUI scale 1 (circle radius " + c[2] + "): " + icons + " drawn as icons, a corner up to " + outside
					+ " pixels outside the circle, the furthest corner " + furthest + " from its middle");
				return;
			}
			Diorama.LOGGER.info("addon-api markers: at GUI scale 1, {} size-2 icons by the round minimap's rim, every corner inside the circle (furthest {} of {})", icons, furthest, c[2]);
		} finally {
			context.runOnClient(mc -> {
				layer.close();
				Settings.get().minimapShape = Settings.Shape.SQUARE;
			});
			DioramaClientTest.setGuiScale(context, scale);
		}
	}

	/**
	 * Coordinates at the ends of the world: lines from one end to the other through the player,
	 * a line from the lowest height to the highest, and markers out there, on both maps. Every
	 * corner drawn is a number, near the view, and the lines through the player are drawn.
	 */
	private static void extremes(ClientGameTestContext context, Scene scene, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		MapLayer layer = context.computeOnClient(mc -> api().openLayer("extremes"));
		int far = Marker.MAX_COORDINATE;
		int high = Marker.MAX_HEIGHT;
		context.runOnClient(mc -> layer.replace(LayerContent.builder()
			.add(MapLine.builder(scene.overworld()).point(-far, scene.playerY(), scene.playerZ()).point(far, scene.playerY(), scene.playerZ()).rgb(ROUND_RGB).build())
			.add(MapLine.builder(scene.overworld()).point(scene.playerX(), scene.playerY(), -far).point(scene.playerX(), scene.playerY(), far).rgb(ROUND_RGB).build())
			.add(MapLine.builder(scene.overworld()).point(scene.playerX(), -high, scene.playerZ()).point(scene.playerX(), high, scene.playerZ()).rgb(ROUND_RGB).build())
			.add(MapLine.builder(scene.overworld()).point(-far, -high, -far).point(far, high, far).point(-far, high, far).rgb(ROUND_RGB).build())
			.add(Marker.builder(scene.overworld(), far, high, far).build())
			.add(Marker.builder(scene.overworld(), -far, -high, -far).build())
			.add(Marker.builder(scene.overworld(), scene.playerX(), scene.playerY(), scene.playerZ()).rgb(SPOT_RGB).build())
			.build()));
		try {
			for (boolean fullscreen : new boolean[] {false, true}) {
				if (fullscreen) {
					openMap(context);
				}
				Frame drawn = snapshot(context, recorder, fullscreen);
				context.takeScreenshot(fullscreen ? "addon-markers-11-extremes-map" : "addon-markers-10-extremes-minimap");
				String where = fullscreen ? "the full map" : "the minimap";
				MapViewport.View view = drawn.view();
				String bad = null;
				for (int pass = 0; pass < 2 && bad == null; pass++) {
					float[] xyz = pass == 0 ? drawn.lineXyz() : drawn.markerXyz();
					int quads = pass == 0 ? drawn.lineQuads() : drawn.markerQuads();
					for (int i = 0; i < quads * 12 && bad == null; i += 3) {
						float x = xyz[i];
						float y = xyz[i + 1];
						if (!Float.isFinite(x) || !Float.isFinite(y) || x < view.x() - 16 || x > view.x() + view.width() + 16 || y < view.y() - 16 || y > view.y() + view.height() + 16) {
							bad = (pass == 0 ? "a line's" : "a marker's") + " corner at (" + x + ", " + y + ")";
						}
					}
				}
				long lines = drawn.lines().stream().filter(key -> key.startsWith(MOD + "/extremes/")).count();
				boolean player = drawn.markers().containsKey(MOD + "/extremes/2");
				if (bad != null || lines < 2 || !player) {
					wrong.add("Coordinates at the ends of the world on " + where + " (view " + view.x() + ", " + view.y() + ", " + view.width() + " by " + view.height() + "): "
						+ (bad == null ? "every corner a number near the view" : bad) + "; " + lines + " of its lines drawn; the marker at the player " + (player ? "drawn" : "not drawn"));
				} else {
					Diorama.LOGGER.info("addon-api markers: coordinates at the ends of the world on {}: {} line quads and {} marker quads, every corner near the view", where, drawn.lineQuads(), drawn.markerQuads());
				}
			}
		} finally {
			context.setScreen(() -> null);
			context.runOnClient(mc -> layer.close());
		}
	}

	/**
	 * A map's budget, on the full map: a layer of lines with all of {@link LayerContent#MAX_LINE_POINTS}
	 * in view fills the line pieces, so the line in the layer after it isn't drawn; 8 layers of
	 * {@link LayerContent#MAX_MARKERS} dots fill the markers, so the one in the layer after isn't;
	 * and two frames draw exactly the same. Once every layer is closed, the arrays that held all
	 * that are let go.
	 */
	private static void budget(ClientGameTestContext context, Scene scene, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		List<MapLayer> layers = new ArrayList<>();
		try {
			context.runOnClient(mc -> {
				MapLayer lines = api().openLayer("budget-lines");
				layers.add(lines);
				LayerContent.Builder content = LayerContent.builder();
				int perLine = MapLine.MAX_POINTS;
				for (int l = 0; l < LayerContent.MAX_LINE_POINTS / perLine; l++) {
					MapLine.Builder line = MapLine.builder(scene.overworld()).rgb(0x3060FF).width(1);
					for (int k = 0; k < perLine; k++) {
						// A zigzag 16 blocks wide, a little further south at each point, all in view.
						line.point(scene.playerX() - 8 + (k % 2) * 16, scene.playerY(), scene.playerZ() - 20 + l * 10 + k * 8.0 / perLine);
					}
					content.add(line.build());
				}
				lines.replace(content.build());
				MapLayer after = api().openLayer("budget-after");
				layers.add(after);
				after.replace(LayerContent.builder()
					.add(MapLine.builder(scene.overworld()).point(scene.playerX(), scene.playerY(), scene.playerZ()).point(scene.playerX() + 4, scene.playerY(), scene.playerZ()).build())
					.build());
				for (int m = 0; m < MapLayer.MAX_DRAWN_MARKERS / LayerContent.MAX_MARKERS; m++) {
					MapLayer markers = api().openLayer("budget-markers-" + m);
					layers.add(markers);
					LayerContent.Builder dots = LayerContent.builder();
					for (int i = 0; i < LayerContent.MAX_MARKERS; i++) {
						dots.add(Marker.builder(scene.overworld(), scene.playerX() - 16 + (i % 32), scene.playerY(), scene.playerZ() - 16 + i / 32 + m * 0.1).size(Marker.MIN_SIZE).build());
					}
					markers.replace(dots.build());
				}
				MapLayer last = api().openLayer("budget-markers-after");
				layers.add(last);
				last.replace(LayerContent.builder().add(Marker.builder(scene.overworld(), scene.playerX(), scene.playerY(), scene.playerZ()).rgb(SPOT_RGB).build()).build());
			});
			openMap(context);
			Frame one = snapshot(context, recorder, true);
			Frame two = snapshot(context, recorder, true);
			context.takeScreenshot("addon-markers-12-budget");
			long full = one.lines().stream().filter(key -> key.startsWith(MOD + "/budget-lines/")).count();
			boolean afterLine = one.lines().stream().anyMatch(key -> key.startsWith(MOD + "/budget-after/"));
			long markers = one.markers().keySet().stream().filter(key -> key.startsWith(MOD + "/budget-markers-") && !key.startsWith(MOD + "/budget-markers-after/")).count();
			boolean afterMarker = one.markers().containsKey(MOD + "/budget-markers-after/0");
			boolean same = one.lineQuads() == two.lineQuads() && one.markerQuads() == two.markerQuads()
				&& Arrays.equals(one.lineXyz(), two.lineXyz()) && Arrays.equals(one.markerXyz(), two.markerXyz());
			if (full != LayerContent.MAX_LINE_POINTS / MapLine.MAX_POINTS || afterLine || one.lineQuads() > MapLayer.MAX_DRAWN_LINE_PIECES
				|| markers != MapLayer.MAX_DRAWN_MARKERS || afterMarker || !same) {
				wrong.add("The budget on the full map: " + full + " lines of the full layer drawn, the line after it " + (afterLine ? "drawn" : "not drawn") + ", " + one.lineQuads()
					+ " line quads (most " + MapLayer.MAX_DRAWN_LINE_PIECES + "); " + markers + " markers drawn (most " + MapLayer.MAX_DRAWN_MARKERS + "), the one after "
					+ (afterMarker ? "drawn" : "not drawn") + "; two frames " + (same ? "the same" : "different"));
				return;
			}
			Diorama.LOGGER.info("addon-api markers: budget: {} line quads and {} markers drawn, the line and the marker after them left out, the same two frames running", one.lineQuads(), markers);
		} finally {
			context.setScreen(() -> null);
			context.runOnClient(mc -> {
				for (MapLayer layer : layers) {
					layer.close();
				}
			});
		}
		long busy = context.computeOnClient(mc -> AddonOverlay.heldBytes());
		// Every layer closed: the next frames' maps find none and let the arrays go.
		int[] open = context.computeOnClient(mc -> new int[] {AddonLayers.open().size()});
		context.waitTicks(10);
		long idle = context.computeOnClient(mc -> AddonOverlay.heldBytes());
		if (open[0] == 0 && idle > 4096) {
			wrong.add("After the budget's layers closed, with none open, the overlay still holds " + idle + " bytes of arrays (" + busy + " while they were open)");
			return;
		}
		Diorama.LOGGER.info("addon-api markers: arrays held: {} bytes with the budget's layers just closed, {} bytes ten ticks on ({} layers open)", busy, idle, open[0]);
	}

	/**
	 * Icons over time on the full map: the test addon's 300 made-up icons, 150 at a time, all
	 * drawn as icons though the marker texture holds {@link MarkerAtlas#SLOTS}; then a thousand
	 * IDs no pack has, 250 at a time, which leave the icon caches no bigger than their bounds.
	 */
	private static void manyIcons(ClientGameTestContext context, Scene scene, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		MapLayer layer = context.computeOnClient(mc -> api().openLayer("many-icons"));
		List<String> dots = new ArrayList<>();
		try {
			openMap(context);
			for (int round = 0; round < 4; round++) {
				int from = round % 2 * 150;
				context.runOnClient(mc -> {
					LayerContent.Builder content = LayerContent.builder();
					for (int i = 0; i < 150; i++) {
						content.add(Marker.builder(scene.overworld(), scene.playerX() - 30 + (i % 15) * 4, scene.playerY(), scene.playerZ() - 20 + (i / 15) * 4)
							.icon(ResourceId.of(MOD + ":many/n" + String.format(Locale.ROOT, "%03d", from + i))).build());
					}
					layer.replace(content.build());
				});
				// An icon gives its tile up only once no draw that may not have reached the screen uses
				// it (AddonOverlay.RING draws), so the new ones have all their tiles a few frames on.
				Frame drawn = snapshot(context, recorder, true, 2 * AddonOverlay.RING);
				long icons = drawn.markers().entrySet().stream().filter(each -> each.getKey().startsWith(MOD + "/many-icons/") && each.getValue()[2] >= 0).count();
				long shown = drawn.markers().keySet().stream().filter(key -> key.startsWith(MOD + "/many-icons/")).count();
				if (shown < 100 || icons != shown) {
					dots.add("round " + round + " (icons " + from + " to " + (from + 149) + "): " + shown + " drawn, " + (shown - icons) + " of them as dots");
				}
				if (round == 1) {
					context.takeScreenshot("addon-markers-14-many-icons");
				}
			}
			for (int round = 0; round < 4; round++) {
				int from = round * 250;
				context.runOnClient(mc -> {
					LayerContent.Builder content = LayerContent.builder();
					for (int i = 0; i < 250; i++) {
						content.add(Marker.builder(scene.overworld(), scene.playerX() - 30 + (i % 25) * 2.4, scene.playerY(), scene.playerZ() - 20 + (i / 25) * 4)
							.icon(ResourceId.of(MOD + ":missing/m" + (from + i))).build());
					}
					layer.replace(content.build());
				});
				snapshot(context, recorder, true);
			}
			int[] counts = context.computeOnClient(mc -> MarkerAtlas.counts());
			int kept = context.computeOnClient(mc -> WaypointIcons.kept());
			if (!dots.isEmpty() || counts[0] > MarkerAtlas.SLOTS || counts[1] > 256 || kept > 512) {
				wrong.add("Icons over time: " + (dots.isEmpty() ? "every one drawn as an icon" : String.join("; ", dots)) + "; after a thousand missing IDs, " + counts[0]
					+ " icons on tiles (most " + MarkerAtlas.SLOTS + "), " + counts[1] + " missing IDs remembered (most 256), " + kept + " icon images kept (most 512)");
				return;
			}
			Diorama.LOGGER.info("addon-api markers: 300 icons over time all drawn as icons; then {} on tiles, {} missing IDs remembered, {} images kept", counts[0], counts[1], kept);
		} finally {
			context.setScreen(() -> null);
			context.runOnClient(mc -> layer.close());
		}
	}

	/** The broken addon opened a layer and filled it before it threw: Diorama closed it and never drew it. */
	private static void failed(ClientGameTestContext context, List<String> wrong) {
		boolean made = context.computeOnClient(mc -> BrokenAddon.layer() != null);
		if (!made) {
			wrong.add("The broken addon never got as far as opening its layer: is its jar installed?");
			return;
		}
		boolean open = context.computeOnClient(mc -> BrokenAddon.layer().isOpen());
		boolean listed = context.computeOnClient(mc -> AddonLayers.open().contains(BrokenAddon.layer()));
		boolean late = context.computeOnClient(mc -> {
			MapLayer layer = BrokenAddon.api().openLayer("late");
			layer.replace(LayerContent.builder().add(Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).build()).build());
			return layer.isOpen() || AddonLayers.open().contains(layer);
		});
		if (open || listed || late) {
			wrong.add("The broken addon's layer, after it failed: " + (open ? "open" : "closed") + ", " + (listed ? "still" : "not") + " among the open layers; a layer it opened after failing "
				+ (late ? "opened" : "stayed closed"));
			return;
		}
		Diorama.LOGGER.info("addon-api markers: the broken addon's layer was closed when it failed, and one opened after stayed closed");
	}

	/** Limits and bad values are refused while the content is built, and layers per addon are limited. */
	private static void limits(ClientGameTestContext context, List<String> wrong) {
		Marker one = Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).build();
		refused(wrong, "a layer's 1,025th marker", IllegalArgumentException.class, () -> {
			LayerContent.Builder content = LayerContent.builder();
			for (int i = 0; i <= LayerContent.MAX_MARKERS; i++) {
				content.add(one);
			}
			return content;
		});
		refused(wrong, "a line's 1,025th point", IllegalArgumentException.class, () -> {
			MapLine.Builder line = MapLine.builder(DimensionId.OVERWORLD);
			for (int i = 0; i <= MapLine.MAX_POINTS; i++) {
				line.point(i, 64, 0);
			}
			return line;
		});
		refused(wrong, "a layer's lines past 4,096 points in all", IllegalArgumentException.class, () -> {
			MapLine.Builder line = MapLine.builder(DimensionId.OVERWORLD);
			for (int i = 0; i < MapLine.MAX_POINTS; i++) {
				line.point(i, 64, 0);
			}
			MapLine full = line.build();
			LayerContent.Builder content = LayerContent.builder();
			for (int i = 0; i <= LayerContent.MAX_LINE_POINTS / MapLine.MAX_POINTS; i++) {
				content.add(full);
			}
			return content;
		});
		refused(wrong, "a label of 33 characters", IllegalArgumentException.class, () -> Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).label("x".repeat(Marker.MAX_LABEL_LENGTH + 1)));
		refused(wrong, "a marker 1 pixel across", IllegalArgumentException.class, () -> Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).size(Marker.MIN_SIZE - 1));
		refused(wrong, "a marker 33 pixels across", IllegalArgumentException.class, () -> Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).size(Marker.MAX_SIZE + 1));
		refused(wrong, "an ARGB colour", IllegalArgumentException.class, () -> Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).rgb(0xFF00FF00));
		refused(wrong, "a NaN coordinate", IllegalArgumentException.class, () -> Marker.builder(DimensionId.OVERWORLD, Double.NaN, 64, 0));
		refused(wrong, "an infinite line point", IllegalArgumentException.class, () -> MapLine.builder(DimensionId.OVERWORLD).point(0, Double.POSITIVE_INFINITY, 0));
		refused(wrong, "a marker at x Double.MAX_VALUE", IllegalArgumentException.class, () -> Marker.builder(DimensionId.OVERWORLD, Double.MAX_VALUE, 64, 0));
		refused(wrong, "a line point at x -Double.MAX_VALUE", IllegalArgumentException.class, () -> MapLine.builder(DimensionId.OVERWORLD).point(-Double.MAX_VALUE, 64, 0));
		refused(wrong, "a marker at z 30,000,001", IllegalArgumentException.class, () -> Marker.builder(DimensionId.OVERWORLD, 0, 64, Marker.MAX_COORDINATE + 1));
		refused(wrong, "a line point at y -20,000,001", IllegalArgumentException.class, () -> MapLine.builder(DimensionId.OVERWORLD).point(0, -Marker.MAX_HEIGHT - 1, 0));
		refused(wrong, "a line point at z -infinity", IllegalArgumentException.class, () -> MapLine.builder(DimensionId.OVERWORLD).point(0, 64, Double.NEGATIVE_INFINITY));
		refused(wrong, "a line of one point", IllegalStateException.class, () -> MapLine.builder(DimensionId.OVERWORLD).point(0, 64, 0).build());
		refused(wrong, "a line 9 pixels wide", IllegalArgumentException.class, () -> MapLine.builder(DimensionId.OVERWORLD).width(MapLine.MAX_WIDTH + 1));
		for (String path : new String[] {"../raid", "many//n000", "many/./n000", "many/", "many/.."}) {
			refused(wrong, "an icon " + MOD + ":" + path, IllegalArgumentException.class, () -> Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).icon(ResourceId.of(MOD, path)));
		}
		try {
			Marker.builder(DimensionId.OVERWORLD, Marker.MAX_COORDINATE, Marker.MAX_HEIGHT, -Marker.MAX_COORDINATE).build();
			MapLine.builder(DimensionId.OVERWORLD).point(-Marker.MAX_COORDINATE, -Marker.MAX_HEIGHT, Marker.MAX_COORDINATE).point(0, 64, 0).build();
		} catch (IllegalArgumentException e) {
			wrong.add("Coordinates at the very ends of the world were refused: " + e.getMessage());
		}
		String coloured = Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).label(" §cRed §lroad§r ").build().label();
		String onlyCodes = Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).label("§a§l").build().label();
		String longWithCodes;
		try {
			longWithCodes = Marker.builder(DimensionId.OVERWORLD, 0, 64, 0).label("§e" + "x".repeat(Marker.MAX_LABEL_LENGTH)).build().label();
		} catch (IllegalArgumentException e) {
			longWithCodes = "refused: " + e.getMessage();
		}
		if (!coloured.equals("Red road") || !onlyCodes.isEmpty() || longWithCodes.length() != Marker.MAX_LABEL_LENGTH) {
			wrong.add("Labels with formatting codes: \"§cRed §lroad§r\" became \"" + coloured + "\", codes alone became \"" + onlyCodes + "\", 32 letters after a code became "
				+ longWithCodes.length() + " long (" + longWithCodes + ")");
		}
		List<String> onClient = context.computeOnClient(mc -> {
			List<String> found = new ArrayList<>();
			refused(found, "a layer named \"Bad Name\"", IllegalArgumentException.class, () -> api().openLayer("Bad Name"));
			List<MapLayer> held = new ArrayList<>();
			try {
				held.add(api().openLayer("twice"));
				refused(found, "a second open layer named \"twice\"", IllegalArgumentException.class, () -> api().openLayer("twice"));
				for (int i = 1; i < MapLayer.MAX_OPEN; i++) {
					held.add(api().openLayer("many" + i));
				}
				refused(found, "a 17th open layer", IllegalStateException.class, () -> api().openLayer("one-too-many"));
			} finally {
				for (MapLayer layer : held) {
					layer.close();
				}
			}
			return found;
		});
		wrong.addAll(onClient);
		if (wrong.isEmpty()) {
			Diorama.LOGGER.info("addon-api markers: limits and bad values refused while building, coordinates at the ends of the world taken, formatting codes taken out of labels, layers limited to {} per addon", MapLayer.MAX_OPEN);
		}
	}

	/** Adds to {@code wrong} unless {@code making} throws {@code expected}. */
	private static void refused(List<String> wrong, String what, Class<? extends RuntimeException> expected, Supplier<?> making) {
		try {
			making.get();
			wrong.add(what + " wasn't refused");
		} catch (RuntimeException e) {
			if (!expected.isInstance(e)) {
				wrong.add(what + " was refused with " + e.getClass().getName() + ", not " + expected.getSimpleName() + ": " + e.getMessage());
			}
		}
	}

	/** From another thread, every layer call throws, and opening one opens nothing. */
	private static void threads(ClientGameTestContext context, List<String> wrong) {
		DioramaApi api = context.computeOnClient(mc -> api());
		MapLayer layer = context.computeOnClient(mc -> api().openLayer("threads"));
		try {
			int before = context.computeOnClient(mc -> AddonLayers.open().size());
			refused(wrong, "openLayer() off the client thread", IllegalStateException.class, () -> api.openLayer("elsewhere"));
			refused(wrong, "replace() off the client thread", IllegalStateException.class, () -> {
				layer.replace(LayerContent.EMPTY);
				return layer;
			});
			refused(wrong, "isOpen() off the client thread", IllegalStateException.class, layer::isOpen);
			refused(wrong, "close() off the client thread", IllegalStateException.class, () -> {
				layer.close();
				return layer;
			});
			int after = context.computeOnClient(mc -> AddonLayers.open().size());
			if (after != before || !context.computeOnClient(mc -> layer.isOpen())) {
				wrong.add("Calls from another thread changed the open layers: " + before + " before, " + after + " after, the layer " + (context.computeOnClient(mc -> layer.isOpen()) ? "open" : "closed"));
			}
		} finally {
			context.runOnClient(mc -> layer.close());
		}
	}

	/**
	 * The test addon's own example on both maps: carts replaced every tick move, the places and
	 * roads show, the icon from its jar is drawn as an icon and the missing one as a dot (read
	 * from what was drawn). Screenshots. Its layers stay open for the next world.
	 */
	private static void showcase(ClientGameTestContext context, Scene scene, List<MapLayer> opened, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		MapMarkers markers = context.computeOnClient(mc -> new MapMarkers(api(), DimensionId.OVERWORLD, scene.spotX() + 4, scene.spotY(), scene.spotZ() + 6, 300));
		example = markers;
		opened.add(markers.carts());
		opened.add(markers.places());
		opened.add(markers.roads());
		for (int i = 0; i < 20; i++) {
			context.runOnClient(mc -> markers.tick());
			context.waitTicks(1);
		}
		Map<String, float[]> first = frame(context, recorder, false);
		for (int i = 0; i < 10; i++) {
			context.runOnClient(mc -> markers.tick());
			context.waitTicks(1);
		}
		Map<String, float[]> later = frame(context, recorder, false);
		context.takeScreenshot("addon-markers-15-showcase-minimap");
		long carts = first.keySet().stream().filter(key -> key.startsWith(MOD + "/carts/")).count();
		long moved = first.entrySet().stream().filter(entry -> entry.getKey().startsWith(MOD + "/carts/")).filter(entry -> {
			float[] now = later.get(entry.getKey());
			return now != null && (now[0] != entry.getValue()[0] || now[1] != entry.getValue()[1]);
		}).count();
		if (carts < 50 || moved < carts / 2) {
			wrong.add("The carts on the minimap: " + carts + " drawn, " + moved + " of them moved over 10 ticks of replacing");
		}
		openMap(context);
		Map<String, float[]> map = frame(context, recorder, true);
		List<String> roads = context.computeOnClient(mc -> new ArrayList<>(recorder.lines.get(1)));
		context.takeScreenshot("addon-markers-16-showcase-map");
		context.setScreen(() -> null);
		long places = map.keySet().stream().filter(key -> key.startsWith(MOD + "/places/")).count();
		long roadCount = roads.stream().filter(key -> key.startsWith(MOD + "/roads/")).count();
		// The places in MapMarkers' order: home, mine, villagers, the jar's own icon, the missing one, a dot.
		float[] own = map.get(MOD + "/places/3");
		float[] missing = map.get(MOD + "/places/4");
		if (places != 6 || roadCount != 3 || own == null || own[2] < 0 || missing == null || missing[2] >= 0) {
			wrong.add("The example on the full map: " + places + " of 6 places and " + roadCount + " of 3 roads drawn; the icon from the addon's jar " + tileText(own)
				+ ", the missing one " + tileText(missing));
			return;
		}
		Diorama.LOGGER.info("addon-api markers: example: {} carts on the minimap, {} moved in 10 ticks; 6 places and 3 roads on the full map; the jar's icon on tile {}, the missing one a dot",
			carts, moved, (int) own[2]);
	}

	private static String text(float[] at) {
		return at == null ? "nowhere" : String.format(Locale.ROOT, "(%.3f, %.3f)", at[0], at[1]);
	}

	/** Whether a pixel within {@code reach} of (x, y) is this colour, give or take 8 a channel. */
	private static boolean near(BufferedImage image, double x, double y, int reach, int rgb) {
		int cx = (int) Math.floor(x);
		int cy = (int) Math.floor(y);
		for (int dy = -reach; dy <= reach; dy++) {
			for (int dx = -reach; dx <= reach; dx++) {
				int px = cx + dx;
				int py = cy + dy;
				if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
					continue;
				}
				int argb = image.getRGB(px, py);
				if (Math.abs((argb >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) <= 8 && Math.abs((argb >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) <= 8 && Math.abs((argb & 0xFF) - (rgb & 0xFF)) <= 8) {
					return true;
				}
			}
		}
		return false;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
