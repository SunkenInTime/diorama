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
 * the screen it scrolls, by the wheel or by its scrollbar. It's as wide as what it opens from, or as
 * its longest line needs where that's wider, so two long names that start alike can be told apart.
 */
public final class FolderMenu {
	private static final int ROW = 12;
	private static final int PAD = 3;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int COUNT = 0xFF9BA3AE;
	private static final int HIDDEN = 0xFF808080;
	/** The folder shown or picked already. */
	private static final int CURRENT = 0xFFFFFF55;
	/** The scrollbar: wide enough to see that the list goes on and to take hold of. */
	private static final int SCROLLBAR = 4;
	private static final int HANDLE_MIN = 8;
	/** No wider than this for its names, however long: a name longer still is cut with an ellipsis. */
	private static final int MAX_WIDTH = 240;

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
	/** How far under the top of the scrollbar's handle the mouse took hold of it, or -1 while it isn't held. */
	private double held = -1;

	/**
	 * A menu at least as wide as what it opens from, which spans {@code top} to {@code bottom}:
	 * under it, or above it when there's more room there and the folders don't all fit below. Wider
	 * than that for a line that needs it, and then moved left as far as it takes to stay on the screen.
	 */
	public FolderMenu(Font font, List<Entry> entries, @Nullable String current, int x, int top, int bottom, int width, int screenWidth, int screenHeight) {
		this.font = font;
		this.entries = List.copyOf(entries);
		this.current = current;
		int below = (screenHeight - bottom - 5) / ROW;
		int above = (top - 5) / ROW;
		boolean up = below < this.entries.size() && above > below;
		this.shown = Math.max(1, Math.min(this.entries.size(), up ? above : below));
		this.y = up ? top - 2 - this.height() : bottom + 2;
		int needed = 0;
		for (Entry entry : this.entries) {
			needed = Math.max(needed, font.width(entry.name()) + countWidth(font, entry));
		}
		needed += 2 * (PAD + 2) + (this.scrolls() ? SCROLLBAR + 1 : 0);
		this.width = Math.max(width, Math.min(needed, Math.min(MAX_WIDTH, screenWidth - 4)));
		this.x = Math.max(Math.min(x, 2), Math.min(x, screenWidth - 2 - this.width));
		for (int i = 0; i < this.entries.size(); i++) {
			if (this.entries.get(i).key().equals(current)) {
				this.scroll = Math.max(0, Math.min(i - this.shown / 2, this.entries.size() - this.shown));
			}
		}
	}

	private int height() {
		return this.shown * ROW + 2;
	}

	private boolean scrolls() {
		return this.entries.size() > this.shown;
	}

	/** The room a line's count takes from its name, with the gap between them. */
	private static int countWidth(Font font, Entry entry) {
		return entry.count() < 0 ? 0 : font.width(Integer.toString(entry.count())) + 6;
	}

	/** The room every line has for its name and count. */
	private int room() {
		return this.width - 2 * (PAD + 2) - (this.scrolls() ? SCROLLBAR + 1 : 0);
	}

	/** The scrollbar's handle: how tall it is, and how far down its track. */
	private int[] handle() {
		int track = this.height() - 2;
		int handle = Math.max(HANDLE_MIN, track * this.shown / this.entries.size());
		return new int[] {handle, (track - handle) * this.scroll / (this.entries.size() - this.shown)};
	}

	/** Over the scrollbar, or the gap between it and the lines' text: a click that just misses it picks nothing. */
	private boolean overScrollbar(double mouseX, double mouseY) {
		return this.scrolls() && this.contains(mouseX, mouseY) && mouseX >= this.x + this.width - PAD - 2 - SCROLLBAR - 1;
	}

	/**
	 * Scrolls to a line. The arrow keys' line is forgotten once it's out of view, so Enter never
	 * picks a folder that isn't showing.
	 */
	private void scrollTo(int line) {
		this.scroll = Math.max(0, Math.min(line, this.entries.size() - this.shown));
		if (this.cursor < this.scroll || this.cursor >= this.scroll + this.shown) {
			this.cursor = -1;
		}
	}

