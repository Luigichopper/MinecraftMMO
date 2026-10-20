package net.xivmmo.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.xivmmo.client.combat.ClientCombatPredictor;
import net.xivmmo.client.hud.FfxivTheme;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.client.input.MmoKeybinds;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.ability.JobDefinition;
import net.xivmmo.data.MmoDataRegistry;
import net.xivmmo.progression.JobProgressionState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FFXIV-inspired Actions & Traits Window ('P').
 * Replicates the FFXIV Actions & Traits window from screenshot #3:
 * - Left category sidebar: ACTIONS | ROLE | TRAITS
 * - Top Job & Level banner: "LEVEL XX VANGUARD"
 * - Two-column list of actions with procedural icons, "Lv. X" unlock badges, MP cost, Potency, and Cast/Recast info
 * - Interactive Hotbar Assignment bar at the bottom: select any action and click a slot (1..= ) to bind it to your hotbar.
 */
public final class FfxivActionsScreen extends Screen {
	private enum CategoryTab {
		JOB_ACTIONS("ACTIONS"),
		ROLE_ACTIONS("ROLE"),
		TRAITS("TRAITS");

		private final String label;

		CategoryTab(String label) {
			this.label = label;
		}
	}

	private record TraitEntry(int unlockLevel, String name, String description) {
	}

	private static final List<TraitEntry> JOB_TRAITS = List.of(
		new TraitEntry(10, "Enhanced Primary Stat I", "Increases primary job attribute and potency scaling by +22%."),
		new TraitEntry(20, "Mastery of Arms I", "Unlocks Tier-2 combo actions and reduces resource strain."),
		new TraitEntry(30, "Soul Crystal Attunement", "Unlocks signature Job actions and increases Armoury mastery."),
		new TraitEntry(40, "Enhanced Primary Stat II", "Increases outgoing damage and healing potency by +97%."),
		new TraitEntry(50, "Aetherial Mastery (Cap)", "Unlocks capstone Level 50 Job actions and +125% output scaling.")
	);

	private CategoryTab activeCategory = CategoryTab.JOB_ACTIONS;
	private Identifier selectedAbilityForAssign = null;

	private int winX;
	private int winY;
	private final int winW = 376;
	private final int winH = 228;

	public FfxivActionsScreen() {
		super(Component.literal("Actions & Traits"));
	}

