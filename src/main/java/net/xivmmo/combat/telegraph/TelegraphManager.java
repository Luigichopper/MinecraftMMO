package net.xivmmo.combat.telegraph;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.xivmmo.combat.ability.AbilityDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active timed AoE telegraphs on the server and resolves their completion events.
 */
public final class TelegraphManager {
	private final Map<ResourceKey<Level>, Map<Integer, TelegraphShapes.ActiveTelegraph>> levelTelegraphs = new ConcurrentHashMap<>();
	private int nextTelegraphId = 1;

	public record CompletedTelegraphEvent(
		ResourceKey<Level> dimension,
		TelegraphShapes.ActiveTelegraph telegraph
	) {
	}

	public TelegraphShapes.ActiveTelegraph spawnTelegraph(
		ServerLevel level,
		int sourceEntityId,
		String label,
		AbilityDefinition.AoeShape shape,
		double x, double y, double z,
		float yawDegrees,
		float radius,
		float innerRadius,
		float coneAngleDegrees,
		int durationTicks,
		int potency,
		boolean hostile
	) {
		int id = this.nextTelegraphId++;
		TelegraphShapes.ActiveTelegraph telegraph = new TelegraphShapes.ActiveTelegraph(
			id,
			sourceEntityId,
			label,
			shape,
			x, y, z,
			yawDegrees,
			radius,
			innerRadius,
			coneAngleDegrees,
			0,
			Math.max(10, durationTicks),
			potency,
			hostile
		);
		this.levelTelegraphs
			.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>())
			.put(id, telegraph);
		return telegraph;
	}

	public List<TelegraphShapes.ActiveTelegraph> getActiveTelegraphs(ResourceKey<Level> dimension) {
		Map<Integer, TelegraphShapes.ActiveTelegraph> map = this.levelTelegraphs.get(dimension);
		if (map == null || map.isEmpty()) {
			return Collections.emptyList();
		}
		return List.copyOf(map.values());
	}

	/**
	 * Advances all active telegraphs by 1 tick and returns any that completed on this tick.
	 */
	public List<CompletedTelegraphEvent> tick() {
		List<CompletedTelegraphEvent> completed = new ArrayList<>();
		for (Map.Entry<ResourceKey<Level>, Map<Integer, TelegraphShapes.ActiveTelegraph>> levelEntry : this.levelTelegraphs.entrySet()) {
			ResourceKey<Level> dim = levelEntry.getKey();
			Map<Integer, TelegraphShapes.ActiveTelegraph> map = levelEntry.getValue();
			Iterator<Map.Entry<Integer, TelegraphShapes.ActiveTelegraph>> it = map.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Integer, TelegraphShapes.ActiveTelegraph> entry = it.next();
				TelegraphShapes.ActiveTelegraph current = entry.getValue();
				int nextElapsed = current.elapsedTicks() + 1;
				if (nextElapsed >= current.totalTicks()) {
					completed.add(new CompletedTelegraphEvent(dim, current.withElapsed(nextElapsed)));
					it.remove();
				} else {
					entry.setValue(current.withElapsed(nextElapsed));
				}
			}
		}
		return completed;
	}
}
