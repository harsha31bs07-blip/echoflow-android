package com.echoflow.core.bus

import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.safety.ScreenVerdict
import com.echoflow.core.safety.Trip
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Typed events shared between modules. Commands are direct calls; this is for observers. */
sealed interface EchoEvent {
    data class SnapshotUpdated(val snapshot: ScreenSnapshot, val verdict: ScreenVerdict) : EchoEvent
    data class SafetyTripped(val trip: Trip) : EchoEvent
    data object SafetyRearmed : EchoEvent
}

class EchoBus {
    private val flow = MutableSharedFlow<EchoEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<EchoEvent> get() = flow

    fun emit(event: EchoEvent) {
        flow.tryEmit(event)
    }
}
