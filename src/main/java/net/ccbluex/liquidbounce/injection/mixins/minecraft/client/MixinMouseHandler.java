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

package net.ccbluex.liquidbounce.injection.mixins.minecraft.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.InputConstants;
import net.ccbluex.liquidbounce.additions.MouseHandlerAddition;
import net.ccbluex.liquidbounce.event.EventManager;
import net.ccbluex.liquidbounce.event.events.*;
import net.ccbluex.liquidbounce.LiquidBounce;
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleNoGui;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleZoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.objectweb.asm.Opcodes;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler implements MouseHandlerAddition {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Unique
    @Override
    public void liquidbounce$setPosition(double x, double y) {
        this.xpos = x;
        this.ypos = y;
        InputConstants.releaseMouse(this.minecraft.getWindow(), this.xpos, this.ypos);
    }

    /**
     * Hook mouse button event
     */
    @Inject(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;", shift = At.Shift.BEFORE, ordinal = 0))
    private void hookMouseButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        final var button = input.button();
        EventManager.INSTANCE.callEvent(new MouseButtonEvent(
                InputConstants.Type.MOUSE.getOrCreate(button),
                button,
                action,
                input.modifiers(),
                this.minecraft.gui.screen()
        ));
    }

    /**
     * Hook mouse scroll event
     */
    @Inject(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;", shift = At.Shift.BEFORE, ordinal = 0))
    private void hookMouseScroll(long window, double horizontal, double vertical, CallbackInfo callbackInfo) {
        EventManager.INSTANCE.callEvent(new MouseScrollEvent(horizontal, vertical));
    }

    @Inject(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z", shift = At.Shift.BEFORE), cancellable = true)
    private void hookMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci, @Local(name = "wheel") int i) {
        if (EventManager.INSTANCE.callEvent(new MouseScrollInHotbarEvent(i)).isCancelled()) {
            ci.cancel();
        }
    }

    /**
     * Hook mouse cursor event
     */
    @Inject(method = "onMove", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z", shift = At.Shift.BEFORE, ordinal = 0))
    private void hookCursorPos(long handle, double xpos, double ypos, double xrel, double yrel, CallbackInfo ci) {
        EventManager.INSTANCE.callEvent(new MouseCursorEvent(xpos, ypos));
    }

    @ModifyExpressionValue(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean injectZoomCondition1(boolean original) {
        return original || ModuleZoom.INSTANCE.getRunning();
    }

    @ModifyExpressionValue(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isScoping()Z"))
    private boolean injectZoomCondition2(boolean original) {
        return original || ModuleZoom.INSTANCE.getRunning();
    }

    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), require = 1, allow = 1)
    private void modifyMouseRotationInput(LocalPlayer instance, double cursorDeltaX, double cursorDeltaY, Operation<Void> original) {
        final MouseRotationEvent event = new MouseRotationEvent(cursorDeltaX, cursorDeltaY);
        EventManager.INSTANCE.callEvent(event);

        if (!event.isCancelled()) {
            original.call(instance, event.getCursorDeltaX(), event.getCursorDeltaY());
        }
    }

    // gppro routes the button decision branches to gameplay, without replacing the
    // screen object at the subsequent dereference inside the GUI branch.
    @ModifyExpressionValue(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;", ordinal = 1))
    private Screen gp$mouseGrabScreen(Screen screen) {
        return (LiquidBounce.INSTANCE.isInitialized() && gp$shouldTreatAsNoScreen(screen)) ? null : screen;
    }

    @ModifyExpressionValue(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;", ordinal = 3))
    private Screen gp$mouseGameScreen(Screen screen) {
        return (LiquidBounce.INSTANCE.isInitialized() && gp$shouldTreatAsNoScreen(screen)) ? null : screen;
    }

    @ModifyExpressionValue(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;", ordinal = 0))
    private Screen gp$scrollScreen(Screen screen) {
        return (LiquidBounce.INSTANCE.isInitialized() && gp$shouldTreatAsNoScreen(screen)) ? null : screen;
    }

    @ModifyExpressionValue(method = "handleAccumulatedMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;", ordinal = 0))
    private Screen gp$mouseMoveScreen(Screen screen) {
        return (LiquidBounce.INSTANCE.isInitialized() && gp$shouldTreatAsNoScreen(screen)) ? null : screen;
    }

    // Vanilla grabMouse normally closes the installed container. Keep it alive here.
    @WrapWithCondition(method = "grabMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private boolean gp$keepContainer(Gui gui, Screen next) {
        return !(LiquidBounce.INSTANCE.isInitialized() && gp$shouldTreatAsNoScreen(net.minecraft.client.Minecraft.getInstance().gui.screen()));
    }

    // Vanilla grabMouse also sets a 10,000 tick attack cooldown intended for real screen closing.
    @WrapWithCondition(method = "grabMouse", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;missTime:I", opcode = Opcodes.PUTFIELD))
    private boolean gp$dontBlockAttacking(Minecraft minecraft, int value) {
        return !(LiquidBounce.INSTANCE.isInitialized() && gp$shouldTreatAsNoScreen(net.minecraft.client.Minecraft.getInstance().gui.screen()));
    }

    /**
     * True when [screen] must behave as if no screen were open.
     *
     * <p>Two cases: {@link ModuleNoGui} hides it, or the Opai Dynamic Island has taken it over. The
     * second one used to be missing here, which is why the island could hide the vanilla chest while
     * the mouse was still routed to a GUI: the cursor stayed grabbed and clicking in the island moved
     * it — the "mouse jumps when the chest opens" symptom.</p>
     *
     * <p>Deliberately session-free (see {@code OpaiChestLoot.isReplacing}): the very first frame after
     * the container screen is installed has to count, otherwise the same one-frame window leaks
     * through.</p>
     */
    @Unique
    private static boolean gp$shouldTreatAsNoScreen(@Nullable Screen screen) {
        if (screen == null) return false;
        if (ModuleNoGui.shouldHide(screen)) return true;
        return net.ccbluex.liquidbounce.features.module.modules.render.OpaiChestLoot.INSTANCE
            .isReplacing(screen);
    }

}