	/**
	 * A left press: on the scrollbar it takes hold of the handle, bringing it under the mouse first
	 * from anywhere else on the track, and the list stays open. False anywhere else.
	 */
	public boolean press(double mouseX, double mouseY) {
		if (!this.overScrollbar(mouseX, mouseY)) {
			return false;
		}
		int[] handle = this.handle();
		double in = mouseY - (this.y + 1 + handle[1]);
		if (in >= 0 && in < handle[0]) {
			// Held where it was pressed: the list doesn't move until the mouse does.
			this.held = in;
		} else {
			this.held = handle[0] / 2.0;
			this.drag(mouseY);
		}
		return true;
	}

	/** The mouse moved with the button down: the handle follows it if it's held. False if it isn't. */
	public boolean drag(double mouseY) {
		if (this.held < 0 || !this.scrolls()) {
			return false;
		}
		int free = this.height() - 2 - this.handle()[0];
		int last = this.entries.size() - this.shown;
		double t = free <= 0 ? 0 : (mouseY - this.held - (this.y + 1)) / free;
		this.scrollTo((int) Math.max(0, Math.min(Math.round(t * last), last)));
		return true;
	}

	/** The button was let go. */
	public void release() {
		this.held = -1;
	}

	public boolean contains(double mouseX, double mouseY) {
		return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height();
	}

	/** The folder on the line under the mouse, or null: what a click there picks. */
	public @Nullable Entry under(double mouseX, double mouseY) {
		if (!this.contains(mouseX, mouseY) || this.overScrollbar(mouseX, mouseY) || this.held >= 0) {
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
		this.scrollTo(this.scroll - (int) Math.signum(amount));
	}

	public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int height = this.height();
		graphics.fill(this.x - 1, this.y - 1, this.x + this.width + 1, this.y + height + 1, 0xFF3A4048);
		graphics.fill(this.x, this.y, this.x + this.width, this.y + height, 0xF0101418);
		boolean scrolls = this.scrolls();
		int right = this.x + this.width - PAD - 2 - (scrolls ? SCROLLBAR + 1 : 0);
		Entry hovered = this.under(mouseX, mouseY);
		for (int i = 0; i < this.shown; i++) {
			Entry entry = this.entries.get(this.scroll + i);
			int top = this.y + 1 + i * ROW;
			if (entry == hovered || this.scroll + i == this.cursor) {
				graphics.fill(this.x + 1, top, this.x + this.width - 1, top + ROW, 0xFF2D3A4E);
			}
			int room = this.room() - countWidth(this.font, entry);
			if (entry.count() >= 0) {
				String count = Integer.toString(entry.count());
				graphics.text(this.font, count, right - this.font.width(count), top + 2, COUNT, false);
			}
			int colour = entry.key().equals(this.current) ? CURRENT : entry.hidden() ? HIDDEN : TEXT;
			graphics.text(this.font, this.fit(entry.name(), room), this.x + PAD + 2, top + 2, colour, false);
		}
		if (scrolls) {
			int[] handle = this.handle();
			int barX = this.x + this.width - 1 - SCROLLBAR;
			boolean lit = this.held >= 0 || this.overScrollbar(mouseX, mouseY);
			graphics.fill(barX, this.y + 1, barX + SCROLLBAR, this.y + height - 1, 0xFF24292F);
			graphics.fill(barX, this.y + 1 + handle[1], barX + SCROLLBAR, this.y + 1 + handle[1] + handle[0], lit ? 0xFFC8CED6 : 0xFF808890);
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

	/** For tests: the names the list is too narrow for, which it cuts short. */
	public List<String> cutNames() {
		return this.entries.stream().filter(entry -> this.font.width(entry.name()) > this.room() - countWidth(this.font, entry)).map(Entry::name).toList();
	}

	/** For tests: where the list is on the screen, frame and all, as left, top, width and height. */
	public int[] bounds() {
		return new int[] {this.x - 1, this.y - 1, this.width + 2, this.height() + 2};
	}

	/** For tests: the middle of the scrollbar's handle, or null when every folder fits. */
	public float @Nullable [] handleCentre() {
		if (!this.scrolls()) {
			return null;
		}
		int[] handle = this.handle();
		return new float[] {this.x + this.width - 1 - SCROLLBAR / 2.0F, this.y + 1 + handle[1] + handle[0] / 2.0F};
	}

	/** For tests: the first line in view, counted from the top of the list. */
	public int scrolled() {
		return this.scroll;
	}

	/** For tests: how many lines show at once. */
	public int shown() {
		return this.shown;
	}
}
