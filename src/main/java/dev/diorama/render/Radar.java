package dev.diorama.render;

import com.google.common.collect.Iterables;
import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.RadarCategories;
import dev.diorama.Settings;
import dev.diorama.TrackedPlayers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Other players, mobs and dropped items on the map. Players show as their skin's face, including players
 * beyond the loaded chunks whose position the server reports (see {@link TrackedPlayers}). Mobs
 * show as their own face (see {@link MobFaces}) framed in a colour by how they treat you, or as a
 * dot of that colour when they have no face icon. Xaero's Minimap's icon files can give a mob an
 * image, an item or a dot instead (see {@link XaeroRadarIcons}), and Pokémon show as their own
 * pictures, a shiny one with a sparkle. Dropped items are small dots under
 * everything else, or the items themselves where there's room to tell them apart (see
 * {@link #itemPictures}). Anything well above or below you fades a little, and all but dropped items get a
 * caret, since the map is a top view and can't otherwise show height; the caret is a setting.
 * Whether something shows, how big, and with its name under it is up to its category (see
 * {@link RadarCategories}).
 */
public final class Radar {
	/** Vertical distance, in blocks, past which a caret shows. */
	private static final double CARET_HEIGHT = 3;
	/** A dropped item's picture is this many times its dot: 8 pixels on the minimap, 8 to 16 on the map. */
	private static final float ITEM_PICTURE = 4;
	/** The most item pictures in one view, fewer than an open double chest draws; piles past it stay dots. */
	private static final int MAX_ITEM_PICTURES = 64;
	/** A pile shows this many kinds of item, two by two. */
	private static final int PILE_KINDS = 4;

	/** In drawing order, which is also the order they lose to each other under the cursor. */
	public enum Kind {
		ITEM(0xFF55FFFF),
		PASSIVE(0xFF62D96B),
		NEUTRAL(0xFFF2C14E),
		HOSTILE(0xFFEF4B4B),
		PLAYER(0xFFFFFFFF);

		final int color;

		Kind(int color) {
			this.color = color;
		}
	}

	/**
	 * An entity placed on screen. {@code dy} is its height relative to the viewing player, and
	 * {@code named} whether its category puts its name under it in this view.
	 */
	public record Blip(Entity entity, Kind kind, float x, float y, double dy, float size, boolean named) {
	}

	/**
	 * A dropped item drawn as itself: its square's corner and side, and one of the dropped items it
	 * stands for. Without a stack it's a pile left as a dot, centred there.
	 */
	public record ItemPicture(Blip blip, @Nullable ItemStack stack, float x, float y, float size) {
	}

	/** The dropped items of one square of ground. */
	private static final class Pile {
		long squareX;
		long squareY;
		float x;
		float y;
		int count;
		final List<Blip> kinds = new ArrayList<>(PILE_KINDS);

		void add(Blip blip, ItemStack stack) {
			this.x += blip.x();
			this.y += blip.y();
			this.count++;
			Item item = stack.getItem();
			for (int i = 0; i < this.kinds.size(); i++) {
				Item other = ((ItemEntity) this.kinds.get(i).entity()).getItem().getItem();
				if (other == item) {
					return;
				}
				// In the items' own order, so a pile looks the same from frame to frame.
				if (Item.getId(item) < Item.getId(other)) {
					this.kinds.add(i, blip);
					if (this.kinds.size() > PILE_KINDS) {
						this.kinds.remove(PILE_KINDS);
					}
					return;
				}
			}
			if (this.kinds.size() < PILE_KINDS) {
				this.kinds.add(blip);
			}
		}
	}

	/**
	 * Entity types that aren't mobs and that their mod wants on the radar all the same, such as a
	 * train: the entity type tag {@code diorama:radar_shown}, in the mod's own data. They follow the
	 * passive mobs' settings, and get their picture from the mod's icon files as a mob does
	 * ({@link XaeroRadarIcons}). The server sends its tags to the client, so the mod has to be on both.
	 */
	public static final TagKey<EntityType<?>> RADAR_SHOWN = TagKey.create(Registries.ENTITY_TYPE, Diorama.id("radar_shown"));

	private Radar() {
	}

	public static @Nullable Kind kind(Entity entity) {
		if (entity instanceof AbstractClientPlayer) {
			return Kind.PLAYER;
		}
		if (entity instanceof ItemEntity) {
			return Kind.ITEM;
		}
		// Neutral first: endermen and zombified piglins are both, and they only attack when provoked.
		if (entity instanceof NeutralMob) {
			return Kind.NEUTRAL;
		}
		if (entity instanceof Enemy) {
			return Kind.HOSTILE;
		}
		if (entity instanceof Mob || entity.getType().builtInRegistryHolder().is(RADAR_SHOWN)) {
			return Kind.PASSIVE;
		}
		return null;
	}

	/** Everything the radar can show inside the view, players last. */
	public static List<Blip> collect(MapViewport.View view, float partialTick, boolean fullscreen) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer self = mc.player;
		Entity viewer = MapSession.viewer();
		List<Blip> blips = new ArrayList<>();
		if (level == null || self == null || viewer == null) {
			return blips;
		}
		double selfY = viewer.getPosition(partialTick).y;
		Settings settings = Settings.get();
		// Entities farther above or below than the range are left off; otherwise cave mobs crowd
		// the surface view.
		double range = settings.radarRange;
		float margin = 12;
		// Held, the player list key (Tab) names everything on the minimap, as it brings out names and
		// icons on Xaero's: a dot or a face the player doesn't know says what it is without opening the map.
		boolean listing = !fullscreen && mc.options.keyPlayerList.isDown();
		for (Entity entity : Iterables.concat(level.entitiesForRendering(), TrackedPlayers.unloaded(level))) {
			// The one the map follows is the arrow in its middle.
			if (entity == self || entity == viewer || !entity.isAlive() || entity.isInvisibleTo(self)) {
				continue;
			}
			Kind kind = kind(entity);
			if (kind == null) {
				continue;
			}
			RadarCategories.Entry category = RadarCategories.of(entity, kind);
			if (!category.shown()) {
				continue;
			}
			Vec3 pos = entity.getPosition(partialTick);
			// A player the server reports from afar is one the player is looking for, at whatever height,
			// and stays on the map as they come near: the caret says which way.
			if (Math.abs(pos.y - selfY) > range && !TrackedPlayers.tracks(entity)) {
				continue;
			}
			double u = Projection.u(pos.x, pos.z);
			double v = Projection.v(pos.x, pos.y, pos.z);
			float x = view.screenX(u, v);
			float y = view.screenY(u, v);
			if (x < view.x() - margin || y < view.y() - margin || x > view.x() + view.width() + margin || y > view.y() + view.height() + margin) {
				continue;
			}
			int percent = category.iconScale() == RadarCategories.SAME_SIZE ? settings.radarIconScale : category.iconScale();
			Settings.NameTags names = category.hasNames() ? category.names() : Settings.NameTags.NEVER;
			boolean named = names == Settings.NameTags.ALWAYS || names == Settings.NameTags.FULLSCREEN && fullscreen || listing && category.hasNames();
			blips.add(new Blip(entity, kind, x, y, pos.y - selfY, size(kind, percent / 100.0F, view.zoom(), fullscreen), named));
		}
		blips.sort(Comparator.comparingInt(b -> b.kind().ordinal()));
		return blips;
	}

	private static float size(Kind kind, float scale, double zoom, boolean fullscreen) {
		if (kind == Kind.ITEM) {
			return scale * (fullscreen ? (float) Math.max(2, Math.min(4, zoom)) : 2);
		}
		if (kind == Kind.PLAYER) {
			return scale * (fullscreen ? (float) Math.max(8, Math.min(16, zoom * 3)) : 7);
		}
		return scale * (fullscreen ? (float) Math.max(4, Math.min(9, zoom * 2.2)) : 4);
	}

	/**
	 * Draws either the players or everything else. Maps draw mobs, then waypoints, then players,
	 * so a player standing at a waypoint shows on top of its marker and a crowd of mobs doesn't
	 * hide one. Mobs draw batched, since a farm can put hundreds on screen.
	 */
	public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, List<Blip> blips, boolean fullscreen, boolean players) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		IconBatch icons = players ? IconBatch.direct(graphics) : IconBatch.batched(graphics);
		Settings settings = Settings.get();
		// Mobs' names go over all the mobs' icons, which draw together at the end.
		List<Blip> named = null;
		// A round view can't cut icons to its circle, so it shows only those wholly inside it, as drawn:
		// pixel heads use whole screen pixels per texel and can come out bigger than their size.
		Circle circle = view.round() ? Circle.in(view) : null;
		// Names on the minimap are part of its text, so its Text size sizes them (#412). The map has no such setting.
		float text = TextSize.scale(settings.radarTextSize);
		IconBatch measure = circle == null ? null : IconBatch.measuring(graphics);
		if (!players) {
			List<ItemPicture> pictures = itemPictures(view, blips, fullscreen);
			if (pictures == null) {
				itemDots(icons, blips, circle);
			} else {
				itemPictures(icons, pictures, circle);
			}
		}
		for (Blip blip : blips) {
			if (blip.kind() == Kind.ITEM || (blip.kind() == Kind.PLAYER) != players) {
				continue;
			}
			int alpha = alphaFor(blip.dy());
			if (measure != null) {
				measure.clear();
				icon(measure, blip, alpha, settings);
				if (!measure.inside(circle)) {
					continue;
				}
			}
			icon(icons, blip, alpha, settings);
			// On a round view, a mob's name only where it's wholly inside the circle, like the icons.
			// Players' names are left as they were.
			if (blip.named() && (players || circle == null || nameInside(circle, blip, text))) {
				if (players) {
					nameTag(graphics, blip, alpha, text);
				} else {
					if (named == null) {
						named = new ArrayList<>();
					}
					named.add(blip);
				}
			}
			icons.endIcon();
		}
		icons.draw();
		if (named != null) {
			for (Blip blip : named) {
				nameTag(graphics, blip, alphaFor(blip.dy()), text);
			}
		}
		if (measure != null) {
			measure.draw();
		}
		graphics.disableScissor();
	}

	/**
	 * All the dropped items as one icon, whatever the radar style: outlines first, then the dots over
	 * them. They look alike, so they need no order among themselves, and a farm's pile of hundreds
	 * draws as one shape in one draw rather than a layer per dot.
	 */
	private static void itemDots(IconBatch icons, List<Blip> blips, @Nullable Circle circle) {
		for (int pass = 0; pass < 2; pass++) {
			for (Blip blip : blips) {
				// They sort first.
				if (blip.kind() != Kind.ITEM) {
					break;
				}
				int alpha = alphaFor(blip.dy());
				int d = Math.max(1, Math.round(blip.size()));
				float x0 = blip.x() - d / 2.0F;
				float y0 = blip.y() - d / 2.0F;
				if (circle != null && !(circle.contains(x0 - 1, y0 - 1) && circle.contains(x0 + d + 1, y0 - 1)
					&& circle.contains(x0 - 1, y0 + d + 1) && circle.contains(x0 + d + 1, y0 + d + 1))) {
					continue;
				}
				if (pass == 0) {
					icons.fill(x0 - 1, y0 - 1, x0 + d + 1, y0 + d + 1, withAlpha(0xFF000000, alpha));
				} else {
					icons.fill(x0, y0, x0 + d, y0 + d, withAlpha(blip.kind().color, alpha));
				}
			}
		}
		icons.endIcon();
	}

	/**
	 * The dropped items as themselves, or null where they're dots: on the fullscreen map unless the
	 * radar style is dots, and on the minimap while the player list key (Tab) is held, as Xaero's
	 * shows its icons. A pile would be one picture hiding the rest, so the items on one square of
	 * ground, two pictures wide, show once per kind, up to four kinds two by two around the pile's
	 * middle: a farm's hundreds of drops come to a few pictures, which is also what keeps this cheap.
	 * The squares are the ground's, not the screen's, so piles don't regroup as the map scrolls.
	 */
	public static @Nullable List<ItemPicture> itemPictures(MapViewport.View view, List<Blip> blips, boolean fullscreen) {
		if (blips.isEmpty() || blips.get(0).kind() != Kind.ITEM) {
			return null;
		}
		if (fullscreen ? Settings.get().radarStyle == Settings.RadarStyle.DOTS : !Minecraft.getInstance().options.keyPlayerList.isDown()) {
			return null;
		}
		Map<Long, Pile> piles = new LinkedHashMap<>();
		float size = 0;
		for (Blip blip : blips) {
			// They sort first.
			if (blip.kind() != Kind.ITEM) {
				break;
			}
			if (!(blip.entity() instanceof ItemEntity dropped)) {
				continue;
			}
			ItemStack stack = dropped.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			size = Math.max(8, blip.size() * ITEM_PICTURE);
			// Where the item is on the whole map at this zoom, in screen pixels, from where it is in the
			// world: its place on screen is already rounded, and would change squares as the map moves.
			double px = Projection.u(dropped.getX(), dropped.getZ()) * view.zoom();
			double py = Projection.v(dropped.getX(), dropped.getY(), dropped.getZ()) * view.zoom();
			// Two pictures wide and tall, which is all the room a pile's four take: no pile's pictures reach into the next square.
			long squareX = (long) Math.floor(px / (size * 2));
			long squareY = (long) Math.floor(py / (size * 2));
			Pile pile = piles.computeIfAbsent(squareX << 32 | squareY & 0xFFFFFFFFL, key -> new Pile());
			pile.squareX = squareX;
			pile.squareY = squareY;
			pile.add(blip, stack);
		}
		List<ItemPicture> pictures = new ArrayList<>();
		int drawn = 0;
		for (Pile pile : piles.values()) {
			int kinds = pile.kinds.size();
			int columns = Math.min(2, kinds);
			int rows = (kinds + 1) / 2;
			// Around the pile's middle, as far as its pictures stay inside its square.
			// In doubles: far from the world's middle a float can't tell one square from the next.
			float left = view.screenX(pile.squareX * (size * 2.0) / view.zoom());
			float top = view.screenY(pile.squareY * (size * 2.0) / view.zoom());
			float cx = Math.max(left + columns * size / 2, Math.min(left + size * 2 - columns * size / 2, pile.x / pile.count));
			float cy = Math.max(top + rows * size / 2, Math.min(top + size * 2 - rows * size / 2, pile.y / pile.count));
			if (view.turn() != 0) {
				// The ground's squares lie at an angle to the screen: the pile's middle as it is.
				cx = pile.x / pile.count;
				cy = pile.y / pile.count;
			}
			if (drawn + kinds > MAX_ITEM_PICTURES) {
				pictures.add(new ItemPicture(pile.kinds.get(0), null, cx, cy, pile.kinds.get(0).size()));
				continue;
			}
			drawn += kinds;
			for (int i = 0; i < kinds; i++) {
				Blip blip = pile.kinds.get(i);
				// The last of three sits in the middle of its row.
				int inRow = i / 2 == rows - 1 ? kinds - 2 * (rows - 1) : columns;
				float x = cx + (i % 2 - inRow / 2.0F) * size;
				float y = cy + (i / 2 - rows / 2.0F) * size;
				pictures.add(new ItemPicture(blip, ((ItemEntity) blip.entity()).getItem(), x, y, size));
			}
		}
		return pictures;
	}

	/** Draws {@link #itemPictures}, under the icons the batch holds. */
	private static void itemPictures(IconBatch icons, List<ItemPicture> pictures, @Nullable Circle circle) {
		for (int pass = 0; pass < 2; pass++) {
			for (ItemPicture picture : pictures) {
				if (picture.stack() != null) {
					float x = picture.x();
					float y = picture.y();
					float s = picture.size();
					if (pass == 0 && (circle == null || circle.contains(x, y) && circle.contains(x + s, y) && circle.contains(x, y + s) && circle.contains(x + s, y + s))) {
						icons.itemUnder(picture.stack(), x, y, s / 16);
					}
					continue;
				}
				int alpha = alphaFor(picture.blip().dy());
				int d = Math.max(1, Math.round(picture.size()));
				float x0 = picture.x() - d / 2.0F;
				float y0 = picture.y() - d / 2.0F;
				if (circle != null && !(circle.contains(x0 - 1, y0 - 1) && circle.contains(x0 + d + 1, y0 - 1)
					&& circle.contains(x0 - 1, y0 + d + 1) && circle.contains(x0 + d + 1, y0 + d + 1))) {
					continue;
				}
				if (pass == 0) {
					icons.fill(x0 - 1, y0 - 1, x0 + d + 1, y0 + d + 1, withAlpha(0xFF000000, alpha));
				} else {
					icons.fill(x0, y0, x0 + d, y0 + d, withAlpha(Kind.ITEM.color, alpha));
				}
			}
		}
		icons.endIcon();
	}

	/** One blip's icon in the radar style, with a caret if it's well above or below and height arrows are on. */
	private static void icon(IconBatch icons, Blip blip, int alpha, Settings settings) {
		switch (settings.radarStyle) {
			case FRAMED -> framed(icons, blip, alpha);
			case DOTS -> dot(icons, blip, alpha);
			default -> pixelHead(icons, blip, alpha, settings.radarTypeOutline);
		}
		if (settings.radarHeightArrows && Math.abs(blip.dy()) > CARET_HEIGHT) {
			boolean below = blip.dy() < 0;
			float s = blip.size();
			if (settings.radarStyle == Settings.RadarStyle.FRAMED) {
				float caret = Math.max(4, s * 0.7F);
				float cx = blip.x() + s * 0.55F + caret * 0.3F;
				float cy = blip.y() - s * 0.45F;
				MapIcons.caret(icons, cx, cy, caret + 2, withAlpha(0xE0101418, alpha), below);
				MapIcons.caret(icons, cx, cy, caret, withAlpha(0xFFFFFFFF, alpha), below);
			} else {
				pixelCaret(icons, blip.x() + s * 0.7F + 1, blip.y() - s * 0.5F, below, alpha);
			}
		}
		if (blip.kind() != Kind.PLAYER && CobblemonPortraits.shiny(blip.entity())) {
			// On the corner across from the caret's. A mob's icon is 1.6 times its size across.
			sparkle(icons, blip.x() - blip.size() * 0.8F, blip.y() - blip.size() * 0.8F, alpha);
		}
	}

	/**
	 * A small yellow sparkle, its centre pixel at (cx, cy): a shiny Pokémon. Its colours differ too
	 * little to tell at an icon's size, and it's the one players go looking for.
	 */
	private static void sparkle(IconBatch icons, float cx, float cy, int alpha) {
		int black = withAlpha(0xFF000000, alpha);
		int yellow = withAlpha(0xFFFFE14A, alpha);
		// A plus three pixels across, outlined, with a white middle.
		icons.fill(cx - 1, cy - 2, cx + 2, cy + 3, black);
		icons.fill(cx - 2, cy - 1, cx + 3, cy + 2, black);
		icons.fill(cx, cy - 1, cx + 1, cy + 2, yellow);
		icons.fill(cx - 1, cy, cx + 2, cy + 1, yellow);
		icons.fill(cx, cy, cx + 1, cy + 1, withAlpha(0xFFFFFFFF, alpha));
	}

	/** Master's look: a player's face on a dark disc, a mob's face in a dark box framed in its kind's colour. */
	private static void framed(IconBatch icons, Blip blip, int alpha) {
		GuiGraphicsExtractor graphics = icons.graphics();
		float s = blip.size();
		if (blip.kind() == Kind.PLAYER && blip.entity() instanceof AbstractClientPlayer player) {
			MapIcons.dot(icons, blip.x(), blip.y(), s * 1.55F, withAlpha(0xFF101418, alpha));
			graphics.pose().pushMatrix();
			graphics.pose().translate(blip.x() - s / 2, blip.y() - s / 2);
			graphics.pose().scale(s / 8);
			icons.playerFace(player, 0, 0, 8, withAlpha(0xFFFFFFFF, alpha));
			graphics.pose().popMatrix();
			return;
		}
		XaeroRadarIcons.Icon icon = XaeroRadarIcons.of(blip.entity());
		if (icon != XaeroRadarIcons.Basic.DOT && (icon != null || MobFaces.has(blip.entity()))) {
			int box = Math.round(s * 1.6F);
			float x0 = blip.x() - box / 2.0F;
			float y0 = blip.y() - box / 2.0F;
			icons.fill(x0 - 1, y0 - 1, x0 + box + 1, y0 + box + 1, withAlpha(blip.kind().color, alpha));
			icons.fill(x0, y0, x0 + box, y0 + box, withAlpha(0xFF101418, alpha));
			if (icon instanceof XaeroRadarIcons.Sprite sprite) {
				sprite.drawFitted(icons, x0 + box / 2.0F, y0 + box / 2.0F, box - 2, withAlpha(0xFFFFFFFF, alpha));
			} else if (icon instanceof XaeroRadarIcons.ItemIcon item) {
				icons.item(item.stack(), x0 + 1, y0 + 1, (box - 2) / 16.0F);
			} else if (!MobFaces.draw(icons, blip.entity(), x0 + box / 2.0F, y0 + box / 2.0F, box - 2, withAlpha(0xFFFFFFFF, alpha))) {
				MapIcons.dot(icons, blip.x(), blip.y(), s, withAlpha(blip.kind().color, alpha));
			}
		} else {
			MapIcons.dot(icons, blip.x(), blip.y(), s + 2, withAlpha(0xE0101418, alpha));
			MapIcons.dot(icons, blip.x(), blip.y(), s, withAlpha(blip.kind().color, alpha));
		}
	}

	/**
	 * The head as pixel art, like a player head item: each texel a whole number of screen pixels,
	 * with a one-texel black outline and a drop shadow. Its position follows the map continuously.
	 */
	private static void pixelHead(IconBatch icons, Blip blip, int alpha, boolean typeOutline) {
		GuiGraphicsExtractor graphics = icons.graphics();
		float s = blip.size();
		int outline = typeOutline && blip.kind() != Kind.PLAYER ? blip.kind().color : 0xFF000000;
		if (blip.kind() == Kind.PLAYER && blip.entity() instanceof AbstractClientPlayer player) {
			float texel = texel(s, 8);
			pixelFrame(icons, blip.x(), blip.y(), 8, 8, texel, withAlpha(outline, alpha), alpha);
			icons.playerFace(player, 0, 0, 8, withAlpha(0xFFFFFFFF, alpha));
			graphics.pose().popMatrix();
			return;
		}
		XaeroRadarIcons.Icon icon = XaeroRadarIcons.of(blip.entity());
		if (icon instanceof XaeroRadarIcons.Sprite sprite) {
			sprite.drawPixel(icons, blip.x(), blip.y(), s * 1.6F, withAlpha(0xFFFFFFFF, alpha), withAlpha(outline, alpha), alpha);
		} else if (icon instanceof XaeroRadarIcons.ItemIcon item) {
			// The item in a slot framed like an 8-pixel face, so it's as big as the heads and has
			// their outline, in the kind's colour. Items can't fade with height: the game's item
			// renderer takes no colour.
			float texel = texel(s * 1.6F, 8);
			pixelFrame(icons, blip.x(), blip.y(), 8, 8, texel, withAlpha(outline, alpha), alpha);
			graphics.pose().popMatrix();
			icons.item(item.stack(), blip.x() - 4 * texel, blip.y() - 4 * texel, texel / 2);
		} else if (icon == XaeroRadarIcons.Basic.DOT
			|| !MobFaces.drawPixel(icons, blip.entity(), blip.x(), blip.y(), s * 1.6F, withAlpha(0xFFFFFFFF, alpha), withAlpha(outline, alpha), alpha)) {
			dot(icons, blip, alpha);
		}
	}

	/**
	 * Screen-pixel-aligned size of one texel for an icon {@code texels} across that should be about
	 * {@code target} GUI pixels: a whole number of screen pixels, at least one.
	 */
	static float texel(float target, int texels) {
		double gui = guiScale();
		long pixels = Math.max(1, Math.round(target * gui / texels));
		return (float) (pixels / gui);
	}

	private static double guiScale() {
		return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
	}

	/** Screen pixels to a GUI pixel. */
	static int pixelsPerGuiPixel() {
		return (int) guiScale();
	}

	/**
	 * Pushes a pose with one unit per texel and its origin at the icon's top left, centred on
	 * (x, y), and draws the shadow and outline of a w x h icon there. The caller draws the icon at
	 * (0, 0) and pops the pose.
	 */
	static void pixelFrame(IconBatch icons, float x, float y, int w, int h, float texel, int outline, int alpha) {
		GuiGraphicsExtractor graphics = icons.graphics();
		float x0 = x - w * texel / 2;
		float y0 = y - h * texel / 2;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x0, y0);
		graphics.pose().scale(texel);
		icons.fill(0, 0, w + 2, h + 2, withAlpha(0x60000000, alpha));
		icons.fill(-1, -1, w + 1, h + 1, outline);
		// Dark under the face, as the framed faces had: an enderman's face has see-through rows at the
		// bottom, which would otherwise show the outline colour as a block.
		icons.fill(0, 0, w, h, withAlpha(0xFF101418, alpha));
	}

	/** A square dot in the kind's colour with a black outline, centred on its fractional map position. */
	private static void dot(IconBatch icons, Blip blip, int alpha) {
		int d = Math.max(2, Math.round(blip.size() * (blip.kind() == Kind.PLAYER ? 0.6F : 0.8F)));
		float x0 = blip.x() - d / 2.0F;
		float y0 = blip.y() - d / 2.0F;
		icons.fill(x0, y0, x0 + d + 2, y0 + d + 2, withAlpha(0x60000000, alpha));
		icons.fill(x0 - 1, y0 - 1, x0 + d + 1, y0 + d + 1, withAlpha(0xFF000000, alpha));
		icons.fill(x0, y0, x0 + d, y0 + d, withAlpha(blip.kind().color, alpha));
		// A one-pixel highlight on the top left, the way Minecraft's GUI bevels its buttons.
		if (d >= 3) {
			int light = withAlpha(0x50FFFFFF, alpha);
			icons.fill(x0, y0, x0 + d - 1, y0 + 1, light);
			icons.fill(x0, y0 + 1, x0 + 1, y0 + d - 1, light);
		}
	}

	/** A small pixel arrow, up or down, white with a black outline, its centre at (cx, cy). */
	private static void pixelCaret(IconBatch icons, float cx, float cy, boolean down, int alpha) {
		int black = withAlpha(0xFF000000, alpha);
		int white = withAlpha(0xFFFFFFFF, alpha);
		// Three rows, 1, 3 and 5 pixels wide, tip first.
		for (int pass = 0; pass < 2; pass++) {
			int grow = pass == 0 ? 1 : 0;
			for (int row = 0; row < 3; row++) {
				int half = row;
				float y = down ? cy + 1 - row : cy - 1 + row;
				icons.fill(cx - half - grow, y - grow, cx + half + 1 + grow, y + 1 + grow, pass == 0 ? black : white);
			}
		}
	}

	/** How far under a blip's centre its name starts. A mob's head is drawn 1.6 times its size. */
	private static float nameOffset(Blip blip) {
		return blip.size() * (blip.kind() == Kind.PLAYER ? 0.75F : 0.8F) + 3;
	}

	/** Whether the plate {@link #nameTag} draws would be wholly inside the circle. */
	private static boolean nameInside(Circle circle, Blip blip, float text) {
		float half = Minecraft.getInstance().font.width(blip.entity().getName().getString()) * text / 2.0F;
		float top = blip.y() + nameOffset(blip) - 1;
		return circle.contains(blip.x() - half - text, top) && circle.contains(blip.x() + half, top)
			&& circle.contains(blip.x() - half - text, top + 9 * text) && circle.contains(blip.x() + half, top + 9 * text);
	}

	/**
	 * The player's or mob's name under its head, on the translucent dark plate vanilla puts name tags
	 * on, {@code text} GUI pixels to a font pixel.
	 */
	private static void nameTag(GuiGraphicsExtractor graphics, Blip blip, int alpha, float text) {
		Font font = Minecraft.getInstance().font;
		String name = blip.entity().getName().getString();
		int w = font.width(name);
		graphics.pose().pushMatrix();
		// The plate's top stays where it is at the game's size, so bigger text grows down, away from the head.
		graphics.pose().translate(blip.x() - w * text / 2.0F, blip.y() + nameOffset(blip) - 1 + text);
		graphics.pose().scale(text);
		graphics.fill(-1, -1, w, 8, withAlpha(0x40000000, alpha));
		graphics.text(font, name, 0, 0, withAlpha(0xFFFFFFFF, alpha), false);
		graphics.pose().popMatrix();
	}

	/**
	 * The blip under the cursor on the fullscreen map, if any, preferring players and then the nearest.
	 * Dropped items drawn as themselves are found where their pictures are, each standing for an item of its kind.
	 */
	public static @Nullable Blip under(MapViewport.View view, List<Blip> blips, double mouseX, double mouseY) {
		List<ItemPicture> pictures = itemPictures(view, blips, true);
		Blip best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Blip blip : blips) {
			if (pictures != null && blip.kind() == Kind.ITEM) {
				continue;
			}
			double dx = blip.x() - mouseX;
			double dy = blip.y() - mouseY;
			double d = Math.sqrt(dx * dx + dy * dy);
			// The square head as drawn (a mob's is 1.6 times its size), with its outline and a little slack.
			double reach = (blip.kind() == Kind.PLAYER || blip.kind() == Kind.ITEM ? blip.size() / 2 : blip.size() * 0.8) + 3;
			if (Math.max(Math.abs(dx), Math.abs(dy)) > reach) {
				continue;
			}
			boolean better = best == null
				|| blip.kind().ordinal() > best.kind().ordinal()
				|| blip.kind() == best.kind() && d < bestDistance;
			if (better) {
				best = blip;
				bestDistance = d;
			}
		}
		if (best == null && pictures != null) {
			// The last drawn is on top.
			for (ItemPicture picture : pictures) {
				float half = picture.stack() == null ? picture.size() / 2 + 3 : 0;
				float x0 = picture.x() - half;
				float y0 = picture.y() - half;
				float side = picture.stack() == null ? half * 2 : picture.size();
				if (mouseX >= x0 && mouseY >= y0 && mouseX < x0 + side && mouseY < y0 + side) {
					best = picture.blip();
				}
			}
		}
		return best;
	}

	/** "Zombie · 23 m · 6 below" */
	public static Component describe(Blip blip, Entity viewer) {
		int distance = (int) Math.round(blip.entity().distanceTo(viewer));
		int dy = (int) Math.round(blip.dy());
		String height = dy > 0 ? " · " + dy + " above" : dy < 0 ? " · " + (-dy) + " below" : "";
		return Component.empty().append(blip.entity().getDisplayName()).append(" · " + distance + " m" + height);
	}

	/** Things far above or below fade, but never below a readable alpha. */
	private static int alphaFor(double dy) {
		double fade = Math.max(0, Math.abs(dy) - 8) / 40;
		return (int) Math.round(255 * Math.max(0.45, 1 - fade));
	}

	static int withAlpha(int argb, int alpha) {
		int a = (argb >>> 24) * alpha / 255;
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
