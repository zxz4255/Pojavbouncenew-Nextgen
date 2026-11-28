/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.lang.reflect.*;
import java.util.*;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.ccbluex.liquidbounce.render.gui.element.ClickGuiGlassGuiElementRenderState;
import org.joml.Matrix3x2f;
/** Production vertex emitter + real MC interface; no game or fake MC compile types. */
public class VertexRegression {
    static int checks;
    static void check(boolean b) { checks++;if(!b) throw new AssertionError("check " + checks); }
    record V(float x,float y,float lx,float ly,float mode,int color) {}
    static List<V> emit(boolean style,int profile,float opacity,float width,float height,float refract,boolean shadow,boolean rim) {
        List<V> out = new ArrayList<>();float[] v=new float[5];int[] color=new int[1];
        VertexConsumer c = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),new Class[]{VertexConsumer.class},
            (p,m,a)->{
                switch(m.getName()) {
                    case "addVertexWith2DPose" -> {v[0]=(Float)a[1];v[1]=(Float)a[2];}
                    case "setUv" -> {v[2]=(Float)a[0];v[3]=(Float)a[1];}
                    case "setColor" -> {if(a.length==1)color[0]=(Integer)a[0];}
                    case "setLineWidth" -> out.add(new V(v[0],v[1],v[2],v[3],(Float)a[0],color[0]));
                }
                return m.getReturnType().isInstance(p)?p:null;
            });
        new ClickGuiGlassGuiElementRenderState(0,0,width,height,12,8,4,0,
            0x509C9C9C,0x509C9C9C,0x50888888,0x50888888,
            opacity,refract,rim?0xA8FFFFFF:0,.65f,shadow?0x70000000:0,14f,style,profile,
            null,new Matrix3x2f(),null,null).buildVertices(c);
        return out;
    }
    public static void main(String[] args) {
        for(int i=0;i<1000;i++) {
            float opacity=(i%64+1)/64f,refract=(i%31)/10f;
            var old=emit(false,0,opacity,80+i%170,32+i%70,refract,true,true);
            check(old.size()==12);
            for(int profile=0;profile<4;profile++) {
                check(old.equals(emit(false,profile,opacity,80+i%170,32+i%70,refract,true,true)));
                var ref=emit(true,profile,opacity,80+i%170,32+i%70,refract,true,true);
                check(ref.size()==(profile==1||profile==2?16:12));
                for(int n=0;n<12;n++) {
                    float mode=old.get(n).mode(),lane=profile*256f;
                    float expect=n<4?mode-lane:mode+lane;
                    check(Math.abs(ref.get(n).mode()-expect)<.0001);
                    check(ref.get(n).x()==old.get(n).x() && ref.get(n).y()==old.get(n).y());
                    check(ref.get(n).color()==old.get(n).color());
                }
                if(profile==1||profile==2)for(int n=12;n<16;n++) {
                    check(ref.get(n).mode()==profile*256f+192f);
                    check(Math.abs(ref.get(n).lx())>(80+i%170)/2f);
                    check(Math.abs(ref.get(n).ly())>(32+i%70)/2f);
                }
            }
        }
        for(int profile=0;profile<4;profile++) {
            check(emit(true,profile,0,100,32,1,true,true).isEmpty());
            check(emit(true,profile,1,0,32,1,true,true).isEmpty());
            check(emit(true,profile,1,100,0,1,true,true).isEmpty());
            check(emit(true,profile,.004f,100,32,1,true,true).isEmpty());
            // Detached arc must survive shadow=off. Soft/panel must NOT grow an arc.
            check(emit(true,profile,1,160,48,1,false,true).size()==(profile==1||profile==2?12:8));
            check(emit(true,profile,1,160,48,1,false,false).size()==4);
        }
        System.out.println("PASS: "+checks+" production vertex assertions; four explicit material lanes; old Liquid Glass lane/vertices unchanged; exterior arc independent of shadow");
    }
}
