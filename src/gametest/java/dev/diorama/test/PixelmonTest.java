package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.XaeroRadarIcons;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * Pokémon on the radar (opt-in: {@code pixelmon}, with Pixelmon installed): every one is the same
 * entity type with its own renderer, so without help they're all the same green square (#226). A
 * few species and a shiny stand round the player, and each must show its own sprite, on the
 * minimap and on the map, in the radar's two icon styles. Set {@code starter-on-join: false} in
 * {@code config/pixelmon/config.yml} first: the screen for picking a starter pauses the world.
 */
final class PixelmonTest {
	/** Each spawn: the arguments to pokespawn, and what its sprite's location must contain. */
	private static final String[][] POKEMON = {
		{"bulbasaur", "001_bulbasaur"},
		{"charizard", "006_charizard"},
		{"pikachu", "025_pikachu"},
		{"snorlax", "143_snorlax"},
		{"eevee", "133_eevee"},
		{"gengar s", "094_gengar/all/base/shiny"},
	};

	private PixelmonTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		if (!context.computeOnClient(mc -> installed())) {
			throw new AssertionError("DIORAMA_TESTS=pixelmon needs Pixelmon installed");
		}
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX();
		int y = at.getY();
		int z = at.getZ();
		world.getServer().runCommand("fill " + (x - 12) + " " + y + " " + (z - 12) + " " + (x + 12) + " " + (y + 4) + " " + (z + 12) + " air");
		world.getServer().runCommand("fill " + (x - 12) + " " + (y - 1) + " " + (z - 12) + " " + (x + 12) + " " + (y - 1) + " " + (z + 12) + " grass_block");
		for (int i = 0; i < POKEMON.length; i++) {
			int px = x + (i % 3 - 1) * 6;
			int pz = z + (i / 3 == 0 ? -6 : 9);
			world.getServer().runCommand("execute positioned " + px + " " + (y - 1) + " " + pz + " run pokespawn " + POKEMON[i][0]);
		}
		// Wild Pokémon are loaded too, so this waits for these ones.
		List<String> sprites = List.of();
		for (int i = 0; i < 20 && !allShown(sprites); i++) {
			context.waitTicks(20);
			sprites = context.computeOnClient(PixelmonTest::sprites);
		}
		if (!allShown(sprites)) {
			throw new AssertionError("Not every Pokémon has its sprite on the radar: " + sprites);
		}
		settle(context);
		context.takeScreenshot("pixelmon-01-minimap");
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> lookAtPlayer(mc, 6.0));
		settle(context);
		context.takeScreenshot("pixelmon-02-map");
		Settings.RadarStyle saved = context.computeOnClient(mc -> Settings.get().radarStyle);
		context.runOnClient(mc -> Settings.get().radarStyle = Settings.RadarStyle.FRAMED);
		context.waitTicks(5);
		context.takeScreenshot("pixelmon-03-map-framed");
		context.setScreen(() -> null);
		context.waitTicks(5);
		context.takeScreenshot("pixelmon-04-minimap-framed");
		context.runOnClient(mc -> Settings.get().radarStyle = saved);
	}

	private static boolean allShown(List<String> sprites) {
		for (String[] pokemon : POKEMON) {
			if (sprites.stream().noneMatch(sprite -> sprite.startsWith("outlined_sprite pixelmon:textures/pokemon/") && sprite.contains(pokemon[1]))) {
				return false;
			}
		}
		return true;
	}

	private static void lookAtPlayer(net.minecraft.client.Minecraft mc, double zoom) {
		if (mc.gui.screen() instanceof MapScreen screen && mc.player != null) {
			screen.lookAt(mc.player.getX(), dev.diorama.Projection.v(mc.player.getY(), mc.player.getZ()), zoom, TileKey.SURFACE);
		}
	}

	private static void settle(ClientGameTestContext context) {
		context.waitTicks(40);
	}

	/** What the radar draws for every Pokémon the client has. */
	private static List<String> sprites(net.minecraft.client.Minecraft mc) {
		List<String> sprites = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (installed() && pokemon().isInstance(entity)) {
				sprites.add(String.valueOf(XaeroRadarIcons.describe(entity)));
			}
		}
		return sprites;
	}

	private static boolean installed() {
		return pokemon() != null;
	}

	private static Class<?> pokemon() {
		try {
			return Class.forName("com.pixelmonmod.pixelmon.entities.pixelmon.PixelmonEntity");
		} catch (ClassNotFoundException e) {
			return null;
		}
	}
}
