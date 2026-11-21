/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.utils.client

import java.util.Properties

/** Build-time GitHub Actions workflow run number, not a release version or a guessed counter. */
object GitHubBuildInfo {
    private val runNumberPattern = Regex("[1-9][0-9]*")

    /**
     * Read the generated resource from both the mod class loader and the context class loader.
     * Fabric's nested/mod class loaders differ between development, standalone and Android builds.
     */
    val runNumber: String? = runCatching {
        val resourceNames = listOf(
            "resources/liquidbounce/github-build.properties",
            "liquidbounce/github-build.properties",
        )
        val loaders = listOfNotNull(
            GitHubBuildInfo::class.java.classLoader,
            Thread.currentThread().contextClassLoader,
        ).distinct()

        val raw = resourceNames.asSequence().mapNotNull { resource ->
            loaders.firstNotNullOfOrNull { loader ->
                loader.getResourceAsStream(resource)?.use { stream ->
                    Properties().apply { load(stream) }.getProperty("runNumber")
                }
            }
        }.firstOrNull()?.trim()

        raw?.takeIf(runNumberPattern::matches)
    }.getOrNull()

    val label: String = runNumber?.let { "#$it" } ?: "#unknown"
}
