package net.xivmmo.client.target;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Unprojects the 2D screen cursor through the active Camera projection and view-rotation matrices
 * into a 3D world-space ray, and resolves entity and ground hits with range and LOS checks.
 *
 * <p>Performance budget: Zero per-frame heap allocations on the matrix unprojection path;
 * pick results are cached once per client render frame.
 */
public final class CursorRaycaster {
	public static final double DEFAULT_MAX_TARGET_RANGE = 30.0;
	public static final double ENTITY_PICK_INFLATION = 0.35;

	private static final CursorRaycaster INSTANCE = new CursorRaycaster();

	// Pre-allocated JOML scratch objects (render-thread confined, zero per-frame allocation)
	private final Matrix4f scratchViewRotProj = new Matrix4f();
	private final Matrix4f scratchInvViewRotProj = new Matrix4f();
	private final Vector3f scratchNearPoint = new Vector3f();
	private final Vector3f scratchFarPoint = new Vector3f();
	private final Vector3f scratchRayDir = new Vector3f();

	private long cachedFrameId = -1L;
	private double cachedCursorX = -1.0;
	private double cachedCursorY = -1.0;
	private @Nullable LivingEntity cachedHoveredEntity = null;
	private @Nullable Vec3 cachedGroundHitPos = null;
	private Vec3 cachedRayOrigin = Vec3.ZERO;
	private Vec3 cachedRayDirection = new Vec3(0.0, 0.0, 1.0);

	private CursorRaycaster() {
	}

	public static CursorRaycaster get() {
		return INSTANCE;
	}

	/**
	 * Pure math helper: unprojects screen-space cursor coordinates {@code (cursorX, cursorY)}
	 * through the combined {@code viewRotationProjectionMatrix} into a normalized world-space ray direction.
	 *
	 * @param viewRotProj  Camera's combined projection * viewRotation matrix
	 * @param cursorX      cursor X in screen pixels [0, screenWidth]
	 * @param cursorY      cursor Y in screen pixels [0, screenHeight]
	 * @param screenWidth  viewport width in pixels
	 * @param screenHeight viewport height in pixels
	 * @param invScratch   scratch Matrix4f for inversion
	 * @param nearScratch  scratch Vector3f
	 * @param farScratch   scratch Vector3f
	 * @param outDir       output normalized ray direction in world space
	 * @return {@code outDir}
	 */
	public static Vector3f unprojectToRayDirection(
		Matrix4fc viewRotProj,
		float cursorX,
		float cursorY,
		float screenWidth,
		float screenHeight,
		Matrix4f invScratch,
		Vector3f nearScratch,
		Vector3f farScratch,
		Vector3f outDir
	) {
		if (screenWidth <= 0.0F || screenHeight <= 0.0F) {
			return outDir.set(0.0F, 0.0F, -1.0F);
		}
		float ndcX = (2.0F * cursorX / screenWidth) - 1.0F;
		float ndcY = 1.0F - (2.0F * cursorY / screenHeight);

		invScratch.set(viewRotProj).invert();
		invScratch.transformProject(ndcX, ndcY, 0.25F, nearScratch);
		invScratch.transformProject(ndcX, ndcY, 0.75F, farScratch);

		// Support both Standard-Z (near at smaller ndcZ, far at larger ndcZ) and
		// Minecraft 26.2 Reverse-Z (near at larger ndcZ, far at smaller ndcZ):
		// Because viewRotation is an orthogonal rotation around the camera origin (0, 0, 0),
		// the unprojected point closer to the far plane always has a larger squared distance from the origin.
		if (nearScratch.lengthSquared() > farScratch.lengthSquared()) {
			outDir.set(nearScratch).sub(farScratch);
		} else {
			outDir.set(farScratch).sub(nearScratch);
		}
		if (outDir.lengthSquared() < 1.0E-12F) {
			return outDir.set(0.0F, 0.0F, -1.0F);
		}
		return outDir.normalize();
	}

