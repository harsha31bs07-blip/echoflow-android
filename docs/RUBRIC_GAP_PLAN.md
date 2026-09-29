# Rubric gap plan: official Theme 3 test cases vs EchoFlow

Source: *Theme 3 – Evaluation Criteria.pdf*, checked 29 Sept 2026. The deadline is 30 Sept, so everything below is ordered by points at risk.

## Why this plan exists
We verified T1–T14 on the device ([TEST_RUN.md](TEST_RUN.md)) using **our own versions** of the tests: Swiggy, garlic bread, and a search-only Amazon flow. The official tests use different scenarios, and judges will run them exactly as written:

| | Official scenario | What we verified |
|---|---|---|
| Food app | **Zomato**, restaurant **Domino's** | Swiggy, no restaurant |
| Command | "Order a Margherita pizza **from Domino's** on Zomato" | "order garlic bread" |
| Shop flow | Amazon: search **and add the first result to cart** | Amazon: search only |
| Stop point | "stops at **payment**", says **"your turn"** | stops at checkout, "I won't pay" |

## Status per test (official wording)

| ID | Pts | Status | Gap |
|---|---|---|---|
| T1 Teach | 5 | ⚠️ Partial | Only one text slot (`item`) exists. In "Order a Margherita pizza from Domino's on Zomato", the first thing typed is usually "Domino's", which matches the item phrase, so **the restaurant gets bound as `{item}`** and the template breaks. The confirmation should read "Learned: order Margherita pizza from Domino's". |
| T2 Exact replay | 5 | ⚠️ Untested | Never run on Zomato. Domino's pizzas open a **required size/crust sheet**, and replay of taught option taps is untested there. |
| T3 Paraphrase | 6 | ⚠️ At risk | "Get me a margherita from dominos": no template match, so the item parses as "margherita dominos". "…margherita pizza on zomato" has **no restaurant**. |
| T4 Slot: item | 4 | ⚠️ Blocked by T1 | Needs a separate restaurant slot. |
| T5 Slot: qty | 4 | ⚠️ At risk | "two" parses. "pizzas" vs "pizza" isn't matched (no plural stemming). The Zomato stepper is untested. |
| T6 Slot: address | 4 | ⚠️ Untested | "deliver to work" parses. Address switching was built and tested on Swiggy only. We need a test account with Home and Work saved on Zomato. |
| T7 Screen change | 6 | ⚠️ Untested on Zomato | The popup whitelist and replace-cart question were only proven on Swiggy. |
| T8 Teach e-com | 4 | ❌ Gap | The saved Amazon flow stops at search. It must include **open the first result → Add to Cart**. |
| T9 Cross-app slot | 4 | ❌ Gap | "First result" must be **positional**. A text-matched earbuds result will never match phone-case results. |
| T10 Stuck | 5 | ⚠️ Untested | Hindi Zomato UI and logged-out Zomato were not tried. We need to prove the ask arrives within 30 s. |
| T11 Credential | 5 (−10) | ✅ Logic done | Add the words **"Your turn"** to the hand-off. Decide the stop point (see P0-6). |
| T12 Unknown | 3 | ✅ Done | — |
| T13 "Order pizza." | 2 | ✅ Likely | Re-check once the restaurant slot exists. It must confirm or ask, never proceed silently. |
| T14 Report | 3 | ✅ Done | "last run" is in the report phrases. Make the answer start with "Yes, it succeeded" or "No, it stopped at step N (…)". |
| B1 Stray taps | +3 | ⚠️ Partial | Show an **incoming call** being discarded during teaching. |
| B2 Amazon→Myntra | +4 | ❌ Not attempted (now ✅, see Status) | — |
| B3 Mid-flow slot | +3 | ⚠️ Partial | The rubric's own example is the missing **restaurant**, which comes with P0-1. |

**Bottom line:** of 60 base points, only about 13 (T11, T12, T13, T14) are safe as-is. Most of the rest depends on one core change (a restaurant slot) plus testing on Zomato and Amazon on the device.

## Progress (29 Sept)
Core fixes are done and unit-tested: `core/src/test/.../nlu/RubricPhrasesTest.kt` runs every official phrase for T1–T6, T8/T9, T12–T14, B1 and B3 against a synthetic Zomato/Domino's demonstration, and there are 115 core tests in total.
- **Done:** P0-1 (restaurant slot, qualifiers such as "pizza", asking "Which restaurant? Last time it was dominos"), P0-2 (relaxed template matching, plurals, articles, "Domino's" = "dominos"), P0-5 (wording).
- **Done in core:** P0-4, positional "first result" (`Step.Tap.pick = "first"`). "Add to Cart" is already SAFE; "Buy now" stays COMMIT.
- **Also done:** "Order pizza." asks which flow when two templates fit (T13). "Yes" after "Want to teach me?" starts teaching that command. "no, don't" no longer counts as yes.
- **Still needs the phone:** P0-3, P0-4 on device, P0-6, and all of P1.

