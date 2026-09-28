package com.echoflow.app.store

import android.content.Context
import com.echoflow.core.flow.Flow
import com.echoflow.core.runlog.RunRecord
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Taught flows as JSON files in filesDir/flows — the saved, inspectable artefact of T1. */
class FlowStore(context: Context) {
    private val dir = File(context.filesDir, "flows").apply { mkdirs() }

    @Synchronized
    fun all(): List<Flow> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { json.decodeFromString(Flow.serializer(), it.readText()) }.getOrNull() }
        .sortedBy { it.createdAtMs }

    @Synchronized
    fun get(id: String): Flow? = File(dir, "$id.json").takeIf { it.exists() }
        ?.let { runCatching { json.decodeFromString(Flow.serializer(), it.readText()) }.getOrNull() }

    @Synchronized
    fun save(flow: Flow) = File(dir, "${flow.id}.json").writeText(json.encodeToString(Flow.serializer(), flow))

    @Synchronized
    fun delete(id: String) = File(dir, "$id.json").delete()

    fun rawJson(id: String): String? = File(dir, "$id.json").takeIf { it.exists() }?.readText()
}

/** Run history for T14 ("what happened last time?"). */
class RunStore(context: Context) {
    private val dir = File(context.filesDir, "runs").apply { mkdirs() }

    @Synchronized
    fun save(run: RunRecord) = File(dir, "${run.startedAtMs}-${run.id}.json").writeText(json.encodeToString(RunRecord.serializer(), run))

    @Synchronized
    fun recent(limit: Int = 20): List<RunRecord> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .sortedByDescending { it.name }
        .take(limit)
        .mapNotNull { runCatching { json.decodeFromString(RunRecord.serializer(), it.readText()) }.getOrNull() }

    fun last(): RunRecord? = recent(1).firstOrNull()
}
