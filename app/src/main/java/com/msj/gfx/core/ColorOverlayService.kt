package com.msj.gfx.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.msj.gfx.MainActivity
import com.msj.gfx.R

/**
 * Full-screen tint layer drawn over whatever is on screen - Free Fire, PUBG,
 * anything.
 *
 * Scope, stated plainly because it is the difference between this working and
 * not working: this is a normal TYPE_APPLICATION_OVERLAY window composited by
 * SurfaceFlinger on top of the app. We do not sample the game's pixels and we
 * do not touch its process. That means we cannot run a colour matrix over the
 * game - a colour matrix needs the input pixels, and the input pixels belong
 * to a process we are deliberately not in.
 *
 * What a tint layer does instead is change what your eye reads. A small dark
 * layer deepens mids, which is why a warm-black overlay makes saturated art
 * look richer on an OLED; a warm or cool shift counteracts the panel's own
 * white point. That is a real, zero-latency, root-free effect, and it is what
 * apps in this category do. It is not a saturation re-map.
 *
 * For a true colour-matrix re-map there are exactly two routes: the OEM's
 * display pipeline (see DisplayController.setOemVividMode), or screen capture
 * and re-presentation, which costs 50-150ms and is unusable for play.
 */
class ColorOverlayService : android.app.Service() {

    private var overlay: View? = null
    private var wm: WindowManager? = null

