package dev.diorama;

import dev.diorama.render.Pins;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
//? if >=26.2 {
import net.minecraft.client.gui.components.tabs.MenuTabBar;
//?}
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
//?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The settings in vanilla tabs, one group at a time: a checkbox for each on/off setting, two to a
 * row, then a button for each choice and a slider for each number. The minimap stays sharp on the
 * right as a live preview, so a change shows on it straight away.
 */
public final class SettingsScreen extends Screen {
	/** Right edge of the settings side on a wide screen. */
	private static final int SIDE = 340;
	/** What the preview needs on the right at the default size: a 128 minimap, its frame and margin. */
	private static final int PREVIEW_ROOM = 140;
	/** The settings' width; two checkbox columns with a gap between them. */
	private static final int CONTENT = 280;
	private static final int COLUMN_GAP = 8;
	private static final int HEADING = 11;
	private static final int TAB_BAR_HEIGHT = 24;
	private static final Identifier LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");

	private final @Nullable Screen parent;
	private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
	private final MinimapHud preview = new MinimapHud();
	private @Nullable TabNavigationBar tabBar;
	private List<SettingsTab> tabs = List.of();
	/** Right edge of the settings side: left of the preview where there's room, else the whole screen. */
	private int side = SIDE;
	/** GPU baking's button, which says so if the check turns it off while the screen is open. */
	private @Nullable Button gpuBaking;
	private boolean gpuFailedShown;

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("diorama.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Settings s = Settings.get();
		// Resizing the window runs this again: stay on the same tab.
		Tab current = this.tabManager.getCurrentTab();
		int selected = current == null ? 0 : Math.max(0, this.tabs.indexOf(current));
		this.side = Math.max(Math.min(SIDE, this.width - PREVIEW_ROOM), Math.min(this.width, CONTENT + 10));

		SettingsTab minimap = new SettingsTab("diorama.settings.minimap");
		minimap.toggle("minimap_shown", () -> s.minimap, v -> s.minimap = v);
		minimap.toggle("compass", () -> s.compass, v -> s.compass = v);
		minimap.toggle("coordinates", () -> s.coordinates, v -> s.coordinates = v);
		minimap.toggle("biome", () -> s.biome, v -> s.biome = v);
		minimap.toggle("auto_caves", () -> s.autoCaves, v -> s.autoCaves = v);
		Settings.Corner[] corners = Settings.Corner.values();
		minimap.cycle("corner", () -> s.minimapCorner.ordinal(), corners.length, i -> s.minimapCorner = corners[i],
			i -> Component.translatable("diorama.settings.corner." + corners[i].name().toLowerCase(Locale.ROOT)));
		minimap.choice("game_time", Settings.GameTime.values(), () -> s.gameTime, v -> s.gameTime = v);
		// Beside Corner, so the tab doesn't grow.
		minimap.lastTwoSideBySide();
		int sizes = (Settings.MAX_SIZE - Settings.MIN_SIZE) / Settings.SIZE_STEP + 1;
		minimap.slider("size", (s.minimapSize - Settings.MIN_SIZE) / Settings.SIZE_STEP, sizes,
			i -> s.minimapSize = Settings.MIN_SIZE + i * Settings.SIZE_STEP, i -> Component.literal(Integer.toString(Settings.MIN_SIZE + i * Settings.SIZE_STEP)));
		int enlargedSizes = (Settings.MAX_ENLARGED - Settings.MIN_ENLARGED) / Settings.ENLARGED_STEP + 1;
		minimap.slider("enlarged", (s.minimapEnlarged - Settings.MIN_ENLARGED) / Settings.ENLARGED_STEP, enlargedSizes,
			i -> s.minimapEnlarged = Settings.MIN_ENLARGED + i * Settings.ENLARGED_STEP,
			i -> Component.literal(java.math.BigDecimal.valueOf(Settings.MIN_ENLARGED + i * Settings.ENLARGED_STEP, 2).stripTrailingZeros().toPlainString() + "×"));
		// Beside Size, so the tab doesn't grow.
		minimap.lastTwoSideBySide();
		minimap.slider("zoom", s.minimapZoom, Settings.ZOOMS.length, i -> s.minimapZoom = i, i -> Component.literal(Settings.ZOOMS[i] + "×"));
		int opacities = (Settings.MAX_OPACITY - Settings.MIN_OPACITY) / Settings.OPACITY_STEP + 1;
		minimap.slider("opacity", (Settings.snapOpacity(s.minimapOpacity) - Settings.MIN_OPACITY) / Settings.OPACITY_STEP, opacities,
			i -> s.minimapOpacity = Settings.MIN_OPACITY + i * Settings.OPACITY_STEP, i -> Component.literal((Settings.MIN_OPACITY + i * Settings.OPACITY_STEP) + "%"));
		// Beside Zoom, so the tab doesn't grow: it has no room for another row on a small screen.
		minimap.lastTwoSideBySide();
		// Side by side, so the tab stays short enough for a small screen.
		minimap.choice("shape", Settings.Shape.values(), () -> s.minimapShape, v -> s.minimapShape = v);
		minimap.choice("frame", Settings.Frame.values(), () -> s.minimapFrame, v -> s.minimapFrame = v);
		minimap.lastTwoSideBySide();

		SettingsTab radar = new SettingsTab("diorama.settings.radar");
		radar.toggle("radar_minimap", () -> s.radarOnMinimap, v -> s.radarOnMinimap = v);
		radar.toggle("radar_map", () -> s.radarOnMap, v -> s.radarOnMap = v);
		radar.toggle("players", () -> s.radarPlayers, v -> s.radarPlayers = v);
		radar.toggle("hostile", () -> s.radarHostile, v -> s.radarHostile = v);
		radar.toggle("neutral", () -> s.radarNeutral, v -> s.radarNeutral = v);
		radar.toggle("passive", () -> s.radarPassive, v -> s.radarPassive = v);
		radar.toggle("items", () -> s.radarItems, v -> s.radarItems = v);
		radar.toggle("type_outline", () -> s.radarTypeOutline, v -> s.radarTypeOutline = v);
		radar.choice("radar_style", Settings.RadarStyle.values(), () -> s.radarStyle, v -> s.radarStyle = v);
		int scales = (Settings.MAX_SCALE - Settings.MIN_SCALE) / Settings.SCALE_STEP + 1;
		radar.slider("icon_size", (s.radarIconScale - Settings.MIN_SCALE) / Settings.SCALE_STEP, scales,
			i -> s.radarIconScale = Settings.MIN_SCALE + i * Settings.SCALE_STEP, i -> Component.literal((Settings.MIN_SCALE + i * Settings.SCALE_STEP) + "%"));
		// Side by side, so the tab stays short enough for a small screen.
		radar.lastTwoSideBySide();
		radar.choice("player_names", Settings.NameTags.values(), () -> s.playerNames, v -> s.playerNames = v);
		int[] ranges = Settings.RANGES;
		radar.slider("range", indexOf(ranges, s.radarRange), ranges.length, i -> s.radarRange = ranges[i], i -> Component.literal(ranges[i] + " m"));

		SettingsTab overlays = new SettingsTab("diorama.settings.overlays");
		overlays.toggle("chunk_grid", () -> s.chunkGrid, v -> s.chunkGrid = v);
		overlays.toggle("slime_chunks", () -> s.slimeChunks, v -> s.slimeChunks = v);
		// Here rather than with the other map buttons: another row there runs into Done on a short window.
		overlays.toggle("map_dimension_button", () -> s.mapDimensionButton, v -> s.mapDimensionButton = v);
		Settings.SpawnSpots[] spots = Settings.SpawnSpots.values();
		overlays.cycle("spawn_spots", () -> s.spawnSpots.ordinal(), spots.length, i -> s.spawnSpots = spots[i],
			i -> Component.translatable("diorama.settings.spawn_spots." + spots[i].name().toLowerCase(Locale.ROOT)));
		if (Claims.supported()) {
			overlays.choice("claims", Settings.ClaimsShown.values(), () -> s.claims, v -> s.claims = v);
		}
		overlays.choice("drawings", Settings.DrawingsShown.values(), () -> s.drawings, v -> s.drawings = v);

		SettingsTab waypoints = new SettingsTab("diorama.settings.waypoints");
		waypoints.toggle("waypoints_map", () -> s.waypointsOnMap, v -> s.waypointsOnMap = v);
		waypoints.toggle("waypoints_world", () -> s.waypointsInWorld, v -> s.waypointsInWorld = v);
		waypoints.toggle("deaths", () -> s.deathMarkers, v -> s.deathMarkers = v);
		waypoints.toggle("beds", () -> s.bedMarkers, v -> s.bedMarkers = v);
		waypoints.toggle("portals", () -> s.portalMarkers, v -> s.portalMarkers = v);
		waypoints.toggle("map_add_waypoints", () -> s.mapAddWaypoints, v -> s.mapAddWaypoints = v);
		waypoints.toggle("map_waypoints_button", () -> s.mapWaypointsButton, v -> s.mapWaypointsButton = v);
		waypoints.toggle("map_settings_button", () -> s.mapSettingsButton, v -> s.mapSettingsButton = v);
		waypoints.toggle("map_maps_button", () -> s.mapMapsButton, v -> s.mapMapsButton = v);
		waypoints.choice("waypoint_labels", Settings.WaypointLabels.values(), () -> s.waypointLabels, v -> s.waypointLabels = v);
		int[] distances = Settings.WAYPOINT_DISTANCES;
		waypoints.slider("waypoint_distance", indexOf(distances, s.waypointDistance), distances.length, i -> s.waypointDistance = distances[i],
			i -> distances[i] == 0 ? Component.translatable("diorama.settings.waypoint_distance.any") : Component.literal(distances[i] + " m"));
		// Side by side, so the tab stays short enough for a small screen.
		waypoints.lastTwoSideBySide();
		int[] pinSizes = Pins.sizes();
		waypoints.heading("waypoint_size");
		waypoints.slider("waypoint_size_minimap", nearestIndex(pinSizes, s.waypointMinimapSize), pinSizes.length, i -> s.waypointMinimapSize = pinSizes[i], i -> Component.literal(pinSizes[i] + "%"));
		waypoints.slider("waypoint_size_map", nearestIndex(pinSizes, s.waypointMapSize), pinSizes.length, i -> s.waypointMapSize = pinSizes[i], i -> Component.literal(pinSizes[i] + "%"));
		waypoints.slider("waypoint_size_world", nearestIndex(pinSizes, s.waypointWorldSize), pinSizes.length, i -> s.waypointWorldSize = pinSizes[i], i -> Component.literal(pinSizes[i] + "%"));
		waypoints.lastThreeSideBySide();
		waypoints.open("teleport_command", Component.literal("/" + s.teleportCommand), () -> this.minecraft.gui.setScreen(new TeleportCommandScreen(this)));

		SettingsTab look = new SettingsTab("diorama.settings.look");
		look.toggle("outlines", () -> s.outlines, v -> s.outlines = v);
		look.toggle("rounded_fog", () -> s.roundedFog, v -> s.roundedFog = v);
		look.toggle("fog_of_war", () -> s.alwaysFogOfWar, v -> s.alwaysFogOfWar = v);
		look.toggle("saved_chunks", () -> s.mapSavedChunks, v -> s.mapSavedChunks = v);
		Settings.Quality[] qualities = Settings.Quality.values();
		look.cycle("quality", () -> s.quality.ordinal(), qualities.length, i -> s.quality = qualities[i],
			i -> Component.translatable("diorama.settings.quality." + qualities[i].name().toLowerCase(Locale.ROOT)));
		// On, but turned itself off on this graphics card: say why. Turning it back on tries again.
		this.gpuBaking = look.cycle("gpu_baking", () -> s.gpuBaking ? 1 : 0, 2, i -> {
			s.gpuBaking = i == 1;
			if (s.gpuBaking) {
				dev.diorama.render.gpu.GpuBakeCheck.retry();
				this.gpuFailedShown = dev.diorama.render.gpu.GpuBakeCheck.failed();
			}
		}, SettingsScreen::gpuBakingValue);
		this.gpuFailedShown = dev.diorama.render.gpu.GpuBakeCheck.failed();
		look.cycle("arrow_color", () -> s.arrowColor, Settings.ARROW_COLORS.length, i -> s.arrowColor = i,
			//? if >=1.21 {
			i -> Component.translatable("diorama.settings.arrow_color." + Settings.ARROW_COLOR_NAMES[i]).withColor(Settings.ARROW_COLORS[i]));
			//?} else {
			/*i -> Component.translatable("diorama.settings.arrow_color." + Settings.ARROW_COLOR_NAMES[i]).withStyle(style -> style.withColor(Settings.ARROW_COLORS[i])));
			*///?}

		// Vanilla's tab bar, over the settings side instead of the whole screen. It's drawn by hand
		// (extractRenderState), clipped to the side: its separator line runs on past the last tab.
		//? if >=26.2 {
		MenuTabBar bar = MenuTabBar.builder(this.tabManager, this.side).addTabs(minimap, radar, overlays, waypoints, look).build();
		bar.arrangeElements(this.side);
		//?} else if >=26.1 {
		/*TabNavigationBar bar = TabNavigationBar.builder(this.tabManager, this.side).addTabs(minimap, radar, overlays, waypoints, look).build();
		bar.updateWidth(this.side);
		*///?} else {
		/*TabNavigationBar bar = TabNavigationBar.builder(this.tabManager, this.side).addTabs(minimap, radar, overlays, waypoints, look).build();
		bar.setWidth(this.side);
		bar.arrangeElements();
		*///?}
		this.tabs = List.of(minimap, radar, overlays, waypoints, look);
		this.tabBar = this.addWidget(bar);
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
			.bounds(this.side / 2 - 100, this.height - 26, 200, 20).build());
		this.tabManager.setTabArea(new ScreenRectangle(0, TAB_BAR_HEIGHT, this.side, this.listBottom() - TAB_BAR_HEIGHT));
		bar.selectTab(selected, false);
	}

	private static Component gpuBakingValue(int on) {
		dev.diorama.render.gpu.GpuBakeCheck.Off off = dev.diorama.render.gpu.GpuBakeCheck.off();
		return Component.translatable(on == 0 ? "options.off" : off == null ? "options.on" : switch (off) {
			case NO_MATCH -> "diorama.settings.gpu_baking.no_match";
			case TOO_SLOW -> "diorama.settings.gpu_baking.too_slow";
			case CRASHED -> "diorama.settings.gpu_baking.crashed";
		});
	}

	@Override
	public void tick() {
		super.tick();
		boolean failed = dev.diorama.render.gpu.GpuBakeCheck.failed();
		if (this.gpuBaking != null && failed != this.gpuFailedShown) {
			this.gpuFailedShown = failed;
			this.gpuBaking.setMessage(label(Component.translatable("diorama.settings.gpu_baking"), gpuBakingValue(Settings.get().gpuBaking ? 1 : 0)));
		}
	}

	private int listBottom() {
		return this.height - 33;
	}

	/** Shows the tab with this index, as clicking it does. For tests. */
	public void selectTab(int index) {
		if (this.tabBar != null) {
			this.tabBar.selectTab(index, false);
		}
	}

	/** The settings in one tab: checkboxes two to a row, then buttons and sliders the full width, or two to a row. */
	private final class SettingsTab extends GridLayoutTab {
		private final List<AbstractWidget> checkboxes = new ArrayList<>();
		private final List<AbstractWidget> wide = new ArrayList<>();
		/** Wide widgets that share a row with the one after them. */
		private final java.util.Set<AbstractWidget> pairedWithNext = new java.util.HashSet<>();
		private final java.util.Set<AbstractWidget> tripled = new java.util.HashSet<>();
		private final java.util.Set<AbstractWidget> headings = new java.util.HashSet<>();

		SettingsTab(String key) {
			super(Component.translatable(key));
		}

		void toggle(String key, BooleanSupplier get, Consumer<Boolean> set) {
			int column = (CONTENT - COLUMN_GAP) / 2;
			//? if >=1.21 {
			Checkbox checkbox = Checkbox.builder(Component.translatable("diorama.settings." + key), SettingsScreen.this.font)
				.selected(get.getAsBoolean())
				.maxWidth(column)
				.onValueChange((box, value) -> {
					set.accept(value);
					Settings.get().save();
				})
				.build();
			//?} else {
			/*// 1.20.1's checkbox has no builder and tells no one it changed. It's a 20 pixel box with
			// the label 4 pixels right of it, as wide as both, and no wider than the column.
			Component label = Component.translatable("diorama.settings." + key);
			Checkbox checkbox = new Checkbox(0, 0, Math.min(24 + SettingsScreen.this.font.width(label), column), 20, label, get.getAsBoolean()) {
				@Override
				public void onPress() {
					super.onPress();
					set.accept(this.selected());
					Settings.get().save();
				}
			};
			*///?}
			// Not through the builder: from 26.2 it keeps a tooltip only for a label too long to show.
			checkbox.setTooltip(tooltip(key));
			this.checkboxes.add(checkbox);
		}

		Button cycle(String key, IntSupplier get, int count, IntConsumer set, IntFunction<Component> name) {
			Component label = Component.translatable("diorama.settings." + key);
			Button button = Button.builder(label(label, name.apply(get.getAsInt())), b -> {
				int next = (get.getAsInt() + 1) % count;
				set.accept(next);
				Settings.get().save();
				b.setMessage(label(label, name.apply(next)));
			}).size(CONTENT, 20).tooltip(tooltip(key)).build();
			this.wide.add(button);
			return button;
		}

		/** A button that steps through an enum's values, named diorama.settings.<key>.<value>. */
		<E extends Enum<E>> Button choice(String key, E[] values, java.util.function.Supplier<E> get, Consumer<E> set) {
			return this.cycle(key, () -> get.get().ordinal(), values.length, i -> set.accept(values[i]),
				i -> Component.translatable("diorama.settings." + key + "." + values[i].name().toLowerCase(Locale.ROOT)));
		}

		/** A button showing the setting's value that opens a screen to change it. */
		void open(String key, Component value, Runnable press) {
			Component label = Component.translatable("diorama.settings." + key);
			this.wide.add(Button.builder(label(label, value), b -> press.run()).size(CONTENT, 20).tooltip(tooltip(key)).build());
		}

		void slider(String key, int index, int count, IntConsumer set, IntFunction<Component> name) {
			StepSlider slider = new StepSlider(Component.translatable("diorama.settings." + key), index, count, set, name);
			slider.setTooltip(tooltip(key));
			this.wide.add(slider);
		}

		void lastTwoSideBySide() {
			this.pairedWithNext.add(this.wide.get(this.wide.size() - 2));
		}

		void lastThreeSideBySide() {
			this.tripled.add(this.wide.get(this.wide.size() - 3));
		}

		/** A line of text over the next widgets, naming them together. */
		void heading(String key) {
			StringWidget heading = new StringWidget(Component.translatable("diorama.settings." + key), SettingsScreen.this.font);
			this.headings.add(heading);
			this.wide.add(heading);
		}

		@Override
		public void visitChildren(Consumer<AbstractWidget> consumer) {
			this.checkboxes.forEach(consumer);
			this.wide.forEach(consumer);
		}

		@Override
		public void doLayout(ScreenRectangle area) {
			int content = Math.min(CONTENT, area.width() - 10);
			int column = (content - COLUMN_GAP) / 2;
			int left = area.left() + (area.width() - content) / 2;
			int rows = this.wide.size() - this.pairedWithNext.size() - this.tripled.size() * 2 - this.headings.size();
			int checkRows = (this.checkboxes.size() + 1) / 2;
			// On a small window, closer together rather than into the buttons below.
			boolean tight = 22 + checkRows * 22 + rows * 24 + this.headings.size() * HEADING > area.height();
			int rowStep = tight ? 22 : 24;
			int y = area.top() + (tight ? 6 : 12);
			for (int i = 0; i < this.checkboxes.size(); i++) {
				this.checkboxes.get(i).setPosition(left + (i % 2) * (column + COLUMN_GAP), y + (i / 2) * (tight ? 20 : 22));
			}
			if (!this.checkboxes.isEmpty()) {
				y += checkRows * (tight ? 20 : 22) + (tight ? 3 : 10);
			}
			for (int i = 0; i < this.wide.size(); i++) {
				AbstractWidget widget = this.wide.get(i);
				if (this.headings.contains(widget)) {
					widget.setPosition(left, y);
					widget.setWidth(content);
					y += HEADING;
					continue;
				}
				if (this.tripled.contains(widget)) {
					int third = (content - 8) / 3;
					for (int j = 0; j < 3; j++) {
						AbstractWidget part = this.wide.get(i + j);
						part.setPosition(left + j * (content - third) / 2, y);
						part.setWidth(third);
					}
					i += 2;
				} else if (this.pairedWithNext.contains(widget) && i + 1 < this.wide.size()) {
					int half = (content - 4) / 2;
					widget.setPosition(left, y);
					widget.setWidth(half);
					AbstractWidget next = this.wide.get(++i);
					next.setPosition(left + content - half, y);
					next.setWidth(half);
				} else {
					widget.setPosition(left, y);
					widget.setWidth(content);
				}
				y += rowStep;
			}
		}
	}

	/**
	 * A slider over a list of values, one equal stretch of the track each, the way vanilla's
	 * option sliders pick a whole number. Saves when the value it points at changes.
	 */
	private static final class StepSlider extends AbstractSliderButton {
		private final Component name;
		private final int count;
		private final IntConsumer set;
		private final IntFunction<Component> valueName;
		private int index;

		StepSlider(Component name, int index, int count, IntConsumer set, IntFunction<Component> valueName) {
			super(0, 0, CONTENT, 20, Component.empty(), index <= 0 ? 0 : index >= count - 1 ? 1 : (index + 0.5) / count);
			this.name = name;
			this.count = count;
			this.set = set;
			this.valueName = valueName;
			this.index = Math.max(0, Math.min(count - 1, index));
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			this.setMessage(label(this.name, this.valueName.apply(this.index)));
		}

		@Override
		protected void applyValue() {
			int picked = Math.min(this.count - 1, (int) Math.floor(this.value * this.count));
			if (picked != this.index) {
				this.index = picked;
				this.set.accept(picked);
				Settings.get().save();
			}
		}
	}

	private static Tooltip tooltip(String key) {
		return Tooltip.create(Component.translatable("diorama.settings." + key + ".tooltip"));
	}

	private static Component label(Component name, Component value) {
		return Component.empty().append(name).append(": ").append(value);
	}

	/** Where in {@code values}, ascending, the one nearest {@code value} is; halfway rounds up, as {@code Pins.scale} does. */
	private static int nearestIndex(int[] values, int value) {
		int nearest = 0;
		for (int i = 1; i < values.length; i++) {
			if (Math.abs(values[i] - value) <= Math.abs(values[nearest] - value)) {
				nearest = i;
			}
		}
		return nearest;
	}

	private static int indexOf(int[] values, int value) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == value) {
				return i;
			}
		}
		return 1;
	}

	//? if >=1.21 {
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
	//?} else {
	/*// A 1.20.1 screen draws its own background, from its render method below.
	private void drawBackground(GuiGraphics graphics) {
		this.renderBackground(graphics);
	*///?}
		// After the blur, so the preview stays sharp. A big minimap would reach under the settings:
		// it's cut off at their edge, which also keeps its depth (before 1.21.6) clear of them.
		graphics.enableScissor(this.side, 0, this.width, this.height);
		//? if >=1.21.2 {
		this.preview.preview(graphics, this.minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true));
		//?} else if >=1.21 {
		/*this.preview.preview(graphics, this.minecraft.getTimer().getGameTimeDeltaPartialTick(true));
		*///?} else {
		/*// The partial tick the frame is drawn with, the paused one while the game is paused.
		this.preview.preview(graphics, this.minecraft.getFrameTime());
		*///?}
		graphics.disableScissor();
		// The settings side, as vanilla draws a list in a world.
		//? if >=1.21.6 {
		graphics.nextStratum();
		graphics.blit(RenderPipelines.GUI_TEXTURED, LIST_BACKGROUND, 0, TAB_BAR_HEIGHT, 0, 0, this.side, this.listBottom() - TAB_BAR_HEIGHT, 32, 32);
		graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.INWORLD_FOOTER_SEPARATOR, 0, this.listBottom(), 0, 0, this.side, 2, 32, 2);
		//?} else if >=1.21.2 {
		/*graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, LIST_BACKGROUND, 0, TAB_BAR_HEIGHT, 0, 0, this.side, this.listBottom() - TAB_BAR_HEIGHT, 32, 32);
		graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, Screen.INWORLD_FOOTER_SEPARATOR, 0, this.listBottom(), 0, 0, this.side, 2, 32, 2);
		*///?} else if >=1.21 {
		/*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		graphics.blit(LIST_BACKGROUND, 0, TAB_BAR_HEIGHT, 0, 0, this.side, this.listBottom() - TAB_BAR_HEIGHT, 32, 32);
		graphics.blit(Screen.INWORLD_FOOTER_SEPARATOR, 0, this.listBottom(), 0, 0, this.side, 2, 32, 2);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		*///?} else {
		/*// 1.20.1 has neither texture. Each is a flat colour per row, the same as ScreenArt fills.
		graphics.fill(0, TAB_BAR_HEIGHT, this.side, this.listBottom(), 0x70000000);
		graphics.fill(0, this.listBottom(), this.side, this.listBottom() + 1, 0xBF000000);
		graphics.fill(0, this.listBottom() + 1, this.side, this.listBottom() + 2, 0x33FFFFFF);
		*///?}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// Before 1.21.6 this draws the background too, so the tab bar goes after it.
		//? if <1.21 {
		/*this.drawBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (this.tabBar != null) {
			graphics.enableScissor(0, 0, this.side, TAB_BAR_HEIGHT);
			this.tabBar.extractRenderState(graphics, mouseX, mouseY, a);
			graphics.disableScissor();
		}
	}

	// Ctrl+Tab and Ctrl+1 to 5 switch tabs, as in vanilla's tabbed screens, whatever has focus.
	//? if >=1.21.9 {
	@Override
	public boolean keyPressed(KeyEvent event) {
		return this.tabBar != null && this.tabBar.keyPressed(event) || super.keyPressed(event);
	}
	//?} else {
	/*@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		return this.tabBar != null && this.tabBar.keyPressed(key) || super.keyPressed(key, scanCode, modifiers);
	}
	*///?}

	@Override
	public void removed() {
		this.preview.close();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
