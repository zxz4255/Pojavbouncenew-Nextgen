package net.ccbluex.liquidbounce.features.module.modules.render.animations
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.InteractionHand
class BlockMode { fun transform(p: PoseStack, arm: HumanoidArm, height: Float, attack: Float) {} }
class Choice { val activeMode = BlockMode() }
object ModuleAnimations {
 val running = false
 val blockAnimationChoice = Choice()
 object EquipOffset { val running=false; val ignorePlace=false; val ignoreAmount=false; val ignoreBlocking=false }
 object OneSevenAnimation { fun transform(p: PoseStack, arm: HumanoidArm, height: Float, attack: Float) {} }
 object MainHand { val running=false; val mainHandX=0f; val mainHandY=0f; val mainHandItemScale=0f; val mainHandPositiveX=0f; val mainHandPositiveY=0f; val mainHandPositiveZ=0f }
 object OffHand { val running=false; val offHandX=0f; val offHandY=0f; val offHandItemScale=0f; val offHandPositiveX=0f; val offHandPositiveY=0f; val offHandPositiveZ=0f }
}
object SwingAnimations { val enabled=false; fun onRenderItem(player: Player, hand: InteractionHand, a: Float, pose: PoseStack) {} }
