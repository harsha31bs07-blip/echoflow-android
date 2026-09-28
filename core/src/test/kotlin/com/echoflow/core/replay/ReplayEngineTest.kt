package com.echoflow.core.replay

import com.echoflow.core.flow.Descriptors
import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.SlotDef
import com.echoflow.core.flow.SlotType
import com.echoflow.core.flow.Step
import com.echoflow.core.gateway.ActionExecutor
import com.echoflow.core.gateway.ActionGateway
import com.echoflow.core.gateway.ActionOutcome
import com.echoflow.core.gateway.GateContext
import com.echoflow.core.gateway.PlannedAction
import com.echoflow.core.gateway.SnapshotSource
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.runlog.RunStatus
import com.echoflow.core.safety.SafetyGuard
import com.echoflow.core.safety.ScreenSafetyClassifier
import com.echoflow.core.testing.screen
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A scripted phone: screens by name, and which label leads where. */
private class FakePhone(
    private val screens: Map<String, (Long) -> ScreenSnapshot>,
    private val transitions: Map<Pair<String, String>, String>,
    start: String,
    val answers: ArrayDeque<String?> = ArrayDeque(),
) : ReplayHost, SnapshotSource, ActionExecutor {
    var name = start
    private var nextId = 100L
    var snap: ScreenSnapshot = screens.getValue(start)(nextId)
    val clicked = mutableListOf<String>()
    var addedRow: String? = null
    val typed = mutableListOf<String>()
    val questions = mutableListOf<String>()
    val guard = SafetyGuard(ScreenSafetyClassifier())
    private val gateway = ActionGateway(guard, this, this, postActionTimeoutMs = 10)
    private var clock = 0L

    fun go(to: String) {
        name = to
        snap = screens.getValue(to)(++nextId)
    }

    override fun current() = snap
    override suspend fun awaitNewerThan(snapshotId: Long, timeoutMs: Long) = snap.takeIf { it.id > snapshotId }
    override suspend fun awaitSettled(afterId: Long, timeoutMs: Long): ScreenSnapshot { clock += 500; return snap }
    override suspend fun perform(action: PlannedAction, context: GateContext) = gateway.perform(action, context)
    override suspend fun ask(question: String, choices: List<String>): String? { questions += question; return answers.removeFirstOrNull() }
    override fun say(text: String) = Unit
    override fun nowMs() = clock.also { clock += 100 }

    override suspend fun execute(action: PlannedAction, snapshot: ScreenSnapshot): Boolean {
        when (action) {
            is PlannedAction.LaunchApp -> go("home")
            is PlannedAction.Click -> {
                val e = snapshot.elements[action.elementIndex]
                val label = e.label ?: snapshot.descendants(e.index).firstNotNullOfOrNull { it.label } ?: "?"
                clicked += label
                if (label == "ADD") addedRow = Descriptors.rowContext(snapshot, e.index).first()
                transitions[name to label]?.let(::go)
            }
            is PlannedAction.SetText -> { typed += action.text; go("results") }
            else -> Unit
        }
        return true
    }
}

class ReplayEngineTest {
    private val pkg = "in.swiggy.android"
    private val screens: Map<String, (Long) -> ScreenSnapshot> = mapOf(
        "home" to { id -> screen(pkg, id) { text("Swiggy"); button("Search for restaurant and food") } },
        "search" to { id -> screen(pkg, id) { edit(hint = "Search for restaurants and food") } },
        "results" to { id ->
            screen(pkg, id) {
                listOf("Garlic Bread" to "₹99", "Paneer Tikka" to "₹199", "Veg Burger" to "₹149").forEach { (n, p) ->
                    val row = container(); text(n, row); text(p, row); button("ADD", row)
                }
            }
        },
        "cart" to { id -> screen(pkg, id) { text("Paneer Tikka"); icon("Click here to change delivery address"); icon("Pay ₹199 using UPI") } },
        "promo" to { id -> screen(pkg, id) { val d = container(className = "android.app.Dialog"); text("Get 50% off with Gold", d); button("Not now", d) } },
    )

