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
@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")

package net.ccbluex.liquidbounce.features.module.modules.render.fpsknife

import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.registerTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class KnifeSkin(override val tag: String) : Tagged {
    NEXUS_HOLO("NexusHolo"),
    FADE("Fade"),
    DOPPLER_SAPPHIRE("DopplerSapphire"),
    DOPPLER_RUBY("DopplerRuby"),
    GAMMA_EMERALD("GammaEmerald"),
    CASE_HARDENED("CaseHardened"),
    CRIMSON_WEB("CrimsonWeb"),
    TIGER_TOOTH("TigerTooth"),
    PRINTSTREAM("Printstream"),
    MATCH_SWORD_TIER("MatchSwordTier"),
}

enum class BladeEngraving(override val tag: String) : Tagged {
    NEXUS("Nexus"),
    STATTRAK("StatTrak"),
    CLEAN("Clean"),
}

enum class ResolvedSkinPalette(val idSlug: String) {
    NEXUS_HOLO("nexus_holo"),
    FADE("fade"),
    DOPPLER_SAPPHIRE("doppler_sapphire"),
    DOPPLER_RUBY("doppler_ruby"),
    GAMMA_EMERALD("gamma_emerald"),
    CASE_HARDENED("case_hardened"),
    CRIMSON_WEB("crimson_web"),
    TIGER_TOOTH("tiger_tooth"),
    PRINTSTREAM("printstream"),
    TIER_DIAMOND("tier_diamond"),
    TIER_NETHERITE("tier_netherite"),
    TIER_GOLD("tier_gold"),
    TIER_IRON("tier_iron"),
    TIER_COPPER("tier_copper"),
    TIER_STONE("tier_stone"),
    TIER_WOOD("tier_wood"),
}

/**
 * Procedural 512x256 UV-mapped FPS knife skin & emissive holographic accent generator.
 *
 * Atlas UV layout:
 * - v in [0.00, 0.22]: Spine / Upper Holographic Bevel (`u` = 0 ricasso -> 1 blade tip)
 * - v in [0.22, 0.52]: Recessed Fuller / Main Blade Flat (`"NEXUS"` cyber stencil & circuit telemetry)
 *   - Sub-region u in [0.70, 1.00], v in [0.00, 0.12] in emissive texture: 10-cell 7-segment StatTrak LED digits (0..9)
 * - v in [0.52, 0.76]: Razor Cutting Edge Bevel (`u` = 0 ricasso -> 1 blade tip)
 * - v in [0.76, 1.00], u in [0.00, 0.55]: Sculpted Handle Scales, Grip Serrations & Pommel Star Emblem
 * - v in [0.76, 1.00], u in [0.55, 1.00]: Anodized Finger Ring, Collar Ticks & Bolster Hardware
 */
object FpsKnifeSkins {

    private const val TEX_W = 512
    private const val TEX_H = 256

    data class SkinTexturePair(
        val baseId: Identifier,
        val emissiveId: Identifier,
        val baseTex: DynamicTexture,
        val emissiveTex: DynamicTexture,
    )

    private data class CacheKey(
        val palette: ResolvedSkinPalette,
        val engraving: BladeEngraving,
    )

    private val cache = HashMap<CacheKey, SkinTexturePair>()

    fun resolvePalette(skin: KnifeSkin, stack: ItemStack): ResolvedSkinPalette = when (skin) {
        KnifeSkin.NEXUS_HOLO -> ResolvedSkinPalette.NEXUS_HOLO
        KnifeSkin.FADE -> ResolvedSkinPalette.FADE
        KnifeSkin.DOPPLER_SAPPHIRE -> ResolvedSkinPalette.DOPPLER_SAPPHIRE
        KnifeSkin.DOPPLER_RUBY -> ResolvedSkinPalette.DOPPLER_RUBY
        KnifeSkin.GAMMA_EMERALD -> ResolvedSkinPalette.GAMMA_EMERALD
        KnifeSkin.CASE_HARDENED -> ResolvedSkinPalette.CASE_HARDENED
        KnifeSkin.CRIMSON_WEB -> ResolvedSkinPalette.CRIMSON_WEB
        KnifeSkin.TIGER_TOOTH -> ResolvedSkinPalette.TIGER_TOOTH
        KnifeSkin.PRINTSTREAM -> ResolvedSkinPalette.PRINTSTREAM
        KnifeSkin.MATCH_SWORD_TIER -> when {
            stack.`is`(Items.NETHERITE_SWORD) -> ResolvedSkinPalette.TIER_NETHERITE
            stack.`is`(Items.DIAMOND_SWORD) -> ResolvedSkinPalette.TIER_DIAMOND
            stack.`is`(Items.GOLDEN_SWORD) -> ResolvedSkinPalette.TIER_GOLD
            stack.`is`(Items.IRON_SWORD) -> ResolvedSkinPalette.TIER_IRON
            stack.`is`(Items.COPPER_SWORD) -> ResolvedSkinPalette.TIER_COPPER
            stack.`is`(Items.STONE_SWORD) -> ResolvedSkinPalette.TIER_STONE
            stack.`is`(Items.WOODEN_SWORD) -> ResolvedSkinPalette.TIER_WOOD
            else -> ResolvedSkinPalette.TIER_DIAMOND
        }
    }

