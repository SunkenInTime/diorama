package dev.diorama.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.Diorama;
import dev.diorama.MinimapHud;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.addon.Addons;
import dev.diorama.addon.HudHides;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import dev.diorama.testaddon.TestAddon;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The settings' Addons tab ({@code addons-settings}), with the test addons in testaddon/ installed
 * (boat_run.py --mods testaddon/build/libs after ./gradlew :testaddon:assemble). The good addon
 * hides the minimap from onReady at the title screen and keeps the handle. The tab lists all seven
 * test mods by the names their loader's metadata gives, the six that failed as stopped; the Minimap
 * tab says which addon hides the minimap; unticking it with a real click brings the minimap back
 * with the handle still open, writes the mod ID to config.json, and ticking it hides the minimap
 * again. The rows follow what addons hide while the screen is open, and a small window turns the
 * list into pages. It ends with the good addon switched off, for {@code addons-restart}: run that
 * next on the same warm VM with DIORAMA_KEEP_CONFIG=1, and the game starts again with that file.
 */
final class AddonsSettingsTest {
	private static final String GOOD = "diorama_test_addon";
	/** Each test mod's name, as its fabric.mod.json, mods.toml and neoforge.mods.toml all give it. */
	private static final Map<String, String> NAMES = new LinkedHashMap<>();

	static {
		NAMES.put(GOOD, "Diorama Test Addon");
		for (String kind : List.of("assertion", "broken", "linkage", "overflow", "recursive", "unprintable")) {
			NAMES.put(GOOD + "_" + kind, "Diorama Test Addon (" + kind + ")");
		}
	}

