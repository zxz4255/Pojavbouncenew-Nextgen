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

package net.ccbluex.liquidbounce.features.module.modules.render.chat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.keybinds.KeybindsBackdrop
import net.ccbluex.liquidbounce.injection.mixins.minecraft.gui.MixinChatComponentAccessor
import net.ccbluex.liquidbounce.render.ChatCardSurface
import net.ccbluex.liquidbounce.render.drawChatCardSurface
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawSoftRoundedShadow
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withAlphaScale
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Turns vanilla's per-line chat background fills into one shared rounded panel. Vanilla still owns
 * message layout, click/hover behavior and scrolling, including scrolling back through the history.
 */
object ModuleChatCards : ClientModule("ChatCards", ModuleCategories.RENDER, state = true) {

    enum class Style(override val tag: String) : Tagged {
        GLASS("Glass"),
        SHADOW("Shadow"),
    }

    private val style by enumChoice("Style", Style.GLASS)
    private val cornerRadius by float("CornerRadius", 10f, 0f..24f, "px")
    private val frostedGlass by boolean("FrostedGlass", true)
    private val glassTint by color("GlassTint", Color4b(35, 48, 72, 158))
    private val shadowSurface by color("ShadowSurface", Color4b(15, 18, 24, 205))
    private val shadowColor by color("ShadowColor", Color4b(0, 0, 0, 125))
    private val shadowBlur by float("ShadowBlur", 8f, 0f..20f, "px")
    private val outline by boolean("Outline", true)

    private data class RenderPass(
        val graphics: GuiGraphicsExtractor,
        val foreground: Boolean,
        val tickCount: Int,
        var cardDrawn: Boolean = false,
    )

    private val renderPass = ThreadLocal<RenderPass?>()

    /** Starts a scope around one vanilla ChatComponent render/extraction pass. */
    fun beginChatRender(graphics: GuiGraphicsExtractor, foreground: Boolean, tickCount: Int) {
        renderPass.set(RenderPass(graphics, foreground, tickCount))
    }

    /** Ends the matching scope. */
    fun endChatRender(graphics: GuiGraphicsExtractor) {
        if (renderPass.get()?.graphics === graphics) {
            renderPass.remove()
        }
    }

    /** Returns true for vanilla message-row fills that should be suppressed after drawing one card. */
    @Suppress("UNUSED_PARAMETER")
    fun interceptMessageBackground(
        graphics: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        vanillaColor: Int,
    ): Boolean {
        if (!enabled || x0 != -4 || x1 <= x0 || y1 <= y0) return false

        val pass = renderPass.get() ?: return false
        if (pass.graphics !== graphics) return false
        if (pass.cardDrawn) return true

        val visibleLines = visibleLineSpan(pass.foreground, pass.tickCount)
        if (visibleLines <= 0) return false

        // ChatComponent visits rows from the bottom upward: the first fill is the newest row at the
        // bottom of the panel. Extend the card upward, not below the chat viewport.
        val lineHeight = y1 - y0
        val top = y1 - visibleLines * lineHeight
        val opacity = firstVisibleLineOpacity(pass.foreground, pass.tickCount)
        if (opacity <= 0f) return false

        drawChatCard(graphics, x0.toFloat(), top.toFloat(), x1.toFloat(), y1.toFloat(), opacity)
        pass.cardDrawn = true
        return true
    }

    /**
     * The vanilla display page is newest-first. In the fading background pass, ignore rows whose
     * vanilla chat lifetime has elapsed so the shared card follows the visible history height.
     */
    private fun visibleLineSpan(foreground: Boolean, currentTick: Int): Int {
        val chat = mc.gui.hud.getChat() as? MixinChatComponentAccessor ?: return 1
        val lines = chat.trimmedMessages
        val scroll = chat.chatScrollbarPos.coerceAtLeast(0)
        val pageSize = chat.invokeGetLinesPerPage().coerceAtLeast(0)
        val pageCount = minOf(pageSize, (lines.size - scroll).coerceAtLeast(0))
        if (pageCount == 0) return 0
        if (foreground) return pageCount

        var lastVisibleIndex = -1
        for (index in 0 until pageCount) {
            if (messageOpacity(lines[scroll + index].addedTime(), currentTick) > 1.0e-5f) {
                lastVisibleIndex = index
            }
        }

        return lastVisibleIndex + 1
    }

    /** The row fade is independent of Minecraft's text-background-opacity option. */
    private fun firstVisibleLineOpacity(foreground: Boolean, currentTick: Int): Float {
        if (foreground) return 1f

        val chat = mc.gui.hud.getChat() as? MixinChatComponentAccessor ?: return 1f
        val scroll = chat.chatScrollbarPos.coerceAtLeast(0)
        val addedTime = chat.trimmedMessages.getOrNull(scroll)?.addedTime() ?: return 0f
        return messageOpacity(addedTime, currentTick)
    }

    private fun messageOpacity(addedTime: Int, currentTick: Int): Float {
        val elapsedTicks = (currentTick - addedTime).coerceAtLeast(0)
        val fade = ((1.0 - elapsedTicks / 200.0) * 10.0).coerceIn(0.0, 1.0)
        return (fade * fade).toFloat()
    }

    private fun drawChatCard(
        graphics: GuiGraphicsExtractor,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        vanillaOpacity: Float,
    ) {
        if (vanillaOpacity <= 0f) return

        val radius = cornerRadius.coerceAtMost((bottom - top) * 0.5f)
        when (style) {
            Style.GLASS -> {
                val blurTexture = if (frostedGlass) runCatching { KeybindsBackdrop.request() }.getOrNull() else null
                if (blurTexture != null) {
                    KeybindsBackdrop.draw(
                        graphics,
                        blurTexture,
                        left,
                        top,
                        right - left,
                        bottom - top,
                        radius,
                        glassTint.argb,
                        vanillaOpacity * (glassTint.a / 255f),
                    )
                } else {
                    graphics.drawChatCardSurface(
                        left, top, right, bottom, radius,
                        glassTint.withAlphaScale(vanillaOpacity),
                        ChatCardSurface.GLASS,
                        strokeWidth = 0.7f,
                    )
                }

                if (outline) {
                    graphics.drawRoundedRect(
                        left, top, right, bottom, radius,
                        fillColor = Color4b.TRANSPARENT,
                        outlineColor = Color4b(220, 232, 255, (155f * vanillaOpacity).toInt()),
                        outlineWidth = 0.75f,
                    )
                }
            }

            Style.SHADOW -> {
                graphics.drawSoftRoundedShadow(
                    left, top, right, bottom, radius, shadowBlur,
                    shadowColor.withAlphaScale(vanillaOpacity),
                )
                graphics.drawChatCardSurface(
                    left, top, right, bottom, radius,
                    shadowSurface.withAlphaScale(vanillaOpacity),
                    ChatCardSurface.SHADOW,
                    strokeWidth = if (outline) 0.7f else 0f,
                )
            }
        }
    }
}
