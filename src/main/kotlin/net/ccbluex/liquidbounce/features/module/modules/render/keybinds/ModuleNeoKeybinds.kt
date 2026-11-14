/* NeoRaven Keybinds -> LiquidBounce 26.3. See README for reference and render limitations. */
package net.ccbluex.liquidbounce.features.module.modules.render.keybinds

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.TtfFont
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Ease
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.READ_FINAL_STATE
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Actual module: the HUD listener is independent so disabling can finish the exit animation. */
object ModuleNeoKeybinds : ClientModule("NeoKeybinds", ModuleCategories.RENDER, state = true) {
    internal var scale by float("Scale", 1f, .5f..2f)
    internal var positionX by float("PositionX", 0f, 0f..100f, "%")
    internal var positionY by float("PositionY", 25f, 0f..100f, "%")
    internal val frosted by boolean("FrostedGlass", true)
    internal val enabledOnly by boolean("EnabledOnly", false)
    internal val showCount by boolean("ShowCount", true)
    /** Optional independent hide list, comma-separated names (does not alter LB's Hidden value). */
    internal val hiddenModules by text("HideModules", "")

    @Suppress("unused")
    private val persistentHud = KeybindsEvents

    override fun onEnabled() { KeybindsHud.reset() }
}

private object KeybindsEvents : EventListener {
    private val mouseDown = HashSet<Int>()
    @Suppress("unused")
    private val mouse = handler<MouseButtonEvent> { event ->
        if (event.isPressed) mouseDown.add(event.button)
        if (event.isReleased) mouseDown.remove(event.button)
    }
    @Suppress("unused")
    private val world = handler<WorldChangeEvent> { mouseDown.clear(); KeybindsHud.reset() }
    @Suppress("unused")
    private val render = handler<OverlayRenderEvent>(priority = READ_FINAL_STATE) { event ->
        if (!mc.isWindowActive) mouseDown.clear()
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden || mc.gui.overlay() != null ||
            mc.debugOverlay.showDebugScreen()) return@handler
        val screen = mc.gui.screen()
        if (screen != null && screen !is ChatScreen) return@handler
        KeybindsHud.render(event.context, screen is ChatScreen, mouseDown)
    }
}

private object KeybindFonts {
    private const val FONT = "/assets/liquidbounce/fonts/music-cjk-fallback.ttf"
    private val font9 by lazy { TtfFont.loadOrNull(FONT, 9f, 4f, 2048, "keybinds-cjk9") }
    private val font24 by lazy { TtfFont.loadOrNull(FONT, 24f, 4f, 2048, "keybinds-cjk24") }
    private var warned = false
    fun available(): Boolean {
        val ok = font9 != null && font24 != null
        if (!ok && !warned) { warned = true; logger.warn("[NeoKeybinds] Missing or invalid font: $FONT") }
        return ok
    }
    // Never let Typeface.runFont silently switch to an OS face for an unsupported codepoint.
    fun supported(text: String): String {
        val f = font9 ?: return ""
        val result = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            result.append(if (cp <= Char.MAX_VALUE.code && f.has(cp.toChar())) cp.toChar() else '?')
            i += Character.charCount(cp)
        }
        return result.toString()
    }
    val headline by lazy { Typeface.fromFont(requireNotNull(font24), 14f, 20f) }
    val body by lazy { Typeface.fromFont(requireNotNull(font9), 12f, 16f) }
    val caption by lazy { Typeface.fromFont(requireNotNull(font9), 10f, 12f) }
    val micro by lazy { Typeface.fromFont(requireNotNull(font9), 9f, 12f) }
}

private object KeybindsHud {
    private const val PAD = 10f
    private const val HEADER = 30f
    private const val ROW_HEIGHT = 32f
    private const val ROW_GAP = 6f
    private const val ROW_PAD = 5f
    private const val EDGE = 20f
    private const val MARGIN = 10f
    private const val KEYCAP_HEIGHT = 24f
    private const val CHIP_WIDTH = 30f
    private const val CHIP_MORPH = 4f
    private val model = KeybindsModel()
    private var lastFrame = 0L
    private var x = 0f; private var y = 0f; private var w = 0f; private var h = 0f
    private var dragging = false; private var dragX = 0f; private var dragY = 0f

