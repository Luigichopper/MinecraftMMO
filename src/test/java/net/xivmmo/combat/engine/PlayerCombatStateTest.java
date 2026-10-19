package net.xivmmo.combat.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.data.MmoDataRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCombatStateTest {
	@Test
	void testGcdAndDoubleWeaveOgcdWithAnimationLock() {
		PlayerCombatState state = new PlayerCombatState();
		AbilityDefinition ironSlash = MmoDataRegistry.getAbility(Identifier.fromNamespaceAndPath("xivmmo", "iron_slash"));
		AbilityDefinition aetherLance = MmoDataRegistry.getAbility(Identifier.fromNamespaceAndPath("xivmmo", "aether_lance"));
		AbilityDefinition vanguardRush = MmoDataRegistry.getAbility(Identifier.fromNamespaceAndPath("xivmmo", "vanguard_rush"));
		assertNotNull(ironSlash);
		assertNotNull(aetherLance);
		assertNotNull(vanguardRush);

		// 1. Trigger GCD (Iron Slash): starts 50-tick GCD and 12-tick animation lock
		PlayerCombatState.AttemptResult r1 = state.tryUseAbility(ironSlash, 1, 10, 0, 0, 0, false);
		assertEquals(PlayerCombatState.AttemptStatus.STARTED_INSTANT, r1.status());
		assertEquals(50, state.getGcdRemainingTicks());
		assertEquals(12, state.getAnimLockRemainingTicks());

		// 2. Immediately pressing oGCD during animation lock (without queueing) is rejected by anim lock
		PlayerCombatState.AttemptResult rEarly = state.tryUseAbility(aetherLance, 2, 10, 0, 0, 0, false);
		assertEquals(PlayerCombatState.AttemptStatus.REJECTED_ANIM_LOCK, rEarly.status());

		// 3. Advance 12 ticks: animation lock clears while GCD still has 38 ticks remaining
		for (int i = 0; i < 12; i++) {
			state.tick(false, MmoDataRegistry::getAbility);
		}
		assertEquals(0, state.getAnimLockRemainingTicks());
		assertEquals(38, state.getGcdRemainingTicks());

		// 4. First oGCD weave (Aether Lance) succeeds during GCD roll!
		PlayerCombatState.AttemptResult rWeave1 = state.tryUseAbility(aetherLance, 3, 10, 0, 0, 0, false);
		assertEquals(PlayerCombatState.AttemptStatus.STARTED_INSTANT, rWeave1.status());
		assertEquals(12, state.getAnimLockRemainingTicks());

		// 5. Advance 12 more ticks (GCD at 26 ticks remaining) and weave second oGCD (Vanguard Rush, 2 charges)
		for (int i = 0; i < 12; i++) {
			state.tick(false, MmoDataRegistry::getAbility);
		}
		PlayerCombatState.AttemptResult rWeave2 = state.tryUseAbility(vanguardRush, 4, 10, 0, 0, 0, false);
		assertEquals(PlayerCombatState.AttemptStatus.STARTED_INSTANT, rWeave2.status());
		assertEquals(1, state.getOrCreateCooldownGroup(vanguardRush).currentCharges());
	}

	@Test
	void testEarlyPressInputQueueWindowExecutesAutomaticallyOnGcdCompletion() {
		PlayerCombatState state = new PlayerCombatState();
		AbilityDefinition ironSlash = MmoDataRegistry.getAbility(Identifier.fromNamespaceAndPath("xivmmo", "iron_slash"));
		AbilityDefinition sunderEdge = MmoDataRegistry.getAbility(Identifier.fromNamespaceAndPath("xivmmo", "sunder_edge"));
		assertNotNull(ironSlash);
		assertNotNull(sunderEdge);

		state.tryUseAbility(ironSlash, 1, 10, 0, 0, 0, true);

		// Advance 42 ticks so 8 ticks remain on GCD (within the 10-tick input queue window)
		for (int i = 0; i < 42; i++) {
			state.tick(false, MmoDataRegistry::getAbility);
		}
		assertEquals(8, state.getGcdRemainingTicks());

		// Pressing Sunder Edge early queues it
		PlayerCombatState.AttemptResult queuedRes = state.tryUseAbility(sunderEdge, 2, 10, 0, 0, 0, true);
		assertEquals(PlayerCombatState.AttemptStatus.QUEUED, queuedRes.status());
		assertNotNull(state.getQueuedAbility());

		// Advance the remaining 8 ticks: on the 8th tick, Sunder Edge pops from queue and executes with combo!
		PlayerCombatState.CompletedExecution autoExecuted = null;
		for (int i = 0; i < 8; i++) {
			PlayerCombatState.TickResult tr = state.tick(false, MmoDataRegistry::getAbility);
			if (!tr.completedExecutions().isEmpty()) {
				autoExecuted = tr.completedExecutions().getFirst();
			}
		}
		assertNotNull(autoExecuted);
		assertEquals(sunderEdge.id(), autoExecuted.ability().id());
		assertTrue(autoExecuted.comboMatched(), "Sunder Edge after Iron Slash should match combo prerequisite");
		assertNull(state.getQueuedAbility());
	}

	@Test
	void testHardCastMovementInterruptionAndSlidecastGraceWindow() {
		PlayerCombatState state = new PlayerCombatState();
		AbilityDefinition astralBolt = MmoDataRegistry.getAbility(Identifier.fromNamespaceAndPath("xivmmo", "astral_bolt"));
		assertNotNull(astralBolt);

		// Start 40-tick (2.0s) hard cast
		PlayerCombatState.AttemptResult castStart = state.tryUseAbility(astralBolt, 1, 10, 0, 0, 0, true);
		assertEquals(PlayerCombatState.AttemptStatus.STARTED_CAST, castStart.status());
		assertNotNull(state.getActiveCast());

		// Moving at tick 5 (35 ticks remaining > 10-tick slidecast window) cancels the cast and resets GCD
		for (int i = 0; i < 5; i++) {
			state.tick(false, MmoDataRegistry::getAbility);
		}
		PlayerCombatState.TickResult interrupted = state.tick(true, MmoDataRegistry::getAbility);
		assertTrue(interrupted.castInterruptedByMovement());
		assertNull(state.getActiveCast());
		assertEquals(0, state.getGcdRemainingTicks());

		// Start cast again and advance 32 ticks (8 ticks remaining <= 10-tick slidecast window)
		state.tryUseAbility(astralBolt, 2, 10, 0, 0, 0, true);
		for (int i = 0; i < 32; i++) {
			state.tick(false, MmoDataRegistry::getAbility);
		}
		// Moving during slidecast window (last 10 ticks) does NOT interrupt the cast!
		PlayerCombatState.CompletedExecution completed = null;
		for (int i = 0; i < 8; i++) {
			PlayerCombatState.TickResult tr = state.tick(true, MmoDataRegistry::getAbility);
			assertFalse(tr.castInterruptedByMovement());
			if (!tr.completedExecutions().isEmpty()) {
				completed = tr.completedExecutions().getFirst();
			}
		}
		assertNotNull(completed);
		assertEquals(astralBolt.id(), completed.ability().id());
		assertEquals(9600, state.getMana());
	}

	@Test
	void testAbilityDefinitionCodecRoundTrip() {
		String jsonText = """
			{
				"id": "xivmmo:test_strike",
				"display_name": "Test Strike",
				"category": "gcd",
				"target_type": "single_enemy",
				"aoe_shape": "cone",
				"range": 5.0,
				"radius": 5.0,
				"cone_angle_degrees": 120.0,
				"cast_ticks": 0,
				"recast_ticks": 50,
				"cooldown_group": "xivmmo:gcd",
				"max_charges": 2,
				"animation_lock_ticks": 12,
				"mana_cost": 200,
				"potency": 250
			}
			""";
		JsonElement json = JsonParser.parseString(jsonText);
		AbilityDefinition parsed = AbilityDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Identifier.fromNamespaceAndPath("xivmmo", "test_strike"), parsed.id());
		assertEquals("Test Strike", parsed.displayName());
		assertEquals(AbilityDefinition.AoeShape.CONE, parsed.aoeShape());
		assertEquals(2, parsed.maxCharges());
		assertEquals(250, parsed.potency());
	}
}
