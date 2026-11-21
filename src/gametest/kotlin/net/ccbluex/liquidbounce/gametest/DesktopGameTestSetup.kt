/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.gametest

import net.ccbluex.liquidbounce.integration.backend.isBrowserDisabled
import net.fabricmc.api.ClientModInitializer

/** Test-source-set only: the production Android backend is unavailable in an x86_64 CI VM. */
class DesktopGameTestSetup : ClientModInitializer {
    override fun onInitializeClient() {
        if (java.lang.Boolean.getBoolean("moonlight.gametest.skipAndroidBrowser")) {
            isBrowserDisabled = true
        }
    }
}
