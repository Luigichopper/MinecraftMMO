package net.xivmmo.combat.engine;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.xivmmo.XivMmoMod;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.ability.JobDefinition;
import net.xivmmo.combat.arena.BossArenaManager;
import net.xivmmo.combat.damage.MmoDamagePipeline;
import net.xivmmo.combat.enmity.EnmityManager;
import net.xivmmo.combat.status.StatusDefinition;
import net.xivmmo.combat.status.StatusManager;
import net.xivmmo.combat.telegraph.TelegraphManager;
import net.xivmmo.combat.telegraph.TelegraphShapes;
import net.xivmmo.data.MmoDataRegistry;
import net.xivmmo.network.MmoNetwork;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative MMO combat engine.
 * Coordinates:
 * - Ability validation & execution (range, LOS, facing, resources, GCD/oGCD, cast times, queueing)
 * - Status effects & fixed 60-tick (3.0s) DoT/HoT server tick with snapshotting ({@link StatusManager})
 * - Potency + Attribute + Buff/Debuff + Armor damage pipeline ({@link MmoDamagePipeline})
 * - Enmity (hate list) generation and Enemy AI target override ({@link EnmityManager})
 * - Timed Enemy & Ground AoE Telegraphs ({@link TelegraphManager})
 * - Boss Arena Encounters ({@link BossArenaManager})
 */
public final class ServerCombatEngine {
	private static final Map<UUID, ServerPlayerSession> SESSIONS = new ConcurrentHashMap<>();
	private static final StatusManager STATUS_MANAGER = new StatusManager();
	private static final EnmityManager ENMITY_MANAGER = new EnmityManager();
	private static final TelegraphManager TELEGRAPH_MANAGER = new TelegraphManager();
	private static final BossArenaManager BOSS_ARENA_MANAGER = new BossArenaManager();
	private static int hudSyncCounter = 0;

	private ServerCombatEngine() {
	}

	public static StatusManager statusManager() {
		return STATUS_MANAGER;
	}

	public static EnmityManager enmityManager() {
		return ENMITY_MANAGER;
	}

	public static TelegraphManager telegraphManager() {
		return TELEGRAPH_MANAGER;
	}

	public static BossArenaManager bossArenaManager() {
		return BOSS_ARENA_MANAGER;
	}

	public static final class ServerPlayerSession {
		private final UUID playerUuid;
		private final PlayerCombatState combatState = new PlayerCombatState();
		private Identifier activeJobId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard");
		private int hardTargetId = -1;
		private int focusTargetId = -1;
		private int lastSyncedTargetOfTargetId = -2;
		private Vec3 lastPos = Vec3.ZERO;
		private boolean hasLastPos = false;

		public ServerPlayerSession(UUID playerUuid) {
			this.playerUuid = playerUuid;
		}

		public PlayerCombatState combatState() {
			return this.combatState;
		}

		public Identifier activeJobId() {
			return this.activeJobId;
		}

		public int hardTargetId() {
			return this.hardTargetId;
		}

		public int focusTargetId() {
			return this.focusTargetId;
		}
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(
			MmoNetwork.C2SSetTargetPayload.TYPE,
			(payload, context) -> {
				ServerPlayer player = context.player();
				ServerPlayerSession session = getOrCreateSession(player);
				session.hardTargetId = payload.hardTargetEntityId();
				session.focusTargetId = payload.focusTargetEntityId();
				session.lastSyncedTargetOfTargetId = -2;
			}
		);

		ServerPlayNetworking.registerGlobalReceiver(
			MmoNetwork.C2SUseAbilityPayload.TYPE,
			(payload, context) -> handleUseAbilityPacket(context.player(), payload)
		);

		ServerPlayNetworking.registerGlobalReceiver(
			MmoNetwork.C2SSpawnTestTelegraphPayload.TYPE,
			(payload, context) -> {
				ServerPlayer player = context.player();
				ServerLevel level = player.level();
				ServerPlayerSession session = getOrCreateSession(player);
				Entity target = session.hardTargetId >= 0 ? level.getEntity(session.hardTargetId) : null;
				Entity originEntity = target != null ? target : player;

				float safeRadius = Float.isFinite(payload.radius()) ? Mth.clamp(payload.radius(), 0.5F, 24.0F) : 6.0F;
				float safeInner = Float.isFinite(payload.innerRadius()) ? Mth.clamp(payload.innerRadius(), 0.0F, safeRadius - 0.25F) : 1.5F;
				float safeAngle = Float.isFinite(payload.coneAngleDegrees()) ? Mth.clamp(payload.coneAngleDegrees(), 15.0F, 360.0F) : 90.0F;
				int safeDuration = Mth.clamp(payload.durationTicks(), 10, 200);

				TELEGRAPH_MANAGER.spawnTelegraph(
					level,
					originEntity.getId(),
					"Test " + payload.shape().getSerializedName().toUpperCase(Locale.ROOT),
					payload.shape(),
					originEntity.getX(),
					originEntity.getY(),
					originEntity.getZ(),
					originEntity.getYRot(),
					safeRadius,
					safeInner,
					safeAngle,
					safeDuration,
					220,
					true
				);
				syncTelegraphsToLevel(level);
			}
		);

