package dev.diorama.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything one {@link MapLayer} shows: its markers and its lines, as a value that never changes.
 * Made with {@link #builder} and handed over whole with {@link MapLayer#replace}. The limits are
 * checked as it is built, in the addon's own code, so content that is too big throws there and
 * never reaches the map.
 *
 * <pre>{@code
 * LayerContent.Builder content = LayerContent.builder();
 * for (Cart cart : carts) {
 *     content.add(Marker.builder(DimensionId.OVERWORLD, cart.x(), cart.y(), cart.z()).rgb(0xF2C14E).build());
 * }
 * layer.replace(content.build());
 * }</pre>
 */
public final class LayerContent {
	/** The most markers one layer can show: 1,024. */
	public static final int MAX_MARKERS = 1024;
	/** The most lines one layer can show: 1,024. */
	public static final int MAX_LINES = 1024;
	/** The most points all of one layer's lines can have together: 4,096. */
	public static final int MAX_LINE_POINTS = 4096;
	/** Nothing: a layer shows this until its first {@link MapLayer#replace}. */
	public static final LayerContent EMPTY = new LayerContent(List.of(), List.of());

	private final List<Marker> markers;
	private final List<MapLine> lines;

	private LayerContent(List<Marker> markers, List<MapLine> lines) {
		this.markers = markers;
		this.lines = lines;
	}

	/** {@return a builder with nothing in it yet} */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * {@return its markers, in the order they were added, which is the order they are drawn in:
	 * a later one over an earlier one where they overlap} The list can't be changed.
	 */
	public List<Marker> markers() {
		return this.markers;
	}

	/** {@return its lines, in the order they were added, which is the order they are drawn in} The list can't be changed. */
	public List<MapLine> lines() {
		return this.lines;
	}

	@Override
	public String toString() {
		return "LayerContent[" + this.markers.size() + " markers, " + this.lines.size() + " lines]";
	}

	/** Collects markers and lines for a {@link LayerContent}, checking the limits as they are added. */
	public static final class Builder {
		private final List<Marker> markers = new ArrayList<>();
		private final List<MapLine> lines = new ArrayList<>();
		private int linePoints;

		private Builder() {
		}

		/**
		 * Adds a marker, drawn over those added before it.
		 *
		 * @param marker the marker
		 * @return this builder
		 * @throws IllegalArgumentException if it already has {@link #MAX_MARKERS} markers
		 * @throws NullPointerException if {@code marker} is null
		 */
		public Builder add(Marker marker) {
			Objects.requireNonNull(marker, "marker");
			if (this.markers.size() == MAX_MARKERS) {
				throw new IllegalArgumentException("A layer can show " + MAX_MARKERS + " markers at most; open another layer for more");
			}
			this.markers.add(marker);
			return this;
		}

		/**
		 * Adds a line, drawn over those added before it.
		 *
		 * @param line the line
		 * @return this builder
		 * @throws IllegalArgumentException if it already has {@link #MAX_LINES} lines, or this
		 *     line's points would take its lines past {@link #MAX_LINE_POINTS} in all
		 * @throws NullPointerException if {@code line} is null
		 */
		public Builder add(MapLine line) {
			Objects.requireNonNull(line, "line");
			if (this.lines.size() == MAX_LINES) {
				throw new IllegalArgumentException("A layer can show " + MAX_LINES + " lines at most; open another layer for more");
			}
			if (this.linePoints + line.pointCount() > MAX_LINE_POINTS) {
				throw new IllegalArgumentException("A layer's lines can have " + MAX_LINE_POINTS + " points in all; this line's " + line.pointCount()
					+ " would take them to " + (this.linePoints + line.pointCount()));
			}
			this.lines.add(line);
			this.linePoints += line.pointCount();
			return this;
		}

		/** {@return the content, which this builder can go on to add to for another} */
		public LayerContent build() {
			return this.markers.isEmpty() && this.lines.isEmpty() ? EMPTY : new LayerContent(List.copyOf(this.markers), List.copyOf(this.lines));
		}
	}
}
