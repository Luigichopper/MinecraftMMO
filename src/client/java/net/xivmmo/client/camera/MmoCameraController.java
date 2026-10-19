package net.xivmmo.client.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.config.MmoConfig;

/**
 * In-house FFXIV-style detached orbit camera controller.
 * Supports free orbit yaw/pitch, smooth zoom interpolation, RMB character yaw alignment,
 * and camera-relative movement vector math.
 */
public final class MmoCameraController implements CameraBridge {
	public static final float MIN_PITCH = -85.0F;
	public static final float MAX_PITCH = 85.0F;

	private static final MmoCameraController INSTANCE = new MmoCameraController();

	private float orbitYaw = 0.0F;
	private float orbitPitch = 18.0F;
	private float targetZoom = 6.0F;
	private float currentZoom = 6.0F;
	private float prevZoom = 6.0F;
	private boolean initializedForEntity = false;

	private MmoCameraController() {
		MmoConfig cfg = MmoConfig.get();
		this.targetZoom = clampZoom(cfg.defaultZoomDistance, cfg.minZoomDistance, cfg.maxZoomDistance);
		this.currentZoom = this.targetZoom;
		this.prevZoom = this.targetZoom;
	}

	public static MmoCameraController get() {
		return INSTANCE;
	}

	/**
	 * Pure math helper: clamps pitch to [-85, 85] degrees so the camera never flips over the poles.
	 */
	public static float clampPitch(float pitchDegrees) {
		return Mth.clamp(pitchDegrees, MIN_PITCH, MAX_PITCH);
	}

	/**
	 * Pure math helper: wraps yaw to [-180, 180) degrees.
	 */
	public static float wrapYaw(float yawDegrees) {
		return Mth.wrapDegrees(yawDegrees);
	}

	/**
	 * Pure math helper: clamps zoom distance to [minZoom, maxZoom].
	 */
	public static float clampZoom(float zoom, float minZoom, float maxZoom) {
		float safeMin = Math.max(0.5F, Math.min(minZoom, maxZoom));
		float safeMax = Math.max(safeMin, maxZoom);
		return Mth.clamp(zoom, safeMin, safeMax);
	}

	/**
	 * Pure math helper: computes the world yaw (in degrees) corresponding to a camera-relative
	 * movement vector {@code (leftImpulse, forwardImpulse)}.
	 *
	 * @param cameraYaw      current camera yaw in degrees
	 * @param forwardImpulse +1 for W (forward), -1 for S (backward)
	 * @param leftImpulse    +1 for A (strafe left), -1 for D (strafe right)
	 * @return target entity yaw in degrees wrapped to [-180, 180)
	 */
	public static float computeMovementYaw(float cameraYaw, float forwardImpulse, float leftImpulse) {
		if (Math.abs(forwardImpulse) < 1.0E-4F && Math.abs(leftImpulse) < 1.0E-4F) {
			return wrapYaw(cameraYaw);
		}
		// In Minecraft coordinates: +Z of input is forward, +X of input is left.
		// Facing straight along cameraYaw corresponds to (forward = +1, left = 0) -> offset 0 deg.
		// Strafing left (forward = 0, left = +1) corresponds to cameraYaw - 90 deg.
		float relativeAngleDeg = (float) Math.toDegrees(Math.atan2(-leftImpulse, forwardImpulse));
		return wrapYaw(cameraYaw + relativeAngleDeg);
	}

	/**
	 * Pure math helper: smoothly steps current angle toward target angle by at most maxTurnStepDegrees.
	 */
	public static float approachYaw(float currentYaw, float targetYaw, float maxTurnStepDegrees) {
		float delta = Mth.wrapDegrees(targetYaw - currentYaw);
		float step = Mth.clamp(delta, -maxTurnStepDegrees, maxTurnStepDegrees);
		return wrapYaw(currentYaw + step);
	}

	@Override
	public boolean isMmoCameraActive() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return false;
		}
		if (!MmoInputHandler.isMmoModeActive()) {
			return false;
		}
		// In Build Mode, respect vanilla's F5 camera perspective if the user wants first-person precision.
		if (MmoInputHandler.isBuildModeActive() && mc.options.getCameraType().isFirstPerson()) {
			return false;
		}
		return true;
	}

	@Override
	public float getYaw() {
		return this.orbitYaw;
	}

	@Override
	public float getPitch() {
		return this.orbitPitch;
	}

	@Override
	public float getZoomDistance(float partialTick) {
		return Mth.lerp(partialTick, this.prevZoom, this.currentZoom);
	}

	@Override
	public float getTargetZoomDistance() {
		return this.targetZoom;
	}

	public void setOrbitAngles(float yaw, float pitch) {
		this.orbitYaw = wrapYaw(yaw);
		this.orbitPitch = clampPitch(pitch);
	}

	public void setTargetZoom(float zoom) {
		MmoConfig cfg = MmoConfig.get();
		this.targetZoom = clampZoom(zoom, cfg.minZoomDistance, cfg.maxZoomDistance);
	}

	@Override
	public void onOrbitDelta(double deltaYawDegrees, double deltaPitchDegrees) {
		MmoConfig cfg = MmoConfig.get();
		float sens = cfg.orbitSensitivity;
		this.orbitYaw = wrapYaw(this.orbitYaw + (float) deltaYawDegrees * sens);
		this.orbitPitch = clampPitch(this.orbitPitch + (float) deltaPitchDegrees * sens);

		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player != null && (MmoInputHandler.isRightMouseOrbiting() || MmoInputHandler.isBothMouseHeld())) {
			alignPlayerToCamera(player, this.orbitYaw, this.orbitPitch);
		}
	}

	public static void alignPlayerToCamera(LocalPlayer player, float camYaw, float camPitch) {
		player.setYRot(camYaw);
		player.yRotO = camYaw;
		player.setYHeadRot(camYaw);
		player.yHeadRotO = camYaw;
		player.setYBodyRot(camYaw);
		player.yBodyRotO = camYaw;
		player.setXRot(clampPitch(camPitch * 0.5F));
		player.xRotO = player.getXRot();
	}

	@Override
	public void onScrollZoom(double scrollDelta) {
		if (Math.abs(scrollDelta) < 1.0E-5) {
			return;
		}
		MmoConfig cfg = MmoConfig.get();
		float nextTarget = this.targetZoom - (float) scrollDelta * cfg.zoomStep;
		this.targetZoom = clampZoom(nextTarget, cfg.minZoomDistance, cfg.maxZoomDistance);
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			this.initializedForEntity = false;
			return;
		}
		if (!this.initializedForEntity) {
			syncBehindEntity(mc.player);
			this.initializedForEntity = true;
		}

		MmoConfig cfg = MmoConfig.get();
		this.targetZoom = clampZoom(this.targetZoom, cfg.minZoomDistance, cfg.maxZoomDistance);
		this.prevZoom = this.currentZoom;
		float smoothing = Mth.clamp(cfg.zoomSmoothing, 0.05F, 1.0F);
		this.currentZoom = Mth.lerp(smoothing, this.currentZoom, this.targetZoom);

		if (MmoInputHandler.isRightMouseOrbiting() || MmoInputHandler.isBothMouseHeld()) {
			alignPlayerToCamera(mc.player, this.orbitYaw, this.orbitPitch);
		}
	}

	@Override
	public void syncBehindEntity(Entity entity) {
		if (entity != null) {
			this.orbitYaw = wrapYaw(entity.getYRot());
			this.orbitPitch = 18.0F;
		}
	}
}
