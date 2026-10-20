package net.xivmmo.client.combat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.xivmmo.XivMmoMod;
import net.xivmmo.client.hud.FfxivTheme;
import net.xivmmo.client.hud.MmoHudManager;
import net.xivmmo.client.hud.TargetFrameHud;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.client.input.MmoKeybinds;
import net.xivmmo.client.target.ClientTargetManager;
import net.xivmmo.client.target.CursorRaycaster;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.ability.JobDefinition;
import net.xivmmo.combat.engine.PlayerCombatState;
import net.xivmmo.config.MmoConfig;
import net.xivmmo.data.MmoDataRegistry;
import net.xivmmo.inventory.MmoInventoryState;
import net.xivmmo.network.MmoNetwork;
import net.xivmmo.progression.JobProgressionState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side ability predictor, packet sender/reconciler, Job & Progression state holder,
 * 140-slot Inventory client cache, and FFXIV Cast Bar & 12-Slot Action Hotbar renderer.
 */
public final class ClientCombatPredictor {
	public static final List<Identifier> PLAYABLE_JOBS = List.of(
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "hierophant"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "lancer"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "arcanist")
	);

	public static final List<Identifier> ROLE_ACTIONS = List.of(
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "second_wind"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "aether_sprint"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "limit_surge"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "sanctuary_ward")
	);

	private static final List<Identifier> DEFAULT_JOB_ACTIONS = List.of(
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

	// Client-synced FFXIV Progression State
	private final Map<Identifier, JobProgressionState.JobLevelEntry> clientJobProgress = new LinkedHashMap<>();
	private long clientGil = 12500L;
	private int clientArmouryBonusPercent = 0;

	// Client-synced FFXIV 140-Slot Inventory + 35-Slot Key/Crystal State
	private final MmoInventoryState clientInventory = new MmoInventoryState();

	// Custom hotbar slot assignments per Job (from Actions & Traits 'P' window)
	private final Map<Identifier, Identifier[]> customHotbarOverrides = new LinkedHashMap<>();

	private ClientCombatPredictor() {
		for (Identifier job : PLAYABLE_JOBS) {
			clientJobProgress.put(job, new JobProgressionState.JobLevelEntry(
				job, 1, 0L, JobProgressionState.getExpForNextLevel(1)
			));
		}
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

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CProgressionSyncPayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"progression_sync",
					"job=" + payload.activeJobId() + " gil=" + payload.gil()
				);
				this.activeJobId = payload.activeJobId();
				this.clientGil = payload.gil();
				this.clientArmouryBonusPercent = payload.armouryBonusPercent();
				for (JobProgressionState.JobLevelEntry entry : payload.jobs()) {
					this.clientJobProgress.put(entry.jobId(), entry);
				}
			}
		);

		ClientPlayNetworking.registerGlobalReceiver(
			MmoNetwork.S2CInventorySyncPayload.TYPE,
			(payload, context) -> {
				net.xivmmo.debug.MmoDebugState.recordPacket(
					"S2C",
					"inventory_sync",
					"gil=" + payload.gil() + " expBag=" + payload.expandedBag().size()
				);
				this.clientGil = payload.gil();
				this.clientInventory.applyClientSync(payload.expandedBag(), payload.keyItemsAndCrystals());
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

	public int getActiveJobLevel() {
		return getJobProgress(this.activeJobId).level();
	}

	public JobProgressionState.JobLevelEntry getJobProgress(Identifier jobId) {
		return this.clientJobProgress.getOrDefault(
			jobId,
			new JobProgressionState.JobLevelEntry(jobId, 1, 0L, JobProgressionState.getExpForNextLevel(1))
		);
	}

	public Map<Identifier, JobProgressionState.JobLevelEntry> getAllJobProgress() {
		return Collections.unmodifiableMap(this.clientJobProgress);
	}

	public long getClientGil() {
		return this.clientGil;
	}

	public int getClientArmouryBonusPercent() {
		return this.clientArmouryBonusPercent;
	}

	public ItemStack getClientInventorySlot(int index) {
		Minecraft mc = Minecraft.getInstance();
		return this.clientInventory.getSlot(mc.player, index);
	}

	public int getUsedMainInventoryCount() {
		Minecraft mc = Minecraft.getInstance();
		return this.clientInventory.getOccupiedMainSlotCount(mc.player);
	}

	public void sendInventoryAction(MmoNetwork.InventoryActionType action, int slotA, int slotB) {
		if (ClientPlayNetworking.canSend(MmoNetwork.C2SInventoryActionPayload.TYPE)) {
			ClientPlayNetworking.send(new MmoNetwork.C2SInventoryActionPayload(action, slotA, slotB));
		}
	}

	public void sendLevelCommand(Identifier jobId, int value, boolean setLevelMode) {
		if (ClientPlayNetworking.canSend(MmoNetwork.C2SLevelCommandPayload.TYPE)) {
			ClientPlayNetworking.send(new MmoNetwork.C2SLevelCommandPayload(setLevelMode, value));
		}
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
		showFeedback(
			"Job: " + job.displayName() + " (Lv. " + getJobProgress(job.id()).level() + ")",
			0xFF66EEFF
		);
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

	/**
	 * Returns the 12-slot FFXIV Action Bar for the current Job (slots 0..7 = Job actions, slots 8..11 = Role actions),
	 * respecting any custom slot assignments made in the Actions & Traits ('P') window.
	 */
	public List<Identifier> getActionBar() {
		JobDefinition job = MmoDataRegistry.getJob(this.activeJobId);
		List<Identifier> baseJob = (job != null && !job.abilities().isEmpty()) ? job.abilities() : DEFAULT_JOB_ACTIONS;
		List<Identifier> fullBar = new ArrayList<>(12);
		for (int i = 0; i < 8; i++) {
			fullBar.add(i < baseJob.size() ? baseJob.get(i) : DEFAULT_JOB_ACTIONS.get(i % DEFAULT_JOB_ACTIONS.size()));
		}
		fullBar.addAll(ROLE_ACTIONS);

		Identifier[] overrides = this.customHotbarOverrides.get(this.activeJobId);
		if (overrides != null) {
			for (int i = 0; i < Math.min(12, overrides.length); i++) {
				if (overrides[i] != null) {
					fullBar.set(i, overrides[i]);
				}
			}
		}
		return fullBar;
	}

	public void assignAbilityToHotbarSlot(int slotIndex, Identifier abilityId) {
		if (slotIndex < 0 || slotIndex >= 12) {
			return;
		}
		if (MmoConfig.get().hotbarLocked) {
			showFeedback("Hotbar is locked! Click the padlock icon to unlock.", 0xFFFF8844);
			return;
		}
		Identifier[] overrides = this.customHotbarOverrides.computeIfAbsent(this.activeJobId, k -> new Identifier[12]);
		overrides[slotIndex] = abilityId;
		AbilityDefinition def = MmoDataRegistry.getAbility(abilityId);
		showFeedback("Assigned " + (def != null ? def.displayName() : abilityId.getPath()) + " to Slot " + MmoKeybinds.FFXIV_HOTBAR_LABELS[slotIndex], 0xFFFFDF66);
	}

	public void requestAbilitySlot(int slotIndex) {
		List<Identifier> bar = getActionBar();
		if (slotIndex < 0 || slotIndex >= bar.size()) {
			return;
		}
		int unlockLv = JobProgressionState.getSlotUnlockLevel(slotIndex);
		int currentLv = getActiveJobLevel();
		if (currentLv < unlockLv) {
			AbilityDefinition def = MmoDataRegistry.getAbility(bar.get(slotIndex));
			String name = def != null ? def.displayName() : bar.get(slotIndex).getPath();
			showFeedback(name + " unlocks at Job Lv. " + unlockLv + " (Current: Lv. " + currentLv + ")", 0xFFFF6655);
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
	 * Renders the FFXIV-styled {@link MmoConfig.HudWidgetId#CAST_BAR} and
	 * 12-Slot {@link MmoConfig.HudWidgetId#ACTION_HOTBAR} widgets.
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

		// 1. CAST_BAR Widget (FFXIV Cast Progress Bar + Slidecast Zone + Player Statuses)
		MmoHudManager.WidgetRect castRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.CAST_BAR, sw, sh
		);
		if (castRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, castRect);
			int cbWidth = MmoConfig.HudWidgetId.CAST_BAR.baseWidth();

			// Player active buffs row right above the Cast bar
			List<MmoNetwork.StatusBadge> playerStatuses = TargetFrameHud.getClientPlayerStatuses();
			if (!playerStatuses.isEmpty()) {
				TargetFrameHud.drawStatusRow(graphics, mc, playerStatuses, 0, -16);
			}

			PlayerCombatState.ActiveCast cast = state.getActiveCast();
			if (cast != null) {
				AbilityDefinition castDef = MmoDataRegistry.getAbility(cast.abilityId());
				String castName = castDef != null ? castDef.displayName() : cast.abilityId().getPath();
				float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
				float progress = Mth.clamp((cast.elapsedTicks() + partial) / Math.max(1, cast.totalTicks()), 0.0F, 1.0F);
				float remSeconds = Math.max(0.0F, (cast.totalTicks() - cast.elapsedTicks() - partial) / 20.0F);

				int cbHeight = 9;
				graphics.fill(-2, 8, cbWidth + 2, 8 + cbHeight + 3, 0xDD121318);
				graphics.outline(-2, 8, cbWidth + 4, cbHeight + 3, FfxivTheme.GOLD_RULE);
				FfxivTheme.drawGaugeBar(graphics, 0, 10, cbWidth, cbHeight - 1, progress, 0xFFE6A832, 0xFFFFDF88);

				float slideRatio = Mth.clamp((float) PlayerCombatState.SLIDECAST_WINDOW_TICKS / cast.totalTicks(), 0.0F, 0.5F);
				int slideW = (int) (cbWidth * slideRatio);
				if (slideW > 0) {
					graphics.fill(cbWidth - slideW, 10, cbWidth, 10 + cbHeight - 1, 0x7055FF88);
				}

				graphics.text(mc.font, castName, 2, -1, FfxivTheme.TEXT_CREAM, true);
				String timeStr = String.format(Locale.ROOT, "%.2f", remSeconds);
				graphics.text(mc.font, timeStr, cbWidth - mc.font.width(timeStr) - 2, -1, FfxivTheme.TEXT_GOLD, true);
			}

			if (System.currentTimeMillis() - predictor.lastFeedbackTimeMs < 2000L && !predictor.lastFeedbackBanner.isEmpty()) {
				graphics.centeredText(mc.font, predictor.lastFeedbackBanner, cbWidth / 2, -26, predictor.lastFeedbackColor);
			}

			MmoHudManager.popWidgetTransform(graphics);
		}

		// 2. ACTION_HOTBAR Widget: Authentic 12-Slot FFXIV Hotbar (Page Badge '1' + 12 Slots + Padlock)
		MmoHudManager.WidgetRect hotbarRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.ACTION_HOTBAR, sw, sh
		);
		if (hotbarRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, hotbarRect);

			List<Identifier> actionBar = predictor.getActionBar();
			int slotCount = Math.min(12, actionBar.size());
			int slotSize = 24;
			int slotGap = 2;
			int startX = 18;
			int sy = 5;

			// Left Page Badge ('1' for primary bar, cycles Job when clicked)
			int jobPageNum = PLAYABLE_JOBS.indexOf(predictor.activeJobId) + 1;
			FfxivTheme.drawHotbarPageBadge(graphics, mc.font, 0, sy - 1, Math.max(1, jobPageNum));

			Identifier queuedId = state.getQueuedAbility() != null ? state.getQueuedAbility().abilityId() : null;
			Identifier comboLastId = state.getLastComboAbilityId();
			int currentJobLv = predictor.getActiveJobLevel();

			for (int i = 0; i < slotCount; i++) {
				Identifier abilId = actionBar.get(i);
				AbilityDefinition def = MmoDataRegistry.getAbility(abilId);
				int sx = startX + i * (slotSize + slotGap);

				int unlockLv = JobProgressionState.getSlotUnlockLevel(i);
				boolean unlocked = currentJobLv >= unlockLv;
				boolean isQueued = abilId.equals(queuedId);
				boolean isComboReady = unlocked && def != null
					&& def.comboPrerequisite().isPresent()
					&& state.getComboRemainingTicks() > 0
					&& def.comboPrerequisite().get().equals(comboLastId);

				// Dark beveled slot well
				FfxivTheme.drawRecessedSlot(graphics, sx - 1, sy - 1, slotSize + 2, false, isComboReady, 0);

				if (def != null) {
					FfxivTheme.drawAbilityIcon(graphics, sx, sy, slotSize, def, unlocked);

					if (unlocked) {
						PlayerCombatState.CooldownGroupState cd = state.getOrCreateCooldownGroup(def);
						int remTicks = def.isGcd()
							? Math.max(state.getGcdRemainingTicks(), cd.currentCharges() == 0 ? cd.rechargeRemainingTicks() : 0)
							: (cd.currentCharges() == 0 ? cd.rechargeRemainingTicks() : 0);
						int totalTicks = def.isGcd() ? Math.max(1, state.getGcdTotalTicks()) : Math.max(1, cd.rechargeTotalTicks());

						if (remTicks > 0) {
							float ratio = Mth.clamp((float) remTicks / totalTicks, 0.0F, 1.0F);
							int overlayH = (int) (slotSize * ratio);
							graphics.fill(sx + 1, sy + (slotSize - overlayH), sx + slotSize - 1, sy + slotSize - 1, 0xAA050608);
							String secStr = remTicks >= 20
								? Integer.toString((remTicks + 19) / 20)
								: String.format(Locale.ROOT, ".%d", (remTicks * 10) / 20);
							graphics.centeredText(mc.font, secStr, sx + slotSize / 2, sy + 8, 0xFFFFDF77);
						}

						// Bottom-left MP cost in magenta (just like FFXIV screenshot #2: '400', '300')
						if (def.manaCost() > 0) {
							String mpCostStr = Integer.toString(def.manaCost());
							graphics.pose().pushMatrix();
							graphics.pose().translate(sx + 2.0F, sy + slotSize - 6.0F);
							graphics.pose().scale(0.65F, 0.65F);
							graphics.text(mc.font, mpCostStr, 0, 0, FfxivTheme.TEXT_MP_MAGENTA, true);
							graphics.pose().popMatrix();
						} else if (def.maxCharges() > 1) {
							String chg = "x" + cd.currentCharges();
							graphics.pose().pushMatrix();
							graphics.pose().translate(sx + slotSize - 9.0F, sy + slotSize - 6.0F);
							graphics.pose().scale(0.65F, 0.65F);
							graphics.text(mc.font, chg, 0, 0, FfxivTheme.TEXT_HP_GREEN, true);
							graphics.pose().popMatrix();
						}
					} else {
						// Locked slot indicator ('LvXX')
						String lvReq = "L" + unlockLv;
						graphics.pose().pushMatrix();
						graphics.pose().translate(sx + 4.0F, sy + 10.0F);
						graphics.pose().scale(0.70F, 0.70F);
						graphics.text(mc.font, lvReq, 0, 0, 0xFFFF7766, true);
						graphics.pose().popMatrix();
					}
				}

				// Combo golden marching-ants border or Queued cyan highlight
				if (isComboReady) {
					boolean pulse = (System.currentTimeMillis() / 180L) % 2L == 0L;
					graphics.outline(sx - 1, sy - 1, slotSize + 2, slotSize + 2, pulse ? 0xFFFFEA55 : 0xFFFFAA00);
				} else if (isQueued) {
					graphics.outline(sx - 1, sy - 1, slotSize + 2, slotSize + 2, 0xFF55FFFF);
				}

				// Top-left crisp FFXIV keybind number ('1'..'9', '0', '-', '=')
				String keyLabel = i < MmoKeybinds.FFXIV_HOTBAR_LABELS.length
					? MmoKeybinds.FFXIV_HOTBAR_LABELS[i]
					: Integer.toString(i + 1);
				graphics.pose().pushMatrix();
				graphics.pose().translate(sx + 2.0F, sy + 1.0F);
				graphics.pose().scale(0.75F, 0.75F);
				graphics.text(mc.font, keyLabel, 0, 0, 0xFFFFFFFF, true);
				graphics.pose().popMatrix();
			}

			// Right Padlock icon
			int lockX = startX + slotCount * (slotSize + slotGap) + 2;
			FfxivTheme.drawPadlockIcon(graphics, lockX, sy + 5, MmoConfig.get().hotbarLocked);

			MmoHudManager.popWidgetTransform(graphics);
		}
	}

	/**
	 * Checks whether a left-click in GUI coordinates hit one of the 12 MMO Action Bar slots,
	 * the left Page Badge (cycles Job), or the right Padlock icon.
	 */
	public boolean tryClickActionBar(double guiX, double guiY, int sw, int sh) {
		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.ACTION_HOTBAR, sw, sh);
		if (!rect.enabled() || !rect.contains(guiX, guiY)) {
			return false;
		}

		double localX = (guiX - rect.x()) / Math.max(0.5F, rect.scale());
		double localY = (guiY - rect.y()) / Math.max(0.5F, rect.scale());

		int slotSize = 24;
		int slotGap = 2;
		int startX = 18;
		int sy = 5;
		if (localY < sy - 2 || localY > sy + slotSize + 2) {
			return false;
		}

		// Left Page Badge click -> cycle Job
		if (localX >= 0 && localX <= 15) {
			cycleNextJob();
			return true;
		}

		List<Identifier> bar = getActionBar();
		int slotCount = Math.min(12, bar.size());
		for (int i = 0; i < slotCount; i++) {
			int sx = startX + i * (slotSize + slotGap);
			if (localX >= sx - 1 && localX <= sx + slotSize + 1) {
				requestAbilitySlot(i);
				return true;
			}
		}

		// Right Padlock click -> toggle hotbar lock
		int lockX = startX + slotCount * (slotSize + slotGap) + 2;
		if (localX >= lockX && localX <= lockX + 14) {
			MmoConfig cfg = MmoConfig.get();
			cfg.hotbarLocked = !cfg.hotbarLocked;
			cfg.save();
			showFeedback("Hotbar " + (cfg.hotbarLocked ? "LOCKED" : "UNLOCKED"), FfxivTheme.TEXT_GOLD);
			return true;
		}
		return false;
	}
}