		ServerPlayNetworking.registerGlobalReceiver(
			MmoNetwork.C2SSelectJobPayload.TYPE,
			(payload, context) -> {
				ServerPlayer player = context.player();
				JobDefinition job = MmoDataRegistry.getJob(payload.jobId());
				if (job != null) {
					ServerPlayerSession session = getOrCreateSession(player);
					session.activeJobId = job.id();
					player.sendSystemMessage(Component.literal(String.format(
						Locale.ROOT,
						"[XIVMMO] Switched Job to %s (%s)",
						job.displayName(),
						job.role().getSerializedName().toUpperCase(Locale.ROOT)
					)).withStyle(ChatFormatting.AQUA));
					syncPartyState(player.level().getServer());
				}
			}
		);

		ServerPlayNetworking.registerGlobalReceiver(
			MmoNetwork.C2SSpawnArenaBossPayload.TYPE,
			(payload, context) -> {
				ServerPlayer player = context.player();
				BOSS_ARENA_MANAGER.spawnArenaEncounter(player, payload.arenaId(), ENMITY_MANAGER);
			}
		);

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (handler.player != null) {
				UUID uuid = handler.player.getUUID();
				SESSIONS.remove(uuid);
				STATUS_MANAGER.clearEntity(uuid);
				ENMITY_MANAGER.removeContenderEverywhere(uuid);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(ServerCombatEngine::onServerTick);
	}

	public static ServerPlayerSession getOrCreateSession(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player.getUUID(), ServerPlayerSession::new);
	}

	public static void handleUseAbilityPacket(ServerPlayer player, MmoNetwork.C2SUseAbilityPayload payload) {
		ServerPlayerSession session = getOrCreateSession(player);
		PlayerCombatState state = session.combatState;

		if (!Double.isFinite(payload.groundX()) || !Double.isFinite(payload.groundY()) || !Double.isFinite(payload.groundZ())) {
			sendRejection(player, payload.sequenceId(), payload.abilityId(), "Invalid ground coordinates", state, null);
			return;
		}

		AbilityDefinition def = MmoDataRegistry.getAbility(payload.abilityId());
		if (def == null) {
			sendRejection(player, payload.sequenceId(), payload.abilityId(), "Unknown ability", state, null);
			return;
		}

		if (!player.isAlive() || player.isSpectator()) {
			sendRejection(player, payload.sequenceId(), def.id(), "Cannot use abilities while dead or spectating", state, def);
			return;
		}

		String validationError = validateTargetRangeLosAndFacing(
			player,
			def,
			payload.targetEntityId(),
			payload.groundX(),
			payload.groundY(),
			payload.groundZ()
		);
		if (validationError != null) {
			sendRejection(player, payload.sequenceId(), def.id(), validationError, state, def);
			return;
		}

		PlayerCombatState.AttemptResult result = state.tryUseAbility(
			def,
			payload.sequenceId(),
			payload.targetEntityId(),
			payload.groundX(),
			payload.groundY(),
			payload.groundZ(),
			true
		);

		PlayerCombatState.CooldownGroupState cd = state.getOrCreateCooldownGroup(def);
		ServerPlayNetworking.send(player, new MmoNetwork.S2CAbilityResponsePayload(
			payload.sequenceId(),
			def.id(),
			result.isAcceptedOrQueued(),
			result.message(),
			state.getGcdRemainingTicks(),
			state.getAnimLockRemainingTicks(),
			def.castTicks(),
			cd.currentCharges(),
			cd.rechargeRemainingTicks(),
			state.getMana()
		));

		if (result.immediateExecution() != null) {
			executeAbilityEffect(player, session, result.immediateExecution());
		}
	}

	private static void sendRejection(
		ServerPlayer player,
		int sequenceId,
		Identifier abilityId,
		String reason,
		PlayerCombatState state,
		@Nullable AbilityDefinition def
	) {
		int charges = 1;
		int rechargeRem = 0;
		if (def != null) {
			PlayerCombatState.CooldownGroupState cd = state.getOrCreateCooldownGroup(def);
			charges = cd.currentCharges();
			rechargeRem = cd.rechargeRemainingTicks();
		}
		ServerPlayNetworking.send(player, new MmoNetwork.S2CAbilityResponsePayload(
			sequenceId,
			abilityId,
			false,
			reason,
			state.getGcdRemainingTicks(),
			state.getAnimLockRemainingTicks(),
			0,
			charges,
			rechargeRem,
			state.getMana()
		));
	}

