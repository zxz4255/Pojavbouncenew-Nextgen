package net.ccbluex.liquidbounce.injection.mixins.minecraft.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHeldItemSpin;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public abstract class MixinHeldSpinItemLayer {
    @WrapOperation(method = "applyTransform", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/resources/model/cuboid/ItemTransform;apply(ZLcom/mojang/blaze3d/vertex/PoseStack$Pose;)V"))
    private void heldSpin$upright(ItemTransform transform, boolean left, PoseStack.Pose pose, Operation<Void> original) {
        original.call(transform, left, pose);
        ModuleHeldItemSpin.uprightItem(pose);
    }
}
