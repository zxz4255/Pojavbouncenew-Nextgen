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
 * Original BongoCat artwork & sprite layout adapted from open-source MIT projects:
 * - kuroni/bongocat-osu (Copyright (c) 2018 Dang Doan Duc Trung, MIT License)
 * - MMmmmoko/Bongo-Cat-Mver (Copyright (c) 2020 Han Zhang, MIT License)
 * - Externalizable/bongo.cat (Copyright (c) 2018 Eric Huber, StrayRogue & DitzyFlama, MIT License)
 */
@file:Suppress("MagicNumber")

package net.ccbluex.liquidbounce.features.module.modules.render.bongocat

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.utils.client.clientIdentifier
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.resources.Identifier

/**
 * Packed `2048x2048` native-resolution (`612x354` canvas) sprite atlas for [ModuleBongoCat].
 */
object BongoCatSprites {

    const val CANVAS_W = 612f
    const val CANVAS_H = 354f

    val ATLAS_ID: Identifier = clientIdentifier("textures/bongocat/bongocat_atlas.png")

    class Region(
        val ox: Float,
        val oy: Float,
        val w: Float,
        val h: Float,
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
    )

    private fun r(ax: Int, ay: Int, ox: Int, oy: Int, w: Int, h: Int): Region = Region(
        ox = ox.toFloat(),
        oy = oy.toFloat(),
        w = w.toFloat(),
        h = h.toFloat(),
        u0 = ax.toFloat() / 2048f,
        v0 = ay.toFloat() / 2048f,
        u1 = (ax + w).toFloat() / 2048f,
        v1 = (ay + h).toFloat() / 2048f,
    )

    fun resolveAtlasSetup(): TextureSetup? = runCatching {
        val tex = mc.textureManager.getTexture(ATLAS_ID)
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        TextureSetup.singleTexture(tex.textureView, sampler)
    }.getOrNull()

    fun draw(
        ctx: GuiGraphicsExtractor,
        setup: TextureSetup,
        region: Region,
        baseX: Float = 0f,
        baseY: Float = 0f,
        argb: Int = -1,
    ) {
        if ((argb ushr 24) == 0) return
        val x0 = baseX + region.ox
        val y0 = baseY + region.oy
        ctx.drawTexQuad(
            textureSetup = setup,
            x0 = x0,
            y0 = y0,
            x1 = x0 + region.w,
            y1 = y0 + region.h,
            u1 = region.u0,
            v1 = region.v0,
            u2 = region.u1,
            v2 = region.v1,
            argb = argb,
            pipeline = ClientRenderPipelines.GUI.TexQuadNoCull,
        )
    }

    val STD_DESK = r(4, 4, 0, 156, 612, 198)
    val STD_FG = r(620, 206, 0, 18, 612, 333)
    val STD_UP = r(1799, 1108, 378, 129, 91, 97)
    val STD_MOUSE = r(540, 1273, 18, 9, 83, 76)
    val STD_MOUSE_LEFT = r(1595, 1273, 28, 40, 54, 56)
    val STD_MOUSE_RIGHT = r(1765, 1273, 5, 19, 48, 55)
    val STD_MOUSE_SIDE = r(257, 1374, 71, 49, 29, 27)

    val STD_HANDS: Array<Region> = arrayOf(
        r(796, 1108, 390, 210, 100, 125),
        r(900, 1108, 389, 206, 99, 127),
        r(691, 1108, 382, 206, 101, 120),
        r(473, 1108, 368, 201, 107, 122),
        r(1551, 886, 342, 193, 125, 120),
        r(1406, 886, 326, 187, 141, 125),
        r(922, 886, 308, 194, 165, 116),
        r(1410, 1108, 392, 199, 94, 97),
        r(1003, 1108, 368, 196, 99, 88),
        r(1680, 886, 336, 188, 125, 91),
        r(4, 1108, 339, 187, 122, 75),
        r(1508, 1108, 392, 199, 94, 82),
        r(1209, 1108, 368, 189, 98, 81),
        r(277, 1273, 390, 198, 85, 75),
        r(4, 1273, 386, 204, 87, 85),
    )

