package com.echoflow.app.monitor

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.SnapshotJson
import com.echoflow.core.safety.Redactor
import com.echoflow.core.safety.ScreenVerdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a *redacted* snapshot to Download/EchoFlow/ as JSON (MediaStore, no storage permission
 * needed). These files are the fixtures for :core's FixtureHarnessTest.
 */
object SnapshotExporter {
    private const val FOLDER = "EchoFlow"

    fun export(context: Context, snapshot: ScreenSnapshot, verdict: ScreenVerdict): String {
        val json = SnapshotJson.encode(Redactor.redact(snapshot))
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(snapshot.timestampMs))
        val app = snapshot.packageName?.substringAfterLast('.') ?: "unknown"
        val kind = verdict.primaryKind?.name?.lowercase() ?: "safe"
        val name = "snap_${stamp}_${app}_$kind.json"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("could not create $name")
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error("could not open $name")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"
    }
}
