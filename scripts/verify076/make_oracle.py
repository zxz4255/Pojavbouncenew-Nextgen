#!/usr/bin/env python3
"""Original pinned InvManager2 sort/match/score methods, toy 1.8 items. Not a game runtime."""
from pathlib import Path
import sys
s=(Path(__file__).parent/'upstream/InvManager2Module.java').read_text()
body=s[s.index('    private Click sortClick('):s.rindex('}')].replace('net.minecraft.init.Blocks.stone','Blocks.stone')
header='''import java.util.*;
public class NeoUpstreamInventoryOracle {
 static final int FIRST_SLOT=9,LAST_SLOT=45;
 static class SliderSetting { double v; double getInput(){return v;} }
 static SliderSetting swordSlot=new SliderSetting(),projectilesSlot=new SliderSetting(),blockSlot=new SliderSetting(),
 bowSlot=new SliderSetting(),pickaxeSlot=new SliderSetting(),axeSlot=new SliderSetting(),shovelSlot=new SliderSetting(),
 foodSlot=new SliderSetting(),potionSlot=new SliderSetting(),fireballSlot=new SliderSetting(),enderPearlSlot=new SliderSetting(),shearsSlot=new SliderSetting();
 static class Block {boolean placeable;Block(boolean p){placeable=p;}}
 static class Blocks {static Block stone=new Block(true);}
 static class Item {double getStrVsBlock(ItemStack s,Block b){return s.value;}}
 static class ItemArmor extends Item {}
 static class ItemSword extends Item {} static class ItemBow extends Item {}
 static class ItemBlock extends Item {Block b;ItemBlock(boolean p){b=new Block(p);}Block getBlock(){return b;}}
 static class ItemPickaxe extends Item {} static class ItemAxe extends Item {} static class ItemSpade extends Item {}
 static class ItemFood extends Item {} static class ItemSnowball extends Item {} static class ItemEgg extends Item {}
 static class ItemFishingRod extends Item {} static class ItemFireball extends Item {} static class ItemShears extends Item {}
 static class ItemPotion extends Item {List<?> getEffects(ItemStack s){return s.splash?List.of():null;}static boolean isSplash(int v){return v==1;}}
 static class Items {static Item golden_apple=new ItemFood(),ender_pearl=new Item();}
 static class ItemUtils {static double attackDamage(ItemStack s){return s.value;}static double bowEnchantLevels(ItemStack s){return s.value;}}
 static class BlockUtils {static boolean isPlaceable(Block b){return b.placeable;}}
 public static class ItemStack {
  Item item;double value;int stackSize;boolean splash;
  public ItemStack(int kind,double score,int count,boolean golden,boolean sp,boolean placeable){
   value=score;stackSize=count;splash=sp;
   item=switch(kind){case 0->new ItemArmor();case 1->new ItemSword();case 2->new ItemSnowball();case 3->new ItemFishingRod();
    case 4->new ItemBlock(placeable);case 5->new ItemBow();case 6->new ItemPickaxe();case 7->new ItemAxe();case 8->new ItemSpade();
    case 9->golden?Items.golden_apple:new ItemFood();case 10->new ItemPotion();case 11->new ItemFireball();case 12->Items.ender_pearl;
    case 13->new ItemShears();default->new Item();};
  }
  Item getItem(){return item;}int getMetadata(){return splash?1:0;}
 }
 static class Slot {ItemStack s;ItemStack getStack(){return s;}}
 static class Container {ItemStack[] stacks;Container(ItemStack[] a){stacks=a;}Slot getSlot(int id){Slot s=new Slot();s.s=stacks[id];return s;}}
 static ItemStack stackAt(Container c,int id){return c.stacks[id];}
 static double efficiency(ItemStack s){return s.value;}
 static class Click {int slot,button,mode;Click(int s,int b,int m){slot=s;button=b;mode=m;}}
 public static double evaluate(ItemStack s,int kind){return matchesKind(s,kind)?score(s,kind):Double.NaN;}
 public static int[] next(ItemStack[] items,int[] targets){
  SliderSetting[] ss={swordSlot,projectilesSlot,blockSlot,bowSlot,pickaxeSlot,axeSlot,shovelSlot,foodSlot,potionSlot,fireballSlot,enderPearlSlot,shearsSlot};
  for(int i=0;i<ss.length;i++)ss[i].v=targets[i];
  Click c=new NeoUpstreamInventoryOracle().sortClick(new Container(items));return c==null?null:new int[]{c.slot,c.button,c.mode};
 }
'''
Path(sys.argv[1]).write_text(header+body+'\n}\n')
print('Generated original pinned InvManager2 sortClick/moveBest/matchesKind/score/isSplashPotion oracle; toy item adapters.')
