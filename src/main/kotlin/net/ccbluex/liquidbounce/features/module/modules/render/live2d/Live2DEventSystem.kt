/*
 * Live2DEventSystem —— 逐 tick 监听玩家状态变化并驱动 Live2D 实时互动反应。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DEventSystem.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.ChatScreen

class Live2DEventSystem(
    private val hud: Live2DHudRenderer,
    private val config: Live2DConfig,
) {

    private var lastHealth = -1f
    private var lastSwinging = false
    private var lastOnGround = true
    private var lastSprinting = false
    private var lastSneaking = false
    private var lastUsingItem = false
    private var lastInChat = false
    private var lastDead = false

    fun tick() {
        val player = mc.player
        if (player == null) {
            reset()
            return
        }

        val health = player.health
        val maxHealth = player.maxHealth
        val ratio = if (maxHealth > 0f) health / maxHealth else 1f

        if (lastHealth >= 0f) {
            val delta = health - lastHealth
            if (delta < -0.5f) {
                fire("hurt")
                if (ratio < 0.3f) fire("low_health")
            } else if (delta > 0.5f) {
                fire("heal")
            }
        }
        lastHealth = health

        val dead = player.isDeadOrDying
        if (dead && !lastDead) fire("death") else if (!dead && lastDead) fire("respawn")
        lastDead = dead

        val swinging = player.isSwinging
        if (swinging && !lastSwinging) fire("attack")
        lastSwinging = swinging

        val onGround = player.onGround()
        if (!onGround && lastOnGround && player.deltaMovement.y > 0.05) {
            fire("jump")
        } else if (onGround && !lastOnGround) {
            fire("land")
        }
        lastOnGround = onGround

        val sprinting = player.isSprinting
        if (sprinting && !lastSprinting) fire("sprint_start")
        lastSprinting = sprinting

        val sneaking = player.isShiftKeyDown
        if (sneaking && !lastSneaking) fire("sneak_start")
        else if (!sneaking && lastSneaking) fire("sneak_end")
        lastSneaking = sneaking

        val usingItem = player.isUsingItem
        if (usingItem && !lastUsingItem) fire("use_item")
        lastUsingItem = usingItem

        val inChat = mc.gui.screen() is ChatScreen
        if (inChat && !lastInChat) fire("chat_opened")
        lastInChat = inChat
    }

    fun fire(event: String) {
        if (config.playerInteraction) {
            hud.onPlayerGameEvent(event)
        }
        val action = config.events[event] ?: return
        hud.triggerAction(action)
    }

    private fun reset() {
        lastHealth = -1f
        lastSwinging = false
        lastOnGround = true
        lastSprinting = false
        lastSneaking = false
        lastUsingItem = false
        lastInChat = false
        lastDead = false
    }
}
