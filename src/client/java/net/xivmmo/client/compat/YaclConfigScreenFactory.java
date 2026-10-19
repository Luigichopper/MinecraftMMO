package net.xivmmo.client.compat;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.xivmmo.client.hud.HudLayoutScreen;
import net.xivmmo.config.MmoConfig;
import org.jspecify.annotations.Nullable;

/**
 * Integrates YetAnotherConfigLib (YACL 3.9.7+26.2-fabric) when present, with a direct launcher button
 * for the interactive drag-and-drop {@link HudLayoutScreen} and clean fallback when YACL is absent.
 */
public final class YaclConfigScreenFactory {
	private YaclConfigScreenFactory() {
	}

	public static Screen createConfigScreen(@Nullable Screen parent) {
		if (FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
			try {
				return YaclImpl.build(parent);
			} catch (Throwable t) {
				// Fallback to interactive HUD layout screen if YACL API changes
			}
		}
		return new HudLayoutScreen(parent);
	}

	private static final class YaclImpl {
		static Screen build(@Nullable Screen parent) {
			MmoConfig cfg = MmoConfig.get();

			ConfigCategory.Builder hudCat = ConfigCategory.createBuilder()
				.name(Component.literal("HUD & Hotbars"))
				.option(ButtonOption.createBuilder()
					.name(Component.literal("Interactive HUD Layout Editor (Drag & Drop / Scale)"))
					.text(Component.literal("Open HUD Editor"))
					.description(OptionDescription.of(Component.literal(
						"Rearrange, scale (50%-200%), or hide any HUD element with your mouse."
					)))
					.action((yaclScreen, btn) -> Minecraft.getInstance().gui.setScreen(new HudLayoutScreen(yaclScreen)))
					.build())
				.option(Option.<Boolean>createBuilder()
					.name(Component.literal("Remove Vanilla Item Hotbar UI in MMO Mode"))
					.description(OptionDescription.of(Component.literal(
						"Hides the vanilla 9-slot item hotbar and XP bar during MMO combat. Automatically reappears in Build Mode (B) or while holding Alt."
					)))
					.binding(true, () -> cfg.hideVanillaHotbarInMmoMode, val -> cfg.hideVanillaHotbarInMmoMode = val)
					.controller(TickBoxControllerBuilder::create)
					.build())
				.option(Option.<Boolean>createBuilder()
					.name(Component.literal("Disable Vanilla Hotbar Mechanics in MMO Mode"))
					.description(OptionDescription.of(Component.literal(
						"Completely disables vanilla hotbar slot switching (1-9, scroll), item dropping (Q), offhand swapping (F), and held-item swings/uses during MMO combat. Re-enabled in Build Mode (B) or while holding Alt."
					)))
					.binding(true, () -> cfg.disableVanillaHotbarMechanicsInMmoMode, val -> cfg.disableVanillaHotbarMechanicsInMmoMode = val)
					.controller(TickBoxControllerBuilder::create)
					.build())
				.option(Option.<Boolean>createBuilder()
					.name(Component.literal("Remove Vanilla Crosshair in MMO Mode"))
					.description(OptionDescription.of(Component.literal(
						"Hides the center crosshair while Free-Cursor MMO mode is active."
					)))
					.binding(true, () -> cfg.hideVanillaCrosshairInMmoMode, val -> cfg.hideVanillaCrosshairInMmoMode = val)
					.controller(TickBoxControllerBuilder::create)
					.build())
				.option(Option.<Boolean>createBuilder()
					.name(Component.literal("Show MMO Action Hotbar"))
					.description(OptionDescription.of(Component.literal(
						"Master toggle for the 8-slot MMO Action Hotbar (can also be toggled in-game with F9 or right-clicked in the HUD Layout Editor)."
					)))
					.binding(true, () -> cfg.showMmoActionHotbar, val -> {
						cfg.showMmoActionHotbar = val;
						cfg.getWidgetLayout(MmoConfig.HudWidgetId.ACTION_HOTBAR).enabled = val;
					})
					.controller(TickBoxControllerBuilder::create)
					.build());

			for (MmoConfig.HudWidgetId id : MmoConfig.HudWidgetId.values()) {
				MmoConfig.WidgetLayout layout = cfg.getWidgetLayout(id);
				hudCat.option(Option.<Boolean>createBuilder()
					.name(Component.literal(id.displayName() + " — Visible"))
					.binding(true, () -> layout.enabled, val -> layout.enabled = val)
					.controller(TickBoxControllerBuilder::create)
					.build());
				hudCat.option(Option.<Float>createBuilder()
					.name(Component.literal(id.displayName() + " — Scale"))
					.binding(1.0F, () -> layout.scale, val -> layout.scale = val)
					.controller(opt -> FloatSliderControllerBuilder.create(opt).range(0.50F, 2.00F).step(0.05F))
					.build());
			}

			ConfigCategory cameraCat = ConfigCategory.createBuilder()
				.name(Component.literal("Camera & Controls"))
				.option(Option.<Boolean>createBuilder()
					.name(Component.literal("Enable MMO Mode by Default"))
					.binding(true, () -> cfg.mmoModeEnabled, val -> cfg.mmoModeEnabled = val)
					.controller(TickBoxControllerBuilder::create)
					.build())
				.option(Option.<Boolean>createBuilder()
					.name(Component.literal("Auto-Face Movement Direction (Legacy Camera Feel)"))
					.binding(true, () -> cfg.autoFaceMovementDirection, val -> cfg.autoFaceMovementDirection = val)
					.controller(TickBoxControllerBuilder::create)
					.build())
				.option(Option.<Float>createBuilder()
					.name(Component.literal("Default Camera Zoom Distance"))
					.binding(6.0F, () -> cfg.defaultZoomDistance, val -> cfg.defaultZoomDistance = val)
					.controller(opt -> FloatSliderControllerBuilder.create(opt).range(1.5F, 16.0F).step(0.5F))
					.build())
				.option(Option.<Float>createBuilder()
					.name(Component.literal("Orbit Sensitivity"))
					.binding(1.0F, () -> cfg.orbitSensitivity, val -> cfg.orbitSensitivity = val)
					.controller(opt -> FloatSliderControllerBuilder.create(opt).range(0.2F, 3.0F).step(0.1F))
					.build())
				.option(Option.<Float>createBuilder()
					.name(Component.literal("Vanilla Armor Mitigation Scale"))
					.binding(0.50F, () -> cfg.armorMitigationScale, val -> cfg.armorMitigationScale = val)
					.controller(opt -> FloatSliderControllerBuilder.create(opt).range(0.0F, 1.0F).step(0.05F))
					.build())
				.build();

			return YetAnotherConfigLib.createBuilder()
				.title(Component.literal("XIV MMO Combat, Camera & HUD Configuration"))
				.category(hudCat.build())
				.category(cameraCat)
				.save(cfg::save)
				.build()
				.generateScreen(parent);
		}
	}
}
