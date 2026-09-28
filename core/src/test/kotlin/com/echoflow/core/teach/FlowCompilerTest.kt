package com.echoflow.core.teach

import com.echoflow.core.flow.Step
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
}
