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
package net.ccbluex.liquidbounce.ui.hud.components

import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.FileDialogMode
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.hud.HudComponent
import net.ccbluex.liquidbounce.ui.hud.HudSize
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 19. Text (`text.json` + `Text.svelte`: multi-instance custom text with `{...}` placeholders, Decorations, Shadow, Glow)
 */
class TextHudComponent : HudComponent(
    name = "Text",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.CENTER, 0, Alignment.ScreenAxisY.CENTER, 0),
    componentDescription = "Displays customizable text.",
    singleton = false,
) {
    private val rawText by text("Text", "Text")
    private val textColor by color("Color", Color4b.WHITE)
    private val fontFamily by text("Font", "Inter")
    private val fontSize by int("Size", 14, 1..100, "px")

    private val decorations = tree(object : ValueGroup("Decorations") {
        val bold by boolean("Bold", false)
        val italic by boolean("Italic", false)
        val underline by boolean("Underline", false)
        val strikethrough by boolean("Strikethrough", false)
    })

    private val shadowGroup = tree(object : ToggleableValueGroup(this, "Shadow", false) {
        val offsetX by int("OffsetX", 0, -10..10, "px")
        val offsetY by int("OffsetY", 0, -10..10, "px")
        val blurRadius by int("BlurRadius", 0, 0..10, "px")
        val shadowColor by color("Color", Color4b.BLACK)
    })

    private val glowGroup = tree(object : ToggleableValueGroup(this, "Glow", false) {
        val radius by int("Radius", 0, 0..10, "px")
        val glowColor by color("Color", Color4b.WHITE)
    })

    companion object {
        private val PLACEHOLDER_REGEX = Regex("\\{([A-Za-z0-9_.]+)\\}")
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    private fun resolvePlaceholder(key: String): String? {
        val p = mc.player
        return when (key.lowercase(Locale.US)) {
            "fps" -> mc.fps.toString()
            "username", "name", "player" -> mc.user.name
            "client", "clientname" -> LiquidBounce.CLIENT_NAME
            "version", "clientversion" -> LiquidBounce.clientVersion
            "time" -> LocalTime.now().format(TIME_FMT)
            "server", "ip" -> mc.currentServer?.ip ?: if (mc.isLocalServer) {
                UiLocalization.text("Singleplayer", "单人游戏")
            } else {
                UiLocalization.text("Menu", "主菜单")
            }
            "ping" -> {
                val info = p?.let { mc.connection?.getPlayerInfo(it.uuid) }
                (info?.latency ?: 0).toString()
            }
            "health", "actualhealth" -> p?.let { String.format(Locale.US, "%.1f", it.health) } ?: "20.0"
            "maxhealth" -> p?.let { String.format(Locale.US, "%.1f", it.maxHealth) } ?: "20.0"
            "food" -> (p?.foodData?.foodLevel ?: 20).toString()
            "armor" -> (p?.armorValue ?: 0).toString()
            "x", "position.x" -> p?.let { String.format(Locale.US, "%.1f", it.x) } ?: "0.0"
            "y", "position.y" -> p?.let { String.format(Locale.US, "%.1f", it.y) } ?: "0.0"
            "z", "position.z" -> p?.let { String.format(Locale.US, "%.1f", it.z) } ?: "0.0"
            "bps", "speed" -> {
                if (p == null) "0.00" else {
                    val dx = p.x - p.xo
                    val dz = p.z - p.zo
                    String.format(Locale.US, "%.2f", hypot(dx, dz) * 20.0)
                }
            }
            else -> null
        }
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val baseText = if (rawText == "Text" && UiLocalization.isChinese) "自定义文本" else rawText
        val resolved = PLACEHOLDER_REGEX.replace(baseText) { m ->
            resolvePlaceholder(m.groupValues[1]) ?: m.value
        }

        val weight = when {
            fontFamily.contains("mono", ignoreCase = true) -> UiFontWeight.MONOSPACE
            decorations.bold -> UiFontWeight.BOLD
            else -> UiFontWeight.REGULAR
        }
        val sz = fontSize.toFloat().coerceAtLeast(1f)
        val tw = max(8f, UiDraw.measureText(resolved, sz, weight, decorations.italic))
        val lh = max(8f, UiDraw.lineHeight(sz, weight))

        if (glowGroup.enabled && glowGroup.radius > 0) {
            val r = glowGroup.radius.toFloat()
            val ga = UiPalette.withAlphaFactor(glowGroup.glowColor.argb, 0.28f)
            UiDraw.drawText(ctx, resolved, -r * 0.5f, 0f, sz, ga, weight, decorations.italic)
            UiDraw.drawText(ctx, resolved, r * 0.5f, 0f, sz, ga, weight, decorations.italic)
            UiDraw.drawText(ctx, resolved, 0f, -r * 0.5f, sz, ga, weight, decorations.italic)
            UiDraw.drawText(ctx, resolved, 0f, r * 0.5f, sz, ga, weight, decorations.italic)
        }

        if (shadowGroup.enabled) {
            UiDraw.drawText(
                ctx = ctx,
                text = resolved,
                x = shadowGroup.offsetX.toFloat(),
                y = shadowGroup.offsetY.toFloat(),
                fontSizePx = sz,
                argb = shadowGroup.shadowColor.argb,
                weight = weight,
                italic = decorations.italic,
            )
        }

        UiDraw.drawText(
            ctx = ctx,
            text = resolved,
            x = 0f,
            y = 0f,
            fontSizePx = sz,
            argb = textColor.argb,
            weight = weight,
            italic = decorations.italic,
            underline = decorations.underline,
            strikethrough = decorations.strikethrough,
        )

        return HudSize(tw, lh)
    }
}

/**
 * 20. Image (`image.json` + `Image.svelte`: multi-instance custom image from local File or URL)
 */
class ImageHudComponent : HudComponent(
    name = "Image",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.CENTER_TRANSLATED, 0, Alignment.ScreenAxisY.CENTER_TRANSLATED, 0),
    componentDescription = "Displays a custom image from a URL or local file.",
    singleton = false,
) {
    private val sourceMode: ModeValueGroup<Mode> = modes(this, "Source") { modeGroup ->
        val urlMode = object : Mode("URL") {
            override val parent: ModeValueGroup<*> get() = modeGroup
            val url by text("URL", "https://liquidbounce.net/images/logos/logo.svg")
        }
        val fileMode = object : Mode("File") {
            override val parent: ModeValueGroup<*> get() = modeGroup
            val file by file(
                "File",
                null,
                dialogMode = FileDialogMode.OPEN_FILE,
                supportedExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "svg"),
            )
        }
        arrayOf(urlMode, fileMode)
    }

    private val imageScale by float("Scale", 1.0f, 0.0f..10.0f)
    private val opacity by int("Opacity", 100, 0..100, "%")

    private var loadedKey: String = ""
    private var loadingKey: String = ""
    private var dynamicTexId: Identifier? = null
    private var dynamicTex: DynamicTexture? = null
    private var imgWidth: Int = 165
    private var imgHeight: Int = 62

    private fun resolveSourceSpec(): Pair<Boolean, String> {
        val active = sourceMode.activeMode
        return if (active.name.equals("File", ignoreCase = true)) {
            val fv = active.inner.firstOrNull { it.name == "File" } as? net.ccbluex.liquidbounce.config.types.FileValue
            val path = fv?.absoluteFile?.takeIf { it.isFile }?.absolutePath.orEmpty()
            true to path
        } else {
            val uv = active.inner.firstOrNull { it.name == "URL" }?.get() as? String
            false to uv.orEmpty().trim()
        }
    }

    private fun ensureTextureLoaded(isFile: Boolean, spec: String) {
        val key = "$isFile|$spec"
        if (key == loadedKey || key == loadingKey) return
        if (spec.isBlank() || spec.endsWith("liquidbounce.net/images/logos/logo.svg")) {
            loadedKey = key
            dynamicTexId = null
            return
        }

        loadingKey = key
        CompletableFuture.runAsync {
            val bytes = runCatching {
                if (isFile) {
                    File(spec).readBytes()
                } else {
                    URI.create(spec).toURL().openStream().use { it.readBytes() }
                }
            }.getOrNull()

            val nativeImg = bytes?.let {
                runCatching { NativeImage.read(ByteArrayInputStream(it)) }.getOrNull()
            }

            mc.execute {
                loadingKey = ""
                loadedKey = key
                if (nativeImg != null) {
                    dynamicTex?.close()
                    val tex = DynamicTexture({ "LB-HudImage-$id" }, nativeImg)
                    val texId = Identifier.fromNamespaceAndPath("liquidbounce", "dynamic/hud_image_${id.toString().lowercase(Locale.US)}")
                    mc.textureManager.register(texId, tex)
                    dynamicTex = tex
                    dynamicTexId = texId
                    imgWidth = max(1, nativeImg.width)
                    imgHeight = max(1, nativeImg.height)
                } else {
                    dynamicTexId = null
                }
            }
        }
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val (isFile, spec) = resolveSourceSpec()
        ensureTextureLoaded(isFile, spec)

        val s = imageScale.takeIf { it > 0.01f } ?: 1f
        val alphaFactor = (opacity.coerceIn(0, 100) / 100f)
        val tint = UiPalette.white(alphaFactor)

        val texId = dynamicTexId
        if (texId != null) {
            var drawW = imgWidth * s
            var drawH = imgHeight * s
            val maxVisualH = hudHeight * 0.70f
            if (drawH > maxVisualH && drawH > 1f) {
                val clampRatio = maxVisualH / drawH
                drawW *= clampRatio
                drawH *= clampRatio
            }
            UiDraw.drawRoundedTexture(ctx, texId, 0f, 0f, drawW, drawH, radius = 0f, argb = tint, pixelated = false)
            return HudSize(max(16f, drawW), max(16f, drawH))
        }

        // Default fallback: bundled LiquidBounce logo (`https://liquidbounce.net/images/logos/logo.svg`)
        val w = 165f * s
        val h = 61.8917f * s
        UiDraw.drawIconStretched(ctx, UiIcons.WATERMARK_BADGE, 0f, 0f, w, h, UiPalette.withAlphaFactor(UiPalette.accent(1f), alphaFactor))
        UiDraw.drawIconStretched(ctx, UiIcons.WATERMARK_FG, 0f, 0f, w, h, tint)
        return HudSize(max(16f, w), max(16f, h))
    }
}
