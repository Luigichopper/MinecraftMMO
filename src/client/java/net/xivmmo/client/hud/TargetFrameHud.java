package net.xivmmo.client.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.xivmmo.XivMmoMod;
import net.xivmmo.client.combat.ClientCombatPredictor;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.client.target.ClientTargetManager;
import net.xivmmo.config.MmoConfig;
import net.xivmmo.network.MmoNetwork;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Renders the rearrangeable & scalable MMO HUD elements:
 * - {@link MmoConfig.HudWidgetId#TARGET_FRAME} (Hard Target, Target-of-Target, Soft Target, Enmity Rank & Statuses)
 * - {@link MmoConfig.HudWidgetId#FOCUS_TARGET} (Focus Target Mini-Frame)
 * - {@link MmoConfig.HudWidgetId#PARTY_LIST} (Party & Role List with HP/MP bars, Enmity pips, Statuses, Click-to-Target)
 * - {@link MmoConfig.HudWidgetId#COMBAT_TEXT} (Floating Combat Text popups)
 */
public final class TargetFrameHud {
	private static volatile int clientEnmityRank = 0;
	private static volatile float clientEnmityRatio = 0.0F;
	private static volatile List<MmoNetwork.StatusBadge> clientPlayerStatuses = List.of();
	private static volatile List<MmoNetwork.StatusBadge> clientTargetStatuses = List.of();
	private static volatile List<MmoNetwork.PartyMemberEntry> clientPartyMembers = List.of();
	private static volatile Identifier clientActiveJobId = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard");

	private record FloatingCombatEntry(String text, int color, boolean emphasize, long createdAtMs) {
	}

	private static final List<FloatingCombatEntry> FLOATING_TEXT_ENTRIES = new ArrayList<>();

	private TargetFrameHud() {
	}

	public static void onCombatHudSync(MmoNetwork.S2CCombatHudSyncPayload payload) {
		clientEnmityRank = payload.enmityRank();
		clientEnmityRatio = payload.enmityRatio();
		clientPlayerStatuses = List.copyOf(payload.playerStatuses());
		clientTargetStatuses = List.copyOf(payload.targetStatuses());
	}

	public static void onPartySync(MmoNetwork.S2CPartySyncPayload payload) {
		clientActiveJobId = payload.activeJobId();
		clientPartyMembers = List.copyOf(payload.members());
		ClientCombatPredictor.get().setActiveJob(payload.activeJobId());
	}

	public static void onCombatText(MmoNetwork.S2CCombatTextPayload payload) {
		synchronized (FLOATING_TEXT_ENTRIES) {
			if (FLOATING_TEXT_ENTRIES.size() >= 10) {
				FLOATING_TEXT_ENTRIES.removeFirst();
			}
			FLOATING_TEXT_ENTRIES.add(new FloatingCombatEntry(
				payload.text(),
				payload.color(),
				payload.emphasize(),
				System.currentTimeMillis()
			));
		}
	}

	public static Identifier getClientActiveJobId() {
		return clientActiveJobId;
	}

	public static List<MmoNetwork.PartyMemberEntry> getClientPartyMembers() {
		return clientPartyMembers;
	}

	/**
	 * Allows left-clicking a row in the Party List HUD to select that party member as the player's Hard Target.
	 */
	public static boolean tryClickPartyList(double guiX, double guiY, int sw, int sh) {
		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.PARTY_LIST, sw, sh);
		if (!rect.enabled() || !rect.contains(guiX, guiY)) {
			return false;
		}
		List<MmoNetwork.PartyMemberEntry> party = clientPartyMembers;
		if (party.isEmpty()) {
			return false;
		}
		double localY = (guiY - rect.y()) / Math.max(0.5F, rect.scale());
		if (localY < 14.0) {
			return false;
		}
		int rowIdx = (int) ((localY - 14.0) / 22.0);
		if (rowIdx >= 0 && rowIdx < party.size()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				Entity e = mc.level.getEntity(party.get(rowIdx).entityId());
				if (e instanceof LivingEntity living) {
					ClientTargetManager.get().setHardTarget(living);
					return true;
				}
			}
		}
		return false;
	}

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.hud.isHidden()) {
			return;
		}
		if (!MmoInputHandler.isMmoModeActive()) {
			return;
		}

		ClientTargetManager tm = ClientTargetManager.get();
		tm.updateFrame();

		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();

		LivingEntity hardTarget = tm.getHardTarget();
		LivingEntity softTarget = tm.getSoftTarget();
		LivingEntity targetOfTarget = tm.getTargetOfTarget();
		LivingEntity focusTarget = tm.getFocusTarget();

		// 1. TARGET_FRAME Widget (Hard Target, ToT, Soft Target, Target Statuses)
		MmoHudManager.WidgetRect targetRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.TARGET_FRAME, screenWidth, screenHeight
		);
		if (targetRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, targetRect);
			int mainWidth = 196;
			int mainHeight = 34;

			if (hardTarget != null) {
				drawEntityBarFrame(
					graphics,
					mc,
					player,
					hardTarget,
					0,
					0,
					mainWidth,
					mainHeight,
					hardTarget instanceof Enemy ? 0xFFFF5555 : 0xFF55FF88
				);

				List<MmoNetwork.StatusBadge> targetStatuses = clientTargetStatuses;
				if (!targetStatuses.isEmpty()) {
					drawStatusRow(graphics, mc, targetStatuses, 0, mainHeight + 3);
				}

				if (targetOfTarget != null) {
					drawCompactFrame(
						graphics,
						mc,
						targetOfTarget,
						mainWidth + 6,
						4,
						112,
						24,
						">> ToT",
						0xFF66CCFF
					);
				}
			}

			if (softTarget != null && softTarget != hardTarget) {
				int softY = hardTarget != null ? mainHeight + 20 : 0;
				drawCompactFrame(
					graphics,
					mc,
					softTarget,
					16,
					softY,
					mainWidth - 32,
					22,
					"SOFT (HOVER)",
					0xFFFFCC44
				);
			}
			MmoHudManager.popWidgetTransform(graphics);
		}

		// 2. FOCUS_TARGET Widget
		MmoHudManager.WidgetRect focusRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.FOCUS_TARGET, screenWidth, screenHeight
		);
		if (focusRect.enabled() && focusTarget != null) {
			MmoHudManager.pushWidgetTransform(graphics, focusRect);
			drawCompactFrame(
				graphics,
				mc,
				focusTarget,
				0,
				0,
				120,
				24,
				"FOCUS",
				0xFFD888FF
			);
			MmoHudManager.popWidgetTransform(graphics);
		}

		// 3. PARTY_LIST Widget (Role-colored Party Frame)
		MmoHudManager.WidgetRect partyRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.PARTY_LIST, screenWidth, screenHeight
		);
		if (partyRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, partyRect);
			renderPartyList(graphics, mc, player);
			MmoHudManager.popWidgetTransform(graphics);
		}

		// 4. COMBAT_TEXT Widget (Floating Combat Text popups)
		MmoHudManager.WidgetRect fctRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.COMBAT_TEXT, screenWidth, screenHeight
		);
		if (fctRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, fctRect);
			renderFloatingCombatText(graphics, mc);
			MmoHudManager.popWidgetTransform(graphics);
		}

		// 5. Milestone 9 Diagnostic & Custom F3 Debug Overlay
		boolean f3Open = mc.gui.hud.getDebugOverlay().showDebugScreen();
		if (net.xivmmo.debug.MmoDebugState.isDebugOverlayEnabled() || f3Open) {
			renderDebugAndF3Overlay(graphics, mc, player, screenWidth, f3Open);
		}
	}

	public static int getClientEnmityRank() {
		return clientEnmityRank;
	}

	public static float getClientEnmityRatio() {
		return clientEnmityRatio;
	}

	public static List<MmoNetwork.StatusBadge> getClientPlayerStatuses() {
		return clientPlayerStatuses;
	}

	private static void renderPartyList(GuiGraphicsExtractor graphics, Minecraft mc, LocalPlayer localPlayer) {
		List<MmoNetwork.PartyMemberEntry> members = clientPartyMembers;
		int w = MmoConfig.HudWidgetId.PARTY_LIST.baseWidth();

		graphics.fill(0, 0, w, 12, 0xC8101420);
		graphics.fill(0, 0, w, 1, 0xFF4A78B8);
		String title = "PARTY (" + Math.max(1, members.size()) + ") [" + clientActiveJobId.getPath().toUpperCase(Locale.ROOT) + "]";
		graphics.text(mc.font, title, 4, 2, 0xFFAACCEE, true);

		if (members.isEmpty()) {
			// Fallback solo entry before first 10-tick server sync
			drawPartyRow(
				graphics, mc,
				localPlayer.getName().getString(),
				"tank",
				localPlayer.getHealth(),
				localPlayer.getMaxHealth(),
				ClientCombatPredictor.get().getPredictedState().getMana(),
				ClientCombatPredictor.get().getPredictedState().getMaxMana(),
				clientEnmityRank,
				"",
				0,
				14,
				w
			);
			return;
		}

		for (int i = 0; i < members.size(); i++) {
			MmoNetwork.PartyMemberEntry m = members.get(i);
			drawPartyRow(
				graphics, mc,
				m.name(),
				m.role(),
				m.hp(),
				m.maxHp(),
				m.mana(),
				m.maxMana(),
				m.enmityRank(),
				m.topStatus(),
				0,
				14 + i * 22,
				w
			);
		}
	}

	private static void drawPartyRow(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		String name,
		String role,
		float hp,
		float maxHp,
		int mana,
		int maxMana,
		int enmityRank,
		String topStatus,
		int x,
		int y,
		int w
	) {
		int roleColor = switch (role.toLowerCase(Locale.ROOT)) {
			case "tank" -> 0xFF4488FF;
			case "healer" -> 0xFF44DD66;
			default -> 0xFFEE4444;
		};
		String roleTag = switch (role.toLowerCase(Locale.ROOT)) {
			case "tank" -> "T";
			case "healer" -> "H";
			case "melee_dps" -> "M";
			default -> "R";
		};

		graphics.fill(x, y, x + w, y + 20, 0xB010141E);
		graphics.fill(x, y, x + 10, y + 20, roleColor);
		graphics.centeredText(mc.font, roleTag, x + 5, y + 6, 0xFFFFFFFF);

		String enmPrefix = enmityRank == 1 ? "[A] " : (enmityRank > 1 ? "[" + enmityRank + "] " : "");
		String display = enmPrefix + (name.length() > 10 ? name.substring(0, 10) : name);
		graphics.text(mc.font, display, x + 13, y + 2, enmityRank == 1 ? 0xFFFF6666 : 0xFFE8F0FF, true);

		if (topStatus != null && !topStatus.isEmpty()) {
			String stShort = topStatus.substring(0, Math.min(4, topStatus.length()));
			graphics.text(mc.font, stShort, x + w - mc.font.width(stShort) - 3, y + 2, 0xFF66FFAA, true);
		}

		float hpRatio = Mth.clamp(hp / Math.max(1.0F, maxHp), 0.0F, 1.0F);
		float mpRatio = Mth.clamp((float) mana / Math.max(1, maxMana), 0.0F, 1.0F);

		int barX = x + 13;
		int barW = w - 16;
		graphics.fill(barX, y + 12, barX + barW, y + 15, 0xFF1E222D);
		graphics.fill(barX, y + 12, barX + (int) (barW * hpRatio), y + 15, 0xFF44CC66);

		graphics.fill(barX, y + 16, barX + barW, y + 18, 0xFF1E222D);
		graphics.fill(barX, y + 16, barX + (int) (barW * mpRatio), y + 18, 0xFF55AAFF);
	}

	private static void renderFloatingCombatText(GuiGraphicsExtractor graphics, Minecraft mc) {
		long now = System.currentTimeMillis();
		synchronized (FLOATING_TEXT_ENTRIES) {
			if (FLOATING_TEXT_ENTRIES.isEmpty()) {
				return;
			}
			Iterator<FloatingCombatEntry> iter = FLOATING_TEXT_ENTRIES.iterator();
			int index = 0;
			while (iter.hasNext()) {
				FloatingCombatEntry e = iter.next();
				long ageMs = now - e.createdAtMs();
				if (ageMs > 1800L) {
					iter.remove();
					continue;
				}
				float progress = ageMs / 1800.0F;
				int yOffset = (int) (45.0F * (1.0F - progress)) - index * 10;
				String label = e.emphasize() ? "★ " + e.text() + " ★" : e.text();
				graphics.text(mc.font, label, 4, Math.max(0, yOffset), e.color(), true);
				index++;
			}
		}
	}

	private static void drawEntityBarFrame(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		LocalPlayer player,
		LivingEntity target,
		int x,
		int y,
		int width,
		int height,
		int accentColor
	) {
		float hp = Math.max(0.0F, target.getHealth());
		float maxHp = Math.max(1.0F, target.getMaxHealth());
		float hpRatio = Mth.clamp(hp / maxHp, 0.0F, 1.0F);
		double dist = player.distanceTo(target);
		boolean hasLos = player.hasLineOfSight(target);

		graphics.fill(x, y, x + width, y + height, 0xB810141E);
		graphics.fill(x, y, x + width, y + 2, accentColor);
		graphics.fill(x, y + height - 1, x + width, y + height, 0x80303848);

		String nameText = target.getName().getString();
		String enmStr = clientEnmityRank > 0
			? String.format(Locale.ROOT, "[#%d %.0f%%] ", clientEnmityRank, clientEnmityRatio * 100.0F)
			: "";
		String rightInfo = enmStr + String.format(Locale.ROOT, "%.1fm%s", dist, hasLos ? "" : " [!LOS]");
		int rightColor = !hasLos ? 0xFFFF6666 : (clientEnmityRank == 1 ? 0xFFFF5555 : 0xFFA8C0D8);

		graphics.text(mc.font, nameText, x + 6, y + 5, 0xFFFFFFFF, true);
		graphics.text(
			mc.font,
			rightInfo,
			x + width - mc.font.width(rightInfo) - 6,
			y + 5,
			rightColor,
			true
		);

		int barX = x + 6;
		int barY = y + 18;
		int barW = width - 12;
		int barH = 10;
		graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF1E222D);
		int fillW = (int) (barW * hpRatio);
		if (fillW > 0) {
			graphics.fill(barX, barY, barX + fillW, barY + barH, accentColor);
		}

		String hpString = String.format(Locale.ROOT, "%.0f / %.0f (%.0f%%)", hp, maxHp, hpRatio * 100.0F);
		graphics.centeredText(mc.font, hpString, x + width / 2, barY + 1, 0xFFFFFFFF);
	}

	private static void drawCompactFrame(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		LivingEntity entity,
		int x,
		int y,
		int width,
		int height,
		String prefix,
		int accentColor
	) {
		float hp = Math.max(0.0F, entity.getHealth());
		float maxHp = Math.max(1.0F, entity.getMaxHealth());
		float hpRatio = Mth.clamp(hp / maxHp, 0.0F, 1.0F);

		graphics.fill(x, y, x + width, y + height, 0xA810141E);
		graphics.fill(x, y, x + width, y + 1, accentColor);

		String label = prefix + ": " + entity.getName().getString();
		graphics.text(mc.font, label, x + 4, y + 3, accentColor, true);

		int barX = x + 4;
		int barY = y + 14;
		int barW = width - 8;
		int barH = 6;
		graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF1E222D);
		int fillW = (int) (barW * hpRatio);
		if (fillW > 0) {
			graphics.fill(barX, barY, barX + fillW, barY + barH, accentColor);
		}
	}

	public static void drawStatusRow(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		List<MmoNetwork.StatusBadge> badges,
		int startX,
		int y
	) {
		int curX = startX;
		for (int i = 0, size = badges.size(); i < size; i++) {
			MmoNetwork.StatusBadge b = badges.get(i);
			String txt = b.displayName() + " " + b.remainingSeconds() + "s";
			int w = mc.font.width(txt) + 6;
			int h = 13;
			int topColor = b.buff() ? 0xFF44DD88 : 0xFFFF6655;
			graphics.fill(curX, y, curX + w, y + h, 0xC0121622);
			graphics.fill(curX, y, curX + w, y + 1, topColor);
			graphics.text(mc.font, txt, curX + 3, y + 3, topColor, true);
			curX += w + 4;
		}
	}

	private static void renderDebugAndF3Overlay(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		LocalPlayer player,
		int screenWidth,
		boolean f3Open
	) {
		var state = ClientCombatPredictor.get().getPredictedState();
		var tm = ClientTargetManager.get();
		var ray = net.xivmmo.client.target.CursorRaycaster.get();

		String castStr = state.getActiveCast() != null
			? state.getActiveCast().abilityId().getPath() + " (" + (state.getActiveCast().totalTicks() - state.getActiveCast().elapsedTicks()) + "t)"
			: "None";
		String queueStr = state.getQueuedAbility() != null
			? state.getQueuedAbility().abilityId().getPath()
			: "Empty";

		List<String> lines = new ArrayList<>(12);
		lines.add(String.format(
			Locale.ROOT,
			"[XIVMMO DEBUG%s] Job: %s | HotbarLock: %s (Slot %d)",
			f3Open ? " + F3" : "",
			clientActiveJobId.getPath(),
			MmoHudManager.shouldDisableVanillaHotbarMechanics() ? "LOCKED" : "UNLOCKED",
			player.getInventory().getSelectedSlot()
		));
		lines.add(String.format(
			Locale.ROOT,
			"GCD: %d/%dt (%.2fs) | AnimLock: %dt | Cast: %s | Queue: %s",
			state.getGcdRemainingTicks(),
			state.getGcdTotalTicks(),
			state.getGcdRemainingTicks() * 0.05F,
			state.getAnimLockRemainingTicks(),
			castStr,
			queueStr
		));
		lines.add(String.format(
			Locale.ROOT,
			"Targets -> Hard: %d | Soft: %d | Focus: %d | Enmity: #%d (%.0f%%)",
			tm.getHardTargetId(),
			tm.getSoftTargetId(),
			tm.getFocusTargetId(),
			clientEnmityRank,
			clientEnmityRatio * 100.0F
		));
		var dir = ray.getLastRayDirection();
		lines.add(String.format(
			Locale.ROOT,
			"RayDir: (%.2f, %.2f, %.2f) | RayCacheHit: %.1f%% | 3DRay: %s",
			dir.x, dir.y, dir.z,
			net.xivmmo.debug.MmoDebugState.getRaycastCacheHitRatePercent(),
			net.xivmmo.debug.MmoDebugState.isShowTargetingRayAndHitboxes() ? "ON" : "OFF"
		));
		lines.add(String.format(
			Locale.ROOT,
			"ServerCombatTick: %.1f us (avg %.1f us) | Net C2S:%d S2C:%d",
			net.xivmmo.debug.MmoDebugState.getLastServerTickMicros(),
			net.xivmmo.debug.MmoDebugState.getRollingAvgServerTickMicros(),
			net.xivmmo.debug.MmoDebugState.getTotalPacketsSent(),
			net.xivmmo.debug.MmoDebugState.getTotalPacketsReceived()
		));

		if (net.xivmmo.debug.MmoDebugState.isPacketTraceEnabled()) {
			List<String> pktLogs = net.xivmmo.debug.MmoDebugState.getRecentPacketLogs();
			int start = Math.max(0, pktLogs.size() - 4);
			for (int i = start; i < pktLogs.size(); i++) {
				lines.add("  " + pktLogs.get(i));
			}
		}

		int maxW = 0;
		for (String line : lines) {
			maxW = Math.max(maxW, mc.font.width(line));
		}
		int boxW = maxW + 10;
		int boxH = lines.size() * 10 + 6;
		int boxX = Math.max(4, screenWidth - boxW - 6);
		int boxY = f3Open ? 64 : 6;

		graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xB80A101C);
		graphics.outline(boxX, boxY, boxW, boxH, 0xFF44CCFF);

		for (int i = 0; i < lines.size(); i++) {
			int color = i == 0 ? 0xFF55EEFF : 0xFFD8E8F8;
			graphics.text(mc.font, lines.get(i), boxX + 5, boxY + 4 + i * 10, color, true);
		}
	}
}
