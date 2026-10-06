package com.echoflow.core.nlu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtterancesTest {
    private fun rank(utterance: String, name: String) = Utterances.appMentionRank(utterance, name)

    @Test fun `the app after on beats a restaurant that also has an app`() {
        val said = "Order a Margherita pizza from Domino's on Zomato."
        val zomato = rank(said, "Zomato")!!
        val dominos = rank(said, "Domino's")!!
        assertTrue(zomato > dominos)
        // The ranking outweighs name length ("dominos" is longer than "zomato").
        assertTrue(zomato * 100 + "zomato".length > dominos * 100 + "dominos".length)
    }

    @Test fun `in using and via also name the app`() {
        assertEquals(2, rank("search for chess in play store", "play store"))
        assertEquals(2, rank("order biryani using swiggy", "Swiggy"))
        assertEquals(2, rank("buy a pen via amazon", "Amazon"))
    }

    @Test fun `a lone mention still counts and an absent name does not`() {
        assertEquals(1, rank("open youtube and search for cats", "YouTube"))
        assertEquals(0, rank("get me a margherita from dominos", "Domino's"))
        assertNull(rank("order pizza on zomato", "Swiggy"))
    }

    @Test fun `a heard to after an ordering verb is the number two`() {
        val p = Utterances.parse("order to farmhouse pizza from dominos on zomato")
        assertEquals(2, p.quantity)
        assertNull(p.address)
        assertEquals("farmhouse pizza", p.item)
    }

    @Test fun `a real destination keeps its to`() {
        assertEquals("work", Utterances.parse("order a margherita from dominos deliver to work").address)
        assertNull(Utterances.parse("add to cart").quantity)
        assertNull(Utterances.parse("get to zomato").quantity)
    }
}
