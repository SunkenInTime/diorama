package dev.diorama.compat;

import dev.diorama.Diorama;
import dev.diorama.bake.CraftBlocks;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where Create's trains are, all of them, including those far outside the client's view, whose
 * blocks the client never gets. Create 6 sends every client the list of trains, and sends the
 * places of their carriages every 5 ticks to a client that keeps asking. Create's own hooks for
 * Xaero's World Map, JourneyMap and FTB Chunks ask only while that map's full screen is open, as
 * this does for Diorama's: the answer holds every train on the server, which would cost every
 * player bandwidth if asked for all the time.
 *
 * <p>Diorama doesn't build against Create. The names below are Create 6's own, the same on Forge,
 * NeoForge and Fabric. Without Create, or with a Create that has no train map (before 6.0), this
 * stays off; if a name isn't where it's expected, it logs once and stays off.
 */
public final class CreateTrains {
	/** Ticks between two places Create sends; it moves a train from the one to the next over that time. */
	private static final double PACKET_TICKS = 5;

	private static boolean started;
	private static boolean failed;
	private static Method requestData;
	private static Method stopRequesting;
	private static Field currentData;
	private static Field lastPacket;
	private static Method getPosition;
	private static Field dimensions;
	private static Object railways;
	private static Field trains;
	private static Field trainField;
	private static Field trainName;
	private static Field mapColorIndex;
	private static Field carriages;
	private static Class<?> carriageEntity;
	private static Field trainId;
	private static Field carriageIndex;
	private static Method clientConfig;
	private static Field showOverlay;
	private static @Nullable Method configGet;
	private static Class<?> bogeyBlock;
	private static @Nullable Method animationTicks;
	private static @Nullable Method animationPartialTicks;

	/** Whether this is asking Create for places now, and Create's {@link #lastPacket} when it started. */
	private static boolean requesting;
	private static double askedAt;
	private static final Map<CraftBlocks, Boolean> CARRIAGES = new WeakHashMap<>();

	/**
	 * Create's train map colours, by the train's colour index: the main colour of each of the 16
	 * carriage sprites in Create's {@code trainmap_sprite_sheet.png}.
	 */
	private static final int[] COLOURS = {
		0x696A6A, 0x8E6348, 0x67BF31, 0x3D6EBD, 0x303036, 0xB63A3A, 0x23853F, 0xA558C0,
		0x4A555A, 0xEC9E37, 0x22AFAF, 0xDF68BD, 0x8A8A83, 0xE0B73E, 0x44C0D0, 0xF9B3BF,
	};

	private CreateTrains() {
	}

	/**
	 * One carriage of a train: the places of its two outer wheels (the front of its first bogey,
	 * the back of its last), moving between Create's last two packets, and whether the client has
	 * its entity, which Diorama draws block for block.
	 */
	public record Carriage(UUID train, int index, Component name, int rgb, Vec3 first, Vec3 second, boolean loaded) {
		/** The middle of the two bogeys. */
		public Vec3 middle() {
			return this.first.add(this.second).scale(0.5);
		}
	}

	private static boolean ready() {
		if (!started) {
			started = true;
			try {
				Class<?> client = Class.forName("com.simibubi.create.compat.trainmap.TrainMapSyncClient");
				requestData = client.getMethod("requestData");
				stopRequesting = client.getMethod("stopRequesting");
				currentData = client.getField("currentData");
				lastPacket = client.getField("lastPacket");
				Class<?> entry = Class.forName("com.simibubi.create.compat.trainmap.TrainMapSync$TrainMapSyncEntry");
				// The places are float[] on 1.20.1 and Float[] on 1.21.1; this method reads either.
				getPosition = entry.getMethod("getPosition", int.class, boolean.class, double.class);
				dimensions = entry.getField("dimensions");
				railways = Class.forName("com.simibubi.create.CreateClient").getField("RAILWAYS").get(null);
				trains = railways.getClass().getField("trains");
				Class<?> train = Class.forName("com.simibubi.create.content.trains.entity.Train");
				trainField = train.getField("id");
				trainName = train.getField("name");
				mapColorIndex = train.getField("mapColorIndex");
				carriages = train.getField("carriages");
				carriageEntity = Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity");
				trainId = carriageEntity.getField("trainId");
				carriageIndex = carriageEntity.getField("carriageIndex");
				clientConfig = Class.forName("com.simibubi.create.infrastructure.config.AllConfigs").getMethod("client");
				showOverlay = clientConfig.getReturnType().getField("showTrainMapOverlay");
				bogeyBlock = Class.forName("com.simibubi.create.content.trains.bogey.AbstractBogeyBlock");
				try {
					// The clock Create times its packets by, so carriages move as on Create's own maps.
					Class<?> ticks = Class.forName("net.createmod.catnip.animation.AnimationTickHolder");
					animationTicks = ticks.getMethod("getTicks");
					animationPartialTicks = ticks.getMethod("getPartialTicks");
				} catch (ReflectiveOperationException e) {
					// Carriages then jump from place to place every 5 ticks.
					animationTicks = null;
					animationPartialTicks = null;
				}
			} catch (ClassNotFoundException e) {
				// No Create, or one from before its train map.
				failed = true;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				fail(e);
			}
		}
		return !failed;
	}

