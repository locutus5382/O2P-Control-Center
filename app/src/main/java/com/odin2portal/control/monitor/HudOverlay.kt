package com.odin2portal.control.monitor

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color

/**
 * A single small Android View overlay, NOT another Compose tree or sampling loop.
 * No fullscreen invisible window, no blur/refraction over gameplay, no root.
 *
 * All methods are invoked on the main thread from GameplayMonitorService.
 */
class HudOverlay(private val context: Context, private val settings: HudPreferences) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var container: LinearLayout? = null
    private var readout: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastSample: MonitorSample? = null
    private var dragging = false
    private var headerText: TextView? = null
    var lastError: String? = null
        private set

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()

    private fun canShow() = Settings.canDrawOverlays(context) &&
        settings.mode == HudDisplayMode.FLOATING_WITH_NOTIFICATION &&
        settings.floatingVisible &&
        context.getSystemService(PowerManager::class.java)?.isInteractive != false &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    fun render(sample: MonitorSample?) {
        lastSample = sample
        settings.refresh()
        if (!canShow()) {
            hide()
            return
        }
        try {
            if (container == null) attach()
            configure()
            val selected = settings.selectedMetrics()
            readout?.text = if (selected.isEmpty()) "No metrics selected" else
                selected.joinToString("\n") { metric ->
                    metric.label + "   " + (sample?.get(metric)?.display ?: "—")
                }
            container?.post { if (!dragging) reposition() }
            lastError = null
        } catch (e: Exception) {
            lastError = "HUD unavailable: " + (e.message ?: "window error")
            hide()
        }
    }

    private fun attach() {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(38, 48, 67), Color.rgb(18, 24, 37))
            ).apply {
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.argb(130, 211, 229, 255))
            }
            elevation = dp(5).toFloat()
        }
        val header = TextView(context).apply {
            setTextColor(Color.rgb(189, 210, 244))
            textSize = 10f
            text = "O2P  ·  DRAG TO MOVE"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        }
        val content = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        outer.addView(header)
        outer.addView(content)
        headerText = header
        readout = content

        var touchStartX = 0f
        var touchStartY = 0f
        var initialX = 0
        var initialY = 0
        outer.setOnTouchListener { _, event ->
            if (settings.locked) return@setOnTouchListener false
            val lp = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    initialX = lp.x
                    initialY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = initialX + (event.rawX - touchStartX).toInt()
                    val newY = initialY + (event.rawY - touchStartY).toInt()
                    val b = wm.currentWindowMetrics.bounds
                    val p = HudGeometry.position(
                        settings.dock, newX, newY, b.width(), b.height(),
                        outer.width.coerceAtLeast(dp(105)), outer.height.coerceAtLeast(dp(35)),
                        dp(12)
                    )
                    lp.x = p.x
                    lp.y = p.y
                    wm.updateViewLayout(outer, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val b = wm.currentWindowMetrics.bounds
                    val maxX = (b.width() - outer.width - dp(12)).coerceAtLeast(1)
                    val maxY = (b.height() - outer.height - dp(12)).coerceAtLeast(1)
                    settings.savePosition(b.width() >= b.height(),
                        lp.x.toFloat() / maxX, lp.y.toFloat() / maxY)
                    true
                }
                else -> false
            }
        }
        // Only this HUD rectangle receives touch while unlocked.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(16)
        lp.y = dp(58)
        lp.alpha = .70f
        wm.addView(outer, lp)
        params = lp
        container = outer
    }

    private fun configure() {
        val lp = params ?: return
        val view = container ?: return
        // Android 12 blocks unsafe untrusted overlay pass-through touches.
        // Keep a locked, untouchable overlay at <=0.8 window opacity.
        val desiredAlpha = settings.opacity.coerceIn(.30f, .90f)
        lp.alpha = if (settings.locked) desiredAlpha.coerceAtMost(.79f) else desiredAlpha
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (settings.locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        val scale = settings.scale
        val base = dp(11).toFloat() / density
        readout?.textSize = base * scale
        headerText?.textSize = 10f * scale
        wm.updateViewLayout(view, lp)
    }

    private fun reposition() {
        val view = container ?: return
        val lp = params ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val landscape = bounds.width() >= bounds.height()
        val (fractionX, fractionY) = settings.position(landscape)
        val maxX = (bounds.width() - view.width - dp(12)).coerceAtLeast(0)
        val maxY = (bounds.height() - view.height - dp(12)).coerceAtLeast(0)
        val desiredX = (maxX * fractionX).toInt()
        val desiredY = (maxY * fractionY).toInt()
        val point = HudGeometry.position(settings.dock, desiredX, desiredY,
            bounds.width(), bounds.height(), view.width, view.height, dp(12))
        if (point.x != lp.x || point.y != lp.y) {
            lp.x = point.x
            lp.y = point.y
            wm.updateViewLayout(view, lp)
        }
    }

    /**
     * Diagnostic, not a visibility assertion: Android may suppress an attached
     * TYPE_APPLICATION_OVERLAY while the foreground app is protected/immersive.
     * There is no trusted public API to confirm on-screen pixels of this window.
     */
    fun diagnosticStatus(): String {
        if (!Settings.canDrawOverlays(context)) return "No Display over other apps permission"
        if (settings.mode == HudDisplayMode.NOTIFICATION_ONLY) return "Notification-only mode"
        if (!settings.floatingVisible) return "Floating HUD hidden by user"
        if (context.getSystemService(PowerManager::class.java)?.isInteractive == false)
            return "HUD hidden because screen is not interactive"
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
            return "HUD hidden on lockscreen"
        val view = container ?: return lastError ?: "Overlay window not attached"
        val lp = params
        return if (view.isAttachedToWindow && lp != null) {
            (lastError?.let { it + " · " } ?: "") +
                "Window attached at (" + lp.x + "," + lp.y + ")" +
                ", " + view.width + "x" + view.height +
                ". Actual visibility above games cannot be verified by Android."
        } else lastError ?: "Overlay window not attached"
    }

    fun hide() {
        val view = container
        if (view != null) {
            val failure = runCatching { wm.removeViewImmediate(view) }.exceptionOrNull()
            if (failure != null && view.isAttachedToWindow) {
                // Do not pretend the HUD was removed after WindowManager
                // refused removal. Preserve the reference for the service's
                // final cleanup retry, and export the real failure.
                lastError = "HUD detach failed: " + failure.javaClass.simpleName +
                    ": " + (failure.message ?: "window still attached").take(100)
                return
            }
        }
        container = null
        params = null
        readout = null
        headerText = null
        dragging = false
        // Keep a genuine earlier HUD rendering error available to Diagnostics.
        // Only clear the detach error after a later removal succeeds.
        if (lastError?.startsWith("HUD detach failed:") == true) lastError = null
    }
}