    fun getOrCreate(skin: KnifeSkin, engraving: BladeEngraving, stack: ItemStack): SkinTexturePair {
        val key = CacheKey(resolvePalette(skin, stack), engraving)
        cache[key]?.let { return it }

        val baseImg = NativeImage(TEX_W, TEX_H, false)
        val emisImg = NativeImage(TEX_W, TEX_H, false)
        paintSkinAtlas(key.palette, key.engraving, baseImg, emisImg)

        val slug = "${key.palette.idSlug}_${key.engraving.tag.lowercase()}"
        val baseId = LiquidBounce.identifier("fps_knife_base_$slug")
        val emisId = LiquidBounce.identifier("fps_knife_emis_$slug")
        val baseTex = baseImg.registerTexture(baseId)
        val emisTex = emisImg.registerTexture(emisId)
        val pair = SkinTexturePair(baseId, emisId, baseTex, emisTex)
        cache[key] = pair
        return pair
    }

    fun clearCache() {
        for (pair in cache.values) {
            runCatching { mc.textureManager.release(pair.baseId) }
            runCatching { mc.textureManager.release(pair.emissiveId) }
        }
        cache.clear()
    }

    // 7-segment LED bitmask for digits 0..9: bits 0..6 = top, top-right, bottom-right, bottom, bottom-left, top-left, middle
    private val DIGIT_SEGMENTS = intArrayOf(
        0b0111111, // 0
        0b0000110, // 1
        0b1011011, // 2
        0b1001111, // 3
        0b1100110, // 4
        0b1101101, // 5
        0b1111101, // 6
        0b0000111, // 7
        0b1111111, // 8
        0b1101111, // 9
    )

    fun digitUvBounds(digit: Int): FloatArray {
        val d = digit.coerceIn(0, 9)
        val uStart = 0.70f + d * 0.03f
        return floatArrayOf(uStart, 0.005f, uStart + 0.028f, 0.115f)
    }

    private fun paintSkinAtlas(
        palette: ResolvedSkinPalette,
        engraving: BladeEngraving,
        base: NativeImage,
        emis: NativeImage,
    ) {
        val ySpineEnd = (TEX_H * 0.22f).roundToInt()
        val yFullerEnd = (TEX_H * 0.52f).roundToInt()
        val yEdgeEnd = (TEX_H * 0.76f).roundToInt()
        val xHandleEnd = (TEX_W * 0.55f).roundToInt()

        for (y in 0 until TEX_H) {
            val v = y / (TEX_H - 1f)
            for (x in 0 until TEX_W) {
                val u = x / (TEX_W - 1f)
                var baseRgb: Int
                var emisArgb = 0

                if (y < ySpineEnd) {
                    val lv = y / (ySpineEnd - 1f).coerceAtLeast(1f)
                    baseRgb = sampleSpineBevel(palette, u, lv)
                    val highlight = (1f - abs(lv - 0.35f) * 2.2f).coerceIn(0f, 1f)
                    baseRgb = blendRgb(baseRgb, 0xFFFFFF, highlight * 0.18f)
                    if (isHoloPalette(palette)) {
                        val ea = (highlight * 95f).roundToInt().coerceIn(0, 120)
                        if (ea > 4) emisArgb = ARGB.color(ea, ARGB.red(baseRgb), ARGB.green(baseRgb), ARGB.blue(baseRgb))
                    }
                } else if (y < yFullerEnd) {
                    val lv = (y - ySpineEnd) / (yFullerEnd - ySpineEnd - 1f).coerceAtLeast(1f)
                    baseRgb = sampleFuller(palette, u, lv)
                    // Subtle micro-circuit / Damascus diagonal lines inside fuller
                    val circuit = sin((u * 38f + lv * 9f) * PI.toFloat()) * sin((u * 14f - lv * 6f) * PI.toFloat())
                    if (circuit > 0.72f) {
                        val accent = fullerAccentColor(palette)
                        baseRgb = blendRgb(baseRgb, accent, (circuit - 0.72f) * 0.9f)
                        if (isHoloPalette(palette)) {
                            emisArgb = ARGB.color(58, ARGB.red(accent), ARGB.green(accent), ARGB.blue(accent))
                        }
                    }
                    // Top & bottom recessed fuller groove bevel lines
                    if (lv < 0.07f || lv > 0.93f) {
                        baseRgb = blendRgb(baseRgb, fullerAccentColor(palette), 0.45f)
                        if (isHoloPalette(palette)) {
                            val acc = fullerAccentColor(palette)
                            emisArgb = ARGB.color(92, ARGB.red(acc), ARGB.green(acc), ARGB.blue(acc))
                        }
                    }
                } else if (y < yEdgeEnd) {
                    val lv = (y - yFullerEnd) / (yEdgeEnd - yFullerEnd - 1f).coerceAtLeast(1f)
                    baseRgb = sampleCuttingEdge(palette, u, lv)
                    // Razor-honed grind striations + bright apex along lv -> 1.0
                    val grind = 0.5f + 0.5f * sin(u * 190f)
                    baseRgb = blendRgb(baseRgb, 0xFFFFFF, grind * 0.06f + (lv * lv) * 0.28f)
                    if (lv > 0.76f && isHoloPalette(palette)) {
                        val edgeGlow = ((lv - 0.76f) / 0.24f * 135f).roundToInt().coerceIn(0, 150)
                        emisArgb = ARGB.color(edgeGlow, ARGB.red(baseRgb), ARGB.green(baseRgb), ARGB.blue(baseRgb))
                    }
                } else if (x < xHandleEnd) {
                    val lu = x / (xHandleEnd - 1f).coerceAtLeast(1f)
                    val lv = (y - yEdgeEnd) / (TEX_H - yEdgeEnd - 1f).coerceAtLeast(1f)
                    baseRgb = sampleHandle(palette, lu, lv)
                } else {
                    val lu = (x - xHandleEnd) / (TEX_W - xHandleEnd - 1f).coerceAtLeast(1f)
                    val lv = (y - yEdgeEnd) / (TEX_H - yEdgeEnd - 1f).coerceAtLeast(1f)
                    baseRgb = sampleHardwareRing(palette, lu, lv)
                    if (isHoloPalette(palette) && lv in 0.36f..0.64f) {
                        val ringGlow = (1f - abs(lv - 0.5f) / 0.14f).coerceIn(0f, 1f)
                        val ea = (ringGlow * 90f).roundToInt()
                        if (ea > 4) emisArgb = ARGB.color(ea, ARGB.red(baseRgb), ARGB.green(baseRgb), ARGB.blue(baseRgb))
                    }
                }

                base.setPixel(x, y, ARGB.color(255, ARGB.red(baseRgb), ARGB.green(baseRgb), ARGB.blue(baseRgb)))
                emis.setPixel(x, y, emisArgb)
            }
        }

        // Paint crisp vector-stencil "NEXUS" lettering along the fuller (matching reference screenshot!)
        if (engraving != BladeEngraving.CLEAN) {
            paintNexusStencil(palette, engraving, base, emis, ySpineEnd, yFullerEnd)
        }

        // Paint 6-point cyber star emblem + grip serrations on handle zone
        paintHandleDetails(palette, base, emis, xHandleEnd, yEdgeEnd)

        // Paint 7-segment LED digits (0..9) into emissive sub-atlas u=[0.70..1.00], v=[0.00..0.12]
        paintStatTrakDigitAtlas(palette, emis)
    }

