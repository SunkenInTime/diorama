package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;

/**
 * The settings on a small window, 1280x720 at GUI scale 3 (a 426x240 GUI), where most tabs are
 * taller than the room between the tab bar and Done and scroll, as vanilla's options lists do.
 * Every tab is laid out with nothing overlapping or past the settings side, photographed at its
 * top and its bottom, and on the Waypoints tab: a slider cut off at the tab's bottom can't be
 * pointed at or clicked where it's cut off, the wheel brings it into view where a click works, Tab brings
 * the last setting into view, another tab starts at the top, and a resize keeps the place.
 */
final class SettingsScrollTest {
	private static final String[] TABS = {"minimap", "radar", "overlays", "waypoints", "look"};
	private static final int WAYPOINTS = 3;
	private static final int LOOK = 4;

	private SettingsScrollTest() {
	}

	static void run(ClientGameTestContext context) {
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.getInput().resizeWindow(1280, 720);
		DioramaClientTest.setGuiScale(context, 3);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.getInput().setCursorPos(0, 0);
		context.takeScreenshot("settings-05-small-screen");
		for (int tab = 0; tab < TABS.length; tab++) {
			int index = tab;
			context.runOnClient(mc -> screen(mc).selectTab(index));
			context.waitTicks(3);
			String wrong = context.computeOnClient(SettingsScrollTest::laidOutWrong);
			if (wrong != null) {
				throw new AssertionError("Settings tab " + TABS[tab] + " on a 1280x720 window: " + wrong);
			}
			String name = "settings-05-small-" + (tab + 1) + "-" + TABS[tab];
			context.takeScreenshot(name + "-top");
			if (context.computeOnClient(mc -> screen(mc).maxScroll()) > 0) {
				wheelToBottom(context);
				context.takeScreenshot(name + "-bottom");
				String cut = context.computeOnClient(mc -> {
					ScreenRectangle area = screen(mc).tabArea();
					List<AbstractWidget> widgets = screen(mc).tabWidgets();
					AbstractWidget last = widgets.get(widgets.size() - 1);
					return last.getBottom() <= area.bottom() ? null : last.getMessage().getString() + " ends at " + last.getBottom() + " below the tab's " + area.bottom();
				});
				if (cut != null) {
					throw new AssertionError("Scrolled to the bottom of " + TABS[tab] + ", " + cut);
				}
			}
		}

		// Back to Waypoints from Look: it starts at the top again, though it was left at its bottom.
		context.runOnClient(mc -> screen(mc).selectTab(WAYPOINTS));
		context.waitTicks(2);
		if (context.computeOnClient(mc -> screen(mc).scroll()) != 0) {
			throw new AssertionError("Waypoints kept its scroll after another tab was picked: " + context.computeOnClient(mc -> screen(mc).scroll()));
		}

		// At the top, the Distance slider runs past the tab's bottom edge, cut off there. Pointed at
		// and clicked where it's cut off, over the line above Done, it neither lights up nor changes.
		AbstractWidget slider = context.computeOnClient(mc -> distanceSlider(mc));
		double[] below = context.computeOnClient(mc -> {
			ScreenRectangle area = screen(mc).tabArea();
			if (slider.getY() >= area.bottom() || slider.getBottom() <= area.bottom() + 2) {
				throw new AssertionError("The Distance slider (" + slider.getY() + " to " + slider.getBottom() + ") isn't cut off by the tab's bottom " + area.bottom());
			}
			return new double[] {slider.getX() + 6, area.bottom() + 2};
		});
		int distance = context.computeOnClient(mc -> Settings.get().waypointDistance);
		pointAt(context, below[0], below[1]);
		context.waitTicks(3);
		context.takeScreenshot("settings-05-small-cut-off-pointed-at");
		if (context.computeOnClient(mc -> slider.isHovered())) {
			throw new AssertionError("A slider cut off at the tab's bottom lights up under the pointer there");
		}
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		if (!(context.computeOnClient(mc -> mc.gui.screen()) instanceof SettingsScreen) || context.computeOnClient(mc -> Settings.get().waypointDistance) != distance) {
			throw new AssertionError("A click below the tab reached the slider cut off there: distance " + distance + " became "
				+ context.computeOnClient(mc -> Settings.get().waypointDistance));
		}

		// The wheel over the settings brings it into view, and then a click at its right end works.
		double[] middle = context.computeOnClient(mc -> {
			ScreenRectangle area = screen(mc).tabArea();
			return new double[] {area.left() + 1, area.top() + area.height() / 2.0};
		});
		pointAt(context, middle[0], middle[1]);
		for (int i = 0; i < 20 && !context.computeOnClient(mc -> slider.getBottom() <= screen(mc).tabArea().bottom()); i++) {
			context.getInput().scroll(-1);
			context.waitTick();
		}
		int scrolled = context.computeOnClient(mc -> screen(mc).scroll());
		if (scrolled <= 0 || !context.computeOnClient(mc -> slider.getBottom() <= screen(mc).tabArea().bottom())) {
			throw new AssertionError("The wheel didn't bring the Distance slider into view: scrolled " + scrolled);
		}
		double[] end = context.computeOnClient(mc -> new double[] {slider.getRight() - 2, slider.getY() + slider.getHeight() / 2.0});
		pointAt(context, end[0], end[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		int farthest = Settings.WAYPOINT_DISTANCES[Settings.WAYPOINT_DISTANCES.length - 1];
		if (context.computeOnClient(mc -> Settings.get().waypointDistance) != farthest) {
			throw new AssertionError("Clicking the scrolled-in slider at its right end set the distance to "
				+ context.computeOnClient(mc -> Settings.get().waypointDistance) + ", not " + farthest);
		}
		context.runOnClient(mc -> {
			Settings.get().waypointDistance = distance;
			Settings.get().save();
		});

		// Tab moves the focus through the settings; the last one is scrolled into view, whole.
		context.runOnClient(mc -> {
			screen(mc).selectTab(LOOK);
			screen(mc).selectTab(WAYPOINTS);
		});
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		boolean reached = false;
		for (int i = 0; i < 80 && !reached; i++) {
			context.getInput().pressKey(InputConstants.KEY_TAB);
			reached = context.computeOnClient(mc -> {
				List<AbstractWidget> widgets = screen(mc).tabWidgets();
				return mc.gui.screen().getFocused() == widgets.get(widgets.size() - 1);
			});
		}
		if (!reached) {
			throw new AssertionError("Tab never reached the Waypoints tab's last setting");
		}
		context.waitTicks(2);
		context.takeScreenshot("settings-05-small-tab-to-last");
		String hidden = context.computeOnClient(mc -> {
			ScreenRectangle area = screen(mc).tabArea();
			List<AbstractWidget> widgets = screen(mc).tabWidgets();
			AbstractWidget last = widgets.get(widgets.size() - 1);
			return last.getY() >= area.top() && last.getBottom() <= area.bottom() ? null : last.getMessage().getString() + " at " + last.getY() + " to " + last.getBottom();
		});
		if (hidden != null) {
			throw new AssertionError("Tabbed to, the last setting stayed out of view: " + hidden);
		}

		// A taller window: the tab keeps its place as far as it still scrolls.
		int before = context.computeOnClient(mc -> screen(mc).scroll());
		context.getInput().resizeWindow(1280, 780);
		context.waitTicks(5);
		int[] after = context.computeOnClient(mc -> new int[] {screen(mc).scroll(), screen(mc).maxScroll()});
		if (after[0] != Math.min(before, after[1])) {
			throw new AssertionError("Resized from " + before + " down the tab, it's at " + after[0] + " of " + after[1]);
		}
		context.getInput().resizeWindow(1280, 720);
		context.waitTicks(5);
		context.getInput().setCursorPos(0, 0);
		context.setScreen(() -> null);
		DioramaClientTest.setGuiScale(context, guiScale);
	}

	/** Checks every tab's layout on the open settings screen, {@code where} naming the window and language. */
	static void checkTabs(ClientGameTestContext context, String where) {
		for (int tab = 0; tab < TABS.length; tab++) {
			int index = tab;
			context.runOnClient(mc -> screen(mc).selectTab(index));
			context.waitTicks(2);
			String wrong = context.computeOnClient(SettingsScrollTest::laidOutWrong);
			if (wrong != null) {
				throw new AssertionError("Settings tab " + TABS[tab] + ", " + where + ": " + wrong);
			}
		}
	}

	/** Turns the wheel over the settings until the tab is at its bottom. */
	static void wheelToBottom(ClientGameTestContext context) {
		double[] middle = context.computeOnClient(mc -> {
			ScreenRectangle area = screen(mc).tabArea();
			return new double[] {area.left() + 1, area.top() + area.height() / 2.0};
		});
		pointAt(context, middle[0], middle[1]);
		for (int i = 0; i < 40 && context.computeOnClient(mc -> screen(mc).scroll() < screen(mc).maxScroll()); i++) {
			context.getInput().scroll(-1);
			context.waitTick();
		}
		int[] at = context.computeOnClient(mc -> new int[] {screen(mc).scroll(), screen(mc).maxScroll()});
		if (at[0] != at[1]) {
			throw new AssertionError("The wheel stopped at " + at[0] + " of " + at[1]);
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
	}

	/** Turns the wheel the other way until the tab is back at its top. */
	static void wheelToTop(ClientGameTestContext context) {
		double[] middle = context.computeOnClient(mc -> {
			ScreenRectangle area = screen(mc).tabArea();
			return new double[] {area.left() + 1, area.top() + area.height() / 2.0};
		});
		pointAt(context, middle[0], middle[1]);
		for (int i = 0; i < 40 && context.computeOnClient(mc -> screen(mc).scroll() > 0); i++) {
			context.getInput().scroll(1);
			context.waitTick();
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
	}

	/**
	 * What's wrong with the shown tab's layout, or null: a setting overlapping another, past the
	 * settings side or into the scrollbar's strip, or under the minimap preview.
	 */
	private static @Nullable String laidOutWrong(Minecraft mc) {
		SettingsScreen screen = screen(mc);
		ScreenRectangle area = screen.tabArea();
		List<AbstractWidget> widgets = screen.tabWidgets();
		int width = mc.gui.screen().width;
		// The preview's frame, at the default size in the top right.
		int preview = width - 6 - 128 - 2;
		// The scrollbar is 6 wide, 4 right of the settings, and the settings stop short of it.
		int right = screen.maxScroll() > 0 ? area.right() - 10 : area.right();
		for (AbstractWidget a : widgets) {
			if (!a.visible) {
				continue;
			}
			if (a.getX() < area.left() || a.getRight() > right) {
				return a.getMessage().getString() + " runs from " + a.getX() + " to " + a.getRight() + ", outside " + area.left() + " to " + right;
			}
			if (a.getRight() > preview && a.getY() < 6 + 128 + 2) {
				return a.getMessage().getString() + " reaches under the minimap preview";
			}
			if (a instanceof Checkbox && a.getHeight() > 20) {
				return "the checkbox " + a.getMessage().getString() + " is " + a.getHeight() + " high, its label on more than two lines";
			}
			for (AbstractWidget b : widgets) {
				if (a != b && b.visible && a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
					return a.getMessage().getString() + " overlaps " + b.getMessage().getString();
				}
			}
		}
		if (screen.maxScroll() == 0 && widgets.stream().anyMatch(w -> w.visible && w.getBottom() > area.bottom())) {
			return "a setting is cut off at the bottom of a tab that doesn't scroll";
		}
		return null;
	}

	/** The Waypoints tab's Distance slider. */
	private static AbstractWidget distanceSlider(Minecraft mc) {
		String label = net.minecraft.network.chat.Component.translatable("diorama.settings.waypoint_distance").getString() + ": ";
		for (AbstractWidget widget : screen(mc).tabWidgets()) {
			if (!(widget instanceof Checkbox) && widget.getMessage().getString().startsWith(label)) {
				return widget;
			}
		}
		throw new AssertionError("No " + label + "slider on the Waypoints tab");
	}

	private static SettingsScreen screen(Minecraft mc) {
		if (!(mc.gui.screen() instanceof SettingsScreen screen)) {
			throw new AssertionError("Expected the settings, found " + mc.gui.screen());
		}
		return screen;
	}

	/** Moves the pointer to a point given in GUI pixels. */
	private static void pointAt(ClientGameTestContext context, double x, double y) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(x * scale, y * scale);
		context.waitTick();
	}
}
