package dev.diorama.mixin;

import dev.diorama.waypoint.WaypointShare;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A player's chat line that carries a shared waypoint gets its "[Add]" link as the line is handed
 * to chat, not inside chat: a chat mod that keeps its own copy of each line (E33Chat) takes it
 * as chat receives it, before {@link ChatComponentMixin} runs. The link goes on the line shown,
 * after Minecraft's checks (hidden chat, blocked players, filtered words); the signed message is
 * untouched. Lines from the server get theirs in {@link ClientPacketListenerMixin}.
 */
@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {
	//? if >=26.1 {
	@ModifyArg(method = "showMessageToPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V"), index = 0)
	//?} else {
	/*@ModifyArg(method = "showMessageToPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V"), index = 0)
	*///?}
	private Component diorama$linkSharedWaypoint(Component contents) {
		return WaypointShare.withAddLinkOnArrival(contents);
	}
}