    private fun isHoloPalette(p: ResolvedSkinPalette): Boolean = when (p) {
        ResolvedSkinPalette.NEXUS_HOLO,
        ResolvedSkinPalette.FADE,
        ResolvedSkinPalette.DOPPLER_SAPPHIRE,
        ResolvedSkinPalette.DOPPLER_RUBY,
        ResolvedSkinPalette.GAMMA_EMERALD,
        ResolvedSkinPalette.PRINTSTREAM,
        ResolvedSkinPalette.TIER_DIAMOND,
        ResolvedSkinPalette.TIER_NETHERITE -> true
        else -> false
    }

    private fun fullerAccentColor(p: ResolvedSkinPalette): Int = when (p) {
        ResolvedSkinPalette.NEXUS_HOLO -> 0x38D6FF
        ResolvedSkinPalette.FADE -> 0xFF56B8
        ResolvedSkinPalette.DOPPLER_SAPPHIRE -> 0x45A8FF
        ResolvedSkinPalette.DOPPLER_RUBY -> 0xFF4268
        ResolvedSkinPalette.GAMMA_EMERALD -> 0x36FF9E
        ResolvedSkinPalette.CASE_HARDENED -> 0x48B5FF
        ResolvedSkinPalette.CRIMSON_WEB -> 0xFF283C
        ResolvedSkinPalette.TIGER_TOOTH -> 0xFFD042
        ResolvedSkinPalette.PRINTSTREAM -> 0xDFEAFF
        ResolvedSkinPalette.TIER_DIAMOND -> 0x4BF2FF
        ResolvedSkinPalette.TIER_NETHERITE -> 0xD88CFF
        ResolvedSkinPalette.TIER_GOLD -> 0xFFE45E
        ResolvedSkinPalette.TIER_IRON -> 0xE2ECFF
        ResolvedSkinPalette.TIER_COPPER -> 0xFF9666
        ResolvedSkinPalette.TIER_STONE -> 0xAEB8C4
        ResolvedSkinPalette.TIER_WOOD -> 0xD8A86C
    }

    private fun sampleSpineBevel(p: ResolvedSkinPalette, u: Float, lv: Float): Int = when (p) {
        ResolvedSkinPalette.NEXUS_HOLO -> {
            // Exact reference gradient: warm silver-peach at bolster -> magenta-violet mid -> royal electric blue-cyan at tip
            when {
                u < 0.34f -> lerpRgb(0xE8C8BA, 0xB86BFF, u / 0.34f)
                u < 0.72f -> lerpRgb(0xB86BFF, 0x5E46FF, (u - 0.34f) / 0.38f)
                else -> lerpRgb(0x5E46FF, 0x22B8FF, (u - 0.72f) / 0.28f)
            }
        }
        ResolvedSkinPalette.FADE -> {
            when {
                u < 0.35f -> lerpRgb(0xFFC436, 0xFF4F8B, u / 0.35f)
                u < 0.72f -> lerpRgb(0xFF4F8B, 0x9B38FF, (u - 0.35f) / 0.37f)
                else -> lerpRgb(0x9B38FF, 0x4A2BFF, (u - 0.72f) / 0.28f)
            }
        }
        ResolvedSkinPalette.DOPPLER_SAPPHIRE -> sampleDoppler(u, lv, 0x091A5C, 0x1B66FF, 0x62DAFF)
        ResolvedSkinPalette.DOPPLER_RUBY -> sampleDoppler(u, lv, 0x4A0514, 0xD8143A, 0xFF6B84)
        ResolvedSkinPalette.GAMMA_EMERALD -> sampleDoppler(u, lv, 0x053B22, 0x0FD46E, 0x7EFFBE)
        ResolvedSkinPalette.CASE_HARDENED -> sampleCaseHardened(u, lv)
        ResolvedSkinPalette.CRIMSON_WEB -> sampleCrimsonWeb(u, lv * 0.35f, 0x1E2026)
        ResolvedSkinPalette.TIGER_TOOTH -> sampleTigerTooth(u, lv)
        ResolvedSkinPalette.PRINTSTREAM -> {
            val pearl = 0.5f + 0.5f * sin((u * 5.2f + lv * 2.1f) * PI.toFloat())
            lerpRgb(0xEEF3FA, lerpRgb(0xFFD9F2, 0xD4F6FF, u), pearl * 0.45f)
        }
        ResolvedSkinPalette.TIER_DIAMOND -> lerpRgb(0x88FFF6, 0x1FC8E8, u * 0.7f + lv * 0.3f)
        ResolvedSkinPalette.TIER_NETHERITE -> lerpRgb(0x5A4E5E, 0x9C6BFF, u * 0.55f)
        ResolvedSkinPalette.TIER_GOLD -> lerpRgb(0xFFF078, 0xE69C18, u * 0.7f)
        ResolvedSkinPalette.TIER_IRON -> lerpRgb(0xF0F4FA, 0x9CA8B8, u * 0.6f)
        ResolvedSkinPalette.TIER_COPPER -> lerpRgb(0xFFB085, 0xC8643B, u * 0.65f)
        ResolvedSkinPalette.TIER_STONE -> lerpRgb(0x9EA4AE, 0x676D76, u * 0.65f)
        ResolvedSkinPalette.TIER_WOOD -> lerpRgb(0xB88954, 0x7A542E, u * 0.65f)
    }