	/**
	 * Pure math slab intersection test between a 3D ray {@code origin + t * dir} (with {@code dir} normalized)
	 * and an axis-aligned bounding box {@code [minX..maxX, minY..maxY, minZ..maxZ]}.
	 *
	 * @return distance {@code t >= 0} along the ray to the entry intersection point, or {@code -1.0} if no hit within {@code maxDistance}.
	 */
	public static double intersectRayAabb(
		double origX, double origY, double origZ,
		double dirX, double dirY, double dirZ,
		double minX, double minY, double minZ,
		double maxX, double maxY, double maxZ,
		double maxDistance
	) {
		double tMin = 0.0;
		double tMax = maxDistance;

		// X slab
		if (Math.abs(dirX) < 1.0E-9) {
			if (origX < minX || origX > maxX) {
				return -1.0;
			}
		} else {
			double invD = 1.0 / dirX;
			double t0 = (minX - origX) * invD;
			double t1 = (maxX - origX) * invD;
			if (t0 > t1) {
				double tmp = t0;
				t0 = t1;
				t1 = tmp;
			}
			tMin = Math.max(tMin, t0);
			tMax = Math.min(tMax, t1);
			if (tMin > tMax) {
				return -1.0;
			}
		}

		// Y slab
		if (Math.abs(dirY) < 1.0E-9) {
			if (origY < minY || origY > maxY) {
				return -1.0;
			}
		} else {
			double invD = 1.0 / dirY;
			double t0 = (minY - origY) * invD;
			double t1 = (maxY - origY) * invD;
			if (t0 > t1) {
				double tmp = t0;
				t0 = t1;
				t1 = tmp;
			}
			tMin = Math.max(tMin, t0);
			tMax = Math.min(tMax, t1);
			if (tMin > tMax) {
				return -1.0;
			}
		}

		// Z slab
		if (Math.abs(dirZ) < 1.0E-9) {
			if (origZ < minZ || origZ > maxZ) {
				return -1.0;
			}
		} else {
			double invD = 1.0 / dirZ;
			double t0 = (minZ - origZ) * invD;
			double t1 = (maxZ - origZ) * invD;
			if (t0 > t1) {
				double tmp = t0;
				t0 = t1;
				t1 = tmp;
			}
			tMin = Math.max(tMin, t0);
			tMax = Math.min(tMax, t1);
			if (tMin > tMax) {
				return -1.0;
			}
		}

		return tMin <= maxDistance ? tMin : -1.0;
	}

