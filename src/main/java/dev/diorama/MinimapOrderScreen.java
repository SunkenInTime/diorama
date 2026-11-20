package dev.diorama;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class MinimapOrderScreen extends Screen {
	private static final int WIDTH = 240;
	private static final int ROW = 24;
	private static final int TOP = 54;
	private final Screen parent;

	public MinimapOrderScreen(Screen parent) {
		super(Component.translatable("diorama.info_order.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		List<String> order = Settings.get().minimapInfoOrder;
		if (order == null || order.isEmpty()) {
			order = new java.util.ArrayList<>(List.of("C", "B", "L", "T"));
			Settings.get().minimapInfoOrder = order;
		}
		int left = this.width / 2 - WIDTH / 2;
		for (int i = 0; i < order.size(); i++) {
			final int idx = i;
			String code = order.get(i);
			int y = TOP + i * ROW;
			Button labelBtn = Button.builder(itemName(code), b -> {}).bounds(left, y, WIDTH - 46, 20).build();
			labelBtn.active = false;
			this.addRenderableWidget(labelBtn);

			Button up = Button.builder(Component.literal("↑"), b -> this.move(idx, -1)).bounds(left + WIDTH - 42, y, 20, 20).build();
			up.active = i > 0;
			this.addRenderableWidget(up);

			Button down = Button.builder(Component.literal("↓"), b -> this.move(idx, 1)).bounds(left + WIDTH - 20, y, 20, 20).build();
			down.active = i < order.size() - 1;
			this.addRenderableWidget(down);
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
			.bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
	}

	private void move(int index, int delta) {
		List<String> order = Settings.get().minimapInfoOrder;
		String item = order.remove(index);
		order.add(index + delta, item);
		Settings.get().save();
		this.rebuildWidgets();
	}

	private static Component itemName(String code) {
		return Component.translatable(switch (code) {
			case "C" -> "diorama.settings.coordinates";
			case "B" -> "diorama.settings.biome";
			case "L" -> "diorama.info_order.claim";
			default -> "diorama.settings.game_time";
		});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		//? if <1.21 {
		/*this.renderBackground(graphics);
		*///?}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
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
