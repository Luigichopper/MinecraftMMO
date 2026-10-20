package net.xivmmo.client.target;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.xivmmo.client.camera.MmoCameraController;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages FFXIV-inspired targeting states on the client:
 * - Hard Target (selected via Left-Click or Tab cycling)
 * - Soft Target (cursor hover or temporary target override)
 * - Target-of-Target (synced from server or local Mob target)
 * - Focus Target (pinned secondary target for boss cast / tank monitoring)
 */
public final class ClientTargetManager {
	private static final ClientTargetManager INSTANCE = new ClientTargetManager();

	private int hardTargetId = -1;
	private int softTargetId = -1;
	private int focusTargetId = -1;
	private int lastTabIndex = -1;
	private long frameCounter = 0L;

	/** Server-synced Target-of-Target mapping: entityId -> targetEntityId. */
	private final Map<Integer, Integer> syncedTargetOfTarget = new HashMap<>();

	/** Listener callback notified whenever hardTargetId or focusTargetId changes so networking can sync. */
	private @Nullable TargetSyncCallback syncCallback = null;

	@FunctionalInterface
	public interface TargetSyncCallback {
		void onTargetChanged(int hardTargetId, int focusTargetId);
	}

	private ClientTargetManager() {
	}

	public static ClientTargetManager get() {
		return INSTANCE;
	}

	public void setSyncCallback(@Nullable TargetSyncCallback callback) {
		this.syncCallback = callback;
	}

	/**
	 * Updates per-frame cursor hover soft target and validates existing target references.
	 */
	public void updateFrame() {
		this.frameCounter++;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			clearAll();
			return;
		}

		// Validate Hard Target
		LivingEntity hard = getHardTarget();
		if (hard != null && (!hard.isAlive() || hard.isRemoved()
			|| player.distanceToSqr(hard) > (CursorRaycaster.DEFAULT_MAX_TARGET_RANGE + 10.0) * (CursorRaycaster.DEFAULT_MAX_TARGET_RANGE + 10.0))) {
			setHardTarget(null);
		}

		// Validate Focus Target
		LivingEntity focus = getFocusTarget();
		if (focus != null && (!focus.isAlive() || focus.isRemoved())) {
			setFocusTarget(null);
		}

