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

package net.ccbluex.liquidbounce.utils.client.vfp;

import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.DataItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.ServerboundPacketType;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ServerboundPackets1_8;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Compatibility layer for ViaFabricPlus on protocol 1.8 (protocol 47 / <= 1.8.x).
 * <p>
 * Provides direct 1.8 wire packet emission (C03, C04, C05, C06, C08, C09, C0A, C0C, C0D, C12, C16)
 * for cross-version modules ported from 1.8.9 clients.
 */
public enum VfpCompatibility1_8 {

    INSTANCE;

    /**
     * Returns true when ViaFabricPlus has an active PLAY connection targeting <= 1.8.x.
     */
    public boolean is1_8ConnectionActive() {
        try {
            return activeConnection1_8() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private @Nullable UserConnection activeConnection1_8() {
        if (!VfpCompatibility.INSTANCE.isOlderThanOrEqual1_8()) {
            return null;
        }

        var api = ViaFabricPlus.api();
        UserConnection connection = api.userConnection();
        if (connection == null || !connection.isClientSide() || !connection.isActive() || connection.isPendingDisconnect()) {
            return null;
        }

        var channel = connection.getChannel();
        if (channel == null || !channel.isOpen()) {
            return null;
        }

        if (connection.getProtocolInfo().getServerState() != State.PLAY) {
            return null;
        }

        return connection;
    }

    /**
     * Translates a 26.3 {@link ItemStack} into a 1.8 {@link Item} for C08 / C0E payloads.
     * Returns {@code null} when the stack is empty (which encodes as {@code short -1} in 1.8).
     */
    public @Nullable Item translateItemTo1_8(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        try {
            Item translated = ViaFabricPlus.api().conversions().translateItem(stack, ProtocolVersion.v1_8);
            if (translated != null) {
                return translated;
            }
        } catch (Throwable ignored) {
        }

        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            byte amount = (byte) Math.max(1, Math.min(127, stack.getCount()));
            short data = stack.is(Items.ENCHANTED_GOLDEN_APPLE) ? (short) 1 : (short) 0;
            return new DataItem(322, amount, data, null);
        }

        return null;
    }

    public void sendSignUpdate(final BlockPos blockPos, final String[] lines) throws IllegalArgumentException {
        if (lines.length != 4) {
            throw new IllegalArgumentException("Lines length does not match 4");
        }

        writePacket(ServerboundPackets1_8.SIGN_UPDATE, packet -> {
            packet.write(Types.BLOCK_POSITION1_8, new BlockPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ()));

            for (var line : lines) {
                packet.write(Types.STRING, line);
            }
        });
    }

    public void sendPlayerInput(float sideways, float forwards, boolean jumping, boolean sneaking) {
        writePacket(ServerboundPackets1_8.PLAYER_INPUT, packet -> {
            packet.write(Types.FLOAT, sideways);
            packet.write(Types.FLOAT, forwards);
            byte b = 0;
            if (jumping) {
                b = (byte) (b | 1);
            }

            if (sneaking) {
                b = (byte) (b | 2);
            }
            packet.write(Types.BYTE, b);
        });
    }

    /**
     * Sends the 1.8 C09 (Set Carried Item) packet without changing the client's selected hotbar slot.
     */
    public void sendHeldItemChange(int slot) {
        if (slot < 0 || slot > 8) {
            throw new IllegalArgumentException("Hotbar slot must be between 0 and 8");
        }

        writePacket(ServerboundPackets1_8.SET_CARRIED_ITEM, packet ->
                packet.write(Types.SHORT, (short) slot));
    }

    /**
     * Sends a 1.8 C08 (Player Block Placement) packet in its use-item-in-air form.
     * Golden apples are item 322 in 1.8; metadata 1 is the enchanted golden apple.
     */
    public void sendGoldenAppleUse(int stackCount, boolean enchanted) {
        final byte amount = (byte) Math.max(1, Math.min(127, stackCount));
        final short data = enchanted ? (short) 1 : (short) 0;

        writePacket(ServerboundPackets1_8.USE_ITEM_ON, packet -> {
            packet.write(Types.BLOCK_POSITION1_8, new BlockPosition(-1, -1, -1));
            packet.write(Types.UNSIGNED_BYTE, (short) 0xFF);
            packet.write(Types.ITEM1_8, new DataItem(322, amount, data, null));
            packet.write(Types.UNSIGNED_BYTE, (short) 0);
            packet.write(Types.UNSIGNED_BYTE, (short) 0);
            packet.write(Types.UNSIGNED_BYTE, (short) 0);
        });
    }

