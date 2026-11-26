package dev.diorama.api;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A waypoint for {@link WaypointEditor#add} to add, made with {@link #builder}. Every value is
 * checked as it is set, so a bad one throws where it was given.
 *
 * <pre>{@code
 * world.waypoints().edit(edits -> edits.add(
 *     NewWaypoint.builder("Village", DimensionId.OVERWORLD, BlockCoord.of(120, 70, -340))
 *         .rgb(0x46A758)
 *         .folder("Villages")
 *         .build()));
 * }</pre>
 */
public final class NewWaypoint {
	/**
	 * The longest name a waypoint or a folder can have, as Diorama's editor allows: 32, counted in
	 * UTF-16 units as {@link String#length} counts them, after spaces at the ends are taken off. A
	 * character outside the Basic Multilingual Plane, such as an emoji, counts two.
	 */
	public static final int MAX_NAME_LENGTH = 32;
	/** The most letters a pin's initials can have: 4, counted in code points. */
	public static final int MAX_INITIALS = 4;

	private final String name;
	private final DimensionId dimension;
	private final BlockCoord pos;
	private final String initials;
	private final @Nullable ResourceId icon;
	private final int rgb;
	private final boolean visible;
	private final String folder;

	private NewWaypoint(Builder builder) {
		this.name = builder.name;
		this.dimension = builder.dimension;
		this.pos = builder.pos;
		this.initials = builder.initials;
		this.icon = builder.icon;
		this.rgb = builder.rgb;
		this.visible = builder.visible;
		this.folder = builder.folder;
	}

	/**
	 * Starts a waypoint with what every one needs.
	 *
	 * @param name its name: not blank, and {@link #MAX_NAME_LENGTH} UTF-16 units at most once
	 *     spaces at the ends are taken off
	 * @param dimension the dimension it is in
	 * @param pos the block it marks
	 * @return a builder with the rest at their defaults: no initials of its own, no icon, the first
	 *     colour of Diorama's palette (red), shown, in its dimension's own folder
	 * @throws IllegalArgumentException if the name won't do
	 * @throws NullPointerException if an argument is null
	 */
	public static Builder builder(String name, DimensionId dimension, BlockCoord pos) {
		return new Builder(Checks.name(name), Objects.requireNonNull(dimension, "dimension"), Objects.requireNonNull(pos, "pos"));
	}

	/** {@return its name} */
	public String name() {
		return this.name;
	}

	/** {@return the dimension it is in} */
	public DimensionId dimension() {
		return this.dimension;
	}

	/** {@return the block it marks} */
	public BlockCoord pos() {
		return this.pos;
	}

	/** {@return its initials, or empty for the first letters of its name} */
	public String initials() {
		return this.initials;
	}

	/** {@return its icon, or empty for none} */
	public Optional<ResourceId> icon() {
		return Optional.ofNullable(this.icon);
	}

	/** {@return its colour, as 0xRRGGBB} */
	public int rgb() {
		return this.rgb;
	}

	/** {@return whether it is shown} */
	public boolean visible() {
		return this.visible;
	}

	/** {@return the name of the player's folder it goes in, or empty for its dimension's own} */
	public String folder() {
		return this.folder;
	}

	/** Sets the rest of a {@link NewWaypoint}. Each setter checks what it is given and returns this builder. */
	public static final class Builder {
		private final String name;
		private final DimensionId dimension;
		private final BlockCoord pos;
		private String initials = "";
		private @Nullable ResourceId icon;
		private int rgb = Checks.DEFAULT_RGB;
		private boolean visible = true;
		private String folder = "";

		private Builder(String name, DimensionId dimension, BlockCoord pos) {
			this.name = name;
			this.dimension = dimension;
			this.pos = pos;
		}

		/**
		 * Sets the letters its pin shows.
		 *
		 * @param initials up to {@link #MAX_INITIALS} letters, or empty for the first letters of
		 *     the name's first two words
		 * @return this builder
		 * @throws IllegalArgumentException if there are more than four
		 * @throws NullPointerException if {@code initials} is null
		 */
		public Builder initials(String initials) {
			this.initials = Checks.initials(initials);
			return this;
		}

		/**
		 * Sets the icon its pin shows instead of initials. An ID no resource pack has an icon for
		 * shows the initials.
		 *
		 * @param icon such as {@code diorama:home} for
		 *     {@code assets/diorama/textures/waypoint_icons/home.png}
		 * @return this builder
		 * @throws NullPointerException if {@code icon} is null
		 */
		public Builder icon(ResourceId icon) {
			this.icon = Objects.requireNonNull(icon, "icon");
			return this;
		}

		/**
		 * Sets its colour. Waypoints are always drawn opaque, so there is no alpha to give.
		 *
		 * @param rgb {@code 0xRRGGBB}, such as {@code 0x46A758}
		 * @return this builder
		 * @throws IllegalArgumentException if anything is set above the low 24 bits, as an ARGB
		 *     colour such as {@code 0xFF46A758} has
		 */
		public Builder rgb(int rgb) {
			this.rgb = Checks.rgb(rgb);
			return this;
		}

		/**
		 * Sets whether it is shown on the maps and in the world.
		 *
		 * @param visible false to add it hidden
		 * @return this builder
		 */
		public Builder visible(boolean visible) {
			this.visible = visible;
			return this;
		}

		/**
		 * Puts it in one of the player's folders, made if there's none of that name yet. Names
		 * match whatever their capitals.
		 *
		 * @param folder the folder's name, {@link #MAX_NAME_LENGTH} UTF-16 units at most, or empty
		 *     for its dimension's own
		 * @return this builder
		 * @throws IllegalArgumentException if the name is too long
		 * @throws NullPointerException if {@code folder} is null
		 */
		public Builder folder(String folder) {
			this.folder = Checks.folder(folder);
			return this;
		}

		/** {@return the waypoint, which this builder can go on to make more of} */
		public NewWaypoint build() {
			return new NewWaypoint(this);
		}
	}
}
