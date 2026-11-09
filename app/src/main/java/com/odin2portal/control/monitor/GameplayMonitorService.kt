package com.odin2portal.control.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.odin2portal.control.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GameplayStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val sessionId: Long? = null,
    val startedAt: Long? = null,
    val lastSampleAt: Long? = null,
    val foregroundLabel: String = "Not identified",
    val attribution: String = "Unknown",
    val intervalsSaved: Int = 0,
    val latestSample: MonitorSample? = null,
    val hudDiagnostic: String? = null,
    val message: String? = null
)

/** In-process status for the UI. History itself is durable in the SQLite store. */
object GameplayRuntime {
    private val live = MutableStateFlow(GameplayStatus())
    val state = live.asStateFlow()
    fun update(value: GameplayStatus) { live.value = value }
}

/** User-started specialUse FGS, no boot receiver, no sysfs writes, no silent monitoring. */
class GameplayMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var stopping = false
    private var lastNotificationAt = 0L

    private lateinit var db: GameSessionStore
    private lateinit var detector: ForegroundAppDetector
    private lateinit var collector: DirectMonitor
    private lateinit var hudSettings: HudPreferences
    private lateinit var hudOverlay: HudOverlay

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = GameSessionStore(this)
        detector = ForegroundAppDetector(this)
        collector = DirectMonitor(application)
        hudSettings = HudPreferences(applicationContext)
        hudOverlay = HudOverlay(this, hudSettings)
        val channel = NotificationChannel(
            CHANNEL_ID, "O2P Gameplay Monitoring", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "User-started performance recording during gameplay"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_HUD ||
            intent?.action == ACTION_REFRESH_HUD ||
            intent?.action == ACTION_REATTACH_HUD) {
            if (job?.isActive == true) {
                if (intent.action == ACTION_TOGGLE_HUD) {
                    hudSettings.changeFloatingVisible(!hudSettings.floatingVisible)
                } else {
                    hudSettings.refresh()
                    if (intent.action == ACTION_REATTACH_HUD) {
                        // User-initiated recovery for window-manager changes at game launch.
                        // Never loop/retry against an app that explicitly hides overlays.
                        hudOverlay.hide()
                    }
                }
                hudOverlay.render(GameplayRuntime.state.value.latestSample)
                val diagnostic = hudOverlay.diagnosticStatus()
                GameplayRuntime.update(GameplayRuntime.state.value.copy(
                    hudDiagnostic = diagnostic,
                    message = if (intent.action == ACTION_REATTACH_HUD)
                        "HUD window refreshed; actual visibility in Eden cannot be verified"
                    else GameplayRuntime.state.value.message
                ))
                updateNotification("O2P gameplay recording",
                    if (intent.action == ACTION_REATTACH_HUD)
                        "HUD refresh requested · visibility unknown"
                    else if (hudSettings.floatingVisible) "HUD requested" else "HUD hidden")
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            hudOverlay.hide()
            stopping = true
            job?.cancel() ?: run {
                GameplayRuntime.update(GameplayStatus(message = "Recording stopped"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || job?.isActive == true || stopping) {
            return START_NOT_STICKY
        }

        val sampleMs = intent.getLongExtra(EXTRA_SAMPLE_MS, 2000L)
            .takeIf { it in listOf(1000L, 2000L, 5000L) } ?: 2000L
        val historyMs = intent.getLongExtra(EXTRA_HISTORY_MS, RecordingIntervals.defaultMs)
            .takeIf(RecordingIntervals::valid) ?: RecordingIntervals.defaultMs
        val manual = (intent.getStringExtra(EXTRA_LABEL) ?: "").trim().take(64)
        val startedAt = System.currentTimeMillis()

        // A visible Stop notification is required for this opt-in beta.
        // Do not start silently if the user has denied notifications.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            GameplayRuntime.update(GameplayStatus(
                message = "Gameplay recording not started: allow notifications to show the Stop action"
            ))
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val initial = notification("Starting performance recording", "Tap to open O2P Control Center")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, initial)
            }
        } catch (e: RuntimeException) {
            GameplayRuntime.update(GameplayStatus(message = "Cannot start monitoring: " + e.message))
            stopSelf()
            return START_NOT_STICKY
        }

        GameplayRuntime.update(GameplayStatus(starting = true, startedAt = startedAt))
        job = scope.launch {
            var sessionId: Long? = null
            var completed = false
            var error: String? = null
            val history = SessionHistory(historyMs)
            var persistedCount = 0L

            suspend fun flush() {
                val totalCompleted = history.rowCount.toLong() + history.droppedRows
                val added = (totalCompleted - persistedCount).toInt()
                if (added <= 0) return
                val rows = history.snapshot().takeLast(added)
                withContext(Dispatchers.IO) { db.append(sessionId!!, rows) }
                persistedCount += rows.size
            }

            try {
                withContext(Dispatchers.IO) {
                    db.recoverInterrupted(startedAt)
                    sessionId = db.start(startedAt, manual, historyMs)
                }
                GameplayRuntime.update(GameplayRuntime.state.value.copy(
                    running = true, starting = false, sessionId = sessionId,
                    message = if (detector.hasUsageAccess()) null
                              else "Usage Access not granted: app attribution will be unverified"
                ))
                while (isActive && !stopping) {
                    val sample = collector.sample()
                    val app = withContext(Dispatchers.IO) {
                        detector.current(sample.timestamp, manual)
                    }
                    history.offer(sample, app)
                    flush()
                    withContext(Dispatchers.Main.immediate) {
                        hudOverlay.render(sample)
                    }
                    val current = GameplayRuntime.state.value
                    GameplayRuntime.update(current.copy(
                        running = true,
                        lastSampleAt = sample.timestamp,
                        foregroundLabel = app.label.ifBlank { "Not identified" },
                        attribution = app.attribution.description,
                        intervalsSaved = persistedCount.toInt(),
                        latestSample = sample,
                        hudDiagnostic = hudOverlay.diagnosticStatus(),
                        message = hudOverlay.lastError ?: current.message
                    ))
                    val elapsed = SystemClock.elapsedRealtime()
                    if (elapsed - lastNotificationAt > 12_000L) {
                        lastNotificationAt = elapsed
                        val title = if (app.attribution == AppAttribution.USAGE_EVENTS)
                            "Recording: " + app.label else "Recording: app unverified"
                        val cpu = sample.get(Metric.SOC_TEMP)?.display ?: "—"
                        val gpu = sample.get(Metric.GPU_TEMP)?.display ?: "—"
                        // This fallback remains readable when Android/Eden hides floating overlays.
                        updateNotification(title,
                            "CPU " + cpu + " · GPU " + gpu +
                                " · " + persistedCount + " intervals")
                    }
                    delay(sampleMs)
                }
                completed = true
            } catch (_: CancellationException) {
                completed = stopping
            } catch (e: Exception) {
                error = "Recording failed: " + (e.message ?: "unknown error")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        history.closePartial()
                        if (sessionId != null) {
                            flush()
                            db.finish(
                                sessionId!!, System.currentTimeMillis(),
                                if (completed || stopping) "COMPLETED" else "FAILED"
                            )
                        }
                    } catch (e: Exception) {
                        error = "Unable to save completed session: " + (e.message ?: "storage error")
                    } finally {
                        val current = GameplayRuntime.state.value
                        GameplayRuntime.update(current.copy(
                            running = false, starting = false,
                            intervalsSaved = (history.rowCount.toLong() + history.droppedRows).toInt(),
                            message = error ?: "Session stopped; history saved on device"
                        ))
                        withContext(Dispatchers.Main.immediate) { hudOverlay.hide() }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, description: String): Notification {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val open = PendingIntent.getActivity(
            this, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this, 3,
            Intent(this, GameplayMonitorService::class.java).setAction(ACTION_TOGGLE_HUD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val refreshHud = PendingIntent.getService(
            this, 4,
            Intent(this, GameplayMonitorService::class.java).setAction(ACTION_REATTACH_HUD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, GameplayMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.take(60))
            .setContentText(description.take(120))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (hudSettings.mode == HudDisplayMode.FLOATING_WITH_NOTIFICATION) {
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_view,
                if (hudSettings.floatingVisible) "Hide HUD" else "Show HUD", toggle
            ).build())
            // The user's game can alter window stacking when starting a renderer.
            // One explicit refresh is safer than a continuous reattach loop.
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_popup_sync, "Refresh HUD", refreshHud
            ).build())
        }
        return builder.addAction(Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop
        ).build()).build()
    }

    private fun updateNotification(title: String, description: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(title, description))
        } catch (_: SecurityException) {
            // Android 13+ can hide notifications when the user revokes permission.
            // The Monitor screen retains its own Stop control.
        }
    }

    override fun onDestroy() {
        hudOverlay.hide()
        job?.cancel()
        scope.cancel()
        db.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.odin2portal.control.gameplay.START"
        const val ACTION_STOP = "com.odin2portal.control.gameplay.STOP"
        const val ACTION_REFRESH_HUD = "com.odin2portal.control.gameplay.REFRESH_HUD"
        const val ACTION_REATTACH_HUD = "com.odin2portal.control.gameplay.REATTACH_HUD"
        const val ACTION_TOGGLE_HUD = "com.odin2portal.control.gameplay.TOGGLE_HUD"
        const val EXTRA_SAMPLE_MS = "sample_interval"
        const val EXTRA_HISTORY_MS = "record_interval"
        const val EXTRA_LABEL = "manual_session_label"
        const val CHANNEL_ID = "gameplay_monitor"
        const val NOTIFICATION_ID = 3301
    }
}
