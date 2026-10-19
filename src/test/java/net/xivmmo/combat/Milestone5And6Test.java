package net.xivmmo.combat;

import net.minecraft.resources.Identifier;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.damage.MmoDamagePipeline;
import net.xivmmo.combat.enmity.EnmityManager;
import net.xivmmo.combat.status.StatusDefinition;
import net.xivmmo.combat.status.StatusManager;
import net.xivmmo.combat.telegraph.TelegraphShapes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Milestone5And6Test {

	@Test
	void testStatusSnapshottingAndFixed60TickCycle() {
		StatusManager manager = new StatusManager();
		Map<Identifier, StatusDefinition> registry = new HashMap<>();

		Identifier surgeBuffId = Identifier.fromNamespaceAndPath("xivmmo", "battle_surge");
		StatusDefinition surgeBuff = new StatusDefinition(
			surgeBuffId,
			"Battle Surge",
			StatusDefinition.StatusType.BUFF,
			200,
			1,
			1.25F, // +25% outgoing damage
			1.0F,
			0,
			0,
			1.0F
		);
		registry.put(surgeBuffId, surgeBuff);

		Identifier dotId = Identifier.fromNamespaceAndPath("xivmmo", "searing_dot");
		StatusDefinition searingDot = new StatusDefinition(
			dotId,
			"Searing Brand",
			StatusDefinition.StatusType.DEBUFF,
			120, // 2 periodic ticks (60, 120)
			1,
			1.0F,
			1.0F,
			50,
			0,
			1.0F
		);
		registry.put(dotId, searingDot);

		UUID caster = UUID.randomUUID();
		UUID enemy = UUID.randomUUID();

		// 1. Apply +25% damage buff to caster
		manager.applyStatus(caster, caster, surgeBuff, 40, registry::get);
		assertEquals(1.25F, manager.computeOutgoingDamageMultiplier(caster, registry::get), 1.0E-4F);

		// 2. Apply DoT to enemy while caster has +25% buff -> DoT snapshots 1.25x
		StatusManager.ActiveStatus appliedDot = manager.applyStatus(enemy, caster, searingDot, 120, registry::get);
		assertEquals(1.25F, appliedDot.snapshottedDealtMultiplier(), 1.0E-4F);

		// 3. Advance 59 ticks: caster's 40-tick buff expires at tick 40, and no periodic tick has fired yet
		for (int i = 1; i <= 59; i++) {
			List<StatusManager.PeriodicTickEvent> events = manager.tick(registry::get);
			assertTrue(events.isEmpty(), "No periodic tick should fire before tick 60");
		}
		assertFalse(manager.hasStatus(caster, surgeBuffId), "Caster buff should have expired at tick 40");
		assertEquals(1.0F, manager.computeOutgoingDamageMultiplier(caster, registry::get), 1.0E-4F);

		// 4. Tick 60: periodic DoT tick fires and MUST retain the 1.25x snapshotted multiplier!
		List<StatusManager.PeriodicTickEvent> tick60Events = manager.tick(registry::get);
		assertEquals(1, tick60Events.size());
		assertEquals(1.25F, tick60Events.getFirst().snapshottedMultiplier(), 1.0E-4F);
		assertEquals(50, tick60Events.getFirst().status().dotPotency());

		// 5. Advance to tick 120: second periodic tick fires and DoT expires cleanly
		for (int i = 61; i <= 119; i++) {
			assertTrue(manager.tick(registry::get).isEmpty());
		}
		List<StatusManager.PeriodicTickEvent> tick120Events = manager.tick(registry::get);
		assertEquals(1, tick120Events.size());
		assertFalse(manager.hasStatus(enemy, dotId), "DoT should expire after 120 ticks");
	}

	@Test
	void testDamagePipelinePotencyAndArmorCoexistence() {
		// Base 100 potency = 4.0 HP at 1.0 attack damage, no buffs, 0 armor
		float base = MmoDamagePipeline.computeDamage(100, 1.0, 1.0F, 1.0F, 0.0, 0.5F);
		assertEquals(4.0F, base, 1.0E-4F);

		// With 20 armor (full diamond) and armorMitigationScale = 0.5 -> 20 * 0.02 * 0.5 = 20% mitigation -> 3.2 HP
		float mitigatedByArmor = MmoDamagePipeline.computeDamage(100, 1.0, 1.0F, 1.0F, 20.0, 0.5F);
		assertEquals(3.2F, mitigatedByArmor, 1.0E-4F);

		// With 1.25x attacker buff and 0.80x defender tank mitigation (1.25 * 0.80 = 1.0)
		float buffedAndMitigated = MmoDamagePipeline.computeDamage(100, 1.0, 1.25F, 0.80F, 0.0, 0.5F);
		assertEquals(4.0F, buffedAndMitigated, 1.0E-4F);

		// Healing potency 300 = 12.0 HP
		assertEquals(12.0F, MmoDamagePipeline.computeHeal(300, 1.0F), 1.0E-4F);
	}

	@Test
	void testEnmityTableProvokeAndShirk() {
		EnmityManager enmity = new EnmityManager();
		UUID boss = UUID.randomUUID();
		UUID dps = UUID.randomUUID();
		UUID healer = UUID.randomUUID();
		UUID tank = UUID.randomUUID();

		// DPS deals 500 enmity, Healer generates 200 healing enmity
		enmity.addEnmity(boss, dps, 500L);
		enmity.addHealingEnmity(healer, dps, 200L);
		assertEquals(dps, enmity.getTopContender(boss));
		assertEquals(200L, enmity.getEnmity(boss, healer));

		// Tank uses Provoke (+1000 over top contender -> 500 + 1000 = 1500)
		long provokedVal = enmity.provoke(boss, tank, 1000L);
		assertEquals(1500L, provokedVal);
		assertEquals(tank, enmity.getTopContender(boss));

		// Tank uses Shirk (25% of 1500 = 375 transferred to DPS)
		long shirked = enmity.shirk(boss, tank, dps, 0.25F);
		assertEquals(375L, shirked);
		assertEquals(1125L, enmity.getEnmity(boss, tank));
		assertEquals(875L, enmity.getEnmity(boss, dps));

		List<EnmityManager.EnmityEntry> rankings = enmity.getSortedEnmityList(boss);
		assertNotNull(rankings);
		assertEquals(3, rankings.size());
		assertEquals(tank, rankings.get(0).contenderUuid());
		assertEquals(1, rankings.get(0).rank());
		assertEquals(1.0F, rankings.get(0).ratioToTop(), 1.0E-4F);
		assertEquals(dps, rankings.get(1).contenderUuid());
		assertEquals(2, rankings.get(1).rank());
	}

	@Test
	void testTelegraphShapesIntersection() {
		// 1. CIRCLE (radius 5 at origin)
		assertTrue(TelegraphShapes.containsPoint(
			AbilityDefinition.AoeShape.CIRCLE, 0, 64, 0, 0.0F, 5.0F, 0.0F, 360.0F,
			3.0, 64.0, 4.0 // dist = 5.0
		));
		assertFalse(TelegraphShapes.containsPoint(
			AbilityDefinition.AoeShape.CIRCLE, 0, 64, 0, 0.0F, 5.0F, 0.0F, 360.0F,
			3.1, 64.0, 4.1 // dist > 5.0
		));

		// 2. DONUT (inner safe hole radius 3.0, outer radius 8.0)
		assertFalse(TelegraphShapes.containsPoint(
			AbilityDefinition.AoeShape.DONUT, 0, 64, 0, 0.0F, 8.0F, 3.0F, 360.0F,
			1.5, 64.0, 1.5 // inside safe inner hole (dist ~ 2.12 < 3.0)
		));
		assertTrue(TelegraphShapes.containsPoint(
			AbilityDefinition.AoeShape.DONUT, 0, 64, 0, 0.0F, 8.0F, 3.0F, 360.0F,
			5.0, 64.0, 0.0 // inside outer ring (3.0 <= 5.0 <= 8.0)
		));

		// 3. CONE (yaw = 0deg -> facing +Z, 90deg cone, radius 6.0)
		assertTrue(TelegraphShapes.containsPoint(
			AbilityDefinition.AoeShape.CONE, 0, 64, 0, 0.0F, 6.0F, 0.0F, 90.0F,
			2.0, 64.0, 4.0 // 26.5 deg off +Z axis, within 45 deg half-angle
		));
		assertFalse(TelegraphShapes.containsPoint(
			AbilityDefinition.AoeShape.CONE, 0, 64, 0, 0.0F, 6.0F, 0.0F, 90.0F,
			4.0, 64.0, 1.0 // ~76 deg off +Z axis, outside 45 deg half-angle
		));

		// 4. LINE (yaw = 0deg -> facing +Z, length = 10.0, halfWidth = 2.0)
		assertTrue(TelegraphShapes.containsPoint(
			AbilityDefinition.AoeShape.LINE, 0, 64, 0, 0.0F, 10.0F, 2.0F, 0.0F,
			1.5, 64.0, 8.5 // forward = 8.5 <= 10.0, lateral = 1.5 <= 2.0
		));
		assertFalse(TelegraphShapes.containsPoint(
			AbilityDefinition.AoeShape.LINE, 0, 64, 0, 0.0F, 10.0F, 2.0F, 0.0F,
			2.5, 64.0, 5.0 // lateral = 2.5 > 2.0
		));
	}
}
