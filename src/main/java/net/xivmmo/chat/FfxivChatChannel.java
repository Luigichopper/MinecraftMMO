package net.xivmmo.chat;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Defines FFXIV chat channels, filter tabs ({@code General}, {@code Battle}, {@code Event}, {@code +}),
 * slash-command channel prefixes ({@code /s}, {@code /p}, {@code /sh}, {@code /y}, {@code /fc}, {@code /t}, {@code /r}),
 * incoming message categorization, and FFXIV Auto-Translate dictionary phrases ({@code ◖...◗}).
 */
public enum FfxivChatChannel {
	SAY("Say", "/s", 0xFFF4F4F0, true),
	PARTY("Party", "/p", 0xFF64D8FF, true),
	SHOUT("Shout", "/sh", 0xFFFF9854, true),
	YELL("Yell", "/y", 0xFFFFE854, true),
	TELL("Tell", "/t", 0xFFFF96DC, false),
	FREE_COMPANY("FC", "/fc", 0xFFABD6F5, true),
	BATTLE("Battle", "", 0xFFFFD888, false),
	EVENT("Event", "", 0xFFDCC8FF, false),
	SYSTEM("System", "", 0xFFCCCCCC, false);

	public static final char AUTO_TRANSLATE_OPEN = '◖';
	public static final char AUTO_TRANSLATE_CLOSE = '◗';

	public static final List<String> AUTO_TRANSLATE_PHRASES = List.of(
		"Hello!",
		"Thank you!",
		"Good game!",
		"Congratulations!",
		"Looking for party.",
		"Looking for members.",
		"Tank",
		"Healer",
		"DPS",
		"Watch the telegraph!",
		"Need healing!",
		"Limit Surge ready!",
		"Pulling boss soon!",
		"Let's do our best!"
	);

	private static final List<FfxivChatChannel> CYCLABLE_INPUT_CHANNELS = List.of(
		SAY, PARTY, SHOUT, YELL, FREE_COMPANY
	);

	private final String displayName;
	private final String slashPrefix;
	private final int textColor;
	private final boolean selectableAsDefault;

	FfxivChatChannel(String displayName, String slashPrefix, int textColor, boolean selectableAsDefault) {
		this.displayName = displayName;
		this.slashPrefix = slashPrefix;
		this.textColor = textColor;
		this.selectableAsDefault = selectableAsDefault;
	}

	public String displayName() {
		return this.displayName;
	}

	public String slashPrefix() {
		return this.slashPrefix;
	}

	public int textColor() {
		return this.textColor;
	}

	public boolean isSelectableAsDefault() {
		return this.selectableAsDefault;
	}

	public FfxivChatChannel nextSelectableChannel() {
		int idx = CYCLABLE_INPUT_CHANNELS.indexOf(this);
		if (idx < 0) {
			return SAY;
		}
		return CYCLABLE_INPUT_CHANNELS.get((idx + 1) % CYCLABLE_INPUT_CHANNELS.size());
	}

	/**
	 * FFXIV Bottom Chat Log Filter Tabs matching the screenshot:
	 * {@code General} | {@code Battle} | {@code Event} | {@code + (All)}.
	 */
	public enum FilterTab {
		GENERAL("General", EnumSet.of(
			FfxivChatChannel.SAY,
			FfxivChatChannel.PARTY,
			FfxivChatChannel.SHOUT,
			FfxivChatChannel.YELL,
			FfxivChatChannel.TELL,
			FfxivChatChannel.FREE_COMPANY,
			FfxivChatChannel.EVENT,
			FfxivChatChannel.SYSTEM
		)),
		BATTLE("Battle", EnumSet.of(
			FfxivChatChannel.BATTLE,
			FfxivChatChannel.EVENT
		)),
		EVENT("Event", EnumSet.of(
			FfxivChatChannel.EVENT,
			FfxivChatChannel.SYSTEM,
			FfxivChatChannel.PARTY,
			FfxivChatChannel.TELL
		)),
		ALL("+", EnumSet.allOf(FfxivChatChannel.class));

		private final String label;
		private final Set<FfxivChatChannel> allowedChannels;

		FilterTab(String label, Set<FfxivChatChannel> allowedChannels) {
			this.label = label;
			this.allowedChannels = allowedChannels;
		}

		public String label() {
			return this.label;
		}

		public boolean accepts(FfxivChatChannel channel) {
			return this.allowedChannels.contains(channel);
		}
	}

