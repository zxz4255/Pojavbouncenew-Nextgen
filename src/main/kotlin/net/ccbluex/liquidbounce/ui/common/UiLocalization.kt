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
package net.ccbluex.liquidbounce.ui.common

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.ClientLanguageChangedEvent
import net.ccbluex.liquidbounce.lang.LanguageManager
import net.ccbluex.liquidbounce.utils.client.logger
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class UiLanguageMode(override val tag: String) : Tagged {
    AUTO("Auto"),
    CHINESE("Chinese"),
    ENGLISH("English"),
}

private class ZhCnUiPack(
    val modules: Map<String, String> = emptyMap(),
    val categories: Map<String, String> = emptyMap(),
    val hudComponents: Map<String, String> = emptyMap(),
    val hudDescriptions: Map<String, String> = emptyMap(),
    val effects: Map<String, String> = emptyMap(),
    val terms: Map<String, String> = emptyMap(),
    val words: Map<String, String> = emptyMap(),
    val preserve: List<String> = emptyList(),
)

/**
 * Central bilingual (Chinese / English) localization engine for ClickGUI, Global Settings,
 * HUD Editor, and all in-game HUD components & overlays.
 */
object UiLocalization {

    private const val PACK_RESOURCE = "/resources/liquidbounce/lang/zh_cn_ui.json"
    private const val CJK_START = 0x4E00
    private const val CJK_END = 0x9FFF

    private val TOKEN_REGEX = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+")
    private val DIMENSION_REGEX = Regex("^\\d+x\\d+$", RegexOption.IGNORE_CASE)
    private val VERSION_CODE_REGEX = Regex("^[A-Za-z]{2,8}\\d+(?:[._-]\\d+)+$")
    private val AXIS_LETTERS = setOf("X", "Y", "Z", "W", "H", "R", "G", "B", "A", "U", "V")
    private val CLAUSE_PIVOTS = mapOf(
        "on" to "时",
        "when" to "时",
        "while" to "时",
        "after" to "后",
        "before" to "前",
        "during" to "期间",
        "until" to "直到",
    )

    @Volatile
    var mode: UiLanguageMode = UiLanguageMode.AUTO
        private set

    private var syncing = false
    private val modeListeners = CopyOnWriteArrayList<(UiLanguageMode) -> Unit>()
    private val zhDisplayCache = ConcurrentHashMap<String, String>(512)

