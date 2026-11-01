package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.DioramaClient;
import dev.diorama.MapSession;
import dev.diorama.render.Pins;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The waypoints of one folder, nearest first, in vanilla's in-world list. Every dimension has a
 * folder of its own, made for it; the player can add folders of their own, rename and delete them,
 * and hide any folder from the maps. The folder bar picks the folder, a search box filters its
 * waypoints by name, and New folder and New waypoint sit beside it. Clicking a row selects it and shows
 * its actions on it: edit, hide or show, teleport (for players allowed to use /tp) and delete,
 * which asks for a second click in place. Double-clicking a row edits it. With no waypoints in
 * this dimension, a pin standing on a piece of the map shows what one is and how to add one.
 * When Xaero's Minimap has waypoints for this world that aren't here yet, a button imports them.
 */
public final class WaypointListScreen extends Screen {
	private static final int LIST_WIDTH = 340;
	private static final int SEARCH_WIDTH = 160;
	private static final int NEW_WIDTH = 100;
	private static final int NEW_FOLDER_WIDTH = 72;
	/** Rows are 24 tall with 2 between them, as in vanilla's lists. */
	private static final int ROW = 24;
	private static final int PITCH = 26;
	/** The folder bar, then the search row under it, then the list. */
	private static final int FOLDER_Y = 22;
	private static final int SEARCH_Y = 46;
	private static final int LIST_TOP = 72;
	/** The arrows either side of the folder's name. */
	private static final int ARROW = 20;
	/** The folder's name between its arrows: wide enough for the actions of a folder of the player's own beside it. */
	private static final int FOLDER_NAME_WIDTH = 160;
	/** Where a row's name and position start, right of its badge. */
	private static final int TEXT_LEFT = 24;
	private static final int ACTION_GAP = 2;
	/** Vanilla's double-click window. */
	private static final long DOUBLE_CLICK_MS = 250;
	private static final int RED = 0xFFFF5555;
	private static final int DETAIL = 0xFF909090;
	private static final int HIDDEN = 0xFF808080;
	private static final int HINT = 0xFFA0A0A0;
	private static final int SEARCH_HINT = 0xFF707070;

	private final @Nullable Screen parent;
	/** The folders, dimensions' first, and which one is shown, by key (see {@link WaypointStore#folderKey}). */
	private final List<Folder> folders = new ArrayList<>();
	private @Nullable String folderKey;
	/** The dimension the map was showing, which has a folder even with no waypoints. */
	private @Nullable String shownDimension;
	/** No waypoints and no folders at all: the empty list's piece of map instead of the list. */
	private boolean empty;
	/**
	 * The folder's buttons at the right end of the folder bar: Hide or Show, then for a folder of the
	 * player's own Rename and Delete in red. Delete arms them into one red Click again to delete.
	 */
	private final List<Button> folderActions = new ArrayList<>();
	private boolean folderArmed;
	/** Every waypoint in the folder shown, nearest first. */
	private final List<Waypoint> all = new ArrayList<>();
	/** The ones the search lets through, still nearest first. */
	private final List<Waypoint> rows = new ArrayList<>();
	/**
	 * The selected row's buttons. They're made with the screen and only moved, shown or hidden, and
	 * they act on whatever is selected when pressed: a button that has gone from view can keep the
	 * keyboard focus, and must not act on a row it no longer sits on.
	 */
	private final List<Button> actions = new ArrayList<>();
	/** Edit and Delete, which a waystone's row goes without, and Teleport, which can be off for another dimension. */
	private @Nullable Button edit;
	private @Nullable Button teleport;
	private @Nullable Button delete;
	private final MapPiece piece = new MapPiece(88, 56, 12);
	/** The sample pin on the empty list's piece of map. */
	private final Waypoint sample = new Waypoint("Home", 0, 0, 0, "", Waypoint.PALETTE[2]);
	private @Nullable EditBox search;
	private @Nullable Waypoint selected;
	/** The selected row's Delete was clicked once: its buttons are one Click again to delete. */
	private boolean armed;
	private @Nullable Button visibility;
	private @Nullable Button confirm;
	private int scroll;
	private int listBottom;
	private int visibleRows;
	private boolean draggingScrollbar;
	private long lastClick;
	private @Nullable Waypoint lastClicked;
	private boolean canTeleport;
	/**
	 * {@link Waystones#changes} when the rows were made. When it moves on, the screen is made again
	 * if it shows the Waystones folder or that folder comes or goes, and otherwise left alone, as
	 * making it again resets the search box's cursor.
	 */
	private int waystoneChanges;
	private boolean hadWaystones;
	/** Set by the add-waypoint key; tick() opens the editor. */
	private boolean newWaypointPressed;
	private boolean xaeroRead;
	private XaeroImport.@Nullable Found xaero;
	/** What the last import brought in, shown above the import button. */
	private @Nullable Component imported;
	private int importedY;
	/** Top of the empty list's block: the piece of map, then the words and New waypoint. */
	private int emptyTop;

