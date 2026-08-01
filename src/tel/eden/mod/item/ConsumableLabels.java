package tel.eden.mod.item;

import java.util.Locale;
import java.util.Map;

/**
 * Short slot labels for crafted consumables, keyed by the texture a
 * {@link CustomItemData} rule resolved to.
 *
 * <p>Riding on the texture name means the label needs no rule of its own: the rule table
 * already identifies every consumable precisely enough to pick an icon for it, so the
 * same match yields the text. Several rules share a texture (two different stat combos
 * both being "mana steal food", say) and therefore share a label, which is the intended
 * behaviour — the label describes what the item <em>is</em>, not which rule caught it.
 *
 * <p>These are first-pass abbreviations derived from the texture names; they are meant to
 * be corrected to whatever shorthand the guild actually says out loud. A texture with no
 * entry here falls back to {@link #derive}, so adding a texture can never leave an item
 * unlabelled.
 */
public final class ConsumableLabels {
	private ConsumableLabels() {
	}

	private static final Map<String, String> LABELS = Map.ofEntries(
				// --- food ---
				Map.entry("food_-atk", "-ATK"), Map.entry("food_agility", "AGI"), Map.entry("food_cat", "CAT"), Map.entry("food_healing_efficiency", "HE"), Map.entry("food_health", "HP"), Map.entry("food_health_infernal", "INF"), Map.entry("food_intelligence", "INT"), Map.entry("food_jh", "JH"), Map.entry("food_mr_big", "MR"), Map.entry("food_ms", "MS"), Map.entry("food_rainbow", "RB"), Map.entry("food_sd", "SD"), Map.entry("food_strength", "STR"), Map.entry("food_strint", "S/I"), Map.entry("food_thunder_water", "T/W"), Map.entry("food_ws", "WS"), Map.entry("food_xp", "XP"),
				// --- potions ---
				Map.entry("potion_def_agi", "D/A"), Map.entry("potion_earth_thunder", "E/T"), Map.entry("potion_exploding", "EXPL"), Map.entry("potion_fairy", "FAIRY"), Map.entry("potion_gatherspeedtype1", "GS"), Map.entry("potion_gatherxptype1", "GXP"), Map.entry("potion_glacial_anomaly", "GLA"), Map.entry("potion_healing_efficiency", "HE"), Map.entry("potion_health", "HP"), Map.entry("potion_mana", "MP"), Map.entry("potion_mr", "MR"), Map.entry("potion_mr_ws", "MR/WS"), Map.entry("potion_ms", "MS"), Map.entry("potion_rainbow", "RB"), Map.entry("potion_sp_agility", "AGI"), Map.entry("potion_sp_defence", "DEF"), Map.entry("potion_sp_dexterity", "DEX"), Map.entry("potion_sp_intelligence", "INT"), Map.entry("potion_sp_strength", "STR"), Map.entry("potion_spell_bat_heart", "BAT"), Map.entry("potion_spell_melee", "SD/MD"), Map.entry("potion_strength", "STR"), Map.entry("potion_strength_dexterity", "S/D"), Map.entry("potion_water", "WTR"), Map.entry("potion_ws", "WS"), Map.entry("potion_xp", "XP"),
				// --- scrolls ---
				Map.entry("scroll_agi", "AGI"), Map.entry("scroll_def", "DEF"), Map.entry("scroll_dex", "DEX"), Map.entry("scroll_healing_efficiency", "HE"), Map.entry("scroll_hp", "HP"), Map.entry("scroll_int", "INT"), Map.entry("scroll_melee", "MD"), Map.entry("scroll_mr", "MR"), Map.entry("scroll_ms", "MS"), Map.entry("scroll_rainbow", "RB"), Map.entry("scroll_rainbow_cheap", "RB-"), Map.entry("scroll_sd", "SD"), Map.entry("scroll_str", "STR"), Map.entry("scroll_thorns", "THN"), Map.entry("scroll_xp", "XP"));

	/** The label for a texture name, never null. */
	public static String forTexture(String texture) {
		String label = LABELS.get(texture);
		return label != null ? label : derive(texture);
	}

	/**
	 * Fallback label for a texture with no entry above: the name minus its
	 * {@code food_}/{@code potion_}/{@code scroll_} prefix, upper-cased and clipped, so a
	 * newly added texture shows something readable instead of nothing.
	 */
	private static String derive(String texture) {
		String text = texture;
		int underscore = text.indexOf('_');
		if (underscore >= 0 && underscore < text.length() - 1) {
			text = text.substring(underscore + 1);
		}
		text = text.replace('_', '/').toUpperCase(Locale.ROOT);
		return text.length() <= 5 ? text : text.substring(0, 5);
	}
}
