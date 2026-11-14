package net.ccbluex.liquidbounce.injection.mixins.minecraft.item;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHeldItemSpin;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class MixinHeldSpinFirstPerson {
    @WrapMethod(method = "submitArmWithItem")
    private void heldSpin$arm(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state,
            float partial, float xRot, InteractionHand hand, float attack, ItemStack item,
            float height, PoseStack stack, SubmitNodeCollector collector, int light, Operation<Void> original) {
        ModuleHeldItemSpin.withFirstPerson(stack, () ->
            original.call(player, state, partial, xRot, hand, attack, item, height, stack, collector, light));
    }

    // Filled maps bypass ItemStackRenderState entirely. Rotate only the sheet, not the hands.
    @WrapMethod(method = "renderMap")
    private void heldSpin$map(PoseStack stack, SubmitNodeCollector collector, int light, ItemStack item,
            boolean mainHand, FirstPersonHandsAndItemsRenderState state, Operation<Void> original) {
        if (!ModuleHeldItemSpin.active()) {
            original.call(stack, collector, light, item, mainHand, state);
            return;
        }
        stack.pushPose();
        try {
            ModuleHeldItemSpin.uprightMap(stack);
            original.call(stack, collector, light, item, mainHand, state);
        } finally {
            stack.popPose();
        }
    }
}
