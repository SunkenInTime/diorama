package dev.diorama.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * A point on the screen, in GUI pixels from its top left, as {@link MapView#project} gives it.
 * Experimental, as {@link MapView} is.
 *
 * @param x right is positive
 * @param y down is positive
 */
@ApiStatus.Experimental
public record ScreenPos(double x, double y) {
}
