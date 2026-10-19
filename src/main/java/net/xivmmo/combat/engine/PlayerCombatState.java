package net.xivmmo.combat.engine;

import net.minecraft.resources.Identifier;
import net.xivmmo.combat.ability.AbilityDefinition;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Deterministic MMO combat state machine shared by both the authoritative server engine
 * and the client-side predictor.
 *
 * <p>Implements:
 * <ul>
 *   <li>Global Cooldown (GCD) with configurable base length (default 50 ticks = 2.50s)</li>
 *   <li>Off-GCD (oGCD) weaving during GCD rolls, gated by Animation Lock (default 12 ticks = 0.60s)</li>
 *   <li>Hard-cast times with cast progress, slidecast window (last 10 ticks = 0.50s), and movement cancellation</li>
 *   <li>Early-press Input Queue Window (default 10 ticks = 0.50s before GCD/cast/animLock completion)</li>
 *   <li>Per-ability cooldown groups and multi-charge recharge timers</li>
 *   <li>Combo prerequisite tracking with 15-second (300 tick) timeout</li>
 *   <li>MP pool (10,000 max) with server-tick regeneration (200 MP per 60 ticks = 3.0s)</li>
 * </ul>
 */
public final class PlayerCombatState {
	public static final int DEFAULT_BASE_GCD_TICKS = 50; // 2.50s at 20 TPS
	public static final int DEFAULT_QUEUE_WINDOW_TICKS = 10; // 0.50s early input queue window
	public static final int SLIDECAST_WINDOW_TICKS = 10; // 0.50s slidecast grace window at end of cast
	public static final int COMBO_TIMEOUT_TICKS = 300; // 15.0s combo chain expiration
	public static final int MANA_REGEN_INTERVAL_TICKS = 60; // 3.0s FFXIV server tick
	public static final int MANA_REGEN_AMOUNT = 200;

	private int baseGcdTicks = DEFAULT_BASE_GCD_TICKS;
	private int queueWindowTicks = DEFAULT_QUEUE_WINDOW_TICKS;

	private int gcdRemainingTicks = 0;
	private int gcdTotalTicks = DEFAULT_BASE_GCD_TICKS;
	private int animLockRemainingTicks = 0;

	private @Nullable ActiveCast activeCast = null;
	private @Nullable QueuedAbility queuedAbility = null;

	private final Map<Identifier, CooldownGroupState> cooldownGroups = new HashMap<>();

	private @Nullable Identifier lastComboAbilityId = null;
	private int comboRemainingTicks = 0;

	private int mana = 10000;
	private int maxMana = 10000;
	private int manaTickCounter = 0;

	public enum AttemptStatus {
		STARTED_INSTANT,
		STARTED_CAST,
		QUEUED,
		REJECTED_CASTING,
		REJECTED_ANIM_LOCK,
		REJECTED_GCD,
		REJECTED_COOLDOWN,
		REJECTED_NO_MANA
	}

	public record AttemptResult(
		AttemptStatus status,
		String message,
		@Nullable CompletedExecution immediateExecution
	) {
		public boolean isAcceptedOrQueued() {
			return status == AttemptStatus.STARTED_INSTANT
				|| status == AttemptStatus.STARTED_CAST
				|| status == AttemptStatus.QUEUED;
		}
	}

	public record ActiveCast(
		int sequenceId,
		Identifier abilityId,
		int targetEntityId,
		double groundX,
		double groundY,
		double groundZ,
		int elapsedTicks,
		int totalTicks,
		boolean canMoveWhileCasting
	) {
		public ActiveCast withElapsed(int nextElapsed) {
			return new ActiveCast(
				sequenceId, abilityId, targetEntityId,
				groundX, groundY, groundZ,
				nextElapsed, totalTicks, canMoveWhileCasting
			);
		}
	}

	public record QueuedAbility(
		int sequenceId,
		Identifier abilityId,
		int targetEntityId,
		double groundX,
		double groundY,
		double groundZ
	) {
	}

	public record CompletedExecution(
		int sequenceId,
		AbilityDefinition ability,
		int targetEntityId,
		double groundX,
		double groundY,
		double groundZ,
		boolean comboMatched
	) {
	}

	public record TickResult(
		boolean castInterruptedByMovement,
		@Nullable Identifier interruptedAbilityId,
		List<CompletedExecution> completedExecutions
	) {
	}

	public static final class CooldownGroupState {
		private final Identifier groupId;
		private int maxCharges;
		private int currentCharges;
		private int rechargeRemainingTicks;
		private int rechargeTotalTicks;

