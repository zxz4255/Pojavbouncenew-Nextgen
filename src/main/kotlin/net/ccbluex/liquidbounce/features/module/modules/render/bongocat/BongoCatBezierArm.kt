/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Smooth Bezier mouse-paw solver & renderer ported from:
 * - kuroni/bongocat-osu `src/osu.cpp` & `src/input.cpp` (Kuvster & Dang Doan Duc Trung, MIT License)
 * - MMmmmoko/Bongo-Cat-Mver `src/catfunc.cpp` (Han Zhang, MIT License)
 */
@file:Suppress("MagicNumber", "LongMethod")

package net.ccbluex.liquidbounce.features.module.modules.render.bongocat

import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.getBounds
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.joml.Matrix3x2f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Exact zero-allocation port of Kuvster's 19-point degree-18 Bernstein Bezier mouse arm
 * in `612x354` BongoCat canvas coordinates.
 */
object BongoCatBezierArm {

    private const val OOF = 6
    private const val ITER = 25
    private const val PTS = ITER + 1 // 26 boundary points
    private const val PAW_START_X = 211.0
    private const val PAW_START_Y = 159.0
    private const val PAW_END_X = 258.0
    private const val PAW_END_Y = 228.0
    private const val DX = -38.0
    private const val DY = -50.0
    private const val CAP_SEGMENTS = 10

    // Precomputed C(18, k) for k = 0..18
    private val BINOM_18 = DoubleArray(19).also { b ->
        b[0] = 1.0
        for (k in 1..18) {
            b[k] = b[k - 1] * (19 - k).toDouble() / k.toDouble()
        }
    }

    private val CAP_COS = FloatArray(CAP_SEGMENTS + 1) { i ->
        cos((i.toDouble() / CAP_SEGMENTS) * PI * 2.0).toFloat()
    }
    private val CAP_SIN = FloatArray(CAP_SEGMENTS + 1) { i ->
        sin((i.toDouble() / CAP_SEGMENTS) * PI * 2.0).toFloat()
    }

    // Scratch buffers (rendered on main client render thread)
    private val pss = DoubleArray(38)
    private val px = FloatArray(PTS)
    private val py = FloatArray(PTS)
    private val nx = FloatArray(PTS)
    private val ny = FloatArray(PTS)
    private val powT = DoubleArray(19)
    private val powOneMinusT = DoubleArray(19)

    var mouseBaseX: Float = 95f
        private set
    var mouseBaseY: Float = 185f
        private set

    private var minBoundX = 0f
    private var minBoundY = 0f
    private var maxBoundX = 612f
    private var maxBoundY = 354f

