package dev.diorama.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * Where a {@link MapView} is drawn on the screen, and which place in the world it shows there:
 * a rectangle in GUI pixels, the place in the world at its middle, and how far in it is zoomed.
 * Made with {@link #builder}, handed to {@link MapView#render} each frame, and never changed
 * after: to pan or zoom, make another one. Every value is checked as it is set, so a bad one
 * throws where the addon gave it.
 *
 * <pre>{@code
 * ViewPlace place = ViewPlace.builder(left, top, width, height)
 *     .center(player.getX(), player.getY(), player.getZ())
 *     .zoom(2)
 *     .build();
 * }</pre>
 *
 * <p>Experimental, as {@link MapView} is.
 */
@ApiStatus.Experimental
public final class ViewPlace {
	/**
	 * The most GUI pixels a view can be wide or high, and how far its corner can be from the
	 * screen's top left either way: 8,192. Only what of it is on the screen is drawn.
	 */
	public static final int MAX_SIZE = 8192;
	/**
	 * The furthest in a view can be zoomed: 16 GUI pixels a block, as far as Diorama's full map
	 * goes.
	 */
	public static final double MAX_ZOOM = 16;
	/**
	 * The furthest out a view can be asked to zoom: 1/4,096 of a GUI pixel a block, which is as far
	 * as Diorama's full map goes at the largest GUI scale. Where the map can't show that much, the
	 * view is drawn at the furthest out it can: see {@link MapView#zoom}.
	 */
	public static final double MIN_ZOOM = 1.0 / 4096;
	/** The zoom when none is given: 2 GUI pixels a block, where Diorama's full map opens. */
	public static final double DEFAULT_ZOOM = 2;

	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final double centerX;
	private final double centerY;
	private final double centerZ;
	private final double zoom;

	private ViewPlace(Builder builder) {
		this.x = builder.x;
		this.y = builder.y;
		this.width = builder.width;
		this.height = builder.height;
		this.centerX = builder.centerX;
		this.centerY = builder.centerY;
		this.centerZ = builder.centerZ;
		this.zoom = builder.zoom;
	}

	/**
	 * Starts a place for a view in this rectangle of the screen, in GUI pixels, the units the
	 * screen's own widgets are placed in.
	 *
	 * @param x the rectangle's left edge
	 * @param y the rectangle's top edge
	 * @param width how wide it is: 1 to {@link #MAX_SIZE}
	 * @param height how high it is: 1 to {@link #MAX_SIZE}
	 * @return a builder that needs {@link Builder#center} before {@link Builder#build}; the zoom is
	 *     {@link #DEFAULT_ZOOM} unless set
	 * @throws IllegalArgumentException if the width or height is outside that range, or {@code x}
	 *     or {@code y} is further than {@link #MAX_SIZE} from 0 either way
	 */
	public static Builder builder(int x, int y, int width, int height) {
		return new Builder(edge("x", x), edge("y", y), size("width", width), size("height", height));
	}

	private static int edge(String what, int value) {
		if (Math.abs((long) value) > MAX_SIZE) {
			throw new IllegalArgumentException("A view's " + what + " is from -" + MAX_SIZE + " to " + MAX_SIZE + " GUI pixels, not " + value);
		}
		return value;
	}

	private static int size(String what, int value) {
		if (value < 1 || value > MAX_SIZE) {
			throw new IllegalArgumentException("A view's " + what + " is 1 to " + MAX_SIZE + " GUI pixels, not " + value);
		}
		return value;
	}

	/** {@return the rectangle's left edge, in GUI pixels} */
	public int x() {
		return this.x;
	}

	/** {@return the rectangle's top edge, in GUI pixels} */
	public int y() {
		return this.y;
	}

	/** {@return how wide the rectangle is, in GUI pixels} */
	public int width() {
		return this.width;
	}

	/** {@return how high the rectangle is, in GUI pixels} */
	public int height() {
		return this.height;
	}

	/** {@return where the place at the rectangle's middle is, east is positive} */
	public double centerX() {
		return this.centerX;
	}

	/** {@return the height of the place at the rectangle's middle} */
	public double centerY() {
		return this.centerY;
	}

	/** {@return where the place at the rectangle's middle is, south is positive} */
	public double centerZ() {
		return this.centerZ;
	}

	/** {@return the zoom asked for, in GUI pixels a block} */
	public double zoom() {
		return this.zoom;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ViewPlace place && place.x == this.x && place.y == this.y && place.width == this.width && place.height == this.height
			&& Double.compare(place.centerX, this.centerX) == 0 && Double.compare(place.centerY, this.centerY) == 0 && Double.compare(place.centerZ, this.centerZ) == 0
			&& Double.compare(place.zoom, this.zoom) == 0;
	}

	@Override
	public int hashCode() {
		int hash = this.x;
		hash = hash * 31 + this.y;
		hash = hash * 31 + this.width;
		hash = hash * 31 + this.height;
		hash = hash * 31 + Double.hashCode(this.centerX);
		hash = hash * 31 + Double.hashCode(this.centerY);
		hash = hash * 31 + Double.hashCode(this.centerZ);
		return hash * 31 + Double.hashCode(this.zoom);
	}

	@Override
	public String toString() {
		return "ViewPlace[" + this.width + "x" + this.height + " at " + this.x + ", " + this.y + ", showing " + this.centerX + ", " + this.centerY + ", " + this.centerZ
			+ " at " + this.zoom + " GUI pixels a block]";
	}

	/** Sets the rest of a {@link ViewPlace}. Each setter checks what it is given and returns this builder. */
	public static final class Builder {
		private final int x;
		private final int y;
		private final int width;
		private final int height;
		private boolean centered;
		private double centerX;
		private double centerY;
		private double centerZ;
		private double zoom = DEFAULT_ZOOM;

		private Builder(int x, int y, int width, int height) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		/**
		 * Sets the place in the world shown at the rectangle's middle. Diorama's map looks down at
		 * the ground from the south at a slant, so the height counts as it does for a
		 * {@link Marker}: the place at the middle is where something at this height shows, and the
		 * ground there is north of it if the ground is lower. Give the height of the ground, or the
		 * player's, to have that in the middle.
		 *
		 * @param x east is positive
		 * @param y up is positive
		 * @param z south is positive
		 * @return this builder
		 * @throws IllegalArgumentException if {@code x} or {@code z} is beyond
		 *     {@link Marker#MAX_COORDINATE} either way, {@code y} beyond {@link Marker#MAX_HEIGHT},
		 *     or any of them NaN; the builder is then as it was
		 */
		public Builder center(double x, double y, double z) {
			// All three checked before any is kept.
			double checkedX = Checks.horizontal("x", x);
			double checkedY = Checks.height(y);
			double checkedZ = Checks.horizontal("z", z);
			this.centerX = checkedX;
			this.centerY = checkedY;
			this.centerZ = checkedZ;
			this.centered = true;
			return this;
		}

		/**
		 * Sets how far in the view is zoomed, in GUI pixels a block, as Diorama's full map counts
		 * it: 2 shows a block 2 GUI pixels across.
		 *
		 * @param zoom from {@link #MIN_ZOOM} to {@link #MAX_ZOOM}
		 * @return this builder
		 * @throws IllegalArgumentException if the zoom is outside that range, or NaN; the builder
		 *     is then as it was
		 */
		public Builder zoom(double zoom) {
			if (!(zoom >= MIN_ZOOM && zoom <= MAX_ZOOM)) {
				throw new IllegalArgumentException("A view's zoom is " + MIN_ZOOM + " to " + MAX_ZOOM + " GUI pixels a block, not " + zoom);
			}
			this.zoom = zoom;
			return this;
		}

		/**
		 * {@return the place, which this builder can go on to make more of}
		 *
		 * @throws IllegalStateException if {@link #center} wasn't called
		 */
		public ViewPlace build() {
			if (!this.centered) {
				throw new IllegalStateException("A view's place needs the place in the world at its middle: call center(x, y, z) before build()");
			}
			return new ViewPlace(this);
		}
	}
}
