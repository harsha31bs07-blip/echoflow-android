package com.echoflow.app.teach

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.safety.ActionRisk
import com.echoflow.core.safety.ActionRiskClassifier
import com.echoflow.core.teach.Fingerprints
import com.echoflow.core.teach.RawAction
import com.echoflow.core.teach.RawKind

/**
 * Records the teacher's taps and typing while teach mode is on. Runs on the main thread (events
 * arrive there). Each tap is matched to a fresh snapshot of the screen *before* it changes.
 * Password fields are never recorded; tapping a pay/place-order button ends the recording.
 */
class TeachingRecorder(
    val utterance: String,
    private val ownPackage: String,
    private val capture: () -> ScreenSnapshot?,
    /** Last settled snapshot, i.e. the screen as it was before this tap changed it. */
    private val previous: () -> ScreenSnapshot? = { null },
    /** Called when the teacher tapped a commit button (pay / place order): teaching must end. */
    private val onCommitTap: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Each recorded step, in words ("tapped \"Search\"", "typed \"margherita\""), for the live caption. */
    private val onRecorded: (String) -> Unit = {},
    /** The screen changed to another page with no tap reported: the app hides its taps (W1). */
    private val onTapsHidden: () -> Unit = {},
) {
    private val startedAt = clock()
    @Volatile private var lastInputAt = 0L
    private var lastLabels: Set<String>? = null
    private var lastPkg: String? = null
    @Volatile private var hiddenReported = false

    /** Taps come from EchoFlow's own tap relay; the app's click reports are ignored (no doubles). */
    @Volatile var relayMode = false
    private val actions = mutableListOf<RawAction>()
    private val risk = ActionRiskClassifier()
    private var typingBounds: Rect? = null

    @Volatile var lastLabel: String? = null
        private set

    val count: Int get() = synchronized(actions) { actions.size }

    fun snapshotActions(): List<RawAction> = synchronized(actions) { actions.toList() }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == ownPackage || pkg.startsWith("com.android.systemui")) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> { lastInputAt = clock(); if (!relayMode) onClick(event, pkg) }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> { lastInputAt = clock(); onText(event) }
        }
    }

    /** Fills in the "after" fingerprint of the last action once the screen has moved on. */
    fun onSnapshot(snapshot: ScreenSnapshot) {
        noticeHiddenTap(snapshot)
        synchronized(actions) {
            val i = actions.indexOfLast { it.postFingerprint == null }
            if (i >= 0 && snapshot.timestampMs > actions[i].atMs + 150) {
                actions[i] = actions[i].copy(postFingerprint = Fingerprints.of(snapshot))
            }
        }
    }

    private fun onClick(event: AccessibilityEvent, pkg: String) {
        val bounds = Rect().also { event.source?.getBoundsInScreen(it) ?: return }
        val cls = event.className?.toString()
        android.util.Log.i("EchoTeach", "click event $cls $bounds text=${event.text} desc=${event.contentDescription}")
        // Prefer the settled pre-tap screen; the click event can arrive after navigation.
        val (snap, index) = listOfNotNull(previous(), capture()).firstNotNullOfOrNull { s ->
            findByBounds(s, bounds, cls, editable = false, exactOnly = true)?.let { s to it }
        } ?: capture()?.let { s -> findByBounds(s, bounds, cls, editable = false)?.let { s to it } }
            ?: return android.util.Log.i("EchoTeach", "  -> no matching element; dropped").let { }
        android.util.Log.i("EchoTeach", "  -> recorded #$index ${snap.elements[index].viewId} '${snap.elements[index].label}'")
        val assessment = risk.assess(snap, snap.elements[index])
        if (assessment.risk == ActionRisk.COMMIT) {
            onCommitTap(assessment.evidence ?: "pay")
            return
        }
        typingBounds = null
        // A pop-up from another app (an incoming call's Decline button) can be matched against
        // the app underneath. The event says where the tap really happened: keep that, so the
        // compiler drops it as a tap in another app (bonus B1).
        val a = Fingerprints.tap(snap, index, clock()).let { t -> if (t.packageName != pkg) t.copy(packageName = pkg) else t }
        if (a.packageName != snap.packageName) android.util.Log.i("EchoTeach", "  -> tap in $pkg, not ${snap.packageName}")
        synchronized(actions) { actions += a }
        lastLabel = a.target.display
        onRecorded("tapped ${a.target.display}")
    }

    /**
     * A different page (most of its labels changed, same app) with no tap or typing reported
     * just before it: the app doesn't report taps (Compose apps such as Play Store). Ignored in
     * the first seconds, while the app opens and loads.
     */
    private fun noticeHiddenTap(snapshot: ScreenSnapshot) {
        val pkg = snapshot.packageName ?: return
        if (pkg == ownPackage || pkg.startsWith("com.android.systemui")) return
        val labels = snapshot.appElements().asSequence().filter { it.visible && !it.editable }.mapNotNull { it.label }
            .map { it.trim().lowercase() }.filter { it.length in 2..40 }.toSet()
        val before = lastLabels
        val samePkg = pkg == lastPkg
        lastLabels = labels
        lastPkg = pkg
        if (hiddenReported || relayMode || before == null || !samePkg || before.size < 5 || labels.size < 5) return
        val now = clock()
        if (now - startedAt < 4_000 || now - lastInputAt < 2_500) return
        val shared = before.intersect(labels).size.toDouble() / before.union(labels).size
        if (shared < 0.35) {
            hiddenReported = true
            onTapsHidden()
        }
    }

    /** A tap caught by EchoFlow's relay at ([x], [y]) on [snap]: records the element under it. */
    enum class RelayTap { RECORDED, NOTHING_THERE, PAY }

    /**
     * A tap caught by EchoFlow's relay at ([x], [y]) on [snap]: records the element under it.
     * [RelayTap.PAY] means it's a pay / place-order button: the relay must NOT pass it on (EchoFlow
     * would be the one paying); teaching ends so the user can tap it themselves.
     */
    fun recordRelayTap(snap: ScreenSnapshot, x: Int, y: Int): RelayTap {
        lastInputAt = clock()
        val under = snap.appElements()
            .filter { it.visible && it.bounds.area > 0 && x in it.bounds.left..it.bounds.right && y in it.bounds.top..it.bounds.bottom }
            .minByOrNull { it.bounds.area } ?: return RelayTap.NOTHING_THERE
        val target = snap.elements[com.echoflow.core.flow.Descriptors.clickableFor(snap, under.index)]
        if (risk.assess(snap, under).risk == ActionRisk.COMMIT || risk.assess(snap, target).risk == ActionRisk.COMMIT) {
            onCommitTap(risk.assess(snap, under).evidence ?: risk.assess(snap, target).evidence ?: "pay")
            return RelayTap.PAY
        }
        val a = Fingerprints.tap(snap, under.index, clock())
        synchronized(actions) { actions += a }
        typingBounds = null
        lastLabel = a.target.display
        onRecorded("tapped ${a.target.display}")
        return RelayTap.RECORDED
    }

    /** Drops the last recorded step; returns it in words, or null if there was none. */
    fun undo(): String? = synchronized(actions) {
        val last = actions.removeLastOrNull() ?: return null
        typingBounds = null
        if (last.kind == RawKind.TYPE) "typed \"${last.typed.orEmpty()}\"" else "tapped ${last.target.display}"
    }

    private fun onText(event: AccessibilityEvent) {
        if (event.isPassword) return // never record secrets
        val text = event.text?.joinToString("")?.trim().orEmpty()
        val bounds = Rect().also { event.source?.getBoundsInScreen(it) ?: return }
        synchronized(actions) {
            val last = actions.lastOrNull()
            if (last?.kind == RawKind.TYPE && bounds == typingBounds) {
                actions[actions.lastIndex] = last.copy(typed = text, atMs = clock())
                lastLabel = "typed \"$text\""
                onRecorded(lastLabel!!)
                return
            }
        }
        val snap = capture() ?: return
        val index = findByBounds(snap, bounds, event.className?.toString(), editable = true) ?: return
        if (snap.elements[index].password) return
        typingBounds = bounds
        val a = Fingerprints.type(snap, index, text, clock())
        synchronized(actions) { actions += a }
        lastLabel = "typed \"$text\""
        onRecorded(lastLabel!!)
    }

    private fun findByBounds(snap: ScreenSnapshot, b: Rect, className: String?, editable: Boolean, exactOnly: Boolean = false): Int? {
        val same = snap.elements.filter {
            it.bounds.left == b.left && it.bounds.top == b.top && it.bounds.right == b.right && it.bounds.bottom == b.bottom &&
                (!editable || it.editable)
        }
        if (same.isEmpty()) {
            if (exactOnly) return null
            // Fall back to the smallest element containing the centre (views that moved slightly).
            val cx = b.centerX()
            val cy = b.centerY()
            return snap.elements
                .filter { it.visible && cx in it.bounds.left..it.bounds.right && cy in it.bounds.top..it.bounds.bottom && (!editable || it.editable) }
                .minByOrNull { it.bounds.area }?.index
        }
        return (same.firstOrNull { it.className == className && it.label != null }
            ?: same.firstOrNull { it.className == className }
            ?: same.maxByOrNull { it.depth })?.index
    }
}