    private fun sampleFuller(p: ResolvedSkinPalette, u: Float, lv: Float): Int = when (p) {
        ResolvedSkinPalette.NEXUS_HOLO -> {
            val n = fbm2(u * 14f, lv * 6f)
            lerpRgb(0x0D1426, 0x1D2E54, n)
        }
        ResolvedSkinPalette.FADE -> lerpRgb(0x181622, 0x2A223B, fbm2(u * 10f, lv * 5f))
        ResolvedSkinPalette.DOPPLER_SAPPHIRE -> sampleDoppler(u * 1.2f, lv, 0x060E36, 0x103EA8, 0x2FA4FF)
        ResolvedSkinPalette.DOPPLER_RUBY -> sampleDoppler(u * 1.2f, lv, 0x2B030C, 0x960D28, 0xEB3B5A)
        ResolvedSkinPalette.GAMMA_EMERALD -> sampleDoppler(u * 1.2f, lv, 0x032615, 0x0A8F49, 0x35EB8E)
        ResolvedSkinPalette.CASE_HARDENED -> blendRgb(sampleCaseHardened(u + 0.12f, lv), 0x141C2B, 0.25f)
        ResolvedSkinPalette.CRIMSON_WEB -> sampleCrimsonWeb(u, 0.35f + lv * 0.35f, 0x9E0E1E)
        ResolvedSkinPalette.TIGER_TOOTH -> blendRgb(sampleTigerTooth(u, lv + 0.3f), 0x241404, 0.22f)
        ResolvedSkinPalette.PRINTSTREAM -> 0x17191F
        ResolvedSkinPalette.TIER_DIAMOND -> lerpRgb(0x0F3B4C, 0x1C748C, fbm2(u * 12f, lv * 5f))
        ResolvedSkinPalette.TIER_NETHERITE -> lerpRgb(0x1F1A22, 0x3A2E3F, fbm2(u * 12f, lv * 5f))
        ResolvedSkinPalette.TIER_GOLD -> lerpRgb(0x523608, 0x8C6214, fbm2(u * 10f, lv * 5f))
        ResolvedSkinPalette.TIER_IRON -> lerpRgb(0x464E59, 0x6D7887, fbm2(u * 10f, lv * 5f))
        ResolvedSkinPalette.TIER_COPPER -> lerpRgb(0x4A281A, 0x2E7A68, u * 0.55f + 0.25f * fbm2(u * 9f, lv * 4f))
        ResolvedSkinPalette.TIER_STONE -> lerpRgb(0x454950, 0x5E646E, fbm2(u * 12f, lv * 5f))
        ResolvedSkinPalette.TIER_WOOD -> lerpRgb(0x54381E, 0x734E2B, sin(u * 42f + lv * 3f) * 0.5f + 0.5f)
    }

    private fun sampleCuttingEdge(p: ResolvedSkinPalette, u: Float, lv: Float): Int = when (p) {
        ResolvedSkinPalette.NEXUS_HOLO -> {
            when {
                u < 0.28f -> lerpRgb(0xD8C6BC, 0x28B4FF, u / 0.28f)
                else -> lerpRgb(0x28B4FF, 0x52E2FF, ((u - 0.28f) / 0.72f) * 0.7f + lv * 0.3f)
            }
        }
        ResolvedSkinPalette.FADE -> sampleSpineBevel(p, (u * 1.05f).coerceAtMost(1f), lv)
        ResolvedSkinPalette.DOPPLER_SAPPHIRE -> sampleDoppler(u + 0.2f, lv, 0x103494, 0x2B82FF, 0x8CEBFF)
        ResolvedSkinPalette.DOPPLER_RUBY -> sampleDoppler(u + 0.2f, lv, 0x6E091D, 0xF02249, 0xFF94A6)
        ResolvedSkinPalette.GAMMA_EMERALD -> sampleDoppler(u + 0.2f, lv, 0x085932, 0x18EB7E, 0xA6FFD2)
        ResolvedSkinPalette.CASE_HARDENED -> sampleCaseHardened(u + 0.05f, lv + 0.4f)
        ResolvedSkinPalette.CRIMSON_WEB -> sampleCrimsonWeb(u, 0.70f + lv * 0.30f, 0xC41226)
        ResolvedSkinPalette.TIGER_TOOTH -> sampleTigerTooth(u + 0.08f, lv + 0.5f)
        ResolvedSkinPalette.PRINTSTREAM -> lerpRgb(0xE8EEF7, 0xFFFFFF, lv)
        else -> blendRgb(sampleSpineBevel(p, u, lv), 0xFFFFFF, 0.24f)
    }

