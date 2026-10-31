package dev.diorama.compat;

import dev.diorama.Diorama;
import java.lang.reflect.Method;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The sprite of a Pixelmon Pokémon, for the radar (#226). Every Pokémon is the same entity type,
 * drawn by Pixelmon's own renderer, so neither a mob's icon file nor its model can tell one from
 * another. Pixelmon gives JourneyMap's radar this same sprite, the one its menus show: the species,
 * form and shininess of that Pokémon, in a resource a pack can replace.
 *
 * <p>Diorama doesn't build against Pixelmon. If {@code PixelmonEntity.getSprite()} isn't there, this
 * logs once and stays off, and Pokémon are dots.
 */
public final class Pixelmon {
	private static boolean started;
	private static @Nullable Class<?> pokemon;
	private static @Nullable Method sprite;

	private Pixelmon() {
	}

	/** Called on the render thread. Where this Pokémon's sprite is, or null for any other entity. */
	public static @Nullable Identifier sprite(Entity entity) {
		if (!started) {
			started = true;
			if (installed()) {
				try {
					pokemon = Class.forName("com.pixelmonmod.pixelmon.entities.pixelmon.PixelmonEntity");
					sprite = pokemon.getMethod("getSprite");
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					Diorama.LOGGER.warn("Pixelmon is installed, but its sprites couldn't be found; Pokémon will be dots on the radar", e);
					pokemon = null;
					sprite = null;
				}
			}
		}
		if (sprite == null || !pokemon.isInstance(entity)) {
			return null;
		}
		try {
			return sprite.invoke(entity) instanceof Identifier location ? location : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read a Pokémon's sprite; Pokémon will be dots on the radar", e);
			sprite = null;
			return null;
		}
	}

	private static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("pixelmon");
		//?} else {
		/*return ModList.get().isLoaded("pixelmon");
		*///?}
	}
}
