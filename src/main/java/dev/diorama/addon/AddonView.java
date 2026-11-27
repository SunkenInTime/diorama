package dev.diorama.addon;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.api.DimensionId;
import dev.diorama.api.MapView;
import dev.diorama.api.Marker;
import dev.diorama.api.ScreenPos;
import dev.diorama.api.ViewPlace;
import dev.diorama.api.WorldPos;
import dev.diorama.bake.TileKey;
import dev.diorama.render.AddonOverlay;
import dev.diorama.render.GuiStacks;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Markers;
import dev.diorama.render.Radar;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One addon's view of the map in its own screen ({@link MapView}). It draws what the full map
 * draws in its map area, in the order the full map draws it, less the full map's controls, the
 * player's drawings and what follows the mouse, and with only the parts of Diorama's the addon
 * opened it with. Its {@link MapViewport} (the fog mask's arrays and textures) and its label grid
 * are made at the first draw and let go at close, on leaving or joining a world and on a resource
 * reload; the next draw makes them again.
 *
 * <p>From 1.21.6 the GUI keeps what a draw hands it, textures included, until the frame is drawn
 * at its end. So a view draws once a frame at most, as the minimap and the full map do, and the
 * textures of a viewport it lets go of are closed as the next frame starts ({@link #frame}),
 * never while a draw of this frame may still use them.
 *
 * <p>How many views are drawn is counted here, across all addons, so a frame never draws more
 * than {@link MapView#MAX_DRAWS_PER_FRAME}: with the minimap or the full map that stays within
 * the draws {@link AddonOverlay#RING} keeps apart.
 */
public final class AddonView implements MapView {
	/** Open views of every addon. Only the client thread changes or reads it. */
	private static final List<AddonView> OPEN = new ArrayList<>();
	/** Viewports let go of this frame, closed as the next starts: a draw of this frame may still use their textures. */
	private static final List<MapViewport> RETIRING = new ArrayList<>();
	/** Moves every frame; {@link #drawnFrame} is the frame {@link #drawn} counts. */
	private static long frame;
	private static long drawnFrame = -1;
	private static int drawn;
	/** How deep in screens' drawing the game is now: 0 outside it. */
	private static int screenDraws;
	/** A view is drawing now. */
	private static boolean drawing;
	private static boolean overSaid;
	private static boolean twiceSaid;
	/** How far from the screen's top left a point {@link #worldAt} takes can be, in GUI pixels: as far as a place's rectangle reaches. */
	private static final double MAX_SCREEN = 2.0 * ViewPlace.MAX_SIZE;
	/** Where in the open screen's graphics a view was drawn last, for the tests. */
	public static MapViewport.@Nullable View lastDrawn;
	/**
	 * For the game tests: run in the middle of a view's map, inside the scissors its drawing has
	 * pushed, as if Diorama's own drawing threw or called back there. Null outside them.
	 */
	public static @Nullable Consumer<GuiGraphicsExtractor> insideDrawForTests;

	private final Addon owner;
	private final Set<Part> parts;
	private final boolean player;
	private final boolean waypoints;
	private final boolean radar;
	private @Nullable AddonOverlay addons;
	private @Nullable MapViewport viewport;
	private boolean isOpen;
	/** The frame it was last drawn in. */
	private long drawnIn = -1;

	private AddonView(Addon owner, Set<Part> parts, boolean isOpen) {
		this.owner = owner;
		EnumSet<Part> copy = EnumSet.noneOf(Part.class);
		copy.addAll(parts);
		this.parts = Collections.unmodifiableSet(copy);
		this.player = copy.contains(Part.PLAYER);
		this.waypoints = copy.contains(Part.WAYPOINTS);
		this.radar = copy.contains(Part.RADAR);
		this.isOpen = isOpen;
		if (isOpen) {
			OPEN.add(this);
		}
	}

	/** Open; its owner keeps it, to close it if it fails. */
	static AddonView opened(Addon owner, Set<Part> parts) {
		return new AddonView(owner, parts, true);
	}

	/** One that never draws, for an addon that was switched off. */
	static AddonView closed(Addon owner, Set<Part> parts) {
		return new AddonView(owner, parts, false);
	}

	/**
	 * A frame starts. Called as the game starts each frame, after the last frame was drawn and
	 * before any screen draws: the viewports views let go of are closed now, and every frame gets
	 * the whole draw budget, whatever opened or closed before it.
	 */
	public static void frame() {
		frame++;
		screenDraws = 0;
		if (!RETIRING.isEmpty()) {
			for (MapViewport viewport : RETIRING) {
				viewport.close();
			}
			RETIRING.clear();
		}
	}

	/** A screen starts drawing (ScreenMixin). */
	public static void screenDrawStarts() {
		screenDraws++;
	}

	/** A screen is done drawing (ScreenMixin). One that threw leaves the count up until the next frame starts. */
	public static void screenDrawEnds() {
		if (screenDraws > 0) {
			screenDraws--;
		}
	}

	/**
	 * Lets go of every open view's fog of war and label grid, on leaving or joining a world and on
	 * a resource reload: one world's fog isn't the next one's, and a view may not be drawn again
	 * for a long while. Its next draw makes them again.
	 */
	public static void letGoOfMemory() {
		for (AddonView view : OPEN) {
			view.letGo();
		}
	}

	/** How many views of every addon are open: for the tests. */
	public static int openCount() {
		return OPEN.size();
	}

	/** How many viewports wait for the next frame to be closed: for the tests. */
	public static int retiringCount() {
		return RETIRING.size();
	}

	/** Its viewport, or null: for the tests that measure what it holds. */
	public @Nullable MapViewport viewportForTests() {
		return this.viewport;
	}

	/** Its label grid's overlay, or null: for the tests that measure what it holds. */
	public @Nullable AddonOverlay overlayForTests() {
		return this.addons;
	}

	/**
	 * Whether it holds a fog of war's arrays and textures or a label grid: for the tests. Not
	 * before its first draw, nor after close, leaving a world or a resource reload.
	 */
	public boolean holdsMemory() {
		return this.viewport != null || this.addons != null;
	}

	@Override
	public Set<Part> parts() {
		Addon.checkThread();
		return this.parts;
	}

	@Override
	public boolean render(Object graphics, ViewPlace place, float partialTick) {
		Addon.checkThread();
		Objects.requireNonNull(graphics, "graphics");
		Objects.requireNonNull(place, "place");
		if (!(graphics instanceof GuiGraphicsExtractor g)) {
			throw new IllegalArgumentException("render() takes the graphics object the game hands a screen's drawing ("
				+ GuiGraphicsExtractor.class.getName() + " on this version), not " + graphics.getClass().getName());
		}
		if (screenDraws == 0) {
			throw new IllegalStateException("render() draws only from inside a screen's own drawing, with the graphics object the game handed it: what's drawn anywhere else is never shown");
		}
		if (drawing) {
			throw new IllegalStateException("render() was called while a map view was drawing");
		}
		float a = partialTick >= 0 && partialTick <= 1 ? partialTick : partialTick > 1 ? 1 : 0;
		if (!this.isOpen) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		MapSession session = MapSession.get();
		Entity viewer = MapSession.viewer();
		// No session in a dimension where the map is off; and maps off on this server close the full
		// map. Just after a respawn or a change of dimension, Diorama holds the old level's session
		// until the next tick: nothing until it is the game's level.
		if (session == null || viewer == null || mc.level == null || mc.player == null || !session.isFor(mc.level) || !ServerRules.minimapAllowed()) {
			return false;
		}
		if (this.drawnIn == frame) {
			if (!twiceSaid) {
				twiceSaid = true;
				Diorama.LOGGER.warn("Diorama addon {} drew the same map view twice in a frame; the second draw is left out: a view draws once a frame, so two places take two views",
					this.owner.id());
			}
			return false;
		}
		if (drawnFrame != frame) {
			drawnFrame = frame;
			drawn = 0;
		}
		if (drawn >= MAX_DRAWS_PER_FRAME) {
			if (!overSaid) {
				overSaid = true;
				Diorama.LOGGER.warn("Diorama addon {} drew a map view after addons' views had been drawn {} times this frame, the most a frame draws; it and any more this frame are left out",
					this.owner.id(), MAX_DRAWS_PER_FRAME);
			}
			return false;
		}
		drawn++;
		this.drawnIn = frame;
		// Only what is on the screen is drawn, and only that asks for tiles.
		int left = Math.max(place.x(), 0);
		int top = Math.max(place.y(), 0);
		int right = Math.min(place.x() + place.width(), g.guiWidth());
		int bottom = Math.min(place.y() + place.height(), g.guiHeight());
		if (right <= left || bottom <= top) {
			return true;
		}
		double zoom = zoomFor(session, place);
		double centerU = Projection.u(place.centerX(), place.centerZ()) + ((left + right) / 2.0 - (place.x() + place.width() / 2.0)) / zoom;
		double centerV = Projection.v(place.centerX(), place.centerY(), place.centerZ()) + ((top + bottom) / 2.0 - (place.y() + place.height() / 2.0)) / zoom;
		MapViewport.View view = new MapViewport.View(left, top, right - left, bottom - top, centerU, centerV, zoom, TileKey.SURFACE);
		lastDrawn = view;
		if (this.viewport == null) {
			this.viewport = new MapViewport();
		}
		if (this.addons == null) {
			this.addons = new AddonOverlay();
		}
		MapViewport viewport = this.viewport;
		AddonOverlay addons = this.addons;
		Settings settings = Settings.get();
		String dimension = mc.level.dimension().identifier().toString();
		Consumer<GuiGraphicsExtractor> inside = insideDrawForTests;
		drawing = true;
		// Drawn on a pose stack of Diorama's own, inside the caller's scissor (a scissor pushed is cut
		// to the one under it), with every scissor pushed popped again however the drawing ends.
		GuiStacks.enter(g);
		try {
			g.enableScissor(left, top, right, bottom);
			// As the full map draws its map area: the tiles with their marks, addons' lines, the radar's
			// mobs, addons' markers, waypoints, the radar's players and the player's arrow. No held
			// lights: they show on cave slices, and this is the surface.
			viewport.draw(g, session.map(), view, MapScreen.overlayBits(session), settings.claims != Settings.ClaimsShown.OFF, inside == null ? null : () -> inside.accept(g));
			DimensionId addonDimension = AddonOverlay.any() ? addons.dimension(dimension) : null;
			if (addonDimension != null) {
				addons.drawLines(g, view, addonDimension, true);
			}
			List<Radar.Blip> blips = this.radar && ServerRules.radarAllowed() && settings.radarOnMap ? Radar.collect(view, a, true) : List.of();
			Radar.draw(g, view, blips, true, false);
			if (addonDimension != null) {
				addons.drawMarkers(g, view, addonDimension, true);
			}
			WaypointStore store = this.waypoints && settings.waypointsOnMap ? WaypointStore.get() : null;
			if (store != null) {
				WaypointOverlay.draw(g, view, WaypointOverlay.layout(view, store.shownIn(dimension), true), true, null);
			}
			Radar.draw(g, view, blips, true, true);
			if (this.player) {
				Vec3 pos = viewer.getPosition(a);
				Markers.player(g, view.screenX(Projection.u(pos.x, pos.z)), view.screenY(Projection.v(pos.x, pos.y, pos.z)),
					Markers.onMap(viewer.getViewYRot(a)), 6 * settings.arrowScale());
			}
		} finally {
			GuiStacks.leave(g);
			drawing = false;
		}
		return true;
	}

	/** The zoom a view draws at: the place's, unless the map has no overview tiles to show it that far out. */
	private static double zoomFor(@Nullable MapSession session, ViewPlace place) {
		boolean overviews = session != null && session.map().hasOverviews();
		return Math.max(MapScreen.minZoom(overviews), Math.min(ViewPlace.MAX_ZOOM, place.zoom()));
	}

	@Override
	public double zoom(ViewPlace place) {
		Addon.checkThread();
		Objects.requireNonNull(place, "place");
		return zoomFor(MapSession.get(), place);
	}

	@Override
	public ScreenPos project(ViewPlace place, double x, double y, double z) {
		Addon.checkThread();
		Objects.requireNonNull(place, "place");
		checkPlace(x, y, z);
		double zoom = zoomFor(MapSession.get(), place);
		double u = Projection.u(x, z) - Projection.u(place.centerX(), place.centerZ());
		double v = Projection.v(x, y, z) - Projection.v(place.centerX(), place.centerY(), place.centerZ());
		return new ScreenPos(place.x() + place.width() / 2.0 + u * zoom, place.y() + place.height() / 2.0 + v * zoom);
	}

	@Override
	public WorldPos worldAt(ViewPlace place, double screenX, double screenY, double y) {
		Addon.checkThread();
		Objects.requireNonNull(place, "place");
		if (!(Math.abs(screenX) <= MAX_SCREEN) || !(Math.abs(screenY) <= MAX_SCREEN)) {
			throw new IllegalArgumentException("A point on the screen is from -" + (int) MAX_SCREEN + " to " + (int) MAX_SCREEN + " GUI pixels either way, as far as a view's rectangle reaches; not "
				+ screenX + ", " + screenY);
		}
		if (!(Math.abs(y) <= Marker.MAX_HEIGHT)) {
			throw new IllegalArgumentException("A place on the map has y from -" + Marker.MAX_HEIGHT + " to " + Marker.MAX_HEIGHT + ", where the game stops a player; y is " + y);
		}
		double zoom = zoomFor(MapSession.get(), place);
		double u = Projection.u(place.centerX(), place.centerZ()) + (screenX - (place.x() + place.width() / 2.0)) / zoom;
		double v = Projection.v(place.centerX(), place.centerY(), place.centerZ()) + (screenY - (place.y() + place.height() / 2.0)) / zoom;
		double turnedZ = Projection.zAt(v, y);
		return new WorldPos(Projection.facing().worldX(u, turnedZ), y, Projection.facing().worldZ(u, turnedZ));
	}

	/** As a marker's place is checked where it's built. */
	private static void checkPlace(double x, double y, double z) {
		if (!(Math.abs(x) <= Marker.MAX_COORDINATE) || !(Math.abs(z) <= Marker.MAX_COORDINATE)) {
			throw new IllegalArgumentException("A place on the map has x and z from -" + Marker.MAX_COORDINATE + " to " + Marker.MAX_COORDINATE + ", where the game stops a player; x is "
				+ x + " and z is " + z);
		}
		if (!(Math.abs(y) <= Marker.MAX_HEIGHT)) {
			throw new IllegalArgumentException("A place on the map has y from -" + Marker.MAX_HEIGHT + " to " + Marker.MAX_HEIGHT + ", where the game stops a player; y is " + y);
		}
	}

	@Override
	public void close() {
		Addon.checkThread();
		if (this.release()) {
			this.owner.forget(this);
		}
	}

	@Override
	public boolean isOpen() {
		Addon.checkThread();
		return this.isOpen;
	}

	/**
	 * Stops drawing it and lets go of what it holds: the handle keeps nothing, and the viewport's
	 * textures are closed as the next frame starts. True if it was still open.
	 */
	boolean release() {
		if (!this.isOpen) {
			return false;
		}
		this.isOpen = false;
		OPEN.remove(this);
		this.letGo();
		return true;
	}

	/** Lets go of the fog of war and the label grid; the viewport is closed as the next frame starts. */
	private void letGo() {
		if (this.viewport != null) {
			RETIRING.add(this.viewport);
			this.viewport = null;
		}
		this.addons = null;
	}

	@Override
	public String toString() {
		return "Map view of " + this.owner.id() + " " + this.parts + (this.isOpen ? "" : " (closed)");
	}
}
