package com.echoflow.app.orchestrator

import android.content.Context
import android.util.Log
import com.echoflow.app.EchoRuntime
import com.echoflow.app.llm.GeminiClient
import com.echoflow.app.store.FlowStore
import com.echoflow.app.store.RunStore
import com.echoflow.app.teach.TeachingRecorder
import com.echoflow.core.decision.Decision
import com.echoflow.core.decision.DecisionLayer
import com.echoflow.core.flow.Flow
import com.echoflow.core.nlu.Candidate
import com.echoflow.core.nlu.IntentMatcher
import com.echoflow.core.nlu.MetaIntent
import com.echoflow.core.nlu.Utterances
import com.echoflow.core.replay.ReplayEngine
import com.echoflow.core.replay.ReplayResult
import com.echoflow.core.runlog.RunRecord
import com.echoflow.core.runlog.RunStatus
import com.echoflow.core.safety.ResumeResult
import com.echoflow.core.safety.Trip
import com.echoflow.core.teach.FlowCompiler
import com.echoflow.core.text.TextNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Mode { IDLE, LISTENING, TEACHING, RUNNING, ASKING }

data class UiState(
    val mode: Mode = Mode.IDLE,
    val status: String = "Say a command, or \"teach\" and a new one.",
    val question: String? = null,
    val choices: List<String> = emptyList(),
)

/**
 * The session state machine (M16): routes each utterance to teaching, a meta command, or
 * match → decide → replay, and owns the voice conversation for questions.
 */
