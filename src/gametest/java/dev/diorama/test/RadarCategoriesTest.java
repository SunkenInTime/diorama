package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.RadarCategories;
import dev.diorama.RadarCategoriesScreen;
import dev.diorama.RadarCategoryScreen;
import dev.diorama.RadarMobsScreen;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.bake.TileKey;
import dev.diorama.render.Radar;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;

/**
 * The radar's categories ({@code radar-categories}), driven as a player would: Radar tab,
 * Categories, New category, a name, its mobs picked from the list, a bigger icon and names. Then
 * the point of them (#344): with the kind a mob belongs to switched off, the mobs in the player's
 * own category stay on the radar. An old settings file keeps what it switched off.
 */
final class RadarCategoriesTest {
	private RadarCategoriesTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX();
		int y = at.getY();
		int z = at.getZ();
		world.getServer().runCommand("fill " + (x - 12) + " " + y + " " + (z - 12) + " " + (x + 12) + " " + (y + 4) + " " + (z + 12) + " air");
		world.getServer().runCommand("fill " + (x - 12) + " " + (y - 1) + " " + (z - 12) + " " + (x + 12) + " " + (y - 1) + " " + (z + 12) + " grass_block");
		world.getServer().runCommand("kill @e[type=!player]");
		String still = "{NoAI:1b,PersistenceRequired:1b,Silent:1b}";
		world.getServer().runCommand("summon cow " + (x - 6) + " " + y + " " + (z - 5) + " " + still);
		world.getServer().runCommand("summon cow " + (x - 3) + " " + y + " " + (z + 7) + " " + still);
		world.getServer().runCommand("summon sheep " + (x + 6) + " " + y + " " + (z - 6) + " " + still);
		world.getServer().runCommand("summon pig " + (x + 7) + " " + y + " " + (z + 5) + " " + still);
		world.getServer().runCommand("summon chicken " + (x - 8) + " " + y + " " + (z + 1) + " " + still);
		world.getServer().runCommand("summon zombie " + (x + 2) + " " + y + " " + (z - 9) + " " + still);
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.radarCategories.clear();
			s.radarKinds.clear();
			s.radarPassive = true;
			RadarCategories.changed();
		});
		DioramaClientTest.settle(context, 60);
		context.takeScreenshot("radar-categories-01-before");

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		DioramaClientTest.click(context, "Radar");
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-02-radar-tab");
		DioramaClientTest.click(context, "Categories: 5");
		expectScreen(context, RadarCategoriesScreen.class);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-03-list");

		DioramaClientTest.click(context, "New category");
		expectScreen(context, RadarCategoryScreen.class);
		context.getInput().typeChars("Farm");
		context.waitTicks(3);
		DioramaClientTest.click(context, "Mobs: 0");
		expectScreen(context, RadarMobsScreen.class);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-04-mobs");
		context.getInput().typeChars("cow");
		context.waitTicks(3);
		DioramaClientTest.click(context, "Cow");
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-05-mobs-search");
		List<String> picked = context.computeOnClient(mc -> List.copyOf(Settings.get().radarCategories.get(0).mobs));
		if (!picked.equals(List.of("minecraft:cow"))) {
			throw new AssertionError("Searching cow and ticking Cow put " + picked + " in the category");
		}
		DioramaClientTest.click(context, "Done");
		expectScreen(context, RadarCategoryScreen.class);
		// The right end of the size slider, and names everywhere.
		DioramaClientTest.clickAlong(context, "Icon size: ", 1.0);
		DioramaClientTest.click(context, "Names: ");
		DioramaClientTest.click(context, "Names: ");
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-06-edit");
		String made = context.computeOnClient(mc -> {
			RadarCategories.Category farm = Settings.get().radarCategories.get(0);
			return farm.name + " " + farm.mobs + " " + farm.iconScale + " " + farm.names + " " + farm.shown;
		});
		if (!made.equals("Farm [minecraft:cow] " + Settings.MAX_SCALE + " ALWAYS true")) {
			throw new AssertionError("The category made on the screens is: " + made);
		}
		DioramaClientTest.click(context, "Done");
		expectScreen(context, RadarCategoriesScreen.class);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-07-list-with-own");
		// Passive mobs off, on this screen: the same setting as the Radar tab's tick.
		DioramaClientTest.click(context, "Passive mobs");
		if (context.computeOnClient(mc -> Settings.get().radarPassive)) {
			throw new AssertionError("Unticking Passive mobs on the categories screen didn't switch them off");
		}
		context.setScreen(() -> null);
		context.waitTicks(10);
		context.takeScreenshot("radar-categories-08-minimap-own-kept");
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(mc.player.getX(), dev.diorama.Projection.v(mc.player.getY(), mc.player.getZ()), 6.0, TileKey.SURFACE);
			}
		});
		DioramaClientTest.settle(context, 40);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-09-map-own-kept");
		Set<String> shown = context.computeOnClient(mc -> {
			Set<String> types = new TreeSet<>();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				// Not the drops of the mobs cleared at the start.
				if (blip.kind() != Radar.Kind.ITEM) {
					types.add(EntityType.getKey(blip.entity().getType()).getPath() + (blip.named() ? "+name" : ""));
				}
			}
			return types;
		});
		if (!shown.equals(Set.of("cow+name", "zombie"))) {
			throw new AssertionError("With passive mobs off and cows in their own category the map's radar shows " + shown + ", not the cows and the zombie");
		}
		// The category switched off hides its mobs though their kind is on.
		context.runOnClient(mc -> {
			Settings.get().radarPassive = true;
			Settings.get().radarCategories.get(0).shown = false;
		});
		context.waitTicks(5);
		context.takeScreenshot("radar-categories-10-map-own-hidden");
		shown = context.computeOnClient(mc -> {
			Set<String> types = new TreeSet<>();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				if (blip.kind() != Radar.Kind.ITEM) {
					types.add(EntityType.getKey(blip.entity().getType()).getPath());
				}
			}
			return types;
		});
		if (!shown.equals(Set.of("chicken", "pig", "sheep", "zombie"))) {
			throw new AssertionError("With the cows' category off the map's radar shows " + shown);
		}
		context.setScreen(() -> null);
		// The category Diorama makes when Cobblemon or Pixelmon is installed, as the list shows it.
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			RadarCategories.Category pokemon = new RadarCategories.Category();
			pokemon.builtIn = RadarCategories.POKEMON;
			pokemon.mobs.add("cobblemon:pokemon");
			pokemon.mobs.add("pixelmon:pixelmon");
			s.radarCategories.get(0).shown = true;
			s.radarCategories.add(0, pokemon);
			RadarCategories.changed();
		});
		context.setScreen(() -> new RadarCategoriesScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("radar-categories-11-list-pokemon");
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings.get().radarCategories.remove(0);
			RadarCategories.changed();
			Settings.get().save();
		});

		// A file from before categories keeps what it switched off, and gains no category.
		context.runOnClient(mc -> {
			java.nio.file.Path file = mc.gameDirectory.toPath().resolve("diorama").resolve("config.json");
			String before;
			try {
				before = java.nio.file.Files.readString(file);
				java.nio.file.Files.writeString(file, "{\"radarPassive\": false, \"radarItems\": false, \"radarIconScale\": 150, \"playerNames\": \"ALWAYS\", \"defaultsMoved\": 1}");
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			Settings s = Settings.reload();
			boolean kept = !s.radarPassive && !s.radarItems && s.radarHostile && s.radarIconScale == 150 && s.radarCategories.isEmpty()
				&& !RadarCategories.kind(Radar.Kind.PASSIVE).shown() && RadarCategories.kind(Radar.Kind.HOSTILE).shown()
				&& RadarCategories.kind(Radar.Kind.PLAYER).names() == Settings.NameTags.ALWAYS
				&& RadarCategories.kind(Radar.Kind.PASSIVE).iconScale() == RadarCategories.SAME_SIZE;
			try {
				java.nio.file.Files.writeString(file, before);
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			Settings restored = Settings.reload();
			if (!kept) {
				throw new AssertionError("An old settings file's radar settings didn't carry over");
			}
			// What the screens saved came back from the file.
			if (restored.radarCategories.size() != 1 || !"Farm".equals(restored.radarCategories.get(0).name)) {
				throw new AssertionError("The category wasn't in the saved file");
			}
			restored.radarCategories.clear();
			restored.radarKinds.clear();
			restored.radarPassive = true;
			RadarCategories.changed();
			restored.save();
		});
		world.getServer().runCommand("kill @e[type=!player]");
	}

	private static void expectScreen(ClientGameTestContext context, Class<?> type) {
		context.waitTicks(3);
		String open = context.computeOnClient(mc -> mc.gui.screen() == null ? "no screen" : mc.gui.screen().getClass().getSimpleName());
		if (!open.equals(type.getSimpleName())) {
			throw new AssertionError("Expected " + type.getSimpleName() + " to be open, not " + open);
		}
	}
}
