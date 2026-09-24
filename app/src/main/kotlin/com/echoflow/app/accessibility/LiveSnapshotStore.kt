package com.echoflow.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.echoflow.core.gateway.SnapshotSource
import com.echoflow.core.model.ScreenSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A snapshot plus the live nodes it was built from; nodes[i] backs snapshot.elements[i]. */
class LiveSnapshot(val snapshot: ScreenSnapshot, internal val nodes: List<AccessibilityNodeInfo>)

class LiveSnapshotStore : SnapshotSource {
    private val state = MutableStateFlow<LiveSnapshot?>(null)

    val latest: StateFlow<LiveSnapshot?> get() = state

    internal fun publish(live: LiveSnapshot) {
        state.value = live
    }

    internal fun live(): LiveSnapshot? = state.value

    override fun current(): ScreenSnapshot? = state.value?.snapshot

    override suspend fun awaitNewerThan(snapshotId: Long, timeoutMs: Long): ScreenSnapshot? =
        withTimeoutOrNull(timeoutMs) {
            state.filterNotNull().first { it.snapshot.id > snapshotId }.snapshot
        }
}
