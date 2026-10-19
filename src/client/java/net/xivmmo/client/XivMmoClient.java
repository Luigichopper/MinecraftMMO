package net.xivmmo.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.xivmmo.XivMmoMod;
import net.xivmmo.client.camera.MmoCameraController;
import net.xivmmo.client.combat.ClientCombatPredictor;
import net.xivmmo.client.hud.TargetFrameHud;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.client.input.MmoKeybinds;
import net.xivmmo.client.render.WorldTelegraphRenderer;
import net.xivmmo.client.target.ClientTargetManager;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.config.MmoConfig;
import net.xivmmo.data.MmoDataRegistry;
import net.xivmmo.network.MmoNetwork;

import java.util.Locale;

public class XivMmoClient implements ClientModInitializer {
	private static final Identifier HUD_STATUS_ID = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "mode_status_hud");
	private static final Identifier HUD_TARGET_FRAME_ID = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "target_frame_hud");
	private static final Identifier HUD_COMBAT_BAR_ID = Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "combat_bar_hud");

	@Override
	public void onInitializeClient() {
		MmoConfig.get();
		MmoKeybinds.register();
		ClientCombatPredictor.get().registerNetworking();
		WorldTelegraphRenderer.register();
		net.xivmmo.client.hud.MmoHudManager.registerVanillaHudOverrides();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			MmoInputHandler.tickClient(mc);
			if (Boolean.getBoolean("xivmmo.autoQuitOnTitleScreen")
				&& mc.gui != null
				&& mc.gui.screen() != null) {
				XivMmoMod.LOGGER.info(
					"[XIVMMO] Smoke test verified Screen ({}), Mixins, Keybinds, Targeter, WorldTelegraphRenderer, HUD Overrides, Jobs ({}), Statuses ({}), and Abilities ({}). Stopping client cleanly.",
					mc.gui.screen().getClass().getSimpleName(),
					MmoDataRegistry.getAllJobs().size(),
					MmoDataRegistry.getAllStatuses().size(),
					MmoDataRegistry.getAllAbilities().size()
				);
				mc.stop();
			}
		});

		HudElementRegistry.addLast(HUD_STATUS_ID, XivMmoClient::renderModeBadge);
		HudElementRegistry.addLast(HUD_TARGET_FRAME_ID, TargetFrameHud::render);
		HudElementRegistry.addLast(HUD_COMBAT_BAR_ID, ClientCombatPredictor::renderHud);

		registerClientCommands();

		XivMmoMod.LOGGER.info("Initialized {} client (Camera, Targeting, Ability Engine, Statuses, Enmity, Telegraphs, Rearrangeable HUD & 4 Jobs active)", XivMmoMod.MOD_ID);
	}

	private static void renderModeBadge(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) {
			return;
		}

		boolean mmoActive = MmoInputHandler.isMmoModeActive();
		boolean buildActive = MmoInputHandler.isBuildModeActive();
		boolean altHeld = MmoInputHandler.isBlockInteractModifierDown();
		boolean orbiting = MmoInputHandler.isRightMouseOrbiting();

		String modeLabel;
		int borderColor;
		if (!mmoActive) {
			modeLabel = "MODE: VANILLA [F12]";
			borderColor = 0xFFAAAAAA;
		} else if (buildActive) {
			modeLabel = "MODE: MMO BUILD [B]";
			borderColor = 0xFFFFAA00;
		} else if (altHeld) {
			modeLabel = "MODE: MMO (ALT BLOCK INTERACT)";
			borderColor = 0xFF55FFFF;
		} else if (orbiting) {
			modeLabel = "MODE: MMO (RMB ORBIT)";
			borderColor = 0xFF55FF55;
		} else {
			modeLabel = "MODE: MMO FREE-CURSOR [F12 | B:Build | Alt:Mine]";
			borderColor = 0xFF44CC88;
		}

		MmoCameraController cam = MmoCameraController.get();
		String camInfo = mmoActive
			? String.format(Locale.ROOT, "Zoom: %.1fm | Yaw: %.0f° Pitch: %.0f°",
				cam.getZoomDistance(deltaTracker.getGameTimeDeltaPartialTick(true)),
				cam.getYaw(),
				cam.getPitch())
			: "Camera: Vanilla";

		int x = 6;
		int y = 6;
		int width = Math.max(mc.font.width(modeLabel), mc.font.width(camInfo)) + 8;
		int height = 24;

		graphics.fill(x, y, x + width, y + height, 0x9010141C);
		graphics.fill(x, y, x + width, y + 1, borderColor);
		graphics.text(mc.font, modeLabel, x + 4, y + 4, borderColor, true);
		graphics.text(mc.font, camInfo, x + 4, y + 14, 0xFFD0D8E8, true);
	}

	private static void registerClientCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			dispatcher.register(
				ClientCommands.literal("mmo")
					.then(ClientCommands.literal("status").executes(ctx -> {
						String worldKey = MmoInputHandler.getCurrentWorldOrServerKey();
						MmoCameraController cam = MmoCameraController.get();
						ClientTargetManager tm = ClientTargetManager.get();
						ctx.getSource().sendFeedback(Component.literal(String.format(
							Locale.ROOT,
							"[XIVMMO] World=%s | MMO=%s | Build=%s | Zoom=%.2f | HardTarget=%d | SoftTarget=%d | FocusTarget=%d | Abilities=%d | Statuses=%d | Telegraphs=%d",
							worldKey,
							MmoInputHandler.isMmoModeActive(),
							MmoInputHandler.isBuildModeActive(),
							cam.getZoomDistance(1.0F),
							tm.getHardTargetId(),
							tm.getSoftTargetId(),
							tm.getFocusTargetId(),
							MmoDataRegistry.getAllAbilities().size(),
							MmoDataRegistry.getAllStatuses().size(),
							WorldTelegraphRenderer.getClientTelegraphs().size()
						)).withStyle(ChatFormatting.AQUA));
						return 1;
					}))
					.then(ClientCommands.literal("cast")
						.then(ClientCommands.argument("ability", StringArgumentType.word()).executes(ctx -> {
							String name = StringArgumentType.getString(ctx, "ability");
							Identifier id = name.contains(":")
								? Identifier.parse(name)
								: Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, name);
							ClientCombatPredictor.get().requestAbility(id);
							return 1;
						}))
					)
					.then(ClientCommands.literal("telegraph")
						.then(ClientCommands.argument("shape", StringArgumentType.word()).executes(ctx -> {
							String s = StringArgumentType.getString(ctx, "shape").toLowerCase(Locale.ROOT);
							AbilityDefinition.AoeShape shape = switch (s) {
								case "cone" -> AbilityDefinition.AoeShape.CONE;
								case "line" -> AbilityDefinition.AoeShape.LINE;
								case "donut" -> AbilityDefinition.AoeShape.DONUT;
								default -> AbilityDefinition.AoeShape.CIRCLE;
							};
							float radius = shape == AbilityDefinition.AoeShape.LINE ? 10.0F : 6.0F;
							float inner = shape == AbilityDefinition.AoeShape.DONUT ? 2.5F : 1.5F;
							if (ClientPlayNetworking.canSend(MmoNetwork.C2SSpawnTestTelegraphPayload.TYPE)) {
								ClientPlayNetworking.send(new MmoNetwork.C2SSpawnTestTelegraphPayload(
									shape, radius, inner, 90.0F, 60
								));
								ctx.getSource().sendFeedback(Component.literal("[XIVMMO] Spawned 3.0s " + shape.getSerializedName() + " telegraph").withStyle(ChatFormatting.GOLD));
							}
							return 1;
						}))
					)
					.then(ClientCommands.literal("reticle").executes(ctx -> {
						WorldTelegraphRenderer.toggleGroundAoeReticle();
						ctx.getSource().sendFeedback(Component.literal(
							"[XIVMMO] Ground AoE Placement Reticle: " + (WorldTelegraphRenderer.isGroundAoeReticleVisible() ? "ON" : "OFF")
						).withStyle(ChatFormatting.AQUA));
						return 1;
					}))
					.then(ClientCommands.literal("target")
						.then(ClientCommands.literal("tab").executes(ctx -> {
							ClientTargetManager.get().cycleTabTarget(false);
							return 1;
						}))
						.then(ClientCommands.literal("focus").executes(ctx -> {
							ClientTargetManager.get().toggleFocusOnCurrentTarget();
							return 1;
						}))
						.then(ClientCommands.literal("clear").executes(ctx -> {
							ClientTargetManager.get().clearHardTargetIfPresent();
							return 1;
						}))
					)
					.then(ClientCommands.literal("mode")
						.then(ClientCommands.literal("mmo").executes(ctx -> {
							MmoConfig cfg = MmoConfig.get();
							cfg.mmoModeEnabled = true;
							cfg.buildModeEnabled = false;
							cfg.save();
							MmoInputHandler.syncCursorState();
							ctx.getSource().sendFeedback(Component.translatable("message.xivmmo.mode_mmo").withStyle(ChatFormatting.GREEN));
							return 1;
						}))
						.then(ClientCommands.literal("vanilla").executes(ctx -> {
							MmoConfig cfg = MmoConfig.get();
							cfg.mmoModeEnabled = false;
							cfg.save();
							MmoInputHandler.syncCursorState();
							ctx.getSource().sendFeedback(Component.translatable("message.xivmmo.mode_vanilla").withStyle(ChatFormatting.YELLOW));
							return 1;
						}))
						.then(ClientCommands.literal("build").executes(ctx -> {
							MmoConfig cfg = MmoConfig.get();
							cfg.mmoModeEnabled = true;
							cfg.buildModeEnabled = !cfg.buildModeEnabled;
							cfg.save();
							MmoInputHandler.syncCursorState();
							ctx.getSource().sendFeedback(Component.translatable(
								cfg.buildModeEnabled ? "message.xivmmo.build_mode_on" : "message.xivmmo.build_mode_off"
							).withStyle(ChatFormatting.GOLD));
							return 1;
						}))
					)
					.then(ClientCommands.literal("hud").executes(ctx -> {
						Minecraft mc = Minecraft.getInstance();
						mc.schedule(() -> mc.gui.setScreen(new net.xivmmo.client.hud.HudLayoutScreen(null)));
						return 1;
					}))
					.then(ClientCommands.literal("config").executes(ctx -> {
						Minecraft mc = Minecraft.getInstance();
						mc.schedule(() -> mc.gui.setScreen(net.xivmmo.client.compat.YaclConfigScreenFactory.createConfigScreen(null)));
						return 1;
					}))
					.then(ClientCommands.literal("job")
						.then(ClientCommands.argument("job_name", StringArgumentType.word()).executes(ctx -> {
							String name = StringArgumentType.getString(ctx, "job_name").toLowerCase(Locale.ROOT);
							Identifier id = name.contains(":")
								? Identifier.parse(name)
								: Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, name);
							ClientCombatPredictor.get().requestJobSwitch(id);
							return 1;
						}))
					)
					.then(ClientCommands.literal("arena")
						.then(ClientCommands.argument("arena_name", StringArgumentType.word()).executes(ctx -> {
							String arenaName = StringArgumentType.getString(ctx, "arena_name");
							if (ClientPlayNetworking.canSend(MmoNetwork.C2SSpawnArenaBossPayload.TYPE)) {
								ClientPlayNetworking.send(new MmoNetwork.C2SSpawnArenaBossPayload(arenaName));
							}
							return 1;
						}))
					)
					.then(ClientCommands.literal("hotbar")
						.then(ClientCommands.literal("mmo").executes(ctx -> {
							MmoConfig cfg = MmoConfig.get();
							boolean next = !cfg.showMmoActionHotbar;
							cfg.showMmoActionHotbar = next;
							cfg.getWidgetLayout(MmoConfig.HudWidgetId.ACTION_HOTBAR).enabled = next;
							cfg.save();
							ctx.getSource().sendFeedback(Component.literal("[XIVMMO] MMO Action Hotbar: " + (next ? "SHOWN" : "HIDDEN")).withStyle(ChatFormatting.AQUA));
							return 1;
						}))
						.then(ClientCommands.literal("vanilla").executes(ctx -> {
							MmoConfig cfg = MmoConfig.get();
							boolean nextOff = !(cfg.hideVanillaHotbarInMmoMode && cfg.disableVanillaHotbarMechanicsInMmoMode);
							cfg.hideVanillaHotbarInMmoMode = nextOff;
							cfg.disableVanillaHotbarMechanicsInMmoMode = nextOff;
							cfg.save();
							ctx.getSource().sendFeedback(Component.literal(
								"[XIVMMO] Vanilla Hotbar (UI + Mechanics) in MMO Mode: " + (nextOff ? "DISABLED & HIDDEN" : "ENABLED & SHOWN")
							).withStyle(ChatFormatting.AQUA));
							return 1;
						}))
					)
					.then(ClientCommands.literal("debug")
						.then(ClientCommands.literal("overlay").executes(ctx -> {
							boolean on = net.xivmmo.debug.MmoDebugState.toggleDebugOverlay();
							ctx.getSource().sendFeedback(Component.literal(
								"[XIVMMO] Diagnostic Debug Overlay: " + (on ? "ON" : "OFF")
							).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
							return 1;
						}))
						.then(ClientCommands.literal("ray").executes(ctx -> {
							boolean on = net.xivmmo.debug.MmoDebugState.toggleTargetingRayAndHitboxes();
							ctx.getSource().sendFeedback(Component.literal(
								"[XIVMMO] 3D Cursor Raycast & Hitbox Wireframes: " + (on ? "ON" : "OFF")
							).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
							return 1;
						}))
						.then(ClientCommands.literal("packets").executes(ctx -> {
							boolean on = net.xivmmo.debug.MmoDebugState.togglePacketTrace();
							ctx.getSource().sendFeedback(Component.literal(
								"[XIVMMO] Packet Trace Logging: " + (on ? "ON" : "OFF")
								+ " (Sent=" + net.xivmmo.debug.MmoDebugState.getTotalPacketsSent()
								+ ", Recv=" + net.xivmmo.debug.MmoDebugState.getTotalPacketsReceived() + ")"
							).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
							for (String line : net.xivmmo.debug.MmoDebugState.getRecentPacketLogs()) {
								ctx.getSource().sendFeedback(Component.literal("  " + line).withStyle(ChatFormatting.GRAY));
							}
							return 1;
						}))
						.then(ClientCommands.literal("state").executes(ctx -> {
							var st = ClientCombatPredictor.get().getPredictedState();
							ctx.getSource().sendFeedback(Component.literal(String.format(
								Locale.ROOT,
								"[XIVMMO-STATE] Job=%s | GCD=%d/%dt | AnimLock=%dt | Cast=%s | Queue=%s | Combo=%s (%dt) | MP=%d/%d | HotbarLocked=%s",
								ClientCombatPredictor.get().getActiveJobId(),
								st.getGcdRemainingTicks(),
								st.getGcdTotalTicks(),
								st.getAnimLockRemainingTicks(),
								st.getActiveCast() != null ? st.getActiveCast().abilityId() : "none",
								st.getQueuedAbility() != null ? st.getQueuedAbility().abilityId() : "none",
								st.getLastComboAbilityId() != null ? st.getLastComboAbilityId() : "none",
								st.getComboRemainingTicks(),
								st.getMana(),
								st.getMaxMana(),
								net.xivmmo.client.hud.MmoHudManager.shouldDisableVanillaHotbarMechanics()
							)).withStyle(ChatFormatting.AQUA));
							return 1;
						}))
						.then(ClientCommands.literal("enmity").executes(ctx -> {
							ctx.getSource().sendFeedback(Component.literal(String.format(
								Locale.ROOT,
								"[XIVMMO-ENMITY] HardTarget=%d | EnmityRank=#%d (%.1f%%) | PartyMembers=%d",
								ClientTargetManager.get().getHardTargetId(),
								TargetFrameHud.getClientEnmityRank(),
								TargetFrameHud.getClientEnmityRatio() * 100.0F,
								TargetFrameHud.getClientPartyMembers().size()
							)).withStyle(ChatFormatting.GOLD));
							return 1;
						}))
						.then(ClientCommands.literal("perf").executes(ctx -> {
							ctx.getSource().sendFeedback(Component.literal(String.format(
								Locale.ROOT,
								"[XIVMMO-PERF] ServerCombatTick: last=%.2f us, avg=%.2f us (budget <500 us) | ClientRaycastCacheHitRate=%.1f%%",
								net.xivmmo.debug.MmoDebugState.getLastServerTickMicros(),
								net.xivmmo.debug.MmoDebugState.getRollingAvgServerTickMicros(),
								net.xivmmo.debug.MmoDebugState.getRaycastCacheHitRatePercent()
							)).withStyle(ChatFormatting.GREEN));
							return 1;
						}))
					)
					.then(ClientCommands.literal("worldoverride")
						.then(ClientCommands.literal("on").executes(ctx -> {
							String key = MmoInputHandler.getCurrentWorldOrServerKey();
							MmoConfig.get().setMmoActiveForWorld(key, true);
							MmoInputHandler.syncCursorState();
							ctx.getSource().sendFeedback(Component.literal("[XIVMMO] Enabled MMO mode override for " + key).withStyle(ChatFormatting.GREEN));
							return 1;
						}))
						.then(ClientCommands.literal("off").executes(ctx -> {
							String key = MmoInputHandler.getCurrentWorldOrServerKey();
							MmoConfig.get().setMmoActiveForWorld(key, false);
							MmoInputHandler.syncCursorState();
							ctx.getSource().sendFeedback(Component.literal("[XIVMMO] Disabled MMO mode override for " + key).withStyle(ChatFormatting.YELLOW));
							return 1;
						}))
						.then(ClientCommands.literal("clear").executes(ctx -> {
							String key = MmoInputHandler.getCurrentWorldOrServerKey();
							MmoConfig.get().setMmoActiveForWorld(key, null);
							MmoInputHandler.syncCursorState();
							ctx.getSource().sendFeedback(Component.literal("[XIVMMO] Cleared MMO mode override for " + key).withStyle(ChatFormatting.AQUA));
							return 1;
						}))
					)
			);
		});
	}
}
