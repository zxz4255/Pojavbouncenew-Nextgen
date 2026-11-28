/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
@file:Suppress("MagicNumber", "LongMethod", "LongParameterList", "TooManyFunctions")

package net.ccbluex.liquidbounce.features.module.modules.render.fpsknife

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.minecraft.client.renderer.texture.OverlayTexture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class KnifeModel(
    override val tag: String,
    val hasSpinRing: Boolean,
) : Tagged {
    NEXUS_SKELETON("NexusSkeleton", hasSpinRing = true),
    KARAMBIT("Karambit", hasSpinRing = true),
    TALON("Talon", hasSpinRing = true),
    BUTTERFLY("Butterfly", hasSpinRing = false),
    M9_BAYONET("M9Bayonet", hasSpinRing = false),
    SKELETON_DAGGER("SkeletonDagger", hasSpinRing = true),
    FLIP_KNIFE("FlipKnife", hasSpinRing = false),
    HUNTSMAN("Huntsman", hasSpinRing = false),
    BOWIE("Bowie", hasSpinRing = false),
    KUKRI("Kukri", hasSpinRing = false),
    KATANA_TANTO("KatanaTanto", hasSpinRing = false),
    STILETTO("Stiletto", hasSpinRing = false),
    KUNAI("Kunai", hasSpinRing = true),
}

/**
 * High-detail procedural 3D faceted FPS knife geometry builder (13 iconic models).
 *
 * Coordinate convention (in knife local space):
 * - `+X`: Blade length direction (from grip handle toward blade tip).
 * - `-X`: Handle / grip tang extending directly into the player's closed fist (`x ~ -0.26 .. +0.02`).
 * - `+Y`: Spine / dorsal edge; `-Y`: Cutting edge / ventral grip.
 * - `-Z`: Primary play-side facing the player camera in default right-hand grip;
 *   `+Z`: Back-side revealed during 180-degree inspect flips.
 * - `(gripPivotX, gripPivotY)`: Exact palm grip center locked to the player's fist socket so the
 *   knife handle stays seated inside the hand model at all times.
 * - `(ringPivotX, ringPivotY)`: Center of the physical finger spin ring for ring-twirl animations.
 */
object FpsKnifeMeshes {

    class KnifeVertex(
        val x: Float,
        val y: Float,
        val z: Float,
        val u: Float,
        val v: Float,
    )

    class KnifeQuad(
        val v0: KnifeVertex,
        val v1: KnifeVertex,
        val v2: KnifeVertex,
        val v3: KnifeVertex,
        val nx: Float,
        val ny: Float,
        val nz: Float,
        val isBlade: Boolean = false,
    )

    class ArticulatedPart(
        val pivotX: Float,
        val pivotY: Float,
        val quads: List<KnifeQuad>,
    )

    class KnifeMeshData(
        val mainQuads: List<KnifeQuad>,
        val safeHandle: ArticulatedPart? = null,
        val biteHandle: ArticulatedPart? = null,
        val gripPivotX: Float = -0.115f,
        val gripPivotY: Float = 0.0f,
        val ringPivotX: Float = 0.0f,
        val ringPivotY: Float = 0.0f,
        val statTrakAnchorX: Float = 0.28f,
        val statTrakAnchorY: Float = 0.0f,
        val statTrakAnchorZ: Float = -0.0155f,
        val foldingBlade: ArticulatedPart? = null,
    )

    // Atlas V bands matching FpsKnifeSkins
    private const val V_SPINE_0 = 0.015f
    private const val V_SPINE_1 = 0.210f
    private const val V_FULLER_0 = 0.230f
    private const val V_FULLER_1 = 0.510f
    private const val V_EDGE_0 = 0.530f
    private const val V_EDGE_1 = 0.750f
    private const val V_HW_0 = 0.770f
    private const val V_HW_1 = 0.985f

    private const val U_HANDLE_0 = 0.02f
    private const val U_HANDLE_1 = 0.53f
    private const val U_RING_0 = 0.56f
    private const val U_RING_1 = 0.98f

    // Emissive StatTrak digit strip (0..9) in FpsKnifeSkins: u in [0.70, 1.00], v in [0.005, 0.115]
    private const val DIGIT_STRIP_U0 = 0.70f
    private const val DIGIT_STRIP_U1 = 1.00f
    private const val DIGIT_STRIP_V0 = 0.008f
    private const val DIGIT_STRIP_V1 = 0.112f

    private val meshes = java.util.EnumMap<KnifeModel, KnifeMeshData>(KnifeModel::class.java)

    fun getMesh(model: KnifeModel): KnifeMeshData =
        meshes.getOrPut(model) {
            val raw = when (model) {
                KnifeModel.NEXUS_SKELETON -> buildNexusSkeleton()
                KnifeModel.KARAMBIT -> buildKarambit()
                KnifeModel.TALON -> buildTalon()
                KnifeModel.BUTTERFLY -> buildButterfly()
                KnifeModel.M9_BAYONET -> buildM9Bayonet()
                KnifeModel.SKELETON_DAGGER -> buildSkeletonDagger()
                KnifeModel.FLIP_KNIFE -> buildFlipKnife()
                KnifeModel.HUNTSMAN -> buildHuntsman()
                KnifeModel.BOWIE -> buildBowie()
                KnifeModel.KUKRI -> buildKukri()
                KnifeModel.KATANA_TANTO -> buildKatanaTanto()
                KnifeModel.STILETTO -> buildStiletto()
                KnifeModel.KUNAI -> buildKunai()
            }
            KnifeMeshData(raw.mainQuads, raw.safeHandle, raw.biteHandle,
                raw.gripPivotX + 0.028f, raw.gripPivotY, raw.ringPivotX, raw.ringPivotY,
                raw.statTrakAnchorX, raw.statTrakAnchorY, raw.statTrakAnchorZ, raw.foldingBlade)
        }

    fun emitMesh(
        mesh: KnifeMeshData,
        poseStack: PoseStack,
        consumer: VertexConsumer,
        packedLight: Int,
        argbColor: Int,
        safeHandleDeg: Float = 0f,
        biteHandleDeg: Float = 0f,
        bladeFoldDeg: Float = 0f,
    ) {
        emitQuadList(mesh.mainQuads, poseStack.last(), consumer, packedLight, argbColor)
        mesh.foldingBlade?.let { part ->
            poseStack.pushPose()
            poseStack.translate(part.pivotX, part.pivotY, 0f)
            poseStack.rotateDegrees(Axis.ZP, bladeFoldDeg)
            poseStack.translate(-part.pivotX, -part.pivotY, 0f)
            emitQuadList(part.quads, poseStack.last(), consumer, packedLight, argbColor)
            poseStack.popPose()
        }

        mesh.safeHandle?.let { part ->
            poseStack.pushPose()
            poseStack.translate(part.pivotX, part.pivotY, 0f)
            if (safeHandleDeg != 0f) {
                poseStack.rotateDegrees(Axis.ZP, safeHandleDeg)
            }
            poseStack.translate(-part.pivotX, -part.pivotY, 0f)
            emitQuadList(part.quads, poseStack.last(), consumer, packedLight, argbColor)
            poseStack.popPose()
        }

        mesh.biteHandle?.let { part ->
            poseStack.pushPose()
            poseStack.translate(part.pivotX, part.pivotY, 0f)
            if (biteHandleDeg != 0f) {
                poseStack.rotateDegrees(Axis.ZP, biteHandleDeg)
            }
            poseStack.translate(-part.pivotX, -part.pivotY, 0f)
            emitQuadList(part.quads, poseStack.last(), consumer, packedLight, argbColor)
            poseStack.popPose()
        }
    }