	public static @Nullable String validateTargetRangeLosAndFacing(
		ServerPlayer player,
		AbilityDefinition def,
		int targetEntityId,
		double groundX,
		double groundY,
		double groundZ
	) {
		ServerLevel level = player.level();

		switch (def.targetType()) {
			case SELF:
			case SELF_AOE:
				return null;

			case GROUND_AOE: {
				Vec3 groundPos = new Vec3(groundX, groundY, groundZ);
				double dist = player.position().distanceTo(groundPos);
				if (dist > def.range() + 1.5) {
					return String.format(Locale.ROOT, "Ground target out of range (%.1fm > %.1fm)", dist, def.range());
				}
				return null;
			}

			case SINGLE_ALLY: {
				LivingEntity ally = resolveTargetOrSelf(level, player, targetEntityId);
				if (!ally.isAlive()) {
					return "Target is not alive";
				}
				double dist = player.distanceTo(ally) - ally.getBbWidth() * 0.5;
				if (dist > def.range() + 0.75) {
					return String.format(Locale.ROOT, "Target out of range (%.1fm > %.1fm)", Math.max(0.0, dist), def.range());
				}
				if (ally != player && !player.hasLineOfSight(ally)) {
					return "Target not in line of sight";
				}
				return null;
			}

			case SINGLE_ENEMY: {
				Entity raw = level.getEntity(targetEntityId);
				if (!(raw instanceof LivingEntity target) || target == player || !target.isAlive()) {
					return "Invalid or missing enemy target";
				}
				double edgeDist = player.distanceTo(target) - (target.getBbWidth() * 0.5);
				if (edgeDist > def.range() + 0.75) {
					return String.format(Locale.ROOT, "Target out of range (%.1fm > %.1fm)", Math.max(0.0, edgeDist), def.range());
				}
				if (!player.hasLineOfSight(target)) {
					return "Target not in line of sight";
				}
				if (!isFacingTarget(player, target, 140.0F)) {
					return "Target is not in front of you";
				}
				return null;
			}
		}
		return null;
	}

	public static boolean isFacingTarget(LivingEntity source, Entity target, float coneDegrees) {
		return isPointInHorizontalCone(
			source.getX(), source.getZ(), source.getYRot(),
			target.getX(), target.getZ(), coneDegrees
		);
	}

	public static boolean isPointInHorizontalCone(
		double originX, double originZ, float yawDegrees,
		double targetX, double targetZ, float coneDegrees
	) {
		double dx = targetX - originX;
		double dz = targetZ - originZ;
		double lenSqr = dx * dx + dz * dz;
		if (lenSqr < 1.0E-4) {
			return true;
		}
		double len = Math.sqrt(lenSqr);
		double rad = Math.toRadians(yawDegrees);
		double forwardX = -Math.sin(rad);
		double forwardZ = Math.cos(rad);
		double dot = (dx / len) * forwardX + (dz / len) * forwardZ;
		double minDot = Math.cos(Math.toRadians(coneDegrees * 0.5));
		return dot >= minDot;
	}

	public static AbilityDefinition.Positional computeAttackerPositional(
		double targetX, double targetZ, float targetYawDegrees,
		double attackerX, double attackerZ
	) {
		double dx = attackerX - targetX;
		double dz = attackerZ - targetZ;
		if (dx * dx + dz * dz < 1.0E-5) {
			return AbilityDefinition.Positional.FRONT;
		}
		float angleToAttacker = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float relAngle = Math.abs(Mth.wrapDegrees(angleToAttacker - targetYawDegrees));
		if (relAngle <= 45.0F) {
			return AbilityDefinition.Positional.FRONT;
		} else if (relAngle >= 135.0F) {
			return AbilityDefinition.Positional.REAR;
		} else {
			return AbilityDefinition.Positional.FLANK;
		}
	}

