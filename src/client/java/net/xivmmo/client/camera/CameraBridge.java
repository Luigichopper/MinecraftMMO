package net.xivmmo.client.camera;

import net.minecraft.world.entity.Entity;

/**
 * Abstraction layer for the MMO third-person camera controller.
 */
public interface CameraBridge {
	/**
	 * Returns true if the detached MMO orbit camera should currently control the view.
	 */
	boolean isMmoCameraActive();

	/**
	 * Current horizontal camera yaw in degrees [-180, 180).
	 */
	float getYaw();

	/**
	 * Current vertical camera pitch in degrees [-85, 85].
	 */
	float getPitch();

	/**
	 * Current interpolated zoom distance in blocks for the given frame partial tick.
	 */
	float getZoomDistance(float partialTick);

	/**
	 *Target (un-interpolated) zoom distance in blocks.
	 */
	float getTargetZoomDistance();

	/**
	 * Applies mouse orbit delta in degrees.
	 */
	void onOrbitDelta(double deltaYawDegrees, double deltaPitchDegrees);

	/**
	 * Applies scroll wheel delta to adjust target zoom distance.
	 */
	void onScrollZoom(double scrollDelta);

	/**
	 * Advances per-tick zoom smoothing and state updates.
	 */
	void tick();

	/**
	 * Snaps the orbit camera behind the given entity's current facing direction.
	 */
	void syncBehindEntity(Entity entity);
}
