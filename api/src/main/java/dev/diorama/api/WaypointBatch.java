package dev.diorama.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Waypoint changes for {@link Waypoints#edit} to make together: adds, changes and removals, in the
 * order they were given. A plain value that never changes, made with {@link #builder} in the
 * addon's own code. Every value in it was checked when it was given, so a bad one throws there,
 * before Diorama sees the batch; that a waypoint is listed, and is one addons may change, is checked
 * when the batch is applied.
 *
 * <pre>{@code
 * WaypointBatch batch = WaypointBatch.builder()
 *     .add(NewWaypoint.builder("Village", DimensionId.OVERWORLD, BlockCoord.of(120, 70, -340)).build())
 *     .update(mineId, WaypointUpdate.builder().name("Old mine").visible(false).build())
 *     .remove(oldId)
 *     .build();
 * EditResult result = api.waypoints().edit(world, revision, batch);
 * }</pre>
 */
public final class WaypointBatch {
	private static final WaypointBatch EMPTY = new WaypointBatch(List.of());

	private final List<Step> steps;

	private WaypointBatch(List<Step> steps) {
		this.steps = steps;
	}

	/** {@return a builder for a batch with nothing in it yet} */
	public static Builder builder() {
		return new Builder();
	}

	/** {@return a batch that changes nothing} */
	public static WaypointBatch empty() {
		return EMPTY;
	}

	/** {@return its steps, in the order they were given} The list can't be changed. */
	public List<Step> steps() {
		return this.steps;
	}

	/** {@return whether it has no steps} */
	public boolean isEmpty() {
		return this.steps.isEmpty();
	}

	@Override
	public String toString() {
		return "WaypointBatch" + this.steps;
	}

	/**
	 * One step of a batch: an {@link Add}, an {@link Update} or a {@link Remove}. There are no
	 * other kinds, and an addon can't make one of its own.
	 */
	public abstract static class Step {
		Step() {
		}
	}

	/** Adds a player-made waypoint ({@link Waypoint.Kind#MANUAL}). */
	public static final class Add extends Step {
		private final NewWaypoint waypoint;

		Add(NewWaypoint waypoint) {
			this.waypoint = waypoint;
		}

		/** {@return what to add} */
		public NewWaypoint waypoint() {
			return this.waypoint;
		}

		@Override
		public String toString() {
			return "add " + this.waypoint.name();
		}
	}

	/** Changes what its {@link WaypointUpdate} sets on a player-made waypoint, and leaves the rest. */
	public static final class Update extends Step {
		private final UUID id;
		private final WaypointUpdate update;

		Update(UUID id, WaypointUpdate update) {
			this.id = id;
			this.update = update;
		}

		/** {@return the waypoint's ID} */
		public UUID id() {
			return this.id;
		}

		/** {@return what to change} */
		public WaypointUpdate update() {
			return this.update;
		}

		@Override
		public String toString() {
			return "update " + this.id;
		}
	}

	/** Removes a player-made waypoint. */
	public static final class Remove extends Step {
		private final UUID id;

		Remove(UUID id) {
			this.id = id;
		}

		/** {@return the waypoint's ID} */
		public UUID id() {
			return this.id;
		}

		@Override
		public String toString() {
			return "remove " + this.id;
		}
	}

	/** Collects the steps of a {@link WaypointBatch}. Each method checks what it is given and returns this builder. */
	public static final class Builder {
		private final List<Step> steps = new ArrayList<>();

		private Builder() {
		}

		/**
		 * Adds a player-made waypoint, on its dimension's saved map in use. Diorama gives it its ID,
		 * which {@link EditResult#added} reports. It records the adding addon's mod ID
		 * ({@link Waypoint#addedBy}). A folder named that doesn't exist yet is made.
		 *
		 * @param waypoint what to add
		 * @return this builder
		 * @throws NullPointerException if {@code waypoint} is null
		 */
		public Builder add(NewWaypoint waypoint) {
			this.steps.add(new Add(Objects.requireNonNull(waypoint, "waypoint")));
			return this;
		}

		/**
		 * Changes what {@code update} sets on a player-made waypoint. A waypoint moved to another
		 * dimension goes on that dimension's saved map in use. A folder named that doesn't exist yet
		 * is made.
		 *
		 * @param id the waypoint's ID, as {@link Waypoints#all} has it
		 * @param update what to change
		 * @return this builder
		 * @throws NullPointerException if an argument is null
		 */
		public Builder update(UUID id, WaypointUpdate update) {
			this.steps.add(new Update(Objects.requireNonNull(id, "id"), Objects.requireNonNull(update, "update")));
			return this;
		}

		/**
		 * Removes a player-made waypoint.
		 *
		 * @param id the waypoint's ID, as {@link Waypoints#all} has it
		 * @return this builder
		 * @throws NullPointerException if {@code id} is null
		 */
		public Builder remove(UUID id) {
			this.steps.add(new Remove(Objects.requireNonNull(id, "id")));
			return this;
		}

		/** {@return whether nothing was given yet} */
		public boolean isEmpty() {
			return this.steps.isEmpty();
		}

		/** {@return the batch, which this builder can go on to make more of} */
		public WaypointBatch build() {
			return this.steps.isEmpty() ? EMPTY : new WaypointBatch(List.copyOf(this.steps));
		}
	}
}
