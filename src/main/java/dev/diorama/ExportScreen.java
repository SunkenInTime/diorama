package dev.diorama;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
import dev.diorama.data.TileStore;
import dev.diorama.render.MapViewport;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingOverlay;
import dev.diorama.drawing.DrawingStore;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * Saving the map as an image: a sidebar with the choices, the map beside it with the box that
 * will be saved, and Save image and Cancel along the bottom. In Area mode the box starts inside
 * the view the map had; drag inside it to move it, its edges or corners to resize it, outside it
 * to pan, or type its corners in the sidebar. Whole map boxes everything explored. Cancel and Save
 * go back to the map as it was.
 *
 * <p>The Area box is kept in whole blocks, so the numbers in the sidebar are all of it and the same
 * numbers save the same image again. The map is drawn at a tilt, where a row of the picture is a
 * line of z only at one height: the box's z is measured on ground at {@link #groundY()}.
 *
 * <p>Opened on a cave slice it saves that slice. Cave slices aren't saved, so the image is drawn
 * from the chunks the map holds, and Whole map is as much of the slice as they cover.
 */
public final class ExportScreen extends Screen {
	private static final int SIDEBAR = 112;
	private static final int FOOTER = 28;
	/** Over the map, but see-through enough that it still reads as the map. */
	private static final int PANEL = 0xC0000000;
	private static final int EDGE = 0xFF3A3A42;
	private static final int MUTED = 0xFFB4BCC8;
	private static final int DIM = 0x90000000;
	/** How close to an edge or corner, in GUI pixels, a press takes hold of it. */
	private static final int GRAB = 4;
	/** The smallest box dragging makes, in blocks a side; typing can make a smaller one. */
	private static final int MIN_BOX = 16;
	/** No corner is typed further out than the world goes. */
	private static final int FARTHEST = 30_000_000;
	private static final int INVALID_TEXT = 0xFFFF0000;
	/** A whole or decimal number in typed or pasted text. */
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
	private static final String[] DETAIL_LABELS = {"4", "2", "1", "½"};
	private static final int LEFT = 1;
	private static final int RIGHT = 2;
	private static final int TOP = 4;
	private static final int BOTTOM = 8;
	private static final int MOVE = 16;
	private static final int PAN = 32;

	private final MapScreen parent;
	/** The view being saved: {@link TileKey#SURFACE}, or the cut of the cave slice the map was on. */
	private final int layer;
	private final MapViewport viewport = new MapViewport();
	private final DrawingOverlay drawingOverlay = new DrawingOverlay();
	/** The middle of the preview, in map units, and its zoom in GUI pixels per unit. */
	private double centerU;
	private double centerV;
	private double zoom;
	private boolean whole;
	/**
	 * The Area box, in whole blocks of the turned space the map is drawn in ({@link Facing}), its z
	 * on ground at {@link #groundY()}; kept while Whole map is shown.
	 */
	private int x0;
	private int z0;
	private int x1;
	private int z1;
	/** The box's corners as the world has them, typed: X and Z of one, then of the other. */
	private final EditBox[] corners = new EditBox[4];
	/** Whether the typed corners read as a box; Save image waits for them. */
	private boolean cornersRead = true;
	/** Set while the fields are being written from the box, which isn't typing. */
	private boolean writing;
	/** What each field last held, to tell what a change put into it. */
	private final String[] cornerText = {"", "", "", ""};
	/** Where the corner fields start, in GUI pixels down the sidebar. */
	private int cornersY;
	/** The box when the current press took hold of it, and how far the press has dragged since, in blocks. */
	private final int[] held = new int[4];
	private double draggedX;
	private double draggedZ;
	/** Everything explored with a margin of fog, once found; null until then or when nothing is. */
	private double @Nullable [] explored;
	private @Nullable CompletableFuture<double @Nullable []> finding;
	/** The saved tiles {@link #explored} was found in. */
	private @Nullable TileStore boundsStore;
	/** The level chosen with the detail buttons, or -1 to follow the default. */
	private int chosenLod = -1;
	private final Button[] detail = new Button[4];
	private @Nullable Button save;
	/** Whether the player's drawings go in the image; only asked when this map has some. */
	private boolean withDrawings = true;
	/** Whether the image is drawn at night, as Xaero's Nighttime option does; only asked where the sky has a night. */
	private boolean atNight;
	/** What the current press is doing: the {@link #LEFT}... bits, {@link #MOVE}, {@link #PAN}, or 0. */
	private int grip;
	private int pressButton = -1;
	/** Which detail buttons have the "too big" tooltip, so it's only set again when that changes. */
	private final boolean[] tooBig = new boolean[4];

