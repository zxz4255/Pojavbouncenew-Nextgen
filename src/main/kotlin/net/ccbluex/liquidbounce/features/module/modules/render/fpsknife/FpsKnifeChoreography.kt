/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.features.module.modules.render.fpsknife

import kotlin.math.PI
import kotlin.math.sin

/**
 * Original, normalized choreography inspired by public CS2 showcases; NOT extracted Valve tracks.
 * Durations/proportions are authored calibration, not measurements of the source game.
 * One fixed view is intentional: user offsets/speed/scale cannot invalidate its safety envelope.
 */
object FpsKnifeChoreography {
    enum class Handling { RING, TALON, BALISONG, HEAVY, SKELETON, FOLDER, HUNTER, BOWIE, KUKRI, TANTO, SWITCHBLADE, KUNAI }
    data class Profile(
        val handling: Handling, val drawSeconds: Float, val inspectSeconds: Float,
        val lightSeconds: Float, val heavySeconds: Float, val scale: Float,
        val wrist: Float, val mass: Float, val cs2Counterpart: Boolean = true,
    )

    // Explicit exhaustiveness: adding a knife requires selecting its handling, timing and proportions.
    fun profile(model: KnifeModel): Profile = when (model) {
        KnifeModel.NEXUS_SKELETON -> Profile(Handling.SKELETON, .88f, 4.4f, .46f, .78f, .88f, -8f, .92f, false)
        KnifeModel.KARAMBIT -> Profile(Handling.RING, .76f, 4.0f, .43f, .72f, 1.0f, -12f, .82f)
        KnifeModel.TALON -> Profile(Handling.TALON, .92f, 5.1f, .47f, .76f, .94f, -9f, .94f)
        KnifeModel.BUTTERFLY -> Profile(Handling.BALISONG, 1.12f, 4.7f, .45f, .74f, 1f, -5f, .83f)
        KnifeModel.M9_BAYONET -> Profile(Handling.HEAVY, .95f, 4.6f, .51f, .84f, .90f, 5f, 1.18f)
        KnifeModel.SKELETON_DAGGER -> Profile(Handling.SKELETON, .84f, 4.5f, .44f, .74f, .95f, -4f, .90f)
        KnifeModel.FLIP_KNIFE -> Profile(Handling.FOLDER, .82f, 4.0f, .44f, .74f, .94f, 3f, .88f)
        KnifeModel.HUNTSMAN -> Profile(Handling.HUNTER, .86f, 4.4f, .49f, .82f, .89f, 7f, 1.10f)
        KnifeModel.BOWIE -> Profile(Handling.BOWIE, 1.02f, 4.8f, .54f, .88f, .84f, 10f, 1.25f)
        KnifeModel.KUKRI -> Profile(Handling.KUKRI, 1.08f, 4.7f, .52f, .86f, .90f, 8f, 1.16f)
        KnifeModel.KATANA_TANTO -> Profile(Handling.TANTO, .96f, 4.5f, .50f, .82f, .80f, 4f, 1.12f, false)
        KnifeModel.STILETTO -> Profile(Handling.SWITCHBLADE, .78f, 3.9f, .42f, .72f, .96f, -2f, .78f)
        KnifeModel.KUNAI -> Profile(Handling.KUNAI, .80f, 3.8f, .43f, .72f, .95f, -7f, .85f, false)
    }

    const val HAND_THICKNESS = .56f
    const val BLADE_TILT = 18f

    private fun s(a: Float, b: Float, p: Float): Float {
        val t = ((p - a) / (b - a)).coerceIn(0f, 1f)
        // Quintic interpolation: zero velocity AND acceleration at attachment boundaries.
        return t * t * t * (t * (6f * t - 15f) + 10f)
    }
    private fun window(p: Float, a: Float, b: Float, c: Float, d: Float) = s(a, b, p) - s(c, d, p)
    private fun pulse(p: Float, a: Float, peak: Float, b: Float) = s(a, peak, p) * (1f - s(peak, b, p))

