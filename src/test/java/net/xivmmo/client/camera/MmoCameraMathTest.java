package net.xivmmo.client.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MmoCameraMathTest {
	@Test
	void testClampPitchBounds() {
		assertEquals(-85.0F, MmoCameraController.clampPitch(-120.0F), 1e-4F);
		assertEquals(85.0F, MmoCameraController.clampPitch(95.0F), 1e-4F);
		assertEquals(22.5F, MmoCameraController.clampPitch(22.5F), 1e-4F);
	}

	@Test
	void testWrapYawNormalization() {
		assertEquals(0.0F, MmoCameraController.wrapYaw(360.0F), 1e-4F);
		assertEquals(-90.0F, MmoCameraController.wrapYaw(270.0F), 1e-4F);
		assertEquals(90.0F, MmoCameraController.wrapYaw(-270.0F), 1e-4F);
	}

	@Test
	void testClampZoomBounds() {
		assertEquals(1.5F, MmoCameraController.clampZoom(0.2F, 1.5F, 16.0F), 1e-4F);
		assertEquals(16.0F, MmoCameraController.clampZoom(25.0F, 1.5F, 16.0F), 1e-4F);
		assertEquals(8.0F, MmoCameraController.clampZoom(8.0F, 1.5F, 16.0F), 1e-4F);
	}

	@Test
	void testComputeMovementYawRelativeToCamera() {
		// Forward (+1, 0) matches camera yaw
		assertEquals(45.0F, MmoCameraController.computeMovementYaw(45.0F, 1.0F, 0.0F), 1e-3F);
		// Backward (-1, 0) is opposite camera yaw (45 - 180 = -135)
		assertEquals(-135.0F, MmoCameraController.computeMovementYaw(45.0F, -1.0F, 0.0F), 1e-3F);
		// Strafe left (0, +1) is cameraYaw - 90 deg
		assertEquals(-90.0F, MmoCameraController.computeMovementYaw(0.0F, 0.0F, 1.0F), 1e-3F);
		// Strafe right (0, -1) is cameraYaw + 90 deg
		assertEquals(90.0F, MmoCameraController.computeMovementYaw(0.0F, 0.0F, -1.0F), 1e-3F);
		// Diagonal forward-right (+1, -1) is cameraYaw + 45 deg
		assertEquals(45.0F, MmoCameraController.computeMovementYaw(0.0F, 1.0F, -1.0F), 1e-3F);
	}

	@Test
	void testApproachYawStepsSmoothlyAcrossWrapBoundary() {
		float stepped = MmoCameraController.approachYaw(170.0F, -170.0F, 10.0F);
		assertEquals(-180.0F, stepped, 1e-3F);
	}
}
