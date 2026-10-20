package net.xivmmo.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.xivmmo.client.combat.ClientCombatPredictor;
import net.xivmmo.client.hud.FfxivTheme;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.inventory.MmoInventoryState;
import net.xivmmo.network.MmoNetwork;

import java.util.Locale;

/**
 * FFXIV-inspired 140-Slot Inventory Screen ('I' or 'E' in MMO Combat Mode).
 * Features:
 * - Dark charcoal-slate window chrome with gold beveled borders & header
 * - Expanded View (all 4 quadrants of 5x7 = 140 slots at once) and Compact Page 1..4 View (5x7 = 35 slots)
 * - Key Items & Crystals Tab (Job Soul Crystals that switch Jobs on right-click + elemental Aether Shards)
 * - Item category color coding (Weapons/Gear blue, Medicines/Meals green, Materials/Crystals purple, Blocks gold)
 * - Click-to-Select & Swap/Stack across all 140 slots, Right-Click to Equip/Use/Quick-Transfer, and Auto-Sort
 * - Gil currency readout & XX/140 capacity indicator + 1-click switch to Vanilla Inventory
 */
public final class FfxivInventoryScreen extends Screen {
	private enum TabMode {
		EXPANDED_ALL("Expanded (140)"),
		COMPACT_PAGE("Compact (35)"),
		KEY_CRYSTALS("Key Items & Crystals");

		private final String label;

		TabMode(String label) {
			this.label = label;
		}
	}

	private static final int SLOT_SIZE = 18;
	private static final int SLOT_STEP = 19;
	private static final int QUAD_GAP = 8;

	private TabMode activeTab = TabMode.EXPANDED_ALL;
	private int compactPageIndex = 0; // 0..3
	private int selectedSlotIndex = -1;

	private int winX;
	private int winY;
	private int winW;
	private int winH;

	public FfxivInventoryScreen() {
		super(Component.literal("FFXIV Inventory"));
	}

	@Override
	protected void init() {
		super.init();
		recomputeBounds();
		ClientCombatPredictor.get().sendInventoryAction(MmoNetwork.InventoryActionType.REQUEST_SYNC, 0, 0);
	}

