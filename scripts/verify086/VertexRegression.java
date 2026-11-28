/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.lang.reflect.*;
import java.util.*;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.ccbluex.liquidbounce.render.gui.element.ClickGuiGlassGuiElementRenderState;
import org.joml.Matrix3x2f;
/** Runs production record packing against the real MC VertexConsumer interface; no renderer startup. */
public class VertexRegression {
    static int checks;
    static void check(boolean b) { checks++;if(!b) throw new AssertionError("check " + checks); }
    static List<Float> emit(boolean style, float opacity, float width, float height, float refract) {
        List<Float> modes = new ArrayList<>();
        VertexConsumer c = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),new Class[]{VertexConsumer.class},
            (p,m,a)->{if(m.getName().equals("setLineWidth"))modes.add((Float)a[0]);
                return m.getReturnType().isInstance(p)?p:null;});
        new ClickGuiGlassGuiElementRenderState(0,0,width,height,12,8,4,0,
            0x509C9C9C,0x509C9C9C,0x50888888,0x50888888,
            opacity,refract,0xA8FFFFFF,.85f,0x70000000,14f,style,null,new Matrix3x2f(),null,null).buildVertices(c);
        return modes;
    }
    public static void main(String[] args) {
        for(int i=0;i<1000;i++) {
            float opacity=(i%64+1)/64f, refract=(i%31)/10f;
            var a=emit(false,opacity,80+i%170,32+i%70,refract);
            var b=emit(true,opacity,80+i%170,32+i%70,refract);
            check(a.equals(b));check(a.size()==12);
            for(int n=0;n<4;n++)check(a.get(n)==-14f);
            for(int n=4;n<8;n++)check(Math.abs(a.get(n)-((i%64+1)+refract*.25f))<1e-5);
            for(int n=8;n<12;n++)check(Math.abs(a.get(n)-100.85f)<1e-5);
        }
        for(boolean flag:new boolean[]{false,true}) {
            check(emit(flag,0,100,32,1).isEmpty());check(emit(flag,1,0,32,1).isEmpty());
            check(emit(flag,1,100,0,1).isEmpty());check(emit(flag,.004f,100,32,1).isEmpty());
        }
        System.out.println("PASS: "+checks+" production record assertions; per-element style leaves old vertex mode packing unchanged");
    }
}
