package dev.diorama.structure;

import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.render.Circle;
import dev.diorama.render.MapViewport;
import dev.diorama.seed.StructureLocator.Found;
import dev.diorama.structure.StructureKinds.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The structures on the fullscreen map: each kind's picture ({@link StructureIcons}), centred on
 * the spot, with no background or frame. No terrain and no outlines of the structure itself. Where icons crowd, the
 * ones that matter most are kept ({@link StructureKinds#priority}) and the rest left out until zoomed in.
 */
public final class StructureOverlay {
	/** An icon's height, in GUI pixels. */
	public static final int SIZE = 14;
	/** Icons closer than this on screen crowd each other: only one of them is drawn. */
	private static final int CELL = 15;
	/** The minimap's icons: the same pictures, half the size. */
	private static final int COMPACT_SIZE = 8;
	private static final int COMPACT_CELL = 9;
	/** The icon under the cursor is drawn this much larger. */
	private static final float HOVER_SCALE = 1.3F;
	/** How far from an icon's centre the cursor still picks it. */
	private static final double REACH = SIZE / 2.0 + 2;
	private static final int MAX_ICONS = 2500;

	/** A structure placed on screen. */
	public record Icon(Found found, Kind kind, float x, float y) {
	}

	private StructureOverlay() {
	}

	public static boolean hidden(Kind kind) {
		return Settings.get().hiddenStructures.contains(kind.id());
	}

	public static void setHidden(Kind kind, boolean hidden) {
		List<String> list = Settings.get().hiddenStructures;
		list.remove(kind.id());
		if (hidden) {
			list.add(kind.id());
		}
		Settings.get().save();
	}

	/** The icons for what's found in a view, crowded ones left out. */
	public static List<Icon> layout(MapViewport.View view) {
		return layout(view, false);
	}

	/**
	 * The icons for what's found in a view, crowded ones left out. {@code compact} is for the
	 * minimap: closer together, and on a round one only those inside the circle.
	 */
	public static List<Icon> layout(MapViewport.View view, boolean compact) {
		Set<String> hidden = new HashSet<>(Settings.get().hiddenStructures);
		List<Icon> icons = new ArrayList<>();
		int margin = compact ? COMPACT_SIZE : SIZE;
		int cell = compact ? COMPACT_CELL : CELL;
		Circle circle = compact && view.round() ? Circle.in(view) : null;
		StructureMap.get().forEach(view, found -> {
			Kind kind = StructureKinds.of(found.structure());
			if (hidden.contains(kind.id())) {
				return;
			}
			float x = view.screenX(found.x() + 0.5);
			float y = view.screenY(Projection.v(found.y(), found.z() + 0.5));
			if (circle != null ? circle.contains(x, y) : x >= view.x() - margin && x <= view.x() + view.width() + margin && y >= view.y() - margin && y <= view.y() + view.height() + margin) {
				icons.add(new Icon(found, kind, x, y));
			}
		});
		icons.sort(Comparator.comparingInt((Icon icon) -> StructureKinds.priority(icon.kind())).thenComparingInt(icon -> icon.found().x()).thenComparingInt(icon -> icon.found().z()));
		List<Icon> kept = new ArrayList<>();
		Set<Long> taken = new HashSet<>();
		for (Icon icon : icons) {
			long spot = (long) (int) Math.floor(icon.x() / cell) << 32 | (int) Math.floor(icon.y() / cell) & 0xFFFFFFFFL;
			if (taken.add(spot)) {
				kept.add(icon);
				if (kept.size() >= MAX_ICONS) {
					break;
				}
			}
		}
		return kept;
	}

	/** The icons on the minimap: the same pictures, smaller, none picked out. */
	public static void drawCompact(GuiGraphicsExtractor graphics, MapViewport.View view, List<Icon> icons) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		for (Icon icon : icons) {
			StructureIcons.draw(graphics, icon.kind(), icon.x(), icon.y(), COMPACT_SIZE);
		}
		graphics.disableScissor();
	}

	public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, List<Icon> icons, @Nullable Icon hovered) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		for (Icon icon : icons) {
			if (icon != hovered) {
				StructureIcons.draw(graphics, icon.kind(), icon.x(), icon.y(), SIZE);
			}
		}
		// Drawn last and larger, so it is picked out and a neighbour doesn't cover it.
		if (hovered != null) {
			StructureIcons.draw(graphics, hovered.kind(), hovered.x(), hovered.y(), SIZE * HOVER_SCALE);
		}
		graphics.disableScissor();
	}

	/** The icon under a point, or null. */
	public static @Nullable Icon under(List<Icon> icons, double mouseX, double mouseY) {
		Icon best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Icon icon : icons) {
			double d = Math.max(Math.abs(icon.x() - mouseX), Math.abs(icon.y() - mouseY));
			if (d <= REACH && d < bestDistance) {
				best = icon;
				bestDistance = d;
			}
		}
		return best;
	}

	/** "Village" over "-382, 825", its place in the world. */
	public static List<Component> describe(Icon icon) {
		return List.of(icon.kind().name(), Component.literal(icon.found().x() + ", " + icon.found().z()));
	}
}