    /**
     * Emits a 6-digit glowing LED StatTrak counter module flush on the play-side fuller.
     */
    fun emitStatTrakCounter(
        mesh: KnifeMeshData,
        poseStack: PoseStack,
        consumer: VertexConsumer,
        fullBrightLight: Int,
        argbColor: Int,
        killCount: Int,
        bladeFoldDeg: Float = 0f,
    ) {
        poseStack.pushPose()
        mesh.foldingBlade?.let { part ->
            poseStack.translate(part.pivotX, part.pivotY, 0f)
            poseStack.rotateDegrees(Axis.ZP, bladeFoldDeg)
            poseStack.translate(-part.pivotX, -part.pivotY, 0f)
        }
        val clamped = killCount.coerceIn(0, 999_999)
        val digits = IntArray(6)
        var rem = clamped
        for (i in 5 downTo 0) {
            digits[i] = rem % 10
            rem /= 10
        }

        val pose = poseStack.last()
        val cellW = 0.0175f
        val cellGap = 0.0028f
        val cellH = 0.025f
        val totalW = 6 * cellW + 5 * cellGap
        val startX = mesh.statTrakAnchorX - totalW * 0.5f
        val y0 = mesh.statTrakAnchorY - cellH * 0.5f
        val y1 = mesh.statTrakAnchorY + cellH * 0.5f
        val z = mesh.statTrakAnchorZ - 0.0018f
        val du = (DIGIT_STRIP_U1 - DIGIT_STRIP_U0) / 10f

        for (i in 0 until 6) {
            val d = digits[i]
            val x0 = startX + i * (cellW + cellGap)
            val x1 = x0 + cellW
            val u0 = DIGIT_STRIP_U0 + d * du + du * 0.04f
            val u1 = DIGIT_STRIP_U0 + (d + 1) * du - du * 0.04f

            putVertex(consumer, pose, x0, y0, z, u0, DIGIT_STRIP_V1, argbColor, fullBrightLight, 0f, 0f, -1f)
            putVertex(consumer, pose, x1, y0, z, u1, DIGIT_STRIP_V1, argbColor, fullBrightLight, 0f, 0f, -1f)
            putVertex(consumer, pose, x1, y1, z, u1, DIGIT_STRIP_V0, argbColor, fullBrightLight, 0f, 0f, -1f)
            putVertex(consumer, pose, x0, y1, z, u0, DIGIT_STRIP_V0, argbColor, fullBrightLight, 0f, 0f, -1f)
        }
        poseStack.popPose()
    }

    private fun emitQuadList(
        quads: List<KnifeQuad>,
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        packedLight: Int,
        argbColor: Int,
    ) {
        for (i in quads.indices) {
            val q = quads[i]
            putVertex(consumer, pose, q.v0.x, q.v0.y, q.v0.z, q.v0.u, q.v0.v, argbColor, packedLight, q.nx, q.ny, q.nz)
            putVertex(consumer, pose, q.v1.x, q.v1.y, q.v1.z, q.v1.u, q.v1.v, argbColor, packedLight, q.nx, q.ny, q.nz)
            putVertex(consumer, pose, q.v2.x, q.v2.y, q.v2.z, q.v2.u, q.v2.v, argbColor, packedLight, q.nx, q.ny, q.nz)
            putVertex(consumer, pose, q.v3.x, q.v3.y, q.v3.z, q.v3.u, q.v3.v, argbColor, packedLight, q.nx, q.ny, q.nz)
        }
    }

