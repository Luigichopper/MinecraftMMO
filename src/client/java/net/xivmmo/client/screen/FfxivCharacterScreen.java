package net.xivmmo.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.xivmmo.client.combat.ClientCombatPredictor;
import net.xivmmo.client.hud.FfxivTheme;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.combat.ability.JobDefinition;
import net.xivmmo.data.MmoDataRegistry;
import net.xivmmo.inventory.MmoInventoryState;
import net.xivmmo.network.MmoNetwork;
import net.xivmmo.progression.JobProgressionState;

import java.util.Locale;

/**
 * FFXIV-inspired Character & Attributes Screen ('C').
 * Replicates the layout from the FFXIV Character window:
 * - Left pane: Attributes (STR, DEX, VIT, INT, MND, PIE) & Offensive / Defensive / Physical / Mental Properties
 * - Center pane: HP / MP / TP summary, 3D live player paperdoll, and flanking Equipment + Soul Crystal slots
 * - Right pane: Classes / Gear Set list (VGD, HIE, LNC, ARC) with independent levels, EXP bars, click-to-switch Job,
 *   and quick EXP/Level test controls.
 */
public final class FfxivCharacterScreen extends Screen {
	private int winX;
	private int winY;
	private final int winW = 412;
	private final int winH = 236;

	public FfxivCharacterScreen() {
		super(Component.literal("Character"));
	}

