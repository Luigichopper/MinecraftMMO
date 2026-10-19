package net.xivmmo.client.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.config.MmoConfig;

/**
 * Coordinates vanilla HUD element replacement (hiding/removing the vanilla item hotbar and crosshair
 * in MMO mode unless Build Mode is active, and repositioning/scaling the vanilla Health, Hunger, Armor,
 * Air, and Mount Health bars via {@link MmoConfig.HudWidgetId#PLAYER_VITALS}) and provides normalized
 * anchor/scale layout helpers for all rearrangeable MMO HUD widgets.
 */
public final class MmoHudManager {
	public record WidgetRect(int x, int y, int width, int height, float scale, boolean enabled) {
		public boolean contains(double px, double py) {
			return px >= this.x && px <= this.x + this.width && py >= this.y && py <= this.y + this.height;
		}
	}

	private MmoHudManager() {
	}

	/**
	 * Registers vanilla HUD element replacements via Fabric's official {@link HudElementRegistry}.
	 */
	public static void registerVanillaHudOverrides() {
		// 1. Suppress vanilla 9-slot item hotbar, XP bar, and held item tooltip in MMO mode (unless Build Mode / Alt is active)
		HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, original -> (graphics, deltaTracker) -> {
			if (!shouldSuppressVanillaHotbar()) {
				original.extractRenderState(graphics, deltaTracker);
			}
		});

		HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, original -> (graphics, deltaTracker) -> {
			if (!shouldSuppressVanillaHotbar()) {
				original.extractRenderState(graphics, deltaTracker);
			}
		});

		HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, original -> (graphics, deltaTracker) -> {
			if (!shouldSuppressVanillaHotbar()) {
				original.extractRenderState(graphics, deltaTracker);
			}
		});

		HudElementRegistry.replaceElement(VanillaHudElements.HELD_ITEM_TOOLTIP, original -> (graphics, deltaTracker) -> {
			if (!shouldSuppressVanillaHotbar()) {
				original.extractRenderState(graphics, deltaTracker);
			}
		});

		// 2. Suppress vanilla center crosshair in Free-Cursor MMO mode
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, deltaTracker) -> {
			if (!shouldSuppressVanillaCrosshair()) {
				original.extractRenderState(graphics, deltaTracker);
			}
		});

		// 3. Reposition & scale vanilla Health, Hunger, Armor, Air, and Mount Health bars to PLAYER_VITALS widget
		HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, MmoHudManager::wrapVitalsElement);
		HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR, MmoHudManager::wrapVitalsElement);
		HudElementRegistry.replaceElement(VanillaHudElements.ARMOR_BAR, MmoHudManager::wrapVitalsElement);
		HudElementRegistry.replaceElement(VanillaHudElements.AIR_BAR, MmoHudManager::wrapVitalsElement);
		HudElementRegistry.replaceElement(VanillaHudElements.MOUNT_HEALTH, MmoHudManager::wrapVitalsElement);
	}

	private static HudElement wrapVitalsElement(HudElement original) {
		return (graphics, deltaTracker) -> {
			if (!MmoInputHandler.isMmoModeActive()) {
				original.extractRenderState(graphics, deltaTracker);
				return;
			}
			int sw = graphics.guiWidth();
			int sh = graphics.guiHeight();
			WidgetRect rect = getWidgetRect(MmoConfig.HudWidgetId.PLAYER_VITALS, sw, sh);
			if (!rect.enabled()) {
				return;
			}
			// In vanilla Hud.extractPlayerHealth, the 182x20 vitals block is anchored at:
			// leftX = sw / 2 - 91, topY (armor/air row) = sh - 49, bottom row (health/hunger) = sh - 39
			int vanillaOriginX = sw / 2 - 91;
			int vanillaOriginY = sh - 49;

			graphics.pose().pushMatrix();
			graphics.pose().translate(rect.x(), rect.y());
			if (Math.abs(rect.scale() - 1.0F) > 1.0E-3F) {
				graphics.pose().scale(rect.scale(), rect.scale());
			}
			graphics.pose().translate(-vanillaOriginX, -vanillaOriginY);
			original.extractRenderState(graphics, deltaTracker);
			graphics.pose().popMatrix();
		};
	}

	public static boolean shouldSuppressVanillaHotbar() {
		return MmoInputHandler.isMmoModeActive()
			&& MmoConfig.get().hideVanillaHotbarInMmoMode
			&& !MmoInputHandler.isBlockInteractionAllowed();
	}

	public static boolean shouldDisableVanillaHotbarMechanics() {
		return MmoInputHandler.isMmoModeActive()
			&& MmoConfig.get().disableVanillaHotbarMechanicsInMmoMode
			&& !MmoInputHandler.isBlockInteractionAllowed();
	}

	public static boolean shouldSuppressVanillaCrosshair() {
		return MmoInputHandler.isFreeCursorMode()
			&& MmoConfig.get().hideVanillaCrosshairInMmoMode
			&& !MmoInputHandler.isBlockInteractionAllowed();
	}

	/**
	 * Computes the screen-space bounding rectangle of a HUD widget given the current GUI-scaled screen dimensions.
	 */
	public static WidgetRect getWidgetRect(MmoConfig.HudWidgetId id, int screenWidth, int screenHeight) {
		MmoConfig cfg = MmoConfig.get();
		MmoConfig.WidgetLayout layout = cfg.getWidgetLayout(id);
		boolean enabled = layout.enabled && (id != MmoConfig.HudWidgetId.ACTION_HOTBAR || cfg.showMmoActionHotbar);
		float scale = Mth.clamp(layout.scale, 0.50F, 2.00F);

		int scaledW = Math.round(id.baseWidth() * scale);
		int scaledH = Math.round(id.baseHeight() * scale);

		int centerX = Math.round(layout.normX * screenWidth);
		int x = Mth.clamp(centerX - scaledW / 2, 2, Math.max(2, screenWidth - scaledW - 2));
		int y = Mth.clamp(Math.round(layout.normY * screenHeight), 2, Math.max(2, screenHeight - scaledH - 2));

		// Ensure PLAYER_VITALS never overlaps ACTION_HOTBAR on small GUI heights when stacked above it
		if (id == MmoConfig.HudWidgetId.PLAYER_VITALS) {
			MmoConfig.WidgetLayout hotbarLayout = cfg.getWidgetLayout(MmoConfig.HudWidgetId.ACTION_HOTBAR);
			boolean hotbarEnabled = hotbarLayout.enabled && cfg.showMmoActionHotbar;
			if (hotbarEnabled && layout.normY <= hotbarLayout.normY) {
				WidgetRect hotbarRect = getWidgetRect(MmoConfig.HudWidgetId.ACTION_HOTBAR, screenWidth, screenHeight);
				boolean horizOverlap = x < hotbarRect.x() + hotbarRect.width() && x + scaledW > hotbarRect.x();
				if (horizOverlap && y + scaledH > hotbarRect.y() - 4) {
					y = Math.max(2, hotbarRect.y() - scaledH - 4);
				}
			}
		} else if (id == MmoConfig.HudWidgetId.CAST_BAR) {
			MmoConfig.WidgetLayout vitalsLayout = cfg.getWidgetLayout(MmoConfig.HudWidgetId.PLAYER_VITALS);
			if (vitalsLayout.enabled && layout.normY <= vitalsLayout.normY) {
				WidgetRect vitalsRect = getWidgetRect(MmoConfig.HudWidgetId.PLAYER_VITALS, screenWidth, screenHeight);
				boolean horizOverlap = x < vitalsRect.x() + vitalsRect.width() && x + scaledW > vitalsRect.x();
				if (horizOverlap && y + scaledH > vitalsRect.y() - 4) {
					y = Math.max(2, vitalsRect.y() - scaledH - 4);
				}
			}
		}

		return new WidgetRect(x, y, scaledW, scaledH, scale, enabled);
	}

	/**
	 * Pushes a 2D translation + scale matrix onto {@link GuiGraphicsExtractor#pose()} so widget drawing code
	 * can draw in local {@code [0..baseWidth, 0..baseHeight]} coordinates.
	 */
	public static void pushWidgetTransform(GuiGraphicsExtractor graphics, WidgetRect rect) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(rect.x(), rect.y());
		if (Math.abs(rect.scale() - 1.0F) > 1.0E-3F) {
			graphics.pose().scale(rect.scale(), rect.scale());
		}
	}

	public static void popWidgetTransform(GuiGraphicsExtractor graphics) {
		graphics.pose().popMatrix();
	}
}
