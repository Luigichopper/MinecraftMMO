package net.xivmmo.client.input;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.xivmmo.client.camera.MmoCameraController;
import net.xivmmo.client.combat.ClientCombatPredictor;
import net.xivmmo.client.target.ClientTargetManager;
import net.xivmmo.config.MmoConfig;

/**
 * Central client input coordinator for Free-Cursor MMO mode, RMB camera orbit,
 * LMB+RMB forward movement, Left-Click cursor picking & action bar clicking,
 * Tab/Focus targeting, and Build Mode / Alt block interaction.
 */
public final class MmoInputHandler {
	private static boolean leftMouseDown = false;
	private static boolean rightMouseDown = false;
	private static boolean rightMouseOrbiting = false;
	private static double savedOrbitCursorX = 0.0;
	private static double savedOrbitCursorY = 0.0;
	private static double lastLeftClickX = -1.0;
	private static double lastLeftClickY = -1.0;
	private static long lastLeftClickTimeMs = 0L;
	private static int lockedHotbarSlot = -1;

	private MmoInputHandler() {
	}

	public static String getCurrentWorldOrServerKey() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return "default";
		}
		IntegratedServer spServer = mc.getSingleplayerServer();
		if (spServer != null) {
			return "sp:" + spServer.getWorldData().getLevelName();
		}
		ServerData mpServer = mc.getCurrentServer();
		if (mpServer != null && mpServer.ip != null && !mpServer.ip.isEmpty()) {
			return "mp:" + mpServer.ip;
		}
		return "default";
	}

	public static boolean isMmoModeActive() {
		return MmoConfig.get().isMmoActiveForWorld(getCurrentWorldOrServerKey());
	}

	public static boolean isBuildModeActive() {
		return isMmoModeActive() && MmoConfig.get().buildModeEnabled;
	}

	public static boolean isFreeCursorMode() {
		return isMmoModeActive() && !MmoConfig.get().buildModeEnabled;
	}

	public static boolean isBlockInteractModifierDown() {
		return MmoKeybinds.KEY_BLOCK_INTERACT_MODIFIER.isDown();
	}

	public static boolean isBlockInteractionAllowed() {
		return !isMmoModeActive() || isBuildModeActive() || isBlockInteractModifierDown();
	}

	public static boolean isLeftMouseDown() {
		return leftMouseDown;
	}

	public static boolean isRightMouseDown() {
		return rightMouseDown;
	}

	public static boolean isRightMouseOrbiting() {
		return rightMouseOrbiting;
	}

	public static boolean isBothMouseHeld() {
		Minecraft mc = Minecraft.getInstance();
		return isFreeCursorMode()
			&& mc.gui.screen() == null
			&& mc.gui.overlay() == null
			&& leftMouseDown
			&& rightMouseDown
			&& !isBlockInteractModifierDown();
	}

	public static double getSavedOrbitCursorX() {
		return savedOrbitCursorX;
	}

	public static double getSavedOrbitCursorY() {
		return savedOrbitCursorY;
	}

	public static double getLastLeftClickX() {
		return lastLeftClickX;
	}

	public static double getLastLeftClickY() {
		return lastLeftClickY;
	}

	public static long getLastLeftClickTimeMs() {
		return lastLeftClickTimeMs;
	}

	public static void setMouseButtonState(int button, boolean pressed) {
		if (button == 0) {
			leftMouseDown = pressed;
		} else if (button == 1) {
			rightMouseDown = pressed;
		}
	}

	public static void startRightMouseOrbit(double cursorX, double cursorY) {
		rightMouseOrbiting = true;
		savedOrbitCursorX = cursorX;
		savedOrbitCursorY = cursorY;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			MmoCameraController cam = MmoCameraController.get();
			MmoCameraController.alignPlayerToCamera(mc.player, cam.getYaw(), cam.getPitch());
		}
	}

	public static void stopRightMouseOrbit() {
		rightMouseOrbiting = false;
	}

	public static void resetMouseStates() {
		leftMouseDown = false;
		rightMouseDown = false;
		rightMouseOrbiting = false;
	}

	/**
	 * Invoked when Left Mouse Button is clicked in Free-Cursor MMO mode:
	 * 1. Checks if the click hit an MMO Action Bar slot on the HUD;
	 * 2. Checks if the click hit a row in the Party List HUD;
	 * 3. Otherwise performs 3D cursor raycast picking to select a Hard Target entity.
	 */
	public static void onLeftClickSelect(double rawCursorX, double rawCursorY) {
		lastLeftClickX = rawCursorX;
		lastLeftClickY = rawCursorY;
		lastLeftClickTimeMs = System.currentTimeMillis();

		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();
		double guiX = MouseHandler.getScaledXPos(window, rawCursorX);
		double guiY = MouseHandler.getScaledYPos(window, rawCursorY);
		int sw = window.getGuiScaledWidth();
		int sh = window.getGuiScaledHeight();

		if (ClientCombatPredictor.get().tryClickActionBar(guiX, guiY, sw, sh)) {
			return;
		}

		if (net.xivmmo.client.hud.TargetFrameHud.tryClickPartyList(guiX, guiY, sw, sh)) {
			return;
		}

		if (net.xivmmo.client.chat.FfxivChatManager.get().tryClickChatHud(guiX, guiY, sw, sh)) {
			return;
		}

		ClientTargetManager.get().onCursorLeftClick(rawCursorX, rawCursorY);
	}

	public static void syncCursorState() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.mouseHandler == null) {
			return;
		}
		if (mc.gui.screen() != null || mc.gui.overlay() != null || mc.level == null) {
			return;
		}
		resetMouseStates();
		if (isFreeCursorMode()) {
			mc.mouseHandler.releaseMouse();
		} else {
			mc.mouseHandler.grabMouse();
		}
	}

	public static void tickClient(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}

		while (MmoKeybinds.KEY_TOGGLE_MMO_MODE.consumeClick()) {
			toggleMmoMode(mc);
		}

		while (MmoKeybinds.KEY_TOGGLE_BUILD_MODE.consumeClick()) {
			toggleBuildMode(mc);
		}

		while (MmoKeybinds.KEY_OPEN_HUD_EDITOR.consumeClick()) {
			mc.gui.setScreen(new net.xivmmo.client.hud.HudLayoutScreen(null));
		}

		while (MmoKeybinds.KEY_OPEN_INVENTORY.consumeClick()) {
			mc.gui.setScreen(new net.xivmmo.client.screen.FfxivInventoryScreen());
		}

		while (MmoKeybinds.KEY_OPEN_CHARACTER.consumeClick()) {
			mc.gui.setScreen(new net.xivmmo.client.screen.FfxivCharacterScreen());
		}

		while (MmoKeybinds.KEY_OPEN_ACTIONS.consumeClick()) {
			mc.gui.setScreen(new net.xivmmo.client.screen.FfxivActionsScreen());
		}

		while (MmoKeybinds.KEY_CYCLE_HOTBAR_PAGE.consumeClick()) {
			ClientCombatPredictor.get().cycleNextJob();
		}

		while (MmoKeybinds.KEY_TOGGLE_ACTION_HOTBAR.consumeClick()) {
			MmoConfig cfg = MmoConfig.get();
			boolean next = !cfg.showMmoActionHotbar;
			cfg.showMmoActionHotbar = next;
			cfg.getWidgetLayout(MmoConfig.HudWidgetId.ACTION_HOTBAR).enabled = next;
			cfg.save();
			mc.player.sendSystemMessage(Component.literal(
				"[XIVMMO] MMO Action Hotbar: " + (next ? "SHOWN" : "HIDDEN")
			).withStyle(next ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
		}

		while (MmoKeybinds.KEY_RESET_CAMERA.consumeClick()) {
			MmoCameraController.get().syncBehindEntity(mc.player);
			mc.player.sendSystemMessage(
				Component.translatable("message.xivmmo.camera_reset").withStyle(ChatFormatting.AQUA)
			);
		}

		while (MmoKeybinds.KEY_ZOOM_IN.consumeClick()) {
			MmoCameraController.get().onScrollZoom(1.0);
		}

		while (MmoKeybinds.KEY_ZOOM_OUT.consumeClick()) {
			MmoCameraController.get().onScrollZoom(-1.0);
		}

		while (MmoKeybinds.KEY_TAB_TARGET.consumeClick()) {
			ClientTargetManager.get().cycleTabTarget(mc.hasShiftDown());
		}

		while (MmoKeybinds.KEY_SET_FOCUS_TARGET.consumeClick()) {
			ClientTargetManager.get().toggleFocusOnCurrentTarget();
		}

		while (MmoKeybinds.KEY_CLEAR_TARGET.consumeClick()) {
			ClientTargetManager.get().clearHardTargetIfPresent();
		}

		for (int i = 0; i < MmoKeybinds.KEY_ABILITY_SLOTS.length; i++) {
			while (MmoKeybinds.KEY_ABILITY_SLOTS[i].consumeClick()) {
				ClientCombatPredictor.get().requestAbilitySlot(i);
			}
		}

		// Lock the active vanilla inventory hotbar slot when vanilla hotbar mechanics are disabled in MMO Combat Mode
		if (net.xivmmo.client.hud.MmoHudManager.shouldDisableVanillaHotbarMechanics()) {
			if (lockedHotbarSlot < 0 || lockedHotbarSlot >= 9) {
				lockedHotbarSlot = mc.player.getInventory().getSelectedSlot();
			} else if (mc.player.getInventory().getSelectedSlot() != lockedHotbarSlot) {
				mc.player.getInventory().setSelectedSlot(lockedHotbarSlot);
			}
		} else {
			lockedHotbarSlot = mc.player.getInventory().getSelectedSlot();
		}

		if (mc.gui.screen() == null && mc.gui.overlay() == null) {
			if (isFreeCursorMode() && !rightMouseOrbiting && mc.mouseHandler.isMouseGrabbed()) {
				mc.mouseHandler.releaseMouse();
			}
		}

		MmoCameraController.get().tick();
		ClientCombatPredictor.get().tick(mc);
	}

	public static void toggleMmoMode(Minecraft mc) {
		MmoConfig cfg = MmoConfig.get();
		String worldKey = getCurrentWorldOrServerKey();
		boolean nextState = !cfg.isMmoActiveForWorld(worldKey);
		if (cfg.worldServerMmoOverrides.containsKey(worldKey)) {
			cfg.setMmoActiveForWorld(worldKey, nextState);
		} else {
			cfg.mmoModeEnabled = nextState;
			cfg.save();
		}
		if (nextState && mc.player != null) {
			MmoCameraController.get().syncBehindEntity(mc.player);
		}
		syncCursorState();
		if (mc.player != null) {
			mc.player.sendSystemMessage(
				Component.translatable(nextState ? "message.xivmmo.mode_mmo" : "message.xivmmo.mode_vanilla")
					.withStyle(nextState ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
			);
		}
	}

	public static void toggleBuildMode(Minecraft mc) {
		if (!isMmoModeActive()) {
			return;
		}
		MmoConfig cfg = MmoConfig.get();
		cfg.buildModeEnabled = !cfg.buildModeEnabled;
		cfg.save();
		syncCursorState();
		if (mc.player != null) {
			mc.player.sendSystemMessage(
				Component.translatable(cfg.buildModeEnabled ? "message.xivmmo.build_mode_on" : "message.xivmmo.build_mode_off")
					.withStyle(cfg.buildModeEnabled ? ChatFormatting.GOLD : ChatFormatting.AQUA)
			);
		}
	}
}