	@Override
	protected void init() {
		super.init();
		this.winX = (this.width - this.winW) / 2;
		this.winY = Math.max(6, (this.height - this.winH) / 2);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.winX = (this.width - this.winW) / 2;
		this.winY = Math.max(6, (this.height - this.winH) / 2);

		graphics.fill(0, 0, this.width, this.height, 0x7006070B);
		String playerName = this.minecraft != null && this.minecraft.player != null
			? this.minecraft.player.getName().getString()
			: "Adventurer";
		FfxivTheme.drawWindowFrame(
			graphics,
			this.font,
			this.winX,
			this.winY,
			this.winW,
			this.winH,
			"CHARACTER  —  " + playerName,
			true
		);

		ClientCombatPredictor predictor = ClientCombatPredictor.get();
		Identifier activeJobId = predictor.getActiveJobId();
		JobProgressionState.JobLevelEntry activeProg = predictor.getJobProgress(activeJobId);
		JobDefinition activeJob = MmoDataRegistry.getJob(activeJobId);
		String roleStr = activeJob != null ? activeJob.role().getSerializedName() : "tank";
		int armorVal = this.minecraft != null && this.minecraft.player != null ? this.minecraft.player.getArmorValue() : 0;
		JobProgressionState.JobAttributes attrs = JobProgressionState.computeAttributes(activeJobId, activeProg.level(), armorVal, 2);

		// ==================== 1. LEFT PANE: ATTRIBUTES & PROPERTIES ====================
		int attrX = this.winX + 8;
		int attrY = this.winY + 26;
		int attrW = 136;
		int attrH = this.winH - 34;
		graphics.fill(attrX, attrY, attrX + attrW, attrY + attrH, 0xCC161820);
		graphics.outline(attrX, attrY, attrW, attrH, FfxivTheme.GOLD_RULE);

		int cy = attrY + 4;
		cy = drawStatHeader(graphics, attrX + 4, cy, attrW - 8, "Attributes");
		cy = drawStatLine(graphics, attrX + 6, cy, attrW - 12, "Strength", attrs.strength(), "Dexterity", attrs.dexterity());
		cy = drawStatLine(graphics, attrX + 6, cy, attrW - 12, "Vitality", attrs.vitality(), "Intelligence", attrs.intelligence());
		cy = drawStatLine(graphics, attrX + 6, cy, attrW - 12, "Mind", attrs.mind(), "Piety", attrs.piety());

		cy += 3;
		cy = drawStatHeader(graphics, attrX + 4, cy, attrW - 8, "Offensive Properties");
		cy = drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Accuracy", attrs.accuracy());
		cy = drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Critical Hit Rate", attrs.criticalHitRate());
		cy = drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Determination", attrs.determination());

		cy += 3;
		cy = drawStatHeader(graphics, attrX + 4, cy, attrW - 8, "Defensive Properties");
		cy = drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Defense", attrs.defense());
		cy = drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Parry", attrs.parry());
		cy = drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Magic Defense", attrs.magicDefense());

		cy += 3;
		cy = drawStatHeader(graphics, attrX + 4, cy, attrW - 8, "Physical & Mental");
		cy = drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Attack Power", attrs.attackPower());
		cy = drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Skill / Spell Spd", Math.max(attrs.skillSpeed(), attrs.spellSpeed()));
		drawSingleStat(graphics, attrX + 6, cy, attrW - 12, "Magic / Heal Pot.", Math.max(attrs.attackMagicPotency(), attrs.healingMagicPotency()));

		// ==================== 2. CENTER PANE: 3D PAPERDOLL & EQUIPMENT ====================
		int midX = attrX + attrW + 6;
		int midY = attrY;
		int midW = 132;
		int midH = attrH;
		graphics.fill(midX, midY, midX + midW, midY + midH, 0xCC14161D);
		graphics.outline(midX, midY, midW, midH, FfxivTheme.GOLD_RULE);

		// Top HP / MP / TP summary banner
		int hpVal = attrs.displayMaxHp();
		int mpVal = predictor.getPredictedState().getMaxMana();
		graphics.pose().pushMatrix();
		graphics.pose().translate(midX + 6.0F, midY + 5.0F);
		graphics.pose().scale(0.80F, 0.80F);
		graphics.text(this.font, "HP " + hpVal, 0, 0, FfxivTheme.TEXT_HP_GREEN, true);
		graphics.text(this.font, "MP " + mpVal, 54, 0, FfxivTheme.TEXT_MP_MAGENTA, true);
		graphics.text(this.font, "TP 1000", 112, 0, FfxivTheme.TEXT_TP_GOLD, true);
		graphics.pose().popMatrix();

		// 3D Paperdoll Viewport
		int dollX0 = midX + 26;
		int dollY0 = midY + 18;
		int dollX1 = midX + midW - 26;
		int dollY1 = midY + midH - 30;
		graphics.fill(dollX0, dollY0, dollX1, dollY1, 0xFF0E1117);
		graphics.outline(dollX0, dollY0, dollX1 - dollX0, dollY1 - dollY0, 0xFF3A3F4E);

		if (this.minecraft != null && this.minecraft.player != null) {
			InventoryScreen.extractEntityInInventoryFollowsMouse(
				graphics,
				dollX0,
				dollY0,
				dollX1,
				dollY1,
				48,
				0.0625F,
				mouseX,
				mouseY,
				this.minecraft.player
			);
		}

		// Flanking Equipment Slots (Left: MainHand, Head, Chest, Legs | Right: OffHand, Feet, Ring, Soul Crystal)
		ItemStack hoveredEquip = ItemStack.EMPTY;
		if (this.minecraft != null && this.minecraft.player != null) {
			ItemStack[] leftGear = {
				this.minecraft.player.getItemBySlot(EquipmentSlot.MAINHAND),
				this.minecraft.player.getItemBySlot(EquipmentSlot.HEAD),
				this.minecraft.player.getItemBySlot(EquipmentSlot.CHEST),
				this.minecraft.player.getItemBySlot(EquipmentSlot.LEGS)
			};
			int jobIdx = Math.max(0, ClientCombatPredictor.PLAYABLE_JOBS.indexOf(activeJobId));
			ItemStack soulCrystal = predictor.getClientInventorySlot(MmoInventoryState.TOTAL_MAIN_SLOTS + jobIdx);
			ItemStack[] rightGear = {
				this.minecraft.player.getItemBySlot(EquipmentSlot.OFFHAND),
				this.minecraft.player.getItemBySlot(EquipmentSlot.FEET),
				predictor.getClientInventorySlot(0),
				soulCrystal
			};

			for (int i = 0; i < 4; i++) {
				int sy = dollY0 + 6 + i * 32;
				int lx = midX + 4;
				boolean lHover = mouseX >= lx && mouseX < lx + 18 && mouseY >= sy && mouseY < sy + 18;
				FfxivTheme.drawRecessedSlot(graphics, lx, sy, 18, lHover, false, FfxivTheme.GOLD_OUTER);
				if (!leftGear[i].isEmpty()) {
					graphics.item(leftGear[i], lx + 1, sy + 1);
					if (lHover) {
						hoveredEquip = leftGear[i];
					}
				}

				int rx = midX + midW - 22;
				boolean rHover = mouseX >= rx && mouseX < rx + 18 && mouseY >= sy && mouseY < sy + 18;
				FfxivTheme.drawRecessedSlot(graphics, rx, sy, 18, rHover, i == 3, i == 3 ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.GOLD_OUTER);
				if (!rightGear[i].isEmpty()) {
					graphics.item(rightGear[i], rx + 1, sy + 1);
					if (rHover) {
						hoveredEquip = rightGear[i];
					}
				}
			}
		}

		// Active Job Level & EXP Footer inside Center Pane
		FfxivTheme.drawRoleEmblem(graphics, midX + 6, midY + midH - 25, roleStr);
		String jobTitle = (activeJob != null ? activeJob.displayName() : "Vanguard") + "  Lv. " + activeProg.level();
		graphics.text(this.font, jobTitle, midX + 22, midY + midH - 23, FfxivTheme.TEXT_GOLD, true);

		float expRatio = activeProg.nextLevelExp() > 0
			? (float) activeProg.currentExp() / activeProg.nextLevelExp()
			: 1.0F;
		FfxivTheme.drawGaugeBar(graphics, midX + 6, midY + midH - 11, midW - 12, 6, expRatio, FfxivTheme.EXP_BAR_FILL, 0xFFFFF0A0);

		// ==================== 3. RIGHT PANE: CLASSES / GEAR SET LIST ====================
		int clsX = midX + midW + 6;
		int clsY = attrY;
		int clsW = this.winX + this.winW - clsX - 8;
		int clsH = attrH;
		graphics.fill(clsX, clsY, clsX + clsW, clsY + clsH, 0xCC161820);
		graphics.outline(clsX, clsY, clsW, clsH, FfxivTheme.GOLD_RULE);

		drawStatHeader(graphics, clsX + 4, clsY + 4, clsW - 8, "Classes & Jobs (Click)");

		int rowY = clsY + 18;
		for (int i = 0; i < ClientCombatPredictor.PLAYABLE_JOBS.size(); i++) {
			Identifier jId = ClientCombatPredictor.PLAYABLE_JOBS.get(i);
			JobDefinition jDef = MmoDataRegistry.getJob(jId);
			JobProgressionState.JobLevelEntry jProg = predictor.getJobProgress(jId);
			boolean isCurrent = jId.equals(activeJobId);
			boolean isHover = mouseX >= clsX + 4 && mouseX <= clsX + clsW - 4 && mouseY >= rowY && mouseY <= rowY + 30;

			graphics.fill(
				clsX + 4,
				rowY,
				clsX + clsW - 4,
				rowY + 30,
				isCurrent ? 0xFF332D1E : (isHover ? 0xFF252936 : 0xFF1A1D26)
			);
			graphics.outline(
				clsX + 4,
				rowY,
				clsW - 8,
				30,
				isCurrent ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.SILVER_MUTED
			);

			String jRole = jDef != null ? jDef.role().getSerializedName() : "tank";
			FfxivTheme.drawRoleEmblem(graphics, clsX + 8, rowY + 4, jRole);

			String line1 = (i + 1) + ". " + FfxivTheme.getJobAbbreviation(jId) + "  (Lv. " + jProg.level() + ")";
			graphics.text(this.font, line1, clsX + 23, rowY + 5, isCurrent ? FfxivTheme.TEXT_GOLD : FfxivTheme.TEXT_CREAM, true);

			float jRatio = jProg.nextLevelExp() > 0 ? (float) jProg.currentExp() / jProg.nextLevelExp() : 1.0F;
			FfxivTheme.drawGaugeBar(graphics, clsX + 8, rowY + 19, clsW - 16, 4, jRatio, FfxivTheme.EXP_BAR_FILL, 0xFFFFF0A0);

			String expNums = jProg.level() >= JobProgressionState.MAX_LEVEL
				? "MAX LEVEL"
				: jProg.currentExp() + " / " + jProg.nextLevelExp();
			graphics.pose().pushMatrix();
			graphics.pose().translate(clsX + 8.0F, rowY + 24.0F);
			graphics.pose().scale(0.65F, 0.65F);
			graphics.text(this.font, expNums, 0, 0, FfxivTheme.TEXT_SILVER, true);
			graphics.pose().popMatrix();

			rowY += 34;
		}

		// Quick Leveling / EXP Testing Controls at bottom of Classes pane
		int btnY = clsY + clsH - 36;
		graphics.text(this.font, "Training / Level Sync:", clsX + 6, btnY - 11, FfxivTheme.TEXT_GOLD, true);

		drawSmallButton(graphics, clsX + 6, btnY, 52, 14, "+500 EXP", mouseX, mouseY);
		drawSmallButton(graphics, clsX + 62, btnY, 52, 14, "+1 Level", mouseX, mouseY);
		drawSmallButton(graphics, clsX + 6, btnY + 17, 52, 14, "Sync Lv 1", mouseX, mouseY);
		drawSmallButton(graphics, clsX + 62, btnY + 17, 52, 14, "Max Lv 50", mouseX, mouseY);

		if (!hoveredEquip.isEmpty()) {
			graphics.setTooltipForNextFrame(this.font, hoveredEquip, mouseX, mouseY);
		}
	}

