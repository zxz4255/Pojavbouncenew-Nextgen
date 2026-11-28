"""Extract exact changed Java method bodies for real-MC API compilation.
Surrounding project/bootstrap/NoGui/ModernGUI services are compile hosts.
No Mixin annotation application is claimed.
"""
from pathlib import Path
import sys
r,w=map(Path,sys.argv[1:]);w.mkdir(parents=True,exist_ok=True)
j=r/'src/main/java/net/ccbluex/liquidbounce'
def method(path,name):
 s=(j/path).read_text()
 # Find declaration, not preceding method invocations.
 import re
 m=re.search(r'    private (?:static )?(?:boolean|void) '+re.escape(name)+r'\(',s);assert m,name
 a=m.start();b=s.index('{',a)+1;d=1
 while d:d+=(s[b]=='{')-(s[b]=='}');b+=1
 return s[a:b].replace('@Nullable ','')
imports='''import net.ccbluex.liquidbounce.LiquidBounce;
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleNoGui;
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.features.FeatureSilentScreen;
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModernGuiInterceptor;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
'''
for cls,path,name in [
 ('GuiHook','client/MixinGui','cancelScreenMouseForChestStealer'),
 ('MinecraftHook','client/MixinMinecraft','gp$shouldTreatAsNoScreen'),
 ('MouseHook','client/MixinMouseHandler','gp$shouldTreatAsNoScreen'),
 ('ScreenHook','gui/MixinScreen','cancelRenderByChestStealer'),
 ('ContainerHook','gui/MixinAbstractContainerScreen','cancelMouseClick')]:
 (w/(cls+'.java')).write_text(imports+'class '+cls+' {\n'+method(Path('injection/mixins/minecraft/'+path+'.java'),name)+'\n}')
(w/'LiquidBounce.java').write_text('package net.ccbluex.liquidbounce; public enum LiquidBounce {INSTANCE; public boolean isInitialized(){return true;}}')
(w/'ModuleNoGui.java').write_text('package net.ccbluex.liquidbounce.features.module.modules.player; public class ModuleNoGui {public static boolean shouldHide(net.minecraft.client.gui.screens.Screen s){return false;}public static boolean isHidingCurrentScreen(){return false;}}')
(w/'ModernGuiInterceptor.java').write_text('package net.ccbluex.liquidbounce.features.module.modules.render.moderngui; public class ModernGuiInterceptor {public static void onBeforeScreenExtract(net.minecraft.client.gui.GuiGraphicsExtractor c,net.minecraft.client.gui.screens.Screen s){}}')
print('Extracted 5 exact changed method bodies; actual AndroidGuiCursor compiled whole, Kotlin FeatureSilentScreen/Opai classes used unchanged.')
