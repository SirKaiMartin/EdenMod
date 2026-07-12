package tel.eden.mod.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.chat.ChatEmoteFormatter;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Shadow
	protected EditBox input;

	@Inject(method = "init", at = @At("TAIL"))
	private void edenmod$addEmoteFormatter(CallbackInfo ci) {
		input.addFormatter((text, cursor) -> {
			FormattedCharSequence formatted = ChatEmoteFormatter.format(text);
			return formatted;
		});
	}
}
