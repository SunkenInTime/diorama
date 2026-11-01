package dev.diorama.waypoint;

import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.render.Circle;
import dev.diorama.render.Pins;
import dev.diorama.render.MapViewport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Waypoints on the minimap and fullscreen map: a pin in the waypoint's colour standing with its
 * point on the spot. Waypoints outside the view sit on its edge as a badge with a caret pointing
 * toward them, so the minimap always shows which way home is.
 */
public final class WaypointOverlay {
	/**
	 * A waypoint placed on screen: the spot it marks, or for {@code clamped} ones pulled in to the
	 * view's edge, the badge's centre. {@code size} is the head's diameter before {@code scale}
	 * ({@link Pins#scale}), which the pin is drawn at.
	 */
	public record Pin(Waypoint waypoint, float x, float y, float size, float scale, boolean clamped) {
		/** Where the head is centred: above the spot for a standing pin. */
		public float headY() {
			return this.clamped ? this.y : this.y - Pins.headAbove(this.waypoint, (int) this.size) * this.scale;
		}
	}

	private WaypointOverlay() {
	}

	public static List<Pin> layout(MapViewport.View view, List<Waypoint> waypoints, boolean fullscreen) {
		List<Pin> pins = new ArrayList<>();
		int size = fullscreen ? Pins.LARGE : Pins.SMALL;
		Settings settings = Settings.get();
		float scale = Pins.scale(fullscreen ? settings.waypointMapSize : settings.waypointMinimapSize);
		float cx = view.x() + view.width() / 2.0F;
		float cy = view.y() + view.height() / 2.0F;
		Circle circle = view.round() ? Circle.in(view) : null;
		for (Waypoint waypoint : waypoints) {
			if (!WaypointStore.isShown(waypoint)) {
				continue;
			}
			// Badges with icons are bigger, so they sit further in.
			float inset = Pins.edgeReach(waypoint, size) * scale;
			// At least a pixel, so a view smaller than a badge still gets finite positions.
			float halfW = Math.max(1, view.width() / 2.0F - inset);
			float halfH = Math.max(1, view.height() / 2.0F - inset);
			float radius = circle == null ? 0 : Math.max(1, circle.r - inset);
			float x = view.screenX(waypoint.x + 0.5);
			float y = view.screenY(Projection.v(waypoint.y, waypoint.z + 0.5));
			float dx = x - cx;
			float dy = y - cy;
			if (circle != null) {
				if (Pins.fitsStanding(waypoint, size, scale, x, y, circle)) {
					pins.add(new Pin(waypoint, x, y, size, scale, false));
				} else {
					// On a round view the badge goes round the rim.
					float reach = Math.max(1, (float) Math.hypot(dx, dy) / radius);
					pins.add(new Pin(waypoint, cx + dx / reach, cy + dy / reach, size, scale, true));
				}
			} else if (Pins.fitsStanding(waypoint, size, scale, x, y, view.x(), view.y(), view.x() + view.width(), view.y() + view.height())) {
				pins.add(new Pin(waypoint, x, y, size, scale, false));
			} else {
				// Too near the edge for the whole pin to show: a badge pointing the way, pulled in to
				// the view's edge when the spot is outside it.
				float reach = Math.max(1, Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH));
				pins.add(new Pin(waypoint, cx + dx / reach, cy + dy / reach, size, scale, true));
			}
		}
		return pins;
	}

	public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, List<Pin> pins, boolean fullscreen, @Nullable Pin hovered) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		float cx = view.x() + view.width() / 2.0F;
		float cy = view.y() + view.height() / 2.0F;
		for (Pin pin : pins) {
			Waypoint waypoint = pin.waypoint();
			int s = (int) pin.size();
			if (pin.clamped()) {
				Pins.edge(graphics, waypoint, pin.x(), pin.y(), s, Math.atan2(pin.y() - cy, pin.x() - cx), pin.scale());
			} else {
				Pins.standing(graphics, waypoint, pin.x(), pin.y(), s, pin.scale());
			}
			// Icons say what a place is, so their names wait for the cursor.
			boolean named = pin == hovered || view.zoom() >= 2 && !Pins.showsIcon(waypoint);
			if (fullscreen && !pin.clamped() && named) {
				int w = font.width(waypoint.name);
				graphics.pose().pushMatrix();
				graphics.pose().translate(pin.x() - w / 2.0F, pin.y() + Pins.SHADOW * pin.scale());
				graphics.fill(-2, -1, w + 2, 9, 0x90000000);
				graphics.text(font, waypoint.name, 0, 0, 0xFFFFFFFF, false);
				graphics.pose().popMatrix();
			}
		}
		graphics.disableScissor();
	}

	public static @Nullable Pin under(List<Pin> pins, double mouseX, double mouseY) {
		Pin best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Pin pin : pins) {
			// A standing pin answers at its head and at the spot its point touches. A head widened for
			// its initials answers along its middle line.
			double along = Math.max(0, Math.abs(pin.x() - mouseX) - Pins.stretch(pin.waypoint(), (int) pin.size()) * pin.scale() / 2.0);
			double d = Math.hypot(along, pin.headY() - mouseY);
			if (!pin.clamped()) {
				d = Math.min(d, Math.hypot(pin.x() - mouseX, pin.y() - mouseY));
			}
			if (d <= Pins.reach(pin.waypoint(), (int) pin.size()) * pin.scale() && d < bestDistance) {
				best = pin;
				bestDistance = d;
			}
		}
		return best;
	}

	/** "Home · 120 m · 4 below" */
	public static Component describe(Waypoint waypoint, Entity viewer) {
		int distance = (int) Math.round(waypoint.distanceTo(viewer.getX(), viewer.getY(), viewer.getZ()));
		int dy = (int) Math.round(waypoint.y - viewer.getY());
		String height = dy > 2 ? " · " + dy + " above" : dy < -2 ? " · " + (-dy) + " below" : "";
		return Component.literal(waypoint.name + " · " + distance + " m" + height);
	}
}
