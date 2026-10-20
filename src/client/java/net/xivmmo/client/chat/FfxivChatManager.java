package net.xivmmo.client.chat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.xivmmo.chat.FfxivChatChannel;
import net.xivmmo.client.hud.FfxivTheme;
import net.xivmmo.client.hud.MmoHudManager;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.client.screen.FfxivChatScreen;
import net.xivmmo.config.MmoConfig;
import net.xivmmo.network.MmoNetwork;
import org.jspecify.annotations.Nullable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side FFXIV Chat Log manager and HUD renderer.
 * Maintains categorized chat history across {@code General}, {@code Battle}, {@code Event}, and {@code +} tabs,
 * handles outgoing channel routing ({@code Say}, {@code Party}, {@code Shout}, {@code Yell}, {@code Tell}, {@code FC}),
 * renders FFXIV Auto-Translate capsules ({@code ◖...◗}), and provides interactive free-cursor tab/input/scroll controls.
 */
public final class FfxivChatManager {
	private static final int MAX_LOG_ENTRIES = 250;
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
	private static final FfxivChatManager INSTANCE = new FfxivChatManager();

	public record ChatEntry(
		FfxivChatChannel channel,
		String senderName,
		String text,
		String timestamp,
		long createdAtMs
	) {
	}

	private record WrappedChatLine(
		ChatEntry sourceEntry,
		String lineText,
		int color,
		boolean firstLineOfEntry
	) {
	}

	private final List<ChatEntry> entries = new ArrayList<>();
	private final Map<FfxivChatChannel.FilterTab, Integer> unreadByTab = new EnumMap<>(FfxivChatChannel.FilterTab.class);
	private FfxivChatChannel.FilterTab activeTab = FfxivChatChannel.FilterTab.GENERAL;
	private FfxivChatChannel defaultChannel = FfxivChatChannel.SAY;
	private String lastTellPartner = "";
	private int scrollOffsetLines = 0;
	private boolean listenersRegistered = false;

	private FfxivChatManager() {
		for (FfxivChatChannel.FilterTab tab : FfxivChatChannel.FilterTab.values()) {
			this.unreadByTab.put(tab, 0);
		}
		seedStarterEntries();
	}

	public static FfxivChatManager get() {
		return INSTANCE;
	}

	private void seedStarterEntries() {
		addEntry(FfxivChatChannel.EVENT, "", "You change to vanguard.");
		addEntry(
			FfxivChatChannel.FREE_COMPANY,
			"Aether Guard",
			"[FC]<Aether Guard> Welcome! Use "
				+ FfxivChatChannel.formatAutoTranslatePhrase("Looking for party.")
				+ " or click the chat bar to talk."
		);
		addEntry(
			FfxivChatChannel.EVENT,
			"",
			"Duty Finder: Boss arenas Sunforge & Void Sanctum are available (/mmo arena)."
		);
		this.unreadByTab.replaceAll((tab, count) -> 0);
	}

