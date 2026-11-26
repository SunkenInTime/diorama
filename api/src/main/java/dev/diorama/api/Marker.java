package dev.diorama.api;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One point an addon shows on Diorama's maps, the minimap and the full map: a dot in a colour, or
 * an icon, with an optional label. Made with {@link #builder}, handed to Diorama in a
 * {@link LayerContent}, and never changed after: to move a marker, replace the layer's content
 * with one where it has moved. Every value is checked as it is set, so a bad one throws where the
 * addon gave it.
 *
 * <p><b>Where it shows.</b> A marker is at a place in the world, {@code x, y, z}, in one
 * dimension, and Diorama draws it where something at that place shows on its 3D map, as it draws
 * a waypoint or a mob there. The map looks down at the ground from the south at a slant, so
 * {@code y} counts: a marker at the ground's height sits on the ground, and one higher up shows
 * further up the picture, as a tower's top does. Give the height of the thing marked, such as
 * the block a building stands on plus one; a marker given sea level for a town on a mountain
 * shows south of the town. In a cave view the same holds, so a marker above the cave slice shown
 * sits north of the spot under it.
 *
 * <p>A marker shows only on a map of its own dimension: on the minimap while the player is in
 * that dimension, and on the full map while it shows that dimension, whether the player is in it
 * or looking at it from another with the map's dimension button. A dimension has the same markers
 * on every one of its saved maps (Diorama's Maps screen), since they are at places in the world.
 * Markers stay upright and the same size on screen as the map is zoomed, turned or tilted, and
 * never move to the edge of the minimap the way a waypoint far away does: one outside the view
 * isn't drawn. On a round minimap, only markers wholly inside its circle are drawn.
 *
 * <pre>{@code
 * Marker inn = Marker.builder(DimensionId.OVERWORLD, 120.5, 71, -339.5)
 *     .icon(ResourceId.of("diorama:home"))
 *     .label("Inn")
 *     .build();
 * Marker cart = Marker.builder(DimensionId.OVERWORLD, cartX, cartY, cartZ).rgb(0xF2C14E).build();
 * }</pre>
 */
public final class Marker {
	/**
	 * The longest label a marker can have: 32, as for a waypoint's name, counted in UTF-16 units as
	 * {@link String#length} counts them, after spaces at the ends are taken off.
	 */
	public static final int MAX_LABEL_LENGTH = 32;
	/** The smallest size a marker can be given, in GUI pixels: 2. */
	public static final int MIN_SIZE = 2;
	/** The largest size a marker can be given, in GUI pixels: 32. */
	public static final int MAX_SIZE = 32;
	/** A dot's size when none is given, in GUI pixels: 6, a little bigger than the radar's mob dots on the minimap. */
	public static final int DOT_SIZE = 6;
	/** An icon's size when none is given, in GUI pixels: 11, the size waypoint icons are drawn at. */
	public static final int ICON_SIZE = 11;
	/**
	 * How far east, west, north or south a marker or a line's point can be: 30,000,000 blocks,
	 * where the game stops a player (its {@code Level.MAX_LEVEL_SIZE}). The world border can't go
	 * further out.
	 */
	public static final int MAX_COORDINATE = 30_000_000;
	/** How far up or down a marker or a line's point can be: 20,000,000 blocks, where the game stops a player. */
	public static final int MAX_HEIGHT = 20_000_000;

	private final DimensionId dimension;
	private final double x;
	private final double y;
	private final double z;
	private final int rgb;
	/** Made once, so reading it every frame allocates nothing. */
	private final Optional<ResourceId> icon;
	private final String label;
	private final int size;

	private Marker(Builder builder) {
		this.dimension = builder.dimension;
		this.x = builder.x;
		this.y = builder.y;
		this.z = builder.z;
		this.rgb = builder.rgb;
		this.icon = Optional.ofNullable(builder.icon);
		this.label = builder.label;
		this.size = builder.size != 0 ? builder.size : builder.icon != null ? ICON_SIZE : DOT_SIZE;
	}

	/**
	 * Starts a marker at a place in the world.
	 *
	 * @param dimension the dimension it is in
	 * @param x east is positive; the middle of a block is its coordinate plus 0.5
	 * @param y up is positive: the height of the thing marked (see the class description)
	 * @param z south is positive; the middle of a block is its coordinate plus 0.5
	 * @return a builder with the rest at their defaults: a dot in the first colour of Diorama's
	 *     palette (red), {@link #DOT_SIZE} across, with no label
	 * @throws IllegalArgumentException if {@code x} or {@code z} is beyond {@link #MAX_COORDINATE}
	 *     either way, {@code y} beyond {@link #MAX_HEIGHT}, or any of them NaN
	 * @throws NullPointerException if {@code dimension} is null
	 */
	public static Builder builder(DimensionId dimension, double x, double y, double z) {
		return new Builder(Objects.requireNonNull(dimension, "dimension"), Checks.horizontal("x", x), Checks.height(y), Checks.horizontal("z", z));
	}

	/** {@return the dimension it is in} */
	public DimensionId dimension() {
		return this.dimension;
	}

	/** {@return where it is, east is positive} */
	public double x() {
		return this.x;
	}

	/** {@return its height} */
	public double y() {
		return this.y;
	}

	/** {@return where it is, south is positive} */
	public double z() {
		return this.z;
	}

	/** {@return its dot's colour, as 0xRRGGBB; an icon shows its own colours, and this one only when no resource pack has it} */
	public int rgb() {
		return this.rgb;
	}

	/** {@return its icon, or empty for a dot} */
	public Optional<ResourceId> icon() {
		return this.icon;
	}

	/** {@return its label, or empty for none} */
	public String label() {
		return this.label;
	}

	/** {@return how big it is drawn, in GUI pixels: the size given, or {@link #DOT_SIZE} or {@link #ICON_SIZE}} */
	public int size() {
		return this.size;
	}

	@Override
	public String toString() {
		return "Marker[" + (this.icon.isPresent() ? this.icon.get() : String.format("dot 0x%06X", this.rgb)) + " at " + this.x + ", " + this.y + ", " + this.z + " in " + this.dimension
			+ (this.label.isEmpty() ? "" : ", \"" + this.label + "\"") + "]";
	}

	/** Sets the rest of a {@link Marker}. Each setter checks what it is given and returns this builder. */
	public static final class Builder {
		private final DimensionId dimension;
		private final double x;
		private final double y;
		private final double z;
		private int rgb = Checks.DEFAULT_RGB;
		private @Nullable ResourceId icon;
		private String label = "";
		/** 0 for the default of whichever look it ends up with. */
		private int size;

		private Builder(DimensionId dimension, double x, double y, double z) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		/**
		 * Sets the dot's colour. Markers are drawn opaque, so there is no alpha to give.
		 *
		 * @param rgb {@code 0xRRGGBB}, such as {@code 0x46A758}
		 * @return this builder
		 * @throws IllegalArgumentException if anything is set above the low 24 bits, as an ARGB
		 *     colour such as {@code 0xFF46A758} has
		 */
		public Builder rgb(int rgb) {
			this.rgb = Checks.rgb("A marker's colour", rgb);
			return this;
		}

		/**
		 * Shows an icon instead of a dot: the same pictures waypoints show, so Diorama's own
		 * ({@code diorama:home}, {@code diorama:mine}, {@code diorama:villagers} and the others in
		 * its waypoint editor) and any a resource pack or the addon's jar adds. The image is
		 * {@code assets/<namespace>/textures/waypoint_icons/<path>.png}: pixel art, cut to its
		 * opaque pixels and drawn on a square tile of 11 pixels, a bigger image shrunk by a whole
		 * factor to fit, each of its pixels a whole number of screen pixels. Draw its own dark
		 * outline in, as Diorama's icons have, so it reads on any ground. An ID no resource pack has
		 * an image for shows as a dot in {@link #rgb}.
		 *
		 * <p>A map shows up to 210 different icons at once, which is as many as its icon texture
		 * holds; a marker whose icon finds no room that frame shows as a dot in {@link #rgb}, and
		 * Diorama logs it once. An icon no marker shows any more gives its room up.
		 *
		 * @param icon such as {@code diorama:home} for
		 *     {@code assets/diorama/textures/waypoint_icons/home.png}
		 * @return this builder
		 * @throws IllegalArgumentException if the icon's path has an empty part, {@code .} or
		 *     {@code ..}, such as {@code mymod:icons//den} or {@code mymod:../den}
		 * @throws NullPointerException if {@code icon} is null
		 */
		public Builder icon(ResourceId icon) {
			this.icon = Checks.icon(icon);
			return this;
		}

		/**
		 * Sets the text the full map shows under it, white on a dark plate, as it shows a
		 * waypoint's name. The minimap shows no labels. Where two labels would overlap, only the
		 * first is drawn, in the order the markers are drawn (layer by layer, then each layer's
		 * markers in order), and the others show once the player zooms in far enough to part
		 * them: put the labels that matter most first.
		 *
		 * <p>A label is plain text, drawn in one colour: the game's formatting codes ({@code §} and
		 * the character after it) are taken out. A label that is empty after that is no label, and
		 * takes no room from others.
		 *
		 * @param label {@link #MAX_LABEL_LENGTH} UTF-16 units at most once formatting codes and
		 *     spaces at the ends are taken off, or empty for none
		 * @return this builder
		 * @throws IllegalArgumentException if the label is too long
		 * @throws NullPointerException if {@code label} is null
		 */
		public Builder label(String label) {
			this.label = Checks.label(label);
			return this;
		}

		/**
		 * Sets how big it is drawn: a dot this many GUI pixels across, or an icon's tile this many
		 * GUI pixels across, rounded to a whole number of screen pixels for each of its pixels.
		 *
		 * @param size from {@link #MIN_SIZE} to {@link #MAX_SIZE}
		 * @return this builder
		 * @throws IllegalArgumentException if the size is outside that range
		 */
		public Builder size(int size) {
			if (size < MIN_SIZE || size > MAX_SIZE) {
				throw new IllegalArgumentException("A marker's size is " + MIN_SIZE + " to " + MAX_SIZE + " GUI pixels, not " + size);
			}
			this.size = size;
			return this;
		}

		/** {@return the marker, which this builder can go on to make more of} */
		public Marker build() {
			return new Marker(this);
		}
	}
}
