package dev.diorama.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link WaypointBatch} changes on a waypoint ({@link WaypointBatch.Builder#update}), made
 * with {@link #builder}: only what is set changes. Every value is checked as {@link NewWaypoint}'s
 * are.
 *
 * <pre>{@code
 * WaypointBatch batch = WaypointBatch.builder().update(id, WaypointUpdate.builder().name("Old mine").visible(false).build()).build();
 * }</pre>
 */
public final class WaypointUpdate {
	private final @Nullable String name;
	private final @Nullable DimensionId dimension;
	private final @Nullable BlockCoord pos;
	private final @Nullable String initials;
	private final @Nullable ResourceId icon;
	private final boolean removesIcon;
	private final @Nullable Integer rgb;
	private final @Nullable Boolean visible;
	private final @Nullable String folder;

	private WaypointUpdate(Builder builder) {
		this.name = builder.name;
		this.dimension = builder.dimension;
		this.pos = builder.pos;
		this.initials = builder.initials;
		this.icon = builder.icon;
		this.removesIcon = builder.removesIcon;
		this.rgb = builder.rgb;
		this.visible = builder.visible;
		this.folder = builder.folder;
	}

	/** {@return a builder that changes nothing until told what to} */
	public static Builder builder() {
		return new Builder();
	}

	/** {@return the new name, if it changes} */
	public Optional<String> name() {
		return Optional.ofNullable(this.name);
	}

	/** {@return the new dimension, if it changes} */
	public Optional<DimensionId> dimension() {
		return Optional.ofNullable(this.dimension);
	}

	/** {@return the new block, if it moves} */
	public Optional<BlockCoord> pos() {
		return Optional.ofNullable(this.pos);
	}

	/** {@return the new initials, if they change} */
	public Optional<String> initials() {
		return Optional.ofNullable(this.initials);
	}

	/** {@return the new icon, if it gets one; see also {@link #removesIcon}} */
	public Optional<ResourceId> icon() {
		return Optional.ofNullable(this.icon);
	}

	/** {@return whether it loses its icon, so its pin shows its initials} */
	public boolean removesIcon() {
		return this.removesIcon;
	}

	/** {@return the new colour as 0xRRGGBB, if it changes} */
	public OptionalInt rgb() {
		return this.rgb == null ? OptionalInt.empty() : OptionalInt.of(this.rgb);
	}

	/** {@return whether it will be shown, if that changes} */
	public Optional<Boolean> visible() {
		return Optional.ofNullable(this.visible);
	}

	/** {@return the new folder, if it changes: a name, or empty for its dimension's own} */
	public Optional<String> folder() {
		return Optional.ofNullable(this.folder);
	}

	/** Sets what a {@link WaypointUpdate} changes. Each setter checks what it is given and returns this builder. */
	public static final class Builder {
		private @Nullable String name;
		private @Nullable DimensionId dimension;
		private @Nullable BlockCoord pos;
		private @Nullable String initials;
		private @Nullable ResourceId icon;
		private boolean removesIcon;
		private @Nullable Integer rgb;
		private @Nullable Boolean visible;
		private @Nullable String folder;

		private Builder() {
		}

		/**
		 * Renames it.
		 *
		 * @param name as for {@link NewWaypoint#builder}
		 * @return this builder
		 * @throws IllegalArgumentException if the name won't do
		 * @throws NullPointerException if {@code name} is null
		 */
		public Builder name(String name) {
			this.name = Checks.name(name);
			return this;
		}

		/**
		 * Moves it to another dimension, onto that dimension's saved map in use. Set its block too
		 * unless it stays at the same coordinates.
		 *
		 * @param dimension the dimension
		 * @return this builder
		 * @throws NullPointerException if {@code dimension} is null
		 */
		public Builder dimension(DimensionId dimension) {
			this.dimension = Objects.requireNonNull(dimension, "dimension");
			return this;
		}

		/**
		 * Moves it to another block.
		 *
		 * @param pos the block
		 * @return this builder
		 * @throws NullPointerException if {@code pos} is null
		 */
		public Builder pos(BlockCoord pos) {
			this.pos = Objects.requireNonNull(pos, "pos");
			return this;
		}

		/**
		 * Changes its initials.
		 *
		 * @param initials as for {@link NewWaypoint.Builder#initials}
		 * @return this builder
		 * @throws IllegalArgumentException if there are more than four
		 * @throws NullPointerException if {@code initials} is null
		 */
		public Builder initials(String initials) {
			this.initials = Checks.initials(initials);
			return this;
		}

		/**
		 * Gives it this icon. Undoes {@link #noIcon}.
		 *
		 * @param icon as for {@link NewWaypoint.Builder#icon}
		 * @return this builder
		 * @throws NullPointerException if {@code icon} is null
		 */
		public Builder icon(ResourceId icon) {
			this.icon = Objects.requireNonNull(icon, "icon");
			this.removesIcon = false;
			return this;
		}

		/**
		 * Takes its icon away, so its pin shows its initials. Undoes {@link #icon}.
		 *
		 * @return this builder
		 */
		public Builder noIcon() {
			this.icon = null;
			this.removesIcon = true;
			return this;
		}

		/**
		 * Changes its colour.
		 *
		 * @param rgb as for {@link NewWaypoint.Builder#rgb}: {@code 0xRRGGBB}
		 * @return this builder
		 * @throws IllegalArgumentException if anything is set above the low 24 bits
		 */
		public Builder rgb(int rgb) {
			this.rgb = Checks.rgb(rgb);
			return this;
		}

		/**
		 * Shows or hides it.
		 *
		 * @param visible false to hide it
		 * @return this builder
		 */
		public Builder visible(boolean visible) {
			this.visible = visible;
			return this;
		}

		/**
		 * Moves it to another folder.
		 *
		 * @param folder as for {@link NewWaypoint.Builder#folder}
		 * @return this builder
		 * @throws IllegalArgumentException if the name is too long
		 * @throws NullPointerException if {@code folder} is null
		 */
		public Builder folder(String folder) {
			this.folder = Checks.folder(folder);
			return this;
		}

		/** {@return the update, which this builder can go on to make more of} */
		public WaypointUpdate build() {
			return new WaypointUpdate(this);
		}
	}
}
