package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.addon.AddonView;
import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.api.MapLine;
import dev.diorama.api.MapView;
import dev.diorama.api.Marker;
import dev.diorama.api.ResourceId;
import dev.diorama.api.ScreenPos;
import dev.diorama.api.ViewPlace;
import dev.diorama.api.WorldPos;
import dev.diorama.bake.TileKey;
import dev.diorama.render.AddonOverlay;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Radar;
import dev.diorama.testaddon.TestAddon;
import dev.diorama.testaddon.broken.BrokenAddon;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Diorama's map drawn in an addon's own screen ({@link MapView}, {@code addon-api}, in the reopened
 * world, before {@link AddonMarkersTest}): the test addon opens the views, and {@link ViewScreen}
 * stands in for its screen, drawing them through the API. Each check says what it saw when it
 * fails.
 * <ul>
 *   <li>Bad places are refused where they're built; an addon can open two views; render() takes
 *       only the game's graphics object; calls from another thread throw; the broken addon's view
 *       was closed when it failed.</li>
 *   <li>The same place as the full map: a view in the middle of the screen showing the place the
 *       full map shows, at the same zoom, puts an addon's marker where the full map puts it, and
 *       where project() says, and its pixels are the full map's (screenshots compared).</li>
 *   <li>Parts: the player's arrow, the radar's pig and a waypoint show only in a view opened
 *       with them, and the radar and waypoints only while the player's settings show them on the
 *       map.</li>
 *   <li>A view partly off the screen draws only what's on it, with its marker still where
 *       project() says; a view as big as allowed draws the screen and no more.</li>
 *   <li>Zoomed out past what the map can show, the view draws where the full map stops, and
 *       zoom() says so; project() and worldAt() undo each other, facing north and east.</li>
 *   <li>Views drawn past the most a frame draws return false and draw nothing, with one warning;
 *       closing a view lets its fog go, and a closed view draws nothing.</li>
 *   <li>Inside an addon's scissor, or with its pose moved, a view draws only where it lands, its
 *       fog included; drawn with graphics kept from an earlier frame it shows nothing.</li>
 *   <li>A resource reload with the view's screen open draws the screen under the loading overlay
 *       without a throw.</li>
 *   <li>With the map off in the player's dimension, a view draws nothing and returns false.</li>
 * </ul>
 * Then, on 26.x Fabric, bytes a frame for a view against the full map at the same place. Everything
 * it opens it closes; the settings it changes it puts back.
 */
final class AddonViewTest {
	private static final String MOD = "diorama_test_addon";
	private static final String PROBE_TAG = "diorama_view_probe";
	private static final int SPOT_RGB = 0xFFFF00;
	private static final int LINE_RGB = 0xFF8000;
	private static final float CLOSE = 0.01F;
	private static final double ZOOM = 4;

	private AddonViewTest() {
	}

	/** Where things are: the player, the probe layer's marker, the pig, the waypoint. */
	record Scene(double px, double py, double pz, double spotX, double spotY, double spotZ, int pigId, Waypoint waypoint) {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		List<String> wrong = new ArrayList<>();
		AddonMarkersTest.Recorder recorder = new AddonMarkersTest.Recorder();
		boolean radarOnMap = context.computeOnClient(mc -> Settings.get().radarOnMap);
		boolean passive = context.computeOnClient(mc -> Settings.get().radarPassive);
		boolean waypointsOnMap = context.computeOnClient(mc -> Settings.get().waypointsOnMap);
		List<MapView> opened = new ArrayList<>();
		MapLayer[] probe = new MapLayer[1];
		Scene scene = null;
		try {
			int before = context.computeOnClient(mc -> AddonView.openCount());
			if (before != 0) {
				wrong.add(before + " views open before the section opened any: the broken addon's should have closed when it failed");
			}
			world.getServer().runCommand("time set noon");
			context.setScreen(() -> null);
			context.waitFor(mc -> mc.player.onGround(), 600);
			BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
			world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 0");
			world.getServer().runCommand("kill @e[type=!player]");
			world.getServer().runCommand("summon pig " + (at.getX() + 7.3) + " " + at.getY() + " " + (at.getZ() - 10.7)
				+ " {NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,Tags:[\"" + PROBE_TAG + "\"]}");
			context.runOnClient(mc -> {
				Settings.get().radarOnMap = true;
				Settings.get().radarPassive = true;
				Settings.get().waypointsOnMap = true;
				AddonOverlay.probe = recorder;
			});
			context.waitTicks(40);
			scene = scene(context, at);
			Scene s = scene;
			probe[0] = context.computeOnClient(mc -> api().openLayer("view-probe"));
			context.runOnClient(mc -> probe[0].replace(LayerContent.builder()
				.add(MapLine.builder(DimensionId.OVERWORLD).point(s.spotX(), s.spotY(), s.spotZ()).point(s.spotX() + 12, s.spotY(), s.spotZ() + 4).rgb(LINE_RGB).width(3).build())
				.add(Marker.builder(DimensionId.OVERWORLD, s.spotX(), s.spotY(), s.spotZ()).rgb(SPOT_RGB).size(6).build())
				.build()));
			DioramaClientTest.settle(context, 20);

			limits(context, opened, wrong);
			threads(context, wrong);
			failedAddon(context, wrong);

			sameAsFullMap(context, scene, recorder, opened, wrong);
			parts(context, scene, opened, wrong);
			cropped(context, scene, recorder, opened, wrong);
			zoomOut(context, scene, opened, wrong);
			roundTrip(context, scene, opened, wrong);
			budget(context, scene, opened, wrong);
			onceAFrame(context, scene, opened, wrong);
			renderThenClose(context, scene, wrong);
			scissored(context, scene, opened, wrong);
			movedPose(context, scene, opened, wrong);
			balanced(context, scene, opened, wrong);
			legacyState(context, scene, opened, wrong);
			keptGraphics(context, scene, wrong);
			closed(context, scene, wrong);
			showcase(context, scene, opened, wrong);
			//? if >=26.1 && fabric {
			ViewMeasure.run(context, scene.px(), scene.py(), scene.pz());
			//?}
			reloadWithScreenOpen(context, scene, wrong);
			reloadLetsGo(context, scene, wrong);
			mapOff(context, scene, wrong);
			twoLevels(context, world, scene, wrong);
		} finally {
			Scene s = scene;
			context.setScreen(() -> null);
			context.runOnClient(mc -> {
				for (MapView view : opened) {
					view.close();
				}
				if (probe[0] != null) {
					probe[0].close();
				}
				AddonOverlay.probe = null;
				Settings.get().radarOnMap = radarOnMap;
				Settings.get().radarPassive = passive;
				Settings.get().waypointsOnMap = waypointsOnMap;
				WaypointStore store = WaypointStore.get();
				if (s != null && store != null) {
					store.remove(s.waypoint());
				}
			});
			world.getServer().runCommand("kill @e[type=pig,tag=" + PROBE_TAG + "]");
		}
		int left = context.computeOnClient(mc -> AddonView.openCount());
		if (left != 0) {
			wrong.add(left + " views still open after the section closed its own");
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("Addon map views:\n  " + String.join("\n  ", wrong));
		}
		Diorama.LOGGER.info("addon-api view: every check passed");
	}

	private static DioramaApi api() {
		return TestAddon.api();
	}

