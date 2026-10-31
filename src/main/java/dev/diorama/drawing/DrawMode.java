package dev.diorama.drawing;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.Waypoint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
//? if <1.21.9 {
/*import net.minecraft.client.gui.screens.Screen;
*///?}
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Draw mode on the fullscreen map: the tool column down the left edge and what the mouse does
 * with each tool. The map screen hands over its presses, drags, releases and keys while drawing.
 *
 * <ul>
 *   <li>Pen: drag to draw freehand.</li>
 *   <li>Line and Area: click points, double-click, right-click or Enter to finish; Shift keeps a
 *   segment straight or diagonal.</li>
 *   <li>Note: click a spot and type; Enter keeps it.</li>
 *   <li>Erase: click or drag over drawings.</li>
 *   <li>Measure: drag from one place to another; see {@link Measure}.</li>
 * </ul>
 * Right-drag pans with every tool, and left-drag too with the clicking tools. Ctrl+Z undoes, while
 * the map is open. Esc drops what's half drawn, then leaves draw mode.
 */
public final class DrawMode {
	public enum Tool {
		PEN, LINE, AREA, NOTE, ERASE, MEASURE
	}

	/** Pixel art for the tool buttons, '#' lit, each centred on its button. */
	private static final String[][] ICONS = {
		{"..##.......", ".#..#......", "#....#....#", "......#..#.", ".......##.."},
		{"........#..", ".......###.", "......#.#..", ".....#.....", "....#......", "...#.......", "..#........", ".###.......", "..#........"},
		{".#######...", ".#.....#...", ".#......#..", ".#.......#.", ".#.......#.", ".#......#..", ".########.."},
		{"#########", "#...#...#", "....#....", "....#....", "....#....", "....#....", "...###..."},
		{"....###..", "...#..##.", "..#..###.", ".#..###..", "#..###...", ".####....", "..#......", "...######"},
		{"###########", "#.#.#.#.#.#", "#.#...#...#", "#.........#", "###########"},
	};
	private static final String[] UNDO_ICON = {"..#......", ".##......", "#######..", ".##....#.", "..#.....#", "........#", ".......#.", "...####.."};
	public static final String[] PENCIL_ICON = {"......##.", ".....#..#", "....#..#.", "...#..#..", "..#..#...", ".#..#....", "#..#.....", "###......"};
	private static final int BUTTON = 20;
	private static final int GAP = 2;
	private static final int LEFT = 4;
	private static final int SWATCH = 10;
	/** Pen points closer than this on screen, in GUI pixels, aren't kept while drawing. */
	private static final double PEN_STEP = 1.5;
	/** How far a finished pen stroke may stray from what was drawn when its points are thinned, in GUI pixels. */
	private static final double PEN_TOLERANCE = 0.4;
	private static final long DOUBLE_CLICK_MS = 350;
	private static final int NOTE_LENGTH = 60;

	// The tool and colour stay picked for the rest of the session.
	private static Tool tool = Tool.PEN;
	private static int color = Waypoint.PALETTE[2];

	private boolean active;
	private final List<Button> toolButtons = new ArrayList<>();
	private @Nullable Button colourButton;
	private @Nullable Button undoButton;
	private @Nullable Button doneButton;
	private @Nullable EditBox noteBox;
	private boolean paletteOpen;
	private final DrawingOverlay overlay = new DrawingOverlay();
	private final Measure measure = new Measure();
	/** Finds the block under a screen point, for the measuring tool. */
	private Measure.Picker picker = (x, y) -> java.util.concurrent.CompletableFuture.completedFuture(null);
	/** The dimension whose map is being drawn on. */
	private Supplier<String> dimension = () -> "";
	/** Gives a widget the screen's keyboard focus. */
	private Consumer<AbstractWidget> focus = w -> {
	};

