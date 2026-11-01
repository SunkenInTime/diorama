package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.render.Pins;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Creates or edits one waypoint: name, initials, position, colour, folder and whether it shows. A
 * preview on the right stands its pin on a piece of the map, as the map will draw it, and follows
 * the colour and initials as they change; the folder, Share in chat and Teleport sit under it. The footer is only
 * the two ways out, Save and Cancel. Deleting lives on the list's rows and the map's menu.
 */
public final class WaypointEditScreen extends Screen {
	/** The form on the left, the preview on the right, as wide together as today's list. */
	private static final int WIDTH = 292;
	private static final int FORM = 200;
	private static final int PREVIEW_LEFT = 212;
	/** Each colour swatch's cell; the swatch is a pixel in from each side. */
	private static final int SLOT = 12;
	/** The hue and shade bars under the swatches, side by side. */
	private static final int BAR_HEIGHT = 8;
	private static final int BAR_WIDTH = 96;
	private static final int BAR_GAP = 8;
	/** Vanilla's colour for a number field it can't read, from the game rules screen. */
	private static final int INVALID_TEXT = 0xFFFF0000;
	private static final int LABEL = 0xFFA0A0A0;

	private final @Nullable Screen parent;
	private final Waypoint waypoint;
	private final boolean isNew;
	/** What the preview draws: the waypoint as the form shows it now. */
	private final Waypoint preview = new Waypoint();
	private final MapPiece piece = new MapPiece(64, 40, 12);
	private EditBox name;
	private EditBox initials;
	private EditBox x;
	private EditBox y;
	private EditBox z;
	private @Nullable Button save;
	private @Nullable Button share;
	private @Nullable Button teleport;
	private int color;
	private boolean visible;
	private EditBox hex;
	private int swatchX;
	private int swatchY;
	/** Where the colour sits on the hue and shade bars. */
	private float hue;
	private float shade = 0.5F;
	/** The bar being dragged: 0 none, 1 hue, 2 shade. */
	private int dragging;
	/** The custom folder name, or empty for the dimension's own folder. */
	private String folder;
	private @Nullable Button folderButton;
	/** The icon picked, by {@link dev.diorama.render.WaypointIcons} id; empty shows the initials. */
	private String icon;
	private @Nullable Button iconButton;

	public WaypointEditScreen(@Nullable Screen parent, Waypoint waypoint, boolean isNew) {
		super(Component.translatable(isNew ? "diorama.waypoint.new" : "diorama.waypoint.edit"));
		this.parent = parent;
		this.waypoint = waypoint;
		this.isNew = isNew;
		this.color = waypoint.color;
		this.visible = waypoint.visible;
		this.icon = waypoint.icon == null ? "" : waypoint.icon;
		WaypointStore store = WaypointStore.get();
		String known = store == null ? null : store.folderName(waypoint.folder);
		this.folder = known == null ? "" : known;
		this.matchBars();
	}

	private int left() {
		return this.width / 2 - WIDTH / 2;
	}

	private int top() {
		return this.height / 2 - 92;
	}

	@Override
	protected void init() {
		int left = this.left();
		int top = this.top();
		int cx = left + FORM / 2;
		// Resizing the window or changing GUI scale runs init() again: keep what's typed, not the saved waypoint.
		this.name = this.field(left, top + 16, 112, draft(this.name, this.waypoint.name), "diorama.waypoint.name", 32);
		this.iconButton = this.addRenderableWidget(Button.builder(this.iconLabel(), b -> this.pickIcon())
			.bounds(left + 116, top + 16, 40, 20).build());
		// A death marker always shows its headstone.
		this.iconButton.active = this.waypoint.kind != Waypoint.Kind.DEATH;
		this.initials = this.field(left + 160, top + 16, 40, draft(this.initials, this.waypoint.initials), "diorama.waypoint.initials", Waypoint.MAX_INITIALS);
		this.x = this.field(left, top + 56, 64, draft(this.x, Integer.toString(this.waypoint.x)), "diorama.waypoint.x", 9);
		this.y = this.field(left + 68, top + 56, 64, draft(this.y, Integer.toString(this.waypoint.y)), "diorama.waypoint.y", 9);
		this.z = this.field(left + 136, top + 56, 64, draft(this.z, Integer.toString(this.waypoint.z)), "diorama.waypoint.z", 9);
		this.x.setResponder(text -> this.validate());
		this.y.setResponder(text -> this.validate());
		this.z.setResponder(text -> this.validate());
		this.swatchX = cx - WaypointColours.COLUMNS * SLOT / 2;
		this.swatchY = top + 92;
		// The hex field sits right of a chip showing the colour, then Random.
		this.hex = this.field(left + 24, top + 132, 70, draft(this.hex, WaypointColours.hex(this.color)), "diorama.waypoint.hex", 7);
		this.hex.setResponder(text -> {
			Integer picked = WaypointColours.parse(text);
			this.hex.setTextColor(picked != null ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT);
			if (picked != null && (picked & 0xFFFFFF) != (this.color & 0xFFFFFF)) {
				this.color = picked;
				this.matchBars();
			}
		});
		// Always a new colour, never close to the one shown.
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.random"), b -> this.pick(WaypointColours.random(this.color)))
			.bounds(left + 98, top + 132, FORM - 98, 20).build());
		//? if >=1.21 {
		this.addRenderableWidget(Checkbox.builder(Component.translatable("diorama.waypoint.show_on_maps"), this.font)
			.pos(left, top + 157)
			.selected(this.visible)
			.onValueChange((box, value) -> this.visible = value)
			.build());
		//?} else {
		/*// 1.20.1's checkbox has no builder and tells no one it changed: a 20 pixel box with the
		// label 4 pixels right of it.
		Component showOnMaps = Component.translatable("diorama.waypoint.show_on_maps");
		this.addRenderableWidget(new Checkbox(left, top + 157, 24 + this.font.width(showOnMaps), 20, showOnMaps, this.visible) {
			@Override
			public void onPress() {
				super.onPress();
				WaypointEditScreen.this.visible = this.selected();
			}
		});
		*///?}

