/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.utils.client.vfp;

import net.ccbluex.liquidbounce.LiquidBounce;
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleSwordBlock;
import net.ccbluex.liquidbounce.utils.client.ProtocolUtilKt;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Native item use gets first refusal. Repairs only a manual 1.8 sword use that returned PASS without
 * starting use (e.g. a replaced stack lacking usable native components). Not a toggleable auto-block.
 * Does not modify the item, key mapping, player use flags, inventory, cooldown or protocol settings.
 */
public final class ManualSwordBlocking {
    private static final LegacySwordUseState STATE = new LegacySwordUseState();
    private ManualSwordBlocking() { }

    private static boolean eligible(Minecraft mc) {
        return LiquidBounce.INSTANCE.isInitialized() && mc.player != null && mc.level != null
            && mc.getConnection() != null && mc.gameMode != null && mc.gui.screen() == null
            && mc.isWindowActive() && mc.options.keyUse.isDown()
            && mc.player.isAlive() && !mc.player.isSpectator()
            && !mc.gameMode.isDestroying()
            && !mc.player.getCooldowns().isOnCooldown(mc.player.getMainHandItem())
            && mc.player.getMainHandItem().is(ItemTags.SWORDS)
            && ProtocolUtilKt.isOlderThanOrEqual1_8() && ModuleSwordBlock.manualLegacyUseAllowed();
    }

    private static boolean matches(Minecraft mc) {
        return mc.player != null && STATE.matches(mc.getConnection(), mc.level, mc.player,
            mc.player.getMainHandItem(), mc.player.getInventory().getSelectedSlot());
    }

    /** Called every client tick, including while a GUI is open. Does not start/restart blocking. */
    public static void cancel() {
        Minecraft mc = Minecraft.getInstance();
        STATE.update(mc.getConnection(), mc.level, mc.player, null, -1, false,
            () -> VfpCompatibility1_8.INSTANCE.sendReleaseUseItem());
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        STATE.update(mc.getConnection(), mc.level, mc.player,
            mc.player == null ? null : mc.player.getMainHandItem(),
            mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot(), eligible(mc),
            () -> VfpCompatibility1_8.INSTANCE.sendReleaseUseItem());
    }

    public static boolean isBlocking(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        return entity == mc.player && eligible(mc) && matches(mc);
    }

    /** Called only AFTER PlayerInteractItemEvent was allowed. Avoid duplicate native uses while held. */
    public static boolean isHolding(Player player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND && isBlocking(player);
    }

    /** useItem's original body already synchronized the selected slot. FAIL and consumed results win. */
    public static InteractionResult recover(Player player, InteractionHand hand, InteractionResult original) {
        Minecraft mc = Minecraft.getInstance();
        if (original != InteractionResult.PASS || player != mc.player || hand != InteractionHand.MAIN_HAND
            || player.isUsingItem() || !eligible(mc)) return original;
        // A silent selection belongs to another controller, not this manual fallback.
        if (!VfpCompatibility1_8.INSTANCE.is1_8ConnectionActive()) return original;
        // Fail closed if conversion is unavailable: never encode a nonempty sword as an empty hand.
        if (VfpCompatibility1_8.INSTANCE.translateItemTo1_8(player.getMainHandItem()) == null) return original;
        boolean sent = STATE.begin(mc.getConnection(), mc.level, player, player.getMainHandItem(),
            player.getInventory().getSelectedSlot(),
            () -> VfpCompatibility1_8.INSTANCE.sendUseItemInAir(player.getMainHandItem()));
        return sent ? InteractionResult.CONSUME : original;
    }
}