		public CooldownGroupState(Identifier groupId, int maxCharges, int rechargeTotalTicks) {
			this.groupId = groupId;
			this.maxCharges = Math.max(1, maxCharges);
			this.currentCharges = this.maxCharges;
			this.rechargeRemainingTicks = 0;
			this.rechargeTotalTicks = Math.max(1, rechargeTotalTicks);
		}

		public Identifier groupId() {
			return this.groupId;
		}

		public int maxCharges() {
			return this.maxCharges;
		}

		public int currentCharges() {
			return this.currentCharges;
		}

		public int rechargeRemainingTicks() {
			return this.rechargeRemainingTicks;
		}

		public int rechargeTotalTicks() {
			return this.rechargeTotalTicks;
		}

		public void setFromServer(int charges, int remainingTicks, int totalTicks, int maxCharges) {
			this.maxCharges = Math.max(1, maxCharges);
			this.currentCharges = Math.clamp(charges, 0, this.maxCharges);
			this.rechargeRemainingTicks = Math.max(0, remainingTicks);
			this.rechargeTotalTicks = Math.max(1, totalTicks);
		}
	}

	/**
	 * Attempts to execute or queue the given ability.
	 */
	public AttemptResult tryUseAbility(
		AbilityDefinition def,
		int sequenceId,
		int targetEntityId,
		double groundX,
		double groundY,
		double groundZ,
		boolean allowQueueing
	) {
		if (this.mana < def.manaCost()) {
			return new AttemptResult(AttemptStatus.REJECTED_NO_MANA, "Not enough MP", null);
		}

		CooldownGroupState cdState = getOrCreateCooldownGroup(def);

		// Determine how many ticks remain until this ability is allowed to fire
		int blockingTicks = computeBlockingTicks(def, cdState);

		if (blockingTicks > 0) {
			if (allowQueueing && blockingTicks <= this.queueWindowTicks) {
				this.queuedAbility = new QueuedAbility(sequenceId, def.id(), targetEntityId, groundX, groundY, groundZ);
				return new AttemptResult(AttemptStatus.QUEUED, "Queued (" + blockingTicks + "t)", null);
			}
			if (this.activeCast != null) {
				return new AttemptResult(AttemptStatus.REJECTED_CASTING, "Already casting", null);
			}
			if (this.animLockRemainingTicks > 0) {
				return new AttemptResult(AttemptStatus.REJECTED_ANIM_LOCK, "Animation lock active", null);
			}
			if (def.isGcd() && this.gcdRemainingTicks > 0) {
				return new AttemptResult(AttemptStatus.REJECTED_GCD, "Global cooldown active", null);
			}
			return new AttemptResult(AttemptStatus.REJECTED_COOLDOWN, "Ability on cooldown", null);
		}

		// Clear any queued ability when firing immediately
		this.queuedAbility = null;

		if (def.isInstant()) {
			CompletedExecution exec = commitAbilityExecution(def, sequenceId, targetEntityId, groundX, groundY, groundZ, cdState);
			return new AttemptResult(AttemptStatus.STARTED_INSTANT, "Executed", exec);
		} else {
			// Start hard cast: GCD rolls immediately upon starting cast (FFXIV standard)
			int gcdLen = def.recastTicks() > 0 && def.isGcd() ? Math.min(this.baseGcdTicks, def.recastTicks()) : this.baseGcdTicks;
			if (def.isGcd()) {
				this.gcdTotalTicks = gcdLen;
				this.gcdRemainingTicks = gcdLen;
			}
			this.animLockRemainingTicks = Math.max(this.animLockRemainingTicks, Math.min(10, def.animationLockTicks()));
			this.activeCast = new ActiveCast(
				sequenceId,
				def.id(),
				targetEntityId,
				groundX,
				groundY,
				groundZ,
				0,
				def.castTicks(),
				def.canMoveWhileCasting()
			);
			return new AttemptResult(AttemptStatus.STARTED_CAST, "Casting " + def.displayName(), null);
		}
	}

	private int computeBlockingTicks(AbilityDefinition def, CooldownGroupState cdState) {
		int blocking = this.animLockRemainingTicks;
		if (this.activeCast != null) {
			int castRem = Math.max(1, this.activeCast.totalTicks() - this.activeCast.elapsedTicks());
			blocking = Math.max(blocking, castRem);
		}
		if (def.isGcd()) {
			blocking = Math.max(blocking, this.gcdRemainingTicks);
		}
		// Non-default cooldown group check
		if (cdState.currentCharges <= 0) {
			blocking = Math.max(blocking, cdState.rechargeRemainingTicks);
		}
		return blocking;
	}

