import net.ccbluex.liquidbounce.ui.clickgui.*
import net.ccbluex.liquidbounce.ui.clickgui.setting.*
import net.ccbluex.liquidbounce.features.module.*
import net.ccbluex.liquidbounce.config.types.group.*
import net.ccbluex.liquidbounce.config.types.list.*
import net.ccbluex.liquidbounce.ui.common.*
import com.mojang.blaze3d.platform.InputConstants
import java.io.File

fun main(args: Array<String>) {
 val op=args.first()
 val root=File(System.getProperty("verify.layoutDir"))
 val layout=File(root,"ui_layout.json")
 if(op=="legacy") {
  ClickGuiLayoutStore.save() // Must load and preserve v1 even when called before constructing any views.
  val p=ClickGuiLayoutStore.panelFor("Render",0)
  check(p.left==321f && p.top==123f && p.scrollTop==456f && p.expanded)
  check(ClickGuiLayoutStore.activeTabIndex==2)
  println("PASS legacy v1 migration / save-before-open")
  return
 }
 if(op=="invalid") {
  val p=ClickGuiLayoutStore.panelFor("Good",0)
  check(p.left==100f && p.scrollTop==0f)
  class Bad {
   var b by ClickGuiLayoutStore.booleanState({"bad"},"expanded")
   var scroll by ClickGuiLayoutStore.scrollState({"bad"})
  }
  val b=Bad();check(!b.b && b.scroll==0f)
  b.scroll=Float.NaN;check(b.scroll==0f)
  b.scroll=-10f;check(b.scroll==0f)
  ClickGuiLayoutStore.save()
  println("PASS malformed-entry isolation / finite nonnegative offsets")
  return
 }
 val cat=ModuleCategories.RENDER
 fun group(name:String,key:String)=ValueGroup(name).apply {this.key=key}
 val outer=group("Animation","module.Module15.Animation")
 val inner=group("Details","module.Module15.Animation.Details")
 repeat(50) { inner.inner.add(Value(false,"Entry$it").apply{key="module.Module15.Animation.Details.Entry$it"}) }
 outer.inner.add(inner)
 val toggle=ToggleableValueGroup("Extra").apply {
  key="module.Module15.Extra";this.inner.add(Value(false,"Option").apply{key="module.Module15.Extra.Option"})
 }
 val mode=Mode("ModeA").apply{key="module.Module15.Choice.ModeA";this.inner.add(Value(false))}
 val choice=ModeValueGroup("Choice",listOf(mode)).apply {key="module.Module15.Choice"}
 val list=MutableListValue("List",MutableList(40){"item$it"}).apply {key="module.Module15.List"}
 for(i in 0 until 30) {
  val m=ClientModule("Module%02d".format(i),cat).apply{key="module.$name"}
  if(i==15) m.inner.addAll(listOf(outer,toggle,choice,list))
  ModuleManager.testModules.add(m)
 }
 val sameName=group("Animation","module.Module16.Animation").apply {this.inner.add(Value(false))}
 val view=ClickGuiTabView()
 fun rows(v:ClickGuiTabView):List<Any> {
  val panel=(v.javaClass.getDeclaredField("panels").apply{isAccessible=true}.get(v) as List<*>).first()!!
  @Suppress("UNCHECKED_CAST") return panel.javaClass.getDeclaredField("modules").apply{isAccessible=true}.get(panel) as List<Any>
 }
 fun row(v:ClickGuiTabView):Any = rows(v).first{ (it.javaClass.getDeclaredField("module").apply{isAccessible=true}.get(it) as ClientModule).name=="Module15" }
 fun setOpen(o:Any,v:Boolean) { o.javaClass.getDeclaredMethod("setExpanded",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(o,v) }
 fun isOpen(o:Any)=o.javaClass.getDeclaredMethod("getExpanded").apply{isAccessible=true}.invoke(o) as Boolean
 fun anim(o:Any)=o.javaClass.getDeclaredField("expandAnim").apply{isAccessible=true}
 fun bound(n:SettingNode) {n.lastX=0f;n.lastY=0f;n.lastWidth=230f;n.lastHeight=2000f}
 val parent=SettingRenderer.nodeFor(outer) as ConfigurableGroupNode
 val child=SettingRenderer.nodeFor(inner) as ConfigurableGroupNode
 val toggleNode=SettingRenderer.nodeFor(toggle)
 val choiceNode=SettingRenderer.nodeFor(choice)
 val listNode=SettingRenderer.nodeFor(list)
 val other=SettingRenderer.nodeFor(sameName) as ConfigurableGroupNode
 val panel=ClickGuiLayoutStore.panelFor("Render",0)
 if(op=="write") {
  panel.left=340f;panel.top=70f;panel.expanded=true;panel.scrollTop=1400f
  setOpen(row(view),true)
  parent.expanded=true;child.expanded=true
  bound(toggleNode);check(toggleNode.mouseClicked(225f,10f,InputConstants.MOUSE_BUTTON_RIGHT,false))
  bound(choiceNode);check(choiceNode.mouseClicked(225f,10f,InputConstants.MOUSE_BUTTON_RIGHT,false)) // Default-open -> remember explicit collapse
  bound(listNode);check(listNode.mouseClicked(10f,10f,InputConstants.MOUSE_BUTTON_LEFT,false))
  listNode.snapLayoutState();check(listNode.mouseScrolled(10f,60f,-3.0))
  check(!other.expanded) // Identical group names under different modules remain independent.
  anim(parent).setFloat(parent,0.02f);anim(child).setFloat(child,0.03f)
  anim(row(view)).setFloat(row(view),0.04f)
  val reopened=ClickGuiTabView() // New screen before old expansion animations have completed.
  check(isOpen(row(reopened)) && anim(row(reopened)).getFloat(row(reopened))==1f)
  check(anim(parent).getFloat(parent)==1f && anim(child).getFloat(child)==1f)
  reopened.onLanguageOrFormatChanged(1200f,800f)
  check(panel.scrollTop==1400f) {"Saved scroll was truncated to ${panel.scrollTop}"}
  ClickGuiLayoutStore.activeTabIndex=0
  ClickGuiLayoutStore.save()
  check(layout.isFile && !File(root,"ui_layout.json.tmp").exists())
  println("PASS in-session reopen: module/nested/toggle/list expansion, first-measure heights, scroll 1400, duplicate-name isolation, atomic save")
 } else {
  check(panel.left==340f && panel.top==70f && panel.expanded && panel.scrollTop==1400f)
  check(isOpen(row(view)) && anim(row(view)).getFloat(row(view))==1f)
  check(parent.expanded && child.expanded && isOpen(toggleNode) && !isOpen(choiceNode) && isOpen(listNode))
  check(anim(parent).getFloat(parent)==1f && anim(child).getFloat(child)==1f)
  check(listNode.javaClass.getDeclaredField("scrollOffset").apply{isAccessible=true}.getFloat(listNode)==90f)
  check(!other.expanded)
  view.onLanguageOrFormatChanged(1200f,800f);check(panel.scrollTop==1400f)
  UiLocalization.setLanguageMode(UiLanguageMode.CHINESE);view.onLanguageOrFormatChanged(1200f,800f);check(panel.scrollTop==1400f)
  UiLocalization.setLanguageMode(UiLanguageMode.ENGLISH);view.onLanguageOrFormatChanged(1200f,800f);check(panel.scrollTop==1400f)
  println("PASS fresh JVM/disk restore: panel XY/open/scroll, module + nested expansion, explicit choice collapse, list scroll, EN/ZH sorting")
 }
}
