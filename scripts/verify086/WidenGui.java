/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Apply exactly the three existing GUI access-widener declarations to real MC bytecode.
 * This is a compilation classpath overlay only, never shipped as patched Minecraft.
 */
public class WidenGui {
    static int widen(int a) { return (a & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC; }
    public static void main(String[] a) throws Exception {
        String host = "net/minecraft/client/gui/GuiGraphicsExtractor";
        String inner = host + "$ScissorStack";
        try (JarFile in = new JarFile(a[0]); JarOutputStream out = new JarOutputStream(Files.newOutputStream(Path.of(a[1])))) {
            for (String name : new String[]{host, inner}) {
                ClassNode n = new ClassNode();
                new ClassReader(in.getInputStream(in.getJarEntry(name + ".class"))).accept(n, 0);
                if (name.equals(inner)) n.access = widen(n.access);
                for (InnerClassNode i : n.innerClasses) if (i.name.equals(inner)) i.access = widen(i.access);
                for (FieldNode f : n.fields) if (name.equals(host) && (f.name.equals("scissorStack") || f.name.equals("guiRenderState"))) f.access = widen(f.access);
                ClassWriter w = new ClassWriter(0); n.accept(w);
                out.putNextEntry(new JarEntry(name + ".class"));out.write(w.toByteArray());out.closeEntry();
            }
        }
    }
}
