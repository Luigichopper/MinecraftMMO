package net.xivmmo.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.xivmmo.XivMmoMod;
import org.lwjgl.glfw.GLFW;

/**
 * Registers all rebindable MMO camera, cursor, targeting, FFXIV windows (Inventory, Character, Actions),
 * and 12-slot ability hotbar keybindings through vanilla's KeyMapping system.
 */
public final class MmoKeybinds {
	public static final KeyMapping.Category CATEGORY_GENERAL = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "general")
	);

	public static final KeyMapping KEY_TOGGLE_MMO_MODE = new KeyMapping(
		"key.xivmmo.toggle_mmo_mode",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_F12,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_TOGGLE_BUILD_MODE = new KeyMapping(
		"key.xivmmo.toggle_build_mode",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_B,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_BLOCK_INTERACT_MODIFIER = new KeyMapping(
		"key.xivmmo.block_interact_modifier",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_LEFT_ALT,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_RESET_CAMERA = new KeyMapping(
		"key.xivmmo.reset_camera",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_HOME,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_ZOOM_IN = new KeyMapping(
		"key.xivmmo.zoom_in",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_PAGE_UP,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_ZOOM_OUT = new KeyMapping(
		"key.xivmmo.zoom_out",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_PAGE_DOWN,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_TAB_TARGET = new KeyMapping(
		"key.xivmmo.tab_target",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_TAB,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_SET_FOCUS_TARGET = new KeyMapping(
		"key.xivmmo.set_focus_target",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_F,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_CLEAR_TARGET = new KeyMapping(
		"key.xivmmo.clear_target",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_BACKSPACE,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_OPEN_HUD_EDITOR = new KeyMapping(
		"key.xivmmo.open_hud_editor",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_H,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_OPEN_INVENTORY = new KeyMapping(
		"key.xivmmo.open_inventory",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_I,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_OPEN_CHARACTER = new KeyMapping(
		"key.xivmmo.open_character",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_C,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_OPEN_ACTIONS = new KeyMapping(
		"key.xivmmo.open_actions",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_P,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_CYCLE_HOTBAR_PAGE = new KeyMapping(
		"key.xivmmo.cycle_hotbar_page",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_GRAVE_ACCENT,
		CATEGORY_GENERAL
	);

	public static final KeyMapping KEY_TOGGLE_ACTION_HOTBAR = new KeyMapping(
		"key.xivmmo.toggle_action_hotbar",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_F9,
		CATEGORY_GENERAL
	);

	/**
	 * 12 FFXIV Hotbar Slots.
	 * Note: In MMO Combat Mode, number keys 1-9 also directly trigger Slots 1-9 while vanilla hotbar
	 * mechanics are disabled, and 0, -, = trigger Slots 10-12.
	 */
	public static final String[] FFXIV_HOTBAR_LABELS = {
		"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="
	};

	private static final int[] DEFAULT_ABILITY_KEYS = {
		GLFW.GLFW_KEY_R,
		GLFW.GLFW_KEY_V,
		GLFW.GLFW_KEY_G,
		GLFW.GLFW_KEY_Z,
		GLFW.GLFW_KEY_X,
		GLFW.GLFW_KEY_Y,
		GLFW.GLFW_KEY_U,
		GLFW.GLFW_KEY_J,
		GLFW.GLFW_KEY_K,
		GLFW.GLFW_KEY_0,
		GLFW.GLFW_KEY_MINUS,
		GLFW.GLFW_KEY_EQUAL
	};

	public static final KeyMapping[] KEY_ABILITY_SLOTS = new KeyMapping[12];

	static {
		for (int i = 0; i < KEY_ABILITY_SLOTS.length; i++) {
			KEY_ABILITY_SLOTS[i] = new KeyMapping(
				"key.xivmmo.ability_slot_" + (i + 1),
				InputConstants.Type.KEYSYM,
				DEFAULT_ABILITY_KEYS[i],
				CATEGORY_GENERAL
			);
		}
	}

	private MmoKeybinds() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(KEY_TOGGLE_MMO_MODE);
		KeyMappingHelper.registerKeyMapping(KEY_TOGGLE_BUILD_MODE);
		KeyMappingHelper.registerKeyMapping(KEY_BLOCK_INTERACT_MODIFIER);
		KeyMappingHelper.registerKeyMapping(KEY_RESET_CAMERA);
		KeyMappingHelper.registerKeyMapping(KEY_ZOOM_IN);
		KeyMappingHelper.registerKeyMapping(KEY_ZOOM_OUT);
		KeyMappingHelper.registerKeyMapping(KEY_TAB_TARGET);
		KeyMappingHelper.registerKeyMapping(KEY_SET_FOCUS_TARGET);
		KeyMappingHelper.registerKeyMapping(KEY_CLEAR_TARGET);
		KeyMappingHelper.registerKeyMapping(KEY_OPEN_HUD_EDITOR);
		KeyMappingHelper.registerKeyMapping(KEY_OPEN_INVENTORY);
		KeyMappingHelper.registerKeyMapping(KEY_OPEN_CHARACTER);
		KeyMappingHelper.registerKeyMapping(KEY_OPEN_ACTIONS);
		KeyMappingHelper.registerKeyMapping(KEY_CYCLE_HOTBAR_PAGE);
		KeyMappingHelper.registerKeyMapping(KEY_TOGGLE_ACTION_HOTBAR);
		for (KeyMapping slotKey : KEY_ABILITY_SLOTS) {
			KeyMappingHelper.registerKeyMapping(slotKey);
		}
	}
}