	public void registerClientHooks() {
		if (this.listenersRegistered) {
			return;
		}
		this.listenersRegistered = true;

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay && message != null) {
				onVanillaMessageReceived(message.getString());
			}
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (message != null) {
				onVanillaMessageReceived(message.getString());
			}
		});
	}

	public void onReceiveChannelPacket(MmoNetwork.S2CChatChannelPayload payload) {
		FfxivChatChannel channel;
		try {
			channel = FfxivChatChannel.valueOf(payload.channelName().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			channel = FfxivChatChannel.SAY;
		}
		if (channel == FfxivChatChannel.TELL && payload.senderName() != null && !payload.senderName().isEmpty()) {
			this.lastTellPartner = payload.senderName();
		}
		addEntry(channel, payload.senderName() != null ? payload.senderName() : "", payload.formattedText());
	}

	public void onVanillaMessageReceived(String plainText) {
		if (plainText == null || plainText.isBlank()) {
			return;
		}
		String cleaned = FfxivChatChannel.expandAutoTranslateTokens(plainText.trim());
		FfxivChatChannel channel = FfxivChatChannel.classifyIncomingMessage(cleaned);
		String sender = extractSenderIfPresent(cleaned);
		addEntry(channel, sender, cleaned);
	}

	private static String extractSenderIfPresent(String text) {
		if (text.startsWith("<") && text.indexOf('>') > 1 && text.indexOf('>') < 24) {
			return text.substring(1, text.indexOf('>'));
		}
		int colon = text.indexOf(": ");
		if (colon > 0 && colon < 22 && !text.startsWith("[") && !text.startsWith(">>")) {
			String candidate = text.substring(0, colon).trim();
			if (candidate.indexOf(' ') < 0) {
				return candidate;
			}
		}
		return "";
	}

	public synchronized void addEntry(FfxivChatChannel channel, String senderName, String text) {
		String timeStr = LocalTime.now().format(TIME_FMT);
		ChatEntry entry = new ChatEntry(channel, senderName, text, timeStr, System.currentTimeMillis());
		this.entries.add(entry);
		while (this.entries.size() > MAX_LOG_ENTRIES) {
			this.entries.removeFirst();
		}

		for (FfxivChatChannel.FilterTab tab : FfxivChatChannel.FilterTab.values()) {
			if (tab != this.activeTab && tab.accepts(channel)) {
				this.unreadByTab.put(tab, Math.min(99, this.unreadByTab.getOrDefault(tab, 0) + 1));
			}
		}
	}

	public synchronized void clearEntries() {
		this.entries.clear();
		this.scrollOffsetLines = 0;
		for (FfxivChatChannel.FilterTab tab : FfxivChatChannel.FilterTab.values()) {
			this.unreadByTab.put(tab, 0);
		}
		addEntry(FfxivChatChannel.SYSTEM, "", "Chat log cleared.");
	}

	public synchronized List<ChatEntry> getFilteredEntries(FfxivChatChannel.FilterTab tab) {
		List<ChatEntry> out = new ArrayList<>();
		for (ChatEntry e : this.entries) {
			if (tab.accepts(e.channel())) {
				out.add(e);
			}
		}
		return out;
	}

	public FfxivChatChannel.FilterTab getActiveTab() {
		return this.activeTab;
	}

	public void setActiveTab(FfxivChatChannel.FilterTab tab) {
		this.activeTab = tab;
		this.unreadByTab.put(tab, 0);
		this.scrollOffsetLines = 0;
	}

	public int getUnreadCount(FfxivChatChannel.FilterTab tab) {
		return this.unreadByTab.getOrDefault(tab, 0);
	}

	public FfxivChatChannel getDefaultChannel() {
		return this.defaultChannel;
	}

	public void setDefaultChannel(FfxivChatChannel channel) {
		if (channel != null && channel.isSelectableAsDefault()) {
			this.defaultChannel = channel;
		}
	}

	public void cycleDefaultChannel() {
		this.defaultChannel = this.defaultChannel.nextSelectableChannel();
	}

	public String getLastTellPartner() {
		return this.lastTellPartner;
	}

	public void setLastTellPartner(String partner) {
		this.lastTellPartner = partner != null ? partner : "";
	}

	public int getScrollOffsetLines() {
		return this.scrollOffsetLines;
	}

	public void scrollLines(int deltaLines) {
		this.scrollOffsetLines = Mth.clamp(this.scrollOffsetLines + deltaLines, 0, 200);
	}

	public void resetScroll() {
		this.scrollOffsetLines = 0;
	}

	/**
	 * Submits an outgoing chat line from {@link FfxivChatScreen}:
	 * - Standard slash commands ({@code /mmo}, {@code /gamemode}, etc.) are sent via vanilla {@code sendCommand};
	 * - FFXIV channel prefixes ({@code /s}, {@code /p}, {@code /sh}, {@code /y}, {@code /fc}, {@code /t}, {@code /r})
	 *   or plain messages are routed over {@link MmoNetwork.C2SChatChannelPayload}.
	 */
	public void submitOutgoingChatInput(Minecraft mc, String rawInput) {
		if (mc.player == null || rawInput == null || rawInput.isBlank()) {
			return;
		}
		FfxivChatChannel.ParsedOutgoingChat parsed = FfxivChatChannel.parseOutgoingInput(
			rawInput,
			this.defaultChannel,
			this.lastTellPartner
		);

		mc.gui.hud.getChat().addRecentChat(rawInput);

		if (parsed.isVanillaSlashCommand()) {
			String cmdWithoutSlash = parsed.messageBody().startsWith("/")
				? parsed.messageBody().substring(1)
				: parsed.messageBody();
			mc.player.connection.sendCommand(cmdWithoutSlash);
			return;
		}

		// If the user typed only a channel switch command like "/p" or "/sh" with no body, switch default channel
		if (parsed.messageBody().isEmpty() && parsed.channel().isSelectableAsDefault()) {
			this.defaultChannel = parsed.channel();
			return;
		}

		if (parsed.channel() == FfxivChatChannel.TELL && !parsed.targetPlayer().isEmpty()) {
			this.lastTellPartner = parsed.targetPlayer();
		} else if (parsed.channel().isSelectableAsDefault()) {
			this.defaultChannel = parsed.channel();
		}

		if (parsed.messageBody().isEmpty()) {
			return;
		}

		if (ClientPlayNetworking.canSend(MmoNetwork.C2SChatChannelPayload.TYPE)) {
			net.xivmmo.debug.MmoDebugState.recordPacket(
				"C2S",
				"C2SChatChannel",
				parsed.channel().name() + ": " + parsed.messageBody()
			);
			ClientPlayNetworking.send(new MmoNetwork.C2SChatChannelPayload(
				parsed.channel().name(),
				parsed.targetPlayer(),
				parsed.messageBody()
			));
		} else {
			mc.player.connection.sendChat(parsed.messageBody());
		}
	}

	/**
	 * Renders the persistent Bottom-Left FFXIV Chat Log Window.
	 * Called both by the HUD layer (when no chat screen is open) and by {@link FfxivChatScreen} (when typing).
	 */
	public void renderChatHud(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		int screenWidth,
		int screenHeight,
		@Nullable String liveInputText,
		int liveCursorPos,
		boolean inputFocused
	) {
		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.CHAT_LOG, screenWidth, screenHeight
		);
		if (!rect.enabled()) {
			return;
		}

		MmoHudManager.pushWidgetTransform(graphics, rect);
		renderLocalChatWindow(graphics, mc, liveInputText, liveCursorPos, inputFocused);
		MmoHudManager.popWidgetTransform(graphics);
	}

	private void renderLocalChatWindow(
		GuiGraphicsExtractor graphics,
		Minecraft mc,
		@Nullable String liveInputText,
		int liveCursorPos,
		boolean inputFocused
	) {
		int w = MmoConfig.HudWidgetId.CHAT_LOG.baseWidth();   // 276
		int h = MmoConfig.HudWidgetId.CHAT_LOG.baseHeight();  // 148
		int logH = 104;
		Font font = mc.font;

		// 1. Message Log Backdrop (Dark translucent charcoal-slate with subtle gold-bronze FFXIV border)
		int logBg = inputFocused ? 0xD2101218 : 0xAA101218;
		graphics.fill(0, 0, w, logH, logBg);
		graphics.outline(0, 0, w, logH, inputFocused ? FfxivTheme.GOLD_RULE : 0x887A6842);
		graphics.fill(1, 1, w - 1, 2, 0x66D8C088);

		// Top-Right FFXIV diagonal resize grip lines
		graphics.fill(w - 7, 2, w - 2, 3, FfxivTheme.GOLD_OUTER);
		graphics.fill(w - 5, 4, w - 2, 5, FfxivTheme.GOLD_OUTER);
		graphics.fill(w - 3, 6, w - 2, 7, FfxivTheme.GOLD_OUTER);

		// 2. Left-Edge Scrollbar Track & Thumb (x = 2..6, y = 4..logH-4)
		int sbX = 2;
		int sbY = 4;
		int sbH = logH - 8;
		graphics.fill(sbX, sbY, sbX + 4, sbY + sbH, 0xB0090A0E);
		graphics.outline(sbX, sbY, 4, sbH, 0xFF282C38);

		// Build wrapped lines for the active filter tab
		float textScale = 0.82F;
		int maxTextPixelW = Math.round((w - 14) / textScale);
		List<WrappedChatLine> wrappedLines = buildWrappedLines(font, maxTextPixelW, MmoConfig.get().showChatTimestamps);

		int visibleLineSlots = 11;
		int maxScroll = Math.max(0, wrappedLines.size() - visibleLineSlots);
		this.scrollOffsetLines = Mth.clamp(this.scrollOffsetLines, 0, maxScroll);

		// Scrollbar thumb position
		int thumbH = Math.max(10, Math.round(sbH * Math.min(1.0F, (float) visibleLineSlots / Math.max(1, wrappedLines.size()))));
		float scrollFrac = maxScroll > 0 ? (float) (maxScroll - this.scrollOffsetLines) / maxScroll : 1.0F;
		int thumbY = sbY + Math.round((sbH - thumbH) * scrollFrac);
		graphics.fill(sbX + 1, thumbY, sbX + 3, thumbY + thumbH, FfxivTheme.GOLD_BRIGHT);

		// 3. Render Visible Chat Lines (bottom-aligned inside log area)
		int endIdx = wrappedLines.size() - this.scrollOffsetLines;
		int startIdx = Math.max(0, endIdx - visibleLineSlots);
		int count = endIdx - startIdx;
		int lineStepY = 9;
		int firstLineY = logH - 4 - count * lineStepY;

		for (int i = 0; i < count; i++) {
			WrappedChatLine wl = wrappedLines.get(startIdx + i);
			int ly = firstLineY + i * lineStepY;
			graphics.pose().pushMatrix();
			graphics.pose().translate(9.0F, ly);
			graphics.pose().scale(textScale, textScale);
			drawFormattedLineWithAutoTranslate(graphics, font, wl.lineText(), 0, 0, wl.color());
			graphics.pose().popMatrix();
		}

		// 4. Channel Selector Pill ("Say" speech bubble badge right above the input bar, matching the screenshot)
		int chanPillX = 8;
		int chanPillY = logH + 1;
		String chanLabel = this.defaultChannel.displayName();
		int chanPillW = Math.max(34, font.width(chanLabel) + 16);
		graphics.fill(chanPillX, chanPillY, chanPillX + chanPillW, chanPillY + 10, 0xEE181B24);
		graphics.outline(chanPillX, chanPillY, chanPillW, 10, FfxivTheme.GOLD_OUTER);
		// Speech bubble mini icon
		graphics.fill(chanPillX + 3, chanPillY + 3, chanPillX + 8, chanPillY + 7, this.defaultChannel.textColor());
		graphics.fill(chanPillX + 4, chanPillY + 7, chanPillX + 6, chanPillY + 8, this.defaultChannel.textColor());
		graphics.pose().pushMatrix();
		graphics.pose().translate(chanPillX + 11.0F, chanPillY + 2.0F);
		graphics.pose().scale(0.78F, 0.78F);
		graphics.text(font, chanLabel, 0, 0, this.defaultChannel.textColor(), true);
		graphics.pose().popMatrix();

		// 5. Recessed FFXIV Input Box (y = 115..130) + Right Auto-Translate (◖◗) Button
		int inputX = 0;
		int inputY = logH + 11;
		int inputW = w;
		int inputH = 15;
		graphics.fill(inputX, inputY, inputX + inputW, inputY + inputH, 0xEA0D0F14);
		graphics.outline(
			inputX, inputY, inputW, inputH,
			inputFocused ? FfxivTheme.GOLD_BRIGHT : 0xFF4C5264
		);

		// Right-side Auto-Translate (◖◗) Dictionary Button inside the input pill
		int atBtnW = 18;
		int atBtnX = inputX + inputW - atBtnW - 1;
		int atBtnY = inputY + 1;
		int atBtnH = inputH - 2;
		graphics.fill(atBtnX, atBtnY, atBtnX + atBtnW, atBtnY + atBtnH, 0xFF1A1E28);
		graphics.outline(atBtnX, atBtnY, atBtnW, atBtnH, FfxivTheme.GOLD_OUTER);
		// Green left curve + Red right curve (iconic FFXIV Auto-Translate emblem)
		graphics.fill(atBtnX + 3, atBtnY + 3, atBtnX + 8, atBtnY + atBtnH - 3, 0xFF2EE66B);
		graphics.fill(atBtnX + 10, atBtnY + 3, atBtnX + 15, atBtnY + atBtnH - 3, 0xFFFF4D4D);
		graphics.fill(atBtnX + 5, atBtnY + 5, atBtnX + 13, atBtnY + atBtnH - 5, 0xFF141820);

		// Input text or placeholder
		int maxInputDisplayW = inputW - atBtnW - 10;
		if (inputFocused && liveInputText != null) {
			String beforeCursor = liveInputText.substring(0, Mth.clamp(liveCursorPos, 0, liveInputText.length()));
			String afterCursor = liveInputText.substring(Mth.clamp(liveCursorPos, 0, liveInputText.length()));
			String blink = (System.currentTimeMillis() / 450L) % 2L == 0L ? "|" : ":";
			String combined = beforeCursor + blink + afterCursor;
			String clipped = font.plainSubstrByWidth(
				combined.length() > 36 && font.width(combined) > maxInputDisplayW
					? combined.substring(Math.max(0, combined.length() - 38))
					: combined,
				maxInputDisplayW
			);
			graphics.text(font, clipped, inputX + 5, inputY + 4, this.defaultChannel.textColor(), true);
		} else {
			String prompt = "/" + this.defaultChannel.slashPrefix().replace("/", "") + "  Click or press Enter to chat...";
			graphics.pose().pushMatrix();
			graphics.pose().translate(inputX + 5.0F, inputY + 4.5F);
			graphics.pose().scale(0.80F, 0.80F);
			graphics.text(font, prompt, 0, 0, FfxivTheme.TEXT_SILVER, true);
			graphics.pose().popMatrix();
		}

		// 6. Bottom Filter Tabs Bar: [⚙] | [General] | [Battle] | [Event] | [+]
		int tabBarY = logH + 28;
		int tabBarH = 15;

		// Left Gear (⚙) Config / Timestamp Toggle Button
		graphics.fill(0, tabBarY, 15, tabBarY + tabBarH, 0xEE181B24);
		graphics.outline(0, tabBarY, 15, tabBarH, FfxivTheme.GOLD_OUTER);
		graphics.centeredText(
			font,
			"⚙",
			7,
			tabBarY + 3,
			MmoConfig.get().showChatTimestamps ? FfxivTheme.TEXT_GOLD : FfxivTheme.TEXT_SILVER
		);

		// 4 Filter Tabs: General, Battle, Event, +
		FfxivChatChannel.FilterTab[] tabs = FfxivChatChannel.FilterTab.values();
		int curTabX = 18;
		for (FfxivChatChannel.FilterTab tab : tabs) {
			boolean active = tab == this.activeTab;
			int tabW = tab == FfxivChatChannel.FilterTab.ALL ? 22 : 52;
			int bg = active ? 0xF2262A38 : 0xC814161E;
			int border = active ? FfxivTheme.GOLD_BRIGHT : FfxivTheme.GOLD_OUTER;
			graphics.fill(curTabX, tabBarY, curTabX + tabW, tabBarY + tabBarH, bg);
			graphics.outline(curTabX, tabBarY, tabW, tabBarH, border);
			if (active) {
				graphics.fill(curTabX + 1, tabBarY + 1, curTabX + tabW - 1, tabBarY + 2, FfxivTheme.GOLD_BRIGHT);
			}

			int textColor = active ? FfxivTheme.TEXT_CREAM : FfxivTheme.TEXT_SILVER;
			graphics.centeredText(font, tab.label(), curTabX + tabW / 2, tabBarY + 3, textColor);

			int unread = getUnreadCount(tab);
			if (!active && unread > 0) {
				graphics.fill(curTabX + tabW - 5, tabBarY + 2, curTabX + tabW - 2, tabBarY + 5, 0xFFFFCC44);
			}
			curTabX += tabW + 3;
		}
	}

	/**
	 * Renders a single line of text, highlighting any {@code ◖...◗} FFXIV Auto-Translate phrases
	 * with a green opening arrow badge and red closing arrow badge.
	 */
	private static void drawFormattedLineWithAutoTranslate(
		GuiGraphicsExtractor graphics,
		Font font,
		String line,
		int x,
		int y,
		int baseColor
	) {
		int openIdx = line.indexOf(FfxivChatChannel.AUTO_TRANSLATE_OPEN);
		if (openIdx < 0) {
			graphics.text(font, line, x, y, baseColor, true);
			return;
		}

		int curX = x;
		int i = 0;
		while (i < line.length()) {
			int open = line.indexOf(FfxivChatChannel.AUTO_TRANSLATE_OPEN, i);
			if (open < 0) {
				String tail = line.substring(i);
				graphics.text(font, tail, curX, y, baseColor, true);
				break;
			}
			int close = line.indexOf(FfxivChatChannel.AUTO_TRANSLATE_CLOSE, open + 1);
			if (close < 0) {
				String tail = line.substring(i);
				graphics.text(font, tail, curX, y, baseColor, true);
				break;
			}
			if (open > i) {
				String prefix = line.substring(i, open);
				graphics.text(font, prefix, curX, y, baseColor, true);
				curX += font.width(prefix);
			}
			String phrase = line.substring(open + 1, close);
			int phraseW = font.width(phrase);
			// Green left arrow badge
			graphics.fill(curX, y, curX + 4, y + 8, 0xFF2EE66B);
			graphics.text(font, "‹", curX + 1, y - 1, 0xFF082210, false);
			curX += 5;
			// Phrase background & crisp white text
			graphics.fill(curX - 1, y, curX + phraseW + 1, y + 8, 0xAA18202C);
			graphics.text(font, phrase, curX, y, 0xFFFFFFFF, true);
			curX += phraseW + 1;
			// Red right arrow badge
			graphics.fill(curX, y, curX + 4, y + 8, 0xFFFF4D4D);
			graphics.text(font, "›", curX + 1, y - 1, 0xFF280606, false);
			curX += 5;

			i = close + 1;
		}
	}

	private synchronized List<WrappedChatLine> buildWrappedLines(Font font, int maxPixelWidth, boolean includeTimestamp) {
		List<WrappedChatLine> out = new ArrayList<>();
		for (ChatEntry entry : this.entries) {
			if (!this.activeTab.accepts(entry.channel())) {
				continue;
			}
			String fullText = includeTimestamp ? "[" + entry.timestamp() + "] " + entry.text() : entry.text();
			int color = entry.channel().textColor();
			List<String> wrapped = wrapStringByWidth(font, fullText, maxPixelWidth);
			for (int i = 0; i < wrapped.size(); i++) {
				out.add(new WrappedChatLine(entry, wrapped.get(i), color, i == 0));
			}
		}
		return out;
	}

	private static List<String> wrapStringByWidth(Font font, String text, int maxWidth) {
		if (text == null || text.isEmpty()) {
			return List.of("");
		}
		if (font.width(text) <= maxWidth) {
			return List.of(text);
		}
		List<String> lines = new ArrayList<>(3);
		String remaining = text;
		while (!remaining.isEmpty()) {
			String fit = font.plainSubstrByWidth(remaining, maxWidth);
			if (fit.isEmpty()) {
				break;
			}
			if (fit.length() < remaining.length()) {
				int lastSpace = fit.lastIndexOf(' ');
				if (lastSpace > fit.length() / 2) {
					fit = fit.substring(0, lastSpace);
				}
			}
			lines.add(fit);
			remaining = remaining.substring(fit.length()).stripLeading();
			if (!remaining.isEmpty()) {
				remaining = "  " + remaining;
			}
		}
		return lines;
	}

	/**
	 * Handles left-clicking the FFXIV Chat Log HUD widget in Free-Cursor MMO Mode.
	 * Returns {@code true} if the click was inside the Chat Log widget (preventing 3D world click-through).
	 */
	public boolean tryClickChatHud(double guiX, double guiY, int screenWidth, int screenHeight) {
		if (!MmoInputHandler.isMmoModeActive() || !MmoConfig.get().useFfxivChatInMmoMode) {
			return false;
		}
		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.CHAT_LOG, screenWidth, screenHeight
		);
		if (!rect.enabled() || !rect.contains(guiX, guiY)) {
			return false;
		}

		double localX = (guiX - rect.x()) / rect.scale();
		double localY = (guiY - rect.y()) / rect.scale();
		int w = MmoConfig.HudWidgetId.CHAT_LOG.baseWidth();
		int logH = 104;

		Minecraft mc = Minecraft.getInstance();

		// 1. Check Bottom Filter Tabs Bar (y = 132..148)
		int tabBarY = logH + 28;
		if (localY >= tabBarY && localY <= tabBarY + 16) {
			// Gear button (x = 0..15)
			if (localX >= 0 && localX <= 15) {
				if (mc.hasShiftDown()) {
					clearEntries();
				} else {
					MmoConfig cfg = MmoConfig.get();
					cfg.showChatTimestamps = !cfg.showChatTimestamps;
					cfg.save();
				}
				return true;
			}

			FfxivChatChannel.FilterTab[] tabs = FfxivChatChannel.FilterTab.values();
			int curTabX = 18;
			for (FfxivChatChannel.FilterTab tab : tabs) {
				int tabW = tab == FfxivChatChannel.FilterTab.ALL ? 22 : 52;
				if (localX >= curTabX && localX <= curTabX + tabW) {
					setActiveTab(tab);
					return true;
				}
				curTabX += tabW + 3;
			}
			return true;
		}

		// 2. Check Channel Selector Pill ("Say" badge at y = 105..115)
		if (localY >= logH && localY < logH + 11 && localX >= 6 && localX <= 70) {
			cycleDefaultChannel();
			return true;
		}

		// 3. Check Input Box + Auto-Translate Button (y = 115..131)
		int inputY = logH + 11;
		if (localY >= inputY && localY <= inputY + 16) {
			boolean clickedAutoTranslate = localX >= w - 20;
			if (mc.gui.screen() == null) {
				FfxivChatScreen chatScreen = new FfxivChatScreen("", false);
				if (clickedAutoTranslate) {
					chatScreen.setAutoTranslateMenuOpen(true);
				}
				mc.gui.setScreen(chatScreen);
			}
			return true;
		}

		// 4. Check Left Scrollbar (x = 0..8, y = 0..logH)
		if (localX >= 0 && localX <= 8 && localY >= 0 && localY <= logH) {
			if (localY < logH * 0.5) {
				scrollLines(3);
			} else {
				scrollLines(-3);
			}
			return true;
		}

		return true;
	}

	/**
	 * Scrolls the FFXIV Chat Log when the mouse wheel is scrolled while hovering over the chat widget.
	 */
	public boolean tryScrollChatHud(double guiX, double guiY, int screenWidth, int screenHeight, double scrollDeltaY) {
		if (!MmoInputHandler.isMmoModeActive() || !MmoConfig.get().useFfxivChatInMmoMode) {
			return false;
		}
		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.CHAT_LOG, screenWidth, screenHeight
		);
		if (!rect.enabled() || !rect.contains(guiX, guiY)) {
			return false;
		}
		int lines = scrollDeltaY > 0 ? 2 : (scrollDeltaY < 0 ? -2 : 0);
		if (lines != 0) {
			scrollLines(lines);
		}
		return true;
	}
}
