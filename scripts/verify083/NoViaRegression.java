package verify083;

import net.ccbluex.liquidbounce.utils.item.LegacySwordBlocking;
import net.ccbluex.liquidbounce.utils.input.AndroidGuiCursor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.*;

public final class NoViaRegression {
    public static void main(String[] args) {
        try {
            Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
            throw new AssertionError("no-Via test incorrectly includes the API facade");
        } catch (ClassNotFoundException expected) { }
        for (int i=0;i<1000;i++) {
            if (LegacySwordBlocking.needsFallback(new ItemStack(Items.IRON_SWORD))) throw new AssertionError("no Via must retain vanilla");
        }
        Minecraft mc=Minecraft.getInstance();mc.gui.current=new Screen();
        AndroidGuiCursor.enforce(mc);
        if (mc.mouseHandler.grabbed || !AndroidGuiCursor.shouldShow(mc)) throw new AssertionError("GUI cursor must not require Via");
        System.out.println("PASS: 083 no-Via separate JVM, 1000 safe sword queries and Android GUI recovery without optional Via facade");
    }
}
