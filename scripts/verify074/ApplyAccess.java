import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Applies only declared `accessible` entries for pipeline classes to a tiny compile-time overlay.
 * Not a Minecraft redistribution or Loom substitute. The original jar remains untouched. */
public class ApplyAccess {
    public static void main(String[] args) throws Exception {
        Map<String, Set<String>> rules = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 5 || !p[0].equals("accessible")) continue;
            if (!p[2].contains("RenderPipeline") && !p[2].endsWith("/DefaultVertexFormat")) continue;
            rules.computeIfAbsent(p[2], k -> new HashSet<>()).add(p[1] + ":" + p[3] + ":" + p[4]);
        }
        try (JarFile original = new JarFile(args[0]); JarOutputStream output = new JarOutputStream(Files.newOutputStream(Path.of(args[2])))) {
            for (var entry : rules.entrySet()) {
                String file = entry.getKey() + ".class";
                byte[] bytes = original.getInputStream(original.getJarEntry(file)).readAllBytes();
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    int widen(int flags) { return (flags & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC; }
                    @Override public MethodVisitor visitMethod(int flags, String name, String desc, String signature, String[] exceptions) {
                        if (entry.getValue().contains("method:" + name + ":" + desc)) flags = widen(flags);
                        return super.visitMethod(flags, name, desc, signature, exceptions);
                    }
                    @Override public FieldVisitor visitField(int flags, String name, String desc, String signature, Object value) {
                        if (entry.getValue().contains("field:" + name + ":" + desc)) flags = widen(flags);
                        return super.visitField(flags, name, desc, signature, value);
                    }
                }, 0);
                output.putNextEntry(new JarEntry(file)); output.write(writer.toByteArray()); output.closeEntry();
            }
        }
        System.out.println("PASS: compile overlay applies existing pipeline access-widener declarations (" + rules.size() + " classes).");
    }
}
