import net.ccbluex.liquidbounce.ui.common.*
import net.ccbluex.liquidbounce.ui.clickgui.*
import net.ccbluex.liquidbounce.features.module.*
import java.text.Collator
import java.util.Locale

fun main() {
 val modules=listOf("FpsKnife", "KillAura", "AutoArmor", "AutoClicker", "Velocity", "Sprint", "XRay", "Fly")
 ModuleManager.testModules.addAll(modules.map { ClientModule(it,ModuleCategories.RENDER, if(it=="FpsKnife") listOf("ValorantKnife") else emptyList()) })
 UiLocalization.setLanguageMode(UiLanguageMode.ENGLISH)
 val view=ClickGuiTabView()
 val results=ClickGuiTabView::class.java.getDeclaredField("filteredSearchModules").apply { isAccessible=true }
 fun search(q:String): List<String> {
  view.searchEditor.forceText(q); view.searchEditor.onChanged?.invoke(q)
  @Suppress("UNCHECKED_CAST") val list=results.get(view) as List<ClientModule>
  return list.map { it.name }
 }
 check(search("Fps Knife")==listOf("FpsKnife"))
 check(search("ka").contains("KillAura"))
 check(search("Valorant")==listOf("FpsKnife"))
 check(search(UiLocalization.chineseCandidate("KillAura")).contains("KillAura"))
 check(search("NOT_A_REAL_MODULE").isEmpty())
 check(search("").isEmpty())
 val panels=ClickGuiTabView::class.java.getDeclaredField("panels").apply{isAccessible=true}.get(view) as List<*>
 val panel=panels.first()!!
 val rows=panel.javaClass.getDeclaredField("modules").apply { isAccessible=true }
 fun order(): List<String> = (rows.get(panel) as List<*>).map { row ->
  (row!!.javaClass.getDeclaredField("module").apply{isAccessible=true}.get(row) as ClientModule).name
 }
 check(order()==modules.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { UiTextUtils.formatEnglishName(it) }))
 UiLocalization.setLanguageMode(UiLanguageMode.CHINESE)
 view.onLanguageOrFormatChanged()
 val cmp=Collator.getInstance(Locale.CHINA)
 check(order()==modules.sortedWith { a,b -> cmp.compare(UiLocalization.moduleName(a), UiLocalization.moduleName(b)) })
 UiLocalization.setLanguageMode(UiLanguageMode.ENGLISH);view.onLanguageOrFormatChanged()
 check(order()==modules.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { UiTextUtils.formatEnglishName(it) }))
 val available=ClickGuiTabView::class.java.getDeclaredMethod("computeAvailableBodyHeight",Float::class.javaPrimitiveType,Float::class.javaPrimitiveType).apply{isAccessible=true}
 for(h in listOf(60f,108f,240f,720f)) for(top in listOf(0f,20f,200f,600f)) {
  val body=available.invoke(view,top,h) as Float
  check(body>=0f && body <= (h-top-ClickGuiTabView.PANEL_HEADER_HEIGHT-8f).coerceAtLeast(0f))
 }
 println("PASS: compact/spaced English, acronym, alias, Chinese, no-match, clear; live EN/ZH/EN sorting; 16 viewport-height boundary checks.")
}
