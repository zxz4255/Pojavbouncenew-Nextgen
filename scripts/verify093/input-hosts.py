from pathlib import Path
import sys
r,w=map(Path,sys.argv[1:])
s=(r/'src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/client/MixinMinecraft.java').read_text()
a=s.index('    private boolean injectMultiActionsAttackingWhileUsingAndEnforcedBlockingState(');b=s.index('{',a);e=b+1;depth=1
while depth:
 depth+=(s[e]=='{')-(s[e]=='}');e+=1
method=s[a:e].replace('net.ccbluex.liquidbounce.utils.client.vfp.ManualSwordBlocking','ManualHost')
host='''public class InputHooks {
 static class Key {int clickCount=3;boolean down=true;boolean isDown(){return down;}}
 static class Options {Key keyUse=new Key(),keyAttack=new Key(),keyPickItem=new Key();}
 static class GameMode {int releases;void releaseUsingItem(Object p){releases++;}}
 static class ManualHost {static boolean active;static boolean isBlocking(Object p){return active;}}
 static class KillAuraAutoBlock {static KillAuraAutoBlock INSTANCE=new KillAuraAutoBlock();boolean running;Object hand;boolean getRunning(){return running;}Object getEnforcedBlockingHand(){return hand;}}
 static class ModuleMultiActions {static boolean attack,breaking;static boolean mayAttackWhileUsing(){return attack;}static boolean mayBreakWhileUsing(){return breaking;}}
 Options options=new Options();GameMode gameMode=new GameMode();Object player=new Object();
'''+method+'''
 static int checks;
 static void test(boolean v){checks++;if(!v)throw new AssertionError(checks);}
 public static void main(String[] args){
  var h=new InputHooks();
  for(int i=0;i<4096;i++){
   h.options.keyUse.down=(i%2==0);int before=h.gameMode.releases;
   test(h.injectMultiActionsAttackingWhileUsingAndEnforcedBlockingState(true));
   test(!h.injectMultiActionsAttackingWhileUsingAndEnforcedBlockingState(false));
   test(h.gameMode.releases==before);test(h.options.keyUse.clickCount==3);
   test(h.options.keyAttack.clickCount==3);test(h.options.keyPickItem.clickCount==3);
  }
  ManualHost.active=true;test(h.injectMultiActionsAttackingWhileUsingAndEnforcedBlockingState(false));ManualHost.active=false;
  ModuleMultiActions.attack=true;
  test(!h.injectMultiActionsAttackingWhileUsingAndEnforcedBlockingState(true));test(h.gameMode.releases==1);
  test(h.options.keyUse.clickCount==0 && h.options.keyPickItem.clickCount==0 && h.options.keyAttack.clickCount==3);
  ModuleMultiActions.attack=false;KillAuraAutoBlock.INSTANCE.running=true;KillAuraAutoBlock.INSTANCE.hand=new Object();
  test(!h.injectMultiActionsAttackingWhileUsingAndEnforcedBlockingState(true));test(h.gameMode.releases==1);
  System.out.println("PASS: "+checks+" exact input-hook assertions; no-feature path retains vanilla branch/click counts/release ownership; active manual fallback and MultiActions/aura takeover modeled. Hosted, not game runtime.");
 }
}
'''
(w/'InputHooks.java').write_text(host)
(w/'InputHooksNegative.java').write_text(host.replace('public class InputHooks {','class InputHooksNegative {').replace('new InputHooks()','new InputHooksNegative()').replace('return isUsingItem;','return false;'))
