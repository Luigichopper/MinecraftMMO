package net.xivmmo.combat.arena;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.enmity.EnmityManager;
import net.xivmmo.combat.telegraph.TelegraphManager;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the 2 Milestone 8 Boss Arena encounters ("sunforge_sanctum" and "obsidian_spire"),
 * constructing the arena floor and driving boss telegraph rotations using the Enmity table.
 */
public final class BossArenaManager {
	public static final String ARENA_SUNFORGE = "sunforge_sanctum";
	public static final String ARENA_OBSIDIAN = "obsidian_spire";

	private final Map<UUID, ActiveArenaBoss> activeBosses = new ConcurrentHashMap<>();

	public record BossMechanicSpec(
		String mechanicName,
		AbilityDefinition.AoeShape shape,
		float radius,
		float innerRadius,
		float coneAngleDegrees,
		int castTicks,
		int potency
	) {
	}

	private static final class ActiveArenaBoss {
		final UUID bossUuid;
		final ResourceKey<Level> dimension;
		final String arenaId;
		int tickCounter = 0;
		int mechanicStep = 0;

		ActiveArenaBoss(UUID bossUuid, ResourceKey<Level> dimension, String arenaId) {
			this.bossUuid = bossUuid;
			this.dimension = dimension;
			this.arenaId = arenaId;
		}
	}

	/**
	 * Pure-logic helper: returns the deterministic boss telegraph spec for the given arena and step index.
	 */
	public static BossMechanicSpec selectNextMechanic(String arenaId, int stepIndex) {
		int step = Math.floorMod(stepIndex, 3);
		if (ARENA_OBSIDIAN.equalsIgnoreCase(arenaId)) {
			return switch (step) {
				case 0 -> new BossMechanicSpec(
					"Void Lance",
					AbilityDefinition.AoeShape.LINE,
					13.0F,
					1.75F,
					90.0F,
					50,
					260
				);
				case 1 -> new BossMechanicSpec(
					"Abyssal Dynamo",
					AbilityDefinition.AoeShape.DONUT,
					9.5F,
					2.8F,
					360.0F,
					60,
					300
				);
				default -> new BossMechanicSpec(
					"Umbral Cleave",
					AbilityDefinition.AoeShape.CONE,
					8.5F,
					0.0F,
					120.0F,
					55,
					280
				);
			};
		}

		// Default: Sunforge Sanctum
		return switch (step) {
			case 0 -> new BossMechanicSpec(
				"Solar Cleave",
				AbilityDefinition.AoeShape.CONE,
				8.0F,
				0.0F,
				90.0F,
				55,
				240
			);
			case 1 -> new BossMechanicSpec(
				"Sunburst Nova",
				AbilityDefinition.AoeShape.CIRCLE,
				6.0F,
				0.0F,
				360.0F,
				60,
				280
			);
			default -> new BossMechanicSpec(
				"Corona Ring",
				AbilityDefinition.AoeShape.DONUT,
				9.5F,
				3.0F,
				360.0F,
				65,
				320
			);
		};
	}