	public ExportScreen(MapScreen parent, double centerU, double centerV, double zoom, int layer, int width, int height) {
		super(Component.translatable("diorama.export.title"));
		this.parent = parent;
		this.layer = layer;
		this.zoom = zoom;
		// The parent's view filled the screen; the preview is the part beside the sidebar and above the footer.
		this.centerU = centerU + (SIDEBAR / 2.0) / zoom;
		this.centerV = centerV - (FOOTER / 2.0) / zoom;
		double halfW = (width - SIDEBAR) / 2.0 / zoom;
		double halfH = (height - FOOTER) / 2.0 / zoom;
		this.x0 = (int) Math.round(this.centerU - halfW * 0.75);
		this.x1 = Math.max(this.x0 + 1, (int) Math.round(this.centerU + halfW * 0.75));
		this.z0 = (int) Math.round(Projection.zAt(this.centerV - halfH * 0.75, this.groundY()));
		this.z1 = Math.max(this.z0 + 1, (int) Math.round(Projection.zAt(this.centerV + halfH * 0.75, this.groundY())));
	}

	/**
	 * The height the box's z is measured at: where most ground is near, as for a point the map knows
	 * nothing about, or the cut of the cave slice.
	 */
	private int groundY() {
		return this.layer == TileKey.SURFACE ? MapScreen.USUAL_GROUND_Y : this.layer;
	}

	/** The Area box in map units. */
	private double[] areaBox() {
		int y = this.groundY();
		return new double[] {this.x0, Projection.v(y, this.z0), this.x1, Projection.v(y, this.z1)};
	}

	/** The Area box's corners in the world's blocks: the lower X and Z, then the higher. */
	private int[] worldCorners() {
		Facing facing = Projection.facing();
		int ax = (int) Math.round(facing.worldX(this.x0, this.z0));
		int az = (int) Math.round(facing.worldZ(this.x0, this.z0));
		int bx = (int) Math.round(facing.worldX(this.x1, this.z1));
		int bz = (int) Math.round(facing.worldZ(this.x1, this.z1));
		return new int[] {Math.min(ax, bx), Math.min(az, bz), Math.max(ax, bx), Math.max(az, bz)};
	}

	/** Shows the box's corners in the fields, after it was dragged. */
	private void writeCorners() {
		if (this.corners[0] == null) {
			return;
		}
		int[] world = this.worldCorners();
		this.writing = true;
		for (int i = 0; i < 4; i++) {
			this.cornerText[i] = Integer.toString(world[i]);
			this.corners[i].setValue(this.cornerText[i]);
			this.corners[i].setTextColor(EditBox.DEFAULT_TEXT_COLOR);
		}
		this.writing = false;
		this.cornersRead = true;
	}

	/**
	 * A corner field changed. Several numbers put in at once are a paste: "x z" or "x y z", as Copy
	 * coordinates and chat give a place, become that corner whatever the field held, and four
	 * become both.
	 */
	private void typed(int field, String text) {
		// 1.20.1 also says so when only the cursor moved.
		if (this.writing || text.equals(this.cornerText[field])) {
			return;
		}
		List<String> numbers = new java.util.ArrayList<>();
		Matcher matcher = NUMBER.matcher(put(this.cornerText[field], text));
		while (matcher.find()) {
			numbers.add(Long.toString((long) Math.floor(Double.parseDouble(matcher.group()))));
		}
		int row = field / 2 * 2;
		this.writing = true;
		if (numbers.size() == 2 || numbers.size() == 3) {
			this.corners[row].setValue(numbers.get(0));
			this.corners[row + 1].setValue(numbers.get(numbers.size() - 1));
		} else if (numbers.size() == 4) {
			for (int i = 0; i < 4; i++) {
				this.corners[i].setValue(numbers.get(i));
			}
		}
		this.writing = false;
		for (int i = 0; i < 4; i++) {
			this.cornerText[i] = this.corners[i].getValue();
		}
		this.readCorners();
	}

