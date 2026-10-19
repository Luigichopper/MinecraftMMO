package net.xivmmo.combat.enmity;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks FFXIV-inspired Enmity (Hate/Aggro) tables per enemy entity, including:
 * - Damage-based and heal-based enmity generation scaled by Tank Stance multipliers
 * - Provoke (top enmity + bonus)
 * - Shirk (transfer 25% enmity to an ally)
 * - Sorted enmity rankings and relative threat percentage for HUD indicators and AI target selection
 */
public final class EnmityManager {
	private final Map<UUID, Map<UUID, Long>> enemyHateTables = new ConcurrentHashMap<>();

	public record EnmityEntry(UUID contenderUuid, long enmity, int rank, float ratioToTop) {
	}

	/**
	 * Adds enmity for {@code contenderUuid} on {@code enemyUuid}.
	 */
	public long addEnmity(UUID enemyUuid, UUID contenderUuid, long amount) {
		if (amount <= 0) {
			return getEnmity(enemyUuid, contenderUuid);
		}
		Map<UUID, Long> table = this.enemyHateTables.computeIfAbsent(enemyUuid, k -> new HashMap<>());
		long next = Math.max(0L, table.getOrDefault(contenderUuid, 0L) + amount);
		table.put(contenderUuid, next);
		return next;
	}

	/**
	 * Adds healing enmity across all enemies that currently have {@code healedTargetUuid} or {@code healerUuid} on their hate list.
	 */
	public void addHealingEnmity(UUID healerUuid, UUID healedTargetUuid, long healEnmityAmount) {
		if (healEnmityAmount <= 0) {
			return;
		}
		for (Map.Entry<UUID, Map<UUID, Long>> entry : this.enemyHateTables.entrySet()) {
			Map<UUID, Long> table = entry.getValue();
			if (table.containsKey(healedTargetUuid) || table.containsKey(healerUuid)) {
				long next = Math.max(0L, table.getOrDefault(healerUuid, 0L) + healEnmityAmount);
				table.put(healerUuid, next);
			}
		}
	}

	/**
	 * Provoke mechanic: sets {@code tankUuid}'s enmity on {@code enemyUuid} to {@code currentTopEnmity + bonusEnmity}.
	 */
	public long provoke(UUID enemyUuid, UUID tankUuid, long bonusEnmity) {
		Map<UUID, Long> table = this.enemyHateTables.computeIfAbsent(enemyUuid, k -> new HashMap<>());
		long max = 0L;
		for (long val : table.values()) {
			if (val > max) {
				max = val;
			}
		}
		long provoked = max + Math.max(1000L, bonusEnmity);
		table.put(tankUuid, provoked);
		return provoked;
	}

	/**
	 * Shirk mechanic: transfers {@code fraction} (e.g. 0.25 = 25%) of {@code sourceUuid}'s enmity on {@code enemyUuid}
	 * to {@code targetAllyUuid}.
	 */
	public long shirk(UUID enemyUuid, UUID sourceUuid, UUID targetAllyUuid, float fraction) {
		Map<UUID, Long> table = this.enemyHateTables.get(enemyUuid);
		if (table == null) {
			return 0L;
		}
		long current = table.getOrDefault(sourceUuid, 0L);
		long transfer = Math.round(current * Math.clamp(fraction, 0.0F, 1.0F));
		if (transfer <= 0L) {
			return 0L;
		}
		table.put(sourceUuid, current - transfer);
		table.put(targetAllyUuid, table.getOrDefault(targetAllyUuid, 0L) + transfer);
		return transfer;
	}

	public long getEnmity(UUID enemyUuid, UUID contenderUuid) {
		Map<UUID, Long> table = this.enemyHateTables.get(enemyUuid);
		return table != null ? table.getOrDefault(contenderUuid, 0L) : 0L;
	}

	/**
	 * Returns the UUID of the highest-enmity contender for {@code enemyUuid}, or null if the hate table is empty.
	 */
	public @Nullable UUID getTopContender(UUID enemyUuid) {
		Map<UUID, Long> table = this.enemyHateTables.get(enemyUuid);
		if (table == null || table.isEmpty()) {
			return null;
		}
		UUID topUuid = null;
		long topVal = -1L;
		for (Map.Entry<UUID, Long> e : table.entrySet()) {
			if (e.getValue() > topVal) {
				topVal = e.getValue();
				topUuid = e.getKey();
			}
		}
		return topUuid;
	}

	/**
	 * Returns the sorted enmity list for {@code enemyUuid} (Rank 1 = highest enmity).
	 */
	public List<EnmityEntry> getSortedEnmityList(UUID enemyUuid) {
		Map<UUID, Long> table = this.enemyHateTables.get(enemyUuid);
		if (table == null || table.isEmpty()) {
			return Collections.emptyList();
		}
		List<Map.Entry<UUID, Long>> entries = new ArrayList<>(table.entrySet());
		entries.sort(Comparator.<Map.Entry<UUID, Long>>comparingLong(Map.Entry::getValue).reversed());

		long topEnmity = Math.max(1L, entries.getFirst().getValue());
		List<EnmityEntry> result = new ArrayList<>(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			Map.Entry<UUID, Long> e = entries.get(i);
			result.add(new EnmityEntry(
				e.getKey(),
				e.getValue(),
				i + 1,
				Math.clamp((float) e.getValue() / topEnmity, 0.0F, 1.0F)
			));
		}
		return result;
	}

	public void removeContenderEverywhere(UUID contenderUuid) {
		for (Map<UUID, Long> table : this.enemyHateTables.values()) {
			table.remove(contenderUuid);
		}
	}

	public void clearEnemy(UUID enemyUuid) {
		this.enemyHateTables.remove(enemyUuid);
	}
}
