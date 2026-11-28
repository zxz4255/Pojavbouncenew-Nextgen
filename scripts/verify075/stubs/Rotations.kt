package net.ccbluex.liquidbounce.utils.aiming
import net.ccbluex.liquidbounce.event.Listenable
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.kotlin.Priority
class RotationTarget(val rotation: Rotation, val ticksUntilReset: Int = 1, val considerInventory: Boolean = false,
 val movementCorrection: MovementCorrection = MovementCorrection.SILENT)
object RotationManager {
 val activeRotationTarget: RotationTarget? = null
 val currentRotation: Rotation? = null
 fun setRotationTarget(plan: RotationTarget, priority: Priority, provider: Listenable) {}
}
