/*
 * Kotlin Mixin for ModuleFriendswine, targeting the LiquidBounce / Minecraft 26.3 source in Work.
 *
 * Place at src/main/kotlin/net/ccbluex/liquidbounce/injection/mixins/minecraft/render/entity/
 * and register "minecraft.render.entity.MixinFriendswine" in the client array of
 * src/main/resources/liquidbounce.mixins.json. Also register ModuleFriendswine in ModuleManager.
 *
 * Vanilla LivingEntityRenderer.submit pushes a pose before these operations and pops it after
 * submitting the body and feature layers. Injecting after its unconditional model-origin
 * translation affects the body and layers, but not the later super.submit nameplate.
 */
package net.ccbluex.liquidbounce.injection.mixins.minecraft.render.entity

import com.mojang.blaze3d.vertex.PoseStack
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleFriendswine
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LivingEntityRenderer::class)
abstract class MixinFriendswine {
    @Inject(
        method = ["submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"],
        at = [At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V",
            ordinal = 1,
            shift = At.Shift.AFTER
        )],
        require = 1
    )
    private fun friendswineTransform(
        state: LivingEntityRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
        ci: CallbackInfo
    ) {
        ModuleFriendswine.transformIfApplicable(state, poseStack)
    }
}
