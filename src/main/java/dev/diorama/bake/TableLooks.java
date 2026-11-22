package dev.diorama.bake;

import dev.diorama.data.Copycats;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Block looks with vanilla colours from {@link VanillaTable}, for where there are no textures to
 * read: a dedicated server drawing the web map. Everything but the colours comes from the
 * game's rules, as for {@link BlockLooks}. A block the table doesn't know, a modded one, takes
 * its colours from its mod jar's model and textures ({@link JarModels}), or failing that its
 * colour on Minecraft's own maps.
 */
public final class TableLooks extends Looks {
	private final VanillaTable table;
	/** The table's look by state id, -1 for a state it doesn't know. */
	private final int[] lookOf;
	private final @Nullable JarModels jars;

	public TableLooks(VanillaTable table) {
		this(table, null);
	}

	public TableLooks(VanillaTable table, @Nullable JarModels jars) {
		super(Block.BLOCK_STATE_REGISTRY.size(), Copycats.INSTALLED ? Block.BLOCK_STATE_REGISTRY.size() + COPYCAT_LOOKS : Block.BLOCK_STATE_REGISTRY.size());
		this.table = table;
		this.jars = jars;
		this.lookOf = table.lookByStateId();
		if (jars != null) {
			jars.useVanilla(table, this.lookOf);
		}
		// What a bake reads where a section's palette has no entry.
		this.ensure(VoxelReader.AIR_ID);
	}

	@Override
	protected void make(int id) {
		synchronized (this) {
			if (this.kind[id] != NOT_YET) {
				return;
			}
			BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
			byte kind = state == null ? AIR : this.work(id, state);
			KINDS.setRelease(this.kind, id, kind);
		}
	}

	@Override
	protected byte colours(int id, BlockState state, byte kind) {
		int index = id < this.lookOf.length ? this.lookOf[id] : -1;
		if (index < 0) {
			FaceColor[] faces = this.jars == null ? null : this.jars.faces(state);
			if (faces == null) {
				return this.flatColours(id, state, kind);
			}
			this.setColours(id, faces[0], faces[1]);
			FaceColor tinted = faces[0].tintIndex() >= 0 ? faces[0] : faces[1];
			if (tinted.tintIndex() >= 0 && greyish(tinted.tinted())) {
				this.tint[id] = JarModels.tintOf(state);
			}
			// As BlockLooks: a solid block whose top texture is mostly see-through draws like glass.
			if (kind == SOLID && !state.is(BlockTags.LEAVES) && faces[0].coverage() < 0.55F) {
				return GLASS;
			}
			return kind;
		}
		VanillaTable.Look look = this.table.looks.get(index);
		this.top[id] = look.top();
		this.topTinted[id] = look.topTinted();
		this.topTintWeight[id] = look.topTintWeight();
		this.side[id] = look.side();
		this.sideTinted[id] = look.sideTinted();
		this.sideTintWeight[id] = look.sideTintWeight();
		this.tint[id] = look.tint();
		this.constantTint[id] = look.constantTint();
		// The game's rules here say how rays treat the block; the table only adds what its
		// textures say, as BlockLooks's do: a see-through solid is glass, a block with nothing to
		// show is air.
		if (look.kind() == AIR || kind == SOLID && look.kind() == GLASS) {
			return look.kind();
		}
		return kind;
	}

	/**
	 * Whether a texture is close to grey, as the ones the game tints are: a tint gives them their
	 * colour. Whether a modded block is tinted is up to its client code, which a server can't run;
	 * a texture already drawn in colour (purple jacaranda leaves, say) is taken as not tinted.
	 */
	private static boolean greyish(int rgb) {
		int r = rgb >> 16 & 0xFF;
		int g = rgb >> 8 & 0xFF;
		int b = rgb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		return max == 0 || (max - min) <= max / 5;
	}
}
