/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.gametest

import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoGapple
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleTs2KillAura
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHelloScreen
import net.ccbluex.liquidbounce.features.module.modules.world.ModuleChestAura
import net.ccbluex.liquidbounce.utils.client.GitHubBuildInfo
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.gui.screens.TitleScreen

/** Desktop smoke test for module registration and screen routing. */
class HelloScreenGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.waitForClient()
        context.onClient {
            check(ModuleManager.contains(ModuleAutoGapple)) { "AutoGapple not registered" }
            check(ModuleManager.contains(ModuleTs2KillAura)) { "Aura not registered" }
            check(ModuleManager.contains(ModuleChestAura)) { "ChestAura not registered" }
            check(ModuleManager.contains(ModuleHelloScreen)) { "HelloScreen module not registered" }
            val before = ModuleHelloScreen.enabled
            ModuleHelloScreen.enabled = false
            check(!ModuleHelloScreen.enabled) { "Hello animation must be configurable outside a world" }
            ModuleHelloScreen.enabled = true
            check(ModuleHelloScreen.enabled) { "Hello animation did not re-enable" }
            ModuleHelloScreen.enabled = before
            check(GitHubBuildInfo.label == "#unknown" || GitHubBuildInfo.label.matches(Regex("#[1-9][0-9]*")))
        }

        // The web theme used to render a custom TITLE route through the Android backend; with the
        // browser system removed the ClickGUI/HUD are fully native and vanilla screens apply as-is.
        context.setScreen { TitleScreen() }
        context.waitForScreen(TitleScreen::class.java)
        context.waitTicks(5)
        context.onClient { client ->
            check(client.gui.screen() is TitleScreen) { "A separate native Hello screen intercepted TITLE" }
        }
    }
}
