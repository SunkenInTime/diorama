package dev.diorama;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.ArrayList;
//? if >=1.21 {
import java.util.ArrayDeque;
//?}
import java.util.List;
//? if >=1.21 {
import java.util.Map;
import java.util.Queue;
import java.util.WeakHashMap;
//?}
import net.minecraft.client.Minecraft;
//? if fabric {
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
//?} else if neoforge {
/*import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
*///?} else {
/*import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.MCRegisterPacketHandler;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
*///?}
//? if fabric && >=1.21 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?} else if fabric {
/*import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
*///?}
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.21 {
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
//? if >=1.21.9 {
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
//?}
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The plugin channels Xaero's Minimap and World Map share with servers. Servers built around
 * Xaero's mods send on them: Xaero's mods installed on the server, and plugins such as
 * MapModCompanion. Both channels carry the same messages, each one byte for its kind and then
 * its body. Diorama reads:
 * <ul>
 *   <li>the level ID (kind 0, a big-endian int): the server's name for the world the player is
 *       in, which tells apart worlds behind one address that share a seed;</li>
 *   <li>the handshake (kind 1, the server's protocol version as an int), which Diorama answers
 *       like Xaero's mods do, since servers only track players for clients that answered;</li>
 *   <li>tracked players (kind 2, one player in network NBT, see {@link TrackedPlayers}) and the
 *       tracker reset (kind 3, empty), sent before the server sends everyone again.</li>
 * </ul>
 *
 * <p>Servers track players on one channel per client, the minimap's when the client answered
 * there. Diorama takes them from that channel only, so a server moving a player from one channel
 * to the other can't remove them. It doesn't answer at all while Xaero's World Map is installed:
 * an answer moves the server's tracking from the World Map's channel to Diorama.
 *
 * <p>Fabric, NeoForge and Forge allow one mod per channel, and registering a channel twice throws
 * at startup, in whichever mod comes second. So a channel is only Diorama's when no other mod has
 * it, and Diorama registers after every other mod. On a channel a Xaero mod has, Diorama still
 * reads level IDs as they arrive, before that mod reads them (see {@link #peek}), and leaves the
 * rest to it. On a channel another mod has, like BuildPrint, which listens there for level IDs,
 * Diorama reads every message that way and answers the handshake itself.
 */
public final class XaeroChannels {
	/** Message kinds, the first byte of every message. */
	static final int LEVEL_ID = 0;
	static final int HANDSHAKE = 1;
	static final int TRACKED_PLAYER = 2;
	static final int TRACKER_RESET = 3;
	/** The protocol version Xaero's mods answer with, which the player tracker needs. */
	private static final int PROTOCOL = 3;

	/** The channels, public for tests that send on them. */
	public static final CustomPacketPayload.Type<Message> MINIMAP = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xaerominimap", "main"));
	public static final CustomPacketPayload.Type<Message> WORLD_MAP = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xaeroworldmap", "main"));
	/** VoxelMap's and JourneyMap's world ID channel, which MapModCompanion also listens on. */
	private static final Identifier WORLD_INFO = Identifier.fromNamespaceAndPath("worldinfo", "world_id");

	/** The channels a Xaero mod has, whose level IDs Diorama reads in {@link #peek}. */
	private static final List<Identifier> CLAIMED = new ArrayList<>(2);
	/** The channels another mod got to first, whose messages Diorama reads in {@link #peek}. */
	private static final List<Identifier> TAKEN = new ArrayList<>(2);
	/** The channel tracked players come on: the first Diorama answered on, the minimap's over the World Map's. */
	private static CustomPacketPayload.@Nullable Type<Message> trackerChannel;

	private XaeroChannels() {
	}

	//? if <1.21 {
	/*// Payload types came after 1.20.1. There a channel is only its id, and its messages are read
	// and written by hand (see read and write). This stands in for the game's payload interface,
	// so the rest of the class is the same on every version.
	public interface CustomPacketPayload {
		record Type<T>(ResourceLocation id) {
		}
	}
	*///?}

	/** One message: its kind, then its body as sent. */
	public record Message(CustomPacketPayload.Type<Message> type, int kind, byte[] body) implements CustomPacketPayload {
		/** The level ID this message carries, or null if it's another kind. */
		public @Nullable Integer levelId() {
			return this.kind == LEVEL_ID && this.body.length >= 4 ? ByteBuffer.wrap(this.body).getInt() : null;
		}
	}

	//? if fabric && >=1.21 {
	/** Listens on the channels no Xaero mod has. Call once, at startup. */
	public static void register() {
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			try {
				PayloadTypeRegistry.clientboundPlay().register(type, codec(type));
				PayloadTypeRegistry.serverboundPlay().register(type, codec(type));
			} catch (IllegalArgumentException e) {
				taken(type);
				continue;
			}
			// Registering a handler also tells the server Diorama listens here. Plugins need that:
			// Paper drops messages on channels the client hasn't registered.
			ClientPlayNetworking.registerGlobalReceiver(type, (message, context) -> receive(message));
		}
	}

	/**
	 * For tests: Diorama leaves the channels as if Xaero's mods had them (true), keeping only what
	 * {@link #peek} reads, or takes them back (false). Call while not on a server.
	 */
	public static void handOverForTests(boolean toXaero) {
		handOver(CLAIMED, toXaero);
	}

	/** For tests: as {@link #handOverForTests}, as if another mod had taken the channels. */
	public static void leaveToAnotherModForTests(boolean leave) {
		handOver(TAKEN, leave);
	}

	private static void handOver(List<Identifier> holder, boolean leave) {
		for (CustomPacketPayload.Type<Message> type : List.of(MINIMAP, WORLD_MAP)) {
			if (leave) {
				ClientPlayNetworking.unregisterGlobalReceiver(type.id());
				holder.add(type.id());
			} else {
				holder.remove(type.id());
				ClientPlayNetworking.registerGlobalReceiver(type, (message, context) -> receive(message));
			}
		}
	}
	//?} else if fabric {
	/*// Listens on the channels no Xaero mod has. Call once, at startup.
	public static void register() {
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			// Registering a handler also tells the server Diorama listens here. Plugins need that:
			// Paper drops messages on channels the client hasn't registered. A channel that already
			// has a handler isn't taken over: here Fabric returns false instead of throwing.
			boolean registered = ClientPlayNetworking.registerGlobalReceiver(type.id(), (client, handler, buf, sender) -> {
				// The handler runs on the network thread, and the buffer is only good until it
				// returns. So the message is read here and handled on the client thread, as later
				// versions do, unless the player has left by then.
				Message message = read(type, buf);
				client.execute(() -> {
					if (handler.getConnection().isConnected()) {
						receive(message);
					}
				});
			});
			if (!registered) {
				taken(type);
			}
		}
	}
	*///?} else if forge {
	/*// Listens on the channels no Xaero mod has. Call once, at startup.
	public static void register() {
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			// Version "1.0" like the NeoForge jars, and any version accepted either way: Forge only
			// lets a player in when both sides accept the other's version of every channel they
			// share, and a server without the channel has none. Forge tells servers that aren't
			// Forge about its channels as the player joins, which Paper needs before it passes
			// messages on.
			EventNetworkChannel channel;
			try {
				channel = NetworkRegistry.newEventChannel(type.id(), () -> "1.0", version -> true, version -> true);
			} catch (IllegalArgumentException e) {
				taken(type);
				continue;
			}
			channel.<NetworkEvent.ServerCustomPayloadEvent>addListener(event -> {
				if (event instanceof NetworkEvent.ServerCustomPayloadLoginEvent) {
					return;
				}
				// Read here: the buffer is only good until this returns. Handled on the client thread,
				// as on Fabric, unless the player has left by then.
				Message message = read(type, event.getPayload());
				NetworkEvent.Context context = event.getSource().get();
				Connection connection = context.getNetworkManager();
				context.enqueueWork(() -> {
					if (connection.isConnected()) {
						receive(message);
					}
				});
				context.setPacketHandled(true);
			});
		}
	}
	*///?} else if >=1.21.6 {
	/*// Listens on the channels no Xaero mod has. NeoForge calls it once, at startup.
	public static void register(RegisterPayloadHandlersEvent event) {
		// Registered the way Xaero's mods register theirs: a NeoForge server with Xaero's mod on it
		// refuses a client whose channel has another version or direction. Optional, so servers
		// without the channel still let the player in. As the player joins a server that isn't
		// NeoForge, NeoForge tells it about optional channels, which Paper needs before it passes
		// messages on.
		PayloadRegistrar registrar = event.registrar("1.0").optional();
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			// From NeoForge 21.6 the client's handler is its own argument, and NeoForge won't start
			// with a clientbound channel that has none. The server side (a LAN guest's messages to
			// the integrated server) has nothing to do.
			try {
				registrar.playBidirectional(type, codec(type), (message, context) -> {}, (message, context) -> receive(message));
			} catch (UnsupportedOperationException e) {
				taken(type);
			}
		}
	}
	*///?} else {
	/*// Listens on the channels no Xaero mod has. NeoForge calls it once, at startup.
	public static void register(RegisterPayloadHandlersEvent event) {
		// Registered the way Xaero's mods register theirs: a NeoForge server with Xaero's mod on it
		// refuses a client whose channel has another version or direction. Optional, so servers
		// without the channel still let the player in. As the player joins a server that isn't
		// NeoForge, NeoForge tells it about optional channels, which Paper needs before it passes
		// messages on.
		PayloadRegistrar registrar = event.registrar("1.0").optional();
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			try {
				registrar.playBidirectional(type, codec(type), (message, context) -> {
					// The integrated server shares the registration, so a LAN guest's messages come here too.
					if (context.flow() == PacketFlow.CLIENTBOUND) {
						receive(message);
					}
				});
			} catch (UnsupportedOperationException e) {
				taken(type);
			}
		}
	}
	*///?}

	/** Another mod registered {@code type} first and handles it. Diorama reads along in {@link #peek}. */
	private static void taken(CustomPacketPayload.Type<Message> type) {
		Diorama.LOGGER.info("Another mod has {}, so Diorama reads it without being its handler", type.id());
		TAKEN.add(type.id());
	}

	/** The channels whose Xaero mod isn't installed. */
	private static List<CustomPacketPayload.Type<Message>> unclaimed() {
		List<CustomPacketPayload.Type<Message>> unclaimed = new ArrayList<>(2);
		CLAIMED.clear();
		// Fair-Play Minimap used the minimap's channel name in some versions.
		if (unclaimed(MINIMAP, "xaerominimap", "xaerominimapfair", "xaerobetterpvp")) {
			unclaimed.add(MINIMAP);
		} else {
			CLAIMED.add(MINIMAP.id());
		}
		if (unclaimed(WORLD_MAP, "xaeroworldmap")) {
			unclaimed.add(WORLD_MAP);
		} else {
			CLAIMED.add(WORLD_MAP.id());
		}
		return unclaimed;
	}

	private static boolean unclaimed(CustomPacketPayload.Type<Message> type, String... xaeroMods) {
		for (String mod : xaeroMods) {
			if (modLoaded(mod)) {
				Diorama.LOGGER.info("{} is installed, so it keeps {}; Diorama only reads level IDs there", mod, type.id());
				return false;
			}
		}
		return true;
	}

	private static boolean modLoaded(String mod) {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded(mod);
		//?} else {
		/*return ModList.get().isLoaded(mod);
		*///?}
	}

	//? if >=1.21 {
	/** Public for tests. */
	public static StreamCodec<FriendlyByteBuf, Message> codec(CustomPacketPayload.Type<Message> type) {
		return StreamCodec.of(XaeroChannels::write, buf -> read(type, buf));
	}
	//?}

	/** Public for tests. */
	public static void write(FriendlyByteBuf buf, Message message) {
		buf.writeByte(message.kind());
		buf.writeBytes(message.body());
	}

	/** The whole message is read, as the game rejects packets with bytes left over. Public for tests. */
	public static Message read(CustomPacketPayload.Type<Message> type, FriendlyByteBuf buf) {
		int kind = buf.isReadable() ? buf.readByte() : -1;
		byte[] body = new byte[buf.readableBytes()];
		buf.readBytes(body);
		return new Message(type, kind, body);
	}

	private static void receive(Message message) {
		switch (message.kind()) {
			case LEVEL_ID -> {
				Integer id = message.levelId();
				if (id != null) {
					WorldKey.serverLevelId(id);
				}
			}
			case HANDSHAKE -> answer(message.type());
			case TRACKED_PLAYER -> {
				if (message.type() == trackerChannel) {
					CompoundTag tag = readNbt(message.body());
					if (tag != null) {
						TrackedPlayers.update(tag);
					}
				}
			}
			case TRACKER_RESET -> {
				if (message.type() == trackerChannel) {
					TrackedPlayers.clear();
				}
			}
			default -> {
			}
		}
	}

	private static void answer(CustomPacketPayload.Type<Message> channel) {
		if (modLoaded("xaeroworldmap")) {
			return;
		}
		//? if !fabric {
		/*// NeoForge throws on a message to a server that didn't declare the channel. Servers that
		// track players declare it, as they read the answer.
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null || !hasChannel(connection, channel.id())) {
			Diorama.LOGGER.info("The server sent a handshake on {} but doesn't listen there, so it isn't answered", channel.id());
			return;
		}
		*///?}
		if (trackerChannel == null || channel == MINIMAP && trackerChannel != MINIMAP) {
			trackerChannel = channel;
			TrackedPlayers.clear();
		}
		Message answer = new Message(channel, HANDSHAKE, ByteBuffer.allocate(4).putInt(PROTOCOL).array());
		//? if fabric && >=1.21 {
		ClientPlayNetworking.send(answer);
		//?} else if fabric {
		/*FriendlyByteBuf buf = PacketByteBufs.create();
		write(buf, answer);
		ClientPlayNetworking.send(channel.id(), buf);
		*///?} else if neoforge {
		/*connection.send(answer);
		*///?} else {
		/*FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		write(buf, answer);
		connection.send(new ServerboundCustomPayloadPacket(channel.id(), buf));
		*///?}
	}

	private static @Nullable CompoundTag readNbt(byte[] body) {
		try {
			return new FriendlyByteBuf(Unpooled.wrappedBuffer(body)).readNbt();
		} catch (RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read a tracked player from the server", e);
			return null;
		}
	}

	/** Left the server, or a network moved the player to another of its servers. */
	public static void onJoinOrLeave() {
		trackerChannel = null;
	}

	/**
	 * The message on {@code channel}, read before the mod that has the channel reads it, if it's
	 * one for Diorama: anything on a channel another mod took, a level ID on a Xaero mod's, else
	 * null. The body is left as it was. Called on the network thread, by a mixin.
	 */
	public static @Nullable Message peek(Identifier channel, ByteBuf body) {
		boolean levelId = CLAIMED.contains(channel) && body.readableBytes() >= 5 && body.getByte(body.readerIndex()) == LEVEL_ID;
		if (!levelId && !TAKEN.contains(channel)) {
			return null;
		}
		return read(channel.equals(MINIMAP.id()) ? MINIMAP : WORLD_MAP, new FriendlyByteBuf(body.slice()));
	}

	//? if >=1.21 {
	/**
	 * Messages {@link #peek} read, by the payload they came in. The codec reads messages both ways,
	 * and a LAN guest's aren't for the client, so a message waits here until the client handles it
	 * as one from the server. From 1.21.9 the client also handles packets in a queue of their own,
	 * ahead of its other tasks, so an ID handled as a task could come after a world change the
	 * server sent after it, and name the wrong world. {@link #queuePeeked} puts it where its
	 * payload is. A queue each, as another mod's codec may return equal payloads for different
	 * messages, or one payload for all it ignores.
	 */
	private static final Map<Object, Queue<Message>> PEEKED = new WeakHashMap<>();

	/** {@code payload} carries {@code message}. Network thread, by a mixin. */
	public static void peeked(Object payload, Message message) {
		synchronized (PEEKED) {
			PEEKED.computeIfAbsent(payload, key -> new ArrayDeque<>()).add(message);
		}
	}

	/**
	 * The client is handling {@code payload}: on the network thread, before the loader hands it to
	 * the client thread, or on the client thread for a payload in a bundle. By a mixin.
	 */
	public static void queuePeeked(CustomPacketPayload payload, ClientCommonPacketListener listener) {
		Message message;
		synchronized (PEEKED) {
			Queue<Message> messages = PEEKED.get(payload);
			if (messages == null) {
				return;
			}
			message = messages.remove();
			if (messages.isEmpty()) {
				PEEKED.remove(payload);
			}
		}
		queue(message, listener);
	}
	//?}

	//? if >=1.21.9 {
	private static void queue(Message message, ClientCommonPacketListener listener) {
		PacketProcessor packets = Minecraft.getInstance().packetProcessor();
		if (packets.isSameThread()) {
			// The client thread handles a bundle's packets one after another, so this is the message's place.
			receivePeeked(message);
		} else {
			packets.scheduleIfPossible(listener, new PeekedMessage(message));
		}
	}

	/** A message from the server in the client's packet queue. */
	private record PeekedMessage(Message message) implements Packet<ClientCommonPacketListener> {
		@Override
		public PacketType<ClientboundCustomPayloadPacket> type() {
			return CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD;
		}

		@Override
		public void handle(ClientCommonPacketListener listener) {
			receivePeeked(this.message);
		}
	}
	//?} else if >=1.21 {
	/*// Packets and tasks share the client's one queue, so a task keeps the message's place.
	private static void queue(Message message, ClientCommonPacketListener listener) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			receivePeeked(message);
		} else {
			mc.execute(() -> receivePeeked(message));
		}
	}
	*///?} else {
	/*// The server's packet carrying message arrived. Packets and tasks share the client's one queue,
	// so a task keeps the message's place among the packets around it. Network thread, by a mixin.
	public static void peeked(Object packet, Message message) {
		Minecraft.getInstance().execute(() -> receivePeeked(message));
	}
	*///?}

	/** A message read off a channel another mod has, unless the player left before the client got to it. */
	private static void receivePeeked(Message message) {
		if (Minecraft.getInstance().getConnection() != null) {
			receive(message);
		}
	}

	/**
	 * Whether the server said it listens on one of the channels that come with level IDs: Xaero's
	 * own, which Xaero's server mod and XaeroTracker listen on, or VoxelMap's, which
	 * MapModCompanion does. Servers declare their channels as the player joins.
	 */
	static boolean serverMayNameWorlds() {
		//? if fabric {
		return ClientPlayNetworking.canSend(MINIMAP.id()) || ClientPlayNetworking.canSend(WORLD_MAP.id()) || ClientPlayNetworking.canSend(WORLD_INFO);
		//?} else {
		/*ClientPacketListener connection = Minecraft.getInstance().getConnection();
		return connection != null && (hasChannel(connection, MINIMAP.id()) || hasChannel(connection, WORLD_MAP.id()) || hasChannel(connection, WORLD_INFO));
		*///?}
	}

	//? if neoforge {
	/*private static boolean hasChannel(ClientPacketListener connection, Identifier channel) {
		return connection.hasChannel(channel);
	}
	*///?} else if forge {
	/*// Whether the server said it listens on the channel: a Forge server in the mod list it sends as
	// the player logs in, any other server with minecraft:register. Forge's own channels check the same.
	private static boolean hasChannel(ClientPacketListener connection, Identifier channel) {
		Connection network = connection.getConnection();
		ConnectionData forge = NetworkHooks.getConnectionData(network);
		MCRegisterPacketHandler.ChannelList other = NetworkHooks.getChannelList(network);
		return forge != null && forge.getChannels().containsKey(channel) || other != null && other.getRemoteLocations().contains(channel);
	}
	*///?}
}
