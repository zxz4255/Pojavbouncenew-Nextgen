/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.injection.mixins.minecraft.item;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.ccbluex.liquidbounce.utils.aiming.RotationManager;
import net.ccbluex.liquidbounce.utils.item.LegacySwordBlocking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Item.class)
public abstract class MixinItem {

    @ModifyExpressionValue(method = "getPlayerPOVHitResult", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;calculateViewVector(FF)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 hookFixRotation(Vec3 original, Level world, Player player, ClipContext.Fluid fluidHandling) {
        if (player == Minecraft.getInstance().player) {
            var rotation = RotationManager.INSTANCE.getCurrentRotation();
            if (rotation != null) {
                return rotation.directionVector();
            }
        }

        return original;
    }

    // Use the normal MultiPlayerGameMode prediction/UseItem packet path. This is not fake blocking.
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void lb$legacySwordUse(Level level, Player player, InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (hand == InteractionHand.MAIN_HAND && player == mc.player && level == mc.level &&
            LegacySwordBlocking.needsFallback(player.getItemInHand(hand))) {
            player.startUsingItem(hand);
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }

    @Inject(method = "getUseAnimation", at = @At("HEAD"), cancellable = true)
    private void lb$legacySwordAnimation(ItemStack stack, CallbackInfoReturnable<ItemUseAnimation> cir) {
        if (LegacySwordBlocking.needsFallback(stack)) cir.setReturnValue(ItemUseAnimation.BLOCK);
    }

    @Inject(method = "getUseDuration", at = @At("HEAD"), cancellable = true)
    private void lb$legacySwordDuration(ItemStack stack, LivingEntity user, CallbackInfoReturnable<Integer> cir) {
        if (LegacySwordBlocking.needsFallback(stack)) cir.setReturnValue(LegacySwordBlocking.USE_DURATION);
    }

}
