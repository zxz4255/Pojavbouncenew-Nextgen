package net.ccbluex.liquidbounce.features.module.modules.render.targethud

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Anim
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Blur
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Ease
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Material 3 Expressive target card — a 1:1 port of NeoRaven's `gui/onyx/hud/TargetHud`.
 *
 * <p>The card is the same rounded surface with the avatar, the name, a distance chip, the health bar
 * (with its lagging "damage" trail, the absorption overlay and the primary→error ramp below 30%) and
 * the armour readout. Its behaviour is the original's:</p>
 *
 * <ul>
 *   <li>it follows the entity the client is currently attacking, anchored on the target's body
 *       centre while the target is on screen;</li>
 *   <li>when the target disappears the card holds its last position for the configured linger time
 *       and then fades out;</li>
 *   <li>when the target switches, the new information is shown first and the card only starts moving
 *       after a short hold, so it never cross-fades while sliding;</li>
 *   <li>when the target is off screen (behind the camera or outside the viewport) the card glides
 *       back to the configured home position.</li>
 * </ul>
 *
 * <p>Only the transport changed: the original drew through GL11 + its own shaders, this draws through
 * the ported Onyx [UI] on top of `GuiGraphicsExtractor`. Everything that decides the look — every
 * number below — is the original's. The two transport consequences are the same ones the music player
 * documents: `Blur.ready()` is always false on 26.3 (so the frosted layer is skipped and the palette
 * surface colour shows instead), and the avatar is a plain quad inside a rounded placeholder,
 * exactly like the original.</p>
 */
object TargetHud {

    private const val MARGIN = 10.0F
    private const val EDGE = 20.0F
    private const val PAD = 9.0F
    private const val AVATAR = 30.0F
    private const val AVATAR_RADIUS = 10.0F
    private const val AVATAR_GAP = 9.0F
    private const val MIN_WIDTH = 152.0F
    private const val MAX_WIDTH = 232.0F
    private const val MIN_BAR_WIDTH = 86.0F
    private const val HEIGHT = 48.0F
    private const val RADIUS = 16.0F
    private const val NAME_Y = 16.0F
    private const val CHIP_HEIGHT = 14.0F
    private const val BAR_Y = 24.0F
    private const val BAR_HEIGHT = 6.0F
    private const val STATS_Y = 37.0F
    private const val BLUR_COVERAGE = 0.62F
    private const val OFF_SCREEN_MARGIN = 28.0F
    private const val SWITCH_HOLD_MS = 160L

    // ------------------------------------------------------------------ state

    private val visible = Anim(0.0F)
    private val width = Anim(MIN_WIDTH)
    private val content = Anim(1.0F)
    private val healthFill = Anim(0.0F)
    private val healthLag = Anim(0.0F)
    private val lowHealth = Anim(0.0F)

    private var entity: LivingEntity? = null
    private var lostAt = 0L
    private var switchedAt = 0L
    private var placed = false

    private var name = ""
    private var health = 0.0F
    private var maxHealth = 20.0F
    private var absorption = 0.0F
    private var armor = 0
    private var distance = 0.0
    private var skin: Identifier? = null

    private var x = 0.0F
    private var y = 0.0F
    private var w = MIN_WIDTH
    private var h = HEIGHT

    private var lastFrame = 0L

    // ---------------------------------------------------------------- render

    /**
     * Draws (and advances) the card.
     *
     * @param preview keeps it visible and pinned to the home position for the position editor.
     */
    fun render(ctx: GuiGraphicsExtractor, module: ModuleTargetHud, preview: Boolean, partialTicks: Float) {
        val delta = tick()
        val now = System.currentTimeMillis()
        val chatOpen = mc.gui.screen() is net.minecraft.client.gui.screens.ChatScreen

        updateTarget(module, preview, now, delta)

        val active = (preview || module.enabled) && entity != null
        visible.to(
            if (active) 1.0F else 0.0F,
            if (active) 180.0F else 200.0F,
            if (active) Ease.EMPHASIZED else Ease.EMPHASIZED_ACCELERATE,
        )
        visible.update(delta)
        var fade = visible.value()
        if (fade <= 0.002F) {
            if (!preview) {
                clearContent()
                return
            }
            fade = 1.0F
        }

        val scheme = Theme.scheme()
        val screenW = ctx.guiWidth().toFloat()
        val screenH = ctx.guiHeight().toFloat()

        val targetWidth = cardWidth(module)
        width.to(targetWidth, 220.0F, Ease.EMPHASIZED_DECELERATE)
        width.update(delta)
        val layoutWidth = width.value()
        this.w = layoutWidth * module.scale()
        this.h = HEIGHT * module.scale()

        updatePosition(module, screenW, screenH, preview, chatOpen, now, delta, partialTicks)

        val ui = UI(ctx)
        ui.begin()
        try {
            if (module.frosted() && fade > 0.01F) {
                Blur.prepare()
            }
            ui.alphaPush(fade)
            ui.push()
            ui.translate(x + w / 2.0F, y + h / 2.0F)
            ui.scale(module.scale() * (0.96F + 0.04F * fade), 0.0F, 0.0F)
            ui.translate(-layoutWidth / 2.0F, -HEIGHT / 2.0F)

            drawCard(ui, module, scheme, layoutWidth)

            ui.pop()
            ui.alphaPop()
        } finally {
            ui.end()
        }
    }

