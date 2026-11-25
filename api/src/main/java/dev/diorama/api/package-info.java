/**
 * Diorama's API for other mods, the same on every Minecraft version and mod loader Diorama runs
 * on: it uses no Minecraft or mod loader types. Start at {@link dev.diorama.api.DioramaAddon}.
 *
 * <p>Compile against the {@code diorama-api} jar and don't bundle it: every Diorama jar carries
 * these classes. Don't give Diorama an upper version bound in your mod's metadata; check
 * {@link dev.diorama.api.DioramaApi#apiVersion} instead.
 *
 * <p>Alpha: the API is not yet a compatibility promise and may change before 1.0.0.
 */
@NullMarked
package dev.diorama.api;

import org.jspecify.annotations.NullMarked;