    /**
     * Evaluates the Bezier arm geometry for normalized cursor `(fx, fy)` in `[0, 1]`.
     * Updates [mouseBaseX] and [mouseBaseY] for placing the mouse sprite underneath the paw.
     */
    fun solve(fxRaw: Float, fyRaw: Float, handOffsetX: Float = 0f, handOffsetY: Float = 0f) {
        val fx = fxRaw.coerceIn(0f, 1f).toDouble()
        val fy = fyRaw.coerceIn(0f, 1f).toDouble()

        val x = -97.0 * fx + 44.0 * fy + 184.0
        val y = -76.0 * fx - 40.0 * fy + 324.0

        var idx = 0
        pss[idx++] = PAW_START_X
        pss[idx++] = PAW_START_Y

        val distL = hypot(PAW_START_X - x, PAW_START_Y - y)
        val cl0 = PAW_START_X - 0.7237 * distL * 0.5
        val cl1 = PAW_START_Y + 0.69 * distL * 0.5

        for (i in 1 until OOF) {
            val t = i.toDouble() / OOF
            val u = 1.0 - t
            pss[idx++] = u * u * PAW_START_X + 2.0 * u * t * cl0 + t * t * x
            pss[idx++] = u * u * PAW_START_Y + 2.0 * u * t * cl1 + t * t * y
        }
        pss[idx++] = x
        pss[idx++] = y

        var a = y - cl1
        var b = cl0 - x
        val leAB = hypot(a, b).coerceAtLeast(1e-6)
        a = x + a / leAB * 60.0
        b = y + b / leAB * 60.0

        val distR = hypot(PAW_END_X - a, PAW_END_Y - b)
        val cr0 = PAW_END_X - 0.6 * distR * 0.5
        val cr1 = PAW_END_Y + 0.8 * distR * 0.5

        val push = 20.0
        var s = x - cl0
        var t1 = y - cl1
        val le1 = hypot(s, t1).coerceAtLeast(1e-6)
        s *= push / le1
        t1 *= push / le1

        var s2 = a - cr0
        var t2 = b - cr1
        val le2 = hypot(s2, t2).coerceAtLeast(1e-6)
        s2 *= push / le2
        t2 *= push / le2

        val c1x = x + s
        val c1y = y + t1
        val c2x = a + s2
        val c2y = b + t2
        for (i in 1 until OOF) {
            val t = i.toDouble() / OOF
            val u = 1.0 - t
            val u2 = u * u
            val tSq = t * t
            pss[idx++] = u2 * u * x + 3.0 * u2 * t * c1x + 3.0 * u * tSq * c2x + tSq * t * a
            pss[idx++] = u2 * u * y + 3.0 * u2 * t * c1y + 3.0 * u * tSq * c2y + tSq * t * b
        }
        pss[idx++] = a
        pss[idx++] = b

        for (i in (OOF - 1) downTo 1) {
            val t = i.toDouble() / OOF
            val u = 1.0 - t
            pss[idx++] = u * u * PAW_END_X + 2.0 * u * t * cr0 + t * t * a
            pss[idx++] = u * u * PAW_END_Y + 2.0 * u * t * cr1 + t * t * b
        }
        pss[idx++] = PAW_END_X
        pss[idx] = PAW_END_Y

        mouseBaseX = (((a + x) * 0.5 - 52.0 - 15.0 + DX) + handOffsetX).toFloat()
        mouseBaseY = (((b + y) * 0.5 - 34.0 + 5.0 + DY) + handOffsetY).toFloat()

        // Degree-18 smooth Bezier resampling (`iter = 25` -> 26 boundary vertices)
        val offX = DX + handOffsetX
        val offY = DY + handOffsetY
        px[0] = (pss[0] + offX).toFloat()
        py[0] = (pss[1] + offY).toFloat()

        for (i in 1 until ITER) {
            val r = i.toDouble() / ITER
            val omr = 1.0 - r
            powT[0] = 1.0
            powOneMinusT[0] = 1.0
            for (p in 1..18) {
                powT[p] = powT[p - 1] * r
                powOneMinusT[p] = powOneMinusT[p - 1] * omr
            }
            var bx = 0.0
            var by = 0.0
            for (p in 0..18) {
                val w = BINOM_18[p] * powT[p] * powOneMinusT[18 - p]
                bx += pss[p * 2] * w
                by += pss[p * 2 + 1] * w
            }
            px[i] = (bx + offX).toFloat()
            py[i] = (by + offY).toFloat()
        }
        px[ITER] = (pss[36] + offX).toFloat()
        py[ITER] = (pss[37] + offY).toFloat()

        // Compute smooth averaged unit normals & bounds
        var bMinX = Float.POSITIVE_INFINITY
        var bMinY = Float.POSITIVE_INFINITY
        var bMaxX = Float.NEGATIVE_INFINITY
        var bMaxY = Float.NEGATIVE_INFINITY

        for (i in 0 until PTS) {
            val xCur = px[i]
            val yCur = py[i]
            bMinX = min(bMinX, xCur)
            bMinY = min(bMinY, yCur)
            bMaxX = max(bMaxX, xCur)
            bMaxY = max(bMaxY, yCur)

            var tx = 0f
            var ty = 0f
            if (i > 0) {
                val dx0 = xCur - px[i - 1]
                val dy0 = yCur - py[i - 1]
                val l0 = hypot(dx0, dy0).coerceAtLeast(1e-4f)
                tx += dx0 / l0
                ty += dy0 / l0
            }
            if (i < PTS - 1) {
                val dx1 = px[i + 1] - xCur
                val dy1 = py[i + 1] - yCur
                val l1 = hypot(dx1, dy1).coerceAtLeast(1e-4f)
                tx += dx1 / l1
                ty += dy1 / l1
            }
            val tl = hypot(tx, ty).coerceAtLeast(1e-4f)
            nx[i] = -ty / tl
            ny[i] = tx / tl
        }

        minBoundX = bMinX - 6f
        minBoundY = bMinY - 6f
        maxBoundX = bMaxX + 6f
        maxBoundY = bMaxY + 6f
    }

