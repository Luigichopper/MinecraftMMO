package net.xivmmo.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.xivmmo.client.camera.MmoCameraController;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.config.MmoConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes WASD movement camera-relative in Free-Cursor MMO mode:
 * - While RMB is held (or LMB+RMB), the character faces the camera direction and strafes/backpedals relative to camera.
 * - While RMB is NOT held, the character moves relative to the camera yaw and smoothly turns to face the movement direction.
 */
@Mixin(Entity.class)
public abstract class EntityMoveRelativeMixin {
	@Shadow
	public abstract Vec3 getDeltaMovement();

	@Shadow
	public abstract void setDeltaMovement(Vec3 deltaMovement);

	@Shadow
	protected static Vec3 getInputVector(Vec3 input, float speed, float yRot) {
		throw new AssertionError();
	}

	@Inject(method = "moveRelative", at = @At("HEAD"), cancellable = true)
	private void xivmmo$moveRelative(float speed, Vec3 input, CallbackInfo ci) {
		if (!((Object) this instanceof LocalPlayer player)) {
			return;
		}
		if (!MmoInputHandler.isFreeCursorMode() || !MmoCameraController.get().isMmoCameraActive()) {
			return;
		}

		float camYaw = MmoCameraController.get().getYaw();
		float camPitch = MmoCameraController.get().getPitch();

		if (MmoInputHandler.isRightMouseOrbiting() || MmoInputHandler.isBothMouseHeld()) {
			MmoCameraController.alignPlayerToCamera(player, camYaw, camPitch);
		} else if (input.lengthSqr() > 1.0E-7 && MmoConfig.get().autoFaceMovementDirection && !player.isPassenger()) {
			float targetYaw = MmoCameraController.computeMovementYaw(camYaw, (float) input.z, (float) input.x);
			float newYaw = MmoCameraController.approachYaw(player.getYRot(), targetYaw, 35.0F);
			player.setYRot(newYaw);
			player.setYHeadRot(newYaw);
			player.setYBodyRot(newYaw);
		}

		Vec3 delta = getInputVector(input, speed, camYaw);
		this.setDeltaMovement(this.getDeltaMovement().add(delta));
		ci.cancel();
	}
}
