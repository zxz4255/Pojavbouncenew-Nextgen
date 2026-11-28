#!/usr/bin/env python3
"""Compile verbatim changed visual/HUD/combat getter bodies; not full existing classes."""
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1])
def read(path):return (root/path).read_text()
def method(source,needle):
 start=source.index(needle);brace=source.index('{',start);level=1;end=brace+1
 while level:
  if source[end]=='{':level+=1
  elif source[end]=='}':level-=1
  end+=1
 return source[start:end]
base='src/main/kotlin/net/ccbluex/liquidbounce/'
common='''
import net.ccbluex.liquidbounce.features.module.modules.combat.neoraven.*
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleTs2KillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.world.entity.LivingEntity
'''
# Stub-only old aura read surfaces, explicitly independent of new port logic.
for f,body in [('ModuleKillAura.kt',' { object targetTracker { var target:net.minecraft.world.entity.LivingEntity?=null } }'),
               ('ModuleTs2KillAura.kt',' { var target:net.minecraft.world.entity.LivingEntity?=null; val shouldAnimateSwordBlock=false }')]:
 p=out/f;p.write_text(p.read_text().strip()+body+'\n')
a=read(base+'ui/hud/components/CombatAndNotificationHudComponents.kt')
b=read(base+'features/module/modules/render/targethud/TargetHudLayer.kt')
(out/'HudIntegrations.kt').write_text('package verify085.hosts\n'+common+'''
object ComponentTargetHost { var lastTarget:LivingEntity?=null;var lastTargetMs=0L
'''+method(a,'private fun resolveTarget(')+'''
}
object LayerTargetHost { var attacked:LivingEntity?=null;var attackedAt=0L;const val HIT_HOLD_MS=1500L
'''+method(b,'fun currentTarget(')+'\n}\n')
s=read(base+'features/module/modules/combat/ModuleSwordBlock.kt')
ext=s[s.index('    private val LivingEntity.shouldApplySwordBlockAnimation'):s.index('    /**\n     * Determines if')]
(out/'SwordIntegration.kt').write_text('package verify085.hosts\n'+common+'''
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.item.isSword
import net.ccbluex.liquidbounce.utils.entity.isInHand
import net.ccbluex.liquidbounce.utils.entity.usingItemOrNull
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ShieldItem
object SwordIntegrationHost {
 val running=false;val fakeOnPressing=false;val alwaysHideShield=false
 val isBlocksAttacksExisting=false
 val player get()=mc.player!!
 object KillAuraAutoBlock {val blockVisual=false}
'''+ext+method(s,'fun shouldAnimateSwordBlock(')+'\n'+method(s,'fun shouldHideOffhand(')+'\n}\n')
p=out/'Entity.kt';p.write_text(p.read_text()+'''
val net.minecraft.world.entity.LivingEntity.usingItemOrNull get()=if(isUsingItem) useItem else null
fun net.minecraft.world.entity.LivingEntity.isInHand(stack:net.minecraft.world.item.ItemStack?,hand:net.minecraft.world.InteractionHand)=getItemInHand(hand)===stack
''')
source=read(base+'utils/combat/CombatManager.kt')
getter=source[source.index('    val isInCombat: Boolean'):source.index('    @AddonApi\n    fun pauseCombatForAtLeast')]
p=out/'CombatManager.kt';p.write_text('package net.ccbluex.liquidbounce.utils.combat\n'+common+'''
object CombatManager { var shouldPauseCombat=false;var duringCombat=0
'''+getter+'\n}\n')
print('PASS: extracted exact changed SwordBlock gates, two HUD resolvers, CombatManager getter for real-API compilation')