		// Update Soft Target from cursor hover when no screen is open
		if (mc.gui.screen() == null && mc.gui.overlay() == null) {
			CursorRaycaster.get().updateForFrame(this.frameCounter, CursorRaycaster.DEFAULT_MAX_TARGET_RANGE);
			LivingEntity hovered = CursorRaycaster.get().getHoveredEntity();
			this.softTargetId = hovered != null ? hovered.getId() : -1;
		} else {
			this.softTargetId = -1;
		}
	}

	/**
	 * Handles Left-Click in Free-Cursor MMO Mode: selects the entity under the cursor as Hard Target.
	 * Falls back to the current Soft Target if hovered on this frame.
	 */
	public void onCursorLeftClick(double cursorX, double cursorY) {
		LivingEntity picked = CursorRaycaster.get().pickEntityNow(cursorX, cursorY, CursorRaycaster.DEFAULT_MAX_TARGET_RANGE);
		if (picked == null) {
			picked = getSoftTarget();
		}
		if (picked != null) {
			this.softTargetId = picked.getId();
			setHardTarget(picked);
		}
	}

	/**
	 * Cycles through valid targets in front of the camera (FFXIV-style Tab targeting).
	 * Prioritizes hostile/combat entities in a forward cone within range and Line of Sight,
	 * sorted by angular offset from camera forward + distance.
	 */
	public void cycleTabTarget(boolean reverse) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			return;
		}

		double maxRange = CursorRaycaster.DEFAULT_MAX_TARGET_RANGE;
		AABB searchBox = player.getBoundingBox().inflate(maxRange);
		List<LivingEntity> rawCandidates = level.getEntitiesOfClass(
			LivingEntity.class,
			searchBox,
			e -> e != player && e.isAlive() && e.isPickable() && !e.isSpectator()
		);

		if (rawCandidates.isEmpty()) {
			return;
		}

		float camYaw = MmoCameraController.get().isMmoCameraActive()
			? MmoCameraController.get().getYaw()
			: player.getYRot();
		double rad = Math.toRadians(camYaw);
		Vec3 camForward = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad)).normalize();
		Vec3 playerEye = player.getEyePosition();

		List<LivingEntity> validCandidates = new ArrayList<>();
		for (LivingEntity candidate : rawCandidates) {
			double distSqr = player.distanceToSqr(candidate);
			if (distSqr > maxRange * maxRange) {
				continue;
			}
			if (!player.hasLineOfSight(candidate)) {
				continue;
			}
			Vec3 toTarget = candidate.getEyePosition().subtract(playerEye);
			Vec3 toTargetHoriz = new Vec3(toTarget.x, 0.0, toTarget.z).normalize();
			double dot = camForward.dot(toTargetHoriz);
			// Forward ~130 degree cone (cos(65 deg) ~= 0.42)
			if (dot >= 0.35) {
				validCandidates.add(candidate);
			}
		}

		// Fallback: if nothing in front cone, allow any in-range LOS entity
		if (validCandidates.isEmpty()) {
			for (LivingEntity candidate : rawCandidates) {
				if (player.distanceToSqr(candidate) <= maxRange * maxRange && player.hasLineOfSight(candidate)) {
					validCandidates.add(candidate);
				}
			}
		}

		if (validCandidates.isEmpty()) {
			return;
		}

		// Sort hostile enemies first, then by distance + angle score
		validCandidates.sort(Comparator
			.<LivingEntity, Boolean>comparing(e -> !(e instanceof Enemy))
			.thenComparingDouble(e -> {
				double dist = player.distanceTo(e);
				Vec3 toTarget = e.getEyePosition().subtract(playerEye).normalize();
				double anglePenalty = (1.0 - camForward.dot(new Vec3(toTarget.x, 0.0, toTarget.z).normalize())) * 15.0;
				return dist + anglePenalty;
			})
			.thenComparingInt(Entity::getId));

		int currentIndex = -1;
		for (int i = 0; i < validCandidates.size(); i++) {
			if (validCandidates.get(i).getId() == this.hardTargetId) {
				currentIndex = i;
				break;
			}
		}

		int nextIndex;
		if (currentIndex == -1) {
			nextIndex = 0;
		} else if (reverse) {
			nextIndex = (currentIndex - 1 + validCandidates.size()) % validCandidates.size();
		} else {
			nextIndex = (currentIndex + 1) % validCandidates.size();
		}

		this.lastTabIndex = nextIndex;
		setHardTarget(validCandidates.get(nextIndex));
	}

	/**
	 * Sets or clears the Focus Target from the current Hard Target (or Soft Target if no Hard Target).
	 */
	public void toggleFocusOnCurrentTarget() {
		LivingEntity target = getEffectiveTarget();
		if (target == null) {
			setFocusTarget(null);
		} else if (this.focusTargetId == target.getId()) {
			setFocusTarget(null);
		} else {
			setFocusTarget(target);
		}
	}

	public void setHardTarget(@Nullable LivingEntity entity) {
		int nextId = entity != null ? entity.getId() : -1;
		if (this.hardTargetId != nextId) {
			this.hardTargetId = nextId;
			notifySync();
		}
	}

	public void setFocusTarget(@Nullable LivingEntity entity) {
		int nextId = entity != null ? entity.getId() : -1;
		if (this.focusTargetId != nextId) {
			this.focusTargetId = nextId;
			notifySync();
		}
	}

	public boolean clearHardTargetIfPresent() {
		if (this.hardTargetId != -1) {
			setHardTarget(null);
			return true;
		}
		return false;
	}

	public void clearAll() {
		this.hardTargetId = -1;
		this.softTargetId = -1;
		this.focusTargetId = -1;
		this.syncedTargetOfTarget.clear();
	}

	public @Nullable LivingEntity getHardTarget() {
		return resolveEntity(this.hardTargetId);
	}

	public @Nullable LivingEntity getSoftTarget() {
		return resolveEntity(this.softTargetId);
	}

	public @Nullable LivingEntity getFocusTarget() {
		return resolveEntity(this.focusTargetId);
	}

	/**
	 * Effective target for ability execution: Soft Target (cursor hover) takes priority if present,
	 * otherwise falls back to Hard Target.
	 */
	public @Nullable LivingEntity getEffectiveTarget() {
		LivingEntity soft = getSoftTarget();
		return soft != null ? soft : getHardTarget();
	}

	/**
	 * Resolves the Target-of-Target for the current Hard Target.
	 */
	public @Nullable LivingEntity getTargetOfTarget() {
		LivingEntity hard = getHardTarget();
		if (hard == null) {
			return null;
		}
		Integer syncedId = this.syncedTargetOfTarget.get(hard.getId());
		if (syncedId != null && syncedId != -1) {
			LivingEntity synced = resolveEntity(syncedId);
			if (synced != null) {
				return synced;
			}
		}
		if (hard instanceof Mob mob && mob.getTarget() != null && mob.getTarget().isAlive()) {
			return mob.getTarget();
		}
		return null;
	}

	public void onServerTargetOfTargetSync(int entityId, int targetOfTargetEntityId) {
		if (targetOfTargetEntityId < 0) {
			this.syncedTargetOfTarget.remove(entityId);
		} else {
			this.syncedTargetOfTarget.put(entityId, targetOfTargetEntityId);
		}
	}

	public int getHardTargetId() {
		return this.hardTargetId;
	}

	public int getSoftTargetId() {
		return this.softTargetId;
	}

	public int getFocusTargetId() {
		return this.focusTargetId;
	}

	private @Nullable LivingEntity resolveEntity(int entityId) {
		if (entityId < 0) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		Entity e = mc.level.getEntity(entityId);
		return (e instanceof LivingEntity living && living.isAlive()) ? living : null;
	}

	private void notifySync() {
		if (this.syncCallback != null) {
			this.syncCallback.onTargetChanged(this.hardTargetId, this.focusTargetId);
		}
	}
}