	/**
	 * Moves the box to the typed corners, in either order, once all four read and it has some size.
	 * A field that doesn't read turns red, as a coordinate does in the waypoint editor.
	 */
	private void readCorners() {
		Integer[] typed = new Integer[4];
		for (int i = 0; i < 4; i++) {
			typed[i] = parse(this.corners[i].getValue());
		}
		boolean reads = true;
		for (int i = 0; i < 4; i++) {
			// A box with no width or depth is nothing to save.
			boolean good = typed[i] != null && !typed[i].equals(typed[i ^ 2]);
			this.corners[i].setTextColor(good ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT);
			reads &= good;
		}
		this.cornersRead = reads;
		if (!reads) {
			return;
		}
		Facing facing = Projection.facing();
		int ax = (int) Math.round(facing.x(typed[0], typed[1]));
		int az = (int) Math.round(facing.z(typed[0], typed[1]));
		int bx = (int) Math.round(facing.x(typed[2], typed[3]));
		int bz = (int) Math.round(facing.z(typed[2], typed[3]));
		this.x0 = Math.min(ax, bx);
		this.x1 = Math.max(ax, bx);
		this.z0 = Math.min(az, bz);
		this.z1 = Math.max(az, bz);
		// Typed somewhere the preview shows none of: bring it into view. A box partly in view stays
		// put, or the map would jump at each digit on the way to a number.
		double[] box = this.areaBox();
		if (this.screenX(box[2]) < SIDEBAR || this.screenX(box[0]) > this.width || this.screenY(box[3]) < 0 || this.screenY(box[1]) > this.previewHeight()) {
			this.fit(box);
		}
	}

	/**
	 * What a change put into a field: the text without what it keeps of the field as it was. Where
	 * that could be cut more than one way (a 5 pasted beside a 5), the cut that starts and ends on a
	 * number, as a pasted place does.
	 */
	private static String put(String was, String text) {
		int length = text.length() - was.length();
		String found = null;
		for (int i = 0; length > 0 && i + length <= text.length(); i++) {
			if (!(text.substring(0, i) + text.substring(i + length)).equals(was)) {
				continue;
			}
			String put = text.substring(i, i + length);
			if (NUMBER.matcher(put.trim()).lookingAt() && Character.isDigit(put.trim().charAt(put.trim().length() - 1))) {
				return put;
			}
			found = found == null ? put : found;
		}
		// Typed over a selection: all of the text is new.
		return found == null ? text : found;
	}

