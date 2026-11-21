/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Legacy Aura actions which cannot be expressed as 26.3 packet objects. */
public final class MoonlightBufferedActions {
    private MoonlightBufferedActions() { }

    public record Interact(int entityId) { }
    public record SwordUse(ItemStack stack) { }

    /** MoonLight discards C07 and C08, but not C02, on a full AutoGap release. */
    public static boolean discardOnFullRelease(Object entry) {
        return entry instanceof SwordUse || entry instanceof ServerboundUseItemPacket ||
            entry instanceof ServerboundUseItemOnPacket || entry instanceof ServerboundPlayerActionPacket;
    }

    public record BurstOrder(List<Object> duringUse, List<Object> afterRestore) { }

    /**
     * Do not replay attacks, swings or entity interactions between the apple's C08 and the
     * movement burst. They could interrupt use or be performed with the spoofed apple slot.
     * Preserve order within each partition and replay the actions only after slot restoration.
     */
    public static BurstOrder partitionForBurst(List<Object> entries) {
        List<Object> during = new ArrayList<>();
        List<Object> after = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof ServerboundAttackPacket || entry instanceof ServerboundInteractPacket ||
                entry instanceof ServerboundPunchPacket || entry instanceof Interact) {
                after.add(entry);
            } else {
                during.add(entry);
            }
        }
        return new BurstOrder(List.copyOf(during), List.copyOf(after));
    }
}
