package com.msj.gfx.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.msj.gfx.MainActivity
import com.msj.gfx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The long-lived half of the app. Foreground + START_STICKY so Android's
 * low-memory killer treats us as user-visible and skips us. Every loop body is
 * wrapped: one thrown exception kills the coroutine, and a dead coroutine in a
 * START_STICKY service is exactly the "it force closed itself" bug we are
 * trying not to have.
 */
class CoolerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var watcherJob: Job? = null
    private val watcher = GameWatcher(scope)

    private val settings by lazy { SettingsStore.get() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Ctx.install(applicationContext)
        ensureChannel()
        promote()

        watcher.onGameLaunched = { game ->
            if (settings.autoTrim.value) {
                scope.launch {
                    // Let the game finish its own loading before touching memory.
                    delay(3500)
                    MemoryTools.trim()
                }
            }
            notify(
                buildNotification(
                    "Booster armed for ${game.label}",
                    "Free RAM ${MemoryTools.freeRamMb()} MB"
                )
            )
        }
        watcher.onGameExited = { game ->
            notify(buildNotification("${game.label} closed", "Standing by"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive: OEM ROMs have been known to deliver a null intent on restart.
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        promote()

        if (monitorJob?.isActive != true) {
            monitorJob = scope.launch { monitorLoop() }
        }
        if (watcherJob?.isActive != true) {
            watcherJob = scope.launch { watcher.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { watcher.stop() }
        monitorJob?.cancel()
        watcherJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recents should not stop the booster while
        // the user is still in a match.
        super.onTaskRemoved(rootIntent)
        if (watcher.current.value != null) return
        stopSelf()
    }

    private suspend fun monitorLoop() {
        var heatTicks = 0
        while (scope.isActive) {
            val s = runCatching { PerfMonitor.sample() }.getOrNull()
            if (s != null) {
                if (s.throttling) heatTicks++ else heatTicks = 0

                // Sustained heat is the real framerate killer. Three ticks over
                // ~12s means the SoC is throttling and dropping to Smooth beats
                // stuttering at Ultra.
                if (heatTicks == 3 && settings.autoTrim.value) {
                    MemoryTools.cooldown(400)
                    MemoryTools.trim()
                    notify(
                        buildNotification(
                            "Device is throttling",
                            "Drop in-game graphics to Smooth"
                        )
                    )
                }

                notify(buildNotification(
                    "CPU ${s.cpuLoad}% - RAM ${s.ramUsedMb}/${s.ramTotalMb} MB",
                    (s.batteryTempC ?: s.cpuTempC)?.let { "${it.toInt()}°C" } ?: "temp n/a"
                ))
            }
            delay(4000)
        }
    }

    private fun promote() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, buildNotification("Booster active", "by M.S.J"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification("Booster active", "by M.S.J"))
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CH_ID, getString(R.string.boost_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.boost_channel_desc) }
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_gfx)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    private fun notify(n: Notification) = runCatching {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
    }

    companion object {
        const val ACTION_STOP = "com.msj.gfx.STOP"
        private const val CH_ID = "msj_gfx_boost"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) = runCatching {
            ctx.startForegroundService(Intent(ctx, CoolerService::class.java))
        }

        fun stop(ctx: Context) = runCatching {
            ctx.startService(
                Intent(ctx, CoolerService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
