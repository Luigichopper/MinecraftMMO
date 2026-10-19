package net.xivmmo.client.combat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.xivmmo.XivMmoMod;
import net.xivmmo.client.hud.MmoHudManager;
import net.xivmmo.client.hud.TargetFrameHud;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.client.target.ClientTargetManager;
import net.xivmmo.client.target.CursorRaycaster;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.ability.JobDefinition;
import net.xivmmo.combat.engine.PlayerCombatState;
import net.xivmmo.config.MmoConfig;
import net.xivmmo.data.MmoDataRegistry;
import net.xivmmo.network.MmoNetwork;

import java.util.List;
import java.util.Locale;

/**
 * Client-side ability predictor, packet sender/reconciler, Job hotbar switcher,
 * and rearrangeable/scalable Cast Bar & MMO Action Hotbar HUD renderer.
 */
public final class ClientCombatPredictor {
	private static final List<Identifier> PLAYABLE_JOBS = List.of(
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "hierophant"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "lancer"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "arcanist")
	);

	private static final List<Identifier> DEFAULT_ACTION_BAR = List.of(
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "iron_slash"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "sunder_edge"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "royal_crest"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "eclipse_sweep"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard_rush"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "provoke_roar"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "shirk_step"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vital_mend")
	);

	private static final ClientCombatPredictor INSTANCE = new ClientCombatPredictor();

	private final PlayerCombatState predictedState = new PlayerCombatState();
	private Identifier activeJobId = PLAYABLE_JOBS.getFirst();
	private int nextSequenceId = 1;
	private Vec3 lastClientPos = Vec3.ZERO;
	private boolean hasLastClientPos = false;
	private String lastFeedbackBanner = "";
	private long lastFeedbackTimeMs = 0L;
	private int lastFeedbackColor = 0xFFFFFFFF;

	private ClientCombatPredictor() {
	}

	public static ClientCombatPredictor get() {
		return INSTANCE;
	}

	public void registerNetworking() {
		ClientTargetManager.get().setSyncCallback((hardId, focusId) -> {
			if (ClientPlayNetworking.canSend(MmoNetwork.C2SSetTargetPayload.TYPE)) {
				net.xivmmo.debug.MmoDebugState.recordPacket("C2S", "set_target", "hard=" + hardId + " focus=" + focusId);
				ClientPlayNetworking.send(new MmoNetwork.C2SSetTargetPayload(hardId, focusId));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CAbilityResponsePayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"ability_response",
					"seq=" + payload.sequenceId() + " id=" + payload.abilityId() + " ok=" + payload.accepted() + " (" + payload.statusMessage() + ")"
				);
				onAbilityResponse(payload);
			}
		);

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CCastStatusPayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"cast_status",
					"id=" + payload.abilityId() + " done=" + payload.completed() + " intr=" + payload.interrupted()
				);
				onCastStatus(payload);
			}
		);

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CTargetOfTargetPayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"target_of_target",
					"ent=" + payload.entityId() + " -> tot=" + payload.targetOfTargetEntityId()
				);
				ClientTargetManager.get().onServerTargetOfTargetSync(
					payload.entityId(),
					payload.targetOfTargetEntityId()
				);
			}
		);

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CTelegraphSyncPayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"telegraph_sync",
					"count=" + payload.telegraphs().size()
				);
				net.xivmmo.client.render.WorldTelegraphRenderer.updateSyncedTelegraphs(payload.telegraphs());
			}
		);

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CCombatHudSyncPayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"combat_hud_sync",
					"rank=#" + payload.enmityRank() + " statuses=" + payload.playerStatuses().size()
				);
				TargetFrameHud.onCombatHudSync(payload);
			}
		);

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CPartySyncPayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"party_sync",
					"job=" + payload.activeJobId() + " members=" + payload.members().size()
				);
				TargetFrameHud.onPartySync(payload);
			}
		);

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CCombatTextPayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"combat_text",
					payload.text()
				);
				TargetFrameHud.onCombatText(payload);
			}
		);
	}

	public void setActiveJob(Identifier jobId) {
		if (MmoDataRegistry.getJob(jobId) != null) {
			this.activeJobId = jobId;
		}
	}

	public Identifier getActiveJobId() {
		return this.activeJobId;
	}

	public void cycleNextJob() {
		int idx = PLAYABLE_JOBS.indexOf(this.activeJobId);
		Identifier nextJob = PLAYABLE_JOBS.get((idx + 1) % PLAYABLE_JOBS.size());
		requestJobSwitch(nextJob);
	}

	public void requestJobSwitch(Identifier jobId) {
		JobDefinition job = MmoDataRegistry.getJob(jobId);
		if (job == null) {
			showFeedback("Unknown job: " + jobId, 0xFFFF5555);
			return;
		}
		this.activeJobId = job.id();
		showFeedback("Job: " + job.displayName() + " [" + job.role().getSerializedName().toUpperCase(Locale.ROOT) + "]", 0xFF66EEFF);
		if (ClientPlayNetworking.canSend(MmoNetwork.C2SSelectJobPayload.TYPE)) {
			net.xivmmo.debug.MmoDebugState.recordPacket("C2S", "select_job", "job=" + job.id());
			ClientPlayNetworking.send(new MmoNetwork.C2SSelectJobPayload(job.id()));
		}
	}

	public void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			this.hasLastClientPos = false;
			return;
		}

		Vec3 currentPos = player.position();
		boolean moved = this.hasLastClientPos && currentPos.distanceToSqr(this.lastClientPos) > 0.0036;
		this.lastClientPos = currentPos;
		this.hasLastClientPos = true;

		PlayerCombatState.TickResult res = this.predictedState.tick(moved, MmoDataRegistry::getAbility);
		if (res.castInterruptedByMovement()) {
			showFeedback("Cast Interrupted (Movement)", 0xFFFF5555);
		}
	}

	public List<Identifier> getActionBar() {
		JobDefinition job = MmoDataRegistry.getJob(this.activeJobId);
		if (job != null && !job.abilities().isEmpty()) {
			return job.abilities();
		}
		return DEFAULT_ACTION_BAR;
	}

	public void requestAbilitySlot(int slotIndex) {
		List<Identifier> bar = getActionBar();
		if (slotIndex < 0 || slotIndex >= bar.size()) {
			return;
		}
		requestAbility(bar.get(slotIndex));
	}

	/**
	 * Performs client-side prediction for the requested ability and dispatches {@link MmoNetwork.C2SUseAbilityPayload}.
	 */
	public void requestAbility(Identifier abilityId) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}

		AbilityDefinition def = MmoDataRegistry.getAbility(abilityId);
		if (def == null) {
			showFeedback("Unknown ability: " + abilityId, 0xFFFF5555);
			return;
		}

		LivingEntity effectiveTarget = ClientTargetManager.get().getEffectiveTarget();
		int targetEntityId = -1;
		double gx = player.getX();
		double gy = player.getY();
		double gz = player.getZ();

		switch (def.targetType()) {
			case SINGLE_ENEMY:
				if (effectiveTarget == null || effectiveTarget == player) {
					showFeedback("No valid enemy target selected", 0xFFFF6666);
					return;
				}
				targetEntityId = effectiveTarget.getId();
				if (MmoInputHandler.isFreeCursorMode() && !MmoInputHandler.isRightMouseOrbiting()) {
					faceEntity(player, effectiveTarget);
				}
				break;

			case SINGLE_ALLY:
				targetEntityId = effectiveTarget != null ? effectiveTarget.getId() : player.getId();
				break;

			case SELF:
			case SELF_AOE:
				targetEntityId = player.getId();
				break;

			case GROUND_AOE:
				Vec3 groundHit = CursorRaycaster.get().getGroundHitPos();
				if (groundHit != null) {
					gx = groundHit.x;
					gy = groundHit.y;
					gz = groundHit.z;
				} else if (effectiveTarget != null) {
					gx = effectiveTarget.getX();
					gy = effectiveTarget.getY();
					gz = effectiveTarget.getZ();
				}
				break;
		}

		int seq = this.nextSequenceId++;
		PlayerCombatState.AttemptResult pred = this.predictedState.tryUseAbility(
			def, seq, targetEntityId, gx, gy, gz, true
		);

		if (!pred.isAcceptedOrQueued()) {
			showFeedback(pred.message(), 0xFFFF8844);
			return;
		}

		if (pred.status() == PlayerCombatState.AttemptStatus.QUEUED) {
			showFeedback("Queued: " + def.displayName(), 0xFF66EEFF);
		} else {
			showFeedback(def.displayName(), 0xFF66FF99);
		}

		if (ClientPlayNetworking.canSend(MmoNetwork.C2SUseAbilityPayload.TYPE)) {
			net.xivmmo.debug.MmoDebugState.recordPacket(
				"C2S",
				"use_ability",
				"seq=" + seq + " id=" + def.id() + " tgt=" + targetEntityId
			);
			ClientPlayNetworking.send(new MmoNetwork.C2SUseAbilityPayload(
				seq, def.id(), targetEntityId, gx, gy, gz
			));
		}
	}

	private static void faceEntity(LocalPlayer player, LivingEntity target) {
		double dx = target.getX() - player.getX();
		double dz = target.getZ() - player.getZ();
		if (dx * dx + dz * dz > 1.0E-4) {
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			player.setYRot(yaw);
			player.setYHeadRot(yaw);
			player.setYBodyRot(yaw);
		}
	}

	private void onAbilityResponse(MmoNetwork.S2CAbilityResponsePayload payload) {
		AbilityDefinition def = MmoDataRegistry.getAbility(payload.abilityId());
		if (!payload.accepted()) {
			if (def != null) {
				this.predictedState.rollbackPrediction(
					def.cooldownGroup(),
					payload.gcdRemainingTicks(),
					payload.animLockRemainingTicks(),
					payload.currentMana()
				);
			}
			showFeedback("Rejected: " + payload.statusMessage(), 0xFFFF4444);
			return;
		}
		if (def != null) {
			this.predictedState.applyServerSync(
				def,
				payload.gcdRemainingTicks(),
				payload.animLockRemainingTicks(),
				payload.castTotalTicks(),
				payload.chargesRemaining(),
				payload.rechargeRemainingTicks(),
				payload.currentMana()
			);
		}
	}

	private void onCastStatus(MmoNetwork.S2CCastStatusPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && payload.casterEntityId() == mc.player.getId()) {
			if (payload.interrupted()) {
				this.predictedState.cancelActiveCast();
				showFeedback(payload.reason(), 0xFFFF4444);
			} else if (payload.completed()) {
				showFeedback("Cast Complete!", 0xFF55FF88);
			}
		}
	}

	public void showFeedback(String text, int color) {
		this.lastFeedbackBanner = text;
		this.lastFeedbackColor = color;
		this.lastFeedbackTimeMs = System.currentTimeMillis();
	}

	public PlayerCombatState getPredictedState() {
		return this.predictedState;
	}

	/**
	 * Renders the rearrangeable & scalable {@link MmoConfig.HudWidgetId#CAST_BAR} and
	 * {@link MmoConfig.HudWidgetId#ACTION_HOTBAR} widgets.
	 */
	public static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) {
			return;
		}
		if (!MmoInputHandler.isMmoModeActive()) {
			return;
		}

		ClientCombatPredictor predictor = get();
		PlayerCombatState state = predictor.predictedState;
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();

		// 1. CAST_BAR Widget (Cast Bar + Slidecast Zone + Player Statuses + MP/GCD Gauge)
		MmoHudManager.WidgetRect castRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.CAST_BAR, sw, sh
		);
		if (castRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, castRect);
			int cbWidth = MmoConfig.HudWidgetId.CAST_BAR.baseWidth();

			// Player active buffs row right above the Cast/GCD bar
			List<MmoNetwork.StatusBadge> playerStatuses = TargetFrameHud.getClientPlayerStatuses();
			if (!playerStatuses.isEmpty()) {
				TargetFrameHud.drawStatusRow(graphics, mc, playerStatuses, 0, -15);
			}

			PlayerCombatState.ActiveCast cast = state.getActiveCast();
			if (cast != null) {
				AbilityDefinition castDef = MmoDataRegistry.getAbility(cast.abilityId());
				String castName = castDef != null ? castDef.displayName() : cast.abilityId().getPath();
				float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
				float progress = Mth.clamp((cast.elapsedTicks() + partial) / Math.max(1, cast.totalTicks()), 0.0F, 1.0F);
				float remSeconds = Math.max(0.0F, (cast.totalTicks() - cast.elapsedTicks() - partial) / 20.0F);

				int cbHeight = 13;
				graphics.fill(-1, -1, cbWidth + 1, cbHeight + 1, 0xD010141E);
				graphics.fill(0, 0, cbWidth, cbHeight, 0xFF1E2430);

				float slideRatio = Mth.clamp((float) PlayerCombatState.SLIDECAST_WINDOW_TICKS / cast.totalTicks(), 0.0F, 0.5F);
				int slideW = (int) (cbWidth * slideRatio);
				if (slideW > 0) {
					graphics.fill(cbWidth - slideW, 0, cbWidth, cbHeight, 0x6044AA66);
				}

				int fillW = (int) (cbWidth * progress);
				if (fillW > 0) {
					graphics.fill(0, 0, fillW, cbHeight, 0xFFEEAA33);
				}

				graphics.text(mc.font, "CAST: " + castName, 4, 3, 0xFFFFFFFF, true);
				String timeStr = String.format(Locale.ROOT, "%.2fs", remSeconds);
				graphics.text(mc.font, timeStr, cbWidth - mc.font.width(timeStr) - 4, 3, 0xFFFFFFFF, true);
			}

			JobDefinition job = MmoDataRegistry.getJob(predictor.activeJobId);
			String jobTitle = job != null ? job.displayName() : "Vanguard";
			String mpText = String.format(
				Locale.ROOT,
				"[%s] MP: %d/%d | GCD: %.2fs",
				jobTitle,
				state.getMana(),
				state.getMaxMana(),
				state.getGcdRemainingTicks() / 20.0F
			);
			graphics.centeredText(mc.font, mpText, cbWidth / 2, 15, 0xFFB8D8FF);

			if (System.currentTimeMillis() - predictor.lastFeedbackTimeMs < 2000L && !predictor.lastFeedbackBanner.isEmpty()) {
				graphics.centeredText(mc.font, predictor.lastFeedbackBanner, cbWidth / 2, -28, predictor.lastFeedbackColor);
			}

			MmoHudManager.popWidgetTransform(graphics);
		}

		// 2. ACTION_HOTBAR Widget (can be rearranged, scaled, or completely hidden/removed via F9 or HUD Editor)
		MmoHudManager.WidgetRect hotbarRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.ACTION_HOTBAR, sw, sh
		);
		if (hotbarRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, hotbarRect);

			List<Identifier> actionBar = predictor.getActionBar();
			int slotCount = actionBar.size();
			int slotSize = 32;
			int slotGap = 5;

			Identifier queuedId = state.getQueuedAbility() != null ? state.getQueuedAbility().abilityId() : null;
			Identifier comboLastId = state.getLastComboAbilityId();

			for (int i = 0; i < slotCount; i++) {
				Identifier abilId = actionBar.get(i);
				AbilityDefinition def = MmoDataRegistry.getAbility(abilId);
				int sx = i * (slotSize + slotGap);
				int sy = 4;

				boolean isQueued = abilId.equals(queuedId);
				boolean isComboReady = def != null
					&& def.comboPrerequisite().isPresent()
					&& state.getComboRemainingTicks() > 0
					&& def.comboPrerequisite().get().equals(comboLastId);

				int borderColor = isQueued ? 0xFF55FFFF : (isComboReady ? 0xFFFFCC22 : 0xFF445066);
				graphics.fill(sx - 1, sy - 1, sx + slotSize + 1, sy + slotSize + 1, borderColor);
				graphics.fill(sx, sy, sx + slotSize, sy + slotSize, 0xD8141824);

				if (def != null) {
					PlayerCombatState.CooldownGroupState cd = state.getOrCreateCooldownGroup(def);
					int remTicks = def.isGcd()
						? Math.max(state.getGcdRemainingTicks(), cd.currentCharges() == 0 ? cd.rechargeRemainingTicks() : 0)
						: (cd.currentCharges() == 0 ? cd.rechargeRemainingTicks() : 0);
					int totalTicks = def.isGcd() ? Math.max(1, state.getGcdTotalTicks()) : Math.max(1, cd.rechargeTotalTicks());

					if (remTicks > 0) {
						float ratio = Mth.clamp((float) remTicks / totalTicks, 0.0F, 1.0F);
						int overlayH = (int) (slotSize * ratio);
						graphics.fill(sx, sy + (slotSize - overlayH), sx + slotSize, sy + slotSize, 0x98000000);
						String secStr = String.format(Locale.ROOT, "%.1f", remTicks / 20.0F);
						graphics.centeredText(mc.font, secStr, sx + slotSize / 2, sy + 12, 0xFFFFDD66);
					} else {
						String abbrev = abbreviate(def.displayName());
						graphics.centeredText(
							mc.font,
							abbrev,
							sx + slotSize / 2,
							sy + 12,
							def.isGcd() ? 0xFFE8F0FF : 0xFF77FFCC
						);
					}

					if (def.maxCharges() > 1) {
						String chg = "x" + cd.currentCharges();
						graphics.text(mc.font, chg, sx + slotSize - mc.font.width(chg) - 1, sy + slotSize - 8, 0xFF88FF88, true);
					}
				}

				if (i < net.xivmmo.client.input.MmoKeybinds.KEY_ABILITY_SLOTS.length) {
					String keyHint = net.xivmmo.client.input.MmoKeybinds.KEY_ABILITY_SLOTS[i].getTranslatedKeyMessage().getString();
					if (keyHint.length() > 3) {
						keyHint = keyHint.substring(0, 3);
					}
					graphics.text(mc.font, keyHint, sx + 2, sy + 1, 0xFFA0B0C8, true);
				}
			}

			MmoHudManager.popWidgetTransform(graphics);
		}
	}

	/**
	 * Checks whether a left-click in GUI coordinates hit one of the MMO Action Bar slots
	 * (respecting custom drag-and-drop position, scale, and visibility).
	 */
	public boolean tryClickActionBar(double guiX, double guiY, int sw, int sh) {
		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.ACTION_HOTBAR, sw, sh);
		if (!rect.enabled() || !rect.contains(guiX, guiY)) {
			return false;
		}

		double localX = (guiX - rect.x()) / Math.max(0.5F, rect.scale());
		double localY = (guiY - rect.y()) / Math.max(0.5F, rect.scale());

		int slotSize = 32;
		int slotGap = 5;
		int sy = 4;
		if (localY < sy - 1 || localY > sy + slotSize + 1) {
			return false;
		}

		List<Identifier> bar = getActionBar();
		for (int i = 0; i < bar.size(); i++) {
			int sx = i * (slotSize + slotGap);
			if (localX >= sx - 1 && localX <= sx + slotSize + 1) {
				requestAbilitySlot(i);
				return true;
			}
		}
		return false;
	}

	private static String abbreviate(String displayName) {
		String[] parts = displayName.split("\\s+");
		if (parts.length >= 2) {
			return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase(Locale.ROOT);
		}
		return displayName.substring(0, Math.min(2, displayName.length())).toUpperCase(Locale.ROOT);
	}
}
