#!/usr/bin/env python3
"""Compile the actual pinned upstream filtering methods unchanged except obsolete FQCN aliases.
Toy 1.8 item/inventory adapters supply explicit scores. This verifies filter logic, NOT modern
component extraction or a real 1.8 runtime. Original upstream files are never overwritten.
"""
from pathlib import Path
import sys
here=Path(__file__).parent
source=(here/'upstream/ChestStealerModule.java').read_text()
body=source[source.index('    public static List<Integer> slotsToSteal('):source.rindex('}')]
body=body.replace('net.minecraft.entity.player.InventoryPlayer','InventoryPlayer')
body=body.replace('net.minecraft.init.Blocks.stone','Blocks.stone')
header='''import java.util.*;
public class NeoUpstreamLootOracle {
 static class ButtonSetting { boolean value; boolean isToggled(){return value;} }
 static ButtonSetting instant=new ButtonSetting(), ignoreTrash=new ButtonSetting(), armor=new ButtonSetting(),
 blocks=new ButtonSetting(), bow=new ButtonSetting(), food=new ButtonSetting(), potions=new ButtonSetting(),
 projectiles=new ButtonSetting(), sword=new ButtonSetting(), tools=new ButtonSetting();
 static class Block { boolean placeable; Block(boolean p){placeable=p;} }
 static class Blocks { static Block stone=new Block(true); }
 static class Item { double getStrVsBlock(ItemStack s,Block b){return s.value;} }
 static class ItemArmor extends Item { int armorType; ItemArmor(int t){armorType=t;} }
 static class ItemBlock extends Item { Block b; ItemBlock(boolean p){b=new Block(p);} Block getBlock(){return b;} }
 static class ItemBow extends Item {} static class ItemFood extends Item {}
 static class ItemPotion extends Item {} static class ItemEnderPearl extends Item {}
 static class ItemEgg extends Item {} static class ItemSnowball extends Item {}
 static class ItemFishingRod extends Item {} static class ItemSword extends Item {}
 static class ItemPickaxe extends Item {} static class ItemAxe extends Item {} static class ItemSpade extends Item {}
 static class Items { static final Item arrow=new Item(), golden_apple=new ItemFood(), fishing_rod=new ItemFishingRod(); }
 static class ItemUtils {
  static double armorValue(ItemStack s){return s.value;}
  static double attackDamage(ItemStack s){return s.value;}
  static double bowEnchantLevels(ItemStack s){return s.value;}
  static boolean hasDurability(ItemStack s,double f){return true;}
 }
 static class BlockUtils {static boolean isPlaceable(Block b){return b.placeable;} }
 interface IInventory { int getSizeInventory(); ItemStack getStackInSlot(int i); }
 static class Inv implements IInventory {
  ItemStack[] stacks; Inv(ItemStack[] s){stacks=s;}
  public int getSizeInventory(){return stacks.length;}
  public ItemStack getStackInSlot(int i){return stacks[i];}
 }
 static class InventoryPlayer extends Inv {
  ItemStack[] armorInventory=new ItemStack[0]; InventoryPlayer(ItemStack[] s){super(s);}
 }
 public static class ItemStack {
  final Item item; final double value;
  public ItemStack(int kind,double score,int armorType,boolean apple,boolean placeable){
   value=score;
   item=switch(kind){
    case 0 -> new ItemArmor(armorType); case 1 -> new ItemBlock(placeable); case 2 -> new ItemBow();
    case 3 -> Items.arrow; case 4 -> apple?Items.golden_apple:new ItemFood(); case 5 -> new ItemPotion();
    case 6 -> new ItemEnderPearl(); case 7 -> new ItemEgg(); case 8 -> new ItemSnowball();
    case 9 -> Items.fishing_rod; case 10 -> new ItemSword(); case 11 -> new ItemPickaxe();
    case 12 -> new ItemAxe(); case 13 -> new ItemSpade(); default -> new Item();
   };
  }
  Item getItem(){return item;}
 }
 public static List<Integer> evaluate(ItemStack[] chest,ItemStack[] inventory,int flags){
  ButtonSetting[] settings={instant,ignoreTrash,armor,blocks,bow,food,potions,projectiles,sword,tools};
  for(int i=0;i<settings.length;i++)settings[i].value=(flags&(1<<i))!=0;
  return slotsToSteal(new Inv(chest),new InventoryPlayer(inventory));
 }
'''
Path(sys.argv[1]).write_text(header+body+'\n}\n')
print('Generated upstream oracle: original slotsToSteal + wantedCategory + isTrash + best-* bodies; toy item/score adapters.')
