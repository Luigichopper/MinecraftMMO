package net.xivmmo.combat.status;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/**
 * Immutable data-driven definition for an MMO status effect (buff, debuff, DoT, HoT, or tank stance).
 * Loaded from {@code data/<namespace>/mmo/statuses/<name>.json} via Mojang {@link Codec}.
 */
public record StatusDefinition(
	Identifier id,
	String displayName,
	StatusType type,
	int defaultDurationTicks,
	int maxStacks,
	float damageDealtMultiplier,
	float damageTakenMultiplier,
	int dotPotency,
	int hotPotency,
	float enmityMultiplier
) {
	public enum StatusType implements StringRepresentable {
		BUFF("buff"),
		DEBUFF("debuff");

		public static final Codec<StatusType> CODEC = StringRepresentable.fromEnum(StatusType::values);
		private final String serializedName;

		StatusType(String serializedName) {
			this.serializedName = serializedName;
		}

		@Override
		public String getSerializedName() {
			return this.serializedName;
		}
	}

	public static final Codec<StatusDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Identifier.CODEC.fieldOf("id").forGetter(StatusDefinition::id),
		Codec.STRING.fieldOf("display_name").forGetter(StatusDefinition::displayName),
		StatusType.CODEC.optionalFieldOf("type", StatusType.BUFF).forGetter(StatusDefinition::type),
		Codec.INT.optionalFieldOf("default_duration_ticks", 300).forGetter(StatusDefinition::defaultDurationTicks),
		Codec.INT.optionalFieldOf("max_stacks", 1).forGetter(StatusDefinition::maxStacks),
		Codec.FLOAT.optionalFieldOf("damage_dealt_multiplier", 1.0F).forGetter(StatusDefinition::damageDealtMultiplier),
		Codec.FLOAT.optionalFieldOf("damage_taken_multiplier", 1.0F).forGetter(StatusDefinition::damageTakenMultiplier),
		Codec.INT.optionalFieldOf("dot_potency", 0).forGetter(StatusDefinition::dotPotency),
		Codec.INT.optionalFieldOf("hot_potency", 0).forGetter(StatusDefinition::hotPotency),
		Codec.FLOAT.optionalFieldOf("enmity_multiplier", 1.0F).forGetter(StatusDefinition::enmityMultiplier)
	).apply(instance, StatusDefinition::new));

	public static final StreamCodec<FriendlyByteBuf, StatusDefinition> STREAM_CODEC = StreamCodec.of(
		(buf, def) -> {
			buf.writeIdentifier(def.id());
			buf.writeUtf(def.displayName());
			buf.writeEnum(def.type());
			buf.writeVarInt(def.defaultDurationTicks());
			buf.writeVarInt(def.maxStacks());
			buf.writeFloat(def.damageDealtMultiplier());
			buf.writeFloat(def.damageTakenMultiplier());
			buf.writeVarInt(def.dotPotency());
			buf.writeVarInt(def.hotPotency());
			buf.writeFloat(def.enmityMultiplier());
		},
		buf -> new StatusDefinition(
			buf.readIdentifier(),
			buf.readUtf(),
			buf.readEnum(StatusType.class),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readFloat()
		)
	);
}
