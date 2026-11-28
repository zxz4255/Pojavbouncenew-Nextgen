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
package net.ccbluex.liquidbounce.features.module.modules.render.bongocat

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.utils.client.logger
import org.lwjgl.system.APIUtil
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.SharedLibrary
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sin

/**
 * Native Android `.so` (`libbongocat.so`) loader and C ABI bridge for [ModuleBongoCat].
 *
 * Unpacks `/natives/android-{arm64,x64}/libbongocat.so` into an executable directory
 * (`POJAV_NATIVEDIR`, `TMPDIR`, `java.io.tmpdir`, `~/.cache`, or `ConfigSystem`) and
 * binds C symbols (`bongocat_get_version`, `bongocat_state_size`, `bongocat_init`,
 * `bongocat_step`) via LWJGL [SharedLibrary] + [JNI].
 */
object NativeBongoCatCore {

    const val IN_LEFT_CLICK = 1 shl 0
    const val IN_RIGHT_CLICK = 1 shl 1
    const val IN_KEY_W = 1 shl 2
    const val IN_KEY_A = 1 shl 3
    const val IN_KEY_S = 1 shl 4
    const val IN_KEY_D = 1 shl 5
    const val IN_KEY_SPACE = 1 shl 6
    const val IN_MODIFIER = 1 shl 7

    const val POSE_FLOATS = 16

    private class Symbols(
        val library: SharedLibrary,
        val getVersion: Long,
        val stateSize: Long,
        val init: Long,
        val step: Long,
    )

    @Volatile
    private var attempted = false

    @Volatile
    private var symbols: Symbols? = null

    @Volatile
    var backendLabel: String = "uninitialized"
        private set

    fun isNativeLoaded(): Boolean {
        ensureLoaded()
        return symbols != null
    }

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (attempted) return symbols != null
        attempted = true

        val arch = detectArch()
        val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val supportsElfSo = osName.contains("linux") || osName.contains("android")
        if (arch == null || !supportsElfSo) {
            backendLabel = "kotlin-fallback ($osName/$arch)"
            return false
        }

        val resourcePath = "/natives/android-$arch/libbongocat.so"
        val bytes = NativeBongoCatCore::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        if (bytes == null || bytes.size < 64) {
            backendLabel = "missing-resource ($resourcePath)"
            return false
        }

        val hashPrefix = sha256Hex(bytes).take(12)
        val fileName = "libbongocat-$arch-$hashPrefix.so"

        for (dir in candidateExtractDirs()) {
            try {
                if (!dir.exists() && !dir.mkdirs()) continue
                val soFile = File(dir, fileName)
                if (!soFile.exists() || soFile.length() != bytes.size.toLong()) {
                    val tmp = File(dir, "$fileName.tmp.${System.nanoTime()}")
                    tmp.writeBytes(bytes)
                    tmp.setReadable(true, false)
                    tmp.setExecutable(true, false)
                    if (!tmp.renameTo(soFile)) {
                        soFile.writeBytes(bytes)
                        tmp.delete()
                    }
                }
                soFile.setReadable(true, false)
                soFile.setExecutable(true, false)

                val lib = APIUtil.apiCreateLibrary(soFile.absolutePath)
                val fnGetVer = APIUtil.apiGetFunctionAddress(lib, "bongocat_get_version")
                val fnSize = APIUtil.apiGetFunctionAddress(lib, "bongocat_state_size")
                val fnInit = APIUtil.apiGetFunctionAddress(lib, "bongocat_init")
                val fnStep = APIUtil.apiGetFunctionAddress(lib, "bongocat_step")

                if (fnGetVer != 0L && fnSize != 0L && fnInit != 0L && fnStep != 0L) {
                    val ver = JNI.invokeI(fnGetVer)
                    val sz = JNI.invokeI(fnSize)
                    if (ver >= 0x00010000 && sz in 64..4096) {
                        symbols = Symbols(lib, fnGetVer, fnSize, fnInit, fnStep)
                        backendLabel = "native-so ($arch v${ver ushr 16}.${(ver ushr 8) and 0xFF})"
                        logger.info("[BongoCat] Loaded Android native library: ${soFile.absolutePath} ($backendLabel)")
                        return true
                    }
                }
                runCatching { lib.free() }
            } catch (t: Throwable) {
                logger.debug("[BongoCat] Candidate dir ${dir.absolutePath} skipped: ${t.message}")
            }
        }