	/**
	 * Result of parsing an outgoing player chat input string.
	 */
	public record ParsedOutgoingChat(
		FfxivChatChannel channel,
		String targetPlayer,
		String messageBody,
		boolean isVanillaSlashCommand
	) {
	}

	/**
	 * Expands curly-brace tokens such as {@code {Looking for members.}} into FFXIV Auto-Translate
	 * markers {@code ◖Looking for members.◗}.
	 */
	public static String expandAutoTranslateTokens(String input) {
		if (input == null || input.isEmpty() || input.indexOf('{') < 0) {
			return input == null ? "" : input;
		}
		StringBuilder sb = new StringBuilder(input.length() + 8);
		int i = 0;
		while (i < input.length()) {
			int open = input.indexOf('{', i);
			if (open < 0) {
				sb.append(input, i, input.length());
				break;
			}
			int close = input.indexOf('}', open + 1);
			if (close < 0) {
				sb.append(input, i, input.length());
				break;
			}
			sb.append(input, i, open);
			String inner = input.substring(open + 1, close).trim();
			if (!inner.isEmpty()) {
				String matched = matchDictionaryPhrase(inner);
				sb.append(AUTO_TRANSLATE_OPEN).append(matched).append(AUTO_TRANSLATE_CLOSE);
			} else {
				sb.append("{}");
			}
			i = close + 1;
		}
		return sb.toString();
	}

	private static String matchDictionaryPhrase(String query) {
		String lower = query.toLowerCase(Locale.ROOT);
		for (String phrase : AUTO_TRANSLATE_PHRASES) {
			if (phrase.toLowerCase(Locale.ROOT).equals(lower) || phrase.toLowerCase(Locale.ROOT).startsWith(lower)) {
				return phrase;
			}
		}
		return query;
	}

	/**
	 * Formats an Auto-Translate phrase with the standard FFXIV markers.
	 */
	public static String formatAutoTranslatePhrase(String phrase) {
		return AUTO_TRANSLATE_OPEN + phrase + AUTO_TRANSLATE_CLOSE;
	}

	/**
	 * Parses a raw chat input line into an {@link FfxivChatChannel}, optional Tell target, and message body,
	 * or marks it as a standard Minecraft slash command if it starts with {@code /} and is not a chat channel alias.
	 */
	public static ParsedOutgoingChat parseOutgoingInput(
		String rawInput,
		FfxivChatChannel defaultChannel,
		String lastTellPartner
	) {
		if (rawInput == null) {
			return new ParsedOutgoingChat(defaultChannel, "", "", false);
		}
		String trimmed = rawInput.trim();
		if (trimmed.isEmpty()) {
			return new ParsedOutgoingChat(defaultChannel, "", "", false);
		}

		if (trimmed.startsWith("/")) {
			int firstSpace = trimmed.indexOf(' ');
			String cmd = (firstSpace > 0 ? trimmed.substring(1, firstSpace) : trimmed.substring(1)).toLowerCase(Locale.ROOT);
			String rest = firstSpace > 0 ? trimmed.substring(firstSpace + 1).trim() : "";

			switch (cmd) {
				case "s", "say" -> {
					return new ParsedOutgoingChat(SAY, "", expandAutoTranslateTokens(rest), false);
				}
				case "p", "party" -> {
					return new ParsedOutgoingChat(PARTY, "", expandAutoTranslateTokens(rest), false);
				}
				case "sh", "shout" -> {
					return new ParsedOutgoingChat(SHOUT, "", expandAutoTranslateTokens(rest), false);
				}
				case "y", "yell" -> {
					return new ParsedOutgoingChat(YELL, "", expandAutoTranslateTokens(rest), false);
				}
				case "fc", "freecompany" -> {
					return new ParsedOutgoingChat(FREE_COMPANY, "", expandAutoTranslateTokens(rest), false);
				}
				case "t", "tell", "w" -> {
					int targetSpace = rest.indexOf(' ');
					if (targetSpace > 0) {
						String target = rest.substring(0, targetSpace).trim();
						if (target.startsWith("@")) {
							target = target.substring(1);
						}
						String body = rest.substring(targetSpace + 1).trim();
						return new ParsedOutgoingChat(TELL, target, expandAutoTranslateTokens(body), false);
					}
					return new ParsedOutgoingChat(TELL, rest.replaceFirst("^@", ""), "", false);
				}
				case "r", "reply" -> {
					String target = lastTellPartner != null ? lastTellPartner : "";
					return new ParsedOutgoingChat(TELL, target, expandAutoTranslateTokens(rest), false);
				}
				default -> {
					return new ParsedOutgoingChat(defaultChannel, "", trimmed, true);
				}
			}
		}

		FfxivChatChannel active = defaultChannel != null && defaultChannel.isSelectableAsDefault() ? defaultChannel : SAY;
		return new ParsedOutgoingChat(active, "", expandAutoTranslateTokens(trimmed), false);
	}

