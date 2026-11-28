package verify084;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Real MC26.3 bytecode selectors/LVT audit; not a full Mixin transformation. */
public final class ApiAudit {
    static int checks;
    static void ok(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    static ClassNode read(byte[] b){ClassNode c=new ClassNode();new ClassReader(b).accept(c,0);return c;}
    static Object value(AnnotationNode a,String key){if(a.values!=null)for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals(key))return a.values.get(i+1);return null;}
    static List<AnnotationNode> annotations(MethodNode m){List<AnnotationNode> r=new ArrayList<>();if(m.visibleAnnotations!=null)r.addAll(m.visibleAnnotations);if(m.invisibleAnnotations!=null)r.addAll(m.invisibleAnnotations);return r;}
    public static void main(String[] args)throws Exception{
        Path compiled=Path.of(args[0]);
        try(ZipFile mc=new ZipFile(args[1])){
            String[][] pairs={
                {"verify084/CursorGuiHooks","net/minecraft/client/gui/Gui"},
                {"verify084/CursorMouseHooks","net/minecraft/client/MouseHandler"}
            };
            int injections=0;
            for(String[] pair:pairs){
                ClassNode hooks=read(Files.readAllBytes(compiled.resolve(pair[0]+".class")));
                ClassNode host=read(mc.getInputStream(mc.getEntry(pair[1]+".class")).readAllBytes());
                for(MethodNode hook:hooks.methods)for(AnnotationNode annotation:annotations(hook)){
                    if(!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))continue;
                    injections++;
                    @SuppressWarnings("unchecked") List<String> selectors=(List<String>)value(annotation,"method");
                    ok(selectors.size()==1,"one target per084 injection");
                    String selector=selectors.getFirst();
                    List<MethodNode> targets=host.methods.stream().filter(m->m.name.equals(selector)).toList();
                    ok(targets.size()==1,"ambiguous/missing real MC target "+selector);
                    MethodNode target=targets.getFirst();
                    Type[] targetArgs=Type.getArgumentTypes(target.desc),hookArgs=Type.getArgumentTypes(hook.desc);
                    for(int i=0;i<targetArgs.length;i++)ok(targetArgs[i].equals(hookArgs[i]),"argument mismatch "+hook.name);
                    Type callback=hookArgs[targetArgs.length];
                    ok(callback.getClassName().equals(Type.getReturnType(target.desc).equals(Type.VOID_TYPE)?
                        "org.spongepowered.asm.mixin.injection.callback.CallbackInfo":"org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable"),"callback kind "+hook.name);
                    @SuppressWarnings("unchecked") List<AnnotationNode> ats=(List<AnnotationNode>)value(annotation,"at");
                    for(AnnotationNode at:ats){
                        String mode=(String)value(at,"value");
                        if(mode.equals("HEAD")||mode.equals("RETURN"))continue;
                        ok(mode.equals("INVOKE"),"unexpected injection mode");
                        String invoke=(String)value(at,"target");int count=0,index=-1,i=0;
                        for(AbstractInsnNode ins:target.instructions){
                            if(ins instanceof MethodInsnNode m && invoke.equals("L"+m.owner+";"+m.name+m.desc)){count++;index=i;}
                            i++;
                        }
                        ok(count==1,"real applyCursor target must be unique");
                        int instruction=index;
                        ok(target.localVariables.stream().anyMatch(v->v.name.equals("graphics") &&
                            v.desc.equals("Lnet/minecraft/client/gui/GuiGraphicsExtractor;") &&
                            target.instructions.indexOf(v.start)<=instruction && target.instructions.indexOf(v.end)>instruction),"graphics LVT live at cursor hook");
                    }
                }
            }
            ok(injections==3,"expected two GUI state hooks + one mouse guard, zero cursor rendering hooks");
            ClassNode vanilla=read(mc.getInputStream(mc.getEntry("com/mojang/blaze3d/platform/InputConstants.class")).readAllBytes());
            MethodNode release=vanilla.methods.stream().filter(m->m.name.equals("releaseMouse")).findFirst().orElseThrow();
            boolean relative=false,show=false;
            for(AbstractInsnNode ins:release.instructions)if(ins instanceof MethodInsnNode m){relative|=m.name.equals("SDL_SetWindowRelativeMouseMode");show|=m.name.equals("SDL_ShowCursor");}
            ok(relative && !show,"actual26.3 vanilla release disables relative mode but does not explicitly ShowCursor");
        }
        ClassNode helper=read(Files.readAllBytes(compiled.resolve("net/ccbluex/liquidbounce/utils/input/AndroidGuiCursor.class")));
        Set<String> sdl=new HashSet<>();
        ok(helper.fields.stream().noneMatch(f->f.name.equals("ARROW")),"no bitmap pointer");
        ok(helper.methods.stream().noneMatch(m->m.name.equals("extract")),"no cursor extraction API");
        for(MethodNode m:helper.methods)for(AbstractInsnNode ins:m.instructions)if(ins instanceof MethodInsnNode call){
            ok(!call.owner.contains("GuiGraphics") && !call.owner.startsWith("net/minecraft/client/renderer/") && !call.owner.endsWith("/UiDraw"),"no drawing dependencies");
            ok(!Set.of("onMove","onButton","getScaledXPos","getScaledYPos","xpos","ypos","releaseAll","setPosition").contains(call.name),"no synthetic input/coordinate remapping/key reset");
            if(call.owner.equals("org/lwjgl/sdl/SDLMouse"))sdl.add(call.name);
        }
        ok(sdl.equals(Set.of("SDL_ShowCursor","SDL_GetWindowRelativeMouseMode","SDL_SetWindowRelativeMouseMode")),"only native visibility and relative-mode operations, no warp/custom cursor");
        System.out.println("PASS: 084 real MC26.3 API/bytecode "+checks+" checks; 3 native-state hook signatures, zero cursor drawing hooks; compiled helper contains no drawing/bitmap/synthetic input/coordinate remap; only native SDL visibility+relative mode. Not full Mixin application.");
    }
}
