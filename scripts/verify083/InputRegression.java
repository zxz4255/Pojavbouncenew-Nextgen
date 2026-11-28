package verify083;

import net.ccbluex.liquidbounce.utils.input.AndroidGuiCursor;
import net.ccbluex.liquidbounce.utils.item.LegacySwordBlocking;
import net.ccbluex.liquidbounce.injection.mixins.minecraft.item.MixinItem;
import net.ccbluex.liquidbounce.LiquidBounce;
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleNoGui;
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.features.FeatureSilentScreen;
import net.ccbluex.liquidbounce.features.module.modules.render.OpaiChestLoot;
import net.minecraft.client.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.*;
import net.minecraft.core.component.*;
import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viafabricplus.api.ViaFabricPlusAPI;
import com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation;
import com.viaversion.viaversion.api.connection.*;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.lwjgl.sdl.SDLMouse;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.lang.reflect.*;
import java.util.*;

/** Executes real-game-compiled production bytecode against explicit runtime hosts. No real server. */
public final class InputRegression {
    static int checks;
    static void ok(boolean v){if(!v)throw new AssertionError("083 check "+checks);checks++;}
    static Method method(Class<?> c,String name){return Arrays.stream(c.getDeclaredMethods()).filter(m->m.getName().equals(name)).peek(m->m.setAccessible(true)).findFirst().orElseThrow();}
    static final MixinItem itemHook=new MixinItem(){};
    static final Method use=method(MixinItem.class,"lb$legacySwordUse");
    static final Method anim=method(MixinItem.class,"lb$legacySwordAnimation");
    static final Method duration=method(MixinItem.class,"lb$legacySwordDuration");
    static <T> CallbackInfoReturnable<T> result(){return new CallbackInfoReturnable<>("test",true);}
    static CallbackInfoReturnable<InteractionResult> press(Player p,Level l,InteractionHand h)throws Exception{
        CallbackInfoReturnable<InteractionResult> r=result();use.invoke(itemHook,l,p,h,r);return r;
    }
    static class Session implements UserConnection,ProtocolInfo,ViaFabricPlusAPI,ProtocolTranslation,io.netty.channel.Channel {
        boolean client=true,active=true,pending=false,open=true;
        boolean hasChannel=true,hasConnection=true;
        State state=State.PLAY;int serverVersion=47,targetVersion=47;
        public boolean isClientSide(){return client;}public boolean isActive(){return active;}public boolean isPendingDisconnect(){return pending;}
        public io.netty.channel.Channel getChannel(){return hasChannel?this:null;}public boolean isOpen(){return open;}
        public ProtocolInfo getProtocolInfo(){return this;}
        public State getServerState(){return state;}
        public ProtocolVersion serverProtocolVersion(){return new ProtocolVersion(serverVersion);}
        public UserConnection userConnection(){return hasConnection?this:null;}
        public ProtocolTranslation protocolTranslation(){return this;}
        public ProtocolVersion targetVersion(io.netty.channel.Channel ch){return new ProtocolVersion(targetVersion);}
    }
    public static void main(String[] args)throws Exception {
        Minecraft mc=Minecraft.getInstance();mc.gui.current=new Screen();
        boolean android=args[0].equals("android");
        if(!android){
            ok(!AndroidGuiCursor.shouldShow(mc));AndroidGuiCursor.enforce(mc);
            GuiGraphicsExtractor g=new GuiGraphicsExtractor();AndroidGuiCursor.extract(mc,g);
            ok(mc.mouseHandler.grabbed && SDLMouse.shows==0 && g.pixels.isEmpty());
            System.out.println("PASS: 083 desktop cursor non-interference "+checks+" assertions");return;
        }
        // A visible GUI must work before client initialization too (title/protocol/login screens).
        LiquidBounce.INSTANCE.initialized=false;ok(AndroidGuiCursor.shouldShow(mc));
        AndroidGuiCursor.enforce(mc);ok(!mc.mouseHandler.grabbed && !SDLMouse.relative && SDLMouse.visible);
        ok(mc.mouseHandler.releases==1);LiquidBounce.INSTANCE.initialized=true;
        double x=mc.mouseHandler.x,y=mc.mouseHandler.y;
        AndroidGuiCursor.enforce(mc);ok(mc.mouseHandler.releases==1 && mc.mouseHandler.x==x && mc.mouseHandler.y==y);
        // OS relative mode can become inconsistent even when Java already thinks the cursor is free.
        SDLMouse.relative=true;int sets=SDLMouse.sets;AndroidGuiCursor.enforce(mc);ok(!SDLMouse.relative && SDLMouse.sets==sets+1);
        GuiGraphicsExtractor g=new GuiGraphicsExtractor();g.pose().translate(55,71).scale(1.3f);
        var before=new org.joml.Matrix3x2f(g.pose());AndroidGuiCursor.extract(mc,g);
        ok(g.pose().equals(before));ok(g.strata==1 && !g.pixels.isEmpty());
        ok(g.pixels.get(0)[0]==(int)x && g.pixels.get(0)[1]==(int)y);
        ok(g.pixels.stream().allMatch(p->p[2]-p[0]==1 && p[3]-p[1]==1));
        ok(g.pixels.stream().anyMatch(p->p[4]==0xFFFFFFFF) && g.pixels.stream().anyMatch(p->p[4]==0xFF151515));
        mc.active=false;int size=g.pixels.size();AndroidGuiCursor.extract(mc,g);ok(g.pixels.size()==size);mc.active=true;
        ModuleNoGui.hide=true;ok(!AndroidGuiCursor.shouldShow(mc));ModuleNoGui.hide=false;
        mc.gui.current=new AbstractContainerScreen<>();FeatureSilentScreen.INSTANCE.hide=true;
        ok(!AndroidGuiCursor.shouldShow(mc));mc.gui.current=new Screen();
        ok(AndroidGuiCursor.shouldShow(mc)); // stale silent-container state cannot hide a settings cursor
        FeatureSilentScreen.INSTANCE.hide=false;OpaiChestLoot.INSTANCE.hide=true;ok(!AndroidGuiCursor.shouldShow(mc));OpaiChestLoot.INSTANCE.hide=false;
        mc.gui.current=null;mc.mouseHandler.grabbed=true;AndroidGuiCursor.enforce(mc);
        ok(mc.mouseHandler.grabbed && !AndroidGuiCursor.shouldShow(mc));
        // Exact extracted production mixin bodies, compiled against the real MC API.
        CursorMouseHooks mouseHooks=new CursorMouseHooks(){};
        Field mf=CursorMouseHooks.class.getDeclaredField("minecraft");mf.setAccessible(true);mf.set(mouseHooks,mc);
        Method grab=method(CursorMouseHooks.class,"lb$keepAndroidGuiCursorVisible");
        var c=new CallbackInfo("grab",true);grab.invoke(mouseHooks,c);ok(!c.isCancelled());
        mc.gui.current=new Screen();c=new CallbackInfo("grab",true);grab.invoke(mouseHooks,c);
        ok(c.isCancelled() && !mc.mouseHandler.grabbed && mc.gui.current!=null);
        CursorGuiHooks guiHooks=new CursorGuiHooks(){};
        mc.mouseHandler.grabbed=true;
        method(CursorGuiHooks.class,"lb$showAndroidCursorAfterScreenChange").invoke(guiHooks,mc.gui.current,new CallbackInfo("screen",false));
        ok(!mc.mouseHandler.grabbed);
        mc.mouseHandler.grabbed=true;
        method(CursorGuiHooks.class,"hookBeginGuiExtract").invoke(guiHooks,null,true,true,new CallbackInfo("extract",false));
        ok(!mc.mouseHandler.grabbed);
        size=g.pixels.size();method(CursorGuiHooks.class,"lb$extractAndroidCursor").invoke(guiHooks,null,true,true,new CallbackInfo("extract",false),g);
        ok(g.pixels.size()>size);
        // Protocol-independent cursor: it must not inspect Via state at all.
        ViaFabricPlus.fail=true;AndroidGuiCursor.enforce(mc);ok(AndroidGuiCursor.shouldShow(mc));ViaFabricPlus.fail=false;

        Session s=new Session();ViaFabricPlus.api=s;
        Item[] swords={Items.WOODEN_SWORD,Items.STONE_SWORD,Items.IRON_SWORD,Items.GOLDEN_SWORD,Items.DIAMOND_SWORD};
        for(Item sword:swords){
            mc.player.main=new ItemStack(sword);ok(LegacySwordBlocking.needsFallback(mc.player.main));
            var r=press(mc.player,mc.level,InteractionHand.MAIN_HAND);
            ok(r.isCancelled() && r.getReturnValue()==InteractionResult.CONSUME && mc.player.using);
            ok(mc.player.usedHand==InteractionHand.MAIN_HAND);
            CallbackInfoReturnable<ItemUseAnimation> a=result();anim.invoke(itemHook,mc.player.main,a);
            ok(a.getReturnValue()==ItemUseAnimation.BLOCK);
            CallbackInfoReturnable<Integer> d=result();duration.invoke(itemHook,mc.player.main,mc.player,d);
            ok(d.getReturnValue()==72000);
            ok(mc.player.main.components.isEmpty());
        }
        for(Item other:new Item[]{Items.APPLE,Items.BOW,Items.SHIELD,Items.AIR,Items.NETHERITE_SWORD})ok(!LegacySwordBlocking.needsFallback(new ItemStack(other)));
        ItemStack sword=new ItemStack(Items.IRON_SWORD);mc.player.main=sword;
        for(DataComponentType<?> type:new DataComponentType<?>[]{DataComponents.BLOCKS_ATTACKS,DataComponents.CONSUMABLE,DataComponents.EQUIPPABLE,DataComponents.KINETIC_WEAPON}){
            sword.components.add(type);ok(!LegacySwordBlocking.needsFallback(sword));
            ok(!press(mc.player,mc.level,InteractionHand.MAIN_HAND).isCancelled());sword.components.clear();
        }
        sword.count=0;ok(!LegacySwordBlocking.needsFallback(sword));sword.count=1;
        ok(!press(mc.player,mc.level,InteractionHand.OFF_HAND).isCancelled());
        ok(!press(new Player(),mc.level,InteractionHand.MAIN_HAND).isCancelled());
        ok(!press(mc.player,new ClientLevel(),InteractionHand.MAIN_HAND).isCancelled());
        // Invalid/mid-transition connections fail closed but recover on the NEXT call, no false latch.
        s.active=false;ok(!LegacySwordBlocking.needsFallback(sword));s.active=true;ok(LegacySwordBlocking.needsFallback(sword));
        s.client=false;ok(!LegacySwordBlocking.needsFallback(sword));s.client=true;
        s.pending=true;ok(!LegacySwordBlocking.needsFallback(sword));s.pending=false;
        s.open=false;ok(!LegacySwordBlocking.needsFallback(sword));s.open=true;
        s.hasChannel=false;ok(!LegacySwordBlocking.needsFallback(sword));s.hasChannel=true;
        s.hasConnection=false;ok(!LegacySwordBlocking.needsFallback(sword));s.hasConnection=true;
        s.state=State.LOGIN;ok(!LegacySwordBlocking.needsFallback(sword));s.state=State.CONFIGURATION;ok(!LegacySwordBlocking.needsFallback(sword));s.state=State.PLAY;
        s.targetVersion=764;ok(!LegacySwordBlocking.needsFallback(sword));s.targetVersion=47;
        s.serverVersion=764;ok(!LegacySwordBlocking.needsFallback(sword));s.serverVersion=47;
        ViaFabricPlus.fail=true;ok(!LegacySwordBlocking.needsFallback(sword));ViaFabricPlus.fail=false;ok(LegacySwordBlocking.needsFallback(sword));
        mc.singleplayer=true;ok(!LegacySwordBlocking.needsFallback(sword));mc.singleplayer=false;
        LocalPlayer p=mc.player;mc.player=null;ok(!LegacySwordBlocking.needsFallback(sword));mc.player=p;
        ClientLevel level=mc.level;mc.level=null;ok(!LegacySwordBlocking.needsFallback(sword));mc.level=level;
        // Simulated round changes: every round gets NEW connection/player/world/stack identities.
        // Hosts model state transitions, not a real server match or actual packet translation.
        for(int i=0;i<1000;i++){
            s.state=State.CONFIGURATION;ok(!LegacySwordBlocking.needsFallback(sword));
            mc.player=new LocalPlayer();mc.level=new ClientLevel();s=new Session();ViaFabricPlus.api=s;
            mc.player.main=new ItemStack(swords[i%swords.length]);sword=mc.player.main;
            ok(LegacySwordBlocking.needsFallback(sword));
            ok(press(mc.player,mc.level,InteractionHand.MAIN_HAND).isCancelled() && mc.player.using);
            mc.player.using=false; // model release, not a production release packet test
            mc.gui.current=new Screen();mc.mouseHandler.grabbed=true;SDLMouse.relative=true;
            AndroidGuiCursor.enforce(mc);ok(!mc.mouseHandler.grabbed && !SDLMouse.relative);
            var ci=new CallbackInfo("grab",true);grab.invoke(mouseHooks,ci);ok(ci.isCancelled());
            mc.gui.current=null;ci=new CallbackInfo("grab",true);grab.invoke(mouseHooks,ci);ok(!ci.isCancelled());
            ok(sword.components.isEmpty());
        }
        System.out.println("PASS: 083 Android/sword "+checks+" assertions; real-MC-compiled production helpers/MixinItem and exact cursor hooks executed with explicit runtime hosts, 1000 simulated round/player/world/connection replacements; NOT actual SDL painting, Mixin transformation, network protocol translation or server matches");
    }
}
