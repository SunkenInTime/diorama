package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.CommonComponents;
import org.jspecify.annotations.Nullable;

/**
 * The list of folders that opens under a folder's name, to pick one: every folder on a line of its
 * own with how many waypoints it holds, as Xaero's Minimap picks a waypoint set from a dropdown. It
 * looks like the map's right-click menu ({@link dev.diorama.MapMenu}). The screen that opens it
 * owns it while it's open and hands it the clicks, the mouse wheel and the keys: the arrow keys go
 * through the lines and Enter picks. With more folders than fit between its name and the edge of
 * the screen it scrolls.
 */
public final class FolderMenu {
	private static final int ROW = 12;
	private static final int PAD = 3;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int COUNT = 0xFF9BA3AE;
	private static final int HIDDEN = 0xFF808080;
	/** The folder shown or picked already. */
	private static final int CURRENT = 0xFFFFFF55;
	private static final int SCROLLBAR = 2;

	/** A folder's line: its key (see {@link WaypointStore#folderKey}), its name, how many waypoints it holds or -1 to say nothing, and whether it's hidden from the maps. */
	public record Entry(String key, String name, int count, boolean hidden) {
	}

	private final List<Entry> entries;
	private final @Nullable String current;
	private final Font font;
	private final int x;
	private final int y;
	private final int width;
	/** How many lines show at once, and the first of them. */
	private final int shown;
	private int scroll;
	/** The line the arrow keys are on, or -1 before one is pressed. */
	private int cursor = -1;

	/**
	 * A menu as wide as what it opens from, which spans {@code top} to {@code bottom}: under it, or
	 * above it when there's more room there and the folders don't all fit below.
	 */
	public FolderMenu(Font font, List<Entry> entries, @Nullable String current, int x, int top, int bottom, int width, int screenHeight) {
		this.font = font;
		this.entries = List.copyOf(entries);
		this.current = current;
		this.x = x;
		this.width = width;
		int below = (screenHeight - bottom - 5) / ROW;
		int above = (top - 5) / ROW;
		boolean up = below < this.entries.size() && above > below;
		this.shown = Math.max(1, Math.min(this.entries.size(), up ? above : below));
		this.y = up ? top - 2 - this.height() : bottom + 2;
		for (int i = 0; i < this.entries.size(); i++) {
			if (this.entries.get(i).key().equals(current)) {
				this.scroll = Math.max(0, Math.min(i - this.shown / 2, this.entries.size() - this.shown));
			}
		}
	}

	private int height() {
		return this.shown * ROW + 2;
	}

	public boolean contains(double mouseX, double mouseY) {
		return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height();
	}

	/** The folder on the line under the mouse, or null: what a click there picks. */
	public @Nullable Entry under(double mouseX, double mouseY) {
		if (!this.contains(mouseX, mouseY)) {
			return null;
		}
		int row = (int) Math.floor((mouseY - this.y - 1) / ROW);
		return row >= 0 && row < this.shown ? this.entries.get(this.scroll + row) : null;
	}

	/**
	 * A key while the list is open: the arrows move to the next line, from the current folder's at
	 * first, and Enter picks that line's folder, which is returned. Null for every other key.
	 */
	public @Nullable Entry keyPressed(int key) {
		if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
			int from = this.cursor;
			for (int i = 0; from < 0 && i < this.entries.size(); i++) {
				if (this.entries.get(i).key().equals(this.current)) {
					from = i;
				}
			}
			int step = key == InputConstants.KEY_UP ? -1 : 1;
			this.cursor = from < 0 ? (step > 0 ? 0 : this.entries.size() - 1) : Math.max(0, Math.min(from + step, this.entries.size() - 1));
			this.scroll = Math.max(this.cursor - this.shown + 1, Math.min(this.scroll, this.cursor));
			return null;
		}
		boolean enter = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
		return enter && this.cursor >= 0 ? this.entries.get(this.cursor) : null;
	}

	/** The mouse wheel: a line at a time. */
	public void scroll(double amount) {
		this.scroll = Math.max(0, Math.min(this.scroll - (int) Math.signum(amount), this.entries.size() - this.shown));
	}

	public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int height = this.height();
		graphics.fill(this.x - 1, this.y - 1, this.x + this.width + 1, this.y + height + 1, 0xFF3A4048);
		graphics.fill(this.x, this.y, this.x + this.width, this.y + height, 0xF0101418);
		boolean scrolls = this.entries.size() > this.shown;
		int right = this.x + this.width - PAD - 2 - (scrolls ? SCROLLBAR + 1 : 0);
		Entry hovered = this.under(mouseX, mouseY);
		for (int i = 0; i < this.shown; i++) {
			Entry entry = this.entries.get(this.scroll + i);
			int top = this.y + 1 + i * ROW;
			if (entry == hovered || this.scroll + i == this.cursor) {
				graphics.fill(this.x + 1, top, this.x + this.width - 1, top + ROW, 0xFF2D3A4E);
			}
			int room = right - (this.x + PAD + 2);
			if (entry.count() >= 0) {
				String count = Integer.toString(entry.count());
				graphics.text(this.font, count, right - this.font.width(count), top + 2, COUNT, false);
				room -= this.font.width(count) + 6;
			}
			int colour = entry.key().equals(this.current) ? CURRENT : entry.hidden() ? HIDDEN : TEXT;
			graphics.text(this.font, this.fit(entry.name(), room), this.x + PAD + 2, top + 2, colour, false);
		}
		if (scrolls) {
			int track = height - 2;
			int handle = Math.max(4, track * this.shown / this.entries.size());
			int at = (track - handle) * this.scroll / (this.entries.size() - this.shown);
			int barX = this.x + this.width - 1 - SCROLLBAR;
			graphics.fill(barX, this.y + 1 + at, barX + SCROLLBAR, this.y + 1 + at + handle, 0xFF808890);
		}
	}

	private String fit(String text, int room) {
		if (this.font.width(text) <= room) {
			return text;
		}
		String ellipsis = CommonComponents.ELLIPSIS.getString();
		return this.font.plainSubstrByWidth(text, Math.max(0, room - this.font.width(ellipsis))) + ellipsis;
	}

	/** For tests: the middle of the line of the folder named {@code name}, in GUI coordinates, or null when it's scrolled out of view. */
	public float @Nullable [] centreOf(String name) {
		for (int i = 0; i < this.shown; i++) {
			if (this.entries.get(this.scroll + i).name().equals(name)) {
				return new float[] {this.x + this.width / 2.0F, this.y + 1 + i * ROW + ROW / 2.0F};
			}
		}
		return null;
	}

	/** For tests: every folder's line, as "name count", scrolled into view or not. */
	public List<String> labels() {
		return this.entries.stream().map(entry -> entry.count() < 0 ? entry.name() : entry.name() + " " + entry.count()).toList();
	}

	/** For tests: every folder's name in the list's order, scrolled into view or not. */
	public List<String> names() {
		return this.entries.stream().map(Entry::name).toList();
	}

	/** For tests: how many lines show at once. */
	public int shown() {
		return this.shown;
	}
}
