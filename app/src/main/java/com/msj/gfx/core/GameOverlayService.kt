package com.msj.gfx.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var wm: WindowManager? = null
    private var tick: Runnable? = null

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
        if (tick == null) startTicking()
        return START_STICKY
    }

    override fun onDestroy() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        runCatching { root?.let { wm?.removeView(it) } }
        root = null
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

        fpsView = label("-- FPS", 22f, Color.WHITE).apply {
            gravity = Gravity.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        panel.addView(fpsView)

        msView = label("--.- ms", 12f, Color.rgb(61, 220, 151)).apply { gravity = Gravity.END }
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
        val r = object : Runnable {
            override fun run() {
                // Never let an exception in the HUD kill the service.
                runCatching {
                    val s = PerfMonitor.sampleBlocking()
                    fpsView?.text = "-- FPS"
                    msView?.text = "%.1f ms".format(s.frameMs.coerceAtLeast(0f))
                    ramView?.text = "RAM ${s.ramUsedMb} / ${s.ramTotalMb}"
                    ramView?.setTextColor(
                        if (s.ramPct >= 88) Color.rgb(255, 122, 69) else Color.rgb(255, 194, 75)
                    )
                }
                handler.postDelayed(this, 500L)
            }
        }
        tick = r
        handler.post(r)
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

    private var fpsView: TextView? = null
    private var msView: TextView? = null
    private var ramView: TextView? = null

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