    /**
     * Sends a 1.8 C08 (Player Block Placement) air-use packet for any held {@link ItemStack}
     * (including {@code null}/empty stack, matching {@code new C08PacketPlayerBlockPlacement(stack)}).
     */
    public boolean sendUseItemInAir(@Nullable ItemStack stack) {
        final Item item1_8 = translateItemTo1_8(stack);
        return writePacketSafe(ServerboundPackets1_8.USE_ITEM_ON, packet -> {
            packet.write(Types.BLOCK_POSITION1_8, new BlockPosition(-1, -1, -1));
            packet.write(Types.UNSIGNED_BYTE, (short) 0xFF);
            packet.write(Types.ITEM1_8, item1_8);
            packet.write(Types.UNSIGNED_BYTE, (short) 0);
            packet.write(Types.UNSIGNED_BYTE, (short) 0);
            packet.write(Types.UNSIGNED_BYTE, (short) 0);
        });
    }

    /**
     * Sends a 1.8 C08 (Player Block Placement) targeted block interaction packet
     * (matching 1.8 {@code onPlayerRightClick(pos, side, heldStack, hitVec)}).
     */
    public boolean sendPlaceBlock(
            BlockPos pos,
            Direction side,
            @Nullable ItemStack heldStack,
            double hitX,
            double hitY,
            double hitZ
    ) {
        final Item item1_8 = translateItemTo1_8(heldStack);
        final short facingX = (short) Math.max(0, Math.min(16, (int) ((hitX - pos.getX()) * 16.0)));
        final short facingY = (short) Math.max(0, Math.min(16, (int) ((hitY - pos.getY()) * 16.0)));
        final short facingZ = (short) Math.max(0, Math.min(16, (int) ((hitZ - pos.getZ()) * 16.0)));

        return writePacketSafe(ServerboundPackets1_8.USE_ITEM_ON, packet -> {
            packet.write(Types.BLOCK_POSITION1_8, new BlockPosition(pos.getX(), pos.getY(), pos.getZ()));
            packet.write(Types.UNSIGNED_BYTE, (short) (side.get3DDataValue() & 0xFF));
            packet.write(Types.ITEM1_8, item1_8);
            packet.write(Types.UNSIGNED_BYTE, facingX);
            packet.write(Types.UNSIGNED_BYTE, facingY);
            packet.write(Types.UNSIGNED_BYTE, facingZ);
        });
    }

    /**
     * Sends a 1.8 C03PacketPlayer(onGround) packet (0x03 MOVE_PLAYER_STATUS_ONLY).
     */
    public boolean sendMoveStatusOnly(boolean onGround) {
        return writePacketSafe(ServerboundPackets1_8.MOVE_PLAYER_STATUS_ONLY, packet ->
                packet.write(Types.BOOLEAN, onGround));
    }

    /**
     * Sends a 1.8 C04PacketPlayerPosition(x, y, z, onGround) packet (0x04 MOVE_PLAYER_POS).
     */
    public boolean sendMovePlayerPos(double x, double y, double z, boolean onGround) {
        return writePacketSafe(ServerboundPackets1_8.MOVE_PLAYER_POS, packet -> {
            packet.write(Types.DOUBLE, x);
            packet.write(Types.DOUBLE, y);
            packet.write(Types.DOUBLE, z);
            packet.write(Types.BOOLEAN, onGround);
        });
    }

    /**
     * Sends a 1.8 C05PacketPlayerLook(yaw, pitch, onGround) packet (0x05 MOVE_PLAYER_ROT).
     */
    public boolean sendMovePlayerRot(float yaw, float pitch, boolean onGround) {
        return writePacketSafe(ServerboundPackets1_8.MOVE_PLAYER_ROT, packet -> {
            packet.write(Types.FLOAT, yaw);
            packet.write(Types.FLOAT, pitch);
            packet.write(Types.BOOLEAN, onGround);
        });
    }

    /**
     * Sends a 1.8 C06PacketPlayerPosLook(x, y, z, yaw, pitch, onGround) packet (0x06 MOVE_PLAYER_POS_ROT).
     */
    public boolean sendMovePlayerPosRot(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        return writePacketSafe(ServerboundPackets1_8.MOVE_PLAYER_POS_ROT, packet -> {
            packet.write(Types.DOUBLE, x);
            packet.write(Types.DOUBLE, y);
            packet.write(Types.DOUBLE, z);
            packet.write(Types.FLOAT, yaw);
            packet.write(Types.FLOAT, pitch);
            packet.write(Types.BOOLEAN, onGround);
        });
    }

