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
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
@file:Suppress(
    "TooManyFunctions",
    "LongParameterList",
    "CognitiveComplexMethod",
    "ReturnCount",
    "ComplexCondition",
    "NamedArguments",
    "BracesOnIfStatements",
    "NestedBlockDepth",
    "UnusedParameter",
    "MaxLineLength",
)

package net.ccbluex.liquidbounce.features.module.modules.render.moderngui

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.ARGB
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.MapColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class MonetSource(override val tag: String) : Tagged {
    WORLD_HYBRID("WorldHybrid"),
    SKY_AND_BIOME("SkyAndBiome"),
    SURROUNDING_BLOCKS("Blocks"),
    HELD_ITEM("HeldItem"),
    AURORA_CYCLE("AuroraCycle"),
    PRESET_SEED("Preset"),
    CUSTOM_SEED("Custom"),
}

enum class MonetVariant(override val tag: String) : Tagged {
    TONAL_SPOT("TonalSpot"),
    VIBRANT("Vibrant"),
    EXPRESSIVE("Expressive"),
    NEUTRAL("Neutral"),
}

enum class MonetBrightness(override val tag: String) : Tagged {
    DARK("Dark"),
    LIGHT("Light"),
    AUTO_DAYLIGHT("AutoDaylight"),
}

enum class MonetPreset(override val tag: String, val seedArgb: Int) : Tagged {
    SAKURA("Sakura", 0xFFF48FB1.toInt()),
    CERULEAN("Cerulean", 0xFF569CD6.toInt()),
    EMERALD("Emerald", 0xFF4CAF82.toInt()),
    AMETHYST("Amethyst", 0xFF9575CD.toInt()),
    AMBER("Amber", 0xFFFFB74D.toInt()),
    CORAL("Coral", 0xFFFF7961.toInt()),
    GLACIER("Glacier", 0xFF64D2FF.toInt()),
    MOCHA("Mocha", 0xFFA1887F.toInt()),
}

/**
 * Resolved Material You / Monet tonal colour roles for all in-game rounded cards & glass surfaces.
 */
data class MonetPalette(
    val seed: Int,
    val isLight: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val secondaryContainer: Int,
    val tertiary: Int,
    val tertiaryContainer: Int,
    val surfaceLowest: Int,
    val surfaceLow: Int,
    val surface: Int,
    val surfaceHigh: Int,
    val surfaceHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val scrim: Int,
)

/**
 * Live zero-GPU-stall Monet colour extractor + CAM16 HCT Material 3 tonal palette synthesizer.
 *
 * Samples surrounding blocks, camera look-ray blocks, biome foliage/water tints, atmospheric sky
 * phase, or held items into an 18-bin chroma-weighted hue histogram (modelled after Android's
 * `QuantizerCelebi` + `Score`), smoothly interpolates the winning seed in perceptual space, and
 * caches the resulting HCT tonal scheme.
 */
object MonetColorEngine {

    private const val HUE_BINS = 18
    private const val BIN_DEGREES = 360.0 / HUE_BINS

    private val binWeight = DoubleArray(HUE_BINS)
    private val binR = DoubleArray(HUE_BINS)
    private val binG = DoubleArray(HUE_BINS)
    private val binB = DoubleArray(HUE_BINS)
    private val samplePos = BlockPos.MutableBlockPos()

    private var targetSeedArgb: Int = 0xFF6789FF.toInt()
    private var smoothedR: Float = 103f
    private var smoothedG: Float = 137f
    private var smoothedB: Float = 255f
    private var smoothedLightMix: Float = 0f

    private var lastSampleTick: Long = -999L
    private var lastFrameNanos: Long = 0L

    private data class CacheKey(
        val seedRgbQuantized: Int,
        val variant: MonetVariant,
        val isLight: Boolean,
        val tintQ: Int,
        val chromaQ: Int,
        val contrastQ: Int,
    )

    private var cachedKey: CacheKey? = null
    private var cachedPalette: MonetPalette? = null

