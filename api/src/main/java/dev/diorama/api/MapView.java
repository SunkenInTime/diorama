package dev.diorama.api;

import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Diorama's map, drawn inside one of the addon's own screens, opened with
 * {@link DioramaApi#openView}. The addon says where on the screen and which place in the world,
 * a {@link ViewPlace}, and calls {@link #render} from its screen's own drawing each frame; Diorama
 * draws the map there at once. Diorama never calls the addon about a view.
 *
 * <pre>{@code
 * // Once, such as when the screen opens:
 * this.view = api.openView(EnumSet.of(MapView.Part.PLAYER));
 *
 * // In the screen's render (extractRenderState from Minecraft 26.1), with its graphics:
 * ViewPlace place = ViewPlace.builder(20, 20, this.width - 40, this.height - 40)
 *     .center(this.lookX, this.lookY, this.lookZ)
 *     .zoom(this.zoom)
 *     .build();
 * if (!this.view.render(graphics, place, partialTick)) {
 *     // Diorama can't show its map right now: draw something else there.
 * }
 *
 * // When the screen closes:
 * this.view.close();
 * }</pre>
 *
 * <p><b>Experimental.</b> This part of the API may change in a minor version, with a line in
 * Diorama's changelog, where the rest only grows.
 *
 * <p><b>What it shows.</b> The map of the dimension the player is in, the same one the minimap
 * shows: the saved map in use there, as it looks with the player's settings (fog of war, night,
 * land claims, the chunk grid and the other marks the full map draws over the ground). Not the
 * light of torches that mobs and players hold, which the full map shows on cave views only. It
 * shows the surface: underground too, since cave
 * views aren't part of this API yet. It looks the way Diorama's full map looks, turned to the
 * same side, so a player who turned the full map to look from the east sees that here too. Every
 * addon's layers of markers and lines ({@link MapLayer}) are drawn on it as on the full map,
 * labels included, and so are the {@link Part parts} the view was opened with. Nothing else of
 * the full map is: its buttons, tooltips, menus and the player's drawings stay in the full map,
 * and clicks on the view are the addon's to handle.
 *
 * <p>The server's rules hold as they do for Diorama's own maps: nothing is drawn where the server
 * turns Diorama's maps off or the map is off in the player's dimension, and the radar shows only
 * where the server lets it. A view only takes away from what the player could see on the full
 * map, never adds.
 *
 * <p><b>Limits.</b> An addon can have {@value #MAX_OPEN} views open at once. A view draws once a
 * frame, and all addons' views together are drawn {@value #MAX_DRAWS_PER_FRAME} times a frame at
 * most: a call past either draws nothing and returns false, and Diorama logs a warning the first
 * time. To show the map in two places, open two views. Only the part of the view's rectangle that
 * is on the screen is drawn, and the map loads and keeps what that part needs, as the full map
 * would at the same size and zoom.
 *
 * <p><b>Memory.</b> From its first {@link #render}, a view holds what it needs to draw again
 * quickly, measured on Minecraft 26.3 for a view over the whole screen:
 * <ul>
 *   <li>2.57 MiB of arrays for working out its fog of war, whatever its size;</li>
 *   <li>two 256 x 256 textures for the fog, 512 KiB together;</li>
 *   <li>with Fancy fog, a texture the fog is drawn into at half the screen's resolution, rounded
 *       up to a multiple of 256 pixels: 4 MiB on a 1920 x 1080 screen, 16 MiB on a 3840 x 2160
 *       one (none with Fast fog, unless the view drew with Fancy before);</li>
 *   <li>a grid that keeps addons' marker labels apart, 4 bytes for each 4 x 4 GUI pixels of the
 *       view: 33 KiB on a 1920 x 1080 screen at GUI scale 4, and by the same count 512 KiB on a
 *       3840 x 2160 screen at GUI scale 2. It keeps the size of the largest rectangle the view
 *       has drawn.</li>
 * </ul>
 * So a whole-screen view with Fancy fog holds 7.1 MiB at 1920 x 1080 and 19.1 MiB at
 * 3840 x 2160 (GUI scale 9); with Fast fog, 3.1 MiB at either.
 * Closing the view lets all of it go (the textures as the next frame starts, since the frame
 * being drawn may still use them), and so do leaving or joining a world and a resource reload,
 * after which the next {@link #render} makes it again. A view stays open until the addon closes
 * it, the addon is switched off after a failure, or the game exits; it is open across worlds and
 * dimensions, and shows whichever the player is in.
 *
 * <p>The player can untick the addon in the Addons tab of Diorama's settings. That stops its
 * layers of markers and lines showing, here too, and leaves its views drawing the map, since a
 * view is drawn in the addon's own screen.
 *
 * <p>Every method must be called on the client thread; calls from any other thread throw
 * {@link IllegalStateException}.
 */
@ApiStatus.Experimental
public interface MapView extends Handle {
	/** The most views one addon can have open at once: 2. */
	int MAX_OPEN = 2;
	/** The most times all addons' views together are drawn in a frame: 2. */
	int MAX_DRAWS_PER_FRAME = 2;

	/** Something of Diorama's that a view draws over the map, if it was opened with it. More may be added. */
	enum Part {
		/** The player's arrow, where the player is. */
		PLAYER,
		/**
		 * The player's waypoints of this dimension, as the full map draws them, when the player's
		 * settings show waypoints on the map.
		 */
		WAYPOINTS,
		/**
		 * The radar's mobs and players, as the full map draws them, when the player's settings show
		 * the radar on the map and the server allows it.
		 */
		RADAR,
	}

	/** {@return the parts it was opened with, which it draws over the map} */
	Set<Part> parts();

	/**
	 * Draws the map in this place, now, into what the screen is drawing this frame: over what was
	 * drawn before, and under what is drawn after. Call it from the screen's own drawing (its
	 * {@code render}, {@code extractRenderState} from Minecraft 26.1), with the graphics object the
	 * game handed that call. Drawn with a graphics object kept from another frame, or from a tick,
	 * it shows nothing, and still counts as the view's draw for the frame. Nothing is drawn outside
	 * the place's rectangle, nor outside a scissor the
	 * screen has on (a scrolling panel's), and the graphics' scissors and transforms are left as
	 * they were, even if the drawing throws. On a closed view this draws nothing.
	 *
	 * <p>A view draws once a frame: a second call in the same frame returns false. Through
	 * Minecraft 1.21.4 the map draws straight away with OpenGL, into the framebuffer that is
	 * bound, and puts back the viewport and the blending it found. From 1.21.2 to 1.21.4 every GUI
	 * draw, the game's own included, binds the game's main framebuffer, so there a view, like the
	 * rest of the screen, draws into that one whatever was bound before.
	 *
	 * @param graphics the graphics object the game handed the screen's drawing this frame: a
	 *     {@code net.minecraft.client.gui.GuiGraphicsExtractor} from Minecraft 26.1, a
	 *     {@code GuiGraphics} before, with no transform pushed on it (its place's rectangle is in
	 *     the screen's own GUI pixels)
	 * @param place where it is drawn and what it shows
	 * @param partialTick how far the frame is between two ticks, 0 to 1, as the game hands it to
	 *     the screen: where the player and mobs are drawn between ticks
	 * @return true if Diorama drew its map, or would have if the rectangle were on the screen;
	 *     false if it couldn't: the view is closed or was drawn already this frame, no world is
	 *     open, the player has just changed world or dimension and Diorama hasn't yet (until the
	 *     next tick), the server turns maps off or the map is off in the player's dimension, or
	 *     views were already drawn {@link #MAX_DRAWS_PER_FRAME} times this frame
	 * @throws IllegalArgumentException if {@code graphics} isn't this Minecraft version's graphics
	 *     object
	 * @throws NullPointerException if {@code graphics} or {@code place} is null
	 * @throws IllegalStateException if called off the client thread, or from inside a view's own
	 *     drawing
	 */
	boolean render(Object graphics, ViewPlace place, float partialTick);

	/**
	 * {@return the zoom the view draws this place at, in GUI pixels a block} That is the place's
	 * own zoom, unless the map can't show that much: zoomed far out, the map draws from pictures it
	 * makes of the saved map, and where there are none yet (a new world, or a map not saved to
	 * disk), the view stops where Diorama's full map stops, half a GUI pixel a block. With no
	 * world open, what it would be without those pictures. {@link #project} and {@link #worldAt}
	 * use this zoom.
	 *
	 * @param place the place it would draw
	 * @throws NullPointerException if {@code place} is null
	 * @throws IllegalStateException if called off the client thread
	 */
	double zoom(ViewPlace place);

	/**
	 * {@return where a place in the world shows on the screen, in a view drawn at this place} The
	 * same answer {@link #render} draws by, so something an addon draws there sits where a marker
	 * at that place would. A place outside the rectangle gives a point outside it. This draws
	 * nothing, and works before the view is first drawn, after it is closed, and with no world
	 * open, though the answer changes when the player turns the full map to another side.
	 *
	 * @param place the place the view is drawn at
	 * @param x east is positive
	 * @param y up is positive: the map shows higher things further up the screen
	 * @param z south is positive
	 * @throws IllegalArgumentException if {@code x} or {@code z} is beyond
	 *     {@link Marker#MAX_COORDINATE} either way, {@code y} beyond {@link Marker#MAX_HEIGHT}, or
	 *     any of them NaN
	 * @throws NullPointerException if {@code place} is null
	 * @throws IllegalStateException if called off the client thread
	 */
	ScreenPos project(ViewPlace place, double x, double y, double z);

	/**
	 * {@return the place in the world at this height that shows at this point on the screen, in a
	 * view drawn at this place} The other way from {@link #project}: the map looks down at a
	 * slant, so a point on the screen is a line through the world, and the height picks one place
	 * on it. To pan a view by dragging, keep the height of the place at its middle and ask where
	 * the dragged point now is. Not where the ground is: that would need what the map knows of the
	 * world, which this API doesn't read yet. The answer is always finite, though zoomed far out
	 * it can be past where the game stops a player.
	 *
	 * @param place the place the view is drawn at
	 * @param screenX GUI pixels from the screen's left, from -2 {@link ViewPlace#MAX_SIZE} to
	 *     2 {@link ViewPlace#MAX_SIZE}: as far as a place's rectangle reaches
	 * @param screenY GUI pixels from the screen's top, in the same range
	 * @param y the height to find the place at, up is positive
	 * @throws IllegalArgumentException if {@code screenX} or {@code screenY} is outside that range
	 *     or NaN, or {@code y} is beyond {@link Marker#MAX_HEIGHT} or NaN
	 * @throws NullPointerException if {@code place} is null
	 * @throws IllegalStateException if called off the client thread
	 */
	WorldPos worldAt(ViewPlace place, double screenX, double screenY, double y);
}
