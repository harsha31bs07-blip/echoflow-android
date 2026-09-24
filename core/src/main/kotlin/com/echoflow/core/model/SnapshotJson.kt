package com.echoflow.core.model

import kotlinx.serialization.json.Json

/** JSON codec for snapshot dumps and test fixtures. Lenient on read so hand-written fixtures can omit defaults. */
object SnapshotJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(snapshot: ScreenSnapshot): String = json.encodeToString(ScreenSnapshot.serializer(), snapshot)

    fun decode(text: String): ScreenSnapshot = json.decodeFromString(ScreenSnapshot.serializer(), text)
}