    val STD_KEYS: Array<Region> = arrayOf(
        r(117, 1374, 436, 321, 32, 30),
        r(224, 1374, 415, 318, 29, 28),
        r(4, 1374, 386, 313, 34, 26),
        r(189, 1374, 364, 308, 31, 26),
        r(153, 1374, 338, 302, 32, 26),
        r(80, 1374, 312, 293, 33, 29),
        r(42, 1374, 286, 291, 34, 25),
        r(1868, 1273, 430, 284, 46, 45),
        r(1710, 1273, 345, 266, 51, 39),
        r(1653, 1273, 301, 257, 53, 39),
        r(1382, 1273, 294, 220, 70, 38),
        r(1968, 1273, 439, 250, 44, 39),
        r(1918, 1273, 356, 233, 46, 34),
        r(1817, 1273, 397, 241, 47, 38),
        r(1529, 1273, 380, 275, 62, 40),
    )

    val OSU_DESK = r(620, 4, 0, 156, 612, 198)
    val OSU_FG = r(1236, 206, 0, 18, 612, 336)
    val OSU_UP = r(713, 1273, 378, 129, 82, 92)
    val OSU_LEFT = r(95, 1273, 386, 203, 87, 85)
    val OSU_RIGHT = r(247, 1108, 340, 190, 109, 88)
    val OSU_WAVE = r(453, 1273, 383, 135, 83, 93)
    val OSU_MOUSE = r(584, 1108, 1, 8, 103, 60)

    val DRUM_DESK = r(1236, 4, 0, 156, 612, 198)
    val DRUM_FG = r(4, 546, 0, 18, 612, 336)
    val DRUM_LEFT_UP = r(799, 1273, 378, 129, 82, 92)
    val DRUM_RIGHT_UP = r(971, 1273, 130, 85, 82, 97)
    val DRUM_LEFT_CENTRE = r(688, 886, 263, 164, 230, 134)
    val DRUM_LEFT_RIM = r(1091, 886, 328, 164, 165, 135)
    val DRUM_RIGHT_CENTRE = r(360, 1108, 152, 103, 109, 161)
    val DRUM_RIGHT_RIM = r(1260, 886, 86, 100, 142, 160)

    val KB_DESK = r(4, 206, 0, 156, 612, 198)
    val KB_FG = r(620, 546, 0, 18, 612, 330)
    val KB_LEFT_UP = r(885, 1273, 378, 129, 82, 92)
    val KB_RIGHT_UP = r(1057, 1273, 130, 85, 82, 97)

    val KB_LEFT_HANDS: Array<Region> = arrayOf(
        r(186, 1273, 386, 203, 87, 85),
        r(1703, 1108, 382, 207, 92, 109),
        r(130, 1108, 336, 190, 113, 86),
    )

    val KB_RIGHT_HANDS: Array<Region> = arrayOf(
        r(627, 1273, 139, 143, 82, 93),
        r(366, 1273, 141, 148, 83, 93),
        r(1606, 1108, 113, 142, 93, 87),
        r(1106, 1108, 116, 147, 99, 109),
    )

    val KB_KEYS: Array<Region> = arrayOf(
        r(1311, 1108, 374, 255, 95, 45),
        r(1809, 886, 346, 290, 123, 58),
        r(1894, 1108, 268, 242, 88, 59),
        r(1306, 1273, 127, 204, 72, 44),
        r(1143, 1273, 179, 216, 79, 48),
        r(1456, 1273, 78, 192, 69, 41),
        r(1226, 1273, 88, 241, 76, 50),
    )

    val FACE_COOL = r(1236, 546, 60, 0, 466, 294)
    val FACE_BLUSH = r(356, 886, 149, 54, 328, 202)
    val FACE_SWEAT = r(4, 886, 180, 35, 348, 218)
}
