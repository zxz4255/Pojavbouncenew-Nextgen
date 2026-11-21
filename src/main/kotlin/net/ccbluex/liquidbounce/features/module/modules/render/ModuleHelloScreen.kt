/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.GitHubBuildInfo

/** Settings consumed by the actual Web title route, including outside an in-game world. */
object ModuleHelloScreen : ClientModule("HelloScreen", ModuleCategories.RENDER, state = true, hide = true) {
    val showBuild by boolean("ShowBuild", true)

    // Read-only, not restored from configuration: a saved value must never impersonate a CI run.
    @Suppress("unused")
    private val build by text("Build", GitHubBuildInfo.label).immutable().notPersistent()
}
