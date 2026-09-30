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

    private val pizza = Flow(
        "pizza", "order a {item} pizza from {restaurant} on zomato", "com.application.zomato", "Zomato", "order a {item} pizza from {restaurant} on zomato",
        listOf("order a margherita pizza from brik oven on zomato"),
        listOf(SlotDef("item", SlotType.TEXT, "margherita"), SlotDef("restaurant", SlotType.TEXT, "brik oven")), emptyList(),
    )
    private val youtube = Flow(
        "yt", "search for {item} on youtube", "com.google.android.youtube", "YouTube", "search for {item} on youtube",
        listOf("search for lofi music on youtube"), listOf(SlotDef("item", SlotType.TEXT, "lofi music")), emptyList(),
    )

    @Test fun `extras and a payment request are split off, not glued onto a name`() {
        val a = Utterances.splitExtras("Order a farmhouse pizza from Brik Oven with extra cheese")
        assertEquals("order a farmhouse pizza from brik oven", a.command)
        assertEquals(listOf("with extra cheese"), a.extras)
        val b = Utterances.splitExtras("order a farmhouse pizza from brik oven and pay with UPI")
        assertEquals("order a farmhouse pizza from brik oven", b.command)
        assertTrue(b.wantsPayment)
        val c = Utterances.splitExtras("order a farmhouse pizza without onion from brik oven")
        assertEquals("order a farmhouse pizza from brik oven", c.command)
        // Names that merely contain these words stay whole.
        assertEquals("order mac and cheese from brik oven", Utterances.splitExtras("order mac and cheese from brik oven").command)
        assertEquals("order an extra large pizza from brik oven", Utterances.splitExtras("order an extra large pizza from brik oven").command)
        val d = assertIs<Decision.Proceed>(decide(a.command, listOf(pizza)))
        assertEquals("brik oven", d.candidate.slots["restaurant"])
    }

    @Test fun `when the AI finds no matching flow, a similarity guess offers to learn instead of asking to run it`() {
        val addFirst = Flow(
            "amz", "search for {item} on amazon and add the first result to cart", "in.amazon.mShop.android.shopping", "Amazon",
            "search for {item} on amazon and add the first result to cart", listOf("search for wireless earbuds on amazon and add the first result to cart"),
            listOf(SlotDef("item", SlotType.TEXT, "wireless earbuds")), emptyList(),
        )
        val u = "show my wishlist on amazon"
        assertIs<Decision.Confirm>(decide(u, listOf(addFirst)))
        assertIs<Decision.OfferTeach>(decide(u, listOf(addFirst), llm = LlmIntent(null, 0.0, emptyMap())))
    }

    @Test fun `negated commands are recognised`() {
        assertTrue(Utterances.isNegated("Don't order the farmhouse pizza from brik oven"))
        assertTrue(Utterances.isNegated("please do not search on youtube"))
        assertTrue(Utterances.isNegated("zomato se pizza order mat karo"))
        assertTrue(!Utterances.isNegated("order a farmhouse pizza from brik oven"))
        assertTrue(!Utterances.isNegated("don't forget to order milk"))
    }

    @Test fun `each half of a two-task sentence matches on its own`() {
        val flows = listOf(pizza, youtube)
        assertIs<Decision.Proceed>(decide("search for lofi music on youtube", flows))
        assertIs<Decision.Proceed>(decide("order a farmhouse pizza from brik oven", flows))
        // The whole sentence is not a clear match for either flow.
        val whole = decide("search for lofi music on youtube and order a farmhouse pizza from brik oven", flows)
        assertTrue(!(whole is Decision.Proceed && whole.candidate.source in setOf("exact", "template")), whole.toString())
    }

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

    @Test fun `casual wording keeps clean values`() {
        val p = Utterances.parse("i am craving a cheesy margherita from that brick oven place")
        assertEquals("brick oven", p.source)
        assertEquals("cheesy margherita", p.item)
        // A real three-word name still fits, and "from the X" still works.
        assertEquals("belgian waffle co", Utterances.parse("order waffles from the belgian waffle co").source)
        assertEquals("dominos", Utterances.parse("get me a margherita from dominos").source)
    }

    @Test fun `look up is a search verb, not part of the value`() {
        val yt = "com.google.android.youtube"
        val flow = Flow("yt", "search for {item} on youtube", yt, "YouTube", "search for {item} on youtube",
            listOf("search for lofi music on youtube"), listOf(SlotDef("item", SlotType.TEXT, "lofi music")), emptyList())
        for (said in listOf("can you look up coldplay on youtube", "look for coldplay on youtube")) {
            val top = IntentMatcher().match(said, listOf(flow)).first()
            assertEquals("coldplay", top.slots["item"], said)
        }
    }
}
