/*
 * This file is part of LiquidBounce. SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.item;

import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Per-query fallback for protocol47 swords without Via's synthetic BLOCKS_ATTACKS component.
 * Never cache player/world/stack/connection and never mutate stack components or send raw packets.
 */
public final class LegacySwordBlocking {
    public static final int USE_DURATION = 72000;
    private static final boolean VIA_PRESENT = viaPresent();

    private LegacySwordBlocking() { }

    private static boolean viaPresent() {
        try {
            Class.forName("com.viaversion.viafabricplus.ViaFabricPlus", false,
                LegacySwordBlocking.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean needsFallback(ItemStack stack) {
        if (stack.isEmpty() || !isLegacySword(stack)) return false;
        // Leave Via's normal emulation and server-defined usable components alone.
        if (stack.has(DataComponents.BLOCKS_ATTACKS) || stack.has(DataComponents.CONSUMABLE) ||
            stack.has(DataComponents.EQUIPPABLE) || stack.has(DataComponents.KINETIC_WEAPON)) return false;
        return activeProtocol47();
    }

    private static boolean isLegacySword(ItemStack stack) {
        // 1.8 has no server tags. Backend transfers may replace tags, so don't depend on SWORDS.
        return stack.is(Items.WOODEN_SWORD) || stack.is(Items.STONE_SWORD) || stack.is(Items.IRON_SWORD) ||
            stack.is(Items.GOLDEN_SWORD) || stack.is(Items.DIAMOND_SWORD);
    }

    private static boolean activeProtocol47() {
        if (!VIA_PRESENT) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.hasSingleplayerServer()) return false;
        try {
            // Don't initialize/cache ProtocolUtil's startup listener result from an Item query.
            // Retry the live API after transitions; a temporarily unavailable API must not latch false.
            return ViaState.active();
        } catch (LinkageError | RuntimeException ignored) {
            // Optional/incompatible Via API or a connection closing mid-query: keep vanilla behavior.
            return false;
        }
    }

    /** Keep optional Via types isolated; no Via installed is a normal vanilla-only client. */
    private static final class ViaState {
        private static boolean active() {
            var api = ViaFabricPlus.api();
            var connection = api.userConnection();
            if (connection == null || !connection.isClientSide() || !connection.isActive() ||
                connection.isPendingDisconnect()) return false;
            var channel = connection.getChannel();
            if (channel == null || !channel.isOpen() || connection.getProtocolInfo().getServerState() != State.PLAY) {
                return false;
            }
            // Validate the actual live channel, not just the version selected in the menu.
            return ProtocolVersion.v1_8.equals(api.protocolTranslation().targetVersion(channel)) &&
                ProtocolVersion.v1_8.equals(connection.getProtocolInfo().serverProtocolVersion());
        }
    }
}
