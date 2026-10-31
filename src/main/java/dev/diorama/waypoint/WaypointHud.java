package dev.diorama.waypoint;

import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.render.Pins;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
//? if >=1.21 {
import net.minecraft.client.DeltaTracker;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Waypoints in the world: each one is projected through the game camera and drawn on the HUD as
 * a pin standing on its spot, with the distance under it, so it shows through terrain at any range. The name appears when it
 * is near the crosshair or close by, which keeps a screen full of waypoints readable.
 */
public final class WaypointHud implements HudElement {
	/** Within this many GUI pixels of the crosshair a waypoint shows its name. */
	private static final float NAME_RADIUS = 40;
	/** Within this many blocks a waypoint always shows its name. */
	private static final double NAME_DISTANCE = 48;

	@Override
	//? if >=1.21 {
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
	//?} else {
	/*public void extractRenderState(GuiGraphicsExtractor graphics, float partialTick) {
	*///?}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		WaypointStore store = WaypointStore.get();
		//? if >=26.2 {
		if (player == null || mc.level == null || store == null || !Settings.get().waypointsInWorld || mc.gui.hud.isHidden() || !ServerRules.minimapAllowed()) {
		//?} else {
		/*if (player == null || mc.level == null || store == null || !Settings.get().waypointsInWorld || mc.options.hideGui || !ServerRules.minimapAllowed()) {
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
		List<Waypoint> waypoints = store.in(mc.level.dimension().identifier().toString());
		float scale = Pins.scale(Settings.get().waypointWorldSize);
		// A pin stands above its spot, so a big one is still on screen with its spot well off it.
		float margin = 28 * Math.max(1, scale);
		for (Waypoint waypoint : waypoints) {
			if (!WaypointStore.isShown(waypoint) || !WaypointStore.isNear(waypoint, player)) {
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
			if (sx < -margin || sy < -margin || sx > width + margin || sy > height + margin) {
				continue;
			}
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			// Keep the world anchor fractional: snapping it to GUI pixels makes markers jump
			// by several framebuffer pixels at larger GUI scales. Move the entire marker together.
			graphics.pose().pushMatrix();
			graphics.pose().translate(sx, sy);
			try {
				Pins.standing(graphics, waypoint, 0, 0, Pins.LARGE, scale);
				Settings.WaypointLabels labels = Settings.get().waypointLabels;
				if (labels == Settings.WaypointLabels.NONE) {
					continue;
				}
				String text = Math.round(distance) + " m";
				boolean nearCrosshair = Math.hypot(sx - width / 2, sy - height / 2) < NAME_RADIUS;
				if (labels == Settings.WaypointLabels.ALWAYS || labels == Settings.WaypointLabels.AUTO && (nearCrosshair || distance < NAME_DISTANCE)) {
					text = waypoint.name + " · " + text;
				}
				int w = font.width(text);
				graphics.pose().translate(-w / 2.0F, 1 + Pins.SHADOW * scale);
				graphics.fill(-2, -1, w + 2, 9, 0x80000000);
				graphics.text(font, text, 0, 0, 0xFFFFFFFF, false);
			} finally {
				graphics.pose().popMatrix();
			}
		}
	}
}
