package net.ccbluex.liquidbounce.features.module.modules.render.targethud

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ScreenRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * Draws (and feeds) the target card — deliberately outside the module's own handler table.
 *
 * <p>The original registered its render listener once, at module construction, "so the card can keep
 * fading out after the module is disabled". LiquidBounce only dispatches a module's handlers while it
 * is `running`, so keeping this in the module would cut the card off mid-fade. An [EventListener] with
 * no parent is always running, which reproduces the original lifecycle exactly — including the fact
 * that the card keeps its last frame and fades out on disable.</p>
 *
 * <p>Initialised through `ModuleTargetHud.layer`, and `ModuleTargetHud` is constructed by
 * `ModuleManager`'s module list at client start.</p>
 */
object TargetHudLayer : EventListener {

    /** How long a manual hit keeps the card up when no aura is running (see [currentTarget]). */
    private const val HIT_HOLD_MS = 1500L

    private var attacked: LivingEntity? = null
    private var attackedAt = 0L

    private var dragging = false
    private var dragOffsetX = 0.0F
    private var dragOffsetY = 0.0F

    /**
     * Remembers the last entity the client hit, so the card also appears without an aura.
     *
     * <p>NeoRaven read the target from KillAura only; in LiquidBounce most players fight by hand, so a
     * pure aura source would leave the card invisible for them. This is the single, documented
     * deviation — see `README-TargetHud.md`.</p>
     */
    @Suppress("unused")
    private val attack = handler<AttackEntityEvent> { event ->
        if (!ModuleTargetHud.enabled || event.isCancelled) return@handler
        val target = event.entity
        if (target is LivingEntity && target !== mc.player && !target.isRemoved && target.level() == mc.level) {
            attacked = target
            attackedAt = System.nanoTime()
        }
    }

    @Suppress("unused")
    private val mouse = handler<MouseButtonEvent> { event ->
        if (!ModuleTargetHud.enabled || !event.isLeftButton) return@handler
        if (mc.gui.screen() !is ChatScreen) {
            dragging = false
            return@handler
        }
        if (event.isPressed) {
            val mouseX = scaledMouseX()
            val mouseY = scaledMouseY()
            if (TargetHud.inside(mouseX, mouseY)) {
                dragging = true
                dragOffsetX = mouseX - TargetHud.left()
                dragOffsetY = mouseY - TargetHud.top()
            }
        } else if (event.isReleased) {
            dragging = false
        }
    }

    @Suppress("unused")
    private val overlay = handler<OverlayRenderEvent> { event ->
        if (mc.gui.hud.isHidden || mc.gui.overlay() != null) {
            dragging = false
            return@handler
        }
        // Nothing to do once the card has faded out and the module is off.
        if (!ModuleTargetHud.enabled && TargetHud.alpha() <= 0.002F) return@handler
        TargetHud.render(event.context, ModuleTargetHud, false, event.tickDelta)
    }

    /**
     * The card over the chat screen.
     *
     * <p>The original drew in `RenderTickEvent`, so the card stayed visible (and draggable) while the
     * player typed. LiquidBounce's HUD overlay event does not fire for screens, so the chat case comes
     * from [ScreenRenderEvent], which is dispatched after any screen has rendered — the same moment in
     * the frame, just for the screen path.</p>
     */
    @Suppress("unused")
    private val screenRender = handler<ScreenRenderEvent> { event ->
        if (!ModuleTargetHud.enabled) return@handler
        if (mc.gui.screen() !is ChatScreen) return@handler
        if (mc.gui.hud.isHidden) return@handler
        dragTick()
        TargetHud.render(event.context, ModuleTargetHud, false, event.partialTicks)
    }

    /** Chat drag is polled per frame, exactly like the original's `Mouse.isButtonDown(0)` loop. */
    private fun dragTick() {
        if (!dragging) return
        if (mc.gui.screen() !is ChatScreen) {
            dragging = false
            return
        }
        TargetHud.dragTo(scaledMouseX() - dragOffsetX, scaledMouseY() - dragOffsetY)
    }

    /**
     * The entity the card describes: the aura's current target, otherwise the entity hit within the
     * last [HIT_HOLD_MS].
     */
    fun currentTarget(): LivingEntity? {
        val level = mc.level ?: return null
        val aura = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target else null
        if (aura != null && !aura.isRemoved && aura.level() == level) {
            return aura
        }
        val recent = attacked ?: return null
        if (System.nanoTime() - attackedAt > HIT_HOLD_MS * 1_000_000L) return null
        if (recent.isRemoved || recent.level() != level) return null
        return recent
    }

    private fun scaledMouseX(): Float = MouseHandler.getScaledXPos(mc.window, mc.mouseHandler.xpos()).toFloat()

    private fun scaledMouseY(): Float = MouseHandler.getScaledYPos(mc.window, mc.mouseHandler.ypos()).toFloat()

    /** Clears the remembered hit, called when the module is turned on. */
    fun reset() {
        attacked = null
        attackedAt = 0L
        dragging = false
    }

}
