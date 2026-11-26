package dev.diorama.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * A line an addon shows on Diorama's maps, the minimap and the full map: straight pieces through
 * two or more places in the world, in one dimension, such as a road, a path to follow or a
 * border. Made with {@link #builder}, handed to Diorama in a {@link LayerContent}, and never
 * changed after. Every value is checked as it is set, so a bad one throws where the addon gave
 * it.
 *
 * <p>Each point is placed on the 3D map the way a {@link Marker} is, so its {@code y} counts the
 * same way, and the piece between two points is the straight line between those places in the
 * world, which the map shows as a straight line. A line over a hill only follows the ground with
 * points along it. It shows only on maps of its dimension, as a marker does, and is cut at the
 * edge of the view, the round minimap's circle included.
 *
 * <p>It is drawn as Diorama draws the player's own lines on the map: a core of its colour,
 * {@link #width} GUI pixels wide at every zoom, over a dark rim a pixel wide either side, round
 * where it bends, under the markers, waypoints and the radar.
 *
 * <pre>{@code
 * MapLine road = MapLine.builder(DimensionId.OVERWORLD)
 *     .point(0.5, 64, 0.5)
 *     .point(80.5, 66, 0.5)
 *     .point(80.5, 70, 120.5)
 *     .rgb(0xC8A15A)
 *     .build();
 * }</pre>
 */
public final class MapLine {
	/** The most points one line can have: 4,096. */
	public static final int MAX_POINTS = 4096;
	/** The narrowest a line can be, in GUI pixels: 1. */
	public static final int MIN_WIDTH = 1;
	/** The widest a line can be, in GUI pixels: 8. */
	public static final int MAX_WIDTH = 8;
	/** A line's width when none is given, in GUI pixels: 2, as the player's own lines on the map. */
	public static final int DEFAULT_WIDTH = 2;

	private final DimensionId dimension;
	/** x, y, z of each point in turn. */
	private final double[] points;
	private final int rgb;
	private final int width;

	private MapLine(Builder builder) {
		this.dimension = builder.dimension;
		this.points = Arrays.copyOf(builder.points, builder.count * 3);
		this.rgb = builder.rgb;
		this.width = builder.width;
	}

	/**
	 * Starts a line in a dimension. Give it its points with {@link Builder#point}.
	 *
	 * @param dimension the dimension it is in
	 * @return a builder with no points yet, in the first colour of Diorama's palette (red),
	 *     {@link #DEFAULT_WIDTH} wide
	 * @throws NullPointerException if {@code dimension} is null
	 */
	public static Builder builder(DimensionId dimension) {
		return new Builder(Objects.requireNonNull(dimension, "dimension"));
	}

	/** {@return the dimension it is in} */
	public DimensionId dimension() {
		return this.dimension;
	}

	/** {@return how many points it goes through: at least 2} */
	public int pointCount() {
		return this.points.length / 3;
	}

	/**
	 * The east-west coordinate of one of its points.
	 *
	 * @param point which, from 0 to {@link #pointCount} minus 1
	 * @return east is positive
	 * @throws IndexOutOfBoundsException if there's no such point
	 */
	public double x(int point) {
		return this.points[Objects.checkIndex(point, this.pointCount()) * 3];
	}

	/**
	 * The height of one of its points.
	 *
	 * @param point which, from 0 to {@link #pointCount} minus 1
	 * @return up is positive
	 * @throws IndexOutOfBoundsException if there's no such point
	 */
	public double y(int point) {
		return this.points[Objects.checkIndex(point, this.pointCount()) * 3 + 1];
	}

	/**
	 * The north-south coordinate of one of its points.
	 *
	 * @param point which, from 0 to {@link #pointCount} minus 1
	 * @return south is positive
	 * @throws IndexOutOfBoundsException if there's no such point
	 */
	public double z(int point) {
		return this.points[Objects.checkIndex(point, this.pointCount()) * 3 + 2];
	}

	/** {@return its colour, as 0xRRGGBB} */
	public int rgb() {
		return this.rgb;
	}

	/** {@return how wide its coloured core is, in GUI pixels} */
	public int width() {
		return this.width;
	}

	@Override
	public String toString() {
		return "MapLine[" + this.pointCount() + " points in " + this.dimension + String.format(", 0x%06X, %d wide]", this.rgb, this.width);
	}

	/** Sets the points and the look of a {@link MapLine}. Each method checks what it is given and returns this builder. */
	public static final class Builder {
		private final DimensionId dimension;
		private double[] points = new double[3 * 8];
		private int count;
		private int rgb = Checks.DEFAULT_RGB;
		private int width = DEFAULT_WIDTH;

		private Builder(DimensionId dimension) {
			this.dimension = dimension;
		}

		/**
		 * Adds the next point the line goes through.
		 *
		 * @param x east is positive
		 * @param y up is positive: the height of the place (see {@link Marker})
		 * @param z south is positive
		 * @return this builder
		 * @throws IllegalArgumentException if a coordinate is infinite or NaN, or the line already
		 *     has {@link #MAX_POINTS} points
		 */
		public Builder point(double x, double y, double z) {
			Checks.coordinate("x", x);
			Checks.coordinate("y", y);
			Checks.coordinate("z", z);
			if (this.count == MAX_POINTS) {
				throw new IllegalArgumentException("A line can go through " + MAX_POINTS + " points at most; split a longer one");
			}
			if (this.count * 3 == this.points.length) {
				this.points = Arrays.copyOf(this.points, Math.min(this.points.length * 2, MAX_POINTS * 3));
			}
			this.points[this.count * 3] = x;
			this.points[this.count * 3 + 1] = y;
			this.points[this.count * 3 + 2] = z;
			this.count++;
			return this;
		}

		/**
		 * Sets its colour. Lines are drawn opaque, so there is no alpha to give.
		 *
		 * @param rgb {@code 0xRRGGBB}, such as {@code 0xC8A15A}
		 * @return this builder
		 * @throws IllegalArgumentException if anything is set above the low 24 bits, as an ARGB
		 *     colour such as {@code 0xFFC8A15A} has
		 */
		public Builder rgb(int rgb) {
			this.rgb = Checks.rgb("A line's colour", rgb);
			return this;
		}

		/**
		 * Sets how wide its coloured core is.
		 *
		 * @param width from {@link #MIN_WIDTH} to {@link #MAX_WIDTH} GUI pixels
		 * @return this builder
		 * @throws IllegalArgumentException if the width is outside that range
		 */
		public Builder width(int width) {
			if (width < MIN_WIDTH || width > MAX_WIDTH) {
				throw new IllegalArgumentException("A line's width is " + MIN_WIDTH + " to " + MAX_WIDTH + " GUI pixels, not " + width);
			}
			this.width = width;
			return this;
		}

		/**
		 * {@return the line, which this builder can go on to make more of}
		 *
		 * @throws IllegalStateException if it has fewer than two points
		 */
		public MapLine build() {
			if (this.count < 2) {
				throw new IllegalStateException("A line needs two points at least; this one has " + this.count);
			}
			return new MapLine(this);
		}
	}
}
