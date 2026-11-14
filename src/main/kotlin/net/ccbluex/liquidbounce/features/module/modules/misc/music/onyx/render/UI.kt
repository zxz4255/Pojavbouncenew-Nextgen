package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.LegacyText
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Immediate-mode renderer: everything the player draws goes through here.
 *
 * <p>One to one port of NeoRaven's `gui.onyx.render.UI` (itself Onyx's `render.Sampler0`). The parts
 * that decide how the player *looks* are unchanged: the alpha stack, the scissor stack, the
 * translate/scale semantics (including the `pixel /= factor` anti-aliasing bookkeeping), the
 * `text`/`textTop`/`textBaseline` baseline maths with its `- 7 * scale` line-box correction, the
 * Material icon centring, the binary-search `truncate`, and every radius/elevation/shadow number.</p>
 *
 * <p>The original drew through GL11 immediate mode and three hand-written shaders. LiquidBounce's
 * 26.3 GUI has no fixed-function path, so the *transport* is different while the geometry is the
 * same: rounded rectangles go through the client's SDF rounded-rect draw, textures and glyphs through
 * its textured blit. Two consequences, both honest limits of that transport and both flagged in the
 * module README:</p>
 * <ul>
 *   <li>`softness &gt; 0` (the soft edge the original got from `onyx_shape`'s `uSoftness`) does not
 *       exist in the stock rounded rect, so [shadow] emulates it by stacking concentric shells whose
 *       combined coverage adds up to the same two alphas the original used.</li>
 *   <li>[imageRounded] cannot mask on the fly: the corner mask is baked into the cover texture by
 *       `CoverCache` (exactly how the previous module handled it), so this call issues a plain blit.</li>
 * </ul>
 */
class UI(private val ctx: GuiGraphicsExtractor) {

    /** Design-space transform: screen = local * scale + offset. */
    private var scale: Float = 1.0F
    private var offsetX: Float = 0.0F
    private var offsetY: Float = 0.0F

    private var alpha: Float = 1.0F
    private var pixel: Float = 1.0F

    private val savedAlpha = ArrayList<Float>()
    private val savedPixel = ArrayList<Float>()
    private val savedTransform = ArrayList<FloatArray>()
    private data class Clip(val rect: IntArray, val pushed: Boolean)
    private val scissors = ArrayList<Clip>()

    // ------------------------------------------------------------ frame state

    fun begin() {
        pixel = 1.0F
        alpha = 1.0F
        scale = 1.0F
        offsetX = 0.0F
        offsetY = 0.0F
        savedAlpha.clear()
        savedPixel.clear()
        savedTransform.clear()
    }

    fun end() {
        while (scissors.isNotEmpty()) {
            scissorPop()
        }
        alpha = 1.0F
        pixel = 1.0F
    }

    fun alpha(): Float = alpha

    /** Multiplies the current alpha and remembers the previous value. */
    fun alphaPush(factor: Float) {
        savedAlpha.add(alpha)
        alpha = Material.clamp(alpha * factor, 0.0F, 1.0F)
    }

    /** Restores the alpha saved by the matching [alphaPush]. */
    fun alphaPop() {
        if (savedAlpha.isNotEmpty()) {
            alpha = savedAlpha.removeAt(savedAlpha.size - 1)
        }
    }

    fun push() {
        savedPixel.add(pixel)
        savedTransform.add(floatArrayOf(scale, offsetX, offsetY))
    }

    fun pop() {
        if (savedTransform.isNotEmpty()) {
            val t = savedTransform.removeAt(savedTransform.size - 1)
            scale = t[0]
            offsetX = t[1]
            offsetY = t[2]
        }
        if (savedPixel.isNotEmpty()) {
            pixel = savedPixel.removeAt(savedPixel.size - 1)
        }
    }

    fun translate(x: Float, y: Float) {
        offsetX += x * scale
        offsetY += y * scale
    }

    /** Scales everything drawn afterwards around a point, keeping AA pixel size constant. */
    fun scale(factor: Float, centerX: Float, centerY: Float) {
        offsetX += centerX * (1.0F - factor) * scale
        offsetY += centerY * (1.0F - factor) * scale
        scale *= factor
        if (factor > 0.0F) {
            pixel /= factor
        }
    }

    // -------------------------------------------------------------- scissors

    /**
     * Push a logical clip. Minecraft 26.3 turns an off-screen/disjoint intersection into a
     * 0x0 rectangle; submitting *any* GUI draw with that scissor crashes RenderPearl later.
     * Keep empty clips in our logical stack, but never leave them on the extractor stack.
     */
    fun scissorPush(x: Float, y: Float, w: Float, h: Float) {
        val valid = x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite() &&
            (x + w).isFinite() && (y + h).isFinite() && w > 0.0F && h > 0.0F &&
            scale.isFinite() && offsetX.isFinite() && offsetY.isFinite() &&
            screenX(x).isFinite() && screenX(x + w).isFinite() &&
            screenY(y).isFinite() && screenY(y + h).isFinite()
        var rect = if (valid) windowRect(x, y, x + w, y + h) else intArrayOf(0, 0, 0, 0)
        val parent = scissors.lastOrNull()?.rect
        if (parent != null) rect = intersect(rect, parent)
        // Respect an enclosing clip owned by another renderer as well as our own stack.
        val external = ctx.scissorStack.peek()
        if (rect[2] <= 0 || rect[3] <= 0 ||
            (external != null && (external.width() <= 0 || external.height() <= 0))) {
            scissors.add(Clip(intArrayOf(0, 0, 0, 0), false))
            return
        }
        ctx.scissorStack.push(ScreenRectangle(rect[0], rect[1], rect[2], rect[3]))
        val actual = ctx.scissorStack.peek()
        if (actual == null || actual.width() <= 0 || actual.height() <= 0) {
            // Positive inputs can still intersect to nothing with the screen/outer scissor.
            ctx.scissorStack.pop()
            scissors.add(Clip(intArrayOf(0, 0, 0, 0), false))
        } else {
            scissors.add(Clip(rect, true))
        }
    }

    fun scissorPop() {
        if (scissors.isEmpty()) return
        val entry = scissors.removeAt(scissors.size - 1)
        if (entry.pushed) ctx.scissorStack.pop()
        // The vanilla stack already reveals its parent; pushing it again leaks a stack entry.
    }

    /** Whether direct Minecraft item/text extraction is safe inside the current clip. */
    fun clipVisible(): Boolean = visibleScissor()

    private fun visibleScissor(): Boolean {
        val entry = scissors.lastOrNull()
        if (entry != null && (entry.rect[2] <= 0 || entry.rect[3] <= 0)) return false
        val actual = ctx.scissorStack.peek() ?: return true
        return actual.width() > 0 && actual.height() > 0
    }

    // ---------------------------------------------------------------- shapes

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        shape(x, y, w, h, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, color, color, color, color)
    }

    fun rounded(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = Theme.radius(radius, w, h)
        shape(x, y, w, h, r, r, r, r, 0.0F, 0.0F, color, color, color, color)
    }

    fun roundedCorners(
        x: Float, y: Float, w: Float, h: Float,
        topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
        color: Int,
    ) {
        shape(
            x, y, w, h,
            Theme.radius(topLeft, w, h), Theme.radius(topRight, w, h),
            Theme.radius(bottomRight, w, h), Theme.radius(bottomLeft, w, h),
            0.0F, 0.0F, color, color, color, color,
        )
    }

    /** Stroked rounded rectangle (thickness inside the bounds), like Material's outline. */
    fun outline(x: Float, y: Float, w: Float, h: Float, radius: Float, thickness: Float, color: Int) {
        if (thickness <= 0.0F) return
        val r = Theme.radius(radius, w, h)
        shape(x, y, w, h, r, r, r, r, -thickness, 0.0F, color, color, color, color)
    }

    /** Vertical two-colour gradient. */
    fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {
        shape(x, y, w, h, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, top, bottom, bottom, top)
    }

    /** Four-corner gradient confined to a rounded rectangle (hue ramps, HSV planes). */
    fun gradient4(
        x: Float, y: Float, w: Float, h: Float, radius: Float,
        topLeft: Int, bottomLeft: Int, bottomRight: Int, topRight: Int,
    ) {
        val r = Theme.radius(radius, w, h)
        shape(x, y, w, h, r, r, r, r, 0.0F, 0.0F, topLeft, bottomLeft, bottomRight, topRight)
    }

    fun circle(centerX: Float, centerY: Float, radius: Float, color: Int) {
        rounded(centerX - radius, centerY - radius, radius * 2.0F, radius * 2.0F, radius, color)
    }

    /**
     * Single-pass rounded shadow for animated HUD cards. Offset, corner radius, blur width and alpha
     * follow the same local transform and fade as the card; the scissor belongs to the GUI extractor.
     * UV1/UV2 carry fixed-point half sizes and radius at 1/8 GUI pixel precision.
     */
    fun smoothShadow(x: Float, y: Float, w: Float, h: Float, radius: Float, elevation: Int) {
        if (!visibleScissor() || !x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() ||
            !radius.isFinite() || w <= 0f || h <= 0f || !scale.isFinite() || scale <= 0f) return
        val spread = Theme.shadowForLevel(elevation)
        if (spread <= 0f) return
        val sigma = spread * 0.9f * scale
        val halfW = w * scale * 0.5f
        val halfH = h * scale * 0.5f
        val cx = screenX(x) + halfW
        // A slight downward displacement gives the halo a natural material shadow.
        val cy = screenY(y) + halfH + spread * 0.25f * scale
        val reach = sigma * 3.5f
        if (!cx.isFinite() || !cy.isFinite() || !reach.isFinite()) return
        val left = cx - halfW - reach
        val right = cx + halfW + reach
        val top = cy - halfH - reach
        val bottom = cy + halfH + reach
        val ew = (halfW * 8f).roundToInt().coerceIn(1, 32767)
        val eh = (halfH * 8f).roundToInt().coerceIn(1, 32767)
        val er = (Theme.radius(radius, w, h) * scale * 8f).roundToInt().coerceIn(0, 32767)
        val color = applyAlpha(Material.withAlpha(Theme.scheme().shadow, 0.20f))
        if (color ushr 24 == 0) return
        ctx.drawCustomElement(
            pipeline = ClientRenderPipelines.OnyxSoftShadow,
            bounds = ctx.getBounds(left, top, right, bottom),
        ) { pose ->
            addVertexWith2DPose(pose, left, top).setUv(left - cx, top - cy).setColor(color)
                .setUv1(ew, eh).setUv2(er, 0).setLineWidth(sigma)
            addVertexWith2DPose(pose, left, bottom).setUv(left - cx, bottom - cy).setColor(color)
                .setUv1(ew, eh).setUv2(er, 0).setLineWidth(sigma)
            addVertexWith2DPose(pose, right, bottom).setUv(right - cx, bottom - cy).setColor(color)
                .setUv1(ew, eh).setUv2(er, 0).setLineWidth(sigma)
            addVertexWith2DPose(pose, right, top).setUv(right - cx, top - cy).setColor(color)
                .setUv1(ew, eh).setUv2(er, 0).setLineWidth(sigma)
        }
    }

    /** Material drop shadow: the original's two stacked soft shells, emulated with concentric fills. */
    fun shadow(x: Float, y: Float, w: Float, h: Float, radius: Float, elevation: Int) {
        val spread = Theme.shadowForLevel(elevation)
        if (spread <= 0.0F) return
        val shadow = Theme.scheme().shadow
        softShell(x - spread, y - spread, w + spread * 2.0F, h + spread * 2.0F, radius + spread, spread, Material.withAlpha(shadow, 0.055F))
        val second = spread * 1.5F
        softShell(
            x - second, y - second + spread * 0.5F, w + second * 2.0F, h + second * 2.0F,
            radius + second, second, Material.withAlpha(shadow, 0.075F),
        )
    }

    /**
     * A soft-edged rounded rectangle: `feather` shells, each a bit larger and a bit fainter, so the
     * summed coverage at the rim falls off the way the original shader's `smoothstep` did.
     */
    private fun softShell(x: Float, y: Float, w: Float, h: Float, radius: Float, feather: Float, color: Int) {
        val layers = max(1, min(8, feather.roundToInt()))
        val base = Material.alpha(color) / 255.0F
        for (i in layers downTo 1) {
            val outward = feather * i / layers
            val layerAlpha = base * (1.0F / layers) * 1.6F
            val c = Material.withAlpha(color, Material.clamp(layerAlpha, 0.0F, 1.0F))
            fillRounded(x - outward, y - outward, w + outward * 2.0F, h + outward * 2.0F, radius + outward, c)
        }
    }

    /** Fills a rounded rectangle with the (already blurred) backdrop, tinted by `tint`. */
    fun frost(x: Float, y: Float, w: Float, h: Float, radius: Float, tint: Int) {
        frost(x, y, w, h, radius, tint, DEFAULT_TINT_COVERAGE)
    }

    /**
     * Fills a rounded rectangle with the backdrop, tinted by `tint`.
     *
     * <p>`coverage` keeps the original meaning — how much the surface tint covers what is behind it —
     * so the alpha maths is identical. The blurred scene itself needs a framebuffer capture this
     * module cannot own on 26.3, so the fill is the tint at the same coverage over whatever is
     * already on screen (documented in the README as the one visual feature that degrades).</p>
     */
    fun frost(x: Float, y: Float, w: Float, h: Float, radius: Float, tint: Int, coverage: Float) {
        frostCorners(x, y, w, h, radius, radius, radius, radius, tint, coverage)
    }

    fun frostCorners(
        x: Float, y: Float, w: Float, h: Float,
        topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
        tint: Int, coverage: Float,
    ) {
        val color = Material.withAlpha(tint, Material.alpha(tint) / 255.0F * Material.clamp(coverage, 0.0F, 1.0F))
        shape(
            x, y, w, h,
            Theme.radius(topLeft, w, h), Theme.radius(topRight, w, h),
            Theme.radius(bottomRight, w, h), Theme.radius(bottomLeft, w, h),
            0.0F, 0.0F, color, color, color, color,
        )
    }

    // ----------------------------------------------------------------- images

    /** Draws a texture quad with a tint. */
    fun image(texture: Identifier?, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, color: Int) {
        if (texture == null || w <= 0.0F || h <= 0.0F) return
        val c = applyAlpha(color)
        if (c ushr 24 == 0) return
        blitQuad(texture, screenX(x), screenY(y), screenX(x) + w * scale, screenY(y) + h * scale, u0, v0, u1, v1, c, QUAD_UV_RESOLUTION)
    }

    /** Draws a texture with rounded corners (album art); the corner mask is baked into the texture. */
    fun imageRounded(texture: Identifier?, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        if (texture == null || w <= 0.0F || h <= 0.0F) return
        image(texture, x, y, w, h, 0.0F, 0.0F, 1.0F, 1.0F, color)
    }

    // ------------------------------------------------------------------- text

    /** Draws a string with the given alignment, vertically centred on `y`. */
    fun text(style: Typeface.Style, text: String?, x: Float, y: Float, color: Int, align: Int) {
        if (text.isNullOrEmpty()) return
        if (text.indexOf('\u00a7') >= 0) {
            legacy(style, text, x, y, color, align, false)
            return
        }
        var left = x
        val width = style.width(text)
        if (align == ALIGN_CENTER) left -= width / 2.0F else if (align == ALIGN_RIGHT) left -= width
        draw(style, text, left, y + style.ascent() / 2.0F - 7.0F * style.scale(), color)
    }

    /** Draws a string with its top at `y` (matches Onyx's leading-anchored text). */
    fun textTop(style: Typeface.Style, text: String?, x: Float, y: Float, color: Int, align: Int) {
        if (text.isNullOrEmpty()) return
        if (text.indexOf('\u00a7') >= 0) {
            legacy(style, text, x, y, color, align, true)
            return
        }
        var left = x
        val width = style.width(text)
        if (align == ALIGN_CENTER) left -= width / 2.0F else if (align == ALIGN_RIGHT) left -= width
        draw(style, text, left, y + style.ascent() - 7.0F * style.scale(), color)
    }

    /**
     * Draws a string on an explicit baseline. The type scale's own vertical maths assumes a 14px line
     * box, which is meaningless for the 42px clock, so oversized text is positioned by its baseline.
     */
    fun textBaseline(style: Typeface.Style, text: String?, x: Float, baseline: Float, color: Int, align: Int) {
        if (text.isNullOrEmpty()) return
        var left = x
        val width = style.width(text)
        if (align == ALIGN_CENTER) left -= width / 2.0F else if (align == ALIGN_RIGHT) left -= width
        draw(style, text, left, baseline, color)
    }

    /** Draws a Material Icons glyph centred on `x`, baseline-relative to `y`. */
    fun icon(codepoint: String, x: Float, y: Float, size: Float, color: Int) {
        val icons = Typeface.ICONS
        val iconScale = size / 16.0F
        val width = icons.width(codepoint) * iconScale
        val c = applyAlpha(color)
        if (c ushr 24 == 0) return
        run(icons, codepoint, screenX(x - width / 2.0F), screenY(y + iconScale), scale * iconScale, c)
    }

    fun width(style: Typeface.Style, text: String?): Float = style.width(text)

    /** Shortens text with an ellipsis so it fits into `maxWidth`. */
    fun truncate(style: Typeface.Style, text: String?, maxWidth: Float): String? {
        if (text.isNullOrEmpty()) return text
        // Keep the colour codes intact and measure only the visible glyphs.
        if (text.indexOf('\u00a7') >= 0) return LegacyText.truncate(style, text, maxWidth)
        if (style.width(text) <= maxWidth) return text
        val ellipsis = "..."
        var limit = maxWidth - style.width(ellipsis)
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (style.width(text.substring(0, mid)) <= limit) low = mid else high = mid - 1
        }
        return text.substring(0, low) + ellipsis
    }

    /** Wraps text into at most `maxLines` lines of `maxWidth`. */
    fun wrap(style: Typeface.Style, text: String, maxWidth: Float, maxLines: Int): List<String> {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        for (word in text.split(" ")) {
            if (word.isEmpty()) continue
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (style.width(candidate) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current.setLength(0)
                current.append(word)
                if (lines.size == maxLines) {
                    current.setLength(0)
                    break
                }
            } else {
                current.setLength(0)
                current.append(candidate)
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    fun scheme(): Scheme = Theme.scheme()

    // ------------------------------------------------------------- internals

    /**
     * Core shape primitive. Corner radii and colours are the original's; `softness`/`expansion`
     * follow its sign convention (negative = inner stroke of that width, positive = soft edge).
     */
    private fun shape(
        x: Float, y: Float, w: Float, h: Float,
        radiusTopLeft: Float, radiusTopRight: Float, radiusBottomRight: Float, radiusBottomLeft: Float,
        softness: Float, expansion: Float,
        colorTopLeft: Int, colorBottomLeft: Int, colorBottomRight: Int, colorTopRight: Int,
    ) {
        if (w <= 0.0F || h <= 0.0F || !visibleScissor()) return
        val c0 = applyAlpha(colorTopLeft)
        val c1 = applyAlpha(colorBottomLeft)
        if ((c0 or c1) ushr 24 == 0) return

        val x0 = x - expansion
        val y0 = y - expansion
        val x1 = x + w + expansion
        val y1 = y + h + expansion
        // A negative softness is a stroke of that thickness drawn inside the bounds.
        if (softness < 0.0F) {
            val thickness = -softness
            ctx.drawRoundedRect(
                screenX(x0), screenY(y0), screenX(x1), screenY(y1),
                max(radiusTopLeft, max(radiusTopRight, max(radiusBottomRight, radiusBottomLeft))) * scale,
                Color4b(0, 0, 0, 0),
                null,
                Color4b(c0),
                thickness * scale,
            )
            return
        }
        if (softness > 0.0F) {
            // Soft-edged: the shell emulation owns the shape, see softShell().
            softShell(x0, y0, x1 - x0, y1 - y0, max(radiusTopLeft, 0.0F), softness, c0)
            return
        }
        ctx.drawRoundedRect(
            screenX(x0), screenY(y0), screenX(x1), screenY(y1),
            max(radiusTopLeft, max(radiusTopRight, max(radiusBottomRight, radiusBottomLeft))) * scale,
            Color4b(c0),
            Color4b(c1),
            Color4b.TRANSPARENT,
            0.0F,
        )
    }

    private fun fillRounded(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        if (w <= 0.0F || h <= 0.0F || !visibleScissor()) return
        ctx.drawRoundedRect(
            screenX(x), screenY(y), screenX(x + w), screenY(y + h),
            Theme.radius(radius, w, h) * scale,
            Color4b(color), null, Color4b.TRANSPARENT, 0.0F,
        )
    }

    /** Draws at an explicit baseline through the type scale's run splitter. */
    private fun draw(style: Typeface.Style, text: String, x: Float, baseline: Float, color: Int) {
        if (!visibleScissor()) return
        val c = applyAlpha(color)
        if (c ushr 24 == 0) return
        val styleScale = style.scale()
        val originX = screenX(x)
        val originY = screenY(baseline)
        // Screen pixels per unit of the *primary* face's nominal size. A run's own unit is this
        // times its relative scale, exactly like the original's matrix stack:
        //   screen = offset + scale * (origin + styleScale * (runX + relative * glyph))
        val baseUnit = scale * styleScale
        style.draw(
            text, c,
            Typeface.TextSink { font, part, runX, relative, col ->
                run(font, part, originX + baseUnit * runX, originY, baseUnit * relative, col)
            },
        )
    }

    /**
     * Emits one run of glyphs.
     *
     * <p>`originX`/`originY` are screen coordinates of the run's pen origin and baseline; `unit` is
     * screen pixels per unit of the run face's own nominal size. The `PADDING - 1` vertical offset is
     * the original's, kept because the layout was tuned against it.</p>
     */
    private fun run(font: TtfFont, text: String, originX: Float, originY: Float, unit: Float, color: Int) {
        if (!visibleScissor()) return
        // Rasterise every glyph first (the original did the same), then push the page to VRAM once.
        for (ch in text) font.glyph(ch)
        font.flush()
        val texture = font.textureId() ?: return
        val textureSetup = mc.textureManager.getTexture(texture).textureSetup
        var caret = 0.0F
        for (ch in text) {
            val glyph = font.glyph(ch)
            if (!glyph.isEmpty()) {
                val x0 = originX + (caret + glyph.bearingX) * unit
                val y0 = originY + (TtfFont.GLYPH_TOP_OFFSET + glyph.bearingY) * unit
                // Keep subpixel glyph positions and full UV precision. Integer blits quantize
                // each corner independently in GUI coordinates before the window scale is applied.
                ctx.drawTexQuad(
                    textureSetup,
                    x0, y0, x0 + glyph.width * unit, y0 + glyph.height * unit,
                    glyph.u0, glyph.v0, glyph.u1, glyph.v1, color,
                )
            }
            caret += glyph.advance
        }
    }

    /** Draws a string that still carries Minecraft `§` formatting. */
    private fun legacy(style: Typeface.Style, text: String, x: Float, y: Float, color: Int, align: Int, top: Boolean) {
        val runs = LegacyText.parse(text, color)
        if (runs.isEmpty()) return
        var total = 0.0F
        for (r in runs) total += style.width(r.text)
        var cursor = when (align) {
            ALIGN_CENTER -> x - total / 2.0F
            ALIGN_RIGHT -> x - total
            else -> x
        }
        val shift = if (top) style.ascent() / 2.0F else 0.0F
        for (r in runs) {
            val runWidth = style.width(r.text)
            if (top) {
                textTop(style, r.text, cursor, y, r.color, ALIGN_LEFT)
            } else {
                text(style, r.text, cursor, y, r.color, ALIGN_LEFT)
            }
            if (r.strikethrough) rect(cursor, y - 0.5F + shift, runWidth, 1.0F, r.color)
            if (r.underline) rect(cursor, y + style.ascent() * 0.5F + 1.0F + shift, runWidth, 1.0F, r.color)
            cursor += runWidth
        }
    }

    private fun applyAlpha(color: Int): Int =
        Material.withAlpha(color, Material.alpha(color) / 255.0F * alpha)

    /**
     * Texture quad through the client's textured GUI blit.
     *
     * <p>The original issued `glTexCoord2f(u, v)` with normalised coordinates; 26.3's `blit` takes
     * absolute texels, so the UV range is projected onto a [texSize]-texel square. With
     * [QUAD_UV_RESOLUTION] that snapping is 1/8192 of the texture - far below a texel of any sprite
     * this module ships - and the quad corners are rounded to whole screen pixels the way the GUI
     * renderer itself does.</p>
     */
    private fun blitQuad(
        texture: Identifier,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: Int,
        texSize: Int,
    ) {
        if (!visibleScissor()) return
        val left = min(x0, x1).roundToInt()
        val top = min(y0, y1).roundToInt()
        val right = max(x0, x1).roundToInt()
        val bottom = max(y0, y1).roundToInt()
        if (right <= left || bottom <= top) return
        ctx.blit(
            RenderPipelines.GUI_TEXTURED,
            texture,
            left, top,
            u0 * texSize, v0 * texSize,
            right - left, bottom - top,
            max(1, ((u1 - u0) * texSize).roundToInt()),
            max(1, ((v1 - v0) * texSize).roundToInt()),
            texSize, texSize,
            color,
        )
    }

    private fun screenX(x: Float): Float = x * scale + offsetX

    private fun screenY(y: Float): Float = y * scale + offsetY

    private fun windowRect(x0: Float, y0: Float, x1: Float, y1: Float): IntArray {
        val a = screenX(x0)
        val b = screenX(x1)
        val c = screenY(y0)
        val d = screenY(y1)
        val x = min(a, b).roundToInt()
        val y = min(c, d).roundToInt()
        val right = max(a, b).roundToInt()
        val bottom = max(c, d).roundToInt()
        return intArrayOf(x, y, max(0, right - x), max(0, bottom - y))
    }

    private fun intersect(a: IntArray, b: IntArray): IntArray {
        val x = max(a[0], b[0])
        val y = max(a[1], b[1])
        val right = min(a[0] + a[2], b[0] + b[2])
        val bottom = min(a[1] + a[3], b[1] + b[3])
        return intArrayOf(x, y, max(0, right - x), max(0, bottom - y))
    }

    companion object {

        /** Texel resolution used to express normalised texture coordinates to [blitQuad]. */
        private const val QUAD_UV_RESOLUTION = 8192

        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2

        /** Panel tint coverage used by the frosted panels (the blurred scene supplies 12%). */
        const val DEFAULT_TINT_COVERAGE = 0.88F
    }
}
