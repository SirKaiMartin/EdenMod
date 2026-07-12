package tel.eden.mod.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.chat.ChatEmoteFormatter;
import tel.eden.mod.chat.EmoteRegistry;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Shadow
	protected EditBox input;

	@Unique
	private static final int EDENMOD_MAX_EMOTE_SUGGESTIONS = 10;

	@Unique
	private final List<String> edenmod$emoteSuggestions = new ArrayList<>();

	@Unique
	private final List<String> edenmod$autocompletePool = new ArrayList<>();

	@Unique
	private int edenmod$selectedSuggestion;

	@Unique
	private int edenmod$suggestionScroll;

	@Unique
	private int edenmod$emoteTokenStart = -1;

	@Unique
	private int edenmod$emoteTokenEnd = -1;

	@Unique
	private String edenmod$suggestionSeed = "";

	@Inject(method = "init", at = @At("TAIL"))
	private void edenmod$addEmoteFormatter(CallbackInfo ci) {
		input.addFormatter((text, cursor) -> {
			FormattedCharSequence formatted = ChatEmoteFormatter.format(text);
			return formatted;
		});
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void edenmod$handleAutocompleteKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		edenmod$refreshEmoteSuggestions();
		if (edenmod$emoteSuggestions.isEmpty()) {
			return;
		}
		int key = event.key();
		if (key == GLFW.GLFW_KEY_TAB) {
			edenmod$applySuggestion(edenmod$selectedSuggestion);
			cir.setReturnValue(true);
			return;
		}
		if (key == GLFW.GLFW_KEY_UP) {
			edenmod$selectedSuggestion = Math.floorMod(edenmod$selectedSuggestion - 1, edenmod$emoteSuggestions.size());
			edenmod$ensureSuggestionVisible();
			cir.setReturnValue(true);
			return;
		}
		if (key == GLFW.GLFW_KEY_DOWN) {
			edenmod$selectedSuggestion = Math.floorMod(edenmod$selectedSuggestion + 1, edenmod$emoteSuggestions.size());
			edenmod$ensureSuggestionVisible();
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void edenmod$handleAutocompleteClick(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {
		edenmod$refreshEmoteSuggestions();
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || edenmod$emoteSuggestions.isEmpty()) {
			return;
		}
		int hit = edenmod$suggestionIndexAt(event.x(), event.y());
		if (hit >= 0) {
			edenmod$applySuggestion(hit);
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void edenmod$renderAutocomplete(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		edenmod$refreshEmoteSuggestions();
		if (!edenmod$emoteSuggestions.isEmpty()) {
			edenmod$renderSuggestions(graphics);
		}
	}

	@Unique
	private void edenmod$refreshEmoteSuggestions() {
		edenmod$emoteSuggestions.clear();
		edenmod$autocompletePool.clear();
		edenmod$emoteTokenStart = -1;
		edenmod$emoteTokenEnd = -1;
		String value = input.getValue();
		if (value.startsWith("/")) {
			return;
		}
		EmoteToken token = edenmod$findActiveEmoteToken(value, input.getCursorPosition());
		if (token == null) {
			edenmod$resetSuggestionSession();
			return;
		}
		if (!token.query().equals(edenmod$suggestionSeed)) {
			edenmod$selectedSuggestion = 0;
			edenmod$suggestionScroll = 0;
		}
		edenmod$suggestionSeed = token.query();
		String lowered = token.query().toLowerCase(java.util.Locale.ROOT);
		edenmod$autocompletePool.addAll(EmoteRegistry.shortcodes());
		List<String> prefixMatches = new ArrayList<>();
		List<String> containsMatches = new ArrayList<>();
		for (String shortcode : edenmod$autocompletePool) {
			String candidate = shortcode.toLowerCase(java.util.Locale.ROOT);
			if (candidate.startsWith(lowered)) {
				prefixMatches.add(shortcode);
			} else if (lowered.isEmpty() || candidate.contains(lowered)) {
				containsMatches.add(shortcode);
			}
		}
		edenmod$emoteSuggestions.addAll(prefixMatches);
		edenmod$emoteSuggestions.addAll(containsMatches);
		if (edenmod$emoteSuggestions.isEmpty()) {
			edenmod$resetSuggestionSession();
			return;
		}
		edenmod$emoteTokenStart = token.start();
		edenmod$emoteTokenEnd = token.end();
		edenmod$selectedSuggestion = Math.max(0, Math.min(edenmod$selectedSuggestion, edenmod$emoteSuggestions.size() - 1));
		edenmod$ensureSuggestionVisible();
	}

	@Unique
	private EmoteToken edenmod$findActiveEmoteToken(String value, int cursor) {
		if (cursor < 0 || cursor > value.length()) {
			return null;
		}
		if (edenmod$isCursorAfterCompleteToken(value, cursor)) {
			return null;
		}
		int tokenStart = cursor;
		while (tokenStart > 0 && edenmod$isEmoteNameChar(value.charAt(tokenStart - 1))) {
			tokenStart--;
		}
		if (tokenStart <= 0 || value.charAt(tokenStart - 1) != ':') {
			return null;
		}
		int startColon = tokenStart - 1;
		if (!edenmod$isTokenBoundary(value, startColon - 1)) {
			return null;
		}
		int tokenEnd = cursor;
		while (tokenEnd < value.length() && edenmod$isEmoteNameChar(value.charAt(tokenEnd))) {
			tokenEnd++;
		}
		if (tokenEnd < value.length() && value.charAt(tokenEnd) == ':') {
			return null;
		}
		String query = value.substring(startColon + 1, cursor);
		return query.chars().allMatch(c -> edenmod$isEmoteNameChar((char) c)) ? new EmoteToken(startColon, tokenEnd, query) : null;
	}

	@Unique
	private boolean edenmod$isCursorAfterCompleteToken(String value, int cursor) {
		if (cursor <= 0 || cursor > value.length() || value.charAt(cursor - 1) != ':') {
			return false;
		}
		int closingColon = cursor - 1;
		int nameStart = closingColon;
		while (nameStart > 0 && edenmod$isEmoteNameChar(value.charAt(nameStart - 1))) {
			nameStart--;
		}
		if (nameStart == closingColon) {
			return false;
		}
		if (nameStart <= 0 || value.charAt(nameStart - 1) != ':') {
			return false;
		}
		return edenmod$isTokenBoundary(value, nameStart - 2);
	}

	@Unique
	private static boolean edenmod$isTokenBoundary(String value, int index) {
		if (index < 0) {
			return true;
		}
		char c = value.charAt(index);
		return c != ':' && !edenmod$isEmoteNameChar(c);
	}

	@Unique
	private void edenmod$applySuggestion(int index) {
		if (index < 0 || index >= edenmod$emoteSuggestions.size() || edenmod$emoteTokenStart < 0) {
			return;
		}
		String suggestion = ":" + edenmod$emoteSuggestions.get(index) + ":";
		String value = input.getValue();
		String updated = value.substring(0, edenmod$emoteTokenStart) + suggestion + value.substring(edenmod$emoteTokenEnd);
		input.setValue(updated);
		input.setCursorPosition(edenmod$emoteTokenStart + suggestion.length());
		edenmod$resetSuggestionSession();
		edenmod$refreshEmoteSuggestions();
	}

	@Unique
	private void edenmod$renderSuggestions(GuiGraphics graphics) {
		Font font = Minecraft.getInstance().font;
		int rowHeight = Math.max(font.lineHeight + 4, 14);
		int width = edenmod$suggestionWidth(font);
		int x = input.getX();
		int visibleCount = Math.min(EDENMOD_MAX_EMOTE_SUGGESTIONS, edenmod$emoteSuggestions.size());
		int y = input.getY() - (visibleCount * rowHeight) - 4;
		graphics.fill(x, y, x + width, y + visibleCount * rowHeight, 0xE0101010);
		for (int row = 0; row < visibleCount; row++) {
			int index = edenmod$suggestionScroll + row;
			int rowY = y + row * rowHeight;
			if (index == edenmod$selectedSuggestion) {
				graphics.fill(x + 1, rowY + 1, x + width - 1, rowY + rowHeight - 1, 0xCC2A5A78);
			}
			String suggestion = edenmod$emoteSuggestions.get(index);
			FormattedCharSequence preview = ChatEmoteFormatter.previewComponent(":" + suggestion + ":").getVisualOrderText();
			graphics.drawString(font, preview, x + 4, rowY + 2, 0xFFFFFFFF);
			graphics.drawString(font, ":" + suggestion + ":", x + 20, rowY + 2, 0xFFFFFFFF);
		}
	}

	@Unique
	private int edenmod$suggestionWidth(Font font) {
		int width = 64;
		for (String suggestion : edenmod$emoteSuggestions) {
			width = Math.max(width, font.width(":" + suggestion + ":") + 28);
		}
		return width;
	}

	@Unique
	private int edenmod$suggestionIndexAt(double mouseX, double mouseY) {
		Font font = Minecraft.getInstance().font;
		int rowHeight = Math.max(font.lineHeight + 4, 14);
		int width = edenmod$suggestionWidth(font);
		int x = input.getX();
		int visibleCount = Math.min(EDENMOD_MAX_EMOTE_SUGGESTIONS, edenmod$emoteSuggestions.size());
		int y = input.getY() - (visibleCount * rowHeight) - 4;
		if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + visibleCount * rowHeight) {
			return -1;
		}
		int row = (int) ((mouseY - y) / rowHeight);
		int index = edenmod$suggestionScroll + row;
		return row >= 0 && row < visibleCount && index < edenmod$emoteSuggestions.size() ? index : -1;
	}

	@Unique
	private void edenmod$ensureSuggestionVisible() {
		int maxScroll = Math.max(0, edenmod$emoteSuggestions.size() - EDENMOD_MAX_EMOTE_SUGGESTIONS);
		if (edenmod$selectedSuggestion < edenmod$suggestionScroll) {
			edenmod$suggestionScroll = edenmod$selectedSuggestion;
		} else if (edenmod$selectedSuggestion >= edenmod$suggestionScroll + EDENMOD_MAX_EMOTE_SUGGESTIONS) {
			edenmod$suggestionScroll = edenmod$selectedSuggestion - EDENMOD_MAX_EMOTE_SUGGESTIONS + 1;
		}
		edenmod$suggestionScroll = Math.max(0, Math.min(maxScroll, edenmod$suggestionScroll));
	}

	@Unique
	private void edenmod$resetSuggestionSession() {
		edenmod$selectedSuggestion = 0;
		edenmod$suggestionScroll = 0;
		edenmod$suggestionSeed = "";
	}

	@Unique
	private static boolean edenmod$isEmoteNameChar(char c) {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '+' || c == '-';
	}

	@Unique
	private record EmoteToken(int start, int end, String query) {
	}
}
