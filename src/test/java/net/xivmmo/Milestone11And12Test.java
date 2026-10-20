package net.xivmmo;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.inventory.MmoInventoryState;
import net.xivmmo.progression.JobProgressionState;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class Milestone11And12Test {

	@Test
	void testFfxivExpCurveAndIndependentJobLeveling() {
		JobProgressionState state = new JobProgressionState();
		Identifier vanguard = Identifier.fromNamespaceAndPath("xivmmo", "vanguard");
		Identifier lancer = Identifier.fromNamespaceAndPath("xivmmo", "lancer");

		assertEquals(1, state.getLevel(vanguard));
		assertEquals(1, state.getLevel(lancer));
		assertEquals(307L, JobProgressionState.getExpForNextLevel(1));

		// Award 357 EXP to Vanguard -> levels up to Lv. 2 with 50 overflow EXP
		JobProgressionState.ExpGainResult r1 = state.awardExp(vanguard, 357L, 25L, 50_000L);
		assertTrue(r1.leveledUp());
		assertEquals(1, r1.oldLevel());
		assertEquals(2, r1.newLevel());
		assertEquals(50L, state.getExp(vanguard));

		// Lancer remains Lv. 1 independently
		assertEquals(1, state.getLevel(lancer));
		assertEquals(2, state.getHighestJobLevel());
	}

	@Test
	void testArmouryBonusAndCombatChainBonus() {
		JobProgressionState state = new JobProgressionState();
		Identifier vanguard = Identifier.fromNamespaceAndPath("xivmmo", "vanguard");
		Identifier arcanist = Identifier.fromNamespaceAndPath("xivmmo", "arcanist");

		state.setLevel(vanguard, 20);
		assertEquals(20, state.getHighestJobLevel());

		// Arcanist is Lv. 1 (< Lv. 20 highest job), so it receives +100% Armoury Bonus
		assertEquals(100, state.getArmouryBonusPercent(arcanist));

		// First kill on Arcanist at 50_000ms: +100% Armoury Bonus (Chain #0) -> 100 base becomes 200 EXP
		JobProgressionState.ExpGainResult hit1 = state.awardExp(arcanist, 100L, 10L, 50_000L);
		assertEquals(100, hit1.bonusPercent());
		assertEquals(200L, hit1.totalExpGained());

		// Second kill within 20 seconds (55_000ms): Chain #1 (+20%) + Armoury (+100%) = +120% -> 100 base becomes 220 EXP
		JobProgressionState.ExpGainResult hit2 = state.awardExp(arcanist, 100L, 10L, 55_000L);
		assertEquals(1, hit2.chainCount());
		assertEquals(120, hit2.bonusPercent());
		assertEquals(220L, hit2.totalExpGained());
	}

	@Test
	void testSlotUnlockLevelsAndAttributeScaling() {
		assertEquals(1, JobProgressionState.getSlotUnlockLevel(0));
		assertEquals(1, JobProgressionState.getSlotUnlockLevel(1));
		assertEquals(2, JobProgressionState.getSlotUnlockLevel(2));
		assertEquals(20, JobProgressionState.getSlotUnlockLevel(7));
		assertEquals(1, JobProgressionState.getSlotUnlockLevel(8)); // Role Action 1 (Second Wind)
		assertEquals(1, JobProgressionState.getSlotUnlockLevel(9)); // Role Action 2 (Aether Sprint)

		assertTrue(JobProgressionState.isSlotUnlockedForLevel(0, 1));
		assertFalse(JobProgressionState.isSlotUnlockedForLevel(7, 19));
		assertTrue(JobProgressionState.isSlotUnlockedForLevel(7, 20));

		Identifier vanguard = Identifier.fromNamespaceAndPath("xivmmo", "vanguard");
		JobProgressionState.JobAttributes lv1 = JobProgressionState.computeAttributes(vanguard, 1, 0, 1);
		JobProgressionState.JobAttributes lv50 = JobProgressionState.computeAttributes(vanguard, 50, 10, 8);

		assertTrue(lv50.strength() > lv1.strength() * 5);
		assertTrue(lv50.vitality() > lv1.vitality() * 5);
		assertTrue(lv50.defense() > lv1.defense());
		assertEquals(1.0F, JobProgressionState.getLevelOutputMultiplier(1), 1.0E-4F);
		assertEquals(2.715F, JobProgressionState.getLevelOutputMultiplier(50), 1.0E-4F);
	}

	@Test
	void testRoleActionJsonFilesDecodeCleanly() throws Exception {
		String[] roleActions = {"second_wind", "aether_sprint", "limit_surge", "sanctuary_ward"};
		for (String name : roleActions) {
			String path = "/data/xivmmo/mmo/abilities/" + name + ".json";
			try (var in = getClass().getResourceAsStream(path)) {
				assertNotNull(in, "Missing role action resource: " + path);
				var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				AbilityDefinition def = AbilityDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
				assertEquals("xivmmo:" + name, def.id().toString());
				assertFalse(def.displayName().isBlank());
			}
		}
	}

	@Test
	void testFfxiv140SlotInventoryGeometry() {
		assertEquals(35, MmoInventoryState.SLOTS_PER_PAGE);
		assertEquals(4, MmoInventoryState.MAIN_PAGES);
		assertEquals(140, MmoInventoryState.TOTAL_MAIN_SLOTS);
		assertEquals(35, MmoInventoryState.KEY_ITEM_SLOTS);
		assertEquals(175, MmoInventoryState.TOTAL_CONTAINER_SLOTS);
	}

	@Test
	void testFfxivChatChannelsAutoTranslateAndFilterTabs() {
		// 1. Auto-Translate expansion ({phrase} -> ◖Phrase◗)
		String expanded = net.xivmmo.chat.FfxivChatChannel.expandAutoTranslateTokens("Hello {Looking for members.} send /tell!");
		assertEquals("Hello ◖Looking for members.◗ send /tell!", expanded);

		// 2. Outgoing channel prefix parsing (/p, /sh, /fc, /tell, /r, and vanilla commands)
		var partyMsg = net.xivmmo.chat.FfxivChatChannel.parseOutgoingInput(
			"/p {Thank you!} everyone",
			net.xivmmo.chat.FfxivChatChannel.SAY,
			""
		);
		assertEquals(net.xivmmo.chat.FfxivChatChannel.PARTY, partyMsg.channel());
		assertEquals("◖Thank you!◗ everyone", partyMsg.messageBody());
		assertFalse(partyMsg.isVanillaSlashCommand());

		var tellMsg = net.xivmmo.chat.FfxivChatChannel.parseOutgoingInput(
			"/tell @vittoria Messaging you from in-game!",
			net.xivmmo.chat.FfxivChatChannel.SAY,
			""
		);
		assertEquals(net.xivmmo.chat.FfxivChatChannel.TELL, tellMsg.channel());
		assertEquals("vittoria", tellMsg.targetPlayer());
		assertEquals("Messaging you from in-game!", tellMsg.messageBody());

		var replyMsg = net.xivmmo.chat.FfxivChatChannel.parseOutgoingInput(
			"/r On my way!",
			net.xivmmo.chat.FfxivChatChannel.SAY,
			"vittoria"
		);
		assertEquals(net.xivmmo.chat.FfxivChatChannel.TELL, replyMsg.channel());
		assertEquals("vittoria", replyMsg.targetPlayer());
		assertEquals("On my way!", replyMsg.messageBody());

		var mmoCmd = net.xivmmo.chat.FfxivChatChannel.parseOutgoingInput(
			"/mmo job hierophant",
			net.xivmmo.chat.FfxivChatChannel.SAY,
			""
		);
		assertTrue(mmoCmd.isVanillaSlashCommand());

		// 3. Incoming message categorization & FilterTab routing (General hides Battle spam)
		assertEquals(
			net.xivmmo.chat.FfxivChatChannel.EVENT,
			net.xivmmo.chat.FfxivChatChannel.classifyIncomingMessage("You change to white mage.")
		);
		assertEquals(
			net.xivmmo.chat.FfxivChatChannel.BATTLE,
			net.xivmmo.chat.FfxivChatChannel.classifyIncomingMessage("You gain 350 (+100%) Vanguard EXP.")
		);
		assertEquals(
			net.xivmmo.chat.FfxivChatChannel.FREE_COMPANY,
			net.xivmmo.chat.FfxivChatChannel.classifyIncomingMessage("[FC]<Bizarro Stormy> anyone want to roulette")
		);

		assertFalse(net.xivmmo.chat.FfxivChatChannel.FilterTab.GENERAL.accepts(net.xivmmo.chat.FfxivChatChannel.BATTLE));
		assertTrue(net.xivmmo.chat.FfxivChatChannel.FilterTab.GENERAL.accepts(net.xivmmo.chat.FfxivChatChannel.SAY));
		assertTrue(net.xivmmo.chat.FfxivChatChannel.FilterTab.BATTLE.accepts(net.xivmmo.chat.FfxivChatChannel.BATTLE));
		assertTrue(net.xivmmo.chat.FfxivChatChannel.FilterTab.ALL.accepts(net.xivmmo.chat.FfxivChatChannel.BATTLE));
	}
}
