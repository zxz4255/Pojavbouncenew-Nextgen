package verify085;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Resolve compiled port's actual MC/VFP owners and descriptors, including inherited members. */
public final class ApiAudit {
    static final Map<String,ClassNode> classes=new HashMap<>();
    static final List<ZipFile> jars=new ArrayList<>();
    static int checks,mcRefs,wireRefs;
    static ClassNode read(byte[] bytes){ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,ClassReader.SKIP_DEBUG);return c;}
    static ClassNode load(String name)throws Exception{
        if(classes.containsKey(name))return classes.get(name);
        for(ZipFile jar:jars){var entry=jar.getEntry(name+".class");if(entry!=null){ClassNode c=read(jar.getInputStream(entry).readAllBytes());classes.put(name,c);return c;}}
        return null;
    }
    static boolean member(String owner,String name,String desc,boolean field,Set<String> seen)throws Exception{
        if(!seen.add(owner))return false;
        ClassNode c=load(owner);if(c==null)return false;
        if(field){for(FieldNode f:c.fields)if(f.name.equals(name)&&f.desc.equals(desc))return true;}
        else for(MethodNode m:c.methods)if(m.name.equals(name)&&m.desc.equals(desc))return true;
        if(name.equals("<init>"))return false;
        if(c.superName!=null && member(c.superName,name,desc,field,seen))return true;
        for(String parent:c.interfaces)if(member(parent,name,desc,field,seen))return true;
        return false;
    }
    public static void main(String[] args)throws Exception{
        for(String p:args[1].split(java.io.File.pathSeparator))if(p.endsWith(".jar"))jars.add(new ZipFile(p));
        Path root=Path.of(args[0]);int files=0;
        try(var paths=Files.walk(root)){
            for(Path file:paths.filter(p->p.toString().endsWith(".class")).toList()){
                ClassNode c=read(Files.readAllBytes(file));
                if(!c.name.startsWith("net/ccbluex/liquidbounce/features/module/modules/combat/neoraven/") || c.name.endsWith("RegressionKt") || c.name.endsWith("Host"))continue;
                files++;
                for(MethodNode m:c.methods)for(AbstractInsnNode ins:m.instructions){
                    String owner,name,desc;boolean field;
                    if(ins instanceof MethodInsnNode call){owner=call.owner;name=call.name;desc=call.desc;field=false;}
                    else if(ins instanceof FieldInsnNode access){owner=access.owner;name=access.name;desc=access.desc;field=true;}
                    else continue;
                    boolean mc=owner.startsWith("net/minecraft/");
                    boolean wire=owner.equals("net/ccbluex/liquidbounce/utils/client/vfp/VfpCompatibility1_8");
                    if(!mc&&!wire)continue;
                    if(!member(owner,name,desc,field,new HashSet<>()))throw new AssertionError("Missing real API: "+owner+"."+name+desc+" from "+c.name+"."+m.name);
                    checks++;if(mc)mcRefs++;else wireRefs++;
                }
            }
        }
        if(mcRefs<100 || wireRefs<5)throw new AssertionError("Incomplete audit input");
        System.out.println("PASS: "+checks+" real API member references (Minecraft="+mcRefs+", production VFP bridge="+wireRefs+") in "+files+" compiled port classes");
        for(ZipFile jar:jars)jar.close();
    }
}
