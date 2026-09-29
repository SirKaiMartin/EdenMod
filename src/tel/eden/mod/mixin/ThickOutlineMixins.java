package tel.eden.mod.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.util.ThickOutlineRenderer;

@Mixin(EntityRenderer.class)
abstract class ThickOutlineEntityExtractionMixin {
	@Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
	private void edenmod$setCustomOutline(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
		ThickOutlineRenderer.capture(entity, cir.getReturnValue());
	}
}

@Mixin(LevelRenderer.class)
abstract class ThickOutlineFrameGraphMixin {
	@Redirect(method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;addToFrame(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;IILnet/minecraft/client/renderer/PostChain$TargetBundle;)V", ordinal = 0))
	private void edenmod$addOutlinePassBeforeVanilla(PostChain vanillaChain, FrameGraphBuilder graph, int width, int height, PostChain.TargetBundle targets) {
		ThickOutlineRenderer.addBeforeVanillaOutlinePass(vanillaChain, graph, width, height, targets);
	}
}

@Mixin(PostChain.class)
abstract class ThickOutlinePostChainAccessorMixin implements ThickOutlineRenderer.PostChainAccess {
	@Accessor("passes")
	public abstract java.util.List<PostPass> edenmod$getPasses();
}

@Mixin(PostPass.class)
abstract class ThickOutlinePostPassAccessorMixin implements ThickOutlineRenderer.PostPassAccess {
	@Accessor("customUniforms")
	public abstract java.util.Map<String, GpuBuffer> edenmod$getCustomUniforms();
}
