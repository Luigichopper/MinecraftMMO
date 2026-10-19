package net.xivmmo.combat.telegraph;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.xivmmo.combat.ability.AbilityDefinition;

/**
 * Pure geometry and intersection logic for MMO AoE & Enemy Telegraph shapes
 * ({@code CIRCLE}, {@code DONUT}, {@code CONE}, {@code LINE}).
 */
public final class TelegraphShapes {
	private TelegraphShapes() {
	}

	/**
	 * Network-serializable representation of an active world telegraph.
	 */
	public record ActiveTelegraph(
		int telegraphId,
		int sourceEntityId,
		String label,
		AbilityDefinition.AoeShape shape,
		double originX,
		double originY,
		double originZ,
		float yawDegrees,
		float radius,
		float innerRadius,
		float coneAngleDegrees,
		int elapsedTicks,
		int totalTicks,
		int potency,
		boolean hostile
	) {
		public static final StreamCodec<FriendlyByteBuf, ActiveTelegraph> STREAM_CODEC = StreamCodec.of(
			(buf, t) -> {
				buf.writeVarInt(t.telegraphId());
				buf.writeVarInt(t.sourceEntityId());
				buf.writeUtf(t.label());
				buf.writeEnum(t.shape());
				buf.writeDouble(t.originX());
				buf.writeDouble(t.originY());
				buf.writeDouble(t.originZ());
				buf.writeFloat(t.yawDegrees());
				buf.writeFloat(t.radius());
				buf.writeFloat(t.innerRadius());
				buf.writeFloat(t.coneAngleDegrees());
				buf.writeVarInt(t.elapsedTicks());
				buf.writeVarInt(t.totalTicks());
				buf.writeVarInt(t.potency());
				buf.writeBoolean(t.hostile());
			},
			buf -> new ActiveTelegraph(
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readUtf(),
				buf.readEnum(AbilityDefinition.AoeShape.class),
				buf.readDouble(),
				buf.readDouble(),
				buf.readDouble(),
				buf.readFloat(),
				buf.readFloat(),
				buf.readFloat(),
				buf.readFloat(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readBoolean()
			)
		);

		public ActiveTelegraph withElapsed(int nextElapsed) {
			return new ActiveTelegraph(
				telegraphId, sourceEntityId, label, shape,
				originX, originY, originZ, yawDegrees,
				radius, innerRadius, coneAngleDegrees,
				nextElapsed, totalTicks, potency, hostile
			);
		}

		public float getProgress(float partialTick) {
			return Mth.clamp((this.elapsedTicks + partialTick) / Math.max(1, this.totalTicks), 0.0F, 1.0F);
		}
	}

	/**
	 * Pure math helper: tests whether a 3D point {@code (px, py, pz)} lies inside the specified telegraph shape.
	 *
	 * <ul>
	 *   <li>{@code CIRCLE}: horizontal distance {@code <= radius}</li>
	 *   <li>{@code DONUT}: horizontal distance in {@code [innerRadius, radius]}</li>
	 *   <li>{@code CONE}: horizontal distance {@code <= radius} and angle within {@code coneAngleDegrees / 2} of {@code yawDegrees}</li>
	 *   <li>{@code LINE}: rectangle extending {@code [0, radius]} forward along {@code yawDegrees} with half-width {@code max(1.0, innerRadius)}</li>
	 * </ul>
	 */
	public static boolean containsPoint(
		AbilityDefinition.AoeShape shape,
		double originX, double originY, double originZ,
		float yawDegrees,
		float radius,
		float innerRadius,
		float coneAngleDegrees,
		double px, double py, double pz
	) {
		// Vertical tolerance of 3.5 blocks from telegraph origin plane
		if (Math.abs(py - originY) > 3.5) {
			return false;
		}

		double dx = px - originX;
		double dz = pz - originZ;
		double distSq = dx * dx + dz * dz;
		double maxR = Math.max(0.1, radius);

		switch (shape) {
			case CIRCLE:
				return distSq <= maxR * maxR;

			case DONUT: {
				double minR = Math.max(0.0, innerRadius);
				return distSq >= minR * minR && distSq <= maxR * maxR;
			}

			case CONE: {
				if (distSq > maxR * maxR) {
					return false;
				}
				if (distSq < 1.0E-5) {
					return true;
				}
				double dist = Math.sqrt(distSq);
				double rad = Math.toRadians(yawDegrees);
				double fwdX = -Math.sin(rad);
				double fwdZ = Math.cos(rad);
				double dot = (dx / dist) * fwdX + (dz / dist) * fwdZ;
				double minDot = Math.cos(Math.toRadians(Math.clamp(coneAngleDegrees, 5.0F, 360.0F) * 0.5));
				return dot >= minDot - 1.0E-5;
			}

			case LINE: {
				double rad = Math.toRadians(yawDegrees);
				double fwdX = -Math.sin(rad);
				double fwdZ = Math.cos(rad);
				// Right vector perpendicular to forward in XZ plane: (fwdZ, -fwdX)
				double rightX = fwdZ;
				double rightZ = -fwdX;

				double forwardProj = dx * fwdX + dz * fwdZ;
				double lateralProj = Math.abs(dx * rightX + dz * rightZ);
				double halfWidth = innerRadius > 0.05F ? innerRadius : 1.5;
				return forwardProj >= -0.1 && forwardProj <= maxR && lateralProj <= halfWidth;
			}

			case NONE:
			default:
				return false;
		}
	}
}
