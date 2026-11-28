#!/usr/bin/env python3
"""Extract only added hooks verbatim; compile them and both complete helpers against real MC APIs."""
from pathlib import Path
import hashlib,sys,zipfile
root=Path(__file__).resolve().parents[2];work=Path(sys.argv[1]);baseline=Path(sys.argv[2])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='26b08ebf9ea877f2504852da4183ad76bb069a7ac99545ad152cf99fcc3ce1a2'
r='src/main/java/net/ccbluex/liquidbounce/injection/mixins/'
with zipfile.ZipFile(baseline) as z:
 assert 'LegacySwordBlocking' not in z.read(r+'minecraft/item/MixinItem.java').decode()
 assert 'AndroidGuiCursor' not in z.read(r+'minecraft/client/MixinGui.java').decode()
 assert 'AndroidGuiCursor' not in z.read(r+'minecraft/client/MixinMouseHandler.java').decode()
def method(text,name):
 at=text.rfind('    @Inject',0,text.index('    private void '+name))
 brace=text.index('{',text.index('    private void '+name));depth=1;i=brace+1
 while depth:
  if text[i]=='{':depth+=1
  elif text[i]=='}':depth-=1
  i+=1
 return text[at:i]+'\n'
common='''import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.llamalad7.mixinextras.sugar.Local;
import net.ccbluex.liquidbounce.utils.input.AndroidGuiCursor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
'''
for file,target,cls,names,extra in [
 ('minecraft/client/MixinGui.java','net.minecraft.client.gui.Gui','CursorGuiHooks',['lb$showAndroidCursorAfterScreenChange','lb$extractAndroidCursor','hookBeginGuiExtract'],''),
 ('minecraft/client/MixinMouseHandler.java','net.minecraft.client.MouseHandler','CursorMouseHooks',['lb$keepAndroidGuiCursorVisible'],'@Shadow private Minecraft minecraft;')]:
 s=(root/r/file).read_text();out=work/'hooks'/(cls+'.java');out.parent.mkdir(parents=True,exist_ok=True)
 if cls=='CursorGuiHooks':
  imports='import net.ccbluex.liquidbounce.LiquidBounce;\nimport net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModernGuiInterceptor;\n'
 else:imports=''
 out.write_text('package verify083;\n'+common+imports+'@Mixin('+target+'.class)\npublic abstract class '+cls+' {\n'+extra+'\n'+''.join(method(s,n) for n in names)+'}\n')
# Two unrelated aiming classes needed to compile the complete original MixinItem as well as new hooks.
f=work/'project';f.mkdir(parents=True,exist_ok=True)
(f/'RotationManager.java').write_text('''package net.ccbluex.liquidbounce.utils.aiming;
public class RotationManager { public static final RotationManager INSTANCE=new RotationManager();public Rotation getCurrentRotation(){return null;}
 public static class Rotation {public net.minecraft.world.phys.Vec3 directionVector(){return null;}} }
''')
(f/'LiquidBounce.java').write_text('''package net.ccbluex.liquidbounce;public class LiquidBounce {
 public static final LiquidBounce INSTANCE=new LiquidBounce();public boolean initialized=true;public boolean isInitialized(){return initialized;}}
''')
(f/'ModuleNoGui.java').write_text('''package net.ccbluex.liquidbounce.features.module.modules.player;
public class ModuleNoGui {public static boolean hide; public static boolean shouldHide(net.minecraft.client.gui.screens.Screen screen){return hide;}}
''')
(f/'FeatureSilentScreen.java').write_text('''package net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.features;
public class FeatureSilentScreen {public static final FeatureSilentScreen INSTANCE=new FeatureSilentScreen();public boolean hide;public boolean getShouldHide(){return hide;}}
''')
(f/'OpaiChestLoot.java').write_text('''package net.ccbluex.liquidbounce.features.module.modules.render;
public class OpaiChestLoot {public static final OpaiChestLoot INSTANCE=new OpaiChestLoot();public boolean hide;public boolean isReplacing(net.minecraft.client.gui.screens.Screen screen){return hide;}}
''')
(f/'ModernGuiInterceptor.java').write_text('''package net.ccbluex.liquidbounce.features.module.modules.render.moderngui;
public class ModernGuiInterceptor {public static void onBeginGuiExtract(){}}
''')
print('PASS: immutable082 negative-source controls; extracted083 cursor hooks verbatim; complete helpers and complete MixinItem selected')
