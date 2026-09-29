# Traceability: official Theme 3 tests → code → evidence

For each official test (the *Theme 3 – Evaluation Criteria* wording), this lists:
- the classes that make it pass;
- the unit tests that check it;
- what happened on the phone ([TEST_RUN.md](TEST_RUN.md#results)).

Where a row says ⚠️, the reason is in [LIMITATIONS.md](LIMITATIONS.md). File paths are under `core/src/main/kotlin/com/echoflow/core/` and `app/src/main/kotlin/com/echoflow/app/`.

## Test cases

| Test | Judge action | How EchoFlow does it | Unit tests | On the phone |
|---|---|---|---|---|
| **T1** Teach – food (5) | "Order a Margherita pizza from Domino's on Zomato." then taps to payment | The command is unknown, so `DecisionLayer` offers to learn it and a yes starts `TeachingRecorder`. `FlowCompiler` drops noise, binds `{item}` (qualifier "pizza") and `{restaurant}` from what was typed, builds steps and the template. `Orchestrator` says *"Learned: …"*. The flow is saved as JSON and shown in `FlowInspectorActivity` | `RubricPhrasesTest` "T1 teaching binds the restaurant and the item separately"; `FlowCompilerTest` | ✅ |
| **T2** Exact replay (5) | Repeat T1 verbatim | `IntentMatcher` exact match (1.00) → `Decision.Proceed` → `ReplayEngine` → hands off at the cart (`SafetyGuard.handOffAtCheckout`) | "T2 exact utterance runs with the taught values"; `ReplayEngineTest` "replays with a new slot value and hands off at checkout" | ✅ |
| **T3** Paraphrase (6) | "Get me a margherita from dominos" / "I want to order margherita pizza on zomato" | Relaxed template match (0.88). A missing restaurant is asked for mid-run (B3). "Domino's" = "dominos" via `TextNormalizer`. Optional Gemini for looser wording | "T3 both paraphrases map to the flow" | ✅ both |
| **T4** Slot: item (4) | "Order a Farmhouse pizza from Domino's on Zomato." | `{item}` typed in the menu search; the ADD is resolved in the row whose text matches the new value (`ElementResolver` row context). A new options sheet keeps its preselected choices | "T4 different item"; `ReplayEngineTest` "picks the ADD button in the row of the requested item" | ✅ |
| **T5** Slot: quantity (4) | "Order two Margherita pizzas from Domino's." | "two" → `qty` = 2. At the cart, `ReplayEngine.adjustQuantity` taps the item's + until the count shown is 2, re-reading it after each tap | "T5 quantity in words with a plural" | ✅ |
| **T6** Slot: address (4) | "…deliver to work." | "to work" → `{address}`. `ReplayEngine.ensureAddress` opens the app's delivery bar and picks the saved address named "Work" | "T6 deliver to work"; `ReplayEngineTest` "an Amazon product page with an offer row is not an address sheet" | ✅ Work, and back to Home |
| **T7** Screen change (6) | Promo pop-up, or item already in cart, then T2 | Pop-ups closed via a whitelist (Close, Not now, ✕…); "Replace cart?" dialogs are always asked; "Something went wrong" → Try again once; a dish already in the cart isn't added again | `ReplayEngineTest` "closes a promo popup", "dish already in the cart is not added again" | ✅ handled by itself |
| **T8** Teach – e-commerce (4) | "Search for wireless earbuds on Amazon and add the first result to cart." | Same teaching path. Amazon doesn't report its result and Add to Cart taps, so the compiler adds `Tap(pick = first)` and `Tap(pick = add_to_cart)` from the command | "T8 when the app reports only the typing…" | ✅ |
| **T9** New search term (4) | "Search for a phone case on Amazon…" | `{item}` = "phone case". The first product card under "Results" is taken by position (skipping AI summaries and video ads), then Add to Cart | "T8 and T9 amazon add-to-cart flow generalises across search terms"; `ReplayEngineTest` "first result is picked by position…", "…skips an AI summary and a video ad…" | ✅ |
| **T10** Genuinely stuck (5) | Hindi UI or logged out, then T2 | Searched value not found → asks *"…couldn't find it. What should I get instead?"* after about 4 s. Login screen → *"Your turn…"*, counted as not succeeded. Other script → *"The app seems to be in a different language…"*. No step takes more than 12 s; it never taps below 0.7 confidence | `ReplayEngineTest` "a search that finds nothing asks…", "logged out - a login screen stops the run as not succeeded", "stuck on an unknown screen stops with a specific reason" | ✅ "not found" case in 27.7 s. ⚠️ Hindi and logged out: unit tests only (L3) |
| **T11** Credential boundary (5, fail −10) | Let T2 reach payment | `SafetyGuard` gate (before every action) and watcher (every screen); `ScreenSafetyClassifier`; `ActionRiskClassifier` (Pay / Place order / Buy now are never tapped); `GestureSafety`; unreadable = unsafe. Message starts *"Your turn."* | `SafetyGuardTest`, `ScreenSafetyClassifierTest` (real cart and payment screen dumps), `ActionRiskClassifierTest`, `GestureSafetyTest`, `ActionGatewayTest` | ✅ never tapped |
| **T12** Unknown intent (3) | "Book a cab to the airport." | Top score below 0.45 → *"I don't know how to … yet. Want to teach me?"*; nothing runs | "T12 unknown intent offers to teach" | ✅ |
| **T13** Ambiguity (2) | "Order pizza." | Missing item or restaurant, or two template matches → confirm or ask which | "T13 order pizza is never run silently" | ✅ |
| **T14** Reporting (3) | "Did the last run succeed?" | `RunRecord.spokenSummary()` from a template: *"Yes, …"* or *"No, … stopped at step N of M (…)"* | "T14 did the last run succeed" | ✅ |

## Bonuses

| Bonus | How EchoFlow does it | Unit tests | On the phone |
|---|---|---|---|
| **B1** Unneeded touches (+3) | `FlowCompiler` drops taps in other apps (a call's accept/decline buttons), double taps and detours, and reports how many it ignored | "B1 taps in the phone app during teaching are dropped" | ✅ real call declined while teaching; its tap dropped |
| **B2** Similar apps (+4) | `IntentMatcher.crossApp`: the command names a different app of the same kind → confirmed, then `Flow.retargeted` runs the same steps there; general recoveries find the search bar, first product and Add to bag | "B2 an Amazon flow is offered on Myntra and Flipkart…"; `ReplayEngineTest` "an Amazon flow on Myntra…" | ✅ Myntra |
| **B3** Missing value mid-flow (+3) | A step needing a value the command didn't give asks when reached: *"Which restaurant should I order from? Last time it was brik oven."*, then continues | "B3 asks for the restaurant and accepts same as last time"; `ReplayEngineTest` "missing item asks mid-flow" | ✅ |

## Core requirements

| Requirement | Where it's enforced |
|---|---|
| Accessibility Service APIs only; no SDKs, deep links or web fallbacks | `app/accessibility/AndroidActionExecutor` is the only code that acts on the phone; `LaunchApp` uses the launcher intent (L15) |
| No hard-coded flows | Flows exist only as JSON produced by `FlowCompiler` from a live demonstration; no app or restaurant names in the replay logic |
| No credential capture; hand over on payment, OTP, password and login | `SafetyGuard` + `ActionGateway`; password and OTP text are never recorded; `Redactor` masks screen dumps |
| Parametrised flows | `FlowCompiler.extractSlots`, `ElementResolver` slot-aware matching |
| Detect changes; carry on or ask | `ReplayEngine` recoveries and questions (T7, T10) |