	/** A folder in the bar: a dimension's own ({@code custom} null) or one of the player's. */
	private record Folder(String key, Component name, @Nullable String dimension, @Nullable String custom) {
	}

	public WaypointListScreen(@Nullable Screen parent) {
		super(Component.translatable("diorama.waypoints.title"));
		this.parent = parent;
	}

	/** Shows the folder with this key when the screen opens again. */
	void showFolder(String key) {
		this.folderKey = key;
		this.selected = null;
		this.scroll = 0;
	}

	/** Shows a dimension's folder when the screen opens, even a modded dimension's with no waypoints yet. */
	public void showDimension(String dimension) {
		this.shownDimension = dimension;
		this.showFolder(WaypointStore.dimensionKey(dimension));
	}

	/**
	 * The folders: the Overworld, the Nether and the End always, any other dimension with waypoints
	 * or the player in it, then the player's own in the order they were made, then Waystones when
	 * the Waystones mod has told the player of any.
	 */
	private void collectFolders(@Nullable WaypointStore store, @Nullable String here) {
		this.folders.clear();
		java.util.LinkedHashSet<String> dimensions = new java.util.LinkedHashSet<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
		if (here != null) {
			dimensions.add(here);
		}
		if (this.shownDimension != null) {
			dimensions.add(this.shownDimension);
		}
		if (store != null) {
			for (Waypoint waypoint : store.all()) {
				if (store.folderName(waypoint.folder) == null) {
					dimensions.add(waypoint.dimension);
				}
			}
		}
		for (String dimension : dimensions) {
			this.folders.add(new Folder(WaypointStore.dimensionKey(dimension), Waypoints.dimensionName(dimension), dimension, null));
		}
		if (store != null) {
			for (String name : store.customFolders()) {
				this.folders.add(new Folder(WaypointStore.customKey(name), Component.literal(name), null, name));
			}
			if (!store.waystones().isEmpty()) {
				this.folders.add(new Folder(WaypointStore.WAYSTONES_KEY, Component.translatable("diorama.folder.waystones"), null, null));
			}
		}
		if (this.folderKey == null || this.folders.stream().noneMatch(f -> f.key().equals(this.folderKey))) {
			this.folderKey = here != null ? WaypointStore.dimensionKey(here) : this.folders.get(0).key();
			this.selected = null;
		}
	}

	private Folder folder() {
		for (Folder folder : this.folders) {
			if (folder.key().equals(this.folderKey)) {
				return folder;
			}
		}
		return this.folders.get(0);
	}

	private void stepFolder(int step) {
		int index = this.folders.indexOf(this.folder());
		this.showFolder(this.folders.get(Math.floorMod(index + step, this.folders.size())).key());
		this.rebuildWidgets();
	}

	/** Right of the name's right arrow, before the folder's own buttons. */
	private static int folderNameLeft(int left) {
		return left + ARROW + ACTION_GAP;
	}