	/**
	 * Builds a 12-block radius circular boss arena floor at {@code player}'s feet and spawns the arena boss.
	 */
	public Mob spawnArenaEncounter(ServerPlayer player, String requestedArenaId, EnmityManager enmityManager) {
		ServerLevel level = player.level();
		String arenaId = ARENA_OBSIDIAN.equalsIgnoreCase(requestedArenaId) ? ARENA_OBSIDIAN : ARENA_SUNFORGE;
		boolean obsidian = ARENA_OBSIDIAN.equals(arenaId);

		BlockPos center = player.blockPosition().below();
		int radius = 11;
		BlockState floorBlock = obsidian
			? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
			: Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
		BlockState rimBlock = obsidian
			? Blocks.CRYING_OBSIDIAN.defaultBlockState()
			: Blocks.SEA_LANTERN.defaultBlockState();

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int distSq = dx * dx + dz * dz;
				if (distSq <= radius * radius) {
					BlockPos pos = center.offset(dx, 0, dz);
					boolean isRim = distSq >= (radius - 1) * (radius - 1);
					level.setBlockAndUpdate(pos, isRim ? rimBlock : floorBlock);
				}
			}
		}

		Mob boss = obsidian
			? EntityTypes.WITHER_SKELETON.create(level, EntitySpawnReason.COMMAND)
			: EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
		if (boss == null) {
			return null;
		}

		double spawnX = player.getX();
		double spawnY = center.getY() + 1.0;
		double spawnZ = player.getZ() + 5.0;
		boss.snapTo(spawnX, spawnY, spawnZ, 180.0F, 0.0F);

		String bossTitle = obsidian ? "Obsidian Archon [Boss]" : "Sunforge Warden [Boss]";
		boss.setCustomName(Component.literal(bossTitle).withStyle(obsidian ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GOLD));
		boss.setCustomNameVisible(true);
		boss.setPersistenceRequired();

		AttributeInstance maxHealthAttr = boss.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealthAttr != null) {
			maxHealthAttr.setBaseValue(obsidian ? 500.0 : 400.0);
		}
		boss.setHealth(boss.getMaxHealth());

		AttributeInstance kbResist = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
		if (kbResist != null) {
			kbResist.setBaseValue(1.0);
		}

		level.addFreshEntity(boss);
		enmityManager.addEnmity(boss.getUUID(), player.getUUID(), 500L);
		boss.setTarget(player);

		this.activeBosses.put(boss.getUUID(), new ActiveArenaBoss(boss.getUUID(), level.dimension(), arenaId));

		player.sendSystemMessage(Component.literal(String.format(
			Locale.ROOT,
			"[XIVMMO] Entered Arena '%s' — %s (%.0f HP) engaged! Watch for ground telegraphs!",
			arenaId,
			bossTitle,
			boss.getMaxHealth()
		)).withStyle(ChatFormatting.GOLD));

		return boss;
	}

	/**
	 * Ticks all active arena bosses, orienting them toward their #1 Enmity contender and firing timed telegraphs.
	 */
	public boolean tick(
		MinecraftServer server,
		EnmityManager enmityManager,
		TelegraphManager telegraphManager
	) {
		if (this.activeBosses.isEmpty()) {
			return false;
		}
		boolean spawnedAnyTelegraph = false;

		Iterator<Map.Entry<UUID, ActiveArenaBoss>> iter = this.activeBosses.entrySet().iterator();
		while (iter.hasNext()) {
			ActiveArenaBoss state = iter.next().getValue();
			ServerLevel level = server.getLevel(state.dimension);
			if (level == null) {
				iter.remove();
				continue;
			}
			Entity entity = level.getEntity(state.bossUuid);
			if (!(entity instanceof Mob boss) || !boss.isAlive()) {
				iter.remove();
				continue;
			}

			// Keep boss focused on top enmity contender
			UUID topUuid = enmityManager.getTopContender(boss.getUUID());
			if (topUuid != null && level.getPlayerByUUID(topUuid) instanceof ServerPlayer topPlayer && topPlayer.isAlive()) {
				boss.setTarget(topPlayer);
			}

			state.tickCounter++;
			if (state.tickCounter % 90 == 0) {
				BossMechanicSpec spec = selectNextMechanic(state.arenaId, state.mechanicStep++);
				LivingEntity target = boss.getTarget();
				float yaw = boss.getYRot();
				if (target != null) {
					double dx = target.getX() - boss.getX();
					double dz = target.getZ() - boss.getZ();
					if (dx * dx + dz * dz > 1.0E-4) {
						yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
						boss.setYRot(yaw);
						boss.setYHeadRot(yaw);
					}
				}

				telegraphManager.spawnTelegraph(
					level,
					boss.getId(),
					spec.mechanicName(),
					spec.shape(),
					boss.getX(),
					boss.getY(),
					boss.getZ(),
					yaw,
					spec.radius(),
					spec.innerRadius(),
					spec.coneAngleDegrees(),
					spec.castTicks(),
					spec.potency(),
					true
				);
				spawnedAnyTelegraph = true;

				for (ServerPlayer p : level.players()) {
					if (p.distanceToSqr(boss) <= 45.0 * 45.0) {
						p.sendSystemMessage(Component.literal(String.format(
							Locale.ROOT,
							"[BOSS CAST] %s begins casting %s (%s)!",
							boss.getName().getString(),
							spec.mechanicName(),
							spec.shape().getSerializedName().toUpperCase(Locale.ROOT)
						)).withStyle(ChatFormatting.RED));
					}
				}
			}
		}

		return spawnedAnyTelegraph;
	}
}