	private static @Nullable Integer parse(String text) {
		try {
			int value = Integer.parseInt(text.trim());
			return value >= -FARTHEST && value <= FARTHEST ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	protected void init() {
		this.pressButton = -1;
		this.grip = 0;
		java.util.Arrays.fill(this.tooBig, false);
		for (int i = 0; i < this.detail.length; i++) {
			int lod = i;
			this.detail[i] = this.addRenderableWidget(Button.builder(Component.literal(DETAIL_LABELS[i]), b -> {
				this.chosenLod = lod;
			}).bounds(6 + i * 25, 46, 24, 20).build());
		}
		if (!this.drawings().isEmpty()) {
			//? if >=1.21 {
			this.addRenderableWidget(Checkbox.builder(Component.translatable("diorama.export.drawings"), this.font)
				.pos(6, 104)
				.selected(this.withDrawings)
				.onValueChange((box, value) -> this.withDrawings = value)
				.build());
			//?} else {
			/*// 1.20.1's checkbox has no builder and tells no one it changed.
			Component label = Component.translatable("diorama.export.drawings");
			this.addRenderableWidget(new Checkbox(6, 104, 24 + this.font.width(label), 20, label, this.withDrawings) {
				@Override
				public void onPress() {
					super.onPress();
					ExportScreen.this.withDrawings = this.selected();
				}
			});
			*///?}
		}
		if (this.hasNight()) {
			int y = this.drawings().isEmpty() ? 104 : 128;
			//? if >=1.21 {
			this.addRenderableWidget(Checkbox.builder(Component.translatable("diorama.export.night"), this.font)
				.pos(6, y)
				.selected(this.atNight)
				.onValueChange((box, value) -> this.atNight = value)
				.build());
			//?} else {
			/*Component label = Component.translatable("diorama.export.night");
			this.addRenderableWidget(new Checkbox(6, y, 24 + this.font.width(label), 20, label, this.atNight) {
				@Override
				public void onPress() {
					super.onPress();
					ExportScreen.this.atNight = this.selected();
				}
			});
			*///?}
		}
		// The corners, under whichever checkboxes there are: X and Z of one, then of the other.
		this.cornersY = 108 + (this.drawings().isEmpty() ? 0 : 24) + (this.hasNight() ? 24 : 0);
		// Resizing the window runs init() again: corners that don't read yet stay as typed.
		String[] unread = this.cornersRead ? null : this.cornerText.clone();
		for (int i = 0; i < 4; i++) {
			int field = i;
			EditBox box = new EditBox(this.font, 14 + i % 2 * 52, this.cornersY + 11 + i / 2 * 18, 40, 16, Component.translatable(i % 2 == 0 ? "diorama.waypoint.x" : "diorama.waypoint.z"));
			// Room for a pasted "x y z", or both corners.
			box.setMaxLength(64);
			box.setResponder(text -> this.typed(field, text));
			box.visible = !this.whole;
			this.corners[i] = this.addRenderableWidget(box);
		}
		this.writeCorners();
		if (unread != null) {
			this.writing = true;
			for (int i = 0; i < 4; i++) {
				this.corners[i].setValue(unread[i]);
				this.cornerText[i] = unread[i];
			}
			this.writing = false;
			this.readCorners();
		}
		this.save = this.addRenderableWidget(Button.builder(Component.translatable("diorama.export.save"), b -> this.save())
			.bounds(6, this.height - FOOTER + 4, 80, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(90, this.height - FOOTER + 4, 60, 20).build());
	}

	private int previewWidth() {
		return this.width - SIDEBAR;
	}

	private int previewHeight() {
		return this.height - FOOTER;
	}

	/** The box being saved, in map units: the Area box, or everything explored. Null while Whole map is still looking. */
	private double @Nullable [] box() {
		return this.whole ? this.explored : this.areaBox();
	}

	private List<Integer> levels(double[] box) {
		return ImageExport.levels(box[0], box[1], box[2], box[3]);
	}

	/** The level the export will be saved at for this box. */
	private int lod(double[] box) {
		List<Integer> levels = this.levels(box);
		if (this.chosenLod >= 0 && levels.contains(this.chosenLod)) {
			return this.chosenLod;
		}
		if (this.chosenLod >= 0) {
			// The chosen level is too big for this box: the next one down that isn't.
			for (int lod : levels) {
				if (lod > this.chosenLod) {
					return lod;
				}
			}
		}
		return ImageExport.defaultLevel(box[0], box[1], box[2], box[3]);
	}

	/**
	 * The pixels saved for a box. Whole map grows to whole pixels, losing nothing explored; the Area
	 * box is the pixels within it, so the image has no row or column of ground outside the corners.
	 */
	private ImageExport.Area area(double[] box) {
		int lod = this.lod(box);
		return this.whole ? ImageExport.Area.ofUnits(lod, box[0], box[1], box[2], box[3]) : ImageExport.Area.within(lod, box[0], box[1], box[2], box[3]);
	}

	private double screenX(double u) {
		return SIDEBAR + this.previewWidth() / 2.0 + (u - this.centerU) * this.zoom;
	}

	private double screenY(double v) {
		return this.previewHeight() / 2.0 + (v - this.centerV) * this.zoom;
	}

	// No blurred, dimmed world behind the map. On 1.20.1 a screen draws no background unless it asks for one.
	//? if >=1.21 {
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}
	//?}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		MapSession session = MapSession.get();
		if (session == null || !ServerRules.minimapAllowed() || (this.caves() ? !ServerRules.caveMapsAllowed() : session.map().savedMap() == null)) {
			this.onClose();
			return;
		}
		if (this.whole && !this.caves() && session.map().savedMap() != this.boundsStore) {
			// The map moved to other saved tiles (another dimension, or the view under the Nether's
			// roof changed kind): what's explored has to be found again there.
			this.whole = false;
			this.showWhole(true);
		}
		if (this.whole && this.caves()) {
			// The slice reaches as far as the chunks the map holds, which come and go while this is open.
			double[] held = this.sliceBox(session);
			if (!java.util.Arrays.equals(held, this.explored)) {
				this.explored = held;
				if (held != null) {
					this.fit(held);
				}
			}
		}
		if (this.whole && this.explored != null && this.finding != null) {
			// Found just now: fit it into the preview.
			this.finding = null;
			this.fit(this.explored);
		}
		// One view over the whole screen, placed so the preview's middle is (centerU, centerV).
		double fullU = this.centerU - (SIDEBAR / 2.0) / this.zoom;
		double fullV = this.centerV + (FOOTER / 2.0) / this.zoom;
		MapViewport.View view = new MapViewport.View(0, 0, this.width, this.height, fullU, fullV, this.zoom, this.layer);
		// The preview shows the image as it will be saved: by day, or at night if asked.
		this.viewport.night(this.atNight && this.hasNight() ? 1 : 0);
		this.viewport.draw(graphics, session.map(), view);
		if (this.withDrawings) {
			// As they'll be in the image, so the preview is what gets saved.
			this.drawingOverlay.draw(graphics, this.font, view, this.drawings(), null, null, true);
		}

		double[] box = this.box();
		int pw = this.previewWidth();
		int ph = this.previewHeight();
		if (box != null) {
			int x0 = (int) Math.round(Math.max(SIDEBAR, Math.min(this.width, this.screenX(box[0]))));
			int x1 = (int) Math.round(Math.max(SIDEBAR, Math.min(this.width, this.screenX(box[2]))));
			int y0 = (int) Math.round(Math.max(0, Math.min(ph, this.screenY(box[1]))));
			int y1 = (int) Math.round(Math.max(0, Math.min(ph, this.screenY(box[3]))));
			graphics.fill(SIDEBAR, 0, this.width, y0, DIM);
			graphics.fill(SIDEBAR, y1, this.width, ph, DIM);
			graphics.fill(SIDEBAR, y0, x0, y1, DIM);
			graphics.fill(x1, y0, this.width, y1, DIM);
			this.outline(graphics, box);
		} else if (this.whole) {
			graphics.fill(SIDEBAR, 0, this.width, ph, DIM);
			Component what = Component.translatable(this.finding != null ? "diorama.export.finding" : "diorama.export.nothing");
			graphics.centeredText(this.font, what, SIDEBAR + pw / 2, ph / 2 - 4, 0xFFFFFFFF);
		}

		// The sidebar and footer, over the map.
		graphics.fill(0, 0, SIDEBAR, ph, PANEL);
		graphics.fill(SIDEBAR - 1, 0, SIDEBAR, ph, EDGE);
		graphics.fill(0, ph, this.width, this.height, PANEL);
		graphics.fill(0, ph, this.width, ph + 1, EDGE);
		this.drawTabs(graphics, mouseX, mouseY);
		graphics.text(this.font, Component.translatable("diorama.export.detail"), 6, 34, MUTED, false);

		int lod = box != null ? this.lod(box) : -1;
		List<Integer> levels = box != null ? this.levels(box) : List.of();
		for (int i = 0; i < this.detail.length; i++) {
			this.detail[i].active = box != null && levels.contains(i) && i != lod;
			boolean tooBig = box != null && !levels.contains(i);
			if (tooBig != this.tooBig[i]) {
				this.tooBig[i] = tooBig;
				this.detail[i].setTooltip(tooBig ? Tooltip.create(Component.translatable("diorama.export.too_big", number(ImageExport.MAX_EDGE))) : null);
			}
		}
		if (this.save != null) {
			this.save.active = box != null && !levels.isEmpty() && MapExport.running() == null && (this.whole || this.cornersRead);
		}
		if (box != null && !levels.isEmpty()) {
			ImageExport.Area area = this.area(box);
			graphics.text(this.font, Component.translatable("diorama.export.pixels", number(area.width()), number(area.height())), 6, 76, 0xFFFFFFFF, false);
			graphics.text(this.font, size(ImageExport.estimateBytes(area)), 6, 88, MUTED, false);
			// Across and, on level ground, from top to bottom: the tilt draws a block's depth shorter than its width.
			Component blocks = Component.translatable("diorama.export.blocks", number(Math.round(box[2] - box[0])), number(Math.round((box[3] - box[1]) / Projection.sin())));
			graphics.fill(SIDEBAR + 4, 4, SIDEBAR + 10 + this.font.width(blocks), 16, DIM);
			graphics.text(this.font, blocks, SIDEBAR + 7, 6, 0xFFFFFFFF, false);
		}
		if (!this.whole) {
			int y = this.cornersY;
			graphics.text(this.font, Component.translatable("diorama.export.corners", this.groundY()), 6, y, MUTED, false);
			for (int row = 0; row < 2; row++) {
				graphics.text(this.font, "X", 6, y + 15 + row * 18, MUTED, false);
				graphics.text(this.font, "Z", 58, y + 15 + row * 18, MUTED, false);
			}
			if (mouseX >= 6 && mouseX < SIDEBAR - 6 && mouseY >= y - 1 && mouseY < y + 10) {
				graphics.setTooltipForNextFrame(this.font, this.font.split(Component.translatable("diorama.export.corners_tip", this.groundY()), 190), mouseX, mouseY);
			}
			// Beside the buttons, not over what's being saved.
			String help = this.font.plainSubstrByWidth(Component.translatable("diorama.export.help").getString(), Math.max(0, this.width - 164));
			graphics.text(this.font, help, 158, ph + 10, MUTED, false);
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
		// The chosen level: a dark face with a white rim and underline, so it doesn't read as hovered or greyed out.
		if (lod >= 0) {
			Button chosen = this.detail[lod];
			int x = chosen.getX();
			int y = chosen.getY();
			graphics.fill(x, y, x + chosen.getWidth(), y + chosen.getHeight(), 0xFFFFFFFF);
			graphics.fill(x + 1, y + 1, x + chosen.getWidth() - 1, y + chosen.getHeight() - 1, 0xFF101014);
			String label = DETAIL_LABELS[lod];
			int lx = x + (chosen.getWidth() - this.font.width(label)) / 2;
			graphics.text(this.font, label, lx, y + 6, 0xFFFFFFFF, true);
			graphics.fill(lx - 1, y + 15, lx + this.font.width(label), y + 16, 0xFFFFFFFF);
		}
	}

	/** The box's edge and, in Area mode, its eight handles. */
	private void outline(GuiGraphicsExtractor graphics, double[] box) {
		int x0 = (int) Math.round(this.screenX(box[0]));
		int x1 = (int) Math.round(this.screenX(box[2]));
		int y0 = (int) Math.round(this.screenY(box[1]));
		int y1 = (int) Math.round(this.screenY(box[3]));
		graphics.enableScissor(SIDEBAR, 0, this.width, this.previewHeight());
		graphics.fill(x0 - 1, y0 - 1, x1 + 1, y0, 0xFFFFFFFF);
		graphics.fill(x0 - 1, y1, x1 + 1, y1 + 1, 0xFFFFFFFF);
		graphics.fill(x0 - 1, y0, x0, y1, 0xFFFFFFFF);
		graphics.fill(x1, y0, x1 + 1, y1, 0xFFFFFFFF);
		if (!this.whole) {
			int mx = (x0 + x1) / 2;
			int my = (y0 + y1) / 2;
			for (int[] p : new int[][] {{x0, y0}, {mx, y0}, {x1, y0}, {x0, my}, {x1, my}, {x0, y1}, {mx, y1}, {x1, y1}}) {
				graphics.fill(p[0] - 3, p[1] - 3, p[0] + 3, p[1] + 3, 0xFF000000);
				graphics.fill(p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, 0xFFFFFFFF);
			}
		}
		graphics.disableScissor();
	}

	/** Area and Whole map, drawn as the map's own Surface and Caves tabs are. */
	private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = 6;
		for (int i = 0; i < 2; i++) {
			boolean selected = (i == 1) == this.whole;
			Component name = Component.translatable(i == 0 ? "diorama.export.area" : "diorama.export.whole");
			int nameWidth = this.font.width(name);
			int w = this.tabWidth(i);
			int h = selected ? 20 : 18;
			int y = 6;
			boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
			MapScreen.tab(graphics, selected, hovered, x, y, w, h);
			if (selected) {
				//? if >=1.21 {
				graphics.fill(x + 2, y + 2, x + w - 2, y + h, 0x40000000);
				//?}
				int underline = nameWidth - 1;
				graphics.fill(x + (w - underline) / 2, y + h - 3, x + (w - underline) / 2 + underline, y + h - 2, 0xFFFFFFFF);
			}
			graphics.text(this.font, name, x + w / 2 - nameWidth / 2, y + (selected ? 5 : 6), selected ? 0xFFFFFFFF : 0xFFE0E0E0, true);
			x += w;
		}
	}

	/** The tabs share the sidebar's width, Whole map getting what its name needs. */
	private int tabWidth(int i) {
		int whole = Math.max(this.font.width(Component.translatable("diorama.export.whole")) + 12, 50);
		return i == 1 ? Math.min(whole, SIDEBAR - 12 - 30) : SIDEBAR - 12 - Math.min(whole, SIDEBAR - 12 - 30);
	}

	/** The tab under a point: 0 Area, 1 Whole map, -1 neither. */
	private int tabAt(double x, double y) {
		if (y < 6 || y >= 24) {
			return -1;
		}
		if (x >= 6 && x < 6 + this.tabWidth(0)) {
			return 0;
		}
		if (x >= 6 + this.tabWidth(0) && x < 6 + this.tabWidth(0) + this.tabWidth(1)) {
			return 1;
		}
		return -1;
	}

	private void showWhole(boolean whole) {
		if (whole == this.whole) {
			return;
		}
		this.whole = whole;
		for (EditBox corner : this.corners) {
			if (corner != null) {
				corner.visible = !whole;
			}
		}
		if (!whole) {
			// Back to the box that was drawn, in view.
			this.fit(this.areaBox());
			return;
		}
		// The fields are out of sight; what's typed in them is there on the way back.
		this.setFocused(null);
		MapSession session = MapSession.get();
		this.explored = null;
		this.finding = null;
		if (session != null && this.caves()) {
			// Nothing of a cave slice is saved: it's whatever the chunks the map holds show of it.
			this.explored = this.sliceBox(session);
			if (this.explored != null) {
				this.fit(this.explored);
			}
			return;
		}
		TileStore store = session == null ? null : session.map().savedMap();
		if (store == null) {
			return;
		}
		this.boundsStore = store;
		CompletableFuture<double @Nullable []> found = CompletableFuture.supplyAsync(() -> {
			try {
				store.catchUp();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			ImageExport.Area area = ImageExport.explored(store, 0);
			if (area == null) {
				return null;
			}
			double[] units = area.units();
			double m = ImageExport.WHOLE_MAP_MARGIN;
			return new double[] {units[0] - m, units[1] - m, units[2] + m, units[3] + m};
		});
		this.finding = found;
		found.whenCompleteAsync((units, failure) -> {
			if (failure != null) {
				Diorama.LOGGER.warn("Couldn't find what's explored for an export", failure);
			}
			if (this.finding == found) {
				this.explored = failure != null ? null : units;
				if (this.explored == null) {
					// Nothing found, or the search failed: stop saying it's looking.
					this.finding = null;
				}
			}
		}, this.minecraft);
	}

	/** The zoom that shows a box with a little room around it in the preview. */
	private double fitZoom(double[] box) {
		double zoom = Math.min(this.previewWidth() / (box[2] - box[0]), this.previewHeight() / (box[3] - box[1])) * 0.9;
		return Math.max(0.04, Math.min(16, zoom));
	}

	private void fit(double[] box) {
		this.centerU = (box[0] + box[2]) / 2;
		this.centerV = (box[1] + box[3]) / 2;
		this.zoom = this.fitZoom(box);
	}

	/** As much of the cave slice as the map's chunks cover now, with Whole map's margin; null with no chunks. */
	private double @Nullable [] sliceBox(MapSession session) {
		double[] held = session.map().sliceBounds(this.layer);
		double m = ImageExport.WHOLE_MAP_MARGIN;
		return held == null ? null : new double[] {held[0] - m, held[1] - m, held[2] + m, held[3] + m};
	}

	/** Whether a cave slice is being saved, not the sky view. */
	private boolean caves() {
		return this.layer != TileKey.SURFACE;
	}

	/** Whether this dimension's sky view has a night to draw: not under a roof, and its time moves. A cave has none. */
	private boolean hasNight() {
		MapSession session = MapSession.get();
		return session != null && !this.caves() && !session.map().roofless() && this.minecraft.level != null && !this.minecraft.level.dimensionType().hasFixedTime();
	}

	/** The drawings on this map of this dimension, as the map shows them. */
	private List<Drawing> drawings() {
		DrawingStore store = DrawingStore.get();
		if (store == null || this.minecraft == null || this.minecraft.level == null) {
			return List.of();
		}
		return store.in(this.minecraft.level.dimension().identifier().toString());
	}

	private void save() {
		double[] box = this.box();
		MapSession session = MapSession.get();
		TileStore store = session == null ? null : session.map().savedMap();
		if (box == null || session == null || !this.caves() && store == null || this.levels(box).isEmpty() || !this.whole && !this.cornersRead) {
			return;
		}
		ImageExport.Area area = this.area(box);
		List<Drawing> drawings = this.withDrawings ? this.drawings() : List.of();
		float night = this.atNight && this.hasNight() ? 1 : 0;
		boolean started = this.caves() ? MapExport.startCave(session.map(), this.layer, area, drawings)
			: MapExport.start(store, area, session.map().roofless(), night, drawings);
		if (started) {
			this.onClose();
		}
	}

	// Mouse events came as separate numbers before 1.21.9.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return super.mouseClicked(event, doubleClick) || this.pressed(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.released(event.button());
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.button(), dx, dy) || super.mouseDragged(event, dx, dy);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return super.mouseClicked(x, y, button) || this.pressed(x, y, button);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		this.released(button);
		return super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
	}
	*///?}

