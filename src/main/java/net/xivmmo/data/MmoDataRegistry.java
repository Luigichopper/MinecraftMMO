package net.xivmmo.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.xivmmo.XivMmoMod;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.ability.JobDefinition;
import net.xivmmo.combat.status.StatusDefinition;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data-driven registry for MMO Abilities, Statuses, and Jobs.
 * Reloaded automatically on {@code /reload} or world startup via Fabric's {@link ResourceLoader},
 * and seeded with built-in starter definitions.
 */
public final class MmoDataRegistry {
	public static final Identifier RELOADER_ID = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "mmo_data_reloader");

	private static final Map<Identifier, AbilityDefinition> ABILITIES = new ConcurrentHashMap<>();
	private static final Map<Identifier, StatusDefinition> STATUSES = new ConcurrentHashMap<>();
	private static final Map<Identifier, JobDefinition> JOBS = new ConcurrentHashMap<>();

	static {
		registerBuiltInDefaults();
	}

	private MmoDataRegistry() {
	}

	public static void registerReloadListeners() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(RELOADER_ID, new SimpleReloadListener<LoadedData>() {
			@Override
			protected LoadedData prepare(PreparableReloadListener.SharedState state) {
				ResourceManager manager = state.resourceManager();
				Map<Identifier, AbilityDefinition> loadedAbilities = new LinkedHashMap<>();
				Map<Identifier, StatusDefinition> loadedStatuses = new LinkedHashMap<>();
				Map<Identifier, JobDefinition> loadedJobs = new LinkedHashMap<>();

				// 1. Load abilities from data/<namespace>/mmo/abilities/*.json
				Map<Identifier, Resource> abilityResources = manager.listResources(
					"mmo/abilities",
					id -> id.getPath().endsWith(".json")
				);
				for (Map.Entry<Identifier, Resource> entry : abilityResources.entrySet()) {
					try (Reader reader = entry.getValue().openAsReader()) {
						JsonElement json = JsonParser.parseReader(reader);
						AbilityDefinition.CODEC.parse(JsonOps.INSTANCE, json)
							.resultOrPartial(err -> XivMmoMod.LOGGER.error("Invalid ability JSON {}: {}", entry.getKey(), err))
							.ifPresent(def -> loadedAbilities.put(def.id(), def));
					} catch (Exception e) {
						XivMmoMod.LOGGER.error("Failed to read ability resource {}", entry.getKey(), e);
					}
				}

				// 2. Load statuses from data/<namespace>/mmo/statuses/*.json
				Map<Identifier, Resource> statusResources = manager.listResources(
					"mmo/statuses",
					id -> id.getPath().endsWith(".json")
				);
				for (Map.Entry<Identifier, Resource> entry : statusResources.entrySet()) {
					try (Reader reader = entry.getValue().openAsReader()) {
						JsonElement json = JsonParser.parseReader(reader);
						StatusDefinition.CODEC.parse(JsonOps.INSTANCE, json)
							.resultOrPartial(err -> XivMmoMod.LOGGER.error("Invalid status JSON {}: {}", entry.getKey(), err))
							.ifPresent(def -> loadedStatuses.put(def.id(), def));
					} catch (Exception e) {
						XivMmoMod.LOGGER.error("Failed to read status resource {}", entry.getKey(), e);
					}
				}

				// 3. Load jobs from data/<namespace>/mmo/jobs/*.json
				Map<Identifier, Resource> jobResources = manager.listResources(
					"mmo/jobs",
					id -> id.getPath().endsWith(".json")
				);
				for (Map.Entry<Identifier, Resource> entry : jobResources.entrySet()) {
					try (Reader reader = entry.getValue().openAsReader()) {
						JsonElement json = JsonParser.parseReader(reader);
						JobDefinition.CODEC.parse(JsonOps.INSTANCE, json)
							.resultOrPartial(err -> XivMmoMod.LOGGER.error("Invalid job JSON {}: {}", entry.getKey(), err))
							.ifPresent(def -> loadedJobs.put(def.id(), def));
					} catch (Exception e) {
						XivMmoMod.LOGGER.error("Failed to read job resource {}", entry.getKey(), e);
					}
				}

				return new LoadedData(loadedAbilities, loadedStatuses, loadedJobs);
			}

			@Override
			protected void apply(LoadedData prepared, PreparableReloadListener.SharedState state) {
				ABILITIES.clear();
				STATUSES.clear();
				JOBS.clear();
				registerBuiltInDefaults();
				ABILITIES.putAll(prepared.abilities());
				STATUSES.putAll(prepared.statuses());
				JOBS.putAll(prepared.jobs());
				XivMmoMod.LOGGER.info(
					"Loaded {} MMO abilities, {} statuses, and {} jobs from datapacks",
					ABILITIES.size(),
					STATUSES.size(),
					JOBS.size()
				);
			}
		});
	}

	public static void registerBuiltInDefaults() {
		// Starter Statuses
		Identifier aegisStanceId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "aegis_stance");
		Identifier ironBulwarkId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "iron_bulwark");
		Identifier searingDotId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "searing_dot");
		Identifier regenGraceId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "regen_grace");
		Identifier battleFocusId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "battle_focus");

		registerStatus(new StatusDefinition(
			aegisStanceId, "Aegis Oath", StatusDefinition.StatusType.BUFF,
			72000, 1, 1.0F, 0.90F, 0, 0, 10.0F
		));
		registerStatus(new StatusDefinition(
			ironBulwarkId, "Iron Bulwark", StatusDefinition.StatusType.BUFF,
			400, 1, 1.0F, 0.80F, 0, 0, 1.0F
		));
		registerStatus(new StatusDefinition(
			searingDotId, "Searing Brand", StatusDefinition.StatusType.DEBUFF,
			480, 1, 1.0F, 1.0F, 60, 0, 1.0F
		));
		registerStatus(new StatusDefinition(
			regenGraceId, "Regen Grace", StatusDefinition.StatusType.BUFF,
			360, 1, 1.0F, 1.0F, 0, 120, 1.0F
		));
		registerStatus(new StatusDefinition(
			battleFocusId, "Battle Focus", StatusDefinition.StatusType.BUFF,
			300, 1, 1.15F, 1.0F, 0, 0, 1.0F
		));

		// Starter Abilities across all 4 vertical-slice Jobs
		Identifier gcdGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "gcd");
		Identifier ogcdFlecheGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_aether_lance");
		Identifier ogcdDashGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_vanguard_rush");
		Identifier gcdBurstGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_solar_flare");
		Identifier ogcdProvokeGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_provoke");
		Identifier ogcdShirkGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_shirk");
		Identifier ogcdSolaceGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_solace");
		Identifier ogcdDiveGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_dragon_dive");

		Identifier ironSlashId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "iron_slash");
		Identifier sunderEdgeId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "sunder_edge");
		Identifier royalCrestId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "royal_crest");
		Identifier aetherLanceId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "aether_lance");
		Identifier vanguardRushId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard_rush");
		Identifier astralBoltId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "astral_bolt");
		Identifier starfallZoneId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "starfall_zone");
		Identifier vitalMendId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vital_mend");
		Identifier provokeRoarId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "provoke_roar");
		Identifier shirkStepId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "shirk_step");
		Identifier eclipseSweepId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "eclipse_sweep");
		Identifier solacePulseId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "solace_pulse");
		Identifier radiantHaloId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "radiant_halo");
		Identifier fangThrustId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "fang_thrust");
		Identifier dragonDiveId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "dragon_dive");
		Identifier crimsonFlareId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "crimson_flare");
		Identifier voidRingId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "void_ring");

		registerAbility(new AbilityDefinition(
			ironSlashId, "Iron Slash",
			AbilityDefinition.Category.GCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			3.5F, 0.0F, 0.0F, 90.0F,
			0, 50, gcdGroup, 1, 12, 0,
			180, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.empty()
		));

		registerAbility(new AbilityDefinition(
			sunderEdgeId, "Sunder Edge",
			AbilityDefinition.Category.GCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			3.5F, 0.0F, 0.0F, 90.0F,
			0, 50, gcdGroup, 1, 12, 0,
			140, Optional.of(ironSlashId), 260, AbilityDefinition.Positional.FLANK, 300, true,
			Optional.empty()
		));

		registerAbility(new AbilityDefinition(
			royalCrestId, "Royal Crest",
			AbilityDefinition.Category.GCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			3.5F, 0.0F, 0.0F, 90.0F,
			0, 50, gcdGroup, 1, 12, 0,
			160, Optional.of(sunderEdgeId), 340, AbilityDefinition.Positional.REAR, 400, true,
			Optional.of(ironBulwarkId)
		));

		registerAbility(new AbilityDefinition(
			aetherLanceId, "Aether Lance",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			25.0F, 0.0F, 0.0F, 90.0F,
			0, 300, ogcdFlecheGroup, 1, 12, 0,
			280, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(battleFocusId)
		));

		registerAbility(new AbilityDefinition(
			vanguardRushId, "Vanguard Rush",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			20.0F, 0.0F, 0.0F, 90.0F,
			0, 400, ogcdDashGroup, 2, 12, 0,
			150, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(aegisStanceId)
		));

		registerAbility(new AbilityDefinition(
			astralBoltId, "Astral Bolt",
			AbilityDefinition.Category.SPELL_GCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			25.0F, 0.0F, 0.0F, 90.0F,
			40, 50, gcdGroup, 1, 10, 400,
			310, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, false,
			Optional.of(searingDotId)
		));

		registerAbility(new AbilityDefinition(
			starfallZoneId, "Starfall Zone",
			AbilityDefinition.Category.SPELL_GCD, AbilityDefinition.TargetType.GROUND_AOE, AbilityDefinition.AoeShape.CIRCLE,
			25.0F, 5.0F, 0.0F, 360.0F,
			20, 50, gcdBurstGroup, 1, 10, 600,
			240, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, false,
			Optional.of(regenGraceId)
		));

		registerAbility(new AbilityDefinition(
			vitalMendId, "Vital Mend",
			AbilityDefinition.Category.SPELL_GCD, AbilityDefinition.TargetType.SINGLE_ALLY, AbilityDefinition.AoeShape.NONE,
			30.0F, 0.0F, 0.0F, 360.0F,
			30, 50, gcdGroup, 1, 10, 500,
			400, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, false,
			Optional.of(regenGraceId)
		));

		registerAbility(new AbilityDefinition(
			provokeRoarId, "Provoke Roar",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			25.0F, 0.0F, 0.0F, 90.0F,
			0, 500, ogcdProvokeGroup, 1, 10, 0,
			50, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(aegisStanceId)
		));

		registerAbility(new AbilityDefinition(
			shirkStepId, "Shirk Step",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SINGLE_ALLY, AbilityDefinition.AoeShape.NONE,
			25.0F, 0.0F, 0.0F, 360.0F,
			0, 600, ogcdShirkGroup, 1, 10, 0,
			0, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.empty()
		));

		registerAbility(new AbilityDefinition(
			eclipseSweepId, "Eclipse Sweep",
			AbilityDefinition.Category.GCD, AbilityDefinition.TargetType.SELF_AOE, AbilityDefinition.AoeShape.CIRCLE,
			0.0F, 5.0F, 0.0F, 360.0F,
			0, 50, gcdGroup, 1, 12, 0,
			160, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(aegisStanceId)
		));

		registerAbility(new AbilityDefinition(
			solacePulseId, "Solace Pulse",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SINGLE_ALLY, AbilityDefinition.AoeShape.NONE,
			30.0F, 0.0F, 0.0F, 360.0F,
			0, 400, ogcdSolaceGroup, 2, 10, 0,
			500, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(regenGraceId)
		));

		registerAbility(new AbilityDefinition(
			radiantHaloId, "Radiant Halo",
			AbilityDefinition.Category.SPELL_GCD, AbilityDefinition.TargetType.SELF_AOE, AbilityDefinition.AoeShape.CIRCLE,
			0.0F, 8.0F, 0.0F, 360.0F,
			30, 50, gcdGroup, 1, 10, 700,
			320, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, false,
			Optional.of(regenGraceId)
		));

		registerAbility(new AbilityDefinition(
			fangThrustId, "Fang Thrust",
			AbilityDefinition.Category.GCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			3.5F, 0.0F, 0.0F, 90.0F,
			0, 50, gcdGroup, 1, 12, 0,
			180, Optional.of(sunderEdgeId), 360, AbilityDefinition.Positional.FLANK, 440, true,
			Optional.of(searingDotId)
		));

		registerAbility(new AbilityDefinition(
			dragonDiveId, "Dragon Dive",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.CONE,
			15.0F, 6.0F, 0.0F, 90.0F,
			0, 400, ogcdDiveGroup, 2, 12, 0,
			320, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.empty()
		));

		registerAbility(new AbilityDefinition(
			crimsonFlareId, "Crimson Flare",
			AbilityDefinition.Category.SPELL_GCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.LINE,
			25.0F, 10.0F, 1.5F, 90.0F,
			25, 50, gcdGroup, 1, 10, 500,
			300, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, false,
			Optional.empty()
		));

		registerAbility(new AbilityDefinition(
			voidRingId, "Void Ring",
			AbilityDefinition.Category.SPELL_GCD, AbilityDefinition.TargetType.SELF_AOE, AbilityDefinition.AoeShape.DONUT,
			0.0F, 7.0F, 2.5F, 360.0F,
			20, 50, gcdGroup, 1, 10, 500,
			290, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, false,
			Optional.empty()
		));

		Identifier secondWindGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_second_wind");
		Identifier aetherSprintGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_aether_sprint");
		Identifier limitSurgeGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_limit_surge");
		Identifier sanctuaryWardGroup = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "cd_sanctuary_ward");

		Identifier secondWindId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "second_wind");
		Identifier aetherSprintId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "aether_sprint");
		Identifier limitSurgeId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "limit_surge");
		Identifier sanctuaryWardId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "sanctuary_ward");

		registerAbility(new AbilityDefinition(
			secondWindId, "Second Wind",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SELF_AOE, AbilityDefinition.AoeShape.CIRCLE,
			0.0F, 3.0F, 0.0F, 360.0F,
			0, 600, secondWindGroup, 1, 10, 0,
			450, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(regenGraceId)
		));

		registerAbility(new AbilityDefinition(
			aetherSprintId, "Aether Sprint",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SELF_AOE, AbilityDefinition.AoeShape.CIRCLE,
			0.0F, 4.0F, 0.0F, 360.0F,
			0, 600, aetherSprintGroup, 1, 10, 0,
			0, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(battleFocusId)
		));

		registerAbility(new AbilityDefinition(
			limitSurgeId, "Limit Surge",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SINGLE_ENEMY, AbilityDefinition.AoeShape.NONE,
			20.0F, 0.0F, 0.0F, 90.0F,
			0, 900, limitSurgeGroup, 1, 14, 0,
			550, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(battleFocusId)
		));

		registerAbility(new AbilityDefinition(
			sanctuaryWardId, "Sanctuary Ward",
			AbilityDefinition.Category.OGCD, AbilityDefinition.TargetType.SELF_AOE, AbilityDefinition.AoeShape.CIRCLE,
			0.0F, 6.0F, 0.0F, 360.0F,
			0, 600, sanctuaryWardGroup, 1, 10, 0,
			0, Optional.empty(), 0, AbilityDefinition.Positional.NONE, 0, true,
			Optional.of(ironBulwarkId)
		));

		// 1. TANK: Vanguard
		Identifier vanguardId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard");
		JOBS.put(vanguardId, new JobDefinition(
			vanguardId,
			"Vanguard",
			JobDefinition.Role.TANK,
			10000,
			List.of(
				ironSlashId, sunderEdgeId, royalCrestId, eclipseSweepId,
				vanguardRushId, provokeRoarId, shirkStepId, vitalMendId,
				secondWindId, aetherSprintId, sanctuaryWardId, limitSurgeId
			)
		));

		// 2. HEALER: Hierophant
		Identifier hierophantId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "hierophant");
		JOBS.put(hierophantId, new JobDefinition(
			hierophantId,
			"Hierophant",
			JobDefinition.Role.HEALER,
			10000,
			List.of(
				astralBoltId, vitalMendId, solacePulseId, radiantHaloId,
				starfallZoneId, aetherLanceId, vanguardRushId, shirkStepId,
				secondWindId, aetherSprintId, sanctuaryWardId, limitSurgeId
			)
		));

		// 3. MELEE DPS: Lancer
		Identifier lancerId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "lancer");
		JOBS.put(lancerId, new JobDefinition(
			lancerId,
			"Lancer",
			JobDefinition.Role.MELEE_DPS,
			10000,
			List.of(
				ironSlashId, sunderEdgeId, royalCrestId, fangThrustId,
				aetherLanceId, dragonDiveId, vanguardRushId, vitalMendId,
				secondWindId, aetherSprintId, sanctuaryWardId, limitSurgeId
			)
		));

		// 4. RANGED DPS: Arcanist
		Identifier arcanistId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "arcanist");
		JOBS.put(arcanistId, new JobDefinition(
			arcanistId,
			"Arcanist",
			JobDefinition.Role.RANGED_DPS,
			10000,
			List.of(
				astralBoltId, crimsonFlareId, starfallZoneId, voidRingId,
				aetherLanceId, dragonDiveId, vanguardRushId, vitalMendId,
				secondWindId, aetherSprintId, sanctuaryWardId, limitSurgeId
			)
		));
	}

	public static void registerAbility(AbilityDefinition definition) {
		ABILITIES.put(definition.id(), definition);
	}

	public static @Nullable AbilityDefinition getAbility(Identifier id) {
		return ABILITIES.get(id);
	}

	public static Collection<AbilityDefinition> getAllAbilities() {
		return Collections.unmodifiableCollection(ABILITIES.values());
	}

	public static void registerStatus(StatusDefinition definition) {
		STATUSES.put(definition.id(), definition);
	}

	public static @Nullable StatusDefinition getStatus(Identifier id) {
		return STATUSES.get(id);
	}

	public static Collection<StatusDefinition> getAllStatuses() {
		return Collections.unmodifiableCollection(STATUSES.values());
	}

	public static @Nullable JobDefinition getJob(Identifier id) {
		return JOBS.get(id);
	}

	public static Collection<JobDefinition> getAllJobs() {
		return Collections.unmodifiableCollection(JOBS.values());
	}

	private record LoadedData(
		Map<Identifier, AbilityDefinition> abilities,
		Map<Identifier, StatusDefinition> statuses,
		Map<Identifier, JobDefinition> jobs
	) {
	}
}
