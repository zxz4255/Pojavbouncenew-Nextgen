#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar"
# Real MC/VFP classes; only the surrounding project facades are compile hosts.
cat > "$W/LiquidBounce.java" <<'JAVA'
package net.ccbluex.liquidbounce; public enum LiquidBounce {INSTANCE; public boolean isInitialized(){return true;}}
JAVA
cat > "$W/ModuleSwordBlock.java" <<'JAVA'
package net.ccbluex.liquidbounce.features.module.modules.combat; public class ModuleSwordBlock {public static boolean manualLegacyUseAllowed(){return true;}}
JAVA
cat > "$W/ProtocolUtilKt.java" <<'JAVA'
package net.ccbluex.liquidbounce.utils.client; public class ProtocolUtilKt {public static boolean isOlderThanOrEqual1_8(){return true;}}
JAVA
cat > "$W/VfpCompatibility.java" <<'JAVA'
package net.ccbluex.liquidbounce.utils.client.vfp; public enum VfpCompatibility {INSTANCE; public boolean isOlderThanOrEqual1_8(){return true;}}
JAVA
J="$R/src/main/java/net/ccbluex/liquidbounce"
"$JAVA_HOME/bin/javac" -proc:none -cp "$CP" -d "$W/classes" "$W/"*.java \
 "$J/utils/client/vfp/"{ManualSwordBlocking,LegacySwordUseState,VfpCompatibility1_8}.java \
 "$R/scripts/verify093/ManualStateRegression.java"
echo 'PASS: complete production manual adapter/state and VFP bridge compile against real MC26.3/VFP; module/protocol-selector/bootstrap facades are compile hosts.'
"$JAVA_HOME/bin/java" -cp "$W/classes" ManualStateRegression
# Verify changed useItem injections as exact Java method excerpts with real MC/event argument types.
python3 - "$R" "$W" <<'PY'
from pathlib import Path
import sys
r,w=map(Path,sys.argv[1:]);src=(r/'src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/network/MixinMultiPlayerGameMode.java').read_text()
def method(name):
 a=src.index('    private void '+name);b=src.index('{',a);depth=1;e=b+1
 while depth:
  depth+=(src[e]=='{')-(src[e]=='}');e+=1
 return src[a:e]
s='''import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
class UseHooks {
 boolean liquid_bounce$manualUseCancelled;
 static class EventManager {static final EventManager INSTANCE=new EventManager(); <T> T callEvent(T e){return e;}}
 static class PlayerInteractItemEvent {PlayerInteractItemEvent(Player p,InteractionHand h){} boolean isCancelled(){return false;}}
 static class PlayerInteractedItemEvent {PlayerInteractedItemEvent(Player p,InteractionHand h,InteractionResult r){}}
'''+method('hookItemInteractAtHead')+'\n'+method('hookItemInteractAtReturn')+'\n}'
(w/'UseHooks.java').write_text(s)
PY
"$JAVA_HOME/bin/javac" -proc:none -cp "$W/classes:$CP" -d "$W/classes" "$W/UseHooks.java"
echo 'PASS: exact HEAD/RETURN item-use hook bodies compile; cancellation checked before held/recovery, post-use observers receive adjusted result. Not Mixin application.'
python3 "$R/scripts/verify093/input-hosts.py" "$R" "$W"
"$JAVA_HOME/bin/javac" -proc:none -d "$W/input" "$W/InputHooks.java" "$W/InputHooksNegative.java"
"$JAVA_HOME/bin/java" -cp "$W/input" InputHooks
if "$JAVA_HOME/bin/java" -cp "$W/input" InputHooksNegative > "$W/negative.txt" 2>&1; then
 echo 'FAIL: legacy forced-false input branch survived negative control';exit 1
fi
echo 'PASS: restoring the old forced-false input result is rejected by negative control.'
