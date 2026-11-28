package net.ccbluex.liquidbounce.features.module.modules.misc.music
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.hud.Island
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
fun main() {
 val field=MusicManager::class.java.getDeclaredField("current").apply{isAccessible=true}
 val track=Song(7,"Fixture","Artist","","",180000)
 val ctx=GuiGraphicsExtractor()
 var checks=0
 for(hasTrack in listOf(false,true)) for(chat in listOf(false,true)) for(returnMini in listOf(false,true)) {
  field.set(MusicManager,if(hasTrack)track else null);mc.gui.current=if(chat)ChatScreen()else null
  ModuleMusic.showIsland=true;Island.returnFromMusic(returnMini);MusicIsland.returned();Island.render(ctx)
  check(Island.width()>0f && UI.paints>0)
  ModuleMusic.showIsland=false;UI.paints=0
  repeat(10){MusicIsland.returned();Island.returnFromMusic(returnMini);Island.render(ctx)}
  Island.snapshot(UI(ctx),net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme.scheme(),true)
  check(UI.paints==0 && Island.width()==0f && Island.height()==0f)
  check(!MusicIsland.miniVisible() && !Island.musicCardOpen())
  check(!Island.musicClick(20f,20f) && !MusicIsland.click(20f,20f))
  check(field.get(MusicManager)==if(hasTrack)track else null)
  ModuleMusic.showIsland=true;Island.returnFromMusic(returnMini);Island.render(ctx)
  check(UI.paints>0 && Island.width()>0f)
  checks++
 }
 MusicManager.shutdown()
 println("PASS: $checks Island state combinations, 80 disabled return/render cycles: no paint, no mini timer, no hidden clicks/snapshot; track retained; explicit re-enable works")
}
