# Generates source subsets for isolated checks, never edits production files.
from pathlib import Path
import sys
r=Path(sys.argv[1])/'src/main/kotlin/net/ccbluex/liquidbounce'
o=Path(sys.argv[2])
s=(r/'ui/clickgui/setting/SettingWidgets.kt').read_text()
def extract(start):
 a=s.index(start); b=s.find('\n/**',a)
 return s[a:b if b>=0 else len(s)]
(o/'ui-stubs/SettingNode.kt').write_text(
 'package net.ccbluex.liquidbounce.ui.clickgui.setting\n'
 'import net.ccbluex.liquidbounce.config.types.group.Value\n'
 'import net.minecraft.client.gui.GuiGraphicsExtractor\n'
 'import net.ccbluex.liquidbounce.ui.clickgui.ClickGuiLayoutStore\n'
 'import net.ccbluex.liquidbounce.ui.common.UiTooltipManager\n'
 + extract('abstract class SettingNode'))
(o/'ui-stubs/Groups.kt').write_text('''package net.ccbluex.liquidbounce.ui.clickgui.setting
import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.group.Value
import net.ccbluex.liquidbounce.config.types.CurveValue
import net.ccbluex.liquidbounce.config.types.list.*
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import org.joml.Vector2f
import java.awt.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import net.ccbluex.liquidbounce.config.types.group.*
import net.ccbluex.liquidbounce.ui.common.*
import net.ccbluex.liquidbounce.ui.clickgui.neumorph.ClickGuiNeumorphSurface
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.min
'''+ '\n'.join(extract('class '+n) for n in ['ChoiceModeGroupNode','ToggleableGroupNode','ConfigurableGroupNode','ColorSettingNode','MultiChooseSettingNode','CurveSettingNode'])+'\n'+extract('internal object SwitchWidget'))
s=(r/'ui/clickgui/setting/SettingRenderer.kt').read_text();a=s.index('    @Suppress("UNCHECKED_CAST")\n    private fun createNode');b=s.index('    /**',a)
s=s[:a]+'''    @Suppress("UNCHECKED_CAST")\n    private fun createNode(value: Value<*>): SettingNode = when (value) {
        is ToggleableValueGroup -> ToggleableGroupNode(value)
        is ModeValueGroup<*> -> ChoiceModeGroupNode(value)
        is ValueGroup -> ConfigurableGroupNode(value)
        is MutableListValue<*, *> -> MutableListSettingNode(value as MutableListValue<MutableCollection<Any?>, Any?>)
        else -> ReadOnlyFallbackSettingNode(value)
    }

'''+s[b:]
s='\n'.join(line for line in s.splitlines() if not line.startswith('import '));idx=s.index('object SettingRenderer')
s=s[:idx]+'''import net.ccbluex.liquidbounce.config.types.group.*
import net.ccbluex.liquidbounce.config.types.list.MutableListValue
import net.ccbluex.liquidbounce.ui.common.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.WeakHashMap

'''+s[idx:];(o/'ui-stubs/G6.kt').write_text(s)
