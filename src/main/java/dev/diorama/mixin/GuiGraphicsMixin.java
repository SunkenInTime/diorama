package dev.diorama.mixin;

import dev.diorama.render.GuiStacks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=1.21.6 {
import org.joml.Matrix3x2fStack;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What {@link GuiStacks} needs to leave an addon's graphics as it found them: the scissors pushed
 * and popped, counted, and the pose stack, swapped for Diorama's own while it draws.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsMixin implements GuiStacks.Poses {
	//? if >=1.21.6 {
	@Shadow
	@Final
	@Mutable
	private Matrix3x2fStack pose;

	@Override
	public void diorama$setPose(Matrix3x2fStack pose) {
		this.pose = pose;
	}
	//?} else {
	/*@Shadow
	@Final
	@Mutable
	private PoseStack pose;

	@Override
	public void diorama$setPose(PoseStack pose) {
		this.pose = pose;
	}
	*///?}

	@Inject(method = "enableScissor", at = @At("HEAD"))
	private void diorama$scissorPushed(CallbackInfo ci) {
		GuiStacks.scissorPushed();
	}

	@Inject(method = "disableScissor", at = @At("HEAD"))
	private void diorama$scissorPopped(CallbackInfo ci) {
		GuiStacks.scissorPopped();
	}
}
