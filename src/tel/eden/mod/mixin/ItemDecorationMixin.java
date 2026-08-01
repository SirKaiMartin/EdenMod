package tel.eden.mod.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.item.CustomItemTextures;

/**
 * Draws the short consumable label ("MR", "SD", …) over an item slot.
 *
 * <p>Hooked on {@code renderItemDecorations} — the method vanilla uses for the stack-count
 * number — because it is the one item hook that carries the slot's screen position, and it
 * runs <em>after</em> the item itself, so the text lands on top rather than under. The
 * four-argument overload delegates to this five-argument one, so this single injection
 * covers every decoration path.
 */
@Mixin(GuiGraphics.class)
public class ItemDecorationMixin {
	@Inject(method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
	private void edenmod$drawConsumableLabel(Font font, ItemStack stack, int x, int y, String countText, CallbackInfo ci) {
		try {
			BridgeConfig config = EdenModClient.instance().config();
			if (!config.consumableLabels) {
				return;
			}
			String label = CustomItemTextures.labelFor(stack);
			if (label == null) {
				return;
			}
			GuiGraphics graphics = (GuiGraphics) (Object) this;
			// Scale about the origin, so the draw position is divided back out to keep the
			// label anchored to this slot rather than drifting with the scale factor.
			float scale = config.consumableLabelScale;
			int drawX = Math.round((x + config.consumableLabelOffsetX) / scale);
			int drawY = Math.round((y + config.consumableLabelOffsetY) / scale);
			graphics.pose().pushMatrix();
			graphics.pose().scale(scale, scale);
			graphics.drawString(font, label, drawX, drawY, 0xFFFFFFFF, true);
			graphics.pose().popMatrix();
		} catch (RuntimeException ignored) {
			// A label must never break item rendering.
		}
	}
}