    private fun sampleHandle(p: ResolvedSkinPalette, lu: Float, lv: Float): Int {
        // Carbon-fiber / tactical texture modulation
        val weave = ((floor(lu * 48f).toInt() + floor(lv * 20f).toInt()) and 1) * 0.06f
        val base = when (p) {
            ResolvedSkinPalette.NEXUS_HOLO -> lerpRgb(0x1D78D2, 0x289CEB, (1f - abs(lv - 0.45f)) * 0.75f)
            ResolvedSkinPalette.FADE,
            ResolvedSkinPalette.DOPPLER_SAPPHIRE,
            ResolvedSkinPalette.DOPPLER_RUBY,
            ResolvedSkinPalette.GAMMA_EMERALD,
            ResolvedSkinPalette.TIGER_TOOTH -> lerpRgb(0x1B1E26, 0x2C313D, weave * 6f)
            ResolvedSkinPalette.CASE_HARDENED -> lerpRgb(0x3B4252, 0x5B667D, fbm2(lu * 9f, lv * 9f))
            ResolvedSkinPalette.CRIMSON_WEB -> lerpRgb(0x16181D, 0x28181C, weave * 5f)
            ResolvedSkinPalette.PRINTSTREAM -> if (lu in 0.32f..0.38f) 0xF0F4FA else lerpRgb(0x17191F, 0x252830, weave * 5f)
            ResolvedSkinPalette.TIER_DIAMOND -> lerpRgb(0x163844, 0x225B6E, weave * 5f)
            ResolvedSkinPalette.TIER_NETHERITE -> lerpRgb(0x231E26, 0x3B303E, weave * 5f)
            ResolvedSkinPalette.TIER_GOLD -> lerpRgb(0x3D2912, 0x6B471E, weave * 5f)
            else -> lerpRgb(0x3A2A1D, 0x543D2B, weave * 5f)
        }
        // Subtle grip bevel edges
        val edgeShade = (1f - abs(lv - 0.5f) * 1.9f).coerceIn(0f, 1f)
        return blendRgb(0x0E1320, base, 0.48f + 0.52f * edgeShade)
    }

    private fun sampleHardwareRing(p: ResolvedSkinPalette, lu: Float, lv: Float): Int {
        // Outer silver-titanium collar with radial tick marks + inner electric-anodized ring wall
        val collarBand = lv < 0.24f || lv > 0.76f
        return if (collarBand) {
            val tick = sin(lu * PI.toFloat() * 44f) > 0.55f && lu in 0.15f..0.45f
            val metal = when (p) {
                ResolvedSkinPalette.NEXUS_HOLO, ResolvedSkinPalette.PRINTSTREAM -> 0xDCE6F5
                ResolvedSkinPalette.TIGER_TOOTH, ResolvedSkinPalette.TIER_GOLD -> 0xF5C84C
                else -> 0x8F9BB0
            }
            if (tick) blendRgb(metal, 0x141A29, 0.72f) else metal
        } else {
            val groove = 0.5f + 0.5f * sin(lv * PI.toFloat() * 10f)
            val ringCol = when (p) {
                ResolvedSkinPalette.NEXUS_HOLO -> lerpRgb(0x1478D4, 0x33C4FF, groove)
                ResolvedSkinPalette.FADE -> lerpRgb(0xB838FF, 0xFF629E, lu)
                ResolvedSkinPalette.DOPPLER_SAPPHIRE -> lerpRgb(0x1248C4, 0x48B8FF, groove)
                ResolvedSkinPalette.DOPPLER_RUBY -> lerpRgb(0x8F0D26, 0xFF3D64, groove)
                ResolvedSkinPalette.GAMMA_EMERALD -> lerpRgb(0x0A7A3E, 0x2EFF98, groove)
                ResolvedSkinPalette.CRIMSON_WEB -> lerpRgb(0x7A0B18, 0xD9162B, groove)
                ResolvedSkinPalette.TIGER_TOOTH -> lerpRgb(0xC97B08, 0xFFC838, groove)
                ResolvedSkinPalette.PRINTSTREAM -> lerpRgb(0xDCE4F2, 0xFFFFFF, groove)
                else -> fullerAccentColor(p)
            }
            ringCol
        }
    }

    private fun sampleDoppler(u: Float, v: Float, dark: Int, mid: Int, bright: Int): Int {
        val swirl = fbm2(u * 6.5f + sin(v * 4.2f) * 0.8f, v * 5.5f + cos(u * 5.0f) * 0.8f)
        val vein = (1f - abs(sin((u * 7.5f + swirl * 2.6f) * PI.toFloat()))).coerceIn(0f, 1f)
        val base = lerpRgb(dark, mid, swirl)
        return lerpRgb(base, bright, (vein * vein * 0.85f).coerceIn(0f, 1f))
    }

