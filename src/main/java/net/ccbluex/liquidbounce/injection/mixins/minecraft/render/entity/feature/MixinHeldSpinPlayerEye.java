package net.ccbluex.liquidbounce.injection.mixins.minecraft.render.entity.feature;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHeldItemSpin;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PlayerItemInHandLayer.class)
public abstract class MixinHeldSpinPlayerEye {
    // Some items, e.g. a spyglass in use, bypass ItemInHandLayer.submitArmWithItem.
    @WrapMethod(method = "renderItemHeldToEye")
    private void heldSpin$eye(AvatarRenderState state, HumanoidArm arm, PoseStack stack,
            SubmitNodeCollector collector, int light, Operation<Void> original) {
        ModuleHeldItemSpin.withThirdPerson(stack, state, () -> original.call(state, arm, stack, collector, light));
    }
}
