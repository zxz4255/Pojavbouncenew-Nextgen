package net.ccbluex.liquidbounce.ui.clickgui.setting
import net.ccbluex.liquidbounce.config.types.list.InnerType
import net.minecraft.client.gui.GuiGraphicsExtractor
object RegistryCatalog {
 class CatalogEntry<E>(val value:E,val searchLower:String) { fun resolvedLabel()="entry" }
 fun <E> entriesFor(type: InnerType): List<CatalogEntry<E>> = emptyList()
 fun drawEntryIcon(ctx: GuiGraphicsExtractor,e: CatalogEntry<*>,x:Float,y:Float,size:Float)=false
}
