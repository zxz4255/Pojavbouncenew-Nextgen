/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.gametest

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleMoonlightAutoGap
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleMoonlightChestAura
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleMoonlightKillAura
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHelloScreen
import net.ccbluex.liquidbounce.integration.interop.ClientInteropServer
import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.utils.client.GitHubBuildInfo
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.gui.screens.TitleScreen
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Desktop smoke test; Android WebView visuals are tested separately in a browser. */
class HelloScreenGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.waitForClient()
        context.onClient {
            check(ModuleManager.contains(ModuleMoonlightAutoGap)) { "AutoGap not registered" }
            check(ModuleManager.contains(ModuleMoonlightKillAura)) { "MoonlightKillAura not registered" }
            check(ModuleManager.contains(ModuleMoonlightChestAura)) { "MoonlightChestAura not registered" }
            check(ModuleManager.contains(ModuleHelloScreen)) { "HelloScreen module not registered" }
            check(CustomScreenType.recognize(TitleScreen()) == CustomScreenType.TITLE) {
                "TitleScreen is no longer recognized by the web routing system"
            }
            val before = ModuleHelloScreen.enabled
            ModuleHelloScreen.enabled = false
            check(!ModuleHelloScreen.enabled) { "Hello animation must be configurable outside a world" }
            ModuleHelloScreen.enabled = true
            check(ModuleHelloScreen.enabled) { "Hello animation did not re-enable" }
            ModuleHelloScreen.enabled = before
            check(GitHubBuildInfo.label == "#unknown" || GitHubBuildInfo.label.matches(Regex("#[1-9][0-9]*")))
        }

        // The client game-test runner pauses client ticks while its test thread blocks. Run the
        // synchronous HTTP sequence on a worker and advance ticks while it waits so Ktor requests
        // dispatched to Minecraft's Main dispatcher can be serviced.
        if (!ClientInteropServer.isSkipping) checkRealHelloApi(context)

        // With the Android backend disabled for desktop CI, basic mode keeps the vanilla
        // title as fallback. Production instead renders the TITLE route inside the WebView.
        context.setScreen { TitleScreen() }
        context.waitForScreen(TitleScreen::class.java)
        context.waitTicks(5)
        context.onClient { client ->
            check(client.gui.screen() is TitleScreen) { "A separate native Hello screen intercepted TITLE" }
        }
    }

    private fun checkRealHelloApi(context: ClientGameTestContext) {
        val task = CompletableFuture.runAsync { checkRealHelloApiBlocking() }
        while (!task.isDone) context.waitTicks(1)
        task.join()
    }

    private fun checkRealHelloApiBlocking() {
        val http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        fun request(path: String, method: String = "GET", body: String = ""): String {
            val join = if (path.contains('?')) "&" else "?"
            val uri = URI.create("${ClientInteropServer.url}/api/v1/client/$path${join}lb_code=${ClientInteropServer.AUTH_CODE}")
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
            when (method) {
                "POST" -> builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                "PUT" -> builder.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                else -> builder.GET()
            }
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "$method $path returned ${response.statusCode()}: ${response.body()}" }
            return response.body()
        }
        fun json(path: String): JsonObject = JsonParser.parseString(request(path)).asJsonObject
        fun setting(settings: JsonObject, name: String) = settings.getAsJsonArray("value")
            .map { it.asJsonObject }.first { it["name"].asString == name }

        val first = json("hello-screen")
        val initialEnabled = first["animationEnabled"].asBoolean
        val initialShowBuild = first["showBuild"].asBoolean
        check(first["build"].asString == GitHubBuildInfo.label)
        check(json("module/HelloScreen")["enabled"].asBoolean == initialEnabled)
        val settings = json("modules/settings?name=HelloScreen")
        check(setting(settings, "ShowBuild")["value"].asBoolean == initialShowBuild)
        check(setting(settings, "Build")["value"].asString == GitHubBuildInfo.label)

        val changed = settings.deepCopy()
        setting(changed, "ShowBuild").addProperty("value", !initialShowBuild)
        setting(changed, "Build").addProperty("value", "#987654") // attempted forgery, never persisted
        var toggled = false
        try {
            request("modules/settings?name=HelloScreen", "PUT", changed.toString())
            val afterSettings = json("hello-screen")
            check(afterSettings["showBuild"].asBoolean != initialShowBuild)
            check(afterSettings["build"].asString == GitHubBuildInfo.label) { "Build label was mutable" }
            check(setting(json("modules/settings?name=HelloScreen"), "Build")["value"].asString == GitHubBuildInfo.label)

            request("modules/toggle", "POST", "{\"name\":\"HelloScreen\"}")
            toggled = true
            check(json("hello-screen")["animationEnabled"].asBoolean != initialEnabled)
            check(json("module/HelloScreen")["enabled"].asBoolean != initialEnabled)
        } finally {
            if (toggled) request("modules/toggle", "POST", "{\"name\":\"HelloScreen\"}")
            request("modules/settings?name=HelloScreen", "PUT", settings.toString())
        }
        check(json("hello-screen")["showBuild"].asBoolean == initialShowBuild)
        check(json("hello-screen")["animationEnabled"].asBoolean == initialEnabled)
    }
}
