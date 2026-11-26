package dev.diorama.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an addon's {@link DioramaAddon} class on NeoForge and Forge, where Diorama finds it in the
 * mod loader's scan of the addon's jar. On Fabric the annotation is ignored; declare a
 * {@code diorama} entrypoint instead.
 *
 * <p>Diorama ties the addon to the mod its jar declares, so a jar that declares more than one mod
 * is skipped, with a line in the log.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DioramaEntrypoint {
}
