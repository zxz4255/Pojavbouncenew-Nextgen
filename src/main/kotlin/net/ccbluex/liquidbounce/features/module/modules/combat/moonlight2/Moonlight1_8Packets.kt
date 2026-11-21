/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Raw protocol-47 packet emission for the 1.8-targeted MoonLight ports.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2

import com.viaversion.viafabricplus.ViaFabricPlus
import com.viaversion.viaversion.api.connection.UserConnection
import com.viaversion.viaversion.api.minecraft.BlockPosition
import com.viaversion.viaversion.api.minecraft.item.Item
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper
import com.viaversion.viaversion.api.protocol.packet.ServerboundPacketType
import com.viaversion.viaversion.api.protocol.packet.State
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import com.viaversion.viaversion.api.type.Types
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ServerboundPackets1_8
import net.ccbluex.liquidbounce.utils.client.isEqual1_8
import net.minecraft.world.item.ItemStack
import java.util.function.Consumer

/**
 * Writes 1.8-era packets straight onto the ViaFabricPlus connection.
 *
 * <p>Everything here is guarded: the connection is validated on every call (state, protocol,
 * channel), every wrapper is written with the 1.8 field order and sent with `sendToServerRaw`, and
 * callers get a boolean instead of an exception so a disconnecting server cannot break a client
 * tick. Nothing is cached, so a protocol switch or a reconnect cannot leave a stale wrapper behind.
 * </p>
 *
 * <p>The 1.8 layouts used here:</p>
 * - C09 Set Carried Item: short slot
 * - C08 Use Item: x/y/z BlockPosition, ubyte face, item NBT, ubyte cursor x/y/z
 * - C02 Use Entity: varint entityId, varint action (0 = INTERACT), and for action 2 an extra
 *   float x/y/z
 */
object Moonlight1_8Packets {

    /** Golden apple in 1.8; metadata 1 is the enchanted variant. */
    const val GOLDEN_APPLE_ID = 322

    fun isAvailable(): Boolean = runCatching { isEqual1_8 && connection() != null }.getOrDefault(false)

    private fun connection(): UserConnection? = runCatching {
        val api = ViaFabricPlus.api()
        val connection = api.userConnection() ?: return null
        if (!connection.isClientSide || !connection.isActive || connection.isPendingDisconnect) return null
        val channel = connection.channel ?: return null
        if (!channel.isOpen) return null
        if (connection.protocolInfo.serverState != State.PLAY) return null
        if (!ProtocolVersion.v1_8.equals(api.protocolTranslation().targetVersion(channel))) return null
        if (!ProtocolVersion.v1_8.equals(connection.protocolInfo.serverProtocolVersion())) return null
        connection
    }.getOrNull()

    /** Converts the held stack into its 1.8 form, or null when ViaFabricPlus cannot translate it. */
    fun toLegacyItem(stack: ItemStack): Item? = runCatching {
        ViaFabricPlus.api().conversions().translateItem(stack, ProtocolVersion.v1_8)
    }.getOrNull()

    // ------------------------------------------------------------------ C09

    fun sendHeldItemChange(slot: Int): Boolean {
        if (slot !in 0..8) return false
        val connection = connection() ?: return false
        return send(connection, ServerboundPackets1_8.SET_CARRIED_ITEM) { wrapper ->
            wrapper.write(Types.SHORT, slot.toShort())
        }
    }

    // ------------------------------------------------------------------ C08

    /**
     * Sends the 1.8 "use item in air" form of C08, which is what a sword block and a golden apple
     * both are on that protocol.
     */
    fun sendUseItemInAir(stack: Item): Boolean {
        val connection = connection() ?: return false
        return send(connection, ServerboundPackets1_8.USE_ITEM_ON) { wrapper ->
            wrapper.write(Types.BLOCK_POSITION1_8, BlockPosition(-1, -1, -1))
            wrapper.write(Types.UNSIGNED_BYTE, 255.toShort())
            wrapper.write(Types.ITEM1_8, stack)
            wrapper.write(Types.UNSIGNED_BYTE, 0.toShort())
            wrapper.write(Types.UNSIGNED_BYTE, 0.toShort())
            wrapper.write(Types.UNSIGNED_BYTE, 0.toShort())
        }
    }

    // ------------------------------------------------------------------ C02

    /** C02 action 0 (interact) with no hand item payload. */
    fun sendInteract(entityId: Int): Boolean {
        val connection = connection() ?: return false
        return send(connection, ServerboundPackets1_8.INTERACT) { wrapper ->
            wrapper.write(Types.VAR_INT, entityId)
            wrapper.write(Types.VAR_INT, 0)
        }
    }

    /** C02 action 2 (interact at) used by some 1.8 anti-cheats for sword blocking. */
    fun sendInteractAt(entityId: Int, x: Double, y: Double, z: Double): Boolean {
        val connection = connection() ?: return false
        return send(connection, ServerboundPackets1_8.INTERACT) { wrapper ->
            wrapper.write(Types.VAR_INT, entityId)
            wrapper.write(Types.VAR_INT, 2)
            wrapper.write(Types.FLOAT, x.toFloat())
            wrapper.write(Types.FLOAT, y.toFloat())
            wrapper.write(Types.FLOAT, z.toFloat())
        }
    }

    // ------------------------------------------------------------------ C16

    /**
     * 1.8 C16 CLIENT_COMMAND "open inventory achievement".
     *
     * <p>Some 1.8 servers only accept container clicks after this command, which is why the silent
     * chest path sends it before its first raw interaction.</p>
     */
    fun sendOpenInventory(): Boolean {
        val connection = connection() ?: return false
        return send(connection, ServerboundPackets1_8.CLIENT_COMMAND) { wrapper ->
            wrapper.write(Types.VAR_INT, 2)
        }
    }

    private fun send(
        connection: UserConnection,
        type: ServerboundPacketType,
        writer: Consumer<PacketWrapper>,
    ): Boolean = runCatching {
        val wrapper = PacketWrapper.create(type, connection)
        writer.accept(wrapper)
        wrapper.sendToServerRaw()
        true
    }.getOrDefault(false)
}
