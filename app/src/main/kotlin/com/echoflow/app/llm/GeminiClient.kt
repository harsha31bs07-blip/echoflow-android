package com.echoflow.app.llm

import com.echoflow.app.BuildConfig
import com.echoflow.core.flow.Flow
import com.echoflow.core.nlu.LlmIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional paraphrase matcher (T3) on the Gemini API free tier. Advisory only: its answer is
 * merged into the local matcher's candidates and still goes through the DecisionLayer; it never
 * sees screen contents or makes safety decisions. Returns null when there is no key, no network
 * or no answer within [TIMEOUT_MS] — the local matcher then works alone.
 */
class GeminiClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val model: String = BuildConfig.GEMINI_MODEL,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val enabled: Boolean get() = apiKey.isNotBlank()

    @Volatile var lastError: String? = null
        private set

    suspend fun matchIntent(utterance: String, flows: List<Flow>): LlmIntent? {
        if (!enabled || flows.isEmpty()) return null
        val catalog = buildJsonArray {
            flows.forEach { f ->
                add(buildJsonObject {
                    put("id", f.id)
                    put("app", f.appLabel ?: f.appPackage)
                    put("template", f.template)
                    put("examples", JsonArray(f.examples.take(5).map(::JsonPrimitive)))
                    put("slots", JsonArray(f.slots.map { JsonPrimitive("${it.name} (${it.type.name.lowercase()}, e.g. ${it.taughtValue})") }))
                })
            }
        }
        val prompt = """
            You route a spoken command to ONE of the user's taught phone automations, or to none.
            Automations: $catalog
            Command: "${utterance.replace("\"", "'")}"
            Rules: pick an automation only if the command asks for the same kind of task (paraphrases count; a different item/quantity/address is fine).
            If the command is a different task, or you are unsure, use null. Copy slot values from the command exactly (quantities as digits).
            Reply with JSON only: {"flowId": string or null, "confidence": number 0..1, "slots": {slotName: value}}
        """.trimIndent()
        val text = generate(prompt) ?: return null
        return runCatching {
            val o = json.parseToJsonElement(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject
            val flowId = o["flowId"]?.jsonPrimitive?.contentOrNull?.takeIf { id -> flows.any { it.id == id } }
            val conf = (o["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0).coerceIn(0.0, 1.0)
            val slots = (o["slots"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
            LlmIntent(flowId, conf, slots)
        }.getOrElse {
            lastError = "bad JSON from model"
            null
        }
    }

    private suspend fun generate(prompt: String): String? = withTimeoutOrNull(TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3_000
                    readTimeout = TIMEOUT_MS.toInt()
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                val body = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) }) })
                    })
                    put("generationConfig", buildJsonObject {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.0)
                    })
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    lastError = "HTTP $code"
                    return@runCatching null
                }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                json.parseToJsonElement(resp).jsonObject["candidates"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            }.getOrElse {
                lastError = it.javaClass.simpleName
                null
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 4_500L
    }
}
