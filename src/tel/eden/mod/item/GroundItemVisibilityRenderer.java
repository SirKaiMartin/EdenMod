package tel.eden.mod.item;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import tel.eden.mod.config.BridgeConfig;

/**
 * Resolves dropped-item scale rules and applies their render-only state to item entities.
 */
public final class GroundItemVisibilityRenderer {
	private static final Map<ItemEntityRenderState, Float> SCALE_BY_STATE = java.util.Collections.synchronizedMap(new WeakHashMap<>());

	private GroundItemVisibilityRenderer() {
	}

	public static void extract(BridgeConfig config, ItemEntity itemEntity, ItemEntityRenderState state) {
		BridgeConfig.GroundItemVisibilityRule rule = matchingRule(config, itemEntity.getItem());
		if (rule == null) {
			SCALE_BY_STATE.remove(state);
			return;
		}
		float scale = rule.size;
		if (!Float.isFinite(scale)) {
			scale = 1.0f;
		}
		scale = Math.max(BridgeConfig.GROUND_ITEM_MIN_SCALE, Math.min(BridgeConfig.GROUND_ITEM_MAX_SCALE, scale));
		if (scale != 1.0f) {
			SCALE_BY_STATE.put(state, scale);
		} else {
			SCALE_BY_STATE.remove(state);
		}
	}

	public static float consumeScale(ItemEntityRenderState state) {
		Float scale = SCALE_BY_STATE.remove(state);
		return scale != null ? scale : 1.0f;
	}

	private static BridgeConfig.GroundItemVisibilityRule matchingRule(BridgeConfig config, ItemStack stack) {
		if (!config.groundItemVisibility || config.groundItemVisibilityRules == null || config.groundItemVisibilityRules.isEmpty()) {
			return null;
		}
		String normalizedName = BridgeConfig.normalizeGroundItemName(stack.getHoverName().getString());
		if (normalizedName.isEmpty()) {
			return null;
		}
		for (BridgeConfig.GroundItemVisibilityRule rule : config.groundItemVisibilityRules) {
			if (rule == null) {
				continue;
			}
			String nameFilter = BridgeConfig.normalizeGroundItemName(rule.nameContains);
			if (nameFilter.isEmpty() || normalizedName.contains(nameFilter)) {
				// A rule supplies the complete scale. Returning the first match makes list
				// order the priority and prevents overlapping rules from stacking transforms.
				return rule;
			}
		}
		return null;
	}
}
