package com.echoflow.app.tile

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.echoflow.app.EchoRuntime
import com.echoflow.app.ui.MainActivity

/**
 * "Talk to EchoFlow" in the Quick Settings shade: one tap closes the shade and starts listening,
 * from anywhere (another way in besides the edge handle). If EchoFlow's accessibility service
 * isn't on yet, the tap opens EchoFlow's setup instead.
 */
class TalkTileService : TileService() {

    override fun onStartListening() {
        val t = qsTile ?: return
        val ready = EchoRuntime.service != null
        t.state = Tile.STATE_INACTIVE
        t.label = "Talk to EchoFlow"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = if (ready) "Tap to speak" else "Set up first"
        t.contentDescription = if (ready) "Talk to EchoFlow: tap to speak" else "Talk to EchoFlow: open EchoFlow to set it up"
        t.updateTile()
    }

    override fun onClick() {
        val service = EchoRuntime.service
        if (service == null) {
            openApp()
            return
        }
        // Close the shade, then open the microphone once it's out of the way.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        Handler(Looper.getMainLooper()).postDelayed({ EchoRuntime.orchestrator.onSpeakPressed() }, SHADE_CLOSE_MS)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val SHADE_CLOSE_MS = 400L
    }
}
