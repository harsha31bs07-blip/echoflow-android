package com.echoflow.app.llm

import com.echoflow.app.BuildConfig
import com.echoflow.core.flow.Flow
import com.echoflow.core.nlu.LlmIntent
import com.echoflow.core.replay.RecoveryAdvice
import com.echoflow.core.replay.RecoveryPrompt
import com.echoflow.core.replay.RecoveryRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 *
 * With no key, requests go through EchoFlow's relay ([relayUrl], see relay/) if the build has
 * one: the relay holds the key and forwards only these prompts, with a daily cap.
 */
class GeminiClient(
    /** Read on every call, so a key pasted in the app takes effect at once. */
    private val apiKey: () -> String = { BuildConfig.GEMINI_API_KEY },
    private val model: String = BuildConfig.GEMINI_MODEL,
    private val relayUrl: String = BuildConfig.GEMINI_RELAY_URL,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val enabled: Boolean get() = apiKey().isNotBlank() || relayUrl.isNotBlank()

    /** Requests go through the relay (no key on this phone). */
    val viaRelay: Boolean get() = apiKey().isBlank() && relayUrl.isNotBlank()

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

    /**
     * Help for a replay that's stuck on an unfamiliar screen (see [RecoveryAdvisor]). Gemini sees
     * the task, the stuck step and a redacted list of on-screen elements (typed text dropped,
     * long numbers and emails masked; never sent from payment, OTP, password or login screens).
     * The engine checks and gates whatever comes back.
     */
    suspend fun adviseRecovery(request: RecoveryRequest): RecoveryAdvice? {
        if (!enabled) return null
        val text = generate(RecoveryPrompt.build(request), RECOVERY_TIMEOUT_MS) ?: return null
        val advice = RecoveryPrompt.parse(text, request.screen.map { it.id }.toSet())
        android.util.Log.i("EchoGemini", "recovery advice for step ${request.stepNumber}: $advice")
        if (advice == null) lastError = "unreadable recovery advice"
        return advice
    }

    /**
     * A tiny request to check the key, the model name and the network, for the app's
     * "Test" button. Returns a short human-readable result.
     */
    suspend fun test(): String {
        if (!enabled) return "No key saved."
        val t0 = System.currentTimeMillis()
        val text = generate("Reply with JSON only: {\"ok\": true}", RECOVERY_TIMEOUT_MS)
        val ms = System.currentTimeMillis() - t0
        val how = if (viaRelay) "via EchoFlow's relay" else model
        return if (text != null) "✓ Gemini answered in ${"%.1f".format(ms / 1000.0)} s ($how)." else "✗ Gemini didn't answer: ${explain(lastError)}"
    }

    private fun explain(error: String?): String = when {
        error == null -> "no reply."
        viaRelay && error.startsWith("HTTP 429") -> error.substringAfter(": ", "the shared daily limit is used up. Try again tomorrow, or paste your own key.")
        viaRelay && (error.startsWith("HTTP 400") || error.startsWith("HTTP 403")) -> "the relay refused the request ($error)."
        error.startsWith("HTTP 429") -> "the free-tier limit is used up for now (HTTP 429). It resets later; try again in a while."
        error.startsWith("HTTP 400") || error.startsWith("HTTP 403") -> "the key was refused ($error). Check it was pasted in full."
        error.startsWith("HTTP 404") -> "model \"$model\" not found ($error)."
        error == "timeout" -> "no reply within ${RECOVERY_TIMEOUT_MS / 1000} s (slow network?)."
        else -> "$error."
    }

    private suspend fun generate(prompt: String, timeoutMs: Long = TIMEOUT_MS): String? {
        lastError = null
        // (Multi-line prompts can start with indentation; the relay checks how they begin.)
        return generateOnce(prompt.trim(), timeoutMs)
    }

    private suspend fun generateOnce(prompt: String, timeoutMs: Long): String? {
        val relay = viaRelay
        // The relay is one more hop (Cloudflare, then Google).
        val limit = if (relay) timeoutMs + RELAY_EXTRA_MS else timeoutMs
        return generateVia(relay, prompt, limit)
    }

    private suspend fun generateVia(relay: Boolean, prompt: String, timeoutMs: Long): String? {
        // The HTTP call blocks its thread and ignores coroutine cancellation, so it runs on its
        // own and the caller stops waiting at the deadline (a late reply is simply dropped).
        val call = calls.async { request(relay, prompt, timeoutMs) }
        val text = withTimeoutOrNull(timeoutMs) { call.await() }
        if (text == null && !call.isCompleted) {
            lastError = "timeout"
            call.cancel()
        }
        if (text == null) android.util.Log.w("EchoGemini", "no answer: $lastError")
        return text
    }

    private val calls = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun request(relay: Boolean, prompt: String, timeoutMs: Long): String? =
            runCatching {
                val url = URL(if (relay) "$relayUrl/generate" else "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = minOf(3_000L, timeoutMs).toInt()
                    readTimeout = timeoutMs.toInt()
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (relay) setRequestProperty("x-echoflow-client", "1") else setRequestProperty("x-goog-api-key", apiKey())
                }
                // The relay sets the model and settings itself and returns Gemini's reply unchanged.
                val body = if (relay) buildJsonObject { put("prompt", prompt) } else buildJsonObject {
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
                    // The error body names the problem (quota, bad key, unknown model); it never contains the key.
                    val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                        ?.let { Regex("\"message\"\\s*:\\s*\"([^\"]{0,160})").find(it)?.groupValues?.get(1) }
                    lastError = "HTTP $code" + (detail?.let { ": $it" } ?: "")
                    return@runCatching null
                }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                json.parseToJsonElement(resp).jsonObject["candidates"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    .also { if (it == null) lastError = "empty reply" }
            }.getOrElse {
                lastError = it.javaClass.simpleName
                null
            }

    companion object {
        /**
         * Understanding a command: the reply waits for this, so keep it short. Without an answer
         * the on-phone matcher decides alone (it still confirms looser wordings first).
         */
        const val TIMEOUT_MS = 2_500L
        /** Recovery prompts list a whole screen, so they take longer than matching a command. */
        const val RECOVERY_TIMEOUT_MS = 10_000L
        const val RELAY_EXTRA_MS = 500L
    }
}
