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
 * Cobblemon's Pokémon and NPCs on the radar (opt-in: {@code cobblemon-radar}, with Cobblemon
 * installed). Every Pokémon is the same entity type, and Cobblemon has no flat pictures of them
 * (#344). A few species, a shiny and an NPC stand round the player, beside a cow and a zombie to
 * compare sizes with, on the minimap and on the map, in the radar's two icon styles.
 */
final class CobblemonRadarTest {
	/** Each spawn: the arguments to pokespawnat, and what its picture's name must start with and contain. */
	private static final String[][] POKEMON = {
		{"bulbasaur", "cobblemon:bulbasaur", ""},
		{"charizard", "cobblemon:charizard", ""},
		{"pikachu", "cobblemon:pikachu", ""},
		{"snorlax", "cobblemon:snorlax", ""},
		{"eevee", "cobblemon:eevee", ""},
		{"gengar shiny=yes", "cobblemon:gengar", "shiny"},
	};
	/** Cobblemon's stock trainer, on the versions that have NPCs. */
	private static final String[] NPC = {"standard", "cobblemon:standard", ""};

	private CobblemonRadarTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		if (!context.computeOnClient(mc -> pokemon() != null)) {
			throw new AssertionError("DIORAMA_TESTS=cobblemon-radar needs Cobblemon installed");
		}
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("gamerule doMobSpawning false");
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX();
		int y = at.getY();
		int z = at.getZ();
		world.getServer().runCommand("fill " + (x - 12) + " " + y + " " + (z - 12) + " " + (x + 12) + " " + (y + 24) + " " + (z + 12) + " air");
		world.getServer().runCommand("fill " + (x - 12) + " " + (y - 1) + " " + (z - 12) + " " + (x + 12) + " " + (y - 1) + " " + (z + 12) + " grass_block");
		for (int i = 0; i < POKEMON.length; i++) {
			int px = x + (i % 3 - 1) * 6;
			int pz = z + (i / 3 == 0 ? -6 : 9);
			world.getServer().runCommand("pokespawnat " + px + " " + y + " " + pz + " " + POKEMON[i][0] + " no_ai=true");
		}
		// Cobblemon's NPCs came with 1.6, so not on 1.20.1.
		boolean npcs = context.computeOnClient(mc -> cobblemonClass("entity.npc.NPCEntity") != null);
		if (npcs) {
			world.getServer().runCommand("execute as @a run npcspawnat " + (x + 9) + " " + y + " " + (z + 5) + " " + NPC[0]);
		}
		world.getServer().runCommand("summon cow " + (x - 9) + " " + y + " " + (z - 2) + " {NoAI:1b}");
		world.getServer().runCommand("summon zombie " + (x - 9) + " " + y + " " + (z + 2) + " {NoAI:1b,PersistenceRequired:1b}");
		// Wild Pokémon are loaded too, so this waits for these ones. A picture is drawn the frame
		// after the radar first asks for it.
		List<String[]> expected = new ArrayList<>(List.of(POKEMON));
		if (npcs) {
			expected.add(NPC);
		}
		List<String> pictures = List.of();
		for (int i = 0; i < 20 && missing(expected, pictures) != null; i++) {
			context.waitTicks(20);
			pictures = context.computeOnClient(CobblemonRadarTest::pictures);
		}
		String[] missing = missing(expected, pictures);
		if (missing != null) {
			throw new AssertionError("No picture on the radar for " + missing[1] + " " + missing[2] + ": " + pictures);
		}
		context.waitTicks(20);
		context.takeScreenshot("cobblemon-radar-00-world");
		context.takeScreenshot("cobblemon-radar-01-minimap");
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> lookAtPlayer(mc, 6.0));
		context.waitTicks(40);
		context.takeScreenshot("cobblemon-radar-02-map");
		context.runOnClient(mc -> lookAtPlayer(mc, 12.0));
		context.waitTicks(20);
		context.takeScreenshot("cobblemon-radar-02-map-close");
		context.runOnClient(mc -> lookAtPlayer(mc, 6.0));
		context.waitTicks(20);
		Settings.RadarStyle saved = context.computeOnClient(mc -> Settings.get().radarStyle);
		context.runOnClient(mc -> Settings.get().radarStyle = Settings.RadarStyle.FRAMED);
		context.waitTicks(5);
		context.takeScreenshot("cobblemon-radar-03-map-framed");
		context.setScreen(() -> null);
		context.waitTicks(5);
		context.takeScreenshot("cobblemon-radar-04-minimap-framed");
		context.runOnClient(mc -> Settings.get().radarStyle = saved);
		world.getServer().runCommand("gamerule doMobSpawning true");
	}

	private static void lookAtPlayer(net.minecraft.client.Minecraft mc, double zoom) {
		if (mc.gui.screen() instanceof MapScreen screen && mc.player != null) {
			screen.lookAt(mc.player.getX(), dev.diorama.Projection.v(mc.player.getY(), mc.player.getZ()), zoom, TileKey.SURFACE);
		}
	}

	/** The first of these with no picture among what the radar draws, or null if each has one. */
	private static String[] missing(List<String[]> expected, List<String> pictures) {
		for (String[] one : expected) {
			if (pictures.stream().noneMatch(picture -> picture.startsWith("outlined_sprite " + one[1]) && picture.contains(one[2]))) {
				return one;
			}
		}
		return null;
	}

	/** What the radar draws for every Pokémon and NPC the client has. */
	private static List<String> pictures(net.minecraft.client.Minecraft mc) {
		List<String> pictures = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (dev.diorama.compat.Cobblemon.has(entity)) {
				pictures.add(String.valueOf(XaeroRadarIcons.describe(entity)));
			}
		}
		return pictures;
	}

	private static Class<?> pokemon() {
		return cobblemonClass("entity.pokemon.PokemonEntity");
	}

	private static Class<?> cobblemonClass(String name) {
		try {
			return Class.forName("com.cobblemon.mod.common." + name);
		} catch (ClassNotFoundException e) {
			return null;
		}
	}
}
