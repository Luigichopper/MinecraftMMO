package net.xivmmo.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.xivmmo.XivMmoMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistent configuration for XIV MMO Combat, Camera, Movement & HUD Layout Overhaul.
 * Stored at {@code config/xivmmo.json}.
 */
public final class MmoConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("xivmmo.json");
	private static MmoConfig instance;

	/** Global default for MMO mode when no per-world/per-server override is present. */
	public boolean mmoModeEnabled = true;

	/**
	 * Build Mode toggle: when true while in MMO mode, locks cursor to crosshair so the player
	 * can mine/place blocks continuously with standard mouse behavior while keeping MMO HUD/systems active.
	 */
	public boolean buildModeEnabled = false;

	/** Camera orbit and zoom settings. */
	public float defaultZoomDistance = 6.0F;
	public float minZoomDistance = 1.5F;
	public float maxZoomDistance = 16.0F;
	public float zoomStep = 1.25F;
	public float zoomSmoothing = 0.30F;
	public float orbitSensitivity = 1.0F;
	public float cameraHeightOffset = 0.25F;
	public float cameraShoulderOffset = 0.0F;

	/**
	 * When true, pressing WASD while NOT holding RMB rotates the character to face the movement vector
	 * relative to the camera (standard FFXIV Legacy movement feel).
	 */
	public boolean autoFaceMovementDirection = true;

	/**
	 * When true, hides/removes the vanilla Minecraft 9-slot item hotbar UI while in MMO Mode
	 * (automatically re-showing it when Build Mode 'B' or 'Alt' is active).
	 */
	public boolean hideVanillaHotbarInMmoMode = true;

	/**
	 * When true, functionally disables the vanilla hotbar mechanics in MMO Mode (blocks 1-9 slot selection,
	 * hotbar item dropping/offhand swapping, middle-click pick block, and hotbar item attacks/uses)
	 * unless Build Mode ('B') or the Block Interact Modifier ('Alt') is active.
	 */
	public boolean disableVanillaHotbarMechanicsInMmoMode = true;

	/**
	 * When true, hides/removes the vanilla crosshair while in Free-Cursor MMO Mode.
	 */
	public boolean hideVanillaCrosshairInMmoMode = true;

	/**
	 * Master toggle for the MMO Action Hotbar (can be disabled to remove the MMO hotbar completely).
	 */
	public boolean showMmoActionHotbar = true;

	/**
	 * Configurable scale (0.0..1.0) for how much vanilla Armor reduces incoming MMO ability damage.
	 */
	public float armorMitigationScale = 0.50F;

	/**
	 * Identifiers for every rearrangeable, scalable, and toggleable MMO HUD element.
	 */
	public enum HudWidgetId {
		TARGET_FRAME("Target & ToT Frame", 0.50F, 0.05F, 1.0F, true, 220, 48),
		FOCUS_TARGET("Focus Target", 0.78F, 0.16F, 1.0F, true, 120, 26),
		CAST_BAR("Cast & GCD Bar", 0.50F, 0.69F, 1.0F, true, 180, 26),
		PLAYER_VITALS("Health & Hunger Bars", 0.50F, 0.78F, 1.0F, true, 182, 20),
		ACTION_HOTBAR("MMO Action Hotbar", 0.50F, 0.87F, 1.0F, true, 296, 42),
		PARTY_LIST("Party & Role List", 0.08F, 0.28F, 1.0F, true, 136, 108),
		COMBAT_TEXT("Floating Combat Text", 0.56F, 0.42F, 1.0F, true, 120, 60);

		private final String displayName;
		private final float defaultNormX;
		private final float defaultNormY;
		private final float defaultScale;
		private final boolean defaultEnabled;
		private final int baseWidth;
		private final int baseHeight;

		HudWidgetId(
			String displayName,
			float defaultNormX,
			float defaultNormY,
			float defaultScale,
			boolean defaultEnabled,
			int baseWidth,
			int baseHeight
		) {
			this.displayName = displayName;
			this.defaultNormX = defaultNormX;
			this.defaultNormY = defaultNormY;
			this.defaultScale = defaultScale;
			this.defaultEnabled = defaultEnabled;
			this.baseWidth = baseWidth;
			this.baseHeight = baseHeight;
		}

		public String displayName() {
			return this.displayName;
		}

		public int baseWidth() {
			return this.baseWidth;
		}

		public int baseHeight() {
			return this.baseHeight;
		}

		public WidgetLayout createDefaultLayout() {
			return new WidgetLayout(this.defaultNormX, this.defaultNormY, this.defaultScale, this.defaultEnabled);
		}
	}

	/**
	 * Normalized screen layout state for a single HUD widget.
	 * {@code normX} represents the horizontal center (0.0..1.0) and {@code normY} the top edge (0.0..1.0).
	 */
	public static final class WidgetLayout {
		public float normX;
		public float normY;
		public float scale;
		public boolean enabled;

		public WidgetLayout() {
			this(0.5F, 0.5F, 1.0F, true);
		}

		public WidgetLayout(float normX, float normY, float scale, boolean enabled) {
			this.normX = Math.clamp(normX, 0.02F, 0.98F);
			this.normY = Math.clamp(normY, 0.01F, 0.96F);
			this.scale = Math.clamp(scale, 0.50F, 2.00F);
			this.enabled = enabled;
		}
	}

	/** Per-widget HUD layout settings. */
	public Map<HudWidgetId, WidgetLayout> hudLayout = createDefaultHudLayout();

	/**
	 * Per-world (singleplayer level name) and per-server (multiplayer IP) overrides for MMO mode.
	 * Key format: {@code "sp:<levelName>"} or {@code "mp:<serverIp>"}.
	 */
	public Map<String, Boolean> worldServerMmoOverrides = new HashMap<>();

	private static Map<HudWidgetId, WidgetLayout> createDefaultHudLayout() {
		Map<HudWidgetId, WidgetLayout> map = new EnumMap<>(HudWidgetId.class);
		for (HudWidgetId id : HudWidgetId.values()) {
			map.put(id, id.createDefaultLayout());
		}
		return map;
	}

	public WidgetLayout getWidgetLayout(HudWidgetId id) {
		if (this.hudLayout == null) {
			this.hudLayout = createDefaultHudLayout();
		}
		return this.hudLayout.computeIfAbsent(id, HudWidgetId::createDefaultLayout);
	}

	public void resetHudLayoutToDefaults() {
		this.hudLayout = createDefaultHudLayout();
		this.showMmoActionHotbar = true;
		this.hideVanillaHotbarInMmoMode = true;
		this.disableVanillaHotbarMechanicsInMmoMode = true;
		this.hideVanillaCrosshairInMmoMode = true;
		save();
	}

	public static synchronized MmoConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public boolean isMmoActiveForWorld(String worldOrServerKey) {
		if (worldOrServerKey != null && worldServerMmoOverrides.containsKey(worldOrServerKey)) {
			return Boolean.TRUE.equals(worldServerMmoOverrides.get(worldOrServerKey));
		}
		return mmoModeEnabled;
	}

	public void setMmoActiveForWorld(String worldOrServerKey, Boolean active) {
		if (worldOrServerKey == null || worldOrServerKey.isEmpty()) {
			return;
		}
		if (active == null) {
			worldServerMmoOverrides.remove(worldOrServerKey);
		} else {
			worldServerMmoOverrides.put(worldOrServerKey, active);
		}
		save();
	}

	public static MmoConfig load() {
		if (Files.exists(CONFIG_PATH)) {
			try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
				MmoConfig loaded = GSON.fromJson(reader, MmoConfig.class);
				if (loaded != null) {
					if (loaded.worldServerMmoOverrides == null) {
						loaded.worldServerMmoOverrides = new HashMap<>();
					}
					if (loaded.hudLayout == null) {
						loaded.hudLayout = createDefaultHudLayout();
					} else {
						boolean hadVitals = loaded.hudLayout.containsKey(HudWidgetId.PLAYER_VITALS);
						for (HudWidgetId id : HudWidgetId.values()) {
							loaded.hudLayout.putIfAbsent(id, id.createDefaultLayout());
						}
						if (!hadVitals) {
							WidgetLayout castLayout = loaded.hudLayout.get(HudWidgetId.CAST_BAR);
							if (castLayout != null && Math.abs(castLayout.normY - 0.76F) < 0.02F) {
								castLayout.normY = HudWidgetId.CAST_BAR.defaultNormY;
							}
						}
					}
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				XivMmoMod.LOGGER.warn("Failed to read config from {}, using defaults", CONFIG_PATH, e);
			}
		}
		MmoConfig defaults = new MmoConfig();
		defaults.save();
		return defaults;
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			XivMmoMod.LOGGER.error("Failed to save config to {}", CONFIG_PATH, e);
		}
	}
}
