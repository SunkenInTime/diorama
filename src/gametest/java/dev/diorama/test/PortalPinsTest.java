package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.WorldKey;
import dev.diorama.bake.TileKey;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Portal pins. {@link #checks} runs by default ({@code portal-pins}): one pin on the biggest Nether
 * portal, a pin each on an End portal and a gateway, and what happens to pins as the setting turns,
 * as the player deletes one and as a portal goes out; portals in far chunks read from the save;
 * nothing under fog of war. {@link #photos} is opt-in ({@code portal-photos}): {@link ShowcaseScene}'s
 * castle with a portal beside it, for the PR's pictures.
 */
final class PortalPinsTest {
	private static final String OVERWORLD = "minecraft:overworld";
	/** Far from anything else the tests build, and from the player. */
	private static final int SAVED_X = -3200;
	private static final int SAVED_Z = -3200;
	/** How long the map may take: a change waits a moment, then a chunk comes up in its queue. */
	private static final int PIN_TICKS = 200;

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

		int ex = at.getX() + 8;
		int ez = at.getZ() + 8;
		world.getServer().runCommand("fill " + ex + " " + y + " " + ez + " " + (ex + 2) + " " + y + " " + (ez + 2) + " end_portal");
		expectPins(context, Waypoint.Kind.END_PORTAL, 1, "a three by three End portal");
		world.getServer().runCommand("setblock " + (ex + 20) + " " + y + " " + ez + " end_gateway");
		expectPins(context, Waypoint.Kind.END_GATEWAY, 1, "a gateway");

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
		context.runOnClient(mc -> {
			Waypoint renamed = pinNowNear(second);
			renamed.name = "Home gate";
			WaypointStore.get().changed();
		});
		frameBroken(world, second, y, z);
		context.waitTicks(40);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "breaking a renamed pin's portal");

		// Nothing the player hasn't seen shows under fog of war.
		context.runOnClient(mc -> ServerRules.setAlwaysFogOfWar(true));
		netherPortal(world, third, y, z, 2, 3);
		context.waitTicks(40);
		context.runOnClient(mc -> ServerRules.setAlwaysFogOfWar(false));
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 2, "a portal lit under fog of war");

		// Two portals sharing a pin: when the one it stands on goes, it moves to the other.
		int pair = at.getX() - 110;
		netherPortal(world, pair, y, z, 2, 3);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "the first of a close pair");
		netherPortal(world, pair + 20, y, z, 2, 3);
		context.waitTicks(40);
		expectPins(context, Waypoint.Kind.NETHER_PORTAL, 3, "the second of a close pair");
		frameBroken(world, pair, y, z);
		context.waitFor(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.NETHER_PORTAL && Math.abs(w.x - (pair + 20)) < 8), PIN_TICKS);
		frameBroken(world, pair + 20, y, z);

		fromTheSave(context, world);

		// Nothing left in the air over spawn for the tests after this one.
		world.getServer().runCommand("fill " + x + " " + y + " " + z + " " + (x + 22) + " " + (y + 22) + " " + z + " air");
		world.getServer().runCommand("fill " + ex + " " + y + " " + ez + " " + (ex + 20) + " " + y + " " + (ez + 2) + " air");
		world.getServer().runCommand("setblock " + (second + 1) + " " + (y + 8) + " " + z + " air");
		frameBroken(world, third, y, z);
		context.waitTicks(40);
		context.runOnClient(mc -> Settings.get().portalMarkers = false);
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

	/** A frame of obsidian and the lit portal in it, with its inside {@code width} by {@code height}, along X. */
	private static void netherPortal(TestSingleplayerContext world, int x, int y, int z, int width, int height) {
		world.getServer().runCommand("fill " + x + " " + y + " " + z + " " + (x + width + 1) + " " + (y + height + 1) + " " + z + " obsidian");
		world.getServer().runCommand("fill " + (x + 1) + " " + (y + 1) + " " + z + " " + (x + width) + " " + (y + height) + " " + z + " nether_portal[axis=x]");
	}

	private static void frameBroken(TestSingleplayerContext world, int x, int y, int z) {
		world.getServer().runCommand("fill " + x + " " + y + " " + z + " " + (x + 3) + " " + (y + 4) + " " + z + " air");
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
