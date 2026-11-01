package dev.diorama;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.waypoint.ScreenArt;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * One dimension's maps ({@link Maps}), in vanilla's in-world list, laid out like the waypoint list.
 * The bar at the top steps through the dimensions, with New map at its right end. Clicking a row
 * selects it and shows its actions on it: Use this map, Rename and Delete, which asks for a second
 * click in place. The map in use can't be deleted. Double-clicking a row uses it.
 */
public final class MapsScreen extends Screen {
	private static final int LIST_WIDTH = 340;
	private static final int NEW_WIDTH = 100;
	private static final int ROW = 24;
	private static final int PITCH = 26;
	private static final int BAR_Y = 22;
	private static final int LIST_TOP = 48;
	private static final int ARROW = 20;
	private static final int NAME_WIDTH = 160;
	private static final int TEXT_LEFT = 6;
	private static final int ACTION_GAP = 2;
	private static final long DOUBLE_CLICK_MS = 250;
	private static final int DETAIL = 0xFF909090;
	private static final int HINT = 0xFFA0A0A0;
	/** Map sizes by folder, worked out off the client thread each time the rows are made. */
	private final Map<Path, CompletableFuture<Long>> sizes = new ConcurrentHashMap<>();

	private final @Nullable Screen parent;
	private final List<String> dimensions = new ArrayList<>();
	private @Nullable String dimension;
	private final List<Maps.Info> rows = new ArrayList<>();
	private @Nullable String selected;
	/** The selected row's Delete was clicked once: its buttons are one Click again to delete. */
	private boolean armed;
	private final List<Button> actions = new ArrayList<>();
	private @Nullable Button use;
	private @Nullable Button delete;
	private @Nullable Button confirm;
	private int scroll;
	private int listBottom;
	private int visibleRows;
	private long lastClick;
	private @Nullable String lastClicked;

	public MapsScreen(@Nullable Screen parent) {
		super(Component.translatable("diorama.maps.title"));
		this.parent = parent;
	}

	/** Shows this dimension's maps with this one selected when the screen opens again. */
	void show(String dimension, @Nullable String selected) {
		this.dimension = dimension;
		this.selected = selected;
		this.armed = false;
	}

	/**
	 * The Overworld, the Nether and the End always, then the player's dimension, then any other with
	 * maps of its own.
	 */
	private void collectDimensions(@Nullable String here, @Nullable Maps maps) {
		LinkedHashSet<String> found = new LinkedHashSet<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
		if (here != null) {
			found.add(here);
		}
		// The map may have opened this on a modded dimension it was looking at.
		if (this.dimension != null) {
			found.add(this.dimension);
		}
		if (maps != null) {
			found.addAll(maps.dimensions());
		}
		this.dimensions.clear();
		this.dimensions.addAll(found);
		if (this.dimension == null || !this.dimensions.contains(this.dimension)) {
			this.dimension = here != null ? here : this.dimensions.get(0);
			this.selected = null;
		}
	}

	private void step(int step) {
		// Every dimension's main map has the same ID, so a click before the step isn't half a double-click.
		this.lastClicked = null;
		int index = this.dimensions.indexOf(this.dimension);
		this.show(this.dimensions.get(Math.floorMod(index + step, this.dimensions.size())), null);
		this.scroll = 0;
		this.rebuildWidgets();
	}

	@Override
	protected void init() {
		String here = this.minecraft.level == null ? null : this.minecraft.level.dimension().identifier().toString();
		Maps maps = Maps.get();
		this.collectDimensions(here, maps);
		this.rows.clear();
		this.sizes.clear();
		if (maps != null) {
			this.rows.addAll(maps.list(this.dimension));
			for (Maps.Info info : this.rows) {
				this.sizes.put(info.folder(), CompletableFuture.supplyAsync(() -> Maps.size(info.folder())));
			}
		}
		if (this.selected != null && this.rows.stream().noneMatch(info -> info.id().equals(this.selected))) {
			this.selected = null;
		}
		this.armed = false;
		int cx = this.width / 2;
		int left = cx - LIST_WIDTH / 2;
		int footer = this.height - 28;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(cx - 100, footer, 200, 20).build());
		this.listBottom = footer - 10;

