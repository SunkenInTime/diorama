package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.Settings;
import dev.diorama.addon.AddonLayers;
import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.api.MapLine;
import dev.diorama.api.Marker;
import dev.diorama.render.AddonOverlay;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Radar;
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
import java.util.HashMap;
import java.util.List;
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
 * {@link AddonApiTest}): through the test addon, which only knows the API. Each check says what it
 * saw when it fails.
 * <ul>
 *   <li>Limits are refused while the content is built, in the addon's own code, and so are bad
 *       values; layers are limited per addon, and a name can't be open twice.</li>
 *   <li>Calls from another thread throw.</li>
 *   <li>Positions: a marker at a block's middle lands where Diorama puts a waypoint's point for
 *       that block, and a marker at a mob lands where the radar puts the mob, to a hundredth of a
 *       pixel, on the minimap facing north, on the minimap turned with the player, and on the full
 *       map; and the screenshot has the marker's colour there, and a line's colour half way along
 *       it.</li>
 *   <li>A marker in the Nether isn't drawn on the Overworld's maps; the full map showing the
 *       Nether draws it, and none of the Overworld's.</li>
 *   <li>Replacing moves what's drawn; the Addons tab switch takes it all off and puts it back
 *       with the layer still open; closing takes it off for good; the broken addon's layer was
 *       closed when it failed and never drawn.</li>
 *   <li>The test addon's own example ({@link MapMarkers}): a few hundred carts replaced every
 *       tick, icons with labels (one from its own jar, one missing), and roads, on the minimap and
 *       the full map, in screenshots.</li>
 * </ul>
 * Everything it opens it closes; the settings it changes it puts back.
 */
final class AddonMarkersTest {
	private static final String MOD = "diorama_test_addon";
	private static final String BROKEN = "diorama_test_addon_broken";
	/** The probe layer's colours: none of them a block's. */
	private static final int SPOT_RGB = 0xFFFF00;
	private static final int MOB_RGB = 0x00FFFF;
	private static final int LINE_RGB = 0xFF8000;
	private static final int NETHER_RGB = 0x8000FF;
	private static final int SIZE = 6;
	/** How far a marker may be from where Diorama puts its own, in GUI pixels. */
	private static final float CLOSE = 0.01F;

	private AddonMarkersTest() {
	}

	/** Each frame's markers and lines, as AddonOverlay placed them, for the minimap [0] and the full map [1]. */
	static final class Recorder implements AddonOverlay.Probe {
		final List<Map<String, float[]>> markers = List.of(new HashMap<>(), new HashMap<>());
		final List<List<String>> lines = List.of(new ArrayList<>(), new ArrayList<>());
		final MapViewport.View[] views = new MapViewport.View[2];
		final int[] frames = new int[2];
		/** Anything of the broken addon's, ever. */
		boolean brokenSeen;

		@Override
		public void frame(boolean fullscreen) {
			int i = fullscreen ? 1 : 0;
			this.markers.get(i).clear();
			this.lines.get(i).clear();
			this.frames[i]++;
		}

		@Override
		public void view(boolean fullscreen, MapViewport.View view) {
			this.views[fullscreen ? 1 : 0] = view;
		}

		@Override
		public void marker(boolean fullscreen, AddonLayers.Layer layer, int index, float x, float y) {
			this.brokenSeen |= layer.modId().equals(BROKEN);
			this.markers.get(fullscreen ? 1 : 0).put(layer.modId() + "/" + layer.layerName() + "/" + index, new float[] {x, y});
		}

		@Override
		public void line(boolean fullscreen, AddonLayers.Layer layer, int index) {
			this.brokenSeen |= layer.modId().equals(BROKEN);
			this.lines.get(fullscreen ? 1 : 0).add(layer.modId() + "/" + layer.layerName() + "/" + index);
		}
	}

