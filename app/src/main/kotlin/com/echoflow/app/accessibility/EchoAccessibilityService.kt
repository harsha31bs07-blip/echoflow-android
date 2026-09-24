package com.echoflow.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.echoflow.app.EchoRuntime
import com.echoflow.app.monitor.Announcer
import com.echoflow.app.monitor.SafetyMonitorOverlay
import com.echoflow.app.monitor.SnapshotExporter
import com.echoflow.core.bus.EchoEvent
import com.echoflow.core.gateway.ActionGateway
import com.echoflow.core.safety.ResumeResult
import com.echoflow.core.safety.ScreenVerdict
import com.echoflow.core.safety.SensitiveKind
import com.echoflow.core.safety.Trip

/**
 * Captures a debounced snapshot after UI events, runs SafetyGuard on every one, and publishes
 * the result. With the safety monitor enabled it also shows an overlay and speaks each change.
 */
class EchoAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var capturer: SnapshotCapturer
    private lateinit var announcer: Announcer
    private var overlay: SafetyMonitorOverlay? = null

    @Volatile private var lastActivity: String? = null
    private var pendingSince = 0L
    private var emptyRetries = 0
    private var recheckStage = 0

    // Main-thread state for announcements.
    private var lastAnnouncedKind: SensitiveKind? = null
    private var hasAnnounced = false
    @Volatile private var pendingHandOff: Trip? = null

    private val captureRunnable = Runnable { captureNow("event") }
    private val recheckRunnable = Runnable { captureNow("recheck") }
    private val tripListener: (Trip) -> Unit = { trip ->
        pendingHandOff = trip
        EchoRuntime.bus.emit(EchoEvent.SafetyTripped(trip))
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mainHandler.post { applyPrefs() } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        EchoRuntime.init(applicationContext)
        captureThread = HandlerThread("echo-capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        capturer = SnapshotCapturer(this)
        announcer = Announcer(this)
        val executor = AndroidActionExecutor(this, EchoRuntime.snapshots)
        EchoRuntime.attach(this, ActionGateway(EchoRuntime.guard, executor, EchoRuntime.snapshots))
        EchoRuntime.guard.addTripListener(tripListener)
        EchoRuntime.prefs.register(prefListener)
        applyPrefs()
        scheduleCapture(0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.takeIf { it.contains('.') }?.let { lastActivity = it }
        }
        scheduleCapture(DEBOUNCE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        EchoRuntime.guard.removeTripListener(tripListener)
        EchoRuntime.prefs.unregister(prefListener)
        EchoRuntime.detach(this)
        overlay?.hide()
        overlay = null
        if (::announcer.isInitialized) announcer.shutdown()
        if (::captureThread.isInitialized) captureThread.quitSafely()
        super.onDestroy()
    }

    /**
     * Debounce: wait until events go quiet for [DEBOUNCE_MS], but never delay a capture more than
     * [MAX_LATENCY_MS] behind the first pending event (animated screens never go quiet).
     */
    private fun scheduleCapture(delayMs: Long) {
        captureHandler.post {
            val now = SystemClock.uptimeMillis()
            if (pendingSince == 0L) pendingSince = now
            if (now - pendingSince < MAX_LATENCY_MS) {
                captureHandler.removeCallbacks(captureRunnable)
                captureHandler.postDelayed(captureRunnable, delayMs)
            } else if (!captureHandler.hasCallbacks(captureRunnable)) {
                captureHandler.post(captureRunnable)
            }
        }
    }

    private fun captureNow(trigger: String) {
        if (trigger == "event") pendingSince = 0L
        val live = capturer.capture(lastActivity, trigger)
        if (live == null) {
            if (emptyRetries++ < EMPTY_RETRIES) captureHandler.postDelayed(captureRunnable, EMPTY_RETRY_MS)
            return
        }
        emptyRetries = 0
        process(live)
        scheduleRecheckIfUnreadable(live, trigger)
    }

    /**
     * Some apps (Lynx/Compose/React Native screens) render content without firing accessibility
     * events, so the last event-driven capture can be an empty shell. Re-capture a screen with
     * nothing readable a few times before believing it.
     */
    private fun scheduleRecheckIfUnreadable(live: LiveSnapshot, trigger: String) {
        captureHandler.removeCallbacks(recheckRunnable)
        if (trigger == "event") recheckStage = 0
        val readable = live.snapshot.diagnostics?.labeledNodes ?: return
        if (readable > 0) {
            recheckStage = 0
        } else if (recheckStage < RECHECK_DELAYS_MS.size) {
            captureHandler.postDelayed(recheckRunnable, RECHECK_DELAYS_MS[recheckStage++])
        }
    }

    /** Publishes a capture, runs the guard on it, and updates the overlay and voice. Capture thread. */
    private fun process(live: LiveSnapshot): ScreenVerdict {
        EchoRuntime.snapshots.publish(live)
        val verdict = EchoRuntime.guard.onSnapshot(live.snapshot)
        EchoRuntime.bus.emit(EchoEvent.SnapshotUpdated(live.snapshot, verdict))

        mainHandler.post {
            overlay?.render(live.snapshot, verdict, EchoRuntime.guard.currentState)
            val handOff = pendingHandOff
            pendingHandOff = null
            if (!EchoRuntime.prefs.monitorEnabled || !EchoRuntime.prefs.speakEnabled) return@post
            when {
                handOff != null -> announcer.speak(handOff.handOffMessage)
                !hasAnnounced || verdict.primaryKind != lastAnnouncedKind ->
                    announcer.speak(verdict.primaryKind?.let { "Sensitive: ${it.spoken}" } ?: "Safe screen")
            }
            hasAnnounced = true
            lastAnnouncedKind = verdict.primaryKind
        }
        return verdict
    }

    private fun applyPrefs() {
        if (EchoRuntime.prefs.monitorEnabled) {
            val o = overlay ?: SafetyMonitorOverlay(this, ::dumpCurrent, ::rearm) { EchoRuntime.prefs.monitorEnabled = false }
                .also { overlay = it }
            o.show()
            o.render(EchoRuntime.snapshots.current(), EchoRuntime.guard.lastVerdict, EchoRuntime.guard.currentState)
        } else {
            overlay?.hide()
            overlay = null
        }
    }

    /**
     * Monitor "Dump" button: captures the screen fresh (a few tries, keeping the most readable one)
     * rather than trusting the last event-driven capture, then saves it redacted as a test fixture.
     */
    private fun dumpCurrent() {
        captureHandler.post {
            var best: LiveSnapshot? = null
            repeat(DUMP_ATTEMPTS) { attempt ->
                if (attempt > 0) Thread.sleep(DUMP_ATTEMPT_GAP_MS)
                val live = capturer.capture(lastActivity, "dump") ?: return@repeat
                if (best == null || readable(live) > readable(best!!)) best = live
            }
            val chosen = best
            val message = if (chosen == null) {
                "Nothing to capture on this screen"
            } else {
                val verdict = process(chosen)
                runCatching { "Saved ${SnapshotExporter.export(this, chosen.snapshot, verdict)}" }
                    .getOrElse { "Dump failed: ${it.message}" }
            }
            mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun readable(live: LiveSnapshot): Int = live.snapshot.diagnostics?.labeledNodes ?: 0

    /** Monitor "Re-arm" button: the same rule as saying "continue" — only works on a safe screen. */
    private fun rearm() {
        val snapshot = EchoRuntime.snapshots.current() ?: return
        val message = when (val r = EchoRuntime.guard.resume(snapshot, userConfirmed = true)) {
            ResumeResult.Resumed -> "Guard re-armed".also { EchoRuntime.bus.emit(EchoEvent.SafetyRearmed) }
            ResumeResult.NotTripped -> "Guard is already armed"
            ResumeResult.NeedsUserConfirmation -> "Needs confirmation"
            is ResumeResult.StillSensitive -> "Still ${r.verdict.primaryKind?.spoken ?: "sensitive"} — leave this screen first"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        overlay?.render(snapshot, EchoRuntime.guard.lastVerdict, EchoRuntime.guard.currentState)
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val MAX_LATENCY_MS = 1_000L
        const val EMPTY_RETRIES = 3
        const val EMPTY_RETRY_MS = 150L
        val RECHECK_DELAYS_MS = longArrayOf(500L, 500L, 1_000L) // re-capture at ~0.5 s, 1 s, 2 s
        const val DUMP_ATTEMPTS = 3
        const val DUMP_ATTEMPT_GAP_MS = 400L
    }
}