	private int drawStatHeader(GuiGraphicsExtractor g, int x, int y, int w, String title) {
		g.fill(x, y, x + w, y + 10, 0xFF262934);
		g.fill(x, y + 9, x + w, y + 10, FfxivTheme.GOLD_RULE);
		g.pose().pushMatrix();
		g.pose().translate(x + 3.0F, y + 2.0F);
		g.pose().scale(0.75F, 0.75F);
		g.text(this.font, title, 0, 0, FfxivTheme.TEXT_GOLD, true);
		g.pose().popMatrix();
		return y + 12;
	}

	private int drawStatLine(
		GuiGraphicsExtractor g,
		int x,
		int y,
		int w,
		String leftName,
		int leftVal,
		String rightName,
		int rightVal
	) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.70F, 0.70F);
		g.text(this.font, leftName.substring(0, Math.min(3, leftName.length())).toUpperCase(Locale.ROOT), 0, 0, FfxivTheme.TEXT_CREAM, true);
		g.text(this.font, Integer.toString(leftVal), 42, 0, FfxivTheme.TEXT_GOLD, true);
		g.text(this.font, rightName.substring(0, Math.min(3, rightName.length())).toUpperCase(Locale.ROOT), 90, 0, FfxivTheme.TEXT_CREAM, true);
		g.text(this.font, Integer.toString(rightVal), 132, 0, FfxivTheme.TEXT_GOLD, true);
		g.pose().popMatrix();
		return y + 8;
	}

	private int drawSingleStat(GuiGraphicsExtractor g, int x, int y, int w, String name, int val) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.70F, 0.70F);
		g.text(this.font, name, 0, 0, FfxivTheme.TEXT_SILVER, true);
		String vStr = Integer.toString(val);
		g.text(this.font, vStr, 174 - this.font.width(vStr), 0, FfxivTheme.TEXT_CREAM, true);
		g.pose().popMatrix();
		return y + 8;
	}

	private void drawSmallButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int mx, int my) {
		boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
		g.fill(x, y, x + w, y + h, hover ? 0xFF3A3528 : 0xFF242834);
		g.outline(x, y, w, h, hover ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.GOLD_OUTER);
		g.pose().pushMatrix();
		g.pose().translate(x + 5.0F, y + 4.0F);
		g.pose().scale(0.75F, 0.75F);
		g.text(this.font, label, 0, 0, FfxivTheme.TEXT_CREAM, true);
		g.pose().popMatrix();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();

		// Close 'X' button
		int bx = this.winX + this.winW - 18;
		int by = this.winY + 5;
		if (mouseX >= bx && mouseX <= bx + 12 && mouseY >= by && mouseY <= by + 12) {
			onClose();
			return true;
		}

		// Right Pane Classes click -> Switch Job
		int attrX = this.winX + 8;
		int attrW = 136;
		int midX = attrX + attrW + 6;
		int midW = 132;
		int clsX = midX + midW + 6;
		int clsY = this.winY + 26;
		int clsW = this.winX + this.winW - clsX - 8;
		int clsH = this.winH - 34;

		int rowY = clsY + 18;
		for (int i = 0; i < ClientCombatPredictor.PLAYABLE_JOBS.size(); i++) {
			if (mouseX >= clsX + 4 && mouseX <= clsX + clsW - 4 && mouseY >= rowY && mouseY <= rowY + 30) {
				ClientCombatPredictor.get().requestJobSwitch(ClientCombatPredictor.PLAYABLE_JOBS.get(i));
				return true;
			}
			rowY += 34;
		}

		// Training / Level Sync Buttons
		int btnY = clsY + clsH - 36;
		ClientCombatPredictor predictor = ClientCombatPredictor.get();
		Identifier activeJob = predictor.getActiveJobId();
		int curLv = predictor.getActiveJobLevel();

		if (mouseX >= clsX + 6 && mouseX <= clsX + 58 && mouseY >= btnY && mouseY <= btnY + 14) {
			predictor.sendLevelCommand(activeJob, 500, false);
			return true;
		}
		if (mouseX >= clsX + 62 && mouseX <= clsX + 114 && mouseY >= btnY && mouseY <= btnY + 14) {
			predictor.sendLevelCommand(activeJob, Math.min(JobProgressionState.MAX_LEVEL, curLv + 1), true);
			return true;
		}
		if (mouseX >= clsX + 6 && mouseX <= clsX + 58 && mouseY >= btnY + 17 && mouseY <= btnY + 31) {
			predictor.sendLevelCommand(activeJob, 1, true);
			return true;
		}
		if (mouseX >= clsX + 62 && mouseX <= clsX + 114 && mouseY >= btnY + 17 && mouseY <= btnY + 31) {
			predictor.sendLevelCommand(activeJob, JobProgressionState.MAX_LEVEL, true);
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(null);
			MmoInputHandler.syncCursorState();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
