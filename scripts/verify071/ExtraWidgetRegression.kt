import net.ccbluex.liquidbounce.ui.clickgui.*
import net.ccbluex.liquidbounce.ui.clickgui.setting.*
import net.ccbluex.liquidbounce.config.types.group.Value
import net.ccbluex.liquidbounce.config.types.CurveValue
import net.ccbluex.liquidbounce.config.types.list.*
import net.ccbluex.liquidbounce.render.engine.type.Color4b

private enum class Option(override val tag:String):Tagged { A("A"), B("B") }
fun main(args:Array<String>) {
 val factories=listOf<() -> SettingNode>(
  { ColorSettingNode(Value(Color4b(20,30,40)).apply{key="test.Color"}) },
  { MultiChooseSettingNode(MultiChoiceListValue("Modes",Option.entries).apply{key="test.Multi"}) },
  { CurveSettingNode(CurveValue("Curve").apply{key="test.Curve"}) },
  { NamedItemListSettingNode(ItemListValue("Named",linkedSetOf<String>()).apply{key="test.Named"}) },
  { RegistryListSettingNode(RegistryListValue("Registry",java.util.LinkedHashSet<String>()).apply{key="test.Registry"}) },
  { RegistryMutableListSettingNode(RegistryMutableListValue("RegistryMutable",mutableListOf<String>()).apply{key="test.RegistryMutable"}) }
 )
 fun getter(n:Any,name:String)=n.javaClass.getDeclaredMethod(name).apply{isAccessible=true}.invoke(n)
 for(factory in factories) {
  val n=factory()
  if(args[0]=="write") {
   n.javaClass.getDeclaredMethod("setExpanded",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(n,true)
  }
  val restored=factory()
  check(getter(restored,"getExpanded")==true)
  check(restored.javaClass.getDeclaredField("expandAnim").apply{isAccessible=true}.getFloat(restored)==1f)
 }
 ClickGuiLayoutStore.save()
 println("PASS ${args[0]}: color, multi-choice, curve, named-list, registry-list and mutable-registry-list expansion + initial animation height")
}
