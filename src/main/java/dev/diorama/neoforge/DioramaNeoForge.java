package dev.diorama.neoforge;

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
//? if >=1.21.6 {
import dev.diorama.render.DioramaPipelines;
//?}
import dev.diorama.waypoint.WaypointHud;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waystones;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
//? if >=1.21.6 {
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
//?}
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Diorama's entrypoint on NeoForge: what {@code dev.diorama.fabric.DioramaFabric} does on Fabric,
 * through NeoForge's events. Only the NeoForge jars contain this package.
 */
@Mod(value = Diorama.MOD_ID, dist = Dist.CLIENT)
public final class DioramaNeoForge {
	public DioramaNeoForge(IEventBus modBus, ModContainer container) {
		DioramaClient.init();
		// The mod list's Config button opens the map settings.
		container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SettingsScreen(parent));
		// The settings file sits in the game directory, and from NeoForge 21.5 mods are constructed
		// before Minecraft exists. Client setup comes after; load them there, on the main thread.
		modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(Settings::get));
		modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
			//? if >=1.21.9 {
			event.registerCategory(DioramaClient.CATEGORY);
			//?}
			DioramaClient.KEY_MAPPINGS.forEach(event::register);
		});
		//? if >=1.21.6 {
		modBus.addListener(RegisterRenderPipelinesEvent.class, event -> event.registerPipeline(DioramaPipelines.FOG));
		//?}
		// As on Fabric: waypoints in the world under the whole vanilla HUD and every screen, as part
		// of the picture of the world, and the minimap above the HUD. Each layer already sits 200
		// deeper than the one before, which the Fabric jars before 1.21.6 have to make up for themselves.
		modBus.addListener(RegisterGuiLayersEvent.class, event -> {
			WaypointHud waypoints = new WaypointHud();
			MinimapHud minimap = new MinimapHud();
			event.registerBelowAll(Diorama.id("waypoints"), (graphics, deltaTracker) -> waypoints.extractRenderState(graphics, deltaTracker));
			event.registerAboveAll(Diorama.id("minimap"), (graphics, deltaTracker) -> minimap.extractRenderState(graphics, deltaTracker));
		});

		// Fired for the integrated server's chunks too, on its own thread.
		NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
			if (event.getLevel() instanceof ClientLevel level && event.getChunk() instanceof LevelChunk chunk && Minecraft.getInstance().isRunning()) {
				MapSession.chunkLoaded(level, chunk);
			}
		});
		// Servers set their rules with codes in system messages; read them before any other mod can
		// change or hide the line. Unlike Fabric's event, this one doesn't fire for a line vanilla
		// drops because it names a player you blocked (with "Hide Matched Names" on).
		NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, ClientChatReceivedEvent.System.class, event -> ServerRules.onServerMessage(event.getMessage()));
		NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> WaypointShare.register(event.getDispatcher()));
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
			ServerRules.onDisconnect();
			WaypointStore.onLeave();
			Maps.onLeave();
			DrawingStore.onLeave();
			WorldKey.onJoinOrLeave();
			XaeroChannels.onJoinOrLeave();
			TrackedPlayers.clear();
			Waystones.clear();
			Claims.clear();
		});
		// A network moving the player to another of its servers joins again without leaving.
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> {
			ServerRules.onDisconnect();
			WaypointStore.onLeave();
			Maps.onLeave();
			DrawingStore.onLeave();
			WorldKey.onJoinOrLeave();
			// Mods sync block ids from the server before this, which can number block states differently.
			BlockLooks.invalidate();
			XaeroChannels.onJoinOrLeave();
			TrackedPlayers.clear();
			Waystones.clear();
			Claims.clear();
		});
		// Lowest: mod bus events run priority by priority across mods, so a mod that has Xaero's
		// channels has them by then.
		modBus.addListener(EventPriority.LOWEST, false, RegisterPayloadHandlersEvent.class, XaeroChannels::register);
		// Radar icons from Xaero's Minimap's icon files, read again on every resource reload.
		//? if >=1.21.4 {
		modBus.addListener(net.neoforged.neoforge.client.event.AddClientReloadListenersEvent.class, event -> event.addListener(XaeroRadarIcons.ID, XaeroRadarIcons.LISTENER));
		//?} else {
		/*modBus.addListener(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent.class, event -> event.registerReloadListener(XaeroRadarIcons.LISTENER));
		*///?}
		// Leaving a world saves the map on the next tick, but closing the window stops ticking. This
		// fires as the window is asked to close, before the last frame, so from then on nothing may
		// start the map again (Minecraft.isRunning() is false).
		NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, event -> MapSession.close());
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.isRunning()) {
				DioramaClient.tick(mc);
			}
		});
	}
}
