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
) {
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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onText(event)
        }
    }

    /** Fills in the "after" fingerprint of the last action once the screen has moved on. */
    fun onSnapshot(snapshot: ScreenSnapshot) {
        synchronized(actions) {
            val i = actions.indexOfLast { it.postFingerprint == null }
            if (i >= 0 && snapshot.timestampMs > actions[i].atMs + 150) {
                actions[i] = actions[i].copy(postFingerprint = Fingerprints.of(snapshot))
            }
        }
    }

    private fun onClick(event: AccessibilityEvent) {
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
        val a = Fingerprints.tap(snap, index, clock())
        synchronized(actions) { actions += a }
        lastLabel = a.target.display
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