	/** A press no widget took: a tab, or on the map a hold of the box or the map. */
	private boolean pressed(double x, double y, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int tab = this.tabAt(x, y);
		if (tab >= 0) {
			this.showWhole(tab == 1);
			this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
			return true;
		}
		if (x < SIDEBAR || y >= this.previewHeight()) {
			return false;
		}
		// A press on the map is done typing.
		this.setFocused(null);
		this.pressButton = button;
		this.grip = this.whole ? PAN : this.gripAt(x, y);
		this.held[0] = this.x0;
		this.held[1] = this.z0;
		this.held[2] = this.x1;
		this.held[3] = this.z1;
		this.draggedX = 0;
		this.draggedZ = 0;
		return true;
	}

	/** What a press at a point takes hold of: edges and corners, the inside, or else the map. */
	private int gripAt(double x, double y) {
		double[] box = this.areaBox();
		double x0 = this.screenX(box[0]);
		double x1 = this.screenX(box[2]);
		double y0 = this.screenY(box[1]);
		double y1 = this.screenY(box[3]);
		boolean inX = x > x0 - GRAB && x < x1 + GRAB;
		boolean inY = y > y0 - GRAB && y < y1 + GRAB;
		int grip = 0;
		if (inY && Math.abs(x - x0) <= GRAB) {
			grip |= LEFT;
		} else if (inY && Math.abs(x - x1) <= GRAB) {
			grip |= RIGHT;
		}
		if (inX && Math.abs(y - y0) <= GRAB) {
			grip |= TOP;
		} else if (inX && Math.abs(y - y1) <= GRAB) {
			grip |= BOTTOM;
		}
		if (grip != 0) {
			return grip;
		}
		return x > x0 && x < x1 && y > y0 && y < y1 ? MOVE : PAN;
	}

