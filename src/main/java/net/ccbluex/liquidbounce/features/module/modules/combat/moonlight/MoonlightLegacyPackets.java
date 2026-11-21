/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ServerboundPackets1_8;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Optional ViaFabricPlus boundary. Never load/call this class unless ViaFabricPlus is present.
 * The raw wrappers are already 1.8 packets, so they must bypass Via's serverbound translator.
 */
public final class MoonlightLegacyPackets {
    private MoonlightLegacyPackets() { }

    public static boolean isAvailable() {
        try {
            return connection() != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static @Nullable UserConnection connection() {
        var api = ViaFabricPlus.api();
        UserConnection connection = api.userConnection();
        if (connection == null || !connection.isClientSide() || !connection.isActive() ||
            connection.isPendingDisconnect() || connection.getChannel() == null || !connection.getChannel().isOpen() ||
            connection.getProtocolInfo().getServerState() != State.PLAY ||
            !ProtocolVersion.v1_8.equals(api.protocolTranslation().targetVersion(connection.getChannel())) ||
            !ProtocolVersion.v1_8.equals(connection.getProtocolInfo().serverProtocolVersion())) {
            return null;
        }
        return connection;
    }

    /**
     * Called on the Minecraft client thread. C09, C08, replay and restore are submitted in this
     * order. Normal 26.3 writes go through Minecraft's Connection, which queues onto the channel's
     * event loop when called off it; Via's raw writes go through the same channel's encoder. The
     * submission order is therefore preserved across the two encoder paths. This ordering is
     * covered by MoonlightNettyOrderTest on a real LocalChannel (translation and item conversion
     * are stubbed there), so it does not by itself prove live-server behaviour.
     *
     * Converts the actual stack through ViaFabricPlus instead of inventing item NBT. This retains
     * the 1.8 gold-apple identifier, metadata, count and custom/enchantment tags.
     */
    public static void sendBurst(ItemStack stack, int appleSlot, int restoreSlot, Runnable replay) {
        if (appleSlot < 0 || appleSlot > 8 || restoreSlot < 0 || restoreSlot > 8) {
            throw new IllegalArgumentException("Hotbar slots must be in 0..8");
        }
        UserConnection connection = connection();
        if (connection == null) throw new IllegalStateException("No active 1.8 ViaFabricPlus connection");
        Item oldItem = ViaFabricPlus.api().conversions().translateItem(stack, ProtocolVersion.v1_8);
        if (oldItem == null || oldItem.identifier() != 322) {
            throw new IllegalStateException("Could not convert the apple to a 1.8 item");
        }

        boolean switched = false;
        try {
            if (appleSlot != restoreSlot) {
                heldItemChange(connection, appleSlot);
                switched = true;
            }
            useItemInAir(connection, oldItem);
            replay.run();
        } finally {
            if (switched && connection.getChannel().isOpen()) {
                heldItemChange(connection, restoreSlot);
            }
        }
    }

    /** 1.8 C02 INTERACT followed by C08 air-use, for MoonLight sword blocking. */
    public static void sendSwordBlock(ItemStack stack, int interactEntityId) {
        UserConnection connection = connection();
        if (connection == null) throw new IllegalStateException("No active 1.8 ViaFabricPlus connection");
        Item oldItem = ViaFabricPlus.api().conversions().translateItem(stack, ProtocolVersion.v1_8);
        if (oldItem == null) throw new IllegalStateException("Could not convert held item to 1.8");
        if (interactEntityId >= 0) {
            interact(connection, interactEntityId);
        }
        useItemInAir(connection, oldItem);
    }

    public static void sendInteract(int entityId) {
        UserConnection connection = connection();
        if (connection == null) throw new IllegalStateException("No active 1.8 ViaFabricPlus connection");
        interact(connection, entityId);
    }

    /** 1.8 C16 OPEN_INVENTORY_ACHIEVEMENT, on the same connection as the following click. */
    public static void sendOpenInventory() {
        UserConnection connection = connection();
        if (connection == null) throw new IllegalStateException("No active 1.8 ViaFabricPlus connection");
        PacketWrapper packet = PacketWrapper.create(ServerboundPackets1_8.CLIENT_COMMAND, connection);
        writeOpenInventory(packet);
        packet.sendToServerRaw();
    }

    static void writeOpenInventory(PacketWrapper packet) {
        packet.write(Types.VAR_INT, 2);
    }

    private static void interact(UserConnection connection, int entityId) {
        PacketWrapper packet = PacketWrapper.create(ServerboundPackets1_8.INTERACT, connection);
        writeInteract(packet, entityId);
        packet.sendToServerRaw();
    }

    static void writeInteract(PacketWrapper packet, int entityId) {
        packet.write(Types.VAR_INT, entityId);
        packet.write(Types.VAR_INT, 0); // 1.8 C02 INTERACT, not INTERACT_AT or ATTACK
    }

    private static void heldItemChange(UserConnection connection, int slot) {
        PacketWrapper packet = PacketWrapper.create(ServerboundPackets1_8.SET_CARRIED_ITEM, connection);
        writeHeldItemChange(packet, slot);
        packet.sendToServerRaw();
    }

    private static void useItemInAir(UserConnection connection, Item stack) {
        PacketWrapper packet = PacketWrapper.create(ServerboundPackets1_8.USE_ITEM_ON, connection);
        writeUseItemInAir(packet, stack);
        packet.sendToServerRaw();
    }

    // Package-private so serialization can be tested without a live network connection.
    static void writeHeldItemChange(PacketWrapper packet, int slot) {
        packet.write(Types.SHORT, (short) slot);
    }

    static void writeUseItemInAir(PacketWrapper packet, Item stack) {
        packet.write(Types.BLOCK_POSITION1_8, new BlockPosition(-1, -1, -1));
        packet.write(Types.UNSIGNED_BYTE, (short) 255);
        packet.write(Types.ITEM1_8, stack);
        packet.write(Types.UNSIGNED_BYTE, (short) 0);
        packet.write(Types.UNSIGNED_BYTE, (short) 0);
        packet.write(Types.UNSIGNED_BYTE, (short) 0);
    }
}