	/** Where the probe layer's things are, worked out once the player stands still. */
	private record Scene(DimensionId overworld, double spotX, double spotY, double spotZ, int blockX, int blockY, int blockZ,
		double mobX, double mobY, double mobZ, int mobId, double netherX, double netherZ) {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		//? if >=26.1 && fabric {
		// First, where master measures the same: the frames with no addon layer.
		long[] none = MapAllocProbe.none(context, world);
		//?}
		List<String> wrong = new ArrayList<>();
		Recorder recorder = new Recorder();
		boolean lockNorth = context.computeOnClient(mc -> Settings.get().minimapLockNorth);
		boolean passive = context.computeOnClient(mc -> Settings.get().radarPassive);
		List<MapLayer> opened = new ArrayList<>();
		context.runOnClient(mc -> {
			Settings.get().radarPassive = true;
			Settings.get().minimapLockNorth = true;
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
			closed(context, scene, probe, wrong);

			showcase(context, scene, opened, wrong);
			//? if >=26.1 && fabric {
			for (MapLayer layer : opened) {
				context.runOnClient(mc -> layer.close());
			}
			allocations(context, scene, none, wrong);
			//?}
		} finally {
			context.runOnClient(mc -> {
				for (MapLayer layer : opened) {
					layer.close();
				}
				AddonOverlay.probe = null;
				Settings.get().minimapLockNorth = lockNorth;
				Settings.get().radarPassive = passive;
			});
			context.setScreen(() -> null);
			world.getServer().runCommand("kill @e[type=pig,tag=diorama_marker_probe]");
		}
		if (recorder.brokenSeen) {
			wrong.add("Something of the broken addon's layer was drawn");
		}
		int left = context.computeOnClient(mc -> AddonLayers.open().size());
		if (left != 0) {
			wrong.add(left + " layers still open after the section closed its own");
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("Addon markers and lines:\n  " + String.join("\n  ", wrong));
		}
		Diorama.LOGGER.info("addon-api markers: every check passed");
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
				pig.getX(), pig.getY(), pig.getZ(), pig.getId(), (at.getX() + 0.5) / 8, (at.getZ() + 0.5) / 8);
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
		int i = fullscreen ? 1 : 0;
		int from = context.computeOnClient(mc -> recorder.frames[i]);
		context.waitFor(mc -> recorder.frames[i] >= from + 2, 200);
		return context.computeOnClient(mc -> new HashMap<>(recorder.markers.get(i)));
	}

	private static Recorder recorder() {
		return (Recorder) AddonOverlay.probe;
	}

