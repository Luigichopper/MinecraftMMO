package net.xivmmo.client.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.xivmmo.combat.ability.AbilityDefinition;

/**
 * Shared FFXIV visual theme and procedural UI rendering toolkit.
 * Provides:
 * - Dark charcoal-slate window frames with warm gold/bronze beveled borders & header rule
 * - Recessed 3D slot wells for hotbars and inventory grids
 * - Role emblems (Tank Blue Shield, Healer Green Cross, DPS Crimson Sword)
 * - Procedural vector/pixel ability icons for every Job and Role action
 * - FFXIV gauge bars with dark casing, specular highlights, and crisp drop-shadowed typography
 */
public final class FfxivTheme {
	// Window Chrome Palette
	public static final int WIN_BG_OUTER = 0xEE1A1B20;
	public static final int WIN_BG_INNER = 0xF223252C;
	public static final int WIN_HEADER_TOP = 0xFF333640;
	public static final int WIN_HEADER_BOT = 0xFF22242B;
	public static final int GOLD_OUTER = 0xFF8C7348;
	public static final int GOLD_BRIGHT = 0xFFD9BE7E;
	public static final int GOLD_RULE = 0xFFB89B5E;
	public static final int SILVER_MUTED = 0xFF6B7080;

	// Slot Well Palette
	public static final int SLOT_BG_DARK = 0xEE14161B;
	public static final int SLOT_BG_HOVER = 0xEE2C3240;
	public static final int SLOT_BEVEL_TOP = 0xFF0A0B0E;
	public static final int SLOT_BEVEL_BOT = 0xFF464B59;
	public static final int SLOT_BORDER_GOLD = 0xFFC5A869;

	// Gauge Colors
	public static final int HP_BAR_FILL = 0xFF5CE665;
	public static final int HP_BAR_TOP = 0xFFA2FF9E;
	public static final int MP_BAR_FILL = 0xFFE84CA8;
	public static final int MP_BAR_TOP = 0xFFFF9AD5;
	public static final int TP_BAR_FILL = 0xFFE6A832;
	public static final int TP_BAR_TOP = 0xFFFFDF7A;
	public static final int EXP_BAR_FILL = 0xFFF2C84B;
	public static final int EXP_BAR_BG = 0xCC101116;

	// Text Colors
	public static final int TEXT_GOLD = 0xFFE5C985;
	public static final int TEXT_CREAM = 0xFFF4F1E8;
	public static final int TEXT_SILVER = 0xFFB8BECC;
	public static final int TEXT_MP_MAGENTA = 0xFFFF66CC;
	public static final int TEXT_HP_GREEN = 0xFF88FF88;
	public static final int TEXT_TP_GOLD = 0xFFFFCC55;

	private FfxivTheme() {
	}

	/**
	 * Draws a classic FFXIV dark charcoal-slate window with a gold/bronze beveled border,
	 * dark-gradient title header, gold horizontal rule, and optional close ('X') button.
	 */
	public static void drawWindowFrame(
		GuiGraphicsExtractor g,
		Font font,
		int x,
		int y,
		int w,
		int h,
		String title,
		boolean drawCloseBtn
	) {
		// Drop shadow
		g.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x88000000);

		// Outer dark frame + gold bevel
		g.fill(x, y, x + w, y + h, WIN_BG_OUTER);
		g.fill(x + 2, y + 2, x + w - 2, y + h - 2, WIN_BG_INNER);

		// Header band
		int headerH = 22;
		g.fill(x + 2, y + 2, x + w - 2, y + headerH, WIN_HEADER_TOP);
		g.fill(x + 2, y + 12, x + w - 2, y + headerH, WIN_HEADER_BOT);

		// Outer gold/bronze border
		g.fill(x, y, x + w, y + 1, GOLD_BRIGHT);
		g.fill(x, y + h - 1, x + w, y + h, GOLD_OUTER);
		g.fill(x, y, x + 1, y + h, GOLD_OUTER);
		g.fill(x + w - 1, y, x + w, y + h, GOLD_OUTER);