	@Override
	protected void init() {
		this.all.clear();
		WaypointStore store = WaypointStore.get();
		Entity viewer = MapSession.viewer();
		String here = this.minecraft.level == null ? null : this.minecraft.level.dimension().identifier().toString();
		this.collectFolders(store, here);
		if (store != null && viewer != null && here != null) {
			String key = this.folder().key();
			for (Waypoint waypoint : key.equals(WaypointStore.WAYSTONES_KEY) ? store.waystones() : store.all()) {
				if (store.folderKey(waypoint).equals(key)) {
					this.all.add(waypoint);
				}
			}
			// This dimension's waypoints nearest first; any from other dimensions after them, by name.
			this.all.sort(Comparator.<Waypoint, Boolean>comparing(w -> !w.dimension.equals(here))
				.thenComparingDouble(w -> w.dimension.equals(here) ? w.distanceTo(viewer.getX(), viewer.getY(), viewer.getZ()) : 0)
				.thenComparing(w -> w.name.toLowerCase(Locale.ROOT)));
		}
		this.empty = store == null || store.all().isEmpty() && store.customFolders().isEmpty() && store.waystones().isEmpty();
		this.waystoneChanges = Waystones.changes();
		this.hadWaystones = store != null && !store.waystones().isEmpty();
		this.folderArmed = false;
		this.folderActions.clear();
		if (this.selected != null && !this.all.contains(this.selected)) {
			// A waystone is a new object each time the server sends it: find it again by its id.
			UUID id = this.selected.id;
			this.selected = this.all.stream().filter(w -> w.id.equals(id)).findFirst().orElse(null);
		}
		if (!this.xaeroRead) {
			this.xaeroRead = true;
			this.xaero = XaeroImport.find();
		}
		this.canTeleport = Waypoints.canTeleport();
		int cx = this.width / 2;
		int left = cx - LIST_WIDTH / 2;

		// The footer: Done alone, and the import button above it when Xaero's has something new.
		int footer = this.height - 28;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(cx - 100, footer, 200, 20).build());
		int above = footer;
		Button importButton = this.importButton(store);
		if (importButton != null) {
			above -= 24;
			this.addRenderableWidget(importButton);
			//? if >=1.21 {
			importButton.setRectangle(204, 20, cx - 102, above);
			//?} else {
			/*// No setRectangle on 1.20.1; the button is already 20 tall.
			importButton.setWidth(204);
			importButton.setPosition(cx - 102, above);
			*///?}
		}
		if (this.imported != null) {
			above -= 12;
			this.importedY = above;
		}
		this.listBottom = above - (above == footer ? 10 : 6);

