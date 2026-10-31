package dev.diorama.fabric;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import dev.diorama.DioramaClient;
import dev.diorama.MapSession;
import dev.diorama.Maps;
import dev.diorama.MinimapHud;
import dev.diorama.ServerRules;
import dev.diorama.TrackedPlayers;
import dev.diorama.WorldKey;
import dev.diorama.XaeroChannels;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.bake.BlockLooks;
import dev.diorama.render.XaeroRadarIcons;
import dev.diorama.waypoint.WaypointHud;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waystones;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?}
import net.minecraft.client.KeyMapping;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.PackType;

/**
 * Diorama's entrypoint on Fabric: registers the key bindings and the HUD, and hooks the mod up to
 * Fabric API's events. Only the Fabric jars contain this package; NeoForge has its own entrypoint.
 */
public final class DioramaFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		DioramaClient.init();
		for (KeyMapping keyMapping : DioramaClient.KEY_MAPPINGS) {
			KeyMappingHelper.registerKeyMapping(keyMapping);
		}
		// Waypoints in the world are part of the picture of the world, so they go under the whole
		// vanilla HUD and every screen; the minimap goes over the HUD. Before 1.21.6
		// GameRendererMixin draws the waypoints.
		//? if >=1.21.6 {
		HudElementRegistry.addFirst(Diorama.id("waypoints"), new WaypointHud());
		HudElementRegistry.addLast(Diorama.id("minimap"), new MinimapHud());
		//?} else {
		/*// Drawn after the whole vanilla HUD, like addLast. Each vanilla HUD layer sits 200 deeper
		// than the one before and the GUI depth-tests, so lift the vanilla fills and text this
		// draws above all of them.
		MinimapHud minimapHud = new MinimapHud();
		HudRenderCallback.EVENT.register((graphics, deltaTracker) -> {
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(0, 0, 5000);
			minimapHud.render(graphics, deltaTracker);
			pose.popPose();
		});
		*///?}
		ClientChunkEvents.CHUNK_LOAD.register(MapSession::chunkLoaded);
		// Joining a server whose mods number block states differently renumbers them here, and leaving puts them back.
		RegistryIdRemapCallback.event(BuiltInRegistries.BLOCK).register(state -> BlockLooks.invalidate());
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> ServerRules.onServerMessage(message));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> WaypointShare.register(dispatcher));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> {
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
		ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> {
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
		// Once every mod has started, so a mod that has Xaero's channels has them by then.
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> XaeroChannels.register());
		// Radar icons from Xaero's Minimap's icon files, read again on every resource reload.
		//? if >=26.1 {
		net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(XaeroRadarIcons.ID, XaeroRadarIcons.LISTENER);
		//?} else if >=1.21.9 {
		/*net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloader(XaeroRadarIcons.ID, XaeroRadarIcons.LISTENER);
		*///?} else {
		/*net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
			new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
				@Override
				public net.minecraft.resources.Identifier getFabricId() {
					return XaeroRadarIcons.ID;
				}

				@Override
				public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
					XaeroRadarIcons.reload(manager);
				}
			});
		*///?}
		// Leaving a world saves the map on the next tick, but closing the window stops ticking first.
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MapSession.close());
		ClientTickEvents.END_CLIENT_TICK.register(DioramaClient::tick);
	}
}