	/** The pen's stroke or the line or area being clicked out, in map space. */
	private final List<double[]> points = new ArrayList<>();
	private boolean penDown;
	private boolean erasing;
	/** The press was the palette's (a colour picked, or the palette closed), so its release is too. */
	private boolean paletteRelease;
	/** Where a note being typed goes, in map space. */
	private double @Nullable [] notePoint;
	private long lastClickTime;
	private double lastClickX;
	private double lastClickY;
	/** Each entry undoes one action: the drawings it added, and the ones it removed with where they were. */
	private final Deque<List<Change>> undo = new ArrayDeque<>();
	private @Nullable List<Change> erasedNow;

	private record Change(Drawing drawing, int removedFrom) {
	}

	public boolean active() {
		return this.active;
	}

	/**
	 * Builds the column; its widgets go to {@code add}. On a resize, which builds it again, what's
	 * being drawn carries over: a note typed so far is kept, a pen stroke ends where it got to, an
	 * erase stays undoable, and the points of a line or area stay (they're in map space).
	 */
	public void init(Font font, Consumer<AbstractWidget> add, Consumer<AbstractWidget> focus, Measure.Picker picker, Supplier<String> dimension) {
		this.commitNote();
		this.endGesture();
		this.dimension = dimension;
		this.focus = focus;
		this.picker = picker;
		this.toolButtons.clear();
		this.paletteOpen = false;
		// The screen being rebuilt never sends the release a palette press was waiting for.
		this.paletteRelease = false;
		int y = 20;
		for (Tool t : Tool.values()) {
			String name = t.name().toLowerCase(java.util.Locale.ROOT);
			Button button = Button.builder(Component.empty(), b -> this.pick(t))
				.bounds(LEFT, y, BUTTON, BUTTON)
				.tooltip(Tooltip.create(Component.translatable("diorama.draw." + name)))
				.createNarration(n -> Component.translatable("diorama.draw." + name))
				.build();
			this.toolButtons.add(button);
			add.accept(button);
			y += BUTTON + GAP;
		}
		y += 4;
		this.colourButton = Button.builder(Component.empty(), b -> this.paletteOpen = !this.paletteOpen)
			.bounds(LEFT, y, BUTTON, BUTTON)
			.tooltip(Tooltip.create(Component.translatable("diorama.draw.colour")))
			.createNarration(n -> Component.translatable("diorama.draw.colour"))
			.build();
		add.accept(this.colourButton);
		y += BUTTON + 4;
		this.undoButton = Button.builder(Component.empty(), b -> this.undo())
			.bounds(LEFT, y, BUTTON, BUTTON)
			.tooltip(Tooltip.create(Component.translatable("diorama.draw.undo")))
			.createNarration(n -> Component.translatable("diorama.draw.undo"))
			.build();
		add.accept(this.undoButton);
		this.doneButton = Button.builder(Component.translatable("gui.done"), b -> this.stop())
			.bounds(LEFT + BUTTON + GAP, y, 40, BUTTON)
			.build();
		add.accept(this.doneButton);
		this.noteBox = new EditBox(font, 0, 0, 120, BUTTON, Component.translatable("diorama.draw.note"));
		this.noteBox.setMaxLength(NOTE_LENGTH);
		add.accept(this.noteBox);
		this.showWidgets();
	}

	public void toggle() {
		if (this.active) {
			this.stop();
		} else {
			this.active = true;
			this.showWidgets();
		}
	}

	public void stop() {
		this.commitNote();
		this.cancel();
		this.active = false;
		this.paletteOpen = false;
		this.showWidgets();
	}

	/** Called as the map closes: undo doesn't outlive it. */
	public void close() {
		this.commitNote();
		this.undo.clear();
	}

	private void showWidgets() {
		for (Button button : this.toolButtons) {
			button.visible = this.active;
		}
		for (AbstractWidget widget : new AbstractWidget[] {this.colourButton, this.undoButton, this.doneButton}) {
			if (widget != null) {
				widget.visible = this.active;
			}
		}
		if (this.undoButton != null) {
			this.undoButton.active = !this.undo.isEmpty();
		}
		if (this.noteBox != null) {
			this.noteBox.visible = this.active && this.notePoint != null;
		}
	}