	private static void onServerTick(MinecraftServer server) {
		long tickStartNanos = System.nanoTime();
		hudSyncCounter++;

		// 1. Tick Status Effects & 60-tick (3.0s) Periodic DoTs / HoTs
		List<StatusManager.PeriodicTickEvent> periodicTicks = STATUS_MANAGER.tick(MmoDataRegistry::getStatus);
		if (!periodicTicks.isEmpty()) {
			resolvePeriodicStatusTicks(server, periodicTicks);
		}

		// 2. Tick Boss Arena Encounters & Timed Telegraphs
		BOSS_ARENA_MANAGER.tick(server, ENMITY_MANAGER, TELEGRAPH_MANAGER);

		List<TelegraphManager.CompletedTelegraphEvent> completedTelegraphs = TELEGRAPH_MANAGER.tick();
		for (TelegraphManager.CompletedTelegraphEvent evt : completedTelegraphs) {
			ServerLevel level = server.getLevel(evt.dimension());
			if (level != null) {
				resolveCompletedTelegraph(level, evt.telegraph());
			}
		}

		if (hudSyncCounter % 2 == 0) {
			for (ServerLevel level : server.getAllLevels()) {
				if (!level.players().isEmpty()) {
					syncTelegraphsToLevel(level);
				}
			}
		}

		// 3. Tick Player Combat States & Sync Enmity / Status / Party HUD
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayerSession session = getOrCreateSession(player);
			Vec3 currentPos = player.position();
			boolean moved = session.hasLastPos && currentPos.distanceToSqr(session.lastPos) > 0.0036;
			session.lastPos = currentPos;
			session.hasLastPos = true;

			PlayerCombatState.TickResult tickResult = session.combatState.tick(moved, MmoDataRegistry::getAbility);
			if (tickResult.castInterruptedByMovement() && tickResult.interruptedAbilityId() != null) {
				ServerPlayNetworking.send(player, new MmoNetwork.S2CCastStatusPayload(
					player.getId(),
					tickResult.interruptedAbilityId(),
					false,
					true,
					"Cast interrupted by movement"
				));
			}

			for (PlayerCombatState.CompletedExecution exec : tickResult.completedExecutions()) {
				String postCheckError = validateTargetRangeLosAndFacing(
					player,
					exec.ability(),
					exec.targetEntityId(),
					exec.groundX(),
					exec.groundY(),
					exec.groundZ()
				);
				if (postCheckError != null) {
					ServerPlayNetworking.send(player, new MmoNetwork.S2CCastStatusPayload(
						player.getId(),
						exec.ability().id(),
						false,
						true,
						postCheckError
					));
				} else {
					executeAbilityEffect(player, session, exec);
					if (!exec.ability().isInstant()) {
						ServerPlayNetworking.send(player, new MmoNetwork.S2CCastStatusPayload(
							player.getId(),
							exec.ability().id(),
							true,
							false,
							"Cast complete"
						));
					}
				}
			}

			syncTargetOfTargetIfNeeded(player, session);

			if (hudSyncCounter % 5 == 0) {
				syncCombatHudState(player, session);
			}
		}

		if (hudSyncCounter % 10 == 0) {
			syncPartyState(server);
		}

