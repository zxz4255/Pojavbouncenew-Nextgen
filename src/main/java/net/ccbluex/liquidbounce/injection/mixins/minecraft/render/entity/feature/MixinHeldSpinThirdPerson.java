package net.ccbluex.liquidbounce.injection.mixins.minecraft.render.entity.feature;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHeldItemSpin;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemInHandLayer.class)
public abstract class MixinHeldSpinThirdPerson {
    @WrapMethod(method = "submitArmWithItem")
    private void heldSpin$arm(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack itemStack,
            HumanoidArm arm, PoseStack stack, SubmitNodeCollector collector, int light, Operation<Void> original) {
        ModuleHeldItemSpin.withThirdPerson(stack, state, () ->
            original.call(state, item, itemStack, arm, stack, collector, light));
    }
}