    // -------------------------------------------------------- target tracking

    private fun updateTarget(module: ModuleTargetHud, preview: Boolean, now: Long, delta: Float) {
        val target = if (preview) mc.player else TargetHudLayer.currentTarget()
        if (target != null) {
            if (target !== entity) {
                val switching = entity != null
                entity = target
                switchedAt = now
                // A switch shows the new info immediately; only the movement is held back.
                content.set(if (switching) 0.2F else 0.55F)
                content.to(1.0F, if (switching) 180.0F else 220.0F, Ease.DECELERATE)
                healthFill.set(healthRatio(target))
                healthLag.set(healthRatio(target))
                if (!switching) {
                    placed = false
                }
            }
            lostAt = 0L
            refresh(target)
        } else if (entity != null) {
            if (lostAt == 0L) {
                lostAt = now
            }
            if (now - lostAt >= (module.linger() * 1000.0F).toLong()) {
                entity = null
            }
        }
        content.update(delta)
        healthFill.update(delta)
        healthLag.update(delta)
        lowHealth.to(if (healthRatio() <= 0.3F) 1.0F else 0.0F, 250.0F, Ease.DECELERATE)
        lowHealth.update(delta)
    }

    private fun refresh(target: LivingEntity) {
        name = target.name.string
        health = target.health
        maxHealth = max(target.maxHealth, 1.0F)
        absorption = max(target.absorptionAmount, 0.0F)
        armor = target.armorValue
        skin = skinOf(target)
        val local = mc.player
        distance = if (local != null && target !== local) local.distanceTo(target) else 0.0
        healthFill.to(healthRatio(), 250.0F, Ease.DECELERATE)
        healthLag.to(healthRatio(), 650.0F, Ease.DECELERATE)
    }

    /** The player's skin sheet, or null for every non-player target. */
    private fun skinOf(target: LivingEntity): Identifier? {
        if (target !is Player) return null
        return try {
            val playerSkin = if (target is AbstractClientPlayer) target.skin else DefaultPlayerSkin.get(target.gameProfile)
            playerSkin.body().texturePath()
        } catch (t: Throwable) {
            null
        }
    }

    private fun healthRatio(target: LivingEntity): Float =
        Material.clamp(target.health / max(target.maxHealth, 1.0F), 0.0F, 1.0F)

    private fun healthRatio(): Float = Material.clamp(health / maxHealth, 0.0F, 1.0F)

    // -------------------------------------------------------------- geometry

    private fun cardWidth(module: ModuleTargetHud): Float {
        val chipWidth = if (module.showDistance()) distanceChipWidth() + 8.0F else 0.0F
        val nameWidth = min(Typeface.HEADLINE.width(name), 100.0F)
        val contentWidth = contentX() + max(nameWidth + chipWidth, MIN_BAR_WIDTH) + PAD
        return Material.clamp(contentWidth, MIN_WIDTH, MAX_WIDTH)
    }

    private fun contentX(): Float = PAD + AVATAR + AVATAR_GAP

    private fun distanceChipWidth(): Float = Typeface.MICRO.width(distanceText()) + 10.0F

    private fun distanceText(): String = String.format(Locale.ROOT, "%.1fm", distance)

    // -------------------------------------------------------------- position

    private fun updatePosition(
        module: ModuleTargetHud,
        screenW: Float,
        screenH: Float,
        preview: Boolean,
        chatOpen: Boolean,
        now: Long,
        delta: Float,
        partialTicks: Float,
    ) {
        val maxX = max(0.0F, screenW - w - EDGE)
        val maxY = max(0.0F, screenH - h - EDGE)
        val homeX = MARGIN + maxX * (Material.clamp(module.positionX(), 0.0F, 100.0F) / 100.0F)
        val homeY = MARGIN + maxY * (Material.clamp(module.positionY(), 0.0F, 100.0F) / 100.0F)

        var desiredX = homeX
        var desiredY = homeY
        var follow = false

        val current = entity
        if (!preview && !chatOpen && current != null && lostAt == 0L) {
            val projected = project(current, partialTicks, screenW, screenH)
            if (projected != null) {
                desiredX = Material.clamp(projected[0] - w / 2.0F, MARGIN, max(MARGIN, screenW - w - MARGIN))
                // The card rides on the target's body centre, not above its head, so it stays on the
                // player it describes even when the camera looks down.
                desiredY = Material.clamp(projected[1] - h / 2.0F, MARGIN, max(MARGIN, screenH - h - MARGIN))
                follow = true
            }
        }

        val lingering = lostAt != 0L
        val switching = now - switchedAt < SWITCH_HOLD_MS
        val hidden = current == null

        if (!placed) {
            x = if (preview || chatOpen || !follow) homeX else desiredX
            y = if (preview || chatOpen || !follow) homeY else desiredY
            placed = true
        } else if (hidden || lingering || switching) {
            // Fading out / lingering / switching: keep the position, the card must not drift yet.
        } else {
            val blend = 1.0F - exp(-delta / 70.0F)
            x += (desiredX - x) * blend
            y += (desiredY - y) * blend
        }
    }

