package dev.diorama;

import dev.diorama.bake.TileKey;
import dev.diorama.compat.Cobblemon;
import dev.diorama.render.Circle;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.MinimapFrames;
import dev.diorama.drawing.DrawingOverlay;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.render.Markers;
import dev.diorama.render.Radar;
import dev.diorama.render.TextSize;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
//? if >=1.21 {
import net.minecraft.client.DeltaTracker;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The minimap, centred on the player, square or round, where the settings put it: some way in from
 * the edges of one corner of the screen, or midway across the top or bottom (see
 * {@link MinimapPositionScreen}). Compass letters sit on its edges, and coordinates, the biome and
 * the game time sit under it, on the side towards the screen edge.
 */
public final class MinimapHud implements HudElement {
	/** How far in from the screen edges the minimap stays, and where a corner puts it. */
	public static final int MARGIN = 6;
	/** Half the hotbar's width; hearts and hunger sit in the same columns just above it. */
	private static final int HOTBAR_HALF_WIDTH = 91;
	/** How far up from the screen's bottom the hotbar and the bars over it reach, armour included. */
	private static final int HOTBAR_HEIGHT = 50;
	/** Each status effect icon's width, and the height of a row of them, as the game draws them. */
	private static final int EFFECT_WIDTH = 25;
	private static final int EFFECT_ROW = 26;
	private static final int[] NO_EFFECTS = {0, 0};
	/** Half a boss bar's width and its height, as the game draws them midway across the top. */
	private static final int BOSS_BAR_HALF_WIDTH = 91;
	private static final int BOSS_BAR_HEIGHT = 5;
	private static final int SMALLEST = 64;
	/**
	 * Longest the minimap keeps showing the old slice while the new one bakes. A tile takes about
	 * half a second to bake and a new slice can need four at once, so this leaves room for that.
	 */
	private static final long LAYER_HOLD_MS = 3000;
	private static final long LAYER_FADE_MS = 300;
	private static final int INFO_COLOR = 0xFFFFFFFF;
	/** The game time once it's dark enough for a bed, which can be earlier than the clock says in a storm. */
	private static final int BED_TIME_COLOR = 0xFF9CC4FF;
	/** Whether the game's effect icon drawing ran to the end since the minimap last looked (see EffectIconsMixin). */
	private static boolean effectIconsDrawn;
	/** The bottom of the lowest boss bar the game drew since the minimap last looked (see BossBarMixin), or 0 with none. */
	private static int bossBarsBottom;
	/** Where the HUD minimap was last drawn: x, y, size, and its height with its lines. Null when it wasn't. */
	private static int @Nullable [] shown;
	/** A size the position screen keeps the minimap at while it's dragged, or 0. */
	private static int heldSize;

	private final MapViewport viewport = new MapViewport();
	/** The slice being faded out, under the one fading in. */
	private final MapViewport fading = new MapViewport();
	private int fadingLayer;
	private long fadeStart = -1;
	private final DrawingOverlay drawings = new DrawingOverlay();
	private final WorldBorderOverlay worldBorder = new WorldBorderOverlay();
	/** The slice on screen. Moving into another slice keeps this one until the next has baked. */
	private int shownLayer;
	private boolean showing;
	/** When the minimap started holding the old slice, or -1 when it isn't. */
	private long holdStart = -1;

