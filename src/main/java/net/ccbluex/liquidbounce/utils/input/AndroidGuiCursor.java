/*
 * This file is part of LiquidBounce. SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.input;

import net.ccbluex.liquidbounce.LiquidBounce;
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.features.FeatureSilentScreen;
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleNoGui;
import net.ccbluex.liquidbounce.features.module.modules.render.OpaiChestLoot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.sdl.SDLMouse;

import java.util.Locale;

/**
 * Native-only Android GUI cursor recovery. No pointer drawing, synthetic input or coordinate mapping.
 * Hardware pointer appearance/availability remains owned by the Android/SDL launcher backend.
 */
public final class AndroidGuiCursor {
    private static final boolean ANDROID = detectAndroid();
    private AndroidGuiCursor() { }

    private static boolean detectAndroid() {
        String os = (System.getProperty("os.version", "") + " " +
            System.getProperty("java.vendor", "") + " " + System.getProperty("java.runtime.name", ""))
            .toLowerCase(Locale.ROOT);
        return os.contains("android") || nonBlank(System.getenv("ANDROID_ROOT")) ||
            nonBlank(System.getenv("POJAV_NATIVEDIR"));
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Hidden containers are intentionally gameplay, not visible GUIs. Do not break their rotation. */
    public static boolean shouldShow(Minecraft mc) {
        if (!ANDROID || mc == null || !mc.isWindowActive() || mc.gui == null || mc.gui.screen() == null) return false;
        if (!LiquidBounce.INSTANCE.isInitialized()) return true;
        return !(FeatureSilentScreen.INSTANCE.getShouldHide() && !FeatureSilentScreen.INSTANCE.getUnlockCursor()) &&
            !ModuleNoGui.shouldHide(mc.gui.screen()) &&
            !OpaiChestLoot.INSTANCE.isReplacing(mc.gui.screen());
    }

    /** Called on screen installation, before GUI extraction, and on attempted GUI re-grabs. */
    public static void enforce(Minecraft mc) {
        if (!shouldShow(mc)) return;
        if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
        // Do not call InputConstants.releaseMouse every frame: it warps the pointer to a new position.
        long window = mc.getWindow().handle();
        if (SDLMouse.SDL_GetWindowRelativeMouseMode(window)) {
            SDLMouse.SDL_SetWindowRelativeMouseMode(window, false);
        }
        // Request the native hardware pointer only. A backend refusing this request gets no fake cursor.
        SDLMouse.SDL_ShowCursor();
    }

}
