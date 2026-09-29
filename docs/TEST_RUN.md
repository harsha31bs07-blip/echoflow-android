# Device test run: the official Theme 3 test cases

**Device:** Samsung Galaxy S24 FE (SM-S721B), Android 15 / One UI. **Date:** 29 Sept 2026.
**Build:** debug APK from `claude/elegant-einstein-c96kwl` (same code as the release build, plus the debug command channel).
**How:** commands and answers went through the debug channel (`scripts/run.ps1`), which uses the same code path as speech. No order was placed and no payment was made in any run.

**Stand-ins (the only differences from the judges' scripts):**
- **Domino's → Brik Oven.** Domino's shows *"Outside delivery range"* for the test phone's address, so the same flow was taught with Brik Oven (menu items "Margherita Pizza" and "Briks Farmhouse Pizza"). The flow has a `{restaurant}` slot, so nothing is specific to either restaurant.
- **T6** needs a saved **Work** address; the test account has only "Home" addresses, so T6 was checked in unit tests and on Swiggy (below), not yet on Zomato.
- **T10 (Hindi):** Zomato ignores Android's per-app language (set to hi-IN, the UI stayed English), so the language change couldn't be reproduced. The equivalent "genuinely stuck" case (a dish the restaurant doesn't have) was run instead; the logged-out case is covered by a unit test (a login screen stops the run, says *"Your turn: please log in"*, and is reported as not succeeded).

## Flows taught (live, by command + taps)

| Test | Taught with | Saved flow |
|---|---|---|
| T1 | "Order a Margherita pizza from Brik Oven on Zomato." → *"I don't know how to … Want to teach me?"* → yes → taps: close location sheet, search, type the restaurant, open it, menu search, type "margherita", ADD, Add item (size sheet), Continue | `order a {item} pizza from {restaurant} on zomato`: open Zomato → type `{restaurant}` → menu search → type `{item}` → ADD → Continue. Confirmation: *"Learned: order a margherita pizza from brik oven on zomato. I saved 6 steps. You can change the item, restaurant."* |
| T8 | "Search for wireless earbuds on Amazon and add the first result to cart." → yes → taps: search, type, first result, Add to Cart, Done | `search for {item} on amazon and add the first result to cart`: open Amazon → type `{item}` → first result → Add to cart. (Amazon doesn't report its result and Add to Cart taps; the command supplies those two steps.) |

## Results

| Test | Command (as the judges say it) | What happened on the phone | Result |
|---|---|---|---|
| **T1** | (teaching above) | Saved, confirmed aloud, visible in the Flow Inspector | ✅ |
| **T2** | "Order a Margherita pizza from Brik Oven on Zomato." | Exact match (1.00), ran unattended: search → restaurant → menu search → ADD → size sheet (kept the preselected size) → cart. *"Your turn. Everything is ready for payment, total ₹285. I won't pay. Please check the order and pay yourself."* Cart: Brik Oven, Margherita Pizza ×1 | ✅ |
| **T3** | "Get me a margherita from brik oven" | Matched without an LLM, ran directly to the cart (₹285) | ✅ |
| **T3** | "I want to order margherita pizza on zomato" | Matched; the restaurant wasn't said, so it asked for it mid-run (B3), then continued to the cart (₹285) | ✅ |
| **T4** | "Order a Farmhouse pizza from Brik Oven on Zomato." | Cart: **Briks Farmhouse Pizza** ×1 (not Margherita), ₹343 | ✅ |
| **T5** | "Order two Margherita pizzas from Brik Oven." | *"set quantity to 2"* at the cart; cart showed **2**, ₹553 | ✅ |
| **T6** | "…deliver to work" | Not run on Zomato (no Work address on the account). Parsing and matching are unit-tested; address switching was verified on Swiggy (Home ↔ Hostel) | ⚠️ needs a Work address |
| **T7** | T2 with a Margherita already in the cart | *"Margherita was already in your cart, so I didn't add another one. Your turn. Everything is ready for payment, total ₹285."* Zomato's location pop-up on every launch and an empty sheet shell were also closed automatically | ✅ (autonomous) |
| **T8** | (teaching above) | Second flow, second app, distinct from T1 | ✅ |
| **T9** | "Search for a phone case on Amazon and add the first result to cart." | Searched, opened the **first product under "Results"** (skipping the AI summary and video ad), scrolled the product page, tapped Add to Cart: Amazon showed *"Added to cart"* (a phone case) | ✅ |
| **T10** | T2 with a dish the restaurant doesn't have ("zzqx unicorn") | Asked *"I searched for "zzqx unicorn" at brik oven but couldn't find it. What should I get instead?"* 27.7 s after the command (the wait before asking has since been cut from 6 s to 4 s); no answer → *"…so I stopped at step 5 without adding anything."* No wrong taps | ✅ (proxy, see above) |
| **T11** | every Zomato run above | Never tapped Place Order; every hand-off starts *"Your turn."* | ✅ |
| **T12** | "Book a cab to the airport." | Score 0.30: *"I don't know how to … yet. Want to teach me?"* No flow ran | ✅ |
| **T13** | "Order pizza." | Score 0.70 (the item is missing): asked *"Do you want me to order a pizza from a restaurant on zomato? I'll ask you which one."* | ✅ |
| **T14** | "Did the last run succeed?" (after a failed run) | *"No, the last run didn't succeed. Order a margherita pizza from brik oven on zomato stopped at step 5 of 6 (Tap "ADD"). …"* After a hand-off it answers *"Yes, the last run succeeded…"* | ✅ |
| **B1** | incoming call during teaching | Unit-tested (taps in the phone app are dropped); not staged on the phone | ⚠️ |
| **B3** | "I want to order margherita pizza on zomato" | Asked which restaurant mid-run, then continued | ✅ |

## Found and fixed on the phone during this run
- Zomato's cart is a sheet over the menu: the menu's "Continue" bar sits under **Place Order** at the same spot. The gesture fallback now refuses any spot shared with a pay/order/delete button (`GestureSafety`).
- Zomato's cart ("PAY USING Google Pay UPI" + Place Order) is now CHECKOUT, not PAYMENT, so quantity can be set there; real payment pages still trip PAYMENT.
- Unreported taps (Zomato suggestions, Amazon results and Add to Cart), clicks that are accepted but ignored, "Something went wrong / Try again" pages, stepper buttons labelled only with icon glyphs, web pages that ignore scroll commands, and an Amazon offer row mistaken for an address sheet.

---

# Earlier run (28–29 Sept): our own versions of T1–T14 on Swiggy

**Device:** Samsung Galaxy S24 FE (SM-S721B), Android 15 / One UI.
**Apps:** Swiggy, Amazon, Zomato (the versions installed on 28–29 Sept 2026).
**Build:** debug APK from `claude/elegant-einstein-c96kwl`.

**How these runs were made:** commands were sent through the debug-only command channel (`scripts/run.ps1`), which uses the same code path as speech. Answers to EchoFlow's questions went through the same channel. Microphone speech recognition itself is Android's standard `SpeechRecognizer`, and still needs checking by hand (see the checklist at the end).

**Safety note:** no order was ever placed. Every run ended on Swiggy's cart with the pay button untouched.

## Flows taught for the test

| Flow | App | Taught with | Saved steps |
|---|---|---|---|
| `order {item}` | Swiggy | "teach order garlic bread": search, type, Dishes tab, ADD on Garlic Breadsticks, View Cart, Done | Open Swiggy → tap search → type `{item}` → Dishes tab → ADD in the row matching `{item}`. Ends at CHECKOUT |
| `search {item} on amazon` | Amazon | "teach search running shoes on amazon": search, type, Done | Open Amazon → type `{item}` (the search-box tap isn't reported by Amazon; replay opens search itself) |
| `order {item} on zomato` | Zomato | "teach order garlic bread on zomato": search, type, Done | Open Zomato → type `{item}` |

## Results

| Test | Command | What happened on the device | Result |
|---|---|---|---|
| **T1** | "teach order garlic bread" | Teaching recorded search → type → Dishes → ADD. The cart was recognised as CHECKOUT, and Done saved the flow with `{item}` bound (search text and the ADD row). Inspectable in *EchoFlow → Learned flows → Flow Inspector* | ✅ |
| **T2** | "order garlic bread" | Exact match (1.00), fresh launch, search, Enter, ADD in the Garlic Breadsticks row, View Cart. Then: *"Everything is ready at checkout, total ₹153. I won't pay. Please check the order and pay yourself."* | ✅ |
| **T3** | "can you get me some garlic bread" | Similarity match 0.75. With no LLM key it confirms first (*"Do you want me to order garlic bread on Swiggy?"*), then replays to checkout | ✅ (confirm path; direct with a Gemini key) |
| **T4** | "order paneer tikka to hostel" | Searched paneer tikka, picked the **Paneer tikka** row's ADD (not garlic bread), and handed off at checkout (₹284) | ✅ |
| **T5** | "order 2 garlic bread" | At the cart, found the item's stepper and tapped + until it read 2 (event *"set quantity to 2"*), then handed off at ₹257 | ✅ |
| **T6** | "order garlic bread to home" | Opened the app's delivery-address bar, picked **Home** from the saved addresses (event *"delivery address: Home"*), and the cart showed the Home address. "…to hostel" switched it back | ✅ |
| **T7** | "order garlic bread" with a WeFit item already in the cart | Swiggy's *"Replace cart item?"* dialog. EchoFlow asked: *"Your cart already has other items. The app says: Your cart contains dishes from WeFit… Should I replace them?"*. Yes → replaced, then hand-off. Also closed a promo popup via its *Close* button on another run | ✅ |
| **T8** | "search running shoes on amazon" | Second flow in a second app replays: opens search, types, submits, shows results | ✅ |
| **T9** | "search wireless earbuds on amazon" | Same flow, new term: results for *wireless earbuds* | ✅ |
| **T10** | "order zzqx unicorn waffles" | 24 s after the command: *"I can't find 'zzqx unicorn waffles'. I can see: Choco Brownie Overload Waffle Sandwich. Which one should I pick?"*. No answer → *"I couldn't find … and didn't get a choice I could use."* No item tapped | ✅ |
| **T11** | Every Swiggy run | Stopped at CHECKOUT with the fixed hand-off line. The pay button is never tapped. Payment Options, OTP, card and login screens are PAYMENT/OTP/LOGIN in fixtures and on device | ✅ |
| **T12** | "book a cab to the airport" | Score 0.30 → *"I don't know how to 'book a cab to the airport' yet. Want to teach me?"* | ✅ |
| **T13** | "get me garlic bread" (with Swiggy and Zomato flows) | Tie at 0.75 → *"I know more than one way to do that. first: order garlic bread on Swiggy; second: order garlic bread on zomato. Which one?"* → "zomato" → ran the Zomato flow | ✅ |
| **T14** | "what happened last time" | *"Your last request, order garlic bread, succeeded. I got it ready and handed over to you. Everything is ready at checkout, total ₹153…"* | ✅ |
| **B3** | Missing slot / item not found mid-flow | Asks with the choices it can see (unit-tested; the T10 run above shows the device wording) | ✅ |

## Real-world problems found and fixed during this run

Each of these was found on the device, fixed, and re-run:

- **Blank loading frames** read as "unreadable" and ended teaching and replay. The watcher no longer hands off on blank frames; the gate refuses to act on them; the engine waits up to 6 s.
- **Swiggy resumed on an old screen** at launch. Replays now start the app fresh (launcher intent + new task).
- **Search results needed the keyboard's Enter**, which isn't reported as a tap. The engine presses Enter on the last typed field when the next step doesn't appear, or when the flow ends on typing.
- **Taps not reported by the app** (Swiggy's checkout bar, Amazon's search box and web results). Recovered at replay: open cart / open search / open the first result matching `{item}`.
- **Views that refuse `ACTION_CLICK`** (Swiggy's address header). A tap gesture on the same gate-approved element is used instead.
- **Timers and carousels re-capture every second**, making taps "stale". Actions are carried over to the identical element in the newest capture.
- **Items with an options sheet** (Paneer tikka's "Choose customization"). Asks *"… has extra options. Add it with the default choices for ₹511?"*
- **Amazon's "Pay" navigation tab** read as a pay button. Navigation tabs are excluded from the CHECKOUT signal; tapping it is still blocked.
- **Skipping ahead past a needed step** (Amazon's cart tab is on every page). Skip-ahead now runs only after waiting and scrolling, and never skips typing or value steps.

## Manual checklist before recording the demo

- [ ] Tap 🎤 in the bubble and **say** a command; the transcript should appear in the bubble.
- [ ] TTS speaks the questions and the hand-off line.
- [ ] On a second phone: install the APK, allow restricted settings, enable the service, and pass the Play Protect prompt if shown.
