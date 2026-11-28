package verify084;

import net.ccbluex.liquidbounce.utils.input.AndroidGuiCursor;
import net.ccbluex.liquidbounce.LiquidBounce;
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleNoGui;
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.features.FeatureSilentScreen;
import net.ccbluex.liquidbounce.features.module.modules.render.OpaiChestLoot;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.*;
import org.lwjgl.sdl.SDLMouse;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual real-API-compiled production bytes; game/SDL hosts are fixtures, NOT a hardware test. */
public final class CursorRegression {
    static int checks;
    static void ok(boolean v){if(!v)throw new AssertionError("084 check "+checks);checks++;}
    static Method method(Class<?> c,String n){return Arrays.stream(c.getDeclaredMethods()).filter(m->m.getName().equals(n)).peek(m->m.setAccessible(true)).findFirst().orElseThrow();}
    static void noChanges(Minecraft mc){
        int shows=SDLMouse.shows,sets=SDLMouse.sets,releases=mc.mouseHandler.releases;
        boolean relative=SDLMouse.relative,visible=SDLMouse.visible,grabbed=mc.mouseHandler.grabbed;
        double x=mc.mouseHandler.x,y=mc.mouseHandler.y;
        AndroidGuiCursor.enforce(mc);
        ok(SDLMouse.shows==shows && SDLMouse.sets==sets && mc.mouseHandler.releases==releases);
        ok(SDLMouse.relative==relative && SDLMouse.visible==visible && mc.mouseHandler.grabbed==grabbed);
        ok(mc.mouseHandler.x==x && mc.mouseHandler.y==y);
    }
    public static void main(String[] args)throws Exception{
        Minecraft mc=Minecraft.getInstance();mc.gui.current=new Screen();
        if(args[0].equals("desktop")){
            ok(!AndroidGuiCursor.shouldShow(mc));noChanges(mc);
            System.out.println("PASS: 084 desktop "+checks+" assertions: visible GUI remains untouched");return;
        }
        ok(!AndroidGuiCursor.shouldShow(null));
        LiquidBounce.INSTANCE.initialized=false;ok(AndroidGuiCursor.shouldShow(mc));
        AndroidGuiCursor.enforce(mc);ok(!mc.mouseHandler.grabbed && !SDLMouse.relative && SDLMouse.visible);
        ok(mc.mouseHandler.releases==1);LiquidBounce.INSTANCE.initialized=true;
        double x=mc.mouseHandler.x,y=mc.mouseHandler.y;
        AndroidGuiCursor.enforce(mc);ok(mc.mouseHandler.releases==1 && mc.mouseHandler.x==x && mc.mouseHandler.y==y);
        SDLMouse.relative=true;int sets=SDLMouse.sets;AndroidGuiCursor.enforce(mc);
        ok(!SDLMouse.relative && SDLMouse.sets==sets+1);
        // An OS/launcher refusal is NOT replaced with any software pointer/input layer.
        SDLMouse.showResult=false;SDLMouse.visible=false;AndroidGuiCursor.enforce(mc);
        ok(!SDLMouse.visible);SDLMouse.showResult=true;
        mc.active=false;ok(!AndroidGuiCursor.shouldShow(mc));noChanges(mc);mc.active=true;
        ModuleNoGui.hide=true;ok(!AndroidGuiCursor.shouldShow(mc));noChanges(mc);ModuleNoGui.hide=false;
        mc.gui.current=new AbstractContainerScreen<>();FeatureSilentScreen.INSTANCE.hide=true;
        ok(!AndroidGuiCursor.shouldShow(mc));noChanges(mc);
        mc.gui.current=new Screen();ok(AndroidGuiCursor.shouldShow(mc));FeatureSilentScreen.INSTANCE.hide=false;
        OpaiChestLoot.INSTANCE.hide=true;ok(!AndroidGuiCursor.shouldShow(mc));noChanges(mc);OpaiChestLoot.INSTANCE.hide=false;
        mc.gui.current=null;mc.mouseHandler.grabbed=true;SDLMouse.relative=true;
        ok(!AndroidGuiCursor.shouldShow(mc));noChanges(mc);
        CursorMouseHooks mh=new CursorMouseHooks(){};
        Field f=CursorMouseHooks.class.getDeclaredField("minecraft");f.setAccessible(true);f.set(mh,mc);
        Method grab=method(CursorMouseHooks.class,"lb$keepAndroidGuiCursorVisible");
        var ci=new CallbackInfo("grab",true);grab.invoke(mh,ci);ok(!ci.isCancelled());noChanges(mc);
        mc.gui.current=new Screen();ci=new CallbackInfo("grab",true);grab.invoke(mh,ci);
        ok(ci.isCancelled() && !mc.mouseHandler.grabbed && mc.gui.current!=null);
        CursorGuiHooks gh=new CursorGuiHooks(){};
        mc.mouseHandler.grabbed=true;
        method(CursorGuiHooks.class,"lb$showAndroidCursorAfterScreenChange").invoke(gh,mc.gui.current,new CallbackInfo("screen",false));
        ok(!mc.mouseHandler.grabbed);
        mc.mouseHandler.grabbed=true;
        method(CursorGuiHooks.class,"hookBeginGuiExtract").invoke(gh,null,true,true,new CallbackInfo("extract",false));
        ok(!mc.mouseHandler.grabbed);
        // No drawing/extract API is present on the production native helper at all.
        ok(Arrays.stream(AndroidGuiCursor.class.getDeclaredMethods()).noneMatch(m->m.getName().equals("extract")));
        // No protocol API is needed. Exercise GUI close/reopen/focus cycles with normal input coordinates.
        for(int i=0;i<2000;i++){
            mc.gui.current=new Screen();mc.mouseHandler.grabbed=true;SDLMouse.relative=true;
            mc.mouseHandler.x=i*0.25;mc.mouseHandler.y=i*0.125;
            AndroidGuiCursor.enforce(mc);
            ok(!mc.mouseHandler.grabbed && !SDLMouse.relative && SDLMouse.visible);
            ok(mc.mouseHandler.x==i*0.25 && mc.mouseHandler.y==i*0.125);
            ci=new CallbackInfo("grab",true);grab.invoke(mh,ci);ok(ci.isCancelled());
            mc.active=false;noChanges(mc);mc.active=true;
            mc.gui.current=null;mc.mouseHandler.grabbed=true;SDLMouse.relative=true;
            noChanges(mc);ci=new CallbackInfo("grab",true);grab.invoke(mh,ci);ok(!ci.isCancelled());
        }
        System.out.println("PASS: 084 Android native-only "+checks+" assertions, 2000 GUI/focus/close cycles: native ShowCursor/relative-mode recovery only; no-GUI/unfocused/hidden-screen no side effects; mouse coordinates untouched by helper; native refusal gets no substitute. Runtime hosts are fixtures, not Android hardware.");
    }
}
