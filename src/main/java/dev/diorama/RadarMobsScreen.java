package dev.diorama;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Picks the mobs in one of the player's radar categories: every mob the game has, a mod's included,
 * by name, with a search box, and before them a tick for every mob a mod adds. Xaero's Minimap
 * takes typed entity IDs and patterns in its include lists; here nobody has to know an ID, and a
 * mod's tick is the pattern {@code mod:*}.
 */
public final class RadarMobsScreen extends Screen {
	private static final int WIDTH = 300;
	private static final int COLUMN_GAP = 8;
	private static final int ROW = 22;
	private static final int TOP = 58;

	private final Screen parent;
	private final RadarCategories.Category category;
	private final List<RadarCategories.Mob> mobs = RadarCategories.mobs();
	/** A row to tick: a mob, or every mob of a mod. */
	private record Row(String entry, Component label, Component tooltip) {
	}

	private final List<Row> rows = new ArrayList<>();
	private final List<AbstractWidget> listed = new ArrayList<>();
	private @Nullable EditBox search;
	private String typed = "";
	private int page;

	public RadarMobsScreen(Screen parent, RadarCategories.Category category) {
		super(Component.translatable("diorama.radar_mobs.title", category.name()));
		this.parent = parent;
		this.category = category;
		for (String mod : RadarCategories.mobMods(this.mobs)) {
			this.rows.add(new Row(mod + ":*", Component.translatable("diorama.radar_mobs.every", RadarCategories.modName(mod)), Component.literal(mod + ":*")));
		}
		for (RadarCategories.Mob mob : this.mobs) {
			this.rows.add(new Row(mob.id(), mob.name(), Component.literal(mob.id())));
		}
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - TOP - 36) / ROW);
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDTH / 2;
		EditBox box = new EditBox(this.font, left, 32, WIDTH, 20, Component.translatable("diorama.radar_mobs.search"));
		box.setMaxLength(64);
		box.setValue(this.typed);
		box.setResponder(text -> {
			if (!text.equals(this.typed)) {
				this.typed = text;
				this.page = 0;
				this.list();
			}
		});
		this.search = this.addRenderableWidget(box);
		this.listed.clear();
		this.list();
		this.setInitialFocus(box);
	}

	/** The rows the search lets through: a mob by its name or ID, a mod by its name or ID. */
	private List<Row> found() {
		String wanted = this.typed.trim().toLowerCase(Locale.ROOT);
		if (wanted.isEmpty()) {
			return this.rows;
		}
		List<Row> found = new ArrayList<>();
		for (Row row : this.rows) {
			if (row.label().getString().toLowerCase(Locale.ROOT).contains(wanted) || row.entry().contains(wanted)) {
				found.add(row);
			}
		}
		return found;
	}

	/** Puts the page's ticks and the buttons under them on the screen, in place of the ones there. Typing keeps the search box as it is. */
	private void list() {
		this.listed.forEach(this::removeWidget);
		this.listed.clear();
		int left = this.width / 2 - WIDTH / 2;
		int column = (WIDTH - COLUMN_GAP) / 2;
		List<Row> found = this.found();
		int perPage = this.rowsPerPage() * 2;
		int pages = Math.max(1, (found.size() + perPage - 1) / perPage);
		this.page = Math.max(0, Math.min(this.page, pages - 1));
		for (int i = this.page * perPage; i < Math.min(found.size(), (this.page + 1) * perPage); i++) {
			Row row = found.get(i);
			boolean own = this.category.mobs.contains(row.entry());
			// A mob that another entry already takes in, as a ticked mod does, is ticked whether or not
			// it's listed itself, and unticked by unticking that entry.
			boolean byPattern = false;
			for (String mob : this.category.mobs) {
				byPattern |= !mob.equals(row.entry()) && RadarCategories.matches(mob, row.entry());
			}
			Checkbox checkbox = SettingsScreen.checkbox(row.label(), this.font, own || byPattern, column, ticked -> {
				this.category.mobs.remove(row.entry());
				if (ticked) {
					this.category.mobs.add(row.entry());
				}
				RadarCategories.changed();
				// A mod's tick changes its mobs' ticks.
				if (row.entry().endsWith("*")) {
					this.list();
				}
			});
			int slot = i - this.page * perPage;
			checkbox.setPosition(left + (slot % 2) * (column + COLUMN_GAP), TOP + (slot / 2) * ROW);
			checkbox.setTooltip(Tooltip.create(row.tooltip()));
			if (byPattern) {
				checkbox.active = false;
				checkbox.setAlpha(0.5F);
			}
			this.listed.add(this.addRenderableWidget(checkbox));
		}
		int bottom = this.height - 26;
		if (pages > 1) {
			Button back = Button.builder(Component.literal("<"), b -> this.turn(-1)).bounds(left, bottom, 20, 20).build();
			back.active = this.page > 0;
			this.listed.add(this.addRenderableWidget(back));
			Button on = Button.builder(Component.literal(">"), b -> this.turn(1)).bounds(left + WIDTH - 20, bottom, 20, 20).build();
			on.active = this.page < pages - 1;
			this.listed.add(this.addRenderableWidget(on));
		}
		this.listed.add(this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
			.bounds(this.width / 2 - 100, bottom, 200, 20).build()));
	}

	private void turn(int pages) {
		this.page += pages;
		this.list();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// A 1.20.1 screen draws its own background.
		//? if <1.21 {
		/*this.renderBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		// The game's own hint goes while the box has focus, which this one always starts with.
		if (this.search != null && this.search.getValue().isEmpty()) {
			graphics.text(this.font, Component.translatable("diorama.radar_mobs.search"), this.search.getX() + 4, this.search.getY() + 6, 0xFF707070, false);
		}
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