	/**
	 * Updates the cached cursor pick for the given frame ID if not already computed this frame.
	 */
	public void updateForFrame(long frameId, double maxPlayerRange) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player == null || level == null) {
			this.cachedHoveredEntity = null;
			this.cachedGroundHitPos = null;
			return;
		}

		double cursorX = mc.mouseHandler.xpos();
		double cursorY = mc.mouseHandler.ypos();
		if (this.cachedFrameId == frameId
			&& Math.abs(this.cachedCursorX - cursorX) < 0.01
			&& Math.abs(this.cachedCursorY - cursorY) < 0.01) {
			net.xivmmo.debug.MmoDebugState.recordClientRaycast(true);
			return;
		}

		net.xivmmo.debug.MmoDebugState.recordClientRaycast(false);
		this.cachedFrameId = frameId;
		this.cachedCursorX = cursorX;
		this.cachedCursorY = cursorY;
		performPick(mc, player, level, cursorX, cursorY, maxPlayerRange);
	}

	/**
	 * Forces an immediate pick at the given screen coordinates (e.g. on Left-Click).
	 */
	public @Nullable LivingEntity pickEntityNow(double cursorX, double cursorY, double maxPlayerRange) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player == null || level == null) {
			return null;
		}
		performPick(mc, player, level, cursorX, cursorY, maxPlayerRange);
		return this.cachedHoveredEntity;
	}

	private void performPick(
		Minecraft mc,
		LocalPlayer player,
		Level level,
		double cursorX,
		double cursorY,
		double maxPlayerRange
	) {
		Camera camera = mc.gameRenderer.mainCamera();
		if (!camera.isInitialized()) {
			this.cachedHoveredEntity = null;
			this.cachedGroundHitPos = null;
			return;
		}

		Window window = mc.getWindow();
		float screenW = (float) window.getScreenWidth();
		float screenH = (float) window.getScreenHeight();
		camera.getViewRotationProjectionMatrix(this.scratchViewRotProj);
		unprojectToRayDirection(
			this.scratchViewRotProj,
			(float) cursorX,
			(float) cursorY,
			screenW,
			screenH,
			this.scratchInvViewRotProj,
			this.scratchNearPoint,
			this.scratchFarPoint,
			this.scratchRayDir
		);
		if (this.scratchRayDir.dot(camera.forwardVector()) <= 0.0F) {
			this.scratchRayDir.negate();
		}

		Vec3 rayOrigin = camera.position();
		Vec3 rayDir = new Vec3(this.scratchRayDir.x, this.scratchRayDir.y, this.scratchRayDir.z);
		this.cachedRayOrigin = rayOrigin;
		this.cachedRayDirection = rayDir;

		double camToPlayerDist = rayOrigin.distanceTo(player.getEyePosition());
		double maxRayDist = maxPlayerRange + camToPlayerDist + 4.0;
		Vec3 rayEnd = rayOrigin.add(rayDir.scale(maxRayDist));

		// 1. Block raycast along cursor ray (determines both ground AoE point and block occlusion distance)
		BlockHitResult blockHit = level.clip(new ClipContext(
			rayOrigin,
			rayEnd,
			ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE,
			player
		));
		double maxUnoccludedRayDist = blockHit.getType() != HitResult.Type.MISS
			? rayOrigin.distanceTo(blockHit.getLocation())
			: maxRayDist;

		if (blockHit.getType() != HitResult.Type.MISS
			&& player.position().distanceToSqr(blockHit.getLocation()) <= maxPlayerRange * maxPlayerRange) {
			this.cachedGroundHitPos = blockHit.getLocation();
		} else {
			this.cachedGroundHitPos = null;
		}

		// 2. Entity ray-vs-AABB intersection within unoccluded ray distance and player max range.
		// Allow a small margin (+0.65m) past the ground hit so clicking near a mob's feet/ring on the floor is never occluded by the floor itself.
		double maxEntityRayDist = Math.min(maxRayDist, maxUnoccludedRayDist + 0.65);
		AABB searchBox = new AABB(rayOrigin, rayOrigin.add(rayDir.scale(maxEntityRayDist))).inflate(1.5);
		List<LivingEntity> candidates = level.getEntitiesOfClass(
			LivingEntity.class,
			searchBox,
			entity -> entity != player && entity.isAlive() && !entity.isSpectator()
		);

		LivingEntity closestEntity = null;
		double closestRayT = maxEntityRayDist;
		double maxRangeSqr = maxPlayerRange * maxPlayerRange;

		for (int i = 0, size = candidates.size(); i < size; i++) {
			LivingEntity candidate = candidates.get(i);
			if (player.distanceToSqr(candidate) > maxRangeSqr) {
				continue;
			}
			double inflate = candidate.getPickRadius() + ENTITY_PICK_INFLATION;
			AABB box = candidate.getBoundingBox().inflate(inflate);
			double t = intersectRayAabb(
				rayOrigin.x, rayOrigin.y, rayOrigin.z,
				rayDir.x, rayDir.y, rayDir.z,
				box.minX, box.minY, box.minZ,
				box.maxX, box.maxY, box.maxZ,
				closestRayT
			);
			if (t >= 0.0 && t < closestRayT) {
				closestRayT = t;
				closestEntity = candidate;
			}
		}

		this.cachedHoveredEntity = closestEntity;
	}

	public @Nullable LivingEntity getHoveredEntity() {
		return this.cachedHoveredEntity;
	}

	public @Nullable Vec3 getGroundHitPos() {
		return this.cachedGroundHitPos;
	}

	public Vec3 getLastRayOrigin() {
		return this.cachedRayOrigin;
	}

	public Vec3 getLastRayDirection() {
		return this.cachedRayDirection;
	}
}