	private void released(int button) {
		if (button == this.pressButton) {
			this.pressButton = -1;
			this.grip = 0;
		}
	}

	private boolean dragged(int button, double dx, double dy) {
		if (button != this.pressButton || this.grip == 0) {
			return false;
		}
		double du = dx / this.zoom;
		double dv = dy / this.zoom;
		if (this.grip == PAN) {
			this.centerU -= du;
			this.centerV -= dv;
			return true;
		}
		// The box moves by whole blocks from where the press found it, so it keeps its size as it's moved.
		this.draggedX += du;
		this.draggedZ += dv / Projection.sin();
		int blocksX = (int) Math.round(this.draggedX);
		int blocksZ = (int) Math.round(this.draggedZ);
		// An edge stops a small box short of the one across from it; a box typed smaller isn't made bigger.
		int across = Math.min(MIN_BOX, this.held[2] - this.held[0]);
		int deep = Math.min(MIN_BOX, this.held[3] - this.held[1]);
		if (this.grip == MOVE) {
			this.x0 = this.held[0] + blocksX;
			this.x1 = this.held[2] + blocksX;
			this.z0 = this.held[1] + blocksZ;
			this.z1 = this.held[3] + blocksZ;
		} else {
			if ((this.grip & LEFT) != 0) {
				this.x0 = Math.min(this.held[0] + blocksX, this.held[2] - across);
			}
			if ((this.grip & RIGHT) != 0) {
				this.x1 = Math.max(this.held[2] + blocksX, this.held[0] + across);
			}
			if ((this.grip & TOP) != 0) {
				this.z0 = Math.min(this.held[1] + blocksZ, this.held[3] - deep);
			}
			if ((this.grip & BOTTOM) != 0) {
				this.z1 = Math.max(this.held[3] + blocksZ, this.held[1] + deep);
			}
		}
		this.writeCorners();
		return true;
	}

