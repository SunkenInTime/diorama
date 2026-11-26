package dev.diorama.mixin;

import dev.diorama.MapSession;
import dev.diorama.waypoint.WaypointShare;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if neoforge && <1.21.4 || forge {
/*import net.neoforged.neoforge.client.ClientCommandHandler;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Tells the map when server light for a chunk has been applied, which cave slices draw, and the
 * sky view keeps the block light of for the night. Hooks the
 * method that applies it; the packet handler only queues that work. Also tells it when a block
 * entity got new data, which is how a copycat being filled reaches the client.
 * It also puts the "[Add]" link on a shared waypoint the server sends.
 * On NeoForge before 1.21.4 and on Forge it also runs the client's own commands from chat links.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "applyLightData", at = @At("TAIL"))
	//? if >=1.21.2 {
	private void diorama$onLightApplied(int x, int z, ClientboundLightUpdatePacketData lightData, boolean scheduleRebuild, CallbackInfo ci) {
	//?} else {
	/*// There was no scheduleRebuild parameter.
	private void diorama$onLightApplied(int x, int z, ClientboundLightUpdatePacketData lightData, CallbackInfo ci) {
	*///?}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			// The sections whose block light the server sent, or emptied.
			java.util.BitSet blockLight = (java.util.BitSet) lightData.blockYMask().clone();
			blockLight.or(lightData.emptyBlockYMask());
			MapSession.lightUpdated(mc.level, x, z, blockLight);
		}
	}

	// Only reached on the client thread: on the network thread the handler hands the packet over and
	// returns by throwing.
	@Inject(method = "handleBlockEntityData", at = @At("TAIL"))
	private void diorama$onBlockEntityData(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			MapSession.blockEntityChanged(mc.level, packet.getPos());
		}
	}

	// A line from the server that carries a shared waypoint gets its "[Add]" link here, as it
	// arrives: a chat mod that keeps its own copy of each line (E33Chat) takes it before chat does,
	// and has to find the link on it. ChatListenerMixin does the same for a player's line.
	@ModifyArg(method = "handleSystemChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/ChatListener;handleSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"), index = 0)
	//? if >=26.1 {
	private Component diorama$linkSharedWaypoint(Component content) {
		return WaypointShare.withAddLink(content);
	}
	//?} else {
	/*// The same call shows a line above the hotbar, where nothing can be clicked.
	private Component diorama$linkSharedWaypoint(Component content, boolean overlay) {
		return overlay ? content : WaypointShare.withAddLink(content);
	}
	*///?}

	// What a command like /say sends: a line with a sender and no signature.
	@ModifyArg(method = "handleDisguisedChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/ChatListener;handleDisguisedChatMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/ChatType$Bound;)V"), index = 0)
	private Component diorama$linkSharedWaypointSaid(Component message) {
		return WaypointShare.withAddLink(message);
	}

	//? if neoforge && <1.21.4 || forge {
	/*// A chat link's command, like a shared waypoint's [Add], goes through here. NeoForge 21.0 and
	// 21.3 send it straight to the server, so the client's own commands never run (NeoForge 21.1
	// checks them itself from 21.1.178, and 21.4 on always does). Forge 1.20.1 does the same.
	@Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
	private void diorama$runClientCommand(String command, CallbackInfoReturnable<Boolean> cir) {
		if (ClientCommandHandler.runCommand(command)) {
			cir.setReturnValue(true);
		}
	}
	*///?}
}
