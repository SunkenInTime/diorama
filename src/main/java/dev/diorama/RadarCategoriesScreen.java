package dev.diorama;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The radar's categories, one to a row: a tick for whether it shows, what it changes, and Edit. The
 * player's own come first, in the order a mob is matched to them, then the five kinds every mob,
 * player and dropped item falls into. Xaero's Minimap's "Entity Category Settings" is the same idea
 * as a tree; see {@link RadarCategories}.
 */
public final class RadarCategoriesScreen extends Screen {
	private static final int WIDTH = 300;
	private static final int ROW = 24;
	private static final int TOP = 62;
	private static final int EDIT = 50;
	private static final int SUMMARY = 0xFFA0A0A0;

	private final Screen parent;
	private int page;
	/** What each row on the page says beside its Edit button, top to bottom. */
	private final List<Component> summaries = new ArrayList<>();

	public RadarCategoriesScreen(Screen parent) {
		super(Component.translatable("diorama.radar_categories.title"));
		this.parent = parent;
	}

	/** What the settings button says: how many categories there are, the player's own included. */
	public static Component summary() {
		return Component.literal(Integer.toString(RadarCategories.all().size()));
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - TOP - 36) / ROW);
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDTH / 2;
		MultiLineTextWidget about = new MultiLineTextWidget(Component.translatable("diorama.radar_categories.about").withStyle(ChatFormatting.GRAY), this.font);
		about.setMaxWidth(WIDTH);
		about.setCentered(true);
		about.setPosition(this.width / 2 - about.getWidth() / 2, 30);
		this.addRenderableWidget(about);

		List<RadarCategories.Entry> entries = RadarCategories.all();
		int perPage = this.rowsPerPage();
		int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
		this.page = Math.min(this.page, pages - 1);
		this.summaries.clear();
		for (int i = this.page * perPage; i < Math.min(entries.size(), (this.page + 1) * perPage); i++) {
			RadarCategories.Entry entry = entries.get(i);
			int y = TOP + (i - this.page * perPage) * ROW;
			Checkbox checkbox = SettingsScreen.checkbox(entry.name(), this.font, entry.shown(), WIDTH / 2, entry::shown);
			checkbox.setPosition(left, y);
			this.addRenderableWidget(checkbox);
			this.summaries.add(summary(entry));
			this.addRenderableWidget(Button.builder(Component.translatable("diorama.radar_categories.edit"), b -> this.minecraft.gui.setScreen(new RadarCategoryScreen(this, entry)))
				.bounds(left + WIDTH - EDIT, y, EDIT, 20).build());
		}
		int bottom = this.height - 26;
		if (pages > 1) {
			Button back = Button.builder(Component.literal("<"), b -> this.turn(-1)).bounds(left, bottom, 20, 20).build();
			back.active = this.page > 0;
			this.addRenderableWidget(back);
			Button on = Button.builder(Component.literal(">"), b -> this.turn(1)).bounds(left + WIDTH - 20, bottom, 20, 20).build();
			on.active = this.page < pages - 1;
			this.addRenderableWidget(on);
		}
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.radar_categories.new"), b -> this.add())
			.bounds(this.width / 2 - 122, bottom, 120, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
			.bounds(this.width / 2 + 2, bottom, 120, 20).build());
	}

	/** "3 mobs · 150% · Names", leaving out what the category doesn't change. */
	private static Component summary(RadarCategories.Entry entry) {
		List<Component> parts = new ArrayList<>();
		if (entry instanceof RadarCategories.Category category) {
			parts.add(Component.translatable("diorama.radar_categories.mobs_count", category.mobs.size()));
		}
		if (entry.iconScale() != RadarCategories.SAME_SIZE) {
			parts.add(Component.literal(entry.iconScale() + "%"));
		}
		if (entry.hasNames() && entry.names() != Settings.NameTags.NEVER) {
			parts.add(Component.translatable("diorama.radar_categories.names_shown"));
		}
		net.minecraft.network.chat.MutableComponent line = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				line.append(" · ");
			}
			line.append(parts.get(i));
		}
		return line;
	}

	/** A new category goes on top, so its mobs follow it and not a category that already lists them. */
	private void add() {
		RadarCategories.Category category = new RadarCategories.Category();
		Settings.get().radarCategories.add(0, category);
		RadarCategories.changed();
		Settings.get().save();
		this.page = 0;
		this.minecraft.gui.setScreen(new RadarCategoryScreen(this, category));
	}

	private void turn(int pages) {
		this.page += pages;
		this.rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// A 1.20.1 screen draws its own background.
		//? if <1.21 {
		/*this.renderBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		int right = this.width / 2 + WIDTH / 2 - EDIT - 6;
		for (int i = 0; i < this.summaries.size(); i++) {
			Component summary = this.summaries.get(i);
			graphics.text(this.font, summary, right - this.font.width(summary), TOP + i * ROW + 6, SUMMARY, true);
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
