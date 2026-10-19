package net.xivmmo.combat;

import net.minecraft.resources.Identifier;
import net.xivmmo.XivMmoMod;
import net.xivmmo.client.hud.MmoHudManager;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.ability.JobDefinition;
import net.xivmmo.combat.arena.BossArenaManager;
import net.xivmmo.config.MmoConfig;
import net.xivmmo.data.MmoDataRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Milestone7And8Test {

	@Test
	void testRearrangeableHudLayoutAndHotbarRemoval() {
		MmoConfig cfg = MmoConfig.get();
		cfg.resetHudLayoutToDefaults();

		assertTrue(cfg.hideVanillaHotbarInMmoMode, "Vanilla hotbar should be hidden by default in MMO mode");
		assertTrue(cfg.showMmoActionHotbar, "MMO action hotbar should be visible by default");

		// 1. Rearrange and scale the Action Hotbar widget
		MmoConfig.WidgetLayout hotbarLayout = cfg.getWidgetLayout(MmoConfig.HudWidgetId.ACTION_HOTBAR);
		hotbarLayout.normX = 0.25F;
		hotbarLayout.normY = 0.60F;
		hotbarLayout.scale = 1.50F;

		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.ACTION_HOTBAR, 1000, 600);
		assertTrue(rect.enabled());
		assertEquals(1.50F, rect.scale(), 1.0E-4F);
		assertEquals(Math.round(MmoConfig.HudWidgetId.ACTION_HOTBAR.baseWidth() * 1.50F), rect.width());
		assertTrue(rect.contains(rect.x() + 10, rect.y() + 10));

		// 2. Remove/hide the MMO Action Hotbar via master toggle
		cfg.showMmoActionHotbar = false;
		MmoHudManager.WidgetRect hiddenRect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.ACTION_HOTBAR, 1000, 600);
		assertFalse(hiddenRect.enabled(), "Action Hotbar should be disabled when showMmoActionHotbar is false");

		// 3. Reset to defaults
		cfg.resetHudLayoutToDefaults();
		assertTrue(cfg.showMmoActionHotbar);
		assertEquals(1.0F, cfg.getWidgetLayout(MmoConfig.HudWidgetId.ACTION_HOTBAR).scale, 1.0E-4F);
	}

	@Test
	void testFourPlayableJobsAndAbilityKits() {
		MmoDataRegistry.registerBuiltInDefaults();

		JobDefinition vanguard = MmoDataRegistry.getJob(Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard"));
		JobDefinition hierophant = MmoDataRegistry.getJob(Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "hierophant"));
		JobDefinition lancer = MmoDataRegistry.getJob(Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "lancer"));
		JobDefinition arcanist = MmoDataRegistry.getJob(Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "arcanist"));

		assertNotNull(vanguard);
		assertEquals(JobDefinition.Role.TANK, vanguard.role());
		assertEquals(8, vanguard.abilities().size());

		assertNotNull(hierophant);
		assertEquals(JobDefinition.Role.HEALER, hierophant.role());
		assertEquals(8, hierophant.abilities().size());

		assertNotNull(lancer);
		assertEquals(JobDefinition.Role.MELEE_DPS, lancer.role());
		assertEquals(8, lancer.abilities().size());

		assertNotNull(arcanist);
		assertEquals(JobDefinition.Role.RANGED_DPS, arcanist.role());
		assertEquals(8, arcanist.abilities().size());

		// Verify every ability referenced by every job exists in MmoDataRegistry
		for (JobDefinition job : MmoDataRegistry.getAllJobs()) {
			for (Identifier abilityId : job.abilities()) {
				assertNotNull(
					MmoDataRegistry.getAbility(abilityId),
					"Missing ability " + abilityId + " for job " + job.id()
				);
			}
		}
	}

	@Test
	void testBossArenaMechanicRotations() {
		// Arena 1: Sunforge Sanctum (CONE -> CIRCLE -> DONUT)
		BossArenaManager.BossMechanicSpec sun0 = BossArenaManager.selectNextMechanic(BossArenaManager.ARENA_SUNFORGE, 0);
		BossArenaManager.BossMechanicSpec sun1 = BossArenaManager.selectNextMechanic(BossArenaManager.ARENA_SUNFORGE, 1);
		BossArenaManager.BossMechanicSpec sun2 = BossArenaManager.selectNextMechanic(BossArenaManager.ARENA_SUNFORGE, 2);

		assertEquals(AbilityDefinition.AoeShape.CONE, sun0.shape());
		assertEquals(AbilityDefinition.AoeShape.CIRCLE, sun1.shape());
		assertEquals(AbilityDefinition.AoeShape.DONUT, sun2.shape());
		assertTrue(sun2.innerRadius() > 0.0F, "Donut telegraph must have a safe inner radius");

		// Arena 2: Obsidian Spire (LINE -> DONUT -> CONE)
		BossArenaManager.BossMechanicSpec obs0 = BossArenaManager.selectNextMechanic(BossArenaManager.ARENA_OBSIDIAN, 0);
		BossArenaManager.BossMechanicSpec obs1 = BossArenaManager.selectNextMechanic(BossArenaManager.ARENA_OBSIDIAN, 1);
		BossArenaManager.BossMechanicSpec obs2 = BossArenaManager.selectNextMechanic(BossArenaManager.ARENA_OBSIDIAN, 2);

		assertEquals(AbilityDefinition.AoeShape.LINE, obs0.shape());
		assertEquals(AbilityDefinition.AoeShape.DONUT, obs1.shape());
		assertEquals(AbilityDefinition.AoeShape.CONE, obs2.shape());
	}
}
