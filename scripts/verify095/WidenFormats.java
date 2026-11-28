import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public class WidenFormats {
 public static void main(String[] a) throws Exception {
  String owner="com/mojang/blaze3d/vertex/DefaultVertexFormat";
  Set<String> fields=new HashSet<>();
  for(String line:Files.readAllLines(Path.of(a[1]))) {
   String[] p=line.trim().split("\\s+");
   if(p.length==5 && p[0].equals("accessible") && p[1].equals("field") && p[2].equals(owner))fields.add(p[3]);
  }
  if(fields.size()!=7)throw new AssertionError(fields);
  try(ZipFile z=new ZipFile(a[0]);ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(Path.of(a[2])))) {
   for(String name:List.of(owner,"net/minecraft/client/renderer/RenderPipelines")) {
    Set<String> selected=new HashSet<>();
    for(String line:Files.readAllLines(Path.of(a[1]))) {
     String[] p=line.trim().split("\\s+");
     if(p.length==5 && p[0].equals("accessible") && p[1].equals("field") && p[2].equals(name))selected.add(p[3]);
    }
    if(name.endsWith("RenderPipelines") && !selected.contains("GUI_SNIPPET"))throw new AssertionError("missing declared GUI AW");
    ClassNode c=new ClassNode();new ClassReader(z.getInputStream(z.getEntry(name+".class"))).accept(c,0);
    for(FieldNode f:c.fields)if(selected.contains(f.name)) f.access=(f.access & ~(Opcodes.ACC_PRIVATE|Opcodes.ACC_PROTECTED))|Opcodes.ACC_PUBLIC;
    ClassWriter w=new ClassWriter(0);c.accept(w);out.putNextEntry(new ZipEntry(name+".class"));out.write(w.toByteArray());out.closeEntry();
   }
  }
 }
}