    fun idle(out: FpsKnifePose, model: KnifeModel) {
        val profile = profile(model)
        out.knifeViewRollZ += profile.wrist
        // Heavy blades sit slightly further out, with a common fixed inward-facing hand stance.
        out.mainArmX += (profile.mass - 1f) * .025f
        out.mainArmY -= (profile.mass - 1f) * .018f
    }

    fun draw(out: FpsKnifePose, model: KnifeModel, phase: Float) {
        val p = phase.coerceIn(0f, 1f)
        val h = profile(model)
        val entry = 1f - s(0f, .32f, p)
        val wrist = pulse(p, .10f, .42f, .95f)
        out.mainArmY -= .32f * entry
        out.mainArmX += .10f * entry
        out.mainArmZ += .12f * entry
        out.offArmY -= .12f * entry
        out.mainArmRollZ -= 9f * wrist
        when (h.handling) {
            Handling.RING, Handling.TALON, Handling.KUNAI -> ring(out, p, if (h.handling == Handling.TALON) -360f else 360f, .04f, .23f, .28f, .62f, .69f, .96f)
            Handling.SKELETON -> {
                out.knifeRollX += 70f * wrist
                out.knifeViewRollZ -= 27f * wrist
            }
            Handling.BALISONG -> balisong(out, p, true, false)
            Handling.FOLDER, Handling.SWITCHBLADE -> {
                // Opening occurs ONLY in the palm-front plane. Both are pivoting side folders,
                // not the erroneous telescoping/OTF mechanism sometimes used for a stiletto.
                out.palmFrontSpinBlend = window(p, 0f, .20f, .72f, .99f)
                out.bladeFoldDeg = 174f * (1f - s(.24f, if (h.handling == Handling.FOLDER) .65f else .40f, p))
                // Before the blade has cleared its handle it is sheathed, not a scaled blade.
                out.knifeViewRollZ += 14f * wrist
            }
            Handling.HEAVY -> { out.knifeViewRollZ += 36f * wrist; out.mainArmPitchX -= 12f * wrist }
            Handling.HUNTER -> { out.knifeRollX += 105f * wrist; out.mainArmX -= .045f * wrist }
            Handling.BOWIE -> { out.knifeViewRollZ += 48f * wrist; out.offArmX -= .04f * wrist }
            Handling.KUKRI -> toss(out, p, 360f, .13f)
            Handling.TANTO -> { out.mainArmZ -= .09f * wrist; out.knifeRollX += 24f * wrist }
        }
    }

    fun inspect(out: FpsKnifePose, model: KnifeModel, phase: Float, variant: Int) {
        val p = phase.coerceIn(0f, 1f)
        val h = profile(model)
        val stage = window(p, .01f, .14f, .86f, .99f)
        out.mainArmX -= .045f * stage
        out.mainArmY += .065f * stage
        out.offArmX -= .045f * stage
        out.offArmY -= .065f * stage
        out.offArmZ += .035f * stage
        val back = window(p, .22f, .36f, .62f, .80f)
        when (h.handling) {
            Handling.RING -> ring(out, p, if (variant == 0) 360f else -360f)
            Handling.TALON -> ring(out, p, if (variant == 0) 720f else -1080f)
            Handling.SKELETON -> if (variant == 0) {
                // Ordinary skeleton inspection is a restrained wrist examination, not a toss.
                out.knifeRollX += 110f * back
                out.knifeViewRollZ -= 18f * stage
            } else ring(out, p, -720f)
            Handling.BALISONG -> balisong(out, p, false, variant != 0)
            Handling.KUNAI -> if (variant == 0) ring(out, p, 360f) else { out.knifeRollX += 135f * back }
            Handling.KUKRI -> if (variant == 1) toss(out, p, -360f, .20f) else {
                out.knifeRollX += 150f * back; out.knifeViewRollZ -= 17f * stage
            }
            Handling.SWITCHBLADE -> if (variant == 1) toss(out, p, 360f, .14f) else {
                out.knifeRollX += 155f * back; out.knifeViewRollZ -= 8f * stage
            }
            Handling.HEAVY -> { out.knifeRollX += 145f * back; out.mainArmY += .024f * stage }
            Handling.HUNTER -> { out.knifeRollX += 128f * back; out.knifeViewRollZ -= 23f * stage }
            Handling.BOWIE -> { out.knifeRollX += 155f * back; out.mainArmX -= .030f * stage }
            Handling.FOLDER -> { out.knifeRollX += 140f * back; out.knifeViewRollZ += 11f * stage }
            Handling.TANTO -> { out.knifeRollX += 160f * back; out.mainArmZ -= .045f * stage }
        }
    }

