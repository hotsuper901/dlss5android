package com.msj.gfx

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.msj.gfx.core.CoolerService
import com.msj.gfx.core.GameOverlayService
import com.msj.gfx.core.SettingsStore
import com.msj.gfx.ui.MsjRoot
import com.msj.gfx.ui.MsjTheme

class MainActivity : ComponentActivity() {

    // Overlay permission is granted in Settings, which does not reliably return
    // a result code, so onResume re-reads the real value instead of trusting one.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SettingsStore.get().markRun()
        setContent {
            MsjTheme {
                val boosterOn by SettingsStore.get().boosterOn.collectAsState()
                MsjRoot(
                    overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        Settings.canDrawOverlays(this),
                    onRequestOverlay = ::requestOverlay,
                    onToggleBooster = { on ->
                        SettingsStore.get().setBooster(on)
                        if (on) CoolerService.start(this) else CoolerService.stop(this)
                    },
                    onToggleOverlay = { on ->
                        SettingsStore.get().setOverlay(on)
                        if (on) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                !Settings.canDrawOverlays(this)
                            ) requestOverlay()
                            else GameOverlayService.start(this)
                        } else GameOverlayService.stop(this)
                    },
                    onOpenGame = { pkg ->
                        runCatching {
                            startActivity(
                                packageManager.getLaunchIntentForPackage(pkg)
                                    ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", pkg, null))
                            )
                        }
                    }
                )
                if (boosterOn) {
                    LaunchedEffect(Unit) { CoolerService.start(this@MainActivity) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            val s = SettingsStore.get()
            if (s.overlayOn.value) GameOverlayService.start(this)
        }
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                overlayPermissionRequestCode
            )
        }
    }

    private companion object { const val overlayPermissionRequestCode = 4401 }
}