	private CompletedExecution commitAbilityExecution(
		AbilityDefinition def,
		int sequenceId,
		int targetEntityId,
		double groundX,
		double groundY,
		double groundZ,
		CooldownGroupState cdState
	) {
		this.mana = Math.max(0, this.mana - def.manaCost());
		this.animLockRemainingTicks = Math.max(this.animLockRemainingTicks, def.animationLockTicks());

		if (def.isGcd()) {
			int gcdLen = this.baseGcdTicks;
			if (this.gcdRemainingTicks <= 0) {
				this.gcdTotalTicks = gcdLen;
				this.gcdRemainingTicks = gcdLen;
			}
		}

		// Spend cooldown charge if this ability uses an independent cooldown or charges
		if (!def.isGcd() || def.maxCharges() > 1 || def.recastTicks() > this.baseGcdTicks) {
			spendCharge(cdState, def.recastTicks());
		}

		boolean comboMatched = false;
		if (def.comboPrerequisite().isPresent()) {
			Identifier prereq = def.comboPrerequisite().get();
			if (this.comboRemainingTicks > 0 && prereq.equals(this.lastComboAbilityId)) {
				comboMatched = true;
			}
		}

		if (def.isGcd()) {
			this.lastComboAbilityId = def.id();
			this.comboRemainingTicks = COMBO_TIMEOUT_TICKS;
		}

		return new CompletedExecution(sequenceId, def, targetEntityId, groundX, groundY, groundZ, comboMatched);
	}

	private void spendCharge(CooldownGroupState cdState, int recastTicks) {
		int effectiveRecast = Math.max(1, recastTicks);
		cdState.rechargeTotalTicks = effectiveRecast;
		if (cdState.currentCharges == cdState.maxCharges) {
			cdState.rechargeRemainingTicks = effectiveRecast;
		}
		cdState.currentCharges = Math.max(0, cdState.currentCharges - 1);
	}

	/**
	 * Advances timers by 1 tick (50ms), handles movement cast interruption, completes finished casts,
	 * and pops any queued ability whose blocking window has cleared.
	 */
	public TickResult tick(boolean movedThisTick, Function<Identifier, @Nullable AbilityDefinition> abilityLookup) {
		List<CompletedExecution> completions = new ArrayList<>(2);
		boolean castInterrupted = false;
		Identifier interruptedId = null;

		// 1. Advance GCD & Animation Lock
		if (this.gcdRemainingTicks > 0) {
			this.gcdRemainingTicks--;
		}
		if (this.animLockRemainingTicks > 0) {
			this.animLockRemainingTicks--;
		}
		if (this.comboRemainingTicks > 0) {
			this.comboRemainingTicks--;
			if (this.comboRemainingTicks == 0) {
				this.lastComboAbilityId = null;
			}
		}

		// 2. Advance Cooldown Group Recharge Timers
		for (CooldownGroupState cd : this.cooldownGroups.values()) {
			if (cd.currentCharges < cd.maxCharges) {
				if (cd.rechargeRemainingTicks > 0) {
					cd.rechargeRemainingTicks--;
				}
				if (cd.rechargeRemainingTicks <= 0) {
					cd.currentCharges++;
					if (cd.currentCharges < cd.maxCharges) {
						cd.rechargeRemainingTicks = cd.rechargeTotalTicks;
					} else {
						cd.rechargeRemainingTicks = 0;
					}
				}
			}
		}

		// 3. Advance MP Regeneration (every 60 ticks = 3.0s)
		this.manaTickCounter++;
		if (this.manaTickCounter >= MANA_REGEN_INTERVAL_TICKS) {
			this.manaTickCounter = 0;
			if (this.mana < this.maxMana) {
				this.mana = Math.min(this.maxMana, this.mana + MANA_REGEN_AMOUNT);
			}
		}

		// 4. Advance Active Hard Cast
		if (this.activeCast != null) {
			int remainingCastTicks = this.activeCast.totalTicks() - this.activeCast.elapsedTicks();
			boolean inSlidecastWindow = remainingCastTicks <= SLIDECAST_WINDOW_TICKS;

			if (movedThisTick && !this.activeCast.canMoveWhileCasting() && !inSlidecastWindow) {
				castInterrupted = true;
				interruptedId = this.activeCast.abilityId();
				this.activeCast = null;
				this.gcdRemainingTicks = 0;
				this.animLockRemainingTicks = 0;
			} else {
				int nextElapsed = this.activeCast.elapsedTicks() + 1;
				if (nextElapsed >= this.activeCast.totalTicks()) {
					ActiveCast finished = this.activeCast;
					this.activeCast = null;
					AbilityDefinition def = abilityLookup.apply(finished.abilityId());
					if (def != null && this.mana >= def.manaCost()) {
						CooldownGroupState cdState = getOrCreateCooldownGroup(def);
						completions.add(commitAbilityExecution(
							def,
							finished.sequenceId(),
							finished.targetEntityId(),
							finished.groundX(),
							finished.groundY(),
							finished.groundZ(),
							cdState
						));
					}
				} else {
					this.activeCast = this.activeCast.withElapsed(nextElapsed);
				}
			}
		}

		// 5. Pop Queued Ability if ready on this tick
		if (this.queuedAbility != null && this.activeCast == null) {
			QueuedAbility queued = this.queuedAbility;
			AbilityDefinition def = abilityLookup.apply(queued.abilityId());
			if (def == null) {
				this.queuedAbility = null;
			} else {
				CooldownGroupState cdState = getOrCreateCooldownGroup(def);
				if (computeBlockingTicks(def, cdState) <= 0) {
					this.queuedAbility = null;
					AttemptResult res = tryUseAbility(
						def,
						queued.sequenceId(),
						queued.targetEntityId(),
						queued.groundX(),
						queued.groundY(),
						queued.groundZ(),
						false
					);
					if (res.immediateExecution() != null) {
						completions.add(res.immediateExecution());
					}
				}
			}
		}

		return new TickResult(castInterrupted, interruptedId, completions);
	}

