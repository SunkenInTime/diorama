package dev.diorama.structure;

import dev.diorama.structure.StructureKinds.Kind;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What each kind of structure looks like on the map: a Minecraft item or block where one says it
 * well (an Eye of Ender for a stronghold), and for the people who live in a structure, their face
 * cut from the mob's own texture, since the game has no villager or pillager head to draw. No
 * background or frame: just the picture. Both come from the game's assets, so resource packs restyle them.
 */
public final class StructureIcons {
	/** A face: pieces of one texture, put together on a box {@code w} x {@code h} texture pixels. */
	private record Face(Identifier texture, int texW, int texH, int w, int h, List<Piece> pieces) {
	}

	/** {@code w} x {@code h} texture pixels from (u, v), drawn at (dx, dy) on the face. */
	private record Piece(int u, int v, int w, int h, int dx, int dy) {
	}

	/** A humanoid villager or illager head's front, and the nose that sticks out of its middle. */
	private static final List<Piece> LONG_NOSED = List.of(new Piece(8, 8, 8, 10, 0, 0), new Piece(26, 2, 2, 3, 3, 7));

	private static final Map<String, Face> FACES = Map.of(
		"village", face("villager/villager", 64, 64, 8, 10, LONG_NOSED),
		"pillager_outpost", face("illager/pillager", 64, 64, 8, 10, LONG_NOSED),
		"mansion", face("illager/evoker", 64, 64, 8, 10, LONG_NOSED),
		"swamp_hut", face("witch/witch", 64, 128, 10, 10, List.of(new Piece(10, 10, 10, 10, 0, 0))));

	/** The portal block's picture is a strip of frames; the first is the top square. */
	private static final Face PORTAL = new Face(Identifier.withDefaultNamespace("textures/block/nether_portal.png"), 16, 512, 16, 16,
		List.of(new Piece(0, 0, 16, 16, 0, 0)));

	private static final Map<String, Item> ITEMS = Map.ofEntries(
		Map.entry("stronghold", Items.ENDER_EYE),
		Map.entry("monument", Items.PRISMARINE_BRICKS),
		Map.entry("ancient_city", Items.SCULK_SHRIEKER),
		Map.entry("trial_chambers", Items.TRIAL_KEY),
		Map.entry("desert_pyramid", Items.CHISELED_SANDSTONE),
		Map.entry("jungle_pyramid", Items.MOSSY_COBBLESTONE),
		Map.entry("igloo", Items.SNOW_BLOCK),
		Map.entry("trail_ruins", Items.BRUSH),
		Map.entry("ocean_ruin", Items.NAUTILUS_SHELL),
		Map.entry("shipwreck", Items.OAK_BOAT),
		Map.entry("buried_treasure", Items.HEART_OF_THE_SEA),
		Map.entry("abandoned_camp", Items.CAMPFIRE),
		Map.entry("mineshaft", Items.CHEST_MINECART),
		Map.entry("fortress", Items.BLAZE_ROD),
		Map.entry("bastion", Items.GILDED_BLACKSTONE),
		Map.entry("nether_fossil", Items.BONE_BLOCK),
		Map.entry("end_ship", Items.ELYTRA),
		Map.entry("end_city", Items.SHULKER_SHELL));

	/** A structure this list doesn't know. */
	private static final Item UNKNOWN = Items.COMPASS;
	private static final Map<String, ItemStack> STACKS = new ConcurrentHashMap<>();

	private StructureIcons() {
	}

	private static Face face(String texture, int texW, int texH, int w, int h, List<Piece> pieces) {
		return new Face(Identifier.withDefaultNamespace("textures/entity/" + texture + ".png"), texW, texH, w, h, pieces);
	}

	/** An item's own size in GUI pixels; the {@code size} asked of {@link #draw} scales from it. */
	private static final int ITEM = 16;
	/** A face fills this much of {@code size} up and down: the faces are taller than wide, and items have a margin. */
	private static final float FACE_FILL = 0.9F;

	/** The kind's picture centred on a point and about {@code size} GUI pixels tall. */
	public static void draw(GuiGraphicsExtractor graphics, Kind kind, float centerX, float centerY, float size) {
		Face face = kind.id().equals("ruined_portal") ? PORTAL : FACES.get(kind.id());
		graphics.pose().pushMatrix();
		if (face != null) {
			float scale = size * FACE_FILL / face.h();
			graphics.pose().translate(centerX - face.w() * scale / 2, centerY - face.h() * scale / 2);
			graphics.pose().scale(scale, scale);
			for (Piece piece : face.pieces()) {
				graphics.blit(RenderPipelines.GUI_TEXTURED, face.texture(), piece.dx(), piece.dy(), piece.u(), piece.v(), piece.w(), piece.h(), face.texW(), face.texH());
			}
		} else {
			float scale = size / ITEM;
			graphics.pose().translate(centerX - ITEM * scale / 2, centerY - ITEM * scale / 2);
			graphics.pose().scale(scale, scale);
			graphics.item(STACKS.computeIfAbsent(kind.id(), id -> new ItemStack(ITEMS.getOrDefault(id, UNKNOWN))), 0, 0);
		}
		graphics.pose().popMatrix();
	}
}