class Orchestrator(context: Context) {
    private val app = context.applicationContext
    // A bug in one command must never take down the accessibility service with it.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "command failed", e)
            _state.value = UiState(Mode.IDLE, "Something went wrong: ${e.javaClass.simpleName}. Please try again.")
        },
    )
    private val matcher = IntentMatcher()
    // A key pasted in the app wins; a build-time key (local.properties) is the fallback.
    private val gemini = GeminiClient(apiKey = { EchoRuntime.prefs.geminiKey.ifBlank { com.echoflow.app.BuildConfig.GEMINI_API_KEY } })
    val flows = FlowStore(app)
    val runs = RunStore(app)

    /** Replay memory, e.g. the delivery address picked last time per app. */
    val memory: android.content.SharedPreferences = app.getSharedPreferences("echoflow_memory", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> get() = _state

    private var recorder: TeachingRecorder? = null
    private var runJob: Job? = null

    /** Counts presses of Speak, so only the newest listening session updates the state. */
    @Volatile private var listenSession = 0

    /** The step the current run is on (number, description), for a cancelled run's record. */
    @Volatile var currentStep: Pair<Int, String>? = null
    @Volatile private var typedAnswer: kotlinx.coroutines.CompletableDeferred<String?>? = null

    val activeRecorder: TeachingRecorder? get() = recorder
    val busy: Boolean get() = _state.value.mode != Mode.IDLE

    private val voice get() = EchoRuntime.service?.voice

    init {
        EchoRuntime.guard.addTripListener { trip -> onTrip(trip) }
    }

    fun status(text: String) {
        _state.value = _state.value.copy(status = text)
    }

    // ---------------- entry points ----------------

    /** Bubble "Speak" button. While a question is open, the answer goes to the question. */
    fun onSpeakPressed() {
        if (_state.value.mode == Mode.ASKING || _state.value.mode == Mode.RUNNING) return
        // Each press is its own session; an older one that ends later must not reset the state.
        val session = ++listenSession
        scope.launch {
            val before = if (_state.value.mode == Mode.LISTENING) Mode.IDLE else _state.value.mode
            _state.value = _state.value.copy(mode = if (before == Mode.TEACHING) Mode.TEACHING else Mode.LISTENING, status = "Listening…")
            val text = listen()
            if (session != listenSession) return@launch // a newer press took over
            if (before != Mode.TEACHING) _state.value = _state.value.copy(mode = Mode.IDLE)
            if (text.isNullOrBlank()) {
                status(when {
                    voice?.recognitionAvailable == false -> "Speech recognition isn't available on this phone"
                    voice?.lastCancelled == true -> "Okay, I stopped listening."
                    else -> "I didn't catch that. Tap the sound-wave button to try again."
                })
                return@launch
            }
            handle(text)
        }
    }

    /** Typed command (main screen box) — same path as speech. */
    fun onTyped(text: String) {
        typedAnswer?.let { if (!it.isCompleted) { it.complete(text); voice?.cancelListening(); return } }
        scope.launch { handle(text) }
    }

    /** A choice button in the bubble. */
    fun onChoice(choice: String) {
        typedAnswer?.complete(choice)
        voice?.cancelListening()
    }

    fun onDonePressed() = scope.launch { finishTeaching("user") }

    fun onStopPressed() = scope.launch { stop() }

    // ---------------- routing ----------------

    private suspend fun handle(text: String) {
        Log.i(TAG, "utterance: $text")
        status("“$text”")
        val meta = matcher.meta(text)
        if (_state.value.mode == Mode.TEACHING) {
            when (meta) {
                MetaIntent.Done -> finishTeaching("user")
                MetaIntent.Stop -> cancelTeaching()
                else -> say("I'm still learning. Keep tapping, then say done.")
            }
            return
        }
        when (meta) {
            is MetaIntent.Teach -> startTeaching(meta.utterance)
            MetaIntent.Report -> say(runs.last()?.spokenSummary() ?: "I haven't run anything yet.")
            MetaIntent.ListFlows -> listFlows()
            MetaIntent.Stop -> stop()
            MetaIntent.Continue -> resumeAfterHandOff()
            MetaIntent.Done -> say("I'm not learning anything right now.")
            null -> command(text)
        }
    }

    private suspend fun command(text: String) {
        val all = flows.all()
        if (all.isEmpty()) {
            offerTeach(text, "I don't know how to \"$text\" yet. Want to teach me? Say yes, then show me.")
            return
        }
        status("Matching “$text”…")
        val local = matcher.match(text, all)
        val llm = if (local.firstOrNull()?.source in setOf("exact", "template")) null else gemini.matchIntent(text, all)
        val candidates = if (llm == null) local else matcher.match(text, all, llm)
        Log.i(TAG, "candidates: " + candidates.joinToString { "${it.flow.id}=${"%.2f".format(it.score)}/${it.source}" } + " llm=$llm err=${gemini.lastError}")
        when (val d = DecisionLayer.decide(text, candidates)) {
            is Decision.Proceed -> runFlow(d.candidate, text)
            is Decision.Confirm -> {
                val a = askUser(d.question, listOf("yes", "no"))
                if (a != null && ReplayEngine.isYes(a)) runFlow(d.candidate, text) else say("Okay, I won't do that.")
            }
            is Decision.Disambiguate -> {
                val labels = d.options.map { DecisionLayer.describe(it) }
                val a = askUser(d.question, labels)
                val chosen = a?.let { pick(it, d.options) }
                if (chosen != null) runFlow(chosen, text) else say("Okay, I'll leave it.")
            }
            is Decision.OfferTeach -> offerTeach(text, d.message)
        }
    }

    /** T12: never run anything; on "yes", start learning this very command. */
    private suspend fun offerTeach(text: String, question: String) {
        val a = askUser(question, listOf("yes", "no"))
        if (a != null && ReplayEngine.isYes(a)) startTeaching(text) else say("Okay. Say teach and the command whenever you want to show me.")
    }

    private fun pick(answer: String, options: List<Candidate>): Candidate? {
        val t = TextNormalizer.tokens(answer)
        val ordinals = listOf(setOf("first", "one", "1"), setOf("second", "two", "2"), setOf("third", "three", "3"))
        ordinals.forEachIndexed { i, words -> if (t.any { it in words } && i < options.size) return options[i] }
        options.firstOrNull { c -> t.any { w -> (c.flow.appLabel ?: c.flow.appPackage).lowercase().contains(w) && w.length > 2 } }?.let { return it }
        return options.maxByOrNull { c -> TextNormalizer.tokens(DecisionLayer.describe(c)).count { it in t } }
            ?.takeIf { c -> TextNormalizer.tokens(DecisionLayer.describe(c)).count { it in t } > 0 }
    }

    // ---------------- teaching ----------------

    private suspend fun startTeaching(raw: String) {
        var utterance = Utterances.stripTeachPrefix(raw)
        if (utterance.isBlank()) {
            utterance = askUser("What command should this answer to? For example, order two garlic bread.") ?: return say("Okay, not learning anything.")
        }
        if (!prepareGuard()) return
        val service = EchoRuntime.service ?: return say("Please turn on the EchoFlow accessibility service first.")
        recorder = TeachingRecorder(
            utterance = utterance,
            ownPackage = app.packageName,
            capture = { service.captureNow() },
            previous = { EchoRuntime.snapshots.current() },
            onCommitTap = { what -> scope.launch { finishTeaching("PAYMENT", "You reached the pay button ($what), so I stopped recording before it.") } },
        )
        _state.value = UiState(Mode.TEACHING, "Teaching “$utterance” — show me, then say done")
        say("Okay, teach me: $utterance. Open the app and do it. Stop before paying, and say done.")
    }

    private suspend fun finishTeaching(endedAt: String, note: String? = null) {
        val rec = recorder ?: return
        recorder = null
        // Pressing Done on a cart/checkout screen means "the flow ends at checkout".
        val endedAt = if (endedAt == "user" && EchoRuntime.snapshots.current()?.let { EchoRuntime.guard.classify(it).isCheckout } == true) "CHECKOUT" else endedAt
        _state.value = UiState(Mode.IDLE, "Saving…")
        val actions = rec.snapshotActions()
        if (actions.isEmpty()) {
            say("I didn't see any taps, so nothing was saved.")
            return
        }
        val pkg = actions.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }?.key
        val label = pkg?.let { p -> runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
        val result = withContext(Dispatchers.Default) {
            runCatching { FlowCompiler().compile("f${System.currentTimeMillis()}", rec.utterance, actions, label, endedAt, System.currentTimeMillis()) }
        }.getOrElse {
            say("Something went wrong saving that flow: ${it.message}")
            return
        }
        Log.i(TAG, "compiled ${actions.size} actions -> ${result.flow.steps.size} steps; dropped=${result.dropped}")
        flows.save(result.flow)
        val f = result.flow
        val slotText = if (f.slots.isEmpty()) "Nothing in it can be changed." else "You can change the " + f.slots.joinToString(", ") { if (it.name == "qty") "quantity" else it.name } + "."
        val n = result.dropped.size
        val noise = when (n) { 0 -> ""; 1 -> " I ignored 1 accidental or unneeded tap."; else -> " I ignored $n accidental or unneeded taps." }
        status("Learned: “${f.examples.firstOrNull() ?: f.template}” (${f.steps.size} steps)")
        val learned = f.examples.firstOrNull() ?: f.template
        say((note?.let { "$it " } ?: "") + "Learned: $learned. I saved ${f.steps.size} steps. $slotText$noise You can see it in the EchoFlow app.")
    }

    private suspend fun cancelTeaching() {
        recorder = null
        _state.value = UiState()
        say("Okay, I discarded that.")
    }

    private fun onTrip(trip: Trip) {
        // Teaching only ends on screens that need the user (payment, OTP, password, login).
        if (recorder != null && trip.kind != com.echoflow.core.safety.SensitiveKind.OPAQUE_UNKNOWN) {
            scope.launch { finishTeaching(trip.kind.name, "This is ${trip.kind.spoken}, so I stopped recording.") }
        }
    }

    // ---------------- replay ----------------

    private suspend fun runFlow(c: Candidate, utterance: String) {
        if (!prepareGuard()) return
        // B2: the same steps in another app of the same kind (never saved over the taught flow).
        val flow = c.targetApp?.let { c.flow.retargeted(it, DecisionLayer.appLabel(it)) } ?: c.flow
        val slots = c.slots.filterValues { it.isNotBlank() }
        _state.value = UiState(Mode.RUNNING, "Running “${DecisionLayer.describe(c)}”")
        say("Okay, ${DecisionLayer.describe(c)}.")
        val started = System.currentTimeMillis()
        currentStep = null
        runJob = scope.launch(Dispatchers.Default) {
            val result = try {
                // With a Gemini key, a step stuck on an unfamiliar screen gets AI help (checked and gated).
                val advisor = if (gemini.enabled) com.echoflow.core.replay.RecoveryAdvisor { gemini.adviseRecovery(it) } else null
                val popupRules = !(com.echoflow.app.BuildConfig.DEBUG && EchoRuntime.debugPopupRulesOff)
                ReplayEngine(AppReplayHost(this@Orchestrator), EchoRuntime.guard, advisor = advisor, popupRules = popupRules).run(flow, slots)
            } catch (e: CancellationException) {
                // Record where it was stopped, for "Did the last run succeed?" (T14).
                val (at, what) = currentStep ?: (0 to null)
                ReplayResult(RunStatus.CANCELLED, "Stopped at step $at, as you asked.", at, flow.steps.size, what, emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "replay crashed", e)
                ReplayResult(RunStatus.HALTED, "Something went wrong: ${e.message}", 0, flow.steps.size, null, emptyList())
            }
            finishRun(flow, utterance, slots, started, result, learnPhrase = c.targetApp == null, matchedBy = if (c.source == "llm") "understood with Gemini" else null)
        }
    }

    private suspend fun finishRun(flow: Flow, utterance: String, slots: Map<String, String>, started: Long, r: ReplayResult, learnPhrase: Boolean = true, matchedBy: String? = null) {
        runs.save(
            RunRecord(
                id = flow.id, utterance = utterance, flowId = flow.id, flowName = flow.template, slots = slots,
                startedAtMs = started, endedAtMs = System.currentTimeMillis(), status = r.status,
                stoppedAtStep = r.stoppedAtStep, totalSteps = r.totalSteps, stepDescription = r.stepDescription,
                message = r.message, events = listOfNotNull(matchedBy) + r.events,
            ),
        )
        // Learn successful paraphrases so they match exactly next time (T3).
        val norm = TextNormalizer.normalize(utterance)
        if (learnPhrase && (r.status == RunStatus.HANDED_OFF || r.status == RunStatus.COMPLETED) &&
            flow.examples.none { TextNormalizer.normalize(it) == norm } && slots == flow.slots.associate { it.name to it.taughtValue }
        ) {
            flows.save(flow.copy(examples = flow.examples + norm))
        }
        withContext(Dispatchers.Main) {
            _state.value = UiState(Mode.IDLE, r.message)
            say(r.message)
        }
    }

    private suspend fun stop() {
        voice?.stopSpeaking()
        voice?.cancelListening()
        typedAnswer?.complete(null)
        if (runJob?.isActive == true) {
            runJob?.cancel()
            status("Stopped")
        } else if (recorder != null) {
            cancelTeaching()
        } else {
            say("Nothing is running.")
        }
    }

    private suspend fun resumeAfterHandOff() {
        val snap = EchoRuntime.snapshots.current() ?: return
        when (val r = EchoRuntime.guard.resume(snap, userConfirmed = true)) {
            ResumeResult.Resumed -> say("Okay, I'm ready again.")
            ResumeResult.NotTripped -> say("I'm ready.")
            is ResumeResult.StillSensitive -> say("This is still ${r.verdict.primaryKind?.spoken ?: "a sensitive screen"}. Please finish it yourself first.")
            ResumeResult.NeedsUserConfirmation -> Unit
        }
    }

    /** A new command from the user re-arms the guard, but only away from sensitive screens. */
    private suspend fun prepareGuard(): Boolean {
        if (!EchoRuntime.guard.isTripped) return true
        val snap = EchoRuntime.snapshots.current() ?: return true
        return when (val r = EchoRuntime.guard.resume(snap, userConfirmed = true)) {
            is ResumeResult.StillSensitive -> {
                say("You're on ${r.verdict.primaryKind?.spoken ?: "a sensitive screen"}. Please leave it first, then ask again.")
                false
            }
            else -> true
        }
    }

    private suspend fun listFlows() {
        val all = flows.all()
        say(if (all.isEmpty()) "I haven't learned anything yet." else "I know ${all.size}: " + all.joinToString("; ") { "${it.template} on ${it.appLabel ?: it.appPackage}" })
    }

    // ---------------- voice ----------------

    suspend fun askUser(question: String, choices: List<String> = emptyList()): String? = withContext(Dispatchers.Main) {
        val previous = _state.value
        Log.i(TAG, "ask: $question | choices=$choices")
        _state.value = previous.copy(mode = Mode.ASKING, question = question, choices = choices, status = question)
        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
        typedAnswer = deferred
        try {
            voice?.speak(question)
            for (attempt in 0 until 2) {
                if (deferred.isCompleted) break
                if (attempt > 0) voice?.speak("Sorry, I didn't catch that. $question")
                // A typed answer or a choice button completes `deferred` and cancels listening.
                val heard = voice?.listen()
                if (heard != null && !deferred.isCompleted) deferred.complete(heard)
            }
            if (deferred.isCompleted) deferred.await() else null
        } finally {
            typedAnswer = null
            _state.value = _state.value.copy(mode = if (runJob?.isActive == true) Mode.RUNNING else previous.mode.let { if (it == Mode.ASKING) Mode.IDLE else it }, question = null, choices = emptyList())
        }
    }

    private suspend fun listen(): String? = voice?.listen()

    suspend fun say(text: String) {
        Log.i(TAG, "say: $text")
        status(text)
        voice?.speak(text)
    }

    fun sayAsync(text: String) {
        status(text)
        voice?.speakAsync(text)
    }

    private companion object {
        const val TAG = "EchoOrchestrator"
    }
}