    fun reset() { model.reset(); lastFrame = 0L; dragging = false }

    fun render(ctx: GuiGraphicsExtractor, chat: Boolean, mouseDown: Set<Int>) {
        if (!KeybindFonts.available()) return
        val now = System.nanoTime()
        val delta = if (lastFrame == 0L) 16f else min((now - lastFrame) / 1_000_000f, 100f).coerceAtLeast(0f)
        lastFrame = now
        val hidden = ModuleNeoKeybinds.hiddenModules.split(',').map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val candidates = ModuleManager.asSequence().filter {
            it !== ModuleNeoKeybinds && !it.notActivatable && !it.disableActivation && !it.bind.isUnbound &&
                it.name.lowercase(Locale.ROOT) !in hidden && (!ModuleNeoKeybinds.enabledOnly || it.enabled)
        }.map { module -> BoundModule(module, KeybindFonts.supported(strip(module.name)), KeybindFonts.supported(label(module.bind)), module.enabled,
            !chat && pressed(module.bind, mouseDown)) }.toList()
        model.update(candidates, delta)
        val fade = model.animateVisibility(ModuleNeoKeybinds.enabled && model.entries.isNotEmpty(), delta)
        if (fade <= .002f) { dragging = false; return }

        val scheme = Theme.scheme()
        val keyWidth = keycapWidth()
        val targetWidth = cardWidth(keyWidth)
        val targetHeight = cardHeight()
        model.cardWidth.to(targetWidth, 280f, Ease.EMPHASIZED_DECELERATE)
        model.cardHeight.to(targetHeight, 280f, Ease.EMPHASIZED_DECELERATE)
        model.cardWidth.update(delta); model.cardHeight.update(delta)
        val layoutW = model.cardWidth.value(); val layoutH = model.cardHeight.value()
        val base = ModuleNeoKeybinds.scale
        w = layoutW * base; h = layoutH * base
        val maxX = max(0f, ctx.guiWidth() - w - EDGE); val maxY = max(0f, ctx.guiHeight() - h - EDGE)
        x = MARGIN + maxX * (ModuleNeoKeybinds.positionX.coerceIn(0f,100f) / 100f)
        y = MARGIN + maxY * (ModuleNeoKeybinds.positionY.coerceIn(0f,100f) / 100f)
        handleDrag(chat, ctx.guiWidth(), ctx.guiHeight(), mouseDown)
        val backdrop = if (ModuleNeoKeybinds.frosted && fade > .01f) KeybindsBackdrop.request() else null
        val actualScale = base * (.94f + .06f * fade)
        val left = x + w / 2f - layoutW * actualScale / 2f
        val top = y + h / 2f - layoutH * actualScale / 2f
        val ui = UI(ctx)
        ui.begin()
        try {
            ui.alphaPush(fade)
            ui.push()
            ui.translate(x + w / 2f, y + h / 2f)
            ui.scale(base * (.94f + .06f * fade), 0f, 0f)
            ui.translate(-layoutW / 2f, -layoutH / 2f)
            drawCard(ui, ctx, backdrop, scheme, layoutW, layoutH, model.cardWidth.target(), keyWidth,
                left, top, actualScale, fade)
            ui.pop(); ui.alphaPop()
        } finally { ui.end() }
    }

