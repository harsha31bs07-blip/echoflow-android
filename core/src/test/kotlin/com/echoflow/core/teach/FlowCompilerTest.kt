package com.echoflow.core.teach

import com.echoflow.core.flow.Step
import com.echoflow.core.nlu.IntentMatcher
import com.echoflow.core.nlu.Utterances
import com.echoflow.core.testing.screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlowCompilerTest {
    private val pkg = "in.swiggy.android"
    private val home = screen(pkg, id = 1) { text("Swiggy"); button("Search for restaurant and food") }
    private val search = screen(pkg, id = 2) { edit(hint = "Search for restaurants and food") }
    private val results = screen(pkg, id = 3) {
        val r1 = container(); text("Garlic Bread", r1); text("₹99", r1); button("ADD").also { }
        val r2 = container(); text("Paneer Tikka", r2); text("₹199", r2)
    }
    private val cart = screen(pkg, id = 4) { text("Garlic Bread"); icon("add one more"); button("Proceed") }

    private fun tap(s: com.echoflow.core.model.ScreenSnapshot, label: String, at: Long, post: com.echoflow.core.model.ScreenSnapshot?) =
        Fingerprints.tap(s, s.elements.first { it.label == label }.index, at).copy(postFingerprint = post?.let(Fingerprints::of))

    @Test fun `utterance parsing finds quantity, address, app and item`() {
        val p = Utterances.parse("order 2 garlic bread to work on swiggy")
        assertEquals(2, p.quantity)
        assertEquals("work", p.address)
        assertEquals("swiggy", p.appMention)
        assertEquals("garlic bread", p.item)
        assertEquals("order three paneer tikka", Utterances.stripTeachPrefix("teach me to order three paneer tikka"))
    }

    @Test fun `compiles a demonstration into a parameterised flow and drops noise`() {
        val actions = listOf(
            Fingerprints.tap(home, 1, 0).copy(packageName = "com.sec.android.app.launcher"), // launcher icon
            tap(home, "Search for restaurant and food", 1_000, search),
            Fingerprints.type(search, 0, "garlic", 2_000),
            Fingerprints.type(search, 0, "garlic bread", 2_500),
            tap(results, "Garlic Bread", 4_000, cart),
            tap(results, "Garlic Bread", 4_100, cart), // accidental double tap
            tap(cart, "add one more", 6_000, null),
        )
        val r = FlowCompiler().compile("f1", "teach order 2 garlic bread", actions, appLabel = "Swiggy")
        val f = r.flow
        assertEquals("order {qty} {item}", f.template)
        assertEquals(listOf("item", "qty"), f.slots.map { it.name })
        assertEquals("garlic bread", f.slot("item")!!.taughtValue)
        assertIs<Step.LaunchApp>(f.steps[0])
        val type = assertIs<Step.TypeText>(f.steps[2])
        assertEquals("item", type.slot)
        val pick = assertIs<Step.Tap>(f.steps[3])
        assertEquals("item", pick.slot)
        assertEquals("{item}", pick.target.text)
        val stepper = assertIs<Step.RepeatTap>(f.steps[4])
        assertEquals(1, stepper.offset) // qty 2 = ADD(1) + one "+" tap
        assertEquals(5, f.steps.size)
        assertTrue(r.dropped.any { it.contains("double tap") }, r.dropped.toString())
        assertTrue(r.dropped.any { it.contains("launcher") }, r.dropped.toString())
    }

    @Test fun `slot values match word prefixes`() {
        assertTrue(com.echoflow.core.flow.ElementResolver.valueMatch("garlic bread", null, listOf("Garlic Breadsticks", "₹99")) > 0)
        assertEquals(0.0, com.echoflow.core.flow.ElementResolver.valueMatch("garlic bread", null, listOf("Paneer Tikka")))
    }

    @Test fun `detours back to the same screen are removed`() {
        val restaurant = screen(pkg, id = 9) { text("Wrong restaurant"); button("Back to list") }
        val actions = listOf(
            tap(home, "Swiggy", 0, restaurant).copy(preFingerprint = "HOME"),
            tap(restaurant, "Back to list", 1_000, home).copy(preFingerprint = "R"),
            tap(home, "Search for restaurant and food", 2_000, search).copy(preFingerprint = "HOME"),
        )
        val r = FlowCompiler().compile("f2", "open search", actions)
        assertEquals(2, r.flow.steps.size, r.flow.steps.toString()) // launch + search tap
        assertEquals(2, r.dropped.count { it.startsWith("detour") })
    }

    private fun pizzaDemonstration(item: String = "margherita", extraSearches: List<String> = emptyList()): List<RawAction> {
        val app = "com.application.zomato"
        val restaurantSearch = screen(app, id = 10) { edit(hint = "Search restaurants") }
        val restaurantResult = screen(app, id = 11) { text("Brik Oven") }
        val menuSearch = screen(app, id = 12) { edit(hint = "Search menu") }
        val menuResult = screen(app, id = 13) { text("Margherita"); button("ADD") }
        val actions = mutableListOf<RawAction>()
        extraSearches.forEachIndexed { i, value ->
            actions += Fingerprints.type(restaurantSearch, 0, value, i * 1_000L)
            actions += tap(restaurantResult, "Brik Oven", i * 1_000L + 500, null)
        }
        actions += Fingerprints.type(restaurantSearch, 0, "Brik Oven", 10_000)
        actions += tap(restaurantResult, "Brik Oven", 11_000, menuSearch)
        actions += Fingerprints.type(menuSearch, 0, item, 12_000)
        actions += tap(menuResult, "Margherita", 13_000, null)
        actions += tap(menuResult, "ADD", 14_000, null)
        return actions
    }

    @Test fun `observed native spelling reconciles recognized names and keeps replay parameterized`() {
        val recognized = listOf(
            "teach order a mergerita pizza from brick oven on Zomato" to "order a {item} pizza from {restaurant} on zomato",
            "teach order amargeria pizza from brick oven on Zomato" to "order {item} pizza from {restaurant} on zomato",
        )
        for ((utterance, expectedTemplate) in recognized) {
            val flow = FlowCompiler().compile("speech", utterance, pizzaDemonstration()).flow
            assertEquals(expectedTemplate, flow.template, utterance)
            assertEquals("margherita", flow.slot("item")!!.taughtValue, utterance)
            assertEquals(listOf("pizza"), flow.slot("item")!!.qualifiers, utterance)
            assertEquals("brik oven", flow.slot("restaurant")!!.taughtValue, utterance)
            // Keep the recognizer's words as the example; only the demonstrated values are canonical.
            assertEquals(listOf(Utterances.stripTeachPrefix(utterance)), flow.examples)
            assertEquals(listOf("restaurant", "item"), flow.steps.filterIsInstance<Step.TypeText>().map { it.slot })
            assertEquals("{restaurant}", flow.steps.filterIsInstance<Step.Tap>().first().target.text)
            assertEquals("{item}", flow.steps.filterIsInstance<Step.Tap>()[1].target.text)

            val changed = IntentMatcher().match("Order two Farmhouse pizzas from brick oven on Zomato", listOf(flow)).single()
            assertTrue(changed.score >= IntentMatcher.RELAXED_SCORE, "$utterance: $changed")
            assertEquals("farmhouse", changed.slots["item"], utterance)
            assertEquals("brik oven", changed.slots["restaurant"], utterance)
            assertEquals("2", changed.slots["qty"], utterance)
        }
    }

    @Test fun `unrelated demonstrated searches remain literal instead of acquiring a spoken slot`() {
        val flow = FlowCompiler().compile("speech", "teach order a mergerita pizza from brick oven on zomato",
            pizzaDemonstration(extraSearches = listOf("summer offers", "discount code"))).flow
        val searches = flow.steps.filterIsInstance<Step.TypeText>()
        assertEquals(listOf("summer offers", "discount code"), searches.take(2).map { it.literal })
        assertTrue(searches.take(2).all { it.slot == null })
        assertEquals(listOf("restaurant", "item"), searches.drop(2).map { it.slot })
    }

    @Test fun `ambiguous near spellings are not selected as the canonical teaching value`() {
        val flow = FlowCompiler().compile("speech", "teach order a mergerita pizza from brick oven on zomato",
            pizzaDemonstration(extraSearches = listOf("mergerito"))).flow
        assertEquals("mergerita pizza", flow.slot("item")!!.taughtValue)
        assertTrue(flow.steps.filterIsInstance<Step.TypeText>().filter { it.literal in listOf("mergerito", "margherita") }
            .all { it.slot == null })
        assertEquals("order a {item} from {restaurant} on zomato", flow.template)
    }
}
