package net.xivmmo.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.xivmmo.chat.FfxivChatChannel;
import net.xivmmo.client.chat.FfxivChatManager;
import net.xivmmo.client.hud.FfxivTheme;
import net.xivmmo.client.hud.MmoHudManager;
import net.xivmmo.client.input.MmoInputHandler;
import net.xivmmo.config.MmoConfig;

import java.util.List;

/**
 * FFXIV-styled Chat Input Screen that replaces vanilla's full-width bottom black input bar in MMO Mode
 * with an in-place input box inside the bottom-left FFXIV Chat Log window, complete with:
 * <ul>
 *   <li>Clickable {@code General} / {@code Battle} / {@code Event} / {@code +} filter tabs</li>
 *   <li>Channel selector pill ({@code Say}, {@code Party}, {@code Shout}, {@code Yell}, {@code FC})</li>
 *   <li>Clickable FFXIV Auto-Translate ({@code ◖◗}) dictionary phrase picker popup</li>
 *   <li>Slash-command and channel prefix hints ({@code /s}, {@code /p}, {@code /sh}, {@code /y}, {@code /fc}, {@code /t}, {@code /r})</li>
 * </ul>
 */
public class FfxivChatScreen extends ChatScreen {
	private boolean autoTranslateMenuOpen = false;

	public FfxivChatScreen(String initial, boolean isDraft) {
		super(initial, isDraft);
	}

	public void setAutoTranslateMenuOpen(boolean open) {
		this.autoTranslateMenuOpen = open;
	}

	@Override
	protected void init() {
		super.init();
		if (this.input != null && isFfxivChatActive()) {
			MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(
				MmoConfig.HudWidgetId.CHAT_LOG, this.width, this.height
			);
			int inputX = rect.x() + Math.round(5 * rect.scale());
			int inputY = rect.y() + Math.round(117 * rect.scale());
			int inputW = Math.max(80, rect.width() - Math.round(28 * rect.scale()));
			this.input.setX(inputX);
			this.input.setY(inputY);
			this.input.setWidth(inputW);
		}
	}

	private boolean isFfxivChatActive() {
		return MmoInputHandler.isMmoModeActive() && MmoConfig.get().useFfxivChatInMmoMode;
	}