        backendLabel = "kotlin-fallback (dlopen unavailable)"
        return false
    }

    private fun detectArch(): String? {
        val raw = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
        return when {
            raw.contains("aarch64") || raw.contains("arm64") -> "arm64"
            raw == "amd64" || raw == "x86_64" || raw == "x64" -> "x64"
            else -> null
        }
    }

    private fun candidateExtractDirs(): List<File> {
        val out = ArrayList<File>(6)
        System.getenv("POJAV_NATIVEDIR")?.takeIf { it.isNotBlank() }?.let { out.add(File(it)) }
        System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }?.let { out.add(File(it, "lb-bongocat")) }
        System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { out.add(File(it, "lb-bongocat")) }
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let {
            out.add(File(it, ".cache/liquidbounce/natives"))
        }
        runCatching { out.add(File(ConfigSystem.rootFolder, "natives-cache")) }
        return out
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /**
     * Per-module off-heap session backed by `libbongocat.so` (or pure Kotlin fallback).
     */
    class Session : AutoCloseable {
        private var statePtr: Long = 0L
        private var maskPtr: Long = 0L
        private var inFloatsPtr: Long = 0L
        private var poseBuf: FloatBuffer? = null
        private var posePtr: Long = 0L

        // Fallback state if running on non-ELF host (e.g. macOS/Windows dev machine)
        private var fbTime = 0f
        private var fbPrevMask = 0
        private var fbLeftDown = 0f
        private var fbRightDown = 0f
        private var fbSquash = 0f
        private var fbBounce = 0f
        private var fbLeftGlow = 0f
        private var fbRightGlow = 0f
        private val fbClicksL = ArrayDeque<Float>()
        private val fbClicksR = ArrayDeque<Float>()

        fun reset() {
            if (ensureLoaded()) {
                val s = symbols ?: return
                if (statePtr == 0L) {
                    val sz = JNI.invokeI(s.stateSize).coerceIn(64, 4096)
                    statePtr = MemoryUtil.nmemCalloc(1L, sz.toLong())
                }
                if (maskPtr == 0L) {
                    maskPtr = MemoryUtil.nmemCalloc(1L, 4L)
                }
                if (inFloatsPtr == 0L) {
                    inFloatsPtr = MemoryUtil.nmemCalloc(4L, 4L)
                }
                if (poseBuf == null) {
                    val buf = MemoryUtil.memCallocFloat(POSE_FLOATS)
                    poseBuf = buf
                    posePtr = MemoryUtil.memAddress(buf)
                }
                if (statePtr != 0L) {
                    JNI.invokePV(statePtr, s.init)
                }
            } else {
                fbTime = 0f
                fbPrevMask = 0
                fbLeftDown = 0f
                fbRightDown = 0f
                fbSquash = 0f
                fbBounce = 0f
                fbLeftGlow = 0f
                fbRightGlow = 0f
                fbClicksL.clear()
                fbClicksR.clear()
            }
        }

        fun step(dt: Float, inputMask: Int, mouseNx: Float, mouseNy: Float, outPose: FloatArray) {
            require(outPose.size >= POSE_FLOATS)
            val s = symbols
            if (s != null) {
                if (statePtr == 0L || maskPtr == 0L || inFloatsPtr == 0L || poseBuf == null) {
                    reset()
                }
                val sp = statePtr
                val mp = maskPtr
                val fp = inFloatsPtr
                val pp = posePtr
                val pb = poseBuf
                if (sp != 0L && mp != 0L && fp != 0L && pp != 0L && pb != null) {
                    MemoryUtil.memPutInt(mp, inputMask)
                    MemoryUtil.memPutFloat(fp, dt)
                    MemoryUtil.memPutFloat(fp + 4L, mouseNx)
                    MemoryUtil.memPutFloat(fp + 8L, mouseNy)
                    JNI.invokePPPPV(sp, mp, fp, pp, s.step)
                    for (i in 0 until POSE_FLOATS) {
                        outPose[i] = pb.get(i)
                    }
                    return
                }
            }

            // Pure-Kotlin mirror fallback for non-ELF hosts
            val cdt = dt.coerceIn(0.0005f, 0.05f)
            fbTime += cdt
            val rising = inputMask and fbPrevMask.inv()
            fbPrevMask = inputMask
            if ((rising and IN_LEFT_CLICK) != 0) fbClicksL.addLast(fbTime)
            if ((rising and IN_RIGHT_CLICK) != 0) fbClicksR.addLast(fbTime)
            while (fbClicksL.isNotEmpty() && fbTime - fbClicksL.first() > 1.0f) fbClicksL.removeFirst()
            while (fbClicksR.isNotEmpty() && fbTime - fbClicksR.first() > 1.0f) fbClicksR.removeFirst()

            val leftHeld = (inputMask and (IN_LEFT_CLICK or IN_KEY_A or IN_KEY_W or IN_KEY_SPACE)) != 0
            val rightHeld = (inputMask and (IN_RIGHT_CLICK or IN_KEY_D or IN_KEY_S or IN_MODIFIER)) != 0
            if ((rising and (IN_LEFT_CLICK or IN_KEY_A or IN_KEY_W or IN_KEY_SPACE)) != 0) {
                fbLeftDown = 1f; fbLeftGlow = 1f; fbSquash = 0.22f; fbBounce = 4.5f
            }
            if ((rising and (IN_RIGHT_CLICK or IN_KEY_D or IN_KEY_S or IN_MODIFIER)) != 0) {
                fbRightDown = 1f; fbRightGlow = 1f; fbSquash = 0.22f; fbBounce = 4.5f
            }
            val k = (cdt * 22f).coerceIn(0f, 1f)
            fbLeftDown += ((if (leftHeld) 1f else 0f) - fbLeftDown) * k
            fbRightDown += ((if (rightHeld) 1f else 0f) - fbRightDown) * k
            fbSquash += (0f - fbSquash) * (cdt * 14f).coerceIn(0f, 1f)
            fbBounce += (sin(fbTime * 3.1f) * 1.4f - fbBounce) * (cdt * 14f).coerceIn(0f, 1f)
            fbLeftGlow = (fbLeftGlow - cdt * 4.2f).coerceIn(0f, 1f)
            fbRightGlow = (fbRightGlow - cdt * 4.2f).coerceIn(0f, 1f)

            outPose[0] = fbLeftDown.coerceIn(0f, 1f)
            outPose[1] = if ((inputMask and IN_KEY_A) != 0) -0.45f else 0f
            outPose[2] = if ((inputMask and IN_KEY_W) != 0) -0.35f else 0f
            outPose[3] = fbRightDown.coerceIn(0f, 1f)
            outPose[4] = mouseNx.coerceIn(-1f, 1f) * 0.7f
            outPose[5] = mouseNy.coerceIn(-1f, 1f) * 0.6f
            outPose[6] = fbSquash
            outPose[7] = fbBounce
            outPose[8] = (fbRightDown - fbLeftDown) * 5f + sin(fbTime * 1.85f) * 2f
            outPose[9] = (fbLeftGlow - fbRightGlow) * 0.6f
            outPose[10] = if ((fbTime % 3.6f) > 3.46f) 0.1f else 1f
            outPose[11] = if (leftHeld || rightHeld) 0.75f else 0.08f
            outPose[12] = fbLeftGlow
            outPose[13] = fbRightGlow
            outPose[14] = fbClicksL.size.toFloat()
            outPose[15] = fbClicksR.size.toFloat()
        }

        override fun close() {
            if (statePtr != 0L) {
                MemoryUtil.nmemFree(statePtr)
                statePtr = 0L
            }
            if (maskPtr != 0L) {
                MemoryUtil.nmemFree(maskPtr)
                maskPtr = 0L
            }
            if (inFloatsPtr != 0L) {
                MemoryUtil.nmemFree(inFloatsPtr)
                inFloatsPtr = 0L
            }
            poseBuf?.let {
                MemoryUtil.memFree(it)
                poseBuf = null
                posePtr = 0L
            }
        }
    }
}
