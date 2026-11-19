package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.RadarCategories;
import dev.diorama.render.Radar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Another mod's entities on the radar ({@code radar-tag}): a minecart, which is no mob, isn't on
 * the radar until a data pack puts its type in the entity type tag {@code diorama:radar_shown}, as
 * a mod would in its own data. Then it's there with the icon its icon file gives it (the test's
 * resources have one for the minecart) and can be put in a radar category, while an armour stand
 * beside it, which nothing tagged, stays off.
 */
final class RadarTagTest {
	private RadarTagTest() {
	}

	static void run(ClientGameTestContext context) {
		List<String> wrong = new ArrayList<>();
		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setName("Diorama Radar Tag");
				s.setSeed("diorama");
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			save = world.getWorldSave();
			world.getServer().runCommand("time set noon");
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100 && mc.player.onGround(), 2400);
			BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
			int x = at.getX();
			int y = at.getY();
			int z = at.getZ();
			world.getServer().runCommand("fill " + (x - 6) + " " + y + " " + (z - 6) + " " + (x + 6) + " " + (y + 3) + " " + (z + 6) + " air");
			world.getServer().runCommand("fill " + (x - 6) + " " + (y - 1) + " " + (z - 6) + " " + (x + 6) + " " + (y - 1) + " " + (z + 6) + " grass_block");
			world.getServer().runCommand("summon minecart " + (x + 3) + " " + y + " " + (z - 3) + " {Invulnerable:1b}");
			world.getServer().runCommand("summon armor_stand " + (x - 3) + " " + y + " " + (z - 3) + " {Invulnerable:1b}");
			world.getServer().runCommand("summon cow " + x + " " + y + " " + (z + 4) + " {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}");
			context.waitFor(mc -> entity(mc.level.entitiesForRendering(), "minecraft:minecart") != null
				&& entity(mc.level.entitiesForRendering(), "minecraft:armor_stand") != null, 400);
			context.runOnClient(mc -> {
				Radar.Kind kind = Radar.kind(entity(mc.level.entitiesForRendering(), "minecraft:minecart"));
				if (kind != null) {
					wrong.add("Before any tag, the minecart is on the radar as " + kind);
				}
			});
			map(context, "radar-tag-01-untagged");
			world.getServer().runCommand("save-all flush");
			context.waitTicks(20);
		}

		Path pack = save.getSaveDirectory().resolve("datapacks").resolve("radar-tag");
		//? if >=1.21 {
		String tags = "data/diorama/tags/entity_type";
		//?} else {
		/*String tags = "data/diorama/tags/entity_types";
		*///?}
		String formats = context.computeOnClient(mc -> {
			//? if >=1.21.9 {
			var format = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.SERVER_DATA);
			return "\"pack_format\": " + format.major() + ", \"min_format\": " + format.major() + ", \"max_format\": " + format.major();
			//?} else if >=1.21.6 {
			/*return "\"pack_format\": " + net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.SERVER_DATA);
			*///?} else {
			/*return "\"pack_format\": " + net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.SERVER_DATA);
			*///?}
		});
		try {
			Files.createDirectories(pack.resolve(tags));
			Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\": {" + formats + ", \"description\": \"Diorama test: radar tag\"}}");
			Files.writeString(pack.resolve(tags).resolve("radar_shown.json"), "{\"values\": [\"minecraft:minecart\"]}");
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}

		try (TestSingleplayerContext world = save.open()) {
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100
				&& entity(mc.level.entitiesForRendering(), "minecraft:minecart") != null
				&& entity(mc.level.entitiesForRendering(), "minecraft:armor_stand") != null, 2400);
			context.runOnClient(mc -> {
				Radar.Kind cart = Radar.kind(entity(mc.level.entitiesForRendering(), "minecraft:minecart"));
				if (cart != Radar.Kind.PASSIVE) {
					wrong.add("Tagged, the minecart is on the radar as " + cart);
				}
				Radar.Kind stand = Radar.kind(entity(mc.level.entitiesForRendering(), "minecraft:armor_stand"));
				if (stand != null) {
					wrong.add("The armour stand, which nothing tagged, is on the radar as " + stand);
				}
				if (RadarCategories.mobs().stream().noneMatch(mob -> mob.id().equals("minecraft:minecart"))) {
					wrong.add("The tagged minecart can't be picked for a radar category");
				}
				if (RadarCategories.mobs().stream().anyMatch(mob -> mob.id().equals("minecraft:armor_stand"))) {
					wrong.add("The armour stand can be picked for a radar category");
				}
			});
			map(context, "radar-tag-02-tagged");
			DioramaClientTest.settle(context, 20);
			context.takeScreenshot("radar-tag-03-tagged-minimap");
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("Radar tag: " + wrong);
		}
	}

	/** The full map close in on the player, the mouse out of the way of the map's tooltips. */
	private static void map(ClientGameTestContext context, String shot) {
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 9.0, dev.diorama.bake.TileKey.SURFACE);
		context.getInput().setCursorPos(800, 895);
		DioramaClientTest.settle(context, 60);
		context.takeScreenshot(shot);
		context.setScreen(() -> null);
	}

	private static Entity entity(Iterable<Entity> entities, String type) {
		for (Entity entity : entities) {
			if (EntityType.getKey(entity.getType()).toString().equals(type)) {
				return entity;
			}
		}
		return null;
	}
}
