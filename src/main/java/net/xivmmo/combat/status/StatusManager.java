package net.xivmmo.combat.status;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Manages active buffs, debuffs, DoTs, and HoTs on entities, including FFXIV-style
 * stat snapshotting at application time and a fixed 60-tick (3.0s) server tick cycle.
 */
public final class StatusManager {
	public static final int SERVER_DOT_HOT_TICK_INTERVAL = 60; // 3.0s fixed tick cycle

	private final Map<UUID, Map<Identifier, ActiveStatus>> entityStatuses = new ConcurrentHashMap<>();
	private int serverTickCounter = 0;

	public record ActiveStatus(
		Identifier statusId,
		@Nullable UUID sourceEntityUuid,
		int remainingTicks,
		int totalTicks,
		int stacks,
		float snapshottedDealtMultiplier
	) {
		public ActiveStatus withRemainingTicks(int nextRemaining) {
			return new ActiveStatus(
				statusId, sourceEntityUuid, nextRemaining, totalTicks, stacks, snapshottedDealtMultiplier
			);
		}
	}

	public record PeriodicTickEvent(
		UUID targetEntityUuid,
		@Nullable UUID sourceEntityUuid,
		StatusDefinition status,
		int stacks,
		float snapshottedMultiplier
	) {
	}

	/**
	 * Applies or refreshes a status effect on {@code targetUuid}, snapshotting the caster's current outgoing
	 * damage multiplier at the moment of application.
	 */
	public ActiveStatus applyStatus(
		UUID targetUuid,
		@Nullable UUID sourceUuid,
		StatusDefinition def,
		int durationTicks,
		Function<Identifier, @Nullable StatusDefinition> statusLookup
	) {
		float casterSnapshot = sourceUuid != null
			? computeOutgoingDamageMultiplier(sourceUuid, statusLookup)
			: 1.0F;

		Map<Identifier, ActiveStatus> activeMap = this.entityStatuses.computeIfAbsent(
			targetUuid,
			k -> new LinkedHashMap<>()
		);

		ActiveStatus existing = activeMap.get(def.id());
		int nextStacks = existing != null ? Math.min(def.maxStacks(), existing.stacks() + 1) : 1;
		int effDuration = durationTicks > 0 ? durationTicks : def.defaultDurationTicks();

		ActiveStatus updated = new ActiveStatus(
			def.id(),
			sourceUuid,
			effDuration,
			effDuration,
			nextStacks,
			casterSnapshot
		);
		activeMap.put(def.id(), updated);
		return updated;
	}

	public boolean removeStatus(UUID targetUuid, Identifier statusId) {
		Map<Identifier, ActiveStatus> map = this.entityStatuses.get(targetUuid);
		if (map != null) {
			return map.remove(statusId) != null;
		}
		return false;
	}

	public void clearEntity(UUID targetUuid) {
		this.entityStatuses.remove(targetUuid);
	}

	public Collection<ActiveStatus> getActiveStatuses(UUID targetUuid) {
		Map<Identifier, ActiveStatus> map = this.entityStatuses.get(targetUuid);
		if (map == null || map.isEmpty()) {
			return Collections.emptyList();
		}
		return List.copyOf(map.values());
	}

	public boolean hasStatus(UUID targetUuid, Identifier statusId) {
		Map<Identifier, ActiveStatus> map = this.entityStatuses.get(targetUuid);
		return map != null && map.containsKey(statusId);
	}

	/**
	 * Computes the multiplicative outgoing damage modifier from all active statuses on {@code entityUuid}.
	 */
	public float computeOutgoingDamageMultiplier(
		UUID entityUuid,
		Function<Identifier, @Nullable StatusDefinition> statusLookup
	) {
		Map<Identifier, ActiveStatus> map = this.entityStatuses.get(entityUuid);
		if (map == null || map.isEmpty()) {
			return 1.0F;
		}
		float mult = 1.0F;
		for (ActiveStatus active : map.values()) {
			StatusDefinition def = statusLookup.apply(active.statusId());
			if (def != null) {
				mult *= def.damageDealtMultiplier();
			}
		}
		return mult;
	}

	/**
	 * Computes the multiplicative incoming damage modifier (mitigation / vulnerability) on {@code entityUuid}.
	 */
	public float computeIncomingDamageMultiplier(
		UUID entityUuid,
		Function<Identifier, @Nullable StatusDefinition> statusLookup
	) {
		Map<Identifier, ActiveStatus> map = this.entityStatuses.get(entityUuid);
		if (map == null || map.isEmpty()) {
			return 1.0F;
		}
		float mult = 1.0F;
		for (ActiveStatus active : map.values()) {
			StatusDefinition def = statusLookup.apply(active.statusId());
			if (def != null) {
				mult *= def.damageTakenMultiplier();
			}
		}
		return mult;
	}

	/**
	 * Computes the multiplicative enmity generation modifier on {@code entityUuid} (e.g. Tank Stance = 10.0x).
	 */
	public float computeEnmityMultiplier(
		UUID entityUuid,
		Function<Identifier, @Nullable StatusDefinition> statusLookup
	) {
		Map<Identifier, ActiveStatus> map = this.entityStatuses.get(entityUuid);
		if (map == null || map.isEmpty()) {
			return 1.0F;
		}
		float mult = 1.0F;
		for (ActiveStatus active : map.values()) {
			StatusDefinition def = statusLookup.apply(active.statusId());
			if (def != null) {
				mult *= def.enmityMultiplier();
			}
		}
		return mult;
	}

	/**
	 * Advances all status durations by 1 tick, and every 60 ticks (3.0s) emits periodic DoT/HoT tick events.
	 */
	public List<PeriodicTickEvent> tick(Function<Identifier, @Nullable StatusDefinition> statusLookup) {
		this.serverTickCounter++;
		boolean isPeriodicTick = (this.serverTickCounter % SERVER_DOT_HOT_TICK_INTERVAL) == 0;
		List<PeriodicTickEvent> events = isPeriodicTick ? new ArrayList<>() : Collections.emptyList();

		Iterator<Map.Entry<UUID, Map<Identifier, ActiveStatus>>> entityIter = this.entityStatuses.entrySet().iterator();
		while (entityIter.hasNext()) {
			Map.Entry<UUID, Map<Identifier, ActiveStatus>> entityEntry = entityIter.next();
			UUID targetUuid = entityEntry.getKey();
			Map<Identifier, ActiveStatus> map = entityEntry.getValue();

			Iterator<Map.Entry<Identifier, ActiveStatus>> statusIter = map.entrySet().iterator();
			while (statusIter.hasNext()) {
				Map.Entry<Identifier, ActiveStatus> statusEntry = statusIter.next();
				ActiveStatus active = statusEntry.getValue();
				StatusDefinition def = statusLookup.apply(active.statusId());

				if (isPeriodicTick && def != null && (def.dotPotency() > 0 || def.hotPotency() > 0)) {
					events.add(new PeriodicTickEvent(
						targetUuid,
						active.sourceEntityUuid(),
						def,
						active.stacks(),
						active.snapshottedDealtMultiplier()
					));
				}

				int nextRem = active.remainingTicks() - 1;
				if (nextRem <= 0) {
					statusIter.remove();
				} else {
					statusEntry.setValue(active.withRemainingTicks(nextRem));
				}
			}

			if (map.isEmpty()) {
				entityIter.remove();
			}
		}

		return events;
	}
}