    private fun drawCard(ui: UI, ctx: GuiGraphicsExtractor, backdrop: net.minecraft.client.gui.render.TextureSetup?,
        s: Scheme, width: Float, height: Float, targetWidth: Float, keyWidth: Float,
        left: Float, top: Float, actualScale: Float, fade: Float) {
        val surface = Material.withAlpha(s.surfaceContainerHigh, 1f)
        ui.smoothShadow(0f, 0f, width, height, 26f, 3)
        ui.rounded(0f, 0f, width, height, 26f, surface)
        if (backdrop != null) KeybindsBackdrop.draw(ctx, backdrop, left, top, width * actualScale,
            height * actualScale, 26f * actualScale, s.surfaceContainerHigh, fade)
        drawHeader(ui, s, width)
        val rowsTop = PAD + HEADER + 10f
        ui.scissorPush(0f, rowsTop - 4f, width, max(0f, height - rowsTop - PAD + 4f))
        var cursor = rowsTop
        for (entry in model.entries) {
            val show = entry.show.value().coerceIn(0f, 1f)
            if (show <= .002f && !entry.wanted) continue
            ui.push(); ui.alphaPush(show)
            ui.translate(PAD, cursor + (1f - show) * 6f)
            drawRow(ui, s, entry, width - PAD * 2f, keyWidth, surface, targetWidth - PAD * 2f)
            ui.alphaPop(); ui.pop()
            cursor += (ROW_HEIGHT + ROW_GAP) * show
        }
        ui.scissorPop()
    }

    private fun drawHeader(ui: UI, s: Scheme, width: Float) {
        val top = PAD; val iconY = top + (HEADER - 26f) / 2f
        ui.rounded(PAD, iconY, 26f, 26f, 9f, s.primaryContainer)
        ui.icon("\ue312", PAD + 13f, iconY + 13f, 15f, s.onPrimaryContainer)
        ui.text(KeybindFonts.headline, "Keybinds", PAD + 26f + 8f, top + HEADER / 2f, s.onSurface, UI.ALIGN_LEFT)
        if (ModuleNeoKeybinds.showCount) {
            val count = "${model.entries.count { it.wanted && it.enabled }}/${model.entries.size}"
            val cw = KeybindFonts.micro.width(count) + 14f
            val cx = width - PAD - cw; val cy = top + (HEADER - 18f) / 2f
            ui.rounded(cx, cy, cw, 18f, 9f, s.secondaryContainer)
            ui.text(KeybindFonts.micro, count, cx + cw / 2f, cy + 9f, s.onSecondaryContainer, UI.ALIGN_CENTER)
        }
        ui.rect(PAD, top + HEADER + 3f, width - PAD * 2f, 1f, Material.withAlpha(s.outlineVariant, .6f))
    }

    private fun drawRow(ui: UI, s: Scheme, entry: KeybindsModel.Entry, width: Float, keyWidth: Float, surface: Int, targetWidth: Float) {
        val t = entry.state.value().coerceIn(0f, 1f)
        val rowOff = Material.mix(surface, s.surfaceContainerLow, .70f)
        val rowOn = Material.mix(surface, s.primaryContainer, .85f)
        val rowFill = Material.mix(rowOff, rowOn, t)
        ui.rounded(0f, 0f, width, ROW_HEIGHT, ROW_HEIGHT / 2f, rowFill)
        val flash = entry.flash.value().coerceIn(0f, 1f)
        if (flash > .001f) {
            ui.rounded(0f, 0f, width, ROW_HEIGHT, ROW_HEIGHT / 2f, Material.withAlpha(s.primary, .13f * flash))
            ui.outline(0f, 0f, width, ROW_HEIGHT, ROW_HEIGHT / 2f, 1f, Material.withAlpha(s.primary, .55f * flash))
        }
        drawKeycap(ui, s, entry, rowFill, t, keyWidth)
        val nameX = ROW_PAD + keyWidth + 10f
        val chipX = max(width, targetWidth) - ROW_PAD - (CHIP_WIDTH + CHIP_MORPH)
        val available = max(0f, chipX - 6f - nameX) + 1f
        val name = ui.truncate(KeybindFonts.body, entry.name, min(110f, available))
        ui.text(KeybindFonts.body, name, nameX, ROW_HEIGHT / 2f, Material.mix(s.onSurfaceVariant, s.onSurface, t), UI.ALIGN_LEFT)
        val chipWidth = CHIP_WIDTH + CHIP_MORPH * t
        val chipLeft = width - ROW_PAD - chipWidth; val chipY = (ROW_HEIGHT - 16f) / 2f
        val offFill = Material.mix(rowFill, s.surfaceContainerHighest, .9f)
        ui.rounded(chipLeft, chipY, chipWidth, 16f, 8f, Material.mix(offFill, s.primary, t))
        val textColor = Material.mix(s.onSurfaceVariant, s.onPrimary, t)
        if (t < .999f) { ui.alphaPush(1f - t); ui.text(KeybindFonts.micro, "OFF", chipLeft + chipWidth / 2f, chipY + 8f, textColor, UI.ALIGN_CENTER); ui.alphaPop() }
        if (t > .001f) { ui.alphaPush(t); ui.text(KeybindFonts.micro, "ON", chipLeft + chipWidth / 2f, chipY + 8f, textColor, UI.ALIGN_CENTER); ui.alphaPop() }
    }

