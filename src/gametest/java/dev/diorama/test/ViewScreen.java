package dev.diorama.test;

import dev.diorama.api.MapView;
import dev.diorama.api.ViewPlace;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * An addon's own screen, as the {@code addon-api} view checks need one: a dark backdrop, then each
 * of its draws, a view of Diorama's map at a place, in order, each frame, through the API alone
 * ({@link MapView#render} takes the graphics object as an Object). What each draw returned on the
 * last frame is kept, and so is anything one threw.
 */
final class ViewScreen extends Screen {
	/** One view drawn at one place, each frame. */
	record Draw(MapView view, ViewPlace place) {
	}

	/** What a check does around the draws each frame, with the frame's graphics: the draws run when it runs {@code draws}. */
	interface Around {
		void draw(GuiGraphicsExtractor graphics, float partialTick, Runnable draws);
	}

	final List<Draw> draws = new ArrayList<>();
	/** Whether each view gets a light frame round it, as a screen of an addon's might draw. */
	boolean framed;
	volatile Around around;
	/** What each draw returned, the last frame. */
	volatile boolean[] returned = new boolean[0];
	volatile int frames;
	volatile RuntimeException thrown;
	/** The graphics object of the last frame, kept as an addon that misuses it might. */
	volatile Object lastGraphics;

	ViewScreen() {
		super(Component.literal("Addon view test"));
	}

	ViewScreen(List<Draw> draws) {
		this();
		this.draws.addAll(draws);
	}

	//? if >=1.21 {
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}
	//?}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.lastGraphics = graphics;
		graphics.fill(0, 0, this.width, this.height, 0xFF101418);
		if (this.framed) {
			for (Draw draw : this.draws) {
				ViewPlace p = draw.place();
				graphics.fill(p.x() - 2, p.y() - 2, p.x() + p.width() + 2, p.y() + p.height() + 2, 0xFFC8D0DA);
			}
		}
		boolean[] result = new boolean[this.draws.size()];
		Runnable drawAll = () -> {
			for (int i = 0; i < result.length; i++) {
				Draw draw = this.draws.get(i);
				Object g = graphics;
				result[i] = draw.view().render(g, draw.place(), a);
			}
		};
		try {
			Around wrap = this.around;
			if (wrap == null) {
				drawAll.run();
			} else {
				wrap.draw(graphics, a, drawAll);
			}
		} catch (RuntimeException e) {
			this.thrown = e;
		}
		this.returned = result;
		this.frames++;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