    /** Projects the target's body centre into GUI coordinates, or null when it is off screen. */
    private fun project(target: LivingEntity, partialTicks: Float, screenW: Float, screenH: Float): FloatArray? {
        val base = target.getPosition(partialTicks)
        val pos = Vec3(base.x, base.y + target.bbHeight * 0.5, base.z)
        val projected = WorldToScreen.calculateScreenPos(pos) ?: return null
        if (!projected.x.isFinite() || !projected.y.isFinite() || projected.z !in -1.0F..1.0F) return null
        val sx = projected.x
        val sy = projected.y
        if (sx < -OFF_SCREEN_MARGIN || sx > screenW + OFF_SCREEN_MARGIN ||
            sy < -OFF_SCREEN_MARGIN || sy > screenH + OFF_SCREEN_MARGIN
        ) {
            return null
        }
        return floatArrayOf(sx, sy)
    }

    /** Moves the card to a GUI position and stores it as a percentage of the free space. */
    fun dragTo(desiredX: Float, desiredY: Float) {
        val screenW = mc.window.guiScaledWidth.toFloat()
        val screenH = mc.window.guiScaledHeight.toFloat()
        val maxX = max(1.0F, screenW - w - EDGE)
        val maxY = max(1.0F, screenH - h - EDGE)
        val percentX = Material.clamp((desiredX - MARGIN) / maxX * 100.0F, 0.0F, 100.0F)
        val percentY = Material.clamp((desiredY - MARGIN) / maxY * 100.0F, 0.0F, 100.0F)
        ModuleTargetHud.setPosition(percentX, percentY)
        x = desiredX
        y = desiredY
    }

    fun inside(mouseX: Float, mouseY: Float): Boolean =
        mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    fun left(): Float = x

    fun top(): Float = y

    fun width(): Float = w

    fun height(): Float = h

    /** How visible the card is (0 = fully faded out); the renderer uses it to stop drawing. */
    fun alpha(): Float = visible.value()

    // --------------------------------------------------------------- drawing

    private fun drawCard(ui: UI, module: ModuleTargetHud, scheme: Scheme, width: Float) {
        val surface = Material.withAlpha(scheme.surfaceContainerHigh, 1.0F)
        ui.shadow(0.0F, 0.0F, width, HEIGHT, RADIUS, 3)
        ui.rounded(0.0F, 0.0F, width, HEIGHT, RADIUS, surface)
        if (module.frosted() && Blur.ready()) {
            ui.frost(0.0F, 0.0F, width, HEIGHT, RADIUS, scheme.surfaceContainerHigh, BLUR_COVERAGE)
        }

        ui.alphaPush(content.value())
        drawAvatar(ui, scheme)

        val contentX = contentX()
        val right = width - PAD
        val chipWidth = if (module.showDistance()) distanceChipWidth() else 0.0F
        val nameWidth = right - contentX - (if (chipWidth > 0.0F) chipWidth + 8.0F else 0.0F)
        val label = ui.truncate(Typeface.HEADLINE, name, max(20.0F, nameWidth)) ?: ""
        ui.text(Typeface.HEADLINE, label, contentX, NAME_Y, scheme.onSurface, UI.ALIGN_LEFT)

        if (chipWidth > 0.0F) {
            val chipX = right - chipWidth
            ui.rounded(
                chipX, NAME_Y - CHIP_HEIGHT / 2.0F, chipWidth, CHIP_HEIGHT, CHIP_HEIGHT / 2.0F,
                scheme.secondaryContainer,
            )
            ui.text(
                Typeface.MICRO, distanceText(), chipX + chipWidth / 2.0F, NAME_Y,
                scheme.onSecondaryContainer, UI.ALIGN_CENTER,
            )
        }

        drawHealthBar(ui, scheme, contentX, right - contentX)
        drawStats(ui, module, scheme, contentX, right)
        ui.alphaPop()
    }

