package dev.diorama.mixin;

import dev.diorama.MinimapHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the minimap where the game drew each boss bar at the top of the screen (a wither, the
 * dragon, a raid, a server's own bars), so it moves below the lowest one while it would be over
 * them. Taken from the drawing itself, as Xaero's Minimap does, so bars a mod hides, moves or
 * spaces differently are followed as drawn: the game stops adding bars a third of the way down the
 * screen, and NeoForge and Forge let mods change each bar's height or leave one out.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossBarMixin {
	// The overload that draws one whole bar; it calls the other for the bar's background and its fill.
	//? if >=26.1 {
	@Inject(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V", at = @At("HEAD"))
	//?} else {
	/*@Inject(method = "drawBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V", at = @At("HEAD"))
	*///?}
	private void diorama$bossBarDrawn(GuiGraphicsExtractor graphics, int x, int y, BossEvent event, CallbackInfo ci) {
		MinimapHud.bossBarDrawn(y);
	}
}
