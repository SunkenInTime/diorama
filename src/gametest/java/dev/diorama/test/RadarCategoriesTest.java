package dev.diorama.test;

import com.mojang.authlib.GameProfile;
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
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;

/**
 * The radar's categories ({@code radar-categories}), driven as a player would: Radar tab,
 * Categories, New category, a name, its mobs picked from the list, a bigger icon and names. Then
 * the point of them (#344): with the kind a mob belongs to switched off, the mobs in the player's
 * own category stay on the radar. An old settings file keeps what it switched off. And each
 * category's own height range, with players at any height from the start.
 */
final class RadarCategoriesTest {
	private static final Set<String> SUMMONED = Set.of("cow", "sheep", "pig", "chicken", "husk");
	/** The stand-in for another player. */
	private static final int FRIEND = -424243;

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
		// A husk: a zombie burns at noon.
		world.getServer().runCommand("summon husk " + (x + 2) + " " + y + " " + (z - 9) + " " + still);
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
		// The cows' names are the minimap's text: its Text size sizes them with the lines under it (#412).
		int[] textSizes = context.computeOnClient(mc -> dev.diorama.render.TextSize.sizes());
		int[] inked = new int[3];
		int[] tried = {textSizes[0], 100, 200};
		for (int i = 0; i < tried.length; i++) {
			int size = tried[i];
			context.runOnClient(mc -> Settings.get().minimapTextSize = size);
			context.waitTicks(5);
			inked[i] = nameInk(context, context.takeScreenshot("radar-categories-08-minimap-names-" + size));
		}
		context.runOnClient(mc -> Settings.get().minimapTextSize = 100);
		// On a GUI scale of 1 the smallest size is 100% itself.
		if (tried[0] < 100 && inked[0] + 20 > inked[1] || inked[1] + 20 > inked[2]) {
			throw new AssertionError("The names on the minimap should grow with its Text size: at " + tried[0] + "%, 100% and 200% the map has "
				+ inked[0] + ", " + inked[1] + " and " + inked[2] + " white pixels");
		}
		// Round: a name shows only where its plate fits inside the circle.
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
		context.waitTicks(5);
		context.takeScreenshot("radar-categories-08b-round-minimap");
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);
		// Held, the player list key names the husk too, whose category has names off.
		context.waitTicks(5);
		int set = nameInk(context, context.takeScreenshot("radar-categories-08c-minimap-names-as-set"));
		KeyMapping playerList = context.computeOnClient(mc -> mc.options.keyPlayerList);
		context.getInput().holdKey(playerList);
		context.waitTicks(5);
		int held = nameInk(context, context.takeScreenshot("radar-categories-08d-minimap-names-key-held"));
		context.getInput().releaseKey(playerList);
		context.waitTicks(5);
		int released = nameInk(context, context.takeScreenshot("radar-categories-08e-minimap-names-key-released"));
		if (held < set + 20 || Math.abs(released - set) > 10) {
			throw new AssertionError("Holding the player list key should name every mob on the minimap, and only while held: the names have "
				+ set + " white pixels before, " + held + " held and " + released + " after");
		}
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(dev.diorama.Projection.u(mc.player.getX(), mc.player.getZ()), dev.diorama.Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 6.0, TileKey.SURFACE);
			}
		});
		DioramaClientTest.settle(context, 40);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-09-map-own-kept");
		Set<String> shown = context.computeOnClient(mc -> {
			Set<String> types = new TreeSet<>();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				// Only the mobs summoned here: the world has its own fish and drops.
				if (SUMMONED.contains(EntityType.getKey(blip.entity().getType()).getPath())) {
					types.add(EntityType.getKey(blip.entity().getType()).getPath() + (blip.named() ? "+name" : ""));
				}
			}
			return types;
		});
		if (!shown.equals(Set.of("cow+name", "husk"))) {
			throw new AssertionError("With passive mobs off and cows in their own category the map's radar shows " + shown + ", not the cows and the husk");
		}
		// The cows are drawn at their category's 200%, twice the husk's size at the radar's 100%.
		float[] sizes = context.computeOnClient(mc -> {
			float cow = 0;
			float husk = 0;
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				String type = EntityType.getKey(blip.entity().getType()).getPath();
				if (type.equals("cow")) {
					cow = blip.size();
				} else if (type.equals("husk")) {
					husk = blip.size();
				}
			}
			return new float[] {cow, husk};
		});
		if (Math.abs(sizes[0] - 2 * sizes[1]) > 0.01F) {
			throw new AssertionError("A cow in a 200% category is drawn at " + sizes[0] + " beside a husk at " + sizes[1]);
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
				if (SUMMONED.contains(EntityType.getKey(blip.entity().getType()).getPath())) {
					types.add(EntityType.getKey(blip.entity().getType()).getPath());
				}
			}
			return types;
		});
		if (!shown.equals(Set.of("chicken", "pig", "sheep", "husk"))) {
			throw new AssertionError("With the cows' category off the map's radar shows " + shown);
		}
		context.setScreen(() -> null);
		heightRanges(context, world, x, y, z);
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
			boolean kept = !s.radarPassive && !s.radarItems && s.radarHostile && s.radarIconScale == 150
				// A file from before Item pictures has them on, as they were (#461).
				&& s.radarItemPictures
				// None but the one Diorama makes itself, if this game has a Pokémon mod.
				&& s.radarCategories.stream().allMatch(category -> category.builtIn != null)
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

	/**
	 * Each category's own height range: with the radar's at its lowest, so the cave's mobs stay off
	 * the map, a friend down there still shows, as players do at any height unless the player says
	 * otherwise. Players set to follow the radar's go with the mobs, which is how it was for everyone
	 * before; hostile mobs set to any height bring the husk back.
	 */
	private static void heightRanges(ClientGameTestContext context, TestSingleplayerContext world, int x, int y, int z) {
		int below = y - 24;
		world.getServer().runCommand("fill " + (x + 4) + " " + below + " " + (z + 3) + " " + (x + 6) + " " + (below + 2) + " " + (z + 5) + " air");
		world.getServer().runCommand("summon husk " + (x + 5) + " " + below + " " + (z + 4) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:1b,Tags:[\"diorama_deep\"]}");
		int range = context.computeOnClient(mc -> Settings.get().radarRange);
		context.runOnClient(mc -> {
			Settings.get().radarRange = Settings.RANGES[0];
			// Tests run one real client, so the friend is a stand-in added on the client.
			RemotePlayer friend = new RemotePlayer(mc.level, new GameProfile(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch"));
			friend.setId(FRIEND);
			friend.snapTo(x - 4.5, below, z + 4.5);
			//? if >=1.21 {
			mc.level.addEntity(friend);
			//?} else {
			/*mc.level.addPlayer(friend.getId(), friend);
			*///?}
		});
		try {
			DioramaClientTest.settle(context, 40);
			context.takeScreenshot("radar-height-01-friend-below-shown");
			expectDeep(context, Set.of("player"), "With the radar's height range at 16 m and nothing else changed");

			// Players follow the radar's range, on their category's screen: the friend goes with the mobs.
			context.setScreen(() -> new RadarCategoryScreen(null, RadarCategories.kind(Radar.Kind.PLAYER)));
			context.waitTicks(5);
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(3);
			context.takeScreenshot("radar-height-02-players-category");
			DioramaClientTest.clickAlong(context, "Height range: ", 0.0);
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(3);
			context.takeScreenshot("radar-height-03-players-same-as-radar");
			if (context.computeOnClient(mc -> RadarCategories.kind(Radar.Kind.PLAYER).range()) != RadarCategories.SAME_RANGE) {
				throw new AssertionError("The left end of the Players' Height range slider isn't the radar's own range");
			}
			context.setScreen(() -> null);
			context.waitTicks(10);
			context.takeScreenshot("radar-height-04-friend-below-hidden");
			expectDeep(context, Set.of(), "With players following the radar's 16 m");

			// Hostile mobs at any height: the husk in the cave shows, the friend stays off.
			context.setScreen(() -> new RadarCategoryScreen(null, RadarCategories.kind(Radar.Kind.HOSTILE)));
			context.waitTicks(5);
			DioramaClientTest.clickAlong(context, "Height range: ", 1.0);
			if (context.computeOnClient(mc -> RadarCategories.kind(Radar.Kind.HOSTILE).range()) != RadarCategories.ANY_RANGE) {
				throw new AssertionError("The right end of the Hostile mobs' Height range slider isn't any height");
			}
			context.setScreen(() -> null);
			context.waitTicks(10);
			context.takeScreenshot("radar-height-05-husk-below-shown");
			expectDeep(context, Set.of("husk"), "With hostile mobs at any height and players following the radar's 16 m");
			context.setScreen(() -> new RadarCategoriesScreen(null));
			context.waitTicks(5);
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(3);
			context.takeScreenshot("radar-height-06-list");
			context.setScreen(() -> null);

			// A range of its own in blocks: 32 m reaches the husk 24 below, 16 m doesn't, whatever the radar's says.
			context.runOnClient(mc -> RadarCategories.kind(Radar.Kind.HOSTILE).range(32));
			expectDeep(context, Set.of("husk"), "With hostile mobs at 32 m");
			context.runOnClient(mc -> {
				RadarCategories.kind(Radar.Kind.HOSTILE).range(16);
				Settings.get().radarRange = 128;
			});
			// The friend follows the radar's range, now 128 m, and is back; the husk keeps to its own 16 m.
			expectDeep(context, Set.of("player"), "With hostile mobs at 16 m and the radar's range, which players follow, at 128 m");
			// One of the player's own categories has its range too: husks in one at any height.
			context.runOnClient(mc -> {
				RadarCategories.Category deep = new RadarCategories.Category();
				deep.name = "Deep";
				deep.mobs.add("minecraft:husk");
				deep.range = RadarCategories.ANY_RANGE;
				Settings.get().radarCategories.add(0, deep);
				RadarCategories.changed();
			});
			expectDeep(context, Set.of("husk", "player"), "With husks in a category of their own at any height");
			// And the file keeps them.
			context.runOnClient(mc -> {
				Settings.get().save();
				Settings s = Settings.reload();
				if (s.radarCategories.get(0).range != RadarCategories.ANY_RANGE || RadarCategories.kind(Radar.Kind.HOSTILE).range() != 16
					|| RadarCategories.kind(Radar.Kind.PLAYER).range() != RadarCategories.SAME_RANGE) {
					throw new AssertionError("The height ranges didn't come back from the saved file");
				}
				s.radarCategories.remove(0);
				RadarCategories.changed();
			});

			// Nothing picked: players at any height, every other kind as the radar's.
			context.runOnClient(mc -> {
				Settings.get().radarKinds.clear();
				for (Radar.Kind kind : Radar.Kind.values()) {
					int usual = kind == Radar.Kind.PLAYER ? RadarCategories.ANY_RANGE : RadarCategories.SAME_RANGE;
					if (RadarCategories.kind(kind).range() != usual) {
						throw new AssertionError(kind + " starts with the height range " + RadarCategories.kind(kind).range());
					}
				}
			});
		} finally {
			context.runOnClient(mc -> {
				Settings.get().radarRange = range;
				Settings.get().radarKinds.clear();
				Settings.get().save();
				mc.level.removeEntity(FRIEND, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
			});
			world.getServer().runCommand("kill @e[tag=diorama_deep]");
		}
	}

	/** Which of the two 24 blocks below, the friend and the husk, the map's radar shows. */
	private static void expectDeep(ClientGameTestContext context, Set<String> expected, String when) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(dev.diorama.Projection.u(mc.player.getX(), mc.player.getZ()),
			dev.diorama.Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 3.0, TileKey.SURFACE));
		context.waitTicks(5);
		Set<String> shown = context.computeOnClient(mc -> {
			Set<String> deep = new TreeSet<>();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				if (blip.entity().getId() == FRIEND) {
					deep.add("player");
				} else if (blip.dy() < -20 && EntityType.getKey(blip.entity().getType()).getPath().equals("husk")) {
					deep.add("husk");
				}
			}
			return deep;
		});
		context.setScreen(() -> null);
		context.waitTicks(3);
		if (!shown.equals(expected)) {
			throw new AssertionError(when + ", of the friend and the husk 24 blocks below the radar shows " + shown + ", not " + expected);
		}
	}

	/**
	 * With Cobblemon installed (opt-in: {@code radar-categories-cobblemon}): the Pokémon category is
	 * there without setup, and with passive mobs off, which a Pokémon is to the game, the cow goes
	 * and the Pokémon stay (#344).
	 */
	static void cobblemon(ClientGameTestContext context, TestSingleplayerContext world) {
		String made = context.computeOnClient(mc -> {
			List<RadarCategories.Category> own = Settings.get().radarCategories;
			return own.isEmpty() ? "none" : own.get(0).builtIn + " " + own.get(0).mobs + " " + own.get(0).name().getString();
		});
		if (!made.startsWith("pokemon [cobblemon:pokemon, pixelmon:pixelmon] Pok")) {
			throw new AssertionError("With Cobblemon installed the first category is: " + made);
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
		world.getServer().runCommand("kill @e[type=!player]");
		String[] species = {"bulbasaur", "pikachu", "snorlax", "eevee"};
		for (int i = 0; i < species.length; i++) {
			world.getServer().runCommand("pokespawnat " + (x + (i % 2 == 0 ? -6 : 6)) + " " + y + " " + (z + (i / 2 == 0 ? -6 : 8)) + " " + species[i] + " no_ai=true");
		}
		String still = "{NoAI:1b,PersistenceRequired:1b,Silent:1b}";
		world.getServer().runCommand("summon cow " + (x - 9) + " " + y + " " + (z - 1) + " " + still);
		world.getServer().runCommand("summon sheep " + (x + 9) + " " + y + " " + (z + 1) + " " + still);
		world.getServer().runCommand("summon pig " + x + " " + y + " " + (z - 9) + " " + still);
		world.getServer().runCommand("summon husk " + x + " " + y + " " + (z + 10) + " " + still);
		context.runOnClient(mc -> Settings.get().radarPassive = true);
		// The portraits are drawn a couple a frame after the radar first asks for them, and Cobblemon's
		// toast about starters sits over the minimap for its first seconds.
		context.waitTicks(260);
		context.takeScreenshot("radar-categories-cobblemon-01-minimap-all");
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(dev.diorama.Projection.u(mc.player.getX(), mc.player.getZ()), dev.diorama.Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 6.0, TileKey.SURFACE);
			}
		});
		context.waitTicks(40);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-cobblemon-01-map-all");
		context.setScreen(() -> new RadarCategoriesScreen(null));
		context.waitTicks(5);
		DioramaClientTest.click(context, "Passive mobs");
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-cobblemon-02-list");
		context.setScreen(() -> null);
		context.waitTicks(10);
		context.takeScreenshot("radar-categories-cobblemon-03-farm-animals-hidden");
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(dev.diorama.Projection.u(mc.player.getX(), mc.player.getZ()), dev.diorama.Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 6.0, TileKey.SURFACE);
			}
		});
		context.waitTicks(40);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("radar-categories-cobblemon-04-map");
		List<String> shown = context.computeOnClient(mc -> {
			List<String> types = new java.util.ArrayList<>();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				if (blip.kind() != Radar.Kind.ITEM) {
					types.add(EntityType.getKey(blip.entity().getType()).toString());
				}
			}
			java.util.Collections.sort(types);
			return types;
		});
		long pokemon = shown.stream().filter("cobblemon:pokemon"::equals).count();
		if (pokemon < species.length || !shown.contains("minecraft:husk") || shown.stream().anyMatch(type -> Set.of("minecraft:cow", "minecraft:sheep", "minecraft:pig").contains(type))) {
			throw new AssertionError("With passive mobs off the radar should keep the Pokémon and the husk and lose the farm animals; it shows " + shown);
		}
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings.get().radarPassive = true;
			Settings.get().save();
		});
		world.getServer().runCommand("kill @e[type=!player]");
	}

	/** How many pixels of the minimap's map are the white of the names: the lines under it aren't counted, and its compass keeps its own size. */
	private static int nameInk(ClientGameTestContext context, java.nio.file.Path file) {
		int[] at = context.computeOnClient(mc -> {
			int[] shown = dev.diorama.MinimapHud.shown();
			int scale = (int) mc.getWindow().getGuiScale();
			return new int[] {shown[0] * scale, shown[1] * scale, shown[2] * scale};
		});
		java.awt.image.BufferedImage shot;
		try {
			shot = javax.imageio.ImageIO.read(file.toFile());
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		int white = 0;
		for (int y = at[1]; y < at[1] + at[2]; y++) {
			for (int x = at[0]; x < at[0] + at[2]; x++) {
				int rgb = shot.getRGB(x, y);
				// Before 1.21.6 the game draws text a shade off the colour it's given.
				if ((rgb >> 16 & 0xFF) >= 251 && (rgb >> 8 & 0xFF) >= 251 && (rgb & 0xFF) >= 251) {
					white++;
				}
			}
		}
		return white;
	}

	private static void expectScreen(ClientGameTestContext context, Class<?> type) {
		context.waitTicks(3);
		String open = context.computeOnClient(mc -> mc.gui.screen() == null ? "no screen" : mc.gui.screen().getClass().getSimpleName());
		if (!open.equals(type.getSimpleName())) {
			throw new AssertionError("Expected " + type.getSimpleName() + " to be open, not " + open);
		}
	}
}