    private fun sampleCaseHardened(u: Float, v: Float): Int {
        // Pattern #387 "Blue Gem": sweeping ocean-blue quench zone with violet-gold temper border
        val d = hypot((u - 0.58f) * 1.15f, (v - 0.45f) * 0.85f) + fbm2(u * 8f, v * 6f) * 0.22f
        return when {
            d < 0.58f -> lerpRgb(0x36B6FF, 0x1D7AE6, (d / 0.58f).coerceIn(0f, 1f))
            d < 0.72f -> lerpRgb(0x7D42E6, 0xD99B26, ((d - 0.58f) / 0.14f).coerceIn(0f, 1f))
            else -> lerpRgb(0xC8A25A, 0x8F8A82, ((d - 0.72f) / 0.35f).coerceIn(0f, 1f))
        }
    }

    private fun sampleCrimsonWeb(u: Float, v: Float, crimsonBase: Int): Int {
        val cx = 0.36f
        val cy = 0.48f
        val dx = (u - cx) * 1.8f
        val dy = v - cy
        val r = sqrt(dx * dx + dy * dy)
        val ang = atan2(dy, dx)
        val spoke = abs(sin(ang * 7f))
        val ring = abs(sin(r * 26f + spoke * 0.55f))
        val onWeb = (spoke < 0.055f / (r + 0.18f).coerceAtMost(1.2f)) || (ring < 0.085f)
        return if (onWeb) blendRgb(crimsonBase, 0x0B0C10, 0.84f) else crimsonBase
    }

    private fun sampleTigerTooth(u: Float, v: Float): Int {
        val gold = lerpRgb(0xFF9800, 0xFFD238, 0.5f + 0.5f * sin(u * 8f))
        val wave = sin((u * 19f + sin(v * 5.5f + u * 4f) * 1.1f) * PI.toFloat())
        return if (wave > 0.52f && (v < 0.78f || sin(u * 31f) > 0f)) {
            val t = ((wave - 0.52f) / 0.22f).coerceIn(0f, 1f)
            lerpRgb(gold, 0x211104, t * 0.90f)
        } else {
            gold
        }
    }

    /**
     * Vector-stroke futuristic stencil font for `"NEXUS"` along the recessed fuller,
     * matching `/home/user/uploads/Screenshot_20261124_232425.jpg`.
     */
    private fun paintNexusStencil(
        palette: ResolvedSkinPalette,
        engraving: BladeEngraving,
        base: NativeImage,
        emis: NativeImage,
        ySpineEnd: Int,
        yFullerEnd: Int,
    ) {
        val fullerH = yFullerEnd - ySpineEnd
        val textTop = ySpineEnd + (fullerH * 0.24f).roundToInt()
        val textBot = ySpineEnd + (fullerH * 0.76f).roundToInt()
        val glyphH = (textBot - textTop).coerceAtLeast(8)
        val glyphW = (glyphH * 0.88f).roundToInt().coerceAtLeast(8)
        val gap = (glyphW * 0.28f).roundToInt().coerceAtLeast(3)
        val startX = (TEX_W * 0.14f).roundToInt()
        val stroke = 2.1f

        val textRgb = when (palette) {
            ResolvedSkinPalette.PRINTSTREAM -> 0xF5F8FF
            ResolvedSkinPalette.TIGER_TOOTH -> 0xFFE680
            ResolvedSkinPalette.CRIMSON_WEB -> 0xFFD6DC
            else -> fullerAccentColor(palette)
        }
        val brightTextRgb = blendRgb(textRgb, 0xFFFFFF, 0.45f)

        val letters = charArrayOf('N', 'E', 'X', 'U', 'S')
        letters.forEachIndexed { idx, ch ->
            val gx0 = startX + idx * (glyphW + gap)
            drawVectorGlyph(ch, gx0.toFloat(), textTop.toFloat(), glyphW.toFloat(), glyphH.toFloat(), stroke) { px, py, cov ->
                val cur = base.getPixel(px, py)
                val curRgb = cur and 0xFFFFFF
                val mixed = blendRgb(curRgb, brightTextRgb, cov * 0.92f)
                base.setPixel(px, py, ARGB.color(255, ARGB.red(mixed), ARGB.green(mixed), ARGB.blue(mixed)))
                val ea = (cov * 235f).roundToInt().coerceIn(0, 255)
                emis.setPixel(px, py, ARGB.color(ea, ARGB.red(brightTextRgb), ARGB.green(brightTextRgb), ARGB.blue(brightTextRgb)))
            }
        }

        // Futuristic telemetry leader bar ahead of "NEXUS"
        val barX0 = startX + letters.size * (glyphW + gap) + 6
        val barX1 = (barX0 + 38).coerceAtMost(TEX_W - 8)
        val midY = (textTop + textBot) / 2
        for (x in barX0..barX1) {
            for (dy in -1..1) {
                val a = if (dy == 0) 0.78f else 0.32f
                val py = (midY + dy).coerceIn(0, TEX_H - 1)
                val mixed = blendRgb(base.getPixel(x, py) and 0xFFFFFF, textRgb, a)
                base.setPixel(x, py, ARGB.color(255, ARGB.red(mixed), ARGB.green(mixed), ARGB.blue(mixed)))
                emis.setPixel(x, py, ARGB.color((a * 190f).roundToInt(), ARGB.red(textRgb), ARGB.green(textRgb), ARGB.blue(textRgb)))
            }
        }

        if (engraving == BladeEngraving.STATTRAK) {
            // Subtle dark bezel frame right beside the telemetry bar where the 3D StatTrak LED counter mounts
            val stX0 = (barX1 + 8).coerceAtMost(TEX_W - 60)
            val stX1 = (stX0 + 48).coerceAtMost(TEX_W - 6)
            for (y in (textTop - 1)..(textBot + 1)) {
                for (x in stX0..stX1) {
                    val border = x == stX0 || x == stX1 || y == textTop - 1 || y == textBot + 1
                    val col = if (border) 0xFF7A1A else 0x0A0B0F
                    base.setPixel(x, y, ARGB.color(255, ARGB.red(col), ARGB.green(col), ARGB.blue(col)))
                }
            }
        }
    }

