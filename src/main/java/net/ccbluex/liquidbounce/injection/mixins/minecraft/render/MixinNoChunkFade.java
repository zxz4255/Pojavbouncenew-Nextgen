package net.ccbluex.liquidbounce.injection.mixins.minecraft.render;

import net.ccbluex.liquidbounce.features.module.modules.render.ModuleNoChunkFade;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only bypass the visual fade after a section exists; do not create or load chunks. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class MixinNoChunkFade {
    @Inject(method = "getVisibility(JJ)F", at = @At("HEAD"), cancellable = true)
    private void liquidbounce$noChunkFade(long now, long fadeDuration, CallbackInfoReturnable<Float> cir) {
        if (ModuleNoChunkFade.INSTANCE.getRunning()) {
            cir.setReturnValue(1.0f);
        }
    }
}
