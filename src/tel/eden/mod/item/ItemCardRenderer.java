package tel.eden.mod.item;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Renders a shared item into a lightweight PNG card. The goal is not pixel-perfect
 * Wynncraft reproduction; it is to preserve the tooltip's reading order clearly in
 * Discord and bridge contexts.
 */
public final class ItemCardRenderer {
	private static final int WIDTH = 360;
	private static final int PAD = 18;
	private static final int NAME_H = 34;
	private static final int PILL_H = 30;
	private static final int ROW_H = 22;
	private static final int DETAIL_ROW_H = 18;
	private static final int GAP = 12;
	private static final int POWDER_DOT_SIZE = 8;
	private static final int POWDER_DOT_STEP = 14;

	private static final Color BG = new Color(0x16, 0x16, 0x1C);
	private static final Color VALUE_POS = new Color(0x55, 0xFF, 0x55);
	private static final Color VALUE_NEG = new Color(0xFF, 0x55, 0x55);
	private static final Color STAT_NAME = new Color(0xD0, 0xD0, 0xD0);
	private static final Color MUTED = new Color(0x88, 0x88, 0x88);
	private static final Color POWDER_EMPTY = new Color(0x7A, 0x7A, 0x7A);

	private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 22);
	private static final Font PILL_FONT = new Font("SansSerif", Font.BOLD, 13);
	private static final Font STAT_FONT = new Font("SansSerif", Font.PLAIN, 15);
	private static final Font SECTION_FONT = new Font("SansSerif", Font.BOLD, 14);
	private static final Font DETAIL_FONT = new Font("SansSerif", Font.PLAIN, 14);

	private ItemCardRenderer() {
	}

	/** Render the item to PNG bytes. */
	public static byte[] render(DecodedItem item) throws IOException {
		BufferedImage image = new BufferedImage(WIDTH, measureHeight(item), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(BG);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());

			int y = PAD + 22;
			y = drawName(g, item, y);
			y = drawPill(g, item, y + 4);
			y += GAP - 2;

			// Keep the section order close to Wynncraft's tooltip: top summary, weights,
			// powders, main identifications, then major IDs at the bottom.
			y = drawWeightings(g, item, y);
			y = addGapIfNeeded(y, !item.weightings().isEmpty(), hasPowderSection(item));
			y = drawPowderSlots(g, item, y);
			y = addGapIfNeeded(y, hasPowderSection(item), !item.identifications().isEmpty());
			y = drawIdentifications(g, item, y);
			y = addGapIfNeeded(y, !item.identifications().isEmpty(), !item.majorIds().isEmpty());
			drawMajorIds(g, item, y);
		} finally {
			g.dispose();
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static int drawName(Graphics2D g, DecodedItem item, int y) {
		g.setFont(NAME_FONT);
		g.setColor(new Color(item.tierColor()));
		if (!item.hasOverall()) {
			drawCentered(g, item.name(), y);
			return y + NAME_H - 18;
		}

		String namePart = item.name();
		String percentPart = String.format("  [%.2f%%]", item.overallPercent());
		int startX = (WIDTH - g.getFontMetrics().stringWidth(namePart + percentPart)) / 2;

		g.drawString(namePart, startX, y);
		g.setColor(rollColor(item.overallPercent()));
		g.drawString(percentPart, startX + g.getFontMetrics().stringWidth(namePart), y);
		return y + NAME_H - 18;
	}

	private static int drawPill(Graphics2D g, DecodedItem item, int y) {
		String typeLabel = item.type().isEmpty() ? "Item" : item.type();
		String text = item.tier().isEmpty() ? typeLabel : item.tier() + " " + typeLabel;
		g.setFont(PILL_FONT);

		int textW = g.getFontMetrics().stringWidth(text);
		int pillW = textW + 24;
		int pillX = (WIDTH - pillW) / 2;
		Color tier = new Color(item.tierColor());

		g.setColor(new Color(tier.getRed(), tier.getGreen(), tier.getBlue(), 40));
		g.fillRoundRect(pillX, y, pillW, 22, 22, 22);
		g.setColor(tier);
		g.drawRoundRect(pillX, y, pillW, 22, 22, 22);
		g.drawString(text, pillX + 12, y + 16);
		return y + PILL_H;
	}

	private static int drawWeightings(Graphics2D g, DecodedItem item, int y) {
		if (item.weightings().isEmpty()) {
			return y;
		}

		String currentSource = null;
		for (DecodedItem.Weighting weighting : item.weightings()) {
			if (!weighting.source().equals(currentSource)) {
				currentSource = weighting.source();
				g.setFont(SECTION_FONT);
				g.setColor(weightingSourceColor(currentSource));
				g.drawString(currentSource, PAD, y);
				y += ROW_H - 4;
			}

			g.setFont(STAT_FONT);
			g.setColor(STAT_NAME);
			g.drawString(weighting.scaleName() + " Scale", PAD, y);

			String percent = String.format("[%.1f%%]", weighting.percentage());
			g.setColor(rollColor(weighting.percentage()));
			g.drawString(percent, WIDTH - PAD - g.getFontMetrics().stringWidth(percent), y);
			y += ROW_H;
		}
		return y;
	}

	private static int drawPowderSlots(Graphics2D g, DecodedItem item, int y) {
		int totalSlots = totalPowderSlots(item);
		if (totalSlots <= 0) {
			return y;
		}

		g.setFont(STAT_FONT);
		g.setColor(MUTED);
		String label = "Powder Slots ";
		g.drawString(label, PAD, y);

		FontMetrics metrics = g.getFontMetrics();
		int x = PAD + metrics.stringWidth(label);
		int centerY = y - metrics.getAscent() + (metrics.getAscent() + metrics.getDescent()) / 2 + 1;

		g.drawString("[", x, y);
		x += metrics.stringWidth("[") + 4;

		for (int i = 0; i < totalSlots; i++) {
			boolean filled = i < item.powders().size();
			// Powders currently decode empty for chat-shared items in the Wynntils path we
			// can access, so these often render as neutral placeholders for now. Keep the
			// color logic in place so real socket colors show up automatically if Wynntils
			// starts exposing them in the future.
			Color color = filled ? powderColor(item.powders().get(i).element()) : POWDER_EMPTY;
			drawPowderDot(g, x + 1, centerY, color, !filled);
			x += POWDER_DOT_STEP;
		}

		g.setColor(MUTED);
		g.drawString("]", x, y);
		return y + ROW_H;
	}

	private static int drawIdentifications(Graphics2D g, DecodedItem item, int y) {
		g.setFont(STAT_FONT);
		for (DecodedItem.Identification id : item.identifications()) {
			g.setColor(STAT_NAME);
			g.drawString(id.name(), PAD, y);

			String value = id.valueText();
			String roll = id.hasRoll() ? String.format(" [%.1f%%]", id.rollPercent()) : "";
			int rollW = id.hasRoll() ? g.getFontMetrics().stringWidth(roll) : 0;
			int valueW = g.getFontMetrics().stringWidth(value);
			int valueX = WIDTH - PAD - rollW - valueW;

			g.setColor(id.positive() ? VALUE_POS : VALUE_NEG);
			g.drawString(value, valueX, y);
			if (id.hasRoll()) {
				g.setColor(rollColor(id.rollPercent()));
				g.drawString(roll, WIDTH - PAD - rollW, y);
			}
			y += ROW_H;
		}
		return y;
	}

	private static int drawMajorIds(Graphics2D g, DecodedItem item, int y) {
		if (item.majorIds().isEmpty()) {
			return y;
		}

		for (DecodedItem.MajorIdentification major : item.majorIds()) {
			g.setFont(SECTION_FONT);
			g.setColor(new Color(item.tierColor()));
			g.drawString(major.name(), PAD, y);
			y += ROW_H - 4;

			g.setFont(DETAIL_FONT);
			g.setColor(STAT_NAME);
			for (String line : wrap(g.getFontMetrics(), major.description(), WIDTH - PAD * 2)) {
				g.drawString(line, PAD, y);
				y += DETAIL_ROW_H;
			}
			y += 4;
		}
		return y;
	}

	private static int addGapIfNeeded(int y, boolean beforeSectionPresent, boolean afterSectionPresent) {
		return beforeSectionPresent && afterSectionPresent ? y + GAP : y;
	}

	private static boolean hasPowderSection(DecodedItem item) {
		return totalPowderSlots(item) > 0;
	}

	private static int totalPowderSlots(DecodedItem item) {
		return Math.max(item.powderSlots(), item.powders().size());
	}

	private static void drawPowderDot(Graphics2D g, int x, int centerY, Color color, boolean empty) {
		int y = centerY - POWDER_DOT_SIZE / 2;
		g.setColor(color);
		if (empty) {
			g.drawOval(x, y, POWDER_DOT_SIZE, POWDER_DOT_SIZE);
			return;
		}
		g.fillOval(x, y, POWDER_DOT_SIZE, POWDER_DOT_SIZE);
		g.setColor(color.darker());
		g.drawOval(x, y, POWDER_DOT_SIZE, POWDER_DOT_SIZE);
	}

	private static int measureHeight(DecodedItem item) {
		// Mirror the same section order as render() so future layout changes only need
		// to reason about one top-to-bottom flow.
		int height = PAD + NAME_H + PILL_H + GAP - 2;
		height += measureWeightingsHeight(item);
		if (!item.weightings().isEmpty() && hasPowderSection(item)) {
			height += GAP;
		}
		if (hasPowderSection(item)) {
			height += ROW_H;
		}
		if (hasPowderSection(item) && !item.identifications().isEmpty()) {
			height += GAP;
		}
		height += item.identifications().size() * ROW_H;
		if (!item.identifications().isEmpty() && !item.majorIds().isEmpty()) {
			height += GAP;
		}
		height += measureMajorIdsHeight(item);
		return height + PAD;
	}

	private static int measureWeightingsHeight(DecodedItem item) {
		if (item.weightings().isEmpty()) {
			return 0;
		}
		int height = 0;
		String currentSource = null;
		for (DecodedItem.Weighting weighting : item.weightings()) {
			if (!weighting.source().equals(currentSource)) {
				currentSource = weighting.source();
				height += ROW_H - 4;
			}
			height += ROW_H;
		}
		return height;
	}

	private static int measureMajorIdsHeight(DecodedItem item) {
		int height = 0;
		for (DecodedItem.MajorIdentification major : item.majorIds()) {
			height += ROW_H - 4;
			height += wrappedLineCount(DETAIL_FONT, major.description()) * DETAIL_ROW_H;
			height += 4;
		}
		return height;
	}

	private static int wrappedLineCount(Font font, String text) {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setFont(font);
			return wrap(g.getFontMetrics(), text, WIDTH - PAD * 2).size();
		} finally {
			g.dispose();
		}
	}

	private static List<String> wrap(FontMetrics metrics, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return lines;
		}

		StringBuilder line = new StringBuilder();
		for (String word : normalized.split("\\s+")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(candidate);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	private static void drawCentered(Graphics2D g, String text, int y) {
		g.drawString(text, (WIDTH - g.getFontMetrics().stringWidth(text)) / 2, y);
	}

	private static Color powderColor(String element) {
		return switch (element.toLowerCase(Locale.ROOT)) {
			case "earth" -> new Color(0x5E, 0xD4, 0x5E);
			case "thunder" -> new Color(0xFF, 0xC8, 0x3A);
			case "water" -> new Color(0x4E, 0xD6, 0xFF);
			case "fire" -> new Color(0xFF, 0x6A, 0x4A);
			case "air" -> new Color(0xE8, 0xE8, 0xE8);
			default -> POWDER_EMPTY;
		};
	}

	private static Color weightingSourceColor(String source) {
		return switch (source.toLowerCase(Locale.ROOT)) {
			case "nori" -> new Color(0x55, 0xFF, 0xFF);
			case "wynnpool" -> new Color(0xFF, 0xAA, 0x55);
			default -> STAT_NAME;
		};
	}

	private static Color rollColor(float percent) {
		if (percent <= 20f) {
			return new Color(0xFF, 0x55, 0x55);
		}
		if (percent < 80f) {
			return new Color(0xFF, 0xFF, 0x55);
		}
		if (percent < 95f) {
			return new Color(0x55, 0xFF, 0x55);
		}
		return new Color(0x55, 0xFF, 0xFF);
	}
}
