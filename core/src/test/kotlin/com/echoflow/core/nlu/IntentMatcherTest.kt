package com.echoflow.core.nlu

import com.echoflow.core.decision.Decision
import com.echoflow.core.decision.DecisionLayer
import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.SlotDef
import com.echoflow.core.flow.SlotType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntentMatcherTest {
    private val food = Flow(
        "food", "order {qty} {item}", "in.swiggy.android", "Swiggy", "order {qty} {item}",
        listOf("order 2 garlic bread"),
        listOf(SlotDef("qty", SlotType.NUMBER, "2"), SlotDef("item", SlotType.TEXT, "garlic bread")), emptyList(),
    )
    private val shop = Flow(
        "shop", "search {item} on amazon", "in.amazon.mShop.android.shopping", "Amazon", "search {item} on amazon",
        listOf("search running shoes on amazon"), listOf(SlotDef("item", SlotType.TEXT, "running shoes")), emptyList(),
    )
    private val zomato = food.copy(id = "zomato", appPackage = "com.application.zomato", appLabel = "Zomato")
    private val m = IntentMatcher()

    private fun decide(u: String, flows: List<Flow> = listOf(food, shop), llm: LlmIntent? = null) =
        DecisionLayer.decide(u, m.match(u, flows, llm))

    @Test fun `exact utterance proceeds with taught values (T2)`() {
        val d = assertIs<Decision.Proceed>(decide("Order 2 garlic bread"))
        assertEquals("food", d.candidate.flow.id)
        assertEquals("garlic bread", d.candidate.slots["item"])
    }

    @Test fun `same shape with new values proceeds (T4, T5)`() {
        val d = assertIs<Decision.Proceed>(decide("order three paneer tikka"))
        assertEquals(mapOf("qty" to "3", "item" to "paneer tikka"), d.candidate.slots)
    }

    @Test fun `address is split out of a greedy item slot (T6)`() {
        val itemOnly = food.copy(template = "order {item}", slots = listOf(SlotDef("item", SlotType.TEXT, "garlic bread")), examples = listOf("order garlic bread"))
        val d = assertIs<Decision.Proceed>(DecisionLayer.decide("x", m.match("order paneer tikka to hostel", listOf(itemOnly))))
        assertEquals("paneer tikka", d.candidate.slots["item"])
        assertEquals("hostel", d.candidate.slots["address"])
    }

    @Test fun `offline paraphrase of the same shape runs (T3 without LLM)`() {
        val d = assertIs<Decision.Proceed>(decide("can you get me garlic bread from swiggy"))
        assertEquals("food", d.candidate.flow.id)
        assertEquals("garlic bread", d.candidate.slots["item"])
    }

    @Test fun `offline paraphrase of a different shape is confirmed`() {
        val d = assertIs<Decision.Confirm>(decide("garlic bread is what i would like on swiggy"))
        assertEquals("food", d.candidate.flow.id)
    }

    @Test fun `LLM paraphrase with high confidence proceeds (T3)`() {
        val d = assertIs<Decision.Proceed>(decide("I'm hungry, garlic bread please", llm = LlmIntent("food", 0.93, mapOf("item" to "garlic bread"))))
        assertEquals("llm", d.candidate.source)
    }

    @Test fun `unknown command offers to teach, never guesses (T12)`() {
        assertIs<Decision.OfferTeach>(decide("set an alarm for seven"))
        assertIs<Decision.OfferTeach>(decide("book a cab to the airport"))
    }

    @Test fun `ambiguous command asks which flow (T13)`() {
        val d = assertIs<Decision.Disambiguate>(decide("get me garlic bread", listOf(food, zomato)))
        assertEquals(2, d.options.size)
        assertTrue(d.question.contains("Swiggy") && d.question.contains("Zomato"), d.question)
    }

    @Test fun `app mention resolves the ambiguity`() {
        val d = decide("get me garlic bread on zomato", listOf(food, zomato))
        val c = assertIs<Decision.Proceed>(d)
        assertEquals("zomato", c.candidate.flow.id)
    }

    @Test fun `a named app picks that app's flow even when another template fits (T13)`() {
        val swiggyItem = food.copy(template = "order {item}", slots = listOf(SlotDef("item", SlotType.TEXT, "garlic bread")), examples = listOf("order garlic bread"))
        val zomatoItem = swiggyItem.copy(id = "z", appPackage = "com.application.zomato", appLabel = "Zomato", template = "order {item} on zomato", examples = listOf("order garlic bread on zomato"))
        val d = assertIs<Decision.Proceed>(DecisionLayer.decide("x", m.match("order paneer tikka on zomato", listOf(swiggyItem, zomatoItem))))
        assertEquals("z", d.candidate.flow.id)
        assertEquals("paneer tikka", d.candidate.slots["item"])
    }

    @Test fun `absurd quantity is confirmed`() {
        assertIs<Decision.Confirm>(decide("order 50 garlic bread"))
    }

    @Test fun `meta intents`() {
        assertIs<MetaIntent.Teach>(m.meta("teach order garlic bread"))
        assertEquals(MetaIntent.Done, m.meta("done"))
        assertEquals(MetaIntent.Report, m.meta("what happened last time?"))
        assertEquals(MetaIntent.Stop, m.meta("Stop"))
        assertEquals(null, m.meta("order garlic bread"))
    }
}
