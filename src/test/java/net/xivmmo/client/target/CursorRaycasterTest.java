package net.xivmmo.client.target;

import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.engine.ServerCombatEngine;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorRaycasterTest {
	@Test
	void testCenterCursorUnprojectsStraightAlongCameraForward() {
		// Perspective matrix looking along -Z (identity view rotation)
		Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 100.0F);
		Matrix4f invScratch = new Matrix4f();
		Vector3f nearScratch = new Vector3f();
		Vector3f farScratch = new Vector3f();
		Vector3f outDir = new Vector3f();

		CursorRaycaster.unprojectToRayDirection(
			proj,
			960.0F,
			540.0F,
			1920.0F,
			1080.0F,
			invScratch,
			nearScratch,
			farScratch,
			outDir
		);

		assertEquals(0.0F, outDir.x, 1e-4F);
		assertEquals(0.0F, outDir.y, 1e-4F);
		assertEquals(-1.0F, outDir.z, 1e-4F);
	}

	@Test
	void testOffCenterCursorUnprojectsIntoExpectedScreenQuadrant() {
		Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 100.0F);
		Matrix4f invScratch = new Matrix4f();
		Vector3f nearScratch = new Vector3f();
		Vector3f farScratch = new Vector3f();
		Vector3f outDir = new Vector3f();

		// Top-right corner (x = 1920, y = 0) should point +X, +Y, -Z
		CursorRaycaster.unprojectToRayDirection(
			proj,
			1920.0F,
			0.0F,
			1920.0F,
			1080.0F,
			invScratch,
			nearScratch,
			farScratch,
			outDir
		);

		assertTrue(outDir.x > 0.3F);
		assertTrue(outDir.y > 0.2F);
		assertTrue(outDir.z < -0.5F);
	}

	@Test
	void testReverseZProjectionUnprojectsForwardAndHitsMobAabb() {
		// Minecraft 26.2 Projection uses Reverse-Z: setPerspective(fov, aspect, zFar, zNear, zZeroToOne)
		Matrix4f reverseZProj = new Matrix4f().setPerspective(
			(float) Math.toRadians(70.0),
			16.0F / 9.0F,
			256.0F, // zFar passed as 3rd param in Reverse-Z
			0.05F,  // zNear passed as 4th param in Reverse-Z
			true
		);
		Matrix4f invScratch = new Matrix4f();
		Vector3f nearScratch = new Vector3f();
		Vector3f farScratch = new Vector3f();
		Vector3f outDir = new Vector3f();

		// Center cursor (960, 540) must still point forward along -Z (not +Z backwards!)
		CursorRaycaster.unprojectToRayDirection(
			reverseZProj,
			960.0F,
			540.0F,
			1920.0F,
			1080.0F,
			invScratch,
			nearScratch,
			farScratch,
			outDir
		);
		assertEquals(0.0F, outDir.x, 1e-4F);
		assertEquals(0.0F, outDir.y, 1e-4F);
		assertEquals(-1.0F, outDir.z, 1e-4F);

		// Ray from camera (0, 1.6, 0) along outDir (0, 0, -1) must hit a mob standing at (0, 0..2, -8)
		double hitT = CursorRaycaster.intersectRayAabb(
			0.0, 1.6, 0.0,
			outDir.x, outDir.y, outDir.z,
			-0.6, 0.0, -8.6,
			0.6, 2.0, -7.4,
			30.0
		);
		assertTrue(hitT > 7.0 && hitT < 8.0);
	}

	@Test
	void testRayVsAabbHitMissAndMaxRange() {
		// Ray from origin (0, 1.5, 0) along +Z (0, 0, 1) against AABB at z in [5, 6]
		double hitDist = CursorRaycaster.intersectRayAabb(
			0.0, 1.5, 0.0,
			0.0, 0.0, 1.0,
			-0.5, 0.0, 5.0,
			0.5, 2.0, 6.0,
			30.0
		);
		assertEquals(5.0, hitDist, 1e-5);

		// Out of max range (maxRange = 4.0)
		double outOfRange = CursorRaycaster.intersectRayAabb(
			0.0, 1.5, 0.0,
			0.0, 0.0, 1.0,
			-0.5, 0.0, 5.0,
			0.5, 2.0, 6.0,
			4.0
		);
		assertEquals(-1.0, outOfRange, 1e-5);

		// Ray pointing away (0, 0, -1) misses
		double missBehind = CursorRaycaster.intersectRayAabb(
			0.0, 1.5, 0.0,
			0.0, 0.0, -1.0,
			-0.5, 0.0, 5.0,
			0.5, 2.0, 6.0,
			30.0
		);
		assertEquals(-1.0, missBehind, 1e-5);
	}

	@Test
	void testHorizontalConeAndPositionalQuadrants() {
		// Origin at (0,0) facing yaw 0 (+Z)
		assertTrue(ServerCombatEngine.isPointInHorizontalCone(0.0, 0.0, 0.0F, 0.0, 10.0, 90.0F));
		assertFalse(ServerCombatEngine.isPointInHorizontalCone(0.0, 0.0, 0.0F, 0.0, -10.0, 90.0F));

		// Target at (0,0) facing yaw 0 (+Z):
		// Attacker at (0, 5) is in FRONT
		assertEquals(
			AbilityDefinition.Positional.FRONT,
			ServerCombatEngine.computeAttackerPositional(0.0, 0.0, 0.0F, 0.0, 5.0)
		);
		// Attacker at (0, -5) is in REAR
		assertEquals(
			AbilityDefinition.Positional.REAR,
			ServerCombatEngine.computeAttackerPositional(0.0, 0.0, 0.0F, 0.0, -5.0)
		);
		// Attacker at (5, 0) is on FLANK
		assertEquals(
			AbilityDefinition.Positional.FLANK,
			ServerCombatEngine.computeAttackerPositional(0.0, 0.0, 0.0F, 5.0, 0.0)
		);
	}
}