    // The view is created once and re-parented on rebuild, so a rotation does
    // not churn a fresh View through the service.


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Ctx.install(applicationContext)
        if (!hasOverlayPermission()) {
            stopSelf()
            return
        }
        ensureChannel()
        startForegroundCompat()
        apply(SettingsStore.get().tintDepth.value, SettingsStore.get().tintWarmth.value)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_UPDATE -> {
                val depth = intent.getIntExtra(EXTRA_DEPTH, -1)
                val warmth = intent.getIntExtra(EXTRA_WARMTH, Int.MIN_VALUE)
                if (depth != -1) SettingsStore.get().setTintDepth(depth)
                if (warmth != Int.MIN_VALUE) SettingsStore.get().setTintWarmth(warmth)
            }
        }
        if (!hasOverlayPermission()) { stopSelf(); return START_NOT_STICKY }
        apply(SettingsStore.get().tintDepth.value, SettingsStore.get().tintWarmth.value)
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { overlay?.let { wm?.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    /**
     * Rebuilds the layer only when its parameters change, because a
     * WindowManager update is a full relayout of a fullscreen window and doing
     * that every slider tick is visible as a hitch over the game.
     */
    private fun apply(depth: Int, warmth: Int) {
        val want = colorFor(depth, warmth)
        val current = overlay
        if (current != null && want == currentTag) {
            runCatching { current.setBackgroundColor(want) }
            return
        }

        if (current != null) {
            runCatching { wm?.removeView(current) }
            overlay = null
            currentTag = null
        }
        if (depth == 0 && warmth == 0) return

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this).apply { setBackgroundColor(want) }
        runCatching { wm?.addView(view, buildParams()) }
        overlay = view
        currentTag = want
    }

    /**
     * Fullscreen, on every screen shape we are likely to meet.
     *
     * Three things stop a naive MATCH_PARENT overlay from being fullscreen:
     *
     *  - The display cutout. FLAG_LAYOUT_IN_SCREEN does not extend into the
     *    notch or punch-hole, so on a portrait phone the strip containing the
     *    camera and the status bar stayed untinted - a visible band along the
     *    top of the game. layoutInDisplayCutoutMode ALWAYS is the fix, with
     *    SHORT_EDGES as the API 28-29 fallback.
     *
     *  - FLAG_LAYOUT_NO_LIMITS makes MATCH_PARENT unreliable on some OEM
     *    builds, where the window gets laid out against the app area rather
     *    than the physical display. So the size is taken explicitly from
     *    currentWindowMetrics, falling back to the raw display metrics.
     *
     *  - Rotation and foldables change the bounds, so the params are rebuilt
     *    on configuration change rather than left at whatever the screen was
     *    when the service started.
     */
    private fun buildParams(): WindowManager.LayoutParams {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (w, h) = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                // getRealMetrics fills the object and returns void.
                val d = wm.defaultDisplay
                @Suppress("DEPRECATION")
                val dm = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
                dm.widthPixels to dm.heightPixels
            }
        }.getOrElse { Pair(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT) }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(
            w, h, type,
            // NOT_TOUCHABLE is the important one: every touch has to fall
            // through to the game underneath, otherwise the layer swallows
            // the controls the player is aiming with. NOT_FOCUSABLE stops it
            // stealing IME focus, NOT_TOUCH_MODAL lets touches pass through
            // outside our bounds, and the LAYOUT flags plus the cutout mode
            // are what make the window genuinely cover the panel.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** Rotation, unfolding, display switch: the bounds moved, so rebuild. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = overlay ?: return
        runCatching { wm?.updateViewLayout(v, buildParams()) }
            .onFailure {
                // Some OEM builds refuse updateViewLayout across a display
                // change; fall back to a clean re-add.
                runCatching { wm?.removeView(v) }
                overlay = null
                currentTag = null
                apply(SettingsStore.get().tintDepth.value, SettingsStore.get().tintWarmth.value)
            }
    }

    private fun colorFor(depth: Int, warmth: Int): Int {
        val d = depth.coerceIn(0, 40)
        val w = warmth.coerceIn(-60, 60)
        // Depth darkens the frame a little; warmth tints it amber or blue.
        // When both are zero we remove the layer entirely rather than leave a
        // fully transparent fullscreen window up, because an empty window is
        // still a compositing cost on the SoC.
        if (d == 0 && w == 0) return Color.TRANSPARENT
        // Alpha used to come from depth alone, so a warmth-only setting had
        // alpha 0 and tinted nothing at all - the warmth slider looked dead
        // until you also dragged depth. Warmth now contributes its own floor.
        val fromDepth = d * 2.55f
        val fromWarmth = kotlin.math.abs(w) * 1.6f
        val alpha = kotlin.math.max(fromDepth, fromWarmth).toInt().coerceIn(0, 110)
        val shift = (w * 2f).toInt().coerceIn(-60, 60)
        val r = (128 + shift).coerceIn(0, 255)
        val b = (128 - shift).coerceIn(0, 255)
        return Color.argb(alpha, r, 128, b)
    }

    private var currentTag: Int? = null

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun startForegroundCompat() = runCatching {
        val n = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("MSJ GFX visual layer")
            .setContentText("Colour overlay running over other apps")
            .setSmallIcon(R.drawable.ic_stat_gfx)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 2, Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .createNotificationChannel(
                android.app.NotificationChannel(
                    CH_ID, "Visual layer", android.app.NotificationManager.IMPORTANCE_MIN
                )
            )
    }

    companion object {
        private const val CH_ID = "msj_gfx_viz"
        private const val NOTIF_ID = 1003
        const val ACTION_STOP = "com.msj.gfx.VIZ_STOP"
        const val ACTION_UPDATE = "com.msj.gfx.VIZ_UPDATE"
        const val EXTRA_DEPTH = "depth"
        const val EXTRA_WARMTH = "warmth"

        fun start(ctx: Context, depth: Int, warmth: Int) = runCatching {
            ctx.startForegroundService(
                Intent(ctx, ColorOverlayService::class.java)
                    .setAction(ACTION_UPDATE)
                    .putExtra(EXTRA_DEPTH, depth)
                    .putExtra(EXTRA_WARMTH, warmth)
            )
        }

        /**
         * startForegroundService, not startService.
         *
         * A background startService throws IllegalStateException from Android
         * 8.0 onward, and the watcher calls this from a background coroutine the
         * moment a game leaves the foreground - which is exactly the case that
         * is not allowed. Wrapped in runCatching, so the old version failed
         * silently every time and the tint was never actually removed. Going
         * through the foreground path works because the service is already
         * foreground, and ACTION_STOP calls stopSelf() immediately.
         */
        fun stop(ctx: Context) = runCatching {
            ctx.startForegroundService(
                Intent(ctx, ColorOverlayService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