	private AddonsSettingsTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		context.setScreen(() -> null);
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);
		Path config = config(context);
		if (!context.computeOnClient(mc -> TestAddon.fromReady() != null && TestAddon.fromReady().isOpen() && HudHides.minimapHidden()
			&& Settings.get().addonHidesOff.isEmpty())) {
			throw new AssertionError("Before the Addons tab: the test addon's handle from onReady isn't hiding the minimap, or an addon is switched off already");
		}
		listing(context);
		context.takeScreenshot("addons-settings-01-minimap-hidden");
		if (context.computeOnClient(mc -> MinimapHud.shown() != null)) {
			throw new AssertionError("The minimap is drawn while the test addon hides it");
		}

		// The Minimap tab: the line under the checkboxes names the addon.
		openSettings(context, 0);
		String note = Component.translatable("diorama.settings.addons.minimap_hidden", NAMES.get(GOOD)).getString();
		if (!context.computeOnClient(mc -> shown(mc, note) != null)) {
			throw new AssertionError("The Minimap tab doesn't say \"" + note + "\"");
		}
		context.takeScreenshot("addons-settings-02-minimap-tab");

		// The Addons tab, sixth: every test mod, named, with what it's doing.
		openSettings(context, 5);
		expectStates(context, "the Addons tab, first opened", "diorama.settings.addons.hiding.minimap");
		context.takeScreenshot("addons-settings-03-addons-tab");

		// Unticked with a real click: the minimap comes back at once, the handle stays the addon's.
		clickTick(context, NAMES.get(GOOD));
		if (!context.computeOnClient(mc -> Settings.get().addonHidesOff.equals(Set.of(GOOD)) && !HudHides.minimapHidden() && TestAddon.fromReady().isOpen())) {
			throw new AssertionError("Unticking the test addon: switched off " + context.computeOnClient(mc -> Settings.get().addonHidesOff)
				+ ", minimap hidden by addons " + context.computeOnClient(mc -> HudHides.minimapHidden()) + ", its handle open " + context.computeOnClient(mc -> TestAddon.fromReady().isOpen()));
		}
		savedOff(config, Set.of(GOOD), "after unticking the test addon");
		expectStates(context, "after unticking it", "diorama.settings.addons.wants.minimap");
		context.getInput().setCursorPos(2, 2);
		context.waitTicks(3);
		context.takeScreenshot("addons-settings-04-switched-off");
		openSettings(context, 0);
		if (context.computeOnClient(mc -> shown(mc, note) != null)) {
			throw new AssertionError("The Minimap tab still says an addon hides it after the addon was switched off");
		}
		context.takeScreenshot("addons-settings-05-minimap-tab-off");
		context.setScreen(() -> null);
		context.waitTicks(3);
		context.takeScreenshot("addons-settings-06-minimap-back");
		if (context.computeOnClient(mc -> MinimapHud.shown() == null || !TestAddon.fromReady().isOpen())) {
			throw new AssertionError("With the test addon switched off and its handle open, the minimap isn't drawn");
		}

		// Ticked again: hidden again, at once, by the same handle.
		openSettings(context, 5);
		clickTick(context, NAMES.get(GOOD));
		if (!context.computeOnClient(mc -> Settings.get().addonHidesOff.isEmpty() && HudHides.minimapHidden() && TestAddon.fromReady().isOpen())) {
			throw new AssertionError("Ticking the test addon again didn't hide the minimap with its handle");
		}
		savedOff(config, Set.of(), "after ticking the test addon again");
		context.setScreen(() -> null);
		context.waitTicks(3);
		context.takeScreenshot("addons-settings-07-hidden-again");
		if (context.computeOnClient(mc -> MinimapHud.shown() != null)) {
			throw new AssertionError("The minimap is drawn after ticking the test addon again");
		}

		// The rows follow what the addon does while the screen is open: a second handle on both parts.
		openSettings(context, 5);
		Handle both = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.MINIMAP, Hud.Part.WORLD_WAYPOINTS));
		context.waitTicks(3);
		expectStates(context, "with a handle on both parts", "diorama.settings.addons.hiding.both");
		context.getInput().setCursorPos(2, 2);
		context.waitTicks(3);
		context.takeScreenshot("addons-settings-08-hiding-both");
		context.runOnClient(mc -> both.close());
		context.waitTicks(3);
		expectStates(context, "after closing that handle", "diorama.settings.addons.hiding.minimap");
		context.setScreen(() -> null);

		smallWindow(context);

		// Left switched off, for addons-restart to find in config.json.
		openSettings(context, 5);
		clickTick(context, NAMES.get(GOOD));
		context.setScreen(() -> null);
		savedOff(config, Set.of(GOOD), "at the end, for addons-restart");
		Diorama.LOGGER.info("addons-settings: listed, switched off and on with real clicks, saved; left off for addons-restart");
	}

	/**
	 * The game started again with config.json from the end of {@link #run}, the test addon
	 * switched off: at the title screen its handle from onReady is open and hides nothing; in the
	 * world the minimap is drawn and the tab shows it unticked; ticking it hides the minimap.
	 */
	static void restartTitleScreen(ClientGameTestContext context) {
		context.waitFor(mc -> TestAddon.api() != null, 200);
		Set<String> off = context.computeOnClient(mc -> new TreeSet<>(Settings.get().addonHidesOff));
		if (!off.equals(Set.of(GOOD))) {
			throw new AssertionError("The game started with addons switched off " + off + ", not [" + GOOD + "]: run addons-restart right after addons-settings, on the same warm VM, with DIORAMA_KEEP_CONFIG=1");
		}
		if (!context.computeOnClient(mc -> TestAddon.fromReady().isOpen() && !HudHides.minimapHidden())) {
			throw new AssertionError("After a restart with the test addon switched off, its handle from onReady hides the minimap, or is closed");
		}
		Diorama.LOGGER.info("addons-restart: started with {} switched off; its handle from onReady is open and hides nothing", GOOD);
	}

	static void restartWorld(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		context.setScreen(() -> null);
		context.waitTicks(220);
		context.takeScreenshot("addons-restart-01-minimap-shown");
		if (context.computeOnClient(mc -> MinimapHud.shown() == null)) {
			throw new AssertionError("After a restart with the test addon switched off, the minimap isn't drawn");
		}
		openSettings(context, 5);
		expectStates(context, "after a restart", "diorama.settings.addons.wants.minimap");
		boolean ticked = context.computeOnClient(mc -> tick(mc, NAMES.get(GOOD)).selected());
		if (ticked) {
			throw new AssertionError("After a restart, the test addon is ticked in the Addons tab");
		}
		context.getInput().setCursorPos(2, 2);
		context.waitTicks(3);
		context.takeScreenshot("addons-restart-02-addons-tab");
		clickTick(context, NAMES.get(GOOD));
		context.setScreen(() -> null);
		context.waitTicks(3);
		context.takeScreenshot("addons-restart-03-ticked");
		if (context.computeOnClient(mc -> MinimapHud.shown() != null || !HudHides.minimapHidden())) {
			throw new AssertionError("Ticking the test addon after a restart didn't hide the minimap");
		}
		savedOff(config(context), Set.of(), "after ticking it after the restart");
		Diorama.LOGGER.info("addons-restart: the minimap showed, the tab had it unticked, ticking it hid the minimap");
	}

	/** The listing: all seven test mods by name, the good one running and hiding the minimap, the six failed ones stopped. */
	private static void listing(ClientGameTestContext context) {
		List<Addons.Listed> listed = context.computeOnClient(mc -> Addons.listed());
		Map<String, String> names = new LinkedHashMap<>();
		List<String> wrong = new ArrayList<>();
		for (Addons.Listed one : listed) {
			names.put(one.modId(), one.name());
			boolean good = one.modId().equals(GOOD);
			if (one.running() != good || !one.allowed() || one.minimap() != good || one.worldWaypoints()) {
				wrong.add(one.toString());
			}
		}
		if (!names.equals(NAMES) || !wrong.isEmpty()) {
			throw new AssertionError("The addons listed: " + names + " (expected " + NAMES + "); wrong states: " + wrong);
		}
		Diorama.LOGGER.info("addons-settings: listed {}", listed);
	}

	/**
	 * The rows on the Addons tab: one checkbox per test mod named as its metadata says, ticked
	 * unless switched off, with the good addon's state as {@code goodState} and the rest stopped.
	 * The page shown is the first: the good addon's row is on it, first by name, and the others on
	 * it say stopped. On 1.20.1, whose checkboxes are taller, seven rows take two pages.
	 */
	private static void expectStates(ClientGameTestContext context, String when, String goodState) {
		List<String> problems = context.computeOnClient(mc -> {
			List<String> found = new ArrayList<>();
			String stopped = Component.translatable("diorama.settings.addons.stopped").getString();
			String good = Component.translatable(goodState).getString();
			List<AbstractWidget> widgets = visible(mc);
			for (Map.Entry<String, String> mod : NAMES.entrySet()) {
				// A page at a time on a small window: the row only has to be there.
				Checkbox tick = tick(mc, mod.getValue());
				if (tick == null) {
					found.add("no checkbox for " + mod.getValue());
				} else if (tick.selected() == Settings.get().addonHidesOff.contains(mod.getKey())) {
					found.add(mod.getValue() + " ticked " + tick.selected());
				}
			}
			long goods = widgets.stream().filter(w -> w.getMessage().getString().equals(good)).count();
			long stops = widgets.stream().filter(w -> w.getMessage().getString().equals(stopped)).count();
			int rows = rows(mc);
			Checkbox first = tick(mc, NAMES.get(GOOD));
			if (goods != 1 || stops != rows - 1 || rows < 2 || first == null || !first.visible) {
				found.add(rows + " rows shown, " + goods + " say \"" + good + "\", " + stops + " say \"" + stopped + "\"");
			}
			return found;
		});
		if (!problems.isEmpty()) {
			throw new AssertionError("The Addons tab " + when + ": " + problems);
		}
	}

	/**
	 * A small window, 1280x720 (a 427x240 GUI): the seven rows take more than one page, nothing on
	 * a page overlaps or runs off, and the arrow turns the page until every row has shown once.
	 */
	private static void smallWindow(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.waitTicks(10);
		openSettings(context, 5);
		List<Integer> pages = new ArrayList<>();
		for (int page = 1; page <= NAMES.size(); page++) {
			context.getInput().setCursorPos(2, 2);
			context.waitTicks(3);
			context.takeScreenshot("addons-settings-09-small-page-" + page);
			pages.add(context.computeOnClient(mc -> rows(mc)));
			noOverlaps(context, "on page " + page);
			if (!context.computeOnClient(mc -> {
				AbstractWidget next = shown(mc, ">");
				return next != null && next.active;
			})) {
				break;
			}
			DioramaClientTest.click(context, ">");
		}
		if (pages.size() < 2 || pages.stream().mapToInt(Integer::intValue).sum() != NAMES.size() || pages.contains(0)) {
			throw new AssertionError("A small window's pages showed " + pages + " rows, of " + NAMES.size());
		}
		context.setScreen(() -> null);
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(10);
	}

	private static int rows(Minecraft mc) {
		return (int) visible(mc).stream().filter(w -> w instanceof Checkbox).count();
	}

	private static void noOverlaps(ClientGameTestContext context, String where) {
		context.runOnClient(mc -> {
			List<AbstractWidget> widgets = visible(mc);
			int width = mc.gui.screen().width;
			int height = mc.gui.screen().height;
			for (AbstractWidget a : widgets) {
				if (a.getX() < 0 || a.getY() < 0 || a.getRight() > width || a.getBottom() > height) {
					throw new AssertionError(a.getMessage().getString() + " runs off the screen " + where);
				}
				for (AbstractWidget b : widgets) {
					if (a != b && a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
						throw new AssertionError(a.getMessage().getString() + " overlaps " + b.getMessage().getString() + " " + where);
					}
				}
			}
		});
	}

	private static void openSettings(ClientGameTestContext context, int tab) {
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(tab));
		context.waitTicks(3);
	}

	/** Clicks the addon's checkbox on its box, as a player would; turns the page to it first if it's on another. */
	private static void clickTick(ClientGameTestContext context, String name) {
		double[] at = context.computeOnClient(mc -> {
			Checkbox tick = tick(mc, name);
			if (tick == null || !tick.visible) {
				throw new AssertionError("No checkbox to click for " + name);
			}
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(tick.getX() + 8) * scale, (tick.getY() + tick.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		// The screen is made again on the next tick, with the rows as they are now.
		context.waitTicks(5);
	}

	private static @Nullable Checkbox tick(Minecraft mc, String name) {
		List<AbstractWidget> widgets = new ArrayList<>();
		collect(mc.gui.screen(), widgets);
		for (AbstractWidget widget : widgets) {
			if (widget instanceof Checkbox checkbox && widget.getMessage().getString().equals(name)) {
				return checkbox;
			}
		}
		return null;
	}

	private static @Nullable AbstractWidget shown(Minecraft mc, String text) {
		for (AbstractWidget widget : visible(mc)) {
			if (widget.getMessage().getString().equals(text)) {
				return widget;
			}
		}
		return null;
	}

	private static List<AbstractWidget> visible(Minecraft mc) {
		List<AbstractWidget> widgets = new ArrayList<>();
		collect(mc.gui.screen(), widgets);
		widgets.removeIf(widget -> !widget.visible);
		return widgets;
	}

	private static void collect(ContainerEventHandler parent, List<AbstractWidget> out) {
		for (var child : parent.children()) {
			if (child instanceof ContainerEventHandler container) {
				collect(container, out);
			} else if (child instanceof AbstractWidget widget) {
				out.add(widget);
			}
		}
	}

	private static Path config(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve("config.json"));
	}

	/** config.json on disk lists exactly these mod IDs as switched off. */
	private static void savedOff(Path config, Set<String> expected, String when) {
		String text;
		try {
			text = Files.readString(config, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		JsonElement field = JsonParser.parseString(text).getAsJsonObject().get("addonHidesOff");
		Set<String> saved = new TreeSet<>();
		if (field instanceof JsonArray array) {
			array.forEach(id -> saved.add(id.getAsString()));
		}
		if (!(field instanceof JsonArray) || !saved.equals(expected)) {
			throw new AssertionError("config.json " + when + " has addonHidesOff " + field + ", not " + expected);
		}
	}
}