	@Override
	//? if >=1.21 {
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
	//?} else {
	/*public void extractRenderState(GuiGraphicsExtractor graphics, float partialTick) {
	*///?}
		Minecraft mc = Minecraft.getInstance();
		Settings settings = Settings.get();
		Screen screen = mc.gui.screen();
		// The minimap comes after the vanilla HUD on every loader, so this is this frame's icons.
		// Taken whether or not the minimap draws, so a frame with no icons can't reuse an old answer.
		boolean effectIcons = effectIconsDrawn;
		effectIconsDrawn = false;
		int bossBars = bossBarsBottom;
		bossBarsBottom = 0;
		shown = null;
		// The position screen shows it over the world, even switched off, to be put in place.
		boolean placing = screen instanceof MinimapPositionScreen;
		//? if >=26.2 {
		if (!placing && (!settings.minimap || mc.gui.hud.isHidden()
		//?} else {
		/*if (!placing && (!settings.minimap || mc.options.hideGui
		*///?}
			|| screen != null && !(screen instanceof ChatScreen) || Cobblemon.inBattle()
			|| debugScreenOpen(mc)) || !this.canDraw(mc)) {
			// Not drawn under any screen but chat, the way Xaero's Minimap and JourneyMap do it:
			// screens fill the middle and other mods put theirs in the corners too, like the item
			// lists JEI, REI and EMI put beside inventories. The settings screen draws its own copy,
			// sharp over the blur, as a preview. A Cobblemon battle keeps its tiles in both top
			// corners even with its screen closed. The F3 screen fills both top corners with text
			// people open it to read.
			// A hold picks up afresh when it's drawn again, rather than having run out meanwhile.
			this.holdStart = -1;
			return;
		}
		// The enlarge key shows it bigger, growing away from the edges it's placed by.
		int size = DioramaClient.minimapEnlarged() ? Math.round(settings.minimapSize * settings.minimapEnlarged / 100.0F) : settings.minimapSize;
		int[] bounds = this.draw(graphics, partialTick, Anchor.of(settings), effectIcons ? effectCounts(mc.player) : NO_EFFECTS, bossBars, size);
		shown = new int[] {bounds[4], bounds[5], bounds[6], bounds[7]};
	}

	/** Where the HUD minimap was drawn this frame: x, y, size, and its height with its lines. Null when it wasn't. */
	public static int @Nullable [] shown() {
		return shown;
	}

	/** Keeps the minimap at this size, or with 0 lets it fit itself again: the position screen holds it while it's dragged. */
	static void holdSize(int size) {
		heldSize = size;
	}

	/**
	 * Which edges the minimap is placed by and how far in from them, in GUI pixels: from the side
	 * edge to the map, and from the top edge to the map or from the bottom edge to the lines under
	 * it. Centered, it sits midway across, and {@code dx} is kept for when it isn't.
	 */
	record Anchor(boolean right, boolean top, boolean centered, int dx, int dy) {
		static Anchor of(Settings settings) {
			Settings.Corner corner = settings.minimapCorner;
			return new Anchor(corner == Settings.Corner.TOP_RIGHT || corner == Settings.Corner.BOTTOM_RIGHT,
				corner == Settings.Corner.TOP_RIGHT || corner == Settings.Corner.TOP_LEFT,
				settings.minimapCentered, settings.minimapOffsetX, settings.minimapOffsetY);
		}
	}

	/**
	 * The settings screen's live preview: the minimap as the settings make it, on the right of the
	 * screen where the settings leave room. A left corner shows on the right at the same height.
	 * Switched off, it still draws, dimmed, so the other minimap settings can be seen.
	 */
	public void preview(GuiGraphicsExtractor graphics, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (!this.canDraw(mc)) {
			return;
		}
		Settings settings = Settings.get();
		boolean top = settings.minimapCorner == Settings.Corner.TOP_RIGHT || settings.minimapCorner == Settings.Corner.TOP_LEFT;
		int[] bounds = this.draw(graphics, partialTick, new Anchor(true, top, false, MARGIN, MARGIN), NO_EFFECTS, 0, settings.minimapSize);
		if (!settings.minimap) {
			//? if <1.21.6 {
			/*// Text sits a little above 0 and the GUI depth-tests, so lift the dimming over it.
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(0, 0, 1);
			*///?}
			if (settings.minimapShape == Settings.Shape.ROUND) {
				// The disc with its frame, then the lines under it, so the corners beside it stay undimmed.
				MapCanvas.of(graphics).solid(new Circle(bounds[4] + bounds[6] / 2.0F, bounds[5] + bounds[6] / 2.0F, bounds[6] / 2.0F + 2).disc(), 0xB0101418);
				if (bounds[3] > bounds[5] + bounds[6] + 2) {
					graphics.fill(bounds[0], bounds[5] + bounds[6] + 2, bounds[2], bounds[3], 0xB0101418);
				}
			} else {
				graphics.fill(bounds[0], bounds[1], bounds[2], bounds[3], 0xB0101418);
			}
			//? if <1.21.6 {
			/*pose.popPose();
			*///?}
		}
	}

	/** Frees the GPU texture the minimap's fog uses. For a copy that's done drawing, like the settings preview. */
	public void close() {
		this.viewport.close();
		this.fading.close();
	}

	/**
	 * Whether F3 is showing the debug screen. From 1.21.9 single lines can be set to show without
	 * it, like the FPS; those alone don't hide the minimap.
	 */
	private static boolean debugScreenOpen(Minecraft mc) {
		//? if >=1.21.9 {
		return mc.debugEntries.isOverlayVisible();
		//?} else if >=1.21 {
		/*return mc.getDebugOverlay().showDebugScreen();
		*///?} else {
		/*return mc.options.renderDebug;
		*///?}
	}

	private boolean canDraw(Minecraft mc) {
		return MapSession.get() != null && mc.player != null && mc.level != null && ServerRules.minimapAllowed();
	}

	/**
	 * Draws the minimap {@code wanted} pixels wide or less where {@code anchor} puts it, below the
	 * status effect icons it would be over ({@code effects} is how many of each kind, see
	 * {@link #effectCounts}) and below the boss bars, which reach down to {@code bossBars}. Returns what it covers, x0, y0, x1, y1, then the map's own x, y and
	 * size, and its height with the lines under it.
	 */
	private int[] draw(GuiGraphicsExtractor graphics, float partialTick, Anchor anchor, int[] effects, int bossBars, int wanted) {
		Minecraft mc = Minecraft.getInstance();
		MapSession session = MapSession.get();
		Entity viewer = MapSession.viewer();
		Settings settings = Settings.get();
		Vec3 pos = viewer.getPosition(partialTick);
		Font font = mc.font;
		List<InfoLine> info = this.infoLines(settings, viewer, mc);
		float text = TextSize.scale(settings.minimapTextSize);
		// Each line is the game's text and a pixel of its shadow, with a pixel between lines.
		int infoHeight = (int) Math.ceil(info.size() * 10 * text);
		int[] placed = place(anchor, heldSize > 0 ? heldSize : wanted, heldSize <= 0, graphics.guiWidth(), graphics.guiHeight(), infoHeight, effects, bossBars);
		int x = placed[0];
		int y = placed[1];
		int size = placed[2];
		int wantedLayer = settings.autoCaves && ServerRules.caveMapsAllowed() ? session.autoLayer(viewer) : TileKey.SURFACE;
		int layer = this.layerToShow(session.map(), new MapViewport.View(x, y, size, size, Projection.u(pos.x, pos.z), Projection.v(pos.x, pos.y, pos.z), zoom(session.map(), wantedLayer), wantedLayer));
		boolean round = settings.minimapShape == Settings.Shape.ROUND;
		float opacity = settings.opacity();
		float fadeIn = this.fadeStart < 0 ? 1 : (System.currentTimeMillis() - this.fadeStart) / (float) LAYER_FADE_MS;
		if (fadeIn < 1) {
			// So the two layers together stay as opaque as the minimap is set.
			this.fading.opacity(opacity * (1 - fadeIn) / (1 - opacity * fadeIn));
			this.fading.draw(graphics, session.map(), new MapViewport.View(x, y, size, size, Projection.u(pos.x, pos.z), Projection.v(pos.x, pos.y, pos.z), zoom(session.map(), this.fadingLayer), this.fadingLayer, round, true), 0, false, null);
		} else {
			this.fadeStart = -1;
		}
		float shownOpacity = opacity * Math.min(1, fadeIn);
		this.viewport.opacity(shownOpacity);

		MapViewport.View view = new MapViewport.View(x, y, size, size, Projection.u(pos.x, pos.z), Projection.v(pos.x, pos.y, pos.z), zoom(session.map(), layer), layer, round, true);
		this.viewport.draw(graphics, session.map(), view, 0, Settings.get().claims == Settings.ClaimsShown.ALWAYS,
			() -> session.lights().draw(graphics, view, partialTick, shownOpacity, ServerRules.radarAllowed() && settings.radarOnMinimap));
		session.crafts().draw(graphics, view, partialTick, opacity);
		if (settings.worldBorder == Settings.WorldBorderShown.ALWAYS) {
			this.worldBorder.draw(graphics, font, view, mc.level, viewer);
		}
		DrawingStore drawings = DrawingStore.get();
		if (drawings != null && settings.drawings == Settings.DrawingsShown.ALWAYS) {
			this.drawings.draw(graphics, font, view, drawings.in(mc.level.dimension().identifier().toString()), null, null, false);
		}
		// Over the map, since a resource pack's frame can reach in over its edge, and under what's marked on it.
		MinimapFrames.draw(graphics, settings.minimapFrame, round, x, y, size);
		List<Radar.Blip> blips = ServerRules.radarAllowed() && settings.radarOnMinimap ? Radar.collect(view, partialTick, false) : List.of();
		Radar.draw(graphics, view, blips, false, false);
		WaypointStore store = WaypointStore.get();
		if (store != null && settings.waypointsOnMap) {
			List<Waypoint> near = store.shownIn(mc.level.dimension().identifier().toString()).stream().filter(w -> WaypointStore.isNear(w, viewer)).toList();
			WaypointOverlay.draw(graphics, view, WaypointOverlay.layout(view, near, false), false, null);
		}
		Radar.draw(graphics, view, blips, false, true);
		Markers.player(graphics, x + size / 2.0F, y + size / 2.0F, Markers.onMap(viewer.getViewYRot(partialTick)), 5);
		if (settings.compass) {
			this.compass(graphics, font, x, y, size, text);
		}
		float textY = y + size + 4;
		float left = x - 2;
		float textRight = x + size + 2;
		for (InfoLine line : info) {
			float w = font.width(line.text()) * text;
			// A line wider than the minimap grows away from the screen edge the minimap is placed by; it stays on screen while it fits.
			// Centred on a whole GUI pixel, rounded down as the game's own text size always was.
			float tx = anchor.centered() ? x + (float) Math.floor((size - w) / 2) : anchor.right() ? x + size - w : x;
			tx = TextSize.snap(Math.max(1, Math.min(tx, graphics.guiWidth() - 1 - w)));
			graphics.pose().pushMatrix();
			graphics.pose().translate(tx, textY);
			graphics.pose().scale(text);
			graphics.text(font, line.text(), 0, 0, line.color(), true);
			graphics.pose().popMatrix();
			left = Math.min(left, tx - 1);
			textRight = Math.max(textRight, tx + w + 1);
			textY += 10 * text;
		}
		return new int[] {(int) Math.floor(left), y - 2, (int) Math.ceil(textRight), info.isEmpty() ? y + size + 2 : y + size + 4 + infoHeight, x, y, size, size + infoHeight};
	}

	/**
	 * The slice to draw for a view of the slice the player is in: that one once its tiles have
	 * baked, the one already on screen until then, so climbing past a slice edge doesn't flash
	 * the minimap blank.
	 */
	private int layerToShow(MapLevel level, MapViewport.View wanted) {
		long now = System.currentTimeMillis();
		if (!this.showing || wanted.layer() == this.shownLayer) {
			if (!this.showing) {
				this.fadeStart = -1;
			}
			this.showing = true;
			this.shownLayer = wanted.layer();
			this.holdStart = -1;
			return this.shownLayer;
		}
		if (this.holdStart < 0) {
			this.holdStart = now;
		}
		MapViewport.View shown = new MapViewport.View(wanted.x(), wanted.y(), wanted.width(), wanted.height(),
			wanted.centerU(), wanted.centerV(), zoom(level, this.shownLayer), this.shownLayer);
		// The old slice only helps if it has something to show: after a teleport or a new dimension it doesn't.
		boolean oldShows = MapViewport.showsAnything(level, shown);
		if (MapViewport.ready(level, wanted) || now - this.holdStart > LAYER_HOLD_MS || !oldShows) {
			// Between cave slices only: a see-through surface view leaves out its detailed tiles.
			if (oldShows && this.shownLayer != TileKey.SURFACE && wanted.layer() != TileKey.SURFACE) {
				this.fadingLayer = this.shownLayer;
				this.fadeStart = now;
			} else {
				// A straight switch: a cave slice still fading out mustn't show over the surface.
				this.fadeStart = -1;
			}
			this.shownLayer = wanted.layer();
			this.holdStart = -1;
		}
		return this.shownLayer;
	}

	/**
	 * The zoom to draw this slice at. Cave slices and unsaved maps have no overview tiles, so they
	 * stop at the detailed zoom, as they do on the fullscreen map.
	 */
	private static double zoom(MapLevel level, int layer) {
		double zoom = Settings.get().zoom();
		return layer == TileKey.SURFACE && level.hasOverviews() ? zoom : Math.max(zoom, MapViewport.DETAILED_ZOOM);
	}

	/**
	 * Where the minimap goes on a {@code width} by {@code height} screen: its x, y and size. It
	 * shrinks so it and its lines fit the screen and, placed low beside the hotbar, so it stays
	 * beside it instead of growing over the hearts or hunger bar ({@code avoidHotbar}). It stays
	 * below the rows of status effect icons it would be over: placed at the top it moves down,
	 * placed at the bottom it grows up only to them. The same goes for the boss bars midway across
	 * the top, whose lowest ends at {@code bossBars} (0 with none), when it would be over or under
	 * them. Whatever the window or GUI scale did since it
	 * was placed, it ends up on screen.
	 */
	static int[] place(Anchor anchor, int wanted, boolean avoidHotbar, int width, int height, int infoHeight, int[] effects, int bossBars) {
		// Enlarged on a tall, narrow window, it would otherwise reach past the far side.
		int fitted = Math.min(wanted, width - 2 * MARGIN - 4);
		if (!anchor.top()) {
			fitted = Math.min(fitted, height - infoHeight - 2 * MARGIN);
			// It grows up and in from the edges it's placed by, so from beside the hotbar it stops
			// at it: in its corner always, as it always has, and elsewhere where it fits beside it.
			// Put any nearer, it's the player's choice to have it over the hotbar.
			int besideHotbar = width / 2 - HOTBAR_HALF_WIDTH - MARGIN - 4 - anchor.dx();
			if (avoidHotbar && !anchor.centered() && anchor.dy() < HOTBAR_HEIGHT && (besideHotbar >= SMALLEST || anchor.dx() == MARGIN)) {
				fitted = Math.min(fitted, besideHotbar);
			}
		}
		int size = Math.max(SMALLEST, fitted);
		// Below each row of icons it reaches under; it can only get smaller from here, and further from them.
		int top = MARGIN;
		int right = x(anchor, size, width) + size + 2;
		if (right > width - EFFECT_WIDTH * effects[1]) {
			top = MARGIN + 2 * EFFECT_ROW;
		} else if (right > width - EFFECT_WIDTH * effects[0]) {
			top = MARGIN + EFFECT_ROW;
		}
		if (bossBars > 0 && right > width / 2 - BOSS_BAR_HALF_WIDTH && right - size - 4 < width / 2 + BOSS_BAR_HALF_WIDTH) {
			top = Math.max(top, bossBars + MARGIN);
		}
		int room = anchor.top() ? height - top - infoHeight - MARGIN - 4 : height - anchor.dy() - infoHeight - top;
		size = Math.max(SMALLEST, Math.min(size, room));
		int y = anchor.top() ? anchor.dy() : height - anchor.dy() - infoHeight - size;
		y = Math.max(top, Math.min(y, height - MARGIN - infoHeight - size));
		return new int[] {x(anchor, size, width), y, size};
	}

	/** The map's left edge at this size, kept on screen. */
	private static int x(Anchor anchor, int size, int width) {
		int x = anchor.centered() ? (width - size) / 2 : anchor.right() ? width - anchor.dx() - size : anchor.dx();
		return Math.max(MARGIN, Math.min(x, width - MARGIN - size));
	}

	/** Letters on the frame, by which way the map faces. At another text size each grows in from its edge. */
	private void compass(GuiGraphicsExtractor graphics, Font font, int x, int y, int size, float text) {
		String[] letters = {"N", "E", "S", "W"};
		int turns = Projection.facing().turns;
		for (int i = 0; i < 4; i++) {
			// The letter's box at the game's text size: a pixel around the letter, two on its left.
			int w = font.width(letters[i]) + 3;
			int h = 9;
			// Which edge it sits on: top, right, bottom, left. The box stays against it, centred along it.
			float bx;
			float by;
			switch (Math.floorMod(i - turns, 4)) {
				case 0 -> {
					bx = x + size / 2 - (w - 3) / 2 - 2 + w / 2.0F - w * text / 2;
					by = y;
				}
				case 1 -> {
					bx = x + size - 5 - (w - 3) / 2 - 2 + w - w * text;
					by = y + size / 2 - 5 + h / 2.0F - h * text / 2;
				}
				case 2 -> {
					bx = x + size / 2 - (w - 3) / 2 - 2 + w / 2.0F - w * text / 2;
					by = y + size - 10 + h - h * text;
				}
				default -> {
					bx = x + 5 - (w - 3) / 2 - 2;
					by = y + size / 2 - 5 + h / 2.0F - h * text / 2;
				}
			}
			graphics.pose().pushMatrix();
			graphics.pose().translate(TextSize.snap(bx), TextSize.snap(by));
			graphics.pose().scale(text);
			graphics.fill(0, 0, w, h, 0xA0101418);
			graphics.text(font, letters[i], 2, 1, i == 0 ? 0xFFFF6B6B : 0xFFFFFFFF, false);
			graphics.pose().popMatrix();
		}
	}

	private List<InfoLine> infoLines(Settings settings, Entity viewer, Minecraft mc) {
		List<InfoLine> lines = new ArrayList<>();
		BlockPos pos = viewer.blockPosition();
		if (settings.coordinates) {
			lines.add(new InfoLine(pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), INFO_COLOR));
		}
		if (settings.biome) {
			mc.level.getBiome(pos).unwrapKey().ifPresent(key ->
				lines.add(new InfoLine(Component.translatable(key.identifier().toLanguageKey("biome")).getString(), INFO_COLOR)));
		}
		if (settings.gameTime != Settings.GameTime.OFF) {
			//? if <1.21.2 {
			/*// Until 1.21.2 the client worked this out only when a level loaded.
			mc.level.updateSkyBrightness();
			*///?}
			// The Overworld's clock in every dimension, as Xaero's Minimap reads it: the Nether and
			// the End have no day of their own, and this is when the player's bed would work.
			lines.add(new InfoLine(gameTime(settings.gameTime, mc.level.getOverworldClockTime()), mc.level.isDarkOutside() ? BED_TIME_COLOR : INFO_COLOR));
		}
		return lines;
	}

	/**
	 * The clock's ticks as Xaero's Minimap writes them. Tick 0 is 06:00 on day 1, so a new day starts
	 * at midnight, not at sunrise as in the game's own day count.
	 */
	public static String gameTime(Settings.GameTime format, long ticks) {
		long sinceMidnight = ticks + 6000;
		long day = Math.floorDiv(sinceMidnight, 24000) + 1;
		int inDay = Math.floorMod(sinceMidnight, 24000);
		int hour = inDay / 1000;
		int minute = inDay % 1000 * 60 / 1000;
		boolean twelveHour = format == Settings.GameTime.DAY_12H || format == Settings.GameTime.ONLY_12H;
		String time = String.format(Locale.ROOT, "%02d:%02d", twelveHour && hour % 12 == 0 ? 12 : twelveHour ? hour % 12 : hour, minute);
		if (twelveHour) {
			time = Component.translatable(hour < 12 ? "diorama.minimap.time_am" : "diorama.minimap.time_pm", time).getString();
		}
		boolean withDay = format == Settings.GameTime.DAY_24H || format == Settings.GameTime.DAY_12H;
		return withDay ? Component.translatable("diorama.minimap.day", day, time).getString() : time;
	}

	/** A line under the minimap. */
	private record InfoLine(String text, int color) {
	}

	/** Called as the game draws each boss bar, with the bar's top. */
	public static void bossBarDrawn(int y) {
		bossBarsBottom = Math.max(bossBarsBottom, y + BOSS_BAR_HEIGHT);
	}

	/** Called when the game's own drawing of the status effect icons runs to the end, icons or not. */
	public static void effectIconsDrawn() {
		effectIconsDrawn = true;
	}

	/**
	 * How many status effect icons vanilla draws in each of its rows in the top-right corner,
	 * beneficial then harmful, each row from the right edge in. Only asked when the game did draw
	 * them: a mod that moves or hides them leaves the corner free.
	 */
	private static int[] effectCounts(LocalPlayer player) {
		int beneficial = 0;
		int harmful = 0;
		for (MobEffectInstance effect : player.getActiveEffects()) {
			if (!effect.showIcon()) {
				continue;
			}
			if (effect.getEffect().value().isBeneficial()) {
				beneficial++;
			} else {
				harmful++;
			}
		}
		return new int[] {beneficial, harmful};
	}
}