	private static Scene scene(ClientGameTestContext context, BlockPos at) {
		return context.computeOnClient(mc -> {
			Entity pig = null;
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (EntityType.getKey(entity.getType()).getPath().equals("pig") && Math.hypot(entity.getX() - (at.getX() + 7.3), entity.getZ() - (at.getZ() - 10.7)) < 0.5) {
					pig = entity;
				}
			}
			if (pig == null) {
				throw new AssertionError("The pig the view test summoned isn't on the client");
			}
			Waypoint waypoint = new Waypoint("View probe", at.getX() + 9, at.getY(), at.getZ() + 5, "minecraft:overworld", 0xFF46A758);
			WaypointStore.get().add(waypoint);
			return new Scene(mc.player.getX(), mc.player.getY(), mc.player.getZ(), at.getX() - 6 + 0.5, at.getY(), at.getZ() - 12 + 0.5, pig.getId(), waypoint);
		});
	}

	/** GUI width and height of the screen. */
	private static int[] screen(ClientGameTestContext context) {
		return context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
	}

	/**
	 * A place in the middle of the screen, half its size, its middle where the full map's is: the
	 * margins either side are the same whole number of pixels.
	 */
	private static ViewPlace middle(ClientGameTestContext context, Scene scene, double zoom) {
		int[] gui = screen(context);
		int w = gui[0] / 2;
		if ((gui[0] - w) % 2 != 0) {
			w--;
		}
		int h = gui[1] / 2;
		if ((gui[1] - h) % 2 != 0) {
			h--;
		}
		return ViewPlace.builder((gui[0] - w) / 2, (gui[1] - h) / 2, w, h).center(scene.px(), scene.py(), scene.pz()).zoom(zoom).build();
	}

	private static MapView open(ClientGameTestContext context, List<MapView> opened, MapView.Part... parts) {
		MapView view = context.computeOnClient(mc -> api().openView(parts.length == 0 ? EnumSet.noneOf(MapView.Part.class) : EnumSet.copyOf(Arrays.asList(parts))));
		opened.add(view);
		return view;
	}

	private static void close(ClientGameTestContext context, List<MapView> opened, MapView view) {
		context.runOnClient(mc -> view.close());
		opened.remove(view);
	}

	/** Shows a screen drawing these and waits for a few frames of it. */
	private static ViewScreen show(ClientGameTestContext context, ViewScreen.Draw... draws) {
		// A screen is made on the client thread: its constructor reads the game.
		ViewScreen screen = context.computeOnClient(mc -> new ViewScreen(List.of(draws)));
		context.setScreen(() -> screen);
		context.waitFor(mc -> screen.frames >= 5, 600);
		context.waitTicks(3);
		int from = screen.frames;
		context.waitFor(mc -> screen.frames >= from + 2, 600);
		if (screen.thrown != null) {
			throw new AssertionError("The view screen's draw threw", screen.thrown);
		}
		return screen;
	}

	/** Bad places are refused where they're built, views are limited per addon, and render() wants the game's graphics. */
	private static void limits(ClientGameTestContext context, List<MapView> opened, List<String> wrong) {
		refused(wrong, "a view 0 pixels wide", IllegalArgumentException.class, () -> ViewPlace.builder(0, 0, 0, 10));
		refused(wrong, "a view higher than MAX_SIZE", IllegalArgumentException.class, () -> ViewPlace.builder(0, 0, 10, ViewPlace.MAX_SIZE + 1));
		refused(wrong, "a view whose left edge is past MAX_SIZE", IllegalArgumentException.class, () -> ViewPlace.builder(-ViewPlace.MAX_SIZE - 1, 0, 10, 10));
		refused(wrong, "a place with no middle", IllegalStateException.class, () -> ViewPlace.builder(0, 0, 10, 10).build());
		refused(wrong, "a zoom of NaN", IllegalArgumentException.class, () -> ViewPlace.builder(0, 0, 10, 10).zoom(Double.NaN));
		refused(wrong, "a zoom past MAX_ZOOM", IllegalArgumentException.class, () -> ViewPlace.builder(0, 0, 10, 10).zoom(ViewPlace.MAX_ZOOM * 2));
		refused(wrong, "a zoom under MIN_ZOOM", IllegalArgumentException.class, () -> ViewPlace.builder(0, 0, 10, 10).zoom(ViewPlace.MIN_ZOOM / 2));
		refused(wrong, "a middle past where the game stops a player", IllegalArgumentException.class, () -> ViewPlace.builder(0, 0, 10, 10).center(Marker.MAX_COORDINATE + 1.0, 64, 0));
		refused(wrong, "a middle at a NaN height", IllegalArgumentException.class, () -> ViewPlace.builder(0, 0, 10, 10).center(0, Double.NaN, 0));
		ViewPlace.Builder partly = ViewPlace.builder(0, 0, 10, 10).center(1, 2, 3).zoom(3);
		refused(wrong, "a middle with a NaN z", IllegalArgumentException.class, () -> partly.center(4, 5, Double.NaN));
		refused(wrong, "a zoom of 0", IllegalArgumentException.class, () -> partly.zoom(0));
		ViewPlace kept = partly.build();
		if (kept.centerX() != 1 || kept.centerY() != 2 || kept.centerZ() != 3 || kept.zoom() != 3) {
			wrong.add("A builder after refused center(4, 5, NaN) and zoom(0) builds " + kept + ", not the middle 1, 2, 3 at zoom 3 it had");
		}
		ViewPlace place = ViewPlace.builder(0, 0, 10, 10).center(0, 64, 0).build();
		if (place.zoom() != ViewPlace.DEFAULT_ZOOM || !place.equals(ViewPlace.builder(0, 0, 10, 10).center(0, 64, 0).zoom(ViewPlace.DEFAULT_ZOOM).build())) {
			wrong.add("A place's zoom when none is given is " + place.zoom() + ", or two places made alike aren't equal");
		}

		String result = context.computeOnClient(mc -> {
			List<String> problems = new ArrayList<>();
			EnumSet<MapView.Part> asked = EnumSet.of(MapView.Part.PLAYER);
			MapView first = api().openView(asked);
			opened.add(first);
			asked.add(MapView.Part.RADAR);
			if (!first.parts().equals(EnumSet.of(MapView.Part.PLAYER))) {
				problems.add("a view's parts changed with the set it was opened with: " + first.parts());
			}
			MapView second = api().openView(EnumSet.noneOf(MapView.Part.class));
			opened.add(second);
			try {
				MapView third = api().openView(EnumSet.noneOf(MapView.Part.class));
				opened.add(third);
				problems.add("a third view opened: an addon has " + MapView.MAX_OPEN + " at most");
			} catch (IllegalStateException expected) {
				// Right.
			}
			if (AddonView.openCount() != 2) {
				problems.add(AddonView.openCount() + " views counted open with the addon's two");
			}
			try {
				api().openView(new HashSet<>(Arrays.asList(MapView.Part.PLAYER, null)));
				problems.add("a view opened with null among its parts");
			} catch (NullPointerException expected) {
				// Right.
			}
			try {
				first.render("not a graphics object", place, 0);
				problems.add("render() took a String for its graphics");
			} catch (IllegalArgumentException expected) {
				// Right.
			}
			try {
				first.project(place, Double.NaN, 64, 0);
				problems.add("project() took NaN");
			} catch (IllegalArgumentException expected) {
				// Right.
			}
			try {
				first.worldAt(place, Double.POSITIVE_INFINITY, 0, 64);
				problems.add("worldAt() took an infinite point");
			} catch (IllegalArgumentException expected) {
				// Right.
			}
			try {
				WorldPos far = first.worldAt(place, Double.MAX_VALUE, -Double.MAX_VALUE, 64);
				problems.add("worldAt() took a point at Double.MAX_VALUE and gave " + far);
			} catch (IllegalArgumentException expected) {
				// Right.
			}
			ViewPlace edge = ViewPlace.builder(-ViewPlace.MAX_SIZE, -ViewPlace.MAX_SIZE, ViewPlace.MAX_SIZE, ViewPlace.MAX_SIZE).center(0, 64, 0).zoom(ViewPlace.MIN_ZOOM).build();
			WorldPos corner = first.worldAt(edge, 2.0 * ViewPlace.MAX_SIZE, 2.0 * ViewPlace.MAX_SIZE, 64);
			if (!Double.isFinite(corner.x()) || !Double.isFinite(corner.z())) {
				problems.add("worldAt() as far out as it takes gave " + corner);
			}
			first.close();
			second.close();
			opened.remove(first);
			opened.remove(second);
			MapView again = api().openView(EnumSet.noneOf(MapView.Part.class));
			if (!again.isOpen()) {
				problems.add("a view opened after the addon closed its two isn't open");
			}
			again.close();
			if (AddonView.openCount() != 0) {
				problems.add(AddonView.openCount() + " views counted open after all were closed");
			}
			return String.join("; ", problems);
		});
		if (!result.isEmpty()) {
			wrong.add("Views per addon and render's arguments: " + result);
			return;
		}
		Diorama.LOGGER.info("addon-api view: bad places refused; two views an addon, the third refused; render() refused a String; parts copied");
	}

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

	/** From another thread, every view call throws, and opening one opens nothing. */
	private static void threads(ClientGameTestContext context, List<String> wrong) {
		DioramaApi api = context.computeOnClient(mc -> api());
		MapView view = context.computeOnClient(mc -> api().openView(EnumSet.noneOf(MapView.Part.class)));
		ViewPlace place = ViewPlace.builder(0, 0, 10, 10).center(0, 64, 0).build();
		try {
			refused(wrong, "openView() off the client thread", IllegalStateException.class, () -> api.openView(EnumSet.noneOf(MapView.Part.class)));
			refused(wrong, "render() off the client thread", IllegalStateException.class, () -> view.render(new Object(), place, 0));
			refused(wrong, "project() off the client thread", IllegalStateException.class, () -> view.project(place, 0, 64, 0));
			refused(wrong, "worldAt() off the client thread", IllegalStateException.class, () -> view.worldAt(place, 0, 0, 64));
			refused(wrong, "zoom() off the client thread", IllegalStateException.class, () -> view.zoom(place));
			refused(wrong, "close() off the client thread", IllegalStateException.class, () -> {
				view.close();
				return view;
			});
			int count = context.computeOnClient(mc -> AddonView.openCount());
			if (count != 1 || !context.computeOnClient(mc -> view.isOpen())) {
				wrong.add("Calls from another thread changed the open views: " + count + " open");
			}
		} finally {
			context.runOnClient(mc -> view.close());
		}
	}

	/** The broken addon opened a view before it threw: it was closed then, and one it opens now is closed. */
	private static void failedAddon(ClientGameTestContext context, List<String> wrong) {
		if (context.computeOnClient(mc -> BrokenAddon.view() == null)) {
			wrong.add("The broken addon never got as far as opening its view: is its jar installed?");
			return;
		}
		boolean open = context.computeOnClient(mc -> BrokenAddon.view().isOpen());
		boolean late = context.computeOnClient(mc -> {
			int count = AddonView.openCount();
			MapView view = BrokenAddon.api().openView(EnumSet.allOf(MapView.Part.class));
			return view.isOpen() || AddonView.openCount() != count;
		});
		if (open || late) {
			wrong.add("The broken addon's view, after it failed: " + (open ? "open" : "closed") + "; a view it opened after failing " + (late ? "opened" : "stayed closed"));
			return;
		}
		Diorama.LOGGER.info("addon-api view: the broken addon's view was closed when it failed, and one opened after stayed closed");
	}

	/**
	 * A view in the middle of the screen, with every part, shows what the full map shows looking
	 * at the same place at the same zoom: the probe marker where the full map puts it and where
	 * project() says, and the same pixels.
	 */
	private static void sameAsFullMap(ClientGameTestContext context, Scene scene, AddonMarkersTest.Recorder recorder, List<MapView> opened, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		AddonMarkersTest.openMap(context);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen map) {
				map.lookAt(Projection.u(scene.px(), scene.pz()), Projection.v(scene.px(), scene.py(), scene.pz()), ZOOM, TileKey.SURFACE);
			}
		});
		DioramaClientTest.settle(context, 10);
		float[] onMap = markerAfterFrames(context, recorder);
		Path mapShot = context.takeScreenshot("addon-view-01-full-map");

		MapView view = open(context, opened, MapView.Part.PLAYER, MapView.Part.RADAR, MapView.Part.WAYPOINTS);
		ViewScreen screen = show(context, new ViewScreen.Draw(view, place));
		DioramaClientTest.settle(context, 10);
		float[] inView = markerAfterFrames(context, recorder);
		Path viewShot = context.takeScreenshot("addon-view-02-view");
		ScreenPos projected = context.computeOnClient(mc -> view.project(place, scene.spotX(), scene.spotY(), scene.spotZ()));
		boolean drew = screen.returned.length == 1 && screen.returned[0];
		if (!drew || onMap == null || inView == null) {
			wrong.add("Same place as the full map: render() returned " + Arrays.toString(screen.returned) + "; the probe marker on the full map at " + text(onMap) + ", in the view at " + text(inView));
			close(context, opened, view);
			return;
		}
		boolean same = Math.abs(onMap[0] - inView[0]) <= CLOSE && Math.abs(onMap[1] - inView[1]) <= CLOSE;
		boolean asProjected = Math.abs(projected.x() - inView[0]) <= CLOSE && Math.abs(projected.y() - inView[1]) <= CLOSE;
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		BufferedImage map = read(mapShot);
		BufferedImage shot = read(viewShot);
		double differ = differing(map, shot, place, scale, 0);
		double shifted = differing(map, shot, place, scale, (int) Math.round(ZOOM * scale));
		boolean colour = near(shot, inView[0] * scale, inView[1] * scale, 1, SPOT_RGB);
		if (!same || !asProjected || differ > 0.02 || !colour) {
			wrong.add(String.format(Locale.ROOT, "Same place as the full map: the probe marker at %s in the view, %s on the full map, %s by project(); its colour %s there in the screenshot; "
				+ "%.2f%% of the view's pixels differ from the full map's (%.2f%% a block to the side)", text(inView), text(onMap), text(new float[] {(float) projected.x(), (float) projected.y()}),
				colour ? "is" : "isn't", differ * 100, shifted * 100));
		} else {
			Diorama.LOGGER.info(String.format(Locale.ROOT, "addon-api view: the probe marker at %s in the view, as on the full map and as project() says; %.2f%% of the view's pixels differ from the full map's, %.2f%% compared a block to the side",
				text(inView), differ * 100, shifted * 100));
		}
		close(context, opened, view);
	}

	/** Waits two more frames of the last view drawn with fullscreen look, and returns where the probe marker went. */
	private static float[] markerAfterFrames(ClientGameTestContext context, AddonMarkersTest.Recorder recorder) {
		int from = context.computeOnClient(mc -> recorder.frames[1]);
		context.waitFor(mc -> recorder.frames[1] >= from + 2, 600);
		return context.computeOnClient(mc -> {
			float[] at = recorder.markers.get(1).get(MOD + "/view-probe/0");
			return at == null ? null : at.clone();
		});
	}

	/**
	 * The share of pixels inside the place (less a 2-pixel rim) whose colour differs by more than
	 * 24 in a channel between the two images, the second read {@code dx} screen pixels to the right.
	 */
	private static double differing(BufferedImage a, BufferedImage b, ViewPlace place, double scale, int dx) {
		int x0 = (int) Math.ceil((place.x() + 2) * scale);
		int y0 = (int) Math.ceil((place.y() + 2) * scale);
		int x1 = (int) Math.floor((place.x() + place.width() - 2) * scale) - dx;
		int y1 = (int) Math.floor((place.y() + place.height() - 2) * scale);
		long count = 0;
		long differ = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				count++;
				if (!alike(a.getRGB(x, y), b.getRGB(x + dx, y), 24)) {
					differ++;
				}
			}
		}
		return count == 0 ? 1 : (double) differ / count;
	}

	private static boolean alike(int p, int q, int by) {
		return Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) <= by && Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) <= by && Math.abs((p & 0xFF) - (q & 0xFF)) <= by;
	}

	/** Pixels that differ by more than 40 in a channel, in a box of {@code reach} GUI pixels round (x, y). */
	private static int changed(BufferedImage a, BufferedImage b, double x, double y, double reach, double scale) {
		int x0 = (int) Math.floor((x - reach) * scale);
		int y0 = (int) Math.floor((y - reach) * scale);
		int x1 = (int) Math.ceil((x + reach) * scale);
		int y1 = (int) Math.ceil((y + reach) * scale);
		int changed = 0;
		for (int py = Math.max(0, y0); py < Math.min(a.getHeight(), y1); py++) {
			for (int px = Math.max(0, x0); px < Math.min(a.getWidth(), x1); px++) {
				if (!alike(a.getRGB(px, py), b.getRGB(px, py), 40)) {
					changed++;
				}
			}
		}
		return changed;
	}

	/**
	 * Each part shows only in a view opened with it: against a view of the map alone, the arrow
	 * changes the pixels where the player is, the radar where the pig is, the waypoints where the
	 * waypoint is, and nothing else changes those spots. The radar and waypoints follow the
	 * player's settings for the map.
	 */
	private static void parts(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		String[] names = {"map alone", "player", "radar", "waypoints", "all, radar and waypoints off on the map"};
		MapView.Part[][] asked = {{}, {MapView.Part.PLAYER}, {MapView.Part.RADAR}, {MapView.Part.WAYPOINTS}, MapView.Part.values()};
		BufferedImage[] shots = new BufferedImage[asked.length];
		float[][] spots = null;
		for (int i = 0; i < asked.length; i++) {
			MapView view = open(context, opened, asked[i]);
			if (i == 4) {
				context.runOnClient(mc -> {
					Settings.get().radarOnMap = false;
					Settings.get().waypointsOnMap = false;
				});
			}
			ViewScreen screen = show(context, new ViewScreen.Draw(view, place));
			if (spots == null) {
				spots = context.computeOnClient(mc -> spots(scene, AddonView.lastDrawn));
			}
			shots[i] = read(context.takeScreenshot("addon-view-0" + (3 + i) + "-parts-" + names[i].replaceAll("[^a-z]+", "-")));
			if (!screen.returned[0]) {
				wrong.add("Parts: the view with " + names[i] + " returned false");
			}
			context.runOnClient(mc -> {
				Settings.get().radarOnMap = true;
				Settings.get().waypointsOnMap = true;
			});
			close(context, opened, view);
		}
		if (spots[1] == null || spots[2] == null) {
			wrong.add("Parts: the radar didn't place the pig (" + (spots[1] != null) + ") or the waypoints the waypoint (" + (spots[2] != null) + ") in the view");
			return;
		}
		// What each view changes from the map alone, at the player [0], the pig [1] and the waypoint [2].
		int[][] change = new int[asked.length][3];
		double[] reach = {4, 3, 4};
		for (int i = 1; i < asked.length; i++) {
			for (int k = 0; k < 3; k++) {
				change[i][k] = changed(shots[0], shots[i], spots[k][0], spots[k][1], reach[k], scale);
			}
		}
		List<String> problems = new ArrayList<>();
		for (int i = 1; i < 4; i++) {
			for (int k = 0; k < 3; k++) {
				boolean shouldChange = k == i - 1;
				if (shouldChange != change[i][k] >= 4) {
					problems.add(names[i] + " changed " + change[i][k] + " pixels at " + new String[] {"the player", "the pig", "the waypoint"}[k]);
				}
			}
		}
		if (change[4][0] < 4 || change[4][1] >= 4 || change[4][2] >= 4) {
			problems.add("with the radar and waypoints off on the map, every part changed " + Arrays.toString(change[4]) + " pixels at the player, the pig and the waypoint");
		}
		if (!problems.isEmpty()) {
			wrong.add("Parts: " + String.join("; ", problems));
			return;
		}
		Diorama.LOGGER.info("addon-api view: parts: pixels changed at the player, pig and waypoint: player {}, radar {}, waypoints {}, all with the map's radar and waypoints off {}",
			Arrays.toString(change[1]), Arrays.toString(change[2]), Arrays.toString(change[3]), Arrays.toString(change[4]));
	}

	/** Where the player, the pig and the waypoint's pin are in this view, in GUI pixels; the pin's middle between its point and head. Client thread. */
	private static float[][] spots(Scene scene, MapViewport.View view) {
		Entity viewer = MapSession.viewer();
		float[] player = {view.screenX(Projection.u(viewer.getX(), viewer.getZ())), view.screenY(Projection.v(viewer.getX(), viewer.getY(), viewer.getZ()))};
		float[] pig = null;
		for (Radar.Blip blip : Radar.collect(view, 1, true)) {
			if (blip.entity().getId() == scene.pigId()) {
				pig = new float[] {blip.x(), blip.y()};
			}
		}
		float[] pin = null;
		List<WaypointOverlay.Pin> pins = WaypointOverlay.layout(view, List.of(scene.waypoint()), true);
		if (!pins.isEmpty()) {
			pin = new float[] {pins.get(0).x(), (pins.get(0).y() + pins.get(0).headY()) / 2};
		}
		return new float[][] {player, pig, pin};
	}

	/**
	 * A view hanging off the screen's left edge draws from the edge on, with the probe marker
	 * where project() says; one as big as allowed draws the screen and no more.
	 */
	private static void cropped(ClientGameTestContext context, Scene scene, AddonMarkersTest.Recorder recorder, List<MapView> opened, List<String> wrong) {
		ViewPlace middle = middle(context, scene, ZOOM);
		ViewPlace place = ViewPlace.builder(-middle.width() / 4, middle.y(), middle.width(), middle.height()).center(scene.px(), scene.py(), scene.pz()).zoom(ZOOM).build();
		MapView view = open(context, opened);
		show(context, new ViewScreen.Draw(view, place));
		float[] marker = markerAfterFrames(context, recorder);
		MapViewport.View drawn = context.computeOnClient(mc -> AddonView.lastDrawn);
		ScreenPos projected = context.computeOnClient(mc -> view.project(place, scene.spotX(), scene.spotY(), scene.spotZ()));
		context.takeScreenshot("addon-view-08-off-the-edge");
		int[] gui = screen(context);
		ViewPlace huge = ViewPlace.builder(-ViewPlace.MAX_SIZE / 2, -ViewPlace.MAX_SIZE / 2, ViewPlace.MAX_SIZE, ViewPlace.MAX_SIZE).center(scene.px(), scene.py(), scene.pz()).zoom(ZOOM).build();
		show(context, new ViewScreen.Draw(view, huge));
		MapViewport.View all = context.computeOnClient(mc -> AddonView.lastDrawn);
		close(context, opened, view);
		boolean edge = drawn.x() == 0 && drawn.width() == place.x() + place.width() && drawn.y() == place.y() && drawn.height() == place.height();
		boolean placed = marker != null && Math.abs(marker[0] - projected.x()) <= CLOSE && Math.abs(marker[1] - projected.y()) <= CLOSE;
		boolean screenOnly = all.x() == 0 && all.y() == 0 && all.width() == gui[0] && all.height() == gui[1];
		if (!edge || !placed || !screenOnly) {
			wrong.add("Off the screen: a view at x " + place.x() + ", " + place.width() + " wide, drew " + drawn.x() + " to " + (drawn.x() + drawn.width()) + " across, its marker at " + text(marker)
				+ ", project() " + String.format(Locale.ROOT, "(%.3f, %.3f)", projected.x(), projected.y()) + "; one " + ViewPlace.MAX_SIZE + " square drew " + all.width() + "x" + all.height()
				+ " at " + all.x() + ", " + all.y() + " on a " + gui[0] + "x" + gui[1] + " screen");
			return;
		}
		Diorama.LOGGER.info("addon-api view: a view off the left edge drew {} wide from 0, marker as project() says; a {} square drew the {}x{} screen", drawn.width(), ViewPlace.MAX_SIZE, gui[0], gui[1]);
	}

	/** Zoomed out past what the map can show, the view draws where the full map stops, and zoom() says so. */
	private static void zoomOut(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		ViewPlace middle = middle(context, scene, ZOOM);
		ViewPlace far = ViewPlace.builder(middle.x(), middle.y(), middle.width(), middle.height()).center(scene.px(), scene.py(), scene.pz()).zoom(ViewPlace.MIN_ZOOM).build();
		ViewPlace three = ViewPlace.builder(middle.x(), middle.y(), middle.width(), middle.height()).center(scene.px(), scene.py(), scene.pz()).zoom(3).build();
		MapView view = open(context, opened);
		show(context, new ViewScreen.Draw(view, far));
		double[] got = context.computeOnClient(mc -> new double[] {view.zoom(far), AddonView.lastDrawn.zoom(), MapScreen.minZoom(MapSession.get().map().hasOverviews()), view.zoom(three)});
		context.takeScreenshot("addon-view-09-zoomed-out");
		close(context, opened, view);
		if (got[0] != got[2] || got[1] != got[2] || got[3] != 3) {
			wrong.add("Zoomed out: zoom() says " + got[0] + " and the view drew at " + got[1] + " where the full map stops at " + got[2] + "; zoom() of a place at 3 says " + got[3]);
			return;
		}
		Diorama.LOGGER.info("addon-api view: asked for {} GUI pixels a block, the view drew at {}, where the full map stops", ViewPlace.MIN_ZOOM, got[1]);
	}

	/** worldAt() undoes project(), facing north and facing east. */
	private static void roundTrip(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		MapView view = open(context, opened);
		String result = context.computeOnClient(mc -> {
			List<String> problems = new ArrayList<>();
			Facing was = Projection.facing();
			ScreenPos north = null;
			try {
				for (Facing facing : new Facing[] {Facing.NORTH, Facing.EAST}) {
					Projection.setFacing(facing);
					ScreenPos at = view.project(place, scene.spotX(), scene.spotY() + 3, scene.spotZ());
					WorldPos back = view.worldAt(place, at.x(), at.y(), scene.spotY() + 3);
					if (Math.abs(back.x() - scene.spotX()) > 1e-6 || Math.abs(back.z() - scene.spotZ()) > 1e-6 || back.y() != scene.spotY() + 3) {
						problems.add("facing " + facing + ", worldAt(project()) gave " + back + " for " + scene.spotX() + ", " + (scene.spotY() + 3) + ", " + scene.spotZ());
					}
					if (facing == Facing.NORTH) {
						north = at;
					} else if (north != null && Math.abs(north.x() - at.x()) < 1 && Math.abs(north.y() - at.y()) < 1) {
						problems.add("project() gave the same point facing north and east: " + at);
					}
				}
			} finally {
				Projection.setFacing(was);
			}
			return String.join("; ", problems);
		});
		close(context, opened, view);
		if (!result.isEmpty()) {
			wrong.add("project() and worldAt(): " + result);
			return;
		}
		Diorama.LOGGER.info("addon-api view: worldAt() undid project() facing north and east");
	}

	/**
	 * Past the most a frame draws across addons, render() returns false, once with a warning: the
	 * test addon's two views and a second addon's view in one frame. Two views each drawn once both
	 * draw.
	 */
	private static void budget(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		long before = lines(context, "drew a map view after addons' views had been drawn");
		MapView one = open(context, opened);
		MapView two = open(context, opened);
		AddonWaypointsTest.Probe second = AddonWaypointsTest.Probe.make(context, "view_budget");
		MapView third;
		boolean[] tried;
		int frames;
		boolean[] both;
		try {
			third = context.computeOnClient(mc -> second.api.openView(EnumSet.noneOf(MapView.Part.class)));
			ViewScreen thrice = show(context, new ViewScreen.Draw(one, place), new ViewScreen.Draw(two, place), new ViewScreen.Draw(third, place));
			tried = thrice.returned.clone();
			frames = thrice.frames;
			ViewScreen twice = show(context, new ViewScreen.Draw(one, place), new ViewScreen.Draw(two, place));
			both = twice.returned.clone();
			context.runOnClient(mc -> third.close());
		} finally {
			second.forget(context);
		}
		close(context, opened, one);
		close(context, opened, two);
		boolean[] three = tried;
		long warned = lines(context, "drew a map view after addons' views had been drawn") - before;
		if (!Arrays.equals(three, new boolean[] {true, true, false}) || !Arrays.equals(both, new boolean[] {true, true}) || warned != (before == 0 ? 1 : 0)) {
			wrong.add("A frame's draws: three a frame returned " + Arrays.toString(three) + " over " + frames + " frames, two views once each " + Arrays.toString(both) + "; " + warned + " new warnings");
			return;
		}
		Diorama.LOGGER.info("addon-api view: three draws a frame returned {}, warned once; two views once each {}", Arrays.toString(three), Arrays.toString(both));
	}

	/** The left and right halves of the screen's middle band: the left one on the player, the right one further east, where the fog is. */
	private static ViewPlace[] halves(ClientGameTestContext context, Scene scene, double zoom) {
		int[] gui = screen(context);
		int w = gui[0] / 2 - 12;
		int h = gui[1] / 2;
		int y = (gui[1] - h) / 2;
		return new ViewPlace[] {
			ViewPlace.builder(8, y, w, h).center(scene.px(), scene.py(), scene.pz()).zoom(zoom).build(),
			ViewPlace.builder(gui[0] - 8 - w, y, w, h).center(scene.px() + 200, scene.py(), scene.pz()).zoom(zoom).build(),
		};
	}

	/** Shows a screen drawing these with {@code around} round them, and waits for a few frames of it. */
	private static ViewScreen showAround(ClientGameTestContext context, ViewScreen.Around around, ViewScreen.Draw... draws) {
		ViewScreen screen = context.computeOnClient(mc -> {
			ViewScreen made = new ViewScreen(List.of(draws));
			made.around = around;
			return made;
		});
		context.setScreen(() -> screen);
		context.waitFor(mc -> screen.frames >= 5, 600);
		context.waitTicks(3);
		int from = screen.frames;
		context.waitFor(mc -> screen.frames >= from + 2, 600);
		if (screen.thrown != null) {
			throw new AssertionError("The view screen's draw threw", screen.thrown);
		}
		return screen;
	}

	/**
	 * A view draws once a frame: two views at two places in one frame each show their own fog, as
	 * each does drawn alone, and one view drawn at two places draws the first and refuses the
	 * second, with one warning, leaving the first's fog as it was.
	 */
	private static void onceAFrame(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		ViewPlace[] at = halves(context, scene, 1);
		long before = lines(context, "drew the same map view twice in a frame");
		MapView a = open(context, opened);
		MapView b = open(context, opened);
		BufferedImage alone1;
		BufferedImage alone2;
		BufferedImage both;
		BufferedImage twice;
		boolean[] bothReturned;
		boolean[] twiceReturned;
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		try (WorldPause paused = WorldPause.pause(context)) {
			show(context, new ViewScreen.Draw(a, at[0]));
			alone1 = read(context.takeScreenshot("addon-view-13-left-alone"));
			show(context, new ViewScreen.Draw(b, at[1]));
			alone2 = read(context.takeScreenshot("addon-view-14-right-alone"));
			ViewScreen two = show(context, new ViewScreen.Draw(a, at[0]), new ViewScreen.Draw(b, at[1]));
			both = read(context.takeScreenshot("addon-view-15-two-views"));
			bothReturned = two.returned.clone();
			ViewScreen one = show(context, new ViewScreen.Draw(a, at[0]), new ViewScreen.Draw(a, at[1]));
			twice = read(context.takeScreenshot("addon-view-16-one-view-twice"));
			twiceReturned = one.returned.clone();
		}
		close(context, opened, a);
		close(context, opened, b);
		long warned = lines(context, "drew the same map view twice in a frame") - before;
		double left = differing(alone1, both, at[0], scale, 0);
		double right = differing(alone2, both, at[1], scale, 0);
		double first = differing(alone1, twice, at[0], scale, 0);
		double apart = differing(alone1, alone2, at[0], scale, 0);
		boolean secondBlank = blank(twice, at[1], context);
		if (!Arrays.equals(bothReturned, new boolean[] {true, true}) || !Arrays.equals(twiceReturned, new boolean[] {true, false}) || left > 0.03 || right > 0.03 || first > 0.03
			|| !secondBlank || warned != (before == 0 ? 1 : 0)) {
			wrong.add(String.format(Locale.ROOT, "Once a frame: two views returned %s, %.2f%% and %.2f%% of their pixels differ from each drawn alone; one view at two places returned %s, "
				+ "%.2f%% of the first place differs from it drawn alone, the second place %s the backdrop; %d new warnings (the two places differ by %.2f%%)",
				Arrays.toString(bothReturned), left * 100, right * 100, Arrays.toString(twiceReturned), first * 100, secondBlank ? "is" : "isn't", warned, apart * 100));
			return;
		}
		Diorama.LOGGER.info(String.format(Locale.ROOT, "addon-api view: two views in a frame differ %.2f%% and %.2f%% from each alone; one view twice returned [true, false] and its first place differs %.2f%% (places differ %.2f%%)",
			left * 100, right * 100, first * 100, apart * 100));
	}

	/**
	 * An addon that opens a view, draws it and closes it within every frame of its screen: every
	 * draw draws, since each frame has the whole budget; the textures it drew with stay until the
	 * frame is drawn, so the picture is right; and nothing is held once the screen is gone.
	 */
	private static void renderThenClose(ClientGameTestContext context, Scene scene, List<String> wrong) {
		ViewPlace place = halves(context, scene, 1)[0];
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		List<Boolean> results = java.util.Collections.synchronizedList(new ArrayList<>());
		BufferedImage alone;
		BufferedImage each;
		int frames;
		try (WorldPause paused = WorldPause.pause(context)) {
			MapView reference = context.computeOnClient(mc -> api().openView(EnumSet.noneOf(MapView.Part.class)));
			show(context, new ViewScreen.Draw(reference, place));
			alone = read(context.takeScreenshot("addon-view-17-drawn-alone"));
			context.runOnClient(mc -> reference.close());
			ViewScreen screen = showAround(context, (g, a, draws) -> {
				MapView view = api().openView(EnumSet.noneOf(MapView.Part.class));
				try {
					results.add(view.render(g, place, a));
				} finally {
					view.close();
				}
			});
			int from = screen.frames;
			context.waitFor(mc -> screen.frames >= from + 10, 600);
			each = read(context.takeScreenshot("addon-view-18-opened-drawn-closed-each-frame"));
			frames = screen.frames;
		}
		context.setScreen(() -> null);
		context.waitTicks(3);
		int retiring = context.computeOnClient(mc -> AddonView.retiringCount());
		int open = context.computeOnClient(mc -> AddonView.openCount());
		List<Boolean> got;
		synchronized (results) {
			got = new ArrayList<>(results);
		}
		long refused = got.stream().filter(r -> !r).count();
		double differ = differing(alone, each, place, scale, 0);
		if (got.size() < 10 || refused != 0 || differ > 0.03 || retiring != 0 || open != 0) {
			wrong.add(String.format(Locale.ROOT, "Opened, drawn and closed each frame: %d of %d draws over %d frames returned false (%s); %.2f%% of the place differs from a view drawn alone; "
				+ "after the screen, %d viewports wait to be closed and %d views are open", refused, got.size(), frames, got.subList(0, Math.min(8, got.size())), differ * 100, retiring, open));
			return;
		}
		Diorama.LOGGER.info(String.format(Locale.ROOT, "addon-api view: opened, drawn and closed in each of %d frames, every draw drew; %.2f%% differ from a view drawn alone; nothing held after", got.size(), differ * 100));
	}

	/**
	 * Inside an addon's narrower scissor (a scroll panel), a view draws nothing outside it, the fog
	 * included, on Fast and on Fancy.
	 */
	private static void scissored(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		ViewPlace middle = middle(context, scene, 1);
		ViewPlace place = ViewPlace.builder(middle.x(), middle.y(), middle.width(), middle.height()).center(scene.px() + 250, scene.py(), scene.pz()).zoom(1).build();
		int x0 = place.x() + place.width() / 3;
		int x1 = place.x() + 2 * place.width() / 3;
		Settings.Quality was = context.computeOnClient(mc -> Settings.get().quality);
		List<String> problems = new ArrayList<>();
		MapView view = open(context, opened);
		try {
			for (Settings.Quality quality : new Settings.Quality[] {Settings.Quality.FAST, Settings.Quality.FANCY}) {
				context.runOnClient(mc -> {
					Settings.get().quality = quality;
					Settings.get().apply();
				});
				DioramaClientTest.settle(context, 10);
				showAround(context, (g, a, draws) -> {
					g.enableScissor(x0, place.y(), x1, place.y() + place.height());
					try {
						draws.run();
					} finally {
						g.disableScissor();
					}
				}, new ViewScreen.Draw(view, place));
				BufferedImage shot = read(context.takeScreenshot("addon-view-19-in-a-scissor-" + quality.name().toLowerCase(Locale.ROOT)));
				double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
				int outside = drawnIn(shot, scale, place.x(), place.y(), x0 - 1, place.y() + place.height()) + drawnIn(shot, scale, x1 + 1, place.y(), place.x() + place.width(), place.y() + place.height());
				int inside = drawnIn(shot, scale, x0 + 1, place.y(), x1 - 1, place.y() + place.height());
				if (outside > 0 || inside == 0) {
					problems.add(quality + ": " + outside + " pixels drawn outside the scissor, " + inside + " inside");
				}
			}
		} finally {
			context.runOnClient(mc -> {
				Settings.get().quality = was;
				Settings.get().apply();
			});
			close(context, opened, view);
		}
		DioramaClientTest.settle(context, 10);
		if (!problems.isEmpty()) {
			wrong.add("In a narrower scissor: " + String.join("; ", problems));
			return;
		}
		Diorama.LOGGER.info("addon-api view: inside a narrower scissor, nothing drawn outside it on Fast or Fancy");
	}

	/**
	 * With Fast fog (from 1.21.6, where the fog is cut to a rectangle of its own), a view drawn with
	 * the caller's pose moved 100 pixels right looks as the same view drawn 100 pixels right
	 * without the move, its fog included, and draws nothing round where it lands.
	 */
	private static void movedPose(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		//? if >=1.21.6 {
		int[] gui = screen(context);
		int size = 50;
		int shift = 100;
		int x = gui[0] / 2 - (size + shift) / 2;
		int y = gui[1] / 2 - size / 2;
		ViewPlace moved = ViewPlace.builder(x, y, size, size).center(scene.px() + 250, scene.py(), scene.pz()).zoom(1).build();
		ViewPlace there = ViewPlace.builder(x + shift, y, size, size).center(scene.px() + 250, scene.py(), scene.pz()).zoom(1).build();
		Settings.Quality was = context.computeOnClient(mc -> Settings.get().quality);
		MapView view = open(context, opened);
		BufferedImage straight;
		BufferedImage shifted;
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		try {
			context.runOnClient(mc -> {
				Settings.get().quality = Settings.Quality.FAST;
				Settings.get().apply();
			});
			DioramaClientTest.settle(context, 10);
			try (WorldPause paused = WorldPause.pause(context)) {
				show(context, new ViewScreen.Draw(view, there));
				straight = read(context.takeScreenshot("addon-view-20-fast-fog-in-place"));
				showAround(context, (g, a, draws) -> {
					g.pose().pushMatrix();
					g.pose().translate(shift, 0);
					try {
						draws.run();
					} finally {
						g.pose().popMatrix();
					}
				}, new ViewScreen.Draw(view, moved));
				shifted = read(context.takeScreenshot("addon-view-20-fast-fog-pose-moved"));
			}
		} finally {
			context.runOnClient(mc -> {
				Settings.get().quality = was;
				Settings.get().apply();
			});
			close(context, opened, view);
		}
		DioramaClientTest.settle(context, 10);
		int left = there.x();
		int right = there.x() + size;
		int shown = drawnIn(straight, scale, left + 1, y + 1, right - 1, y + size - 1);
		double differ = differing(straight, shifted, there, scale, 0);
		int round = drawnIn(shifted, scale, x - 20, y - 20, left - 1, y + size + 20) + drawnIn(shifted, scale, right + 1, y - 20, right + 20, y + size + 20)
			+ drawnIn(shifted, scale, left, y - 20, right, y - 1) + drawnIn(shifted, scale, left, y + size + 1, right, y + size + 20);
		if (shown == 0 || differ > 0.03 || round > 0) {
			wrong.add(String.format(Locale.ROOT, "Fast fog with the pose moved: %.2f%% of the view differs from it drawn in place without the move (%d pixels drawn in place); %d pixels drawn round where it landed",
				differ * 100, shown, round));
			return;
		}
		Diorama.LOGGER.info(String.format(Locale.ROOT, "addon-api view: with the pose moved %d px, Fast fog and land differ %.2f%% from the view drawn in place; nothing drawn round it", shift, differ * 100));
		//?}
	}

	/** Pixels in this GUI rectangle that aren't the view screen's backdrop, sampled every third. */
	private static int drawnIn(BufferedImage image, double scale, int x0, int y0, int x1, int y1) {
		int count = 0;
		for (int y = (int) Math.ceil(y0 * scale); y < (int) (y1 * scale) && y < image.getHeight(); y += 3) {
			for (int x = (int) Math.ceil(x0 * scale); x < (int) (x1 * scale) && x < image.getWidth(); x += 3) {
				if (!alike(image.getRGB(x, y), 0x101418, 4)) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * A throw in the middle of a view's drawing, with a pose and a scissor of Diorama's still
	 * pushed, leaves the caller's transform and scissor as they were; and a view drawn from inside a
	 * view's drawing is refused.
	 */
	private static void balanced(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		MapView view = open(context, opened);
		MapView other = open(context, opened);
		String[] seen = new String[3];
		boolean[] done = new boolean[1];
		try {
			// The first frame only: before 26.1 the test shim draws frames between the test's calls,
			// after the view below is closed too.
			showAround(context, (g, a, draws) -> {
				if (done[0]) {
					return;
				}
				done[0] = true;
				g.enableScissor(place.x() + 10, place.y() + 10, place.x() + 60, place.y() + 40);
				Object pose = g.pose();
				String before = poseText(g) + " " + scissorText(g);
				AddonView.insideDrawForTests = inner -> {
					//? if >=1.21.6 {
					inner.pose().pushMatrix();
					inner.pose().translate(37, 0);
					//?} else {
					/*inner.pose().pushPose();
					inner.pose().translate(37, 0, 0);
					*///?}
					inner.enableScissor(0, 0, 5, 5);
					try {
						seen[1] = "returned " + other.render(inner, place, a);
					} catch (IllegalStateException e) {
						seen[1] = e.getMessage();
					}
					throw new IllegalStateException("planted");
				};
				try {
					view.render(g, place, a);
					seen[0] = "didn't throw";
				} catch (IllegalStateException e) {
					seen[0] = e.getMessage();
				} finally {
					AddonView.insideDrawForTests = null;
				}
				String after = poseText(g) + " " + scissorText(g);
				seen[2] = g.pose() == pose && before.equals(after) ? null : "before " + before + ", after " + after + (g.pose() == pose ? "" : ", another pose stack");
				g.disableScissor();
			});
		} finally {
			AddonView.insideDrawForTests = null;
			close(context, opened, view);
			close(context, opened, other);
		}
		if (!"planted".equals(seen[0]) || seen[1] == null || !seen[1].contains("while a map view was drawing") || seen[2] != null) {
			wrong.add("A throw inside a view's drawing: render() " + seen[0] + "; a view drawn from inside it: " + seen[1] + "; the caller's pose and scissor: " + (seen[2] == null ? "as they were" : seen[2]));
			return;
		}
		Diorama.LOGGER.info("addon-api view: a throw inside the drawing left the caller's pose and scissor as they were; a view drawn from inside a view's drawing was refused");
	}

	/** The caller's transform, as text. */
	private static String poseText(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		//? if >=1.21.6 {
		return "pose " + new org.joml.Matrix3x2f(g.pose());
		//?} else {
		/*return "pose " + new org.joml.Matrix4f(g.pose().last().pose());
		*///?}
	}

	/** The scissor the caller's draws are cut to now, as text. */
	private static String scissorText(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		//? if >=1.21.6 {
		return "scissor " + g.scissorStack.peek();
		//?} else if <1.21.5 {
		/*int[] box = new int[4];
		org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_SCISSOR_BOX, box);
		return "scissor " + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST) + " " + Arrays.toString(box);
		*///?} else {
		/*return "scissor (not read on 1.21.5)";
		*///?}
	}

	/**
	 * Through 1.21.4 the GUI draws straight away with OpenGL, and the fog layer into a framebuffer
	 * of its own: after a view, the framebuffer (up to 1.21.1), viewport and blending the caller
	 * had are as they were.
	 */
	private static void legacyState(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		//? if <1.21.5 {
		/*ViewPlace place = middle(context, scene, 1);
		Settings.Quality was = context.computeOnClient(mc -> Settings.get().quality);
		context.runOnClient(mc -> {
			Settings.get().quality = Settings.Quality.FANCY;
			Settings.get().apply();
		});
		String[] seen = new String[2];
		try {
			// A new view, so its fog layer is drawn at its first draw; once.
			showAround(context, (g, a, draws) -> {
				if (seen[0] != null) {
					return;
				}
				MapView view = api().openView(EnumSet.noneOf(MapView.Part.class));
				try {
					g.flush();
					bindCallerTarget();
					com.mojang.blaze3d.platform.GlStateManager._viewport(0, 0, 64, 64);
					com.mojang.blaze3d.systems.RenderSystem.enableBlend();
					com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ZERO, org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ONE);
					seen[0] = glState();
					view.render(g, place, a);
					g.flush();
					seen[1] = glState();
				} finally {
					view.close();
					com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
					net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
				}
			});
		} finally {
			context.runOnClient(mc -> {
				Settings.get().quality = was;
				Settings.get().apply();
			});
		}
		DioramaClientTest.settle(context, 10);
		if (seen[0] == null || !seen[0].equals(seen[1])) {
			wrong.add("OpenGL state round a view: before " + seen[0] + ", after " + seen[1]);
			return;
		}
		Diorama.LOGGER.info("addon-api view: the caller's OpenGL state was as it was after a view: {}", seen[1]);
		*///?}
	}

	//? if <1.21.2 {
	/*// The window's own framebuffer, not the game's main one, as an addon drawing into a target of its own would have.
	private static void bindCallerTarget() {
		com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
	}

	private static String framebufferText() {
		return "framebuffer " + org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING) + ", ";
	}
	*///?} else {
	// From 1.21.2 every GUI draw binds the game's main framebuffer, so only the main one is kept.
	private static void bindCallerTarget() {
	}

	private static String framebufferText() {
		return "";
	}
	//?}

	//? if <1.21.5 {
	/*private static String glState() {
		int[] viewport = new int[4];
		org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);
		return framebufferText() + "viewport " + Arrays.toString(viewport)
			+ ", blend " + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND) + " " + org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB)
			+ "/" + org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB) + "/" + org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA)
			+ "/" + org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
	}
	*///?}

	/**
	 * With the graphics object of an earlier frame, called from a client tick, render() throws
	 * nothing and nothing shows: the game never draws what's handed to graphics outside a frame.
	 */
	private static void keptGraphics(ClientGameTestContext context, Scene scene, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		AddonView view = (AddonView) context.computeOnClient(mc -> api().openView(EnumSet.noneOf(MapView.Part.class)));
		try {
			ViewScreen screen = show(context);
			Object kept = screen.lastGraphics;
			String result = context.computeOnClient(mc -> {
				try {
					return "returned " + view.render(kept, place, 0);
				} catch (RuntimeException e) {
					return "threw " + e;
				}
			});
			int from = screen.frames;
			context.waitFor(mc -> screen.frames >= from + 2, 600);
			BufferedImage shot = read(context.takeScreenshot("addon-view-21-kept-graphics"));
			boolean nothing = blank(shot, place, context);
			if (result.startsWith("threw") || !nothing) {
				wrong.add("render() from a tick with an earlier frame's graphics " + result + "; the place " + (nothing ? "shows" : "doesn't show") + " only the backdrop");
				return;
			}
			Diorama.LOGGER.info("addon-api view: render() from a tick with an earlier frame's graphics {} and nothing showed", result);
		} finally {
			context.runOnClient(mc -> view.close());
			context.setScreen(() -> null);
		}
	}

	/**
	 * A screen drawing a view stays open through a resource reload. While the loading overlay is up
	 * the game draws the open screen itself (through 1.21.5 by calling its render straight, not
	 * through the method screens are drawn by otherwise), and the view's draws there throw nothing.
	 */
	private static void reloadWithScreenOpen(ClientGameTestContext context, Scene scene, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		MapView view = context.computeOnClient(mc -> api().openView(EnumSet.noneOf(MapView.Part.class)));
		java.util.concurrent.atomic.AtomicInteger underOverlay = new java.util.concurrent.atomic.AtomicInteger();
		try {
			ViewScreen screen = showAround(context, (g, a, draws) -> {
				if (net.minecraft.client.Minecraft.getInstance().gui.overlay() != null) {
					underOverlay.incrementAndGet();
				}
				draws.run();
			}, new ViewScreen.Draw(view, place));
			java.util.concurrent.CompletableFuture<Void> done = context.computeOnClient(mc -> mc.reloadResourcePacks());
			context.waitFor(mc -> done.isDone() && mc.gui.overlay() == null, 2400);
			done.join();
			context.waitTicks(10);
			boolean open = context.computeOnClient(mc -> mc.gui.screen() == screen);
			// Through 1.21.5 the overlay draws the screen as it fades in and out; later versions are
			// only counted.
			//? if <=1.21.5 {
			/*boolean drawnUnder = underOverlay.get() > 0;
			*///?} else {
			boolean drawnUnder = true;
			//?}
			if (screen.thrown != null || !drawnUnder || !open) {
				wrong.add("A resource reload with a view's screen open: " + (screen.thrown == null ? "nothing thrown" : "the screen's draw threw " + screen.thrown) + "; the screen drawn "
					+ underOverlay.get() + " times under the loading overlay; " + (open ? "still open" : "no longer open"));
				return;
			}
			Diorama.LOGGER.info("addon-api view: a resource reload with a view's screen open drew it {} times under the loading overlay, nothing thrown", underOverlay.get());
		} finally {
			context.runOnClient(mc -> view.close());
			context.setScreen(() -> null);
			DioramaClientTest.settle(context, 20);
		}
	}

	/** A resource reload lets go of a view's fog of war and label grid, and its next draw makes them again. */
	private static void reloadLetsGo(ClientGameTestContext context, Scene scene, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		AddonView view = (AddonView) context.computeOnClient(mc -> api().openView(EnumSet.noneOf(MapView.Part.class)));
		try {
			show(context, new ViewScreen.Draw(view, place));
			boolean drawn = context.computeOnClient(mc -> view.holdsMemory());
			context.setScreen(() -> null);
			DioramaClientTest.reloadPacks(context, mc -> {
			});
			boolean afterReload = context.computeOnClient(mc -> view.holdsMemory());
			int retiring = context.computeOnClient(mc -> AddonView.retiringCount());
			DioramaClientTest.settle(context, 20);
			ViewScreen again = show(context, new ViewScreen.Draw(view, place));
			boolean redrawn = context.computeOnClient(mc -> view.holdsMemory());
			if (!drawn || afterReload || retiring != 0 || !redrawn || !again.returned[0]) {
				wrong.add("Resource reload: fog held after a draw " + drawn + ", after the reload " + afterReload + " (" + retiring + " viewports waiting to close); drawn again it returned "
					+ again.returned[0] + " and holds it " + redrawn);
				return;
			}
			Diorama.LOGGER.info("addon-api view: a resource reload let go of a view's fog, and its next draw made it again");
		} finally {
			context.runOnClient(mc -> view.close());
			context.setScreen(() -> null);
		}
	}

	/**
	 * Just after a change of dimension, Minecraft has the new level while Diorama may still hold
	 * the old one's session until a tick: a view then draws nothing and returns false. Here the
	 * player goes to the Nether, and the game's level is set back to the Overworld's for one draw.
	 */
	private static void twoLevels(ClientGameTestContext context, TestSingleplayerContext world, Scene scene, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		net.minecraft.client.multiplayer.ClientLevel overworld = context.computeOnClient(mc -> mc.level);
		MapView view = context.computeOnClient(mc -> api().openView(EnumSet.noneOf(MapView.Part.class)));
		Boolean[] mixed = new Boolean[1];
		boolean[] matched = new boolean[1];
		try {
			world.getServer().runCommand("execute in minecraft:the_nether run tp @a " + scene.px() / 8 + " 128 " + scene.pz() / 8);
			context.waitFor(mc -> mc.level != overworld && MapSession.get() != null && MapSession.get().isFor(mc.level), 2400);
			context.waitTicks(20);
			showAround(context, (g, a, draws) -> {
				// The first frame only, as in balanced().
				if (mixed[0] != null) {
					return;
				}
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				net.minecraft.client.multiplayer.ClientLevel real = mc.level;
				mc.level = overworld;
				try {
					mixed[0] = view.render(g, place, a);
				} finally {
					mc.level = real;
				}
			});
			ViewScreen same = show(context, new ViewScreen.Draw(view, place));
			matched[0] = same.returned[0];
		} finally {
			context.runOnClient(mc -> view.close());
			context.setScreen(() -> null);
			world.getServer().runCommand("execute in minecraft:overworld run tp @a " + scene.px() + " " + scene.py() + " " + scene.pz() + " 180 0");
			context.waitFor(mc -> mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && MapSession.get() != null && MapSession.get().isFor(mc.level)
				&& MapSession.get().map().chunkCount() > 100, 2400);
			DioramaClientTest.settle(context, 20);
		}
		if (mixed[0] == null || mixed[0] || !matched[0]) {
			wrong.add("Two levels at once: with the game's level not Diorama's, render() returned " + mixed[0] + "; with both the Nether's it returned " + matched[0]);
			return;
		}
		Diorama.LOGGER.info("addon-api view: with the game's level not the session's, render() returned false; with both the same it drew");
	}

	/** A view holds its fog from its first draw until close; closed, it draws nothing. */
	private static void closed(ClientGameTestContext context, Scene scene, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		AddonView view = (AddonView) context.computeOnClient(mc -> api().openView(EnumSet.noneOf(MapView.Part.class)));
		boolean beforeDraw = context.computeOnClient(mc -> view.holdsMemory());
		show(context, new ViewScreen.Draw(view, place));
		boolean drawn = context.computeOnClient(mc -> view.holdsMemory());
		// The handle lets go at once; the textures wait for the next frame to start.
		int[] waiting = new int[1];
		boolean afterClose = context.computeOnClient(mc -> {
			view.close();
			waiting[0] = AddonView.retiringCount();
			return view.holdsMemory() || view.isOpen();
		});
		ViewScreen screen = show(context, new ViewScreen.Draw(view, place));
		Path shot = context.takeScreenshot("addon-view-10-closed");
		int count = context.computeOnClient(mc -> AddonView.openCount());
		int left = context.computeOnClient(mc -> AddonView.retiringCount());
		context.runOnClient(mc -> view.close());
		if (beforeDraw || !drawn || afterClose || waiting[0] != 1 || left != 0 || screen.returned[0] || count != 0 || !blank(read(shot), place, context)) {
			wrong.add("Closing: fog held before the first draw " + beforeDraw + ", after it " + drawn + ", after close (or still open) " + afterClose + "; viewports waiting to close: "
				+ waiting[0] + " at close, " + left + " frames later; a closed view's render returned " + screen.returned[0] + " and its place "
				+ (blank(read(shot), place, context) ? "is" : "isn't") + " the screen's backdrop; " + count + " views open");
			return;
		}
		Diorama.LOGGER.info("addon-api view: fog held from the first draw until close; a closed view returned false and drew nothing");
	}

	/** Whether the place shows only the view screen's own backdrop. */
	private static boolean blank(BufferedImage image, ViewPlace place, ClientGameTestContext context) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		for (int y = (int) Math.ceil(place.y() * scale); y < (int) ((place.y() + place.height()) * scale); y += 3) {
			for (int x = (int) Math.ceil(place.x() * scale); x < (int) ((place.x() + place.width()) * scale); x += 3) {
				if (!alike(image.getRGB(x, y), 0x101418, 4)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * A screen an addon might make: two views side by side in frames, one close with every part
	 * and one further out with the map alone, and a layer of places and a road on both.
	 */
	private static void showcase(ClientGameTestContext context, Scene scene, List<MapView> opened, List<String> wrong) {
		int[] gui = screen(context);
		int w = (gui[0] - 36) / 2;
		int h = gui[1] - 40;
		ViewPlace near = ViewPlace.builder(12, 20, w, h).center(scene.px(), scene.py(), scene.pz()).zoom(3).build();
		ViewPlace farther = ViewPlace.builder(24 + w, 20, w, h).center(scene.px(), scene.py(), scene.pz()).zoom(0.75).build();
		MapLayer places = context.computeOnClient(mc -> api().openLayer("view-showcase"));
		try {
			context.runOnClient(mc -> places.replace(LayerContent.builder()
				.add(MapLine.builder(DimensionId.OVERWORLD).point(scene.px() - 60, scene.py(), scene.pz() + 20).point(scene.px() - 10, scene.py(), scene.pz() + 8)
					.point(scene.px() + 30, scene.py(), scene.pz() - 25).point(scene.px() + 80, scene.py(), scene.pz() - 30).rgb(0xE0B050).width(3).build())
				.add(Marker.builder(DimensionId.OVERWORLD, scene.px() - 10, scene.py(), scene.pz() + 8).icon(ResourceId.of("diorama:home")).label("Inn").build())
				.add(Marker.builder(DimensionId.OVERWORLD, scene.px() + 30, scene.py(), scene.pz() - 25).icon(ResourceId.of("diorama:villagers")).label("Market").build())
				.build()));
			MapView close = open(context, opened, MapView.Part.values());
			MapView wide = open(context, opened);
			ViewScreen screen = context.computeOnClient(mc -> new ViewScreen(List.of(new ViewScreen.Draw(close, near), new ViewScreen.Draw(wide, farther))));
			screen.framed = true;
			context.setScreen(() -> screen);
			context.waitFor(mc -> screen.frames >= 5, 600);
			DioramaClientTest.settle(context, 20);
			context.takeScreenshot("addon-view-11-showcase");
			if (screen.thrown != null || !Arrays.equals(screen.returned, new boolean[] {true, true})) {
				wrong.add("Showcase: render() returned " + Arrays.toString(screen.returned) + (screen.thrown != null ? ", threw " + screen.thrown : ""));
			}
			close(context, opened, close);
			close(context, opened, wide);
		} finally {
			context.runOnClient(mc -> places.close());
		}
	}

	/** With the map off in the player's dimension, a view draws nothing and returns false; on again, it draws. */
	private static void mapOff(ClientGameTestContext context, Scene scene, List<String> wrong) {
		ViewPlace place = middle(context, scene, ZOOM);
		MapView view = context.computeOnClient(mc -> api().openView(EnumSet.noneOf(MapView.Part.class)));
		Set<String> was = context.computeOnClient(mc -> Set.copyOf(Settings.get().mapOffDimensions));
		try {
			context.runOnClient(mc -> Settings.get().mapOffDimensions.add("minecraft:overworld"));
			context.waitFor(mc -> MapSession.get() == null, 200);
			ViewScreen off = show(context, new ViewScreen.Draw(view, place));
			Path shot = context.takeScreenshot("addon-view-12-map-off");
			boolean returned = off.returned[0];
			boolean blank = blank(read(shot), place, context);
			context.runOnClient(mc -> {
				Settings.get().mapOffDimensions.clear();
				Settings.get().mapOffDimensions.addAll(was);
			});
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
			DioramaClientTest.settle(context, 20);
			ViewScreen on = show(context, new ViewScreen.Draw(view, place));
			if (returned || !blank || !on.returned[0]) {
				wrong.add("Map off in the Overworld: render() returned " + returned + ", the place " + (blank ? "is" : "isn't") + " the screen's backdrop; on again it returned " + on.returned[0]);
				return;
			}
			Diorama.LOGGER.info("addon-api view: with the map off in the Overworld, render() returned false and drew nothing; on again it drew");
		} finally {
			context.runOnClient(mc -> {
				view.close();
				Settings.get().mapOffDimensions.clear();
				Settings.get().mapOffDimensions.addAll(was);
			});
			context.setScreen(() -> null);
		}
	}

	private static long lines(ClientGameTestContext context, String text) {
		String log = context.computeOnClient(mc -> {
			try {
				return new String(Files.readAllBytes(mc.gameDirectory.toPath().resolve("logs/latest.log")), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		return log.lines().filter(line -> line.contains(text)).count();
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
				if (px >= 0 && py >= 0 && px < image.getWidth() && py < image.getHeight() && alike(image.getRGB(px, py), rgb, 8)) {
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
