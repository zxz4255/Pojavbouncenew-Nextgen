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
package net.ccbluex.liquidbounce.injection.mixins.minecraft.gui;

import net.ccbluex.liquidbounce.features.module.modules.render.chat.ModuleChatCards;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts vanilla message-row fills, leaving scroll bars and prompt rectangles untouched. */
@Mixin(targets = {
    "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess",
    "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess"
})
public abstract class MixinChatCardGraphicsAccess {

    @Shadow
    @Final
    private GuiGraphicsExtractor graphics;

    @Inject(method = "fill(IIIII)V", at = @At("HEAD"), cancellable = true)
    private void liquidbounce$renderOneChatCard(int x0, int y0, int x1, int y1, int color, CallbackInfo ci) {
        if (ModuleChatCards.INSTANCE.interceptMessageBackground(graphics, x0, y0, x1, y1, color)) {
            ci.cancel();
        }
    }
}
