package dev.diorama.mixin;

import dev.diorama.addon.AddonView;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link AddonView} when a screen is drawing: an addon's map view draws only from inside a
 * screen's own drawing, where the graphics object it is handed is this frame's. The game draws
 * every screen through this method, on every loader.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
	//? if >=26.1 {
	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
	//?} else if >=1.21.9 {
	/*@Inject(method = "renderWithTooltipAndSubtitles", at = @At("HEAD"))
	*///?} else {
	/*@Inject(method = "renderWithTooltip", at = @At("HEAD"))
	*///?}
	private void diorama$screenDrawStarts(CallbackInfo ci) {
		AddonView.screenDrawStarts();
	}

	//? if >=26.1 {
	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
	//?} else if >=1.21.9 {
	/*@Inject(method = "renderWithTooltipAndSubtitles", at = @At("RETURN"))
	*///?} else {
	/*@Inject(method = "renderWithTooltip", at = @At("RETURN"))
	*///?}
	private void diorama$screenDrawEnds(CallbackInfo ci) {
		AddonView.screenDrawEnds();
	}
}
