/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * MoonLight KillAura port for a ViaFabricPlus 1.8 target.
 * Original project: https://github.com/randomguy3725/MoonLight (GPL-3.0).
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2.AimPoint
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2.Moonlight1_8Packets
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2.MoonlightRotation
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2.RotationMode
import net.ccbluex.liquidbounce.features.module.modules.misc.antibot.ModuleAntiBot
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.client.isEqual1_8
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.entity.armorItems
import net.ccbluex.liquidbounce.utils.item.isSword
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * A MoonLight-style KillAura for a 1.8 server seen through ViaFabricPlus.
 *
 * <p>Design goals, in the order the options are evaluated each tick:</p>
 * 1. **自己转头 (rotation)** — the aura owns a client-side rotation, never the mouse. It computes the
 *    aim point, the yaw/pitch delta, and advances by a per-tick speed budget, so the server sees a
 *    gradual turn instead of a snap. `RotationMode` picks the easing curve, `RotationJitter` makes
 *    the turn imperfect on purpose, and `MovementCorrection` decides whether the movement packets
 *    follow the fake rotation. `Silent` keeps the screen still while the server is told the aura's
 *    angle; `ChangeLook` visibly turns the camera.
 * 2. **AutoBlock** — sword blocking on protocol 47, which is a C08 use-item-in-air (plus the C02
 *    interact variants the different anti-cheats expect). `None`,`Vanilla`,`Interact`,`InteractAt`,
 *    `Watchdog`,`Hypixel` and `Blink` are selectable, with a release-switch-timing option because a
 *    1.8 server only applies a hit while the blocking flag is set.
 * 3. **ChestAura** — see [ModuleMoonlightChestAura]; it lives in the same package and shares this
 *    module's rotation and packet helpers.
 *
 * <p>Nothing here is borrowed code: the packet layouts are the documented protocol-47 fields, the
 * rotation maths is [MoonlightRotation], and the option list is reconstructed from what MoonLight
 * exposes so an imported 1.8 config still makes sense.</p>
 */
