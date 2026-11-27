package dev.diorama.testaddon.broken;

import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.api.MapLine;
import dev.diorama.api.MapView;
import dev.diorama.api.Marker;
import java.util.EnumSet;

/**
 * Hides the minimap, opens a layer of markers and lines across the Overworld's spawn and a view of
 * the map, then fails: Diorama should switch it off, show the minimap again, take its layer off the
 * maps and close its view.
 */
@DioramaEntrypoint
public final class BrokenAddon implements DioramaAddon {
	private static DioramaApi api;
	private static Handle held;
	private static MapLayer layer;
	private static MapView view;

	@Override
	public void onReady(DioramaApi api) {
		BrokenAddon.api = api;
		held = api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
		layer = api.openLayer("everywhere");
		view = api.openView(EnumSet.allOf(MapView.Part.class));
		LayerContent.Builder content = LayerContent.builder();
		for (int x = -256; x <= 256; x += 16) {
			for (int z = -256; z <= 256; z += 16) {
				content.add(Marker.builder(DimensionId.OVERWORLD, x, 70, z).rgb(0x00FF00).build());
			}
		}
		content.add(MapLine.builder(DimensionId.OVERWORLD).point(-256, 70, -256).point(256, 70, 256).rgb(0x00FF00).width(8).build());
		content.add(MapLine.builder(DimensionId.OVERWORLD).point(-256, 70, 256).point(256, 70, -256).rgb(0x00FF00).width(8).build());
		layer.replace(content.build());
		throw new IllegalStateException("Diorama test addon: broken on purpose");
	}

	/** The API Diorama handed this addon before it failed. */
	public static DioramaApi api() {
		return api;
	}

	/** The handle it took before it failed. */
	public static Handle held() {
		return held;
	}

	/** The layer it opened before it failed. */
	public static MapLayer layer() {
		return layer;
	}

	/** The view it opened before it failed. */
	public static MapView view() {
		return view;
	}
}