	private void pick(Tool picked) {
		this.commitNote();
		this.cancel();
		tool = picked;
		this.paletteOpen = false;
	}

	/**
	 * The map is about to show another dimension: a note being typed and a stroke being drawn are
	 * kept, what's half made is dropped, and undo stops here, since what it would take back won't
	 * be on screen.
	 */
	public void leaveDimension() {
		this.commitNote();
		this.endGesture();
		this.cancel();
		this.undo.clear();
	}

	/**
	 * Ends a pen stroke or an erase the mouse is still down for, as if it were let go: the screen
	 * that would have had the release is being rebuilt.
	 */
	private void endGesture() {
		this.measure.end();
		if (this.penDown) {
			this.penDown = false;
			if (this.points.size() >= 2) {
				this.finish(new Drawing(Drawing.Kind.STROKE, this.dimension.get(), color, flatten(this.points), ""));
			}
			this.points.clear();
		}
		if (this.erasing) {
			this.erasing = false;
			if (this.erasedNow != null && !this.erasedNow.isEmpty()) {
				this.undo.push(this.erasedNow);
			}
			this.erasedNow = null;
		}
	}

	/**
	 * Any press on the screen, before a widget gets it: a press off the note box keeps the note
	 * being typed, so a button can't take the keyboard from it half-typed.
	 */
	public void anyPress(double x, double y) {
		if (this.notePoint != null && this.noteBox != null && !on(this.noteBox, x, y)) {
			this.commitNote();
		}
	}

	/** Drops anything half drawn. An erase cut short keeps what it erased undoable. */
	private void cancel() {
		this.points.clear();
		this.measure.clear();
		this.penDown = false;
		this.paletteRelease = false;
		this.erasing = false;
		if (this.erasedNow != null && !this.erasedNow.isEmpty()) {
			this.undo.push(this.erasedNow);
		}
		this.erasedNow = null;
		if (this.noteBox != null) {
			this.noteBox.setValue("");
			this.noteBox.visible = false;
			if (this.notePoint != null) {
				// Keys go back to the map.
				this.focus.accept(null);
			}
		}
		this.notePoint = null;
	}

	/** Whether a note is being typed, so keys go to its box rather than the map. */
	public boolean typing() {
		return this.active && this.notePoint != null;
	}

	/** Whether a screen point is on the column or the open palette. */
	public boolean onControls(double x, double y) {
		if (!this.active) {
			return false;
		}
		if (this.paletteOpen && this.onPalette(x, y)) {
			return true;
		}
		for (Button button : this.toolButtons) {
			if (on(button, x, y)) {
				return true;
			}
		}
		for (AbstractWidget widget : new AbstractWidget[] {this.colourButton, this.undoButton, this.doneButton, this.noteBox}) {
			if (widget != null && widget.visible && on(widget, x, y)) {
				return true;
			}
		}
		return false;
	}

	private static boolean on(AbstractWidget widget, double x, double y) {
		return x >= widget.getX() && x < widget.getX() + widget.getWidth() && y >= widget.getY() && y < widget.getY() + widget.getHeight();
	}

	private int paletteX() {
		return LEFT + BUTTON + 4;
	}

	/** The palette opens beside the colour button and upward from its bottom edge, clear of Undo and Done below. */
	private int paletteY() {
		return this.colourButton == null ? 0 : this.colourButton.getY() + BUTTON - 4 * SWATCH;
	}

	private boolean onPalette(double x, double y) {
		return x >= this.paletteX() - 2 && x < this.paletteX() + 4 * SWATCH + 2 && y >= this.paletteY() - 2 && y < this.paletteY() + 4 * SWATCH + 2;
	}

	/** Whether a drag with {@code button} should pan the map rather than draw. */
	public boolean pans(int button) {
		return !this.active || button == InputConstants.MOUSE_BUTTON_RIGHT || button == InputConstants.MOUSE_BUTTON_MIDDLE
			|| tool == Tool.LINE || tool == Tool.AREA || tool == Tool.NOTE;
		// The pen, the eraser and the measuring tool drag.
	}

