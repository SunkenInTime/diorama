package dev.diorama.test;

import dev.diorama.MapSession;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointBeams;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.WorldProjection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Beams on waypoints in the world (opt-in: {@code waypoint-beams}): which waypoints get one under
 * each choice of the setting, and what hides one (a wall, a roof, the ground over a waypoint, the
 * Nether's rock) and what doesn't (glass), read off the screen where each beam stands; and pictures
 * by day and night, from close by, looking up one, and with a hundred and twenty waypoints.
 */
final class WaypointBeamsTest {
	private static final String OVERWORLD = "minecraft:overworld";
	/** A pixel this near a beam's colour is in a beam. */
	private static final int IN_BEAM = 70;

	private WaypointBeamsTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("difficulty peaceful");
		if (context.computeOnClient(mc -> mc.player.isSpectator())) {
			throw new AssertionError("Run waypoint-beams without showcase or icon-waypoints: they leave the player spectating");
		}
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos spawn = context.computeOnClient(mc -> mc.player.blockPosition());
		// A lookout over the trees, facing north and a little down, as from a hilltop: the beams ahead
		// stand against the land and the sky both.
		BlockPos at = new BlockPos(spawn.getX(), surface(context, spawn.getX(), spawn.getZ()) + 16, spawn.getZ());
		stand(world, at.getX(), at.getY(), at.getZ(), 4);
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);

		Settings.Beams before = context.computeOnClient(mc -> Settings.get().waypointBeams);
		Waypoint camp = on(context, "Camp", at.getX(), at.getZ() - 45, 0xFFF76B15);
		camp.kind = Waypoint.Kind.TEMPORARY;
		Waypoint death = on(context, "Death", at.getX() - 40, at.getZ() - 140, 0xFFE5484D);
		death.kind = Waypoint.Kind.DEATH;
		Waypoint home = on(context, "Home", at.getX() + 25, at.getZ() - 80, 0xFFD6409F);
		// Past where the land is loaded, at the lookout's own height.
		Waypoint farm = new Waypoint("Iron Farm", at.getX() - 150, at.getY() - 16, at.getZ() - 600, OVERWORLD, 0xFFFFFFFF);
		Waypoint village = new Waypoint("Village", at.getX() + 420, at.getY() - 16, at.getZ() - 1400, OVERWORLD, 0xFFFFC53D);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			for (Waypoint waypoint : new Waypoint[] {camp, death, home, farm, village}) {
				store.add(waypoint);
			}
		});

		BufferedImage off = shot(context, Settings.Beams.OFF, "beams-day-off");
		BufferedImage temporary = shot(context, Settings.Beams.TEMPORARY, "beams-day-temporary");
		BufferedImage all = shot(context, Settings.Beams.ALL, "beams-day-all");
		// Points up each beam, above its pin.
		int[][] onCamp = points(context, camp, off);
		int[][] onDeath = points(context, death, off);
		int[][] onHome = points(context, home, off);
		expect(off, onCamp, camp, false, "off, the temporary waypoint");
		expect(off, onDeath, death, false, "off, the death marker");
		expect(off, onHome, home, false, "off, the ordinary waypoint");
		expect(temporary, onCamp, camp, true, "temporary and deaths, the temporary waypoint");
		expect(temporary, onDeath, death, true, "temporary and deaths, the death marker");
		expect(temporary, onHome, home, false, "temporary and deaths, the ordinary waypoint");
		expect(all, onCamp, camp, true, "all, the temporary waypoint");
		expect(all, onDeath, death, true, "all, the death marker");
		expect(all, onHome, home, true, "all, the ordinary waypoint");

		// A waypoint past the distance limit has no beam, one inside it has, and a temporary one keeps
		// its beam at any distance, as it keeps its pin. Home is about 85 blocks off.
		context.runOnClient(mc -> Settings.get().waypointDistance = 64);
		BufferedImage limited = shot(context, Settings.Beams.ALL, "beams-day-limited");
		expect(limited, onHome, home, false, "all and a distance of 64, the ordinary waypoint past it");
		expect(limited, onDeath, death, true, "all and a distance of 64, the death marker");
		context.runOnClient(mc -> Settings.get().waypointDistance = 128);
		expect(shot(context, Settings.Beams.ALL, "beams-day-limit-128"), onHome, home, true, "all and a distance of 128, the ordinary waypoint inside it");
		// A hidden waypoint has none.
		context.runOnClient(mc -> {
			Settings.get().waypointDistance = 0;
			home.visible = false;
		});
		expect(shot(context, Settings.Beams.ALL, "beams-day-hidden"), onHome, home, false, "all, a hidden waypoint");
		context.runOnClient(mc -> home.visible = true);

		// A clearing from Home towards the player, flat and with no tree over it.
		int floor = home.y - 1;
		fill(world, home.x - 4, floor + 1, home.z - 3, home.x + 4, floor + 40, home.z + 26, "air");
		fill(world, home.x - 4, floor, home.z - 3, home.x + 4, floor, home.z + 26, "grass_block");
		// From close by a beam fades, and is gone at the waypoint; looking up one, it narrows into the sky.
		for (int away : new int[] {20, 8, 5, 2}) {
			String spot = "tp @a " + (home.x + 0.5) + " " + (floor + 1) + " " + (home.z + away + 0.5) + " 180 ";
			world.getServer().runCommand(spot + "0");
			context.waitTicks(220);
			context.takeScreenshot("beams-near-" + away);
			if (away == 20) {
				// Looking nearly straight up it: the beam still shows, right to where it leaves the screen.
				world.getServer().runCommand(spot + "-80");
				context.waitTicks(220);
				BufferedImage up = shot(context, Settings.Beams.ALL, "beams-look-up");
				expect(up, points(context, home, up, 24, 160, 4), home, true, "looking up, the ordinary waypoint");
			}
		}

		// Blocks in front of a beam hide it, as they hide a beacon's; its pin still shows through them.
		// The player stands 20 blocks from Home, looking a little up.
		world.getServer().runCommand("tp @a " + (home.x + 0.5) + " " + (floor + 1) + " " + (home.z + 20.5) + " 180 -15");
		// A wall six high halfway to it: the beam shows above the wall, from about 9 blocks up it.
		fill(world, home.x - 4, floor + 1, home.z + 10, home.x + 4, floor + 6, home.z + 10, "stone");
		context.waitTicks(220);
		BufferedImage wall = shot(context, Settings.Beams.ALL, "beams-wall");
		expect(wall, points(context, home, wall, 3, 7, 1), home, false, "behind a wall, the foot of the beam");
		expect(wall, points(context, home, wall, 12, 22, 2), home, true, "behind a wall, the beam above it");
		// A bridge one block thick across the view hides only the stretch behind it, about 5.2 to 7.7
		// blocks up the beam: it shows under the bridge and over it.
		fill(world, home.x - 4, floor + 1, home.z + 10, home.x + 4, floor + 6, home.z + 10, "air");
		fill(world, home.x - 4, floor + 5, home.z + 10, home.x + 4, floor + 5, home.z + 10, "stone");
		context.waitTicks(220);
		BufferedImage bridge = shot(context, Settings.Beams.ALL, "beams-bridge");
		expect(bridge, points(context, home, bridge, 6, 7, 1, 2), home, false, "behind a bridge, the stretch of beam it covers");
		expect(bridge, points(context, home, bridge, 3, 4, 1, 2), home, true, "behind a bridge, the beam under it");
		expect(bridge, points(context, home, bridge, 10, 22, 2), home, true, "behind a bridge, the beam above it");
		fill(world, home.x - 4, floor + 5, home.z + 10, home.x + 4, floor + 5, home.z + 10, "air");
		// A closed door hides it from the front; a fence doesn't.
		world.getServer().runCommand("setblock " + home.x + " " + (floor + 1) + " " + (home.z + 19) + " minecraft:iron_door[half=lower,facing=south]");
		world.getServer().runCommand("setblock " + home.x + " " + (floor + 2) + " " + (home.z + 19) + " minecraft:iron_door[half=upper,facing=south]");
		context.waitTicks(220);
		BufferedImage door = shot(context, Settings.Beams.ALL, "beams-door");
		expect(door, points(context, home, door, 3, 5, 1), home, false, "behind a closed door, the foot of the beam");
		fill(world, home.x, floor + 1, home.z + 19, home.x, floor + 2, home.z + 19, "air");
		fill(world, home.x - 1, floor + 1, home.z + 19, home.x + 1, floor + 2, home.z + 19, "oak_fence");
		context.waitTicks(40);
		BufferedImage fence = shot(context, Settings.Beams.ALL, "beams-fence");
		expect(fence, points(context, home, fence, 3, 7, 1), home, true, "behind a fence, the foot of the beam");
		fill(world, home.x - 1, floor + 1, home.z + 19, home.x + 1, floor + 2, home.z + 19, "air");
		// Glass lets it through.
		fill(world, home.x - 4, floor + 1, home.z + 10, home.x + 4, floor + 6, home.z + 10, "glass");
		context.waitTicks(220);
		BufferedImage glass = shot(context, Settings.Beams.ALL, "beams-glass");
		expect(glass, points(context, home, glass, 3, 7, 1), home, true, "behind glass, the foot of the beam");
		fill(world, home.x - 4, floor + 1, home.z + 10, home.x + 4, floor + 6, home.z + 10, "air");
		// Under a roof, as in a cave or a house: no beam at all.
		world.getServer().runCommand("fill " + (home.x - 2) + " " + (floor + 1) + " " + (home.z + 18) + " " + (home.x + 2) + " " + (floor + 5) + " " + (home.z + 22) + " minecraft:stone hollow");
		world.getServer().runCommand("setblock " + (home.x + 1) + " " + (floor + 2) + " " + (home.z + 21) + " minecraft:torch");
		context.waitTicks(220);
		BufferedImage roof = shot(context, Settings.Beams.ALL, "beams-under-a-roof");
		expect(roof, points(context, home, roof, 3, 22, 2), home, false, "under a roof, the beam");
		fill(world, home.x - 2, floor + 1, home.z + 18, home.x + 2, floor + 5, home.z + 22, "air");
		// A waypoint 30 blocks under the ground: its beam shows from where it comes out of the ground.
		Waypoint mine = new Waypoint("Mine", home.x + 3, floor - 30, home.z, OVERWORLD, 0xFFF76B15);
		context.runOnClient(mc -> {
			home.visible = false;
			WaypointStore.get().add(mine);
		});
		context.waitTicks(220);
		BufferedImage under = shot(context, Settings.Beams.ALL, "beams-waypoint-underground");
		expect(under, points(context, mine, under, 34, 50, 2), mine, true, "a waypoint under the ground, the beam above the ground");
		context.runOnClient(mc -> {
			home.visible = true;
			WaypointStore.get().remove(mine);
		});

		stand(world, at.getX(), at.getY(), at.getZ(), 4);
		world.getServer().runCommand("time set midnight");
		context.waitTicks(220);
		shot(context, Settings.Beams.TEMPORARY, "beams-night-temporary");
		BufferedImage night = shot(context, Settings.Beams.ALL, "beams-night-all");
		expect(night, onHome, home, true, "at night, the ordinary waypoint");
		world.getServer().runCommand("time set noon");
		context.waitTicks(220);

		// A world full of waypoints, every one with a beam.
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			Random random = new Random(151);
			for (int i = 0; i < 120; i++) {
				double distance = i < 12 ? 30 + random.nextDouble() * 200 : 260 + random.nextDouble() * 1500;
				double angle = random.nextDouble() * Math.PI * 2;
				int x = at.getX() + (int) Math.round(Math.cos(angle) * distance);
				int z = at.getZ() + (int) Math.round(Math.sin(angle) * distance);
				store.add(new Waypoint("W" + i, x, at.getY() + random.nextInt(40) - 20, z, OVERWORLD, Waypoint.PALETTE[random.nextInt(Waypoint.PALETTE.length)]));
			}
		});
		// Each frame looks at a few beams, so a hundred take a moment to all be seen.
		context.runOnClient(mc -> Settings.get().waypointBeams = Settings.Beams.ALL);
		context.waitTicks(400);
		shot(context, Settings.Beams.ALL, "beams-clutter-all");
		shot(context, Settings.Beams.TEMPORARY, "beams-clutter-temporary");

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(3));
		// Off the buttons, so no tooltip covers them.
		context.getInput().setCursorPos(1500, 880);
		context.waitTicks(5);
		context.takeScreenshot("beams-settings");
		context.setScreen(() -> null);

		// The Nether, in a tunnel under a roof of netherrack: the beam ends at the roof.
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0.5 70 0.5 180 -15");
		context.waitFor(mc -> mc.level.dimension() == Level.NETHER && MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		context.waitTicks(40);
		world.getServer().runCommand("execute in minecraft:the_nether run fill -5 69 -45 5 69 5 minecraft:netherrack");
		world.getServer().runCommand("execute in minecraft:the_nether run fill -5 70 -45 5 80 5 minecraft:air");
		world.getServer().runCommand("execute in minecraft:the_nether run fill -5 81 -45 5 83 5 minecraft:netherrack");
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0.5 70 0.5 180 -15");
		Waypoint portal = new Waypoint("Portal", 0, 69, -30, "minecraft:the_nether", 0xFFF76B15);
		portal.kind = Waypoint.Kind.TEMPORARY;
		context.runOnClient(mc -> WaypointStore.get().add(portal));
		context.waitTicks(220);
		BufferedImage nether = shot(context, Settings.Beams.TEMPORARY, "beams-nether");
		expect(nether, points(context, portal, nether, 3, 8, 1), portal, true, "in the Nether, the beam under the roof");
		expect(nether, points(context, portal, nether, 14, 30, 2), portal, false, "in the Nether, the beam above the roof");
		world.getServer().runCommand("execute in minecraft:overworld run tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 4");
		context.waitFor(mc -> mc.level.dimension() == Level.OVERWORLD && MapSession.get() != null, 2400);
		context.waitTicks(100);

		context.runOnClient(mc -> {
			Settings.get().waypointBeams = before;
			Settings.get().save();
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
		});
	}

	private static BufferedImage shot(ClientGameTestContext context, Settings.Beams beams, String name) {
		context.runOnClient(mc -> Settings.get().waypointBeams = beams);
		context.waitTicks(10);
		Path file = context.takeScreenshot(name);
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A waypoint on the ground at (x, z), under any tree. */
	private static Waypoint on(ClientGameTestContext context, String name, int x, int z, int color) {
		return new Waypoint(name, x, ground(context, x, z) + 1, z, OVERWORLD, color);
	}

	/** The height of the ground at (x, z), which has to be loaded: the highest block that isn't part of a tree. */
	private static int ground(ClientGameTestContext context, int x, int z) {
		return context.computeOnClient(mc -> {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
			while (mc.level.getBlockState(pos).isAir() || mc.level.getBlockState(pos).is(BlockTags.LEAVES) || mc.level.getBlockState(pos).is(BlockTags.LOGS)) {
				pos.move(0, -1, 0);
			}
			return pos.getY();
		});
	}

	/** The height of the highest block at (x, z), which has to be loaded. */
	static int surface(ClientGameTestContext context, int x, int z) {
		return context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)) - 1;
	}

	/** Stands the player in the block (x, y, z) on a block nobody can see, facing north at {@code pitch}. */
	static void stand(TestSingleplayerContext world, int x, int y, int z, int pitch) {
		world.getServer().runCommand("setblock " + x + " " + (y - 1) + " " + z + " minecraft:barrier");
		world.getServer().runCommand("tp @a " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 180 " + pitch);
	}

	/** Where on a screenshot points up a waypoint's beam are, above its trees: those that are on it. */
	static int[][] points(ClientGameTestContext context, Waypoint waypoint, BufferedImage shot) {
		return points(context, waypoint, shot, 16, 48, 4);
	}

	/** The same from {@code lowest} to {@code highest} blocks above the beam's foot, every {@code step}. */
	static int[][] points(ClientGameTestContext context, Waypoint waypoint, BufferedImage shot, int lowest, int highest, int step) {
		return points(context, waypoint, shot, lowest, highest, step, 3);
	}

	/** The same, where as few as {@code least} points on screen will do. */
	static int[][] points(ClientGameTestContext context, Waypoint waypoint, BufferedImage shot, int lowest, int highest, int step, int least) {
		List<int[]> points = new ArrayList<>();
		for (int up = lowest; up <= highest; up += step) {
			double y = waypoint.y + 1.0 + up;
			float[] screen = context.computeOnClient(mc -> WorldProjection.project(waypoint.x + 0.5, y, waypoint.z + 0.5, shot.getWidth(), shot.getHeight()));
			if (screen != null && screen[0] >= 0 && screen[1] >= 0 && screen[0] < shot.getWidth() && screen[1] < shot.getHeight()) {
				points.add(new int[] {Math.min(shot.getWidth() - 1, Math.round(screen[0])), Math.min(shot.getHeight() - 1, Math.round(screen[1]))});
			}
		}
		if (points.size() < least) {
			throw new AssertionError("Too little of the beam of " + waypoint.name + " would be on screen: " + points.size() + " points");
		}
		return points.toArray(int[][]::new);
	}

	private static void fill(TestSingleplayerContext world, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
		world.getServer().runCommand("fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " minecraft:" + block);
	}

	/**
	 * A beam shows at most of its points: another waypoint's pin or label may cover a few. Without one,
	 * none of them is the beam's colour.
	 */
	static void expect(BufferedImage shot, int[][] points, Waypoint waypoint, boolean beam, String what) {
		int colour = WaypointBeams.coreColor(waypoint.color & 0xFFFFFF);
		int found = 0;
		StringBuilder seen = new StringBuilder();
		for (int[] at : points) {
			// A far beam is a few pixels wide, and the point is rounded to one: the nearest match close by counts.
			double away = Double.MAX_VALUE;
			int nearest = 0;
			for (int x = Math.max(0, at[0] - 4); x <= Math.min(shot.getWidth() - 1, at[0] + 4); x++) {
				int rgb = shot.getRGB(x, at[1]);
				double d = Math.sqrt(square((rgb >> 16 & 0xFF) - (colour >> 16 & 0xFF)) + square((rgb >> 8 & 0xFF) - (colour >> 8 & 0xFF)) + square((rgb & 0xFF) - (colour & 0xFF)));
				if (d < away) {
					away = d;
					nearest = rgb;
				}
			}
			if (away < IN_BEAM) {
				found++;
			}
			seen.append(" #").append(Integer.toHexString(nearest & 0xFFFFFF)).append(" at ").append(at[0]).append(',').append(at[1]);
		}
		if (beam ? found * 3 < points.length * 2 : found > 0) {
			throw new AssertionError("With beams " + what + (beam ? " should stand in a beam" : " should have no beam") + ": " + found + " of " + points.length
				+ " points up it are the beam's colour #" + Integer.toHexString(colour) + ":" + seen);
		}
	}

	private static double square(double v) {
		return v * v;
	}
}