    private fun flow(): Flow {
        val home = screens.getValue("home")(1)
        val search = screens.getValue("search")(2)
        val results = screens.getValue("results")(3)
        val add = results.elements.first { it.label == "ADD" }.index // first row = Garlic Bread
        val addTarget = Descriptors.describe(results, add).let { d -> d.copy(context = d.context.map { if (it == "Garlic Bread") "{item}" else it }) }
        return Flow(
            "food", "order {item}", pkg, "Swiggy", "order {item}", listOf("order garlic bread"),
            listOf(SlotDef("item", SlotType.TEXT, "garlic bread")),
            listOf(
                Step.LaunchApp(pkg, "Swiggy"),
                Step.Tap(Descriptors.describe(home, 1)),
                Step.TypeText(Descriptors.describe(search, 0), slot = "item"),
                Step.Tap(addTarget, slot = "item"),
            ),
        )
    }

    private fun phone(start: String = "home", extra: Map<Pair<String, String>, String> = emptyMap()) = FakePhone(
        screens,
        mapOf(("home" to "Search for restaurant and food") to "search", ("results" to "ADD") to "cart") + extra,
        start,
    )

    @Test fun `replays with a new slot value and hands off at checkout (T2, T4, T11)`() = runTest {
        val p = phone()
        val r = ReplayEngine(p, p.guard).run(flow(), mapOf("item" to "paneer tikka"))
        assertEquals(RunStatus.HANDED_OFF, r.status, r.toString())
        assertEquals(listOf("paneer tikka"), p.typed)
        assertEquals(listOf("Search for restaurant and food", "ADD"), p.clicked)
        assertTrue(r.message.contains("₹199"), r.message)
    }

    @Test fun `picks the ADD button in the row of the requested item`() = runTest {
        for ((item, row) in listOf("veg burger" to "Veg Burger", "garlic bread" to "Garlic Bread", "paneer tikka" to "Paneer Tikka")) {
            val p = phone()
            ReplayEngine(p, p.guard).run(flow(), mapOf("item" to item))
            assertEquals(row, p.addedRow, "for $item")
        }
    }

    @Test fun `closes a promo popup, then continues (T7)`() = runTest {
        val p = phone(extra = mapOf(("promo" to "Not now") to "home"))
        p.go("promo")
        val engine = ReplayEngine(p, p.guard)
        // Launch lands on home in the fake; put the promo up after launch by starting there.
        val r = engine.run(flow().copy(steps = flow().steps.drop(1)), mapOf("item" to "garlic bread"))
        assertEquals(RunStatus.HANDED_OFF, r.status, r.toString())
        assertTrue(p.clicked.first() == "Not now", p.clicked.toString())
        assertTrue(r.events.any { it.contains("closed popup") }, r.events.toString())
    }

    @Test fun `missing item asks mid-flow (B3)`() = runTest {
        val p = phone()
        p.answers.addLast("veg burger")
        val r = ReplayEngine(p, p.guard).run(flow(), emptyMap())
        assertEquals(RunStatus.HANDED_OFF, r.status, r.toString())
        assertTrue(p.questions.first().contains("Which item"), p.questions.toString())
        assertEquals(listOf("veg burger"), p.typed)
    }

    @Test fun `item not on screen asks with the visible options, no wrong tap (T10)`() = runTest {
        val p = phone()
        p.answers.addLast(null)
        val r = ReplayEngine(p, p.guard).run(flow(), mapOf("item" to "sushi platter"))
        assertEquals(RunStatus.NO_ANSWER, r.status, r.toString())
        assertTrue(p.questions.any { it.contains("sushi platter") && it.contains("Paneer Tikka") }, p.questions.toString())
        assertEquals(listOf("Search for restaurant and food"), p.clicked) // never tapped an ADD
    }

    @Test fun `stuck on an unknown screen stops with a specific reason`() = runTest {
        val p = FakePhone(screens + ("blank" to { id -> screen(pkg, id) { text("Something new") } }), emptyMap(), "home")
        val f = flow()
        val r = ReplayEngine(p, p.guard, stepBudgetMs = 3_000).run(f.copy(steps = f.steps.drop(1)), mapOf("item" to "x"))
        // Home has the search button, but tapping it goes nowhere in this fake, so step 3 can't find the field.
        assertEquals(RunStatus.HALTED, r.status, r.toString())
        assertTrue(r.message.contains("couldn't find"), r.message)
    }
}
