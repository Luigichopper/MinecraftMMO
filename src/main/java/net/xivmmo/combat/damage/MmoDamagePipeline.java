package net.xivmmo.combat.damage;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.xivmmo.XivMmoMod;
import org.jspecify.annotations.Nullable;

/**
 * Potency-based damage and healing pipeline.
 * Scales potency with Minecraft's {@link Attributes#ATTACK_DAMAGE} and {@link Attributes#ARMOR},
 * applies active MMO buff/debuff multipliers, and constructs custom MMO {@link DamageSource}s.
 */
public final class MmoDamagePipeline {
	public static final ResourceKey<DamageType> PHYSICAL_ABILITY_DAMAGE = ResourceKey.create(
		Registries.DAMAGE_TYPE,
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "physical_ability")
	);

	public static final ResourceKey<DamageType> MAGICAL_ABILITY_DAMAGE = ResourceKey.create(
		Registries.DAMAGE_TYPE,
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "magical_ability")
	);

	public static final ResourceKey<DamageType> UNASPECTED_DOT_DAMAGE = ResourceKey.create(
		Registries.DAMAGE_TYPE,
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "unaspected_dot")
	);

	private MmoDamagePipeline() {
	}

	/**
	 * Pure math helper: computes final MMO ability damage from potency, attacker's Attack Damage attribute,
	 * outgoing buff multiplier, incoming vulnerability/mitigation multiplier, and target's Armor attribute.
	 *
	 * @param potency                ability potency (e.g. 100 = 4.0 base HP before stats)
	 * @param attackerAttackDamage   attacker's {@code generic.attack_damage} attribute value (vanilla base = 1.0)
	 * @param outgoingBuffMultiplier product of attacker's damage-dealt status multipliers
	 * @param incomingTakenMultiplier product of target's damage-taken status multipliers (e.g. 0.80 for 20% mitigation)
	 * @param targetArmor            target's {@code generic.armor} attribute value (0..30)
	 * @param armorMitigationScale   configurable scale for how much vanilla armor reduces MMO ability damage (0.0..1.0)
	 */
	public static float computeDamage(
		int potency,
		double attackerAttackDamage,
		float outgoingBuffMultiplier,
		float incomingTakenMultiplier,
		double targetArmor,
		float armorMitigationScale
	) {
		if (potency <= 0) {
			return 0.0F;
		}
		float baseFromPotency = potency * 0.04F;
		float weaponScalar = 1.0F + 0.12F * (float) Math.max(0.0, attackerAttackDamage - 1.0);
		float raw = baseFromPotency * weaponScalar * Math.max(0.0F, outgoingBuffMultiplier) * Math.max(0.0F, incomingTakenMultiplier);

		// Coexist with vanilla armor: up to 40% reduction at 20 armor * armorMitigationScale
		float clampedArmor = (float) Math.clamp(targetArmor, 0.0, 30.0);
		float armorReduction = Math.clamp((clampedArmor * 0.02F) * Math.clamp(armorMitigationScale, 0.0F, 1.0F), 0.0F, 0.75F);
		return Math.max(0.5F, raw * (1.0F - armorReduction));
	}

	/**
	 * Pure math helper: computes healing amount from heal potency and outgoing buff multiplier.
	 */
	public static float computeHeal(int healPotency, float outgoingMultiplier) {
		if (healPotency <= 0) {
			return 0.0F;
		}
		return Math.max(0.5F, (healPotency * 0.04F) * Math.max(0.1F, outgoingMultiplier));
	}

	/**
	 * Resolves a custom MMO {@link DamageSource} from the world's {@link Registries#DAMAGE_TYPE} registry,
	 * falling back cleanly to {@code magic()} or {@code mobAttack()} if a datapack overrides it.
	 */
	public static DamageSource createAbilityDamageSource(
		ServerLevel level,
		ResourceKey<DamageType> damageTypeKey,
		@Nullable LivingEntity attacker
	) {
		var registryOpt = level.registryAccess().lookup(Registries.DAMAGE_TYPE);
		if (registryOpt.isPresent()) {
			var holderOpt = registryOpt.get().get(damageTypeKey);
			if (holderOpt.isPresent()) {
				return new DamageSource(holderOpt.get(), attacker);
			}
		}
		return attacker != null
			? level.damageSources().mobAttack(attacker)
			: level.damageSources().magic();
	}
}