	@Override
	protected void init() {
		super.init();
		this.winX = (this.width - this.winW) / 2;
		this.winY = Math.max(8, (this.height - this.winH) / 2);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.winX = (this.width - this.winW) / 2;
		this.winY = Math.max(8, (this.height - this.winH) / 2);

		graphics.fill(0, 0, this.width, this.height, 0x7006070B);
		FfxivTheme.drawWindowFrame(graphics, this.font, this.winX, this.winY, this.winW, this.winH, "ACTIONS & TRAITS", true);

		ClientCombatPredictor predictor = ClientCombatPredictor.get();
		Identifier jobId = predictor.getActiveJobId();
		JobDefinition job = MmoDataRegistry.getJob(jobId);
		int jobLv = predictor.getActiveJobLevel();
		String jobName = job != null ? job.displayName().toUpperCase(Locale.ROOT) : "VANGUARD";
		String roleStr = job != null ? job.role().getSerializedName() : "tank";

		// 1. Left Category Sidebar
		int sideX = this.winX + 8;
		int sideY = this.winY + 28;
		int sideW = 68;
		int sideH = this.winH - 68;
		graphics.fill(sideX, sideY, sideX + sideW, sideY + sideH, 0xCC15171F);
		graphics.outline(sideX, sideY, sideW, sideH, FfxivTheme.GOLD_RULE);

		int ty = sideY + 6;
		for (CategoryTab tab : CategoryTab.values()) {
			boolean active = this.activeCategory == tab;
			boolean hover = mouseX >= sideX + 4 && mouseX <= sideX + sideW - 4 && mouseY >= ty && mouseY <= ty + 18;
			graphics.fill(
				sideX + 4,
				ty,
				sideX + sideW - 4,
				ty + 18,
				active ? 0xFF3A3322 : (hover ? 0xFF262A36 : 0xFF1B1E27)
			);
			graphics.outline(sideX + 4, ty, sideW - 8, 18, active ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.SILVER_MUTED);
			graphics.centeredText(
				this.font,
				tab.label,
				sideX + sideW / 2,
				ty + 5,
				active ? FfxivTheme.TEXT_GOLD : FfxivTheme.TEXT_CREAM
			);
			ty += 22;
		}

		// Quick Job switcher inside sidebar
		graphics.text(this.font, "Switch Job:", sideX + 6, sideY + 82, FfxivTheme.TEXT_SILVER, true);
		int jy = sideY + 94;
		for (Identifier j : ClientCombatPredictor.PLAYABLE_JOBS) {
			boolean cur = j.equals(jobId);
			String abbr = FfxivTheme.getJobAbbreviation(j) + " L" + predictor.getJobProgress(j).level();
			graphics.fill(sideX + 6, jy, sideX + sideW - 6, jy + 13, cur ? 0xFF342E1E : 0xFF1A1D24);
			graphics.outline(sideX + 6, jy, sideW - 12, 13, cur ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.SILVER_MUTED);
			graphics.centeredText(this.font, abbr, sideX + sideW / 2, jy + 3, cur ? FfxivTheme.TEXT_GOLD : FfxivTheme.TEXT_SILVER);
			jy += 15;
		}

		// 2. Main Content Pane
		int mainX = sideX + sideW + 6;
		int mainY = sideY;
		int mainW = this.winX + this.winW - mainX - 8;
		int mainH = sideH;
		graphics.fill(mainX, mainY, mainX + mainW, mainY + mainH, 0xCC14161D);
		graphics.outline(mainX, mainY, mainW, mainH, FfxivTheme.GOLD_RULE);

		// Header: Role Emblem + "LEVEL XX VANGUARD"
		FfxivTheme.drawRoleEmblem(graphics, mainX + 6, mainY + 4, roleStr);
		graphics.text(this.font, "LEVEL " + jobLv + "  " + jobName, mainX + 22, mainY + 6, FfxivTheme.TEXT_GOLD, true);
		graphics.fill(mainX + 6, mainY + 18, mainX + mainW - 6, mainY + 19, FfxivTheme.GOLD_RULE);

		if (this.activeCategory == CategoryTab.TRAITS) {
			int trY = mainY + 24;
			for (TraitEntry trait : JOB_TRAITS) {
				boolean unlocked = jobLv >= trait.unlockLevel();
				graphics.fill(mainX + 8, trY, mainX + mainW - 8, trY + 24, unlocked ? 0xFF1F232E : 0xFF15171E);
				graphics.outline(mainX + 8, trY, mainW - 16, 24, unlocked ? FfxivTheme.GOLD_OUTER : 0xFF383C48);
				String badge = "Lv. " + trait.unlockLevel();
				graphics.text(this.font, badge, mainX + 12, trY + 4, unlocked ? FfxivTheme.TEXT_GOLD : 0xFFFF6655, true);
				graphics.text(this.font, trait.name(), mainX + 48, trY + 4, unlocked ? FfxivTheme.TEXT_CREAM : FfxivTheme.TEXT_SILVER, true);
				graphics.pose().pushMatrix();
				graphics.pose().translate(mainX + 12.0F, trY + 15.0F);
				graphics.pose().scale(0.72F, 0.72F);
				graphics.text(this.font, trait.description(), 0, 0, FfxivTheme.TEXT_SILVER, true);
				graphics.pose().popMatrix();
				trY += 27;
			}
		} else {
			List<Identifier> list = getDisplayedActions(job);
			int colW = (mainW - 18) / 2;
			int rowH = 30;
			AbilityDefinition hoveredDef = null;
			int hoveredUnlockLv = 1;

			for (int i = 0; i < list.size(); i++) {
				Identifier abilId = list.get(i);
				AbilityDefinition def = MmoDataRegistry.getAbility(abilId);
				if (def == null) {
					continue;
				}
				int slotIdx = this.activeCategory == CategoryTab.ROLE_ACTIONS ? (8 + i) : i;
				int unlockLv = JobProgressionState.getSlotUnlockLevel(slotIdx);
				boolean unlocked = jobLv >= unlockLv;

				int col = i % 2;
				int row = i / 2;
				int cx = mainX + 6 + col * (colW + 6);
				int cy = mainY + 23 + row * (rowH + 4);

				boolean isSelected = abilId.equals(this.selectedAbilityForAssign);
				boolean isHover = mouseX >= cx && mouseX <= cx + colW && mouseY >= cy && mouseY <= cy + rowH;

				graphics.fill(
					cx,
					cy,
					cx + colW,
					cy + rowH,
					isSelected ? 0xFF38311F : (isHover ? 0xFF252936 : 0xFF1A1C24)
				);
				graphics.outline(
					cx,
					cy,
					colW,
					rowH,
					isSelected ? FfxivTheme.GOLD_BRIGHT : (unlocked ? FfxivTheme.GOLD_OUTER : 0xFF404452)
				);

				FfxivTheme.drawAbilityIcon(graphics, cx + 3, cy + 3, 24, def, unlocked);

				// Action Name + Level requirement
				graphics.pose().pushMatrix();
				graphics.pose().translate(cx + 30.0F, cy + 4.0F);
				graphics.pose().scale(0.80F, 0.80F);
				graphics.text(this.font, def.displayName(), 0, 0, unlocked ? FfxivTheme.TEXT_CREAM : FfxivTheme.TEXT_SILVER, true);
				graphics.pose().popMatrix();

				String lvText = "Lv. " + unlockLv + (def.manaCost() > 0 ? "  MP " + def.manaCost() : "  Pot " + def.potency());
				graphics.pose().pushMatrix();
				graphics.pose().translate(cx + 30.0F, cy + 16.0F);
				graphics.pose().scale(0.72F, 0.72F);
				graphics.text(this.font, lvText, 0, 0, unlocked ? FfxivTheme.TEXT_GOLD : 0xFFFF6655, true);
				graphics.pose().popMatrix();

				if (isHover) {
					hoveredDef = def;
					hoveredUnlockLv = unlockLv;
				}
			}

			if (hoveredDef != null) {
				List<Component> tip = new ArrayList<>();
				tip.add(Component.literal(hoveredDef.displayName() + " (Lv. " + hoveredUnlockLv + ")"));
				tip.add(Component.literal(String.format(
					Locale.ROOT,
					"Category: %s | Potency: %d | Range: %.1fm",
					hoveredDef.category().getSerializedName().toUpperCase(Locale.ROOT),
					hoveredDef.potency(),
					hoveredDef.range()
				)));
				tip.add(Component.literal("L-Click: Select for Hotbar assignment | R-Click: Execute Action"));
				graphics.setComponentTooltipForNextFrame(this.font, tip, mouseX, mouseY);
			}
		}

		// 3. Bottom Hotbar Slot Assignment Strip
		int footY = this.winY + this.winH - 34;
		String assignLabel = this.selectedAbilityForAssign != null
			? "Click a Hotbar Slot (1..=) below to assign [" + this.selectedAbilityForAssign.getPath() + "]:"
			: "Select any Action above, then click a Hotbar Slot (1..=) below to assign:";
		graphics.pose().pushMatrix();
		graphics.pose().translate(this.winX + 10.0F, footY - 2.0F);
		graphics.pose().scale(0.75F, 0.75F);
		graphics.text(this.font, assignLabel, 0, 0, FfxivTheme.TEXT_GOLD, true);
		graphics.pose().popMatrix();

		List<Identifier> currentBar = predictor.getActionBar();
		int slotStartX = this.winX + 14;
		int slotY = footY + 7;
		for (int i = 0; i < 12; i++) {
			int sx = slotStartX + i * 29;
			boolean hover = mouseX >= sx && mouseX <= sx + 26 && mouseY >= slotY && mouseY <= slotY + 20;
			graphics.fill(sx, slotY, sx + 26, slotY + 20, hover ? 0xFF3A3424 : 0xFF1C1F28);
			graphics.outline(sx, slotY, 26, 20, hover ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.GOLD_OUTER);

			String kLabel = MmoKeybinds.FFXIV_HOTBAR_LABELS[i];
			graphics.text(this.font, kLabel, sx + 3, slotY + 2, FfxivTheme.TEXT_GOLD, true);

			if (i < currentBar.size()) {
				String shortCode = currentBar.get(i).getPath().substring(0, Math.min(3, currentBar.get(i).getPath().length())).toUpperCase(Locale.ROOT);
				graphics.pose().pushMatrix();
				graphics.pose().translate(sx + 3.0F, slotY + 12.0F);
				graphics.pose().scale(0.65F, 0.65F);
				graphics.text(this.font, shortCode, 0, 0, FfxivTheme.TEXT_CREAM, true);
				graphics.pose().popMatrix();
			}
		}
	}

