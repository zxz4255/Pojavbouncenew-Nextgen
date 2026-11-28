import java.util.*;
import java.lang.reflect.*;
import net.ccbluex.liquidbounce.utils.inventory.ContainerItemSlot;
/** Executes ORIGINAL nested selection-mode process methods. Config and slots are compile hosts;
 * slot distance formula mirrors actual ContainerItemSlot.distance. Not an item/scheduler/game test. */
public class SelectionRegression {
    static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("selection #"+checks);}
    static Object instance(String name)throws Exception{
        Class<?> c=Class.forName("net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.ModuleChestStealer$"+name);
        Field f=c.getDeclaredField("INSTANCE");f.setAccessible(true);return f.get(null);
    }
    static ArrayList<ContainerItemSlot> apply(Object mode,List<ContainerItemSlot> input)throws Exception{
        var a=new ArrayList<>(input);Method m=mode.getClass().getDeclaredMethod("process",ArrayList.class);m.setAccessible(true);m.invoke(mode,a);return a;
    }
    static List<Integer> ids(List<ContainerItemSlot> a){return a.stream().map(ContainerItemSlot::getSlotInContainer).sorted().toList();}
    public static void main(String[] args)throws Exception{
        Object index=instance("Index"),distance=instance("Distance"),random=instance("Random");Random rng=new Random(98098);
        for(int trial=0;trial<6000;trial++){
            var input=new ArrayList<ContainerItemSlot>();
            for(int i=0;i<54;i++)if(rng.nextBoolean())input.add(new ContainerItemSlot(i));
            if(trial%55==0)input.clear();
            Collections.shuffle(input,rng);
            for(Object mode:new Object[]{index,distance,random}){
                var result=apply(mode,input);check(ids(result).equals(ids(input)));
                if(mode==index)for(int i=1;i<result.size();i++)check(result.get(i-1).getSlotInContainer()<result.get(i).getSlotInContainer());
                if(mode==distance&&!result.isEmpty()){
                    check(result.getFirst()==input.getFirst());
                    for(int i=0;i+1<result.size();i++){
                        int chosen=result.get(i).distance(result.get(i+1));
                        for(int j=i+2;j<result.size();j++)check(chosen<=result.get(i).distance(result.get(j)));
                    }
                }
            }
        }
        System.out.println("PASS: "+checks+" assertions executing original Index/Distance(default settings)/Random process methods on 6000 deterministic generated slot lists. Config/slot services hosted; no inventory clicks/game runtime claim.");
    }
}
