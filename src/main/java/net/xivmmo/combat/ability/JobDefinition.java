package net.xivmmo.combat.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import java.util.List;

/**
 * Immutable data-driven definition for an MMO Job (Tank, Healer, Melee DPS, Ranged DPS).
 * Loaded from {@code data/<namespace>/mmo/jobs/<name>.json} via Mojang {@link Codec}.
 */
public record JobDefinition(
	Identifier id,
	String displayName,
	Role role,
	int baseMana,
	List<Identifier> abilities
) {
	public enum Role implements StringRepresentable {
		TANK("tank"),
		HEALER("healer"),
		MELEE_DPS("melee_dps"),
		RANGED_DPS("ranged_dps");

		public static final Codec<Role> CODEC = StringRepresentable.fromEnum(Role::values);
		private final String serializedName;

		Role(String serializedName) {
			this.serializedName = serializedName;
		}

		@Override
		public String getSerializedName() {
			return this.serializedName;
		}
	}

	public static final Codec<JobDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Identifier.CODEC.fieldOf("id").forGetter(JobDefinition::id),
		Codec.STRING.fieldOf("display_name").forGetter(JobDefinition::displayName),
		Role.CODEC.fieldOf("role").forGetter(JobDefinition::role),
		Codec.INT.optionalFieldOf("base_mana", 10000).forGetter(JobDefinition::baseMana),
		Identifier.CODEC.listOf().fieldOf("abilities").forGetter(JobDefinition::abilities)
	).apply(instance, JobDefinition::new));
}
