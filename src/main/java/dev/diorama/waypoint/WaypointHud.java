package dev.diorama.waypoint;

import dev.diorama.MapSession;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.render.Pins;
import dev.diorama.render.TextSize;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
//? if >=1.21 {
import net.minecraft.client.DeltaTracker;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Waypoints in the world: each one is projected through the game camera and drawn on the HUD as
 * a pin standing on its spot, with the distance under it, so it shows through terrain at any range. The name appears when it
 * is near the crosshair or close by, which keeps a screen full of waypoints readable. The World size
 * setting sizes the pin and Text size the text under it. Under the pins go the beams ({@link WaypointBeams})
 * of the waypoints the beam setting picks.
 */
public final class WaypointHud implements HudElement {
	/** Within this many GUI pixels of the crosshair a waypoint shows its name. */
	private static final float NAME_RADIUS = 40;
	/** Within this many blocks a waypoint always shows its name. */
	private static final double NAME_DISTANCE = 48;

	private final WaypointBeams beams = new WaypointBeams();

	@Override
	//? if >=1.21 {
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
	//?} else {
	/*public void extractRenderState(GuiGraphicsExtractor graphics, float partialTick) {
	*///?}
		Minecraft mc = Minecraft.getInstance();
		Entity viewer = MapSession.viewer();
		WaypointStore store = WaypointStore.get();
		//? if >=26.2 {
		if (viewer == null || mc.level == null || store == null || !Settings.get().waypointsInWorld || mc.gui.hud.isHidden() || !ServerRules.minimapAllowed()) {
		//?} else {
		/*if (viewer == null || mc.level == null || store == null || !Settings.get().waypointsInWorld || mc.options.hideGui || !ServerRules.minimapAllowed()) {
		*///?}
			return;
		}
		Vec3 eye = WorldProjection.camera();
		if (eye == null) {
			return;
		}
		float width = graphics.guiWidth();
		float height = graphics.guiHeight();
		Font font = mc.font;
		String dimension = mc.level.dimension().identifier().toString();
		// Cobblemon Raid Dens Icons patches this method where it calls WaypointStore.in(String), to
		// leave out its far raid markers, and the game doesn't start when that call is gone
		// (0.10.0, #387). So the dimension's own waypoints are asked for here, with that call.
		List<Waypoint> waypoints = store.shownIn(dimension, store.in(dimension));
		float scale = Pins.scale(Settings.get().waypointWorldSize);
		float textScale = TextSize.scale(Settings.get().waypointWorldTextSize);
		// A pin stands above its spot, so a big one is still on screen with its spot well off it.
		float margin = 28 * Math.max(1, scale);
		Settings.Beams beamsShown = Settings.get().waypointBeams;
		if (beamsShown != Settings.Beams.OFF) {
			// Under every pin and label, so a beam never covers one. A pin shows through the land; its
			// beam only where nothing stands in front of it.
			float z = 0;
			//? if <1.21.6 {
			/*// Drawn straight away here, at the depth the pins are given.
			z = graphics.pose().last().pose().m32();
			*///?}
			this.beams.begin(mc.level, eye, width, height, z);
			try {
				for (Waypoint waypoint : waypoints) {
					if (WaypointBeams.has(waypoint, beamsShown) && WaypointStore.isShown(waypoint) && WaypointStore.isNear(waypoint, viewer)) {
						this.beams.add(waypoint);
					}
				}
				this.beams.draw(graphics);
			} finally {
				this.beams.end();
			}
		}
		for (Waypoint waypoint : waypoints) {
			if (!WaypointStore.isShown(waypoint) || !WaypointStore.isNear(waypoint, viewer)) {
				continue;
			}
			double dx = waypoint.x + 0.5 - eye.x;
			double dy = waypoint.y + 1.0 - eye.y;
			double dz = waypoint.z + 0.5 - eye.z;
			float[] screen = WorldProjection.project(waypoint.x + 0.5, waypoint.y + 1.0, waypoint.z + 0.5, width, height);
			if (screen == null) {
				continue;
			}
			float sx = screen[0];
			float sy = screen[1];
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			Settings.WaypointLabels labels = Settings.get().waypointLabels;
			String text = null;
			if (labels != Settings.WaypointLabels.NONE) {
				text = Math.round(distance) + " m";
				boolean nearCrosshair = Math.hypot(sx - width / 2, sy - height / 2) < NAME_RADIUS;
				if (labels == Settings.WaypointLabels.ALWAYS || labels == Settings.WaypointLabels.AUTO && (nearCrosshair || distance < NAME_DISTANCE)) {
					text = waypoint.shownName() + " · " + text;
				}
			}
			int w = text == null ? 0 : font.width(text);
			// It stays until the pin and the text under it have both left the screen: big text is
			// wider than the pin, and hangs further below the spot.
			float side = Math.max(margin, (w / 2.0F + 2) * textScale);
			float below = Math.max(margin, Pins.SHADOW * scale + 10 * textScale);
			if (sx < -side || sy < -below || sx > width + side || sy > height + margin) {
				continue;
			}
			// Keep the world anchor fractional: snapping it to GUI pixels makes markers jump
			// by several framebuffer pixels at larger GUI scales. Move the entire marker together.
			graphics.pose().pushMatrix();
			graphics.pose().translate(sx, sy);
			try {
				Pins.standing(graphics, waypoint, 0, 0, Pins.LARGE, scale);
				if (text == null) {
					continue;
				}
				graphics.pose().translate(-w * textScale / 2.0F, textScale + Pins.SHADOW * scale);
				graphics.pose().scale(textScale);
				graphics.fill(-2, -1, w + 2, 9, 0x80000000);
				graphics.text(font, text, 0, 0, 0xFFFFFFFF, false);
			} finally {
				graphics.pose().popMatrix();
			}
		}
	}
}
