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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.echoflow.app.BuildConfig
import com.echoflow.app.ui.EchoBubble
import com.echoflow.app.voice.VoiceIO
import com.echoflow.core.bus.EchoEvent
import com.echoflow.core.model.ScreenSnapshot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var bubble: EchoBubble? = null
    private val uiScope = MainScope()

    /** Speech in/out for the orchestrator; lives as long as the service. */
    lateinit var voice: VoiceIO
        private set

    @Volatile private var lastActivity: String? = null
    private var pendingSince = 0L
    private var emptyRetries = 0
    private var recheckStage = 0

    // Main-thread state for announcements.
    private var lastAnnounced: String? = null
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
        EchoRuntime.prefs.everConnected = true
        captureThread = HandlerThread("echo-capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        capturer = SnapshotCapturer(this)
        announcer = Announcer(this)
        val executor = AndroidActionExecutor(this, EchoRuntime.snapshots)
        EchoRuntime.attach(this, ActionGateway(EchoRuntime.guard, executor, EchoRuntime.snapshots))
        EchoRuntime.guard.addTripListener(tripListener)
        EchoRuntime.prefs.register(prefListener)
        voice = VoiceIO(this)
        voice.onStatus = { s -> EchoRuntime.orchestrator.status(s) }
        bubble = EchoBubble(this, EchoRuntime.orchestrator).also { it.show() }
        voice.onSpeechUi = { e -> bubble?.onSpeech(e) }
        // The Android accessibility button, gesture or shortcut (e.g. holding both volume keys,
        // once assigned to EchoFlow in Accessibility settings): speak without touching the screen.
        accessibilityButtonController.registerAccessibilityButtonCallback(object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                EchoRuntime.orchestrator.onSpeakPressed()
            }
        })
        uiScope.launch { EchoRuntime.orchestrator.state.collect { bubble?.render(it) } }
        if (BuildConfig.DEBUG) {
            // Debug builds only: `adb shell am broadcast -a com.echoflow.DEBUG_COMMAND --es text "..."`
            // stands in for speech during automated device testing. Never present in release APKs.
            registerReceiver(debugReceiver, IntentFilter(DEBUG_ACTION), RECEIVER_EXPORTED)
        }
        applyPrefs()
        scheduleCapture(0)
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val o = EchoRuntime.orchestrator
            when {
                intent.getBooleanExtra("snap", false) -> captureHandler.post {
                    val s = capturer.capture(lastActivity, "debug")?.snapshot ?: return@post
                    val lines = s.elements.filter { it.visible && (it.label != null || it.clickable || it.editable || it.scrollable || it.simpleClassName.contains("WebView")) }.joinToString("\n") { e ->
                        val b = e.bounds
                        "${e.index}^${e.parent}\t${(b.left + b.right) / 2},${(b.top + b.bottom) / 2}\t${e.simpleClassName}\t${if (e.clickable) "C" else ""}${if (e.editable) "E" else ""}${if (e.scrollable) "S" else ""}\t${e.viewId?.substringAfter(":id/") ?: ""}\t${(e.text ?: e.contentDescription ?: "").replace('\n', ' ').take(90)}"
                    }
                    java.io.File(filesDir, "debug_snap.txt").writeText("${s.packageName} ${s.activityName} ${EchoRuntime.guard.classify(s).label}\n$lines")
                    // The whole screen, to replay it in a unit test.
                    java.io.File(filesDir, "debug_snap.json").writeText(com.echoflow.core.model.SnapshotJson.encode(s))
                }
                intent.hasExtra("popup_rules") -> EchoRuntime.debugPopupRulesOff = !intent.getBooleanExtra("popup_rules", true)
                intent.getBooleanExtra("done", false) -> o.onDonePressed()
                intent.getBooleanExtra("stop", false) -> o.onStopPressed()
                else -> intent.getStringExtra("text")?.let(o::onTyped)
            }
        }
    }

    /**
     * While EchoFlow performs a gesture (a scroll swipe or a fallback tap), its own floating panel
     * lets touches through, so the gesture reaches the app underneath, not EchoFlow's buttons.
     */
    fun setOverlayPassThrough(on: Boolean) = mainHandler.post { bubble?.setPassThrough(on) }

    /** A system window (notification shade, lock screen) covering most of the screen. */
    private fun systemCoversScreen(): Boolean = runCatching {
        val screenH = resources.displayMetrics.heightPixels
        windows.any { w ->
            val r = android.graphics.Rect().also(w::getBoundsInScreen)
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM && r.height() > screenH / 2
        }
    }.getOrDefault(false)

    /** Synchronous capture for the teaching recorder (the screen *before* a tap changes it). */
    fun captureNow(): ScreenSnapshot? = capturer.capture(lastActivity, "teach")?.snapshot

    private val payHighlight by lazy { com.echoflow.app.ui.PayHighlight(this) }

    private var tapRelay: com.echoflow.app.ui.TapRelay? = null

    /**
     * Teaching in an app that hides its taps (W1): catch and pass on touches, recording each tap.
     * The EchoFlow panel is raised above the layer so Done and Stop stay tappable.
     */
    fun startTapRelay(recorder: com.echoflow.app.teach.TeachingRecorder) {
        if (tapRelay != null) return
        recorder.relayMode = true
        tapRelay = com.echoflow.app.ui.TapRelay(this) { x, y ->
            val snap = captureNow() ?: EchoRuntime.snapshots.current() ?: return@TapRelay true
            when (recorder.recordRelayTap(snap, x, y)) {
                com.echoflow.app.teach.TeachingRecorder.RelayTap.PAY -> { stopTapRelay(); false } // never passed on
                else -> true
            }
        }.also { it.start() }
        mainHandler.postDelayed({ bubble?.raise(); tapRelay?.setKeyboardUp(keyboardShowing()) }, 150)
    }

    fun stopTapRelay() {
        tapRelay?.stop()
        tapRelay = null
    }

    private fun keyboardShowing(): Boolean = runCatching {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /** At "Your turn": outline the screen's Pay / Place order button (display only; never tapped). */
    fun highlightPayButton() {
        val snap = EchoRuntime.snapshots.current() ?: return
        val risk = com.echoflow.core.safety.ActionRiskClassifier()
        val pay = snap.appElements()
            .filter { it.visible && it.clickable && it.bounds.area > 0 && it.bounds.height < snap.screenHeight / 4 }
            .filter { risk.assess(snap, it).risk == com.echoflow.core.safety.ActionRisk.COMMIT }
            .maxByOrNull { it.bounds.top } ?: return // the checkout bar sits at the bottom
        payHighlight.show(pay.bounds)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        // The notification shade or lock screen is up: step aside (the bubble would cover them).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg?.startsWith("com.android.systemui") == true)
        ) {
            bubble?.setShadeOpen(systemCoversScreen())
            if (tapRelay?.active == true) tapRelay?.setKeyboardUp(keyboardShowing())
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && !pkg.startsWith("com.android.systemui")) {
            // The bubble is for other apps; EchoFlow's own screens have their own controls. (Events
            // from the bubble's own overlay window also carry our package: only our activities hide it.)
            if (pkg != packageName) {
                bubble?.setVisible(true)
            } else if (event.className?.toString()?.let { it.startsWith("com.echoflow.app.ui.") && it.endsWith("Activity") } == true) {
                bubble?.setVisible(false)
            }
        }
        if (pkg == packageName) return
        EchoRuntime.orchestrator.activeRecorder?.let { rec -> runCatching { rec.onEvent(event) } }
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
        bubble?.hide()
        bubble = null
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(debugReceiver) }
        uiScope.cancel()
        if (::voice.isInitialized) voice.shutdown()
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
        if (trigger == "event") recheckStage = 0
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
        val preview = EchoRuntime.guard.classify(live.snapshot)
        val onlyOpaque = preview.kinds == setOf(SensitiveKind.OPAQUE_UNKNOWN)
        // Blank/unreadable screens (splash, loading) never hand off from here. Acting on them is
        // still refused: the gateway's gate re-checks the screen before every action.
        val verdict = if (onlyOpaque) preview else EchoRuntime.guard.onSnapshot(live.snapshot)
        EchoRuntime.bus.emit(EchoEvent.SnapshotUpdated(live.snapshot, verdict))
        EchoRuntime.orchestrator.activeRecorder?.onSnapshot(live.snapshot)

        mainHandler.post {
            overlay?.render(live.snapshot, verdict, EchoRuntime.guard.currentState)
            val handOff = pendingHandOff
            pendingHandOff = null
            // The orchestrator does the talking while teaching or running.
            if (!EchoRuntime.prefs.monitorEnabled || !EchoRuntime.prefs.speakEnabled || EchoRuntime.orchestrator.busy) return@post
            when {
                handOff != null -> announcer.speak(handOff.handOffMessage)
                verdict.label != lastAnnounced -> announcer.speak(
                    when {
                        verdict.primaryKind != null -> "Sensitive: ${verdict.primaryKind!!.spoken}"
                        verdict.isCheckout -> "Checkout screen" + (verdict.checkout?.amount?.let { ", $it" } ?: "")
                        else -> "Safe screen"
                    },
                )
            }
            lastAnnounced = verdict.label
        }
        return verdict
    }

    private fun applyPrefs() {
        bubble?.refreshMode()
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
        const val DEBUG_ACTION = "com.echoflow.DEBUG_COMMAND"
    }
}
