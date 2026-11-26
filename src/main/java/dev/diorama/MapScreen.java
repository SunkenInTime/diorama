package dev.diorama;

import dev.diorama.bake.BlockLooks;
import dev.diorama.api.DimensionId;
import dev.diorama.render.AddonOverlay;
import dev.diorama.bake.MapPick;
import dev.diorama.drawing.DrawMode;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileKey;
import dev.diorama.compat.CreateTrains;
import dev.diorama.craft.FarTrains;
import dev.diorama.bake.TileSpots;
import dev.diorama.data.SavedBiomes;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Markers;
import dev.diorama.render.Pins;
import dev.diorama.render.Radar;
import dev.diorama.render.SavedChunkImport;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waystones;
import dev.diorama.waypoint.Waypoints;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.client.multiplayer.ClientLevel;
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///?}
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The fullscreen map: drag to pan, scroll to zoom, C for caves, PgUp/PgDn to move the slice,
 * click a waypoint to edit it, right-click for a menu of what can be done at a spot or waypoint.
 * Each key also has a control on screen: Surface and Caves tabs, a Waypoints button, zoom and
 * recenter buttons, and on the cave map a depth gauge whose band moves the slice.
 *
 * <p>The dimension button, a square at the bottom left, shows the saved map of another dimension,
 * without going there, and each click is the next dimension, as on Xaero's World Map. Only sky
 * views are saved, so that's what shows, without the live things the player's own level gives the
 * map: caves, the radar, claims, overlays and distances. Waypoints, drawings, the right-click menu
 * and teleporting are that dimension's.
 */
public final class MapScreen extends Screen {
	/** How many Overworld blocks a block of the Nether stands for. */
	private static final double NETHER_SCALE = 8;
	/**
	 * How far out the saved surface map zooms, in screen pixels per block: 512 blocks to a pixel, as
	 * Xaero's World Map with its Unlimited Fullscreen Zoom-out on. In screen pixels, not the GUI's,
	 * so the map reaches as far at any GUI scale. Overview tiles go up to a level that draws this
	 * with a texture pixel a screen pixel ({@link TileKey#MAX_LOD}), so it costs what any zoom does.
	 */
	private static final double MIN_SCREEN_ZOOM = 1.0 / 512;
	/** How long the open map keeps the layer on screen while the one the player moved into bakes; as the minimap. */
	private static final long FOLLOW_HOLD_MS = 3000;

	private static final double MAX_ZOOM = 16;

	/** The dimension button's left edge, under the draw tools. */
	private static final int DIMENSION_BUTTON_X = 4;
	/** Height of the top bar, which holds the tabs and buttons. */
	private static final int BAR = 16;
	private static final int BOTTOM_BAR = 14;
	private static final Component HELP = Component.translatable("diorama.map.help");
	private static final String STATUS_GAP = "   ·   ";
	/** One press of a zoom button: two notches of the mouse wheel. */
	private static final int ZOOM_BUTTON_STEPS = 2;
	private static final Identifier TAB = Identifier.withDefaultNamespace("widget/tab");
	private static final Identifier TAB_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/tab_highlighted");
	private static final Identifier TAB_SELECTED = Identifier.withDefaultNamespace("widget/tab_selected");
	private static final Identifier TAB_SELECTED_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/tab_selected_highlighted");
	//? if <1.21 {
	/*// 1.20.1's tab buttons draw all four looks from rows of one texture.
	private static final Identifier TAB_BUTTON = Identifier.withDefaultNamespace("textures/gui/tab_button.png");
	*///?}
	private static final Component[] TAB_NAMES = {Component.translatable("diorama.map.surface"), Component.translatable("diorama.map.caves")};

	private final MapViewport viewport = new MapViewport();
	private double centerU;
	private double centerV;
	private double zoom = 2.0;
	private int layer;
	/** Set once the player picks a layer with the map open: auto caves stops following them then. */
	private boolean layerPicked;
	/**
	 * When following the player moved them onto a layer whose tiles aren't there yet, since when
	 * the old one has stayed on screen; -1 when not waiting. See {@link #followLayer}.
	 */
	private long followHoldStart = -1;
	private boolean following = true;
	private List<Radar.Blip> blips = List.of();
	/** The map's own tooltip at the mouse this frame: a mob, player, waypoint or spot. Null with none, or over a control. */
	private @Nullable List<FormattedCharSequence> tooltipLines;
	/** The status line as drawn last frame. */
	private String statusShown = "";
	private List<WaypointOverlay.Pin> pins = List.of();
	private MapViewport.@Nullable View lastView;
	/** Where the current mouse press started, to tell a click from a drag. */
	private double pressX;
	private double pressY;
	private boolean dragged;
	/** The right-click menu, while it's open. */
	private @Nullable MapMenu menu;
	/** Whether the current mouse press started on the menu. */
	private boolean pressOnMenu;
	/** The button of the latest press, until it's released. */
	private int pressButton = -1;
	/** Whether the latest press went to a control (a button, a tab, the gauge), so it can't pan. */
	private boolean pressOnControl;
	private final DepthGauge gauge = new DepthGauge();
	private Button export;
	private Button zoomIn;
	private Button zoomOut;
	private Button recenter;
	private Button sliceUp;
	private Button sliceDown;
	private Button dimensionButton;
	/** Whether there's a dimension to switch to, worked out when the screen is built. */
	private boolean severalDimensions;
	private Button mapsButton;
	private Button waypointsButton;
	private Button settingsButton;
	/** Turns draw mode on and off; it sits above the zoom buttons. */
	private Button drawButton;
	private final DrawMode draw = new DrawMode();
	private final WorldBorderOverlay worldBorder = new WorldBorderOverlay();
	/** Addons' markers and lines. */
	private final AddonOverlay addons = new AddonOverlay();
	/** The tabs' left edges and widths as drawn last frame, and where the status text starts. */
	private final int[] tabX = new int[2];
	private final int[] tabWidth = new int[2];
	private int tabsEnd;
	/** The block under the mouse, kept a few frames while the map point under it stays in one block. */
	private int spotU = Integer.MIN_VALUE;
	private int spotV;
	private int spotLayer;
	private int spotAge;
	private @Nullable BlockPos spot;
	/** The latest look-up of {@link #spot} from a saved tile; an older one's answer is dropped. */
	private @Nullable Object spotRequest;
	/** Biomes for the tooltip over land the client has no chunks for; null until that's hovered, and outside singleplayer. */
	private @Nullable SavedBiomes savedBiomes;
	/** The dimension whose saved map is shown, or null for the one the player is in. */
	private @Nullable String otherDimension;
	/** That map while it's open. It's closed with the screen, and with each screen opened over this one. */
	private @Nullable OtherMap otherMap;
	private Button facingButton;
	/**
	 * Opens the box for typed or pasted coordinates, as Xaero's World Map's "Hop to specified
	 * coordinates" button does, at the left edge like it: above the dimension button's place.
	 */
	private Button goButton;
	private EditBox goBox;
	/** Whether the box is open; it stays open through a resize. */
	private boolean goOpen;
	/** Why the box couldn't act on what's in it, said above it; null with nothing to say. */
	private @Nullable Component goProblem;
	/** The place the box last went to, marked on the map for a while. */
	private @Nullable GoMark goMark;
	/**
	 * The Turn map key is down since a press that turned the map. Kept while another screen is over this
	 * one, so a key still held on coming back doesn't turn the map again; let go over there, its next
	 * press here is taken for the held one and does nothing.
	 */
	private boolean turnKeyHeld;

	/**
	 * Where the Go to coordinates box took the view. The map is seen at a slant, so a place without a
	 * height isn't one point on it: {@code y} starts as a guess and {@link #findGround} corrects it
	 * from the map, and {@code heightKnown} says whether it's the place's own.
	 */
	private static final class GoMark {
		private final int x;
		private int y;
		private final int z;
		private boolean heightKnown;
		private final String dimension;
		/** The slice and facing the height is looked up on. */
		private final int layer;
		private final Facing facing;
		/**
		 * Whether the height is still being looked up. Off for good once the map shows another slice
		 * or facing, or another screen: an answer that comes after is for a view that's gone, even if
		 * the player has come back to the same slice since.
		 */
		private boolean looking;
		private final long until;
		/** The view's middle as this mark set it: a later correction moves the view only if the player hasn't. */
		private double centerU;
		private double centerV;

