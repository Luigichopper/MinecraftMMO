package net.xivmmo.progression;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.xivmmo.XivMmoMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FFXIV-inspired Armoury & Job Leveling System (Lv. 1 to Lv. 50 per Job),
 * Primary/Secondary Attribute Calculator, Chain Bonus tracker, and Gil pouch.
 *
 * <p>Core Mechanics:
 * <ul>
 *   <li>Each Job ({@code vanguard}, {@code hierophant}, {@code lancer}, {@code arcanist}) tracks its own independent
 *       Level ({@code 1..50}) and Experience Points ({@code EXP}).</li>
 *   <li><b>Armoury Bonus</b>: Any Job whose level is lower than the player's highest-level Job receives a
 *       {@code +100%} EXP bonus (or {@code +50%} at Lv. 30+), matching FFXIV's Armoury System.</li>
 *   <li><b>Consecutive Chain Bonus</b>: Defeating or striking enemies within 20 seconds builds a Chain (#1..#5, +20% EXP/Gil per stack).</li>
 *   <li><b>Level-Gated Actions</b>: Abilities unlock at levels {@code 1, 1, 2, 4, 6, 10, 15, 20} (with optional config override).</li>
 *   <li><b>FFXIV Attributes</b>: Computes Strength, Dexterity, Vitality, Intelligence, Mind, Piety, Critical Hit,
 *       Determination, Defense, Magic Defense, Attack Power, Skill Speed, and Spell Speed from Job + Level + Gear.</li>
 * </ul>
 */
public final class JobProgressionState {
	public static final int MIN_LEVEL = 1;
	public static final int MAX_LEVEL = 50;

	public static final List<Identifier> ALL_JOBS = List.of(
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "hierophant"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "lancer"),
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "arcanist")
	);

	/** Default unlock levels for the 8 Job slots + 4 Role/General slots (12 total slots). */
	private static final int[] SLOT_UNLOCK_LEVELS = {
		1, 1, 2, 4, 6, 10, 15, 20, // Job slots 1..8
		1, 1, 10, 5                // Role/General slots 9, 0, -, =
	};

	public record JobLevelEntry(Identifier jobId, int level, long currentExp, long nextLevelExp) {
		public static final StreamCodec<FriendlyByteBuf, JobLevelEntry> STREAM_CODEC = StreamCodec.of(
			(buf, e) -> {
				buf.writeIdentifier(e.jobId());
				buf.writeVarInt(e.level());
				buf.writeVarLong(e.currentExp());
				buf.writeVarLong(e.nextLevelExp());
			},
			buf -> new JobLevelEntry(
				buf.readIdentifier(),
				buf.readVarInt(),
				buf.readVarLong(),
				buf.readVarLong()
			)
		);
	}

	public record ExpGainResult(
		Identifier jobId,
		long baseExp,
		long totalExpGained,
		int bonusPercent,
		int chainCount,
		int oldLevel,
		int newLevel,
		long currentExp,
		long nextLevelExp,
		long gilGained
	) {
		public boolean leveledUp() {
			return this.newLevel > this.oldLevel;
		}

		public String formatChatLogMessage(String jobDisplayName) {
			if (this.bonusPercent > 0) {
				return String.format(
					Locale.ROOT,
					"You gain %d (+%d%%) %s experience points.",
					this.totalExpGained,
					this.bonusPercent,
					jobDisplayName.toLowerCase(Locale.ROOT)
				);
			}
			return String.format(
				Locale.ROOT,
				"You gain %d %s experience points.",
				this.totalExpGained,
				jobDisplayName.toLowerCase(Locale.ROOT)
			);
		}
	}

	public record JobAttributes(
		int level,
		int displayMaxHp,
		int displayMaxMp,
		int displayMaxTp,
		int strength,
		int dexterity,
		int vitality,
		int intelligence,
		int mind,
		int piety,
		int accuracy,
		int criticalHitRate,
		int determination,
		int defense,
		int parry,
		int magicDefense,
		int attackPower,
		int skillSpeed,
		int attackMagicPotency,
		int healingMagicPotency,
		int spellSpeed
	) {
	}

	private final Map<Identifier, Integer> jobLevels = new LinkedHashMap<>();
	private final Map<Identifier, Long> jobExps = new LinkedHashMap<>();
	private long gil = 12500L;
	private int chainCount = 0;
	private long lastExpGainTimeMs = 0L;

	public JobProgressionState() {
		for (Identifier jobId : ALL_JOBS) {
			this.jobLevels.put(jobId, 1);
			this.jobExps.put(jobId, 0L);
		}
	}

	/**
	 * Smooth FFXIV-inspired experience curve from Level 1 to Level 50.
	 * Lv 1 -> 300 EXP, Lv 10 -> 4,500 EXP, Lv 25 -> 48,000 EXP, Lv 35 -> 125,000 EXP, Lv 49 -> 340,000 EXP.
	 */
	public static long getExpForNextLevel(int level) {
		int clamped = Math.clamp(level, MIN_LEVEL, MAX_LEVEL);
		if (clamped >= MAX_LEVEL) {
			return 0L;
		}
		return Math.round(200.0 + 95.0 * clamped * clamped + 12.0 * clamped * clamped * clamped);
	}

	public static int getSlotUnlockLevel(int slotIndex) {
		if (slotIndex < 0 || slotIndex >= SLOT_UNLOCK_LEVELS.length) {
			return 1;
		}
		return SLOT_UNLOCK_LEVELS[slotIndex];
	}

	public static boolean isSlotUnlockedForLevel(int slotIndex, int jobLevel) {
		return jobLevel >= getSlotUnlockLevel(slotIndex);
	}

	public int getLevel(Identifier jobId) {
		return this.jobLevels.getOrDefault(jobId, 1);
	}

	public long getExp(Identifier jobId) {
		return this.jobExps.getOrDefault(jobId, 0L);
	}

	public int getHighestJobLevel() {
		int max = 1;
		for (int lv : this.jobLevels.values()) {
			if (lv > max) {
				max = lv;
			}
		}
		return max;
	}

	/**
	 * Computes FFXIV Armoury Bonus percentage:
	 * +100% if active Job is below the player's highest Job level (and below Lv 30),
	 * +50% if below highest Job level at Lv 30+.
	 */
	public int getArmouryBonusPercent(Identifier jobId) {
		int curLv = getLevel(jobId);
		int maxLv = getHighestJobLevel();
		if (curLv < maxLv) {
			return curLv < 30 ? 100 : 50;
		}
		return 0;
	}

	public void setLevel(Identifier jobId, int targetLevel) {
		int clamped = Math.clamp(targetLevel, MIN_LEVEL, MAX_LEVEL);
		this.jobLevels.put(jobId, clamped);
		this.jobExps.put(jobId, 0L);
	}

	public long getGil() {
		return this.gil;
	}

	public void setGil(long gil) {
		this.gil = Math.max(0L, gil);
	}

	public void addGil(long amount) {
		if (amount > 0) {
			this.gil = Math.max(0L, this.gil + amount);
		}
	}

	/**
	 * Awards combat/completion EXP and Gil to {@code jobId}, applying Armoury Bonus + Chain Bonus
	 * and processing single or multiple level-ups up to {@link #MAX_LEVEL}.
	 */
	public ExpGainResult awardExp(Identifier jobId, long baseExp, long baseGil, long nowMs) {
		if (!this.jobLevels.containsKey(jobId)) {
			this.jobLevels.put(jobId, 1);
			this.jobExps.put(jobId, 0L);
		}

		// Chain bonus: consecutive EXP gains within 20 seconds build chain #1..#5 (+20% per chain)
		if (nowMs - this.lastExpGainTimeMs <= 20_000L) {
			this.chainCount = Math.min(5, this.chainCount + 1);
		} else {
			this.chainCount = 0;
		}
		this.lastExpGainTimeMs = nowMs;

		int armouryBonus = getArmouryBonusPercent(jobId);
		int chainBonus = this.chainCount * 20;
		int totalBonusPercent = armouryBonus + chainBonus;

		long safeBase = Math.max(1L, baseExp);
		long gainedExp = Math.round(safeBase * (1.0 + totalBonusPercent / 100.0));
		long gainedGil = Math.max(0L, Math.round(baseGil * (1.0 + (this.chainCount * 0.10))));
		addGil(gainedGil);

		int oldLv = getLevel(jobId);
		int curLv = oldLv;
		long curExp = getExp(jobId);

		if (curLv < MAX_LEVEL) {
			curExp += gainedExp;
			while (curLv < MAX_LEVEL) {
				long needed = getExpForNextLevel(curLv);
				if (needed > 0 && curExp >= needed) {
					curExp -= needed;
					curLv++;
				} else {
					break;
				}
			}
			if (curLv >= MAX_LEVEL) {
				curExp = 0L;
			}
		}

		this.jobLevels.put(jobId, curLv);
		this.jobExps.put(jobId, curExp);

		return new ExpGainResult(
			jobId,
			safeBase,
			gainedExp,
			totalBonusPercent,
			this.chainCount,
			oldLv,
			curLv,
			curExp,
			getExpForNextLevel(curLv),
			gainedGil
		);
	}

	public List<JobLevelEntry> getAllJobEntries() {
		List<JobLevelEntry> list = new ArrayList<>(ALL_JOBS.size());
		for (Identifier jobId : ALL_JOBS) {
			int lv = getLevel(jobId);
			list.add(new JobLevelEntry(jobId, lv, getExp(jobId), getExpForNextLevel(lv)));
		}
		return list;
	}

	public void applySyncedEntries(List<JobLevelEntry> entries, long syncedGil) {
		for (JobLevelEntry e : entries) {
			this.jobLevels.put(e.jobId(), Math.clamp(e.level(), MIN_LEVEL, MAX_LEVEL));
			this.jobExps.put(e.jobId(), Math.max(0L, e.currentExp()));
		}
		this.gil = Math.max(0L, syncedGil);
	}

	/**
	 * Outgoing potency/healing multiplier granted by Job Level (Lv 1 = 1.00x, Lv 50 = 2.71x).
	 */
	public static float getLevelOutputMultiplier(int level) {
		int clamped = Math.clamp(level, MIN_LEVEL, MAX_LEVEL);
		return 1.0F + (clamped - 1) * 0.035F;
	}

	/**
	 * Computes the full FFXIV Character Attributes sheet for the given Job, Level, and vanilla Armor/Weapon values.
	 */
	public static JobAttributes computeAttributes(Identifier jobId, int level, int armorValue, int weaponDamage) {
		int lv = Math.clamp(level, MIN_LEVEL, MAX_LEVEL);
		String path = jobId.getPath();

		boolean isTank = "vanguard".equals(path);
		boolean isHealer = "hierophant".equals(path);
		boolean isMelee = "lancer".equals(path);
		boolean isCaster = "arcanist".equals(path);

		int baseStat = 18 + lv * 5;
		int str = baseStat + (isTank ? lv * 3 : (isMelee ? lv * 5 : lv));
		int dex = baseStat + (isMelee ? lv * 3 : lv * 2);
		int vit = baseStat + (isTank ? lv * 6 : (isMelee ? lv * 3 : lv * 2)) + armorValue * 2;
		int intel = baseStat + (isCaster ? lv * 6 : lv);
		int mnd = baseStat + (isHealer ? lv * 6 : (isCaster ? lv * 2 : lv));
		int pie = baseStat + (isHealer || isCaster ? lv * 4 : lv * 2);

		int displayHp = 280 + vit * 8 + lv * 22;
		int displayMp = 10000;
		int displayTp = 1000;

		int accuracy = 340 + lv * 4;
		int crit = 340 + lv * 5 + (isMelee || isCaster ? lv * 2 : 0);
		int det = 220 + lv * 4 + weaponDamage * 6;
		int def = 110 + lv * 8 + armorValue * 18 + (isTank ? lv * 6 : 0);
		int parry = 340 + lv * 3 + (isTank ? lv * 5 : 0);
		int magDef = 110 + lv * 8 + armorValue * 15 + (isHealer || isCaster ? lv * 4 : 0);

		int atkPower = str + weaponDamage * 8;
		int skillSpd = 380 + lv * 3 + (isMelee ? lv * 2 : 0);
		int atkMag = intel + weaponDamage * 8;
		int healMag = mnd + weaponDamage * 8;
		int spellSpd = 380 + lv * 3 + (isCaster || isHealer ? lv * 2 : 0);

		return new JobAttributes(
			lv, displayHp, displayMp, displayTp,
			str, dex, vit, intel, mnd, pie,
			accuracy, crit, det,
			def, parry, magDef,
			atkPower, skillSpd,
			atkMag, healMag, spellSpd
		);
	}
}