	private void recomputeBounds() {
		if (this.activeTab == TabMode.EXPANDED_ALL) {
			// 2x2 quadrants of 5 cols x 7 rows
			this.winW = 24 + (5 * SLOT_STEP) * 2 + QUAD_GAP;
			this.winH = 68 + (7 * SLOT_STEP) * 2 + QUAD_GAP + 26;
		} else {
			// Single 5x7 grid + right info panel
			this.winW = 256;
			this.winH = 68 + (7 * SLOT_STEP) + 34;
		}
		this.winX = (this.width - this.winW) / 2;
		this.winY = Math.max(8, (this.height - this.winH) / 2);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		recomputeBounds();
		graphics.fill(0, 0, this.width, this.height, 0x7006070B);

		FfxivTheme.drawWindowFrame(graphics, this.font, this.winX, this.winY, this.winW, this.winH, "INVENTORY", true);

		// Top Tabs row
		int tabY = this.winY + 26;
		int tabX = this.winX + 8;
		for (TabMode tab : TabMode.values()) {
			int tw = this.font.width(tab.label) + 12;
			boolean active = this.activeTab == tab;
			boolean hovered = mouseX >= tabX && mouseX <= tabX + tw && mouseY >= tabY && mouseY <= tabY + 15;
			graphics.fill(tabX, tabY, tabX + tw, tabY + 15, active ? 0xFF3A3426 : (hovered ? 0xFF2B2F3A : 0xFF1A1C23));
			graphics.outline(tabX, tabY, tw, 15, active ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.SILVER_MUTED);
			graphics.text(this.font, tab.label, tabX + 6, tabY + 4, active ? FfxivTheme.TEXT_GOLD : FfxivTheme.TEXT_SILVER, true);
			tabX += tw + 4;
		}

		// Compact page sub-buttons (1 | 2 | 3 | 4) when in COMPACT_PAGE mode
		if (this.activeTab == TabMode.COMPACT_PAGE) {
			int subY = this.winY + 44;
			int subX = this.winX + 10;
			for (int p = 0; p < 4; p++) {
				int pw = 22;
				boolean active = this.compactPageIndex == p;
				graphics.fill(subX, subY, subX + pw, subY + 13, active ? 0xFF443B24 : 0xFF1A1D24);
				graphics.outline(subX, subY, pw, 13, active ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.SILVER_MUTED);
				graphics.centeredText(this.font, Integer.toString(p + 1), subX + pw / 2, subY + 3, active ? FfxivTheme.TEXT_GOLD : FfxivTheme.TEXT_CREAM);
				subX += pw + 4;
			}
		}

		ClientCombatPredictor predictor = ClientCombatPredictor.get();
		int hoveredSlot = -1;
		ItemStack hoveredStack = ItemStack.EMPTY;

		if (this.activeTab == TabMode.EXPANDED_ALL) {
			int gridStartX = this.winX + 12;
			int gridStartY = this.winY + 46;
			int quadW = 5 * SLOT_STEP;
			int quadH = 7 * SLOT_STEP;

			for (int q = 0; q < 4; q++) {
				int qCol = q % 2;
				int qRow = q / 2;
				int qx = gridStartX + qCol * (quadW + QUAD_GAP);
				int qy = gridStartY + qRow * (quadH + QUAD_GAP);

				// Subtle quadrant frame
				graphics.outline(qx - 2, qy - 2, quadW + 3, quadH + 3, 0xFF343844);

				for (int r = 0; r < 7; r++) {
					for (int c = 0; c < 5; c++) {
						int slotIdx = q * 35 + r * 5 + c;
						int sx = qx + c * SLOT_STEP;
						int sy = qy + r * SLOT_STEP;

						ItemStack stack = predictor.getClientInventorySlot(slotIdx);
						boolean isHover = mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE;
						boolean isSelected = this.selectedSlotIndex == slotIdx;
						int catColor = stack.isEmpty() ? 0 : MmoInventoryState.classifyItem(stack).borderColor();

						FfxivTheme.drawRecessedSlot(graphics, sx, sy, SLOT_SIZE, isHover, isSelected, catColor);
						if (!stack.isEmpty()) {
							graphics.item(stack, sx + 1, sy + 1);
							graphics.itemDecorations(this.font, stack, sx + 1, sy + 1);
						}
						if (isHover) {
							hoveredSlot = slotIdx;
							hoveredStack = stack;
						}
					}
				}
			}
		} else {
			int baseSlot = this.activeTab == TabMode.KEY_CRYSTALS
				? MmoInventoryState.TOTAL_MAIN_SLOTS
				: this.compactPageIndex * 35;
			int qx = this.winX + 12;
			int qy = this.winY + 60;

			for (int r = 0; r < 7; r++) {
				for (int c = 0; c < 5; c++) {
					int slotIdx = baseSlot + r * 5 + c;
					int sx = qx + c * SLOT_STEP;
					int sy = qy + r * SLOT_STEP;

					ItemStack stack = predictor.getClientInventorySlot(slotIdx);
					boolean isHover = mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE;
					boolean isSelected = this.selectedSlotIndex == slotIdx;
					int catColor = stack.isEmpty() ? 0 : MmoInventoryState.classifyItem(stack).borderColor();

					FfxivTheme.drawRecessedSlot(graphics, sx, sy, SLOT_SIZE, isHover, isSelected, catColor);
					if (!stack.isEmpty()) {
						graphics.item(stack, sx + 1, sy + 1);
						graphics.itemDecorations(this.font, stack, sx + 1, sy + 1);
					}
					if (isHover) {
						hoveredSlot = slotIdx;
						hoveredStack = stack;
					}
				}
			}

			// Right side FFXIV Help & Soul Crystal Legend Panel
			int infoX = qx + 5 * SLOT_STEP + 10;
			int infoY = qy;
			int infoW = this.winW - (infoX - this.winX) - 10;
			int infoH = 7 * SLOT_STEP - 1;
			graphics.fill(infoX, infoY, infoX + infoW, infoY + infoH, 0xCC15171E);
			graphics.outline(infoX, infoY, infoW, infoH, FfxivTheme.GOLD_RULE);

			if (this.activeTab == TabMode.KEY_CRYSTALS) {
				graphics.text(this.font, "Soul Crystals", infoX + 6, infoY + 6, FfxivTheme.TEXT_GOLD, true);
				graphics.text(this.font, "Right-click a", infoX + 6, infoY + 20, FfxivTheme.TEXT_CREAM, true);
				graphics.text(this.font, "Job Soul Crystal", infoX + 6, infoY + 30, FfxivTheme.TEXT_CREAM, true);
				graphics.text(this.font, "to attune and", infoX + 6, infoY + 40, FfxivTheme.TEXT_CREAM, true);
				graphics.text(this.font, "switch your", infoX + 6, infoY + 50, FfxivTheme.TEXT_CREAM, true);
				graphics.text(this.font, "active Job!", infoX + 6, infoY + 60, FfxivTheme.TEXT_GOLD, true);
				graphics.text(this.font, "Aether Shards", infoX + 6, infoY + 80, 0xFFCC77FF, true);
				graphics.text(this.font, "Elemental", infoX + 6, infoY + 92, FfxivTheme.TEXT_SILVER, true);
				graphics.text(this.font, "synthesis", infoX + 6, infoY + 102, FfxivTheme.TEXT_SILVER, true);
				graphics.text(this.font, "crystals.", infoX + 6, infoY + 112, FfxivTheme.TEXT_SILVER, true);
			} else {
				graphics.text(this.font, "Bag Page " + (this.compactPageIndex + 1) + "/4", infoX + 6, infoY + 6, FfxivTheme.TEXT_GOLD, true);
				graphics.text(this.font, "L-Click:", infoX + 6, infoY + 22, FfxivTheme.TEXT_GOLD, true);
				graphics.text(this.font, " Move / Merge", infoX + 6, infoY + 32, FfxivTheme.TEXT_CREAM, true);
				graphics.text(this.font, "R-Click:", infoX + 6, infoY + 48, FfxivTheme.TEXT_GOLD, true);
				graphics.text(this.font, " Equip or Send", infoX + 6, infoY + 58, FfxivTheme.TEXT_CREAM, true);
				graphics.text(this.font, " to Page 2-4", infoX + 6, infoY + 68, FfxivTheme.TEXT_CREAM, true);
				graphics.text(this.font, "Color Key:", infoX + 6, infoY + 86, FfxivTheme.TEXT_SILVER, true);
				graphics.text(this.font, "■ Gear/Arms", infoX + 6, infoY + 97, 0xFF55AAFF, true);
				graphics.text(this.font, "■ Meals/Meds", infoX + 6, infoY + 107, 0xFF55DD77, true);
				graphics.text(this.font, "■ Materials", infoX + 6, infoY + 117, 0xFFCC77FF, true);
			}
		}

		// Footer: Gil Currency + Sort + Vanilla Bag + XX/140 Slot Counter
		int footY = this.winY + this.winH - 22;
		graphics.fill(this.winX + 6, footY - 3, this.winX + this.winW - 6, footY - 2, FfxivTheme.GOLD_RULE);

		// Gil Coin + Amount
		graphics.fill(this.winX + 10, footY + 2, this.winX + 18, footY + 10, FfxivTheme.GOLD_BRIGHT);
		graphics.outline(this.winX + 10, footY + 2, 8, 8, FfxivTheme.GOLD_OUTER);
		String gilStr = String.format(Locale.ROOT, "%,d Gil", predictor.getClientGil());
		graphics.text(this.font, gilStr, this.winX + 22, footY + 2, FfxivTheme.TEXT_GOLD, true);

		// Sort Button
		int sortX = this.winX + 94;
		int sortW = 36;
		graphics.fill(sortX, footY, sortX + sortW, footY + 14, 0xFF282C38);
		graphics.outline(sortX, footY, sortW, 14, FfxivTheme.GOLD_RULE);
		graphics.centeredText(this.font, "Sort", sortX + sortW / 2, footY + 3, FfxivTheme.TEXT_CREAM);

		// Vanilla Bag Button
		int vanX = sortX + sortW + 5;
		int vanW = 58;
		graphics.fill(vanX, footY, vanX + vanW, footY + 14, 0xFF282C38);
		graphics.outline(vanX, footY, vanW, 14, FfxivTheme.SILVER_MUTED);
		graphics.centeredText(this.font, "Vanilla Bag", vanX + vanW / 2, footY + 3, FfxivTheme.TEXT_SILVER);

		// Slot Capacity (XX/140)
		String capStr = predictor.getUsedMainInventoryCount() + "/" + MmoInventoryState.TOTAL_MAIN_SLOTS;
		graphics.text(
			this.font,
			capStr,
			this.winX + this.winW - this.font.width(capStr) - 10,
			footY + 3,
			FfxivTheme.TEXT_CREAM,
			true
		);

		if (!hoveredStack.isEmpty() && hoveredSlot >= 0) {
			graphics.setTooltipForNextFrame(this.font, hoveredStack, mouseX, mouseY);
		}
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

		// Top Tabs
		int tabY = this.winY + 26;
		int tabX = this.winX + 8;
		for (TabMode tab : TabMode.values()) {
			int tw = this.font.width(tab.label) + 12;
			if (mouseX >= tabX && mouseX <= tabX + tw && mouseY >= tabY && mouseY <= tabY + 15) {
				this.activeTab = tab;
				this.selectedSlotIndex = -1;
				return true;
			}
			tabX += tw + 4;
		}

		// Compact Page sub-buttons
		if (this.activeTab == TabMode.COMPACT_PAGE) {
			int subY = this.winY + 44;
			int subX = this.winX + 10;
			for (int p = 0; p < 4; p++) {
				int pw = 22;
				if (mouseX >= subX && mouseX <= subX + pw && mouseY >= subY && mouseY <= subY + 13) {
					this.compactPageIndex = p;
					return true;
				}
				subX += pw + 4;
			}
		}

		// Footer buttons (Sort & Vanilla Bag)
		int footY = this.winY + this.winH - 22;
		int sortX = this.winX + 94;
		int sortW = 36;
		if (mouseX >= sortX && mouseX <= sortX + sortW && mouseY >= footY && mouseY <= footY + 14) {
			ClientCombatPredictor.get().sendInventoryAction(MmoNetwork.InventoryActionType.SORT_INVENTORY, 0, 0);
			this.selectedSlotIndex = -1;
			return true;
		}

		int vanX = sortX + sortW + 5;
		int vanW = 58;
		if (mouseX >= vanX && mouseX <= vanX + vanW && mouseY >= footY && mouseY <= footY + 14) {
			if (this.minecraft != null && this.minecraft.player != null) {
				this.minecraft.gui.setScreen(new InventoryScreen(this.minecraft.player));
			}
			return true;
		}

		// Slot clicks
		int clickedSlot = resolveSlotAt(mouseX, mouseY);
		if (clickedSlot >= 0) {
			if (button == 1) {
				// Right-click: Use/Equip Soul Crystal or Armor, or Quick-Transfer between Page 1 and Pages 2-4
				ClientCombatPredictor.get().sendInventoryAction(
					MmoNetwork.InventoryActionType.RIGHT_CLICK_SLOT,
					clickedSlot,
					0
				);
				this.selectedSlotIndex = -1;
				return true;
			} else if (button == 0) {
				// Left-click: Select or Swap/Merge slots (only within main 0..139 slots)
				if (clickedSlot >= MmoInventoryState.TOTAL_MAIN_SLOTS) {
					// Clicking a Soul Crystal with LMB also attunes/equips it for convenience
					ClientCombatPredictor.get().sendInventoryAction(
						MmoNetwork.InventoryActionType.RIGHT_CLICK_SLOT,
						clickedSlot,
						0
					);
					return true;
				}
				if (this.selectedSlotIndex < 0) {
					if (!ClientCombatPredictor.get().getClientInventorySlot(clickedSlot).isEmpty()) {
						this.selectedSlotIndex = clickedSlot;
					}
				} else if (this.selectedSlotIndex == clickedSlot) {
					this.selectedSlotIndex = -1;
				} else {
					ClientCombatPredictor.get().sendInventoryAction(
						MmoNetwork.InventoryActionType.SWAP_SLOTS,
						this.selectedSlotIndex,
						clickedSlot
					);
					this.selectedSlotIndex = -1;
				}
				return true;
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	private int resolveSlotAt(double mouseX, double mouseY) {
		if (this.activeTab == TabMode.EXPANDED_ALL) {
			int gridStartX = this.winX + 12;
			int gridStartY = this.winY + 46;
			int quadW = 5 * SLOT_STEP;
			int quadH = 7 * SLOT_STEP;

			for (int q = 0; q < 4; q++) {
				int qCol = q % 2;
				int qRow = q / 2;
				int qx = gridStartX + qCol * (quadW + QUAD_GAP);
				int qy = gridStartY + qRow * (quadH + QUAD_GAP);
				for (int r = 0; r < 7; r++) {
					for (int c = 0; c < 5; c++) {
						int sx = qx + c * SLOT_STEP;
						int sy = qy + r * SLOT_STEP;
						if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
							return q * 35 + r * 5 + c;
						}
					}
				}
			}
			return -1;
		} else {
			int baseSlot = this.activeTab == TabMode.KEY_CRYSTALS
				? MmoInventoryState.TOTAL_MAIN_SLOTS
				: this.compactPageIndex * 35;
			int qx = this.winX + 12;
			int qy = this.winY + 60;
			for (int r = 0; r < 7; r++) {
				for (int c = 0; c < 5; c++) {
					int sx = qx + c * SLOT_STEP;
					int sy = qy + r * SLOT_STEP;
					if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
						return baseSlot + r * 5 + c;
					}
				}
			}
			return -1;
		}
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
