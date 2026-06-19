package tel.eden.mod.gui;

import tel.eden.mod.EdenModClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Minimal settings screen: link button, bridge toggle, presence toggle, status. */
public final class BridgeConfigScreen extends Screen {
	private static final int WIDTH = 220;
	private static final int HEIGHT = 20;

	private final Screen parent;
	private final EdenModClient mod;
	private String status = "";

	public BridgeConfigScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("EdenMod"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2 - WIDTH / 2;
		int y = this.height / 4;

		addRenderableWidget(Button.builder(Component.literal("Link account"), b -> onLink()).bounds(centerX, y + 14, WIDTH, HEIGHT).build());

		addRenderableWidget(Button.builder(Component.literal(enabledLabel()), this::toggleEnabled).bounds(centerX, y + 42, WIDTH, HEIGHT).build());

		addRenderableWidget(Button.builder(Component.literal(presenceLabel()), this::togglePresence).bounds(centerX, y + 66, WIDTH, HEIGHT).build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(centerX, y + 98, WIDTH, HEIGHT).build());
	}

	private String enabledLabel() {
		return "Bridge: " + (mod.config().enabled ? "Enabled" : "Disabled");
	}

	private void toggleEnabled(Button button) {
		mod.config().enabled = !mod.config().enabled;
		mod.config().save();
		button.setMessage(Component.literal(enabledLabel()));
	}

	private String presenceLabel() {
		return "My login/logout messages: " + (mod.config().announceSelfPresence ? "On" : "Off");
	}

	private void togglePresence(Button button) {
		mod.config().announceSelfPresence = !mod.config().announceSelfPresence;
		mod.config().save();
		button.setMessage(Component.literal(presenceLabel()));
	}

	private void onLink() {
		status = "Opening browser… complete the link there.";
		mod.startLinkFlow(() -> status = mod.config().hasValidJwt() ? "Linked!" : "Not linked.");
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 16, 0xFFFFFF);
		if (!status.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.literal(status), this.width / 2, this.height / 4 + 130, 0xAAAAAA);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
