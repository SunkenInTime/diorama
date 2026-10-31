package dev.diorama.waypoint;

import dev.diorama.Settings;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Making waypoints (where the player stands, at a picked spot, where they die) and going to them. */
public final class Waypoints {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private Waypoints() {
	}

	public static @Nullable String dimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.dimension().identifier().toString();
	}

	/** A new, unsaved waypoint where the player stands. */
	public static @Nullable Waypoint atPlayer() {
		LocalPlayer player = Minecraft.getInstance().player;
		String dimension = dimension();
		return player == null || dimension == null ? null : at(player.blockPosition(), dimension);
	}

	/** A new, unsaved waypoint at a block position, named and coloured so the next one differs. */
	public static @Nullable Waypoint at(BlockPos pos, String dimension) {
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			return null;
		}
		int count = store.all().size() + 1;
		// A fresh random colour every time, never close to the last waypoint's.
		List<Waypoint> all = store.all();
		int color = WaypointColours.random(all.isEmpty() ? 0 : all.get(all.size() - 1).color);
		return new Waypoint("Waypoint " + count, pos.getX(), pos.getY(), pos.getZ(), dimension, color);
	}

	/** A dimension's name for its folder: Overworld, Nether, End, or the id's path for a modded one. */
	public static net.minecraft.network.chat.Component dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> net.minecraft.network.chat.Component.translatable("diorama.folder.overworld");
			case "minecraft:the_nether" -> net.minecraft.network.chat.Component.translatable("diorama.folder.nether");
			case "minecraft:the_end" -> net.minecraft.network.chat.Component.translatable("diorama.folder.end");
			default -> {
				String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ').replace('/', ' ');
				yield net.minecraft.network.chat.Component.literal(path.isEmpty() ? dimension : Character.toUpperCase(path.charAt(0)) + path.substring(1));
			}
		};
	}

	/** Adds a temporary waypoint at a block position: no editor, and it's never saved. */
	public static void addTemporary(BlockPos pos, String dimension) {
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			return;
		}
		String name = net.minecraft.network.chat.Component.translatable("diorama.waypoint.temporary").getString();
		Waypoint temporary = new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), dimension, 0xFFFFFFFF);
		temporary.kind = Waypoint.Kind.TEMPORARY;
		store.add(temporary);
	}

	/**
	 * The server only sends the commands a player may use, so teleporting is offered when it sent
	 * the teleport command's first word.
	 */
	public static boolean canTeleport() {
		return mayUse(Settings.get().teleportCommand);
	}

	/**
	 * Whether teleporting into a dimension is on offer. To another one, the teleport is run through
	 * {@code /execute in}, as Xaero's World Map does. That only suits {@code /tp}: nothing says a
	 * server's own teleport command would still work through it, or take the player's dimension along.
	 */
	public static boolean canTeleport(String dimension) {
		return dimension.equals(dimension()) ? canTeleport()
			: Settings.get().teleportCommand.equals(Settings.DEFAULT_TELEPORT_COMMAND) && canTeleport() && mayUse("execute");
	}

	private static boolean mayUse(String command) {
		var connection = Minecraft.getInstance().getConnection();
		return connection != null && connection.getCommands().getRoot().getChild(command.split(" ", 2)[0]) != null;
	}

	/** Sends the player's teleport command, {@code /tp} unless they changed it, to a block position in a dimension. */
	public static void teleport(String dimension, int x, int y, int z) {
		LocalPlayer player = Minecraft.getInstance().player;
		String command = (dimension.equals(dimension()) ? "" : "execute in " + dimension + " run ") + Settings.get().teleportCommand
			.replace("{x}", Integer.toString(x))
			.replace("{y}", Integer.toString(y))
			.replace("{z}", Integer.toString(z));
		// The command packet holds 256 characters; a longer one throws instead of sending.
		if (player != null && command.length() <= 256) {
			player.connection.sendCommand(command);
		}
	}

	private static boolean wasSleeping;

	/**
	 * Keeps a bed waypoint where the player last lay down to sleep, one per dimension, moved when
	 * they sleep somewhere else. Lying down is what the client sees of the server setting their
	 * respawn point, on any bed that sets one. Called every tick.
	 */
	public static void watchSleep(Minecraft mc) {
		LocalPlayer player = mc.player;
		boolean sleeping = player != null && player.isSleeping();
		if (sleeping && !wasSleeping && Settings.get().bedMarkers) {
			// On joining a server the world may not be known yet; until it is, try again each tick.
			sleeping = player.getSleepingPos().map(Waypoints::slept).orElse(true);
		}
		wasSleeping = sleeping;
	}

	/** Puts the bed pin on a bed; false if there's no world to keep it in yet. */
	private static boolean slept(BlockPos pos) {
		WaypointStore store = WaypointStore.get();
		String dimension = dimension();
		if (store == null || dimension == null) {
			return false;
		}
		//? if >=26.3 {
		// A straw bed is for a night away: the respawn point stays where it was, and so does the pin.
		var level = Minecraft.getInstance().level;
		if (level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.AbstractBedBlock bed && bed.getBedRule(level, pos).canSetSpawn() == net.minecraft.world.attribute.BedRule.Rule.NEVER) {
			return true;
		}
		//?}
		for (Waypoint waypoint : store.in(dimension)) {
			if (waypoint.kind == Waypoint.Kind.BED) {
				waypoint.x = pos.getX();
				waypoint.y = pos.getY();
				waypoint.z = pos.getZ();
				store.changed();
				return true;
			}
		}
		Waypoint bed = new Waypoint("Bed", pos.getX(), pos.getY(), pos.getZ(), dimension, 0xFFE5484D);
		bed.kind = Waypoint.Kind.BED;
		bed.icon = dev.diorama.render.WaypointIcons.BED;
		store.add(bed);
		return true;
	}

	/** Adds a death marker where the player stands. Called when the server reports their death. */
	public static void died(LocalPlayer player) {
		if (!Settings.get().deathMarkers) {
			return;
		}
		WaypointStore store = WaypointStore.get();
		String dimension = dimension();
		if (store == null || dimension == null) {
			return;
		}
		BlockPos pos = player.blockPosition();
		Waypoint death = new Waypoint("Death " + LocalTime.now().format(TIME), pos.getX(), pos.getY(), pos.getZ(), dimension, 0xFF60646C);
		death.kind = Waypoint.Kind.DEATH;
		store.add(death);
	}
}
