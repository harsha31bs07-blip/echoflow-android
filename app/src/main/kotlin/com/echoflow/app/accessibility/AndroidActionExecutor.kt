package com.echoflow.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.echoflow.core.gateway.ActionExecutor
import com.echoflow.core.gateway.PlannedAction
import com.echoflow.core.model.ScreenSnapshot
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
                val node = resolveNode(action) ?: return@withContext false
                when (action) {
                    is PlannedAction.Click -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
        val live = store.live()?.takeIf { it.snapshot.id == action.snapshotId } ?: return null
        val node = live.nodes.getOrNull(action.elementIndex) ?: return null
        return node.takeIf { it.refresh() }
    }

    private fun launch(packageName: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { service.startActivity(intent) }.isSuccess
    }
}
