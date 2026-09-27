package com.msj.gfx.core

/**
 * A one-line plain-English answer to "why is nothing being detected?".
 *
 * A detector that silently reports nothing is indistinguishable from a broken
 * one, so every failure path states which of the possible causes it is.
 */
object BoostDiagnostics {

    fun explain(hit: GameDetector.Hit?): String = when {
        !MemoryTools.hasUsageAccess() ->
            "No Usage access. Android 5.1+ hides other apps from us without it."

        hit != null && hit.known ->
            "Matched ${hit.label} (${hit.packageName}) from the preset catalog."

        hit != null ->
            "Matched ${hit.label} (${hit.packageName}) by shape - game, but no preset."

        !GameCatalog.launchableApps().isEmpty() ->
            "Usage access is granted and we can see " +
                "${GameCatalog.launchableApps().size} apps, but no game is in the " +
                "foreground right now. Start a game and wait a few seconds."

        else ->
            "We cannot enumerate installed apps at all. The <queries> block in the " +
                "manifest is not taking effect on this device."
    }
}
