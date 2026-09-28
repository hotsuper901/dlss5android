package com.msj.gfx.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.msj.gfx.MainActivity
import com.msj.gfx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Frametime / memory HUD drawn on top of whatever is on screen.
 *
 * This works on any app, including Free Fire and PUBG Mobile, because
 * TYPE_APPLICATION_OVERLAY is a normal user-granted window permission and the
 * overlay is simply composited on top by SurfaceFlinger. That is the entire
 * reason this part is honest: we are not injected into the game, we are drawn
 * over it.
 */
class GameOverlayService : android.app.Service() {

    private var root: LinearLayout? = null
    private var wm: WindowManager? = null
    private var tickScope: CoroutineScope? = null

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
        if (tickScope == null) startTicking()
    }

    @SuppressLint("InflateParams")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasOverlayPermission()) { stopSelf(); return START_NOT_STICKY }
        if (root == null) buildOverlay()
        startForegroundCompat()
        // Idempotent: the loop is guarded so a second ACTION_TOGGLE start
        // cannot spin up a second sampler.
        if (tickScope == null) startTicking()
        return START_STICKY
    }

    override fun onDestroy() {
        tickScope?.cancel()
        tickScope = null
        runCatching { root?.let { wm?.removeView(it) } }
        root = null
        overlayParams = null
        screenFlagOn = false
        super.onDestroy()
    }

    private fun buildOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val ctx = this
        val pad = dp(10f)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable().apply {
                setCornerRadius(dp(14f).toFloat())
                setColor(Color.argb(150, 7, 10, 20))
                setStroke(dp(1f), Color.argb(90, 0, 229, 255))
            }
        }

        panel.addView(label("MSJ GFX", 10f, Color.rgb(0, 229, 255)).apply { gravity = Gravity.END })

        msView = label("--\u00b0C", 18f, Color.rgb(61, 220, 151)).apply {
            gravity = Gravity.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        panel.addView(msView)

        ramView = label("RAM -- / --", 11f, Color.rgb(255, 194, 75)).apply { gravity = Gravity.END }
        panel.addView(ramView)

        // Drag handle - top of the panel doubles as the grab zone.
        root = panel

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f)
            y = dp(64f)
        }

        // "Keep screen awake" rides on the HUD window only while a game session
        // is actually active - the same bracket the watcher uses for the
        // wakelock. FLAG_KEEP_SCREEN_ON is the platform's preferred mechanism,
        // so it is used in addition to the session lock in PowerKeeper, which
        // covers the case where no window of ours exists. The tick loop keeps
        // it in step afterwards (see syncScreenFlag).
        screenFlagOn = SettingsStore.get().keepAwake.value && PowerKeeper.isScreenSessionActive()
        if (screenFlagOn) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        overlayParams = params

        runCatching { wm?.addView(panel, params) }
        attachDrag(panel, params)
    }

    /** Finger-drag repositioning, clamped so the panel can never be lost offscreen. */
    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val maxX = (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0)
                    params.x = (startX + (ev.rawX - touchX).toInt()).coerceIn(0, maxX)
                    params.y = (startY + (ev.rawY - touchY).toInt()).coerceIn(0, maxY)
                    runCatching { wm?.updateViewLayout(view, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun startTicking() {
        if (tickScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tickScope = scope
        scope.launch {
            while (scope.isActive) {
                // sampleBlocking() reads /sys and makes a binder call. Doing
                // that on the Handler thread freezes whatever is on screen
                // underneath the HUD, which is exactly when the user least
                // wants a stutter.
                runCatching {
                    val s = PerfMonitor.sampleBlocking()
                    val free = s.freeRamMb
                    val total = s.totalRamMb
                    val pct = s.deviceRamPct
                    val hot = s.throttling
                    val temp = s.hottestC
                    withContext(Dispatchers.Main) {
                        msView?.text = temp?.let { "${it.toInt()}\u00b0C" } ?: "--\u00b0C"
                        msView?.setTextColor(if (hot) Color.rgb(255, 122, 69) else Color.rgb(61, 220, 151))
                        ramView?.text = "RAM $free / $total"
                        ramView?.setTextColor(
                            if (pct >= 88) Color.rgb(255, 122, 69) else Color.rgb(255, 194, 75)
                        )
                        // Same cadence as the readouts: the screen-on flag
                        // follows a session starting or ending within 500ms.
                        syncScreenFlag()
                    }
                }
                delay(500)
            }
        }
    }

    /**
     * Keep the HUD window's FLAG_KEEP_SCREEN_ON in step with the game session.
     *
     * Only ever called on the main thread, because updateViewLayout is the only
     * way to change a flag on an attached window and it is not thread-safe.
     */
    private fun syncScreenFlag() {
        val want = SettingsStore.get().keepAwake.value && PowerKeeper.isScreenSessionActive()
        if (want == screenFlagOn) return
        val params = overlayParams ?: return
        val view = root ?: return
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        params.flags = if (want) params.flags or flag else params.flags and flag.inv()
        runCatching { wm?.updateViewLayout(view, params) }
        screenFlagOn = want
    }

    private fun label(text: String, sp: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setShadowLayer(dp(3f).toFloat(), 0f, dp(1f).toFloat(), Color.BLACK)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)

    private fun startForegroundCompat() = runCatching {
        val n = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("MSJ GFX HUD")
            .setContentText("Overlay running - drag the panel to move it")
            .setSmallIcon(R.drawable.ic_stat_gfx)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 1, Intent(this, MainActivity::class.java),
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
                    CH_ID, "HUD overlay", android.app.NotificationManager.IMPORTANCE_MIN
                )
            )
    }

    private var msView: TextView? = null
    private var ramView: TextView? = null

    /** The live window params, kept so the screen-on flag can be toggled in place. */
    private var overlayParams: WindowManager.LayoutParams? = null

    /** Whether the window currently carries FLAG_KEEP_SCREEN_ON. Main thread only. */
    private var screenFlagOn = false

    companion object {
        private const val CH_ID = "msj_gfx_hud"
        private const val NOTIF_ID = 1002
        const val ACTION_STOP = "com.msj.gfx.HUD_STOP"

        fun start(ctx: Context) = runCatching {
            ctx.startForegroundService(Intent(ctx, GameOverlayService::class.java))
        }
        fun stop(ctx: Context) = runCatching {
            ctx.startService(
                Intent(ctx, GameOverlayService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