		// What to do with this place, under its preview. A new waypoint has none until it's saved.
		int previewLeft = left + PREVIEW_LEFT;
		int previewWidth = WIDTH - PREVIEW_LEFT;
		this.share = null;
		this.teleport = null;
		this.folderButton = this.addRenderableWidget(Button.builder(this.folderLabel(), b -> this.nextFolder())
			.bounds(previewLeft, this.previewBottom() + 14, previewWidth, 20)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("diorama.waypoint.folder_tooltip")))
			.build());
		if (!this.isNew) {
			this.share = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.share"), b -> this.share())
				.bounds(previewLeft, this.previewBottom() + 38, previewWidth, 20).build());
			this.teleport = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.teleport"), b -> this.teleport())
				.bounds(previewLeft, this.previewBottom() + 62, previewWidth, 20).build());
		}

		// The footer: only the two ways out, Save then Cancel, as vanilla orders them.
		int half = (WIDTH - 4) / 2;
		this.save = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.save"), b -> this.save())
			.bounds(left, top + 192, half, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(left + WIDTH - half, top + 192, half, 20).build());
		this.validate();
		this.setInitialFocus(this.name);
	}

	/** The icon's name cut to fit its narrow button. */
	private Component iconLabel() {
		String text = WaypointIconScreen.name(this.icon).getString();
		if (this.font.width(text) > 34) {
			text = this.font.plainSubstrByWidth(text, 34 - this.font.width("..")) + "..";
		}
		return Component.literal(text);
	}

	private void pickIcon() {
		this.preview.name = this.name.getValue().trim().isEmpty() ? this.waypoint.name : this.name.getValue().trim();
		this.preview.initials = this.initials.getValue().trim();
		this.preview.color = this.color;
		this.minecraft.gui.setScreen(new WaypointIconScreen(this, this.preview, this.icon, id -> {
			this.icon = id;
			if (this.iconButton != null) {
				this.iconButton.setMessage(this.iconLabel());
			}
		}));
	}

	private int previewTop() {
		return this.top() + 16;
	}

	private int previewBottom() {
		return this.top() + 98;
	}

	/** The folders this waypoint can go in: its dimension's own, then the custom ones. */
	private List<String> folders() {
		List<String> out = new ArrayList<>();
		out.add("");
		WaypointStore store = WaypointStore.get();
		if (store != null) {
			out.addAll(store.customFolders());
		}
		return out;
	}

	private void nextFolder() {
		List<String> folders = this.folders();
		int index = folders.indexOf(this.folder);
		this.folder = folders.get((index + 1) % folders.size());
		if (this.folderButton != null) {
			this.folderButton.setMessage(this.folderLabel());
		}
	}

	/**
	 * The folder's name alone, cut to fit the narrow button; the button's tooltip and the "Folder"
	 * caption drawn on the preview say what it is.
	 */
	private Component folderLabel() {
		Component name = this.folder.isEmpty() ? Waypoints.dimensionName(this.waypoint.dimension) : Component.literal(this.folder);
		String text = name.getString();
		int room = WIDTH - PREVIEW_LEFT - 8;
		if (this.font.width(text) > room) {
			text = this.font.plainSubstrByWidth(text, room - this.font.width("...")) + "...";
		}
		return Component.literal(text);
	}

	private static String draft(@Nullable EditBox box, String saved) {
		return box != null ? box.getValue() : saved;
	}

	/**
	 * Colours a coordinate that isn't a whole number red, the way vanilla's game rules screen does,
	 * and keeps Save, Share and Teleport off until every coordinate reads.
	 */
	private void validate() {
		boolean valid = true;
		for (EditBox box : new EditBox[] {this.x, this.y, this.z}) {
			boolean reads = parse(box.getValue()) != null;
			box.setTextColor(reads ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT);
			valid &= reads;
		}
		if (this.save != null) {
			this.save.active = valid;
		}
		if (this.share != null) {
			this.share.active = valid;
		}
		if (this.teleport != null) {
			this.teleport.active = valid && Waypoints.canTeleport(this.waypoint.dimension);
		}
	}

	private EditBox field(int x, int y, int width, String value, String hint, int maxLength) {
		EditBox box = new EditBox(this.font, x, y, width, 20, Component.translatable(hint));
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setHint(Component.translatable(hint));
		return this.addRenderableWidget(box);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// A 1.20.1 screen draws its own background.
		//? if <1.21 {
		/*this.renderBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.left();
		int top = this.top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 12, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("diorama.waypoint.name"), left, top + 5, LABEL, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.icon"), left + 116, top + 5, LABEL, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.initials"), left + 160, top + 5, LABEL, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.position"), left, top + 45, LABEL, true);
		Component colourLabel = Component.translatable("diorama.waypoint.colour");
		graphics.text(this.font, colourLabel, left, top + 81, LABEL, true);
		WaypointColours.Swatch current = WaypointColours.named(this.color);
		if (current != null) {
			graphics.text(this.font, Component.literal("· ").append(current.name()), left + this.font.width(colourLabel) + 4, top + 81, 0xFFFFFFFF, true);
		}
		this.drawSwatches(graphics, mouseX, mouseY);
		this.drawPreview(graphics);
	}

	/**
	 * The colours as flat swatches on a dark panel, the hovered one ringed in grey and the picked
	 * one in white; then the hue bar and the shade bar for any other colour, each with a marker
	 * where the colour sits; then a chip of the colour itself beside its hex.
	 */
	private void drawSwatches(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int rows = WaypointColours.rows();
		int x0 = this.swatchX;
		int y0 = this.swatchY;
		int x1 = x0 + WaypointColours.COLUMNS * SLOT;
		int y1 = y0 + rows * SLOT;
		graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0x80000000);
		int hovered = this.swatchAt(mouseX, mouseY);
		int selected = -1;
		for (int i = 0; i < WaypointColours.ALL.size(); i++) {
			int sx = x0 + (i % WaypointColours.COLUMNS) * SLOT;
			int sy = y0 + (i / WaypointColours.COLUMNS) * SLOT;
			int argb = WaypointColours.ALL.get(i).argb();
			if (i == hovered) {
				graphics.fill(sx, sy, sx + SLOT, sy + SLOT, 0xFFA0A0A0);
			}
			graphics.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, argb);
			if ((argb & 0xFFFFFF) == (this.color & 0xFFFFFF) && selected < 0) {
				selected = i;
			}
		}
		if (selected >= 0) {
			int sx = x0 + (selected % WaypointColours.COLUMNS) * SLOT;
			int sy = y0 + (selected / WaypointColours.COLUMNS) * SLOT;
			ring(graphics, sx - 1, sy - 1, sx + SLOT + 1, sy + SLOT + 1, 0xFF000000);
			ring(graphics, sx, sy, sx + SLOT, sy + SLOT, 0xFFFFFFFF);
		}
		int barY = this.barY();
		int hueX = this.hueX();
		int shadeX = hueX + BAR_WIDTH + BAR_GAP;
		graphics.fill(hueX - 1, barY - 1, hueX + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, 0xFF000000);
		graphics.fill(shadeX - 1, barY - 1, shadeX + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, 0xFF000000);
		for (int i = 0; i < BAR_WIDTH; i++) {
			float t = i / (float) (BAR_WIDTH - 1);
			graphics.fill(hueX + i, barY, hueX + i + 1, barY + BAR_HEIGHT, WaypointColours.rgb(t, 1, 1));
			graphics.fill(shadeX + i, barY, shadeX + i + 1, barY + BAR_HEIGHT, WaypointColours.shade(this.hue, t));
		}
		marker(graphics, hueX + Math.round(this.hue * (BAR_WIDTH - 1)), barY);
		marker(graphics, shadeX + Math.round(this.shade * (BAR_WIDTH - 1)), barY);
		// The colour chip, framed like the text fields beside it.
		int chipX = this.left();
		int chipY = this.top() + 132;
		graphics.fill(chipX, chipY, chipX + 20, chipY + 20, 0xFFA0A0A0);
		graphics.fill(chipX + 1, chipY + 1, chipX + 19, chipY + 19, 0xFF000000);
		graphics.fill(chipX + 2, chipY + 2, chipX + 18, chipY + 18, 0xFF000000 | this.color);
		if (hovered >= 0) {
			WaypointColours.Swatch swatch = WaypointColours.ALL.get(hovered);
			graphics.setTooltipForNextFrame(this.font, Component.empty().append(swatch.name()).append(" " + WaypointColours.hex(swatch.argb())), mouseX, mouseY);
		}
	}

	private static void ring(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
		graphics.fill(x0, y0, x1, y0 + 1, color);
		graphics.fill(x0, y1 - 1, x1, y1, color);
		graphics.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
		graphics.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
	}

	/** A bar's marker: a white line a pixel wide in a black outline, a little taller than the bar. */
	private static void marker(GuiGraphicsExtractor graphics, int x, int barY) {
		graphics.fill(x - 1, barY - 2, x + 2, barY + BAR_HEIGHT + 2, 0xFF000000);
		graphics.fill(x, barY - 1, x + 1, barY + BAR_HEIGHT + 1, 0xFFFFFFFF);
	}

	private int barY() {
		return this.swatchY + WaypointColours.rows() * SLOT + 4;
	}

	private int hueX() {
		return this.left() + (FORM - 2 * BAR_WIDTH - BAR_GAP) / 2;
	}

	/** The swatch under a point, or -1. */
	private int swatchAt(double x, double y) {
		if (x < this.swatchX || y < this.swatchY) {
			return -1;
		}
		int column = (int) ((x - this.swatchX) / SLOT);
		int row = (int) ((y - this.swatchY) / SLOT);
		int i = row * WaypointColours.COLUMNS + column;
		return column < WaypointColours.COLUMNS && i < WaypointColours.ALL.size() ? i : -1;
	}

	/** The bar under a point: 1 hue, 2 shade, 0 neither. Its marker overhangs it, so a pixel more counts. */
	private int barAt(double x, double y) {
		int barY = this.barY();
		if (y < barY - 2 || y >= barY + BAR_HEIGHT + 2) {
			return 0;
		}
		int hueX = this.hueX();
		int shadeX = hueX + BAR_WIDTH + BAR_GAP;
		if (x >= hueX - 1 && x < hueX + BAR_WIDTH + 1) {
			return 1;
		}
		return x >= shadeX - 1 && x < shadeX + BAR_WIDTH + 1 ? 2 : 0;
	}

	/** Moves a bar's marker to x and takes the colour it gives. */
	private void dragBar(int bar, double x) {
		int start = bar == 1 ? this.hueX() : this.hueX() + BAR_WIDTH + BAR_GAP;
		float t = (float) Math.max(0, Math.min(1, (x - start) / (BAR_WIDTH - 1)));
		if (bar == 1) {
			this.hue = Math.min(t, 0.999F);
		} else {
			this.shade = t;
		}
		this.color = WaypointColours.shade(this.hue, this.shade);
		this.hex.setValue(WaypointColours.hex(this.color));
	}

	/** Picks a colour and shows it in the hex field and on the bars. */
	private void pick(int argb) {
		this.color = argb;
		this.matchBars();
		this.hex.setValue(WaypointColours.hex(argb));
	}

	/** Puts the bars' markers where the colour is; a grey keeps the hue it had. */
	private void matchBars() {
		float[] hsv = WaypointColours.hsv(this.color);
		if (hsv[1] > 0.05F) {
			this.hue = hsv[0];
		}
		this.shade = WaypointColours.shadeOf(this.color);
	}

	/**
	 * The pin as the map will draw it, standing on the same piece of map as the empty list, in a
	 * list panel of its own. The pin's head rises above the piece, so the two are centred together.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics) {
		int x0 = this.left() + PREVIEW_LEFT;
		int x1 = this.left() + WIDTH;
		int y0 = this.previewTop();
		int y1 = this.previewBottom();
		graphics.text(this.font, Component.translatable("diorama.waypoint.preview"), x0, this.top() + 5, LABEL, true);
		ScreenArt.list(graphics, x0, y0, x1, y1);
		this.preview.name = this.name.getValue().trim().isEmpty() ? this.waypoint.name : this.name.getValue().trim();
		this.preview.initials = this.initials.getValue().trim();
		this.preview.color = this.color;
		this.preview.kind = this.waypoint.kind;
		this.preview.icon = this.icon;
		// At twice its size, the pin reaches this far above its tip: its head, its rim and its point.
		// An icon's pin is bigger.
		float radius = Pins.reach(this.preview, Pins.LARGE) - 1;
		int rise = Math.round(2 * (Pins.headAbove(this.preview, Pins.LARGE) + radius)) - this.piece.tipY();
		int total = rise + this.piece.outerHeight();
		int pieceX = (x0 + x1) / 2 - this.piece.outerWidth() / 2;
		int pieceY = y0 + (y1 - y0 - total) / 2 + rise;
		this.piece.draw(graphics, pieceX, pieceY);
		// The caption of the folder button under the panel.
		graphics.text(this.font, Component.translatable("diorama.waypoint.folder_caption"), x0, y1 + 3, LABEL, true);
		graphics.pose().pushMatrix();
		graphics.pose().translate(pieceX + this.piece.tipX(), pieceY + this.piece.tipY());
		graphics.pose().scale(2);
		Pins.standing(graphics, this.preview, 0, 0, Pins.LARGE);
		graphics.pose().popMatrix();
	}

	// Mouse events came as separate numbers before 1.21.9.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return this.clicked(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.x()) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.dragging = 0;
		return super.mouseReleased(event);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return this.clicked(x, y, button) || super.mouseClicked(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(x) || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		this.dragging = 0;
		return super.mouseReleased(x, y, button);
	}
	*///?}

	/** Picks the colour swatch or the bar spot under a left click; false if the click was on neither. */
	private boolean clicked(double x, double y, int button) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			int i = this.swatchAt(x, y);
			if (i >= 0) {
				this.pick(WaypointColours.ALL.get(i).argb());
				return true;
			}
			int bar = this.barAt(x, y);
			if (bar != 0) {
				this.dragging = bar;
				this.dragBar(bar, x);
				return true;
			}
		}
		return false;
	}

	/** Follows the mouse along the bar being dragged. */
	private boolean dragged(double x) {
		if (this.dragging == 0) {
			return false;
		}
		this.dragBar(this.dragging, x);
		return true;
	}

	/** The waypoint being edited. For tests. */
	public Waypoint waypoint() {
		return this.waypoint;
	}

	/** The waypoint as the fields show it now, edits included, without saving; null while a coordinate isn't a number. */
	private @Nullable Waypoint shown() {
		Integer x = parse(this.x.getValue());
		Integer y = parse(this.y.getValue());
		Integer z = parse(this.z.getValue());
		if (x == null || y == null || z == null) {
			return null;
		}
		String name = this.name.getValue().trim();
		Waypoint shown = new Waypoint(name.isEmpty() ? this.waypoint.name : name, x, y, z, this.waypoint.dimension, this.color);
		String initials = this.initials.getValue().trim();
		shown.initials = initials.isEmpty() ? shown.initials : initials;
		return shown;
	}

	/** Sends the waypoint to chat for others to add. */
	private void share() {
		Waypoint shown = this.shown();
		if (shown == null) {
			return;
		}
		if (WaypointShare.send(shown)) {
			this.onClose();
		}
	}

	private void teleport() {
		Waypoint shown = this.shown();
		if (shown == null) {
			return;
		}
		Waypoints.teleport(shown.dimension, shown.x, shown.y, shown.z);
		this.onClose();
	}

	private void save() {
		Waypoint shown = this.shown();
		if (shown == null) {
			return;
		}
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			this.onClose();
			return;
		}
		this.waypoint.name = shown.name;
		this.waypoint.initials = this.initials.getValue().trim();
		this.waypoint.x = shown.x;
		this.waypoint.y = shown.y;
		this.waypoint.z = shown.z;
		this.waypoint.color = this.color;
		this.waypoint.icon = this.icon;
		this.waypoint.visible = this.visible;
		this.waypoint.folder = this.folder;
		// Saving a temporary waypoint from the editor keeps it; otherwise a renamed one would still
		// vanish on leaving the world.
		if (this.waypoint.kind == Waypoint.Kind.TEMPORARY) {
			this.waypoint.kind = Waypoint.Kind.MANUAL;
		}
		if (this.isNew) {
			store.add(this.waypoint);
		} else {
			store.changed();
		}
		this.onClose();
	}

	private static @Nullable Integer parse(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public void removed() {
		this.piece.close();
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