    private fun ring(out: FpsKnifePose, p: Float, degrees: Float, a: Float = .04f, b: Float = .23f,
                     c: Float = .28f, d: Float = .70f, e: Float = .77f, f: Float = .96f) {
        out.palmFrontSpinBlend = window(p, a, b, e, f).coerceIn(0f, 1f)
        // Keep the final complete turn until detachment ends. No resetting on an arbitrary frame.
        out.ringSpinDeg = degrees * s(c, d, p)
    }

    private fun balisong(out: FpsKnifePose, p: Float, deploy: Boolean, alternate: Boolean) {
        out.heldBalisongBlend = window(p, .01f, .20f, .80f, .99f).coerceIn(0f, 1f)
        // The safe handle is the attachment; moving bite handle only moves after regrip is complete.
        val first = pulse(p, .24f, .36f, .48f)
        val second = pulse(p, .49f, .61f, .76f)
        out.biteHandleDeg = (if (deploy) 155f else 142f) * first + (if (alternate) 166f else 128f) * second
        out.safeHandleDeg = 0f
        out.knifeViewRollZ += 10f * window(p, .23f, .35f, .64f, .77f)
    }

    private fun toss(out: FpsKnifePose, p: Float, degrees: Float, height: Float) {
        out.knifeRelease = window(p, .03f, .20f, .80f, .97f).coerceIn(0f, 1f)
        out.knifeFreePoseBlend = window(p, .22f, .29f, .71f, .78f).coerceIn(0f, 1f)
        val flight = s(.30f, .70f, p)
        out.knifePalmOffsetZ = -.50f * out.knifeRelease
        out.knifePalmOffsetY = .17f * out.knifeRelease + height * sin(PI.toFloat() * flight)
        out.knifePalmOffsetX = -.035f * out.knifeRelease
        out.ringSpinDeg = degrees * flight
    }

    fun attack(out: FpsKnifePose, model: KnifeModel, phase: Float, heavy: Boolean, combo: Int) {
        val p = phase.coerceIn(0f, 1f)
        val h = profile(model)
        val wind = pulse(p, 0f, .16f, .30f)
        val cut = window(p, .19f, .31f, .43f, 1f)
        val mass = h.mass
        if (heavy) {
            out.mainArmX += .045f * wind - .14f * cut
            out.mainArmY -= .035f * wind - .065f * cut
            out.mainArmZ += .075f * wind - .31f * cut
            out.mainArmPitchX += -9f * wind + 19f * cut
            out.knifeYawY -= (if (h.handling == Handling.RING || h.handling == Handling.TALON) 23f else 12f) * cut
        } else {
            val direction = if (combo % 2 == 0) 1f else -1f
            out.mainArmX += direction * (.065f * wind - .24f * cut) / mass
            out.mainArmY += .045f * wind - .065f * cut
            out.mainArmZ += .045f * wind - .19f * cut
            out.mainArmYawY += direction * (-9f * wind + 18f * cut)
            out.knifeViewRollZ += direction * (14f * wind - 40f * cut)
            if (h.handling == Handling.BOWIE || h.handling == Handling.KUKRI) out.mainArmPitchX += 12f * cut
        }
        out.offArmX -= .05f * cut
        out.offArmY -= .05f * cut
        out.offArmZ += .025f * cut
    }
}