    private fun paintHandleDetails(
        palette: ResolvedSkinPalette,
        base: NativeImage,
        emis: NativeImage,
        xHandleEnd: Int,
        yEdgeEnd: Int,
    ) {
        val hHeight = TEX_H - yEdgeEnd
        // 1. Diagonal pommel spine serrations (matching bottom-right of reference screenshot)
        for (i in 0 until 5) {
            val sx = (xHandleEnd * (0.72f + i * 0.042f)).roundToInt()
            drawSegment(
                sx.toFloat(), (yEdgeEnd + 3).toFloat(),
                (sx + 8).toFloat(), (yEdgeEnd + hHeight * 0.42f),
                1.6f,
            ) { px, py, cov ->
                if (px in 0 until xHandleEnd && py in yEdgeEnd until TEX_H) {
                    val mixed = blendRgb(base.getPixel(px, py) and 0xFFFFFF, 0x091122, cov * 0.82f)
                    base.setPixel(px, py, ARGB.color(255, ARGB.red(mixed), ARGB.green(mixed), ARGB.blue(mixed)))
                }
            }
        }

        // 2. Hex pivot fastener mid-handle
        val fx = xHandleEnd * 0.48f
        val fy = yEdgeEnd + hHeight * 0.56f
        for (py in (fy - 8).toInt()..(fy + 8).toInt()) {
            for (px in (fx - 8).toInt()..(fx + 8).toInt()) {
                if (px !in 0 until xHandleEnd || py !in yEdgeEnd until TEX_H) continue
                val d = hypot(px - fx, py - fy)
                if (d <= 6.2f) {
                    val col = if (d < 3.0f) 0x0D1320 else 0xCFD9EA
                    base.setPixel(px, py, ARGB.color(255, ARGB.red(col), ARGB.green(col), ARGB.blue(col)))
                }
            }
        }

        // 3. 6-point cyber star emblem near the pommel butt (matching reference screenshot!)
        val cx = xHandleEnd * 0.84f
        val cy = yEdgeEnd + hHeight * 0.72f
        val starCol = blendRgb(fullerAccentColor(palette), 0xFFFFFF, 0.5f)
        for (arm in 0 until 3) {
            val ang = arm * (PI / 3.0)
            val r = 7.5f
            val x0 = cx - cos(ang).toFloat() * r
            val y0 = cy - sin(ang).toFloat() * r
            val x1 = cx + cos(ang).toFloat() * r
            val y1 = cy + sin(ang).toFloat() * r
            drawSegment(x0, y0, x1, y1, 1.15f) { px, py, cov ->
                if (px in 0 until xHandleEnd && py in yEdgeEnd until TEX_H) {
                    val mixed = blendRgb(base.getPixel(px, py) and 0xFFFFFF, starCol, cov * 0.88f)
                    base.setPixel(px, py, ARGB.color(255, ARGB.red(mixed), ARGB.green(mixed), ARGB.blue(mixed)))
                    emis.setPixel(px, py, ARGB.color((cov * 150f).roundToInt(), ARGB.red(starCol), ARGB.green(starCol), ARGB.blue(starCol)))
                }
            }
        }
    }

    private fun paintStatTrakDigitAtlas(palette: ResolvedSkinPalette, emis: NativeImage) {
        // Glowing CS2 StatTrak amber-orange (or cyan on Printstream) 7-segment LED digits in u=[0.70..1.00], v=[0.00..0.12]
        val ledRgb = if (palette == ResolvedSkinPalette.PRINTSTREAM) 0x38E2FF else 0xFF7B1A
        val brightRgb = blendRgb(ledRgb, 0xFFFFFF, 0.42f)
        val y0 = (TEX_H * 0.015f).roundToInt()
        val y1 = (TEX_H * 0.105f).roundToInt()
        val ym = (y0 + y1) / 2

        for (d in 0..9) {
            val bounds = digitUvBounds(d)
            val x0 = (bounds[0] * TEX_W).roundToInt() + 2
            val x1 = (bounds[2] * TEX_W).roundToInt() - 2
            // Clear cell first so digits have clean alpha cutout
            for (py in 0..(TEX_H * 0.125f).roundToInt()) {
                for (px in (bounds[0] * TEX_W).roundToInt()..(bounds[2] * TEX_W).roundToInt().coerceAtMost(TEX_W - 1)) {
                    emis.setPixel(px, py, 0)
                }
            }
            val mask = DIGIT_SEGMENTS[d]
            fun seg(bit: Int, sx0: Int, sy0: Int, sx1: Int, sy1: Int) {
                if ((mask and (1 shl bit)) == 0) return
                drawSegment(sx0.toFloat(), sy0.toFloat(), sx1.toFloat(), sy1.toFloat(), 1.35f) { px, py, cov ->
                    val a = (cov * 250f).roundToInt().coerceIn(0, 255)
                    val prevA = ARGB.alpha(emis.getPixel(px, py))
                    if (a > prevA) {
                        emis.setPixel(px, py, ARGB.color(a, ARGB.red(brightRgb), ARGB.green(brightRgb), ARGB.blue(brightRgb)))
                    }
                }
            }
            seg(0, x0 + 1, y0, x1 - 1, y0)       // top
            seg(1, x1, y0 + 1, x1, ym - 1)       // top-right
            seg(2, x1, ym + 1, x1, y1 - 1)       // bottom-right
            seg(3, x0 + 1, y1, x1 - 1, y1)       // bottom
            seg(4, x0, ym + 1, x0, y1 - 1)       // bottom-left
            seg(5, x0, y0 + 1, x0, ym - 1)       // top-left
            seg(6, x0 + 1, ym, x1 - 1, ym)       // middle
        }
    }

