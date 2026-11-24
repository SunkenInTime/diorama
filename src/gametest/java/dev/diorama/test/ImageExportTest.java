package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.ExportScreen;
import dev.diorama.MapExport;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.data.TileStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Exporting the map as an image, through the screen as a player does: the Export button, the
 * Area box dragged bigger, panning beside it, a detail button, Save image, then Whole map, the
 * same from the cave view, and the box's corners typed. Each saved PNG is opened and checked
 * against what the screen said it would be.
 */
final class ImageExportTest {
	private ImageExportTest() {
	}

	/** A drawn line through the player and a note under it, in colours the test world's ground doesn't have. */
	private static final int LINE = 0xFF00FF;
	private static final int NOTE = 0xFFFF55;
	/** A note in Arabic, which the game shapes and reverses in a right-to-left language. */
	private static final int ARABIC = 0xFF55FF;
	/** A note in characters only Unifont has. */
	private static final int CJK = 0x55FFFF;

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		List<Drawing> made = new java.util.ArrayList<>();
		double[] drawn = context.computeOnClient(mc -> {
			double u = mc.player.getX();
			double v = Projection.v(mc.player.getY(), mc.player.getZ());
			String dimension = mc.level.dimension().identifier().toString();
			made.add(new Drawing(Drawing.Kind.STROKE, dimension, 0xFF000000 | LINE, new double[] {u - 20, v, u + 20, v}, ""));
			made.add(new Drawing(Drawing.Kind.NOTE, dimension, 0xFF000000 | NOTE, new double[] {u, v + 30}, "Hi"));
			made.add(new Drawing(Drawing.Kind.NOTE, dimension, 0xFF000000 | CJK, new double[] {u, v - 30}, "\u5730\u56fe"));
			made.forEach(DrawingStore.get()::add);
			return new double[] {u, v};
		});
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()), 1.0, TileKey.SURFACE));
		settle(context);

		// The Export button above the draw and zoom buttons, with its tooltip, opens the export screen.
		double[] button = context.computeOnClient(mc -> {
			double[] c = centre(((MapScreen) mc.gui.screen()).exportButton());
			double scale = mc.getWindow().getGuiScale();
			return new double[] {c[0] * scale, c[1] * scale};
		});
		context.getInput().setCursorPos(button[0], button[1]);
		context.waitTicks(10);
		context.takeScreenshot("image-export-00-button");
		click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), false);
		context.waitTicks(3);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof ExportScreen)) {
			throw new AssertionError("The Export button didn't open the export screen");
		}
		settle(context);
		context.takeScreenshot("image-export-01-area");

		// Dragging the box's bottom-right corner makes it bigger; dragging outside it pans and leaves it alone.
		double[] before = box(context);
		drag(context, before[2], before[3], 40, 30);
		double[] grown = box(context);
		if (!(grown[2] > before[2] + 30 && grown[3] > before[3] + 20 && Math.abs(grown[0] - before[0]) < 1)) {
			throw new AssertionError("Dragging the box's corner didn't resize it: " + List.of(before[2], before[3]) + " -> " + List.of(grown[2], grown[3]));
		}
		ImageExport.Area beforePan = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		double[] outside = {grown[2] + 20, grown[1] + 10};
		drag(context, outside[0], outside[1], -30, 0);
		double[] panned = box(context);
		ImageExport.Area afterPan = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		if (!(Math.abs(panned[0] - (grown[0] - 30)) < 2)) {
			throw new AssertionError("Dragging beside the box didn't pan the map under it");
		}
		if (!beforePan.equals(afterPan)) {
			// Moving the box would also shift it on screen; panning leaves the ground it covers alone.
			throw new AssertionError("Dragging beside the box moved what it saves: " + beforePan + " -> " + afterPan);
		}

		// 1 px per block, then Save image.
		click(context, mc -> centre(((ExportScreen) mc.gui.screen()).detailButton(2)), false);
		ImageExport.Area area = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		if (area == null || area.lod() != 2) {
			throw new AssertionError("The 1 px per block button didn't choose that detail: " + area);
		}
		context.takeScreenshot("image-export-02-area-resized");
		Path areaFile = save(context);
		checkImage(areaFile, area);
		// The drawings are in, ticked by default: the line through the player, the note's text under it.
		if (!lineAt(areaFile, area, drawn)) {
			throw new AssertionError("The drawn line isn't in " + areaFile.getFileName());
		}
		// Its text in its colour, and the game's shadow, a quarter of it, behind.
		if (countNear(areaFile, area, drawn[0], drawn[1] + 30, NOTE) < 40 || countNear(areaFile, area, drawn[0], drawn[1] + 30, (NOTE & 0xFCFCFC) >> 2) < 10) {
			throw new AssertionError("The note isn't in " + areaFile.getFileName());
		}
		// Two Unifont characters are 8 GUI pixels across each; the game's box for a missing one is 5,
		// so two boxes span 22 export pixels and the real glyphs about 31.
		int[] cjk = spanNear(areaFile, area, drawn[0], drawn[1] - 30, CJK);
		if (cjk[0] < 30 || cjk[1] < 28) {
			throw new AssertionError("The note in Unifont characters isn't in " + areaFile.getFileName() + ": " + cjk[0] + " pixels over " + cjk[1]);
		}

		// Whole map: everything explored, boxed with fog round it.
		click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), false);
		context.waitTicks(3);
		click(context, mc -> {
			int[] tab = ((ExportScreen) mc.gui.screen()).tabCenter(1);
			return new double[] {tab[0], tab[1]};
		}, false);
		context.waitFor(mc -> mc.gui.screen() instanceof ExportScreen screen && screen.plannedArea() != null, 600);
		// Untick Drawings: this one is the ground alone.
		click(context, mc -> new double[] {16, 114}, false);
		settle(context);
		context.takeScreenshot("image-export-03-whole");
		ImageExport.Area whole = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		coversMappedChunks(context, world, whole);
		Path wholeFile = save(context);
		checkImage(wholeFile, whole);
		matchesTiles(context, wholeFile, whole);
		if (lineAt(wholeFile, whole, drawn)) {
			throw new AssertionError("Drawings were unticked, but the line is in " + wholeFile.getFileName());
		}
		// Only this test's drawings: other sections' stay as they left them.
		context.runOnClient(mc -> DrawingStore.get().batch(() -> made.forEach(DrawingStore.get()::remove)));
		noteMatchesMap(context, drawn);
		context.setScreen(() -> null);
		caveSlice(context);
		corners(context, world);
	}

	/**
	 * The box's corners typed in the sidebar (#454). On a level floor at the height the corners are
	 * measured at, an area typed as two corners saves an image of those blocks and no others: the
	 * floor is white inside the typed area with a red and a blue block in two of its corners, and
	 * lime all around it. Dragging the box writes its corners back into the fields.
	 */
	private static void corners(ClientGameTestContext context, TestSingleplayerContext world) {
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		// The tight case for the sidebar: 427 x 240 GUI pixels on the test window.
		DioramaClientTest.setGuiScale(context, 3);
		int[] player = context.computeOnClient(mc -> new int[] {mc.player.getBlockX(), mc.player.getBlockZ()});
		int x1 = player[0] + 24;
		int z1 = player[1] - 10;
		int x2 = x1 + 24;
		int z2 = z1 + 20;
		// Nothing over the floor, or south of it where higher ground would be drawn over it. Cleared in
		// slabs: a fill may change only so many blocks at once.
		for (int y = 64; y < 134; y += 10) {
			world.getServer().runCommand("fill " + (x1 - 4) + " " + y + " " + (z1 - 4) + " " + (x2 + 3) + " " + (y + 9) + " " + (z2 + 3 + 60) + " air");
		}
		world.getServer().runCommand("fill " + (x1 - 4) + " 63 " + (z1 - 4) + " " + (x2 + 3) + " 63 " + (z2 + 3) + " lime_concrete");
		world.getServer().runCommand("fill " + x1 + " 63 " + z1 + " " + (x2 - 1) + " 63 " + (z2 - 1) + " white_concrete");
		world.getServer().runCommand("setblock " + x1 + " 63 " + z1 + " red_concrete");
		world.getServer().runCommand("setblock " + (x2 - 1) + " 63 " + (z2 - 1) + " blue_concrete");
		// A drawing well away from the floor, so the sidebar has both its checkboxes above the fields.
		Drawing far = context.computeOnClient(mc -> {
			double v = Projection.v(64, z1 - 200);
			Drawing made = new Drawing(Drawing.Kind.STROKE, mc.level.dimension().identifier().toString(), 0xFF000000 | LINE, new double[] {x1, v, x2, v}, "");
			DrawingStore.get().add(made);
			return made;
		});
		double floorTop = Projection.v(64, z1);
		double floorBottom = Projection.v(64, z2);
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt((x1 + x2) / 2.0, (floorTop + floorBottom) / 2, 2.0, TileKey.SURFACE));
		settle(context);
		click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), false);
		context.waitTicks(3);
		settle(context);
		context.takeScreenshot("image-export-08-corners");

		// Dragging the box moves both corners by the same whole blocks: 20 GUI pixels are 10 blocks at this zoom.
		int[] before = typedCorners(context);
		double[] box = box(context);
		drag(context, (box[0] + box[2]) / 2, (box[1] + box[3]) / 2, 20, 0);
		int[] moved = typedCorners(context);
		if (moved[0] != before[0] + 10 || moved[2] != before[2] + 10 || moved[1] != before[1] || moved[3] != before[3]) {
			throw new AssertionError("Dragging the box 10 blocks east didn't write that into the corner fields: "
				+ java.util.Arrays.toString(before) + " -> " + java.util.Arrays.toString(moved));
		}

		// One corner typed a field at a time, the other pasted as Copy coordinates gives a place: x y z.
		DioramaClientTest.typeInto(context, 0, Integer.toString(x1));
		DioramaClientTest.typeInto(context, 1, Integer.toString(z1));
		// Pasted into the field as it stands, number and all: the paste is the corner, not part of the number.
		click(context, mc -> centre(((ExportScreen) mc.gui.screen()).cornerField(2)), false);
		context.runOnClient(mc -> ((ExportScreen) mc.gui.screen()).cornerField(2).insertText(x2 + " 71 " + z2));
		context.waitTicks(2);
		int[] typed = typedCorners(context);
		if (!java.util.Arrays.equals(typed, new int[] {x1, z1, x2, z2})) {
			throw new AssertionError("The corner fields read " + java.util.Arrays.toString(typed) + " after typing " + java.util.Arrays.toString(new int[] {x1, z1, x2, z2}));
		}
		// A field that isn't a number keeps Save image off until it's one again.
		context.getInput().typeChars("k");
		context.waitTicks(2);
		if (context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).saveButton().active)) {
			throw new AssertionError("Save image stayed on with a corner that isn't a number");
		}
		context.takeScreenshot("image-export-09-corners-invalid");
		context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		context.waitTicks(2);

		// The mouse on the heading: what the corners are measured on.
		double guiScale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		int headingY = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).cornerField(0).getY()) - 8;
		context.getInput().setCursorPos(40 * guiScale, headingY * guiScale);
		context.waitTicks(10);
		context.takeScreenshot("image-export-10-corners-typed");

		ImageExport.Area area = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		// The rows of pixels whose middles are on the typed blocks, four rows to a map unit.
		long top = (long) Math.ceil(floorTop * 4 - 0.5);
		long bottom = (long) Math.ceil(floorBottom * 4 - 0.5);
		ImageExport.Area expected = new ImageExport.Area(0, x1 * 4L, top, (x2 - x1) * 4, (int) (bottom - top));
		if (!expected.equals(area)) {
			throw new AssertionError("Typed corners " + x1 + " " + z1 + " to " + x2 + " " + z2 + " plan " + area + ", not " + expected);
		}
		if (area.width() != (x2 - x1) * 4 || area.x() != x1 * 4L) {
			throw new AssertionError("The image isn't the typed blocks across at 4 px a block: " + area);
		}
		Path file = save(context);
		BufferedImage image = read(file);
		if (image == null || image.getWidth() != area.width() || image.getHeight() != area.height()) {
			throw new AssertionError(file.getFileName() + " isn't the " + area.width() + "x" + area.height() + " the screen said");
		}
		// Every pixel is the floor inside the corners. A block's edge can fall inside a row of pixels,
		// which is then at least half the white floor: lime is what's mostly the floor outside.
		int lime = 0;
		int white = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int rgb = image.getRGB(x, y);
				int r = rgb >> 16 & 0xFF;
				int g = rgb >> 8 & 0xFF;
				int b = rgb & 0xFF;
				lime += g > r * 1.4 && g > b * 1.5 ? 1 : 0;
				white += Math.abs(r - g) < 24 && Math.abs(g - b) < 24 && r > 90 ? 1 : 0;
			}
		}
		int inner = image.getWidth() * image.getHeight();
		System.out.println("IMAGE-EXPORT corners " + file.getFileName() + " " + image.getWidth() + "x" + image.getHeight() + " lime " + lime + " white " + white + " of " + inner);
		if (lime > 0) {
			throw new AssertionError(file.getFileName() + " has " + lime + " pixels of the lime floor outside the typed corners");
		}
		if (white < inner * 0.9) {
			throw new AssertionError(file.getFileName() + " is the white floor inside the typed corners in only " + white + " of " + inner + " pixels");
		}
		// The red block is the first inside the first corner, the blue the last inside the second: 4 pixels across, 3 down.
		for (int i = 1; i <= 2; i++) {
			int first = image.getRGB(i, i);
			int last = image.getRGB(image.getWidth() - 1 - i, image.getHeight() - 1 - i);
			if (!((first >> 16 & 0xFF) > (first >> 8 & 0xFF) * 1.5 && (first >> 16 & 0xFF) > (first & 0xFF) * 1.5)) {
				throw new AssertionError(file.getFileName() + " doesn't start on the red block at the first typed corner: " + Integer.toHexString(first));
			}
			if (!((last & 0xFF) > (last >> 8 & 0xFF) * 1.5 && (last & 0xFF) > (last >> 16 & 0xFF) * 1.5)) {
				throw new AssertionError(file.getFileName() + " doesn't end on the blue block at the second typed corner: " + Integer.toHexString(last));
			}
		}

		context.runOnClient(mc -> DrawingStore.get().remove(far));
		context.setScreen(() -> null);
		DioramaClientTest.setGuiScale(context, scale);
	}

	/** The four corner fields as numbers: X and Z of one corner, then of the other. */
	private static int[] typedCorners(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			int[] typed = new int[4];
			for (int i = 0; i < 4; i++) {
				typed[i] = Integer.parseInt(((ExportScreen) mc.gui.screen()).cornerField(i).getValue());
			}
			return typed;
		});
	}

	/**
	 * The cave view has the Export button too (#257), and what it saves is the slice on the map:
	 * an Area image is compared with a screenshot of the map at the same scale, pixel for pixel,
	 * and Whole map saves as much of the slice as the map's chunks cover.
	 */
	private static void caveSlice(ClientGameTestContext context) {
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		// At GUI scale 2 and zoom 2 a screen pixel is a map pixel, as it is in an image at 4 px per block.
		DioramaClientTest.setGuiScale(context, 2);
		boolean radar = context.computeOnClient(mc -> Settings.get().radarOnMap);
		context.runOnClient(mc -> Settings.get().radarOnMap = false);
		int layer = Integer.getInteger("diorama.caveY", 24);
		// On whole map pixels, so the screen's and the image's line up.
		double[] at = context.computeOnClient(mc -> new double[] {Math.floor(mc.player.getX()), Math.round(Projection.v(layer - 6, mc.player.getZ()) * 4) / 4.0});
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(at[0], at[1], 2.0, layer));
		context.getInput().setCursorPos(0, 0);
		settle(context);
		BufferedImage map = read(context.takeScreenshot("image-export-05-cave-map"));
		if (!context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).exportButton().visible)) {
			throw new AssertionError("The cave view has no Export button");
		}
		int[] middle = context.computeOnClient(mc -> {
			var view = ((MapScreen) mc.gui.screen()).view();
			double g = mc.getWindow().getGuiScale();
			return new int[] {(int) Math.round(view.screenX(at[0]) * g), (int) Math.round(view.screenY(at[1]) * g)};
		});

		click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), false);
		context.waitTicks(3);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof ExportScreen)) {
			throw new AssertionError("The Export button didn't open the export screen from the cave view");
		}
		settle(context);
		context.takeScreenshot("image-export-06-cave-area");
		ImageExport.Area area = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		if (area == null || area.lod() != 0) {
			throw new AssertionError("The cave export didn't start at 4 px per block: " + area);
		}
		long started = System.nanoTime();
		Path areaFile = save(context);
		System.out.println("IMAGE-EXPORT cave area " + area + " took " + (System.nanoTime() - started) / 1_000_000 + " ms");
		BufferedImage image = checkCave(areaFile, area);
		// Left of the player's arrow, clear of the bars and buttons: the image against the map on screen.
		int span = 90;
		int sx = middle[0] - 130;
		int sy = middle[1];
		int ix = (int) (Math.round(at[0] * 4) - area.x()) - 130;
		int iy = (int) (Math.round(at[1] * 4) - area.y());
		int first = image.getRGB(ix - span, iy - span);
		long difference = 0;
		long spread = 0;
		int count = 0;
		for (int dy = -span; dy < span; dy++) {
			for (int dx = -span; dx < span; dx++) {
				int a = map.getRGB(sx + dx, sy + dy);
				int b = image.getRGB(ix + dx, iy + dy);
				for (int shift = 0; shift <= 16; shift += 8) {
					difference += Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF));
					spread += Math.abs((first >> shift & 0xFF) - (b >> shift & 0xFF));
				}
				count += 3;
			}
		}
		System.out.println("IMAGE-EXPORT cave area differs from the map on screen by " + (double) difference / count + "/255 a channel, its own spread " + (double) spread / count);
		if (spread / count < 6) {
			throw new AssertionError(areaFile.getFileName() + " is one flat colour beside the player: no cave in it?");
		}
		if (difference / count > 14) {
			throw new AssertionError(areaFile.getFileName() + " isn't the cave slice on the map: off by " + difference / count + "/255 a channel on average");
		}

		// Whole map: as much of the slice as the map's chunks cover, at 1 px per block.
		click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), false);
		context.waitTicks(3);
		click(context, mc -> {
			int[] tab = ((ExportScreen) mc.gui.screen()).tabCenter(1);
			return new double[] {tab[0], tab[1]};
		}, false);
		context.waitFor(mc -> mc.gui.screen() instanceof ExportScreen screen && screen.plannedArea() != null, 600);
		click(context, mc -> centre(((ExportScreen) mc.gui.screen()).detailButton(2)), false);
		settle(context);
		context.takeScreenshot("image-export-07-cave-whole");
		ImageExport.Area whole = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		double[] units = whole.units();
		if (whole.lod() != 2 || !(units[0] < at[0] - 64 && units[2] > at[0] + 64 && units[1] < at[1] - 48 && units[3] > at[1] + 48)) {
			throw new AssertionError("Whole map of the cave slice is " + whole + ", not the chunks around the player at 1 px per block");
		}
		started = System.nanoTime();
		Path wholeFile = save(context);
		System.out.println("IMAGE-EXPORT cave whole " + whole + " took " + (System.nanoTime() - started) / 1_000_000 + " ms");
		checkCave(wholeFile, whole);

		context.setScreen(() -> null);
		context.runOnClient(mc -> Settings.get().radarOnMap = radar);
		DioramaClientTest.setGuiScale(context, scale);
	}

	/** A cave image is the size the screen said and fully opaque: the cave's dark where nothing shows. */
	private static BufferedImage checkCave(Path file, ImageExport.Area area) {
		BufferedImage image = read(file);
		if (image == null || image.getWidth() != area.width() || image.getHeight() != area.height()) {
			throw new AssertionError(file.getFileName() + " is " + (image == null ? "unreadable" : image.getWidth() + "x" + image.getHeight())
				+ ", but the screen said " + area.width() + "x" + area.height());
		}
		Set<Integer> colours = new HashSet<>();
		for (int y = 0; y < image.getHeight(); y += 3) {
			for (int x = 0; x < image.getWidth(); x += 3) {
				int argb = image.getRGB(x, y);
				if ((argb >>> 24) != 0xFF) {
					throw new AssertionError(file.getFileName() + " has see-through pixels; where nothing shows should be the cave's dark");
				}
				colours.add(argb & 0xF0F0F0);
			}
		}
		if (colours.size() < 40) {
			throw new AssertionError(file.getFileName() + " has only " + colours.size() + " colours: no cave in it?");
		}
		System.out.println("IMAGE-EXPORT " + file.getFileName() + " " + image.getWidth() + "x" + image.getHeight() + " " + colours.size() + " colours");
		return image;
	}

	/**
	 * A note in the image looks as it does on the map: the same glyphs in the same order, in a
	 * right-to-left language too, where the game shapes Arabic and reverses the line. At GUI scale 2
	 * a GUI pixel is 2 screen pixels, as it's 2 pixels in an export, so the note's text in a
	 * screenshot of the map and in the image are compared pixel for pixel.
	 */
	private static void noteMatchesMap(ClientGameTestContext context, double[] at) {
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		String language = context.computeOnClient(mc -> mc.options.languageCode);
		DioramaClientTest.setGuiScale(context, 2);
		DioramaClientTest.reloadPacks(context, mc -> {
			mc.options.languageCode = "ar_sa";
			mc.getLanguageManager().setSelected("ar_sa");
		});
		double u = at[0];
		double v = at[1] + 60;
		Drawing note = context.computeOnClient(mc -> {
			Drawing made = new Drawing(Drawing.Kind.NOTE, mc.level.dimension().identifier().toString(), 0xFF000000 | ARABIC, new double[] {u, v},
				"\u0645\u0631\u062d\u0628\u0627 Diorama");
			DrawingStore.get().add(made);
			return made;
		});
		// Mob markers mustn't cover the note on the map.
		boolean radar = context.computeOnClient(mc -> Settings.get().radarOnMap);
		context.runOnClient(mc -> Settings.get().radarOnMap = false);
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u, v, 1.0, TileKey.SURFACE));
		context.getInput().setCursorPos(0, 0);
		settle(context);
		double[] onScreen = context.computeOnClient(mc -> {
			var view = ((MapScreen) mc.gui.screen()).view();
			double g = mc.getWindow().getGuiScale();
			return new double[] {view.screenX(u) * g, view.screenY(v) * g};
		});
		BufferedImage shot = read(context.takeScreenshot("image-export-04-arabic-note"));
		boolean[][] mapText = mask(shot, (int) onScreen[0], (int) onScreen[1], ARABIC);

		Set<Path> old = new HashSet<>(exports(context));
		ImageExport.Area area = ImageExport.Area.ofUnits(2, u - 80, v - 30, u + 80, v + 30);
		boolean started = context.computeOnClient(mc -> MapExport.start(MapSession.get().map().savedMap(), area, false, List.of(note)));
		if (!started) {
			throw new AssertionError("Another export was still running");
		}
		context.waitFor(mc -> MapExport.running() == null, 2400);
		Path file = exports(context).stream().filter(p -> !old.contains(p)).findFirst().orElseThrow();
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		boolean[][] imageText = mask(read(file), (int) Math.floor(u * perUnit - area.x()), (int) Math.floor(v * perUnit - area.y()), ARABIC);

		context.runOnClient(mc -> DrawingStore.get().remove(note));
		context.runOnClient(mc -> Settings.get().radarOnMap = radar);
		context.setScreen(() -> null);
		DioramaClientTest.reloadPacks(context, mc -> {
			mc.options.languageCode = language;
			mc.getLanguageManager().setSelected(language);
		});
		DioramaClientTest.setGuiScale(context, scale);

		int differ = 0;
		int ink = 0;
		if (mapText.length != imageText.length || mapText.length == 0 || mapText[0].length != imageText[0].length) {
			throw new AssertionError("The Arabic note's text is " + size(imageText) + " in the image but " + size(mapText) + " on the map");
		}
		for (int y = 0; y < mapText.length; y++) {
			for (int x = 0; x < mapText[0].length; x++) {
				ink += mapText[y][x] ? 1 : 0;
				differ += mapText[y][x] != imageText[y][x] ? 1 : 0;
			}
		}
		if (ink < 60 || differ > ink / 20) {
			throw new AssertionError("The Arabic note's text differs from the map's in " + differ + " of " + ink + " pixels");
		}
	}

	/**
	 * Pixels of a colour around a point, cut to the box they fill: a note's text. Within a few
	 * levels: some versions draw text a shade off its colour (1.21.1 draws 0xFF55FF as 0xFC54FC).
	 */
	private static boolean[][] mask(BufferedImage image, int cx, int cy, int colour) {
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int y1 = Integer.MIN_VALUE;
		for (int y = Math.max(0, cy - 24); y < Math.min(image.getHeight(), cy + 24); y++) {
			for (int x = Math.max(0, cx - 120); x < Math.min(image.getWidth(), cx + 120); x++) {
				if (close(image.getRGB(x, y) & 0xFFFFFF, colour, 8)) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		}
		if (x1 < 0) {
			return new boolean[0][0];
		}
		boolean[][] out = new boolean[y1 - y0 + 1][x1 - x0 + 1];
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				out[y - y0][x - x0] = close(image.getRGB(x, y) & 0xFFFFFF, colour, 8);
			}
		}
		return out;
	}

	private static String size(boolean[][] mask) {
		return mask.length == 0 ? "missing" : mask[0].length + "x" + mask.length;
	}

	/** Presses Save image, waits for the file, and returns it. */
	private static Path save(ClientGameTestContext context) {
		Set<Path> old = new HashSet<>(exports(context));
		click(context, mc -> centre(((ExportScreen) mc.gui.screen()).saveButton()), false);
		context.waitTicks(2);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen)) {
			throw new AssertionError("Save image didn't go back to the map");
		}
		// A cave slice is drawn for its export, which takes a while without a graphics card.
		context.waitFor(mc -> MapExport.running() == null, 12000);
		List<Path> made = exports(context).stream().filter(p -> !old.contains(p)).toList();
		if (made.size() != 1) {
			throw new AssertionError("Expected one new export, found " + made);
		}
		return made.get(0);
	}

	private static List<Path> exports(ClientGameTestContext context) {
		Path folder = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("screenshots"));
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> p.getFileName().toString().startsWith("diorama_") && p.toString().endsWith(".png"))
				.sorted(Comparator.naturalOrder()).toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The PNG is the size the screen said, fully opaque (fog where nothing is mapped), and not all fog. */
	private static void checkImage(Path file, ImageExport.Area area) {
		BufferedImage image = read(file);
		if (image == null || image.getWidth() != area.width() || image.getHeight() != area.height()) {
			throw new AssertionError(file.getFileName() + " is " + (image == null ? "unreadable" : image.getWidth() + "x" + image.getHeight())
				+ ", but the screen said " + area.width() + "x" + area.height());
		}
		int seeThrough = 0;
		Set<Integer> colours = new HashSet<>();
		for (int y = 0; y < image.getHeight(); y += 3) {
			for (int x = 0; x < image.getWidth(); x += 3) {
				int argb = image.getRGB(x, y);
				if ((argb >>> 24) != 0xFF) {
					seeThrough++;
				}
				colours.add(argb & 0xF0F0F0);
			}
		}
		if (seeThrough > 0) {
			throw new AssertionError(file.getFileName() + " has " + seeThrough + " see-through pixels; unmapped ground should be fog");
		}
		if (colours.size() < 200) {
			throw new AssertionError(file.getFileName() + " has only " + colours.size() + " colours: no map in it?");
		}
		System.out.println("IMAGE-EXPORT " + file.getFileName() + " " + image.getWidth() + "x" + image.getHeight() + " " + colours.size() + " colours");
	}

	/**
	 * Around the player, the image shows the saved tiles: their pixels, found here from the map
	 * coordinates alone, match the image's. The fog leaves only a faint veil over mapped ground.
	 */
	private static void matchesTiles(ClientGameTestContext context, Path file, ImageExport.Area area) {
		BufferedImage image = read(file);
		int[] diff = context.computeOnClient(mc -> {
			double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
			long gx = (long) Math.floor(mc.player.getX() * perUnit);
			long gy = (long) Math.floor(Projection.v(mc.player.getY(), mc.player.getZ()) * perUnit);
			TileStore store = MapSession.get().map().savedMap();
			int edge = Projection.TILE_PIXELS;
			int worst = 0;
			int compared = 0;
			for (long y = gy - 6; y <= gy + 6; y += 2) {
				for (long x = gx - 6; x <= gx + 6; x += 2) {
					int[] tile = store.read(new TileKey(TileKey.SURFACE, (int) Math.floorDiv(x, edge), (int) Math.floorDiv(y, edge), area.lod()));
					int ix = (int) (x - area.x());
					int iy = (int) (y - area.y());
					if (tile == null || ix < 0 || iy < 0 || ix >= image.getWidth() || iy >= image.getHeight()) {
						continue;
					}
					int abgr = tile[(int) Math.floorMod(y, edge) * edge + (int) Math.floorMod(x, edge)];
					if ((abgr >>> 24) != 0xFF) {
						continue;
					}
					int argb = image.getRGB(ix, iy);
					worst = Math.max(worst, Math.abs((abgr & 0xFF) - ((argb >> 16) & 0xFF)));
					worst = Math.max(worst, Math.abs(((abgr >> 8) & 0xFF) - ((argb >> 8) & 0xFF)));
					worst = Math.max(worst, Math.abs(((abgr >> 16) & 0xFF) - (argb & 0xFF)));
					compared++;
				}
			}
			return new int[] {worst, compared};
		});
		if (diff[1] < 20) {
			throw new AssertionError(file.getFileName() + ": only " + diff[1] + " mapped pixels around the player to compare");
		}
		if (diff[0] > 16) {
			throw new AssertionError(file.getFileName() + " doesn't show the saved map around the player: off by up to " + diff[0] + "/255");
		}
	}

	/**
	 * Whole map's box holds the middle of every chunk the map has near the player, on its ground.
	 * The ground's height comes from the server: a chunk the map keeps may be unloaded on the
	 * client by now, and the client reads an unloaded chunk's ground at the bottom of the world.
	 */
	private static void coversMappedChunks(ClientGameTestContext context, TestSingleplayerContext world, ImageExport.Area whole) {
		List<int[]> mapped = context.computeOnClient(mc -> {
			List<int[]> chunks = new java.util.ArrayList<>();
			int cx0 = mc.player.getBlockX() >> 4;
			int cz0 = mc.player.getBlockZ() >> 4;
			for (int cz = cz0 - 12; cz <= cz0 + 12; cz++) {
				for (int cx = cx0 - 12; cx <= cx0 + 12; cx++) {
					if (MapSession.get().map().chunk(cx, cz) != null) {
						chunks.add(new int[] {cx, cz});
					}
				}
			}
			return chunks;
		});
		double[] units = whole.units();
		String missing = world.getServer().computeOnServer(server -> {
			for (int[] chunk : mapped) {
				int x = chunk[0] * 16 + 8;
				int z = chunk[1] * 16 + 8;
				int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				double v = Projection.v(y, z);
				if (x < units[0] || x > units[2] || v < units[1] || v > units[3]) {
					return "chunk " + chunk[0] + "," + chunk[1] + " (ground at y " + y + ", map row " + v + ")";
				}
			}
			return null;
		});
		if (missing != null) {
			throw new AssertionError("Whole map " + whole + " leaves out the mapped " + missing);
		}
	}

	/** Whether the image is the line's colour where the line crosses the player, found from map units alone. */
	private static boolean lineAt(Path file, ImageExport.Area area, double[] at) {
		BufferedImage image = read(file);
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		int x = (int) Math.floor(at[0] * perUnit - area.x());
		int y = (int) Math.floor(at[1] * perUnit - area.y());
		int rgb = image.getRGB(x, y) & 0xFFFFFF;
		return close(rgb, LINE, 24);
	}

	/** Pixels near a point about a colour, and how wide a span they cover: {count, width}. */
	private static int[] spanNear(Path file, ImageExport.Area area, double u, double v, int colour) {
		BufferedImage image = read(file);
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		int cx = (int) Math.floor(u * perUnit - area.x());
		int cy = (int) Math.floor(v * perUnit - area.y());
		int count = 0;
		int left = Integer.MAX_VALUE;
		int right = Integer.MIN_VALUE;
		for (int y = cy - 16; y <= cy + 16; y++) {
			for (int x = cx - 30; x <= cx + 30; x++) {
				if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight() && close(image.getRGB(x, y) & 0xFFFFFF, colour, 12)) {
					count++;
					left = Math.min(left, x);
					right = Math.max(right, x);
				}
			}
		}
		return new int[] {count, count == 0 ? 0 : right - left + 1};
	}

	/** How many pixels near a point are about a colour: a note's text, or its shadow. */
	private static int countNear(Path file, ImageExport.Area area, double u, double v, int colour) {
		BufferedImage image = read(file);
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		int cx = (int) Math.floor(u * perUnit - area.x());
		int cy = (int) Math.floor(v * perUnit - area.y());
		int count = 0;
		for (int y = cy - 16; y <= cy + 16; y++) {
			for (int x = cx - 24; x <= cx + 24; x++) {
				if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight() && close(image.getRGB(x, y) & 0xFFFFFF, colour, 12)) {
					count++;
				}
			}
		}
		return count;
	}

	private static boolean close(int rgb, int want, int within) {
		return Math.abs((rgb >> 16 & 0xFF) - (want >> 16 & 0xFF)) <= within && Math.abs((rgb >> 8 & 0xFF) - (want >> 8 & 0xFF)) <= within
			&& Math.abs((rgb & 0xFF) - (want & 0xFF)) <= within;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static double[] box(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).boxOnScreen());
	}

	private static double[] centre(net.minecraft.client.gui.components.AbstractWidget button) {
		return new double[] {button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0};
	}

	/** Clicks a point given in GUI pixels. */
	private static void click(ClientGameTestContext context, java.util.function.Function<net.minecraft.client.Minecraft, double[]> where, boolean right) {
		double[] p = context.computeOnClient(mc -> {
			double[] gui = where.apply(mc);
			double scale = mc.getWindow().getGuiScale();
			return new double[] {gui[0] * scale, gui[1] * scale};
		});
		context.getInput().setCursorPos(p[0], p[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(right ? InputConstants.MOUSE_BUTTON_RIGHT : InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** Drags from a point by an offset, all in GUI pixels, in a few steps so the screen sees the moves. */
	private static void drag(ClientGameTestContext context, double x, double y, double dx, double dy) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(x * scale, y * scale);
		context.waitTicks(2);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		for (int i = 0; i < 5; i++) {
			context.getInput().moveCursor(dx * scale / 5, dy * scale / 5);
			context.waitTicks(1);
		}
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	private static void settle(ClientGameTestContext context) {
		context.waitTicks(40);
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