## Status (29 Sept, evening), after the phone run ([TEST_RUN.md](TEST_RUN.md))
- ✅ **Passed on the phone:** T1, T2, T3 (both phrases), T4, T5, T7 (handled without asking), T8, T9, T11, T12, T13, T14, B3. T10 was run as "stuck" (Zomato ignores a Hindi app language) and asked within 30 s.
- ✅ **T6:** verified with the account's Work and Home addresses.
- ✅ **B1:** a real incoming call, declined while teaching, was dropped from the flow (after a recorder fix).
- ✅ **B2:** a flow taught on Amazon is offered on Myntra or Flipkart (confirmed first) and run with the same steps there. Verified on the phone with Myntra: the first sunglasses result went into the bag.
- The demo script follows the required a→e order ([DEMO_SCRIPT.md](DEMO_SCRIPT.md)).

## P0: must do (tonight / tomorrow morning)

**P0-1 · Multi-slot flows: `{restaurant}` (T1–T6, T13, B3). About 3 h, core + tests**
- `Utterances.parse`: pull out `from <X>` (the words before `on <app>` or the end) as `restaurant`. Normalise apostrophes: "Domino's" = "dominos" = "domino s".
- `FlowCompiler.extractSlots`: bind **each typed string** to the utterance phrase it matches. The restaurant phrase gives `{restaurant}` and the rest gives `{item}`. Template the restaurant result tap and the menu-item tap with their own slot.
- Template: `order {item} from {restaurant} on zomato`. `templateRegex` already supports any slot name.
- When a slot is missing (T3 phrase 2, B3), ask: *"Which restaurant? Last time it was Domino's."* Accept "same" or "yes" as the taught value.
- Tests: add a `FlowCompilerTest` and an `IntentMatcherTest` case for every official phrase in T1–T6 and T13.

**P0-2 · Item matching tolerance (T3, T5). About 1 h**
- Light stemming in `valueMatch` and in parsing: pizzas→pizza, "margherita" ⊂ "margherita pizza".
- Similarity path: when the command has "from X" matching a flow's restaurant and the item is present, score 0.7. That still confirms without a Gemini key.

**P0-3 · Zomato end-to-end on the device (T1, T2, T4–T7, T11). About 3 h**
- Teach the exact T1 utterance and taps once.
- Replay T2–T6 and record the results in TEST_RUN.md.
- Fix whatever breaks. Expect problems in: the restaurant search result, the in-menu search, the **customisation sheet** (replay the taught size choice), Zomato's cart stepper, and the Zomato address picker (Home ↔ Work).
- T7: open Domino's with an item already in the cart from another restaurant. Also trigger a promo popup.

**P0-4 · Amazon add-to-cart with positional first result (T8, T9). About 2 h**
- Re-teach with the exact T8 utterance.
- When the utterance says "first result", or the tapped result doesn't contain the `{item}` value, store the tap as **the first clickable result in the list** (position weighted, text ignored). Skip "Sponsored"-only headers.
- Make sure the product page's **Add to Cart** is classed SAFE, not COMMIT, and scroll to it if needed.
- Replay T9 with "phone case", and check that the parsed item isn't "a phone case" or "… first result cart".

**P0-5 · Wording (T1, T11, T14). About 30 min**
- Teach confirmation: "Learned: order Margherita pizza from Domino's. N steps."
- Hand-off: "Your turn. The order is ready at payment, total ₹…. I won't pay."
- Report: "Yes, the last run succeeded…" or "No, the last run stopped at step N, *search Domino's*, because …".

**P0-6 · Stop point decision (T2, T11)**
Keep stopping at the checkout screen that has the pay/place-order button, and never tap *Place order*. Zomato can place a cash-on-delivery order with one tap, so a −10 is not worth risking. If the taught flow includes a "Proceed to pay" or "Select payment method" tap that only **navigates**, allow it and stop on the PAYMENT screen. SafetyGuard already allows only navigation taps and blocks COMMIT taps. Test both on Zomato.

## P1: should do (tomorrow midday)
- **T10:** switch Zomato to Hindi and repeat T2. It must ask or report within 30 s with no wrong taps. Also log out of Zomato: the LOGIN screen must stop the run. Time both.
- **B1:** have a teammate call during teaching. Make sure dialer/in-call taps (another package) are dropped, and add a unit test.
- **Demo video:** rewrite DEMO_SCRIPT.md in the required **unedited order**: (a) teach by voice and taps, (b) exact replay, (c) paraphrase, (d) changed slot, (e) asking when stuck. Use the Zomato/Domino's flow so the judges see their own test.
- **Docs:** update TEST_RUN.md, the README rubric table, the target apps (Zomato becomes primary for food), and LIMITATIONS.md.

## P2: only if P0 and P1 are green
- **B2 (+4) Amazon→Myntra:** role-based replay. Map the steps to roles (search box → submit → first result → add to cart/bag) and find them in the second app by role hints (editable plus "search"; first result in the list; a button labelled "add to bag/cart"). Allow it only with the same verb shape and a mentioned app that isn't the taught one. About 4 h.

## Order of work
P0-1 → P0-2 → P0-5 (core, JVM tests; no phone needed) → P0-3 and P0-4 on the phone → P1 → rebuild the release APK → update the README and deck → the team records the video → merge into `main` and tag (with the team's OK).
