package net.xivmmo.client.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.xivmmo.client.compat.YaclConfigScreenFactory;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.config.MmoConfig;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Interactive FFXIV-inspired HUD Layout Editor Screen.
 * Allows the player to:
 * - Left-Click + Drag any HUD element to rearrange its screen position (with center-snap guides)
 * - Scroll the Mouse Wheel over any HUD element to scale it (50% - 200%)
 * - Right-Click any HUD element to toggle it Visible / Hidden (including hiding the MMO Action Hotbar)
 * - Toggle Vanilla Minecraft Hotbar removal and reset to defaults
 */
public final class HudLayoutScreen extends Screen {
	private final @Nullable Screen parent;
	private MmoConfig.@Nullable HudWidgetId selectedWidget = MmoConfig.HudWidgetId.ACTION_HOTBAR;
	private MmoConfig.@Nullable HudWidgetId draggingWidget = null;
	private double dragOffsetX = 0.0;
	private double dragOffsetY = 0.0;

	public HudLayoutScreen(@Nullable Screen parent) {
		super(Component.literal("XIV MMO — Interactive HUD Layout Editor"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		rebuildToolbarButtons();
	}

	private void rebuildToolbarButtons() {
		this.clearWidgets();
		MmoConfig cfg = MmoConfig.get();

		int btnY = 6;
		int totalW = 516;
		int startX = Math.max(4, (this.width - totalW) / 2);

		boolean vanillaDisabled = cfg.hideVanillaHotbarInMmoMode && cfg.disableVanillaHotbarMechanicsInMmoMode;
		String vanillaBarLabel = vanillaDisabled ? "Vanilla Bar: OFF" : "Vanilla Bar: ON";
		this.addRenderableWidget(Button.builder(Component.literal(vanillaBarLabel), btn -> {
			boolean nextOff = !vanillaDisabled;
			cfg.hideVanillaHotbarInMmoMode = nextOff;
			cfg.disableVanillaHotbarMechanicsInMmoMode = nextOff;
			cfg.save();
			rebuildToolbarButtons();
		}).bounds(startX, btnY, 112, 18).build());

		boolean mmoBarShown = cfg.showMmoActionHotbar && cfg.getWidgetLayout(MmoConfig.HudWidgetId.ACTION_HOTBAR).enabled;
		String mmoBarLabel = mmoBarShown ? "MMO Hotbar: SHOWN" : "MMO Hotbar: HIDDEN";
		this.addRenderableWidget(Button.builder(Component.literal(mmoBarLabel), btn -> {
			boolean next = !mmoBarShown;
			cfg.showMmoActionHotbar = next;
			cfg.getWidgetLayout(MmoConfig.HudWidgetId.ACTION_HOTBAR).enabled = next;
			cfg.save();
			rebuildToolbarButtons();
		}).bounds(startX + 116, btnY, 112, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal("Reset Layout"), btn -> {
			cfg.resetHudLayoutToDefaults();
			rebuildToolbarButtons();
		}).bounds(startX + 232, btnY, 86, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal("All Settings..."), btn -> {
			if (this.minecraft != null) {
				this.minecraft.gui.setScreen(YaclConfigScreenFactory.createConfigScreen(this));
			}
		}).bounds(startX + 322, btnY, 96, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal("Save & Close"), btn -> onClose())
			.bounds(startX + 422, btnY, 90, 18).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0x78080C14);

		// Alignment grid & center snap guides
		int centerX = this.width / 2;
		int centerY = this.height / 2;
		graphics.verticalLine(centerX, 0, this.height, 0x454488CC);
		graphics.horizontalLine(0, this.width, centerY, 0x354488CC);

		// Render each rearrangeable HUD widget box
		MmoConfig cfg = MmoConfig.get();
		for (MmoConfig.HudWidgetId id : MmoConfig.HudWidgetId.values()) {
			MmoConfig.WidgetLayout layout = cfg.getWidgetLayout(id);
			MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(id, this.width, this.height);

			boolean isSelected = id == this.selectedWidget;
			boolean isHovered = rect.contains(mouseX, mouseY);
			boolean visible = rect.enabled();

			int bgColor = visible
				? (isSelected ? 0xB8182C48 : (isHovered ? 0xA816243A : 0x90101826))
				: 0x80281216;
			int borderColor = visible
				? (isSelected ? 0xFF55EEFF : (isHovered ? 0xFF88CCFF : 0xFF4477AA))
				: 0xFFCC4444;

			graphics.fill(rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height(), bgColor);
			graphics.outline(rect.x(), rect.y(), rect.width(), rect.height(), borderColor);

			String title = id.displayName();
			String info = String.format(
				Locale.ROOT,
				"%.0f%% | %s",
				layout.scale * 100.0F,
				visible ? "ENABLED" : "HIDDEN (Right-Click)"
			);

			int textCenterX = rect.x() + rect.width() / 2;
			int textY = rect.y() + Math.max(2, (rect.height() - 18) / 2);
			graphics.centeredText(this.font, title, textCenterX, textY, 0xFFFFFFFF);
			graphics.centeredText(
				this.font,
				info,
				textCenterX,
				textY + 10,
				visible ? 0xFF88FFAA : 0xFFFF7777
			);
		}

		// Help instructions banner below top toolbar
		String instructions = "LMB + Drag: Move Widget  |  Scroll Wheel: Scale (50%-200%)  |  Right-Click Widget: Hide/Show";
		graphics.centeredText(this.font, instructions, this.width / 2, 28, 0xFFD0E4FF);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		double mx = event.x();
		double my = event.y();
		int button = event.button();

		// Iterate in reverse so top-most widget is picked first
		MmoConfig.HudWidgetId[] values = MmoConfig.HudWidgetId.values();
		for (int i = values.length - 1; i >= 0; i--) {
			MmoConfig.HudWidgetId id = values[i];
			MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(id, this.width, this.height);
			if (rect.contains(mx, my)) {
				this.selectedWidget = id;
				if (button == 0) {
					this.draggingWidget = id;
					int centerX = rect.x() + rect.width() / 2;
					this.dragOffsetX = mx - centerX;
					this.dragOffsetY = my - rect.y();
					return true;
				} else if (button == 1) {
					MmoConfig cfg = MmoConfig.get();
					MmoConfig.WidgetLayout layout = cfg.getWidgetLayout(id);
					layout.enabled = !layout.enabled;
					if (id == MmoConfig.HudWidgetId.ACTION_HOTBAR) {
						cfg.showMmoActionHotbar = layout.enabled;
					}
					cfg.save();
					rebuildToolbarButtons();
					return true;
				}
			}
		}

		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.draggingWidget != null && event.button() == 0) {
			MmoConfig cfg = MmoConfig.get();
			MmoConfig.WidgetLayout layout = cfg.getWidgetLayout(this.draggingWidget);

			double targetCenterX = event.x() - this.dragOffsetX;
			double targetTopY = event.y() - this.dragOffsetY;

			// Snap to horizontal screen center if within 5 pixels
			if (Math.abs(targetCenterX - (this.width * 0.5)) <= 5.0) {
				targetCenterX = this.width * 0.5;
			}

			layout.normX = Mth.clamp((float) (targetCenterX / Math.max(1, this.width)), 0.03F, 0.97F);
			layout.normY = Mth.clamp((float) (targetTopY / Math.max(1, this.height)), 0.02F, 0.95F);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.draggingWidget != null && event.button() == 0) {
			this.draggingWidget = null;
			MmoConfig.get().save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (Math.abs(scrollY) > 1.0E-4) {
			MmoConfig.HudWidgetId targetId = null;
			for (MmoConfig.HudWidgetId id : MmoConfig.HudWidgetId.values()) {
				MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(id, this.width, this.height);
				if (rect.contains(mouseX, mouseY)) {
					targetId = id;
					break;
				}
			}
			if (targetId == null) {
				targetId = this.selectedWidget;
			}
			if (targetId != null) {
				this.selectedWidget = targetId;
				MmoConfig cfg = MmoConfig.get();
				MmoConfig.WidgetLayout layout = cfg.getWidgetLayout(targetId);
				float delta = scrollY > 0 ? 0.05F : -0.05F;
				layout.scale = Mth.clamp(Math.round((layout.scale + delta) * 20.0F) / 20.0F, 0.50F, 2.00F);
				cfg.save();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		MmoConfig.get().save();
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(this.parent);
			MmoInputHandler.syncCursorState();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
