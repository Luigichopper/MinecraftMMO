package net.xivmmo.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.xivmmo.XivMmoMod;
import net.xivmmo.client.target.ClientTargetManager;
import net.xivmmo.client.target.CursorRaycaster;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.telegraph.TelegraphShapes;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders world-space FFXIV-style Ground Telegraphs ({@code CIRCLE}, {@code CONE}, {@code LINE}, {@code DONUT}),
 * Ground-Target AoE Placement Reticles, and Entity Target/Positional Rings.
 *
 * <p>Compatibility & Performance Guarantees:
 * <ul>
 *   <li>Uses Fabric's official 26.2 {@link LevelRenderEvents#COLLECT_SUBMITS} and {@link SubmitNodeCollector#submitCustomGeometry}</li>
 *   <li>Uses {@link RenderTypes#entityTranslucent(Identifier, boolean)} with a dedicated white texture and full {@code ENTITY} vertex
 *       attributes ({@code Position}, {@code Color}, {@code UV0}, {@code Overlay}, {@code Lightmap}, {@code Normal}) plus
 *       {@link RenderTypes#lines()} perimeter outlines so Iris shaderpacks ({@code gbuffers_entities_translucent} & {@code gbuffers_line})
 *       render telegraphs and target selectors accurately with zero missing-pipeline errors</li>
 *   <li>Zero per-frame heap allocations on the vertex submission path via pre-allocated {@link Vector3f} scratch registers and sin/cos lookup tables</li>
 * </ul>
 */
public final class WorldTelegraphRenderer {
	private static final Identifier TELEGRAPH_WHITE_TEXTURE =
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "textures/misc/telegraph_white.png");
	private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;

	private static final int SEGMENTS = 48;
	private static final float[] COS_TABLE = new float[SEGMENTS + 1];
	private static final float[] SIN_TABLE = new float[SEGMENTS + 1];

	static {
		for (int i = 0; i <= SEGMENTS; i++) {
			double angle = (Math.PI * 2.0 * i) / SEGMENTS;
			COS_TABLE[i] = (float) Math.cos(angle);
			SIN_TABLE[i] = (float) Math.sin(angle);
		}
	}

	private static final Vector3f VEC_A = new Vector3f();
	private static final Vector3f VEC_B = new Vector3f();
	private static final Vector3f VEC_C = new Vector3f();
	private static final Vector3f VEC_D = new Vector3f();

	private static volatile List<TelegraphShapes.ActiveTelegraph> clientTelegraphs = List.of();
	private static long lastSyncNanoTime = System.nanoTime();
	private static boolean showGroundAoeReticle = false;
	private static float groundReticleRadius = 5.0F;

	private WorldTelegraphRenderer() {
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(WorldTelegraphRenderer::onCollectSubmits);
	}

	public static void updateSyncedTelegraphs(List<TelegraphShapes.ActiveTelegraph> telegraphs) {
		clientTelegraphs = new ArrayList<>(telegraphs);
		lastSyncNanoTime = System.nanoTime();
	}

	public static List<TelegraphShapes.ActiveTelegraph> getClientTelegraphs() {
		return clientTelegraphs;
	}

	public static void setGroundAoeReticle(boolean visible, float radius) {
		showGroundAoeReticle = visible;
		groundReticleRadius = radius;
	}

	public static boolean isGroundAoeReticleVisible() {
		return showGroundAoeReticle;
	}

	public static void toggleGroundAoeReticle() {
		showGroundAoeReticle = !showGroundAoeReticle;
	}

	private static void onCollectSubmits(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		PoseStack poseStack = context.poseStack();
		if (poseStack == null) {
			return;
		}

		Vec3 camPos = context.levelState().cameraRenderState.pos;
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

		ClientTargetManager.get().updateFrame();
		LivingEntity hardTarget = ClientTargetManager.get().getHardTarget();
		LivingEntity softTarget = ClientTargetManager.get().getSoftTarget();
		LivingEntity focusTarget = ClientTargetManager.get().getFocusTarget();
		Vec3 groundHit = CursorRaycaster.get().getGroundHitPos();
		List<TelegraphShapes.ActiveTelegraph> activeList = clientTelegraphs;
		boolean showDebugRay = net.xivmmo.debug.MmoDebugState.isShowTargetingRayAndHitboxes();

		if (hardTarget == null && softTarget == null && activeList.isEmpty()
			&& (!showGroundAoeReticle || groundHit == null) && !showDebugRay) {
			return;
		}

		SubmitNodeCollector collector = context.submitNodeCollector();
		RenderType fillRenderType = RenderTypes.entityTranslucent(TELEGRAPH_WHITE_TEXTURE, false);

		// 1. Submit translucent filled geometry via Iris-supported ENTITY_TRANSLUCENT pipeline
		collector.submitCustomGeometry(poseStack, fillRenderType, (pose, buffer) -> {
			// Hard Target Ring with FFXIV Rear/Flank/Front positional indicator
			if (hardTarget != null && hardTarget.isAlive()) {
				float tx = (float) (Mth.lerp(partialTick, hardTarget.xo, hardTarget.getX()) - camPos.x);
				float ty = (float) (Mth.lerp(partialTick, hardTarget.yo, hardTarget.getY()) - camPos.y + 0.05);
				float tz = (float) (Mth.lerp(partialTick, hardTarget.zo, hardTarget.getZ()) - camPos.z);
				float r = Math.max(0.75F, hardTarget.getBbWidth() * 0.85F);
				drawAnnulusQuads(pose, buffer, tx, ty, tz, r * 0.80F, r, 0xAAFF3B3B);
				drawDirectionChevronQuads(pose, buffer, tx, ty + 0.01F, tz, hardTarget.getViewYRot(partialTick), r, 0xEEFFEE55);
			}

			// Soft Target Hover Ring
			if (softTarget != null && softTarget != hardTarget && softTarget.isAlive()) {
				float sx = (float) (Mth.lerp(partialTick, softTarget.xo, softTarget.getX()) - camPos.x);
				float sy = (float) (Mth.lerp(partialTick, softTarget.yo, softTarget.getY()) - camPos.y + 0.04);
				float sz = (float) (Mth.lerp(partialTick, softTarget.zo, softTarget.getZ()) - camPos.z);
				float r = Math.max(0.65F, softTarget.getBbWidth() * 0.75F);
				drawAnnulusQuads(pose, buffer, sx, sy, sz, r * 0.82F, r, 0x99FFCC44);
			}

			// Ground-Targeted AoE Placement Reticle
			if (showGroundAoeReticle && groundHit != null) {
				float gx = (float) (groundHit.x - camPos.x);
				float gy = (float) (groundHit.y - camPos.y + 0.05);
				float gz = (float) (groundHit.z - camPos.z);
				drawAnnulusQuads(pose, buffer, gx, gy, gz, 0.0F, groundReticleRadius, 0x5544CCFF);
				drawAnnulusQuads(pose, buffer, gx, gy + 0.008F, gz, Math.max(0.0F, groundReticleRadius - 0.18F), groundReticleRadius, 0xCC66EEFF);
			}

			// Active Enemy / Boss AoE Telegraphs with animated fill-up progress
			for (int i = 0, size = activeList.size(); i < size; i++) {
				TelegraphShapes.ActiveTelegraph t = activeList.get(i);
				float ox = (float) (t.originX() - camPos.x);
				float oy = (float) (t.originY() - camPos.y + 0.05);
				float oz = (float) (t.originZ() - camPos.z);
				float progress = t.getProgress(partialTick);

				int baseColor = t.hostile() ? 0x58FF5522 : 0x5044CCFF;
				int fillColor = t.hostile() ? 0x99FF8822 : 0x9066EEFF;
				int rimColor = t.hostile() ? 0xEEFFCC44 : 0xEE88FFFF;

				renderTelegraphShapeQuads(pose, buffer, t, ox, oy, oz, progress, baseColor, fillColor, rimColor);
			}
		});

		// 2. Submit crisp 3D perimeter outlines & debug ray/hitboxes via Iris-supported LINES pipeline
		collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
			if (hardTarget != null && hardTarget.isAlive()) {
				float tx = (float) (Mth.lerp(partialTick, hardTarget.xo, hardTarget.getX()) - camPos.x);
				float ty = (float) (Mth.lerp(partialTick, hardTarget.yo, hardTarget.getY()) - camPos.y + 0.055);
				float tz = (float) (Mth.lerp(partialTick, hardTarget.zo, hardTarget.getZ()) - camPos.z);
				float r = Math.max(0.75F, hardTarget.getBbWidth() * 0.85F);
				drawCircleLines(pose, buffer, tx, ty, tz, r, 0xFFFF5555, 3.0F);
				drawCircleLines(pose, buffer, tx, ty, tz, r * 0.80F, 0xCCFF8888, 2.0F);
			}

			if (softTarget != null && softTarget != hardTarget && softTarget.isAlive()) {
				float sx = (float) (Mth.lerp(partialTick, softTarget.xo, softTarget.getX()) - camPos.x);
				float sy = (float) (Mth.lerp(partialTick, softTarget.yo, softTarget.getY()) - camPos.y + 0.045);
				float sz = (float) (Mth.lerp(partialTick, softTarget.zo, softTarget.getZ()) - camPos.z);
				float r = Math.max(0.65F, softTarget.getBbWidth() * 0.75F);
				drawCircleLines(pose, buffer, sx, sy, sz, r, 0xFFFFDD66, 2.5F);
			}

			if (showGroundAoeReticle && groundHit != null) {
				float gx = (float) (groundHit.x - camPos.x);
				float gy = (float) (groundHit.y - camPos.y + 0.06);
				float gz = (float) (groundHit.z - camPos.z);
				drawCircleLines(pose, buffer, gx, gy, gz, groundReticleRadius, 0xFF88FFFF, 3.0F);
			}

			for (int i = 0, size = activeList.size(); i < size; i++) {
				TelegraphShapes.ActiveTelegraph t = activeList.get(i);
				float ox = (float) (t.originX() - camPos.x);
				float oy = (float) (t.originY() - camPos.y + 0.065);
				float oz = (float) (t.originZ() - camPos.z);
				int lineColor = t.hostile() ? 0xFFFFDD55 : 0xFF88FFFF;
				float maxR = Math.max(0.5F, t.radius());
				if (t.shape() == AbilityDefinition.AoeShape.CIRCLE) {
					drawCircleLines(pose, buffer, ox, oy, oz, maxR, lineColor, 3.0F);
				} else if (t.shape() == AbilityDefinition.AoeShape.DONUT) {
					float inR = Math.clamp(t.innerRadius(), 0.2F, maxR - 0.2F);
					drawCircleLines(pose, buffer, ox, oy, oz, maxR, lineColor, 3.0F);
					drawCircleLines(pose, buffer, ox, oy, oz, inR, lineColor, 2.5F);
				}
			}

			if (showDebugRay) {
				Vec3 rayStart = mc.player.getEyePosition(partialTick);
				Vec3 rayDir = CursorRaycaster.get().getLastRayDirection();
				Vec3 rayEnd = groundHit != null
					? groundHit
					: CursorRaycaster.get().getLastRayOrigin().add(rayDir.scale(20.0));
				drawSegmentLine(
					pose, buffer,
					(float) (rayStart.x - camPos.x), (float) (rayStart.y - camPos.y), (float) (rayStart.z - camPos.z),
					(float) (rayEnd.x - camPos.x), (float) (rayEnd.y - camPos.y), (float) (rayEnd.z - camPos.z),
					0xFF55FFFF, 2.5F
				);

				if (hardTarget != null && hardTarget.isAlive()) {
					drawEntityPickAabbLines(pose, buffer, hardTarget, camPos, 0xFFFF4444, 2.5F);
				}
				if (softTarget != null && softTarget != hardTarget && softTarget.isAlive()) {
					drawEntityPickAabbLines(pose, buffer, softTarget, camPos, 0xFFFFDD44, 2.0F);
				}
				if (focusTarget != null && focusTarget != hardTarget && focusTarget.isAlive()) {
					drawEntityPickAabbLines(pose, buffer, focusTarget, camPos, 0xFF55DDFF, 2.0F);
				}
			}
		});
	}

	private static void renderTelegraphShapeQuads(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		TelegraphShapes.ActiveTelegraph t,
		float ox, float oy, float oz,
		float progress,
		int baseColor,
		int fillColor,
		int rimColor
	) {
		float maxR = Math.max(0.5F, t.radius());
		switch (t.shape()) {
			case CIRCLE: {
				drawAnnulusQuads(pose, buffer, ox, oy, oz, 0.0F, maxR, baseColor);
				drawAnnulusQuads(pose, buffer, ox, oy + 0.006F, oz, 0.0F, maxR * progress, fillColor);
				drawAnnulusQuads(pose, buffer, ox, oy + 0.012F, oz, Math.max(0.0F, maxR - 0.15F), maxR, rimColor);
				break;
			}
			case DONUT: {
				float inR = Math.clamp(t.innerRadius(), 0.2F, maxR - 0.2F);
				drawAnnulusQuads(pose, buffer, ox, oy, oz, inR, maxR, baseColor);
				float fillOuter = Mth.lerp(progress, inR, maxR);
				drawAnnulusQuads(pose, buffer, ox, oy + 0.006F, oz, inR, fillOuter, fillColor);
				drawAnnulusQuads(pose, buffer, ox, oy + 0.012F, oz, Math.max(inR, maxR - 0.14F), maxR, rimColor);
				drawAnnulusQuads(pose, buffer, ox, oy + 0.012F, oz, inR, Math.min(maxR, inR + 0.14F), rimColor);
				break;
			}
			case CONE: {
				drawConeQuads(pose, buffer, ox, oy, oz, t.yawDegrees(), t.coneAngleDegrees(), 0.0F, maxR, baseColor);
				drawConeQuads(pose, buffer, ox, oy + 0.006F, oz, t.yawDegrees(), t.coneAngleDegrees(), 0.0F, maxR * progress, fillColor);
				drawConeQuads(pose, buffer, ox, oy + 0.012F, oz, t.yawDegrees(), t.coneAngleDegrees(), Math.max(0.0F, maxR - 0.15F), maxR, rimColor);
				break;
			}
			case LINE: {
				float halfW = t.innerRadius() > 0.05F ? t.innerRadius() : 1.5F;
				drawLineBoxQuads(pose, buffer, ox, oy, oz, t.yawDegrees(), maxR, halfW, baseColor);
				drawLineBoxQuads(pose, buffer, ox, oy + 0.006F, oz, t.yawDegrees(), maxR * progress, halfW, fillColor);
				break;
			}
			default:
				break;
		}
	}

	private static void drawAnnulusQuads(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		float cx, float cy, float cz,
		float innerR, float outerR,
		int color
	) {
		if (outerR <= innerR + 1.0E-4F) {
			return;
		}
		// Keep a tiny non-zero inner radius so all 4 quad vertices are distinct for Iris tangent calculation
		float safeInnerR = Math.max(0.002F, innerR);
		for (int i = 0; i < SEGMENTS; i++) {
			float c0 = COS_TABLE[i];
			float s0 = SIN_TABLE[i];
			float c1 = COS_TABLE[i + 1];
			float s1 = SIN_TABLE[i + 1];

			emitQuad(
				pose, buffer,
				cx + c0 * safeInnerR, cy, cz + s0 * safeInnerR,
				cx + c0 * outerR, cy, cz + s0 * outerR,
				cx + c1 * outerR, cy, cz + s1 * outerR,
				cx + c1 * safeInnerR, cy, cz + s1 * safeInnerR,
				color
			);
		}
	}

	private static void drawConeQuads(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		float cx, float cy, float cz,
		float yawDegrees,
		float coneAngleDegrees,
		float innerR, float outerR,
		int color
	) {
		if (outerR <= innerR + 1.0E-4F) {
			return;
		}
		float safeInnerR = Math.max(0.002F, innerR);
		int steps = 24;
		float halfConeRad = (float) Math.toRadians(coneAngleDegrees * 0.5F);
		float centerRad = (float) Math.toRadians(yawDegrees);
		float startAngle = centerRad - halfConeRad;
		float stepAngle = (halfConeRad * 2.0F) / steps;

		for (int i = 0; i < steps; i++) {
			float a0 = startAngle + i * stepAngle;
			float a1 = a0 + stepAngle;
			float x0 = -(float) Math.sin(a0);
			float z0 = (float) Math.cos(a0);
			float x1 = -(float) Math.sin(a1);
			float z1 = (float) Math.cos(a1);

			emitQuad(
				pose, buffer,
				cx + x0 * safeInnerR, cy, cz + z0 * safeInnerR,
				cx + x0 * outerR, cy, cz + z0 * outerR,
				cx + x1 * outerR, cy, cz + z1 * outerR,
				cx + x1 * safeInnerR, cy, cz + z1 * safeInnerR,
				color
			);
		}
	}

	private static void drawLineBoxQuads(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		float ox, float oy, float oz,
		float yawDegrees,
		float length,
		float halfWidth,
		int color
	) {
		if (length <= 1.0E-3F) {
			return;
		}
		double rad = Math.toRadians(yawDegrees);
		float fwdX = -(float) Math.sin(rad);
		float fwdZ = (float) Math.cos(rad);
		float rightX = fwdZ;
		float rightZ = -fwdX;

		float x0 = ox - rightX * halfWidth;
		float z0 = oz - rightZ * halfWidth;
		float x1 = ox + rightX * halfWidth;
		float z1 = oz + rightZ * halfWidth;
		float x2 = x1 + fwdX * length;
		float z2 = z1 + fwdZ * length;
		float x3 = x0 + fwdX * length;
		float z3 = z0 + fwdZ * length;

		emitQuad(pose, buffer, x0, oy, z0, x3, oy, z3, x2, oy, z2, x1, oy, z1, color);
	}

	private static void drawDirectionChevronQuads(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		float cx, float cy, float cz,
		float yawDegrees,
		float radius,
		int color
	) {
		double rad = Math.toRadians(yawDegrees);
		float fwdX = -(float) Math.sin(rad);
		float fwdZ = (float) Math.cos(rad);
		float rightX = fwdZ;
		float rightZ = -fwdX;

		float baseDist = radius * 1.02F;
		float tipDist = radius * 1.34F;
		float wingW = radius * 0.30F;

		float tipLeftX = cx + fwdX * tipDist - rightX * 0.04F;
		float tipLeftZ = cz + fwdZ * tipDist - rightZ * 0.04F;
		float tipRightX = cx + fwdX * tipDist + rightX * 0.04F;
		float tipRightZ = cz + fwdZ * tipDist + rightZ * 0.04F;
		float leftX = cx + fwdX * baseDist - rightX * wingW;
		float leftZ = cz + fwdZ * baseDist - rightZ * wingW;
		float rightWingX = cx + fwdX * baseDist + rightX * wingW;
		float rightWingZ = cz + fwdZ * baseDist + rightZ * wingW;

		emitQuad(pose, buffer, leftX, cy, leftZ, tipLeftX, cy, tipLeftZ, tipRightX, cy, tipRightZ, rightWingX, cy, rightWingZ, color);
	}

	private static void drawCircleLines(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		float cx, float cy, float cz,
		float radius,
		int color,
		float lineWidth
	) {
		Matrix4f mat = pose.pose();
		for (int i = 0; i < SEGMENTS; i++) {
			float x0 = cx + COS_TABLE[i] * radius;
			float z0 = cz + SIN_TABLE[i] * radius;
			float x1 = cx + COS_TABLE[i + 1] * radius;
			float z1 = cz + SIN_TABLE[i + 1] * radius;

			mat.transformPosition(x0, cy, z0, VEC_A);
			mat.transformPosition(x1, cy, z1, VEC_B);

			float dx = VEC_B.x - VEC_A.x;
			float dy = VEC_B.y - VEC_A.y;
			float dz = VEC_B.z - VEC_A.z;
			float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			float nx = len > 1.0E-5F ? dx / len : 1.0F;
			float ny = len > 1.0E-5F ? dy / len : 0.0F;
			float nz = len > 1.0E-5F ? dz / len : 0.0F;

			buffer.addVertex(VEC_A.x, VEC_A.y, VEC_A.z).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
			buffer.addVertex(VEC_B.x, VEC_B.y, VEC_B.z).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
		}
	}

	/**
	 * Zero-allocation quad emitter with full {@code DefaultVertexFormat.ENTITY} attributes
	 * ({@code Position}, {@code Color}, {@code UV0}, {@code Overlay}, {@code Lightmap}, {@code Normal})
	 * so Iris's extended vertex format and G-buffer tangent/normal calculations succeed on all shaderpacks.
	 */
	private static void emitQuad(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		float x0, float y0, float z0,
		float x1, float y1, float z1,
		float x2, float y2, float z2,
		float x3, float y3, float z3,
		int color
	) {
		Matrix4f mat = pose.pose();
		mat.transformPosition(x0, y0, z0, VEC_A);
		mat.transformPosition(x1, y1, z1, VEC_B);
		mat.transformPosition(x2, y2, z2, VEC_C);
		mat.transformPosition(x3, y3, z3, VEC_D);

		buffer.addVertex(VEC_A.x, VEC_A.y, VEC_A.z)
			.setColor(color)
			.setUv(0.0F, 0.0F)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(FULL_BRIGHT_LIGHT)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
		buffer.addVertex(VEC_B.x, VEC_B.y, VEC_B.z)
			.setColor(color)
			.setUv(0.0F, 1.0F)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(FULL_BRIGHT_LIGHT)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
		buffer.addVertex(VEC_C.x, VEC_C.y, VEC_C.z)
			.setColor(color)
			.setUv(1.0F, 1.0F)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(FULL_BRIGHT_LIGHT)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
		buffer.addVertex(VEC_D.x, VEC_D.y, VEC_D.z)
			.setColor(color)
			.setUv(1.0F, 0.0F)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(FULL_BRIGHT_LIGHT)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}

	private static void drawSegmentLine(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		float x0, float y0, float z0,
		float x1, float y1, float z1,
		int color,
		float lineWidth
	) {
		Matrix4f mat = pose.pose();
		mat.transformPosition(x0, y0, z0, VEC_A);
		mat.transformPosition(x1, y1, z1, VEC_B);
		float dx = VEC_B.x - VEC_A.x;
		float dy = VEC_B.y - VEC_A.y;
		float dz = VEC_B.z - VEC_A.z;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float nx = len > 1.0E-5F ? dx / len : 1.0F;
		float ny = len > 1.0E-5F ? dy / len : 0.0F;
		float nz = len > 1.0E-5F ? dz / len : 0.0F;
		buffer.addVertex(VEC_A.x, VEC_A.y, VEC_A.z).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
		buffer.addVertex(VEC_B.x, VEC_B.y, VEC_B.z).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
	}

	private static void drawEntityPickAabbLines(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		LivingEntity entity,
		Vec3 camPos,
		int color,
		float lineWidth
	) {
		double inflate = entity.getPickRadius() + CursorRaycaster.ENTITY_PICK_INFLATION;
		net.minecraft.world.phys.AABB box = entity.getBoundingBox().inflate(inflate);
		float x0 = (float) (box.minX - camPos.x);
		float y0 = (float) (box.minY - camPos.y);
		float z0 = (float) (box.minZ - camPos.z);
		float x1 = (float) (box.maxX - camPos.x);
		float y1 = (float) (box.maxY - camPos.y);
		float z1 = (float) (box.maxZ - camPos.z);

		// Bottom rectangle
		drawSegmentLine(pose, buffer, x0, y0, z0, x1, y0, z0, color, lineWidth);
		drawSegmentLine(pose, buffer, x1, y0, z0, x1, y0, z1, color, lineWidth);
		drawSegmentLine(pose, buffer, x1, y0, z1, x0, y0, z1, color, lineWidth);
		drawSegmentLine(pose, buffer, x0, y0, z1, x0, y0, z0, color, lineWidth);

		// Top rectangle
		drawSegmentLine(pose, buffer, x0, y1, z0, x1, y1, z0, color, lineWidth);
		drawSegmentLine(pose, buffer, x1, y1, z0, x1, y1, z1, color, lineWidth);
		drawSegmentLine(pose, buffer, x1, y1, z1, x0, y1, z1, color, lineWidth);
		drawSegmentLine(pose, buffer, x0, y1, z1, x0, y1, z0, color, lineWidth);

		// Vertical pillars
		drawSegmentLine(pose, buffer, x0, y0, z0, x0, y1, z0, color, lineWidth);
		drawSegmentLine(pose, buffer, x1, y0, z0, x1, y1, z0, color, lineWidth);
		drawSegmentLine(pose, buffer, x1, y0, z1, x1, y1, z1, color, lineWidth);
		drawSegmentLine(pose, buffer, x0, y0, z1, x0, y1, z1, color, lineWidth);
	}
}
