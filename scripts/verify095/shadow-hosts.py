from pathlib import Path
import sys
r,w=map(Path,sys.argv[1:]);w.mkdir(parents=True,exist_ok=True)
k=r/'src/main/kotlin/net/ccbluex/liquidbounce'
def block(s,needle):
 a=s.index(needle);i=s.index('{',a)+1;d=1
 while d:
  d+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
s=(k/'render/ClientRenderPipelines.kt').read_text()
(w/'Pipeline.kt').write_text('''package net.ccbluex.liquidbounce.render
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.RenderPipelines
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
object ClientRenderPipelines {
fun newPipeline(name:String,fn:RenderPipeline.Builder.()->Unit):RenderPipeline=RenderPipeline.builder().withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("liquidbounce",name)).apply(fn).build()
'''+block(s,'    val OpaiIslandShadow =')+'\n}\n')
s=(k/'render/ClientShaders.kt').read_text()
v=next(x for x in s.splitlines() if 'val OnyxSoftShadow =' in x and '.vsh' in x)
f=next(x for x in s.splitlines() if 'val OpaiIslandShadow =' in x)
(w/'Shaders.kt').write_text('''package net.ccbluex.liquidbounce.render
private operator fun String.invoke(path:String)=net.minecraft.resources.Identifier.fromNamespaceAndPath("liquidbounce",path)
object ClientShaders {object Vertex {
'''+v+'\n}; object Fragment {\n'+f+'\n}}\n')
s=(k/'features/module/modules/render/ModuleOpaiDymisisland.kt').read_text()
(w/'Shadow.kt').write_text('''package verify095
import kotlin.math.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.ccbluex.liquidbounce.render.*
class Surface(val ctx:GuiGraphicsExtractor) {
'''+block(s,'    override fun shadow(').replace('override fun','fun',1)+'\n}\n')
# Project-only queue/bounds hosts have the exact production extension signature and real MC types.
s=(k/'render/Render2D.kt').read_text();sig=s[s.index('inline fun GuiGraphicsExtractor.drawCustomElement('):s.index(') = this.guiRenderState.addGuiElement(',s.index('inline fun GuiGraphicsExtractor.drawCustomElement('))+1]
sig=sig.replace('inline fun','fun').replace('this.scissorStack.peek()','null')
(w/'Draw.kt').write_text('''package net.ccbluex.liquidbounce.render
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.navigation.ScreenRectangle
import com.mojang.blaze3d.vertex.VertexConsumer
import org.joml.Matrix3x2fc
import net.ccbluex.liquidbounce.utils.render.VerticesSetupHandler
'''+sig+''' {}
fun GuiGraphicsExtractor.getBounds(x:Float,y:Float,r:Float,b:Float):ScreenRectangle=ScreenRectangle(x.toInt(),y.toInt(),(r-x).toInt(),(b-y).toInt())
''')