	private List<Identifier> getDisplayedActions(JobDefinition job) {
		if (this.activeCategory == CategoryTab.ROLE_ACTIONS) {
			return ClientCombatPredictor.ROLE_ACTIONS;
		}
		if (job != null && !job.abilities().isEmpty()) {
			return job.abilities();
		}
		return ClientCombatPredictor.get().getActionBar().subList(0, 8);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		int button = event.button();

		// Close 'X' button
		int bx = this.winX + this.winW - 18;
		int by = this.winY + 5;
		if (mouseX >= bx && mouseX <= bx + 12 && mouseY >= by && mouseY <= by + 12) {
			onClose();
			return true;
		}

		// Left Sidebar Categories
		int sideX = this.winX + 8;
		int sideY = this.winY + 28;
		int sideW = 68;
		int ty = sideY + 6;
		for (CategoryTab tab : CategoryTab.values()) {
			if (mouseX >= sideX + 4 && mouseX <= sideX + sideW - 4 && mouseY >= ty && mouseY <= ty + 18) {
				this.activeCategory = tab;
				return true;
			}
			ty += 22;
		}

		// Quick Job switcher inside sidebar
		int jy = sideY + 94;
		for (Identifier j : ClientCombatPredictor.PLAYABLE_JOBS) {
			if (mouseX >= sideX + 6 && mouseX <= sideX + sideW - 6 && mouseY >= jy && mouseY <= jy + 13) {
				ClientCombatPredictor.get().requestJobSwitch(j);
				return true;
			}
			jy += 15;
		}

		// Action cards click
		ClientCombatPredictor predictor = ClientCombatPredictor.get();
		JobDefinition job = MmoDataRegistry.getJob(predictor.getActiveJobId());
		if (this.activeCategory != CategoryTab.TRAITS) {
			List<Identifier> list = getDisplayedActions(job);
			int mainX = sideX + sideW + 6;
			int mainY = sideY;
			int mainW = this.winX + this.winW - mainX - 8;
			int colW = (mainW - 18) / 2;
			int rowH = 30;

			for (int i = 0; i < list.size(); i++) {
				int col = i % 2;
				int row = i / 2;
				int cx = mainX + 6 + col * (colW + 6);
				int cy = mainY + 23 + row * (rowH + 4);
				if (mouseX >= cx && mouseX <= cx + colW && mouseY >= cy && mouseY <= cy + rowH) {
					Identifier clickedId = list.get(i);
					if (button == 1) {
						int slotIdx = this.activeCategory == CategoryTab.ROLE_ACTIONS ? (8 + i) : i;
						int unlockLv = JobProgressionState.getSlotUnlockLevel(slotIdx);
						if (predictor.getActiveJobLevel() >= unlockLv) {
							predictor.requestAbility(clickedId);
						} else {
							predictor.showFeedback("Locked until Lv. " + unlockLv, 0xFFFF6655);
						}
					} else {
						this.selectedAbilityForAssign = clickedId;
					}
					return true;
				}
			}
		}

		// Bottom Hotbar Assignment Strip click
		int footY = this.winY + this.winH - 34;
		int slotStartX = this.winX + 14;
		int slotY = footY + 7;
		for (int i = 0; i < 12; i++) {
			int sx = slotStartX + i * 29;
			if (mouseX >= sx && mouseX <= sx + 26 && mouseY >= slotY && mouseY <= slotY + 20) {
				if (this.selectedAbilityForAssign != null) {
					predictor.assignAbilityToHotbarSlot(i, this.selectedAbilityForAssign);
					this.selectedAbilityForAssign = null;
				} else {
					predictor.requestAbilitySlot(i);
				}
				return true;
			}
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
