/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.minecraft.item.DataItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ServerboundPackets1_8;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoonlightLegacyPacketsTest {
    @Test
    void c02InteractUsesTheLegacyEntityAndActionVarints() throws Exception {
        var packet = new PacketWrapperImpl(ServerboundPackets1_8.INTERACT, null, null);
        MoonlightLegacyPackets.writeInteract(packet, 300);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.writeToBuffer(bytes);
            assertEquals(0x02, bytes.readUnsignedByte());
            assertEquals(300, (int) Types.VAR_INT.read(bytes));
            assertEquals(0, (int) Types.VAR_INT.read(bytes));
            assertEquals(0, bytes.readableBytes());
        } finally {
            bytes.release();
        }
    }

    @Test
    void c16OpenInventoryUsesTheLegacyActionVarint() throws Exception {
        var packet = new PacketWrapperImpl(ServerboundPackets1_8.CLIENT_COMMAND, null, null);
        MoonlightLegacyPackets.writeOpenInventory(packet);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.writeToBuffer(bytes);
            assertEquals(0x16, bytes.readUnsignedByte());
            assertEquals(2, (int) Types.VAR_INT.read(bytes));
            assertEquals(0, bytes.readableBytes());
        } finally {
            bytes.release();
        }
    }

    @Test
    void c09UsesLegacyIdAndAShortSlot() {
        var packet = new PacketWrapperImpl(ServerboundPackets1_8.SET_CARRIED_ITEM, null, null);
        MoonlightLegacyPackets.writeHeldItemChange(packet, 7);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.writeToBuffer(bytes);
            assertEquals(0x09, bytes.readUnsignedByte());
            assertEquals(7, bytes.readShort());
            assertEquals(0, bytes.readableBytes());
        } finally {
            bytes.release();
        }
    }

    @Test
    void c08UsesAirPositionAndPreservesEnchantedAppleMetadataAndNbt() throws Exception {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("moonlight-test", "preserved");
        Item apple = new DataItem(322, (byte) 3, (short) 1, nbt);
        var packet = new PacketWrapperImpl(ServerboundPackets1_8.USE_ITEM_ON, null, null);
        MoonlightLegacyPackets.writeUseItemInAir(packet, apple);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.writeToBuffer(bytes);
            assertEquals(0x08, bytes.readUnsignedByte());
            assertEquals(-1L, bytes.readLong()); // (-1,-1,-1) packed as a 1.8 block position
            assertEquals(255, bytes.readUnsignedByte());
            Item decoded = Types.ITEM1_8.read(bytes);
            assertEquals(322, decoded.identifier());
            assertEquals(3, decoded.amount());
            assertEquals(1, decoded.data());
            assertEquals("preserved", decoded.tag().getString("moonlight-test"));
            assertEquals(0, bytes.readUnsignedByte());
            assertEquals(0, bytes.readUnsignedByte());
            assertEquals(0, bytes.readUnsignedByte());
            assertEquals(0, bytes.readableBytes());
        } finally {
            bytes.release();
        }
    }

    @Test
    void c08AlsoEncodesAnOrdinaryAppleWithoutNbt() throws Exception {
        var packet = new PacketWrapperImpl(ServerboundPackets1_8.USE_ITEM_ON, null, null);
        MoonlightLegacyPackets.writeUseItemInAir(packet, new DataItem(322, (byte) 1, (short) 0, null));
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.writeToBuffer(bytes);
            assertEquals(0x08, bytes.readUnsignedByte());
            assertEquals(-1L, bytes.readLong());
            assertEquals(255, bytes.readUnsignedByte());
            Item decoded = Types.ITEM1_8.read(bytes);
            assertEquals(322, decoded.identifier());
            assertEquals(0, decoded.data());
            assertNull(decoded.tag());
            assertEquals(3, bytes.readableBytes());
        } finally {
            bytes.release();
        }
    }
}
