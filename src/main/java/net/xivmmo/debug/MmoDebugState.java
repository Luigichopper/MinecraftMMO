package net.xivmmo.debug;

import net.xivmmo.XivMmoMod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Central diagnostic & profiling state for Milestone 9 Debug Tooling:
 * - Toggleable on-screen MMO diagnostic overlay (also shown automatically when F3 is active)
 * - Toggleable 3D cursor raycast line and Hard/Soft/Focus target hitbox wireframe rendering
 * - C2S / S2C packet trace ring buffer and structured SLF4J logger
 * - Server combat engine tick microsecond profiler and client per-frame raycast cache metrics
 */
public final class MmoDebugState {
	private static final int MAX_PACKET_LOGS = 16;
	private static final Deque<String> PACKET_LOG_RING = new ArrayDeque<>(MAX_PACKET_LOGS);

	private static volatile boolean debugOverlayEnabled = false;
	private static volatile boolean showTargetingRayAndHitboxes = false;
	private static volatile boolean packetTraceEnabled = false;

	private static volatile double lastServerTickMicros = 0.0;
	private static volatile double rollingAvgServerTickMicros = 0.0;
	private static volatile long totalPacketsSent = 0L;
	private static volatile long totalPacketsReceived = 0L;
	private static volatile long raycastQueries = 0L;
	private static volatile long raycastCacheHits = 0L;

	private MmoDebugState() {
	}

	public static boolean isDebugOverlayEnabled() {
		return debugOverlayEnabled;
	}

	public static void setDebugOverlayEnabled(boolean enabled) {
		debugOverlayEnabled = enabled;
	}

	public static boolean toggleDebugOverlay() {
		debugOverlayEnabled = !debugOverlayEnabled;
		return debugOverlayEnabled;
	}

	public static boolean isShowTargetingRayAndHitboxes() {
		return showTargetingRayAndHitboxes;
	}

	public static void setShowTargetingRayAndHitboxes(boolean enabled) {
		showTargetingRayAndHitboxes = enabled;
	}

	public static boolean toggleTargetingRayAndHitboxes() {
		showTargetingRayAndHitboxes = !showTargetingRayAndHitboxes;
		return showTargetingRayAndHitboxes;
	}

	public static boolean isPacketTraceEnabled() {
		return packetTraceEnabled;
	}

	public static void setPacketTraceEnabled(boolean enabled) {
		packetTraceEnabled = enabled;
	}

	public static boolean togglePacketTrace() {
		packetTraceEnabled = !packetTraceEnabled;
		return packetTraceEnabled;
	}

	public static void recordPacket(String direction, String payloadType, String summary) {
		if ("C2S".equalsIgnoreCase(direction)) {
			totalPacketsSent++;
		} else {
			totalPacketsReceived++;
		}
		if (!packetTraceEnabled) {
			return;
		}
		String entry = String.format(
			Locale.ROOT,
			"[%s] %s: %s",
			direction,
			payloadType,
			summary
		);
		synchronized (PACKET_LOG_RING) {
			if (PACKET_LOG_RING.size() >= MAX_PACKET_LOGS) {
				PACKET_LOG_RING.removeFirst();
			}
			PACKET_LOG_RING.addLast(entry);
		}
		XivMmoMod.LOGGER.info("[XIVMMO-NET] {}", entry);
	}

	public static List<String> getRecentPacketLogs() {
		synchronized (PACKET_LOG_RING) {
			return new ArrayList<>(PACKET_LOG_RING);
		}
	}

	public static void clearPacketLogs() {
		synchronized (PACKET_LOG_RING) {
			PACKET_LOG_RING.clear();
		}
		totalPacketsSent = 0L;
		totalPacketsReceived = 0L;
	}

	public static void recordServerTickDurationNanos(long durationNanos) {
		double micros = durationNanos / 1000.0;
		lastServerTickMicros = micros;
		if (rollingAvgServerTickMicros <= 0.001) {
			rollingAvgServerTickMicros = micros;
		} else {
			rollingAvgServerTickMicros = rollingAvgServerTickMicros * 0.90 + micros * 0.10;
		}
	}

	public static double getLastServerTickMicros() {
		return lastServerTickMicros;
	}

	public static double getRollingAvgServerTickMicros() {
		return rollingAvgServerTickMicros;
	}

	public static long getTotalPacketsSent() {
		return totalPacketsSent;
	}

	public static long getTotalPacketsReceived() {
		return totalPacketsReceived;
	}

	public static void recordClientRaycast(boolean cacheHit) {
		raycastQueries++;
		if (cacheHit) {
			raycastCacheHits++;
		}
	}

	public static double getRaycastCacheHitRatePercent() {
		long total = raycastQueries;
		if (total <= 0L) {
			return 0.0;
		}
		return (raycastCacheHits * 100.0) / total;
	}
}
