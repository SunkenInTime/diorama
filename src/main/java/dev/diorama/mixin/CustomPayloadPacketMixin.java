package dev.diorama.mixin;

//? if >=1.21 {
import dev.diorama.XaeroChannels;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
*///?}

/**
 * Queues the message {@link CustomPayloadMixin} read where it arrives among the server's packets.
 * Before 1.21 that mixin only sees the server's messages and queues them itself.
 */
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class CustomPayloadPacketMixin {
	//? if >=1.21 {
	// HEAD: the first call, on the network thread before the loader hands the message to the client
	// thread, which calls this again; or on the client thread for a message in a bundle.
	@Inject(method = "handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V", at = @At("HEAD"))
	private void diorama$queuePeeked(ClientCommonPacketListener listener, CallbackInfo ci) {
		XaeroChannels.queuePeeked(((ClientboundCustomPayloadPacket) (Object) this).payload(), listener);
	}
	//?}
}
