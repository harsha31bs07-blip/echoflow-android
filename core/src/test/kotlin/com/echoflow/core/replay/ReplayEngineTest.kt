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

    @Test fun `first result is picked by position, not by the taught title (T8, T9)`() = runTest {
        val amz = "in.amazon.mShop.android.shopping"
        fun results(titles: List<String>): (Long) -> ScreenSnapshot = { id ->
            screen(amz, id) {
                edit(hint = "Search Amazon.in"); text("Showing results for your search"); text("Prime")
                titles.forEach { t -> val row = container(clickable = true); text(t, row) }
            }
        }
        val product: (Long) -> ScreenSnapshot = { id -> screen(amz, id) { text("Product details"); button("Add to Cart") } }
        val added: (Long) -> ScreenSnapshot = { id -> screen(amz, id) { text("Added to Cart") } }
        val flow = Flow(
            "a1", "search for {item} on amazon and add the first result to cart", amz, "Amazon",
            "search for {item} on amazon and add the first result to cart", listOf("search for wireless earbuds on amazon and add the first result to cart"),
            listOf(SlotDef("item", SlotType.TEXT, "wireless earbuds")),
            listOf(
                Step.LaunchApp(amz, "Amazon"),
                Step.TypeText(com.echoflow.core.flow.ElementDescriptor(className = "android.widget.EditText"), slot = "item"),
                Step.Tap(com.echoflow.core.flow.ElementDescriptor(className = "android.widget.TextView", text = "{item}"), slot = "item", pick = "first"),
                Step.Tap(com.echoflow.core.flow.ElementDescriptor(className = "android.widget.Button", text = "Add to Cart")),
            ),
        )
        for ((titles, expected) in listOf(
            // None names the item: plain position.
            listOf("Spigen Ultra Hybrid Back Cover for iPhone 15", "Amazon Basics Charging Cable 1m") to "Spigen Ultra Hybrid Back Cover for iPhone 15",
            // One of the top three names it: that one.
            listOf("Amazon Basics Charging Cable 1m", "OtterBox Phone Case for Galaxy S24") to "OtterBox Phone Case for Galaxy S24",
        )) {
            val p = FakePhone(
                mapOf("home" to { id -> screen(amz, id) { edit(hint = "Search Amazon.in") } }, "results" to results(titles), "product" to product, "added" to added),
                mapOf(("results" to expected) to "product", ("product" to "Add to Cart") to "added"),
                "home",
            )
            val r = ReplayEngine(p, p.guard).run(flow, mapOf("item" to "phone case"))
            assertEquals(RunStatus.COMPLETED, r.status, r.toString())
            assertEquals(listOf(expected, "Add to Cart"), p.clicked)
        }
    }

    @Test fun `logged out - a login screen stops the run as not succeeded (T10, T14)`() = runTest {
        val login: (Long) -> ScreenSnapshot = { id -> screen(pkg, id) { text("Log in or sign up"); edit(hint = "Enter phone number", inputType = com.echoflow.core.model.InputTypes.TYPE_CLASS_PHONE); button("Continue") } }
        val p = FakePhone(screens + ("home" to login), emptyMap(), "search")
        val r = ReplayEngine(p, p.guard).run(flow(), mapOf("item" to "garlic bread"))
        assertEquals(RunStatus.HALTED, r.status, r.toString())
        assertTrue(r.message.contains("Your turn"), r.message)
        assertTrue(p.clicked.isEmpty(), p.clicked.toString())
        val report = com.echoflow.core.runlog.RunRecord(id = "r", utterance = "order garlic bread", flowId = "food", flowName = "order {item}",
            slots = mapOf("item" to "garlic bread"), startedAtMs = 0, status = r.status, stoppedAtStep = r.stoppedAtStep,
            totalSteps = r.totalSteps, stepDescription = r.stepDescription, message = r.message).spokenSummary()
        assertTrue(report.startsWith("No"), report)
    }

    @Test fun `back is allowed only on an empty sheet shell`() {
        val guard = SafetyGuard(ScreenSafetyClassifier())
        val empty = screen(pkg) { add { com.echoflow.core.model.UiElement(it, windowId = 1, className = "android.view.View", packageName = pkg, viewId = "$pkg:id/touch_outside", clickable = true, bounds = com.echoflow.core.model.Bounds(0, 0, 1080, 2400)) } }
        assertTrue(com.echoflow.core.safety.EmptySheet.matches(empty))
        assertTrue(guard.gate(PlannedAction.Back, empty, GateContext(isRecovery = true)) is com.echoflow.core.safety.GateDecision.Allow)
        assertTrue(guard.gate(PlannedAction.Click(empty.id, 0), empty, GateContext(isRecovery = true)) is com.echoflow.core.safety.GateDecision.Block)
        val blank = screen(pkg) { add { com.echoflow.core.model.UiElement(it, windowId = 1, className = "android.webkit.WebView", packageName = pkg, bounds = com.echoflow.core.model.Bounds(0, 0, 1080, 2400)) } }
        assertTrue(guard.gate(PlannedAction.Back, blank, GateContext(isRecovery = true)) is com.echoflow.core.safety.GateDecision.Block)
    }

    @Test fun `dish already in the cart is not added again (T7)`() = runTest {
        val inCart: (Long) -> ScreenSnapshot = { id ->
            screen(pkg, id) {
                val a = container(); text("Garlic Bread", a); text("₹99", a)
                // The stepper sits on one line: − 1 +
                fun cell(label: String, left: Int, clickable: Boolean) = add {
                    com.echoflow.core.model.UiElement(it, a, 1, className = "android.widget.TextView", packageName = pkg, text = label,
                        bounds = com.echoflow.core.model.Bounds(left, 900, left + 70, 960), clickable = clickable)
                }
                cell("−", 800, true); cell("1", 880, false); cell("+", 960, true)
                val b = container(); text("Paneer Tikka", b); text("₹199", b); button("ADD", b)
            }
        }
        val p = FakePhone(screens + ("results" to inCart), mapOf(("home" to "Search for restaurant and food") to "search"), "home")
        val r = ReplayEngine(p, p.guard).run(flow(), mapOf("item" to "garlic bread"))
        assertTrue(r.events.any { it.contains("already in the cart") }, "$r / clicked=${p.clicked}")
        assertTrue("+" !in p.clicked && "ADD" !in p.clicked, p.clicked.toString())
    }

    @Test fun `first result skips an AI summary and a video ad and takes the first product card (T9)`() = runTest {
        val amz = "in.amazon.mShop.android.shopping"
        fun t(label: String, top: Int, parent: Int, clickable: Boolean = false) = { b: com.echoflow.core.testing.ScreenBuilder ->
            b.add { com.echoflow.core.model.UiElement(it, parent, 1, className = "android.widget.TextView", packageName = amz, text = label,
                bounds = com.echoflow.core.model.Bounds(300, top, 1000, top + 60), clickable = clickable) }
        }
        val results: (Long) -> ScreenSnapshot = { id ->
            screen(amz, id) {
                edit(hint = "Search Amazon.in")
                val ai = add { com.echoflow.core.model.UiElement(it, -1, 1, className = "android.view.View", packageName = amz, bounds = com.echoflow.core.model.Bounds(0, 560, 1080, 1100), clickable = true) }
                t("Researched by AI", 600, ai)(this); t("Budget earbuds under ₹7,000 now deliver adaptive ANC and long battery life without flagship pricing.", 700, ai)(this)
                val video = add { com.echoflow.core.model.UiElement(it, -1, 1, className = "android.view.View", packageName = amz, bounds = com.echoflow.core.model.Bounds(0, 1150, 1080, 1500), clickable = true) }
                t("Noise Alt Clip Wireless Open-Earbuds (2026), Hi-Res Audio", 1200, video)(this); t("4.0", 1280, video)(this)
                t("Results", 1620, -1)(this)
                val card = add { com.echoflow.core.model.UiElement(it, -1, 1, className = "android.view.View", packageName = amz, bounds = com.echoflow.core.model.Bounds(0, 1700, 1080, 2300), clickable = true) }
                t("Sponsored Ad - Spigen Rugged Armor Back Cover Case for Galaxy S24", 1720, card)(this); t("3.8 out of 5 stars", 1800, card)(this); t("₹999", 1900, card)(this)
            }
        }
        val product: (Long) -> ScreenSnapshot = { id -> screen(amz, id) { text("Product details"); button("Add to Cart") } }
        val added: (Long) -> ScreenSnapshot = { id -> screen(amz, id) { text("Added to Cart") } }
        val flow = Flow(
            "a1", "search for {item} on amazon and add the first result to cart", amz, "Amazon",
            "search for {item} on amazon and add the first result to cart", listOf("search for wireless earbuds on amazon and add the first result to cart"),
            listOf(SlotDef("item", SlotType.TEXT, "wireless earbuds")),
            listOf(
                Step.LaunchApp(amz, "Amazon"),
                Step.TypeText(com.echoflow.core.flow.ElementDescriptor(className = "android.widget.EditText"), slot = "item"),
                Step.Tap(com.echoflow.core.flow.ElementDescriptor(className = "android.widget.TextView", text = "{item}"), slot = "item", pick = "first"),
                Step.Tap(com.echoflow.core.flow.ElementDescriptor(className = "android.widget.Button", text = "Add to Cart")),
            ),
        )
        val title = "Sponsored Ad - Spigen Rugged Armor Back Cover Case for Galaxy S24"
        val p = FakePhone(
            mapOf("home" to { id -> screen(amz, id) { edit(hint = "Search Amazon.in") } }, "results" to results, "product" to product, "added" to added),
            // (The fake names a tapped card by its last child: the price.)
            mapOf(("results" to "₹999") to "product", ("product" to "Add to Cart") to "added"),
            "home",
        )
        val r = ReplayEngine(p, p.guard).run(flow, mapOf("item" to "phone case"))
        assertEquals(RunStatus.COMPLETED, r.status, r.toString())
        assertTrue(r.events.any { it.contains(title) }, r.events.toString())
        assertEquals(listOf("₹999", "Add to Cart"), p.clicked)
    }

    @Test fun `an Amazon product page with an offer row is not an address sheet`() = runTest {
        val amz = "in.amazon.mShop.android.shopping"
        val page: (Long) -> ScreenSnapshot = { id ->
            screen(amz, id) {
                text("Deliver to 560054")
                val offer = container(clickable = true); text("Buy for", offer); text("₹3,600 with SBI credit card and no cost EMI", offer)
                button("Add to Cart")
            }
        }
        val added: (Long) -> ScreenSnapshot = { id -> screen(amz, id) { text("Added to Cart") } }
        val flow = Flow("a", "search for {item} on amazon", amz, "Amazon", "search for {item} on amazon", listOf("x"),
            listOf(SlotDef("item", SlotType.TEXT, "phone case")),
            listOf(Step.LaunchApp(amz, "Amazon"), Step.Tap(com.echoflow.core.flow.ElementDescriptor(text = "Add to cart", className = "android.widget.Button"), pick = "add_to_cart")))
        val p = FakePhone(mapOf("home" to page, "added" to added), mapOf(("home" to "Add to Cart") to "added"), "added")
        val r = ReplayEngine(p, p.guard).run(flow, mapOf("item" to "phone case"))
        assertTrue(r.events.none { it.contains("address") }, r.events.toString())
        assertEquals(listOf("Add to Cart"), p.clicked)
    }
}
