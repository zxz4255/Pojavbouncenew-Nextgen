/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.config.types

/** Opt-in diagnostics only. A trace consumer must never be used to transform a value. */
data class ValueWriteTrace<T : Any>(
    val id: Long,
    val stage: Stage,
    val previous: T,
    val requested: T,
    val candidate: T,
    val actual: T,
    val published: T,
    val failure: Throwable? = null,
) {
    enum class Stage { REQUEST, UNCHANGED, TRANSFORMED, IMMUTABLE, APPLY_FAILED, COMMITTED, COMPLETED,
        REJECTED, OBSERVER_FAILED }
}
