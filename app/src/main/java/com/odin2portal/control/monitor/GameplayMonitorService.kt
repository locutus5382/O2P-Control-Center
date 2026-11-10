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
import com.odin2portal.control.quick.QuickTileUpdates
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
    val message: String? = null,
    // Cross-process readbacks: never infer that a selected profile applied.
    val cpuDisplay: String? = null,
    val gpuDisplay: String? = null,
    val lifecycleStatus: String? = null,
    val lifecycleEvents: List<String> = emptyList(),
    val monitorProcessId: Int? = null,
    val recoveryPending: Boolean = false
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
    private lateinit var journal: SessionReliabilityJournal

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = GameSessionStore(this)
        detector = ForegroundAppDetector(this)
        collector = DirectMonitor(application)
        hudSettings = HudPreferences(applicationContext)
        hudOverlay = HudOverlay(this, hudSettings)
        journal = SessionReliabilityJournal(applicationContext)
        journal.event("SERVICE_CREATED")
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
            return if (job?.isActive == true) START_STICKY else START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            journal.stopRequested()
            hudOverlay.hide()
            stopping = true
            job?.cancel() ?: run {
                GameplayRuntime.update(GameplayStatus(message = "Recording stopped"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                journal.event("STOP_WITHOUT_ACTIVE_JOB")
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY
        if (stopping) return START_NOT_STICKY

        // Android may recreate a previously user-started foreground service with
        // a null intent. Only resume if the durable, recent, explicitly active
        // recording matches an open SQLite session. Never start after reboot.
        val stickyRestart = intent == null
        val previous = if (stickyRestart) journal.lease() else null
        val resumable = if (stickyRestart) runCatching {
            SessionResumePolicy.shouldResume(previous, System.currentTimeMillis(),
                previous?.sessionId?.let(db::isStillRecording) == true)
        }.getOrDefault(false) else false
        if (stickyRestart && !resumable) {
            journal.markOrphaned("No current user-started session to resume")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!stickyRestart && intent?.action != ACTION_START) return START_NOT_STICKY
        val resuming = stickyRestart && resumable
        val sampleMs = if (resuming) previous!!.sampleMs else intent!!.getLongExtra(EXTRA_SAMPLE_MS, 2000L)
            .takeIf { it in listOf(1000L, 2000L, 5000L) } ?: 2000L
        val historyMs = if (resuming) previous!!.historyMs else intent!!.getLongExtra(EXTRA_HISTORY_MS, RecordingIntervals.defaultMs)
            .takeIf(RecordingIntervals::valid) ?: RecordingIntervals.defaultMs
        val manual = if (resuming) previous!!.manualLabel
            else (intent!!.getStringExtra(EXTRA_LABEL) ?: "").trim().take(64)
        val startedAt = if (resuming) previous!!.startedAt else System.currentTimeMillis()
        journal.event(if (resuming) "STICKY_RESTART" else "START_REQUESTED",
            if (resuming) "Resuming " + previous!!.sessionId else "New user-started recording")

        // A visible Stop notification is required for this opt-in beta.
        // Do not start silently if the user has denied notifications.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            GameplayRuntime.update(GameplayStatus(
                message = "Gameplay recording not started: allow notifications to show the Stop action"
            ))
            journal.event("START_REJECTED", "Notification permission unavailable")
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
            journal.event("FOREGROUND_START_FAILED", e.javaClass.simpleName + ": " + e.message)
            stopSelf()
            return START_NOT_STICKY
        }

        GameplayRuntime.update(GameplayStatus(
            starting = true, startedAt = startedAt,
            message = if (resuming) "Recovering previous recording after service restart" else null
        ))
        QuickTileUpdates.refresh(applicationContext)
        job = scope.launch {
            var sessionId: Long? = null
            var completed = false
            var error: String? = null
            val history = SessionHistory(historyMs)
            var persistedCount = 0L
            var lastJournalAt = 0L
            var lastKnownEden = false
            var missingAttributionAfterEden = 0
            var transitionRecoveryAttempted = false
            var handledEdenActivityTransition = 0L
            var pendingEdenRecoveryAt: Long? = null

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
                    if (resuming) {
                        val old = previous!!.sessionId
                        check(db.isStillRecording(old)) { "Session no longer recoverable" }
                        sessionId = old
                        journal.event("SESSION_RESUMED", "recording=$old")
                        journal.heartbeat(old, System.currentTimeMillis())
                    } else {
                        db.recoverInterrupted(startedAt)
                        sessionId = db.start(startedAt, manual, historyMs)
                        journal.begin(SessionLease(
                            sessionId!!, startedAt, System.currentTimeMillis(),
                            sampleMs, historyMs, manual, false,
                            android.os.Process.myPid(), "SERVICE_STARTED"
                        ))
                    }
                }
                GameplayRuntime.update(GameplayRuntime.state.value.copy(
                    running = true, starting = false, sessionId = sessionId,
                    message = if (detector.hasUsageAccess()) null
                              else "Usage Access not granted: app attribution will be unverified"
                ))
                QuickTileUpdates.refresh(applicationContext)
                while (isActive && !stopping) {
                    val sample = collector.sample()
                    val app = withContext(Dispatchers.IO) {
                        detector.current(sample.timestamp, manual)
                    }
                    // Eden may pause/replace its launcher Activity while a game boots.
                    // This *may* drop attribution and alter WindowManager stacking.
                    // Offer one bounded, user-configurable refresh, never a retry loop.
                    val activitySequence = detector.edenActivityTransitions
                    if (activitySequence > handledEdenActivityTransition) {
                        handledEdenActivityTransition = activitySequence
                        pendingEdenRecoveryAt = sample.timestamp + 2_500L
                    }
                    if (app.packageName == ForegroundAppDetector.EDEN_PACKAGE) {
                        lastKnownEden = true
                        missingAttributionAfterEden = 0
                    } else if (lastKnownEden && app.packageName == null) {
                        missingAttributionAfterEden++
                    } else if (app.packageName != null) {
                        lastKnownEden = false
                        missingAttributionAfterEden = 0
                    }
                    val recoveredAfterEvent = pendingEdenRecoveryAt?.let {
                        sample.timestamp >= it
                    } == true
                    val recoveredAfterAttributionLoss =
                        lastKnownEden && missingAttributionAfterEden >= 2
                    val recoverAtTransition = !transitionRecoveryAttempted &&
                        (recoveredAfterEvent || recoveredAfterAttributionLoss) &&
                        hudSettings.autoRecoverGameLaunch && hudSettings.floatingVisible &&
                        hudSettings.mode == HudDisplayMode.FLOATING_WITH_NOTIFICATION
                    history.offer(sample, app)
                    flush()
                    if (sample.timestamp - lastJournalAt >= 10_000L) {
                        lastJournalAt = sample.timestamp
                        journal.heartbeat(sessionId!!, sample.timestamp)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (recoverAtTransition) {
                            // One attempt per session. This is not intended to override
                            // Android's protected-window overlay suppression policy.
                            transitionRecoveryAttempted = true
                            hudOverlay.hide()
                        }
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
                        message = hudOverlay.lastError ?:
                            if (recoverAtTransition)
                                "One HUD reattach after Eden activity transition; visibility unverified"
                            else current.message
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
                if (!stopping) journal.event("UNEXPECTED_CANCELLATION")
            } catch (e: Exception) {
                error = "Recording failed: " + (e.message ?: "unknown error")
                journal.event("RECORDING_EXCEPTION",
                    e.javaClass.simpleName + ": " + (e.message ?: "unknown"))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        history.closePartial()
                        if (sessionId != null) {
                            flush()
                            db.finish(
                                sessionId!!, System.currentTimeMillis(),
                                if (completed || stopping) "COMPLETED"
                                else if (error != null) "FAILED" else "INTERRUPTED"
                            )
                            journal.finish(sessionId!!,
                                if (completed || stopping) "COMPLETED"
                                else if (error != null) "FAILED" else "INTERRUPTED",
                                error ?: if (stopping) "User requested Stop" else "Recording ended")
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
                        QuickTileUpdates.refresh(applicationContext)
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // AYN/Eden launching a game may remove the O2P task; this must never
        // be interpreted as a user request to stop a foreground recording.
        journal.event("APP_TASK_REMOVED", "Recording continues if service remains alive")
        super.onTaskRemoved(rootIntent)
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
        journal.event("SERVICE_DESTROYED",
            if (stopping) "Stop requested" else "Service destroyed while active=" +
                (job?.isActive == true))
        hudOverlay.hide()
        job?.cancel()
        scope.cancel()
        val running = job
        if (running != null) running.invokeOnCompletion { db.close() }
        else db.close()
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
