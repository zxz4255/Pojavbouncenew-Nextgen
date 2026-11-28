/*
 * This file is part of LiquidBounce. SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.ui.clickgui.setting

import net.ccbluex.liquidbounce.config.types.Value
import java.util.regex.PatternSyntaxException

/** TEXT is a presentation type shared by String and Regex, not a Value<String> guarantee. */
internal object TextSettingCodec {
    fun supports(value: Value<*>): Boolean = when (value.get()) {
        is String, is Regex -> true
        else -> false
    }

    fun text(value: Value<*>): String = when (val current = value.get()) {
        is Regex -> current.pattern
        else -> current.toString()
    }

    fun regex(text: String, options: Set<RegexOption> = emptySet()): Regex? = try {
        Regex(text, options)
    } catch (_: PatternSyntaxException) {
        null
    }

    /** Invalid drafts never reach Value.set/listeners/config; preserve existing Regex options. */
    @Suppress("UNCHECKED_CAST")
    fun apply(value: Value<*>, text: String): Boolean = when (val current = value.get()) {
        is String -> {
            (value as Value<String>).set(text)
            true
        }
        is Regex -> {
            val next = regex(text, current.options)
            if (next == null) {
                false
            } else {
                // Regex equality is referential: a second commit should not emit a spurious change.
                if (current.pattern != next.pattern || current.options != next.options) {
                    (value as Value<Regex>).set(next)
                }
                true
            }
        }
        else -> false // Unsupported TEXT payloads stay read-only, never coerce their stored type.
    }
}
