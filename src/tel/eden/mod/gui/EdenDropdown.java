package tel.eden.mod.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A reusable vanilla-styled select button with caller-configured popup bounds and row count. */
final class EdenDropdown<T> extends AbstractButton {
	private static final int POPUP_BORDER = 0xFF8E8E8E;
	private static final int POPUP_BACKGROUND = 0xFF202020;
	private static final int OPTION_HOVERED = 0xFF4A4A4A;
	private static final int OPTION_SELECTED = 0xFF303830;

	private final Font font;
	private final List<T> values;
	private final Function<T, String> label;
	private final Consumer<T> onChange;
	private final Consumer<EdenDropdown<?>> onOpen;
	private final PopupSettings popupSettings;

	private T value;
	private boolean open;
	private int optionOffset;

	EdenDropdown(int x, int y, int width, int height, Font font, List<T> values, T initialValue, Function<T, String> label, Consumer<T> onChange, Consumer<EdenDropdown<?>> onOpen, PopupSettings popupSettings) {
		super(x, y, width, height, Component.empty());
		Objects.requireNonNull(values, "values");
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Dropdown values cannot be empty");
		}
		this.font = Objects.requireNonNull(font, "font");
		this.values = List.copyOf(values);
		this.value = this.values.contains(initialValue) ? initialValue : this.values.getFirst();
		this.label = Objects.requireNonNull(label, "label");
		this.onChange = Objects.requireNonNull(onChange, "onChange");
		this.onOpen = Objects.requireNonNull(onOpen, "onOpen");
		this.popupSettings = Objects.requireNonNull(popupSettings, "popupSettings");
		updateMessage();
	}

	boolean isOpen() {
		return open;
	}

	void close() {
		open = false;
	}

	boolean isOverPopup(double mouseX, double mouseY) {
		return open && mouseX >= getX() && mouseX < getRight() && mouseY >= popupY() && mouseY < popupY() + popupHeight();
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return super.isMouseOver(mouseX, mouseY) || isOverPopup(mouseX, mouseY);
	}

	@Override
	public void onPress(InputWithModifiers input) {
		if (input instanceof MouseButtonEvent mouse && isOverPopup(mouse.x(), mouse.y())) {
			selectAt(mouse.y());
			return;
		}
		open = !open;
		if (open) {
			revealSelection();
			onOpen.accept(this);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!open || (!isOverPopup(mouseX, mouseY) && !super.isMouseOver(mouseX, mouseY))) {
			return false;
		}
		int maxOffset = Math.max(0, values.size() - visibleOptionCount());
		optionOffset = Math.max(0, Math.min(maxOffset, optionOffset - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		renderDefaultSprite(graphics);
		renderDefaultLabel(graphics.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.NONE));

		int arrowX = getRight() - 9;
		int arrowY = getY() + (getHeight() / 2) - 1;
		int color = active ? 0xFFFFFFFF : 0xFF888888;
		graphics.fill(arrowX - 2, arrowY, arrowX + 3, arrowY + 1, color);
		graphics.fill(arrowX - 1, arrowY + 1, arrowX + 2, arrowY + 2, color);
		graphics.fill(arrowX, arrowY + 2, arrowX + 1, arrowY + 3, color);
	}

	void renderPopup(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!open) {
			return;
		}

		int popupY = popupY();
		int popupHeight = popupHeight();
		if (isOverPopup(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
		graphics.fill(getX() - 1, popupY - 1, getRight() + 1, popupY + popupHeight + 1, POPUP_BORDER);
		graphics.fill(getX(), popupY, getRight(), popupY + popupHeight, POPUP_BACKGROUND);

		for (int visible = 0; visible < visibleOptionCount(); visible++) {
			int index = optionOffset + visible;
			if (index >= values.size()) {
				break;
			}
			int optionY = popupY + visible * popupSettings.optionHeight();
			boolean hovered = mouseX >= getX() && mouseX < getRight() && mouseY >= optionY && mouseY < optionY + popupSettings.optionHeight();
			T option = values.get(index);
			if (hovered || option.equals(value)) {
				graphics.fill(getX() + 1, optionY + 1, getRight() - 1, optionY + popupSettings.optionHeight() - 1, hovered ? OPTION_HOVERED : OPTION_SELECTED);
			}
			graphics.drawString(font, trimLabel(label.apply(option)), getX() + 6, optionY + Math.max(1, (popupSettings.optionHeight() - font.lineHeight) / 2), option.equals(value) ? 0xFF55FF55 : 0xFFFFFFFF);
		}

		drawScrollbar(graphics, popupY, popupHeight);
	}

	private void selectAt(double mouseY) {
		int index = optionOffset + (int) ((mouseY - popupY()) / popupSettings.optionHeight());
		if (index >= 0 && index < values.size()) {
			T selected = values.get(index);
			if (!selected.equals(value)) {
				value = selected;
				updateMessage();
				onChange.accept(value);
			}
		}
		close();
	}

	private void updateMessage() {
		setMessage(Component.literal(label.apply(value)));
	}

	private int visibleOptionCount() {
		int below = Math.max(0, popupSettings.maxY() - getBottom() - 1);
		int above = Math.max(0, getY() - popupSettings.minY() - 1);
		int availableRows = Math.max(1, Math.max(below, above) / popupSettings.optionHeight());
		return Math.min(values.size(), Math.min(popupSettings.maxVisibleOptions(), availableRows));
	}

	private boolean opensAbove() {
		int below = Math.max(0, popupSettings.maxY() - getBottom() - 1);
		int above = Math.max(0, getY() - popupSettings.minY() - 1);
		return below < popupHeight() && above > below;
	}

	private int popupY() {
		return opensAbove() ? getY() - popupHeight() - 1 : getBottom() + 1;
	}

	private int popupHeight() {
		return visibleOptionCount() * popupSettings.optionHeight();
	}

	private void revealSelection() {
		int selected = values.indexOf(value);
		if (selected < optionOffset) {
			optionOffset = selected;
		} else if (selected >= optionOffset + visibleOptionCount()) {
			optionOffset = selected - visibleOptionCount() + 1;
		}
	}

	private String trimLabel(String text) {
		int availableWidth = getWidth() - 18;
		if (font.width(text) <= availableWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, availableWidth - font.width("..."))) + "...";
	}

	private void drawScrollbar(GuiGraphics graphics, int popupY, int popupHeight) {
		if (values.size() <= visibleOptionCount()) {
			return;
		}
		int trackX = getRight() - 4;
		graphics.fill(trackX, popupY + 1, getRight() - 1, popupY + popupHeight - 1, 0x55000000);
		int thumbHeight = Math.max(6, Math.round((popupHeight - 2) * (visibleOptionCount() / (float) values.size())));
		int travel = Math.max(1, popupHeight - 2 - thumbHeight);
		int maxOffset = values.size() - visibleOptionCount();
		int thumbY = popupY + 1 + Math.round((optionOffset / (float) maxOffset) * travel);
		graphics.fill(trackX, thumbY, getRight() - 1, thumbY + thumbHeight, 0xFF8A8A8A);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}

	record PopupSettings(int minY, int maxY, int optionHeight, int maxVisibleOptions) {
		PopupSettings {
			if (maxY <= minY) {
				throw new IllegalArgumentException("Dropdown popup bounds must have positive height");
			}
			optionHeight = Math.max(1, optionHeight);
			maxVisibleOptions = Math.max(1, maxVisibleOptions);
		}
	}
}