@Suppress("TooManyFunctions")
object ModuleMoonlightKillAura : ClientModule(
    "MoonlightKillAura", ModuleCategories.COMBAT, disableOnQuit = true, aliases = listOf("MoonAura")
) {

    // ------------------------------------------------------------------ target selection

    private val targets by multiEnumChoice("Targets", TargetKind.PLAYERS)
    private val mode by enumChoice("TargetMode", TargetMode.SINGLE)
    private val priority by enumChoice("Priority", TargetPriority.DISTANCE)
    private val switchDelay by int("SwitchDelay", 250, 0..2000, "ms")
    private val searchRange by float("SearchRange", 6f, 1f..12f, "blocks")
    private val rotationRange by float("RotationRange", 3.6f, 1f..6f, "blocks")
    private val attackRange by float("AttackRange", 3f, 1f..6f, "blocks")
    private val throughWallsRange by float("ThroughWallsRange", 0f, 0f..6f, "blocks")
    private val fov by float("FOV", 180f, 10f..360f, "°")
    private val ignoreInvisible by boolean("IgnoreInvisible", true)
    private val teams by boolean("Teams", true)
    private val ignoreFriends by boolean("IgnoreFriends", true)
    private val ignoreBots by boolean("IgnoreBots", true)
    private val ignoreNaked by boolean("IgnoreNaked", false)
    private val ignoreMobs by boolean("IgnoreMobs", true)
    private val ignoreAnimals by boolean("IgnoreAnimals", true)
    private val multiTargetLimit by int("MultiTargetLimit", 3, 1..8)

    // ------------------------------------------------------------------ rotation

    private val aimPoint by enumChoice("AimPoint", AimPoint.NEAREST)
    private val rotationMode by enumChoice("RotationMode", RotationMode.ADAPTIVE)
    private val yawSpeed by float("YawSpeed", 180f, 1f..180f, "°/tick")
    private val pitchSpeed by float("PitchSpeed", 180f, 1f..180f, "°/tick")
    private val rotationJitter by float("RotationJitter", 0f, 0f..1f)
    private val rotationReset by boolean("ResetRotation", false)
    private val movementCorrection by enumChoice("MovementCorrection", MovementCorrection.SILENT)
    private val keepRotationOnTarget by boolean("KeepRotation", true)

    // ------------------------------------------------------------------ attack scheduling

    private val minAps by int("MinAPS", 9, 1..20)
    private val maxAps by int("MaxAPS", 12, 1..20)
    private val apsMode by enumChoice("ApsMode", ApsMode.RANDOM)
    private val perfectHit by boolean("PerfectHit", true)
    private val perfectHitThreshold by float("PerfectHitThreshold", 0.85f, 0.1f..1f)
    private val rayCast by boolean("RayCast", true)
    private val hitSelect by boolean("HitSelect", false)
    private val hitSelectThreshold by float("HitSelectThreshold", 0.5f, 0f..1f)
    private val swing by boolean("Swing", true)

    // ------------------------------------------------------------------ auto block

    private val autoBlock by enumChoice("AutoBlock", AutoBlockMode.NONE)
    private val blockRange by float("AutoBlockRange", 5f, 1f..8f, "blocks")
    private val blockOnlySword by boolean("BlockOnlySword", true)
    private val blockReleaseOnAttack by boolean("ReleaseOnAttack", true)
    private val blockReleaseTicks by int("ReleaseTicks", 1, 0..5, "ticks")
    private val blockInteractAt by boolean("InteractAtPayload", false)
    private val blockSwingBefore by boolean("SwingBeforeBlock", false)

    // ------------------------------------------------------------------ runtime state

    private var currentTarget: LivingEntity? = null
    private var lastTargetSwitch = 0L
    private var lastAttackAt = 0L
    private var nextAttackDelay = 100L
    private var blocking = false
    private var blockReleaseUntil = 0L
    private var pendingRelease = false
    private var rotationYaw = 0f
    private var rotationPitch = 0f
    private var rotationInitialised = false

    override fun onEnabled() {
        rotationInitialised = false
        currentTarget = null
        blocking = false
        pendingRelease = false
        lastAttackAt = 0L
        nextAttackDelay = 0L
    }

    override fun onDisabled() {
        releaseBlock(force = true)
        currentTarget = null
        rotationInitialised = false
    }

    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> {
        // A new world invalidates entity ids and any half-sent block state.
        blocking = false
        currentTarget = null
        rotationInitialised = false
    }

    /**
     * The outgoing movement packet is rewritten so the server receives our aura rotation while the
     * client keeps drawing the player's own view. This is the "silent" part of the rotation; with
     * [MovementCorrection.CHANGE_LOOK] the rotation is pushed into the player itself instead and this
     * handler stays passive.
     *
     * <p>The access widener opens these fields for writing, and the injection point used by every
     * other packet-rewriting module (NoFall, NoSlow, ...) is the same one, so the replacement packet
     * is what actually leaves the client. Only the rotation fields are touched; position, ground
     * flag and collision flag keep whatever the movement code decided.</p>
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING) return@handler
        if (event.isCancelled) return@handler
        if (!rotationInitialised) return@handler
        if (movementCorrection == MovementCorrection.CHANGE_LOOK) return@handler
        if (mc.gui.screen() != null && !keepRotationOnTarget) return@handler

        val packet = event.packet as? ServerboundMovePlayerPacket ?: return@handler
        if (!packet.hasRot) return@handler
        if (event.packet is ServerboundMovePlayerPacket.StatusOnly) return@handler

        // Only substitute a rotation this aura owns. A Scaffold/Blink/velocity rotation pushed through
        // the shared manager is authoritative and must not be overwritten here.
        val current = RotationManager.serverRotation
        if (abs(MoonlightRotation.delta(current.yaw, rotationYaw)) < 0.05f &&
            abs(MoonlightRotation.delta(current.pitch, rotationPitch)) < 0.05f
        ) {
            return@handler
        }
        packet.yRot = rotationYaw
        packet.xRot = MoonlightRotation.clampPitch(rotationPitch)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val p = mc.player ?: return@handler
        val level = mc.level ?: return@handler
        if (!rotationInitialised) {
            rotationYaw = p.yRot
            rotationPitch = p.xRot
            rotationInitialised = true
        }

        val candidates = level.entitiesForRendering()
            .filterIsInstance<LivingEntity>()
            .filter { valid(it, p) }

        val selected = selectTargets(candidates, p)
        val primary = selected.firstOrNull()

        if (primary == null) {
            currentTarget = null
            releaseBlock(force = false)
            if (rotationReset) decayRotation(p)
            return@handler
        }
        currentTarget = primary

        rotateTowards(primary, p)
        updateAutoBlock(primary, p)

        val now = System.currentTimeMillis()
        if (now - lastAttackAt < nextAttackDelay) return@handler

        for (target in selected) {
            if (!canAttack(target, p)) continue
            if (perfectHit && !perfectHitReady(p)) continue
            if (wallCheckFails(target, p)) continue
            if (!finishAttack(target, p)) continue
            if (hitSelect || mode == TargetMode.SINGLE) break
        }
    }

    // ------------------------------------------------------------------ target logic

    private fun valid(entity: LivingEntity, self: LocalPlayer): Boolean {
        if (entity === self) return false
        if (entity.isRemoved || !entity.isAlive) return false
        if (ignoreInvisible && entity.isInvisible) return false
        if (entity.distanceTo(self) > searchRange) return false
        if (entity is Player) {
            if (TargetKind.PLAYERS !in targets) return false
            if (ignoreFriends && FriendManager.isFriend(entity)) return false
            if (ignoreBots && ModuleAntiBot.isBot(entity)) return false
            if (teams && self.team != null && self.team == entity.team) return false
            if (ignoreNaked && entity.armorItems.all { it.isEmpty }) return false
        } else {
            val kind = if (isAnimal(entity)) TargetKind.ANIMALS else TargetKind.MOBS
            if (kind !in targets) return false
            if (kind == TargetKind.MOBS && ignoreMobs) return false
            if (kind == TargetKind.ANIMALS && ignoreAnimals) return false
        }
        if (fov < 360f) {
            val toEntity = MoonlightRotation.rotationTo(self.eyePosition, entity.position())
            val current = Rotation(rotationYaw, rotationPitch, true)
            if (MoonlightRotation.angleBetween(current, toEntity) > fov / 2f) return false
        }
        return true
    }

    private fun isAnimal(entity: LivingEntity): Boolean = when (entity.type.toShortString()) {
        "cow", "pig", "sheep", "chicken", "rabbit", "horse", "donkey", "mule", "llama",
        "wolf", "cat", "fox", "bee", "turtle", "panda", "polar_bear", "goat", "frog", "axolotl",
        "mooshroom", "ocelot", "parrot", "strider", "hoglin", "camel", "sniffer", "armadillo" -> true
        else -> false
    }

    private fun selectTargets(candidates: List<LivingEntity>, self: LocalPlayer): List<LivingEntity> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = when (priority) {
            TargetPriority.DISTANCE -> candidates.sortedBy { self.distanceToSqr(it) }
            TargetPriority.HEALTH -> candidates.sortedBy { it.health }
            TargetPriority.ARMOR -> candidates.sortedBy { armorValue(it) }
            TargetPriority.HURT_TIME -> candidates.sortedByDescending { it.hurtTime }
        }
        val now = System.currentTimeMillis()
        if (mode == TargetMode.SINGLE) {
            val sticky = currentTarget
            if (sticky != null && sorted.contains(sticky) && now - lastTargetSwitch < switchDelay) {
                return listOf(sticky)
            }
            lastTargetSwitch = now
            return listOf(sorted.first())
        }
        return sorted.take(multiTargetLimit.coerceAtLeast(1))
    }

    /** Sum of the armour pieces' durability: a rough "how geared is this target" score. */
    private fun armorValue(entity: LivingEntity): Int = entity.armorItems.sumOf { stack ->
        if (stack.isEmpty) 0 else stack.count.coerceAtLeast(1) + stack.getDamageValue()
    }

    // ------------------------------------------------------------------ rotation

    private fun rotateTowards(target: LivingEntity, self: LocalPlayer) {
        val eyes = self.eyePosition
        val aim = MoonlightRotation.aimPoint(target, eyes, aimPoint)
        val wanted = MoonlightRotation.rotationTo(eyes, aim)

        val distance = MoonlightRotation.boxDistance(eyes, target.boundingBox)
        // The closer the target, the more precise the last degrees have to be: scale the budget by
        // how far outside the rotation range we currently are.
        val outside = (distance - rotationRange).coerceAtLeast(0.0).toFloat()
        val boost = if (outside > 0f) 1f + outside.coerceAtMost(3f) * 0.6f else 1f
        val jitter = if (rotationJitter > 0f) rotationJitter else 0f

        // Remaining ticks are estimated from the angle and the speed budget: this is what makes the
        // easing curve land on the target on the tick we intend to click.
        val yawDelta = abs(MoonlightRotation.delta(rotationYaw, wanted.yaw))
        val pitchDelta = abs(MoonlightRotation.delta(rotationPitch, wanted.pitch))
        val yawTicks = (yawDelta / (yawSpeed * boost)).toInt().coerceAtLeast(1)
        val pitchTicks = (pitchDelta / (pitchSpeed * boost)).toInt().coerceAtLeast(1)

        val nextYaw = if (jitter > 0f) {
            val wobble = (Random.nextFloat() - 0.5f) * 2f * jitter * yawSpeed * 0.15f
            MoonlightRotation.step(rotationYaw, wanted.yaw + wobble, yawSpeed * boost, rotationMode, yawTicks, jitter)
        } else {
            MoonlightRotation.step(rotationYaw, wanted.yaw, yawSpeed * boost, rotationMode, yawTicks, 0f)
        }
        val nextPitch = if (jitter > 0f) {
            val wobble = (Random.nextFloat() - 0.5f) * 2f * jitter * pitchSpeed * 0.1f
            MoonlightRotation.step(rotationPitch, wanted.pitch + wobble, pitchSpeed * boost, rotationMode, pitchTicks, jitter)
        } else {
            MoonlightRotation.step(rotationPitch, wanted.pitch, pitchSpeed * boost, rotationMode, pitchTicks, 0f)
        }

        rotationYaw = MoonlightRotation.wrapYaw(nextYaw)
        rotationPitch = MoonlightRotation.clampPitch(nextPitch)

        // Publish through the shared rotation manager so every other module sees one consistent view
        // of where we look, and so the movement correction is applied by the same code path as the
        // built-in KillAura. `CHANGE_LOOK` also pushes the rotation onto the player itself.
        if (keepRotationOnTarget || movementCorrection == MovementCorrection.CHANGE_LOOK) {
            val plan = RotationTarget(
                Rotation(rotationYaw, rotationPitch, true),
                target,
                movementCorrection = movementCorrection,
                considerInventory = true,
            )
            RotationManager.setRotationTarget(plan, Priority.IMPORTANT_FOR_USAGE_2, this)
        }
    }

    private fun decayRotation(self: LocalPlayer) {
        val yawDelta = MoonlightRotation.delta(rotationYaw, self.yRot)
        val pitchDelta = MoonlightRotation.delta(rotationPitch, self.xRot)
        if (abs(yawDelta) < 0.5f && abs(pitchDelta) < 0.5f) {
            rotationYaw = self.yRot
            rotationPitch = self.xRot
            return
        }
        rotationYaw = MoonlightRotation.wrapYaw(
            rotationYaw + yawDelta.sign * minOf(abs(yawDelta), yawSpeed * 0.35f)
        )
        rotationPitch = MoonlightRotation.clampPitch(
            rotationPitch + pitchDelta.sign * minOf(abs(pitchDelta), pitchSpeed * 0.35f)
        )
    }

    // ------------------------------------------------------------------ attack

    private fun canAttack(target: LivingEntity, self: LocalPlayer): Boolean {
        val box = target.boundingBox
        val distance = MoonlightRotation.boxDistance(self.eyePosition, box)
        if (distance > attackRange) return false
        if (hitSelect && !hitSelectWantsAttack(target, self)) return false
        if (rayCast && isLookingAtEntity(target, attackRange.toDouble(), Rotation(rotationYaw, rotationPitch, true)) == null) {
            // Not on the crosshair with the aura rotation; the packet handler will push the rotation
            // out on the next movement packet, so this is a "try again next tick", not a failure.
            return false
        }
        return true
    }

    private fun wallCheckFails(target: LivingEntity, self: LocalPlayer): Boolean {
        val distance = MoonlightRotation.boxDistance(self.eyePosition, target.boundingBox)
        if (distance <= throughWallsRange) return false
        return distance > attackRange
    }

    private fun perfectHitReady(self: LocalPlayer): Boolean {
        if (!isEqual1_8) return true
        // 1.8 has no attack cooldown meter the server enforces, but the client still tracks the
        // vanilla attack-strength meter. "Perfect" means the meter is far enough along that the hit
        // would not be a weak swing, so the threshold is read against that scale.
        val strength = self.getAttackStrengthScale(0.5f)
        return strength >= perfectHitThreshold
    }

    private fun hitSelectWantsAttack(target: LivingEntity, self: LocalPlayer): Boolean {
        // Only attack on our own hurt-time window or when the target cannot hurt us back this tick.
        if (self.hurtTime > 0) return true
        return Random.nextFloat() >= hitSelectThreshold
    }

    private fun finishAttack(target: LivingEntity, self: LocalPlayer): Boolean {
        val now = System.currentTimeMillis()
        nextAttackDelay = nextDelay()

        if (blocking && blockReleaseOnAttack) {
            // A blocking 1.8 client has to release before the hit for the server to register damage;
            // releaseBlock also clears the client's own item-use state off a 1.8 target.
            releaseBlock(force = true)
            blockReleaseUntil = now + blockReleaseTicks * 50L
            pendingRelease = true
        }

        if (blockSwingBefore && swing) {
            // 26.3's LocalPlayer.swing takes the swing animation and the "notify the swinging entity"
            // flag; the shared helper reads the held item's animation, which is what the game does.
            SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
        }

        // `attackEntity` sends the swing packet itself through Minecraft's own attack pipeline, which
        // ViaFabricPlus translates to C0A. Adding a second raw swing here would double the animation
        // on a 1.8 server, so none is sent.
        val attacked = attackEntity(
            target,
            if (swing) SwingMode.DO_NOT_HIDE else SwingMode.HIDE_BOTH,
            keepSprint = true,
        )
        if (attacked) lastAttackAt = now
        return attacked
    }

    private fun nextDelay(): Long {
        if (maxAps <= 0) return 1000L
        val aps = when (apsMode) {
            ApsMode.FIXED -> minAps
            ApsMode.RANDOM -> if (maxAps <= minAps) minAps else Random.nextInt(minAps, maxAps + 1)
        }.coerceAtLeast(1)
        return (1000L / aps).coerceAtLeast(1L)
    }

    // ------------------------------------------------------------------ auto block

    private fun updateAutoBlock(target: LivingEntity, self: LocalPlayer) {
        // Sword blocking only exists up to 1.8. On anything newer every "block" would be a bare
        // use-item packet, which is exactly what servers read as auto-block ("Illegal combat
        // modifications" is the usual kick message). So the whole feature stays off there instead of
        // degrading into a use-item spam.
        if (!isEqual1_8) {
            releaseBlock(force = false)
            return
        }
        val now = System.currentTimeMillis()
        if (now < blockReleaseUntil) {
            // Between the release and the re-block we must not send a new use packet.
            return
        }
        if (pendingRelease) pendingRelease = false

        val wanted = autoBlock != AutoBlockMode.NONE &&
            self.distanceTo(target) <= blockRange &&
            (!blockOnlySword || self.mainHandItem.isSword)

        if (!wanted) {
            releaseBlock(force = false)
            return
        }
        if (blocking) return

        val sent = when (autoBlock) {
            AutoBlockMode.NONE -> false
            AutoBlockMode.VANILLA -> {
                // Let the client itself hold the use key, which ViaFabricPlus translates.
                self.startUsingItem(InteractionHand.MAIN_HAND)
                true
            }
            AutoBlockMode.BLINK -> {
                // Blink-style: the block packet is only built and sent together with the attack.
                pendingRelease = true
                true
            }
            AutoBlockMode.HYT -> {
                // HYT's own client sends the interact packet first, then the use-item packet, and
                // keeps the interact on the target the aura is currently aiming at.
                if (isEqual1_8 && Moonlight1_8Packets.isAvailable()) {
                    val item = Moonlight1_8Packets.toLegacyItem(self.mainHandItem)
                    if (item == null || !Moonlight1_8Packets.sendInteract(target.id)) return
                    item != null && Moonlight1_8Packets.sendUseItemInAir(item)
                } else {
                    self.startUsingItem(InteractionHand.MAIN_HAND)
                    true
                }
            }
            AutoBlockMode.RELEASE -> {
                // Only keep the block between hits: the packet is the vanilla use packet, and the
                // release window is handled by `ReleaseOnAttack` + `ReleaseTicks` above.
                if (isEqual1_8 && Moonlight1_8Packets.isAvailable()) {
                    val item = Moonlight1_8Packets.toLegacyItem(self.mainHandItem)
                    item != null && Moonlight1_8Packets.sendUseItemInAir(item)
                } else {
                    self.startUsingItem(InteractionHand.MAIN_HAND)
                    true
                }
            }
            AutoBlockMode.INTERACT, AutoBlockMode.INTERACT_AT, AutoBlockMode.WATCHDOG,
            AutoBlockMode.HYPIXEL -> {
                if (isEqual1_8 && Moonlight1_8Packets.isAvailable()) {
                    val item = Moonlight1_8Packets.toLegacyItem(self.mainHandItem)
                    val interacted = item != null && when (autoBlock) {
                        AutoBlockMode.INTERACT -> Moonlight1_8Packets.sendInteract(target.id)
                        AutoBlockMode.INTERACT_AT -> Moonlight1_8Packets.sendInteractAt(
                            target.id, target.x, target.boundingBox.center.y, target.z
                        )
                        AutoBlockMode.WATCHDOG -> Moonlight1_8Packets.sendInteract(target.id)
                        else -> Moonlight1_8Packets.sendInteract(target.id)
                    }
                    if (!interacted) return
                    if (blockInteractAt && autoBlock != AutoBlockMode.INTERACT_AT) {
                        Moonlight1_8Packets.sendInteractAt(target.id, target.x, target.boundingBox.center.y, target.z)
                    }
                    item != null && Moonlight1_8Packets.sendUseItemInAir(item)
                } else {
                    self.startUsingItem(InteractionHand.MAIN_HAND)
                    true
                }
            }
        }
        if (sent) blocking = true
    }

    /**
     * Stops blocking.
     *
     * <p>On 1.8 the block flag is a server-side consequence of the last C08, so it is dropped by not
     * sending another one: no release packet exists on protocol 47 and no swing is needed. On any
     * other target the client's own item-use state is what has to be cleared.</p>
     */
    private fun releaseBlock(force: Boolean) {
        if (!blocking) return
        blocking = false
        if (force) pendingRelease = false
        if (isEqual1_8 && Moonlight1_8Packets.isAvailable()) return
        mc.player?.stopUsingItem()
    }

    /** True while the aura believes the server sees a block. Used by the HUD and ChestAura. */
    fun isBlocking(): Boolean = blocking

    /** The rotation the aura is convincing the server of, for tools that need to agree with it. */
    fun auraRotation(): Rotation = Rotation(rotationYaw, rotationPitch, true)

    /** Human readable state for the module tag. */
    fun stateLabel(): String = when {
        blocking -> "Block"
        currentTarget != null -> "Aura"
        else -> "Idle"
    }

    enum class TargetMode(override val tag: String) : Tagged { SINGLE("Single"), SWITCH("Switch"), MULTI("Multi") }

    enum class TargetPriority(override val tag: String) : Tagged {
        DISTANCE("Distance"), HEALTH("Health"), ARMOR("Armor"), HURT_TIME("HurtTime"),
    }

    enum class ApsMode(override val tag: String) : Tagged { RANDOM("Random"), FIXED("Fixed") }

    /**
     * Sword-block modes.
     *
     * <p>The first six tags are the ones the previous MoonLight port wrote into its config, so an
     * existing config keeps loading instead of failing on an unknown option and losing the whole
     * module section. `HYT` and `Release` are kept as real, working modes rather than aliases: `HYT`
     * is the C02-interact-then-C08 burst and `Release` is the "block only between hits" variant.</p>
     */
    enum class AutoBlockMode(override val tag: String) : Tagged {
        NONE("None"),
        VANILLA("Vanilla"),
        HYT("HYT"),
        WATCHDOG("Watchdog"),
        RELEASE("Release"),
        INTERACT("Interact"),
        INTERACT_AT("InteractAt"),
        HYPIXEL("Hypixel"),
        BLINK("Blink"),
    }

    enum class TargetKind(override val tag: String) : Tagged {
        PLAYERS("Players"), MOBS("Mobs"), ANIMALS("Animals"),
    }
}
