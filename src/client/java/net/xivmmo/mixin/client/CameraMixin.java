package net.xivmmo.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.xivmmo.client.camera.MmoCameraController;
import net.xivmmo.config.MmoConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detaches and positions the third-person MMO orbit camera in {@link Camera#alignWithEntity(float)},
 * using vanilla's 8-corner raycast {@link Camera#getMaxZoom(float)} for collision handling.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private Entity entity;

	@Shadow
	private boolean detached;

	@Shadow
	private float eyeHeight;

	@Shadow
	private float eyeHeightOld;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void move(float forwards, float up, float right);

	@Shadow
	protected abstract float getMaxZoom(float cameraDist);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void xivmmo$alignWithEntity(float partialTicks, CallbackInfo ci) {
		MmoCameraController controller = MmoCameraController.get();
		if (this.entity == null || !controller.isMmoCameraActive()) {
			return;
		}
		if (this.entity instanceof LivingEntity living && living.isSleeping()) {
			return;
		}

		MmoConfig cfg = MmoConfig.get();
		double interpX = Mth.lerp(partialTicks, this.entity.xo, this.entity.getX());
		double interpY = Mth.lerp(partialTicks, this.entity.yo, this.entity.getY())
			+ Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight)
			+ cfg.cameraHeightOffset;
		double interpZ = Mth.lerp(partialTicks, this.entity.zo, this.entity.getZ());

		this.detached = true;
		this.setPosition(interpX, interpY, interpZ);
		this.setRotation(controller.getYaw(), controller.getPitch());

		float desiredZoom = controller.getZoomDistance(partialTicks);
		float safeZoom = this.getMaxZoom(desiredZoom);
		this.move(-safeZoom, 0.0F, cfg.cameraShoulderOffset);
	}
}