	@Override
	public void handleChatInput(String text, boolean addToHistory) {
		if (!isFfxivChatActive()) {
			super.handleChatInput(text, addToHistory);
			return;
		}
		String normalized = this.normalizeChatMessage(text);
		if (normalized.isEmpty()) {
			return;
		}
		FfxivChatManager.get().submitOutgoingChatInput(this.minecraft, normalized);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (isFfxivChatActive() && this.autoTranslateMenuOpen && event.key() == 256) { // Escape closes popup first
			this.autoTranslateMenuOpen = false;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (isFfxivChatActive()) {
			int lines = scrollY > 0 ? 2 : (scrollY < 0 ? -2 : 0);
			if (lines != 0) {
				FfxivChatManager.get().scrollLines(lines);
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!isFfxivChatActive()) {
			return super.mouseClicked(event, doubleClick);
		}

		double mx = event.x();
		double my = event.y();

		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.CHAT_LOG, this.width, this.height
		);

		// 1. Check if Auto-Translate popup is open and clicked
		if (this.autoTranslateMenuOpen) {
			int popupW = 196;
			int popupH = FfxivChatChannel.AUTO_TRANSLATE_PHRASES.size() * 11 + 18;
			int popupX = Math.clamp(rect.x() + rect.width() - popupW, 4, Math.max(4, this.width - popupW - 4));
			int popupY = Math.max(4, rect.y() - popupH + Math.round(112 * rect.scale()));

			if (mx >= popupX && mx <= popupX + popupW && my >= popupY && my <= popupY + popupH) {
				int relY = (int) (my - (popupY + 15));
				int idx = relY / 11;
				List<String> phrases = FfxivChatChannel.AUTO_TRANSLATE_PHRASES;
				if (relY >= 0 && idx >= 0 && idx < phrases.size() && this.input != null) {
					String formatted = FfxivChatChannel.formatAutoTranslatePhrase(phrases.get(idx)) + " ";
					this.input.insertText(formatted);
					this.autoTranslateMenuOpen = false;
				}
				return true;
			}
		}

		// 2. Check clicks inside the FFXIV Chat Log widget (tabs, channel pill, gear, Auto-Translate button)
		if (rect.enabled() && rect.contains(mx, my)) {
			double localX = (mx - rect.x()) / rect.scale();
			double localY = (my - rect.y()) / rect.scale();
			int w = MmoConfig.HudWidgetId.CHAT_LOG.baseWidth();
			int logH = 104;

			// Auto-Translate (◖◗) button on right end of input pill
			int inputY = logH + 11;
			if (localY >= inputY && localY <= inputY + 16 && localX >= w - 20) {
				this.autoTranslateMenuOpen = !this.autoTranslateMenuOpen;
				return true;
			}

			// Channel selector pill or bottom filter tabs
			if (localY >= logH && (localY < inputY || localY > inputY + 16)) {
				FfxivChatManager.get().tryClickChatHud(mx, my, this.width, this.height);
				return true;
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (!isFfxivChatActive()) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}

		String val = this.input != null ? this.input.getValue() : "";
		int cursor = this.input != null ? this.input.getCursorPosition() : 0;

		// 1. Render the bottom-left FFXIV Chat Window with live input text & cursor
		FfxivChatManager.get().renderChatHud(
			graphics,
			this.minecraft,
			this.width,
			this.height,
			val,
			cursor,
			true
		);

		MmoHudManager.WidgetRect rect = MmoHudManager.getWidgetRect(
			MmoConfig.HudWidgetId.CHAT_LOG, this.width, this.height
		);

		// 2. Channel & Command Helper Strip above the Channel Pill when typing
		int helperY = Math.max(4, rect.y() - 14);
		graphics.fill(rect.x(), helperY, rect.x() + rect.width(), helperY + 12, 0xD812141C);
		graphics.outline(rect.x(), helperY, rect.width(), 12, FfxivTheme.GOLD_OUTER);
		graphics.pose().pushMatrix();
		graphics.pose().translate(rect.x() + 4.0F, helperY + 2.5F);
		graphics.pose().scale(0.75F, 0.75F);
		graphics.text(
			this.font,
			"Channels: /s Say  /p Party  /sh Shout  /y Yell  /fc FC  /t <name> Tell  {phrase} Auto-Translate",
			0,
			0,
			FfxivTheme.TEXT_GOLD,
			true
		);
		graphics.pose().popMatrix();

		// 3. Render Auto-Translate (◖◗) Dictionary Popup if open
		if (this.autoTranslateMenuOpen) {
			renderAutoTranslatePopup(graphics, rect, mouseX, mouseY);
		}
	}

	private void renderAutoTranslatePopup(
		GuiGraphicsExtractor graphics,
		MmoHudManager.WidgetRect rect,
		int mouseX,
		int mouseY
	) {
		List<String> phrases = FfxivChatChannel.AUTO_TRANSLATE_PHRASES;
		int popupW = 196;
		int popupH = phrases.size() * 11 + 18;
		int popupX = Math.clamp(rect.x() + rect.width() - popupW, 4, Math.max(4, this.width - popupW - 4));
		int popupY = Math.max(4, rect.y() - popupH + Math.round(112 * rect.scale()));

		graphics.fill(popupX, popupY, popupX + popupW, popupY + popupH, 0xF2141720);
		graphics.outline(popupX, popupY, popupW, popupY + popupH - popupY, FfxivTheme.GOLD_BRIGHT);
		graphics.fill(popupX + 1, popupY + 1, popupX + popupW - 1, popupY + 13, 0xFF222634);
		graphics.text(this.font, "Auto-Translate Dictionary", popupX + 6, popupY + 3, FfxivTheme.TEXT_GOLD, true);

		for (int i = 0; i < phrases.size(); i++) {
			int rowY = popupY + 15 + i * 11;
			boolean hovered = mouseX >= popupX + 2 && mouseX <= popupX + popupW - 2 && mouseY >= rowY && mouseY < rowY + 11;
			if (hovered) {
				graphics.fill(popupX + 2, rowY, popupX + popupW - 2, rowY + 10, 0x883A4460);
			}
			// Green ‹ and Red › badges
			graphics.fill(popupX + 5, rowY + 1, popupX + 9, rowY + 9, 0xFF2EE66B);
			graphics.fill(popupX + popupW - 9, rowY + 1, popupX + popupW - 5, rowY + 9, 0xFFFF4D4D);
			graphics.pose().pushMatrix();
			graphics.pose().translate(popupX + 12.0F, rowY + 1.5F);
			graphics.pose().scale(0.80F, 0.80F);
			graphics.text(
				this.font,
				phrases.get(i),
				0,
				0,
				hovered ? FfxivTheme.TEXT_GOLD : FfxivTheme.TEXT_CREAM,
				true
			);
			graphics.pose().popMatrix();
		}
	}
}