		private GoMark(int x, int y, int z, boolean heightKnown, String dimension, int layer, Facing facing, long until) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.heightKnown = heightKnown;
			this.dimension = dimension;
			this.layer = layer;
			this.facing = facing;
			this.until = until;
		}
	}

	/** How long the mark stays, and how long its fading at the end takes. */
	private static final long GO_MARK_MS = 20_000;
	private static final long GO_MARK_FADE_MS = 2_000;
	/** How many times the ground is looked up for a place without a height: each look corrects the last one's guess. */
	private static final int GROUND_TRIES = 4;
	/**
	 * Where else the ground is looked for when nothing shows at the first guess: high ground at the
	 * edge of what's mapped is drawn rows above where Y 64 would be, over land the map hasn't seen.
	 */
	private static final int[] OTHER_GROUND = {96, 128, 32, 160, 0, 192, 224, -32};
	private static final int GO_BOX_WIDTH = 140;
	/** The longest chat line, which a shared waypoint can fill. */
	private static final int GO_BOX_LENGTH = 256;

	/** A dimension's saved map, and what it was opened for: a change to either opens it again. */
	private record OtherMap(Path folder, MapStyle style, Facing facing, MapLevel map) {
	}

	public MapScreen() {
		super(Component.translatable("diorama.screen.title"));
		this.layer = this.layerOnOpening();
		this.refreshShared();
	}

	/** Every so long while the map is open, as the server saves every few minutes. */
	private static final int REFRESH_TICKS = 20 * 60;
	private int refreshIn = REFRESH_TICKS;

	/**
	 * On a server that shares its map, asks what others changed since: as the map opens, and every
	 * minute while it's open.
	 */
	private void refreshShared() {
		dev.diorama.render.SavedChunkImport fill = MapSession.get() == null ? null : MapSession.get().savedChunks();
		if (fill != null) {
			fill.refresh();
		}
	}

	/** Underground the map opens on the cave the player is in, unless the settings say surface only. */
	private int layerOnOpening() {
		MapSession session = MapSession.get();
		Entity viewer = MapSession.viewer();
		return session != null && viewer != null && Settings.get().autoCaves && ServerRules.caveMapsAllowed() ? session.autoLayer(viewer) : TileKey.SURFACE;
	}

	@Override
	protected void init() {
		// Built for the old size, so a resize closes it. A press that opened another screen was
		// released there, so none is held on coming back.
		this.menu = null;
		this.gauge.cancel();
		this.pressButton = -1;
		this.pressOnMenu = false;
		this.pressOnControl = false;
		Settings settings = Settings.get();
		this.dimensionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.nextDimension())
			.size(20, 20).createNarration(n -> this.dimensionName().copy()).build());
		this.severalDimensions = this.dimensions().size() > 1;
		// Any of them can be hidden; the ones shown sit together in the corner, Settings last.
		this.mapsButton = this.addRenderableWidget(Button.builder(Component.translatable("diorama.map.maps"), b -> {
			MapsScreen maps = new MapsScreen(this);
			if (this.otherDimension != null) {
				maps.show(this.otherDimension, null);
			}
			this.minecraft.gui.setScreen(maps);
		}).size(64, BAR).build());
		this.mapsButton.visible = settings.mapMapsButton;
		this.mapsButton.active = Maps.get() != null;
		this.waypointsButton = this.addRenderableWidget(Button.builder(Component.translatable("diorama.map.waypoints"), b -> this.openWaypoints()).size(64, BAR).build());
		this.waypointsButton.visible = settings.mapWaypointsButton;
		this.settingsButton = this.addRenderableWidget(Button.builder(Component.translatable("diorama.settings.button"), b ->
			this.minecraft.gui.setScreen(new SettingsScreen(this))).size(64, BAR).build());
		this.settingsButton.visible = settings.mapSettingsButton;
		int right = this.width;
		for (Button button : List.of(this.settingsButton, this.waypointsButton, this.mapsButton)) {
			if (button.visible) {
				right -= button.getWidth();
				button.setPosition(right, 0);
				right -= 2;
			}
		}
		this.export = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.minecraft.gui.setScreen(new ExportScreen(this, this.centerU, this.centerV, this.zoom, this.layer, this.width, this.height));
		}).size(20, 20).createNarration(n -> Component.translatable("diorama.export.title")).build());
		this.zoomIn = this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.zoomButton(ZOOM_BUTTON_STEPS))
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.zoom_in")).build());
		this.zoomOut = this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.zoomButton(-ZOOM_BUTTON_STEPS))
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.zoom_out")).build());
		this.recenter = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.following = true;
		}).size(20, 20).createNarration(n -> Component.translatable("diorama.map.recenter")).build());
		this.facingButton = this.addRenderableWidget(Button.builder(facingLetter(), b -> this.turn(DrawMode.shiftDown() ? -1 : 1))
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.facing." + Projection.facing().id())).build());
		this.sliceUp = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.shiftSlice(DepthGauge.STEP, MapSession.viewer());
		}).size(18, 14).createNarration(n -> Component.translatable("diorama.map.slice_up")).build());
		this.sliceDown = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.shiftSlice(-DepthGauge.STEP, MapSession.viewer());
		}).size(18, 14).createNarration(n -> Component.translatable("diorama.map.slice_down")).build());
		this.drawButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.menu = null;
			this.draw.toggle();
		}).size(20, 20).createNarration(n -> Component.translatable("diorama.draw.button")).build());
		this.draw.init(this.font, this::addRenderableWidget, this::setFocused, (x, y) -> this.spotAt(x, y, false).thenApply(MapLevel.Spot::pos),
			(u, v) -> this.spotAtMap(u, v, false).thenApply(MapLevel.Spot::pos), this::dimension);
		this.goButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.toggleGo())
			.size(20, 20).createNarration(n -> Component.translatable("diorama.map.go_to")).build());
		// A resize builds the screen again: what was typed stays.
		String typed = this.goBox == null ? "" : this.goBox.getValue();
		this.goBox = this.addRenderableWidget(new EditBox(this.font, 0, 0, GO_BOX_WIDTH, 20, Component.translatable("diorama.map.go_to")));
		this.goBox.setMaxLength(GO_BOX_LENGTH);
		this.goBox.setHint(Component.translatable("diorama.map.go_to.hint").withStyle(ChatFormatting.DARK_GRAY));
		this.goBox.setValue(typed);
		this.goBox.setResponder(text -> this.goProblem = null);
		this.goBox.visible = this.goOpen;
		this.placeControls();
	}

	/** The dimensions to step through: the player's, and every other with a saved map. */
	private List<String> dimensions() {
		Maps maps = Maps.get();
		ClientPacketListener connection = this.minecraft.getConnection();
		if (maps == null || connection == null || this.minecraft.level == null) {
			return List.of();
		}
		// Not the ones with the map off: what was mapped there before stays out of sight too.
		List<String> saved = maps.saved(this.minecraft.level.dimension().identifier().toString(),
			connection.levels().stream().map(level -> level.identifier().toString()).toList())
			.stream().filter(dimension -> !ServerRules.mapOff(dimension)).toList();
		// The one shown stays a stop even if nothing is left in it, or there'd be no way off it.
		if (this.otherDimension == null || saved.contains(this.otherDimension)) {
			return saved;
		}
		List<String> withShown = new ArrayList<>(saved);
		withShown.add(this.otherDimension);
		return withShown;
	}

	/** The dimension button: the next dimension's map, or the last one's with Shift. */
	private void nextDimension() {
		List<String> dimensions = this.dimensions();
		if (dimensions.size() < 2 || this.minecraft.level == null) {
			return;
		}
		int step = DrawMode.shiftDown() ? -1 : 1;
		this.showDimension(dimensions.get(Math.floorMod(dimensions.indexOf(this.dimension()) + step, dimensions.size())));
	}

	/** Shows the map of {@code next}, one of {@link #dimensions}. */
	private void showDimension(String next) {
		this.draw.leaveDimension();
		this.closeOtherMap();
		this.otherDimension = next.equals(this.minecraft.level.dimension().identifier().toString()) ? null : next;
		this.viewChanged();
	}

	/** The map now shows another dimension, or the player's own again. */
	private void viewChanged() {
		this.menu = null;
		this.gauge.cancel();
		this.following = true;
		this.layerPicked = false;
		this.followHoldStart = -1;
		// A mark is a place in the dimension that was shown.
		this.goMark = null;
		this.layer = this.otherDimension == null ? this.layerOnOpening() : TileKey.SURFACE;
		this.spotU = Integer.MIN_VALUE;
		this.spot = null;
		this.spotRequest = null;
	}

	/**
	 * The map to draw: the live one, or the saved one of the dimension being looked at. Walking into
	 * that dimension meanwhile brings the live one back, which has everything the saved one has.
	 */
	private MapLevel shownMap(MapSession session) {
		String other = this.otherDimension;
		Maps maps = Maps.get();
		if (other == null) {
			return session.map();
		}
		// Or the player turned the map off in the dimension shown, in Settings over this screen.
		if (maps == null || this.minecraft.level == null || other.equals(this.minecraft.level.dimension().identifier().toString()) || ServerRules.mapOff(other)) {
			this.draw.leaveDimension();
			this.closeOtherMap();
			this.otherDimension = null;
			this.viewChanged();
			return session.map();
		}
		// The player can switch this dimension's map, or change the view, from the screens over this one.
		Path folder = maps.tileFolder(other, maps.active(other));
		if (this.otherMap == null || !this.otherMap.folder().equals(folder) || this.otherMap.style() != MapStyle.get() || this.otherMap.facing() != Projection.facing()) {
			this.closeOtherMap();
			this.otherMap = new OtherMap(folder, MapStyle.get(), Projection.facing(), MapLevel.saved(folder));
		}
		this.otherMap.map().setFogOfWar(ServerRules.fogOfWar(other.equals("minecraft:the_nether"), this.otherMap.map().roofless()));
		return this.otherMap.map();
	}

	private void closeOtherMap() {
		if (this.otherMap != null) {
			this.otherMap.map().close();
			this.otherMap = null;
		}
	}

	/** The dimension shown, as its button says it. */
	private Component dimensionName() {
		return Waypoints.dimensionName(this.dimension());
	}

	/**
	 * How a position in the player's dimension reads in the one shown. A modded dimension can scale
	 * too, but nothing says so until the player is there.
	 */
	private double scaleToShown() {
		String here = this.minecraft.level == null ? "" : this.minecraft.level.dimension().identifier().toString();
		return dimensionScale(here) / dimensionScale(this.dimension());
	}

	public static double dimensionScale(String dimension) {
		return dimension.equals("minecraft:the_nether") ? NETHER_SCALE : 1;
	}

	@Override
	public void tick() {
		super.tick();
		if (--this.refreshIn <= 0) {
			this.refreshIn = REFRESH_TICKS;
			this.refreshShared();
		}
		// The session updates its own map.
		if (this.otherMap != null) {
			this.otherMap.map().update();
		}
		CreateTrains.tick(true);
	}

	/** Puts the zoom buttons beside the gauge on the cave map, in the corner otherwise. */
	private static Component facingLetter() {
		return Component.translatable("diorama.map.facing_letter." + Projection.facing().id());
	}

	/** Turns this world's map, keeping the same ground in the middle of the view. */
	public void turn(int steps) {
		Maps maps = Maps.get();
		MapSession session = MapSession.get();
		if (maps == null || session == null) {
			return;
		}
		this.menu = null;
		Facing was = Projection.facing();
		Facing now = was.next(steps);
		if (!maps.facing(now)) {
			return;
		}
		// The ground in the middle stays there: on a cave slice, the slice's.
		double y = this.layer == TileKey.SURFACE ? USUAL_GROUND_Y : this.layer;
		double z = Projection.zAt(this.centerV, y);
		double worldX = was.worldX(this.centerU, z);
		double worldZ = was.worldZ(this.centerU, z);
		Projection.setFacing(now);
		this.closeOtherMap();
		MapSession.facingChanged();
		// The block under the mouse was the old facing's.
		this.spotU = Integer.MIN_VALUE;
		this.spot = null;
		this.spotRequest = null;
		this.centerU = Projection.u(worldX, worldZ);
		this.centerV = Projection.v(worldX, y, worldZ);
		this.facingButton.setMessage(facingLetter());
	}

	private void placeControls() {
		boolean caves = this.layer != TileKey.SURFACE;
		int x = this.width - (caves ? 56 : 26);
		int y = this.height - 90;
		this.drawButton.setPosition(x, y - 26);
		// The sky view is exported from what's saved, a cave slice from the chunks the map holds; not
		// while drawing on it.
		MapSession session = MapSession.get();
		this.export.visible = !this.draw.active() && this.otherDimension == null && session != null && (caves || session.map().savedMap() != null);
		this.export.setPosition(x, y - 48);
		this.zoomIn.setPosition(x, y);
		this.zoomOut.setPosition(x, y + 22);
		this.recenter.setPosition(x, y + 48);
		this.facingButton.setPosition(x - 22, y + 48);
		// The draw tools can reach down this far on a short window, and turning moves what's being drawn on.
		this.facingButton.visible = !this.draw.active();
		// Nothing to switch to until a second dimension has a map. Not while drawing, where the draw
		// tools can reach down this far on a short window.
		this.dimensionButton.visible = this.severalDimensions && Settings.get().mapDimensionButton && !this.draw.active();
		this.dimensionButton.setPosition(DIMENSION_BUTTON_X, y + 48);
		// Not while drawing either: the draw tools stand at this edge.
		if (this.draw.active() && this.goOpen) {
			this.closeGo();
		}
		this.goButton.visible = !this.draw.active();
		this.goButton.setPosition(DIMENSION_BUTTON_X, y + 26);
		this.goBox.visible = this.goOpen;
		this.goBox.setPosition(DIMENSION_BUTTON_X + 22, y + 26);
		// Keys are the box's while it's open, whichever button was clicked last.
		if (this.goOpen && this.getFocused() != this.goBox) {
			this.setFocused(this.goBox);
		}
		this.zoomIn.active = this.zoom < MAX_ZOOM;
		this.zoomOut.active = this.zoom > this.minZoom();
		this.sliceUp.visible = caves;
		this.sliceDown.visible = caves;
		ClientLevel level = this.minecraft.level;
		if (caves && level != null) {
			this.sliceUp.active = this.layer < level.getMaxY() + 1;
			this.sliceDown.active = this.layer > level.getMinY() + DepthGauge.STEP;
		}
	}

	// No blurred, dimmed world behind the map. On 1.20.1 a screen draws no background unless it
	// asks for one, so there's nothing to turn off.
	//? if >=1.21 {
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}
	//?}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.tooltipLines = null;
		if (!ServerRules.minimapAllowed()) {
			// The server can switch maps off while this screen is open.
			this.onClose();
			return;
		}
		// The map can open before a server names its world, and the list of maps is the world's.
		this.mapsButton.active = Maps.get() != null;
		MapSession session = MapSession.get();
		Entity viewer = MapSession.viewer();
		if (session == null || viewer == null) {
			return;
		}
		MapLevel shown = this.shownMap(session);
		boolean elsewhere = this.otherDimension != null;
		Vec3 pos = viewer.getPosition(a);
		double scale = this.scaleToShown();
		double playerU = Projection.u(pos.x * scale, pos.z * scale);
		double playerV = Projection.v(pos.x * scale, pos.y, pos.z * scale);
		if (this.following) {
			this.centerU = playerU;
			this.centerV = playerV;
		}
		this.followLayer(session, viewer);
		// Switching to a cave slice while zoomed far out comes back to the closest zoom it can show.
		this.zoom = Math.max(this.minZoom(), this.zoom);
		MapViewport.View view = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, this.zoom, this.layer);
		this.lastView = view;
		if (this.draw.holding() && this.pressButton != InputConstants.MOUSE_BUTTON_LEFT) {
			// The left button is still down for the pen, eraser or measuring tool, but another button was
			// pressed over it (the middle one, to pan). The game sends drags for the latest press only, and
			// none once that is let go, so the drawing follows the cursor from here instead. lastView is
			// already this frame's, which the measuring tool picks its block through.
			this.draw.follow(view, this.font, this.dimension(), mouseX, mouseY);
		}
		// Held lights belong to this dimension's map, not one shown from elsewhere.
		this.viewport.draw(graphics, shown, view, elsewhere ? 0 : overlayBits(session), !elsewhere && Settings.get().claims != Settings.ClaimsShown.OFF,
			elsewhere ? null : () -> session.lights().draw(graphics, view, a, 1, ServerRules.radarAllowed() && Settings.get().radarOnMap));
		if (!elsewhere) {
			session.crafts().draw(graphics, view, a, 1);
		}
		Component train = FarTrains.draw(graphics, view, shown, this.dimension(), elsewhere ? null : session.crafts(), mouseX, mouseY);
		// The client only knows the border of the dimension it's in.
		if (!elsewhere && Settings.get().worldBorder != Settings.WorldBorderShown.OFF) {
			this.worldBorder.draw(graphics, this.font, view, this.minecraft.level, viewer);
		}
		DrawingStore drawings = DrawingStore.get();
		if (drawings != null && (this.draw.active() || Settings.get().drawings != Settings.DrawingsShown.OFF)) {
			this.draw.drawMap(graphics, this.font, view, drawings.in(this.dimension()), mouseX, mouseY, !this.overControls(mouseX, mouseY));
		}
		// The shown dimension's, from another dimension too: they're at places in the world it shows.
		DimensionId addonDimension = AddonOverlay.any() ? this.addons.dimension(this.dimension()) : null;
		if (addonDimension != null) {
			this.addons.drawLines(graphics, view, addonDimension, true);
		}
		boolean caves = this.layer != TileKey.SURFACE;
		this.placeControls();
		Radar.Blip hovered = null;
		this.blips = List.of();
		if (!elsewhere && ServerRules.radarAllowed() && Settings.get().radarOnMap) {
			this.blips = Radar.collect(view, a, true);
			Radar.draw(graphics, view, this.blips, true, false);
			hovered = Radar.under(view, this.blips, mouseX, mouseY);
		}
		if (addonDimension != null) {
			this.addons.drawMarkers(graphics, view, addonDimension, true);
		}
		WaypointStore store = WaypointStore.get();
		this.pins = store == null || !Settings.get().waypointsOnMap ? List.of() : this.layoutPins(view, store.shownIn(this.dimension()));
		WaypointOverlay.Pin hoveredPin = WaypointOverlay.under(this.pins, mouseX, mouseY);
		WaypointOverlay.draw(graphics, view, this.pins, true, hoveredPin);
		this.drawGoMark(graphics, view);
		Radar.draw(graphics, view, this.blips, true, true);
		Markers.player(graphics, view.screenX(playerU), view.screenY(playerV), Markers.onMap(viewer.getViewYRot(a)), 6 * Settings.get().arrowScale());
		if (caves) {
			this.gauge.layout(this.minecraft.level, viewer.blockPosition(), this.layer, this.width, this.height);
			this.sliceUp.setPosition(this.gauge.upButtonX(), this.gauge.upButtonY());
			this.sliceDown.setPosition(this.gauge.upButtonX(), this.gauge.downButtonY());
			// Its labels slide left of the map's buttons (draw, zoom, recenter) rather than under them.
			int avoidX = this.zoomIn.getX() - 3;
			int avoidTop = this.drawButton.getY() - 2;
			int avoidBottom = this.recenter.getY() + this.recenter.getHeight() + 2;
			//? if >=1.21.2 {
			this.gauge.draw(graphics, this.font, pos.y, this.layer, avoidX, avoidTop, avoidBottom);
			//?} else {
			/*// Each fill would be its own draw call otherwise.
			graphics.drawManaged(() -> this.gauge.draw(graphics, this.font, pos.y, this.layer, avoidX, avoidTop, avoidBottom));
			*///?}
		} else {
			this.gauge.cancel();
		}
		// The tooltip is for a control under the mouse, else whatever is drawn on top: a player,
		// then a waypoint, then a mob, then a far train, then the spot itself. The menu covers them
		// while it's open.
		if (this.menu == null) {
			if (this.draw.active() && !this.overControls(mouseX, mouseY)) {
				// Drawing: the map's own tooltips would sit on what's being drawn.
			} else if (this.overControls(mouseX, mouseY)) {
				Tip tip = this.controlTooltip(mouseX, mouseY);
				if (tip != null) {
					// Placed like a button's own tooltip: below the control, or above where there's no room.
					//? if >=1.21.6 {
					graphics.setTooltipForNextFrame(this.font, tip.lines(), new MenuTooltipPositioner(tip.control()), mouseX, mouseY, false);
					//?} else if >=1.21 {
					/*this.setTooltipForNextRenderPass(tip.lines(), new MenuTooltipPositioner(tip.control()), false);
					*///?} else {
					/*// 1.20.1's positioner takes a widget, of which it reads only the top and height, and
					// the tabs aren't widgets: a button over the control stands in for it.
					ScreenRectangle control = tip.control();
					Button stand = Button.builder(Component.empty(), b -> {
					}).bounds(control.left(), control.top(), control.width(), control.height()).build();
					this.setTooltipForNextRenderPass(tip.lines(), new MenuTooltipPositioner(stand), false);
					*///?}
				}
			} else if (hovered != null && hovered.kind() == Radar.Kind.PLAYER) {
				this.tooltipLines = withClaim(Radar.describe(hovered, viewer), hovered.entity().getBlockX(), hovered.entity().getBlockZ());
			} else if (hoveredPin != null) {
				Waypoint waypoint = hoveredPin.waypoint();
				// Another dimension's map: the claims known are those of the dimension the player is in.
				this.tooltipLines = elsewhere ? List.of(Component.literal(waypoint.shownName()).getVisualOrderText())
					: withClaim(WaypointOverlay.describe(waypoint, viewer), waypoint.x, waypoint.z);
			} else if (hovered != null) {
				this.tooltipLines = withClaim(Radar.describe(hovered, viewer), hovered.entity().getBlockX(), hovered.entity().getBlockZ());
			} else if (train != null) {
				this.tooltipLines = List.of(train.getVisualOrderText());
			} else if (this.pressButton == -1) {
				this.tooltipLines = this.spotTooltip(mouseX, mouseY);
			}
			if (this.tooltipLines != null) {
				graphics.setTooltipForNextFrame(this.font, this.tooltipLines, mouseX, mouseY);
			}
		}

		// The bars: tabs and status on top with the buttons, the mouse's ways in at the bottom.
		graphics.fill(0, 0, this.width, BAR, 0x90000000);
		this.drawTabs(graphics, mouseX, mouseY);
		int statusX = this.tabsEnd + 8;
		this.statusShown = this.statusText(shown, viewer, caves, this.buttonsStart() - 2 - statusX);
		graphics.text(this.font, this.statusShown, statusX, 4, 0xFFFFFFFF, false);
		graphics.fill(0, this.height - BOTTOM_BAR, this.width, this.height, 0x90000000);
		String help = (this.draw.active() ? this.draw.help() : HELP).getString();
		graphics.text(this.font, help, 6, this.height - 11, 0xFFC8D0DA, false);
		this.drawMapName(graphics, 6 + this.font.width(help) + 12);
		// Widgets (the buttons) draw over the map, then the icons on them.
		super.extractRenderState(graphics, mouseX, mouseY, a);
		Markers.player(graphics, this.recenter.getX() + 10, this.recenter.getY() + 10.5F, 180, 5);
		DrawMode.icon(graphics, DrawMode.PENCIL_ICON, this.drawButton.getX(), this.drawButton.getY(), 0xFFFFFFFF);
		if (this.draw.active()) {
			DrawMode.outline(graphics, this.drawButton.getX(), this.drawButton.getY(), 20, 20, 0xFFFFFFFF);
		}
		this.draw.drawControls(graphics);
		if (this.export.visible) {
			DrawMode.icon(graphics, PICTURE, this.export.getX(), this.export.getY(), 0xFFFFFFFF);
		}
		if (this.dimensionButton.visible) {
			this.drawDimensionIcon(graphics);
		}
		if (this.goButton.visible) {
			DrawMode.icon(graphics, TARGET, this.goButton.getX(), this.goButton.getY(), 0xFFFFFFFF);
			if (this.goOpen) {
				DrawMode.outline(graphics, this.goButton.getX(), this.goButton.getY(), 20, 20, 0xFFFFFFFF);
				if (this.goProblem != null) {
					int left = this.goBox.getX();
					int top = this.goBox.getY() - 12;
					graphics.fill(left, top, left + this.font.width(this.goProblem) + 4, top + 11, 0xC0101418);
					graphics.text(this.font, this.goProblem, left + 2, top + 2, 0xFFFF7A70, false);
				}
			}
		}
		if (caves) {
			this.caret(graphics, this.sliceUp, false);
			this.caret(graphics, this.sliceDown, true);
		}
		if (this.menu != null) {
			// Over everything drawn so far, text included.
			//? if >=1.21.6 {
			graphics.nextStratum();
			this.menu.draw(graphics, mouseX, mouseY);
			//?} else {
			/*graphics.flush();
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(0, 0, 400);
			this.menu.draw(graphics, mouseX, mouseY);
			pose.popPose();
			*///?}
		}
	}

	/**
	 * The status line: whether the view is under the Nether's roof, the player's position, the
	 * chunks mapped, whether baking is running and how far an image export is. It has {@code room}
	 * pixels before the buttons; what doesn't fit is left out whole, the chunk count first, then
	 * baking, the roof and the export. On another dimension's map, where the player's position
	 * doesn't belong, it says where they are instead, and there are no chunks or bakes to count.
	 */
	private String statusText(MapLevel shown, Entity viewer, boolean caves, int room) {
		boolean elsewhere = this.otherDimension != null;
		String roof = !caves && shown.roofless() ? Component.translatable("diorama.map.under_roof").getString() : null;
		String position = elsewhere
			? Component.translatable("diorama.map.you_are_in", Waypoints.dimensionName(viewer.level().dimension().identifier().toString())).getString()
			: String.format("%d, %d, %d", viewer.getBlockX(), viewer.getBlockY(), viewer.getBlockZ());
		String chunks = elsewhere ? null : Component.translatable("diorama.map.chunks", shown.chunkCount()).getString();
		SavedChunkImport job = elsewhere ? null : MapSession.get().savedChunks();
		boolean redrawing = job != null && shown.memory() != null && job.chunksLeft() > 0;
		String baking = redrawing ? Component.translatable("diorama.map.redrawing", job.percentDone()).getString()
			: !elsewhere && shown.bakingCount() > 0 ? Component.translatable("diorama.map.baking").getString() : null;
		MapExport export = MapExport.running();
		String saving = export != null ? Component.translatable("diorama.export.saving", Math.round(export.progress() * 100)).getString() : null;
		List<String> parts = new ArrayList<>();
		for (String part : new String[] {roof, position, chunks, baking, saving}) {
			if (part != null) {
				parts.add(part);
			}
		}
		for (String dropped : new String[] {chunks, baking, roof, saving}) {
			if (this.font.width(String.join(STATUS_GAP, parts)) <= room) {
				break;
			}
			parts.remove(dropped);
		}
		return this.font.plainSubstrByWidth(String.join(STATUS_GAP, parts), Math.max(0, room));
	}

	/**
	 * The name of the dimension's map in use, at the right end of the bottom bar, once the dimension
	 * has more than one. Shortened to fit after the help text, or left out if even a little won't.
	 */
	private void drawMapName(GuiGraphicsExtractor graphics, int left) {
		Maps maps = Maps.get();
		if (maps == null || this.minecraft.level == null) {
			return;
		}
		String dimension = this.dimension();
		if (!maps.several(dimension)) {
			return;
		}
		String name = Component.translatable("diorama.map.current_map", maps.name(dimension, maps.active(dimension))).getString();
		int room = this.width - 6 - left;
		if (this.font.width(name) > room) {
			String ellipsis = CommonComponents.ELLIPSIS.getString();
			if (room < this.font.width(ellipsis) * 4) {
				return;
			}
			name = this.font.plainSubstrByWidth(name, room - this.font.width(ellipsis)) + ellipsis;
		}
		graphics.text(this.font, name, this.width - 6 - this.font.width(name), this.height - 11, 0xFFFFFFFF, false);
	}

	/** Where the buttons at the top right start: the status line has to end before them. */
	private int buttonsStart() {
		int start = this.width;
		for (Button button : List.of(this.mapsButton, this.waypointsButton, this.settingsButton)) {
			if (button.visible) {
				start = Math.min(start, button.getX());
			}
		}
		return start;
	}

	// Mouse and key events came as separate numbers before 1.21.9. Each override hands them to a
	// method below that both versions share.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.draw.anyPress(event.x(), event.y());
		return this.boundPressed(event.button(), DioramaClient.WAYPOINT_LIST.matchesMouse(event), DioramaClient.OPEN_MAP.matchesMouse(event), DioramaClient.TOGGLE_DIMENSION.matchesMouse(event), DioramaClient.TURN_MAP.matchesMouse(event))
			|| this.menuPressed(event.x(), event.y(), event.button()) || this.clicked(super.mouseClicked(event, doubleClick), event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return this.released(event.x(), event.y(), event.button()) || super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.x(), event.y(), event.button(), dx, dy) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return this.keyDown(event.key(), DioramaClient.WAYPOINT_LIST.matches(event), DioramaClient.OPEN_MAP.matches(event), DioramaClient.TOGGLE_DIMENSION.matches(event), DioramaClient.TURN_MAP.matches(event))
			|| super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (DioramaClient.TURN_MAP.matches(event)) {
			this.turnKeyHeld = false;
		}
		return super.keyReleased(event);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		this.draw.anyPress(x, y);
		return this.boundPressed(button, DioramaClient.WAYPOINT_LIST.matchesMouse(button), DioramaClient.OPEN_MAP.matchesMouse(button), DioramaClient.TOGGLE_DIMENSION.matchesMouse(button), DioramaClient.TURN_MAP.matchesMouse(button))
			|| this.menuPressed(x, y, button) || this.clicked(super.mouseClicked(x, y, button), x, y, button);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return this.released(x, y, button) || super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(x, y, button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return this.keyDown(key, DioramaClient.WAYPOINT_LIST.matches(key, scancode), DioramaClient.OPEN_MAP.matches(key, scancode), DioramaClient.TOGGLE_DIMENSION.matches(key, scancode), DioramaClient.TURN_MAP.matches(key, scancode))
			|| super.keyPressed(key, scancode, modifiers);
	}

	@Override
	public boolean keyReleased(int key, int scancode, int modifiers) {
		if (DioramaClient.TURN_MAP.matches(key, scancode)) {
			this.turnKeyHeld = false;
		}
		return super.keyReleased(key, scancode, modifiers);
	}
	*///?}

	/**
	 * A mouse button bound to one of the map's keys, checked before the menu and the widgets as a key
	 * is: widgets only click with the left button. The map is used with the left and right buttons,
	 * and pans with the middle one while drawing, so a binding on those does nothing here.
	 */
	private boolean boundPressed(int button, boolean waypointListKey, boolean mapKey, boolean dimensionKey, boolean turnKey) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT
			|| (button == InputConstants.MOUSE_BUTTON_MIDDLE && this.draw.active())) {
			return false;
		}
		if (this.goOpen) {
			// Typing coordinates: a binding on a mouse button waits, as one on a key does, or it would
			// close the map over what's typed or change the dimension the coordinates are for.
			return true;
		}
		return this.bindingPressed(waypointListKey, mapKey, dimensionKey, turnKey);
	}

	/**
	 * A press while the menu is open, which the widgets don't get: on the menu it's for the menu,
	 * and anywhere else its release only closes the menu. False when the menu isn't open.
	 */
	private boolean menuPressed(double x, double y, int button) {
		if (this.menu == null) {
			return false;
		}
		this.pressOnMenu = this.menu.contains(x, y);
		this.pressButton = button;
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		return true;
	}

	/** A press; {@code widgetTook} if a widget (a button) took it. */
	private boolean clicked(boolean widgetTook, double x, double y, int button) {
		this.pressButton = button;
		this.pressOnControl = false;
		// A press anywhere else puts the box away, as it would take the keys from it.
		if (this.goOpen && !on(this.goBox, x, y) && !on(this.goButton, x, y)) {
			this.closeGo();
		}
		// The game gives the keys to the button just clicked, after the click opened the box.
		if (this.goOpen && this.getFocused() != this.goBox) {
			this.setFocused(this.goBox);
		}
		if (!widgetTook && button == InputConstants.MOUSE_BUTTON_LEFT) {
			int tab = this.tabAt(x, y);
			if (tab >= 0) {
				if ((tab == 1) != (this.layer != TileKey.SURFACE)) {
					this.toggleCaves();
				} else if (this.otherDimension == null) {
					// The tab already shown, clicked: a pick too, so the map stops following.
					this.layerPicked = true;
				}
				this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
				widgetTook = true;
			} else if (this.layer != TileKey.SURFACE && this.minecraft.level != null && this.gauge.contains(x, y)) {
				this.menu = null;
				// Picking a slice: following the player could switch layers under the drag.
				this.layerPicked = true;
				this.gauge.grab(this.minecraft.level, y);
				widgetTook = true;
			}
		}
		if (!widgetTook && this.onButton(x, y)) {
			// A button that can't go further (zoom at its limit) still isn't the map.
			widgetTook = true;
		}
		if (widgetTook) {
			this.pressOnControl = true;
			this.dragged = true;
			return true;
		}
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		if (this.draw.active() && this.lastView != null) {
			this.draw.press(this.lastView, this.font, this.dimension(), x, y, button);
		}
		return true;
	}

	/** A release; false leaves it to the screen's widgets. */
	private boolean released(double x, double y, int button) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT && this.gauge.dragging()) {
			// The slice moves only now, so the map prepares just the one it was let go at.
			int slice = this.gauge.release();
			if (this.layer != TileKey.SURFACE) {
				this.layer = slice;
				this.layerPicked = true;
			}
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT && button != this.pressButton && this.draw.holding() && this.lastView != null) {
			// Another button was pressed while drawing with the left: letting go of the left still ends the stroke,
			// erase or measurement.
			this.draw.release(this.lastView, this.font, this.dimension(), x, y, button, true);
			return true;
		}
		// Only the release of the latest press counts: letting go of a button held since before
		// the menu opened mustn't close it.
		if (button != this.pressButton) {
			return true;
		}
		this.pressButton = -1;
		boolean moved = this.dragged || Math.hypot(x - this.pressX, y - this.pressY) > 3;
		if (this.pressOnMenu) {
			this.pressOnMenu = false;
			if (!moved && button == InputConstants.MOUSE_BUTTON_LEFT && this.menu != null && this.menu.click(x, y)) {
				this.menu = null;
			}
			return true;
		}
		if (this.draw.active() && !this.pressOnControl && this.lastView != null
			&& this.draw.release(this.lastView, this.font, this.dimension(), x, y, button, moved)) {
			return true;
		}
		if (moved) {
			return true;
		}
		if (this.menu != null) {
			// A click off the menu only closes it; a right-click opens the next one.
			this.menu = null;
			if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				this.openMenu(x, y);
			}
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, x, y);
			if (pin != null && pin.waypoint().editable()) {
				// A pin drawn last frame for a waypoint deleted since (by an addon, say) opens nothing.
				if (held(pin.waypoint().source())) {
					this.minecraft.gui.setScreen(new WaypointEditScreen(this, pin.waypoint().source(), false));
				}
			} else if (pin != null) {
				// A waystone can't be edited here: its menu instead.
				this.openMenu(x, y);
			}
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.openMenu(x, y);
			return true;
		}
		return false;
	}

	/** Opens the menu for what's under a screen point: a waypoint's, or the spot's. */
	private void openMenu(double screenX, double screenY) {
		WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, screenX, screenY);
		// The pins are last frame's: one whose waypoint was deleted since (by an addon, say) gets no menu.
		if (pin != null && held(pin.waypoint().source())) {
			this.menu = new MapMenu(this.waypointItems(pin.waypoint()), this.font, screenX, screenY, this.width, this.height);
			return;
		}
		CompletableFuture<MapLevel.Spot> spot = this.spotAt(screenX, screenY, true);
		BlockPos rough = this.roughSpot(screenX, screenY);
		try {
			// Where the chunks are gone, the spot comes off the disk, usually in a few milliseconds.
			MapLevel.Spot found = spot.get(SPOT_WAIT_MS, TimeUnit.MILLISECONDS);
			this.menu = new MapMenu(this.spotItems(found, rough), this.font, screenX, screenY, this.width, this.height);
		} catch (TimeoutException e) {
			// A slow disk: say so until it answers, unless the menu has been closed or replaced by then.
			MapMenu waiting = new MapMenu(List.of(MapMenu.Item.info(Component.translatable("diorama.menu.finding"))),
				this.font, screenX, screenY, this.width, this.height);
			this.menu = waiting;
			spot.thenAcceptAsync(found -> {
				if (this.menu == waiting) {
					this.menu = new MapMenu(this.spotItems(found, rough), this.font, screenX, screenY, this.width, this.height);
				}
			}, this.minecraft);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			this.menu = new MapMenu(this.spotItems(MapLevel.Spot.NOTHING, rough), this.font, screenX, screenY, this.width, this.height);
		}
	}

	/** How long a right-click waits for a spot from a saved tile before opening the menu without it. */
	private static final long SPOT_WAIT_MS = 50;

	/**
	 * Where a player would stand on the block shown under a screen point. Answered at once from
	 * the map's chunks; where they're gone, from the spots saved with the tile, which takes a read
	 * off the render thread, or with {@code readWorld} from the world's save where the tile has none.
	 */
	private CompletableFuture<MapLevel.Spot> spotAt(double screenX, double screenY, boolean readWorld) {
		MapViewport.View view = this.lastView;
		if (view == null) {
			return CompletableFuture.completedFuture(MapLevel.Spot.NOTHING);
		}
		return this.spotAtMap(view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom(),
			view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom(), readWorld);
	}

	/** {@link #spotAt} for a map point, wherever the map has moved since. */
	private CompletableFuture<MapLevel.Spot> spotAtMap(double u, double mapV, boolean readWorld) {
		MapSession session = MapSession.get();
		BlockLooks looks = BlockLooks.get();
		if (session == null || looks == null) {
			return CompletableFuture.completedFuture(MapLevel.Spot.NOTHING);
		}
		double v = TileSpots.rowMiddle(mapV);
		if (this.otherDimension != null) {
			// No chunks here, and the world's save is for where the player is.
			return this.shownMap(session).savedSpot(this.layer, u, v, looks, false);
		}
		TileKey tile = new TileKey(this.layer, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		MapPick pick = new MapPick(session.map(), looks, tile, session.map().fogOfWar());
		return switch (pick.pick(u, v)) {
			case MapPick.FOUND -> CompletableFuture.completedFuture(new MapLevel.Spot(Projection.facing().worldPos(new BlockPos((int) Math.floor(u), pick.y, pick.z)), false));
			case MapPick.NOTHING -> CompletableFuture.completedFuture(MapLevel.Spot.NOTHING);
			default -> session.map().savedSpot(this.layer, u, v, looks, readWorld);
		};
	}

	/** The height most ground is near, for a point the map knows nothing about. */
	static final int USUAL_GROUND_Y = 64;

	/**
	 * Roughly where a screen point is when the map can't say. The camera is tilted, so a point alone
	 * gives x but not z: z is off by about 0.8 of a block for each block the real ground is from this guess.
	 */
	private @Nullable BlockPos roughSpot(double screenX, double screenY) {
		MapViewport.View view = this.lastView;
		if (view == null) {
			return null;
		}
		int y = this.layer == TileKey.SURFACE ? USUAL_GROUND_Y : this.layer;
		double u = view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom();
		double v = TileSpots.rowMiddle(view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom());
		return Projection.facing().worldPos(new BlockPos((int) Math.floor(u), y, (int) Math.floor(Projection.zAt(v, y))));
	}

	private List<MapMenu.Item> spotItems(MapLevel.Spot found, @Nullable BlockPos rough) {
		BlockPos spot = found.pos();
		if (spot == null) {
			List<MapMenu.Item> unknown = new ArrayList<>();
			// Drawn by Diorama 0.3.0 or older, with no save to read the ground from: it comes back when the player does.
			unknown.add(MapMenu.Item.info(Component.translatable(found.unsaved() ? "diorama.menu.not_saved" : "diorama.menu.not_mapped")));
			if (rough != null) {
				// The distance would be across two dimensions.
				if (this.otherDimension == null) {
					unknown.add(MapMenu.Item.info(this.roughly(rough.getX(), rough.getZ())));
				}
				unknown.add(this.copyItem(rough.getX() + " " + rough.getZ()));
			}
			return unknown;
		}
		int x = spot.getX();
		int y = spot.getY();
		int z = spot.getZ();
		String dimension = this.dimension();
		List<MapMenu.Item> items = new ArrayList<>();
		items.add(MapMenu.Item.info(this.where(x, y, z)));
		items.add(MapMenu.Item.action(Component.translatable("diorama.menu.teleport_here"), Waypoints.canTeleport(dimension), () -> {
			Waypoints.teleport(dimension, x, y, z);
			this.minecraft.gui.setScreen(null);
		}));
		if (Settings.get().mapAddWaypoints) {
			items.add(MapMenu.Item.action(Component.translatable("diorama.menu.add_waypoint"), true, () -> {
				Waypoint waypoint = Waypoints.at(spot, dimension);
				if (waypoint != null) {
					this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, true));
				}
			}));
			items.add(MapMenu.Item.action(Component.translatable("diorama.menu.add_temporary"), true, () -> Waypoints.addTemporary(spot, dimension)));
		}
		items.add(this.copyItem(x + " " + y + " " + z));
		// Chat can't take it back, so sharing asks twice.
		items.add(MapMenu.Item.confirmed(Component.translatable("diorama.menu.share_location"), Component.translatable("diorama.menu.share_confirm"), () -> {
			Waypoint location = new Waypoint(Component.translatable("diorama.menu.shared_location").getString(), x, y, z, dimension, Waypoint.PALETTE[0]);
			if (WaypointShare.send(location)) {
				this.minecraft.gui.setScreen(null);
			}
		}));
		return items;
	}

	/**
	 * A pin's menu. For another dimension's waypoint shown on this map, the name, position and
	 * copied coordinates are the pin's, here; everything else acts on the waypoint itself, so
	 * Teleport takes the player to it in its own dimension, as Xaero's Minimap does.
	 */
	private List<MapMenu.Item> waypointItems(Waypoint shown) {
		Waypoint waypoint = shown.source();
		List<MapMenu.Item> items = new ArrayList<>();
		items.add(MapMenu.Item.title(Component.literal(shown.shownName())));
		items.add(MapMenu.Item.info(this.where(shown.x, shown.y, shown.z)));
		if (waypoint.editable()) {
			items.add(MapMenu.Item.action(Component.translatable("diorama.waypoints.edit"), true, ifHeld(waypoint, () ->
				this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, false)))));
		}
		items.add(MapMenu.Item.action(Component.translatable("diorama.waypoint.teleport"), Waypoints.canTeleport(waypoint.dimension), ifHeld(waypoint, () -> {
			Waypoints.teleport(waypoint.dimension, waypoint.x, waypoint.y, waypoint.z);
			this.minecraft.gui.setScreen(null);
		})));
		// Copying stays open to say it copied, so a waypoint gone since closes the menu instead.
		String coordinates = shown.x + " " + shown.y + " " + shown.z;
		items.add(MapMenu.Item.stays(Component.translatable("diorama.menu.copy_coordinates"), Component.translatable("diorama.menu.copied"), () -> {
			if (held(waypoint)) {
				this.minecraft.keyboardHandler.setClipboard(coordinates);
			} else {
				this.menu = null;
			}
		}));
		items.add(MapMenu.Item.confirmed(Component.translatable("diorama.waypoint.share"), Component.translatable("diorama.menu.share_confirm"), ifHeld(waypoint, () -> {
			if (WaypointShare.send(waypoint)) {
				this.minecraft.gui.setScreen(null);
			}
		})));
		if (waypoint.kind == Waypoint.Kind.TEMPORARY) {
			items.add(MapMenu.Item.action(Component.translatable("diorama.menu.keep"), true, ifHeld(waypoint, () -> {
				waypoint.kind = Waypoint.Kind.MANUAL;
				this.changed();
			})));
		}
		items.add(MapMenu.Item.action(Component.translatable("diorama.waypoints.hide"), true, ifHeld(waypoint, () -> {
			WaypointStore store = WaypointStore.get();
			if (store != null) {
				store.setVisible(waypoint, false);
			}
		})));
		if (waypoint.editable()) {
			items.add(MapMenu.Item.confirmed(Component.translatable("diorama.waypoint.delete"), Component.translatable("diorama.menu.delete_confirm"), ifHeld(waypoint, () -> {
				WaypointStore store = WaypointStore.get();
				if (store != null) {
					store.remove(waypoint);
				}
			})));
		}
		return items;
	}

	/**
	 * Whether a waypoint is still one the store has: false for one deleted since a pin or a menu
	 * was made for it, by an addon say. A waystone is the Waystones mod's, never the store's: it's
	 * held while Waystones still has it, and it isn't picked up.
	 */
	private static boolean held(Waypoint waypoint) {
		WaypointStore store = WaypointStore.get();
		return store != null && (waypoint.kind == Waypoint.Kind.WAYSTONE ? Waystones.has(waypoint.id) : store.holds(waypoint));
	}

	/** A menu item's action that does nothing once its waypoint is gone ({@link #held}). */
	private static Runnable ifHeld(Waypoint waypoint, Runnable action) {
		return () -> {
			if (held(waypoint)) {
				action.run();
			}
		};
	}

	/** A line putting {@code coordinates} on the clipboard, spaced the way commands take them. */
	private MapMenu.Item copyItem(String coordinates) {
		return MapMenu.Item.stays(Component.translatable("diorama.menu.copy_coordinates"), Component.translatable("diorama.menu.copied"),
			() -> this.minecraft.keyboardHandler.setClipboard(coordinates));
	}

	private void changed() {
		WaypointStore store = WaypointStore.get();
		if (store != null) {
			store.changed();
		}
	}

	/** "120, 64, -340 · 212 m": a block position and how far the player is from it, if they're in this dimension. */
	private Component where(int x, int y, int z) {
		if (this.otherDimension != null) {
			return Component.translatable("diorama.map.spot", x, y, z);
		}
		Entity viewer = MapSession.viewer();
		long distance = viewer == null ? 0 : Math.round(Math.sqrt(viewer.distanceToSqr(x + 0.5, y, z + 0.5)));
		return Component.translatable("diorama.menu.where", x, y, z, distance);
	}

	/** "About 120, -340 · 212 m": a point whose height isn't known, and how far the player is from it across the ground. */
	private Component roughly(int x, int z) {
		Entity viewer = MapSession.viewer();
		long distance = viewer == null ? 0 : Math.round(Math.hypot(viewer.getX() - (x + 0.5), viewer.getZ() - (z + 0.5)));
		return Component.translatable("diorama.menu.where_rough", x, z, distance);
	}

	/** A drag; false leaves it to the screen's widgets. */
	private boolean dragged(double x, double y, int button, double dx, double dy) {
		if (this.pressButton == -1) {
			// No press of this screen's: it was reset (a resize rebuilt the screen mid-drag).
			return false;
		}
		if (Math.hypot(x - this.pressX, y - this.pressY) > 3) {
			this.dragged = true;
		}
		if (this.pressOnMenu) {
			return true;
		}
		if (this.gauge.dragging()) {
			if (this.minecraft.level != null) {
				this.gauge.drag(this.minecraft.level, y);
			}
			return true;
		}
		if (this.pressOnControl) {
			return false;
		}
		if (this.draw.active() && !this.draw.pans(button)) {
			if (this.lastView != null) {
				this.draw.drag(this.lastView, this.font, this.dimension(), x, y);
			}
			return true;
		}
		// While drawing, the right and middle buttons pan too.
		if ((button == InputConstants.MOUSE_BUTTON_LEFT || this.draw.active()) && this.dragged) {
			// The menu stays where it opened, so it can't follow the map.
			this.menu = null;
			this.centerU -= dx / this.zoom;
			this.centerV -= dy / this.zoom;
			this.following = false;
			return true;
		}
		return false;
	}

	// The mouse wheel had only the one axis on 1.20.1.
	@Override
	//? if >=1.21 {
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
	//?} else {
	/*public boolean mouseScrolled(double x, double y, double scrollY) {
	*///?}
		this.menu = null;
		double before = this.zoom;
		double after = Math.max(this.minZoom(), Math.min(MAX_ZOOM, before * Math.pow(1.25, scrollY)));
		if (after != before) {
			// Keep the map point under the cursor where it is.
			double mapU = this.centerU + (x - this.width / 2.0) / before;
			double mapV = this.centerV + (y - this.height / 2.0) / before;
			this.centerU = mapU - (x - this.width / 2.0) / after;
			this.centerV = mapV - (y - this.height / 2.0) / after;
			this.zoom = after;
			if (this.following) {
				this.following = false;
			}
		}
		return true;
	}

	/** A zoom button: zooms about the middle of the screen, so the map keeps following the player. */
	private void zoomButton(int steps) {
		this.menu = null;
		this.zoom = Math.max(this.minZoom(), Math.min(MAX_ZOOM, this.zoom * Math.pow(1.25, steps)));
	}

	/** A key press, and whether it is bound to the waypoint list or the map; false leaves it to the screen. */
	private boolean keyDown(int key, boolean waypointListKey, boolean mapKey, boolean dimensionKey, boolean turnKey) {
		if (this.draw.typing()) {
			// Typing a note: only Enter and Esc are the map's, the rest are the note's.
			return this.draw.key(key, this.dimension());
		}
		if (this.goOpen) {
			// Typing coordinates: Enter goes there and Esc puts the box away. The rest are the box's, or
			// C, Space and the player's own keys would act on the map as they type.
			if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				this.goTo(this.goBox.getValue());
				return true;
			}
			if (key == InputConstants.KEY_ESCAPE) {
				this.closeGo();
				return true;
			}
			return false;
		}
		// The player's own bindings come before the map's fixed keys, so Open map rebound to C still closes it.
		if (turnKey) {
			// A key held down repeats, and each turn redraws the whole map: only the press turns it.
			boolean held = this.turnKeyHeld;
			this.turnKeyHeld = true;
			return held || this.bindingPressed(false, false, false, true);
		}
		if (this.bindingPressed(waypointListKey, mapKey, dimensionKey, false)) {
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && this.menu != null) {
			this.menu = null;
			return true;
		}
		if (this.draw.key(key, this.dimension())) {
			return true;
		}
		Entity viewer = MapSession.viewer();
		// Each of these changes the view, which an open menu belongs to, so they close it.
		switch (key) {
			case InputConstants.KEY_C -> {
				this.toggleCaves();
				return true;
				}
			case InputConstants.KEY_PAGEUP -> {
				this.menu = null;
				this.shiftSlice(4, viewer);
				return true;
				}
			case InputConstants.KEY_PAGEDOWN -> {
				this.menu = null;
				this.shiftSlice(-4, viewer);
				return true;
				}
			case InputConstants.KEY_SPACE -> {
				this.menu = null;
				this.following = true;
				return true;
				}
			default -> {
			}
		}
		return false;
	}

	private boolean bindingPressed(boolean waypointListKey, boolean mapKey, boolean dimensionKey, boolean turnKey) {
		if (turnKey) {
			// As the facing button, which is hidden while drawing: turning moves what's being drawn on.
			if (!this.draw.active()) {
				this.turn(DrawMode.shiftDown() ? -1 : 1);
			}
			return true;
		}
		if (dimensionKey) {
			this.nextDimension();
			return true;
		}
		if (waypointListKey) {
			this.openWaypoints();
			return true;
		}
		if (mapKey) {
			this.onClose();
			return true;
		}
		return false;
	}

	/** The Go to coordinates button: opens its box, empty and ready to type or paste in, or puts it away. */
	private void toggleGo() {
		this.menu = null;
		if (this.goOpen) {
			this.closeGo();
			return;
		}
		this.goOpen = true;
		this.goProblem = null;
		this.goBox.setValue("");
		this.goBox.visible = true;
		this.setFocused(this.goBox);
	}

	private void closeGo() {
		this.goOpen = false;
		this.goProblem = null;
		this.goBox.visible = false;
		if (this.getFocused() == this.goBox) {
			// Keys go back to the map.
			this.setFocused(null);
		}
	}

	/**
	 * Moves the view to the place {@code text} names ({@link MapCoordinates}) and marks it, on the
	 * map of the dimension the text names if it names one. Xaero's World Map's hop moves its camera
	 * the same way and leaves it there; the mark is Diorama's own, since its map is seen at a slant
	 * and the middle of the screen alone doesn't say which block was meant. False, with the reason
	 * above the box, if the text names no place or a dimension with no map. Public for tests.
	 */
	public boolean goTo(String text) {
		MapCoordinates.Place place = MapCoordinates.parse(text);
		if (place == null) {
			this.goProblem = Component.translatable("diorama.map.go_to.unread");
			return false;
		}
		String dimension = place.dimension();
		if (dimension != null && !dimension.equals(this.dimension())) {
			if (this.minecraft.level == null || !this.dimensions().contains(dimension)) {
				this.goProblem = Component.translatable("diorama.map.go_to.no_map", Waypoints.dimensionName(dimension));
				return false;
			}
			this.showDimension(dimension);
		}
		this.closeGo();
		this.menu = null;
		// Without a height, where most ground is, or on a cave slice the slice: the map corrects it where it knows better.
		int y = place.y() != null ? place.y() : this.layer == TileKey.SURFACE ? USUAL_GROUND_Y : this.layer;
		GoMark mark = new GoMark(place.x(), y, place.z(), place.y() != null, this.dimension(), this.layer, Projection.facing(), System.currentTimeMillis() + GO_MARK_MS);
		this.goMark = mark;
		this.centreOn(mark);
		if (!mark.heightKnown) {
			// A cave slice shows one height: there's nowhere else to look.
			mark.looking = true;
			int ground = this.loadedGround(mark.x, mark.z);
			this.findGround(mark, ground != Integer.MIN_VALUE ? ground : y, GROUND_TRIES, this.layer == TileKey.SURFACE ? 0 : OTHER_GROUND.length);
		}
		return true;
	}

	private void centreOn(GoMark mark) {
		this.centerU = Projection.u(mark.x + 0.5, mark.z + 0.5);
		this.centerV = Projection.v(mark.x + 0.5, mark.y, mark.z + 0.5);
		this.following = false;
		mark.centerU = this.centerU;
		mark.centerV = this.centerV;
	}

	/**
	 * Corrects the height of a mark that came without one. The map says which block shows at the
	 * point a block of height {@code y} in the mark's column would be drawn at: in the mark's own
	 * column, that's its ground. In another column, the guess was off, that block's height is a
	 * better one, and the next look starts from it. Where nothing shows, the ground may still be
	 * mapped higher or lower, so a few other heights are tried ({@link #OTHER_GROUND}); land the map
	 * has never seen answers nothing at any of them, and the mark keeps its guess.
	 */
	private void findGround(GoMark mark, int y, int tries, int other) {
		this.spotAtMap(Projection.u(mark.x + 0.5, mark.z + 0.5), Projection.v(mark.x + 0.5, y, mark.z + 0.5), false).thenAcceptAsync(spot -> {
			// Another place since, or the view the look-up was for is gone ({@link GoMark#looking}).
			if (this.goMark != mark || !mark.looking) {
				return;
			}
			BlockPos ground = spot.pos();
			if (ground == null) {
				if (other < OTHER_GROUND.length) {
					this.findGround(mark, OTHER_GROUND[other], tries, other + 1);
				}
				return;
			}
			if (ground.getY() != mark.y) {
				boolean viewLeft = !this.following && this.centerU == mark.centerU && this.centerV == mark.centerV;
				mark.y = ground.getY();
				if (viewLeft) {
					this.centreOn(mark);
				}
			}
			if (ground.getX() == mark.x && ground.getZ() == mark.z) {
				mark.heightKnown = true;
				mark.looking = false;
			} else if (ground.getY() != y && tries > 1) {
				// From here on the look-ups follow the ground found, not the list of heights.
				this.findGround(mark, ground.getY(), tries - 1, OTHER_GROUND.length);
			}
		}, this.minecraft);
	}

	/**
	 * The top of the ground at a column the game has loaded, on the surface map of the dimension the
	 * player is in: the best first guess there is, which the map then corrects to the block it draws.
	 * {@link Integer#MIN_VALUE} anywhere else.
	 */
	private int loadedGround(int x, int z) {
		ClientLevel level = this.minecraft.level;
		if (level == null || this.otherDimension != null || this.layer != TileKey.SURFACE || !level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
			return Integer.MIN_VALUE;
		}
		return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
	}

	/** The mark: a ring with its coordinates under it, fading out at the end of its time. */
	private void drawGoMark(GuiGraphicsExtractor graphics, MapViewport.View view) {
		GoMark mark = this.goMark;
		if (mark == null) {
			return;
		}
		// Drawn every frame, so no change of slice or facing gets past this, however briefly it lasts.
		if (this.layer != mark.layer || Projection.facing() != mark.facing) {
			mark.looking = false;
		}
		long left = mark.until - System.currentTimeMillis();
		if (left <= 0 || !mark.dimension.equals(this.dimension())) {
			this.goMark = null;
			return;
		}
		int alpha = (int) Math.max(4, Math.min(255, left * 255 / GO_MARK_FADE_MS));
		int white = alpha << 24 | 0xFFFFFF;
		int dark = alpha << 24;
		int x = Math.round(view.screenX(Projection.u(mark.x + 0.5, mark.z + 0.5)));
		int y = Math.round(view.screenY(Projection.v(mark.x + 0.5, mark.y, mark.z + 0.5)));
		// Four ticks around the block, dark under white so they read on snow and on water.
		for (int pass = 0; pass < 2; pass++) {
			int grow = pass == 0 ? 1 : 0;
			int colour = pass == 0 ? dark : white;
			graphics.fill(x - 9 - grow, y - grow, x - 3 + grow, y + 1 + grow, colour);
			graphics.fill(x + 4 - grow, y - grow, x + 10 + grow, y + 1 + grow, colour);
			graphics.fill(x - grow, y - 9 - grow, x + 1 + grow, y - 3 + grow, colour);
			graphics.fill(x - grow, y + 4 - grow, x + 1 + grow, y + 10 + grow, colour);
		}
		Component label = mark.heightKnown ? Component.translatable("diorama.map.spot", mark.x, mark.y, mark.z) : Component.literal(mark.x + ", " + mark.z);
		// On a dark plate: unmapped land is drawn pale.
		int half = this.font.width(label) / 2;
		graphics.fill(x - half - 2, y + 12, x - half + this.font.width(label) + 2, y + 22, (alpha * 3 / 4) << 24 | 0x101418);
		graphics.text(this.font, label, x - half, y + 13, white, false);
	}

	/** The waypoint list, on the folder of the dimension shown. */
	private void openWaypoints() {
		WaypointListScreen list = new WaypointListScreen(this);
		if (this.otherDimension != null) {
			list.showDimension(this.otherDimension);
		}
		this.minecraft.gui.setScreen(list);
	}

	/** Between the surface and the slice at the player's feet, as C and the tabs do. */
	private void toggleCaves() {
		this.menu = null;
		if (this.otherDimension != null || !ServerRules.caveMapsAllowed()) {
			return;
		}
		this.gauge.cancel();
		Entity viewer = MapSession.viewer();
		this.layer = this.layer == TileKey.SURFACE && viewer != null ? playerSlice(viewer) : TileKey.SURFACE;
		this.layerPicked = true;
	}

	/** The slice the minimap shows for the viewer underground, so C and the slice keys start where it is. */
	private static int playerSlice(Entity viewer) {
		MapSession session = MapSession.get();
		return session != null ? session.caveLayer(viewer) : MapSession.caveLayerFor(viewer.getY());
	}

	private void shiftSlice(int delta, @Nullable Entity viewer) {
		if (this.otherDimension != null || !ServerRules.caveMapsAllowed()) {
			return;
		}
		if (this.layer == TileKey.SURFACE) {
			if (viewer == null) {
				return;
			}
			this.layer = playerSlice(viewer);
		}
		this.layer = Math.max(this.minecraft.level.getMinY() + 4, Math.min(this.minecraft.level.getMaxY() + 1, this.layer + delta));
		this.layerPicked = true;
	}

	/**
	 * Lays out the waypoint pins with the edge ones kept clear of the bars, the depth gauge and the
	 * zoom buttons: pins inside the view stay where they are.
	 */
	private List<WaypointOverlay.Pin> layoutPins(MapViewport.View view, List<Waypoint> waypoints) {
		int top = BAR + 1;
		int bottom = this.height - BOTTOM_BAR - 1;
		int right = this.width - (this.layer != TileKey.SURFACE ? 60 : 0);
		// The same map under a smaller view: points land where they do in the full one.
		MapViewport.View clear = new MapViewport.View(0, top, right, bottom - top,
			view.centerU() + (right / 2.0 - view.x() - view.width() / 2.0) / view.zoom(),
			view.centerV() + ((top + bottom) / 2.0 - view.y() - view.height() / 2.0) / view.zoom(),
			view.zoom(), view.layer());
		List<WaypointOverlay.Pin> pins = WaypointOverlay.layout(clear, waypoints, true);
		// The column of buttons, and the facing button left of recenter.
		int bx0 = (this.facingButton.visible ? this.facingButton.getX() : this.zoomIn.getX()) - 2;
		// From the export (or draw) button down to recenter.
		int by0 = (this.export.visible ? this.export.getY() : this.drawButton.getY()) - 2;
		int bx1 = this.zoomIn.getX() + this.zoomIn.getWidth() + 2;
		int by1 = this.recenter.getY() + this.recenter.getHeight() + 2;
		// The buttons at the left edge: Go to coordinates above the dimension button's place.
		boolean leftButtons = this.goButton.visible || this.dimensionButton.visible;
		int dx1 = this.dimensionButton.getX() + this.dimensionButton.getWidth() + 2;
		int dy0 = (this.goButton.visible ? this.goButton.getY() : this.dimensionButton.getY()) - 2;
		int dy1 = (this.dimensionButton.visible ? this.dimensionButton.getY() + this.dimensionButton.getHeight() : this.goButton.getY() + this.goButton.getHeight()) + 2;
		List<WaypointOverlay.Pin> out = new ArrayList<>(pins.size());
		for (WaypointOverlay.Pin pin : pins) {
			float r = Pins.reach(pin.waypoint(), (int) pin.size()) * pin.scale();
			// Initials of three or four letters make the badge wider than it's tall.
			float rx = r + Pins.stretch(pin.waypoint(), (int) pin.size()) * pin.scale() / 2.0F;
			if (pin.clamped() && pin.x() + rx > bx0 && pin.x() - rx < bx1 && pin.y() + r > by0 && pin.y() - r < by1) {
				// An edge pin behind the zoom buttons moves the shorter way out: above them or left.
				float up = pin.y() + r - by0;
				float left = pin.x() + rx - bx0;
				pin = up <= left
					? new WaypointOverlay.Pin(pin.waypoint(), pin.x(), by0 - r, pin.size(), pin.scale(), true)
					: new WaypointOverlay.Pin(pin.waypoint(), bx0 - rx, pin.y(), pin.size(), pin.scale(), true);
			}
			if (leftButtons && pin.clamped() && pin.x() - rx < dx1 && pin.y() + r > dy0 && pin.y() - r < dy1) {
				// And behind the buttons at the left edge, above them or right.
				float up = pin.y() + r - dy0;
				float across = dx1 - (pin.x() - rx);
				pin = up <= across
					? new WaypointOverlay.Pin(pin.waypoint(), pin.x(), dy0 - r, pin.size(), pin.scale(), true)
					: new WaypointOverlay.Pin(pin.waypoint(), dx1 + rx, pin.y(), pin.size(), pin.scale(), true);
			}
			out.add(pin);
		}
		return out;
	}

	/** Surface and Caves as the game's tabs, the open one taller and underlined. */
	private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = 2;
		for (int i = 0; i < this.tabs(); i++) {
			boolean selected = (i == 1) == (this.layer != TileKey.SURFACE);
			String name = TAB_NAMES[i].getString();
			int nameWidth = this.font.width(name);
			int w = nameWidth + 13;
			int h = selected ? BAR + 2 : BAR;
			boolean hovered = this.menu == null && mouseX >= x && mouseX < x + w && mouseY >= 0 && mouseY < h;
			sprite(graphics, selected ? (hovered ? TAB_SELECTED_HIGHLIGHTED : TAB_SELECTED) : (hovered ? TAB_HIGHLIGHTED : TAB), x, 0, w, h);
			if (selected) {
				// The selected tab's sprite is open in the middle; the game puts its menu backdrop there.
				// 1.20.1's tab texture is filled in, so it needs none.
				//? if >=1.21 {
				graphics.fill(x + 2, 2, x + w - 2, h, 0x40000000);
				//?}
				int underline = nameWidth - 1;
				graphics.fill(x + (w - underline) / 2, h - 3, x + (w - underline) / 2 + underline, h - 2, 0xFFFFFFFF);
			}
			graphics.text(this.font, name, x + w / 2 - nameWidth / 2, selected ? 4 : 6, selected ? 0xFFFFFFFF : 0xFFE0E0E0, true);
			this.tabX[i] = x;
			this.tabWidth[i] = w;
			x += w;
		}
		this.tabsEnd = x;
	}

	/**
	 * How many tabs show: cave slices aren't saved, so another dimension's map has only Surface, as
	 * does a server that turns cave maps off.
	 */
	private int tabs() {
		return this.otherDimension == null && ServerRules.caveMapsAllowed() ? 2 : 1;
	}

	/** The tab under a point: 0 Surface, 1 Caves, -1 neither. */
	private int tabAt(double x, double y) {
		for (int i = 0; i < this.tabs(); i++) {
			if (x >= this.tabX[i] && x < this.tabX[i] + this.tabWidth[i] && y >= 0 && y < BAR) {
				return i;
			}
		}
		return -1;
	}

	/** One of the game's tabs, as the map's Surface and Caves are drawn. */
	static void tab(GuiGraphicsExtractor graphics, boolean selected, boolean hovered, int x, int y, int w, int h) {
		sprite(graphics, selected ? (hovered ? TAB_SELECTED_HIGHLIGHTED : TAB_SELECTED) : (hovered ? TAB_HIGHLIGHTED : TAB), x, y, w, h);
	}

	private static void sprite(GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int w, int h) {
		//? if >=1.21.6 {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
		//?} else if >=1.21.2 {
		/*graphics.blitSprite(RenderType::guiTextured, sprite, x, y, w, h);
		*///?} else if >=1.21 {
		/*graphics.blitSprite(sprite, x, y, w, h);
		*///?} else {
		/*// From 1.20.1's tab texture, one 24 pixel row per state. Its tabs are always 24 tall; these
		// are shorter, so they take the top h rows and slice only the 2 pixel sides (1.20.1's
		// nine-slice divides by the bottom border when the height differs, and the tabs have none).
		int row = sprite == TAB_SELECTED ? 0 : sprite == TAB_SELECTED_HIGHLIGHTED ? 1 : sprite == TAB ? 2 : 3;
		graphics.blitNineSliced(TAB_BUTTON, x, y, w, h, 2, 2, 2, 0, 130, h, 0, row * 24);
		*///?}
	}

	/** The dimension button's face: its icon, or for a modded dimension the first letter of its name. */
	private void drawDimensionIcon(GuiGraphicsExtractor graphics) {
		int x = this.dimensionButton.getX();
		int y = this.dimensionButton.getY();
		String[] icon = switch (this.dimension()) {
			case "minecraft:overworld" -> OVERWORLD_ICON;
			case "minecraft:the_nether" -> NETHER_ICON;
			case "minecraft:the_end" -> END_ICON;
			default -> null;
		};
		if (icon != null) {
			DrawMode.icon(graphics, icon, x, y, 0xFFFFFFFF);
			return;
		}
		String name = this.dimensionName().getString();
		String letter = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
		graphics.text(this.font, letter, x + 10 - this.font.width(letter) / 2, y + 6, 0xFFFFFFFF, true);
	}

	/** The Overworld on the dimension button: a tree, 11 by 9. */
	private static final String[] OVERWORLD_ICON = {
		"....###....",
		"..#######..",
		".#########.",
		".#########.",
		"..#######..",
		"....###....",
		".....#.....",
		".....#.....",
		"....###...."
	};

	/** The Nether on the dimension button: a flame, 11 by 9. */
	private static final String[] NETHER_ICON = {
		".....#.....",
		"....##.....",
		"....###.#..",
		"...#####...",
		"..#######..",
		".####.####.",
		".###...###.",
		"..##...##..",
		"...#####..."
	};

	/** The End on the dimension button: an eye, 11 by 7. */
	private static final String[] END_ICON = {
		"...#####...",
		".##.....##.",
		"#...###...#",
		"#..#####..#",
		"#...###...#",
		".##.....##.",
		"...#####..."
	};

	/** The Export button's icon: a framed picture of a hill under the sun, 11 by 9. */
	private static final String[] PICTURE = {
		"###########",
		"#.........#",
		"#......##.#",
		"#......##.#",
		"#...#.....#",
		"#..#.#....#",
		"#.#...#.#.#",
		"##.....#.##",
		"###########"
	};

	/** The Go to coordinates button's icon: a target, 9 by 9. */
	private static final String[] TARGET = {
		"....#....",
		"..#####..",
		".#.....#.",
		".#.....#.",
		"##..#..##",
		".#.....#.",
		".#.....#.",
		"..#####..",
		"....#...."
	};

	/** A small up or down arrow on a slice button, grey when it can't go further. */
	private void caret(GuiGraphicsExtractor graphics, Button button, boolean down) {
		int colour = button.active ? 0xFFFFFFFF : 0xFFA0A0A0;
		int cx = button.getX() + button.getWidth() / 2;
		int y = button.getY() + (button.getHeight() - 3) / 2;
		for (int row = 0; row < 3; row++) {
			int half = down ? 2 - row : row;
			graphics.fill(cx - half, y + row, cx + half + 1, y + row + 1, colour);
		}
	}

	/** Whether a point is on the bars or a control, where the map's own tooltips don't show. */
	private boolean overControls(double x, double y) {
		if (y < BAR || y >= this.height - BOTTOM_BAR) {
			return true;
		}
		return this.onButton(x, y) || this.layer != TileKey.SURFACE && this.gauge.contains(x, y) || this.draw.onControls(x, y);
	}

	/** Whether a point is on a shown button, whether or not it can be pressed right now. */
	private boolean onButton(double x, double y) {
		for (AbstractWidget widget : new AbstractWidget[] {this.facingButton, this.dimensionButton, this.goButton, this.goBox, this.mapsButton, this.waypointsButton, this.settingsButton, this.export, this.zoomIn, this.zoomOut, this.recenter, this.sliceUp, this.sliceDown, this.drawButton}) {
			if (on(widget, x, y)) {
				return true;
			}
		}
		return false;
	}

	/** Whether a point is on a widget that's shown; unlike isMouseOver, also when it's inactive. */
	private static boolean on(AbstractWidget widget, double x, double y) {
		return widget.visible && x >= widget.getX() && x < widget.getX() + widget.getWidth() && y >= widget.getY() && y < widget.getY() + widget.getHeight();
	}

	/** A control's tooltip and where the control is. */
	private record Tip(List<FormattedCharSequence> lines, ScreenRectangle control) {
	}

	/** What a control does and its key: the player's binding where there is one. */
	private @Nullable Tip controlTooltip(double x, double y) {
		int tab = this.tabAt(x, y);
		if (tab >= 0) {
			return new Tip(lines(TAB_NAMES[tab], key(keyName(InputConstants.KEY_C))), new ScreenRectangle(this.tabX[tab], 0, this.tabWidth[tab], BAR));
		}
		if (on(this.dimensionButton, x, y)) {
			return tip(this.dimensionButton, this.dimensionName(), DioramaClient.TOGGLE_DIMENSION.isUnbound()
				? Component.translatable("diorama.map.dimension_button")
				: Component.translatable("diorama.map.dimension_button_key", DioramaClient.TOGGLE_DIMENSION.getTranslatedKeyMessage()));
		}
		// Not with its box open: the tooltip would lie over what the box says is wrong.
		if (on(this.goButton, x, y) && !this.goOpen) {
			return tip(this.goButton, Component.translatable("diorama.map.go_to"), Component.translatable("diorama.map.go_to.how"));
		}
		if (on(this.waypointsButton, x, y) && !DioramaClient.WAYPOINT_LIST.isUnbound()) {
			return tip(this.waypointsButton, this.waypointsButton.getMessage(), key(DioramaClient.WAYPOINT_LIST.getTranslatedKeyMessage()));
		}
		if (on(this.export, x, y)) {
			return new Tip(List.of(Component.translatable("diorama.export.title").getVisualOrderText()),
				new ScreenRectangle(this.export.getX(), this.export.getY(), this.export.getWidth(), this.export.getHeight()));
		}
		if (on(this.zoomIn, x, y)) {
			return tip(this.zoomIn, Component.translatable("diorama.map.zoom_in"), Component.translatable("diorama.map.scroll_up"));
		}
		if (on(this.zoomOut, x, y)) {
			return tip(this.zoomOut, Component.translatable("diorama.map.zoom_out"), Component.translatable("diorama.map.scroll_down"));
		}
		if (on(this.facingButton, x, y)) {
			MapSession session = MapSession.get();
			boolean walk = session != null && session.map().memory() != null && session.savedChunks() == null;
			return tip(this.facingButton, Component.translatable("diorama.map.facing." + Projection.facing().id()),
				walk ? Component.translatable("diorama.map.facing_walk")
					: DioramaClient.TURN_MAP.isUnbound() ? Component.translatable("diorama.map.facing_turn")
					: Component.translatable("diorama.map.facing_turn_key", DioramaClient.TURN_MAP.getTranslatedKeyMessage()));
		}
		if (on(this.recenter, x, y)) {
			return tip(this.recenter, Component.translatable("diorama.map.recenter"), key(keyName(InputConstants.KEY_SPACE)));
		}
		if (on(this.drawButton, x, y)) {
			return tip(this.drawButton, Component.translatable("diorama.draw.button"), Component.translatable(this.draw.active() ? "diorama.draw.button.stop" : "diorama.draw.button.start"));
		}
		if (on(this.sliceUp, x, y)) {
			return tip(this.sliceUp, Component.translatable("diorama.map.slice_up"), key(keyName(InputConstants.KEY_PAGEUP)));
		}
		if (on(this.sliceDown, x, y)) {
			return tip(this.sliceDown, Component.translatable("diorama.map.slice_down"), key(keyName(InputConstants.KEY_PAGEDOWN)));
		}
		return null;
	}

	private static Tip tip(AbstractWidget control, Component name, Component second) {
		return new Tip(lines(name, second), new ScreenRectangle(control.getX(), control.getY(), control.getWidth(), control.getHeight()));
	}

	/** "-382, 62, 825" over "River · 146 m" for the block under an empty spot of the map; the second line alone with the Cursor coordinates setting off. */
	private @Nullable List<FormattedCharSequence> spotTooltip(int mouseX, int mouseY) {
		MapViewport.View view = this.lastView;
		Entity viewer = MapSession.viewer();
		if (view == null || viewer == null) {
			return null;
		}
		// The pick walks a ray through the map's blocks; do it again only for another block.
		double u = view.centerU() + (mouseX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (mouseY - view.y() - view.height() / 2.0) / view.zoom();
		int blockU = (int) Math.floor(u);
		int blockV = (int) Math.floor(v);
		boolean moved = blockU != this.spotU || blockV != this.spotV || this.layer != this.spotLayer;
		if (moved || ++this.spotAge > 10) {
			this.spotU = blockU;
			this.spotV = blockV;
			this.spotLayer = this.layer;
			this.spotAge = 0;
			// Not from the world's save: that loads chunks on the server, too much for every block the mouse crosses.
			CompletableFuture<@Nullable BlockPos> found = this.spotAt(mouseX, mouseY, false).thenApply(MapLevel.Spot::pos);
			Object request = new Object();
			this.spotRequest = request;
			if (found.isDone()) {
				this.spot = found.getNow(null);
			} else {
				// From a saved tile: the old answer stands meanwhile if the mouse is still on its block.
				if (moved) {
					this.spot = null;
				}
				found.thenAcceptAsync(spot -> {
					if (this.spotRequest == request) {
						this.spot = spot;
					}
				}, this.minecraft);
			}
		}
		BlockPos spot = this.spot;
		if (spot == null) {
			return null;
		}
		boolean coordinates = Settings.get().mapCoordinates;
		if (this.otherDimension != null) {
			// Another dimension's map has only the coordinates to say.
			return coordinates ? List.of(Component.translatable("diorama.map.spot", spot.getX(), spot.getY(), spot.getZ()).getVisualOrderText()) : null;
		}
		long distance = Math.round(Math.sqrt(viewer.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5)));
		Component biome = this.biomeAt(spot);
		Component detail = biome != null
			? Component.translatable("diorama.map.spot_detail", biome, distance)
			: Component.translatable("diorama.map.distance", distance);
		if (!coordinates) {
			return withClaim(detail, spot.getX(), spot.getZ());
		}
		return withClaim(lines(Component.translatable("diorama.map.spot", spot.getX(), spot.getY(), spot.getZ()), detail), spot.getX(), spot.getZ());
	}

	/**
	 * A mob's, player's or waypoint's tooltip, with the claim it stands in on a line underneath, as
	 * an empty spot's tooltip has: the claim would be unreadable wherever something stands on it.
	 */
	private static List<FormattedCharSequence> withClaim(Component described, int x, int z) {
		return withClaim(List.of(described.getVisualOrderText()), x, z);
	}

	private static List<FormattedCharSequence> withClaim(List<FormattedCharSequence> lines, int x, int z) {
		Claims.Claim claim = Settings.get().claims == Settings.ClaimsShown.OFF ? null : Claims.get().at(x >> 4, z >> 4);
		if (claim == null) {
			return lines;
		}
		List<FormattedCharSequence> both = new ArrayList<>(lines);
		both.add(claimLine(claim).getVisualOrderText());
		return both;
	}

	/** The text of the tooltip the map itself showed at the mouse last frame, a line each; empty with none. Public for tests. */
	public List<String> tooltipText() {
		List<String> text = new ArrayList<>();
		for (FormattedCharSequence line : this.tooltipLines == null ? List.<FormattedCharSequence>of() : this.tooltipLines) {
			StringBuilder builder = new StringBuilder();
			line.accept((index, style, codePoint) -> {
				builder.appendCodePoint(codePoint);
				return true;
			});
			text.add(builder.toString());
		}
		return text;
	}

	/** The status line at the top of the map as drawn last frame. Public for tests. */
	public String statusLine() {
		return this.statusShown;
	}

	/** Who owns the chunk under the mouse: a square of their colour, then their name, or the claim's own title. */
	private static Component claimLine(Claims.Claim claim) {
		if (claim.titled()) {
			return Component.literal("■ ").withStyle(s -> s.withColor(claim.rgb()))
				.append(Component.literal(claim.name()).withStyle(ChatFormatting.GRAY));
		}
		Component owner = claim.name().isEmpty() ? Component.translatable("diorama.map.claim.unnamed") : Component.literal(claim.name());
		Component line = Component.literal("■ ").withStyle(s -> s.withColor(claim.rgb()))
			.append(Component.translatable("diorama.map.claim", owner).withStyle(ChatFormatting.GRAY));
		return claim.forceLoaded() ? line.copy().append(Component.translatable("diorama.map.claim.force_loaded").withStyle(ChatFormatting.GRAY)) : line;
	}

	/**
	 * The biome at a spot: the game's where it has the chunk loaded, else in singleplayer the one in
	 * the world's save, which takes a moment to read, and on a server the one seen there before
	 * ({@link #seenBiome}). Null until then, and where none has it.
	 * Public for tests.
	 */
	public @Nullable Component biomeAt(BlockPos pos) {
		ClientLevel level = this.minecraft.level;
		if (level == null) {
			return null;
		}
		// The game's lookup picks the biome from one of the cells around the spot, and near a chunk's
		// edge that cell can be in the next chunk. Where that one isn't loaded, the game's stand-in
		// chunk answers, all Plains. So every chunk a candidate cell is in has to be loaded. Ask the
		// chunk source: the client level answers that every chunk is loaded (#262).
		int cellX = (pos.getX() - 2) >> 2;
		int cellZ = (pos.getZ() - 2) >> 2;
		boolean loaded = true;
		for (int chunkX = cellX >> 2; chunkX <= ((cellX + 1) >> 2); chunkX++) {
			for (int chunkZ = cellZ >> 2; chunkZ <= ((cellZ + 1) >> 2); chunkZ++) {
				loaded &= level.getChunkSource().hasChunk(chunkX, chunkZ);
			}
		}
		Holder<Biome> biome;
		if (loaded) {
			biome = level.getBiome(pos);
		} else {
			if (this.savedBiomes == null || this.savedBiomes.level() != level) {
				this.savedBiomes = SavedBiomes.open(level);
			}
			biome = this.savedBiomes == null ? null : this.savedBiomes.at(pos);
			if (this.savedBiomes == null) {
				return this.seenBiome(pos);
			}
		}
		return biome == null ? null : biome.unwrapKey()
			.map(k -> (Component) Component.translatableWithFallback(k.identifier().toLanguageKey("biome"), k.identifier().getPath()))
			.orElse(null);
	}

	/**
	 * On a server, the biome the player saw at the surface over a spot, kept with the map. One for
	 * every 4 × 4 columns, so a block or two from a border it can name the neighbour, where the game
	 * blurs the line. A cave map's spots are under the surface, in biomes of their own: none there.
	 */
	private @Nullable Component seenBiome(BlockPos pos) {
		MapSession session = MapSession.get();
		if (session == null || session.seenBiomes() == null || this.layer != TileKey.SURFACE) {
			return null;
		}
		String name = session.seenBiomes().at(pos.getX(), pos.getZ());
		Identifier id = name == null ? null : Identifier.tryParse(name);
		return id == null ? null : Component.translatableWithFallback(id.toLanguageKey("biome"), id.getPath());
	}

	/** A tooltip: a white first line and a grey second one. */
	private static List<FormattedCharSequence> lines(Component first, Component second) {
		return List.of(first.getVisualOrderText(), second.copy().withStyle(ChatFormatting.GRAY).getVisualOrderText());
	}

	private static Component key(Component name) {
		return Component.translatable("diorama.map.key", name);
	}

	/** A fixed key's name in the player's keyboard layout and language. */
	private static Component keyName(int key) {
		//? if >=26.3 {
		return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName();
		//?} else {
		/*return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName();
		*///?}
	}

	/** For tests on versions without a tick freeze: the map pauses a singleplayer world while it's open. */
	private static boolean pausesForTests;

	/** For tests: see {@link #pausesForTests}. */
	public static void pauseForTests(boolean pause) {
		pausesForTests = pause;
	}

	@Override
	public boolean isPauseScreen() {
		return pausesForTests;
	}

	@Override
	public void removed() {
		CreateTrains.tick(false);
		this.viewport.close();
		this.draw.close();
		this.closeOtherMap();
		// A look-up for the mark's height still under way is dropped.
		if (this.goMark != null) {
			this.goMark.looking = false;
		}
	}

	/** The dimension the map shows. */
	public String dimension() {
		if (this.otherDimension != null) {
			return this.otherDimension;
		}
		return this.minecraft.level == null ? "" : this.minecraft.level.dimension().identifier().toString();
	}

	/** For tests: draw mode. */
	public DrawMode drawMode() {
		return this.draw;
	}

	/** For tests: the button that turns draw mode on and off. */
	public Button drawButton() {
		return this.drawButton;
	}

	/** For tests: the button that steps through the dimensions. */
	public Button dimensionButton() {
		return this.dimensionButton;
	}

	/** For tests: the saved map of the dimension shown, if it isn't the player's. */
	public @Nullable MapLevel otherMap() {
		return this.otherMap == null ? null : this.otherMap.map();
	}

	/** For tests: the view drawn last frame. */
	public MapViewport.@Nullable View view() {
		return this.lastView;
	}

	/** For tests: the waypoint pins drawn last frame, in GUI coordinates. */
	public List<WaypointOverlay.Pin> pins() {
		return this.pins;
	}

	/** For tests: the Go to coordinates button and its box. */
	public Button goButton() {
		return this.goButton;
	}

	public EditBox goBox() {
		return this.goBox;
	}

	/** For tests: what the box last couldn't act on, as said above it. */
	public @Nullable String goProblem() {
		return this.goProblem == null ? null : this.goProblem.getString();
	}

	/** For tests: the mark's block, and 1 if its height is the place's own, 0 for a guess; null with no mark. */
	public int @Nullable [] goMark() {
		GoMark mark = this.goMark;
		return mark == null ? null : new int[] {mark.x, mark.y, mark.z, mark.heightKnown ? 1 : 0};
	}

	/** For tests: the map point in the middle of the view. */
	public double[] centre() {
		return new double[] {this.centerU, this.centerV};
	}

	/** For tests: the Export button. */
	public Button exportButton() {
		return this.export;
	}

	/** For tests: what a right-click at this GUI point opens, from the pins drawn last frame, without a mouse event. */
	public void openMenuForTests(double x, double y) {
		this.openMenu(x, y);
	}

	/** For tests: the right-click menu, if it's open. */
	public @Nullable MapMenu menu() {
		return this.menu;
	}

	/** For tests: the middle of the depth gauge's slice band last frame, in GUI coordinates; null on the surface. */
	public float @Nullable [] sliceBand() {
		return this.layer == TileKey.SURFACE ? null : this.gauge.band();
	}

	/** For tests: the radar markers drawn last frame, in GUI coordinates. */
	public List<Radar.Blip> blips() {
		return this.blips;
	}

	/** The {@link Overlays} bits to draw, from the settings. */
	private static int overlayBits(MapSession session) {
		Settings s = Settings.get();
		int bits = 0;
		if (s.chunkGrid) {
			bits |= Overlays.CHUNK_WEST | Overlays.CHUNK_NORTH;
		}
		if (s.slimeChunks && session.slimeSeed() != null) {
			bits |= Overlays.SLIME;
		}
		if (s.spawnSpots != Settings.SpawnSpots.OFF) {
			bits |= Overlays.SPAWN_ANY_TIME;
		}
		if (s.spawnSpots == Settings.SpawnSpots.AT_NIGHT_TOO) {
			bits |= Overlays.SPAWN_AT_NIGHT;
		}
		return bits;
	}

	/** Cave slices and unsaved maps have no overview tiles, so they stop at the detailed zoom. */
	/**
	 * Follows the player between the surface and caves, until they pick a layer themselves. Not
	 * while looking at another dimension, which the player isn't standing in. As on the minimap,
	 * the layer on screen stays until the new one has its tiles, or for {@link #FOLLOW_HOLD_MS},
	 * so climbing past a slice edge doesn't swap a map for fog.
	 */
	private void followLayer(MapSession session, Entity viewer) {
		if (!ServerRules.caveMapsAllowed()) {
			this.layer = TileKey.SURFACE;
		}
		if (this.layerPicked || this.otherDimension != null || !Settings.get().autoCaves || !ServerRules.caveMapsAllowed()) {
			this.followHoldStart = -1;
			return;
		}
		int auto = session.autoLayer(viewer);
		if (auto == this.layer) {
			this.followHoldStart = -1;
			return;
		}
		long now = System.currentTimeMillis();
		if (this.followHoldStart < 0) {
			this.followHoldStart = now;
		}
		MapLevel level = session.map();
		MapViewport.View wanted = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, Math.max(this.minZoom(auto), this.zoom), auto);
		MapViewport.View current = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, this.zoom, this.layer);
		// The old layer only helps if it has something to show: after a teleport it doesn't.
		if (MapViewport.ready(level, wanted) || now - this.followHoldStart > FOLLOW_HOLD_MS || !MapViewport.showsAnything(level, current)) {
			this.layer = auto;
			this.followHoldStart = -1;
			// A menu belongs to the view it was opened on, as when the player changes layer.
			this.menu = null;
		}
	}

	private double minZoom() {
		return this.minZoom(this.layer);
	}

	private double minZoom(int layer) {
		MapSession session = MapSession.get();
		boolean overviews = layer == TileKey.SURFACE && session != null && (this.otherDimension != null || session.map().hasOverviews());
		return overviews ? MIN_SCREEN_ZOOM / net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale() : MapViewport.DETAILED_ZOOM;
	}

	/** For tests: the zoom the map last drew at, in GUI pixels per block. */
	public double zoom() {
		return this.zoom;
	}

	/** For tests: put the view at a spot without following the player. */
	public void lookAt(double u, double v, double zoom, int layer) {
		this.centerU = u;
		this.centerV = v;
		this.zoom = zoom;
		this.layer = layer;
		this.layerPicked = true;
		this.following = false;
	}
}