    private val pack: ZhCnUiPack by lazy {
        runCatching {
            val stream = UiLocalization::class.java.getResourceAsStream(PACK_RESOURCE)
                ?: return@runCatching ZhCnUiPack()
            val root = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader).asJsonObject
            }
            fun readStringMap(key: String): Map<String, String> {
                val obj: JsonObject = root.getAsJsonObject(key) ?: return emptyMap()
                val out = HashMap<String, String>(obj.size() * 2)
                for ((k, v) in obj.entrySet()) {
                    if (v != null && v.isJsonPrimitive) {
                        out[k] = v.asString
                    }
                }
                return out
            }
            val preserveArr = root.getAsJsonArray("preserve")
            val preserveList = if (preserveArr != null) {
                buildList(preserveArr.size()) {
                    for (el in preserveArr) {
                        if (el != null && el.isJsonPrimitive) add(el.asString)
                    }
                }
            } else {
                emptyList()
            }
            ZhCnUiPack(
                modules = readStringMap("modules"),
                categories = readStringMap("categories"),
                hudComponents = readStringMap("hudComponents"),
                hudDescriptions = readStringMap("hudDescriptions"),
                effects = readStringMap("effects"),
                terms = readStringMap("terms"),
                words = readStringMap("words"),
                preserve = preserveList,
            )
        }.onFailure {
            logger.error("Failed to load $PACK_RESOURCE", it)
        }.getOrDefault(ZhCnUiPack())
    }

    private val exactLookup: Map<String, String> by lazy {
        val map = HashMap<String, String>(1024)
        fun putSection(section: Map<String, String>) {
            for ((k, v) in section) {
                map.putIfAbsent(k, v)
                map.putIfAbsent(k.lowercase(Locale.ROOT), v)
                val compact = k.replace(" ", "").lowercase(Locale.ROOT)
                map.putIfAbsent(compact, v)
            }
        }
        putSection(pack.terms)
        putSection(pack.hudComponents)
        putSection(pack.categories)
        putSection(pack.modules)
        map
    }

    private val moduleLookup: Map<String, String> by lazy {
        buildCaseInsensitiveMap(pack.modules)
    }

    private val categoryLookup: Map<String, String> by lazy {
        buildCaseInsensitiveMap(pack.categories)
    }

    private val hudComponentLookup: Map<String, String> by lazy {
        buildCaseInsensitiveMap(pack.hudComponents)
    }

    private val hudDescriptionLookup: Map<String, String> by lazy {
        buildCaseInsensitiveMap(pack.hudDescriptions)
    }

    private val effectLookup: Map<String, String> by lazy {
        buildCaseInsensitiveMap(pack.effects)
    }

    private val wordLookup: Map<String, String> by lazy {
        val map = HashMap<String, String>(pack.words.size * 2)
        for ((k, v) in pack.words) {
            map[k.lowercase(Locale.ROOT)] = v
        }
        map
    }

    private val preserveLookup: Map<String, String> by lazy {
        val map = HashMap<String, String>(pack.preserve.size * 2)
        for (token in pack.preserve) {
            map[token.lowercase(Locale.ROOT)] = token
        }
        map
    }

    val isChinese: Boolean
        get() = when (mode) {
            UiLanguageMode.CHINESE -> true
            UiLanguageMode.ENGLISH -> false
            UiLanguageMode.AUTO -> LanguageManager.isAutoChineseLocale()
        }

    fun registerSyncListener(listener: (UiLanguageMode) -> Unit) {
        modeListeners += listener
    }

    fun setLanguageMode(newMode: UiLanguageMode) {
        if (syncing) return
        val changed = mode != newMode
        syncing = true
        try {
            mode = newMode
            for (listener in modeListeners) {
                listener(newMode)
            }
        } finally {
            syncing = false
        }
        if (changed) {
            LanguageManager.reloadEffectiveLanguage()
            EventManager.callEvent(ClientLanguageChangedEvent())
        }
    }

    fun toggleChineseMode() {
        val target = if (isChinese) UiLanguageMode.ENGLISH else UiLanguageMode.CHINESE
        setLanguageMode(target)
    }

    fun text(en: String, zh: String): String = if (isChinese) zh else en

    fun moduleName(raw: String): String {
        if (!isChinese) return UiTextUtils.formatEnglishName(raw)
        return moduleLookup[raw]
            ?: moduleLookup[raw.lowercase(Locale.ROOT)]
            ?: translateToChinese(raw)
    }

    fun categoryName(raw: String): String {
        if (!isChinese) return raw
        return categoryLookup[raw]
            ?: categoryLookup[raw.lowercase(Locale.ROOT)]
            ?: translateToChinese(raw)
    }

    fun hudName(raw: String): String {
        if (!isChinese) return UiTextUtils.formatEnglishName(raw)
        return hudComponentLookup[raw]
            ?: hudComponentLookup[raw.lowercase(Locale.ROOT)]
            ?: translateToChinese(raw)
    }

    fun hudDescription(rawName: String, englishFallback: String): String {
        if (!isChinese) return englishFallback
        return hudDescriptionLookup[rawName]
            ?: hudDescriptionLookup[rawName.lowercase(Locale.ROOT)]
            ?: englishFallback
    }

    fun effectName(rawName: String): String {
        if (!isChinese || containsHan(rawName)) return rawName
        return effectLookup[rawName]
            ?: effectLookup[rawName.lowercase(Locale.ROOT)]
            ?: rawName
    }

    fun displayName(raw: String): String {
        if (raw.isEmpty()) return raw
        if (!isChinese) return UiTextUtils.formatEnglishName(raw)
        return translateToChinese(raw)
    }

    fun chineseCandidate(raw: String): String = translateToChinese(raw)

    /**
     * Bilingual query matcher supporting raw camelCase, spaced English, and Chinese labels.
     */
    fun matchesQuery(query: String, raw: String, extraText: String? = null): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        if (raw.lowercase(Locale.ROOT).contains(q)) return true
        if (UiTextUtils.formatEnglishName(raw).lowercase(Locale.ROOT).contains(q)) return true
        if (translateToChinese(raw).lowercase(Locale.ROOT).contains(q)) return true
        if (moduleLookup[raw]?.lowercase(Locale.ROOT)?.contains(q) == true) return true
        if (hudComponentLookup[raw]?.lowercase(Locale.ROOT)?.contains(q) == true) return true
        if (hudDescriptionLookup[raw]?.lowercase(Locale.ROOT)?.contains(q) == true) return true
        if (!extraText.isNullOrEmpty() && extraText.lowercase(Locale.ROOT).contains(q)) return true
        return false
    }

    private fun translateToChinese(raw: String): String {
        if (raw.isEmpty() || containsHan(raw) || !containsAsciiLetter(raw)) {
            return raw
        }
        return zhDisplayCache.computeIfAbsent(raw) { computeChineseLabel(it) }
    }

    private fun computeChineseLabel(raw: String): String {
        exactLookup[raw]?.let { return it }
        val lower = raw.lowercase(Locale.ROOT)
        exactLookup[lower]?.let { return it }
        val compact = lower.replace(" ", "")
        exactLookup[compact]?.let { return it }
        preserveLookup[lower]?.let { return it }
        if (DIMENSION_REGEX.matches(raw) || VERSION_CODE_REGEX.matches(raw)) {
            return raw
        }

        val tokens = TOKEN_REGEX.findAll(raw).map { it.value }.toList()
        if (tokens.isEmpty()) return raw

        // Flip English "[Action] + On/When/After/Before + [Condition]" to idiomatic "[Condition]时[Action]"
        val pivotIdx = tokens.indexOfFirst { tok ->
            tok.lowercase(Locale.ROOT) in CLAUSE_PIVOTS
        }
        if (pivotIdx in 1 until tokens.lastIndex) {
            val pivotWord = CLAUSE_PIVOTS[tokens[pivotIdx].lowercase(Locale.ROOT)].orEmpty()
            val head = joinTranslatedTokens(tokens.subList(0, pivotIdx))
            val tail = joinTranslatedTokens(tokens.subList(pivotIdx + 1, tokens.size))
            if (head.isNotEmpty() && tail.isNotEmpty()) {
                return if (tokens[pivotIdx].equals("until", ignoreCase = true)) {
                    "$head$pivotWord$tail"
                } else {
                    "$tail$pivotWord$head"
                }
            }
        }

        val joined = joinTranslatedTokens(tokens)
        return joined.ifEmpty { raw }
    }

    private fun joinTranslatedTokens(tokens: List<String>): String {
        val sb = StringBuilder()
        var index = 0
        while (index < tokens.size) {
            // Preserve inline dimension chunks like 1x1Bedrock -> 1x1基岩
            if (index + 2 < tokens.size &&
                tokens[index].all(Char::isDigit) &&
                tokens[index + 1].equals("x", ignoreCase = true) &&
                tokens[index + 2].all(Char::isDigit)
            ) {
                appendPiece(sb, "${tokens[index]}x${tokens[index + 2]}")
                index += 3
                continue
            }
            val piece = translateSingleToken(tokens[index])
            if (piece.isNotEmpty()) {
                appendPiece(sb, piece)
            }
            index++
        }
        return sb.toString()
    }

    private fun translateSingleToken(token: String): String {
        if (token.all(Char::isDigit)) return token
        val lower = token.lowercase(Locale.ROOT)
        preserveLookup[lower]?.let { return it }
        if (token.length == 1 && token.uppercase(Locale.ROOT) in AXIS_LETTERS) {
            return token.uppercase(Locale.ROOT)
        }
        wordLookup[lower]?.let { return it }
        exactLookup[lower]?.let { return it }
        return token
    }

    private fun appendPiece(sb: StringBuilder, piece: String) {
        if (sb.isNotEmpty() && isAsciiWordChar(sb.last()) && isAsciiWordChar(piece.first())) {
            sb.append(' ')
        }
        sb.append(piece)
    }

    private fun isAsciiWordChar(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'

    private fun containsAsciiLetter(text: String): Boolean {
        for (ch in text) {
            if (ch in 'A'..'Z' || ch in 'a'..'z') return true
        }
        return false
    }

    private fun containsHan(text: String): Boolean {
        for (ch in text) {
            if (ch.code in CJK_START..CJK_END) return true
        }
        return false
    }

    private fun buildCaseInsensitiveMap(source: Map<String, String>): Map<String, String> {
        val map = HashMap<String, String>(source.size * 2)
        for ((k, v) in source) {
            map[k] = v
            map.putIfAbsent(k.lowercase(Locale.ROOT), v)
        }
        return map
    }
}