	// The mouse wheel had only the one axis on 1.20.1.
	@Override
	//? if >=1.21 {
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
	//?} else {
	/*public boolean mouseScrolled(double x, double y, double scrollY) {
	*///?}
		if (x < SIDEBAR || y >= this.previewHeight()) {
			return false;
		}
		double before = this.zoom;
		double after = Math.max(0.04, Math.min(16, before * Math.pow(1.25, scrollY)));
		// Keep the map point under the cursor where it is.
		double px = x - SIDEBAR - this.previewWidth() / 2.0;
		double py = y - this.previewHeight() / 2.0;
		double mapU = this.centerU + px / before;
		double mapV = this.centerV + py / before;
		this.centerU = mapU - px / after;
		this.centerV = mapV - py / after;
		this.zoom = after;
		return true;
	}

	private static String number(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	/** "about 6 MB", or kilobytes under a megabyte. */
	private static Component size(long bytes) {
		if (bytes < 1_000_000) {
			return Component.translatable("diorama.export.size_kb", number(Math.max(1, Math.round(bytes / 1000.0))));
		}
		return Component.translatable("diorama.export.size_mb", number(Math.round(bytes / 1_000_000.0)));
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void removed() {
		this.viewport.close();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** For tests: the image Save image would write now, or null while there's none to write. */
	public ImageExport.@Nullable Area plannedArea() {
		double[] box = this.box();
		if (box == null || this.levels(box).isEmpty()) {
			return null;
		}
		return this.area(box);
	}

	/** For tests: the box on screen in GUI pixels, {x0, y0, x1, y1}, or null while there's none. */
	public double @Nullable [] boxOnScreen() {
		double[] box = this.box();
		return box == null ? null : new double[] {this.screenX(box[0]), this.screenY(box[1]), this.screenX(box[2]), this.screenY(box[3])};
	}

	/** For tests: the middle of a tab in GUI pixels, 0 Area, 1 Whole map. */
	public int[] tabCenter(int tab) {
		int x = 6 + (tab == 1 ? this.tabWidth(0) : 0);
		return new int[] {x + this.tabWidth(tab) / 2, 14};
	}

	/** For tests: a corner field, 0 to 3: X and Z of one corner, then of the other. */
	public EditBox cornerField(int field) {
		return this.corners[field];
	}

	/** For tests: a detail button, 0 (4 px per block) to 3, and Save image. */
	public Button detailButton(int lod) {
		return this.detail[lod];
	}

	public @Nullable Button saveButton() {
		return this.save;
	}
}