    private fun drawKeycap(ui: UI, s: Scheme, e: KeybindsModel.Entry, rowFill: Int, t: Float, keyWidth: Float) {
        val ky = (ROW_HEIGHT - KEYCAP_HEIGHT) / 2f
        val pressed = e.press.value().coerceIn(0f, 1f)
        val pop = e.pop.value().coerceIn(.7f, 1.15f) * (1f - .04f * pressed)
        ui.push(); ui.translate(ROW_PAD + keyWidth / 2f, ky + KEYCAP_HEIGHT / 2f + pressed * .8f)
        ui.scale(pop, 0f, 0f); ui.translate(-keyWidth / 2f, -KEYCAP_HEIGHT / 2f)
        val off = Material.mix(rowFill, s.surfaceContainerHighest, .92f)
        val fill = Material.mix(off, s.primary, t)
        val border = Material.mix(s.outlineVariant, s.primary, t)
        val label = Material.mix(s.onSurfaceVariant, s.onPrimary, t)
        ui.rounded(0f, 1.5f, keyWidth, KEYCAP_HEIGHT, 9f, Material.mix(fill, s.shadow, .30f))
        ui.rounded(0f, 0f, keyWidth, KEYCAP_HEIGHT - 1.5f, 9f, fill)
        ui.outline(0f, 0f, keyWidth, KEYCAP_HEIGHT - 1.5f, 9f, 1f, Material.withAlpha(border, 1f - .65f * t))
        // LB supports modifiers, unlike Raven's single-key bind; long chords need the same
        // keycap width cap without spilling into the module name.
        val labelText = ui.truncate(KeybindFonts.caption, e.label, keyWidth - 12f)
        ui.text(KeybindFonts.caption, labelText, keyWidth / 2f, (KEYCAP_HEIGHT - 1.5f) / 2f, label, UI.ALIGN_CENTER)
        ui.pop()
    }

