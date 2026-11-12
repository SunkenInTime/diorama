package dev.diorama;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * One radar category's settings: whether it shows, its icon size and its names, and for one of the
 * player's own its name, its mobs and Delete. Each change is saved as it's made, as on the settings
 * screen. Xaero's Minimap has these three per category among a dozen more.
 */
public final class RadarCategoryScreen extends Screen {
	private static final int WIDTH = 260;
	private static final int ROW = 24;
	private static final int LABEL = 0xFFA0A0A0;

	private final Screen parent;
	private final RadarCategories.Entry entry;
	private final RadarCategories.@Nullable Category own;
	private @Nullable EditBox name;
	/** Delete was clicked once and asks for a second click. */
	private boolean armed;

	public RadarCategoryScreen(Screen parent, RadarCategories.Entry entry) {
		super(entry.name());
		this.parent = parent;
		this.entry = entry;
		this.own = entry instanceof RadarCategories.Category category ? category : null;
	}

	private int rows() {
		return 3 + (this.own != null ? 2 : 0);
	}

	private int top() {
		return Math.max(34, this.height / 2 - (this.rows() * ROW + 30) / 2);
	}

	@Override
	protected void init() {
		Settings settings = Settings.get();
		int left = this.width / 2 - WIDTH / 2;
		int y = this.top();
		RadarCategories.Category own = this.own;
		if (own != null) {
			String typed = this.name != null ? this.name.getValue() : own.name == null ? "" : own.name;
			EditBox box = new EditBox(this.font, left + 60, y, WIDTH - 60, 20, Component.translatable("diorama.waypoint.name"));
			box.setMaxLength(32);
			box.setValue(typed);
			box.setHint(own.name().copy().withStyle(ChatFormatting.DARK_GRAY));
			box.setResponder(text -> {
				own.name = text.isBlank() ? null : text.trim();
				settings.save();
			});
			this.name = this.addRenderableWidget(box);
			this.setInitialFocus(box);
			y += ROW;
		}
		Checkbox shown = SettingsScreen.checkbox(Component.translatable("diorama.radar_category.shown"), this.font, this.entry.shown(), WIDTH, this.entry::shown);
		shown.setPosition(left, y);
		shown.setTooltip(Tooltip.create(Component.translatable("diorama.radar_category.shown.tooltip")));
		this.addRenderableWidget(shown);
		y += ROW;

		// The first step follows the Radar tab's Icon size; the rest are its own steps.
		int steps = (Settings.MAX_SCALE - Settings.MIN_SCALE) / Settings.SCALE_STEP + 1;
		int scale = this.entry.iconScale();
		int step = scale == RadarCategories.SAME_SIZE ? 0 : 1 + (Settings.snapScale(scale) - Settings.MIN_SCALE) / Settings.SCALE_STEP;
		SettingsScreen.StepSlider size = new SettingsScreen.StepSlider(Component.translatable("diorama.settings.icon_size"), step, steps + 1,
			i -> this.entry.iconScale(i == 0 ? RadarCategories.SAME_SIZE : Settings.MIN_SCALE + (i - 1) * Settings.SCALE_STEP),
			i -> i == 0 ? Component.translatable("diorama.radar_category.same_size", settings.radarIconScale + "%") : Component.literal((Settings.MIN_SCALE + (i - 1) * Settings.SCALE_STEP) + "%"));
		size.setPosition(left, y);
		size.setWidth(WIDTH);
		size.setTooltip(Tooltip.create(Component.translatable("diorama.radar_category.icon_size.tooltip")));
		this.addRenderableWidget(size);
		y += ROW;

		if (this.entry.hasNames()) {
			Settings.NameTags[] values = Settings.NameTags.values();
			Component label = Component.translatable("diorama.radar_category.names");
			this.addRenderableWidget(Button.builder(SettingsScreen.label(label, namesValue(this.entry.names())), b -> {
				Settings.NameTags next = values[(this.entry.names().ordinal() + 1) % values.length];
				this.entry.names(next);
				settings.save();
				b.setMessage(SettingsScreen.label(label, namesValue(next)));
			}).bounds(left, y, WIDTH, 20).tooltip(Tooltip.create(Component.translatable("diorama.radar_category.names.tooltip"))).build());
		}
		y += ROW;

		if (own != null) {
			this.addRenderableWidget(Button.builder(SettingsScreen.label(Component.translatable("diorama.radar_category.mobs"), Component.literal(Integer.toString(own.mobs.size()))),
				b -> this.minecraft.gui.setScreen(new RadarMobsScreen(this, own)))
				.bounds(left, y, WIDTH, 20).tooltip(Tooltip.create(Component.translatable("diorama.radar_category.mobs.tooltip"))).build());
			y += ROW;
		}

		int bottom = y + 10;
		if (own != null) {
			Component delete = Component.translatable(this.armed ? "diorama.menu.delete_confirm" : "diorama.waypoint.delete").withStyle(ChatFormatting.RED);
			this.addRenderableWidget(Button.builder(delete, b -> this.delete()).bounds(left, bottom, WIDTH / 2 - 2, 20).build());
			this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
				.bounds(left + WIDTH / 2 + 2, bottom, WIDTH / 2 - 2, 20).build());
		} else {
			this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
				.bounds(this.width / 2 - 100, bottom, 200, 20).build());
		}
	}

	private static Component namesValue(Settings.NameTags names) {
		return Component.translatable("diorama.settings.player_names." + names.name().toLowerCase(Locale.ROOT));
	}

	private void delete() {
		if (!this.armed) {
			this.armed = true;
			this.rebuildWidgets();
			return;
		}
		Settings.get().radarCategories.remove(this.own);
		RadarCategories.changed();
		Settings.get().save();
		this.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// A 1.20.1 screen draws its own background.
		//? if <1.21 {
		/*this.renderBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		// The name as it is now, a typed one included.
		graphics.centeredText(this.font, this.entry.name(), this.width / 2, this.top() - 20, 0xFFFFFFFF);
		if (this.own != null) {
			graphics.text(this.font, Component.translatable("diorama.waypoint.name"), this.width / 2 - WIDTH / 2, this.top() + 6, LABEL, true);
		}
	}

	@Override
	public void onClose() {
		// The game makes the list behind again as it shows it, with this category's name and what it changes.
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
