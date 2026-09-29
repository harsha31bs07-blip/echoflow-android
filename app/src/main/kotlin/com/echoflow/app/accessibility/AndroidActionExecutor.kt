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
                    is PlannedAction.Scroll -> node.performAction(
                        if (action.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    )
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

    private suspend fun tapCentre(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also(node::getBoundsInScreen)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val done = CompletableDeferred<Boolean>()
        val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done.complete(true) }
            override fun onCancelled(d: GestureDescription?) { done.complete(false) }
        }, null)
        return sent && (withTimeoutOrNull(1_500) { done.await() } ?: false)
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