		// The dimension bar: its name between arrows that step through the dimensions, New map at the right.
		int nameRight = left + ARROW + ACTION_GAP + NAME_WIDTH;
		this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.step(-1)).bounds(left, BAR_Y, ARROW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.step(1)).bounds(nameRight + ACTION_GAP, BAR_Y, ARROW, 20).build());
		Button newMap = this.addRenderableWidget(Button.builder(Component.translatable("diorama.maps.new"), b ->
			this.minecraft.gui.setScreen(new MapNameScreen(this, this.dimension, null))).bounds(left + LIST_WIDTH - NEW_WIDTH, BAR_Y, NEW_WIDTH, 20).build());
		newMap.active = maps != null;

		this.actions.clear();
		this.use = this.action(Component.translatable("diorama.maps.use"), () -> this.use(this.selected));
		this.actions.add(this.use);
		this.actions.add(this.action(Component.translatable("diorama.maps.rename"), () -> {
			if (this.selected != null) {
				this.minecraft.gui.setScreen(new MapNameScreen(this, this.dimension, this.selected));
			}
		}));
		this.delete = this.action(Component.translatable("diorama.waypoint.delete").withStyle(ChatFormatting.RED), () -> {
			this.armed = true;
			this.placeRowButtons();
		});
		this.actions.add(this.delete);
		this.confirm = this.addRenderableWidget(Button.builder(Component.translatable("diorama.menu.delete_confirm").withStyle(ChatFormatting.RED), b -> {
			Maps current = Maps.get();
			if (this.armed && this.selected != null && current != null) {
				current.delete(this.dimension, this.selected);
				this.selected = null;
				this.rebuildWidgets();
			}
		}).size(20, 20).build());
		this.visibleRows = Math.max(1, (this.listBottom - LIST_TOP - 2) / PITCH);
		this.scrollTo(this.scroll);
	}

	private Button action(Component label, Runnable onPress) {
		return this.addRenderableWidget(Button.builder(label, b -> onPress.run()).size(Math.max(32, this.font.width(label) + 10), 20).build());
	}

	private void use(@Nullable String id) {
		Maps maps = Maps.get();
		if (id != null && maps != null && this.dimension != null) {
			maps.use(this.dimension, id);
			this.rebuildWidgets();
		}
	}

	private Maps.@Nullable Info selectedInfo() {
		for (Maps.Info info : this.rows) {
			if (info.id().equals(this.selected)) {
				return info;
			}
		}
		return null;
	}

	/** The selected row's buttons: Use this map and Delete only on a map that isn't in use. */
	private List<Button> rowActions() {
		Maps.Info info = this.selectedInfo();
		if (info == null || !info.active()) {
			return this.actions;
		}
		return this.actions.stream().filter(b -> b != this.use && b != this.delete).toList();
	}

	private int actionsWidth() {
		int span = -ACTION_GAP;
		for (Button button : this.rowActions()) {
			span += button.getWidth() + ACTION_GAP;
		}
		return span;
	}

	private void placeRowButtons() {
		int index = this.selected == null ? -1 : this.rows.indexOf(this.selectedInfo()) - this.scroll;
		boolean shown = index >= 0 && index < this.visibleRows;
		List<Button> buttons = this.rowActions();
		int span = this.actionsWidth();
		int right = this.width / 2 + LIST_WIDTH / 2 - 2;
		int y = shown ? this.rowY(index) + 2 : 0;
		int x = right - span;
		for (Button button : this.actions) {
			boolean has = buttons.contains(button);
			show(button, shown && !this.armed && has);
			button.setPosition(x, y);
			if (has) {
				x += button.getWidth() + ACTION_GAP;
			}
		}
		Button confirm = this.confirm;
		if (confirm != null) {
			show(confirm, shown && this.armed);
			//? if >=1.21 {
			confirm.setRectangle(span, 20, right - span, y);
			//?} else {
			/*confirm.setWidth(span);
			confirm.setPosition(right - span, y);
			*///?}
		}
	}

	private static void show(Button button, boolean shown) {
		button.visible = shown;
		button.active = shown;
	}

	private int rowY(int index) {
		return LIST_TOP + 2 + index * PITCH;
	}

	private int maxScroll() {
		return Math.max(0, this.rows.size() - this.visibleRows);
	}

	private void scrollTo(int scroll) {
		this.scroll = Math.max(0, Math.min(scroll, this.maxScroll()));
		this.placeRowButtons();
	}

	private int rowAt(double x, double y) {
		int left = this.width / 2 - LIST_WIDTH / 2;
		if (x < left || x >= left + LIST_WIDTH || y < LIST_TOP || y >= this.listBottom) {
			return -1;
		}
		int index = (int) ((y - LIST_TOP - 2) / PITCH);
		if (index < 0 || index >= this.visibleRows || this.scroll + index >= this.rows.size() || y >= this.rowY(index) + ROW) {
			return -1;
		}
		return this.scroll + index;
	}

	//? if >=1.21 {
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
	//?} else {
	/*@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
		this.drawBackground(graphics);
		super.render(graphics, mouseX, mouseY, a);
	}

	private void drawBackground(GuiGraphics graphics) {
		this.renderBackground(graphics);
	*///?}
		// Drawn with the background, so the buttons go on top of it, as in the waypoint list.
		//? if >=1.21.6 {
		graphics.nextStratum();
		//?}
		int cx = this.width / 2;
		int left = cx - LIST_WIDTH / 2;
		int right = left + LIST_WIDTH;
		graphics.centeredText(this.font, this.title, cx, 8, 0xFFFFFFFF);
		int nameLeft = left + ARROW + ACTION_GAP;
		ScreenArt.sprite(graphics, ScreenArt.TEXT_FIELD, nameLeft, BAR_Y, NAME_WIDTH, 20);
		String label = this.fit(Waypoints.dimensionName(this.dimension).getString() + " (" + this.rows.size() + ")", NAME_WIDTH - 8);
		graphics.centeredText(this.font, Component.literal(label), nameLeft + NAME_WIDTH / 2, BAR_Y + 6, 0xFFFFFFFF);
		ScreenArt.list(graphics, 0, LIST_TOP, this.width, this.listBottom);
		if (this.rows.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("diorama.maps.none"), cx, LIST_TOP + 20, HINT);
		}
		WaypointStore store = WaypointStore.get();
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.rows.size(); i++) {
			Maps.Info info = this.rows.get(this.scroll + i);
			int y = this.rowY(i);
			boolean selected = info.id().equals(this.selected);
			if (selected) {
				ScreenArt.selection(graphics, left, y, right, y + ROW);
			}
			int room = right - (selected ? this.actionsWidth() + 4 : 6) - (left + TEXT_LEFT);
			graphics.text(this.font, this.fit(info.name(), room), left + TEXT_LEFT, y + 4, 0xFFFFFFFF, true);
			graphics.text(this.font, this.fit(this.detail(info, store), room), left + TEXT_LEFT, y + 14, DETAIL, true);
		}
		if (this.maxScroll() > 0) {
			int track = this.listBottom - LIST_TOP;
			int handle = Math.max(ScreenArt.SCROLLER_MIN, Math.min(track - 8, track * this.visibleRows / Math.max(1, this.rows.size())));
			int top = (track - handle) * this.scroll / this.maxScroll();
			ScreenArt.scrollbar(graphics, right + 4, LIST_TOP, this.listBottom, handle, top);
		}
	}

	/** In use, its size, how many waypoints, and when it was made. */
	private String detail(Maps.Info info, @Nullable WaypointStore store) {
		List<String> parts = new ArrayList<>();
		if (info.active()) {
			parts.add(Component.translatable("diorama.maps.in_use").getString());
		}
		CompletableFuture<Long> size = this.sizes.get(info.folder());
		Long bytes = size != null && size.isDone() && !size.isCompletedExceptionally() ? size.join() : null;
		parts.add(bytes == null ? CommonComponents.ELLIPSIS.getString() : bytes == 0 ? Component.translatable("diorama.maps.empty").getString() : Maps.readableSize(bytes));
		if (store != null) {
			int count = store.count(this.dimension, info.id());
			parts.add(Component.translatable(count == 1 ? "diorama.maps.waypoint" : "diorama.maps.waypoints", count).getString());
		}
		if (info.created() > 0) {
			parts.add(Component.translatable("diorama.maps.made", date(info.created())).getString());
		}
		return String.join("  ·  ", parts);
	}

	private static String date(long millis) {
		LocalDate day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
		return day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
	}

	private String fit(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		String ellipsis = CommonComponents.ELLIPSIS.getString();
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width(ellipsis))) + ellipsis;
	}

	// The mouse wheel had only the one axis on 1.20.1.
	@Override
	//? if >=1.21 {
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
	//?} else {
	/*public boolean mouseScrolled(double x, double y, double scrollY) {
	*///?}
		this.scrollTo(this.scroll - (int) Math.signum(scrollY));
		return true;
	}

	// Mouse events came as separate numbers before 1.21.9.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.disarmUnless(event.x(), event.y());
		return super.mouseClicked(event, doubleClick) || this.clicked(event.x(), event.y(), event.button());
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		this.disarmUnless(x, y);
		return super.mouseClicked(x, y, button) || this.clicked(x, y, button);
	}
	*///?}

	/** A click anywhere but on Click again to delete puts the row's buttons back. */
	private void disarmUnless(double x, double y) {
		Button button = this.confirm;
		if (this.armed && (button == null || !button.isMouseOver(x, y))) {
			this.armed = false;
			// Delete had the focus from its click and shows again now: Enter mustn't ask again.
			if (this.getFocused() == button || this.actions.contains(this.getFocused())) {
				this.setFocused(null);
			}
			this.placeRowButtons();
		}
	}

	/** A click no widget took: the dimension's name steps to the next, a row is selected, a second click uses it. */
	private boolean clicked(double x, double y, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int nameLeft = this.width / 2 - LIST_WIDTH / 2 + ARROW + ACTION_GAP;
		if (x >= nameLeft && x < nameLeft + NAME_WIDTH && y >= BAR_Y && y < BAR_Y + 20) {
			this.step(1);
			return true;
		}
		int row = this.rowAt(x, y);
		if (row < 0) {
			return false;
		}
		String id = this.rows.get(row).id();
		long now = System.currentTimeMillis();
		if (id.equals(this.lastClicked) && now - this.lastClick < DOUBLE_CLICK_MS) {
			this.use(id);
		} else {
			this.selected = id;
			this.armed = false;
			this.placeRowButtons();
		}
		this.lastClick = now;
		this.lastClicked = id;
		return true;
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