    /**
     * Sends a 1.8 C02PacketUseEntity(entityId, ATTACK) packet (0x02 INTERACT, action = 1).
     */
    public boolean sendAttackEntity(int entityId) {
        return writePacketSafe(ServerboundPackets1_8.INTERACT, packet -> {
            packet.write(Types.VAR_INT, entityId);
            packet.write(Types.VAR_INT, 1);
        });
    }

    /**
     * Sends a 1.8 C02PacketUseEntity(entityId, INTERACT) packet (0x02 INTERACT, action = 0).
     */
    public boolean sendInteractEntity(int entityId) {
        return writePacketSafe(ServerboundPackets1_8.INTERACT, packet -> {
            packet.write(Types.VAR_INT, entityId);
            packet.write(Types.VAR_INT, 0);
        });
    }

    /**
     * Sends a 1.8 C02PacketUseEntity(entityId, hitVec) packet (0x02 INTERACT, action = 2 = INTERACT_AT).
     */
    public boolean sendInteractEntityAt(int entityId, double relX, double relY, double relZ) {
        return writePacketSafe(ServerboundPackets1_8.INTERACT, packet -> {
            packet.write(Types.VAR_INT, entityId);
            packet.write(Types.VAR_INT, 2);
            packet.write(Types.FLOAT, (float) relX);
            packet.write(Types.FLOAT, (float) relY);
            packet.write(Types.FLOAT, (float) relZ);
        });
    }

    /**
     * Sends a 1.8 C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN) packet
     * (0x07 PLAYER_ACTION, status = 5, pos = (0,0,0), face = 0).
     */
    public boolean sendReleaseUseItem() {
        return writePacketSafe(ServerboundPackets1_8.PLAYER_ACTION, packet -> {
            packet.write(Types.BYTE, (byte) 5);
            packet.write(Types.BLOCK_POSITION1_8, new BlockPosition(0, 0, 0));
            packet.write(Types.UNSIGNED_BYTE, (short) 0);
        });
    }

    /**
     * Sends a 1.8 C0APacketAnimation() swing packet (0x0A SWING).
     */
    public boolean sendSwing() {
        return writePacketSafe(ServerboundPackets1_8.SWING, packet -> {
        });
    }

    /**
     * Sends a 1.8 C0DPacketCloseWindow(windowId) packet (0x0D CONTAINER_CLOSE).
     */
    public boolean sendCloseWindow(int windowId) {
        return writePacketSafe(ServerboundPackets1_8.CONTAINER_CLOSE, packet ->
                packet.write(Types.UNSIGNED_BYTE, (short) (windowId & 0xFF)));
    }

    /**
     * Sends a 1.8 C16PacketClientStatus(OPEN_INVENTORY_ACHIEVEMENT) packet (0x16 CLIENT_COMMAND, action = 2).
     */
    public boolean sendOpenInventory() {
        return writePacketSafe(ServerboundPackets1_8.CLIENT_COMMAND, packet ->
                packet.write(Types.VAR_INT, 2));
    }

    public boolean sendHeldItemChangeSafe(int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        return writePacketSafe(ServerboundPackets1_8.SET_CARRIED_ITEM, packet ->
                packet.write(Types.SHORT, (short) slot));
    }

    private boolean writePacketSafe(ServerboundPacketType packetType, Consumer<PacketWrapper> writer) {
        try {
            UserConnection userConnection = activeConnection1_8();
            if (userConnection == null) {
                return false;
            }

            var packet = PacketWrapper.create(packetType, userConnection);
            writer.accept(packet);
            packet.sendToServerRaw();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void writePacket(ServerboundPacketType packetType, Consumer<PacketWrapper> writer) {
        if (!VfpCompatibility.INSTANCE.isOlderThanOrEqual1_8()) {
            throw new IllegalStateException("Not on 1.8 protocol");
        }

        var userConnection = ViaFabricPlus.api().userConnection();
        if (userConnection == null) {
            throw new IllegalStateException("No ViaVersion user connection (not connected to a server)");
        }

        var packet = PacketWrapper.create(packetType, userConnection);
        writer.accept(packet);
        packet.sendToServerRaw();
    }

}
