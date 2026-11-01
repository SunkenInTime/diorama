package dev.diorama.test;

import dev.diorama.bake.BlockLooks;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A block state that fails when asked for its shape, as a mod's block with a null shape does under
 * ModernFix, which builds each state's cache on first use: the map's block colours still build,
 * where it used to crash the game as a world loaded (NeoForge 1.21.1, Diorama 0.6.1). A registered
 * block can't stand in here, since without ModernFix the game builds every cache itself and would
 * crash first, so lodestone's cached collision shape is nulled while the table builds. Lodestone
 * comes out as a solid full block with the colours of its model.
 */
final class FailingShapeTest {
	private FailingShapeTest() {
	}

	static void run(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			BlockState lodestone = Blocks.LODESTONE.defaultBlockState();
			int id = Block.BLOCK_STATE_REGISTRY.getId(lodestone);
			BlockLooks real = BlockLooks.build();
			BlockLooks looks;
			try {
				Object cache = cache().get(lodestone);
				Field collisionShape = collisionShape(cache);
				Object shape = collisionShape.get(cache);
				collisionShape.set(cache, null);
				try {
					looks = BlockLooks.build();
				} finally {
					collisionShape.set(cache, shape);
				}
			} catch (IllegalAccessException e) {
				throw new AssertionError(e);
			} finally {
				BlockLooks.build();
			}
			if (looks.kind[id] != BlockLooks.SOLID || looks.shape[id] != 0) {
				throw new AssertionError("Lodestone with a failing shape is kind " + looks.kind[id] + " shape " + looks.shape[id] + ", not a solid full block");
			}
			if (looks.top[id] != real.top[id] || looks.side[id] != real.side[id]) {
				throw new AssertionError(String.format("Lodestone with a failing shape looks #%06x/#%06x, not #%06x/#%06x",
					looks.top[id], looks.side[id], real.top[id], real.side[id]));
			}
		});
	}

	/** A state's cache, found by type so it works where the game's names are obfuscated. */
	private static Field cache() {
		for (Field field : BlockBehaviour.BlockStateBase.class.getDeclaredFields()) {
			if (field.getType().getEnclosingClass() == BlockBehaviour.BlockStateBase.class) {
				field.setAccessible(true);
				return field;
			}
		}
		throw new AssertionError("BlockStateBase has no cache field");
	}

	private static Field collisionShape(Object cache) {
		for (Field field : cache.getClass().getDeclaredFields()) {
			if (field.getType() == VoxelShape.class) {
				field.setAccessible(true);
				return field;
			}
		}
		throw new AssertionError(cache.getClass() + " has no shape field");
	}
}
