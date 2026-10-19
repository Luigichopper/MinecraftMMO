package net.xivmmo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.xivmmo.client.hud.MmoHudManager;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.engine.PlayerCombatState;
import net.xivmmo.combat.enmity.EnmityManager;
import net.xivmmo.combat.status.StatusDefinition;
import net.xivmmo.combat.status.StatusManager;
import net.xivmmo.config.MmoConfig;
import net.xivmmo.debug.MmoDebugState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Milestone9And10Test {
	@Test
	void testPlayerVitalsPositioningAndHotbarMechanicsConfig() {
		MmoConfig cfg = MmoConfig.get();
		cfg.resetHudLayoutToDefaults();
		assertTrue(cfg.hideVanillaHotbarInMmoMode, "Vanilla hotbar UI should be hidden by default in MMO mode");
		assertTrue(cfg.disableVanillaHotbarMechanicsInMmoMode, "Vanilla hotbar mechanics should be disabled by default in MMO mode");

		// Verify CAST_BAR, PLAYER_VITALS, and ACTION_HOTBAR stack cleanly without vertical overlap on both 1080p (480x270) and compact (320x180) GUI sizes
		for (int[] dims : new int[][]{{480, 270}, {320, 180}}) {
			int sw = dims[0];
			int sh = dims[1];
			MmoHudManager.WidgetRect castRect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.CAST_BAR, sw, sh);
			MmoHudManager.WidgetRect vitalsRect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.PLAYER_VITALS, sw, sh);
			MmoHudManager.WidgetRect hotbarRect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.ACTION_HOTBAR, sw, sh);

			assertTrue(vitalsRect.enabled(), "PLAYER_VITALS must be enabled by default");
			assertEquals(182, vitalsRect.width());
			assertEquals(20, vitalsRect.height());

			assertTrue(
				vitalsRect.y() + vitalsRect.height() <= hotbarRect.y() - 4,
				"Health & Hunger bars (PLAYER_VITALS) must sit cleanly above ACTION_HOTBAR without overlapping at " + sw + "x" + sh
			);
			assertTrue(
				castRect.y() + castRect.height() <= vitalsRect.y() - 4,
				"CAST_BAR must sit cleanly above PLAYER_VITALS without overlapping at " + sw + "x" + sh
			);
		}
	}

	@Test
	void testDebugStateRingBufferAndProfilerMetrics() {
		MmoDebugState.clearPacketLogs();
		MmoDebugState.setPacketTraceEnabled(true);

		for (int i = 1; i <= 20; i++) {
			MmoDebugState.recordPacket("C2S", "use_ability", "seq=" + i);
		}
		assertEquals(20L, MmoDebugState.getTotalPacketsSent());
		List<String> recent = MmoDebugState.getRecentPacketLogs();
		assertEquals(16, recent.size(), "Packet ring buffer must cap at 16 entries");
		assertTrue(recent.getFirst().contains("seq=5"));
		assertTrue(recent.getLast().contains("seq=20"));

		MmoDebugState.recordServerTickDurationNanos(120_000L); // 120 us
		assertEquals(120.0, MmoDebugState.getLastServerTickMicros(), 0.1);
		assertTrue(MmoDebugState.getRollingAvgServerTickMicros() > 0.0);

		MmoDebugState.recordClientRaycast(false);
		MmoDebugState.recordClientRaycast(true);
		MmoDebugState.recordClientRaycast(true);
		assertTrue(MmoDebugState.getRaycastCacheHitRatePercent() > 50.0);

		MmoDebugState.setPacketTraceEnabled(false);
	}

	@Test
	void testFiftyPlusEntityCombatTickPerformanceBudget() {
		StatusManager statusManager = new StatusManager();
		EnmityManager enmityManager = new EnmityManager();
		Identifier dotId = Identifier.fromNamespaceAndPath("xivmmo", "combust_dot");
		StatusDefinition dotDef = new StatusDefinition(
			dotId, "Combust", StatusDefinition.StatusType.DEBUFF, 240, 1, 1.0F, 1.0F, 50, 0, 1.0F
		);

		List<UUID> entities = new ArrayList<>(60);
		UUID tankUuid = UUID.randomUUID();
		for (int i = 0; i < 60; i++) {
			UUID mobUuid = UUID.randomUUID();
			entities.add(mobUuid);
			statusManager.applyStatus(mobUuid, tankUuid, dotDef, 240, id -> dotDef);
			enmityManager.addEnmity(mobUuid, tankUuid, 5000L + i * 100L);
		}

		PlayerCombatState playerState = new PlayerCombatState();
		AbilityDefinition gcdDef = new AbilityDefinition(
			Identifier.fromNamespaceAndPath("xivmmo", "iron_slash"),
			"Iron Slash",
			AbilityDefinition.Category.GCD,
			AbilityDefinition.TargetType.SINGLE_ENEMY,
			AbilityDefinition.AoeShape.NONE,
			3.5F, 0.0F, 0.0F, 90.0F,
			0, 50,
			Identifier.fromNamespaceAndPath("xivmmo", "gcd"),
			1, 12, 0, 180,
			java.util.Optional.empty(), 0,
			AbilityDefinition.Positional.NONE, 0,
			false
		);

		// Warmup
		for (int t = 0; t < 20; t++) {
			statusManager.tick(id -> dotDef);
			playerState.tick(false, id -> gcdDef);
		}

		long startNanos = System.nanoTime();
		int iterations = 100;
		for (int t = 0; t < iterations; t++) {
			statusManager.tick(id -> dotDef);
			playerState.tick(false, id -> gcdDef);
			for (int i = 0; i < entities.size(); i++) {
				enmityManager.getTopContender(entities.get(i));
			}
		}
		long elapsedNanos = System.nanoTime() - startNanos;
		double avgMicrosPerTick = (elapsedNanos / 1000.0) / iterations;

		assertTrue(
			avgMicrosPerTick < 500.0,
			"60-entity combat tick must execute well under 500 us budget (actual: " + avgMicrosPerTick + " us)"
		);
	}

	@Test
	void testModpackFilesAndModrinthIndexIntegrity() throws Exception {
		Path packToml = Path.of("modpack/pack.toml");
		Path indexToml = Path.of("modpack/index.toml");
		Path mrIndexPath = Path.of("modpack/modrinth.index.json");

		assertTrue(Files.exists(packToml), "modpack/pack.toml must exist");
		assertTrue(Files.exists(indexToml), "modpack/index.toml must exist");
		assertTrue(Files.exists(mrIndexPath), "modpack/modrinth.index.json must exist");

		String packText = Files.readString(packToml);
		assertTrue(packText.contains("minecraft = \"26.2\""));
		assertTrue(packText.contains("fabric = \"0.19.5\""));

		JsonObject mrRoot = JsonParser.parseString(Files.readString(mrIndexPath)).getAsJsonObject();
		assertEquals(1, mrRoot.get("formatVersion").getAsInt());
		assertEquals("26.2", mrRoot.getAsJsonObject("dependencies").get("minecraft").getAsString());

		JsonArray files = mrRoot.getAsJsonArray("files");
		assertEquals(20, files.size(), "Modpack must contain all 18 verified 26.2 Fabric mods + 2 verified shaderpacks");

		boolean foundSodium = false;
		boolean foundIris = false;
		boolean foundPhoton = false;
		boolean foundComplementary = false;
		for (int i = 0; i < files.size(); i++) {
			JsonObject entry = files.get(i).getAsJsonObject();
			String path = entry.get("path").getAsString();
			if (path.contains("sodium")) foundSodium = true;
			if (path.contains("iris")) foundIris = true;
			if (path.contains("photon")) foundPhoton = true;
			if (path.contains("ComplementaryReimagined")) foundComplementary = true;
		}
		assertTrue(foundSodium && foundIris && foundPhoton && foundComplementary);
	}
}
