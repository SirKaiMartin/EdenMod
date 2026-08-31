package tel.eden.mod.item;

import java.util.List;

/**
 * One custom-texture rule (ported from avomod2): a consumable {@code type}
 * ("food"/"potion"/"scroll") plus name and lore regexes that must <em>all</em> match,
 * mapping the item to a {@code edenmod:<texture>} model.
 *
 * <p>A rule can instead be {@code labelOnly}, meaning it identifies the item for its slot
 * label but leaves the model alone — for consumables the guild wants marked without an
 * icon being drawn for them. {@code texture} is then only the key the label is looked up
 * under, and no {@code edenmod:} model has to exist for it.
 */
public record CustomItem(String type, List<String> names, List<String> lores, String texture, boolean labelOnly) {
	public CustomItem {
		names = List.copyOf(names);
		lores = List.copyOf(lores);
	}

	/** A rule that swaps the item's model to {@code edenmod:<texture>} and labels it. */
	public CustomItem(String type, List<String> names, List<String> lores, String texture) {
		this(type, names, lores, texture, false);
	}

	/** A rule that only labels the slot; {@code key} names the label, not a model. */
	public static CustomItem labelOnly(String type, List<String> names, List<String> lores, String key) {
		return new CustomItem(type, names, lores, key, true);
	}
}
