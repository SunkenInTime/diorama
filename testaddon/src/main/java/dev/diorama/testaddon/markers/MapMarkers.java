package dev.diorama.testaddon.markers;

import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.api.MapLine;
import dev.diorama.api.Marker;
import dev.diorama.api.ResourceId;
import dev.diorama.api.World;
import dev.diorama.api.WorldId;
import java.util.Objects;

/**
 * What an addon draws on Diorama's maps, in three layers around a spot, using nothing but the
 * API: as a mod that tracks carts, marks places and draws roads would.
 * <ul>
 *   <li>{@code carts}: a few hundred markers going round in rings, the layer's whole content
 *       replaced every tick ({@link #tick}), as a feed of trains or players would.</li>
 *   <li>{@code places}: icons with labels that never move, handed over once: Diorama's own
 *       {@code diorama:home}, {@code diorama:mine} and {@code diorama:villagers}, one from this
 *       jar ({@code diorama_test_addon:raid}, in {@code assets/diorama_test_addon/textures/waypoint_icons/}),
 *       one no resource pack has (shown as a dot), and a dot with a label.</li>
 *   <li>{@code roads}: three lines, one of them climbing a hill.</li>
 * </ul>
 * A real addon would call {@link #tick} from its own client tick event; the game tests call it
 * from theirs.
 *
 * <p>Diorama empties every layer when the world changes, so {@link #tick} also watches
 * {@link DioramaApi#world} and hands the places and roads over again whenever the world's ID
 * changes. (A real addon would show each world's own places; these are the same ones around the
 * same spot in any world.) The carts are handed over every tick anyway.
 */
public final class MapMarkers implements AutoCloseable {
	/** A colour no block uses, so the tests can find the carts on screen. */
	public static final int CART_RGB = 0xFF00FF;
	public static final ResourceId OWN_ICON = ResourceId.of("diorama_test_addon:raid");
	public static final ResourceId MISSING_ICON = ResourceId.of("diorama_test_addon:no_such_icon");

	private final DioramaApi api;
	private final DimensionId dimension;
	private final double x;
	private final double y;
	private final double z;
	private final int carts;
	private final MapLayer cartLayer;
	private final MapLayer placeLayer;
	private final MapLayer roadLayer;
	/** The world the places and roads were last handed over in, or null for none. */
	private WorldId shownFor;
	private long ticks;

	/**
	 * Opens the three layers around (x, y, z) and shows the places and roads.
	 *
	 * @param carts how many carts go round, up to {@link LayerContent#MAX_MARKERS}
	 */
	public MapMarkers(DioramaApi api, DimensionId dimension, double x, double y, double z, int carts) {
		this.api = api;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.carts = carts;
		this.roadLayer = api.openLayer("roads");
		this.cartLayer = api.openLayer("carts");
		this.placeLayer = api.openLayer("places");
		this.tick();
	}

	/** Hands the places and roads over again if the world changed since they last were: Diorama emptied them. */
	private void followWorld() {
		WorldId now = this.api.world().map(World::worldId).orElse(null);
		if (now != null && !Objects.equals(now, this.shownFor)) {
			this.shownFor = now;
			this.placeLayer.replace(this.placeContent());
			this.roadLayer.replace(this.roadContent());
		}
	}

	private LayerContent placeContent() {
		return LayerContent.builder()
			.add(this.place(-24, -18, ResourceId.of("diorama:home"), "Inn"))
			.add(this.place(20, -22, ResourceId.of("diorama:mine"), "Old mine"))
			.add(this.place(-30, 16, ResourceId.of("diorama:villagers"), "Market"))
			.add(this.place(26, 18, OWN_ICON, "Raid den"))
			.add(this.place(0, -34, MISSING_ICON, "No icon"))
			.add(Marker.builder(this.dimension, this.x + 34, this.y, this.z - 4).rgb(0x46A758).size(8).label("Well").build())
			.build();
	}

	private Marker place(double dx, double dz, ResourceId icon, String label) {
		return Marker.builder(this.dimension, this.x + dx, this.y, this.z + dz).icon(icon).rgb(0x3E63DD).label(label).build();
	}

	private LayerContent roadContent() {
		return LayerContent.builder()
			.add(MapLine.builder(this.dimension).point(this.x - 40, this.y, this.z).point(this.x + 40, this.y, this.z).rgb(0xC8A15A).build())
			.add(MapLine.builder(this.dimension).point(this.x, this.y, this.z - 40).point(this.x, this.y, this.z + 40).rgb(0xC8A15A).width(3).build())
			// Up a hill and over: each point at its own height.
			.add(MapLine.builder(this.dimension)
				.point(this.x - 30, this.y, this.z + 30).point(this.x - 10, this.y + 12, this.z + 30)
				.point(this.x + 10, this.y + 12, this.z + 30).point(this.x + 30, this.y, this.z + 30)
				.rgb(0x2F80ED).width(2).build())
			.build();
	}

	/** Moves the carts on one step and hands the layer all of them again, and follows the world. */
	public void tick() {
		this.followWorld();
		this.ticks++;
		LayerContent.Builder content = LayerContent.builder();
		for (int i = 0; i < this.carts; i++) {
			int ring = i % 6;
			double radius = 8 + ring * 5;
			double angle = (i * 2 * Math.PI / this.carts) * 6 + this.ticks * 0.02 * (ring % 2 == 0 ? 1 : -1);
			content.add(Marker.builder(this.dimension, this.x + Math.cos(angle) * radius, this.y, this.z + Math.sin(angle) * radius).rgb(CART_RGB).size(4).build());
		}
		this.cartLayer.replace(content.build());
	}

	public MapLayer carts() {
		return this.cartLayer;
	}

	public MapLayer places() {
		return this.placeLayer;
	}

	public MapLayer roads() {
		return this.roadLayer;
	}

	@Override
	public void close() {
		this.cartLayer.close();
		this.placeLayer.close();
		this.roadLayer.close();
	}
}
