package dev.diorama.forge;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import dev.diorama.DioramaClient;
import dev.diorama.MapSession;
import dev.diorama.Maps;
import dev.diorama.MinimapHud;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.TrackedPlayers;
import dev.diorama.WorldKey;
import dev.diorama.XaeroChannels;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.bake.BlockLooks;
import dev.diorama.render.XaeroRadarIcons;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waystones;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * What {@code dev.diorama.neoforge.DioramaNeoForge} does on NeoForge, through Forge's events.
 * {@link DioramaForge} calls it on the client only.
 */
final class DioramaForgeClient {
	private DioramaForgeClient() {
	}

	static void init() {
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		DioramaClient.init();
		// The mod list's Config button opens the map settings.
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
			() -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new SettingsScreen(parent)));
		// The settings file sits in the game directory, and Forge constructs mods before Minecraft
		// exists. Client setup comes after; load them there, on the main thread.
		modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, event -> event.enqueueWork(Settings::get));
		modBus.addListener(EventPriority.NORMAL, false, RegisterKeyMappingsEvent.class, event -> DioramaClient.KEY_MAPPINGS.forEach(event::register));
		// The minimap over the whole HUD. Waypoints in the world go under it all, nausea and the totem
		// animation included, which GameRendererMixin draws as the frame starts its GUI, as on Fabric.
		modBus.addListener(EventPriority.NORMAL, false, RegisterGuiOverlaysEvent.class, event -> {
			MinimapHud minimap = new MinimapHud();
			event.registerAboveAll("minimap", (gui, graphics, partialTick, width, height) -> minimap.render(graphics, partialTick));
		});
		modBus.addListener(EventPriority.NORMAL, false, RegisterClientReloadListenersEvent.class, event -> event.registerReloadListener(XaeroRadarIcons.LISTENER));
		// Once every mod has loaded, so a mod that has Xaero's channels has them by then.
		modBus.addListener(EventPriority.NORMAL, false, FMLLoadCompleteEvent.class, event -> event.enqueueWork(XaeroChannels::register));

		// Fired for the integrated server's chunks too, on its own thread.
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ChunkEvent.Load.class, event -> {
			if (event.getLevel() instanceof ClientLevel level && event.getChunk() instanceof LevelChunk chunk && Minecraft.getInstance().isRunning()) {
				MapSession.chunkLoaded(level, chunk);
			}
		});
		// Servers set their rules with codes in system messages; read them before any other mod can
		// change or hide the line.
		MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, ClientChatReceivedEvent.System.class, event -> ServerRules.onServerMessage(event.getMessage()));
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegisterClientCommandsEvent.class, event -> WaypointShare.register(event.getDispatcher()));
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientPlayerNetworkEvent.LoggingOut.class, event -> joinedOrLeft());
		// A network moving the player to another of its servers joins again without leaving.
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientPlayerNetworkEvent.LoggingIn.class, event -> {
			joinedOrLeft();
			// Forge syncs block ids from the server before this, which can number block states differently.
			BlockLooks.invalidate();
		});
		// Leaving a world saves the map on the next tick, but closing the window stops ticking first.
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, GameShuttingDownEvent.class, event -> MapSession.close());
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, event -> {
			Minecraft mc = Minecraft.getInstance();
			if (event.phase == TickEvent.Phase.END && mc.isRunning()) {
				DioramaClient.tick(mc);
			}
		});
	}

	private static void joinedOrLeft() {
		ServerRules.onDisconnect();
		WaypointStore.onLeave();
		Maps.onLeave();
		DrawingStore.onLeave();
		WorldKey.onJoinOrLeave();
		XaeroChannels.onJoinOrLeave();
		TrackedPlayers.clear();
		Waystones.clear();
		Claims.clear();
	}
}