		if (this.empty) {
			// The empty list: the piece of map, the headline and hints, then New waypoint under them.
			int newY = 186;
			this.emptyTop = Math.min(0, this.listBottom + 6 - (newY + 20 + 4));
			this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.new"), b -> this.newWaypoint())
				.bounds(cx - 75, newY + this.emptyTop, 150, 20).build());
			this.search = null;
			this.actions.clear();
			this.visibility = null;
			this.confirm = null;
			return;
		}

		// The folder bar: on the left the folder's name between arrows that step through the folders,
		// at the right end the folder's own buttons, as a selected row has them.
		Folder folder = this.folder();
		int nameRight = folderNameLeft(left) + FOLDER_NAME_WIDTH;
		this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.stepFolder(-1)).bounds(left, FOLDER_Y, ARROW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.stepFolder(1)).bounds(nameRight + ACTION_GAP, FOLDER_Y, ARROW, 20).build());
		boolean hidden = store != null && store.folderHidden(folder.key());
		this.folderActions.add(this.action(Component.translatable(hidden ? "diorama.waypoints.show" : "diorama.waypoints.hide"), () -> {
			WaypointStore current = WaypointStore.get();
			if (current != null) {
				current.setFolderHidden(this.folder().key(), !current.folderHidden(this.folder().key()));
				this.rebuildWidgets();
			}
		}));
		String custom = folder.custom();
		if (custom != null) {
			this.folderActions.add(this.action(Component.translatable("diorama.folder.rename"),
				() -> this.minecraft.gui.setScreen(new WaypointFolderScreen(this, custom))));
			this.folderActions.add(this.action(Component.translatable("diorama.waypoint.delete").withStyle(ChatFormatting.RED), () -> {
				this.folderArmed = true;
				this.placeFolderButtons();
			}));
			this.folderActions.add(this.addRenderableWidget(Button.builder(Component.translatable("diorama.menu.delete_confirm").withStyle(ChatFormatting.RED), b -> {
				WaypointStore current = WaypointStore.get();
				if (this.folderArmed && current != null) {
					current.deleteFolder(custom);
					this.rebuildWidgets();
				}
			}).size(20, 20).build()));
		}
		this.placeFolderButtons();

		// The search box: vanilla's text field frame with the search icon in it, the text right of the icon.
		String query = this.search != null ? this.search.getValue() : "";
		EditBox box = new EditBox(this.font, left + 20, SEARCH_Y + 6, SEARCH_WIDTH - 24, 12, Component.translatable("diorama.waypoints.search"));
		box.setBordered(false);
		box.setMaxLength(32);
		box.setValue(query);
		box.setResponder(text -> this.filter());
		this.search = this.addRenderableWidget(box);
		// The two ways to add something sit beside the search box: New folder, then New waypoint.
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.folder.new"), b -> this.minecraft.gui.setScreen(new WaypointFolderScreen(this, null)))
			.bounds(left + LIST_WIDTH - NEW_WIDTH - 4 - NEW_FOLDER_WIDTH, SEARCH_Y, NEW_FOLDER_WIDTH, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.new"), b -> this.newWaypoint())
			.bounds(left + LIST_WIDTH - NEW_WIDTH, SEARCH_Y, NEW_WIDTH, 20).build());
		this.actions.clear();
		this.edit = this.action(Component.translatable("diorama.waypoints.edit"), () -> this.edit(this.selected));
		this.actions.add(this.edit);
		this.visibility = this.action(Component.translatable("diorama.waypoints.hide"), () -> this.toggleVisible(this.selected));
		this.actions.add(this.visibility);
		this.teleport = null;
		if (this.canTeleport) {
			this.teleport = this.action(Component.translatable("diorama.waypoint.teleport"), () -> this.teleport(this.selected));
			this.actions.add(this.teleport);
		}
		this.delete = this.action(Component.translatable("diorama.waypoint.delete").withStyle(ChatFormatting.RED), () -> {
			this.armed = true;
			this.placeRowButtons();
		});
		this.actions.add(this.delete);
		this.confirm = this.addRenderableWidget(Button.builder(Component.translatable("diorama.menu.delete_confirm").withStyle(ChatFormatting.RED), b -> {
			if (this.armed) {
				this.delete(this.selected);
			}
		}).size(20, 20).build());
		this.visibleRows = Math.max(1, (this.listBottom - LIST_TOP - 2) / PITCH);
		this.filter();
		this.setInitialFocus(box);
	}

	/** Shows the waypoints whose name has the search text in it, any case, and lays the rows out again. */
	private void filter() {
		String query = this.search != null ? this.search.getValue().trim().toLowerCase(Locale.ROOT) : "";
		this.rows.clear();
		for (Waypoint waypoint : this.all) {
			if (query.isEmpty() || waypoint.name.toLowerCase(Locale.ROOT).contains(query)) {
				this.rows.add(waypoint);
			}
		}
		if (this.selected != null && !this.rows.contains(this.selected)) {
			this.select(null);
		}
		this.scrollTo(this.scroll);
	}

	private int maxScroll() {
		return Math.max(0, this.rows.size() - this.visibleRows);
	}

	private void scrollTo(int scroll) {
		this.scroll = Math.max(0, Math.min(scroll, this.maxScroll()));
		this.placeRowButtons();
	}

	private void select(@Nullable Waypoint waypoint) {
		this.selected = waypoint;
		this.armed = false;
		this.placeRowButtons();
	}

	/**
	 * The selected row's buttons, right-aligned inside it: Edit, Hide or Show, Teleport, then
	 * Delete in red. Once Delete is clicked they become one red Click again to delete across them.
	 * A waystone's row has only Hide or Show and Teleport.
	 */
	private void placeRowButtons() {
		int index = this.selected == null ? -1 : this.rows.indexOf(this.selected) - this.scroll;
		boolean shown = index >= 0 && index < this.visibleRows;
		Button visibility = this.visibility;
		if (shown && visibility != null) {
			Component label = Component.translatable(this.selected.visible ? "diorama.waypoints.hide" : "diorama.waypoints.show");
			visibility.setMessage(label);
			visibility.setWidth(width(this.font.width(label)));
		}
		List<Button> buttons = this.rowActions();
		int span = -ACTION_GAP;
		for (Button button : buttons) {
			span += button.getWidth() + ACTION_GAP;
		}
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
		Button teleport = this.teleport;
		if (shown && teleport != null) {
			teleport.active = !this.armed && Waypoints.canTeleport(this.selected.dimension);
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

	/**
	 * The folder's buttons, right-aligned in the folder bar. The last one, for a folder of the
	 * player's own, is Click again to delete: shown alone across the others once Delete is clicked.
	 */
	private void placeFolderButtons() {
		boolean custom = this.folderActions.size() > 1;
		List<Button> buttons = custom ? this.folderActions.subList(0, this.folderActions.size() - 1) : this.folderActions;
		int span = -ACTION_GAP;
		for (Button button : buttons) {
			span += button.getWidth() + ACTION_GAP;
		}
		int right = this.width / 2 + LIST_WIDTH / 2;
		int x = right - span;
		for (Button button : buttons) {
			show(button, !this.folderArmed);
			button.setPosition(x, FOLDER_Y);
			x += button.getWidth() + ACTION_GAP;
		}
		if (custom) {
			Button confirm = this.folderActions.get(this.folderActions.size() - 1);
			show(confirm, this.folderArmed);
			//? if >=1.21 {
			confirm.setRectangle(span, 20, right - span, FOLDER_Y);
			//?} else {
			/*confirm.setWidth(span);
			confirm.setPosition(right - span, FOLDER_Y);
			*///?}
		}
	}

	private static void show(Button button, boolean shown) {
		button.visible = shown;
		button.active = shown;
	}

	/** A row button just wide enough for its label, and never narrower than vanilla's small ones. */
	private Button action(Component label, Runnable onPress) {
		return this.addRenderableWidget(Button.builder(label, b -> onPress.run()).size(width(this.font.width(label)), 20).build());
	}

	private static int width(int label) {
		return Math.max(32, label + 10);
	}

	/** The buttons the selected row has: all of them, or for a waystone all but Edit and Delete. */
	private List<Button> rowActions() {
		if (this.selected == null || this.selected.editable()) {
			return this.actions;
		}
		List<Button> buttons = new ArrayList<>(this.actions);
		buttons.remove(this.edit);
		buttons.remove(this.delete);
		return buttons;
	}

	/** How wide the selected row's buttons are together, with the gap before them. */
	private int actionsWidth() {
		int span = 0;
		for (Button button : this.rowActions()) {
			span += button.getWidth() + ACTION_GAP;
		}
		return span;
	}

	/** Where a row's badge is centred, from the row's left: a round one in its usual place, a wider one with its left edge there. */
	private static float badgeX(Waypoint waypoint) {
		int width = Pins.badgeWidth(waypoint, Pins.LARGE);
		return width > Pins.LARGE ? 4 + width / 2.0F : 11.5F;
	}

	private int rowY(int index) {
		return LIST_TOP + 2 + index * PITCH;
	}

	/** The row under (x, y), or -1. */
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
	/*// A 1.20.1 screen draws its own background, and its render method draws only the widgets.
	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
		this.drawBackground(graphics, mouseX, mouseY);
		super.render(graphics, mouseX, mouseY, a);
	}

	private void drawBackground(GuiGraphics graphics, int mouseX, int mouseY) {
		this.renderBackground(graphics);
	*///?}
		// Drawn with the background, so the buttons go on top of it: before 1.21.6 a screen's own
		// drawing comes after its background, and the rows have to be under their buttons.
		//? if >=1.21.6 {
		graphics.nextStratum();
		//?}
		if (this.empty) {
			this.drawEmpty(graphics);
		} else {
			this.drawList(graphics, mouseX, mouseY);
		}
	}

	private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int cx = this.width / 2;
		int left = cx - LIST_WIDTH / 2;
		int right = left + LIST_WIDTH;
		graphics.centeredText(this.font, this.title, cx, 8, 0xFFFFFFFF);
		// The folder's name between its arrows, with how many waypoints it holds; dimmed when hidden.
		int nameLeft = folderNameLeft(left);
		int nameRight = nameLeft + FOLDER_NAME_WIDTH;
		ScreenArt.sprite(graphics, ScreenArt.TEXT_FIELD, nameLeft, FOLDER_Y, nameRight - nameLeft, 20);
		WaypointStore store = WaypointStore.get();
		Folder folder = this.folder();
		boolean folderHidden = store != null && store.folderHidden(folder.key());
		String label = this.fit(folder.name().getString() + " (" + this.all.size() + ")", nameRight - nameLeft - 8);
		graphics.centeredText(this.font, Component.literal(label), (nameLeft + nameRight) / 2, FOLDER_Y + 6, folderHidden ? HIDDEN : 0xFFFFFFFF);
		EditBox box = this.search;
		if (box != null) {
			ScreenArt.sprite(graphics, box.isFocused() ? ScreenArt.TEXT_FIELD_FOCUSED : ScreenArt.TEXT_FIELD, left, SEARCH_Y, SEARCH_WIDTH, 20);
			ScreenArt.sprite(graphics, ScreenArt.SEARCH, left + 4, SEARCH_Y + 4, 12, 12);
			if (box.getValue().isEmpty()) {
				graphics.text(this.font, Component.translatable("diorama.waypoints.search"), left + 20, SEARCH_Y + 6, SEARCH_HINT, false);
			}
		}
		ScreenArt.list(graphics, 0, LIST_TOP, this.width, this.listBottom);
		Entity viewer = MapSession.viewer();
		if (this.rows.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable(this.all.isEmpty() ? "diorama.folder.empty" : "diorama.waypoints.no_match"), cx, LIST_TOP + 20, HINT);
		}
		String here = this.minecraft.level == null ? "" : this.minecraft.level.dimension().identifier().toString();
		// Names start clear of the widest badge showing: initials of three or four letters widen it.
		int textLeft = TEXT_LEFT;
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.rows.size(); i++) {
			textLeft = Math.max(textLeft, (int) Math.ceil(badgeX(this.rows.get(this.scroll + i)) + Pins.badgeWidth(this.rows.get(this.scroll + i), Pins.LARGE) / 2.0F) + 2);
		}
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.rows.size(); i++) {
			Waypoint waypoint = this.rows.get(this.scroll + i);
			int y = this.rowY(i);
			boolean selected = waypoint == this.selected;
			if (selected) {
				ScreenArt.selection(graphics, left, y, right, y + ROW);
			}
			Pins.badge(graphics, waypoint, left + badgeX(waypoint), y + 12.5F, Pins.LARGE);
			// The name and position stop short of the selected row's buttons.
			int room = right - (selected ? this.actionsWidth() + 4 : 6) - (left + textLeft);
			String name = this.fit(waypoint.name, room);
			graphics.text(this.font, name, left + textLeft, y + 4, waypoint.visible && !folderHidden ? 0xFFFFFFFF : HIDDEN, true);
			String detail = waypoint.x + ", " + waypoint.y + ", " + waypoint.z;
			if (!waypoint.dimension.equals(here)) {
				detail = Waypoints.dimensionName(waypoint.dimension).getString() + " · " + detail;
			} else if (viewer != null) {
				detail = Math.round(waypoint.distanceTo(viewer.getX(), viewer.getY(), viewer.getZ())) + " m · " + detail;
			}
			if (!waypoint.visible) {
				detail += "  ·  " + Component.translatable("diorama.waypoint.hidden").getString();
			}
			String shownDetail = this.fit(detail, room);
			graphics.text(this.font, shownDetail, left + textLeft, y + 14, DETAIL, true);
			boolean overText = mouseX >= left + textLeft && mouseX < left + textLeft + room && mouseY >= y && mouseY < y + ROW;
			if (overText && (!name.equals(waypoint.name) || !shownDetail.equals(detail))) {
				//? if >=1.21.6 {
				graphics.setComponentTooltipForNextFrame(this.font, List.of(Component.literal(waypoint.name), Component.literal(detail)), mouseX, mouseY);
				//?} else {
				/*// The screen draws this tooltip after everything else.
				this.setTooltipForNextRenderPass(List.of(Component.literal(waypoint.name).getVisualOrderText(), Component.literal(detail).getVisualOrderText()));
				*///?}
			}
		}
		if (this.maxScroll() > 0) {
			int[] bar = this.scrollbar();
			ScreenArt.scrollbar(graphics, bar[0], LIST_TOP, this.listBottom, bar[1], bar[2]);
		}
		if (this.imported != null) {
			graphics.centeredText(this.font, this.imported, cx, this.importedY, 0xFFA0E0A0);
		}
	}

	/** The scrollbar: its x, the handle's height and how far down the track it sits. */
	private int[] scrollbar() {
		int track = this.listBottom - LIST_TOP;
		int handle = Math.max(ScreenArt.SCROLLER_MIN, Math.min(track - 8, track * this.visibleRows / Math.max(1, this.rows.size())));
		int top = this.maxScroll() == 0 ? 0 : (track - handle) * this.scroll / this.maxScroll();
		return new int[] {this.width / 2 + LIST_WIDTH / 2 + 4, handle, top};
	}

	/**
	 * The empty list: a pin standing on a piece of the map around the player, then what's
	 * missing and the two ways to add a waypoint.
	 */
	private void drawEmpty(GuiGraphicsExtractor graphics) {
		int cx = this.width / 2;
		int top = this.emptyTop;
		graphics.centeredText(this.font, this.title, cx, 16 + Math.max(top, -8), 0xFFFFFFFF);
		int pieceX = cx - this.piece.outerWidth() / 2;
		int pieceY = 52 + top;
		this.piece.draw(graphics, pieceX, pieceY);
		// The map's standing pin at twice its size, its tip on the middle of the piece's top.
		graphics.pose().pushMatrix();
		graphics.pose().translate(pieceX + this.piece.tipX(), pieceY + this.piece.tipY());
		graphics.pose().scale(2);
		Pins.standing(graphics, this.sample, 0, 0, Pins.LARGE);
		graphics.pose().popMatrix();
		graphics.centeredText(this.font, Component.translatable("diorama.waypoints.empty_title"), cx, 142 + top, 0xFFFFFFFF);
		Component how = DioramaClient.NEW_WAYPOINT.isUnbound()
			? Component.translatable("diorama.waypoints.empty_unbound")
			: Component.translatable("diorama.waypoints.empty", DioramaClient.NEW_WAYPOINT.getTranslatedKeyMessage());
		graphics.centeredText(this.font, how, cx, 158 + top, HINT);
		graphics.centeredText(this.font, Component.translatable("diorama.waypoints.empty_map", Component.translatable("diorama.menu.add_waypoint")), cx, 168 + top, HINT);
		if (this.imported != null) {
			graphics.centeredText(this.font, this.imported, cx, this.importedY, 0xFFA0E0A0);
		}
	}

	/**
	 * "Import from Xaero's Minimap (N)" when Xaero's has waypoints for this singleplayer world
	 * that aren't here; on a server, or when it's unclear which of Xaero's worlds this is, the
	 * button asks the player.
	 */
	private @Nullable Button importButton(@Nullable WaypointStore store) {
		XaeroImport.Found found = this.xaero;
		if (found == null || store == null) {
			return null;
		}
		XaeroImport.World chosen = found.chosen();
		if (chosen != null) {
			int count = XaeroImport.missing(chosen, store).size();
			return count == 0 ? null : Button.builder(Component.translatable("diorama.xaero.import", count), b -> {
				this.importFrom(chosen);
				this.rebuildWidgets();
			}).build();
		}
		if (found.worlds().stream().allMatch(world -> XaeroImport.missing(world, store).isEmpty())) {
			return null;
		}
		return Button.builder(Component.translatable("diorama.xaero.import_pick"), b ->
			this.minecraft.gui.setScreen(new XaeroWorldScreen(this, found.worlds()))).build();
	}

	/** What the last import brought in, or null before one. */
	@Nullable Component imported() {
		return this.imported;
	}

	/** Imports one of Xaero's worlds and says how it went. */
	void importFrom(XaeroImport.World world) {
		WaypointStore store = WaypointStore.get();
		if (store == null || this.minecraft.level == null) {
			return;
		}
		List<Waypoint> added = XaeroImport.importInto(world, store);
		String here = this.minecraft.level.dimension().identifier().toString();
		long elsewhere = added.stream().filter(w -> !w.dimension.equals(here)).count();
		Component count = added.size() == 1
			? Component.translatable("diorama.xaero.count_one")
			: Component.translatable("diorama.xaero.count", added.size());
		this.imported = elsewhere == 0
			? Component.translatable("diorama.xaero.imported", count)
			: Component.translatable("diorama.xaero.imported_elsewhere", count, elsewhere);
	}

	/** The text, cut to the width with an ellipsis the way vanilla clips a label, if it's wider. */
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

	/**
	 * The buttons the list itself clicks with. A click over a widget counts as the widget's whatever
	 * the button, though widgets only act on the left one, so a middle or side button bound to add a
	 * waypoint is checked before them, as a key is. A binding on these two waits until nothing else
	 * takes the click.
	 */
	private static boolean usedByList(int button) {
		return button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT;
	}

	// Mouse and key events came as separate numbers before 1.21.9. Each override hands them to a
	// method below that both versions share.
	//? if >=1.21.9 {
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (DioramaClient.NEW_WAYPOINT.matches(event) && !(this.getFocused() instanceof EditBox)) {
			this.newWaypointPressed = true;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.disarmUnless(event.x(), event.y());
		if (!usedByList(event.button()) && DioramaClient.NEW_WAYPOINT.matchesMouse(event)) {
			this.newWaypointPressed = true;
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (DioramaClient.NEW_WAYPOINT.matchesMouse(event)) {
			this.newWaypointPressed = true;
			return true;
		}
		return this.clicked(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.y()) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.draggingScrollbar = false;
		return super.mouseReleased(event);
	}
	//?} else {
	/*@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (DioramaClient.NEW_WAYPOINT.matches(key, scancode) && !(this.getFocused() instanceof EditBox)) {
			this.newWaypointPressed = true;
			return true;
		}
		return super.keyPressed(key, scancode, modifiers);
	}

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		this.disarmUnless(x, y);
		if (!usedByList(button) && DioramaClient.NEW_WAYPOINT.matchesMouse(button)) {
			this.newWaypointPressed = true;
			return true;
		}
		if (super.mouseClicked(x, y, button)) {
			return true;
		}
		if (DioramaClient.NEW_WAYPOINT.matchesMouse(button)) {
			this.newWaypointPressed = true;
			return true;
		}
		return this.clicked(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(y) || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		this.draggingScrollbar = false;
		return super.mouseReleased(x, y, button);
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

	/** A click no widget took: the search frame, the scrollbar, or a row. */
	private boolean clicked(double x, double y, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT || this.empty) {
			return false;
		}
		int left = this.width / 2 - LIST_WIDTH / 2;
		// A click on the folder's name steps to the next folder, as its right arrow does.
		if (x >= folderNameLeft(left) && x < folderNameLeft(left) + FOLDER_NAME_WIDTH && y >= FOLDER_Y && y < FOLDER_Y + 20) {
			this.stepFolder(1);
			return true;
		}
		EditBox box = this.search;
		if (box != null && x >= left && x < left + SEARCH_WIDTH && y >= SEARCH_Y && y < SEARCH_Y + 20) {
			this.setFocused(box);
			return true;
		}
		int[] bar = this.scrollbar();
		if (this.maxScroll() > 0 && x >= bar[0] && x < bar[0] + ScreenArt.SCROLLBAR_WIDTH && y >= LIST_TOP && y < this.listBottom) {
			this.draggingScrollbar = true;
			this.dragged(y);
			return true;
		}
		int row = this.rowAt(x, y);
		if (row < 0) {
			return false;
		}
		Waypoint waypoint = this.rows.get(row);
		long now = System.currentTimeMillis();
		boolean twice = waypoint == this.lastClicked && now - this.lastClick < DOUBLE_CLICK_MS;
		this.lastClicked = waypoint;
		this.lastClick = now;
		this.setFocused(null);
		if (twice) {
			this.edit(waypoint);
		} else if (waypoint != this.selected) {
			this.select(waypoint);
		}
		return true;
	}

	/** Moves the list with the scrollbar handle while it's held. */
	private boolean dragged(double y) {
		if (!this.draggingScrollbar) {
			return false;
		}
		int[] bar = this.scrollbar();
		int track = this.listBottom - LIST_TOP - bar[1];
		double t = track <= 0 ? 0 : (y - LIST_TOP - bar[1] / 2.0) / track;
		this.scrollTo((int) Math.round(t * this.maxScroll()));
		return true;
	}

	@Override
	public void tick() {
		// A waystone renamed, moved or gone while the list is open, or the Waystones folder coming or going.
		int changes = Waystones.changes();
		if (changes != this.waystoneChanges) {
			this.waystoneChanges = changes;
			WaypointStore store = WaypointStore.get();
			boolean hasWaystones = store != null && !store.waystones().isEmpty();
			if (hasWaystones != this.hadWaystones || WaypointStore.WAYSTONES_KEY.equals(this.folderKey)) {
				this.rebuildWidgets();
			}
		}
		if (this.newWaypointPressed) {
			this.newWaypointPressed = false;
			this.newWaypoint();
		}
	}

	private void newWaypoint() {
		Waypoint fresh = Waypoints.atPlayer();
		if (fresh != null) {
			// Made from one of the player's folders, it goes in that folder.
			String custom = this.folders.isEmpty() ? null : this.folder().custom();
			if (custom != null) {
				fresh.folder = custom;
			}
			this.minecraft.gui.setScreen(new WaypointEditScreen(this, fresh, true));
		}
	}

	private void edit(@Nullable Waypoint waypoint) {
		if (waypoint != null && waypoint.editable()) {
			this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, false));
		}
	}

	private void toggleVisible(@Nullable Waypoint waypoint) {
		if (waypoint == null) {
			return;
		}
		WaypointStore store = WaypointStore.get();
		if (store != null) {
			store.setVisible(waypoint, !waypoint.visible);
		}
		this.placeRowButtons();
	}

	private void delete(@Nullable Waypoint waypoint) {
		WaypointStore store = WaypointStore.get();
		if (store != null && waypoint != null) {
			store.remove(waypoint);
		}
		this.selected = null;
		this.armed = false;
		this.rebuildWidgets();
	}

	private void teleport(@Nullable Waypoint waypoint) {
		if (waypoint != null) {
			Waypoints.teleport(waypoint.dimension, waypoint.x, waypoint.y, waypoint.z);
		}
		this.minecraft.gui.setScreen(null);
	}

	/** The waypoint whose row is selected, or null. For tests. */
	public @Nullable Waypoint selected() {
		return this.selected;
	}

	/** The name of the folder shown. For tests. */
	public String folderName() {
		return this.folders.isEmpty() ? "" : this.folder().name().getString();
	}

	/** The rows the search lets through, nearest first. For tests. */
	public List<Waypoint> shown() {
		return List.copyOf(this.rows);
	}

	/** The middle of a waypoint's name on screen, or null when its row is scrolled out of view. For tests. */
	public int @Nullable [] rowCentre(Waypoint waypoint) {
		int index = this.rows.indexOf(waypoint) - this.scroll;
		if (index < 0 || index >= this.visibleRows) {
			return null;
		}
		return new int[] {this.width / 2 - LIST_WIDTH / 2 + TEXT_LEFT + 20, this.rowY(index) + ROW / 2};
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
