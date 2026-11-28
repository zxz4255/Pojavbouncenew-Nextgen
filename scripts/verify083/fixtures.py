#!/usr/bin/env python3
"""Runtime hosts only. Production bytes are NOT recompiled against these fixtures."""
from pathlib import Path
import sys
out=Path(sys.argv[1])/'runtime-hosts';out.mkdir(parents=True,exist_ok=True)
def put(name,s):
 package,cls=name.rsplit('.',1);(out/(cls+'.java')).write_text('package '+package+';\n'+s+'\n')
put('net.minecraft.client.Minecraft','''public class Minecraft {
 public static Minecraft instance=new Minecraft();
 public net.minecraft.client.gui.Gui gui=new net.minecraft.client.gui.Gui();
 public net.minecraft.client.MouseHandler mouseHandler=new net.minecraft.client.MouseHandler();
 public net.minecraft.client.player.LocalPlayer player=new net.minecraft.client.player.LocalPlayer();
 public net.minecraft.client.multiplayer.ClientLevel level=new net.minecraft.client.multiplayer.ClientLevel();
 public boolean singleplayer=false,active=true;
 public static Minecraft getInstance(){return instance;}
 public boolean hasSingleplayerServer(){return singleplayer;}
 public boolean isWindowActive(){return active;}
 public com.mojang.blaze3d.platform.Window getWindow(){return new com.mojang.blaze3d.platform.Window();}
}''')
put('net.minecraft.client.gui.Gui','''public class Gui {
 public net.minecraft.client.gui.screens.Screen current;
 public net.minecraft.client.gui.screens.Screen screen(){return current;}
}''')
put('net.minecraft.client.gui.screens.Screen','public class Screen {}')
put('net.minecraft.client.gui.screens.inventory.AbstractContainerScreen','public class AbstractContainerScreen<T> extends net.minecraft.client.gui.screens.Screen {}')
put('com.mojang.blaze3d.platform.Window','public class Window {public long handle(){return 7L;}}')
put('net.minecraft.client.MouseHandler','''public class MouseHandler {
 public boolean grabbed=true;public int releases=0;public double x=33.5,y=71.2;
 public boolean isMouseGrabbed(){return grabbed;}
 public void releaseMouse(){if(grabbed){grabbed=false;releases++;org.lwjgl.sdl.SDLMouse.relative=false;}}
 public double getScaledXPos(com.mojang.blaze3d.platform.Window w){return x;}
 public double getScaledYPos(com.mojang.blaze3d.platform.Window w){return y;}
}''')
put('org.lwjgl.sdl.SDLMouse','''public class SDLMouse {
 public static boolean relative=true,visible=false;public static int shows=0,sets=0;
 public static boolean SDL_GetWindowRelativeMouseMode(long w){return relative;}
 public static boolean SDL_SetWindowRelativeMouseMode(long w,boolean v){relative=v;sets++;return true;}
 public static boolean SDL_ShowCursor(){visible=true;shows++;return true;}
}''')
put('net.minecraft.client.gui.GuiGraphicsExtractor','''public class GuiGraphicsExtractor {
 public final java.util.List<int[]> pixels=new java.util.ArrayList<>();public int strata;
 private final org.joml.Matrix3x2fStack pose=new org.joml.Matrix3x2fStack(8);
 public void nextStratum(){strata++;}
 public org.joml.Matrix3x2fStack pose(){return pose;}
 public void fill(int x1,int y1,int x2,int y2,int argb){pixels.add(new int[]{x1,y1,x2,y2,argb});}
}''')
put('net.minecraft.client.DeltaTracker','public interface DeltaTracker {}')
put('net.minecraft.world.level.Level','public class Level {}')
put('net.minecraft.client.multiplayer.ClientLevel','public class ClientLevel extends net.minecraft.world.level.Level {}')
put('net.minecraft.world.level.ClipContext','public class ClipContext {public enum Fluid {NONE}}')
put('net.minecraft.world.phys.Vec3','public class Vec3 {}')
put('net.minecraft.world.InteractionHand','public enum InteractionHand { MAIN_HAND,OFF_HAND }')
put('net.minecraft.world.InteractionResult','''public interface InteractionResult {
 public static final Success CONSUME=new Success();public static final Pass PASS=new Pass();
 public static final class Success implements InteractionResult {} public static final class Pass implements InteractionResult {}
}''')
put('net.minecraft.world.entity.LivingEntity','public class LivingEntity {}')
put('net.minecraft.world.entity.player.Player','''public class Player extends net.minecraft.world.entity.LivingEntity {
 public net.minecraft.world.item.ItemStack main=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
 public net.minecraft.world.item.ItemStack off=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.AIR);
 public boolean using;public int starts;
 public net.minecraft.world.InteractionHand usedHand;
 public net.minecraft.world.item.ItemStack getItemInHand(net.minecraft.world.InteractionHand hand){return hand==net.minecraft.world.InteractionHand.MAIN_HAND?main:off;}
 public void startUsingItem(net.minecraft.world.InteractionHand hand){using=true;starts++;usedHand=hand;}
}''')
put('net.minecraft.client.player.LocalPlayer','public class LocalPlayer extends net.minecraft.world.entity.player.Player {}')
put('net.minecraft.world.item.Item','public class Item {}')
put('net.minecraft.world.item.Items','''public class Items {
 public static final Item WOODEN_SWORD=new Item(),STONE_SWORD=new Item(),IRON_SWORD=new Item(),GOLDEN_SWORD=new Item(),DIAMOND_SWORD=new Item(),NETHERITE_SWORD=new Item(),AIR=new Item(),APPLE=new Item(),BOW=new Item(),SHIELD=new Item();
}''')
put('net.minecraft.core.component.DataComponentType','public class DataComponentType<T> {}')
put('net.minecraft.core.component.DataComponents','''public class DataComponents {
 public static final DataComponentType<Object> BLOCKS_ATTACKS=new DataComponentType<>(),CONSUMABLE=new DataComponentType<>(),EQUIPPABLE=new DataComponentType<>(),KINETIC_WEAPON=new DataComponentType<>();
}''')
put('net.minecraft.world.item.ItemStack','''public class ItemStack {
 public final Item item;public int count=1;public final java.util.Set<net.minecraft.core.component.DataComponentType<?>> components=new java.util.HashSet<>();
 public ItemStack(Item item){this.item=item;}
 public boolean isEmpty(){return count==0 || item==Items.AIR;}
 public boolean is(Object item){return this.item==item;}
 public boolean has(net.minecraft.core.component.DataComponentType<?> type){return components.contains(type);}
}''')
put('net.minecraft.world.item.ItemUseAnimation','public enum ItemUseAnimation {NONE,BLOCK,EAT}')
put('com.viaversion.viafabricplus.ViaFabricPlus','''public class ViaFabricPlus {
 public static com.viaversion.viafabricplus.api.ViaFabricPlusAPI api;
 public static boolean fail;
 public static com.viaversion.viafabricplus.api.ViaFabricPlusAPI api(){if(fail)throw new IllegalStateException("transition");return api;}
}''')
put('com.viaversion.viafabricplus.api.ViaFabricPlusAPI','''public interface ViaFabricPlusAPI {
 com.viaversion.viaversion.api.connection.UserConnection userConnection();
 com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation protocolTranslation();
}''')
put('com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation','''public interface ProtocolTranslation {
 com.viaversion.viaversion.api.protocol.version.ProtocolVersion targetVersion(io.netty.channel.Channel channel);
}''')
put('com.viaversion.viaversion.api.connection.UserConnection','''public interface UserConnection {
 boolean isClientSide(); boolean isActive();boolean isPendingDisconnect();io.netty.channel.Channel getChannel();ProtocolInfo getProtocolInfo();
}''')
put('com.viaversion.viaversion.api.connection.ProtocolInfo','''public interface ProtocolInfo {
 com.viaversion.viaversion.api.protocol.packet.State getServerState();
 com.viaversion.viaversion.api.protocol.version.ProtocolVersion serverProtocolVersion();
}''')
put('io.netty.channel.Channel','public interface Channel {boolean isOpen();}')
put('com.viaversion.viaversion.api.protocol.packet.State','public enum State {PLAY,LOGIN,CONFIGURATION}')
put('com.viaversion.viaversion.api.protocol.version.ProtocolVersion','''public class ProtocolVersion {
 public static final ProtocolVersion v1_8=new ProtocolVersion(47);public final int id;
 public ProtocolVersion(int id){this.id=id;}public boolean equals(Object o){return o instanceof ProtocolVersion p && p.id==id;}
 public int hashCode(){return id;}
}''')
