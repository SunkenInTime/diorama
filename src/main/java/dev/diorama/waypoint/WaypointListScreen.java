package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.DioramaClient;
import dev.diorama.MapSession;
import dev.diorama.render.Pins;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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
 * The waypoints of one folder, in vanilla's in-world list. Every dimension has a folder of its own,
 * made for it; the player can add folders of their own, rename and delete them, and hide any folder
 * from the maps. The folder bar picks the folder, by its arrows or from the list of every folder that
 * a click on its name opens; a search box filters its waypoints by name and
 * finds the ones in every other folder too, under a heading of their own, and
 * beside it are the sort order with its direction, New folder and New waypoint. Clicking a row selects it and shows
 * its actions on it: edit, hide or show, teleport (for players allowed to use /tp) and delete,
 * which asks for a second click in place. Double-clicking a row edits it. A Ctrl click adds a row to
 * the selection and a Shift click the rows up to it, as in Xaero's Minimap's list; several rows have
 * Move, which sends them all to a folder picked from a list, hide or show, and delete. A grey line
 * under the list says so. With no waypoints in
 * this dimension, a pin standing on a piece of the map shows what one is and how to add one.
 * When Xaero's Minimap has waypoints for this world that aren't here yet, a button imports them.
 */
public final class WaypointListScreen extends Screen {
	private static final int LIST_WIDTH = 340;
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
	 * The folder's buttons at the right end of the folder bar: Hide or Show, then for another
	 * dimension's folder Show here, or for a folder of the player's own Rename and Delete in red.
	 * Delete arms them into one red Click again to delete.
	 */
	private final List<Button> folderActions = new ArrayList<>();
	/** The folder's name in the folder bar: as wide as the folder's buttons leave, in a language with long ones. */
	private int folderNameWidth = FOLDER_NAME_WIDTH;
	private @Nullable Button nextFolder;
	/**
	 * Every folder to pick from, open under the folder's name after a click on it, as Xaero's Minimap
	 * picks a waypoint set from a dropdown: one click of an arrow per folder is slow with many, and
	 * doesn't show which there are. Drawn over the screen, which hands it the clicks while it's open.
	 */
	private @Nullable FolderMenu folderMenu;
	/** The open list of folders was opened by Move: a pick moves the selected rows there, and shows no other folder. */
	private boolean menuMoves;
	private boolean folderArmed;
	/** Every waypoint in the folder shown, in the sort order. */
	private final List<Waypoint> all = new ArrayList<>();
	/** Every waypoint in the other folders, in the sort order: a search looks through them too. */
	private final List<Waypoint> others = new ArrayList<>();
	/** The ones the search lets through, still in the sort order: the folder's, then the other folders'. */
	private final List<Waypoint> rows = new ArrayList<>();
	/**
	 * Where in {@link #rows} the other folders' waypoints start, or -1 with none. They go under a
	 * heading that takes a line of the list, so the list has a line more than it has rows, and
	 * {@link #scroll} counts lines.
	 */
	private int otherStart = -1;
	/** The search box's frame, as wide as the sort and New buttons beside it leave room for. */
	private int searchWidth;
	/**
	 * The sort order, which steps to the next one, and its direction, which reverses it: a caret on
	 * it points up for the usual way, down for reversed, like the map's slice buttons.
	 */
	private @Nullable Button sortOrder;
	private @Nullable Button sortDirection;
	private boolean sortReversed;
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
	/** Where a row's waypoint stands in this dimension, when shown here too: made again in place for each row. */
	private final Waypoint spotHere = new Waypoint();
	/** The sample pin on the empty list's piece of map. */
	private final Waypoint sample = new Waypoint("Home", 0, 0, 0, "", Waypoint.PALETTE[2]);
	private @Nullable EditBox search;
	private @Nullable Waypoint selected;
	/**
	 * The rows selected together with Ctrl or Shift, {@link #selected} among them: it's the one
	 * clicked last, and has their buttons. Empty while one row or none is selected.
	 */
	private final java.util.Set<Waypoint> marked = new java.util.LinkedHashSet<>();
	/** The row a Shift click selects from: the last one clicked without Shift. */
	private @Nullable Waypoint anchor;
	/**
	 * Whether the click being handled came with Ctrl (Cmd on macOS, as the game reads it for its own
	 * shortcuts) or Shift held.
	 */
	private boolean controlClick;
	private boolean shiftClick;
	/** Move, which only several rows have: one row's folder is picked in its editor. */
	private @Nullable Button move;
	/** The selected row's Delete was clicked once: its buttons are one Click again to delete. */
	private boolean armed;
	private @Nullable Button visibility;
	private @Nullable Button confirm;
	private int scroll;
	private int listBottom;
	/** Where the line about selecting several rows goes: midway between the list's bottom edge and the buttons. */
	private int hintY;
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
		this.marked.clear();
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
			this.marked.clear();
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

	/** Opens the list of every folder under the folder's name, each with how many waypoints it holds. */
	private void openFolderMenu() {
		WaypointStore store = WaypointStore.get();
		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		if (store != null) {
			for (List<Waypoint> list : List.of(store.all(), store.waystones())) {
				for (Waypoint waypoint : list) {
					counts.merge(store.folderKey(waypoint), 1, Integer::sum);
				}
			}
		}
		List<FolderMenu.Entry> entries = new ArrayList<>();
		for (Folder folder : this.folders) {
			entries.add(new FolderMenu.Entry(folder.key(), folder.name().getString(), counts.getOrDefault(folder.key(), 0),
				store != null && store.folderHidden(folder.key())));
		}
		int left = folderNameLeft(this.width / 2 - LIST_WIDTH / 2);
		this.menuMoves = false;
		this.folderMenu = new FolderMenu(this.font, entries, this.folder().key(), left, FOLDER_Y, FOLDER_Y + 20, Math.max(FOLDER_NAME_WIDTH, this.folderNameWidth), this.height);
	}