    fun currentSeedColor4b(): Color4b = Color4b(
        smoothedR.roundToInt().coerceIn(0, 255),
        smoothedG.roundToInt().coerceIn(0, 255),
        smoothedB.roundToInt().coerceIn(0, 255),
        255,
    )

    /**
     * Advances live sampling & temporal colour smoothing once per GUI frame and returns the active [MonetPalette].
     */
    fun updateAndGetPalette(
        source: MonetSource,
        variant: MonetVariant,
        brightness: MonetBrightness,
        surfaceTint: Float,
        chromaBoost: Float,
        contrast: Float,
        transitionSpeed: Float,
        sampleIntervalTicks: Int,
        preset: MonetPreset,
        customSeed: Color4b,
    ): MonetPalette {
        val nowNanos = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((nowNanos - lastFrameNanos) * 1e-9f).coerceIn(0.001f, 0.25f)
        }
        lastFrameNanos = nowNanos

        val level = mc.level
        val player = mc.player

        targetSeedArgb = when (source) {
            MonetSource.CUSTOM_SEED -> ARGB.opaque(customSeed.argb)
            MonetSource.PRESET_SEED -> preset.seedArgb
            MonetSource.AURORA_CYCLE -> computeAuroraSeed(nowNanos)
            else -> {
                if (level != null && player != null) {
                    val tickNow = level.gameTime
                    if (abs(tickNow - lastSampleTick) >= sampleIntervalTicks.coerceAtLeast(1)) {
                        lastSampleTick = tickNow
                        sampleWorldTargetSeed(level, player, source, customSeed.argb)
                    } else {
                        targetSeedArgb
                    }
                } else {
                    ARGB.opaque(customSeed.argb)
                }
            }
        }

        val targetLight = when (brightness) {
            MonetBrightness.DARK -> 0f
            MonetBrightness.LIGHT -> 1f
            MonetBrightness.AUTO_DAYLIGHT -> if (level != null) computeDaylightFactor(level) else 0f
        }

        val alpha = (1f - exp(-transitionSpeed.coerceIn(0.2f, 20f) * dt)).coerceIn(0.01f, 1f)
        val tr = ARGB.red(targetSeedArgb).toFloat()
        val tg = ARGB.green(targetSeedArgb).toFloat()
        val tb = ARGB.blue(targetSeedArgb).toFloat()

        smoothedR += (tr - smoothedR) * alpha
        smoothedG += (tg - smoothedG) * alpha
        smoothedB += (tb - smoothedB) * alpha
        smoothedLightMix += (targetLight - smoothedLightMix) * alpha

        // Quantize to 2-step RGB buckets (~128^3) so HCT bisection is practically zero-cost while still silky-smooth
        val qr = (smoothedR.roundToInt().coerceIn(0, 255) and 0xFE)
        val qg = (smoothedG.roundToInt().coerceIn(0, 255) and 0xFE)
        val qb = (smoothedB.roundToInt().coerceIn(0, 255) and 0xFE)
        val quantRgb = (qr shl 16) or (qg shl 8) or qb
        val isLight = smoothedLightMix >= 0.52f

        val key = CacheKey(
            seedRgbQuantized = quantRgb,
            variant = variant,
            isLight = isLight,
            tintQ = (surfaceTint * 50f).roundToInt(),
            chromaQ = (chromaBoost * 50f).roundToInt(),
            contrastQ = (contrast * 50f).roundToInt(),
        )

        val existing = cachedPalette
        if (existing != null && cachedKey == key) {
            return existing
        }