    private fun drawAvatar(ui: UI, scheme: Scheme) {
        ui.rounded(PAD, PAD, AVATAR, AVATAR, AVATAR_RADIUS, scheme.secondaryContainer)
        val texture = skin
        if (texture != null) {
            ui.image(
                texture, PAD + 2.0F, PAD + 2.0F, AVATAR - 4.0F, AVATAR - 4.0F,
                8.0F / 64.0F, 8.0F / 64.0F, 16.0F / 64.0F, 16.0F / 64.0F, 0xFFFFFFFF.toInt(),
            )
        } else {
            val letter = if (name.isEmpty()) "?" else name.substring(0, 1).uppercase(Locale.ROOT)
            ui.text(
                Typeface.HEADLINE, letter, PAD + AVATAR / 2.0F, PAD + AVATAR / 2.0F,
                scheme.onSecondaryContainer, UI.ALIGN_CENTER,
            )
        }
    }

    private fun drawHealthBar(ui: UI, scheme: Scheme, barX: Float, barWidth: Float) {
        val fill = Material.clamp(healthFill.value(), 0.0F, 1.0F)
        val lag = Material.clamp(healthLag.value(), 0.0F, 1.0F)
        val low = Material.clamp(lowHealth.value(), 0.0F, 1.0F)
        val fillColor = mixSrgb(scheme.primary, scheme.error, low)
        val track = Material.mix(scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, 0.9F)

        ui.rounded(barX, BAR_Y, barWidth, BAR_HEIGHT, BAR_HEIGHT / 2.0F, track)

        if (lag > fill + 0.002F) {
            ui.rounded(
                barX, BAR_Y, barWidth * lag, BAR_HEIGHT, BAR_HEIGHT / 2.0F,
                Material.withAlpha(scheme.error, 0.55F),
            )
        }
        if (fill > 0.002F) {
            ui.rounded(barX, BAR_Y, barWidth * fill, BAR_HEIGHT, BAR_HEIGHT / 2.0F, fillColor)
        }
        val absorb = Material.clamp(absorption / maxHealth, 0.0F, 1.0F - fill)
        if (absorb > 0.002F) {
            ui.rounded(
                barX + barWidth * fill, BAR_Y, barWidth * absorb, BAR_HEIGHT,
                BAR_HEIGHT / 2.0F, scheme.tertiary,
            )
        }
    }

    /**
     * Plain sRGB mix for the health bar.
     *
     * <p>[Material.mix] blends in Onyx's linear working space, which turns the primary → error ramp
     * into a muddy beige halfway through; the bar needs the two roles to stay recognisable.</p>
     */
    private fun mixSrgb(from: Int, to: Int, tRaw: Float): Int {
        val t = Material.clamp(tRaw, 0.0F, 1.0F)
        return Material.argb(
            255,
            (Material.red(from) + (Material.red(to) - Material.red(from)) * t).roundToInt(),
            (Material.green(from) + (Material.green(to) - Material.green(from)) * t).roundToInt(),
            (Material.blue(from) + (Material.blue(to) - Material.blue(from)) * t).roundToInt(),
        )
    }

    private fun drawStats(ui: UI, module: ModuleTargetHud, scheme: Scheme, contentX: Float, right: Float) {
        if (module.showHealth()) {
            val text = String.format(Locale.ROOT, "%.1f / %d", health, maxHealth.roundToInt())
            ui.text(Typeface.CAPTION, text, contentX, STATS_Y, scheme.onSurfaceVariant, UI.ALIGN_LEFT)
        }
        if (module.showArmor() && armor > 0) {
            val text = armor.toString()
            val textWidth = Typeface.CAPTION.width(text)
            ui.text(Typeface.CAPTION, text, right, STATS_Y, scheme.onSurfaceVariant, UI.ALIGN_RIGHT)
            ui.icon("\ue9e0", right - textWidth - 8.0F, STATS_Y, 10.0F, scheme.onSurfaceVariant)
        }
    }

    // ------------------------------------------------------------------ misc

    /** Clears per-session state so re-enabling starts from an empty card. */
    fun reset() {
        entity = null
        lostAt = 0L
        switchedAt = 0L
        placed = false
        visible.set(0.0F)
        content.set(1.0F)
        healthFill.set(0.0F)
        healthLag.set(0.0F)
        lowHealth.set(0.0F)
        width.set(MIN_WIDTH)
        lastFrame = 0L
    }

    private fun clearContent() {
        // Keep the cached text while fading out; only drop it once the card is fully hidden.
        if (entity == null && visible.isDone() && visible.value() <= 0.002F) {
            name = ""
            health = 0.0F
            absorption = 0.0F
            armor = 0
            skin = null
            placed = false
        }
    }

    private fun tick(): Float {
        val now = System.nanoTime()
        val delta = if (lastFrame == 0L) 16.0F else min((now - lastFrame) / 1_000_000.0F, 100.0F)
        lastFrame = now
        return delta
    }

}