	/**
	 * A click while the list of folders is open: on a folder it shows that folder, anywhere else it
	 * only closes the list, as a click outside a menu does.
	 */
	private void clickFolderMenu(double x, double y, int button) {
		FolderMenu menu = this.folderMenu;
		this.folderMenu = null;
		this.pickFolder(menu == null || button != InputConstants.MOUSE_BUTTON_LEFT ? null : menu.under(x, y));
	}

	/**
	 * A key while the list of folders is open goes to the list and nowhere else, so nothing under
	 * it is typed into or pressed: Escape closes it, the arrows and Enter pick from it.
	 */
	private void keyFolderMenu(int key) {
		FolderMenu menu = this.folderMenu;
		if (menu == null || key == InputConstants.KEY_ESCAPE) {
			this.folderMenu = null;
			return;
		}
		FolderMenu.Entry picked = menu.keyPressed(key);
		if (picked != null) {
			this.folderMenu = null;
			this.pickFolder(picked);
		}
	}

	private void pickFolder(FolderMenu.@Nullable Entry picked) {
		if (this.menuMoves) {
			if (picked != null) {
				this.moveTo(picked.key());
			}
			return;
		}
		if (picked != null && !picked.key().equals(this.folderKey)) {
			this.showFolder(picked.key());
			this.rebuildWidgets();
		}
	}

	/** The selected rows that can be moved and deleted: all but waystones. */
	private List<Waypoint> movable() {
		return this.marked.stream().filter(Waypoint::editable).toList();
	}

	/**
	 * Opens the folders the selected rows can go to under Move: first their dimension's own, then
	 * the player's. Rows from several dimensions each go back to their own dimension's.
	 */
	private void openMoveMenu() {
		Button button = this.move;
		WaypointStore store = WaypointStore.get();
		List<Waypoint> moving = this.movable();
		if (button == null || store == null || moving.isEmpty()) {
			return;
		}
		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (Waypoint waypoint : store.all()) {
			counts.merge(store.folderKey(waypoint), 1, Integer::sum);
		}
		String dimension = moving.get(0).dimension;
		boolean oneDimension = moving.stream().allMatch(w -> w.dimension.equals(dimension));
		String in = store.folderKey(moving.get(0));
		boolean oneFolder = moving.stream().allMatch(w -> store.folderKey(w).equals(in));
		List<FolderMenu.Entry> entries = new ArrayList<>();
		String current = null;
		if (oneDimension) {
			String key = WaypointStore.dimensionKey(dimension);
			entries.add(new FolderMenu.Entry("", Waypoints.dimensionName(dimension).getString(), counts.getOrDefault(key, 0), store.folderHidden(key)));
			current = oneFolder && in.equals(key) ? "" : null;
		} else {
			entries.add(new FolderMenu.Entry("", Component.translatable("diorama.folder.own_dimensions").getString(), -1, false));
		}
		for (String name : store.customFolders()) {
			String key = WaypointStore.customKey(name);
			entries.add(new FolderMenu.Entry(name, name, counts.getOrDefault(key, 0), store.folderHidden(key)));
			if (oneFolder && in.equals(key)) {
				current = name;
			}
		}
		int width = 150;
		int x = Math.max(2, Math.min(button.getX(), this.width - width - 2));
		this.menuMoves = true;
		this.folderMenu = new FolderMenu(this.font, entries, current, x, button.getY(), button.getY() + button.getHeight(), width, this.height);
	}

