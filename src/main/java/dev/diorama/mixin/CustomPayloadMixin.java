package dev.diorama.mixin;

//? if >=1.21 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.diorama.XaeroChannels;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import dev.diorama.XaeroChannels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Shows XaeroChannels each mod message as it arrives, before whichever mod has its channel reads
 * it, so Diorama gets its messages on channels another mod has.
 */
//? if >=1.21 {
// The codec every mod message goes through, on every loader: it reads the channel, then hands the
// rest to that channel's codec.
@Mixin(targets = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
public abstract class CustomPayloadMixin {
	@WrapOperation(method = "decode", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;decode(Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object diorama$peek(StreamCodec<Object, Object> codec, Object input, Operation<Object> original, @Local Identifier channel) {
		XaeroChannels.Message message = XaeroChannels.peek(channel, (ByteBuf) input);
		Object payload = original.call(codec, input);
		if (message != null) {
			XaeroChannels.peeked(payload, message);
		}
		return payload;
	}

	// Diorama's answer on a channel another mod has would go through that mod's codec, or none, so
	// its messages are written here.
	@Inject(method = "encode(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V", at = @At("HEAD"), cancellable = true)
	private void diorama$write(FriendlyByteBuf output, CustomPacketPayload value, CallbackInfo ci) {
		if (value instanceof XaeroChannels.Message message) {
			output.writeIdentifier(message.type().id());
			XaeroChannels.write(output, message);
			ci.cancel();
		}
	}
}
//?} else {
/*// Before 1.21 the message keeps its bytes, read as it arrives.
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class CustomPayloadMixin {
	@Shadow
	@Final
	private Identifier identifier;
	@Shadow
	@Final
	private FriendlyByteBuf data;

	@Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
	private void diorama$peek(FriendlyByteBuf input, CallbackInfo ci) {
		XaeroChannels.Message message = XaeroChannels.peek(this.identifier, this.data);
		if (message != null) {
			XaeroChannels.peeked(this, message);
		}
	}
}
*///?}
