#!/usr/bin/env python3
"""Extract unchanged production class bodies; only rendering/game/unrelated services are fixtures."""
from pathlib import Path
import re,sys,zipfile,hashlib
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]); baseline=Path(sys.argv[2])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='4ef8b17ad9ec340ce9ca506434db7b1c70ff3a5512e972db15d2b7ff1d55fc12'
k='src/main/kotlin/net/ccbluex/liquidbounce/'
u=k+'ui/clickgui/setting/'
def put(name,text):
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
def source(path,old=False):
 if old:
  with zipfile.ZipFile(baseline) as z:return z.read(path).decode()
 return (root/path).read_text()
def section(s,start,end):return s[s.index(start):s.index(end,s.index(start))]
# Full actual Value and UiTextEditState; not hand-written facsimiles.
put('common/Value.kt',source(k+'config/types/Value.kt'))
put('common/UiTextEditState.kt',source(k+'ui/common/UiTextEditState.kt'))
for mode in ['old','fixed']:
 old=mode=='old';s=source(u+'SettingWidgets.kt',old)
 imports='''package net.ccbluex.liquidbounce.ui.clickgui.setting
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.ui.clickgui.ClickGuiLayoutStore
import net.ccbluex.liquidbounce.ui.common.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import com.mojang.blaze3d.platform.InputConstants
'''
 put(mode+'/ExtractedWidgets.kt',imports+section(s,'abstract class SettingNode(', '/**\n * Exact `Switch.svelte`')+section(s,'class TextSettingNode(', '/**\n * 7. COLOR'))
 put(mode+'/SettingRenderer.kt',source(u+'SettingRenderer.kt',old))
 s=source(u+'ListSettingWidgets.kt',old)
 put(mode+'/ExtractedListWidget.kt',imports+'import net.ccbluex.liquidbounce.config.types.list.MutableListValue\nimport kotlin.math.*\n'+section(s,'class MutableListSettingNode<','/**\n * 21. NAMED_ITEM_LIST'))
# Actual ListValue + MutableListValue bodies: Gson/listener/deserialization behavior is not mocked.
s=source(k+'config/types/list/ListValue.kt')
put('common/ExtractedListValues.kt','''package net.ccbluex.liquidbounce.config.types.list
import com.google.gson.*
import net.ccbluex.liquidbounce.config.types.*
import net.ccbluex.liquidbounce.config.gson.stategies.*
import net.ccbluex.liquidbounce.utils.input.HumanInputDeserializer
'''+section(s,'open class ListValue<','open class ItemListValue<'))
# Retain the exact enum names and exact TEXT StringDeserializer behavior.
s=source(k+'config/types/ValueType.kt')
names=re.findall(r'^    ([A-Z][A-Z0-9_]+)(?:\(|,|\n)',s,re.M)
put('fixtures/ValueType.kt','''package net.ccbluex.liquidbounce.config.types
import net.ccbluex.liquidbounce.utils.input.HumanInputDeserializer
// Dependencies of non-TEXT enum entries (registries/completers) are not used by this harness.
enum class ValueType(val deserializer: HumanInputDeserializer.StringDeserializer<*>? = null) {
'''+',\n'.join(n+('(HumanInputDeserializer.textDeserializer)' if n=='TEXT' else '') for n in names)+'\n}\n')
factory=source(k+'config/types/group/ValueGroup.kt')
factory=section(factory,'    fun text(name: String, default: String)', '    fun easing(')
put('fixtures/Groups.kt','''package net.ccbluex.liquidbounce.config.types.group
import net.ccbluex.liquidbounce.config.types.*
import net.ccbluex.liquidbounce.config.types.list.*
open class ValueGroup(name: String, children: MutableList<Value<*>> = mutableListOf()) : Value<MutableList<Value<*>>>(name, defaultValue=children, valueType=ValueType.CONFIGURABLE) {
    fun <T:Any> value(name:String, default:T, type:ValueType):Value<T> = Value(name,defaultValue=default,valueType=type).also { inner.add(it) }
    internal inline fun <C:MutableCollection<E>,reified E> mutableList(name:String, default:C, type:ValueType):MutableListValue<C,E> = MutableListValue(name,default,type,E::class.java).also { inner.add(it) }
'''+factory+'''}
class Mode(val name:String="Mode")
class ModeValueGroup<T>(name:String="Mode"):ValueGroup(name) { val activeMode=Mode() }
open class ToggleableValueGroup(name:String="Toggle"):ValueGroup(name)
''')
print('Prepared exact immutable081 negative-control and patched node/renderer/list bodies; actual Value, UiTextEditState and list values; host fixtures explicitly separated')
