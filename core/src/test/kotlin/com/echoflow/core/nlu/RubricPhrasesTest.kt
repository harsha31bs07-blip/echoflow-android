package com.echoflow.core.nlu

import com.echoflow.core.decision.Decision
import com.echoflow.core.decision.DecisionLayer
import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.SlotDef
import com.echoflow.core.flow.SlotType
import com.echoflow.core.flow.Step
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.replay.ReplayEngine
import com.echoflow.core.runlog.RunRecord
import com.echoflow.core.runlog.RunStatus
import com.echoflow.core.teach.FlowCompiler
import com.echoflow.core.teach.Fingerprints
import com.echoflow.core.teach.RawAction
import com.echoflow.core.testing.screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The official Theme 3 test phrases (Evaluation Criteria T1–T14), run against a flow compiled from
 * a synthetic Zomato / Domino's demonstration. The device runs are in docs/TEST_RUN.md.
 */
class RubricPhrasesTest {
    private val zomato = "com.application.zomato"
    private val home = screen(zomato, id = 1) { text("Zomato"); button("Search for restaurant, item or more") }
    private val search = screen(zomato, id = 2) { edit(hint = "Restaurant name or a dish...") }
    private val results = screen(zomato, id = 3) {
        val r = container(clickable = true); text("Domino's Pizza", r); text("Pizza, Fast Food", r)
        val r2 = container(clickable = true); text("Pizza Hut", r2)
    }
    private val menu = screen(zomato, id = 4) {
        text("Domino's Pizza")
        val r1 = container(); text("Margherita", r1); text("₹109", r1); button("ADD", r1)
        val r2 = container(); text("Farmhouse", r2); text("₹239", r2); button("ADD", r2)
    }

    private fun tapText(s: ScreenSnapshot, label: String, at: Long): RawAction =
        Fingerprints.tap(s, s.elements.first { it.text == label }.index, at)

    private fun teachZomato(): Flow {
        val actions = listOf(
            tapText(home, "Search for restaurant, item or more", 1_000),
            Fingerprints.type(search, 0, "Domino's", 2_000),
            tapText(results, "Domino's Pizza", 3_000),
            Fingerprints.tap(menu, menu.elements.first { it.text == "ADD" }.index, 4_000), // Margherita's ADD
        )
        return FlowCompiler().compile("z1", "Order a Margherita pizza from Domino's on Zomato.", actions, "Zomato").flow
    }

    private val flow by lazy { teachZomato() }
    private val m = IntentMatcher()
    private fun decide(u: String, flows: List<Flow> = listOf(flow)) = DecisionLayer.decide(u, m.match(u, flows))

    @Test fun `T1 teaching binds the restaurant and the item separately`() {
        assertEquals("order a {item} pizza from {restaurant} on zomato", flow.template)
        assertEquals("margherita", flow.slot("item")!!.taughtValue)
        assertEquals(listOf("pizza"), flow.slot("item")!!.qualifiers)
        assertEquals("dominos", flow.slot("restaurant")!!.taughtValue)
        val type = assertIs<Step.TypeText>(flow.steps[2])
        assertEquals("restaurant", type.slot)
        assertEquals("restaurant", assertIs<Step.Tap>(flow.steps[3]).slot)
        assertEquals("item", assertIs<Step.Tap>(flow.steps[4]).slot)
    }

    @Test fun `typing only part of the spoken item makes the rest a qualifier`() {
        val restMenu = screen(zomato, id = 5) { edit(hint = "Search within menu") }
        val found = screen(zomato, id = 6) { val r = container(); text("Margherita Pizza", r); text("₹225", r); button("ADD", r) }
        val actions = listOf(
            tapText(home, "Search for restaurant, item or more", 1_000),
            Fingerprints.type(search, 0, "Brik Oven", 2_000),
            tapText(results, "Domino's Pizza", 3_000),
            Fingerprints.type(restMenu, 0, "margherita", 4_000),
            Fingerprints.tap(found, found.elements.first { it.text == "ADD" }.index, 5_000),
        )
        val f = FlowCompiler().compile("z3", "Order a Margherita pizza from Brik Oven on Zomato.", actions, "Zomato").flow
        assertEquals("order a {item} pizza from {restaurant} on zomato", f.template)
        assertEquals("margherita", f.slot("item")!!.taughtValue)
        assertEquals(listOf("pizza"), f.slot("item")!!.qualifiers)
        assertEquals("brik oven", f.slot("restaurant")!!.taughtValue)
        for (u in listOf("Get me a margherita from brik oven", "Order a Margherita from Brik Oven, deliver to work.")) {
            val d = assertIs<Decision.Proceed>(decide(u, listOf(f)), u)
            assertEquals("margherita", d.candidate.slots["item"], u)
            assertEquals("brik oven", d.candidate.slots["restaurant"], u)
        }
    }

    @Test fun `T2 exact utterance runs with the taught values`() {
        val d = assertIs<Decision.Proceed>(decide("Order a Margherita pizza from Domino's on Zomato."))
        assertEquals(mapOf("item" to "margherita", "restaurant" to "dominos"), d.candidate.slots)
    }

