package dev.diorama.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.diorama.waypoint.WorldProjection;
import net.minecraft.client.renderer.GameRenderer;
//? if >=26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
//?} else {
/*import net.minecraft.client.Camera;
import org.joml.Quaternionf;
*///?}
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the projection the world is drawn with, after view bobbing, the damage tilt and
 * nausea are applied, so waypoint labels can stay pinned to their spot in the world. It has to be
 * this frame's by the time the waypoints are laid out, or they trail a turning camera by a frame
 * (#399). On Fabric before 1.21.6 it also draws those waypoints, before the rest of the GUI.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	// The GPU bake experiment draws its tiles before the frame opens any pass, and a finished tile
	// goes up before the GUI is drawn. Taking one can free a texture the tile had, such as its night
	// light: the GUI keeps what it's handed until the end of the frame, so a frame mustn't take
	// tiles after it has started drawing it. From 26.1 the GUI is handed its draws in extract,
	// before render; until then, at the start of render.
	//? if >=26.1 {
	@Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	//?} else if >=1.21 {
	/*@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	*///?} else {
	/*@Inject(method = "render(FJZ)V", at = @At("HEAD"))
	*///?}
	private void diorama$gpuBakes(CallbackInfo ci) {
		boolean baked = dev.diorama.render.gpu.GpuTileBaker.frame();
		dev.diorama.MapSession.frame(baked);
		dev.diorama.addon.AddonView.frame();
	}

	//? if >=26.1 {
	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Shadow
	private void bobHurt(CameraRenderState cameraState, PoseStack poseStack) {
	}

	@Shadow
	private void bobView(CameraRenderState cameraState, PoseStack poseStack) {
	}

	//? if <26.3 {
	/*@Shadow
	@Final
	private net.minecraft.client.Minecraft minecraft;
	@Shadow
	private float spinningEffectTime;
	@Shadow
	private float spinningEffectSpeed;
	*///?}

	// From 26.1 the GUI is laid out in extract, before renderLevel draws the world, so renderLevel's
	// projection would reach the waypoints a frame late. The frame's camera is already extracted
	// when the GUI's turn comes, so the projection is worked out here as renderLevel will from the
	// same state: the camera's, then the damage tilt and view bobbing, then nausea's skew.
	//? if >=26.2 {
	@Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V"))
	//?} else {
	/*@Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extractGui(Lnet/minecraft/client/DeltaTracker;ZZ)V"))
	*///?}
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(argsOnly = true) net.minecraft.client.DeltaTracker deltaTracker) {
		CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
		// Not before a world's camera has been extracted. A frame that doesn't draw the world
		// doesn't lay out the HUD either, so what's worked out from its left-over camera goes unused.
		if (!cameraState.initialized) {
			return;
		}
		Matrix4f projectionMatrix = new Matrix4f(cameraState.projectionMatrix);
		PoseStack bobStack = new PoseStack();
		this.bobHurt(cameraState, bobStack);
		if (this.gameRenderState.optionsRenderState.bobView) {
			this.bobView(cameraState, bobStack);
		}
		projectionMatrix.mul(bobStack.last().pose());
		//? if >=26.3 {
		var playerState = this.gameRenderState.levelRenderState.playerRenderState;
		float portal = playerState.portalEffectIntensity;
		float nausea = playerState.nauseaEffectIntensity;
		float spinningAngle = playerState.spinningEffectAngle;
		//?} else {
		/*var player = this.minecraft.player;
		float worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
		float portal = player == null ? 0 : net.minecraft.util.Mth.lerp(worldPartialTicks, player.oPortalEffectIntensity, player.portalEffectIntensity);
		float nausea = player == null ? 0 : player.getEffectBlendFactor(net.minecraft.world.effect.MobEffects.NAUSEA, worldPartialTicks);
		float spinningAngle = this.spinningEffectTime + worldPartialTicks * this.spinningEffectSpeed;
		*///?}
		float screenEffectScale = this.gameRenderState.optionsRenderState.screenEffectScale;
		float spinning = Math.max(portal, nausea) * screenEffectScale * screenEffectScale;
		if (spinning > 0) {
			float skew = 5.0F / (spinning * spinning + 5.0F) - spinning * 0.04F;
			skew *= skew;
			Vector3f axis = new Vector3f(0, net.minecraft.util.Mth.SQRT_OF_TWO / 2, net.minecraft.util.Mth.SQRT_OF_TWO / 2);
			float angle = spinningAngle * (float) (Math.PI / 180.0);
			projectionMatrix.rotate(angle, axis);
			projectionMatrix.scale(1 / skew, 1, 1);
			projectionMatrix.rotate(-angle, axis);
		}
		WorldProjection.capture(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
	}
	//?} else if >=1.21.9 {
	/*// renderLevel sets the projection twice here: first for the world, then for the hand.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix) {
		// The view rotation renderLevel builds next, for the level renderer.
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.position());
	}
	*///?} else if >=1.21.6 {
	/*// As above, but the culling projection is made before the world's is set, so the world's
	// is picked as the first Matrix4f local.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.position());
	}
	*///?} else if >=1.21.2 {
	/*// As above; the camera's position had a getter.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.getPosition());
	}
	*///?} else if >=1.21 {
	/*// renderLevel set the world's projection through resetProjectionMatrix, and it was the only
	// Matrix4f local at that point.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.getPosition());
	}
	*///?} else {
	/*// 1.20.1 sets the camera up after the world's projection, so this waits for the level
	// renderer's call: by then the camera has this frame's rotation, where the projection's call
	// would still see the last frame's. The projection is the first Matrix4f local there too. The
	// view rotation is the pose stack renderLevel turned by the camera's pitch and yaw, as the
	// level renderer gets it: 1.20.1's camera quaternion doesn't turn the same way as later ones'.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix, @Local(argsOnly = true) com.mojang.blaze3d.vertex.PoseStack view) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f(view.last().pose()), camera.getPosition());
	}
	*///?}

	// Waypoints in the world go under the whole GUI on Fabric before 1.21.6 and on Forge too, as
	// addFirst from 1.21.6 (and NeoForge's registerBelowAll) puts them. Fabric API's HUD layers and
	// Forge's GUI overlays there come after the nausea overlay and the totem animation, which this
	// frame draws before the HUD, so they're drawn as the frame starts its GUI. The GUI depth-tests
	// and vanilla draws some of it below zero (1.20.1's hotbar at -90), so the waypoints go further
	// down than any of it: 5000 down, or 900 on Forge, whose GUI reaches only 1000 below zero
	// (ForgeHooksClient.getGuiFarPlane) where vanilla's reaches 10000.
	//? if fabric && >=1.21 && <1.21.6 {
	/*@org.spongepowered.asm.mixin.Unique
	private static final dev.diorama.waypoint.WaypointHud diorama$waypoints = new dev.diorama.waypoint.WaypointHud();

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=gui"))
	private void diorama$waypointsUnderGui(CallbackInfo ci, @Local net.minecraft.client.gui.GuiGraphicsExtractor graphics, @Local(argsOnly = true) net.minecraft.client.DeltaTracker deltaTracker) {
		var pose = graphics.pose();
		pose.pushPose();
		pose.translate(0, 0, -5000);
		diorama$waypoints.extractRenderState(graphics, deltaTracker);
		pose.popPose();
	}
	*///?} else if fabric && <1.21 {
	/*@org.spongepowered.asm.mixin.Unique
	private static final dev.diorama.waypoint.WaypointHud diorama$waypoints = new dev.diorama.waypoint.WaypointHud();

	@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=gui"))
	private void diorama$waypointsUnderGui(CallbackInfo ci, @Local net.minecraft.client.gui.GuiGraphicsExtractor graphics, @Local(argsOnly = true, ordinal = 0) float partialTick) {
		var pose = graphics.pose();
		pose.pushPose();
		pose.translate(0, 0, -5000);
		diorama$waypoints.extractRenderState(graphics, partialTick);
		pose.popPose();
	}
	*///?} else if forge {
	/*@org.spongepowered.asm.mixin.Unique
	private static final dev.diorama.waypoint.WaypointHud diorama$waypoints = new dev.diorama.waypoint.WaypointHud();

	@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=gui"))
	private void diorama$waypointsUnderGui(CallbackInfo ci, @Local net.minecraft.client.gui.GuiGraphicsExtractor graphics, @Local(argsOnly = true, ordinal = 0) float partialTick) {
		var pose = graphics.pose();
		pose.pushPose();
		pose.translate(0, 0, -900);
		diorama$waypoints.extractRenderState(graphics, partialTick);
		pose.popPose();
	}
	*///?}
}