        val built = buildPalette(
            seedArgb = (0xFF shl 24) or quantRgb,
            variant = variant,
            isLight = isLight,
            surfaceTint = surfaceTint.coerceIn(0f, 1f),
            chromaBoost = chromaBoost.coerceIn(0.35f, 2.2f),
            contrast = contrast.coerceIn(-0.5f, 0.85f),
        )
        cachedKey = key
        cachedPalette = built
        return built
    }

    private fun buildPalette(
        seedArgb: Int,
        variant: MonetVariant,
        isLight: Boolean,
        surfaceTint: Float,
        chromaBoost: Float,
        contrast: Float,
    ): MonetPalette {
        val baseHct = Material.Hct.fromInt(seedArgb)
        val hue = baseHct.hue
        val rawChroma = max(14.0, baseHct.chroma) * chromaBoost.toDouble()

        val primaryHue: Double
        val primaryChroma: Double
        val secondaryHue: Double
        val secondaryChroma: Double
        val tertiaryHue: Double
        val tertiaryChroma: Double
        val neutralChromaBase: Double
        val neutralVarChromaBase: Double

        when (variant) {
            MonetVariant.TONAL_SPOT -> {
                primaryHue = hue
                primaryChroma = rawChroma.coerceIn(28.0, 68.0)
                secondaryHue = hue
                secondaryChroma = (rawChroma * 0.38).coerceIn(12.0, 28.0)
                tertiaryHue = Material.sanitizeDegrees(hue + 60.0)
                tertiaryChroma = (rawChroma * 0.55).coerceIn(20.0, 42.0)
                neutralChromaBase = 10.0
                neutralVarChromaBase = 18.0
            }
            MonetVariant.VIBRANT -> {
                primaryHue = hue
                primaryChroma = (rawChroma * 1.32).coerceIn(48.0, 96.0)
                secondaryHue = Material.sanitizeDegrees(hue + 18.0)
                secondaryChroma = (rawChroma * 0.58).coerceIn(22.0, 44.0)
                tertiaryHue = Material.sanitizeDegrees(hue + 42.0)
                tertiaryChroma = (rawChroma * 0.72).coerceIn(28.0, 56.0)
                neutralChromaBase = 18.0
                neutralVarChromaBase = 30.0
            }
            MonetVariant.EXPRESSIVE -> {
                primaryHue = Material.sanitizeDegrees(hue + 24.0)
                primaryChroma = (rawChroma * 1.15).coerceIn(38.0, 84.0)
                secondaryHue = Material.sanitizeDegrees(hue + 75.0)
                secondaryChroma = (rawChroma * 0.48).coerceIn(18.0, 36.0)
                tertiaryHue = Material.sanitizeDegrees(hue + 135.0)
                tertiaryChroma = (rawChroma * 0.68).coerceIn(26.0, 52.0)
                neutralChromaBase = 14.0
                neutralVarChromaBase = 24.0
            }
            MonetVariant.NEUTRAL -> {
                primaryHue = hue
                primaryChroma = (rawChroma * 0.52).coerceIn(12.0, 32.0)
                secondaryHue = hue
                secondaryChroma = (rawChroma * 0.25).coerceIn(6.0, 16.0)
                tertiaryHue = Material.sanitizeDegrees(hue + 35.0)
                tertiaryChroma = (rawChroma * 0.35).coerceIn(10.0, 24.0)
                neutralChromaBase = 3.0
                neutralVarChromaBase = 6.0
            }
        }

        val tintMul = (surfaceTint * 1.65f).toDouble()
        val pPal = Material.TonalPalette.of(primaryHue, primaryChroma)
        val sPal = Material.TonalPalette.of(secondaryHue, secondaryChroma)
        val tPal = Material.TonalPalette.of(tertiaryHue, tertiaryChroma)
        val nPal = Material.TonalPalette.of(hue, neutralChromaBase * tintMul)
        val nvPal = Material.TonalPalette.of(hue, neutralVarChromaBase * tintMul)

        val cShift = contrast * 10.0

        return if (!isLight) {
            MonetPalette(
                seed = seedArgb,
                isLight = false,
                primary = pPal.tone((80.0 + cShift).coerceIn(62.0, 95.0)),
                onPrimary = pPal.tone((20.0 - cShift * 0.6).coerceIn(6.0, 32.0)),
                primaryContainer = pPal.tone((32.0 + cShift * 0.4).coerceIn(18.0, 48.0)),
                onPrimaryContainer = pPal.tone(92.0),
                secondary = sPal.tone(80.0),
                secondaryContainer = sPal.tone(30.0),
                tertiary = tPal.tone(82.0),
                tertiaryContainer = tPal.tone(32.0),
                surfaceLowest = nvPal.tone((5.0 - cShift * 0.35).coerceIn(2.0, 12.0)),
                surfaceLow = nvPal.tone((10.0 - cShift * 0.30).coerceIn(4.0, 18.0)),
                surface = nvPal.tone((14.0 - cShift * 0.25).coerceIn(6.0, 24.0)),
                surfaceHigh = nvPal.tone((19.0 - cShift * 0.15).coerceIn(10.0, 30.0)),
                surfaceHighest = nvPal.tone((25.0 - cShift * 0.10).coerceIn(14.0, 38.0)),
                onSurface = nPal.tone((93.0 + cShift * 0.5).coerceIn(85.0, 99.0)),
                onSurfaceVariant = nvPal.tone((80.0 + cShift * 0.4).coerceIn(68.0, 92.0)),
                outline = nvPal.tone((62.0 + cShift * 0.8).coerceIn(45.0, 86.0)),
                outlineVariant = nvPal.tone((34.0 + cShift * 0.6).coerceIn(20.0, 56.0)),
                scrim = nPal.tone(3.0),
            )
        } else {
            MonetPalette(
                seed = seedArgb,
                isLight = true,
                primary = pPal.tone((40.0 - cShift).coerceIn(20.0, 56.0)),
                onPrimary = pPal.tone(98.0),
                primaryContainer = pPal.tone((88.0 + cShift * 0.3).coerceIn(76.0, 95.0)),
                onPrimaryContainer = pPal.tone(14.0),
                secondary = sPal.tone(42.0),
                secondaryContainer = sPal.tone(88.0),
                tertiary = tPal.tone(42.0),
                tertiaryContainer = tPal.tone(88.0),
                surfaceLowest = nvPal.tone(99.0),
                surfaceLow = nvPal.tone((95.0 + cShift * 0.2).coerceIn(88.0, 99.0)),
                surface = nvPal.tone((92.0 + cShift * 0.2).coerceIn(84.0, 97.0)),
                surfaceHigh = nvPal.tone((87.0 - cShift * 0.2).coerceIn(78.0, 94.0)),
                surfaceHighest = nvPal.tone((82.0 - cShift * 0.3).coerceIn(72.0, 90.0)),
                onSurface = nPal.tone((12.0 - cShift * 0.5).coerceIn(4.0, 22.0)),
                onSurfaceVariant = nvPal.tone((30.0 - cShift * 0.4).coerceIn(16.0, 44.0)),
                outline = nvPal.tone((50.0 - cShift * 0.7).coerceIn(32.0, 68.0)),
                outlineVariant = nvPal.tone((76.0 - cShift * 0.5).coerceIn(58.0, 88.0)),
                scrim = nPal.tone(12.0),
            )
        }
    }

    private fun sampleWorldTargetSeed(
        level: ClientLevel,
        player: Player,
        source: MonetSource,
        fallbackArgb: Int,
    ): Int {
        if (source == MonetSource.HELD_ITEM) {
            extractItemColor(level, player, player.mainHandItem)?.let { return it }
            extractItemColor(level, player, player.offhandItem)?.let { return it }
        }

        binWeight.fill(0.0)
        binR.fill(0.0)
        binG.fill(0.0)
        binB.fill(0.0)

        val px = floor(player.x).toInt()
        val py = floor(player.eyeY).toInt()
        val pz = floor(player.z).toInt()

        // 1. Sky + Biome foliage/grass/water colors
        if (source == MonetSource.WORLD_HYBRID || source == MonetSource.SKY_AND_BIOME) {
            val skyCol = estimateAtmosphericColor(level)
            accumulateSample(skyCol, if (source == MonetSource.SKY_AND_BIOME) 3.2 else 1.55)

            samplePos.set(px, py, pz)
            val grassCol = BiomeColors.getAverageGrassColor(level, samplePos)
            val foliageCol = BiomeColors.getAverageFoliageColor(level, samplePos)
            val waterCol = BiomeColors.getAverageWaterColor(level, samplePos)
            accumulateSample(grassCol, 1.05)
            accumulateSample(foliageCol, 1.15)
            accumulateSample(waterCol, 0.85)
        }

        // 2. Camera look-ray + surrounding 3D block palette
        if (source == MonetSource.WORLD_HYBRID || source == MonetSource.SURROUNDING_BLOCKS || source == MonetSource.HELD_ITEM) {
            val look = player.lookAngle
            val raySteps = intArrayOf(2, 4, 7, 11, 16)
            for (dist in raySteps) {
                val rx = floor(player.x + look.x * dist).toInt()
                val ry = floor(player.eyeY + look.y * dist).toInt().coerceIn(level.minY, level.maxY - 1)
                val rz = floor(player.z + look.z * dist).toInt()
                sampleBlockAt(level, rx, ry, rz, 2.2)
            }

            val offsets = intArrayOf(-6, -2, 2, 6)
            val yOffsets = intArrayOf(-2, 0, 3)
            for (dx in offsets) {
                for (dz in offsets) {
                    for (dy in yOffsets) {
                        val by = (py + dy).coerceIn(level.minY, level.maxY - 1)
                        sampleBlockAt(level, px + dx, by, pz + dz, 0.9)
                    }
                }
            }
        }

        // 3. Subtle held-item accent contribution in WORLD_HYBRID
        if (source == MonetSource.WORLD_HYBRID) {
            extractItemColor(level, player, player.mainHandItem)?.let { itemCol ->
                accumulateSample(itemCol, 1.35)
            }
        }

        var bestBin = -1
        var bestScore = 0.0
        for (i in 0 until HUE_BINS) {
            if (binWeight[i] > bestScore) {
                bestScore = binWeight[i]
                bestBin = i
            }
        }

        if (bestBin < 0 || bestScore <= 0.001) {
            return ARGB.opaque(fallbackArgb)
        }

        val inv = 1.0 / bestScore
        val r = (binR[bestBin] * inv).roundToInt().coerceIn(0, 255)
        val g = (binG[bestBin] * inv).roundToInt().coerceIn(0, 255)
        val b = (binB[bestBin] * inv).roundToInt().coerceIn(0, 255)
        return ARGB.color(255, r, g, b)
    }

    private fun sampleBlockAt(level: ClientLevel, x: Int, y: Int, z: Int, baseWeight: Double) {
        samplePos.set(x, y, z)
        val state = level.getBlockState(samplePos)
        if (state.isAir) return
        val mapColor = state.getMapColor(level, samplePos)
        if (mapColor == MapColor.NONE) return
        val rgb = mapColor.col
        if (rgb != 0) {
            accumulateSample(rgb, baseWeight)
        }
    }

    private fun accumulateSample(rgbRaw: Int, baseWeight: Double) {
        val argb = ARGB.opaque(rgbRaw)
        val hsv = Material.hsv(argb)
        val hue = hsv[0].toDouble()
        val sat = hsv[1].toDouble()
        val value = hsv[2].toDouble()
        if (value < 0.06) return

        // Chroma-preferring score inspired by Android's Monet `Score.score`:
        // colourful blocks/biomes strongly outweigh grey stone/dirt, while pure snow/quartz still registers softly.
        val chromaWeight = if (sat < 0.10) {
            0.12 * value
        } else {
            0.25 + (sat.pow(1.35) * 2.65) * (0.45 + 0.55 * value)
        }
        val w = baseWeight * chromaWeight
        val bin = (floor(Material.sanitizeDegrees(hue) / BIN_DEGREES).toInt()) % HUE_BINS
        binWeight[bin] += w
        binR[bin] += ARGB.red(argb) * w
        binG[bin] += ARGB.green(argb) * w
        binB[bin] += ARGB.blue(argb) * w
    }

    private fun estimateAtmosphericColor(level: ClientLevel): Int {
        val dim = level.dimension()
        if (dim == Level.NETHER) {
            return 0xFFB83B2E.toInt()
        }
        if (dim == Level.END) {
            return 0xFF8E63CE.toInt()
        }

        val timeTicks = ((level.defaultClockTime % 24000L) + 24000L) % 24000L
        // 0 = sunrise/morning, 6000 = noon, 12000 = sunset, 18000 = midnight
        val baseSky = when {
            timeTicks in 11200L..13600L -> {
                val t = abs(timeTicks - 12400L) / 1200f
                Material.mix(0xFFFF7E54.toInt(), 0xFF5D76CB.toInt(), t.coerceIn(0f, 1f))
            }
            timeTicks >= 22500L || timeTicks <= 800L -> {
                val dist = if (timeTicks >= 22500L) (24000L - timeTicks) else timeTicks
                val t = dist / 1500f
                Material.mix(0xFFFF9468.toInt(), 0xFF64A4F5.toInt(), t.coerceIn(0f, 1f))
            }
            timeTicks in 13601L..22499L -> 0xFF3E5496.toInt()
            else -> 0xFF63A4FF.toInt()
        }

        val rain = level.getRainLevel(1f)
        return if (rain > 0.05f) {
            Material.mix(baseSky, 0xFF60768D.toInt(), (rain * 0.65f).coerceIn(0f, 1f))
        } else {
            baseSky
        }
    }

    private fun computeDaylightFactor(level: ClientLevel): Float {
        if (level.dimension() != Level.OVERWORLD) return 0f
        val timeTicks = ((level.defaultClockTime % 24000L) + 24000L) % 24000L
        val sunPhase = cos(((timeTicks - 6000L).toDouble() / 24000.0) * 2.0 * PI).toFloat()
        val rainPenalty = level.getRainLevel(1f) * 0.45f
        return ((sunPhase * 0.5f + 0.5f) - rainPenalty).coerceIn(0f, 1f)
    }

    private fun extractItemColor(level: ClientLevel, player: Player, stack: ItemStack): Int? {
        if (stack.isEmpty) return null

        stack[DataComponents.DYED_COLOR]?.rgb?.let { return ARGB.opaque(it) }
        stack[DataComponents.POTION_CONTENTS]?.color?.let { return ARGB.opaque(it) }

        val item = stack.item
        if (item is BlockItem) {
            samplePos.set(floor(player.x).toInt(), floor(player.y).toInt(), floor(player.z).toInt())
            val mapCol = item.block.defaultBlockState().getMapColor(level, samplePos).col
            if (mapCol != 0) {
                return ARGB.opaque(mapCol)
            }
        }

        val path = BuiltInRegistries.ITEM.getKey(item).path
        return when {
            "diamond" in path -> 0xFF4EE2EC.toInt()
            "emerald" in path -> 0xFF34D07B.toInt()
            "gold" in path || "totem" in path || "blaze" in path -> 0xFFFFBE42.toInt()
            "amethyst" in path || "chorus" in path -> 0xFFA66CFF.toInt()
            "redstone" in path || "apple" in path -> 0xFFFF5252.toInt()
            "lapis" in path -> 0xFF4478FF.toInt()
            "ender" in path || "prismarine" in path || "sculk" in path || "warped" in path -> 0xFF26C6B8.toInt()
            "netherite" in path -> 0xFF8D6E7A.toInt()
            "copper" in path -> 0xFFE07A4F.toInt()
            "cherry" in path -> 0xFFF48FB1.toInt()
            else -> null
        }
    }

    private fun computeAuroraSeed(nowNanos: Long): Int {
        val seconds = (nowNanos * 1e-9) % 3600.0
        val hue = Material.sanitizeDegrees(seconds * 14.0 + sin(seconds * 0.35) * 28.0)
        return Material.fromHsv(hue.toFloat(), 0.68f, 0.94f, 255)
    }
}
