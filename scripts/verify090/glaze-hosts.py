#!/usr/bin/env python3
"""Compile the exact two changed header guards/calls with explicit surrounding dimensions.
NOT full ClickGuiTabView / GlobalSettingsTabView compilation.
"""
from pathlib import Path
import sys
W=Path(sys.argv[1]);R=Path(sys.argv[2]);K=R/'src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui'
def block(src,needle):
 a=src.index(needle);start=src.index('{',a);level=1;i=start+1
 while level:
  if src[i]=='{':level+=1
  elif src[i]=='}':level-=1
  i+=1
 return src[a:i]
code='''package verify090
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.minecraft.client.gui.GuiGraphicsExtractor
'''
for name in ['ClickGuiTabView','GlobalSettingsTabView']:
 s=(K/(name+'.kt')).read_text();body=block(s,'            if (!ModuleClickGui.isLightPollutionGlass) {')
 code+='fun '+name+'Glaze(ctx:GuiGraphicsExtractor,px:Float,py:Float,bodyH:Float,panelRadius:Float,PANEL_WIDTH:Float,PANEL_HEADER_HEIGHT:Float,winX:Float,winY:Float,winW:Float,headerH:Float) {\n'+body+'\n}\n'
(W/'QuietGlaze.kt').write_text(code)
print('090: exact2 changed header-glaze guard/call excerpts extracted; enclosing UI layout remains a host, not full UI compilation')
