package net.xivmmo.combat.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;
import java.util.Optional;

/**
 * Immutable data-driven definition for an MMO ability (weaponskill, spell, or off-GCD action).
 * Loaded from {@code data/<namespace>/mmo/abilities/<name>.json} via Mojang {@link Codec}.
 */
public record AbilityDefinition(
	Identifier id,
	String displayName,
	Category category,
	TargetType targetType,
	AoeShape aoeShape,
	float range,
	float radius,
	float innerRadius,
	float coneAngleDegrees,
	int castTicks,
	int recastTicks,
	Identifier cooldownGroup,
	int maxCharges,
	int animationLockTicks,
	int manaCost,
	int potency,
	Optional<Identifier> comboPrerequisite,
	int comboPotency,
	Positional positional,
	int positionalPotency,
	boolean canMoveWhileCasting,
	Optional<Identifier> appliedStatus
) {
	public AbilityDefinition(
		Identifier id,
		String displayName,
		Category category,
		TargetType targetType,
		AoeShape aoeShape,
		float range,
		float radius,
		float innerRadius,
		float coneAngleDegrees,
		int castTicks,
		int recastTicks,
		Identifier cooldownGroup,
		int maxCharges,
		int animationLockTicks,
		int manaCost,
		int potency,
		Optional<Identifier> comboPrerequisite,
		int comboPotency,
		Positional positional,
		int positionalPotency,
		boolean canMoveWhileCasting
	) {
		this(
			id, displayName, category, targetType, aoeShape,
			range, radius, innerRadius, coneAngleDegrees, castTicks, recastTicks,
			cooldownGroup, maxCharges, animationLockTicks, manaCost, potency,
			comboPrerequisite, comboPotency, positional, positionalPotency, canMoveWhileCasting,
			Optional.empty()
		);
	}

	public enum Category implements StringRepresentable {
		GCD("gcd", true),
		SPELL_GCD("spell_gcd", true),
		OGCD("ogcd", false);

		public static final Codec<Category> CODEC = StringRepresentable.fromEnum(Category::values);
		private final String serializedName;
		private final boolean triggersGcd;

		Category(String serializedName, boolean triggersGcd) {
			this.serializedName = serializedName;
			this.triggersGcd = triggersGcd;
		}

		public boolean triggersGcd() {
			return this.triggersGcd;
		}

		@Override
		public String getSerializedName() {
			return this.serializedName;
		}
	}

	public enum TargetType implements StringRepresentable {
		SINGLE_ENEMY("single_enemy"),
		SINGLE_ALLY("single_ally"),
		SELF("self"),
		GROUND_AOE("ground_aoe"),
		SELF_AOE("self_aoe");

		public static final Codec<TargetType> CODEC = StringRepresentable.fromEnum(TargetType::values);
		private final String serializedName;

		TargetType(String serializedName) {
			this.serializedName = serializedName;
		}

		@Override
		public String getSerializedName() {
			return this.serializedName;
		}
	}

	public enum AoeShape implements StringRepresentable {
		NONE("none"),
		CIRCLE("circle"),
		CONE("cone"),
		LINE("line"),
		DONUT("donut");

		public static final Codec<AoeShape> CODEC = StringRepresentable.fromEnum(AoeShape::values);
		private final String serializedName;

		AoeShape(String serializedName) {
			this.serializedName = serializedName;
		}

		@Override
		public String getSerializedName() {
			return this.serializedName;
		}
	}

	public enum Positional implements StringRepresentable {
		NONE("none"),
		REAR("rear"),
		FLANK("flank"),
		FRONT("front");

		public static final Codec<Positional> CODEC = StringRepresentable.fromEnum(Positional::values);
		private final String serializedName;

		Positional(String serializedName) {
			this.serializedName = serializedName;
		}

		@Override
		public String getSerializedName() {
			return this.serializedName;
		}
	}

	private record CorePart(
		Identifier id,
		String displayName,
		Category category,
		TargetType targetType,
		AoeShape aoeShape,
		float range,
		float radius,
		float innerRadius,
		float coneAngleDegrees,
		int castTicks,
		int recastTicks
	) {
		static final MapCodec<CorePart> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Identifier.CODEC.fieldOf("id").forGetter(CorePart::id),
			Codec.STRING.fieldOf("display_name").forGetter(CorePart::displayName),
			Category.CODEC.optionalFieldOf("category", Category.GCD).forGetter(CorePart::category),
			TargetType.CODEC.optionalFieldOf("target_type", TargetType.SINGLE_ENEMY).forGetter(CorePart::targetType),
			AoeShape.CODEC.optionalFieldOf("aoe_shape", AoeShape.NONE).forGetter(CorePart::aoeShape),
			Codec.FLOAT.optionalFieldOf("range", 3.0F).forGetter(CorePart::range),
			Codec.FLOAT.optionalFieldOf("radius", 0.0F).forGetter(CorePart::radius),
			Codec.FLOAT.optionalFieldOf("inner_radius", 0.0F).forGetter(CorePart::innerRadius),
			Codec.FLOAT.optionalFieldOf("cone_angle_degrees", 90.0F).forGetter(CorePart::coneAngleDegrees),
			Codec.INT.optionalFieldOf("cast_ticks", 0).forGetter(CorePart::castTicks),
			Codec.INT.optionalFieldOf("recast_ticks", 50).forGetter(CorePart::recastTicks)
		).apply(instance, CorePart::new));
	}

	private record CombatPart(
		Identifier cooldownGroup,
		int maxCharges,
		int animationLockTicks,
		int manaCost,
		int potency,
		Optional<Identifier> comboPrerequisite,
		int comboPotency,
		Positional positional,
		int positionalPotency,
		boolean canMoveWhileCasting,
		Optional<Identifier> appliedStatus
	) {
		static final MapCodec<CombatPart> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Identifier.CODEC.optionalFieldOf("cooldown_group", Identifier.fromNamespaceAndPath("xivmmo", "gcd")).forGetter(CombatPart::cooldownGroup),
			Codec.INT.optionalFieldOf("max_charges", 1).forGetter(CombatPart::maxCharges),
			Codec.INT.optionalFieldOf("animation_lock_ticks", 12).forGetter(CombatPart::animationLockTicks),
			Codec.INT.optionalFieldOf("mana_cost", 0).forGetter(CombatPart::manaCost),
			Codec.INT.optionalFieldOf("potency", 100).forGetter(CombatPart::potency),
			Identifier.CODEC.optionalFieldOf("combo_prerequisite").forGetter(CombatPart::comboPrerequisite),
			Codec.INT.optionalFieldOf("combo_potency", 0).forGetter(CombatPart::comboPotency),
			Positional.CODEC.optionalFieldOf("positional", Positional.NONE).forGetter(CombatPart::positional),
			Codec.INT.optionalFieldOf("positional_potency", 0).forGetter(CombatPart::positionalPotency),
			Codec.BOOL.optionalFieldOf("can_move_while_casting", false).forGetter(CombatPart::canMoveWhileCasting),
			Identifier.CODEC.optionalFieldOf("applied_status").forGetter(CombatPart::appliedStatus)
		).apply(instance, CombatPart::new));
	}

	public static final Codec<AbilityDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		CorePart.MAP_CODEC.forGetter(d -> new CorePart(
			d.id(), d.displayName(), d.category(), d.targetType(), d.aoeShape(),
			d.range(), d.radius(), d.innerRadius(), d.coneAngleDegrees(), d.castTicks(), d.recastTicks()
		)),
		CombatPart.MAP_CODEC.forGetter(d -> new CombatPart(
			d.cooldownGroup(), d.maxCharges(), d.animationLockTicks(), d.manaCost(), d.potency(),
			d.comboPrerequisite(), d.comboPotency(), d.positional(), d.positionalPotency(),
			d.canMoveWhileCasting(), d.appliedStatus()
		))
	).apply(instance, (core, combat) -> new AbilityDefinition(
		core.id(), core.displayName(), core.category(), core.targetType(), core.aoeShape(),
		core.range(), core.radius(), core.innerRadius(), core.coneAngleDegrees(), core.castTicks(), core.recastTicks(),
		combat.cooldownGroup(), combat.maxCharges(), combat.animationLockTicks(), combat.manaCost(), combat.potency(),
		combat.comboPrerequisite(), combat.comboPotency(), combat.positional(), combat.positionalPotency(),
		combat.canMoveWhileCasting(), combat.appliedStatus()
	)));

	public static final StreamCodec<FriendlyByteBuf, AbilityDefinition> STREAM_CODEC = StreamCodec.of(
		(buf, def) -> {
			buf.writeIdentifier(def.id());
			buf.writeUtf(def.displayName());
			buf.writeEnum(def.category());
			buf.writeEnum(def.targetType());
			buf.writeEnum(def.aoeShape());
			buf.writeFloat(def.range());
			buf.writeFloat(def.radius());
			buf.writeFloat(def.innerRadius());
			buf.writeFloat(def.coneAngleDegrees());
			buf.writeVarInt(def.castTicks());
			buf.writeVarInt(def.recastTicks());
			buf.writeIdentifier(def.cooldownGroup());
			buf.writeVarInt(def.maxCharges());
			buf.writeVarInt(def.animationLockTicks());
			buf.writeVarInt(def.manaCost());
			buf.writeVarInt(def.potency());
			buf.writeOptional(def.comboPrerequisite(), FriendlyByteBuf::writeIdentifier);
			buf.writeVarInt(def.comboPotency());
			buf.writeEnum(def.positional());
			buf.writeVarInt(def.positionalPotency());
			buf.writeBoolean(def.canMoveWhileCasting());
			buf.writeOptional(def.appliedStatus(), FriendlyByteBuf::writeIdentifier);
		},
		buf -> new AbilityDefinition(
			buf.readIdentifier(),
			buf.readUtf(),
			buf.readEnum(Category.class),
			buf.readEnum(TargetType.class),
			buf.readEnum(AoeShape.class),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readIdentifier(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readOptional(FriendlyByteBuf::readIdentifier),
			buf.readVarInt(),
			buf.readEnum(Positional.class),
			buf.readVarInt(),
			buf.readBoolean(),
			buf.readOptional(FriendlyByteBuf::readIdentifier)
		)
	);

	public boolean isInstant() {
		return this.castTicks <= 0;
	}

	public boolean isGcd() {
		return this.category.triggersGcd();
	}

	public String formatCastTime() {
		if (this.castTicks <= 0) {
			return "Instant";
		}
		return String.format(Locale.ROOT, "%.2fs", this.castTicks / 20.0F);
	}
}
