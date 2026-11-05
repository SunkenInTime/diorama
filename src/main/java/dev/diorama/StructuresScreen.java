package dev.diorama;

import dev.diorama.structure.StructureIcons;
import dev.diorama.structure.StructureKinds;
import dev.diorama.structure.StructureMap;
import dev.diorama.structure.StructureOverlay;
import dev.diorama.structure.StructureSeed;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The small window behind the map's Structures button: whether structures show on the map at all,
 * the world's seed to find them from, and which kinds of structure to show. Changes apply as they
 * are made; the map behind keeps working.
 */
public final class StructuresScreen extends Screen {
	private static final int WIDTH = 204;
	private static final int ROW = 22;
	private static final int KIND_WIDTH = 140;
	private static final int LABEL = 0xFFA0A0A0;
	private static final int MAX_SEED_LENGTH = 64;

	private final Screen parent;
	/** The dimension of the map being looked at: its structures are what's found. */
	private final String dimension;
	private Button master;
	private EditBox seed;
	private final List<Button> kindButtons = new ArrayList<>();
	private final List<StructureKinds.Kind> kinds = new ArrayList<>();

	public StructuresScreen(Screen parent, String dimension) {
		super(Component.translatable("diorama.structures.title"));
		this.parent = parent;
		this.dimension = dimension;
	}

	private int top() {
		return 24;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDTH / 2;
		int top = this.top();
		this.kindButtons.clear();
		this.kinds.clear();
		this.master = this.addRenderableWidget(Button.builder(this.masterMessage(), b -> {
			Settings settings = Settings.get();
			settings.structuresOnMap = !settings.structuresOnMap;
			settings.save();
			b.setMessage(this.masterMessage());
			this.updateKinds();
		}).bounds(left, top, WIDTH, 20).build());
		String typed = this.seed != null ? this.seed.getValue() : StructureSeed.entered();
		EditBox box = new EditBox(this.font, left, top + 38, WIDTH, 20, Component.translatable("diorama.structures.seed_label"));
		box.setMaxLength(MAX_SEED_LENGTH);
		box.setValue(typed);
		box.setHint(Component.translatable("diorama.structures.seed_hint"));
		box.setResponder(StructureSeed::remember);
		this.seed = this.addRenderableWidget(box);

		// The kinds in columns that fit the window: as many rows as there is room for.
		this.kinds.addAll(StructureKinds.all());
		int listTop = top + 84;
		int room = this.height - 32 - listTop;
		int rows = Math.max(3, room / ROW);
		int columns = (this.kinds.size() + rows - 1) / rows;
		int buttonWidth = Math.min(KIND_WIDTH, (this.width - 8) / columns - 2);
		int gridLeft = (this.width - columns * (buttonWidth + 2) + 2) / 2;
		for (int i = 0; i < this.kinds.size(); i++) {
			StructureKinds.Kind kind = this.kinds.get(i);
			Button button = this.addRenderableWidget(Button.builder(this.kindMessage(kind), b -> {
				StructureOverlay.setHidden(kind, !StructureOverlay.hidden(kind));
				b.setMessage(this.kindMessage(kind));
			}).bounds(gridLeft + i / rows * (buttonWidth + 2), listTop + i % rows * ROW, buttonWidth, 20).build());
			this.kindButtons.add(button);
		}

		int bottom = this.height - 26;
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.structures.show_all"), b -> this.setAll(false))
			.bounds(this.width / 2 - 154, bottom, 98, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
			.bounds(this.width / 2 - 50, bottom, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.structures.hide_all"), b -> this.setAll(true))
			.bounds(this.width / 2 + 56, bottom, 98, 20).build());
		this.setInitialFocus(box);
		this.updateKinds();
	}

	private Component masterMessage() {
		return CommonComponents.optionStatus(Component.translatable("diorama.structures.show"), Settings.get().structuresOnMap);
	}

	private Component kindMessage(StructureKinds.Kind kind) {
		// Room on the left for the kind's icon.
		return Component.literal("   ").append(CommonComponents.optionStatus(kind.name(), !StructureOverlay.hidden(kind)));
	}

	private void setAll(boolean hidden) {
		for (StructureKinds.Kind kind : this.kinds) {
			List<String> list = Settings.get().hiddenStructures;
			list.remove(kind.id());
			if (hidden) {
				list.add(kind.id());
			}
		}
		Settings.get().save();
		for (int i = 0; i < this.kindButtons.size(); i++) {
			this.kindButtons.get(i).setMessage(this.kindMessage(this.kinds.get(i)));
		}
	}

	/** The kinds can't be chosen while structures are off. */
	private void updateKinds() {
		for (Button button : this.kindButtons) {
			button.active = Settings.get().structuresOnMap;
		}
	}

	/** One line on how the search is going, or why there is none. */
	private Component status() {
		StructureMap map = StructureMap.get();
		if (StructureMap.dimensionKey(this.dimension) == null) {
			return Component.translatable("diorama.structures.unavailable");
		}
		Long seed = StructureSeed.current();
		if (seed == null) {
			return Component.translatable("diorama.structures.need_seed");
		}
		String failure = map.failure();
		if (failure != null) {
			return Component.translatable("diorama.structures.failed", failure);
		}
		if (!Settings.get().structuresOnMap) {
			return Component.translatable("diorama.structures.off");
		}
		Component source = Component.translatable(StructureSeed.isWorlds() ? "diorama.structures.seed_world" : "diorama.structures.seed_entered", String.valueOf(seed));
		if (map.pendingTiles() > 0) {
			return Component.translatable("diorama.structures.searching", map.found(), StructureMap.RANGE, source);
		}
		return Component.translatable("diorama.structures.found", map.found(), StructureMap.RANGE, source);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int left = this.width / 2 - WIDTH / 2;
		int top = this.top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 14, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("diorama.structures.seed_label"), left, top + 27, LABEL, true);
		graphics.centeredText(this.font, this.status(), this.width / 2, top + 62, LABEL);
		for (int i = 0; i < this.kindButtons.size(); i++) {
			Button button = this.kindButtons.get(i);
			StructureIcons.draw(graphics, this.kinds.get(i), button.getX() + 10, button.getY() + 10, 16);
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