    @Test fun `T3 both paraphrases map to the flow`() {
        val a = assertIs<Decision.Proceed>(decide("Get me a margherita from dominos"))
        assertEquals(mapOf("item" to "margherita", "restaurant" to "dominos"), a.candidate.slots)
        // No restaurant said: runs, and asks for it when the restaurant step comes (B3).
        val b = assertIs<Decision.Proceed>(decide("I want to order margherita pizza on zomato"))
        assertEquals("margherita", b.candidate.slots["item"])
        assertNull(b.candidate.slots["restaurant"])
        assertEquals(listOf("restaurant"), b.candidate.missing)
    }

    @Test fun `T4 different item`() {
        val d = assertIs<Decision.Proceed>(decide("Order a Farmhouse pizza from Domino's on Zomato."))
        assertEquals("farmhouse", d.candidate.slots["item"])
        assertEquals("dominos", d.candidate.slots["restaurant"])
    }

    @Test fun `speech spellings of taught names are read as the taught names`() {
        // Speech recognition writes "margarita" and "dominoes"; the app knows "margherita" and "dominos".
        val d = assertIs<Decision.Proceed>(decide("Order a margarita pizza from dominoes on Zomato."))
        assertEquals("margherita", d.candidate.slots["item"])
        assertEquals("dominos", d.candidate.slots["restaurant"])
        // A really different value is kept.
        assertEquals("farmhouse", assertIs<Decision.Proceed>(decide("Order a Farmhouse pizza from Domino's on Zomato.")).candidate.slots["item"])
    }

    @Test fun `T5 quantity in words with a plural`() {
        val d = assertIs<Decision.Proceed>(decide("Order two Margherita pizzas from Domino's."))
        assertEquals(mapOf("item" to "margherita", "restaurant" to "dominos", "qty" to "2"), d.candidate.slots)
    }

    @Test fun `the acknowledgement names an apostrophe app once`() {
        val dominos = flow.copy(appPackage = "com.Dominos", appLabel = "Domino's", template = "order a {item} pizza on dominos")
        val c = Candidate(dominos, 1.0, mapOf("item" to "farmhouse"), "exact")
        assertEquals("order a farmhouse pizza on dominos", DecisionLayer.describe(c))
    }

    @Test fun `several of an item search for the singular`() {
        val d = assertIs<Decision.Proceed>(decide("Order two choco lava cakes from Domino's on Zomato."))
        assertEquals("choco lava cake", d.candidate.slots["item"])
        assertEquals("2", d.candidate.slots["qty"])
    }

    @Test fun `T5 two heard as to by speech recognition`() {
        val d = assertIs<Decision.Proceed>(decide("order to Margherita pizzas from Domino's"))
        assertEquals(mapOf("item" to "margherita", "restaurant" to "dominos", "qty" to "2"), d.candidate.slots)
    }

    @Test fun `T5 confirmation wording puts the number in place of the article`() {
        val c = m.match("Order two Margherita pizzas from Domino's.", listOf(flow)).first()
        assertEquals("order 2 margherita pizza from dominos on zomato", DecisionLayer.describe(c))
    }

    @Test fun `T6 deliver to work`() {
        val d = assertIs<Decision.Proceed>(decide("Order a Margherita from Domino's, deliver to work."))
        assertEquals(mapOf("item" to "margherita", "restaurant" to "dominos", "address" to "work"), d.candidate.slots)
    }

    @Test fun `T8 and T9 amazon add-to-cart flow generalises across search terms`() {
        val amazon = "in.amazon.mShop.android.shopping"
        val home = screen(amazon, id = 11) { edit(hint = "Search Amazon.in") }
        val results = screen(amazon, id = 12) {
            val r = container(clickable = true); text("boAt Airdopes 141 Wireless Earbuds", r)
        }
        val product = screen(amazon, id = 13) { text("boAt Airdopes 141"); button("Add to Cart") }
        val shop = FlowCompiler().compile(
            "a1", "Search for wireless earbuds on Amazon and add the first result to cart.",
            listOf(
                Fingerprints.type(home, 0, "wireless earbuds", 1_000),
                tapText(results, "boAt Airdopes 141 Wireless Earbuds", 2_000),
                tapText(product, "Add to Cart", 3_000),
            ),
            "Amazon",
        ).flow
        assertEquals("search for {item} on amazon and add the first result to cart", shop.template)
        val d = assertIs<Decision.Proceed>(decide("Search for a phone case on Amazon and add the first result to cart.", listOf(flow, shop)))
        assertEquals("a1", d.candidate.flow.id)
        assertEquals("phone case", d.candidate.slots["item"])
    }

    @Test fun `T8 when the app reports only the typing, the command supplies first-result and add-to-cart`() {
        val amazon = "in.amazon.mShop.android.shopping"
        val home = screen(amazon, id = 31) { edit(hint = "Search Amazon.in") }
        val f = FlowCompiler().compile(
            "a2", "Search for wireless earbuds on Amazon and add the first result to cart.",
            listOf(Fingerprints.type(home, 0, "wireless earbuds", 1_000)), "Amazon",
        ).flow
        assertEquals(listOf(null, null, "first", "add_to_cart"), f.steps.map { (it as? Step.Tap)?.pick })
        assertEquals("item", (f.steps[2] as Step.Tap).slot)
    }