	/** A press on the map (not on a control). True if draw mode took it. */
	public boolean press(MapViewport.View view, Font font, String dimension, double x, double y, int button) {
		if (this.paletteOpen) {
			// The palette is open: a press picks a colour from it, or just closes it.
			if (this.onPalette(x, y)) {
				int i = (int) ((x - this.paletteX()) / SWATCH);
				int j = (int) ((y - this.paletteY()) / SWATCH);
				if (i >= 0 && i < 4 && j >= 0 && j < 4) {
					color = Waypoint.PALETTE[j * 4 + i];
				}
			}
			this.paletteOpen = false;
			this.paletteRelease = true;
			return true;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (tool == Tool.PEN) {
			this.points.clear();
			this.points.add(mapPoint(view, x, y));
			this.penDown = true;
			return true;
		}
		if (tool == Tool.ERASE) {
			this.erasing = true;
			this.erasedNow = new ArrayList<>();
			this.eraseAt(view, font, dimension, x, y);
			return true;
		}
		if (tool == Tool.MEASURE) {
			this.measure.start(mapPoint(view, x, y), this.picker.at(x, y));
			return true;
		}
		return false;
	}

	/** A drag on the map with the left button down. True if draw mode took it. */
	public boolean drag(MapViewport.View view, Font font, String dimension, double x, double y) {
		if (this.penDown) {
			double[] last = this.points.get(this.points.size() - 1);
			if (Math.hypot(view.screenX(last[0]) - x, view.screenY(last[1]) - y) >= PEN_STEP) {
				this.points.add(mapPoint(view, x, y));
			}
			return true;
		}
		if (this.erasing) {
			this.eraseAt(view, font, dimension, x, y);
			return true;
		}
		if (this.measure.dragging()) {
			this.measure.move(mapPoint(view, x, y), this.picker.at(x, y));
			return true;
		}
		return false;
	}

	/**
	 * A release on the map; {@code moved} if the press was dragged. True if draw mode took it.
	 * Clicks out points for the line and area tools and places notes.
	 */
	public boolean release(MapViewport.View view, Font font, String dimension, double x, double y, int button, boolean moved) {
		if (this.paletteRelease) {
			this.paletteRelease = false;
			return true;
		}
		if (this.penDown && button == InputConstants.MOUSE_BUTTON_LEFT) {
			this.penDown = false;
			this.points.add(mapPoint(view, x, y));
			double tolerance = PEN_TOLERANCE / view.zoom();
			this.finish(new Drawing(Drawing.Kind.STROKE, dimension, color, thin(this.points, tolerance), ""));
			this.points.clear();
			return true;
		}
		if (this.measure.dragging() && button == InputConstants.MOUSE_BUTTON_LEFT) {
			this.measure.move(mapPoint(view, x, y), this.picker.at(x, y));
			this.measure.end();
			return true;
		}
		if (this.erasing && button == InputConstants.MOUSE_BUTTON_LEFT) {
			this.erasing = false;
			if (this.erasedNow != null && !this.erasedNow.isEmpty()) {
				this.undo.push(this.erasedNow);
			}
			this.erasedNow = null;
			this.showWidgets();
			return true;
		}
		if (moved) {
			return false;
		}
		boolean clicking = tool == Tool.LINE || tool == Tool.AREA;
		if (clicking && button == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.finishShape(dimension);
			return true;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			// Right-clicks open nothing while drawing.
			return true;
		}
		long now = System.currentTimeMillis();
		boolean doubleClick = now - this.lastClickTime < DOUBLE_CLICK_MS && Math.hypot(x - this.lastClickX, y - this.lastClickY) < 4;
		this.lastClickTime = now;
		this.lastClickX = x;
		this.lastClickY = y;
		if (clicking) {
			if (doubleClick && !this.points.isEmpty()) {
				this.finishShape(dimension);
				// So a third click starts afresh rather than counting as another double-click.
				this.lastClickTime = 0;
			} else {
				this.points.add(this.snapped(view, x, y));
			}
			return true;
		}
		if (tool == Tool.NOTE && this.noteBox != null) {
			this.notePoint = mapPoint(view, x, y);
			this.noteBox.setValue("");
			int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
			this.noteBox.setX((int) Math.max(2, Math.min(screenWidth - this.noteBox.getWidth() - 2, Math.round(x - this.noteBox.getWidth() / 2.0))));
			this.noteBox.setY((int) Math.round(y - BUTTON / 2.0));
			this.noteBox.visible = true;
			this.focus.accept(this.noteBox);
			return true;
		}
		return true;
	}

	/** A key while drawing; true if draw mode took it. Typing in a note's box goes to the box. */
	public boolean key(int key, String dimension) {
		if (!this.active) {
			return false;
		}
		if (this.notePoint != null) {
			if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				this.commitNote();
				return true;
			}
			if (key == InputConstants.KEY_ESCAPE) {
				this.cancel();
				return true;
			}
			return false;
		}
		if (key == InputConstants.KEY_ESCAPE) {
			if (this.paletteOpen) {
				this.paletteOpen = false;
			} else if (!this.points.isEmpty() || this.penDown || this.measure.shown()) {
				this.cancel();
			} else {
				this.stop();
			}
			return true;
		}
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
			// Enter finishes a line or area, and is draw mode's either way: let through, it would
			// press whichever button was clicked last (a tool, or the pencil, which leaves draw mode).
			if (!this.penDown && !this.points.isEmpty()) {
				this.finishShape(dimension);
			}
			return true;
		}
		if (key == InputConstants.KEY_Z && controlDown()) {
			this.undo();
			return true;
		}
		return false;
	}

	private static boolean controlDown() {
		//? if >=1.21.9 {
		return Minecraft.getInstance().hasControlDown();
		//?} else {
		/*return Screen.hasControlDown();
		*///?}
	}

	public static boolean shiftDown() {
		//? if >=1.21.9 {
		return Minecraft.getInstance().hasShiftDown();
		//?} else {
		/*return Screen.hasShiftDown();
		*///?}
	}

	private void finishShape(String dimension) {
		Drawing.Kind kind = tool == Tool.AREA ? Drawing.Kind.AREA : Drawing.Kind.STROKE;
		double[] flat = flatten(this.points);
		this.points.clear();
		this.finish(new Drawing(kind, dimension, color, flat, ""));
	}

	private void commitNote() {
		if (this.notePoint == null || this.noteBox == null) {
			return;
		}
		String text = this.noteBox.getValue().strip();
		String dimension = this.dimension.get();
		double[] at = this.notePoint;
		this.cancel();
		this.finish(new Drawing(Drawing.Kind.NOTE, dimension, color, at, text));
	}

	private void finish(Drawing drawing) {
		DrawingStore store = DrawingStore.get();
		if (store == null || !drawing.complete()) {
			return;
		}
		store.add(drawing);
		this.undo.push(List.of(new Change(drawing, -1)));
		this.showWidgets();
	}

	private void eraseAt(MapViewport.View view, Font font, String dimension, double x, double y) {
		DrawingStore store = DrawingStore.get();
		if (store == null) {
			return;
		}
		Drawing hit = DrawingOverlay.under(font, view, store.in(dimension), x, y, true);
		if (hit != null) {
			int index = store.remove(hit);
			if (this.erasedNow != null) {
				this.erasedNow.add(new Change(hit, index));
			}
		}
	}

	private void undo() {
		DrawingStore store = DrawingStore.get();
		if (store == null || this.undo.isEmpty()) {
			return;
		}
		List<Change> changes = this.undo.pop();
		store.batch(() -> {
			// Removals go back in the reverse of the order they were taken, so each finds its place.
			for (int i = changes.size() - 1; i >= 0; i--) {
				Change change = changes.get(i);
				if (change.removedFrom < 0) {
					store.remove(change.drawing);
				} else {
					store.restore(change.drawing, change.removedFrom);
				}
			}
		});
		this.showWidgets();
	}

	/** The drawings, what's being drawn, and the eraser's target lit up. */
	public void drawMap(GuiGraphicsExtractor graphics, Font font, MapViewport.View view, List<Drawing> drawings, double mouseX, double mouseY, boolean overMap) {
		Drawing preview = null;
		Drawing target = null;
		if (this.active && !this.points.isEmpty()) {
			List<double[]> shown = new ArrayList<>(this.points);
			if (!this.penDown && overMap) {
				shown.add(this.snapped(view, mouseX, mouseY));
			}
			preview = new Drawing(tool == Tool.AREA ? Drawing.Kind.AREA : Drawing.Kind.STROKE, "", color, flatten(shown), "");
		} else if (this.active && tool == Tool.ERASE && overMap) {
			target = DrawingOverlay.under(font, view, drawings, mouseX, mouseY, true);
		} else if (this.active && this.measure.shown()) {
			preview = this.measure.line();
		}
		this.overlay.draw(graphics, font, view, drawings, preview, target, true);
		if (this.active && this.measure.shown()) {
			this.measure.drawReading(graphics, font, view);
		}
		if (this.active && this.notePoint != null) {
			float sx = view.screenX(this.notePoint[0]);
			float sy = view.screenY(this.notePoint[1]);
			graphics.fill(Math.round(sx) - 1, Math.round(sy) - 1, Math.round(sx) + 1, Math.round(sy) + 1, 0xFF000000 | color);
		}
	}

	/** The icons on the column's buttons and the open palette, over the widgets. */
	public void drawControls(GuiGraphicsExtractor graphics) {
		if (!this.active) {
			return;
		}
		Tool[] tools = Tool.values();
		for (int i = 0; i < this.toolButtons.size(); i++) {
			Button button = this.toolButtons.get(i);
			icon(graphics, ICONS[i], button.getX(), button.getY(), 0xFFFFFFFF);
			if (tools[i] == tool) {
				outline(graphics, button.getX(), button.getY(), BUTTON, BUTTON, 0xFFFFFFFF);
			}
		}
		if (this.colourButton != null) {
			int x = this.colourButton.getX() + 4;
			int y = this.colourButton.getY() + 4;
			graphics.fill(x, y, x + 12, y + 12, 0xFF000000);
			graphics.fill(x + 1, y + 1, x + 11, y + 11, 0xFF000000 | color);
		}
		if (this.undoButton != null) {
			icon(graphics, UNDO_ICON, this.undoButton.getX(), this.undoButton.getY(), this.undoButton.active ? 0xFFFFFFFF : 0xFFA0A0A0);
		}
		if (this.paletteOpen) {
			int x0 = this.paletteX();
			int y0 = this.paletteY();
			graphics.fill(x0 - 2, y0 - 2, x0 + 4 * SWATCH + 2, y0 + 4 * SWATCH + 2, 0xE0000000);
			for (int k = 0; k < 16; k++) {
				int x = x0 + (k % 4) * SWATCH;
				int y = y0 + (k / 4) * SWATCH;
				int c = Waypoint.PALETTE[k];
				if (c == color) {
					graphics.fill(x - 1, y - 1, x + SWATCH, y + SWATCH, 0xFFFFFFFF);
				}
				graphics.fill(x, y, x + SWATCH - 1, y + SWATCH - 1, 0xFF000000);
				graphics.fill(x + 1, y + 1, x + SWATCH - 2, y + SWATCH - 2, c);
			}
		}
	}

	/** For tests: blocks across the ground the measuring tool reads, or -1 when it reads nothing. */
	public int measured() {
		return this.measure.shown() ? this.measure.blocks() : -1;
	}

	/** For tests: what the measuring tool shows, or null. */
	public @Nullable String measuredText() {
		return this.measure.shown() ? this.measure.reading().getString() : null;
	}

	/** For tests: the tool buttons, in {@link Tool} order. */
	public List<Button> toolButtons() {
		return this.toolButtons;
	}

	/** For tests: the colour button, then the undo button. */
	public List<Button> otherButtons() {
		return List.of(this.colourButton, this.undoButton);
	}

	/** For tests: the middle of palette colour {@code k}, in GUI coordinates. */
	public float[] swatch(int k) {
		return new float[] {this.paletteX() + (k % 4) * SWATCH + SWATCH / 2.0F, this.paletteY() + (k / 4) * SWATCH + SWATCH / 2.0F};
	}

	/** The help line for the tool in hand. */
	public Component help() {
		return Component.translatable("diorama.draw.help." + tool.name().toLowerCase(java.util.Locale.ROOT));
	}

	/** Pixel art centred on a 20-pixel button at (x, y). */
	public static void icon(GuiGraphicsExtractor graphics, String[] rows, int x, int y, int argb) {
		int h = rows.length;
		int w = rows[0].length();
		int x0 = x + (BUTTON - w) / 2;
		int y0 = y + (BUTTON - h) / 2;
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				if (rows[r].charAt(c) == '#') {
					graphics.fill(x0 + c, y0 + r, x0 + c + 1, y0 + r + 1, argb);
				}
			}
		}
	}

	/** A one-pixel frame like a vanilla button's highlight. */
	public static void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int argb) {
		graphics.fill(x, y, x + w, y + 1, argb);
		graphics.fill(x, y + h - 1, x + w, y + h, argb);
		graphics.fill(x, y + 1, x + 1, y + h - 1, argb);
		graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
	}

	private static double[] mapPoint(MapViewport.View view, double x, double y) {
		return new double[] {
			view.centerU() + (x - view.x() - view.width() / 2.0) / view.zoom(),
			view.centerV() + (y - view.y() - view.height() / 2.0) / view.zoom(),
		};
	}

	/** The map point under the cursor, kept straight or diagonal from the last point while Shift is held. */
	private double[] snapped(MapViewport.View view, double x, double y) {
		double[] point = mapPoint(view, x, y);
		if (this.points.isEmpty() || !shiftDown()) {
			return point;
		}
		double[] last = this.points.get(this.points.size() - 1);
		double du = point[0] - last[0];
		double dv = point[1] - last[1];
		double length = Math.hypot(du, dv);
		double angle = Math.round(Math.atan2(dv, du) / (Math.PI / 4)) * (Math.PI / 4);
		return new double[] {last[0] + Math.cos(angle) * length, last[1] + Math.sin(angle) * length};
	}

	private static double[] flatten(List<double[]> points) {
		double[] flat = new double[points.size() * 2];
		for (int i = 0; i < points.size(); i++) {
			flat[i * 2] = points.get(i)[0];
			flat[i * 2 + 1] = points.get(i)[1];
		}
		return flat;
	}

	/**
	 * A pen stroke with the points it doesn't need dropped (Ramer-Douglas-Peucker): none strays
	 * more than {@code tolerance} map units from the line kept. A tap stays a dot.
	 */
	static double[] thin(List<double[]> points, double tolerance) {
		int n = points.size();
		if (n < 3) {
			return flatten(n == 1 ? List.of(points.get(0), points.get(0)) : points);
		}
		boolean[] keep = new boolean[n];
		keep[0] = true;
		keep[n - 1] = true;
		Deque<int[]> spans = new ArrayDeque<>();
		spans.push(new int[] {0, n - 1});
		while (!spans.isEmpty()) {
			int[] span = spans.pop();
			double[] a = points.get(span[0]);
			double[] b = points.get(span[1]);
			double far = -1;
			int at = -1;
			for (int i = span[0] + 1; i < span[1]; i++) {
				double[] p = points.get(i);
				double dx = b[0] - a[0];
				double dy = b[1] - a[1];
				double length = dx * dx + dy * dy;
				double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length));
				double d = Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
				if (d > far) {
					far = d;
					at = i;
				}
			}
			if (at >= 0 && far > tolerance) {
				keep[at] = true;
				spans.push(new int[] {span[0], at});
				spans.push(new int[] {at, span[1]});
			}
		}
		List<double[]> kept = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (keep[i]) {
				kept.add(points.get(i));
			}
		}
		return flatten(kept);
	}
}
