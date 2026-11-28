import net.ccbluex.liquidbounce.utils.client.vfp.LegacySwordUseState;
public final class ManualStateRegression {
 static int checks, uses, releases;
 static void test(boolean value) { checks++; if(!value)throw new AssertionError(checks); }
 static boolean use() {uses++;return true;} static boolean release() {releases++;return true;}
 public static void main(String[] args) {
  var s=new LegacySwordUseState();Object c=new Object(),w=new Object(),p=new Object(),item=new Object();
  for(int i=0;i<4096;i++) {
   test(!s.matches(c,w,p,item,0));
   test(s.begin(c,w,p,item,0,ManualStateRegression::use));
   for(int frame=0;frame<8;frame++) {
    test(s.begin(c,w,p,item,0,()->{throw new AssertionError("duplicate C08");}));
    s.update(c,w,p,item,0,true,()->{throw new AssertionError("held release");});
    test(s.matches(c,w,p,item,0));
   }
   s.update(c,w,p,item,0,false,ManualStateRegression::release);
   test(!s.matches(c,w,p,item,0));
   s.update(c,w,p,item,0,false,()->{throw new AssertionError("duplicate C07");});
  }
  test(uses==4096 && releases==4096);
  test(!s.begin(c,w,p,item,0,()->false));test(!s.matches(c,w,p,item,0));
  // Failed conversion is guarded by adapter; failed transport may never enable the visual.
  test(!s.begin(null,w,p,item,0,()->{throw new AssertionError();}));
  for(int boundary=0;boundary<6;boundary++) {
   test(s.begin(c,w,p,item,0,ManualStateRegression::use));
   Object nc=boundary==0?new Object():c,nw=boundary==1?new Object():w;
   Object np=boundary==2?new Object():p,ni=boundary==3?new Object():item;
   int ns=boundary==4?1:0;boolean eligible=boundary!=5;
   int before=releases;
   s.update(nc,nw,np,ni,ns,eligible,ManualStateRegression::release);
   test(!s.matches(c,w,p,item,0));
   test(releases==before+(boundary>=3?1:0));
  }
  test(s.begin(c,w,p,item,0,ManualStateRegression::use));
  try{s.update(c,w,p,item,0,false,()->{throw new IllegalStateException("injected transport fault");});throw new AssertionError();}
  catch(IllegalStateException expected){}
  test(!s.matches(c,w,p,item,0));test(s.begin(c,w,p,item,0,ManualStateRegression::use));
  test(!s.begin(c,w,p,new Object(),1,()->{throw new AssertionError("unreleased replacement");}));
  System.out.println("PASS: "+checks+" manual-use state assertions; 4096 repeated presses, held idempotence, failed sends, release/slot/stack/world/player/connection boundaries, release fault cleanup. No game/server runtime claim.");
 }
}