		net.xivmmo.debug.MmoDebugState.recordServerTickDurationNanos(System.nanoTime() - tickStartNanos);
	}

	private static void resolvePeriodicStatusTicks(
		MinecraftServer server,
		List<StatusManager.PeriodicTickEvent> events
	) {
		for (StatusManager.PeriodicTickEvent evt : events) {
			LivingEntity target = findLivingEntityByUuid(server, evt.targetEntityUuid());
			if (target == null || !target.isAlive() || !(target.level() instanceof ServerLevel level)) {
				continue;
			}
			LivingEntity caster = evt.sourceEntityUuid() != null
				? findLivingEntityByUuid(server, evt.sourceEntityUuid())
				: null;

			StatusDefinition status = evt.status();
			if (status.dotPotency() > 0) {
				float incomingMult = STATUS_MANAGER.computeIncomingDamageMultiplier(target.getUUID(), MmoDataRegistry::getStatus);
				double armor = target.getAttributeValue(Attributes.ARMOR);
				float dotDmg = MmoDamagePipeline.computeDamage(
					status.dotPotency() * evt.stacks(),
					1.0,
					evt.snapshottedMultiplier(),
					incomingMult,
					armor,
					0.25F
				);
				DamageSource dotSource = MmoDamagePipeline.createAbilityDamageSource(
					level,
					MmoDamagePipeline.UNASPECTED_DOT_DAMAGE,
					caster
				);
				target.invulnerableTime = 0;
				if (target.hurtServer(level, dotSource, dotDmg) && caster != null) {
					float enmityMult = STATUS_MANAGER.computeEnmityMultiplier(caster.getUUID(), MmoDataRegistry::getStatus);
					ENMITY_MANAGER.addEnmity(target.getUUID(), caster.getUUID(), Math.round(dotDmg * 100.0F * enmityMult));
					updateMobEnmityTarget(level, target);
					broadcastCombatText(
						level,
						target,
						String.format(Locale.ROOT, "-%.1f (%s)", dotDmg, status.displayName()),
						0xFFFF8844,
						false
					);
				}
			}

			if (status.hotPotency() > 0) {
				float healAmt = MmoDamagePipeline.computeHeal(status.hotPotency() * evt.stacks(), evt.snapshottedMultiplier());
				target.heal(healAmt);
				broadcastCombatText(
					level,
					target,
					String.format(Locale.ROOT, "+%.1f (%s)", healAmt, status.displayName()),
					0xFF55FF88,
					false
				);
				if (caster != null) {
					float enmityMult = STATUS_MANAGER.computeEnmityMultiplier(caster.getUUID(), MmoDataRegistry::getStatus);
					ENMITY_MANAGER.addHealingEnmity(caster.getUUID(), target.getUUID(), Math.round(healAmt * 50.0F * enmityMult));
				}
			}
		}
	}

	private static void resolveCompletedTelegraph(ServerLevel level, TelegraphShapes.ActiveTelegraph t) {
		double boundR = Math.max(2.0, t.radius() + 2.0);
		AABB searchBox = new AABB(
			t.originX() - boundR, t.originY() - 4.0, t.originZ() - boundR,
			t.originX() + boundR, t.originY() + 4.0, t.originZ() + boundR
		);
		Entity sourceEntity = t.sourceEntityId() >= 0 ? level.getEntity(t.sourceEntityId()) : null;
		LivingEntity caster = sourceEntity instanceof LivingEntity living ? living : null;

		List<LivingEntity> entities = level.getEntitiesOfClass(
			LivingEntity.class,
			searchBox,
			e -> e.isAlive() && !e.isSpectator()
		);

		DamageSource source = MmoDamagePipeline.createAbilityDamageSource(
			level,
			MmoDamagePipeline.MAGICAL_ABILITY_DAMAGE,
			caster
		);

		for (LivingEntity victim : entities) {
			if (t.hostile() && !(victim instanceof ServerPlayer)) {
				continue;
			}
			if (!t.hostile() && victim == caster) {
				continue;
			}
			boolean inside = TelegraphShapes.containsPoint(
				t.shape(),
				t.originX(), t.originY(), t.originZ(),
				t.yawDegrees(),
				t.radius(),
				t.innerRadius(),
				t.coneAngleDegrees(),
				victim.getX(), victim.getY(), victim.getZ()
			);
			if (inside) {
				float incomingMult = STATUS_MANAGER.computeIncomingDamageMultiplier(victim.getUUID(), MmoDataRegistry::getStatus);
				float dmg = MmoDamagePipeline.computeDamage(
					t.potency(),
					1.0,
					1.0F,
					incomingMult,
					victim.getAttributeValue(Attributes.ARMOR),
					0.50F
				);
				victim.invulnerableTime = 0;
				victim.hurtServer(level, source, dmg);
				broadcastCombatText(
					level,
					victim,
					String.format(Locale.ROOT, "!! -%.1f (%s) !!", dmg, t.label()),
					0xFFFF3333,
					true
				);
				if (victim instanceof ServerPlayer sp) {
					sp.sendSystemMessage(Component.literal(String.format(
						Locale.ROOT,
						"[XIVMMO] Hit by telegraph '%s' (%s) for %.1f dmg!",
						t.label(),
						t.shape().getSerializedName(),
						dmg
					)).withStyle(ChatFormatting.RED));
				}
			}
		}
		syncTelegraphsToLevel(level);
	}

	private static void syncTelegraphsToLevel(ServerLevel level) {
		List<TelegraphShapes.ActiveTelegraph> active = TELEGRAPH_MANAGER.getActiveTelegraphs(level.dimension());
		MmoNetwork.S2CTelegraphSyncPayload payload = new MmoNetwork.S2CTelegraphSyncPayload(active);
		for (ServerPlayer player : level.players()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void broadcastCombatText(
		ServerLevel level,
		LivingEntity target,
		String text,
		int color,
		boolean emphasize
	) {
		MmoNetwork.S2CCombatTextPayload payload = new MmoNetwork.S2CCombatTextPayload(
			target.getId(),
			text,
			color,
			emphasize
		);
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(target) <= 48.0 * 48.0) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private static void executeAbilityEffect(
		ServerPlayer player,
		ServerPlayerSession session,
		PlayerCombatState.CompletedExecution exec
	) {
		ServerLevel level = player.level();
		AbilityDefinition def = exec.ability();
		player.swing(InteractionHand.MAIN_HAND, true);

		float attackerOutMult = STATUS_MANAGER.computeOutgoingDamageMultiplier(player.getUUID(), MmoDataRegistry::getStatus);
		float attackerEnmityMult = STATUS_MANAGER.computeEnmityMultiplier(player.getUUID(), MmoDataRegistry::getStatus);
		double attackerWeaponDmg = player.getAttributeValue(Attributes.ATTACK_DAMAGE);

		switch (def.targetType()) {
			case SELF:
			case SINGLE_ALLY: {
				LivingEntity ally = resolveTargetOrSelf(level, player, exec.targetEntityId());
				if ("shirk_step".equals(def.id().getPath())) {
					if (session.hardTargetId >= 0 && level.getEntity(session.hardTargetId) instanceof LivingEntity enemy) {
						long transferred = ENMITY_MANAGER.shirk(enemy.getUUID(), player.getUUID(), ally.getUUID(), 0.25F);
						updateMobEnmityTarget(level, enemy);
						broadcastCombatText(level, ally, "SHIRK (+25% Enmity)", 0xFFFFAA33, true);
						player.sendSystemMessage(Component.literal(String.format(
							Locale.ROOT,
							"[XIVMMO] Shirked %d enmity to %s",
							transferred,
							ally.getName().getString()
						)).withStyle(ChatFormatting.GOLD));
					}
					break;
				}

				float healAmount = MmoDamagePipeline.computeHeal(def.potency(), attackerOutMult);
				ally.heal(healAmount);

				applyOptionalStatus(player, ally, def);

				long healEnmity = Math.round(healAmount * 60.0F * attackerEnmityMult);
				ENMITY_MANAGER.addHealingEnmity(player.getUUID(), ally.getUUID(), healEnmity);

				broadcastCombatText(
					level,
					ally,
					String.format(Locale.ROOT, "+%.1f %s", healAmount, def.displayName()),
					0xFF55FF88,
					def.potency() >= 450
				);

				player.sendSystemMessage(Component.literal(String.format(
					Locale.ROOT,
					"[XIVMMO] %s healed %s for %.1f HP (%d potency)",
					def.displayName(),
					ally.getName().getString(),
					healAmount,
					def.potency()
				)).withStyle(ChatFormatting.GREEN));
				break;
			}

			case GROUND_AOE:
			case SELF_AOE: {
				Vec3 center = def.targetType() == AbilityDefinition.TargetType.SELF_AOE
					? player.position()
					: new Vec3(exec.groundX(), exec.groundY(), exec.groundZ());
				float radius = Math.max(1.5F, def.radius());

				// Spawn a brief friendly visual ground telegraph on execution
				TELEGRAPH_MANAGER.spawnTelegraph(
					level,
					player.getId(),
					def.displayName(),
					def.aoeShape() != AbilityDefinition.AoeShape.NONE ? def.aoeShape() : AbilityDefinition.AoeShape.CIRCLE,
					center.x, center.y, center.z,
					player.getYRot(),
					radius,
					def.innerRadius(),
					def.coneAngleDegrees(),
					12,
					0,
					false
				);
				syncTelegraphsToLevel(level);

				if ("radiant_halo".equals(def.id().getPath())) {
					float healAmt = MmoDamagePipeline.computeHeal(def.potency(), attackerOutMult);
					for (ServerPlayer p : level.players()) {
						if (p.position().distanceToSqr(center) <= radius * radius) {
							p.heal(healAmt);
							applyOptionalStatus(player, p, def);
							broadcastCombatText(level, p, String.format(Locale.ROOT, "+%.1f %s", healAmt, def.displayName()), 0xFF55FFAA, true);
						}
					}
					break;
				}

				AABB area = new AABB(center, center).inflate(radius);
				List<LivingEntity> victims = level.getEntitiesOfClass(
					LivingEntity.class,
					area,
					e -> e != player && e.isAlive() && !e.isSpectator() && e.position().distanceToSqr(center) <= radius * radius
				);
				DamageSource source = MmoDamagePipeline.createAbilityDamageSource(
					level,
					MmoDamagePipeline.MAGICAL_ABILITY_DAMAGE,
					player
				);
				int hitCount = 0;
				float totalDmg = 0.0F;
				for (LivingEntity victim : victims) {
					float inMult = STATUS_MANAGER.computeIncomingDamageMultiplier(victim.getUUID(), MmoDataRegistry::getStatus);
					float dmg = MmoDamagePipeline.computeDamage(
						def.potency(),
						attackerWeaponDmg,
						attackerOutMult,
						inMult,
						victim.getAttributeValue(Attributes.ARMOR),
						0.50F
					);
					victim.invulnerableTime = 0;
					if (victim.hurtServer(level, source, dmg)) {
						hitCount++;
						totalDmg = dmg;
						applyOptionalStatus(player, victim, def);
						ENMITY_MANAGER.addEnmity(victim.getUUID(), player.getUUID(), Math.round(dmg * 100.0F * attackerEnmityMult));
						updateMobEnmityTarget(level, victim);
						broadcastCombatText(
							level,
							victim,
							String.format(Locale.ROOT, "-%.1f %s", dmg, def.displayName()),
							0xFFFFCC44,
							false
						);
					}
				}
				player.sendSystemMessage(Component.literal(String.format(
					Locale.ROOT,
					"[XIVMMO] %s hit %d target(s) for %.1f dmg (%d potency)",
					def.displayName(),
					hitCount,
					totalDmg,
					def.potency()
				)).withStyle(ChatFormatting.GOLD));
				break;
			}

			case SINGLE_ENEMY: {
				Entity raw = level.getEntity(exec.targetEntityId());
				if (!(raw instanceof LivingEntity target) || !target.isAlive()) {
					return;
				}

				if ("provoke_roar".equals(def.id().getPath())) {
					long provoked = ENMITY_MANAGER.provoke(target.getUUID(), player.getUUID(), 1500L);
					applyOptionalStatus(player, target, def);
					updateMobEnmityTarget(level, target);
					broadcastCombatText(level, target, "PROVOKE! (#1 Enmity)", 0xFFFF5555, true);
					player.sendSystemMessage(Component.literal(String.format(
						Locale.ROOT,
						"[XIVMMO] Provoked %s! Enmity set to %d (#1)",
						target.getName().getString(),
						provoked
					)).withStyle(ChatFormatting.RED));
					break;
				}

				int effectivePotency = def.potency();
				boolean positionalBonusHit = false;
				if (exec.comboMatched() && def.comboPotency() > 0) {
					effectivePotency = def.comboPotency();
				}
				if (def.positional() != AbilityDefinition.Positional.NONE && def.positionalPotency() > 0) {
					AbilityDefinition.Positional actualPos = computeAttackerPositional(
						target.getX(), target.getZ(), target.getYRot(),
						player.getX(), player.getZ()
					);
					if (actualPos == def.positional() && (!def.comboPrerequisite().isPresent() || exec.comboMatched())) {
						effectivePotency = def.positionalPotency();
						positionalBonusHit = true;
					}
				}

				float inMult = STATUS_MANAGER.computeIncomingDamageMultiplier(target.getUUID(), MmoDataRegistry::getStatus);
				float dmg = MmoDamagePipeline.computeDamage(
					effectivePotency,
					attackerWeaponDmg,
					attackerOutMult,
					inMult,
					target.getAttributeValue(Attributes.ARMOR),
					0.50F
				);
				DamageSource source = MmoDamagePipeline.createAbilityDamageSource(
					level,
					def.category() == AbilityDefinition.Category.SPELL_GCD
						? MmoDamagePipeline.MAGICAL_ABILITY_DAMAGE
						: MmoDamagePipeline.PHYSICAL_ABILITY_DAMAGE,
					player
				);
				target.invulnerableTime = 0;
				target.hurtServer(level, source, dmg);

				applyOptionalStatus(player, target, def);

				long addedEnmity = Math.round(dmg * 100.0F * attackerEnmityMult);
				ENMITY_MANAGER.addEnmity(target.getUUID(), player.getUUID(), addedEnmity);
				updateMobEnmityTarget(level, target);

				String tags = (exec.comboMatched() ? " [COMBO]" : "") + (positionalBonusHit ? " [POSITIONAL!]" : "");
				broadcastCombatText(
					level,
					target,
					String.format(Locale.ROOT, "-%.1f %s%s", dmg, def.displayName(), positionalBonusHit ? "!" : ""),
					positionalBonusHit ? 0xFFFF77FF : (exec.comboMatched() ? 0xFFFFCC44 : 0xFF66EEFF),
					positionalBonusHit || exec.comboMatched()
				);

				player.sendSystemMessage(Component.literal(String.format(
					Locale.ROOT,
					"[XIVMMO] %s -> %s: %.1f dmg (%d potency)%s",
					def.displayName(),
					target.getName().getString(),
					dmg,
					effectivePotency,
					tags
				)).withStyle(positionalBonusHit ? ChatFormatting.LIGHT_PURPLE : (exec.comboMatched() ? ChatFormatting.GOLD : ChatFormatting.AQUA)));
				break;
			}
		}

		syncCombatHudState(player, session);
	}

	private static void applyOptionalStatus(ServerPlayer caster, LivingEntity primaryTarget, AbilityDefinition def) {
		if (def.appliedStatus().isEmpty()) {
			return;
		}
		StatusDefinition statusDef = MmoDataRegistry.getStatus(def.appliedStatus().get());
		if (statusDef == null) {
			return;
		}
		// Self-buffs (BUFF on SINGLE_ENEMY abilities) apply to caster; debuffs or ally buffs apply to primaryTarget
		LivingEntity recipient = (statusDef.type() == StatusDefinition.StatusType.BUFF
			&& def.targetType() == AbilityDefinition.TargetType.SINGLE_ENEMY)
			? caster
			: primaryTarget;

		STATUS_MANAGER.applyStatus(
			recipient.getUUID(),
			caster.getUUID(),
			statusDef,
			statusDef.defaultDurationTicks(),
			MmoDataRegistry::getStatus
		);
	}

	private static void updateMobEnmityTarget(ServerLevel level, LivingEntity enemy) {
		if (!(enemy instanceof Mob mob) || !mob.isAlive()) {
			return;
		}
		UUID topUuid = ENMITY_MANAGER.getTopContender(mob.getUUID());
		if (topUuid != null && level.getPlayerByUUID(topUuid) instanceof ServerPlayer topPlayer) {
			if (topPlayer.isAlive() && !topPlayer.isSpectator() && mob.distanceToSqr(topPlayer) <= 45.0 * 45.0) {
				mob.setTarget(topPlayer);
			}
		}
	}

	private static void syncPartyState(MinecraftServer server) {
		List<ServerPlayer> allPlayers = server.getPlayerList().getPlayers();
		if (allPlayers.isEmpty()) {
			return;
		}

		List<MmoNetwork.PartyMemberEntry> entries = new ArrayList<>(Math.min(8, allPlayers.size()));
		for (int i = 0; i < allPlayers.size() && i < 8; i++) {
			ServerPlayer p = allPlayers.get(i);
			ServerPlayerSession s = getOrCreateSession(p);
			JobDefinition job = MmoDataRegistry.getJob(s.activeJobId);
			String roleStr = job != null ? job.role().getSerializedName() : "tank";

			int enmRank = 0;
			if (s.hardTargetId >= 0) {
				Entity t = p.level().getEntity(s.hardTargetId);
				if (t instanceof LivingEntity living && living.isAlive()) {
					for (EnmityManager.EnmityEntry e : ENMITY_MANAGER.getSortedEnmityList(living.getUUID())) {
						if (e.contenderUuid().equals(p.getUUID())) {
							enmRank = e.rank();
							break;
						}
					}
				}
			}

			String topStatus = "";
			Collection<StatusManager.ActiveStatus> activeStatuses = STATUS_MANAGER.getActiveStatuses(p.getUUID());
			if (!activeStatuses.isEmpty()) {
				StatusDefinition sd = MmoDataRegistry.getStatus(activeStatuses.iterator().next().statusId());
				if (sd != null) {
					topStatus = sd.displayName();
				}
			}

			entries.add(new MmoNetwork.PartyMemberEntry(
				p.getId(),
				p.getName().getString(),
				s.activeJobId,
				roleStr,
				p.getHealth(),
				p.getMaxHealth(),
				s.combatState.getMana(),
				s.combatState.getMaxMana(),
				enmRank,
				topStatus
			));
		}

		for (ServerPlayer recipient : allPlayers) {
			ServerPlayerSession s = getOrCreateSession(recipient);
			ServerPlayNetworking.send(recipient, new MmoNetwork.S2CPartySyncPayload(s.activeJobId, entries));
		}
	}

	private static void syncCombatHudState(ServerPlayer player, ServerPlayerSession session) {
		List<MmoNetwork.StatusBadge> playerBadges = buildStatusBadges(player.getUUID());
		List<MmoNetwork.StatusBadge> targetBadges = List.of();
		int enmityRank = 0;
		float enmityRatio = 0.0F;

		if (session.hardTargetId >= 0) {
			Entity e = player.level().getEntity(session.hardTargetId);
			if (e instanceof LivingEntity target && target.isAlive()) {
				targetBadges = buildStatusBadges(target.getUUID());
				List<EnmityManager.EnmityEntry> list = ENMITY_MANAGER.getSortedEnmityList(target.getUUID());
				for (EnmityManager.EnmityEntry entry : list) {
					if (entry.contenderUuid().equals(player.getUUID())) {
						enmityRank = entry.rank();
						enmityRatio = entry.ratioToTop();
						break;
					}
				}
			}
		}

		ServerPlayNetworking.send(player, new MmoNetwork.S2CCombatHudSyncPayload(
			enmityRank,
			enmityRatio,
			playerBadges,
			targetBadges
		));
	}

	private static List<MmoNetwork.StatusBadge> buildStatusBadges(UUID entityUuid) {
		Collection<StatusManager.ActiveStatus> active = STATUS_MANAGER.getActiveStatuses(entityUuid);
		if (active.isEmpty()) {
			return List.of();
		}
		List<MmoNetwork.StatusBadge> badges = new ArrayList<>(active.size());
		for (StatusManager.ActiveStatus s : active) {
			StatusDefinition def = MmoDataRegistry.getStatus(s.statusId());
			if (def != null) {
				badges.add(new MmoNetwork.StatusBadge(
					def.displayName(),
					def.type() == StatusDefinition.StatusType.BUFF,
					Math.max(1, (s.remainingTicks() + 19) / 20),
					s.stacks()
				));
			}
		}
		return badges;
	}

	private static @Nullable LivingEntity findLivingEntityByUuid(MinecraftServer server, UUID uuid) {
		for (ServerLevel level : server.getAllLevels()) {
			Entity e = level.getEntity(uuid);
			if (e instanceof LivingEntity living) {
				return living;
			}
		}
		return null;
	}

	public static float computeEffectMagnitude(int potency) {
		return Math.max(0.5F, potency * 0.04F);
	}

	private static LivingEntity resolveTargetOrSelf(ServerLevel level, ServerPlayer player, int targetEntityId) {
		if (targetEntityId >= 0) {
			Entity e = level.getEntity(targetEntityId);
			if (e instanceof LivingEntity living && living.isAlive()) {
				return living;
			}
		}
		return player;
	}

	private static void syncTargetOfTargetIfNeeded(ServerPlayer player, ServerPlayerSession session) {
		if (session.hardTargetId < 0) {
			return;
		}
		Entity hard = player.level().getEntity(session.hardTargetId);
		int totId = -1;
		if (hard instanceof Mob mob && mob.getTarget() != null && mob.getTarget().isAlive()) {
			totId = mob.getTarget().getId();
		} else if (hard instanceof ServerPlayer otherPlayer) {
			ServerPlayerSession otherSession = SESSIONS.get(otherPlayer.getUUID());
			if (otherSession != null) {
				totId = otherSession.hardTargetId;
			}
		}
		if (totId != session.lastSyncedTargetOfTargetId) {
			session.lastSyncedTargetOfTargetId = totId;
			ServerPlayNetworking.send(player, new MmoNetwork.S2CTargetOfTargetPayload(session.hardTargetId, totId));
		}
	}
}