    private fun putVertex(
        vc: VertexConsumer,
        pose: PoseStack.Pose,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        argb: Int,
        light: Int,
        nx: Float,
        ny: Float,
        nz: Float,
    ) {
        vc.addVertex(pose, x, y, z)
            .setColor(argb)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, nx, ny, nz)
    }

    // =========================================================================
    // 1. NEXUS SKELETON RING KNIFE (Default — matches reference screenshot 1:1)
    // =========================================================================
    private fun buildNexusSkeleton(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(260)

        addRing(q, cx = 0f, cy = 0f, rIn = 0.043f, rOut = 0.064f, zHalf = 0.0155f, segs = 24, vBand0 = 0.30f, vBand1 = 0.70f)
        addRing(q, cx = 0f, cy = 0f, rIn = 0.064f, rOut = 0.074f, zHalf = 0.0135f, segs = 24, vBand0 = 0.04f, vBand1 = 0.22f)

        addBeamSegment(q, 0.058f, 0.028f, 0.058f, 0.014f, 0.168f, 0.018f, 0.048f, 0.0135f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.06f)
        addBeamSegment(q, 0.058f, -0.058f, -0.028f, 0.014f, 0.168f, -0.046f, -0.016f, 0.0135f, U_RING_0, U_RING_1, V_HW_0 + 0.08f, V_HW_1)
        addBeamSegment(q, 0.095f, -0.026f, -0.014f, 0.010f, 0.138f, 0.016f, 0.028f, 0.010f, U_RING_0, U_RING_0 + 0.15f, V_HW_0, V_HW_0 + 0.04f)

        val bladeStations = arrayOf(
            BladeStation(0.168f, 0.048f, 0.020f, -0.016f, -0.046f, 0.0135f, 0.04f),
            BladeStation(0.295f, 0.046f, 0.019f, -0.016f, -0.044f, 0.0125f, 0.34f),
            BladeStation(0.435f, 0.038f, 0.015f, -0.014f, -0.036f, 0.0105f, 0.66f),
            BladeStation(0.555f, 0.022f, 0.008f, -0.008f, -0.021f, 0.0065f, 0.86f),
            BladeStation(0.652f, 0.0015f, 0.0005f, -0.0005f, -0.0015f, 0.0012f, 0.99f),
        )
        addLoftedBlade(q, bladeStations)

        val handleStations = arrayOf(
            HandleStation(-0.058f, 0.052f, -0.052f, 0.0155f, 0.05f),
            HandleStation(-0.135f, 0.045f, -0.046f, 0.0150f, 0.36f),
            HandleStation(-0.225f, 0.034f, -0.037f, 0.0135f, 0.72f),
            HandleStation(-0.308f, 0.020f, -0.024f, 0.0110f, 0.98f),
        )
        addLoftedHandle(q, handleStations)

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.145f,
            gripPivotY = -0.005f,
            ringPivotX = 0.0f,
            ringPivotY = 0.0f,
            statTrakAnchorX = 0.345f,
            statTrakAnchorY = 0.001f,
            statTrakAnchorZ = -0.0132f,
        )
    }

    // =========================================================================
    // 2. KARAMBIT (Ergonomic Palm Grip + Index Spin Ring + Curved Hawkbill Talon)
    // =========================================================================
    private fun buildKarambit(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(260)

        // Index-finger spin ring at (0, 0) + ergonomic curved grip extending into the palm (-X)
        addRing(q, cx = -0.242f, cy = 0.008f, rIn = 0.036f, rOut = 0.053f, zHalf = 0.015f, segs = 22, vBand0 = 0.28f, vBand1 = 0.72f)
        addRing(q, cx = -0.242f, cy = 0.008f, rIn = 0.053f, rOut = 0.060f, zHalf = 0.013f, segs = 22, vBand0 = 0.04f, vBand1 = 0.22f)

        val grip = arrayOf(
            HandleStation(0.035f, 0.038f, -0.042f, 0.0155f, 0.06f),
            HandleStation(-0.045f, 0.042f, -0.034f, 0.0165f, 0.35f),
            HandleStation(-0.125f, 0.043f, -0.028f, 0.0165f, 0.65f),
            HandleStation(-0.195f, 0.038f, -0.032f, 0.0150f, 0.95f),
        )
        addLoftedHandle(q, grip)

        val clawPts = arrayOf(
            floatArrayOf(0.035f, -0.002f, 0.043f, 0.0135f, 0.05f),
            floatArrayOf(0.145f, -0.018f, 0.041f, 0.0125f, 0.28f),
            floatArrayOf(0.250f, -0.055f, 0.036f, 0.0110f, 0.52f),
            floatArrayOf(0.335f, -0.115f, 0.027f, 0.0085f, 0.74f),
            floatArrayOf(0.395f, -0.190f, 0.015f, 0.0050f, 0.90f),
            floatArrayOf(0.422f, -0.248f, 0.0018f, 0.0015f, 0.99f),
        )
        val clawStations = Array(clawPts.size) { i ->
            val p = clawPts[i]
            val hw = p[2]
            BladeStation(
                x = p[0],
                ySpine = p[1] + hw,
                yMidUp = p[1] + hw * 0.42f,
                yMidLo = p[1] - hw * 0.35f,
                yEdge = p[1] - hw,
                zHalf = p[3],
                u = p[4],
            )
        }
        addLoftedBlade(q, clawStations)

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.110f,
            gripPivotY = 0.0f,
            ringPivotX = -0.242f,
            ringPivotY = 0.008f,
            statTrakAnchorX = 0.145f,
            statTrakAnchorY = -0.018f,
            statTrakAnchorZ = -0.0132f,
        )
    }

    // =========================================================================
    // 3. TALON KNIFE (CS2 Talon: Riveted Handle + Pommel Ring + Sawtooth Claw)
    // =========================================================================
    private fun buildTalon(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(310)

        // Brass-collared finger ring at pommel + scalloped bolster handle inside palm
        addRing(q, cx = -0.246f, cy = 0.006f, rIn = 0.037f, rOut = 0.055f, zHalf = 0.016f, segs = 22, vBand0 = 0.06f, vBand1 = 0.28f)
        val grip = arrayOf(
            HandleStation(0.042f, 0.044f, -0.046f, 0.017f, 0.05f),
            HandleStation(-0.040f, 0.046f, -0.036f, 0.018f, 0.34f),
            HandleStation(-0.125f, 0.045f, -0.030f, 0.018f, 0.66f),
            HandleStation(-0.198f, 0.040f, -0.034f, 0.016f, 0.96f),
        )
        addLoftedHandle(q, grip)
        // Forward bolster guard lip
        addBeamSegment(q, 0.032f, 0.052f, -0.058f, 0.019f, 0.055f, 0.048f, -0.052f, 0.017f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.06f)

        // Broad aggressive Talon hawkbill blade
        val clawPts = arrayOf(
            floatArrayOf(0.052f, 0.002f, 0.050f, 0.0145f, 0.04f),
            floatArrayOf(0.165f, -0.014f, 0.048f, 0.0135f, 0.28f),
            floatArrayOf(0.275f, -0.052f, 0.042f, 0.0120f, 0.54f),
            floatArrayOf(0.365f, -0.112f, 0.031f, 0.0090f, 0.76f),
            floatArrayOf(0.425f, -0.186f, 0.016f, 0.0055f, 0.91f),
            floatArrayOf(0.452f, -0.242f, 0.0020f, 0.0015f, 0.99f),
        )
        val clawStations = Array(clawPts.size) { i ->
            val p = clawPts[i]
            val hw = p[2]
            BladeStation(p[0], p[1] + hw, p[1] + hw * 0.44f, p[1] - hw * 0.36f, p[1] - hw, p[3], p[4])
        }
        addLoftedBlade(q, clawStations)

        // 5 dorsal spine sawteeth along the outer curve of the Talon
        for (t in 0 until 5) {
            val sx0 = 0.085f + t * 0.036f
            val sx1 = sx0 + 0.024f
            val baseY = 0.050f - t * 0.008f
            addBeamSegment(
                q,
                sx0, baseY - 0.004f, baseY + 0.015f, 0.009f,
                sx1, baseY - 0.006f, baseY + 0.003f, 0.008f,
                0.18f + t * 0.08f, 0.24f + t * 0.08f, V_SPINE_0, V_SPINE_1,
            )
        }

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.110f,
            gripPivotY = 0.0f,
            ringPivotX = -0.246f,
            ringPivotY = 0.006f,
            statTrakAnchorX = 0.165f,
            statTrakAnchorY = -0.012f,
            statTrakAnchorZ = -0.0142f,
        )
    }

    // =========================================================================
    // 4. BUTTERFLY / BALISONG (Articulated Twin Titanium Handles + Spear Blade)
    // =========================================================================
    private fun buildButterfly(): KnifeMeshData {
        val bladeQ = ArrayList<KnifeQuad>(140)

        addRing(bladeQ, cx = 0.030f, cy = 0.030f, rIn = 0.005f, rOut = 0.014f, zHalf = 0.015f, segs = 10, vBand0 = 0.05f, vBand1 = 0.22f)
        addRing(bladeQ, cx = 0.030f, cy = -0.030f, rIn = 0.005f, rOut = 0.014f, zHalf = 0.015f, segs = 10, vBand0 = 0.05f, vBand1 = 0.22f)

        val bladeStations = arrayOf(
            BladeStation(0.035f, 0.036f, 0.015f, -0.015f, -0.036f, 0.0125f, 0.04f),
            BladeStation(0.160f, 0.039f, 0.016f, -0.016f, -0.039f, 0.0120f, 0.28f),
            BladeStation(0.340f, 0.036f, 0.014f, -0.014f, -0.036f, 0.0105f, 0.62f),
            BladeStation(0.475f, 0.023f, 0.009f, -0.009f, -0.023f, 0.0070f, 0.85f),
            BladeStation(0.585f, 0.0015f, 0.0005f, -0.0005f, -0.0015f, 0.0012f, 0.99f),
        )
        addLoftedBlade(bladeQ, bladeStations)

        val safeQ = ArrayList<KnifeQuad>(60)
        val safeStations = arrayOf(
            HandleStation(0.032f, 0.044f, 0.006f, 0.0165f, 0.05f),
            HandleStation(-0.115f, 0.046f, 0.005f, 0.0160f, 0.50f),
            HandleStation(-0.275f, 0.042f, 0.006f, 0.0150f, 0.96f),
        )
        addLoftedHandle(safeQ, safeStations)

        val biteQ = ArrayList<KnifeQuad>(72)
        val biteStations = arrayOf(
            HandleStation(0.032f, -0.006f, -0.044f, 0.0165f, 0.05f),
            HandleStation(-0.115f, -0.005f, -0.046f, 0.0160f, 0.50f),
            HandleStation(-0.275f, -0.006f, -0.042f, 0.0150f, 0.96f),
        )
        addLoftedHandle(biteQ, biteStations)
        addBeamSegment(biteQ, -0.275f, -0.014f, -0.032f, 0.008f, -0.312f, -0.010f, -0.036f, 0.009f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.05f)

        return KnifeMeshData(
            mainQuads = bladeQ,
            safeHandle = ArticulatedPart(pivotX = 0.030f, pivotY = 0.030f, quads = safeQ),
            biteHandle = ArticulatedPart(pivotX = 0.030f, pivotY = -0.030f, quads = biteQ),
            gripPivotX = -0.120f,
            gripPivotY = 0.0f,
            statTrakAnchorX = 0.225f,
            statTrakAnchorY = 0.0f,
            statTrakAnchorZ = -0.0126f,
        )
    }

    // =========================================================================
    // 5. M9 BAYONET (Sawtooth Spine + Muzzle Guard Ring + Ribbed Combat Grip)
    // =========================================================================
    private fun buildM9Bayonet(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(260)

        val gripStations = arrayOf(
            HandleStation(0.045f, 0.038f, -0.038f, 0.021f, 0.06f),
            HandleStation(-0.065f, 0.041f, -0.041f, 0.023f, 0.38f),
            HandleStation(-0.180f, 0.040f, -0.040f, 0.022f, 0.72f),
            HandleStation(-0.265f, 0.036f, -0.036f, 0.019f, 0.96f),
        )
        addLoftedHandle(q, gripStations)
        addBeamSegment(q, -0.265f, 0.044f, -0.044f, 0.022f, -0.298f, 0.038f, -0.038f, 0.019f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.05f)

        addBeamSegment(q, 0.045f, 0.088f, -0.078f, 0.024f, 0.072f, 0.088f, -0.078f, 0.024f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.05f)
        addRing(q, cx = 0.058f, cy = 0.072f, rIn = 0.014f, rOut = 0.026f, zHalf = 0.014f, segs = 14, vBand0 = 0.04f, vBand1 = 0.22f)

        val bladeStations = arrayOf(
            BladeStation(0.072f, 0.052f, 0.024f, -0.018f, -0.054f, 0.0145f, 0.04f),
            BladeStation(0.270f, 0.052f, 0.023f, -0.018f, -0.053f, 0.0140f, 0.40f),
            BladeStation(0.435f, 0.044f, 0.018f, -0.016f, -0.044f, 0.0115f, 0.72f),
            BladeStation(0.535f, 0.022f, 0.009f, -0.009f, -0.024f, 0.0070f, 0.90f),
            BladeStation(0.610f, 0.0018f, 0.0006f, -0.0006f, -0.0018f, 0.0014f, 0.99f),
        )
        addLoftedBlade(q, bladeStations)

        for (t in 0 until 6) {
            val sx0 = 0.125f + t * 0.034f
            val sx1 = sx0 + 0.024f
            addBeamSegment(
                q,
                sx0, 0.050f, 0.065f, 0.009f,
                sx1, 0.050f, 0.052f, 0.008f,
                0.20f + t * 0.08f, 0.25f + t * 0.08f, V_SPINE_0, V_SPINE_1,
            )
        }

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.115f,
            gripPivotY = 0.0f,
            statTrakAnchorX = 0.260f,
            statTrakAnchorY = 0.003f,
            statTrakAnchorZ = -0.0148f,
        )
    }

    // =========================================================================
    // 6. SKELETON DAGGER (CS2 Skeleton Knife: Taped Tang + Center Ring + Spear)
    // =========================================================================
    private fun buildSkeletonDagger(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(250)

        // Large machined center spin ring at (0, 0)
        addRing(q, cx = 0f, cy = 0f, rIn = 0.042f, rOut = 0.060f, zHalf = 0.0135f, segs = 24, vBand0 = 0.22f, vBand1 = 0.68f)

        // Skeletonized taped handle rails (-X) + rear ringlet
        addBeamSegment(q, -0.054f, 0.018f, 0.038f, 0.0135f, -0.225f, 0.012f, 0.030f, 0.0130f, U_HANDLE_0, U_HANDLE_1, V_HW_0, V_HW_0 + 0.08f)
        addBeamSegment(q, -0.054f, -0.038f, -0.018f, 0.0135f, -0.225f, -0.030f, -0.012f, 0.0130f, U_HANDLE_0, U_HANDLE_1, V_HW_1 - 0.08f, V_HW_1)
        // Cord-wrapped grip bridge in center of handle
        val wrap = arrayOf(
            HandleStation(-0.085f, 0.035f, -0.035f, 0.0155f, 0.22f),
            HandleStation(-0.185f, 0.031f, -0.031f, 0.0150f, 0.78f),
        )
        addLoftedHandle(q, wrap)
        addRing(q, cx = -0.252f, cy = 0f, rIn = 0.014f, rOut = 0.028f, zHalf = 0.0125f, segs = 16, vBand0 = 0.05f, vBand1 = 0.25f)

        // Symmetrical double-edged spear-point dagger blade (+X)
        val bladeStations = arrayOf(
            BladeStation(0.054f, 0.046f, 0.016f, -0.016f, -0.046f, 0.0130f, 0.04f),
            BladeStation(0.185f, 0.050f, 0.018f, -0.018f, -0.050f, 0.0125f, 0.30f),
            BladeStation(0.365f, 0.039f, 0.014f, -0.014f, -0.039f, 0.0105f, 0.64f),
            BladeStation(0.515f, 0.020f, 0.007f, -0.007f, -0.020f, 0.0065f, 0.88f),
            BladeStation(0.615f, 0.0015f, 0.0005f, -0.0005f, -0.0015f, 0.0012f, 0.99f),
        )
        addLoftedBlade(q, bladeStations)

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.135f,
            gripPivotY = 0.0f,
            ringPivotX = 0.0f,
            ringPivotY = 0.0f,
            statTrakAnchorX = 0.245f,
            statTrakAnchorY = 0.0f,
            statTrakAnchorZ = -0.0132f,
        )
    }

    // =========================================================================
    // 7. FLIP KNIFE (CS2 Persian Upswept Tactical Folder + Pivot Bolster)
    // =========================================================================
    private fun buildFlipKnife(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(230)

        // Curved tactical folder handle (-X) + pivot bolster washer & thumb stud
        val handleStations = arrayOf(
            HandleStation(0.042f, 0.038f, -0.042f, 0.0165f, 0.05f),
            HandleStation(-0.055f, 0.036f, -0.032f, 0.0170f, 0.36f),
            HandleStation(-0.165f, 0.032f, -0.038f, 0.0160f, 0.72f),
            HandleStation(-0.265f, 0.024f, -0.048f, 0.0145f, 0.98f),
        )
        addLoftedHandle(q, handleStations)
        addRing(q, cx = 0.028f, cy = 0.0f, rIn = 0.006f, rOut = 0.018f, zHalf = 0.018f, segs = 12, vBand0 = 0.05f, vBand1 = 0.25f)
        // Lower finger guard tab
        addBeamSegment(q, 0.025f, -0.038f, -0.064f, 0.012f, 0.048f, -0.036f, -0.054f, 0.011f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.05f)

        // Swept Persian upswept blade (+X)
        val bladeStations = arrayOf(
            BladeStation(0.042f, 0.036f, 0.015f, -0.014f, -0.040f, 0.0125f, 0.04f),
            BladeStation(0.205f, 0.034f, 0.014f, -0.015f, -0.043f, 0.0120f, 0.34f),
            BladeStation(0.385f, 0.038f, 0.016f, -0.012f, -0.035f, 0.0105f, 0.66f),
            BladeStation(0.515f, 0.046f, 0.022f, 0.002f, -0.012f, 0.0070f, 0.88f),
            BladeStation(0.605f, 0.054f, 0.053f, 0.051f, 0.050f, 0.0014f, 0.99f),
        )
        val blade = ArrayList<KnifeQuad>()
        // Rebuild the blade proportions so its folded length fits the real handle cavity.
        val fitted = Array(bladeStations.size) { i ->
            val b = bladeStations[i]
            BladeStation(0.028f + (b.x - 0.028f) * 0.48f,
                b.ySpine * .76f, b.yMidUp * .76f, b.yMidLo * .76f, b.yEdge * .76f, b.zHalf * .8f, b.u)
        }
        addLoftedBlade(blade, fitted)

        return KnifeMeshData(
            mainQuads = q,
            foldingBlade = ArticulatedPart(0.028f, 0f, blade),
            ringPivotX = 0.028f,
            gripPivotX = -0.115f,
            gripPivotY = -0.004f,
            statTrakAnchorX = 0.13216f,
            statTrakAnchorY = -0.002f,
            statTrakAnchorZ = -0.01024f,
        )
    }

    // =========================================================================
    // 8. HUNTSMAN KNIFE (CS2 Modern Tactical Tanto + Dorsal Gut-Hook & Teeth)
    // =========================================================================
    private fun buildHuntsman(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(280)

        // Deep finger-choil ergonomic combat handle (-X)
        val handleStations = arrayOf(
            HandleStation(0.048f, 0.044f, -0.052f, 0.0185f, 0.05f),
            HandleStation(-0.045f, 0.042f, -0.028f, 0.0190f, 0.35f),
            HandleStation(-0.155f, 0.045f, -0.042f, 0.0185f, 0.70f),
            HandleStation(-0.268f, 0.038f, -0.054f, 0.0165f, 0.98f),
        )
        addLoftedHandle(q, handleStations)

        // Heavy angular Huntsman tanto blade (+X) with gut-hook notch near tip
        val bladeStations = arrayOf(
            BladeStation(0.048f, 0.052f, 0.022f, -0.018f, -0.056f, 0.0145f, 0.04f),
            BladeStation(0.245f, 0.056f, 0.024f, -0.020f, -0.060f, 0.0140f, 0.38f),
            BladeStation(0.415f, 0.058f, 0.025f, -0.020f, -0.058f, 0.0125f, 0.68f),
            // Gut-hook dip in spine before angular tanto point
            BladeStation(0.475f, 0.036f, 0.018f, -0.018f, -0.050f, 0.0110f, 0.79f),
            BladeStation(0.535f, 0.048f, 0.020f, -0.012f, -0.032f, 0.0080f, 0.90f),
            BladeStation(0.618f, 0.012f, 0.010f, 0.008f, 0.006f, 0.0016f, 0.99f),
        )
        addLoftedBlade(q, bladeStations)

        // 4 aggressive spine teeth near base
        for (t in 0 until 4) {
            val sx0 = 0.095f + t * 0.032f
            addBeamSegment(
                q,
                sx0, 0.050f, 0.066f, 0.010f,
                sx0 + 0.020f, 0.050f, 0.054f, 0.009f,
                0.15f + t * 0.07f, 0.20f + t * 0.07f, V_SPINE_0, V_SPINE_1,
            )
        }

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.115f,
            gripPivotY = -0.004f,
            statTrakAnchorX = 0.255f,
            statTrakAnchorY = 0.0f,
            statTrakAnchorZ = -0.0146f,
        )
    }

    // =========================================================================
    // 9. BOWIE KNIFE (CS2 Heavy Survival Bowie + S-Crossguard + Clip Swedge)
    // =========================================================================
    private fun buildBowie(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(250)

        val handleStations = arrayOf(
            HandleStation(0.045f, 0.038f, -0.040f, 0.019f, 0.05f),
            HandleStation(-0.065f, 0.042f, -0.036f, 0.020f, 0.38f),
            HandleStation(-0.175f, 0.040f, -0.044f, 0.019f, 0.72f),
            HandleStation(-0.272f, 0.034f, -0.052f, 0.017f, 0.98f),
        )
        addLoftedHandle(q, handleStations)

        // Classic swept Bowie S-guard crossguard
        addBeamSegment(q, 0.042f, 0.082f, -0.088f, 0.022f, 0.068f, 0.074f, -0.078f, 0.020f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.06f)

        // Massive wide clip-point survival blade with concave swedge
        val bladeStations = arrayOf(
            BladeStation(0.068f, 0.054f, 0.024f, -0.020f, -0.058f, 0.0150f, 0.04f),
            BladeStation(0.255f, 0.056f, 0.025f, -0.022f, -0.064f, 0.0145f, 0.36f),
            BladeStation(0.435f, 0.058f, 0.024f, -0.020f, -0.058f, 0.0125f, 0.66f),
            BladeStation(0.555f, 0.030f, 0.012f, -0.012f, -0.034f, 0.0080f, 0.87f),
            BladeStation(0.665f, 0.006f, 0.003f, -0.001f, -0.004f, 0.0016f, 0.99f),
        )
        addLoftedBlade(q, bladeStations)

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.118f,
            gripPivotY = -0.004f,
            statTrakAnchorX = 0.275f,
            statTrakAnchorY = -0.002f,
            statTrakAnchorZ = -0.0152f,
        )
    }

    // =========================================================================
    // 10. KUKRI KNIFE (CS2 Nepalese Forward-Curved Recurve Chopping Blade)
    // =========================================================================
    private fun buildKukri(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(250)

        // Traditional flared pommel grip (-X) + bolster collar
        val handleStations = arrayOf(
            HandleStation(0.042f, 0.034f, -0.036f, 0.0175f, 0.05f),
            HandleStation(-0.065f, 0.036f, -0.038f, 0.0185f, 0.38f),
            HandleStation(-0.175f, 0.034f, -0.040f, 0.0175f, 0.72f),
            HandleStation(-0.265f, 0.046f, -0.056f, 0.0190f, 0.98f),
        )
        addLoftedHandle(q, handleStations)
        addBeamSegment(q, 0.036f, 0.044f, -0.046f, 0.020f, 0.056f, 0.040f, -0.042f, 0.018f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.05f)

        // Forward-dropping recurve Kukri belly blade
        val bladeStations = arrayOf(
            BladeStation(0.056f, 0.036f, 0.016f, -0.012f, -0.032f, 0.0135f, 0.04f),
            BladeStation(0.175f, 0.042f, 0.018f, -0.014f, -0.036f, 0.0135f, 0.26f),
            BladeStation(0.335f, 0.030f, 0.004f, -0.034f, -0.074f, 0.0130f, 0.54f),
            BladeStation(0.475f, 0.004f, -0.018f, -0.048f, -0.086f, 0.0105f, 0.78f),
            BladeStation(0.575f, -0.028f, -0.036f, -0.052f, -0.072f, 0.0065f, 0.92f),
            BladeStation(0.635f, -0.052f, -0.053f, -0.055f, -0.057f, 0.0015f, 0.99f),
        )
        addLoftedBlade(q, bladeStations)

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.115f,
            gripPivotY = -0.004f,
            statTrakAnchorX = 0.265f,
            statTrakAnchorY = -0.006f,
            statTrakAnchorZ = -0.0140f,
        )
    }

    // =========================================================================
    // 11. KATANA TANTO (Cyber Wakizashi / RGX Tanto + Tsuba Guard + Tsuka Grip)
    // =========================================================================
    private fun buildKatanaTanto(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(260)

        // Rectangular Japanese Tsuka handle (-X) + Kashira pommel
        val tsukaStations = arrayOf(
            HandleStation(0.042f, 0.030f, -0.032f, 0.0155f, 0.05f),
            HandleStation(-0.075f, 0.029f, -0.033f, 0.0155f, 0.42f),
            HandleStation(-0.195f, 0.028f, -0.035f, 0.0150f, 0.78f),
            HandleStation(-0.285f, 0.027f, -0.037f, 0.0145f, 0.98f),
        )
        addLoftedHandle(q, tsukaStations)

        // Octagonal / circular Tsuba disc guard + brass Habaki blade collar
        addBeamSegment(q, 0.040f, 0.068f, -0.070f, 0.028f, 0.052f, 0.068f, -0.070f, 0.028f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.06f)
        addBeamSegment(q, 0.052f, 0.032f, -0.034f, 0.016f, 0.082f, 0.031f, -0.033f, 0.015f, U_RING_0, U_RING_0 + 0.2f, V_SPINE_0, V_SPINE_1)

        // Gently upswept Shinogi-Zukuri Katana/Wakizashi blade with crisp Kissaki tanto tip
        val bladeStations = arrayOf(
            BladeStation(0.082f, 0.029f, 0.012f, -0.010f, -0.031f, 0.0115f, 0.04f),
            BladeStation(0.255f, 0.032f, 0.014f, -0.008f, -0.029f, 0.0110f, 0.36f),
            BladeStation(0.445f, 0.038f, 0.018f, -0.003f, -0.023f, 0.0095f, 0.70f),
            BladeStation(0.585f, 0.045f, 0.024f, 0.005f, -0.012f, 0.0070f, 0.90f),
            BladeStation(0.675f, 0.049f, 0.048f, 0.046f, 0.044f, 0.0014f, 0.99f),
        )
        addLoftedBlade(q, bladeStations)

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.120f,
            gripPivotY = -0.002f,
            statTrakAnchorX = 0.255f,
            statTrakAnchorY = 0.003f,
            statTrakAnchorZ = -0.0118f,
        )
    }

    // =========================================================================
    // 12. STILETTO (Sleek Italian Switchblade with S-Quillons & Needle Spear)
    // =========================================================================
    private fun buildStiletto(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(200)

        val handleStations = arrayOf(
            HandleStation(0.048f, 0.026f, -0.026f, 0.015f, 0.05f),
            HandleStation(-0.065f, 0.029f, -0.029f, 0.016f, 0.42f),
            HandleStation(-0.185f, 0.025f, -0.025f, 0.015f, 0.78f),
            HandleStation(-0.275f, 0.031f, -0.031f, 0.014f, 0.98f),
        )
        addLoftedHandle(q, handleStations)

        addBeamSegment(q, 0.048f, 0.066f, -0.066f, 0.016f, 0.068f, 0.058f, -0.058f, 0.015f, U_RING_0, U_RING_1, V_HW_0, V_HW_0 + 0.05f)

        val bladeStations = arrayOf(
            BladeStation(0.068f, 0.025f, 0.010f, -0.010f, -0.025f, 0.0105f, 0.04f),
            BladeStation(0.245f, 0.024f, 0.009f, -0.009f, -0.024f, 0.0095f, 0.38f),
            BladeStation(0.435f, 0.017f, 0.006f, -0.006f, -0.017f, 0.0070f, 0.74f),
            BladeStation(0.618f, 0.0012f, 0.0004f, -0.0004f, -0.0012f, 0.0010f, 0.99f),
        )
        val blade = ArrayList<KnifeQuad>()
        // Rebuild the blade proportions so its folded length fits the real handle cavity.
        val fitted = Array(bladeStations.size) { i ->
            val b = bladeStations[i]
            BladeStation(0.058f + (b.x - 0.058f) * 0.5f,
                b.ySpine * .76f, b.yMidUp * .76f, b.yMidLo * .76f, b.yEdge * .76f, b.zHalf * .8f, b.u)
        }
        addLoftedBlade(blade, fitted)

        return KnifeMeshData(
            mainQuads = q,
            foldingBlade = ArticulatedPart(0.058f, 0f, blade),
            ringPivotX = 0.058f,
            gripPivotX = -0.115f,
            gripPivotY = 0.0f,
            statTrakAnchorX = 0.14400f,
            statTrakAnchorY = 0.0f,
            statTrakAnchorZ = -0.00864f,
        )
    }

    // =========================================================================
    // 13. KUNAI (Cord-Wrapped Palm Grip + Rear Pommel Ring + Diamond Leaf Blade)
    // =========================================================================
    private fun buildKunai(): KnifeMeshData {
        val q = ArrayList<KnifeQuad>(220)

        // Rear pommel twirl ring + cord-wrapped shaft extending squarely inside palm (-X)
        addRing(q, cx = -0.252f, cy = 0f, rIn = 0.038f, rOut = 0.055f, zHalf = 0.014f, segs = 20, vBand0 = 0.28f, vBand1 = 0.72f)

        val shaftStations = arrayOf(
            HandleStation(0.042f, 0.024f, -0.024f, 0.015f, 0.05f),
            HandleStation(-0.080f, 0.020f, -0.020f, 0.016f, 0.50f),
            HandleStation(-0.198f, 0.018f, -0.018f, 0.015f, 0.96f),
        )
        addLoftedHandle(q, shaftStations)

        val bladeStations = arrayOf(
            BladeStation(0.042f, 0.026f, 0.010f, -0.010f, -0.026f, 0.012f, 0.04f),
            BladeStation(0.155f, 0.068f, 0.024f, -0.024f, -0.068f, 0.016f, 0.28f),
            BladeStation(0.345f, 0.044f, 0.015f, -0.015f, -0.044f, 0.012f, 0.64f),
            BladeStation(0.565f, 0.0015f, 0.0005f, -0.0005f, -0.0015f, 0.0012f, 0.99f),
        )
        addLoftedBlade(q, bladeStations)

        return KnifeMeshData(
            mainQuads = q,
            gripPivotX = -0.105f,
            gripPivotY = 0.0f,
            ringPivotX = -0.252f,
            ringPivotY = 0.0f,
            statTrakAnchorX = 0.215f,
            statTrakAnchorY = 0.0f,
            statTrakAnchorZ = -0.0165f,
        )
    }

    // =========================================================================
    // Geometry Builders (Non-mirrored UVs + Exact Outward Surface Normals)
    // =========================================================================

    private class BladeStation(
        val x: Float,
        val ySpine: Float,
        val yMidUp: Float,
        val yMidLo: Float,
        val yEdge: Float,
        val zHalf: Float,
        val u: Float,
    )

    private class HandleStation(
        val x: Float,
        val yUp: Float,
        val yLo: Float,
        val zHalf: Float,
        val uNorm: Float,
    )

    /** Bounded cubic silhouette refinement; cannot overshoot an authored section envelope. */
    private fun boundedCubic(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val t2 = t * t
        val value = .5f * (2f * b + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 +
            (-a + 3f * b - 3f * c + d) * t2 * t)
        return value.coerceIn(minOf(b, c), maxOf(b, c))
    }

    private fun refineBlade(input: Array<BladeStation>): Array<BladeStation> =
        Array((input.size - 1) * 3 + 1) { index ->
            if (index == (input.size - 1) * 3) input.last() else {
                val i = index / 3; val t = (index % 3) / 3f
                val a = input[(i - 1).coerceAtLeast(0)]; val b = input[i]
                val c = input[i + 1]; val d = input[(i + 2).coerceAtMost(input.lastIndex)]
                fun y(get: (BladeStation) -> Float) = boundedCubic(get(a), get(b), get(c), get(d), t)
                BladeStation(b.x + (c.x - b.x) * t, y { it.ySpine }, y { it.yMidUp },
                    y { it.yMidLo }, y { it.yEdge }, y { it.zHalf }, b.u + (c.u - b.u) * t)
            }
        }

    private fun refineHandle(input: Array<HandleStation>): Array<HandleStation> =
        Array((input.size - 1) * 3 + 1) { index ->
            if (index == (input.size - 1) * 3) input.last() else {
                val i = index / 3; val t = (index % 3) / 3f
                val a = input[(i - 1).coerceAtLeast(0)]; val b = input[i]
                val c = input[i + 1]; val d = input[(i + 2).coerceAtMost(input.lastIndex)]
                fun y(get: (HandleStation) -> Float) = boundedCubic(get(a), get(b), get(c), get(d), t)
                HandleStation(b.x + (c.x - b.x) * t, y { it.yUp }, y { it.yLo },
                    y { it.zHalf }, b.uNorm + (c.uNorm - b.uNorm) * t)
            }
        }

    private fun addLoftedBlade(out: MutableList<KnifeQuad>, input: Array<BladeStation>) {
        val st = refineBlade(input)
        val start = out.size
        for (i in 0 until st.size - 1) {
            val a = st[i]
            val b = st[i + 1]

            // Play-side (-Z)
            addQuadComputedNormal(
                out,
                KnifeVertex(a.x, a.ySpine, 0f, a.u, V_SPINE_0),
                KnifeVertex(b.x, b.ySpine, 0f, b.u, V_SPINE_0),
                KnifeVertex(b.x, b.yMidUp, -b.zHalf, b.u, V_SPINE_1),
                KnifeVertex(a.x, a.yMidUp, -a.zHalf, a.u, V_SPINE_1),
                preferSignZ = -1f,
            )
            addQuadComputedNormal(
                out,
                KnifeVertex(a.x, a.yMidLo, -a.zHalf, a.u, V_FULLER_0),
                KnifeVertex(b.x, b.yMidLo, -b.zHalf, b.u, V_FULLER_0),
                KnifeVertex(b.x, b.yMidUp, -b.zHalf, b.u, V_FULLER_1),
                KnifeVertex(a.x, a.yMidUp, -a.zHalf, a.u, V_FULLER_1),
                preferSignZ = -1f,
            )
            addQuadComputedNormal(
                out,
                KnifeVertex(a.x, a.yMidLo, -a.zHalf, a.u, V_EDGE_0),
                KnifeVertex(b.x, b.yMidLo, -b.zHalf, b.u, V_EDGE_0),
                KnifeVertex(b.x, b.yEdge, 0f, b.u, V_EDGE_1),
                KnifeVertex(a.x, a.yEdge, 0f, a.u, V_EDGE_1),
                preferSignZ = -1f,
            )

            // Back-side (+Z)
            addQuadComputedNormal(
                out,
                KnifeVertex(a.x, a.ySpine, 0f, a.u, V_SPINE_0),
                KnifeVertex(a.x, a.yMidUp, a.zHalf, a.u, V_SPINE_1),
                KnifeVertex(b.x, b.yMidUp, b.zHalf, b.u, V_SPINE_1),
                KnifeVertex(b.x, b.ySpine, 0f, b.u, V_SPINE_0),
                preferSignZ = 1f,
            )
            addQuadComputedNormal(
                out,
                KnifeVertex(a.x, a.yMidUp, a.zHalf, a.u, V_FULLER_0),
                KnifeVertex(a.x, a.yMidLo, a.zHalf, a.u, V_FULLER_1),
                KnifeVertex(b.x, b.yMidLo, b.zHalf, b.u, V_FULLER_1),
                KnifeVertex(b.x, b.yMidUp, b.zHalf, b.u, V_FULLER_0),
                preferSignZ = 1f,
            )
            addQuadComputedNormal(
                out,
                KnifeVertex(a.x, a.yMidLo, a.zHalf, a.u, V_EDGE_0),
                KnifeVertex(a.x, a.yEdge, 0f, a.u, V_EDGE_1),
                KnifeVertex(b.x, b.yEdge, 0f, b.u, V_EDGE_1),
                KnifeVertex(b.x, b.yMidLo, b.zHalf, b.u, V_EDGE_0),
                preferSignZ = 1f,
            )
        }
        for (i in start until out.size) {
            val q = out[i]
            out[i] = KnifeQuad(q.v0, q.v1, q.v2, q.v3, q.nx, q.ny, q.nz, isBlade = true)
        }
    }

    private fun addLoftedHandle(out: MutableList<KnifeQuad>, input: Array<HandleStation>) {
        val st = refineHandle(input)
        for (i in 0 until st.size - 1) {
            val a = st[i]
            val b = st[i + 1]
            val ua = U_HANDLE_0 + (U_HANDLE_1 - U_HANDLE_0) * a.uNorm
            val ub = U_HANDLE_0 + (U_HANDLE_1 - U_HANDLE_0) * b.uNorm

            addQuadComputedNormal(
                out,
                KnifeVertex(a.x, a.yUp, -a.zHalf, ua, V_HW_0),
                KnifeVertex(b.x, b.yUp, -b.zHalf, ub, V_HW_0),
                KnifeVertex(b.x, b.yLo, -b.zHalf, ub, V_HW_1),
                KnifeVertex(a.x, a.yLo, -a.zHalf, ua, V_HW_1),
                preferSignZ = -1f,
            )
            addQuadComputedNormal(
                out,
                KnifeVertex(a.x, a.yUp, a.zHalf, ua, V_HW_0),
                KnifeVertex(a.x, a.yLo, a.zHalf, ua, V_HW_1),
                KnifeVertex(b.x, b.yLo, b.zHalf, ub, V_HW_1),
                KnifeVertex(b.x, b.yUp, b.zHalf, ub, V_HW_0),
                preferSignZ = 1f,
            )
            addQuadExplicitNormal(
                out,
                KnifeVertex(a.x, a.yUp, -a.zHalf, ua, V_HW_0),
                KnifeVertex(a.x, a.yUp, a.zHalf, ua, V_HW_0 + 0.04f),
                KnifeVertex(b.x, b.yUp, b.zHalf, ub, V_HW_0 + 0.04f),
                KnifeVertex(b.x, b.yUp, -b.zHalf, ub, V_HW_0),
                0f, 1f, 0f,
            )
            addQuadExplicitNormal(
                out,
                KnifeVertex(a.x, a.yLo, a.zHalf, ua, V_HW_1 - 0.04f),
                KnifeVertex(a.x, a.yLo, -a.zHalf, ua, V_HW_1),
                KnifeVertex(b.x, b.yLo, -b.zHalf, ub, V_HW_1),
                KnifeVertex(b.x, b.yLo, a.zHalf, ub, V_HW_1 - 0.04f),
                0f, -1f, 0f,
            )
        }
        val first = st.first()
        val uf = U_HANDLE_0 + (U_HANDLE_1 - U_HANDLE_0) * first.uNorm
        addQuadExplicitNormal(
            out,
            KnifeVertex(first.x, first.yUp, -first.zHalf, uf, V_HW_0),
            KnifeVertex(first.x, first.yLo, -first.zHalf, uf, V_HW_1),
            KnifeVertex(first.x, first.yLo, first.zHalf, uf, V_HW_1),
            KnifeVertex(first.x, first.yUp, first.zHalf, uf, V_HW_0),
            1f, 0f, 0f,
        )
        val last = st.last()
        val ul = U_HANDLE_0 + (U_HANDLE_1 - U_HANDLE_0) * last.uNorm
        addQuadExplicitNormal(
            out,
            KnifeVertex(last.x, last.yUp, last.zHalf, ul, V_HW_0),
            KnifeVertex(last.x, last.yLo, last.zHalf, ul, V_HW_1),
            KnifeVertex(last.x, last.yLo, -last.zHalf, ul, V_HW_1),
            KnifeVertex(last.x, last.yUp, -last.zHalf, ul, V_HW_0),
            -1f, 0f, 0f,
        )
    }

    private fun addRing(
        out: MutableList<KnifeQuad>,
        cx: Float,
        cy: Float,
        rIn: Float,
        rOut: Float,
        zHalf: Float,
        segs: Int,
        vBand0: Float,
        vBand1: Float,
    ) {
        val v0 = V_HW_0 + (V_HW_1 - V_HW_0) * vBand0
        val v1 = V_HW_0 + (V_HW_1 - V_HW_0) * vBand1
        val vm = (v0 + v1) * 0.5f

        for (i in 0 until segs) {
            val t0 = i.toFloat() / segs
            val t1 = (i + 1f) / segs
            val a0 = (t0 * 2.0 * PI).toFloat()
            val a1 = (t1 * 2.0 * PI).toFloat()
            val c0 = cos(a0)
            val s0 = sin(a0)
            val c1 = cos(a1)
            val s1 = sin(a1)
            val cm = cos((a0 + a1) * 0.5f)
            val sm = sin((a0 + a1) * 0.5f)
            val u0 = U_RING_0 + (U_RING_1 - U_RING_0) * t0
            val u1 = U_RING_0 + (U_RING_1 - U_RING_0) * t1

            addQuadExplicitNormal(
                out,
                KnifeVertex(cx + rIn * c0, cy + rIn * s0, -zHalf, u0, v1),
                KnifeVertex(cx + rOut * c0, cy + rOut * s0, -zHalf, u0, v0),
                KnifeVertex(cx + rOut * c1, cy + rOut * s1, -zHalf, u1, v0),
                KnifeVertex(cx + rIn * c1, cy + rIn * s1, -zHalf, u1, v1),
                0f, 0f, -1f,
            )
            addQuadExplicitNormal(
                out,
                KnifeVertex(cx + rIn * c1, cy + rIn * s1, zHalf, u1, v1),
                KnifeVertex(cx + rOut * c1, cy + rOut * s1, zHalf, u1, v0),
                KnifeVertex(cx + rOut * c0, cy + rOut * s0, zHalf, u0, v0),
                KnifeVertex(cx + rIn * c0, cy + rIn * s0, zHalf, u0, v1),
                0f, 0f, 1f,
            )
            addQuadExplicitNormal(
                out,
                KnifeVertex(cx + rIn * c0, cy + rIn * s0, -zHalf, u0, v0),
                KnifeVertex(cx + rIn * c1, cy + rIn * s1, -zHalf, u1, v0),
                KnifeVertex(cx + rIn * c1, cy + rIn * s1, zHalf, u1, vm),
                KnifeVertex(cx + rIn * c0, cy + rIn * s0, zHalf, u0, vm),
                -cm, -sm, 0f,
            )
            addQuadExplicitNormal(
                out,
                KnifeVertex(cx + rOut * c1, cy + rOut * s1, -zHalf, u1, v0),
                KnifeVertex(cx + rOut * c0, cy + rOut * s0, -zHalf, u0, v0),
                KnifeVertex(cx + rOut * c0, cy + rOut * s0, zHalf, u0, vm),
                KnifeVertex(cx + rOut * c1, cy + rOut * s1, zHalf, u1, vm),
                cm, sm, 0f,
            )
        }
    }

    private fun addBeamSegment(
        out: MutableList<KnifeQuad>,
        x0: Float,
        y0Lo: Float,
        y0Hi: Float,
        z0: Float,
        x1: Float,
        y1Lo: Float,
        y1Hi: Float,
        z1: Float,
        u0: Float,
        u1: Float,
        v0: Float,
        v1: Float,
    ) {
        val ayTop = maxOf(y0Lo, y0Hi)
        val ayBot = minOf(y0Lo, y0Hi)
        val byTop = maxOf(y1Lo, y1Hi)
        val byBot = minOf(y1Lo, y1Hi)

        addQuadComputedNormal(
            out,
            KnifeVertex(x0, ayTop, -z0, u0, v0),
            KnifeVertex(x1, byTop, -z1, u1, v0),
            KnifeVertex(x1, byBot, -z1, u1, v1),
            KnifeVertex(x0, ayBot, -z0, u0, v1),
            preferSignZ = -1f,
        )
        addQuadComputedNormal(
            out,
            KnifeVertex(x0, ayTop, z0, u0, v0),
            KnifeVertex(x0, ayBot, z0, u0, v1),
            KnifeVertex(x1, byBot, z1, u1, v1),
            KnifeVertex(x1, byTop, z1, u1, v0),
            preferSignZ = 1f,
        )
        addQuadExplicitNormal(
            out,
            KnifeVertex(x0, ayTop, -z0, u0, v0),
            KnifeVertex(x0, ayTop, z0, u0, v1),
            KnifeVertex(x1, byTop, z1, u1, v1),
            KnifeVertex(x1, byTop, -z1, u1, v0),
            0f, 1f, 0f,
        )
        addQuadExplicitNormal(
            out,
            KnifeVertex(x0, ayBot, z0, u0, v0),
            KnifeVertex(x0, ayBot, -z0, u0, v1),
            KnifeVertex(x1, byBot, -z1, u1, v1),
            KnifeVertex(x1, byBot, z1, u1, v0),
            0f, -1f, 0f,
        )
    }

    private fun addQuadComputedNormal(
        out: MutableList<KnifeQuad>,
        v0: KnifeVertex,
        v1: KnifeVertex,
        v2: KnifeVertex,
        v3: KnifeVertex,
        preferSignZ: Float,
    ) {
        val e1x = v1.x - v0.x
        val e1y = v1.y - v0.y
        val e1z = v1.z - v0.z
        val e2x = v2.x - v0.x
        val e2y = v2.y - v0.y
        val e2z = v2.z - v0.z
        var nx = e1y * e2z - e1z * e2y
        var ny = e1z * e2x - e1x * e2z
        var nz = e1x * e2y - e1y * e2x
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-6f) {
            nx /= len
            ny /= len
            nz /= len
        } else {
            nx = 0f
            ny = 0f
            nz = preferSignZ
        }
        if (nz * preferSignZ < 0f) {
            nx = -nx
            ny = -ny
            nz = -nz
        }
        out.add(KnifeQuad(v0, v1, v2, v3, nx, ny, nz))
    }

    private fun addQuadExplicitNormal(
        out: MutableList<KnifeQuad>,
        v0: KnifeVertex,
        v1: KnifeVertex,
        v2: KnifeVertex,
        v3: KnifeVertex,
        nx: Float,
        ny: Float,
        nz: Float,
    ) {
        out.add(KnifeQuad(v0, v1, v2, v3, nx, ny, nz))
    }
}
