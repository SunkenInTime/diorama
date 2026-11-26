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
import dev.diorama.render.MapViewport;
import dev.diorama.render.Markers;
import dev.diorama.render.Radar;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One addon's view of the map in its own screen ({@link MapView}). It draws what the full map
 * draws in its map area, in the order the full map draws it, less the full map's controls, the
 * player's drawings and what follows the mouse, and with only the parts of Diorama's the addon
 * opened it with. Its {@link MapViewport} (the fog mask's arrays and textures, about 0.6 MB) is
 * made at the first draw and let go at close.
 *
 * <p>How many views are open and drawn is counted here, across all addons ({@link #frame}), so a
 * frame never draws more than {@link MapView#MAX_DRAWS_PER_FRAME}: with the minimap or the full
 * map that stays within the draws {@link AddonOverlay#RING} keeps apart.
 */
public final class AddonView implements MapView {
	/** Open views of every addon. Only the client thread changes or reads it. */
	private static int open;
	/** Moves each frame while a view is open; {@link #drawnFrame} is the frame {@link #drawn} counts. */
	private static long frame;
	private static long drawnFrame = -1;
	private static int drawn;
	private static boolean overSaid;
	/** Where in the open screen's graphics a view was drawn last, for the tests. */
	public static MapViewport.@Nullable View lastDrawn;

	private final Addon owner;
	private final Set<Part> parts;
	private final boolean player;
	private final boolean waypoints;
	private final boolean radar;
	private final AddonOverlay addons = new AddonOverlay();
	private @Nullable MapViewport viewport;
	private boolean isOpen;

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
			open++;
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
	 * A frame starts. Called as the game starts each frame, before any screen draws; with no view
	 * open it does nothing.
	 */
	public static void frame() {
		if (open > 0) {
			frame++;
		}
	}

	/** How many views of every addon are open: for the tests. */
	public static int openCount() {
		return open;
	}

	/** Whether it holds a fog of war's arrays and textures: for the tests. Not before its first draw, nor after close. */
	public boolean holdsFog() {
		return this.viewport != null;
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
		float a = partialTick >= 0 && partialTick <= 1 ? partialTick : partialTick > 1 ? 1 : 0;
		if (!this.isOpen) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		MapSession session = MapSession.get();
		Entity viewer = MapSession.viewer();
		// No session in a dimension where the map is off; and maps off on this server close the full map.
		if (session == null || viewer == null || mc.level == null || mc.player == null || !ServerRules.minimapAllowed()) {
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
		Settings settings = Settings.get();
		String dimension = mc.level.dimension().identifier().toString();
		g.enableScissor(left, top, right, bottom);
		try {
			// As the full map draws its map area: the tiles with their marks, addons' lines, the radar's
			// mobs, addons' markers, waypoints, the radar's players and the player's arrow. No held
			// lights: they show on cave slices, and this is the surface.
			this.viewport.draw(g, session.map(), view, MapScreen.overlayBits(session), settings.claims != Settings.ClaimsShown.OFF, null);
			DimensionId addonDimension = AddonOverlay.any() ? this.addons.dimension(dimension) : null;
			if (addonDimension != null) {
				this.addons.drawLines(g, view, addonDimension, true);
			}
			List<Radar.Blip> blips = this.radar && ServerRules.radarAllowed() && settings.radarOnMap ? Radar.collect(view, a, true) : List.of();
			Radar.draw(g, view, blips, true, false);
			if (addonDimension != null) {
				this.addons.drawMarkers(g, view, addonDimension, true);
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
			g.disableScissor();
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
		if (!Double.isFinite(screenX) || !Double.isFinite(screenY)) {
			throw new IllegalArgumentException("A point on the screen has finite coordinates, not " + screenX + ", " + screenY);
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

	/** Stops drawing it and lets its fog of war go. True if it was still open. */
	boolean release() {
		if (!this.isOpen) {
			return false;
		}
		this.isOpen = false;
		open--;
		if (this.viewport != null) {
			this.viewport.close();
			this.viewport = null;
		}
		return true;
	}

	@Override
	public String toString() {
		return "Map view of " + this.owner.id() + " " + this.parts + (this.isOpen ? "" : " (closed)");
	}
}