		// Corner gold rivets
		g.fill(x + 1, y + 1, x + 4, y + 4, GOLD_BRIGHT);
		g.fill(x + w - 4, y + 1, x + w - 1, y + 4, GOLD_BRIGHT);
		g.fill(x + 1, y + h - 4, x + 4, y + h - 1, GOLD_OUTER);
		g.fill(x + w - 4, y + h - 4, x + w - 1, y + h - 1, GOLD_OUTER);

		// Gold horizontal rule below header
		g.fill(x + 6, y + headerH, x + w - 6, y + headerH + 1, GOLD_RULE);
		g.fill(x + 6, y + headerH + 1, x + w - 6, y + headerH + 2, 0x88000000);

		// Title text
		g.text(font, title, x + 10, y + 7, TEXT_GOLD, true);

		if (drawCloseBtn) {
			int bx = x + w - 18;
			int by = y + 5;
			g.fill(bx, by, bx + 12, by + 12, 0xFF3A2226);
			g.outline(bx, by, 12, 12, GOLD_OUTER);
			g.text(font, "x", bx + 3, by + 2, TEXT_CREAM, true);
		}
	}

	/**
	 * Draws a recessed 3D FFXIV slot well (used for inventory slots and hotbar cells).
	 */
	public static void drawRecessedSlot(
		GuiGraphicsExtractor g,
		int x,
		int y,
		int size,
		boolean hovered,
		boolean selected,
		int categoryBorderArgb
	) {
		g.fill(x, y, x + size, y + size, hovered ? SLOT_BG_HOVER : SLOT_BG_DARK);
		// Top/left recessed shadow
		g.fill(x, y, x + size, y + 1, SLOT_BEVEL_TOP);
		g.fill(x, y, x + 1, y + size, SLOT_BEVEL_TOP);
		// Bottom/right subtle highlight
		g.fill(x, y + size - 1, x + size, y + size, SLOT_BEVEL_BOT);
		g.fill(x + size - 1, y, x + size, y + size, SLOT_BEVEL_BOT);

		if (categoryBorderArgb != 0) {
			g.outline(x + 1, y + 1, size - 2, size - 2, categoryBorderArgb);
		}
		if (selected) {
			g.outline(x, y, size, size, 0xFFFFDF66);
		} else if (hovered) {
			g.outline(x, y, size, size, SLOT_BORDER_GOLD);
		}
	}

	/**
	 * Draws a sleek FFXIV gauge bar with dark recessed track, bright top specular edge, and main fill.
	 */
	public static void drawGaugeBar(
		GuiGraphicsExtractor g,
		int x,
		int y,
		int w,
		int h,
		float ratio,
		int fillColor,
		int topHighlightColor
	) {
		float clamped = Math.clamp(ratio, 0.0F, 1.0F);
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xEE0B0C10);
		g.fill(x, y, x + w, y + h, 0xFF1D2028);
		int fillW = Math.round(w * clamped);
		if (fillW > 0) {
			g.fill(x, y, x + fillW, y + h, fillColor);
			if (h >= 3) {
				g.fill(x, y, x + fillW, y + 1, topHighlightColor);
			}
		}
		// Subtle bottom metallic trim
		g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFF5A5240);
	}

	/**
	 * Draws an FFXIV Role Emblem icon (12x12) at (x, y):
	 * - Tank: Blue Shield with silver crest
	 * - Healer: Green Cross with bright center
	 * - DPS: Crimson/Red crossed blades emblem
	 */
	public static void drawRoleEmblem(GuiGraphicsExtractor g, int x, int y, String role) {
		String r = role == null ? "tank" : role.toLowerCase();
		if (r.contains("tank")) {
			// Blue shield
			g.fill(x + 1, y + 1, x + 11, y + 9, 0xFF2858B8);
			g.fill(x + 2, y + 9, x + 10, y + 11, 0xFF2858B8);
			g.fill(x + 4, y + 11, x + 8, y + 12, 0xFF2858B8);
			g.outline(x + 1, y + 1, 10, 9, 0xFF9AC4FF);
			// Inner shield cross
			g.fill(x + 5, y + 3, x + 7, y + 9, 0xFFD8ECFF);
			g.fill(x + 3, y + 4, x + 9, y + 6, 0xFFD8ECFF);
		} else if (r.contains("heal")) {
			// Green healer badge
			g.fill(x + 1, y + 1, x + 11, y + 11, 0xFF1E7A36);
			g.outline(x + 1, y + 1, 10, 10, 0xFF8CFF9E);
			// White/mint cross
			g.fill(x + 5, y + 3, x + 7, y + 9, 0xFFE6FFEA);
			g.fill(x + 3, y + 5, x + 9, y + 7, 0xFFE6FFEA);
		} else {
			// Crimson DPS badge
			g.fill(x + 1, y + 1, x + 11, y + 11, 0xFF9E2424);
			g.outline(x + 1, y + 1, 10, 10, 0xFFFF8888);
			// Diagonal blade motif
			g.fill(x + 3, y + 7, x + 5, y + 9, 0xFFFFDFAA);
			g.fill(x + 4, y + 5, x + 7, y + 8, 0xFFFFEFE0);
			g.fill(x + 6, y + 3, x + 9, y + 6, 0xFFFFFFFF);
		}
	}

	/**
	 * Draws a procedural FFXIV-style ability icon inside a (size x size) box at (x, y).
	 * Uses the ability's ID, category, target type, and role color palette to render a rich,
	 * distinguishable layered emblem with a glossy top bevel so every slot looks like an authentic action icon.
	 */
	public static void drawAbilityIcon(
		GuiGraphicsExtractor g,
		int x,
		int y,
		int size,
		AbilityDefinition def,
		boolean unlocked
	) {
		String path = def.id().getPath();
		int hash = Math.abs(path.hashCode());

		// Base background color by ability theme
		int bgTop;
		int bgBot;
		int accent;
		int secondary;

		if (path.contains("bulwark") || path.contains("aegis") || path.contains("iron") || path.contains("sentinel")
			|| path.contains("sunder") || path.contains("tempest") || path.contains("provoke") || path.contains("shirk")) {
			// Vanguard (Tank): Cobalt / Gold / Steel
			bgTop = 0xFF203868;
			bgBot = 0xFF101C38;
			accent = 0xFF66B8FF;
			secondary = 0xFFFFD266;
		} else if (path.contains("lumen") || path.contains("mend") || path.contains("regen") || path.contains("halo")
			|| path.contains("sacred") || path.contains("vital") || path.contains("celestial") || path.contains("lucid")) {
			// Hierophant (Healer): Emerald / Mint / Gold-White
			bgTop = 0xFF184D38;
			bgBot = 0xFF0E291E;
			accent = 0xFF70FFAA;
			secondary = 0xFFFFF2A6;
		} else if (path.contains("keen") || path.contains("vorpal") || path.contains("wheeling") || path.contains("fang")
			|| path.contains("skydive") || path.contains("blood") || path.contains("sweep") || path.contains("feint")) {
			// Lancer (Melee DPS): Crimson / Amber / Dragon Gold
			bgTop = 0xFF5E1B24;
			bgBot = 0xFF2E0B10;
			accent = 0xFFFF6E59;
			secondary = 0xFFFFD966;
		} else if (path.contains("ruin") || path.contains("miasma") || path.contains("starfall") || path.contains("void")
			|| path.contains("aether") || path.contains("swift") || path.contains("mana") || path.contains("addle")) {
			// Arcanist (Ranged Caster): Amethyst / Violet / Cyan
			bgTop = 0xFF441E63;
			bgBot = 0xFF210D33;
			accent = 0xFFD97AFF;
			secondary = 0xFF66F0FF;
		} else {
			// Role / General Actions (Second Wind, Sprint, Limit Surge, Sanctuary Ward)
			bgTop = 0xFF4F3D1B;
			bgBot = 0xFF261C0A;
			accent = 0xFFFFCC44;
			secondary = 0xFFFFFFFF;
		}

		// Background gradient
		int half = size / 2;
		g.fill(x + 1, y + 1, x + size - 1, y + half, bgTop);
		g.fill(x + 1, y + half, x + size - 1, y + size - 1, bgBot);

		int cx = x + half;
		int cy = y + half;

		// Distinct central motif based on ability behavior / name
		if (def.targetType() == AbilityDefinition.TargetType.SELF || path.contains("stance") || path.contains("ward") || path.contains("rampart")) {
			// Shield / Crest motif
			g.fill(cx - 5, cy - 5, cx + 5, cy + 3, accent);
			g.fill(cx - 3, cy + 3, cx + 3, cy + 6, accent);
			g.fill(cx - 2, cy - 3, cx + 2, cy + 3, secondary);
		} else if (def.targetType() == AbilityDefinition.TargetType.SINGLE_ALLY || path.contains("mend") || path.contains("wind") || path.contains("regen")) {
			// Healing Cross / Sunburst motif
			g.fill(cx - 2, cy - 6, cx + 2, cy + 6, accent);
			g.fill(cx - 6, cy - 2, cx + 6, cy + 2, accent);
			g.fill(cx - 2, cy - 2, cx + 2, cy + 2, secondary);
		} else if (def.targetType() == AbilityDefinition.TargetType.GROUND_AOE || def.targetType() == AbilityDefinition.TargetType.SELF_AOE) {
			// AoE Ring + Nova motif
			g.outline(cx - 6, cy - 6, 12, 12, accent);
			g.outline(cx - 4, cy - 4, 8, 8, secondary);
			g.fill(cx - 2, cy - 2, cx + 2, cy + 2, accent);
		} else {
			// Offensive Slash / Spellburst motif with variation per ability hash
			int variant = hash % 3;
			if (variant == 0) {
				// Diagonal slash + cross spark
				g.fill(cx - 6, cy + 2, cx - 2, cy + 6, accent);
				g.fill(cx - 3, cy - 2, cx + 2, cy + 3, secondary);
				g.fill(cx + 1, cy - 6, cx + 6, cy - 1, accent);
			} else if (variant == 1) {
				// Twin lance / twin fangs
				g.fill(cx - 5, cy - 5, cx - 1, cy + 5, accent);
				g.fill(cx + 1, cy - 5, cx + 5, cy + 5, secondary);
				g.fill(cx - 3, cy - 2, cx + 3, cy + 2, 0xFFFFFFFF);
			} else {
				// Arcane diamond / star
				g.fill(cx - 5, cy - 2, cx + 5, cy + 2, accent);
				g.fill(cx - 2, cy - 5, cx + 2, cy + 5, secondary);
				g.fill(cx - 3, cy - 3, cx + 3, cy + 3, accent);
			}
		}

		// Top glossy specular highlight bar (authentic FFXIV icon glass sheen)
		g.fill(x + 2, y + 2, x + size - 2, y + 4, 0x44FFFFFF);

		// Metallic icon border
		g.outline(x, y, size, size, unlocked ? 0xFF9C8B65 : 0xFF444854);

		if (!unlocked) {
			g.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0xAA08090C);
		}
	}

	/**
	 * Draws the FFXIV left hotbar Page Number badge (e.g., '1' inside a dark gold-trimmed chevron/hexagon).
	 */
	public static void drawHotbarPageBadge(GuiGraphicsExtractor g, Font font, int x, int y, int pageNumber) {
		g.fill(x, y + 3, x + 14, y + 23, 0xEE181A22);
		g.outline(x, y + 3, 14, 20, GOLD_RULE);
		g.fill(x + 1, y + 4, x + 13, y + 5, GOLD_BRIGHT);
		String num = Integer.toString(pageNumber);
		g.text(font, num, x + 4, y + 9, TEXT_GOLD, true);
	}

	/**
	 * Draws the FFXIV right hotbar Padlock icon at (x, y).
	 */
	public static void drawPadlockIcon(GuiGraphicsExtractor g, int x, int y, boolean locked) {
		int color = locked ? GOLD_BRIGHT : SILVER_MUTED;
		// Shackle
		g.outline(x + 3, y + 2, 6, 5, color);
		// Body
		g.fill(x + 2, y + 6, x + 10, y + 13, 0xFF1B1D24);
		g.outline(x + 2, y + 6, 8, 7, color);
		// Keyhole dot
		g.fill(x + 5, y + 8, x + 7, y + 11, color);
	}

	public static String getJobAbbreviation(Identifier jobId) {
		if (jobId == null) {
			return "ADV";
		}
		return switch (jobId.getPath()) {
			case "vanguard" -> "VGD";
			case "hierophant" -> "HIE";
			case "lancer" -> "LNC";
			case "arcanist" -> "ARC";
			default -> jobId.getPath().substring(0, Math.min(3, jobId.getPath().length())).toUpperCase();
		};
	}
}
