package dev.diorama;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The menu right-clicking the map opens at the cursor: grey lines about what was clicked, then
 * what can be done there. {@link MapScreen} owns it while it's open and hands it the clicks.
 */
public final class MapMenu {
	private static final int ROW = 12;
	private static final int PAD = 3;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int INFO = 0xFF9BA3AE;
	private static final int OFF = 0xFF5C636D;
	private static final int CONFIRM = 0xFFFF7A70;
	private static final int DONE = 0xFF7FD17F;

	/** One line. Without an action it's information and can't be clicked. */
	public static final class Item {
		private final Component label;
		private final @Nullable Runnable action;
		private final boolean active;
		/** Shown in place of the label after a first click; the second click runs the action. */
		private final @Nullable Component confirm;
		/** The action runs on the first click and the menu stays open; {@link #confirm} then says it's done. */
		private final boolean stays;
		private boolean armed;

		private Item(Component label, @Nullable Runnable action, boolean active, @Nullable Component confirm, boolean stays) {
			this.label = label;
			this.action = action;
			this.active = active;
			this.confirm = confirm;
			this.stays = stays;
		}

		/** A grey line of information. */
		public static Item info(Component label) {
			return new Item(label, null, false, null, false);
		}

		/** A white line of information, for the name of what was clicked. */
		public static Item title(Component label) {
			return new Item(label, null, true, null, false);
		}

		public static Item action(Component label, boolean active, Runnable action) {
			return new Item(label, action, active, null, false);
		}

		/** An action that asks for a second click first. */
		public static Item confirmed(Component label, Component confirm, Runnable action) {
			return new Item(label, action, true, confirm, false);
		}

		/** An action with nothing to see afterwards: it keeps the menu open and shows {@code done} in its place. */
		public static Item stays(Component label, Component done, Runnable action) {
			return new Item(label, action, true, done, true);
		}

		private Component shown() {
			return this.armed && this.confirm != null ? this.confirm : this.label;
		}

		private boolean clickable() {
			return this.action != null && this.active;
		}
	}

	private final List<Item> items;
	private final Font font;
	private final int x;
	private final int y;
	private final int width;

	/** A menu with its corner at the cursor, moved in where it would run off the screen. */
	public MapMenu(List<Item> items, Font font, double cursorX, double cursorY, int screenWidth, int screenHeight) {
		this.items = items;
		this.font = font;
		this.width = this.measure();
		int height = this.height();
		this.x = (int) Math.max(0, Math.min(cursorX, screenWidth - this.width));
		this.y = (int) Math.max(0, Math.min(cursorY, screenHeight - height));
	}

	private int measure() {
		int widest = 0;
		for (Item item : this.items) {
			widest = Math.max(widest, this.font.width(item.label));
			if (item.confirm != null) {
				widest = Math.max(widest, this.font.width(item.confirm));
			}
		}
		return widest + PAD * 2 + 4;
	}

	private int height() {
		return this.items.size() * ROW + 2;
	}

	public boolean contains(double mouseX, double mouseY) {
		return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height();
	}

	private @Nullable Item under(double mouseX, double mouseY) {
		if (!this.contains(mouseX, mouseY)) {
			return null;
		}
		int row = (int) Math.floor((mouseY - this.y - 1) / ROW);
		return row >= 0 && row < this.items.size() ? this.items.get(row) : null;
	}

	/** A click inside the menu; true when it ran an action and the menu should close. */
	public boolean click(double mouseX, double mouseY) {
		Item item = this.under(mouseX, mouseY);
		if (item == null || !item.clickable()) {
			return false;
		}
		if (item.stays) {
			item.action.run();
			item.armed = true;
			return false;
		}
		if (item.confirm != null && !item.armed) {
			item.armed = true;
			return false;
		}
		item.action.run();
		return true;
	}

	public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int height = this.height();
		graphics.fill(this.x - 1, this.y - 1, this.x + this.width + 1, this.y + height + 1, 0xFF3A4048);
		graphics.fill(this.x, this.y, this.x + this.width, this.y + height, 0xF0101418);
		Item hovered = this.under(mouseX, mouseY);
		for (int i = 0; i < this.items.size(); i++) {
			Item item = this.items.get(i);
			int top = this.y + 1 + i * ROW;
			if (item == hovered && item.clickable()) {
				graphics.fill(this.x + 1, top, this.x + this.width - 1, top + ROW, 0xFF2D3A4E);
			}
			int colour = item.action == null ? (item.active ? TEXT : INFO) : !item.active ? OFF : !item.armed ? TEXT : item.stays ? DONE : CONFIRM;
			graphics.text(this.font, item.shown(), this.x + PAD + 2, top + 2, colour, false);
		}
	}

	/** For tests: the middle of the line labelled {@code label}, in GUI coordinates. */
	public float @Nullable [] centreOf(String label) {
		for (int i = 0; i < this.items.size(); i++) {
			if (this.items.get(i).shown().getString().equals(label)) {
				return new float[] {this.x + this.width / 2.0F, this.y + 1 + i * ROW + ROW / 2.0F};
			}
		}
		return null;
	}

	/** For tests: every line as shown. */
	public List<String> labels() {
		return this.items.stream().map(item -> item.shown().getString()).toList();
	}
}