	/**
	 * The probe layer's markers on one view are where Diorama puts its own: the block's middle
	 * where a waypoint on that block has its point, the pig's marker where the radar draws the
	 * pig; the Nether's isn't drawn; and the screenshot shows each colour there, the line's half
	 * way between.
	 */
	private static void positions(ClientGameTestContext context, Scene scene, boolean fullscreen, String where, String shot, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		Map<String, float[]> placed = frame(context, recorder, fullscreen);
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
		BufferedImage image = read(context.takeScreenshot(shot));
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		boolean spotSeen = near(image, spot[0] * scale, spot[1] * scale, 1, SPOT_RGB);
		boolean mobSeen = near(image, mob[0] * scale, mob[1] * scale, 1, MOB_RGB);
		boolean lineSeen = near(image, (spot[0] + mob[0]) / 2 * scale, (spot[1] + mob[1]) / 2 * scale, 1, LINE_RGB);
		if (!spotSeen || !mobSeen || !lineSeen) {
			wrong.add(where + ": in " + shot + ".png, the block's marker's colour " + (spotSeen ? "is" : "isn't") + " at (" + Math.round(spot[0] * scale) + ", " + Math.round(spot[1] * scale)
				+ "), the pig's " + (mobSeen ? "is" : "isn't") + " at (" + Math.round(mob[0] * scale) + ", " + Math.round(mob[1] * scale) + "), the line's " + (lineSeen ? "is" : "isn't") + " half way");
			return;
		}
		Diorama.LOGGER.info("addon-api markers: {}: block marker at {} = waypoint point {}, pig marker at {} = radar {}; colours seen in {}", where, text(spot), text(expected[0]), text(mob), text(expected[1]), shot);
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
		BufferedImage image = read(context.takeScreenshot("addon-markers-06-closed"));
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
		refused(wrong, "a layer's 4,097th marker", IllegalArgumentException.class, () -> {
			LayerContent.Builder content = LayerContent.builder();
			for (int i = 0; i <= LayerContent.MAX_MARKERS; i++) {
				content.add(one);
			}
			return content;
		});
		refused(wrong, "a line's 4,097th point", IllegalArgumentException.class, () -> {
			MapLine.Builder line = MapLine.builder(DimensionId.OVERWORLD);
			for (int i = 0; i <= MapLine.MAX_POINTS; i++) {
				line.point(i, 64, 0);
			}
			return line;
		});
		refused(wrong, "a layer's lines past 16,384 points in all", IllegalArgumentException.class, () -> {
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
		refused(wrong, "a line of one point", IllegalStateException.class, () -> MapLine.builder(DimensionId.OVERWORLD).point(0, 64, 0).build());
		refused(wrong, "a line 9 pixels wide", IllegalArgumentException.class, () -> MapLine.builder(DimensionId.OVERWORLD).width(MapLine.MAX_WIDTH + 1));
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
			Diorama.LOGGER.info("addon-api markers: limits and bad values refused while building, layers limited to {} per addon", MapLayer.MAX_OPEN);
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
	 * roads show, the icon from its jar loads and the missing one shows as a dot. Screenshots.
	 */
	private static void showcase(ClientGameTestContext context, Scene scene, List<MapLayer> opened, List<String> wrong) {
		Recorder recorder = context.computeOnClient(mc -> recorder());
		MapMarkers markers = context.computeOnClient(mc -> new MapMarkers(api(), DimensionId.OVERWORLD, scene.spotX() + 4, scene.spotY(), scene.spotZ() + 6, 300));
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
		context.takeScreenshot("addon-markers-07-showcase-minimap");
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
		context.takeScreenshot("addon-markers-08-showcase-map");
		long places = map.keySet().stream().filter(key -> key.startsWith(MOD + "/places/")).count();
		long roadCount = roads.stream().filter(key -> key.startsWith(MOD + "/roads/")).count();
		int ownIcon = context.computeOnClient(mc -> iconTile(MapMarkers.OWN_ICON));
		int missingIcon = context.computeOnClient(mc -> iconTile(MapMarkers.MISSING_ICON));
		context.setScreen(() -> null);
		if (places != 6 || roadCount != 3 || ownIcon < 0 || missingIcon != -1) {
			wrong.add("The example on the full map: " + places + " of 6 places and " + roadCount + " of 3 roads drawn; the icon from the addon's jar on tile " + ownIcon
				+ ", the missing one on " + missingIcon + " (-1 is a dot)");
			return;
		}
		Diorama.LOGGER.info("addon-api markers: example: {} carts on the minimap, {} moved in 10 ticks; 6 places and 3 roads on the full map; the jar's icon on tile {}", carts, moved, ownIcon);
	}

	//? if >=26.1 && fabric {
	/** What a feed of moving markers does each client tick, or null. */
	private static Runnable feed;
	private static boolean feeding;

	/** Markers in rings round the player's block: {@code count} dots, or icons with labels, turned by {@code step}. */
	private static LayerContent rings(Scene scene, int count, long step, boolean icons) {
		LayerContent.Builder content = LayerContent.builder();
		for (int i = 0; i < count; i++) {
			int ring = i % 10;
			double radius = 4 + ring * 4;
			double angle = i * 2 * Math.PI * 10 / count + step * 0.02;
			double x = scene.spotX() + 6 + Math.cos(angle) * radius;
			double z = scene.spotZ() + 12 + Math.sin(angle) * radius;
			Marker.Builder marker = Marker.builder(scene.overworld(), x, scene.spotY(), z);
			if (icons) {
				marker.icon(i % 2 == 0 ? dev.diorama.api.ResourceId.of("diorama:home") : MapMarkers.OWN_ICON).label("Place " + i);
			} else {
				marker.rgb(MapMarkers.CART_RGB).size(4);
			}
			content.add(marker.build());
		}
		return content.build();
	}

	/**
	 * Bytes the minimap and the full map allocate a frame, medians over 300 frames with the map
	 * settled and the world paused: no layer, then one layer of 100 and of 1,000 dots replaced every
	 * client tick, 1,000 dots that don't move, and 1,000 icons with labels that don't move; and what
	 * one replace of 1,000 markers costs, the addon building them and Diorama taking them.
	 */
	private static void allocations(ClientGameTestContext context, Scene scene, long[] none, List<String> wrong) {
		AddonOverlay.Probe recorder = context.computeOnClient(mc -> AddonOverlay.probe);
		context.runOnClient(mc -> {
			AddonOverlay.probe = null;
			if (!feeding) {
				feeding = true;
				net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
					if (feed != null) {
						feed.run();
					}
				});
			}
		});
		List<String> table = new ArrayList<>();
		table.add(String.format(java.util.Locale.ROOT, "%-34s %10s %10s", "layer", "minimap", "full map"));
		table.add(String.format(java.util.Locale.ROOT, "%-34s %10d %10d", "none, no mobs (as on master)", none[0], none[1]));
		MapLayer[] layers = new MapLayer[1];
		try (WorldPause paused = WorldPause.pause(context)) {
			// The same scene as the rows under it: the probe pig and the mobs about.
			table.add(row(context, "none"));
			MapLayer layer = context.computeOnClient(mc -> api().openLayer("alloc"));
			layers[0] = layer;
			for (int count : new int[] {100, 1000}) {
				long[] step = {0};
				context.runOnClient(mc -> feed = () -> layer.replace(rings(scene, count, step[0]++, false)));
				table.add(row(context, count + " dots replaced every tick"));
				context.runOnClient(mc -> feed = null);
			}
			context.runOnClient(mc -> layer.replace(rings(scene, 1000, 0, false)));
			table.add(row(context, "1000 dots, still"));
			context.runOnClient(mc -> layer.replace(rings(scene, 1000, 0, true)));
			table.add(row(context, "1000 icons with labels, still"));
			context.runOnClient(mc -> layer.replace(LayerContent.EMPTY));
			table.add(row(context, "layer open, empty"));
		} finally {
			context.runOnClient(mc -> {
				feed = null;
				if (layers[0] != null) {
					layers[0].close();
				}
				AddonOverlay.probe = recorder;
			});
		}
		// One replace of 1,000 markers, 200 times: the addon building them, and Diorama taking them.
		long[] replace = context.computeOnClient(mc -> {
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
			MapLayer timed = api().openLayer("replace-cost");
			try {
				long buildBytes = 0;
				long buildNanos = 0;
				long takeBytes = 0;
				long takeNanos = 0;
				for (int i = 0; i < 250; i++) {
					long a = threads.getCurrentThreadAllocatedBytes();
					long t0 = System.nanoTime();
					LayerContent content = rings(scene, 1000, i, false);
					long t1 = System.nanoTime();
					long b = threads.getCurrentThreadAllocatedBytes();
					timed.replace(content);
					long t2 = System.nanoTime();
					long c = threads.getCurrentThreadAllocatedBytes();
					// The first 50 warm the JIT up.
					if (i >= 50) {
						buildBytes += b - a;
						buildNanos += t1 - t0;
						takeBytes += c - b;
						takeNanos += t2 - t1;
					}
				}
				return new long[] {buildBytes / 200, buildNanos / 200, takeBytes / 200, takeNanos / 200};
			} finally {
				timed.close();
			}
		});
		table.add(String.format(java.util.Locale.ROOT, "one replace of 1000 dots: the addon builds them in %d bytes, %.1f µs; replace() takes them in %d bytes, %.2f µs",
			replace[0], replace[1] / 1000.0, replace[2], replace[3] / 1000.0));
		Diorama.LOGGER.info("addon-api alloc: bytes a frame (medians of 200 frames, world paused):\n{}", String.join("\n", table));
		if (replace[2] != 0) {
			wrong.add("replace() allocated " + replace[2] + " bytes taking 1,000 markers");
		}
	}

	/** The minimap's and then the full map's median bytes a frame, as one row of the table. */
	private static String row(ClientGameTestContext context, String what) {
		context.setScreen(() -> null);
		context.waitTicks(10);
		AllocProbe.measure(context, "warm-up, " + what, 100);
		long minimap = AllocProbe.measure(context, what, 200);
		openMap(context);
		context.waitTicks(10);
		MapAllocProbe.measure(context, "warm-up, " + what, 100);
		long map = MapAllocProbe.measure(context, what, 200);
		context.setScreen(() -> null);
		return String.format(java.util.Locale.ROOT, "%-34s %10d %10d", what, minimap, map);
	}
	//?}

	private static int iconTile(dev.diorama.api.ResourceId icon) {
		try {
			java.lang.reflect.Method tile = Class.forName("dev.diorama.render.MarkerAtlas").getDeclaredMethod("tile", dev.diorama.api.ResourceId.class);
			tile.setAccessible(true);
			return (int) tile.invoke(null, icon);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static String text(float[] at) {
		return at == null ? "nowhere" : String.format(java.util.Locale.ROOT, "(%.3f, %.3f)", at[0], at[1]);
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
