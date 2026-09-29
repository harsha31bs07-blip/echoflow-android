package com.echoflow.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.echoflow.core.gateway.ActionExecutor
import com.echoflow.core.gateway.PlannedAction
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.safety.GestureSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only code that touches the service's action APIs. Internal on purpose: it is handed to
 * ActionGateway by the service and to nothing else.
 */
internal class AndroidActionExecutor(
    private val service: AccessibilityService,
    private val store: LiveSnapshotStore,
) : ActionExecutor {

    override suspend fun execute(action: PlannedAction, snapshot: ScreenSnapshot): Boolean = withContext(Dispatchers.Main) {
        when (action) {
            PlannedAction.Back -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            is PlannedAction.LaunchApp -> launch(action.packageName)
            is PlannedAction.Targeted -> {
                val node = resolveNode(action) ?: return@withContext false.also {
                    Log.i(TAG, "no live node for $action (snapshot gone or node refreshed away)")
                }
                when (action) {
                    // Views that handle touches themselves (Lynx/Compose/custom) refuse ACTION_CLICK;
                    // then tap the centre of the same, gate-approved element with a gesture, unless
                    // something that could pay, order or delete also sits under that point.
                    is PlannedAction.Click -> (!action.gesture && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) || run {
                        val blocked = GestureSafety.blocker(snapshot, action.elementIndex)
                        if (blocked != null) Log.i(TAG, "no gesture for #${action.elementIndex}: $blocked")
                        (blocked == null && tapCentre(node)).also { ok ->
                            if (blocked == null) Log.i(TAG, "gesture ${if (ok) "sent" else "failed"} for #${action.elementIndex}")
                        }
                    }
                    is PlannedAction.SetText -> node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) },
                    )
                    is PlannedAction.ImeEnter -> node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    // Web content (Amazon's product page) accepts ACTION_SCROLL_* and doesn't move: swipe.
                    is PlannedAction.Scroll -> if (node.className?.toString()?.contains("WebView") == true) {
                        swipe(node, action.forward)
                    } else {
                        node.performAction(
                            if (action.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                        ) || swipe(node, action.forward)
                    }
                }
            }
        }
    }

    /** Only acts on nodes from the exact snapshot the gate approved, and only if they still exist. */
    private fun resolveNode(action: PlannedAction.Targeted): AccessibilityNodeInfo? {
        val live = store.liveById(action.snapshotId) ?: return null
        val node = live.nodes.getOrNull(action.elementIndex) ?: return null
        return node.takeIf { it.refresh() }
    }

    /** A vertical swipe inside the node (70% → 30% of its height): scrolls, never taps. */
    private suspend fun swipe(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val r = Rect().also(node::getBoundsInScreen)
        if (r.height() < 200) return false
        val x = r.exactCenterX()
        val (from, to) = r.top + r.height() * 0.7f to r.top + r.height() * 0.3f
        val path = Path().apply { moveTo(x, if (forward) from else to); lineTo(x, if (forward) to else from) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        return dispatch(gesture, 2_000)
    }

    private suspend fun tapCentre(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also(node::getBoundsInScreen)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatch(gesture, 1_500)
    }

    /**
     * Sends a gesture with EchoFlow's floating panel letting touches through for its duration: a
     * swipe or tap that starts where the panel happens to be must reach the app, not the panel.
     */
    private suspend fun dispatch(gesture: GestureDescription, timeoutMs: Long): Boolean {
        val echo = service as? EchoAccessibilityService
        echo?.setOverlayPassThrough(true)
        kotlinx.coroutines.delay(60) // let the window flag apply before the touch starts
        try {
            val done = CompletableDeferred<Boolean>()
            val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { done.complete(true) }
                override fun onCancelled(d: GestureDescription?) { done.complete(false) }
            }, null)
            return sent && (withTimeoutOrNull(timeoutMs) { done.await() } ?: false)
        } finally {
            echo?.setOverlayPassThrough(false)
        }
    }

    private companion object {
        const val TAG = "EchoAct"
    }

    private fun launch(packageName: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        // Fresh task: every replay starts from the app's home screen, as it did when taught,
        // instead of wherever the user left it. Still the plain launcher intent, not a deep link.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { service.startActivity(intent) }.isSuccess
    }
}
