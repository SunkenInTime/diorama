package dev.diorama.test;

import dev.diorama.MinimapHud;
import dev.diorama.Settings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The minimap and the boss bars midway across the top of the screen. Placed top-centre it sits
 * below the lowest bar the game draws, one or three; with the bars gone it's back at the top. In a
 * top corner, beside the bars, it stays where it is, and from the side it moves down only once it
 * would reach over the end of a bar. The bars are the server's own, from {@code /bossbar}, which
 * draw exactly as a wither's or a raid's do.
 */
final class BossBarTest {
	private static final int MARGIN = 6;
	/** A bar is 5 high and the first is drawn 12 down, each next one 19 further. */
	private static final int ONE_BAR = 12 + 5;
	private static final int THREE_BARS = 12 + 2 * 19 + 5;

	private BossBarTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		Object[] saved = context.computeOnClient(mc -> {
			Settings s = Settings.get();
			return new Object[] {s.minimapCorner, s.minimapOffsetX, s.minimapOffsetY, s.minimapCentered, s.minimapSize, s.minimap};
		});
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.setScreen(() -> null);
		context.getInput().resizeWindow(1280, 720);
		DioramaClientTest.setGuiScale(context, 3);
		world.getServer().runCommand("effect clear @a");
		// The rule's name before and since 26.1; the wrong one is refused and changes nothing.
		for (String rule : new String[] {"send_command_feedback", "sendCommandFeedback"}) {
			world.getServer().runCommand("gamerule " + rule + " false");
		}
		// Checked at the end, so a failing run still takes every picture.
		List<String> wrong = new ArrayList<>();
		try {
			place(context, Settings.Corner.TOP_LEFT, true, MARGIN);
			DioramaClientTest.settle(context, 20);
			expectTop(context, wrong, MARGIN, "top-centre with no boss bar");
			context.takeScreenshot("boss-bars-01-none");

			bar(world, "one", "Raid", "red", 60);
			context.waitTicks(10);
			context.takeScreenshot("boss-bars-02-one");
			expectTop(context, wrong, ONE_BAR + MARGIN, "top-centre under one boss bar");

			bar(world, "two", "Wither", "purple", 100);
			bar(world, "three", "Ender Dragon", "pink", 35);
			context.waitTicks(10);
			context.takeScreenshot("boss-bars-03-three");
			expectTop(context, wrong, THREE_BARS + MARGIN, "top-centre under three boss bars");

			// In the corner it's beside the bars, so it stays up.
			place(context, Settings.Corner.TOP_RIGHT, false, MARGIN);
			context.waitTicks(5);
			context.takeScreenshot("boss-bars-04-corner");
			expectTop(context, wrong, MARGIN, "in the top-right corner beside three boss bars");

			// From the left: up against the bars' end it stays, ten pixels over it it moves down.
			int width = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
			int beside = width / 2 - 91 - 2 - 96;
			place(context, Settings.Corner.TOP_LEFT, false, beside);
			context.waitTicks(5);
			context.takeScreenshot("boss-bars-05-beside");
			expectTop(context, wrong, MARGIN, "left of three boss bars, touching their end");
			place(context, Settings.Corner.TOP_LEFT, false, beside + 10);
			context.waitTicks(5);
			context.takeScreenshot("boss-bars-06-over-the-end");
			expectTop(context, wrong, THREE_BARS + MARGIN, "ten pixels over the end of three boss bars");

			// Hidden bars leave the top free again.
			place(context, Settings.Corner.TOP_LEFT, true, MARGIN);
			for (String id : new String[] {"one", "two", "three"}) {
				world.getServer().runCommand("bossbar set diorama:" + id + " visible false");
			}
			context.waitTicks(10);
			context.takeScreenshot("boss-bars-07-hidden");
			expectTop(context, wrong, MARGIN, "top-centre once the boss bars are hidden");
			if (!wrong.isEmpty()) {
				throw new AssertionError(String.join("; ", wrong));
			}
			System.out.println("[diorama-test] boss bars: the minimap stayed clear of one and three bars");
		} finally {
			for (String rule : new String[] {"send_command_feedback", "sendCommandFeedback"}) {
				world.getServer().runCommand("gamerule " + rule + " true");
			}
			for (String id : new String[] {"one", "two", "three"}) {
				world.getServer().runCommand("bossbar remove diorama:" + id);
			}
			context.getInput().resizeWindow(1600, 900);
			DioramaClientTest.setGuiScale(context, guiScale);
			context.runOnClient(mc -> {
				Settings s = Settings.get();
				s.minimapCorner = (Settings.Corner) saved[0];
				s.minimapOffsetX = (int) saved[1];
				s.minimapOffsetY = (int) saved[2];
				s.minimapCentered = (boolean) saved[3];
				s.minimapSize = (int) saved[4];
				s.minimap = (boolean) saved[5];
				s.save();
			});
		}
	}

	private static void bar(TestSingleplayerContext world, String id, String name, String color, int value) {
		world.getServer().runCommand("bossbar add diorama:" + id + " \"" + name + "\"");
		world.getServer().runCommand("bossbar set diorama:" + id + " color " + color);
		world.getServer().runCommand("bossbar set diorama:" + id + " value " + value);
		world.getServer().runCommand("bossbar set diorama:" + id + " players @a");
	}

	private static void place(ClientGameTestContext context, Settings.Corner corner, boolean centered, int offsetX) {
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimap = true;
			s.minimapSize = 96;
			s.minimapCorner = corner;
			s.minimapCentered = centered;
			s.minimapOffsetX = offsetX;
			s.minimapOffsetY = MARGIN;
		});
	}

	private static void expectTop(ClientGameTestContext context, List<String> wrong, int y, String what) {
		String problem = context.computeOnClient(mc -> {
			int[] box = MinimapHud.shown();
			if (box == null) {
				return "the minimap wasn't drawn " + what;
			}
			return box[1] == y ? "" : "placed " + what + ", the minimap's top should be at " + y + ", got " + Arrays.toString(box) + " on a " + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight() + " screen";
		});
		if (!problem.isEmpty()) {
			wrong.add(problem);
		}
	}
}
