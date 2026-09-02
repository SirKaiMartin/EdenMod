package tel.eden.mod.item;

import java.util.List;

/**
 * A decoded Wynncraft item, reduced to just what the card renderer needs.
 *
 * @param name           the item name (e.g. {@code "Gale's Force"})
 * @param tier           the rarity/gear tier display name (e.g. {@code "Legendary"})
 * @param tierColor      the rarity colour as 0xRRGGBB (for the name + pill)
 * @param type           the gear type display name (e.g. {@code "Bow"}), may be empty
 * @param overallPercent the overall roll quality 0-100, or a negative value if absent
 * @param identifications the rolled identifications, top to bottom
 * @param majorIds       the item's major IDs, if any
 * @param weightings     item-weight scores exposed by Wynntils services, if any
 * @param shinyTracker   the item's shiny tracker stat, if Wynntils exposes one
 * @param powders        decoded powder contents, retained for future use even though the
 *                       current renderer intentionally omits powder visuals
 * @param powderSlots    the number of powder slots on the item
 * @param rerollCount    the number of rerolls, or a negative value if not applicable
 */
public record DecodedItem(
		String name,
		String tier,
		int tierColor,
		String type,
		float overallPercent,
		List<Identification> identifications,
		List<MajorIdentification> majorIds,
		List<Weighting> weightings,
		ShinyTracker shinyTracker,
		List<PowderSlot> powders,
		int powderSlots,
		int rerollCount) {
	public DecodedItem {
		identifications = List.copyOf(identifications);
		majorIds = List.copyOf(majorIds);
		weightings = List.copyOf(weightings);
		powders = List.copyOf(powders);
	}

	/** Whether an overall roll quality is present (gear with variable stats). */
	public boolean hasOverall() {
		return overallPercent >= 0;
	}

	/** Whether a shiny-tracker stat is present. */
	public boolean hasShinyTracker() {
		return shinyTracker != null;
	}

	/** Whether a reroll count is present for this item type. */
	public boolean hasRerollCount() {
		return rerollCount >= 0;
	}

	/**
	 * One rolled identification line.
	 *
	 * @param name        stat display name (e.g. {@code "Walk Speed"})
	 * @param valueText   the formatted value with sign + unit (e.g. {@code "+38%"})
	 * @param rollPercent the roll quality 0-100, or a negative value if not applicable
	 * @param positive    whether the roll is beneficial (green) vs detrimental (red)
	 */
	public record Identification(String name, String valueText, float rollPercent, boolean positive) {

		/** Whether a roll percentage applies (variable, non pre-identified stats). */
		public boolean hasRoll() {
			return rollPercent >= 0;
		}
	}

	/**
	 * One major-ID block.
	 *
	 * @param name        major-ID name (e.g. {@code "Rally"})
	 * @param description plain-text lore/description
	 */
	public record MajorIdentification(String name, String description) {
	}

	/**
	 * One weighting row from a source such as Nori or Wynnpool.
	 *
	 * @param source     weighting source name
	 * @param scaleName  weighting profile name
	 * @param percentage computed score 0-100
	 */
	public record Weighting(String source, String scaleName, float percentage) {
	}

	/**
	 * One shiny tracker row.
	 *
	 * @param name      tracker display name (e.g. {@code "Major World Events Won"})
	 * @param valueText formatted tracker value
	 */
	public record ShinyTracker(String name, String valueText) {
	}

	/**
	 * One powder socket entry.
	 *
	 * @param element the powder element name (e.g. {@code "Air"})
	 */
	public record PowderSlot(String element) {
	}
}