    private fun drawVectorGlyph(
        ch: Char,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        stroke: Float,
        plot: (Int, Int, Float) -> Unit,
    ) {
        val x0 = x
        val x1 = x + w
        val y0 = y
        val y1 = y + h
        val ym = y + h * 0.5f
        when (ch) {
            'N' -> {
                drawSegment(x0, y1, x0, y0, stroke, plot)
                drawSegment(x0, y0, x1, y1, stroke, plot)
                drawSegment(x1, y1, x1, y0, stroke, plot)
            }
            'E' -> {
                drawSegment(x0, y0, x0, y1, stroke, plot)
                drawSegment(x0, y0, x1, y0, stroke, plot)
                drawSegment(x0, ym, x0 + w * 0.82f, ym, stroke, plot)
                drawSegment(x0, y1, x1, y1, stroke, plot)
            }
            'X' -> {
                drawSegment(x0, y0, x1, y1, stroke, plot)
                drawSegment(x1, y0, x0, y1, stroke, plot)
            }
            'U' -> {
                drawSegment(x0, y0, x0, y1 - h * 0.22f, stroke, plot)
                drawSegment(x0, y1 - h * 0.22f, x0 + w * 0.25f, y1, stroke, plot)
                drawSegment(x0 + w * 0.25f, y1, x1 - w * 0.25f, y1, stroke, plot)
                drawSegment(x1 - w * 0.25f, y1, x1, y1 - h * 0.22f, stroke, plot)
                drawSegment(x1, y1 - h * 0.22f, x1, y0, stroke, plot)
            }
            'S' -> {
                drawSegment(x1, y0, x0 + w * 0.2f, y0, stroke, plot)
                drawSegment(x0 + w * 0.2f, y0, x0, y0 + h * 0.22f, stroke, plot)
                drawSegment(x0, y0 + h * 0.22f, x0 + w * 0.2f, ym, stroke, plot)
                drawSegment(x0 + w * 0.2f, ym, x1 - w * 0.2f, ym, stroke, plot)
                drawSegment(x1 - w * 0.2f, ym, x1, y1 - h * 0.22f, stroke, plot)
                drawSegment(x1, y1 - h * 0.22f, x1 - w * 0.2f, y1, stroke, plot)
                drawSegment(x1 - w * 0.2f, y1, x0, y1, stroke, plot)
            }
        }
    }

    private fun drawSegment(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radius: Float,
        plot: (Int, Int, Float) -> Unit,
    ) {
        val minX = floor(minOf(x0, x1) - radius - 1f).toInt().coerceAtLeast(0)
        val maxX = floor(maxOf(x0, x1) + radius + 1f).toInt().coerceAtMost(TEX_W - 1)
        val minY = floor(minOf(y0, y1) - radius - 1f).toInt().coerceAtLeast(0)
        val maxY = floor(maxOf(y0, y1) + radius + 1f).toInt().coerceAtMost(TEX_H - 1)
        val dx = x1 - x0
        val dy = y1 - y0
        val lenSq = (dx * dx + dy * dy).coerceAtLeast(1e-5f)

        for (py in minY..maxY) {
            for (px in minX..maxX) {
                val t = (((px - x0) * dx + (py - y0) * dy) / lenSq).coerceIn(0f, 1f)
                val dist = hypot(px - (x0 + t * dx), py - (y0 + t * dy))
                val cov = (radius + 0.5f - dist).coerceIn(0f, 1f)
                if (cov > 0.01f) plot(px, py, cov)
            }
        }
    }

    private fun hash2(ix: Int, iy: Int): Float {
        var n = ix * 374761393 + iy * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) and 0x7FFFFFFF) / 2147483647f
    }

    private fun valueNoise2(x: Float, y: Float): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = hash2(ix, iy)
        val b = hash2(ix + 1, iy)
        val c = hash2(ix, iy + 1)
        val d = hash2(ix + 1, iy + 1)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    private fun fbm2(x: Float, y: Float): Float {
        var sum = 0f
        var amp = 0.5f
        var fx = x
        var fy = y
        repeat(4) {
            sum += amp * valueNoise2(fx, fy)
            fx *= 2.03f
            fy *= 2.03f
            amp *= 0.5f
        }
        return sum.coerceIn(0f, 1f)
    }

    private fun lerpRgb(c0: Int, c1: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = (ARGB.red(c0) + (ARGB.red(c1) - ARGB.red(c0)) * k).roundToInt().coerceIn(0, 255)
        val g = (ARGB.green(c0) + (ARGB.green(c1) - ARGB.green(c0)) * k).roundToInt().coerceIn(0, 255)
        val b = (ARGB.blue(c0) + (ARGB.blue(c1) - ARGB.blue(c0)) * k).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun blendRgb(dst: Int, src: Int, alpha: Float): Int = lerpRgb(dst, src, alpha)
}
