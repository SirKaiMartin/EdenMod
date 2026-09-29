package tel.eden.mod.util;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/** Captures Eden's silhouettes in the vanilla outline target and expands them in screen space. */
public final class ThickOutlineRenderer {
	private static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("edenmod", "thick_outline");
	private static final int STYLE_UNIFORM_BYTES = OutlineManager.MAX_STYLE_SLOTS * 16;
	private static final OutlineManager MANAGER = new OutlineManager();
	private static boolean active;
	private static PostChain uniformChain;
	private static List<OutlineManager.Style> uploadedStyles;

	private ThickOutlineRenderer() {
	}

	public static OutlineManager manager() {
		return MANAGER;
	}

	/** Called after vanilla extracts its render state, before the state is submitted. */
	public static void capture(Entity entity, EntityRenderState state) {
		int styleSlot = MANAGER.styleSlot(entity);
		if (styleSlot < 0) {
			return;
		}
		// A reserved dark-red key carries the style slot; other channels remain zero.
		int keyRed = 0xA0 | (styleSlot + 1);
		state.outlineColor = 0xFF000000 | (keyRed << 16);
		active = true;
	}

	/** Adds Eden's mask pass before vanilla blurs and composites the remaining vanilla outlines. */
	public static void addBeforeVanillaOutlinePass(PostChain vanillaChain, FrameGraphBuilder graph, int width, int height, PostChain.TargetBundle targets) {
		if (active) {
			active = false;
			PostChain chain = net.minecraft.client.Minecraft.getInstance().getShaderManager().getPostChain(CHAIN_ID, LevelTargetBundle.OUTLINE_TARGETS);
			if (chain != null) {
				List<OutlineManager.Style> styles = MANAGER.styleTable();
				updateStyleUniform(chain, styles);
				chain.addToFrame(graph, width, height, targets);
			}
		}
		vanillaChain.addToFrame(graph, width, height, targets);
	}

	private static void updateStyleUniform(PostChain chain, List<OutlineManager.Style> styles) {
		if (chain == uniformChain && styles.equals(uploadedStyles)) {
			return;
		}
		ByteBuffer value = ByteBuffer.allocateDirect(STYLE_UNIFORM_BYTES).order(ByteOrder.nativeOrder());
		for (int slot = 0; slot < OutlineManager.MAX_STYLE_SLOTS; slot++) {
			OutlineManager.Style style = slot < styles.size() ? styles.get(slot) : null;
			if (style == null) {
				value.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
			} else {
				value.putFloat(((style.argb() >>> 16) & 255) / 255.0f);
				value.putFloat(((style.argb() >>> 8) & 255) / 255.0f);
				value.putFloat((style.argb() & 255) / 255.0f);
				value.putFloat(style.thickness());
			}
		}
		value.flip();
		Map<String, GpuBuffer> uniforms = ((PostPassAccess) ((PostChainAccess) chain).edenmod$getPasses().getFirst()).edenmod$getCustomUniforms();
		GpuBuffer replacement = RenderSystem.getDevice().createBuffer(() -> "Eden outline style table", GpuBuffer.USAGE_UNIFORM, value);
		GpuBuffer previous = uniforms.put("StyleConfig", replacement);
		if (previous != null) {
			previous.close();
		}
		uniformChain = chain;
		uploadedStyles = styles;
	}

	public interface PostChainAccess {
		List<PostPass> edenmod$getPasses();
	}

	public interface PostPassAccess {
		Map<String, GpuBuffer> edenmod$getCustomUniforms();
	}
}
