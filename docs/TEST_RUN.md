# Device test run: rubric T1–T14

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
