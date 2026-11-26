package dev.diorama.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * A place in the world, in blocks, as {@link MapView#worldAt} gives it. Experimental, as
 * {@link MapView} is.
 *
 * @param x east is positive
 * @param y up is positive
 * @param z south is positive
 */
@ApiStatus.Experimental
public record WorldPos(double x, double y, double z) {
}
