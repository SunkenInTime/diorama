package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.WorldKey;
import dev.diorama.bake.TileKey;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Portal pins. {@link #checks} runs by default ({@code portal-pins}): one pin on the biggest Nether
 * portal, a pin each on an End portal and a gateway, and what happens to pins as the setting turns,
 * as the player deletes one and as a portal goes out; portals in far chunks read from the save;
 * nothing under fog of war; a portal out of its frame is named plainly. {@link #pluginPortals} is
 * opt-in ({@code portal-plugin}), on a Paper server with the AdvancedPortals plugin. {@link #photos} is opt-in ({@code portal-photos}): {@link ShowcaseScene}'s
 * castle with a portal beside it, for the PR's pictures.
 */
final class PortalPinsTest {
	private static final String OVERWORLD = "minecraft:overworld";
	/** Far from anything else the tests build, and from the player. */
	private static final int SAVED_X = -3200;
	private static final int SAVED_Z = -3200;
	/** How long the map may take: a change waits a moment, then a chunk comes up in its queue. */
	private static final int PIN_TICKS = 200;
	/** Server commands run in the Overworld; this runs one in the Nether. */
	private static final String NETHER = "execute in minecraft:the_nether run ";

	private PortalPinsTest() {
	}

	static void checks(ClientGameTestContext context, TestSingleplayerContext world) {
		context.runOnClient(mc -> Settings.get().portalMarkers = true);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int y = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ())) + 30;
		// In the air north of the player, the widest portal the game allows, over several chunks.
		int x = at.getX() - 11;
		int z = at.getZ() - 12;
		netherPortal(world, x, y, z, 21, 21);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 1, "the widest Nether portal");
		Waypoint big = pin(context, Waypoint.Kind.NETHER_PORTAL);
		if (big.x < x || big.x > x + 22 || big.z != z || big.y < y || big.y > y + 22) {
			throw new AssertionError("The pin is off the portal: " + big.x + " " + big.y + " " + big.z);
		}
		expectName(context, Waypoint.Kind.NETHER_PORTAL, "Nether Portal", "a portal in obsidian");

		int ex = at.getX() + 8;
		int ez = at.getZ() + 8;
		world.getServer().runCommand("fill " + ex + " " + y + " " + ez + " " + (ex + 2) + " " + y + " " + (ez + 2) + " end_portal");
		expectPins(context, Waypoint.Kind.END_PORTAL, 1, "a three by three End portal");
		// Issue #311: out of its frame it's some server's or builder's portal, to who knows where.
		expectName(context, Waypoint.Kind.END_PORTAL, "Portal", "End portal blocks without a frame");
		world.getServer().runCommand("fill " + (ex - 1) + " " + y + " " + (ez - 1) + " " + (ex + 3) + " " + y + " " + (ez + 3) + " end_portal_frame[eye=true] replace air");
		expectName(context, Waypoint.Kind.END_PORTAL, "End Portal", "the frame going up around them");
		if (context.computeOnClient(mc -> WaypointStore.open(WorldKey.current()).all().stream().noneMatch(w -> w.kind == Waypoint.Kind.END_PORTAL && w.name.equals("End Portal")))) {
			throw new AssertionError("The pin's new name isn't in the file");
		}
		world.getServer().runCommand("setblock " + (ex + 20) + " " + y + " " + ez + " end_gateway");
		expectPins(context, Waypoint.Kind.END_GATEWAY, 1, "a gateway");
		expectName(context, Waypoint.Kind.END_GATEWAY, "Portal", "a gateway without its bedrock");
		world.getServer().runCommand("setblock " + (ex + 20) + " " + (y + 1) + " " + ez + " bedrock");
		world.getServer().runCommand("setblock " + (ex + 20) + " " + (y - 1) + " " + ez + " bedrock");
		expectName(context, Waypoint.Kind.END_GATEWAY, "End Gateway", "bedrock above and below it");

		// Well apart from the first, so a pin of its own.
		int second = at.getX() + 50;
		netherPortal(world, second, y, z, 2, 3);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "a second portal further on");
		Waypoint small = pinNear(context, second);

		// The setting shows and hides them, and they're found with it off.
		context.runOnClient(mc -> Settings.get().portalMarkers = false);
		expectListed(context, 0, "with portal pins off");
		int third = at.getX() - 60;
		netherPortal(world, third, y, z, 2, 3);
		context.waitTicks(40);
		context.runOnClient(mc -> Settings.get().portalMarkers = true);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "a portal lit with the setting off");
		expectListed(context, 5, "with portal pins back on");
		// A waypoint of the player's with the portal icon is that portal's pin.
		int fourth = at.getX() + 110;
		context.runOnClient(mc -> {
			Waypoint mine = new Waypoint("My gate", fourth, y, z, OVERWORLD, Waypoint.PALETTE[0]);
			mine.icon = "diorama:portal";
			WaypointStore.get().add(mine);
		});
		netherPortal(world, fourth, y, z, 2, 3);
		context.waitTicks(40);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "a portal already marked by the player");
		frameBroken(world, fourth, y, z);
		context.waitTicks(40);
		context.runOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.name.equals("My gate")).toList().forEach(WaypointStore.get()::remove));

		// A deleted pin stays gone while the portal stands, through more news from its chunk.
		context.runOnClient(mc -> WaypointStore.get().remove(WaypointStore.get().all().stream().filter(w -> w.id.equals(small.id)).findFirst().orElseThrow()));
		world.getServer().runCommand("setblock " + (second + 1) + " " + (y + 8) + " " + z + " glass");
		context.waitTicks(40);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "deleting a pin");
		if (context.computeOnClient(mc -> WaypointStore.open(WorldKey.current()).all().stream().anyMatch(w -> w.id.equals(small.id)))) {
			throw new AssertionError("The deleted pin came back from the file");
		}

		// Breaking the portal takes its pin, and a new one there gets a pin again.
		frameBroken(world, second, y, z);
		context.waitTicks(40);
		netherPortal(world, second, y, z, 2, 3);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "relighting a portal whose pin was deleted");
		// The one lit with the setting off goes out, and takes its pin along.
		frameBroken(world, third, y, z);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "breaking a portal");
		// A pin the player renamed is theirs, and stays.
		rename(context, pinNear(context, second), "Home gate");
		frameBroken(world, second, y, z);
		context.waitTicks(40);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "breaking a renamed pin's portal");

		// Nothing the player hasn't seen shows under fog of war.
		context.runOnClient(mc -> ServerRules.setAlwaysFogOfWar(true));
		netherPortal(world, third, y, z, 2, 3);
		context.waitTicks(40);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "a portal lit under fog of war");
		// Gone before the fog lifts: the next news from its chunk would pin it, and rightly.
		frameBroken(world, third, y, z);
		context.waitTicks(40);
		context.runOnClient(mc -> ServerRules.setAlwaysFogOfWar(false));

		// Three portals in a row sharing a pin: when the middle one goes, so does the pin, and each
		// of the others gets its own.
		int pair = at.getX() - 110;
		netherPortal(world, pair, y, z, 2, 3);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "the first of a close pair");
		netherPortal(world, pair + 20, y, z, 2, 3);
		netherPortal(world, pair - 20, y, z, 2, 3);
		context.waitTicks(40);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "two portals beside it");
		frameBroken(world, pair, y, z);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 4, "breaking the portal between two others");
		frameBroken(world, pair + 20, y, z);
		frameBroken(world, pair - 20, y, z);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "breaking the two left");

		// The same with both in one chunk, where a portal of the kind is still there. It goes for a
		// deleted pin too: the portal left was never the one the player deleted.
		int chunk = (at.getX() - 140) & ~15;
		netherPortal(world, chunk, y, z, 2, 3);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "the first portal in a chunk");
		netherPortal(world, chunk + 8, y, z, 2, 3);
		context.waitTicks(40);
		frameBroken(world, chunk, y, z);
		context.waitFor(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.NETHER_PORTAL && w.x >= chunk + 8 && w.x <= chunk + 11), PIN_TICKS);
		context.runOnClient(mc -> WaypointStore.get().remove(pinNowNear(chunk + 9)));
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "deleting that pin");
		netherPortal(world, chunk, y, z, 2, 3);
		context.waitTicks(40);
		frameBroken(world, chunk + 8, y, z);
		context.waitTicks(40);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "breaking a portal with a deleted pin on it");
		frameBroken(world, chunk, y, z);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "breaking the last in the chunk");

		fromTheSave(context, world);

		// Nothing left in the air over spawn for the tests after this one.
		world.getServer().runCommand("fill " + x + " " + y + " " + z + " " + (x + 22) + " " + (y + 22) + " " + z + " air");
		world.getServer().runCommand("fill " + (ex - 1) + " " + (y - 1) + " " + (ez - 1) + " " + (ex + 20) + " " + (y + 1) + " " + (ez + 3) + " air");
		world.getServer().runCommand("setblock " + (second + 1) + " " + (y + 8) + " " + z + " air");
		context.waitTicks(40);
		context.runOnClient(mc -> Settings.get().portalMarkers = false);
	}

	/**
	 * Issue #315: a portal on the Nether roof gets a pin of its own, though one under the roof stands
	 * a few blocks away on the map or straight below it, and breaking it takes only its own pin.
	 */
	static void netherRoof(ClientGameTestContext context, TestSingleplayerContext world) {
		context.runOnClient(mc -> Settings.get().portalMarkers = true);
		String from = context.computeOnClient(mc -> mc.level.dimension().identifier().toString());
		BlockPos start = context.computeOnClient(mc -> mc.player.blockPosition());
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0");
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER
			&& MapSession.get().map().chunkCount() > 200, 2400);
		DioramaClientTest.settle(context, 100);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX() + 4;
		int z = at.getZ() + 4;
		// A roof portal alone is found, so what follows is about the two sharing a pin.
		netherPortal(world, NETHER, x + 60, 128, z, 2, 3);
		expectNetherPins(context, 1, "a portal alone on the roof");
		frameBroken(world, NETHER, x + 60, 128, z);
		expectNetherPins(context, 0, "breaking the lone roof portal");
		// Under the roof, as high as a portal goes: a pocket just under the bedrock.
		world.getServer().runCommand(NETHER + "fill " + (x - 1) + " 116 " + (z - 2) + " " + (x + 4) + " 122 " + (z + 2) + " air");
		netherPortal(world, NETHER, x, 117, z, 2, 3);
		expectNetherPins(context, 1, "a portal under the roof");
		// On the roof, ten blocks along, standing on the bedrock: the player in the issue.
		int roofX = x + 10;
		netherPortal(world, NETHER, roofX, 128, z, 2, 3);
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a " + (roofX + 2) + " 128 " + (z + 6));
		context.waitTicks(40);
		DioramaClientTest.settle(context, 40);
		context.setScreen(MapScreen::new);
		DioramaClientTest.settle(context, 40);
		context.takeScreenshot("portal-roof-map");
		context.setScreen(() -> new dev.diorama.waypoint.WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("portal-roof-list");
		context.setScreen(() -> null);
		expectNetherPins(context, 2, "a portal on the roof beside it");
		if (context.computeOnClient(mc -> netherPins().stream().noneMatch(w -> w.y >= 128 && Math.abs(w.x - roofX - 1) <= 2))) {
			throw new AssertionError("No pin on the roof portal");
		}
		// Breaking the roof portal takes its pin; the one under the roof stays.
		frameBroken(world, NETHER, roofX, 128, z);
		expectNetherPins(context, 1, "breaking the roof portal");
		if (context.computeOnClient(mc -> netherPins().get(0).y >= 128)) {
			throw new AssertionError("The pin left is the roof portal's");
		}
		frameBroken(world, NETHER, x, 117, z);
		expectNetherPins(context, 0, "breaking the portal under the roof");

		// Straight above one under the roof, in the same chunk: the chunk has a portal on each side.
		// The one under is wider, so most of the chunk's portal blocks are its.
		int same = (x + 40) & ~15;
		world.getServer().runCommand(NETHER + "fill " + (same - 1) + " 116 " + (z - 2) + " " + (same + 6) + " 122 " + (z + 2) + " air");
		netherPortal(world, NETHER, same, 117, z, 4, 3);
		expectNetherPins(context, 1, "a wide portal under the roof");
		netherPortal(world, NETHER, same, 128, z, 2, 3);
		expectNetherPins(context, 2, "a portal on the roof straight above it, in the same chunk");
		world.getServer().runCommand(NETHER + "fill " + same + " 117 " + z + " " + (same + 5) + " 121 " + z + " air");
		frameBroken(world, NETHER, same, 128, z);
		expectNetherPins(context, 0, "breaking both");
		context.runOnClient(mc -> Settings.get().portalMarkers = false);
		// Back where the player was: the tests after this one expect the dimension they'd have had.
		world.getServer().runCommand("execute in " + from + " run tp @a " + start.getX() + " " + start.getY() + " " + start.getZ());
		context.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().toString().equals(from) && mc.gui.screen() == null
			&& MapSession.get() != null && MapSession.get().map().chunkCount() > 200, 2400);
		DioramaClientTest.settle(context, 100);
	}

	private static java.util.List<Waypoint> netherPins() {
		return WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.NETHER_PORTAL && w.dimension.equals("minecraft:the_nether")).toList();
	}

	private static void expectNetherPins(ClientGameTestContext context, int count, String after) {
		for (int i = 0; ; i++) {
			int pins = context.computeOnClient(mc -> netherPins().size());
			if (pins == count) {
				return;
			}
			if (i > PIN_TICKS) {
				throw new AssertionError(count + " Nether portal pins in the Nether expected after " + after + ", got " + pins);
			}
			context.waitTick();
		}
	}

	/** A portal in a chunk the client never had, read from the save behind the map. */
	private static void fromTheSave(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("forceload add " + SAVED_X + " " + SAVED_Z);
		int chunkX = SAVED_X >> 4;
		int chunkZ = SAVED_Z >> 4;
		for (int i = 0; world.getServer().computeOnServer(server -> server.overworld().getChunkSource().getChunkNow(chunkX, chunkZ) == null); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunk never loaded");
			}
			context.waitTick();
		}
		netherPortal(world, SAVED_X + 2, 200, SAVED_Z + 2, 2, 3);
		world.getServer().runCommand("forceload remove all");
		for (int i = 0; world.getServer().computeOnServer(server -> server.overworld().getChunkSource().getChunkNow(chunkX, chunkZ) != null); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunk never unloaded");
			}
			context.waitTick();
		}
		world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
		context.waitTicks(40);
		if (context.computeOnClient(mc -> MapSession.get().map().chunk(chunkX, chunkZ) != null)) {
			throw new AssertionError("The client knows the far chunk: this wouldn't show the save being read");
		}
		try (WorldPause paused = WorldPause.pause(context)) {
			context.runOnClient(mc -> MapSession.get().savedChunks().rescanSoon());
			context.waitFor(mc -> MapSession.get().savedChunks().idle(), 3600);
			context.waitFor(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.NETHER_PORTAL && w.x >> 9 == SAVED_X >> 9 && w.z >> 9 == SAVED_Z >> 9), PIN_TICKS);
		}
	}

	/** Types a new name in the waypoint editor and presses Save, as the player does. */
	private static void rename(ClientGameTestContext context, Waypoint waypoint, String name) {
		context.setScreen(() -> new WaypointEditScreen(null, waypoint, false));
		context.waitTicks(3);
		double[] save = context.computeOnClient(mc -> {
			mc.gui.screen().children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().setValue(name);
			Button button = mc.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
				.filter(b -> b.getMessage().getString().equals(Component.translatable("diorama.waypoint.save").getString())).findFirst().orElseThrow();
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + button.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(save[0], save[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		String saved = context.computeOnClient(mc -> waypoint.name);
		if (!saved.equals(name)) {
			throw new AssertionError("The editor didn't rename the pin: " + saved);
		}
	}

	/** A frame of obsidian and the lit portal in it, with its inside {@code width} by {@code height}, along X. */
	private static void netherPortal(TestSingleplayerContext world, int x, int y, int z, int width, int height) {
		netherPortal(world, "", x, y, z, width, height);
	}

	/** The same in a dimension: {@code in} is an {@code execute in} prefix, as {@link #NETHER}. */
	private static void netherPortal(TestSingleplayerContext world, String in, int x, int y, int z, int width, int height) {
		world.getServer().runCommand(in + "fill " + x + " " + y + " " + z + " " + (x + width + 1) + " " + (y + height + 1) + " " + z + " obsidian");
		world.getServer().runCommand(in + "fill " + (x + 1) + " " + (y + 1) + " " + z + " " + (x + width) + " " + (y + height) + " " + z + " nether_portal[axis=x]");
	}

	private static void frameBroken(TestSingleplayerContext world, int x, int y, int z) {
		frameBroken(world, "", x, y, z);
	}

	private static void frameBroken(TestSingleplayerContext world, String in, int x, int y, int z) {
		world.getServer().runCommand(in + "fill " + x + " " + y + " " + z + " " + (x + 3) + " " + (y + 4) + " " + z + " air");
	}

	private static void expectPins(ClientGameTestContext context, Waypoint.Kind kind, int count, String after) {
		for (int i = 0; ; i++) {
			long pins = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == kind).count());
			if (pins == count) {
				return;
			}
			if (i > PIN_TICKS) {
				throw new AssertionError(count + " " + kind + " pins expected after " + after + ", got " + pins);
			}
			context.waitTick();
		}
	}

	/** Waits for the one pin of a kind to be saved under a name. */
	private static void expectName(ClientGameTestContext context, Waypoint.Kind kind, String name, String after) {
		for (int i = 0; ; i++) {
			String saved = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == kind).findFirst().map(w -> w.name).orElse("no pin"));
			if (saved.equals(name)) {
				return;
			}
			if (i > PIN_TICKS) {
				throw new AssertionError("The " + kind + " pin should be named " + name + " after " + after + ", but it's " + saved);
			}
			context.waitTick();
		}
	}

	/**
	 * Issue #311, as the player met it: a Paper server with the AdvancedPortals plugin, whose portals
	 * are the game's portal blocks in whatever frame the hub's builders liked, leading wherever the
	 * owner set. Three of those, from the plugin's own files, and, for comparison, an End portal and a Nether portal built the
	 * game's way. Needs {@code DIORAMA_REAL_SERVER=1 REAL_SERVER_KIND=paper} and the plugin's jar in
	 * {@code REAL_SERVER_PLUGINS}.
	 */
	static void pluginPortals(ClientGameTestContext context) {
		context.getInput().resizeWindow(1920, 1080);
		try (RealServer server = RealServer.start(false)) {
			if (!server.console().contains("AdvancedPortals")) {
				throw new AssertionError("The server started without the AdvancedPortals plugin");
			}
			server.join(context);
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
			context.runOnClient(mc -> Settings.get().portalMarkers = true);
			String player = context.computeOnClient(mc -> mc.player.getName().getString());
			BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
			int y = at.getY() + 12;
			int z = at.getZ();
			int floor = at.getX() + 8;
			int wall = floor + 40;
			int gate = floor + 80;
			int stronghold = floor - 40;
			int nether = floor - 80;
			// The plugin's own files, as its /portal create and /desti create write them.
			try {
				java.nio.file.Path plugin = server.folder.resolve("plugins/AdvancedPortals");
				java.nio.file.Files.createDirectories(plugin.resolve("portals"));
				java.nio.file.Files.createDirectories(plugin.resolve("desti"));
				java.nio.file.Files.writeString(plugin.resolve("desti/away.yaml"), "loc:\n  posX: " + (at.getX() + 0.5) + "\n  posY: " + (y + 20.0)
					+ "\n  posZ: " + (z + 200.5) + "\n  worldName: world\n  yaw: 0.0\n  pitch: 0.0\nargs:\n  name:\n  - away\n");
				java.nio.file.Files.writeString(plugin.resolve("portals/selector.yaml"), pluginPortal("selector", "END_PORTAL", floor + 1, y, z + 1, floor + 3, y, z + 3));
				java.nio.file.Files.writeString(plugin.resolve("portals/warps.yaml"), pluginPortal("warps", "END_GATEWAY", wall + 1, y + 1, z, wall + 2, y + 3, z));
				java.nio.file.Files.writeString(plugin.resolve("portals/survival.yaml"), pluginPortal("survival", "NETHER_PORTAL", gate + 1, y + 1, z, gate + 2, y + 3, z));
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			server.command("difficulty peaceful");
			server.command("time set noon");
			server.command("portal reload");
			context.waitTicks(40);
			server.command("fill " + floor + " " + y + " " + z + " " + (floor + 4) + " " + y + " " + (z + 4) + " quartz_block");
			server.command("fill " + (floor + 1) + " " + y + " " + (z + 1) + " " + (floor + 3) + " " + y + " " + (z + 3) + " end_portal");
			server.command("fill " + wall + " " + y + " " + z + " " + (wall + 3) + " " + (y + 4) + " " + z + " quartz_block");
			server.command("fill " + (wall + 1) + " " + (y + 1) + " " + z + " " + (wall + 2) + " " + (y + 3) + " " + z + " end_gateway");
			server.command("fill " + gate + " " + y + " " + z + " " + (gate + 3) + " " + (y + 4) + " " + z + " quartz_block");
			server.command("fill " + (gate + 1) + " " + (y + 1) + " " + z + " " + (gate + 2) + " " + (y + 3) + " " + z + " nether_portal[axis=x]");
			server.command("fill " + stronghold + " " + y + " " + z + " " + (stronghold + 4) + " " + y + " " + (z + 4) + " end_portal_frame[eye=true]");
			server.command("fill " + (stronghold + 1) + " " + y + " " + (z + 1) + " " + (stronghold + 3) + " " + y + " " + (z + 3) + " end_portal");
			server.command("fill " + nether + " " + y + " " + z + " " + (nether + 3) + " " + (y + 4) + " " + z + " obsidian");
			server.command("fill " + (nether + 1) + " " + (y + 1) + " " + z + " " + (nether + 2) + " " + (y + 3) + " " + z + " nether_portal[axis=x]");
			for (int i = 0; ; i++) {
				long pins = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind.isPortal()).count());
				if (pins == 5) {
					break;
				}
				if (i > 600) {
					String console = server.console();
					throw new AssertionError("Five portal pins expected on the plugin's server, got " + pins + "\n" + console.substring(Math.max(0, console.length() - 3000)));
				}
				context.waitTick();
			}
			DioramaClientTest.settle(context, 60);
			String names = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind.isPortal())
				.sorted(java.util.Comparator.comparingInt(w -> w.x)).map(w -> w.shownName() + " (" + w.kind + " at " + w.x + " " + w.y + " " + w.z + ")")
				.collect(java.util.stream.Collectors.joining(", ")));
			dev.diorama.Diorama.LOGGER.info("[portal-plugin] pins from west to east: {}", names);
			context.setScreen(() -> new dev.diorama.waypoint.WaypointListScreen(null));
			context.waitTicks(5);
			context.takeScreenshot("portal-plugin-list");
			context.setScreen(MapScreen::new);
			context.getInput().setCursorPos(1300, 30);
			double v = Projection.v(y, z);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(floor + 2.5, v, 3.0, TileKey.SURFACE));
			settle(context, 100);
			context.takeScreenshot("portal-plugin-map");
			context.setScreen(() -> null);

			// They are the plugin's portals: it lists them.
			server.commandAndWait("portal list", "Portals: selector, survival, warps", 30);

			String expected = "Nether Portal, End Portal, Portal, Portal, Portal";
			String saved = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind.isPortal())
				.sorted(java.util.Comparator.comparingInt(w -> w.x)).map(w -> w.name).collect(java.util.stream.Collectors.joining(", ")));
			if (!saved.equals(expected)) {
				throw new AssertionError("Pins from west to east should be " + expected + ", got " + saved);
			}
			context.runOnClient(mc -> Settings.get().portalMarkers = false);
			RealServer.leave(context);
		}
	}

	/** An AdvancedPortals portal file: a box of trigger blocks that sends players to the destination "away". */
	private static String pluginPortal(String name, String trigger, int x1, int y1, int z1, int x2, int y2, int z2) {
		return "maxLoc:\n  posX: " + x2 + "\n  posY: " + y2 + "\n  posZ: " + z2 + "\n  worldName: world\n"
			+ "minLoc:\n  posX: " + x1 + "\n  posY: " + y1 + "\n  posZ: " + z1 + "\n  worldName: world\n"
			+ "args:\n  name:\n  - " + name + "\n  triggerblock:\n  - " + trigger + "\n  destination:\n  - away\n";
	}

	private static void expectListed(ClientGameTestContext context, int count, String when) {
		long listed = context.computeOnClient(mc -> WaypointStore.get().in(OVERWORLD).stream().filter(w -> w.kind.isPortal()).count());
		if (listed != count) {
			throw new AssertionError(count + " portal pins expected on the map " + when + ", got " + listed);
		}
	}

	private static Waypoint pin(ClientGameTestContext context, Waypoint.Kind kind) {
		return context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == kind).findFirst().orElseThrow());
	}

	private static Waypoint pinNear(ClientGameTestContext context, int x) {
		return context.computeOnClient(mc -> pinNowNear(x));
	}

	private static Waypoint pinNowNear(int x) {
		return WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.NETHER_PORTAL && Math.abs(w.x - x) < 8).findFirst().orElseThrow();
	}

	/** The castle with a Nether portal and an End portal beside it, without pins, then with. */
	static void photos(ClientGameTestContext context, TestSingleplayerContext world) {
		context.getInput().resizeWindow(1920, 1080);
		world.getServer().runCommand("difficulty peaceful");
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int cx = village.getX();
		int cz = village.getZ() - 110;
		world.getServer().runCommand("tp @a " + cx + " 200 " + (cz + 40));
		context.waitTicks(100);
		ShowcaseScene scene = world.getServer().computeOnServer(server -> ShowcaseScene.site(server.overworld(), cx, cz));
		int base = scene.base();
		world.getServer().runOnServer(server -> scene.castle("waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs"));
		context.runOnClient(mc -> {
			Settings.get().radarNeutral = false;
			Settings.get().radarPassive = false;
			Settings.get().radarItems = false;
			Settings.get().portalMarkers = false;
		});
		world.getServer().runCommand("time set noon");
		int px = cx - 10;
		int pz = cz + 34;
		int y = base + 1;
		world.getServer().runCommand("fill " + (px - 3) + " " + (y - 1) + " " + (pz - 3) + " " + (px + 12) + " " + (y - 1) + " " + (pz + 3) + " stone_bricks");
		world.getServer().runCommand("fill " + (px - 3) + " " + y + " " + (pz - 3) + " " + (px + 12) + " " + (y + 7) + " " + (pz + 3) + " air");
		netherPortal(world, px, y, pz, 2, 3);
		int ex = px + 9;
		world.getServer().runCommand("fill " + (ex - 2) + " " + (y - 1) + " " + (pz - 2) + " " + (ex + 2) + " " + (y - 1) + " " + (pz + 2) + " end_portal_frame[eye=true]");
		world.getServer().runCommand("fill " + (ex - 1) + " " + (y - 1) + " " + (pz - 1) + " " + (ex + 1) + " " + (y - 1) + " " + (pz + 1) + " end_portal");
		world.getServer().runCommand("gamemode spectator @a");
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 92.5) + " 180 -4");
		context.waitTicks(60);
		context.setScreen(MapScreen::new);
		context.getInput().setCursorPos(1300, 30);
		double v = Projection.v(base + 12, cz + 20);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 3.2, TileKey.SURFACE));
		settle(context, 200);
		context.takeScreenshot("portal-pins-off");
		context.runOnClient(mc -> Settings.get().portalMarkers = true);
		settle(context, 5);
		context.takeScreenshot("portal-pins-on");
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 1.0, TileKey.SURFACE));
		settle(context, 80);
		context.takeScreenshot("portal-pins-far");
		context.setScreen(() -> null);
		context.takeScreenshot("portal-pins-hud");
		context.setScreen(() -> new dev.diorama.waypoint.WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("portal-pins-list");
		context.setScreen(() -> null);
	}

	private static void settle(ClientGameTestContext context, int minTicks) {
		context.waitTicks(minTicks);
		try (WorldPause paused = WorldPause.pause(context)) {
			int quiet = 0;
			for (int i = 0; i < 1200 && quiet < 30; i++) {
				context.waitTick();
				boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
				quiet = baking ? 0 : quiet + 1;
			}
		}
	}
}
