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
import net.xivmmo.client.screen.FfxivActionsScreen;
import net.xivmmo.client.screen.FfxivCharacterScreen;
import net.xivmmo.client.screen.FfxivInventoryScreen;
import net.xivmmo.client.target.ClientTargetManager;
import net.xivmmo.combat.ability.JobDefinition;
import net.xivmmo.config.MmoConfig;
import net.xivmmo.data.MmoDataRegistry;
import net.xivmmo.inventory.MmoInventoryState;
import net.xivmmo.network.MmoNetwork;
import net.xivmmo.progression.JobProgressionState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Renders the FFXIV-restyled MMO HUD elements:
 * - {@link MmoConfig.HudWidgetId#TARGET_FRAME} (FFXIV Gold-Beveled Target Bar, Target-of-Target, Soft Target, Enmity & Statuses)
 * - {@link MmoConfig.HudWidgetId#FOCUS_TARGET} (FFXIV Focus Target Mini-Frame)
 * - {@link MmoConfig.HudWidgetId#PARTY_LIST} ("LIGHT PARTY" List with Role Emblems, LvXX Name, side-by-side HP/MP bars & numbers)
 * - {@link MmoConfig.HudWidgetId#PLAYER_VITALS} (FFXIV Bottom Parameter Bar: HP green, MP magenta, TP gold + Bottom Full-Width EXP Bar)
 * - Bottom-Right Main Command Micro-Menu (Character 'C', Inventory 'I', Actions 'P', HUD 'H') + Gil + 4-Quadrant Inventory Grid Indicator
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
	 * Allows left-clicking a row in the Party List HUD (or a button in the Bottom-Right Main Command Micro-Menu)
	 * while in Free-Cursor MMO Mode.
	 */
	public static boolean tryClickPartyList(double guiX, double guiY, int sw, int sh) {
		// 1. Check Bottom-Right Main Command Micro-Menu buttons (C, I, P, H)
		int menuX = sw - 122;
		int menuY = sh - 32;
		if (guiY >= menuY && guiY <= menuY + 18 && guiX >= menuX && guiX <= menuX + 80) {
			int btnIdx = (int) ((guiX - menuX) / 20.0);
			Minecraft mc = Minecraft.getInstance();
			switch (btnIdx) {
				case 0 -> mc.gui.setScreen(new FfxivCharacterScreen());
				case 1 -> mc.gui.setScreen(new FfxivInventoryScreen());
				case 2 -> mc.gui.setScreen(new FfxivActionsScreen());
				case 3 -> mc.gui.setScreen(new HudLayoutScreen(null));
			}
			return true;
		}
		// Clicking the 4-block inventory grid indicator also opens FfxivInventoryScreen
		if (guiY >= menuY && guiY <= menuY + 20 && guiX > menuX + 80 && guiX <= sw - 4) {
			Minecraft.getInstance().gui.setScreen(new FfxivInventoryScreen());
			return true;
		}

		// 2. Check Party List rows
		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(MmoConfig.HudWidgetId.PARTY_LIST, sw, sh);
		if (!rect.enabled() || !rect.contains(guiX, guiY)) {
			return false;
		}
		List<MmoNetwork.PartyMemberEntry> party = clientPartyMembers;
		if (party.isEmpty()) {
			return false;
		}
		double localY = (guiY - rect.y()) / Math.max(0.5F, rect.scale());
		if (localY < 16.0) {
			return false;
		}
		int rowIdx = (int) ((localY - 16.0) / 26.0);
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

		// 1. TARGET_FRAME Widget (FFXIV Gold-Beveled Target Frame + ToT + Soft Target + Statuses)
		MmoHudManager.WidgetRect targetRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.TARGET_FRAME, screenWidth, screenHeight
		);
		if (targetRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, targetRect);
			int mainWidth = 204;
			int mainHeight = 32;

			if (hardTarget != null) {
				drawFfxivTargetBar(
					graphics,
					mc,
					player,
					hardTarget,
					0,
					0,
					mainWidth,
					mainHeight,
					hardTarget instanceof Enemy ? 0xFFFF6666 : 0xFF66EE88
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
						3,
						114,
						24,
						">> ",
						FfxivTheme.TEXT_GOLD
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
					"SOFT: ",
					0xFFFFDF77
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
				126,
				24,
				"FOCUS: ",
				0xFFD99AFF
			);
			MmoHudManager.popWidgetTransform(graphics);
		}

		// 3. PARTY_LIST Widget ("LIGHT PARTY" Frame)
		MmoHudManager.WidgetRect partyRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.PARTY_LIST, screenWidth, screenHeight
		);
		if (partyRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, partyRect);
			renderPartyList(graphics, mc, player);
			MmoHudManager.popWidgetTransform(graphics);
		}

		// 4. PLAYER_VITALS Widget (FFXIV Bottom Parameter Bar: HP / MP / TP) + Full-Width Bottom EXP Bar
		if (MmoConfig.get().useFfxivParameterBar && !MmoInputHandler.isBlockInteractionAllowed()) {
			MmoHudManager.WidgetRect vitalsRect = MmoHudManager.getWidgetRect(
				MmoConfig.HudWidgetId.PLAYER_VITALS, screenWidth, screenHeight
			);
			if (vitalsRect.enabled()) {
				MmoHudManager.pushWidgetTransform(graphics, vitalsRect);
				renderFfxivParameterBar(graphics, mc, player);
				MmoHudManager.popWidgetTransform(graphics);
			}
			renderBottomExpAndMicroMenu(graphics, mc, screenWidth, screenHeight);
		}

		// 5. COMBAT_TEXT Widget (Floating Combat Text popups)
		MmoHudManager.WidgetRect fctRect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.COMBAT_TEXT, screenWidth, screenHeight
		);
		if (fctRect.enabled()) {
			MmoHudManager.pushWidgetTransform(graphics, fctRect);
			renderFloatingCombatText(graphics, mc);
			MmoHudManager.popWidgetTransform(graphics);
		}

		// 6. Milestone 9 Diagnostic & Custom F3 Debug Overlay
		boolean f3Open = mc.gui.hud.getDebugOverlay().showDebugScreen();
		if (net.xivmmo.debug.MmoDebugState.isDebugOverlayEnabled() || f3Open) {
			renderDebugAndF3Overlay(graphics, mc, player, screenWidth, f3Open);
		}
	}

	/**
	 * Renders the FFXIV Bottom Parameter Bar (HP green bar, MP magenta bar, TP gold bar with crisp numbers below)
	 * matching Screenshot #3.
	 */
	private static void renderFfxivParameterBar(GuiGraphicsExtractor graphics, Minecraft mc, LocalPlayer player) {
		ClientCombatPredictor predictor = ClientCombatPredictor.get();
		int jobLv = predictor.getActiveJobLevel();
		JobProgressionState.JobAttributes attrs = JobProgressionState.computeAttributes(
			predictor.getActiveJobId(),
			jobLv,
			player.getArmorValue(),
			2
		);

		float rawHp = Math.max(0.0F, player.getHealth());
		float rawMaxHp = Math.max(1.0F, player.getMaxHealth());
		float hpRatio = Mth.clamp(rawHp / rawMaxHp, 0.0F, 1.0F);
		int ffxivMaxHp = attrs.displayMaxHp();
		int ffxivCurHp = Math.round(ffxivMaxHp * hpRatio);

		int curMp = predictor.getPredictedState().getMana();
		int maxMp = Math.max(1, predictor.getPredictedState().getMaxMana());
		float mpRatio = Mth.clamp((float) curMp / maxMp, 0.0F, 1.0F);

		float foodRatio = Mth.clamp(player.getFoodData().getFoodLevel() / 20.0F, 0.0F, 1.0F);
		int curTp = Math.round(1000.0F * foodRatio);

		int barW = 76;
		int gap = 8;
		int barH = 5;

		// 1. HP Gauge (Green)
		int hpX = 0;
		FfxivTheme.drawGaugeBar(graphics, hpX, 4, barW, barH, hpRatio, FfxivTheme.HP_BAR_FILL, FfxivTheme.HP_BAR_TOP);
		graphics.text(mc.font, "HP", hpX, 11, FfxivTheme.TEXT_GOLD, true);
		String hpNum = Integer.toString(ffxivCurHp);
		graphics.text(mc.font, hpNum, hpX + barW - mc.font.width(hpNum), 11, FfxivTheme.TEXT_HP_GREEN, true);

		// 2. MP Gauge (Magenta)
		int mpX = hpX + barW + gap;
		FfxivTheme.drawGaugeBar(graphics, mpX, 4, barW, barH, mpRatio, FfxivTheme.MP_BAR_FILL, FfxivTheme.MP_BAR_TOP);
		graphics.text(mc.font, "MP", mpX, 11, FfxivTheme.TEXT_GOLD, true);
		String mpNum = Integer.toString(curMp);
		graphics.text(mc.font, mpNum, mpX + barW - mc.font.width(mpNum), 11, FfxivTheme.TEXT_MP_MAGENTA, true);

		// 3. TP Gauge (Gold)
		int tpX = mpX + barW + gap;
		FfxivTheme.drawGaugeBar(graphics, tpX, 4, barW, barH, foodRatio, FfxivTheme.TP_BAR_FILL, FfxivTheme.TP_BAR_TOP);
		graphics.text(mc.font, "TP", tpX, 11, FfxivTheme.TEXT_GOLD, true);
		String tpNum = Integer.toString(curTp);
		graphics.text(mc.font, tpNum, tpX + barW - mc.font.width(tpNum), 11, FfxivTheme.TEXT_TP_GOLD, true);
	}

	/**
	 * Renders the full-width bottom FFXIV Experience Bar ("Lv XX EXP XXXXX/YYYYY") and the
	 * Bottom-Right Main Command Micro-Menu + Gil + 4-Quadrant Inventory Grid Indicator.
	 */
	private static void renderBottomExpAndMicroMenu(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		int sw,
		int sh
	) {
		ClientCombatPredictor predictor = ClientCombatPredictor.get();
		Identifier jobId = predictor.getActiveJobId();
		JobProgressionState.JobLevelEntry prog = predictor.getJobProgress(jobId);

		// 1. Full-Width Bottom EXP Bar
		int expW = Math.min(340, sw - 160);
		int expX = (sw - expW) / 2;
		int expY = sh - 6;
		float expRatio = prog.nextLevelExp() > 0
			? Mth.clamp((float) prog.currentExp() / prog.nextLevelExp(), 0.0F, 1.0F)
			: 1.0F;

		FfxivTheme.drawGaugeBar(graphics, expX, expY, expW, 4, expRatio, FfxivTheme.EXP_BAR_FILL, 0xFFFFF4AA);
		// 10% tick dividers along the EXP bar
		for (int i = 1; i < 10; i++) {
			int tx = expX + (expW * i) / 10;
			graphics.fill(tx, expY, tx + 1, expY + 4, 0x88000000);
		}

		String jobAbbr = FfxivTheme.getJobAbbreviation(jobId);
		String bonusStr = predictor.getClientArmouryBonusPercent() > 0
			? "  (+" + predictor.getClientArmouryBonusPercent() + "% Armoury)"
			: "";
		String expLabel = String.format(
			Locale.ROOT,
			"Lv %d %s   EXP %,d / %,d%s",
			prog.level(),
			jobAbbr,
			prog.currentExp(),
			prog.nextLevelExp(),
			bonusStr
		);
		graphics.pose().pushMatrix();
		graphics.pose().translate(expX + 2.0F, expY - 7.0F);
		graphics.pose().scale(0.75F, 0.75F);
		graphics.text(mc.font, expLabel, 0, 0, FfxivTheme.TEXT_CREAM, true);
		graphics.pose().popMatrix();

		// 2. Bottom-Right Main Command Micro-Menu (C, I, P, H) + Gil + 4-Block Inventory Grid
		int menuX = sw - 122;
		int menuY = sh - 32;
		String[] btnLabels = {"C", "I", "P", "H"};
		int[] btnColors = {0xFFD9BE7E, 0xFF88CCFF, 0xFFFF99CC, 0xFF99EEAA};
		for (int i = 0; i < 4; i++) {
			int bx = menuX + i * 20;
			graphics.fill(bx, menuY, bx + 17, menuY + 16, 0xEE1A1C24);
			graphics.outline(bx, menuY, 17, 16, FfxivTheme.GOLD_OUTER);
			graphics.centeredText(mc.font, btnLabels[i], bx + 8, menuY + 4, btnColors[i]);
		}

		// 4-Quadrant Inventory Grid Indicator (right of micro-menu)
		int gridX = menuX + 83;
		int gridY = menuY;
		graphics.fill(gridX - 2, gridY, gridX + 35, gridY + 16, 0xEE161820);
		graphics.outline(gridX - 2, gridY, 37, 16, FfxivTheme.GOLD_OUTER);
		for (int q = 0; q < 4; q++) {
			int qx = gridX + q * 8;
			int usedInQuad = 0;
			for (int s = q * 35; s < (q + 1) * 35; s++) {
				if (!predictor.getClientInventorySlot(s).isEmpty()) {
					usedInQuad++;
				}
			}
			int dotColor = usedInQuad >= 35 ? 0xFFFF5555 : (usedInQuad > 0 ? 0xFF66DDFF : 0xFF333846);
			graphics.fill(qx, gridY + 3, qx + 6, gridY + 13, dotColor);
			graphics.outline(qx, gridY + 3, 6, 10, 0xFF0E1015);
		}

		// Gil readout below micro-menu
		String gilText = String.format(Locale.ROOT, "%,d Gil", predictor.getClientGil());
		graphics.pose().pushMatrix();
		graphics.pose().translate(sw - 6.0F - mc.font.width(gilText) * 0.75F, sh - 12.0F);
		graphics.pose().scale(0.75F, 0.75F);
		graphics.text(mc.font, gilText, 0, 0, FfxivTheme.TEXT_GOLD, true);
		graphics.pose().popMatrix();
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

		// FFXIV "LIGHT PARTY" Gold Header + Horizontal Rule
		String header = members.size() > 4 ? "FULL PARTY" : "LIGHT PARTY";
		graphics.text(mc.font, header, 2, 1, FfxivTheme.TEXT_GOLD, true);
		graphics.fill(0, 11, w, 12, FfxivTheme.GOLD_RULE);
		graphics.fill(0, 12, w, 13, 0x88000000);

		if (members.isEmpty()) {
			ClientCombatPredictor pred = ClientCombatPredictor.get();
			JobDefinition job = MmoDataRegistry.getJob(pred.getActiveJobId());
			String role = job != null ? job.role().getSerializedName() : "tank";
			drawPartyRow(
				graphics, mc,
				localPlayer.getName().getString(),
				role,
				pred.getActiveJobLevel(),
				localPlayer.getHealth(),
				localPlayer.getMaxHealth(),
				pred.getPredictedState().getMana(),
				pred.getPredictedState().getMaxMana(),
				clientEnmityRank,
				"",
				0,
				16,
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
				m.level(),
				m.hp(),
				m.maxHp(),
				m.mana(),
				m.maxMana(),
				m.enmityRank(),
				m.topStatus(),
				0,
				16 + i * 26,
				w
			);
		}
	}

	private static void drawPartyRow(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		String name,
		String role,
		int level,
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
		graphics.fill(x, y, x + w, y + 24, 0xCC141720);
		graphics.outline(x, y, w, 24, 0xFF323644);

		// Left Role Emblem (Tank Shield / Healer Cross / DPS Blade)
		FfxivTheme.drawRoleEmblem(graphics, x + 2, y + 2, role);

		// "LvXX PlayerName" + Enmity Rank Badge
		String enmPrefix = enmityRank == 1 ? "[A] " : (enmityRank > 1 ? "[" + enmityRank + "] " : "");
		String shortName = name.length() > 11 ? name.substring(0, 11) : name;
		String title = "Lv" + Math.max(1, level) + " " + enmPrefix + shortName;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + 16.0F, y + 2.0F);
		graphics.pose().scale(0.80F, 0.80F);
		graphics.text(mc.font, title, 0, 0, enmityRank == 1 ? 0xFFFF7777 : FfxivTheme.TEXT_CREAM, true);
		graphics.pose().popMatrix();

		if (topStatus != null && !topStatus.isEmpty()) {
			String stShort = topStatus.substring(0, Math.min(4, topStatus.length()));
			graphics.pose().pushMatrix();
			graphics.pose().translate(x + w - 22.0F, y + 2.0F);
			graphics.pose().scale(0.75F, 0.75F);
			graphics.text(mc.font, stShort, 0, 0, 0xFF77FFAA, true);
			graphics.pose().popMatrix();
		}

		// Side-by-side HP (Green) and MP (Magenta) bars + numeric readouts below (matching FFXIV Party List)
		float hpRatio = Mth.clamp(hp / Math.max(1.0F, maxHp), 0.0F, 1.0F);
		float mpRatio = Mth.clamp((float) mana / Math.max(1, maxMana), 0.0F, 1.0F);

		int hpBarX = x + 16;
		int hpBarW = 72;
		int mpBarX = hpBarX + hpBarW + 6;
		int mpBarW = w - (mpBarX - x) - 4;

		FfxivTheme.drawGaugeBar(graphics, hpBarX, y + 11, hpBarW, 4, hpRatio, FfxivTheme.HP_BAR_FILL, FfxivTheme.HP_BAR_TOP);
		FfxivTheme.drawGaugeBar(graphics, mpBarX, y + 11, mpBarW, 4, mpRatio, FfxivTheme.MP_BAR_FILL, FfxivTheme.MP_BAR_TOP);

		int scaledHp = Math.round(hp * 25.0F + level * 15.0F);
		graphics.pose().pushMatrix();
		graphics.pose().translate(hpBarX, y + 17.0F);
		graphics.pose().scale(0.65F, 0.65F);
		graphics.text(mc.font, "HP " + scaledHp, 0, 0, FfxivTheme.TEXT_HP_GREEN, true);
		graphics.pose().popMatrix();

		graphics.pose().pushMatrix();
		graphics.pose().translate(mpBarX, y + 17.0F);
		graphics.pose().scale(0.65F, 0.65F);
		graphics.text(mc.font, "MP " + mana, 0, 0, FfxivTheme.TEXT_MP_MAGENTA, true);
		graphics.pose().popMatrix();
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

	private static void drawFfxivTargetBar(
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

		graphics.fill(x, y, x + width, y + height, 0xCC161820);
		graphics.outline(x, y, width, height, FfxivTheme.GOLD_RULE);
		graphics.fill(x + 1, y + 1, x + width - 1, y + 2, FfxivTheme.GOLD_BRIGHT);

		int estLevel = target.getMaxHealth() >= 80.0F ? 50 : Math.clamp(Math.round(target.getMaxHealth() * 1.5F), 1, 50);
		String nameText = "Lv" + estLevel + "  " + target.getName().getString();
		String enmStr = clientEnmityRank > 0
			? String.format(Locale.ROOT, "[#%d %.0f%%] ", clientEnmityRank, clientEnmityRatio * 100.0F)
			: "";
		String rightInfo = enmStr + String.format(Locale.ROOT, "%.1fm%s", dist, hasLos ? "" : " [!LOS]");
		int rightColor = !hasLos ? 0xFFFF6666 : (clientEnmityRank == 1 ? 0xFFFF7777 : FfxivTheme.TEXT_SILVER);

		graphics.text(mc.font, nameText, x + 6, y + 5, FfxivTheme.TEXT_GOLD, true);
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + width - 6.0F - mc.font.width(rightInfo) * 0.80F, y + 6.0F);
		graphics.pose().scale(0.80F, 0.80F);
		graphics.text(mc.font, rightInfo, 0, 0, rightColor, true);
		graphics.pose().popMatrix();

		int barX = x + 6;
		int barY = y + 18;
		int barW = width - 12;
		int barH = 7;
		FfxivTheme.drawGaugeBar(graphics, barX, barY, barW, barH, hpRatio, accentColor, 0xFFFFB8B8);

		String hpPct = String.format(Locale.ROOT, "%.0f%%", hpRatio * 100.0F);
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + width / 2.0F - 8.0F, barY + 1.0F);
		graphics.pose().scale(0.75F, 0.75F);
		graphics.text(mc.font, hpPct, 0, 0, FfxivTheme.TEXT_CREAM, true);
		graphics.pose().popMatrix();
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

		graphics.fill(x, y, x + width, y + height, 0xCC161820);
		graphics.outline(x, y, width, height, FfxivTheme.GOLD_OUTER);

		String label = prefix + entity.getName().getString();
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + 4.0F, y + 3.0F);
		graphics.pose().scale(0.80F, 0.80F);
		graphics.text(mc.font, label, 0, 0, accentColor, true);
		graphics.pose().popMatrix();

		int barX = x + 4;
		int barY = y + 14;
		int barW = width - 8;
		int barH = 5;
		FfxivTheme.drawGaugeBar(graphics, barX, barY, barW, barH, hpRatio, accentColor, 0xFFFFFFFF);
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
			int topColor = b.buff() ? 0xFF55EE99 : 0xFFFF6655;
			graphics.fill(curX, y, curX + w, y + h, 0xD8161820);
			graphics.outline(curX, y, w, h, FfxivTheme.GOLD_OUTER);
			graphics.fill(curX + 1, y + 1, curX + w - 1, y + 2, topColor);
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
		var predictor = ClientCombatPredictor.get();
		var state = predictor.getPredictedState();
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
			"[XIVMMO DEBUG%s] Job: %s (Lv.%d) | Gil: %d | Bag: %d/%d",
			f3Open ? " + F3" : "",
			clientActiveJobId.getPath(),
			predictor.getActiveJobLevel(),
			predictor.getClientGil(),
			predictor.getUsedMainInventoryCount(),
			MmoInventoryState.TOTAL_MAIN_SLOTS
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
		graphics.outline(boxX, boxY, boxW, boxH, FfxivTheme.GOLD_RULE);

		for (int i = 0; i < lines.size(); i++) {
			int color = i == 0 ? FfxivTheme.TEXT_GOLD : FfxivTheme.TEXT_CREAM;
			graphics.text(mc.font, lines.get(i), boxX + 5, boxY + 4 + i * 10, color, true);
		}
	}
}