	/** Moves the selected rows to one of the player's folders, or with an empty name back to their dimensions' own. */
	private void moveTo(String folder) {
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			return;
		}
		for (Waypoint waypoint : this.movable()) {
			waypoint.folder = folder;
		}
		store.changed();
		this.selected = null;
		this.marked.clear();
		this.rebuildWidgets();
	}

	/** macOS, where the game's own shortcuts use Cmd for Ctrl, and a Ctrl click is a right click. */
	private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

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
		this.others.clear();
		WaypointStore store = WaypointStore.get();
		Entity viewer = MapSession.viewer();
		String here = this.minecraft.level == null ? null : this.minecraft.level.dimension().identifier().toString();
		this.collectFolders(store, here);
		if (store != null && viewer != null && here != null) {
			String key = this.folder().key();
			for (List<Waypoint> list : List.of(store.all(), store.waystones())) {
				for (Waypoint waypoint : list) {
					(store.folderKey(waypoint).equals(key) ? this.all : this.others).add(waypoint);
				}
			}
			this.resort(store);
		}
		this.empty = store == null || store.all().isEmpty() && store.customFolders().isEmpty() && store.waystones().isEmpty();
		this.waystoneChanges = Waystones.changes();
		this.hadWaystones = store != null && !store.waystones().isEmpty();
		this.folderArmed = false;
		this.folderActions.clear();
		this.folderMenu = null;
		if (this.selected != null && !this.all.contains(this.selected) && !this.others.contains(this.selected)) {
			// A waystone is a new object each time the server sends it: find it again by its id.
			UUID id = this.selected.id;
			this.selected = java.util.stream.Stream.concat(this.all.stream(), this.others.stream()).filter(w -> w.id.equals(id)).findFirst().orElse(null);
		}
		if (!this.marked.isEmpty()) {
			java.util.Set<UUID> ids = new java.util.HashSet<>();
			this.marked.forEach(w -> ids.add(w.id));
			this.marked.clear();
			java.util.stream.Stream.concat(this.all.stream(), this.others.stream()).filter(w -> ids.contains(w.id)).forEach(this.marked::add);
		}
		if (!this.xaeroRead) {
			this.xaeroRead = true;
			this.xaero = XaeroImport.find();
		}
		this.canTeleport = Waypoints.canTeleport();
		int cx = this.width / 2;
		int left = cx - LIST_WIDTH / 2;

		// The footer: Done alone, and the import button above it when Xaero's has something new.
		int footer = this.height - 26;
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
		// The hint line under the list wants 18 between the list and what's below it, to sit clear of
		// both. The list gives up the strip under its last whole row for that, never a row: where the
		// rows fill the list, it ends right at the last one and the hint gets 14, or 12 in the least room.
		this.visibleRows = Math.max(1, (above - 12 - LIST_TOP - 2) / PITCH);
		this.listBottom = Math.min(above - 12, Math.max(above - 18, LIST_TOP + 2 + this.visibleRows * PITCH - (PITCH - ROW)));
		this.hintY = this.listBottom + 2 + (above - this.listBottom - 2 - 7) / 2;

		if (this.empty) {
			// The empty list: the piece of map, the headline and hints, then New waypoint under them.
			int newY = 186;
			this.emptyTop = Math.min(0, above - 6 - (newY + 20 + 4));
			this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.new"), b -> this.newWaypoint())
				.bounds(cx - 75, newY + this.emptyTop, 150, 20).build());
			this.search = null;
			this.sortOrder = null;
			this.sortDirection = null;
			this.actions.clear();
			this.marked.clear();
			this.move = null;
			this.visibility = null;
			this.confirm = null;
			return;
		}

		// The folder bar: on the left the folder's name between arrows that step through the folders,
		// at the right end the folder's own buttons, as a selected row has them.
		Folder folder = this.folder();
		int nameRight = folderNameLeft(left) + FOLDER_NAME_WIDTH;
		this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.stepFolder(-1)).bounds(left, FOLDER_Y, ARROW, 20).build());
		this.nextFolder = this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.stepFolder(1)).bounds(nameRight + ACTION_GAP, FOLDER_Y, ARROW, 20).build());
		boolean hidden = store != null && store.folderHidden(folder.key());
		this.folderActions.add(this.action(Component.translatable(hidden ? "diorama.waypoints.show" : "diorama.waypoints.hide"), () -> {
			WaypointStore current = WaypointStore.get();
			if (current != null) {
				current.setFolderHidden(this.folder().key(), !current.folderHidden(this.folder().key()));
				this.rebuildWidgets();
			}
		}));
		// Another dimension's waypoints, shown where the player is as well: Xaero's Minimap switches its
		// waypoint list to another dimension for the same, and shows them at this one's scale.
		String from = folder.dimension();
		if (store != null && here != null && from != null && !from.equals(here)) {
			Button showHere = this.action(CommonComponents.optionStatus(Component.translatable("diorama.folder.show_here"), store.showsIn(here, from)), () -> {
				WaypointStore current = WaypointStore.get();
				if (current != null) {
					current.setShownIn(here, from, !current.showsIn(here, from));
					this.rebuildWidgets();
				}
			});
			showHere.setTooltip(Tooltip.create(Component.translatable("diorama.folder.show_here.tooltip", Waypoints.dimensionName(from), Waypoints.dimensionName(here))));
			this.folderActions.add(showHere);
		}
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

		// The search row, right to left: New waypoint and New folder, each as wide as its label, then
		// the sort direction and the sort order, as wide as the longest order's name so it stays put
		// when stepped. The search box has the rest.
		Component newWaypoint = Component.translatable("diorama.waypoint.new");
		Component newFolder = Component.translatable("diorama.folder.new");
		int newWidth = width(this.font.width(newWaypoint));
		int newFolderWidth = width(this.font.width(newFolder));
		int sortWidth = 0;
		for (WaypointSort sort : WaypointSort.values()) {
			sortWidth = Math.max(sortWidth, width(this.font.width(Component.translatable(sort.key()))));
		}
		int x = left + LIST_WIDTH - newWidth;
		this.addRenderableWidget(Button.builder(newWaypoint, b -> this.newWaypoint()).bounds(x, SEARCH_Y, newWidth, 20).build());
		x -= 4 + newFolderWidth;
		this.addRenderableWidget(Button.builder(newFolder, b -> this.minecraft.gui.setScreen(new WaypointFolderScreen(this, null)))
			.bounds(x, SEARCH_Y, newFolderWidth, 20).build());
		x -= 4 + ARROW;
		this.sortDirection = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.changeSort(false))
			.createNarration(n -> Component.translatable(this.sortDirectionKey())).bounds(x, SEARCH_Y, ARROW, 20).build());
		x -= ACTION_GAP + sortWidth;
		this.sortOrder = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.changeSort(true)).bounds(x, SEARCH_Y, sortWidth, 20).build());
		this.labelSort(store);
		this.searchWidth = x - 4 - left;

		// The search box: vanilla's text field frame with the search icon in it, the text right of the icon.
		String query = this.search != null ? this.search.getValue() : "";
		EditBox box = new EditBox(this.font, left + 20, SEARCH_Y + 6, this.searchWidth - 24, 12, Component.translatable("diorama.waypoints.search"));
		box.setBordered(false);
		box.setMaxLength(32);
		box.setValue(query);
		box.setResponder(text -> this.filter());
		this.search = this.addRenderableWidget(box);
		this.actions.clear();
		this.edit = this.action(Component.translatable("diorama.waypoints.edit"), () -> this.edit(this.selected));
		this.actions.add(this.edit);
		this.move = this.action(Component.translatable("diorama.waypoints.move"), this::openMoveMenu);
		this.actions.add(this.move);
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
		this.filter();
		this.setInitialFocus(box);
	}

	/** Steps to the next sort order or reverses the direction, and sorts the folder again from the top. */
	private void changeSort(boolean nextOrder) {
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			return;
		}
		if (nextOrder) {
			// The direction stays as it was, as Xaero's keeps Reversed Sort Order apart from Sort By.
			store.setSort(store.sort().next(), store.sortReversed());
		} else {
			store.setSort(store.sort(), !store.sortReversed());
		}
		this.labelSort(store);
		this.resort(store);
		this.scroll = 0;
		this.filter();
	}

	/** Sorts the folder's waypoints and the other folders' again, after the order or something it goes by changed. */
	private void resort(WaypointStore store) {
		Entity viewer = MapSession.viewer();
		if (viewer != null && this.minecraft.level != null) {
			String here = this.minecraft.level.dimension().identifier().toString();
			java.util.Comparator<Waypoint> order = store.sort().comparator(store.sortReversed(), w -> this.distance(store, here, viewer, w));
			this.all.sort(order);
			this.others.sort(order);
		}
	}

	/**
	 * How far the player is from a waypoint: from its own spot in their dimension, or from where it
	 * stands here when its dimension is shown here too. NaN for another dimension's.
	 */
	private double distance(WaypointStore store, String here, Entity viewer, Waypoint waypoint) {
		if (waypoint.dimension.equals(here)) {
			return waypoint.distanceTo(viewer.getX(), viewer.getY(), viewer.getZ());
		}
		if (!store.showsIn(here, waypoint.dimension)) {
			return Double.NaN;
		}
		double divider = store.coordinateScale(here) / store.coordinateScale(waypoint.dimension);
		return waypoint.scaled(divider, this.spotHere).distanceTo(viewer.getX(), viewer.getY(), viewer.getZ());
	}

	/** The sort order's name on its button, and on both buttons a tooltip saying what comes first. */
	private void labelSort(@Nullable WaypointStore store) {
		Button order = this.sortOrder;
		Button direction = this.sortDirection;
		if (order == null || direction == null) {
			return;
		}
		WaypointSort sort = store == null ? WaypointSort.DISTANCE : store.sort();
		boolean reversed = store != null && store.sortReversed();
		Component name = Component.translatable(sort.key());
		order.setMessage(name);
		this.sortReversed = reversed;
		Tooltip tooltip = Tooltip.create(Component.translatable("diorama.waypoints.sort", name).append("\n")
			.append(Component.translatable(sort.directionKey(reversed)).withStyle(ChatFormatting.GRAY)));
		order.setTooltip(tooltip);
		direction.setTooltip(tooltip);
	}

	private String sortDirectionKey() {
		WaypointStore store = WaypointStore.get();
		return (store == null ? WaypointSort.DISTANCE : store.sort()).directionKey(this.sortReversed);
	}

	/** The direction button's caret, drawn over the button as {@code MapScreen} draws its slice buttons'. */
	private void drawSortCaret(GuiGraphicsExtractor graphics) {
		Button button = this.sortDirection;
		if (button == null || !button.visible) {
			return;
		}
		int cx = button.getX() + button.getWidth() / 2;
		int y = button.getY() + (button.getHeight() - 3) / 2;
		for (int row = 0; row < 3; row++) {
			int half = this.sortReversed ? 2 - row : row;
			graphics.fill(cx - half, y + row, cx + half + 1, y + row + 1, 0xFFFFFFFF);
		}
	}

	/**
	 * Shows the waypoints the search text finds, any case, and lays the rows out again: those with it
	 * in their name, and everything in a folder with it in the folder's name. With nothing typed
	 * that's the folder shown. With something typed the other folders are searched as well, and
	 * what's found there goes after this folder's, so a waypoint is found without knowing which
	 * folder it's in. Xaero's Minimap's filter looks only through the set it's showing.
	 */
	private void filter() {
		String query = this.query();
		this.rows.clear();
		this.otherStart = -1;
		WaypointStore store = WaypointStore.get();
		boolean whole = !query.isEmpty() && this.folder().name().getString().toLowerCase(Locale.ROOT).contains(query);
		for (Waypoint waypoint : this.all) {
			if (query.isEmpty() || whole || waypoint.shownName().toLowerCase(Locale.ROOT).contains(query)) {
				this.rows.add(waypoint);
			}
		}
		if (!query.isEmpty() && store != null) {
			int start = this.rows.size();
			java.util.Map<String, Boolean> named = new java.util.HashMap<>();
			for (Waypoint waypoint : this.others) {
				boolean inNamed = named.computeIfAbsent(store.folderKey(waypoint), key -> this.folderName(key).toLowerCase(Locale.ROOT).contains(query));
				if (inNamed || waypoint.shownName().toLowerCase(Locale.ROOT).contains(query)) {
					this.rows.add(waypoint);
				}
			}
			if (this.rows.size() > start) {
				this.otherStart = start;
			}
		}
		// A row the search no longer shows leaves the selection; the buttons go to the last one left.
		// Fewer rows selected than Delete was clicked for: it's asked again.
		if (this.marked.retainAll(new java.util.HashSet<>(this.rows))) {
			this.armed = false;
		}
		if (this.selected != null && !this.rows.contains(this.selected)) {
			this.selected = null;
			for (Waypoint waypoint : this.marked) {
				this.selected = waypoint;
			}
			this.armed = false;
		}
		if (this.marked.size() < 2) {
			this.marked.clear();
		}
		this.scrollTo(this.scroll);
	}

	private String query() {
		return this.search != null ? this.search.getValue().trim().toLowerCase(Locale.ROOT) : "";
	}

	/** The name of the folder with this key, or nothing for one the bar doesn't have. */
	private String folderName(String key) {
		for (Folder folder : this.folders) {
			if (folder.key().equals(key)) {
				return folder.name().getString();
			}
		}
		return "";
	}

	/** How many lines the list has: its rows, and the other folders' heading when a search found some there. */
	private int lines() {
		return this.rows.size() + (this.otherStart >= 0 ? 1 : 0);
	}

	/** The line a row is on. */
	private int lineOf(int row) {
		return this.otherStart >= 0 && row >= this.otherStart ? row + 1 : row;
	}

	/** The row on a line, or -1 for the heading's. */
	private int rowOn(int line) {
		if (this.otherStart < 0 || line < this.otherStart) {
			return line;
		}
		return line == this.otherStart ? -1 : line - 1;
	}

	private int maxScroll() {
		return Math.max(0, this.lines() - this.visibleRows);
	}

	private void scrollTo(int scroll) {
		this.scroll = Math.max(0, Math.min(scroll, this.maxScroll()));
		this.placeRowButtons();
	}

	private void select(@Nullable Waypoint waypoint) {
		this.selected = waypoint;
		this.marked.clear();
		this.armed = false;
		this.placeRowButtons();
	}

	/**
	 * The selected row's buttons, right-aligned inside it: Edit, Hide or Show, Teleport, then
	 * Delete in red. Once Delete is clicked they become one red Click again to delete across them.
	 * A waystone's row has only Hide or Show and Teleport. Several rows have Move, Hide or Show and
	 * Delete, on the row clicked last.
	 */
	private void placeRowButtons() {
		int row = this.selected == null ? -1 : this.rows.indexOf(this.selected);
		int index = row < 0 ? -1 : this.lineOf(row) - this.scroll;
		boolean shown = index >= 0 && index < this.visibleRows;
		Button visibility = this.visibility;
		boolean several = this.marked.size() > 1;
		if (shown && visibility != null) {
			// Several rows: Hide while any of them shows, as Xaero's Disable/Enable goes by the selection.
			boolean hide = several ? this.marked.stream().anyMatch(w -> w.visible) : this.selected.visible;
			Component label = Component.translatable(hide ? "diorama.waypoints.hide" : "diorama.waypoints.show");
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
		if (shown && teleport != null && buttons.contains(teleport)) {
			teleport.active = !this.armed && Waypoints.canTeleport(this.selected.dimension);
		}
		Button confirm = this.confirm;
		if (confirm != null) {
			show(confirm, shown && this.armed);
			// Several rows: it says how many go, and is as wide as that takes.
			Component ask = several
				? Component.translatable("diorama.waypoints.delete_many", this.movable().size()).withStyle(ChatFormatting.RED)
				: Component.translatable("diorama.menu.delete_confirm").withStyle(ChatFormatting.RED);
			confirm.setMessage(ask);
			int wide = several ? Math.max(span, this.font.width(ask) + 10) : span;
			//? if >=1.21 {
			confirm.setRectangle(wide, 20, right - wide, y);
			//?} else {
			/*confirm.setWidth(wide);
			confirm.setPosition(right - wide, y);
			*///?}
		}
	}

	/**
	 * The folder's buttons, right-aligned in the folder bar. The last one, for a folder of the
	 * player's own, is Click again to delete: shown alone across the others once Delete is clicked.
	 */
	private void placeFolderButtons() {
		boolean custom = this.folder().custom() != null;
		List<Button> buttons = custom ? this.folderActions.subList(0, this.folderActions.size() - 1) : this.folderActions;
		int span = -ACTION_GAP;
		for (Button button : buttons) {
			span += button.getWidth() + ACTION_GAP;
		}
		int right = this.width / 2 + LIST_WIDTH / 2;
		int x = right - span;
		// The name and its second arrow end clear of the buttons: Russian and Belarusian ones are
		// wide enough to reach over a name of the usual width.
		int nameLeft = folderNameLeft(right - LIST_WIDTH);
		this.folderNameWidth = Math.max(60, Math.min(FOLDER_NAME_WIDTH, x - 6 - ARROW - ACTION_GAP - nameLeft));
		if (this.nextFolder != null) {
			this.nextFolder.setPosition(nameLeft + this.folderNameWidth + ACTION_GAP, FOLDER_Y);
		}
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

	/**
	 * The buttons the selected row has: all but Move, and for a waystone not Edit and Delete either.
	 * Several rows have Move, Hide or Show and Delete; Move and Delete only with a row among them
	 * that isn't a waystone.
	 */
	private List<Button> rowActions() {
		List<Button> buttons = new ArrayList<>(this.actions);
		if (this.marked.size() > 1) {
			buttons.remove(this.edit);
			buttons.remove(this.teleport);
			if (this.movable().isEmpty()) {
				buttons.remove(this.move);
				buttons.remove(this.delete);
			}
			return buttons;
		}
		buttons.remove(this.move);
		if (this.selected != null && !this.selected.editable()) {
			buttons.remove(this.edit);
			buttons.remove(this.delete);
		}
		return buttons;
	}

	/** How wide the selected row's buttons are together, with the gap before them. */
	private int actionsWidth() {
		int span = 0;
		for (Button button : this.rowActions()) {
			span += button.getWidth() + ACTION_GAP;
		}
		// Click again to delete, for several rows, can be wider than the buttons it stands in for.
		Button confirm = this.confirm;
		if (this.armed && confirm != null) {
			span = Math.max(span, confirm.getWidth() + ACTION_GAP);
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
		if (index < 0 || index >= this.visibleRows || this.scroll + index >= this.lines() || y >= this.rowY(index) + ROW) {
			return -1;
		}
		return this.rowOn(this.scroll + index);
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
		boolean overMenu = this.folderMenu != null && this.folderMenu.contains(mouseX, mouseY);
		super.render(graphics, overMenu ? -1 : mouseX, overMenu ? -1 : mouseY, a);
		this.drawSortCaret(graphics);
		this.drawFolderMenu(graphics, mouseX, mouseY);
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
		int nameRight = nameLeft + this.folderNameWidth;
		ScreenArt.sprite(graphics, ScreenArt.TEXT_FIELD, nameLeft, FOLDER_Y, nameRight - nameLeft, 20);
		WaypointStore store = WaypointStore.get();
		Folder folder = this.folder();
		boolean folderHidden = store != null && store.folderHidden(folder.key());
		String label = this.fit(folder.name().getString() + " (" + this.all.size() + ")", nameRight - nameLeft - 28);
		graphics.centeredText(this.font, Component.literal(label), (nameLeft + nameRight) / 2, FOLDER_Y + 6, folderHidden ? HIDDEN : 0xFFFFFFFF);
		// A caret at the name's right end: a click on the name opens the list of folders.
		for (int row = 0; row < 3; row++) {
			graphics.fill(nameRight - 12 + row, FOLDER_Y + 9 + row, nameRight - 7 - row, FOLDER_Y + 10 + row, DETAIL);
		}
		EditBox box = this.search;
		if (box != null) {
			ScreenArt.sprite(graphics, box.isFocused() ? ScreenArt.TEXT_FIELD_FOCUSED : ScreenArt.TEXT_FIELD, left, SEARCH_Y, this.searchWidth, 20);
			ScreenArt.sprite(graphics, ScreenArt.SEARCH, left + 4, SEARCH_Y + 4, 12, 12);
			if (box.getValue().isEmpty()) {
				// Cut short in a language whose button labels leave the box narrower.
				graphics.text(this.font, this.fit(Component.translatable("diorama.waypoints.search").getString(), this.searchWidth - 24), left + 20, SEARCH_Y + 6, SEARCH_HINT, false);
			}
		}
		ScreenArt.list(graphics, 0, LIST_TOP, this.width, this.listBottom);
		Entity viewer = MapSession.viewer();
		if (this.rows.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable(this.all.isEmpty() && this.query().isEmpty() ? "diorama.folder.empty" : "diorama.waypoints.no_match"), cx, LIST_TOP + 20, HINT);
		}
		String here = this.minecraft.level == null ? "" : this.minecraft.level.dimension().identifier().toString();
		// Names start clear of the widest badge showing: initials of three or four letters widen it.
		int textLeft = TEXT_LEFT;
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.lines(); i++) {
			int row = this.rowOn(this.scroll + i);
			if (row >= 0) {
				textLeft = Math.max(textLeft, (int) Math.ceil(badgeX(this.rows.get(row)) + Pins.badgeWidth(this.rows.get(row), Pins.LARGE) / 2.0F) + 2);
			}
		}
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.lines(); i++) {
			int row = this.rowOn(this.scroll + i);
			int y = this.rowY(i);
			if (row < 0) {
				// What the search found in the other folders goes under this, each row saying which folder.
				graphics.centeredText(this.font, Component.translatable("diorama.waypoints.other_folders"), cx, y + 8, HINT);
				graphics.fill(left, y + ROW - 3, right, y + ROW - 2, 0xFF606060);
				continue;
			}
			Waypoint waypoint = this.rows.get(row);
			boolean other = this.otherStart >= 0 && row >= this.otherStart;
			// A row from another folder is dimmed when that folder is hidden, not when this one is.
			boolean inHidden = other ? store != null && store.folderHidden(store.folderKey(waypoint)) : folderHidden;
			boolean selected = waypoint == this.selected;
			if (selected || this.marked.contains(waypoint)) {
				ScreenArt.selection(graphics, left, y, right, y + ROW);
			}
			Pins.badge(graphics, waypoint, left + badgeX(waypoint), y + 12.5F, Pins.LARGE);
			// The name and position stop short of the selected row's buttons.
			int room = right - (selected ? this.actionsWidth() + 4 : 6) - (left + textLeft);
			String full = waypoint.shownName();
			String name = this.fit(full, room);
			graphics.text(this.font, name, left + textLeft, y + 4, waypoint.visible && !inHidden ? 0xFFFFFFFF : HIDDEN, true);
			String detail = waypoint.x + ", " + waypoint.y + ", " + waypoint.z;
			if (!waypoint.dimension.equals(here)) {
				detail = Waypoints.dimensionName(waypoint.dimension).getString() + " · " + detail;
				double distance = viewer == null || store == null ? Double.NaN : this.distance(store, here, viewer, waypoint);
				if (!Double.isNaN(distance)) {
					// Shown here too: how far its spot here is.
					detail = Math.round(distance) + " m · " + detail;
				}
			} else if (viewer != null) {
				detail = Math.round(waypoint.distanceTo(viewer.getX(), viewer.getY(), viewer.getZ())) + " m · " + detail;
			}
			// Its folder, unless that's its dimension's own and the dimension is named already.
			if (other && store != null) {
				String key = store.folderKey(waypoint);
				if (waypoint.dimension.equals(here) || !key.equals(WaypointStore.dimensionKey(waypoint.dimension))) {
					detail = this.folderName(key) + " · " + detail;
				}
			}
			if (!waypoint.visible) {
				detail += "  ·  " + Component.translatable("diorama.waypoint.hidden").getString();
			}
			String shownDetail = this.fit(detail, room);
			graphics.text(this.font, shownDetail, left + textLeft, y + 14, DETAIL, true);
			boolean overText = mouseX >= left + textLeft && mouseX < left + textLeft + room && mouseY >= y && mouseY < y + ROW;
			if (overText && this.folderMenu == null && (!name.equals(full) || !shownDetail.equals(detail))) {
				//? if >=1.21.6 {
				graphics.setComponentTooltipForNextFrame(this.font, List.of(Component.literal(full), Component.literal(detail)), mouseX, mouseY);
				//?} else {
				/*// The screen draws this tooltip after everything else.
				this.setTooltipForNextRenderPass(List.of(Component.literal(full).getVisualOrderText(), Component.literal(detail).getVisualOrderText()));
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
		// Under the list, like the line under the map: nothing else says several rows can be selected.
		String hint = Component.translatable("diorama.waypoints.select_hint", MAC ? "Cmd" : "Ctrl").getString();
		graphics.centeredText(this.font, Component.literal(this.fit(hint, this.width - 8)), cx, this.hintY, HINT);
	}

	/** The scrollbar: its x, the handle's height and how far down the track it sits. */
	private int[] scrollbar() {
		int track = this.listBottom - LIST_TOP;
		int handle = Math.max(ScreenArt.SCROLLER_MIN, Math.min(track - 8, track * this.visibleRows / Math.max(1, this.lines())));
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
		if (this.folderMenu != null) {
			this.folderMenu.scroll(scrollY);
			return true;
		}
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
		if (this.folderMenu != null) {
			this.keyFolderMenu(event.key());
			return true;
		}
		if (DioramaClient.NEW_WAYPOINT.matches(event) && !(this.getFocused() instanceof EditBox)) {
			this.newWaypointPressed = true;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.folderMenu != null) {
			this.clickFolderMenu(event.x(), event.y(), event.button());
			return true;
		}
		// What came with the click, and what's held now: a click made by a test brings no modifiers.
		net.minecraft.client.Minecraft held = net.minecraft.client.Minecraft.getInstance();
		this.shiftClick = event.hasShiftDown() || held.hasShiftDown();
		//? if >=1.21.11 {
		this.controlClick = event.hasControlDownWithQuirk() || !MAC && held.hasControlDown();
		//?} else {
		/*this.controlClick = event.hasControlDown() || !MAC && held.hasControlDown();
		*///?}
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
		if (this.folderMenu != null) {
			this.keyFolderMenu(key);
			return true;
		}
		if (DioramaClient.NEW_WAYPOINT.matches(key, scancode) && !(this.getFocused() instanceof EditBox)) {
			this.newWaypointPressed = true;
			return true;
		}
		return super.keyPressed(key, scancode, modifiers);
	}

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		if (this.folderMenu != null) {
			this.clickFolderMenu(x, y, button);
			return true;
		}
		this.shiftClick = Screen.hasShiftDown();
		this.controlClick = Screen.hasControlDown();
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
		// A click on the folder's name opens the list of folders to pick from.
		if (x >= folderNameLeft(left) && x < folderNameLeft(left) + this.folderNameWidth && y >= FOLDER_Y && y < FOLDER_Y + 20) {
			this.setFocused(null);
			this.openFolderMenu();
			return true;
		}
		EditBox box = this.search;
		if (box != null && x >= left && x < left + this.searchWidth && y >= SEARCH_Y && y < SEARCH_Y + 20) {
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
		boolean shift = this.shiftClick;
		if (shift || this.controlClick) {
			this.lastClicked = null;
			this.selectMore(waypoint, row, shift);
			return true;
		}
		this.anchor = waypoint;
		if (twice) {
			this.edit(waypoint);
		} else if (waypoint != this.selected || !this.marked.isEmpty()) {
			// A plain click leaves this row alone selected, also when it was one of several.
			this.select(waypoint);
		}
		return true;
	}

	/**
	 * A Shift or Ctrl click on a row, as in Xaero's Minimap's list and any file list: Shift selects
	 * the rows from the last one clicked without it to this one, Ctrl adds this row to the
	 * selection or takes it out. The buttons go to the row clicked.
	 */
	private void selectMore(Waypoint waypoint, int row, boolean shift) {
		this.armed = false;
		int from = shift && this.anchor != null ? this.rows.indexOf(this.anchor) : -1;
		if (from >= 0) {
			this.marked.clear();
			for (int i = Math.min(from, row); i <= Math.max(from, row); i++) {
				this.marked.add(this.rows.get(i));
			}
			this.selected = waypoint;
		} else if (waypoint == this.selected || this.marked.contains(waypoint)) {
			this.anchor = waypoint;
			this.marked.remove(waypoint);
			if (waypoint == this.selected) {
				this.selected = null;
				for (Waypoint left : this.marked) {
					this.selected = left;
				}
			}
		} else {
			this.anchor = waypoint;
			if (this.selected != null) {
				this.marked.add(this.selected);
			}
			this.marked.add(waypoint);
			this.selected = waypoint;
		}
		if (this.marked.size() < 2) {
			this.marked.clear();
		}
		this.placeRowButtons();
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
			// The open list of folders has the counts from before: closed, to be opened on the new ones.
			this.folderMenu = null;
			WaypointStore store = WaypointStore.get();
			boolean hasWaystones = store != null && !store.waystones().isEmpty();
			// A search may have waystones among its rows whatever the folder.
			if (hasWaystones != this.hadWaystones || WaypointStore.WAYSTONES_KEY.equals(this.folderKey) || !this.query().isEmpty()) {
				this.rebuildWidgets();
			} else if (store != null) {
				// Nothing typed and another folder shown: the waystones a search would look through, made anew.
				this.others.removeIf(w -> w.kind == Waypoint.Kind.WAYSTONE);
				this.others.addAll(store.waystones());
				this.resort(store);
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
		if (store == null) {
			return;
		}
		if (this.marked.size() > 1) {
			boolean hide = this.marked.stream().anyMatch(w -> w.visible);
			for (Waypoint each : List.copyOf(this.marked)) {
				if (each.visible == hide) {
					store.setVisible(each, !hide);
				}
			}
		} else {
			store.setVisible(waypoint, !waypoint.visible);
		}
		// Hidden waypoints go last, as in Xaero's: the row moves, and the list follows it so the row
		// and its buttons stay in view.
		this.resort(store);
		this.filter();
		int index = this.rows.indexOf(waypoint);
		index = index < 0 ? -1 : this.lineOf(index);
		if (index >= 0 && index < this.scroll) {
			this.scrollTo(index);
		} else if (index >= this.scroll + this.visibleRows) {
			this.scrollTo(index - this.visibleRows + 1);
		}
	}

	private void delete(@Nullable Waypoint waypoint) {
		WaypointStore store = WaypointStore.get();
		if (store != null && this.marked.size() > 1) {
			this.movable().forEach(store::remove);
		} else if (store != null && waypoint != null) {
			store.remove(waypoint);
		}
		this.selected = null;
		this.marked.clear();
		this.armed = false;
		this.rebuildWidgets();
	}

	private void teleport(@Nullable Waypoint waypoint) {
		if (waypoint != null) {
			Waypoints.teleport(waypoint.dimension, waypoint.x, waypoint.y, waypoint.z);
		}
		this.minecraft.gui.setScreen(null);
	}

	//? if >=1.21 {
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// The buttons under the open list of folders don't light up as the mouse goes over them.
		boolean overMenu = this.folderMenu != null && this.folderMenu.contains(mouseX, mouseY);
		super.extractRenderState(graphics, overMenu ? -1 : mouseX, overMenu ? -1 : mouseY, a);
		this.drawSortCaret(graphics);
		this.drawFolderMenu(graphics, mouseX, mouseY);
	}
	//?}

	/** The open list of folders, over everything drawn so far, text included. */
	private void drawFolderMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		FolderMenu menu = this.folderMenu;
		if (menu == null) {
			return;
		}
		//? if >=1.21.6 {
		graphics.nextStratum();
		menu.draw(graphics, mouseX, mouseY);
		//?} else {
		/*graphics.flush();
		var pose = graphics.pose();
		pose.pushPose();
		pose.translate(0, 0, 400);
		menu.draw(graphics, mouseX, mouseY);
		pose.popPose();
		*///?}
	}

	/** The open list of folders, or null. For tests. */
	public @Nullable FolderMenu folderMenu() {
		return this.folderMenu;
	}

	/** The middle of the folder's name in the folder bar. For tests. */
	public int[] folderNameCentre() {
		return new int[] {folderNameLeft(this.width / 2 - LIST_WIDTH / 2) + this.folderNameWidth / 2, FOLDER_Y + 10};
	}

	/** The sort direction's button, which has a caret for a label. For tests. */
	public @Nullable Button sortDirection() {
		return this.sortDirection;
	}

	/** The waypoint whose row is selected, or null. For tests. */
	public @Nullable Waypoint selected() {
		return this.selected;
	}

	/** Every selected row's waypoint, in the order selected. For tests. */
	public List<Waypoint> selection() {
		return !this.marked.isEmpty() ? List.copyOf(this.marked) : this.selected == null ? List.of() : List.of(this.selected);
	}

	/** The name of the folder shown. For tests. */
	public String folderName() {
		return this.folders.isEmpty() ? "" : this.folder().name().getString();
	}

	/** How many of {@link #shown} are the folder's own; the rest were found in other folders. For tests. */
	public int shownInFolder() {
		return this.otherStart < 0 ? this.rows.size() : this.otherStart;
	}

	/** The rows the search lets through, in the sort order: the folder's, then the other folders'. For tests. */
	public List<Waypoint> shown() {
		return List.copyOf(this.rows);
	}

	/** The middle of a waypoint's name on screen, or null when its row is scrolled out of view. For tests. */
	public int @Nullable [] rowCentre(Waypoint waypoint) {
		int row = this.rows.indexOf(waypoint);
		int index = row < 0 ? -1 : this.lineOf(row) - this.scroll;
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
