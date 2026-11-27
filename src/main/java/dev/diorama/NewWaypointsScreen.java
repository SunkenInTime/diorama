package dev.diorama;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Where a waypoint the player makes shows at first: the waypoint editor's three Visibility boxes,
 * set here for every new one. Each change is saved as it's made, as on the settings screen. From
 * Settings, Waypoints, whose tab has no room for three more checkboxes.
 */
public final class NewWaypointsScreen extends Screen {
	private static final int WIDTH = 260;
	private static final int ROW = 22;

	private final Screen parent;

	public NewWaypointsScreen(Screen parent) {
		super(Component.translatable("diorama.new_waypoints.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Settings s = Settings.get();
		int left = this.width / 2 - WIDTH / 2;
		MultiLineTextWidget about = new MultiLineTextWidget(Component.translatable("diorama.new_waypoints.about").withStyle(net.minecraft.ChatFormatting.GRAY), this.font);
		about.setMaxWidth(WIDTH);
		int height = about.getHeight() + 8 + 3 * ROW + 10 + 20;
		int y = Math.max(28, this.height / 2 - height / 2);
		about.setPosition(left, y);
		this.addRenderableWidget(about);
		y += about.getHeight() + 8;
		y = this.place(left, y, "in_world", s.newWaypointInWorld, v -> s.newWaypointInWorld = v);
		y = this.place(left, y, "on_minimap", s.newWaypointOnMinimap, v -> s.newWaypointOnMinimap = v);
		y = this.place(left, y, "on_map", s.newWaypointOnMap, v -> s.newWaypointOnMap = v);
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
			.bounds(this.width / 2 - 100, y + 10, 200, 20).build());
	}

	/** One of the editor's Visibility boxes, for new waypoints; the row under it. */
	private int place(int left, int y, String key, boolean selected, Consumer<Boolean> set) {
		Checkbox box = SettingsScreen.checkbox(Component.translatable("diorama.waypoint." + key), this.font, selected, WIDTH, set);
		box.setPosition(left, y);
		box.setTooltip(Tooltip.create(Component.translatable("diorama.waypoint." + key + ".tooltip")));
		this.addRenderableWidget(box);
		return y + ROW;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// A 1.20.1 screen draws its own background.
		//? if <1.21 {
		/*this.renderBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
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
