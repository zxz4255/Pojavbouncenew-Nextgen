from pathlib import Path
import sys
R=Path(__file__).resolve().parents[2];K=R/'src/main/kotlin/net/ccbluex/liquidbounce';W=Path(sys.argv[1]);W.mkdir(exist_ok=True)
def read(p):return (K/p).read_text()
def block(s,needle):
 a=s.index(needle);b=s.index('{',a);n=1;i=b+1
 while n:
  n+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
for name in ['S1.kt','S3.kt','S4.kt','S6.kt','Client.kt','Color.kt']:
 s=(R/'scripts/verify076/stubs'/name).read_text();(W/name).write_text(s)
(W/'Events.kt').write_text('package net.ccbluex.liquidbounce.event.events\nimport net.ccbluex.liquidbounce.event.Event\nclass WorldChangeEvent:Event\n')
p=W/'Client.kt';p.write_text(p.read_text()+'\nval gpuDevice get()=com.mojang.blaze3d.systems.RenderSystem.getDevice()\n')
(W/'LiquidBounce.kt').write_text('''package net.ccbluex.liquidbounce
object LiquidBounce { const val CLIENT_NAME="verify091";fun identifier(s:String)=net.minecraft.resources.Identifier.fromNamespaceAndPath("liquidbounce",s) }
''')
(W/'Optional.kt').write_text('package net.ccbluex.liquidbounce.utils.kotlin\ninline fun <T:Any> optional()=java.util.Optional.empty<T>()\n')
s=read('render/ClientRenderPipelines.kt');methods='\n'.join(block(s,n) if '{' in s[s.index(n):s.index(n)+130] else '' for n in [])
# Exact helpers + new production pipeline, isolated from unrelated registry entries.
a=s.index('    inline fun RenderPipeline.Builder.withBindGroupLayout(');b=s.index('    private inline fun RenderPipeline.Builder.forWorldRender',a)
helpers=s[a:b]+block(s,'    inline fun RenderPipeline.Builder.screenQuadSnippet()')
imports='\n'.join(x for x in s.splitlines() if x.startswith('import ') and not any(y in x for y in ['fastutil','logger','gpuDevice','Util','LiquidBounce']))
new='''    fun newPipeline(name:String, fn:RenderPipeline.Builder.()->Unit):RenderPipeline = RenderPipeline.builder().withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("liquidbounce",name)).apply(fn).build()
'''
(W/'Pipelines.kt').write_text('package net.ccbluex.liquidbounce.render\n'+imports+'\nobject ClientRenderPipelines {\n'+new+helpers+'\n'+block(s,'    val MotionBlur =')+'\nval AtmosphereBlit=MotionBlur\n}\n')
s=read('render/ClientShaders.kt');line=next(x for x in s.splitlines() if 'val MotionBlur =' in x)
(W/'Shaders.kt').write_text('''package net.ccbluex.liquidbounce.render
object ClientShaders { object Fragment {
private operator fun String.invoke(path:String)=net.minecraft.resources.Identifier.fromNamespaceAndPath("liquidbounce",this)
'''+line+'\n} }\n')
s=read('render/RenderPassExtensions.kt');a=s.index('private val RENDER_PASS_DEFAULT_LABEL');
imports='\n'.join(x for x in s.splitlines() if x.startswith('import ') and 'Color4b' not in x)
(W/'Pass.kt').write_text('package net.ccbluex.liquidbounce.render\n'+imports+'\n'+block(s,'inline fun RenderPass.setPipeline(')+'\n'+s[a:])
s=read('utils/render/RenderExtensions.kt');a=s.index('class KStd140SizeCalculator');b=s.index('inline fun GpuDevice.createUbo',a)
# Exact std140 calculator/write methods. Only clearColorAndDepth below is a host.
(W/'Std140.kt').write_text('''package net.ccbluex.liquidbounce.utils.render
import com.mojang.blaze3d.buffers.*
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import java.nio.ByteBuffer
'''+s[a:b]+'''\ninline fun GpuBufferSlice.mapBuffer(read:Boolean=false,write:Boolean=false)=this.map(read,write)
'''+s[s.index('inline fun ByteBuffer.writeStd140'):s.index('inline fun Std140Builder.putVec4')]+'''
fun com.mojang.blaze3d.pipeline.RenderTarget.clearColorAndDepth() {}
''')
print('Generated render hosts. Complete actual uniform enum, history holder, renderer and module compiled separately; exact pipeline/helper/pass/std140 excerpts.')