	/**
	 * Formats a social chat message according to FFXIV channel conventions:
	 * <ul>
	 *   <li>{@code SAY}: {@code SenderName: message}</li>
	 *   <li>{@code YELL}: {@code SenderName yells: message}</li>
	 *   <li>{@code SHOUT}: {@code SenderName shouts: message}</li>
	 *   <li>{@code PARTY}: {@code (SenderName) message}</li>
	 *   <li>{@code FREE_COMPANY}: {@code [FC]<SenderName> message}</li>
	 *   <li>{@code TELL} (outgoing): {@code >> TargetName: message}</li>
	 *   <li>{@code TELL} (incoming): {@code SenderName >>: message}</li>
	 * </ul>
	 */
	public static String formatSocialMessage(
		FfxivChatChannel channel,
		String senderName,
		String targetName,
		String messageBody,
		boolean outgoingTell
	) {
		return switch (channel) {
			case SAY -> senderName + ": " + messageBody;
			case YELL -> senderName + " yells: " + messageBody;
			case SHOUT -> senderName + " shouts: " + messageBody;
			case PARTY -> "(" + senderName + ") " + messageBody;
			case FREE_COMPANY -> "[FC]<" + senderName + "> " + messageBody;
			case TELL -> outgoingTell
				? ">> " + targetName + ": " + messageBody
				: senderName + " >>: " + messageBody;
			default -> messageBody;
		};
	}

	/**
	 * Categorizes an incoming plain-text system or chat message into the appropriate {@link FfxivChatChannel}
	 * so that combat logs route to {@code Battle}, job/arena/duty logs route to {@code Event}, and social
	 * messages route to their respective social channel.
	 */
	public static FfxivChatChannel classifyIncomingMessage(String plainText) {
		if (plainText == null || plainText.isEmpty()) {
			return SYSTEM;
		}
		String lower = plainText.toLowerCase(Locale.ROOT);

		// 1. Check explicit FFXIV social prefixes
		if (plainText.startsWith("[FC]<")) {
			return FREE_COMPANY;
		}
		if (plainText.startsWith(">> ") || plainText.contains(" >>: ") || lower.contains(" whispers to you:")) {
			return TELL;
		}
		if (plainText.startsWith("(") && plainText.indexOf(')') > 1 && plainText.indexOf(')') < 24) {
			return PARTY;
		}
		if (lower.contains(" shouts: ")) {
			return SHOUT;
		}
		if (lower.contains(" yells: ")) {
			return YELL;
		}

		// 2. Check Battle / Combat log patterns
		if (lower.contains(" exp")
			|| lower.contains("gil.")
			|| lower.contains("damage")
			|| lower.contains("recovers ")
			|| lower.contains("you cast ")
			|| lower.contains("uses ")
			|| lower.contains("takes ")
			|| lower.contains("suffers ")
			|| lower.contains("enmity")
			|| lower.contains("combo")
			|| lower.contains("potency")) {
			return BATTLE;
		}

		// 3. Check Event / Job / Duty / Boss Arena notifications
		if (lower.contains("you change to ")
			|| lower.contains("switched job to ")
			|| lower.contains("soul of the ")
			|| lower.contains("level up")
			|| lower.contains("attained level ")
			|| lower.contains("duty ")
			|| lower.contains("arena ")
			|| lower.contains("boss ")
			|| lower.contains("telegraph")
			|| lower.contains("is now online")) {
			return EVENT;
		}

		// 4. Vanilla "<Player> message" or "Player: message"
		if ((plainText.startsWith("<") && plainText.indexOf('>') > 1 && plainText.indexOf('>') < 24)
			|| (!plainText.startsWith("[") && plainText.indexOf(": ") > 0 && plainText.indexOf(": ") < 24)) {
			return SAY;
		}

		return SYSTEM;
	}
}