    /**
     * Renders the white Bezier paw interior and tapered dual-pass anti-aliased ink outline.
     */
    fun draw(
        ctx: GuiGraphicsExtractor,
        fillArgb: Int = 0xFFFFFFFF.toInt(),
        edgeArgb: Int = 0xFF000000.toInt(),
    ) {
        val edgeAlpha = (edgeArgb ushr 24) and 0xFF
        val shadAlpha = edgeAlpha / 3
        val edgeRgb = edgeArgb and 0x00FFFFFF
        val shadArgb = (shadAlpha shl 24) or edgeRgb

        ctx.drawCustomElement(
            pipeline = ClientRenderPipelines.GUI.triangles(false),
            bounds = ctx.getBounds(minBoundX, minBoundY, maxBoundX, maxBoundY),
        ) { pose ->
            // 1. Cross-bridged TriangleStrip interior fill (`(P_k, P_{25-k})` for k = 0..12)
            if ((fillArgb ushr 24) != 0) {
                for (k in 0 until 12) {
                    val l0x = px[k]; val l0y = py[k]
                    val r0x = px[25 - k]; val r0y = py[25 - k]
                    val l1x = px[k + 1]; val l1y = py[k + 1]
                    val r1x = px[24 - k]; val r1y = py[24 - k]

                    emitTri(pose, l0x, l0y, r0x, r0y, l1x, l1y, fillArgb)
                    emitTri(pose, l1x, l1y, r0x, r0y, r1x, r1y, fillArgb)
                }
            }

            // 2. Soft anti-aliasing outer stroke (`width = 7.0 -> 5.0`, alpha = edgeAlpha / 3)
            if (shadAlpha > 0) {
                emitStrokeRibbon(pose, startWidth = 7.2f, stepDrop = 0.08f, argb = shadArgb)
            }

            // 3. Crisp tapered marker stroke (`width = 6.0 -> 4.0`)
            if (edgeAlpha > 0) {
                emitStrokeRibbon(pose, startWidth = 5.8f, stepDrop = 0.08f, argb = edgeArgb)
            }
        }
    }

    private fun VertexConsumer.emitStrokeRibbon(
        pose: Matrix3x2f,
        startWidth: Float,
        stepDrop: Float,
        argb: Int,
    ) {
        val rStart = startWidth * 0.5f
        emitDisc(pose, px[0], py[0], rStart, argb)

        var prevAx = px[0] + nx[0] * rStart
        var prevAy = py[0] + ny[0] * rStart
        var prevBx = px[0] - nx[0] * rStart
        var prevBy = py[0] - ny[0] * rStart

        var curW = startWidth
        for (i in 1 until PTS) {
            curW -= stepDrop
            val hw = curW * 0.5f
            val curAx = px[i] + nx[i] * hw
            val curAy = py[i] + ny[i] * hw
            val curBx = px[i] - nx[i] * hw
            val curBy = py[i] - ny[i] * hw

            emitTri(pose, prevAx, prevAy, prevBx, prevBy, curAx, curAy, argb)
            emitTri(pose, curAx, curAy, prevBx, prevBy, curBx, curBy, argb)

            prevAx = curAx
            prevAy = curAy
            prevBx = curBx
            prevBy = curBy
        }

        emitDisc(pose, px[PTS - 1], py[PTS - 1], curW * 0.5f, argb)
    }

    private fun VertexConsumer.emitDisc(
        pose: Matrix3x2f,
        cx: Float,
        cy: Float,
        radius: Float,
        argb: Int,
    ) {
        for (i in 0 until CAP_SEGMENTS) {
            emitTri(
                pose,
                cx, cy,
                cx + CAP_COS[i] * radius, cy + CAP_SIN[i] * radius,
                cx + CAP_COS[i + 1] * radius, cy + CAP_SIN[i + 1] * radius,
                argb,
            )
        }
    }

    private fun VertexConsumer.emitTri(
        pose: Matrix3x2f,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        argb: Int,
    ) {
        addVertexWith2DPose(pose, x0, y0).setColor(argb)
        addVertexWith2DPose(pose, x1, y1).setColor(argb)
        addVertexWith2DPose(pose, x2, y2).setColor(argb)
    }
}