    private fun keycapWidth(): Float = model.entries.filter { it.measured }.fold(26f) { width, e -> max(width, min(66f, KeybindFonts.caption.width(e.label) + 12f)) }
    private fun cardWidth(keyWidth: Float): Float {
        val nameWidth = model.entries.filter { it.measured }.fold(0f) { width, e -> max(width, min(110f, KeybindFonts.body.width(e.name))) } + 2f
        val rows = PAD * 2f + ROW_PAD * 2f + keyWidth + 10f + nameWidth + 6f + CHIP_WIDTH + CHIP_MORPH
        var header = PAD * 2f + 26f + 8f + KeybindFonts.headline.width("Keybinds")
        if (ModuleNeoKeybinds.showCount) header += 10f + KeybindFonts.micro.width("${model.entries.size}/${model.entries.size}") + 14f + 8f
        return max(132f, max(rows, header))
    }
    private fun cardHeight(): Float {
        var rows = 0f; var visibleRows = 0
        for (entry in model.entries) {
            val show = entry.show.value().coerceIn(0f, 1f)
            if (show > .001f) { rows += (ROW_HEIGHT + ROW_GAP) * show; visibleRows++ }
        }
        if (visibleRows > 0) rows -= ROW_GAP else rows = ROW_HEIGHT
        return PAD * 2f + HEADER + 10f + rows
    }
    private fun handleDrag(chat: Boolean, screenWidth: Int, screenHeight: Int, mouseDown: Set<Int>) {
        // MouseHandler.isLeftPressed is only updated when no screen is open in 26.3. Use the
        // independent MouseButtonEvent listener instead, otherwise dragging in chat never starts.
        if (!chat || InputConstants.MOUSE_BUTTON_LEFT !in mouseDown) { dragging = false; return }
        val mx = MouseHandler.getScaledXPos(mc.window, mc.mouseHandler.xpos()).toFloat()
        val my = MouseHandler.getScaledYPos(mc.window, mc.mouseHandler.ypos()).toFloat()
        if (dragging) {
            ModuleNeoKeybinds.positionX = ((mx - dragX - MARGIN) / max(1f, screenWidth - w - EDGE) * 100f).coerceIn(0f, 100f)
            ModuleNeoKeybinds.positionY = ((my - dragY - MARGIN) / max(1f, screenHeight - h - EDGE) * 100f).coerceIn(0f, 100f)
        } else if (mx >= x && mx < x + w && my >= y && my < y + h) {
            dragging = true; dragX = mx - x; dragY = my - y
        }
    }
    private fun pressed(bind: InputBind, mouse: Set<Int>): Boolean {
        if (bind.isUnbound || bind.modifiers.any { !it.isAnyPressed }) return false
        val key = bind.boundKey
        return when (key.type) {
            InputConstants.Type.KEYBOARD -> key.value in 1..511 && InputConstants.isKeyDown(key.value)
            InputConstants.Type.MOUSE -> key.value in mouse
        }
    }
    private fun strip(text: String): String = text.replace(Regex("§."), "")
    private fun label(bind: InputBind): String {
        val key = bind.boundKey
        val name = if (key.type == InputConstants.Type.MOUSE) {
            "MB" + when (key.value) { 1 -> 0; 3 -> 1; 2 -> 2; else -> key.value - 1 }
        } else {
            val raw = key.name.removePrefix("key.keyboard.").uppercase(Locale.ROOT)
            when (raw) {
                "LEFT.CONTROL" -> "LCTRL"; "RIGHT.CONTROL" -> "RCTRL"; "LEFT.ALT" -> "LALT"; "RIGHT.ALT" -> "RALT"
                "LEFT.SHIFT" -> "LSHIFT"; "RIGHT.SHIFT" -> "RSHIFT"; "LEFT.WIN" -> "LWIN"; "RIGHT.WIN" -> "RWIN"
                "KEYPAD.DIVIDE" -> "/"; "KEYPAD.MULTIPLY" -> "*"; "KEYPAD.SUBTRACT" -> "-"; "KEYPAD.ADD" -> "+"; "KEYPAD.PERIOD" -> "."
                "CAPS.LOCK" -> "CAPS"; "ENTER" -> "ENTER"; "BACKSPACE" -> "BKSP"; "ESCAPE" -> "ESC"
                "PAGE.UP" -> "PGUP"; "PAGE.DOWN" -> "PGDN"; "INSERT" -> "INS"; "DELETE" -> "DEL"
                "APOSTROPHE" -> "'"; "GRAVE.ACCENT" -> "`"; "BACKSLASH" -> "\\"; "SEMICOLON" -> ";"
                "LEFT.BRACKET" -> "["; "RIGHT.BRACKET" -> "]"; "EQUAL" -> "="; "MINUS" -> "-"
                "COMMA" -> ","; "PERIOD" -> "."; "SLASH" -> "/"
                else -> if (raw.startsWith("KEYPAD.") && raw.length == 8 && raw.last().isDigit())
                    "NUM" + raw.last() else raw.take(8).ifEmpty { "?" }
            }
        }
        val modifiers = bind.modifiers.map { when (it) { InputBind.Modifier.CONTROL -> "CTRL"; InputBind.Modifier.SHIFT -> "SHIFT"; InputBind.Modifier.ALT -> "ALT"; InputBind.Modifier.SUPER -> "SUPER" } }
        return (modifiers + name).joinToString("+")
    }
}