    @Test fun `B2 an Amazon flow is offered on Myntra and Flipkart, confirmed first`() {
        val amazon = "in.amazon.mShop.android.shopping"
        val home = screen(amazon, id = 41) { edit(hint = "Search Amazon.in") }
        val shop = FlowCompiler().compile(
            "a3", "Search for wireless earbuds on Amazon and add the first result to cart.",
            listOf(Fingerprints.type(home, 0, "wireless earbuds", 1_000)), "Amazon",
        ).flow
        for ((app, pkg) in listOf("Myntra" to "com.myntra.android", "Flipkart" to "com.flipkart.android")) {
            val d = assertIs<Decision.Confirm>(decide("Search for a phone case on $app and add the first result to cart.", listOf(flow, shop)))
            assertEquals(pkg, d.candidate.targetApp)
            assertEquals("phone case", d.candidate.slots["item"])
            assertTrue(d.question.startsWith("I learned this on Amazon.") && d.question.contains("on ${app.lowercase()}"), d.question)
            val moved = shop.retargeted(pkg, app)
            assertEquals(pkg, (moved.steps.first() as Step.LaunchApp).packageName)
        }
        // The flow for the named app wins when there is one; a food flow is never tried on Amazon.
        assertIs<Decision.OfferTeach>(decide("Order a Margherita pizza from Domino's on Amazon.", listOf(flow)))
    }

    @Test fun `T12 unknown intent offers to teach`() {
        assertIs<Decision.OfferTeach>(decide("Book a cab to the airport."))
    }

    @Test fun `T13 order pizza is never run silently`() {
        val d = assertIs<Decision.Confirm>(decide("Order pizza."))
        assertTrue(d.question.contains("which one"), d.question)
        assertEquals("z1", d.candidate.flow.id)
        // With a generic Swiggy "order {item}" flow as well: ask which one.
        val swiggy = Flow(
            "s1", "order {item}", "in.swiggy.android", "Swiggy", "order {item}", listOf("order garlic bread"),
            listOf(SlotDef("item", SlotType.TEXT, "garlic bread")), emptyList(),
        )
        assertIs<Decision.Disambiguate>(decide("Order pizza.", listOf(swiggy, flow)))
    }

    @Test fun `T14 did the last run succeed`() {
        assertEquals(MetaIntent.Report, m.meta("Did the last run succeed?"))
        val ok = RunRecord(
            id = "r1", utterance = "order a margherita pizza from dominos on zomato", flowId = "z1", flowName = flow.template,
            slots = mapOf("item" to "margherita", "restaurant" to "dominos"), startedAtMs = 0, status = RunStatus.HANDED_OFF,
            stoppedAtStep = 5, totalSteps = 5, message = "Your turn.",
        )
        assertTrue(ok.spokenSummary().startsWith("Yes"), ok.spokenSummary())
        val stuck = ok.copy(status = RunStatus.HALTED, stoppedAtStep = 3, stepDescription = "Tap \"{restaurant}\"", message = "I can't find it.")
        assertTrue(stuck.spokenSummary().startsWith("No") && stuck.spokenSummary().contains("step 3"), stuck.spokenSummary())
    }

    @Test fun `B3 asks for the restaurant and accepts same as last time`() {
        val def = flow.slot("restaurant")
        assertEquals("Which restaurant should I order from? Last time it was dominos.", ReplayEngine.slotQuestion("restaurant", def))
        assertEquals("dominos", ReplayEngine.slotAnswer("same as last time", def))
        assertEquals("pizza hut", ReplayEngine.slotAnswer("from Pizza Hut", def))
        assertEquals("Which pizza do you want? Last time it was margherita.", ReplayEngine.slotQuestion("item", flow.slot("item")))
        assertEquals("farmhouse", ReplayEngine.slotAnswer("a farmhouse pizza", flow.slot("item")))
    }

    @Test fun `B1 taps in the phone app during teaching are dropped`() {
        val call = screen("com.samsung.android.incallui", id = 20) { button("Decline") }
        val actions = listOf(
            tapText(home, "Search for restaurant, item or more", 1_000),
            Fingerprints.tap(call, 0, 1_500).copy(packageName = "com.samsung.android.incallui"),
            Fingerprints.type(search, 0, "Domino's", 2_000),
        )
        val r = FlowCompiler().compile("z2", "order a margherita pizza from dominos on zomato", actions, "Zomato")
        assertTrue(r.dropped.any { it.contains("incallui") }, r.dropped.toString())
        assertTrue(r.flow.steps.none { it.description.contains("Decline") })
    }

    @Test fun `yes and no answers`() {
        assertTrue(ReplayEngine.isYes("yes please"))
        assertTrue(!ReplayEngine.isYes("no, don't do it"))
    }
}
