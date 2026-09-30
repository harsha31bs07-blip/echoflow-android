# Device test run: the official Theme 3 test cases

**Device:** Samsung Galaxy S24 FE (SM-S721B), Android 15 / One UI. **Date:** 29 Sept 2026.
**Build:** debug APK from `claude/elegant-einstein-c96kwl` (same code as the release build, plus the debug command channel).
**How:** commands and answers went through the debug channel (`scripts/run.ps1`), which uses the same code path as speech. No order was placed and no payment was made in any run.

**Stand-ins (the only differences from the judges' scripts):**
- **Domino's → Brik Oven.** Domino's shows *"Outside delivery range"* for the test phone's address, so the same flow was taught with Brik Oven (menu items "Margherita Pizza" and "Briks Farmhouse Pizza"). The flow has a `{restaurant}` slot, so nothing is specific to either restaurant.
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
| **T6** | "Order a Margherita from Brik Oven, deliver to work." / "…deliver to home." | Opened Zomato's location picker from the address bar, picked **Work** from *Saved addresses* (event *"delivery address: Work"*). The test account's Work address is in another city where Brik Oven doesn't deliver, so EchoFlow then asked what to get instead and added nothing. "…deliver to home" switched back to **Home** and finished at the cart ("… to Home") | ✅ (switching verified both ways) |
| **T7** | T2 with a Margherita already in the cart | *"Margherita was already in your cart, so I didn't add another one. Your turn. Everything is ready for payment, total ₹285."* Zomato's location pop-up on every launch and an empty sheet shell were also closed automatically | ✅ (autonomous) |
| **T8** | (teaching above) | Second flow, second app, distinct from T1 | ✅ |
| **T9** | "Search for a phone case on Amazon and add the first result to cart." | Searched, opened the **first product under "Results"** (skipping the AI summary and video ad), scrolled the product page, tapped Add to Cart: Amazon showed *"Added to cart"* (a phone case) | ✅ |
| **T10** | T2 with a dish the restaurant doesn't have ("zzqx unicorn") | Asked *"I searched for "zzqx unicorn" at brik oven but couldn't find it. What should I get instead?"* 27.7 s after the command (the wait before asking has since been cut from 6 s to 4 s); no answer → *"…so I stopped at step 5 without adding anything."* No wrong taps | ✅ (proxy, see above) |
| **T11** | every Zomato run above | Never tapped Place Order; every hand-off starts *"Your turn."* | ✅ |
| **T12** | "Book a cab to the airport." | Score 0.30: *"I don't know how to … yet. Want to teach me?"* No flow ran | ✅ |
| **T13** | "Order pizza." | Score 0.70 (the item is missing): asked *"Do you want me to order a pizza from a restaurant on zomato? I'll ask you which one."* | ✅ |
| **T14** | "Did the last run succeed?" (after a failed run) | *"No, the last run didn't succeed. Order a margherita pizza from brik oven on zomato stopped at step 5 of 6 (Tap "ADD"). …"* After a hand-off it answers *"Yes, the last run succeeded…"* | ✅ |
| **B1** | incoming call during teaching, declined | First try: the Decline tap on the call pop-up was matched against YouTube's screen underneath and kept as a blank step. Fixed (the tap keeps the app its click came from). Second call: *"Learned: search for chess videos on youtube. I saved 3 steps. … I ignored 1 accidental or unneeded tap."* (dropped: tap in com.samsung.android.incallui) | ✅ |
| **B2** | "Search for sunglasses on Myntra and add the first result to cart." (Myntra was never taught; the Amazon flow was) | Matched the Amazon flow as a cross-app candidate (0.78) and asked *"I learned this on Amazon. Do you want me to try the same steps on Myntra: …? Say yes or no."* → yes → opened Myntra's search bar, typed, pressed Enter, opened the first product (**Carlton London Women Oversized Sunglasses**), tapped the page's **Add to Bag**. Myntra's bag then held that exact product. The Amazon flow (T9) and the Zomato flow (T4) were re-run afterwards and still pass | ✅ |
| **B3** | "I want to order margherita pizza on zomato" | Asked which restaurant mid-run, then continued | ✅ |

## A flow in an app EchoFlow had never seen (29 Sept, night)

Judges will teach new flows live, so we taught one the same way in an app EchoFlow had never been used on, with no code changes for it:

| Step | What happened |
|---|---|
| Teach | "teach search for lofi music on youtube" → opened YouTube, tapped Search, typed, pressed Enter, tapped ✓ Done → *"Learned: search for lofi music on youtube. I saved 3 steps. You can change the item."* |
| New value | "search for arijit singh songs on youtube" → template match (0.95) → YouTube searched **arijit singh songs** |
| Paraphrase | "can you look up coldplay on youtube" → relaxed match (0.88) → searched **coldplay** (the first try searched "up coldplay"; "look up" / "look for" / "check out" are now treated as the verb) |

(Spotify was tried first, but its own search showed "Something went wrong" for every query, so it wasn't a fair test.)

## Spoken, not typed (29 Sept, night)

Tapping the edge handle and saying *"Order a Farmhouse pizza from Brik Oven on Zomato"* was heard as "order a farmhouse pizza from **Brick** oven Zomato". The first try opened a different restaurant, *Brick Oven Pizzeria*, where Zomato showed a "Serving from exceptional distance / Okay, got it!" sheet. EchoFlow didn't recognise that sheet and stopped without tapping anything. Two fixes, both then checked on the phone:
- **Spelling:** a spoken value a letter or two away from the taught one uses the taught spelling ("brick oven" → brik oven, "margarita" → margherita). The same command then reached Brik Oven's cart: Farmhouse, ₹343.
- **Sheets:** bottom-sheet dialogs count as pop-ups, and an unlabelled ✕ (id `crossButton`) closes them. Asking for "Brick Oven Pizzeria" on purpose: the sheet was closed, the dish wasn't on that menu, EchoFlow asked what to get instead, and "nothing" stopped it without adding anything.

## AI help when stuck (30 Sept, just after midnight)

With a Gemini key pasted in the app, and EchoFlow's built-in pop-up rules switched off (a debug-build test switch, so only the AI helper could get past the pop-up):

| Command | What happened |
|---|---|
| "order a farmhouse pizza from brick oven pizzeria on zomato" | Stuck at step 3 on Zomato's "Serving from exceptional distance" sheet. EchoFlow asked Gemini, which answered *dismiss the 'Okay, got it!' popup blocking the screen*. The button passed the safety check and was tapped, and the run carried on: menu search, no Farmhouse there, asked what to get instead, "nothing", stopped with nothing added. Event: `AI helper: closed "Okay, got it!"` |
| "order a margherita pizza from brik oven on zomato" (rules back on) | Normal run to the cart (hand-off). Zomato's "Step back. Grab a snack." interstitial stayed up more than 5 s, so Gemini was asked once and said *still loading*; EchoFlow waited, then continued. (Since then EchoFlow waits 7 s and skips screens with a loading spinner.) |

## Overnight regression run (30 Sept, 01:00–01:30, branch build)

Re-run after the night's UI changes (listening panel, ✕ close, panel redesign, animations). Brik Oven was closed, so the Zomato tests weren't repeated.

| Test | Result |
|---|---|
| T9 (Amazon, phone case, first result) | ❌ at first: the product page never scrolled, because the scroll swipe started on EchoFlow's own taller panel. Fixed (the panel lets EchoFlow's gestures through), then ✅: added to cart. Cart emptied |
| B2 (Amazon flow on Myntra, sunglasses) | ✅ confirmed first, first product added to the bag. Bag emptied |
| T1-style teach (YouTube, "cricket highlights") | ✅ *"Learned: … I saved 3 steps. You can change the item."*; test flow deleted afterwards |
| T12 ("Book a cab to the airport.") | ✅ offers to learn it |
| T13 ("Order pizza.") | ✅ asks before running |
| T14 (after a YouTube run) | ✅ *"Yes, the last run succeeded. … completed all 3 steps."* |
| ✕ mid-run (Zomato, step 2) | ✅ cancelled, nothing added; recorded as "Stopped at step 2, as you asked." |

## Morning bug from real use (30 Sept, 08:22)
Spoken *"order a farmhouse pizza from Brick oven"* stopped at step 3 with *"I searched for "brik oven" but couldn't find it"*, although Brik Oven was the first search result. Further down, Zomato showed an empty "Restaurant based on your search" section saying **"Uh-oh! No results found!"**, and EchoFlow believed that text. Now a "no results" message only counts when nothing on screen matches the search, and a missing restaurant asks *"Which restaurant should I use instead?"* rather than what to get. Re-run on the phone: reached the cart (Farmhouse, ₹343), cart emptied.

## Found and fixed on the phone during this run
- Zomato's cart is a sheet over the menu: the menu's "Continue" bar sits under **Place Order** at the same spot. The gesture fallback now refuses any spot shared with a pay/order/delete button (`GestureSafety`).
- Zomato's cart ("PAY USING Google Pay UPI" + Place Order) is now CHECKOUT, not PAYMENT, so quantity can be set there; real payment pages still trip PAYMENT.
- Myntra (B2): the home screen's own "Deliver to …" bar was read as an address list, and "Use my current location" as a search result; the search bar has no "Search" label (only rotating hints); trending products show before Enter is pressed; the product page's real Add to Bag is plain text in a tappable bar, while "Similar products" cards each have their own Add to Bag; some items open a size sheet. All handled now, with a unit test that has each of these traps.
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
- **AI helper timed out and the run stopped** (Domino's, 30 Sept). The recovery prompt lists a whole screen and took longer than the 4.5 s allowed, so "asked the AI helper; no answer". Recovery now allows 10 s, the wait doesn't count against the step's 12 s, failures are logged (`EchoGemini`), and *Advanced → Test the key* checks the key, model and network.
- **Restaurant "Outside delivery range"** (same run). Opening it only showed a loading page. The result card's status is now read before opening; EchoFlow says *"Domino's Pizza says 'Outside delivery range' right now. Which restaurant should I use instead?"* and continues with the answer (verified: switched to Brik Oven). The AI helper is also told that a quote or logo on a blank page means "wait", not "go back" (verified: "still loading").
- **AI help with no key on the phone** (30 Sept). Relay deployed on Cloudflare; pinned to a US region because Google refused calls from the Chennai location ("User location is not supported"). With the pasted key removed: *Test AI help* answered in 1.5 s via the relay, and "order a farmhouse pizza from brik oven" got the AI helper's "still loading" mid-run through the relay and handed off at the cart, ₹343 (cart emptied afterwards). The relay first refused the stuck-screen prompt because it started with indentation; prompts are now trimmed.
- **Zomato ignored a tap on ADD** (same day). If ADD still says ADD with no options sheet, EchoFlow taps it once more as a real touch.
- **Teaching was slow to start** (reported 30 Sept). Three causes: understanding a new command waited up to ~8 s for the AI (now a hard 3 s cap; the phone's matcher decides without it); an answer tapped mid-question waited for the question to finish, and an interrupted question then waited out a ~10 s timeout (both fixed); and the user had to find the app. Now the app named in the command opens by itself. Measured: "play liked songs on spotify" → offer to learn in 2 s → Spotify open and recording 0.7 s after "yes".
- **After "stop", every command got "I'm still working on …"** until a restart (in v1.1.5). The stopped run's own clean-up was cancelled with it; it now always finishes, and "stop" resets at once. Verified: stop at step 2, then a YouTube command ran.
- **AI said "no match" but EchoFlow still asked to run a similar flow** ("show my wishlist on amazon" → "search … and add the first result to cart?"). Now it offers to learn the command.
- **AI helper quality:** it now sees how the stuck element looked when taught, the next step, where each element is and what's in a pop-up; a suggestion that changes nothing isn't tried again; asked after 5 s (was 7), up to 3 times per step.
- **A tap refused because the button was redrawn** (YouTube's search) ended the run; it's now found again and retried. YouTube 3/3 after the fix.
- **✕ on the listening panel folded everything into the handle** (reported on a OnePlus, 30 Sept). It now closes only the microphone and opens the controls (speak, home, move, ✕), which tuck away after the usual idle time. Verified on the S24 FE.
- **B2 re-checked on Myntra** after the "first result" changes: first sunglasses into the bag (bag emptied).

## Manual checklist before recording the demo

- [ ] Tap 🎤 in the bubble and **say** a command; the transcript should appear in the bubble.
- [ ] TTS speaks the questions and the hand-off line.
- [ ] On a second phone: install the APK, allow restricted settings, enable the service, and pass the Play Protect prompt if shown.
