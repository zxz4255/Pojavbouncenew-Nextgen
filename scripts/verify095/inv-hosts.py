from pathlib import Path
import sys
w=Path(sys.argv[1])
p=w/'Config.kt';s=p.read_text().replace('const val KEY_PREFIX="liquidbounce"','const val KEY_PREFIX="liquidbounce";var stores=0;fun store(config:Any){stores++}');p.write_text(s)
(w/'Manager.kt').write_text('package net.ccbluex.liquidbounce.features.module\nobject ModuleManager{val modulesConfig=Any()}\n')
p=w/'Input.kt';s=p.read_text().replace('class InputBind(type:', 'data class InputBind(val type:').replace(',code:Int=0',',val code:Int=0');p.write_text(s)
