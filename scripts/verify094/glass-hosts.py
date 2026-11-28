from pathlib import Path
import sys
w,r=map(Path,sys.argv[1:]);p=r/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/onyx/render/UI.kt';s=p.read_text();a=s.index('    fun musicPanelCard(');b=s.index('{',a);i=b+1;d=1
while d:
 d+=(s[i]=='{')-(s[i]=='}');i+=1
p=w/'OnyxHook.kt';code=p.read_text();i2=code.rindex('}');code=code[:i2]+s[a:i]+code[i2:];code=code.replace('import net.minecraft.client.gui.GuiGraphicsExtractor','import kotlin.math.min\nimport net.minecraft.client.gui.GuiGraphicsExtractor');p.write_text(code)
print('094: exact dedicated Music quiet-glass painter body added to the real-API surface compile; shared HUD painter unchanged')