	public CooldownGroupState getOrCreateCooldownGroup(AbilityDefinition def) {
		return this.cooldownGroups.computeIfAbsent(
			def.cooldownGroup(),
			id -> new CooldownGroupState(id, def.maxCharges(), def.recastTicks())
		);
	}

	public @Nullable CooldownGroupState getCooldownGroup(Identifier groupId) {
		return this.cooldownGroups.get(groupId);
	}

	public void cancelActiveCast() {
		if (this.activeCast != null) {
			this.activeCast = null;
			this.gcdRemainingTicks = 0;
		}
	}

	public void rollbackPrediction(Identifier cooldownGroupId, int gcdRem, int animLockRem, int manaVal) {
		this.activeCast = null;
		this.queuedAbility = null;
		this.gcdRemainingTicks = Math.max(0, gcdRem);
		this.animLockRemainingTicks = Math.max(0, animLockRem);
		this.mana = Math.clamp(manaVal, 0, this.maxMana);
	}

	public void applyServerSync(
		AbilityDefinition def,
		int gcdRem,
		int animLockRem,
		int castTotalTicks,
		int chargesRemaining,
		int rechargeRemainingTicks,
		int manaVal
	) {
		this.gcdRemainingTicks = Math.max(0, gcdRem);
		if (gcdRem > 0) {
			this.gcdTotalTicks = Math.max(this.baseGcdTicks, gcdRem);
		}
		this.animLockRemainingTicks = Math.max(0, animLockRem);
		this.mana = Math.clamp(manaVal, 0, this.maxMana);
		CooldownGroupState cd = getOrCreateCooldownGroup(def);
		cd.setFromServer(chargesRemaining, rechargeRemainingTicks, def.recastTicks(), def.maxCharges());
	}

	public int getBaseGcdTicks() {
		return this.baseGcdTicks;
	}

	public void setBaseGcdTicks(int baseGcdTicks) {
		this.baseGcdTicks = Math.max(10, baseGcdTicks);
	}

	public int getQueueWindowTicks() {
		return this.queueWindowTicks;
	}

	public void setQueueWindowTicks(int queueWindowTicks) {
		this.queueWindowTicks = Math.max(0, queueWindowTicks);
	}

	public int getGcdRemainingTicks() {
		return this.gcdRemainingTicks;
	}

	public int getGcdTotalTicks() {
		return this.gcdTotalTicks;
	}

	public int getAnimLockRemainingTicks() {
		return this.animLockRemainingTicks;
	}

	public @Nullable ActiveCast getActiveCast() {
		return this.activeCast;
	}

	public @Nullable QueuedAbility getQueuedAbility() {
		return this.queuedAbility;
	}

	public @Nullable Identifier getLastComboAbilityId() {
		return this.lastComboAbilityId;
	}

	public int getComboRemainingTicks() {
		return this.comboRemainingTicks;
	}

	public int getMana() {
		return this.mana;
	}

	public void setMana(int mana) {
		this.mana = Math.clamp(mana, 0, this.maxMana);
	}

	public int getMaxMana() {
		return this.maxMana;
	}
}
