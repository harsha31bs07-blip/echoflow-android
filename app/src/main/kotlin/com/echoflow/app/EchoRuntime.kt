package com.echoflow.app

import android.content.Context
import com.echoflow.app.accessibility.EchoAccessibilityService
import com.echoflow.app.accessibility.LiveSnapshotStore
import com.echoflow.core.bus.EchoBus
import com.echoflow.core.gateway.ActionGateway
import com.echoflow.core.safety.SafetyGuard
import com.echoflow.core.safety.ScreenSafetyClassifier

/**
 * Process-wide singletons. The accessibility service attaches itself here when the user enables
 * it; other modules only ever see [gateway], never the service's raw action APIs.
 */
object EchoRuntime {
    lateinit var prefs: EchoPrefs
        private set
    lateinit var guard: SafetyGuard
        private set

    val bus = EchoBus()
    val snapshots = LiveSnapshotStore()

    @Volatile
    var service: EchoAccessibilityService? = null
        private set

    @Volatile
    var gateway: ActionGateway? = null
        private set

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        prefs = EchoPrefs(app)
        guard = SafetyGuard(ScreenSafetyClassifier(ownPackage = app.packageName))
        initialized = true
    }

    internal fun attach(service: EchoAccessibilityService, gateway: ActionGateway) {
        this.service = service
        this.gateway = gateway
    }

    internal fun detach(service: EchoAccessibilityService) {
        if (this.service === service) {
            this.service = null
            this.gateway = null
        }
    }
}