	private static void fail(Throwable e) {
		if (!failed) {
			failed = true;
			requesting = false;
			Diorama.LOGGER.warn("Couldn't read where Create's trains are; far trains won't show on the map", e);
		}
	}

	/**
	 * Asks Create for the trains' places while {@code wanted}, the map screen being open, and stops
	 * once it isn't, as Create's own map hooks do. Follows Create's own setting for its train map
	 * overlay. Client thread, every tick.
	 */
	public static void tick(boolean wanted) {
		if (!wanted && !requesting || !ready()) {
			return;
		}
		try {
			if (wanted && overlayShown()) {
				if (!requesting) {
					requesting = true;
					askedAt = lastPacket.getDouble(null);
				}
				requestData.invoke(null);
			} else if (requesting) {
				requesting = false;
				stopRequesting.invoke(null);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	private static boolean overlayShown() throws ReflectiveOperationException {
		Object config = clientConfig.invoke(null);
		Object value = config == null ? null : showOverlay.get(config);
		if (value == null) {
			return true;
		}
		if (configGet == null) {
			configGet = value.getClass().getMethod("get");
		}
		return !Boolean.FALSE.equals(configGet.invoke(value));
	}

	/**
	 * Whether Create has answered since this started asking, so the places are those of every
	 * train on the server now.
	 */
	public static boolean known() {
		if (!requesting || failed) {
			return false;
		}
		try {
			return lastPacket.getDouble(null) != askedAt;
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return false;
		}
	}

	/**
	 * The carriages Create says are in {@code dimension} (a level's id, as {@code
	 * minecraft:overworld}) this frame. Empty unless this is asking, and until Create has answered.
	 */
	public static List<Carriage> carriages(String dimension) {
		if (!requesting || failed) {
			return List.of();
		}
		try {
			Map<?, ?> data = (Map<?, ?>) currentData.get(null);
			if (data.isEmpty()) {
				return List.of();
			}
			double time = 1;
			if (animationTicks != null && animationPartialTicks != null) {
				double now = ((Number) animationTicks.invoke(null)).intValue() + ((Number) animationPartialTicks.invoke(null)).doubleValue();
				time = Math.max(0, Math.min(1, (now - lastPacket.getDouble(null)) / PACKET_TICKS));
			}
			Set<Key> loaded = loaded(dimension);
			List<Carriage> out = new ArrayList<>();
			for (Object train : ((Map<?, ?>) trains.get(railways)).values()) {
				UUID id = (UUID) trainField.get(train);
				Object entry = data.get(id);
				if (entry == null) {
					continue;
				}
				List<?> dims = (List<?>) dimensions.get(entry);
				List<?> list = (List<?>) carriages.get(train);
				Component name = (Component) trainName.get(train);
				int rgb = COLOURS[Math.floorMod(mapColorIndex.getInt(train), COLOURS.length)];
				for (int i = 0; i < list.size() && i < dims.size(); i++) {
					// No dimension: crossing a portal, or derailed in two at once.
					if (!(dims.get(i) instanceof ResourceKey<?> key) || !key.identifier().toString().equals(dimension)) {
						continue;
					}
					Vec3 first = (Vec3) getPosition.invoke(entry, i, true, time);
					Vec3 second = (Vec3) getPosition.invoke(entry, i, false, time);
					boolean here = loaded.contains(new Key(id, i));
					out.add(new Carriage(id, i, name, rgb, first, second, here));
				}
			}
			return out;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
			return List.of();
		}
	}

	private record Key(UUID train, int index) {
	}

	/** The carriages whose entities the client has in {@code dimension}. */
	private static Set<Key> loaded(String dimension) throws ReflectiveOperationException {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !level.dimension().identifier().toString().equals(dimension)) {
			return Set.of();
		}
		Set<Key> out = new HashSet<>();
		for (Entity entity : level.entitiesForRendering()) {
			if (carriageEntity.isInstance(entity) && !entity.isRemoved() && trainId.get(entity) instanceof UUID train) {
				out.add(new Key(train, carriageIndex.getInt(entity)));
			}
		}
		return out;
	}

	/** Whether these blocks are a train carriage's: Create's carriages carry their bogeys as blocks. */
	public static boolean isCarriage(CraftBlocks blocks) {
		if (!ready()) {
			return false;
		}
		return CARRIAGES.computeIfAbsent(blocks, b -> {
			IntSet seen = new IntOpenHashSet();
			for (int i = 0; i < b.volume(); i++) {
				int state = b.state(i);
				if (state != 0 && seen.add(state)) {
					BlockState blockState = Block.BLOCK_STATE_REGISTRY.byId(state);
					if (blockState != null && bogeyBlock.isInstance(blockState.getBlock())) {
						return true;
					}
				}
			}
			return false;
		});
	}
}
