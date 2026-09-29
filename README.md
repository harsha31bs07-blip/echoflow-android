# EchoFlow — Teachable Voice Automation (Android)

**Samsung PRISM GenAI Hackathon 2026 · Theme 3: Teachable Voice Automation**

You teach EchoFlow a task on your phone by saying a command and then doing the task yourself with taps. Later it replays the task when you say that command, a paraphrase of it, or a version with a different item, quantity or saved address.

It uses **only Android Accessibility Service APIs**: no app SDKs, deep links or web fallbacks, and no hard-coded flows. It **never pays**: it hands control back to you at checkout and on any payment, OTP, password or login screen.

| | |
|---|---|
| **Team** | *(add team name and members here)* |
| **Demo video** | *(add link here)* |
| **Presentation** | *(add link or file here)* |
| **APK** | [`release/EchoFlow.apk`](release/EchoFlow.apk) |

## Judges' quick start (3 minutes)
1. Install [`release/EchoFlow.apk`](release/EchoFlow.apk) (Android 11+). Open **EchoFlow → Open Accessibility settings** and turn on **EchoFlow automation**. On Android 13+, if the toggle is greyed out, see [the steps below](#enable-the-accessibility-service-judges-read-this).
2. Tap the slim **handle on the right edge** of the screen and say a task it doesn't know, e.g. *"Order a Margherita pizza from Domino's on Zomato."* It answers *"I don't know how to … yet. Want to teach me?"* → say **yes** → do it yourself in Zomato → tap **✓ Done** on the cart (never tap Pay). It says *"Learned: …"*.
3. Say the same sentence, a paraphrase, or change the dish, the quantity ("two") or the address ("deliver to work"). It runs to the cart and says *"Your turn…"*.

No account, server or API key is needed; everything runs on the phone.

## Scorecard: the official Theme 3 tests

Run on a Galaxy S24 FE with real apps (details for every row: [docs/TEST_RUN.md](docs/TEST_RUN.md#results)); every official phrase is also a unit test in [`RubricPhrasesTest`](core/src/test/kotlin/com/echoflow/core/nlu/RubricPhrasesTest.kt).

| Test | Points | On the phone | Unit tests |
|---|---|---|---|
| T1 Teach (food) | 5 | ✅ Zomato, confirmation spoken, flow visible in the Flow Inspector | `RubricPhrasesTest` T1 · `FlowCompilerTest` |
| T2 Exact replay | 5 | ✅ reached the cart unattended, same dish and restaurant | T2 |
| T3 Paraphrase ×2 | 6 | ✅ both phrases | T3 |
| T4 Slot: item | 4 | ✅ Farmhouse in the cart | T4 |
| T5 Slot: quantity | 4 | ✅ cart showed 2 | T5 |
| T6 Slot: address | 4 | ✅ switched to Work, and back to Home | T6 |
| T7 Screen change | 6 | ✅ handled by itself (dish already in cart; location pop-up; empty sheet) | `ReplayEngineTest` |
| T8 Teach (e-commerce) | 4 | ✅ Amazon, second flow | T8 |
| T9 New search term | 4 | ✅ phone case added from the first result | T8 and T9 |
| T10 Genuinely stuck | 5 | ✅ asked within 30 s ("couldn't find it, what instead?"); logged-out and other-language messages unit-tested | `ReplayEngineTest` |
| T11 Credential boundary | 5 (−10) | ✅ never tapped Pay or Place Order; every stop starts *"Your turn."* | `SafetyGuardTest` · `ScreenSafetyClassifierTest` · `GestureSafetyTest` |
| T12 Unknown intent | 3 | ✅ offers to learn it | T12 |
| T13 Ambiguity | 2 | ✅ asks before running | T13 |
| T14 Reporting | 3 | ✅ "Yes …" / "No … stopped at step 5 of 6 (…)" | T14 |
| B1 Unneeded taps | +3 | ✅ a real incoming call declined while teaching: its tap was dropped (*"I ignored 1 accidental or unneeded tap"*) | B1 |
| B2 Other similar app | +4 | ✅ Amazon flow run on Myntra: first result in the bag | B2 · `ReplayEngineTest` |
| B3 Missing value mid-flow | +3 | ✅ asked for the restaurant, then continued | B3 |

## What it does (the official test cases, run on a Galaxy S24 FE: [docs/TEST_RUN.md](docs/TEST_RUN.md))

| You say | EchoFlow does | Test |
|---|---|---|
| "Order a Margherita pizza from Domino's on Zomato.", then yes to *"Want to teach me?"*, then you tap through to payment | Records the taps, drops accidental ones, learns `{item}` and `{restaurant}`, says *"Learned: …"*, saves an inspectable flow | T1, B1 |
| the same sentence again | Runs unattended to Zomato's cart: *"Your turn. Everything is ready for payment, total ₹…. I won't pay."* | T2, T11 |
| "Get me a margherita from dominos" / "I want to order margherita pizza on zomato" | Same flow; when the restaurant isn't said, asks for it mid-run | T3, B3 |
| "Order a Farmhouse pizza…", "Order two Margherita pizzas…", "…deliver to work" | New dish, quantity 2 at the cart, saved address switched | T4, T5, T6 |
| *(the dish is already in the cart, or a pop-up appears)* | *"Margherita was already in your cart, so I didn't add another one."*; closes pop-ups and "Try again" error pages | T7 |
| "Search for wireless earbuds on Amazon and add the first result to cart." (taught), then "…a phone case…" | A second flow in a second app; opens the first product under "Results" and adds it to the cart | T8, T9 |
| "Search for sunglasses on Myntra and add the first result to cart." (only ever taught on Amazon) | *"I learned this on Amazon. Do you want me to try the same steps on Myntra…?"* → yes → searches Myntra, opens the first product, taps Add to Bag (and the size sheet's Done) | B2 |
| *(the app can't find the dish, or you're logged out)* | *"I searched for "…" but couldn't find it. What should I get instead?"* within 30 s, or stops at a login screen: *"Your turn: please log in"*. Never taps the wrong thing | T10 |
| *(stuck on a screen it wasn't taught, with an optional Gemini key)* | Gemini sees the screen's labels (redacted) and suggests one way out, such as closing an unfamiliar pop-up. EchoFlow checks the suggestion is safe, then carries on. Never on payment or login screens | T7, T10 |
| "Book a cab to the airport." | *"I don't know how to … yet. Want to teach me?"* | T12 |
| "Order pizza." | *"Do you want me to order a pizza from a restaurant on zomato? I'll ask you which one."* | T13 |
| "Did the last run succeed?" | *"Yes, …"* or *"No, … stopped at step 5 of 6 (Tap "ADD") …"* | T14 |

## How to use
1. Install the APK, open **EchoFlow**, and enable the accessibility service (steps below). Allow the microphone.
2. EchoFlow stays out of the way, like Siri on an iPhone. When idle it's only a **slim handle on the right edge** of the screen:
   - **tap the handle** to speak a command: a listening panel rises with coral dots that move with your voice, and your words appear as you speak;
   - **✕** on the bubble or the listening panel stops whatever is going on (a task, a question, a lesson being taught) and folds EchoFlow back into the handle, any time;
   - **long-press it** to open the controls;
   - while EchoFlow is listening, teaching, working or asking, **the screen's edges glow** (coral: listening or recording; mint: working; gold: asking you) and the controls stay open;
   - the controls are the coral **sound-wave** button (speak), **✓ Done** (finish teaching), **■** (stop), **⌂** (open EchoFlow), **⇅** (move to the other corner) and **✕** (stop and hide);
   - EchoFlow's own home screen has the same sound-wave button next to the text box;
   - prefer the controls always on screen? Turn it off in **EchoFlow → Advanced → Bubble**.
3. **Teach:** tap the handle and say *"teach order garlic bread"*. Do the task yourself in the app, and **stop before paying**. Then tap **✓ Done**.
4. **Replay:** tap the handle and say the command, a paraphrase, or a different item, quantity or address.
5. **Inspect:** EchoFlow → *Learned flows* → tap a flow to see its steps and changeable values.

Optional **AI help**: open **EchoFlow → Advanced → AI help** and paste a free Gemini API key (from aistudio.google.com). Two things change:
- **Looser wordings.** They're understood without asking "Do you want me to…?" first.
- **Stuck screens.** When a run is stuck on a screen it wasn't taught, such as an unfamiliar pop-up or a renamed button, EchoFlow first tries all its own recoveries. Only then does it ask Gemini for one suggestion:
  - Gemini sees the button and text labels, with typed text and numbers removed;
  - it's never asked about payment, login, OTP or cart screens;
  - EchoFlow follows a suggestion only if it passes the same safety checks as everything else, so it never pays, orders or deletes;
  - every AI step is logged in the run's history.

Without a key, everything runs on the phone. Developers can instead set `GEMINI_API_KEY=…` in `local.properties` before building.

## Accessibility and ease of use
- **Voice first, but never voice only.** Every question can be answered out loud, by tapping a choice, or by typing.
- **Readable.** All text and buttons meet WCAG AA contrast (at least 4.5:1). Text sizes follow the phone's font-size setting.
- **Easy to hit.** Every button and tappable chip is at least 48dp, including on the floating bubble.
- **Works with TalkBack.** Screen and section titles are headings. Icon buttons have spoken labels. Status changes and questions are announced as they appear, and questions are shown in full, not cut off.
- **Calm.** With "Remove animations" turned on, the listening dot stops pulsing.

## Design and documentation
- [ARCHITECTURE.md](docs/ARCHITECTURE.md): speech-to-intent, UI-tree capture, generalisation, slot extraction, replay and safety, with diagrams
- [TEST_MATRIX.md](docs/TEST_MATRIX.md): rubric → modules
- [TEST_RUN.md](docs/TEST_RUN.md): device results for T1–T14
- [LIMITATIONS.md](docs/LIMITATIONS.md): known limitations (honest list)
- [RESEARCH.md](docs/RESEARCH.md): prior art (SUGILITE, SkillDroid, …) and Android constraints
- [DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md): the 5-minute demo, phrase by phrase
- [SAFETY_FIXTURES.md](docs/SAFETY_FIXTURES.md): how the payment/OTP detector is validated on real apps

## Target apps (declared)

| Flow | App | Status |
|---|---|---|
| Food ordering: item, restaurant, quantity, saved address | **Zomato** | Verified end to end (T1–T7, T10–T14, B3) |
| Shopping: search and add the first result to cart | **Amazon** | Verified (T8, T9) |
| The Amazon flow, run in a similar app | **Myntra** (Flipkart mapped, untested) | Verified (B2) |
| Food ordering: item, quantity, saved address | **Swiggy** | Verified end to end (earlier run) |

Nothing in the code is specific to these apps: any app that shows its screen to accessibility services can be taught. What's untested is listed in [LIMITATIONS.md](docs/LIMITATIONS.md).

## Project layout

```
core/     Pure Kotlin/JVM, unit-tested (130 tests incl. real Swiggy screen fixtures and every official test phrase):
          safety (SafetyGuard, CHECKOUT/PAYMENT/OTP/LOGIN detection), gateway (the only way to act),
          teach (FlowCompiler), nlu (IntentMatcher), decision (DecisionLayer), replay (ReplayEngine),
          flow (weighted ElementResolver), runlog.
app/      Android: AccessibilityService, snapshot capture, action executor, voice (SpeechRecognizer + TTS),
          floating bubble, Orchestrator, Flow Inspector. Framework APIs only (no AndroidX).
docs/     Architecture, rubric traceability, device test run, limitations, research, demo script.
scripts/  Device test helpers (debug builds): install.ps1, run.ps1, echo.ps1.
```

## Build and install

You need JDK 17–21 (Android Studio's bundled JBR works) and the Android SDK (compileSdk 35).

```bash
./gradlew :core:test :app:assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To build and test only the safety core, without the Android SDK: `./gradlew -Pechoflow.jvmOnly=true :core:test`.

### Enable the accessibility service (judges, read this)
1. Open **EchoFlow** and tap **Open Accessibility settings**.
2. Enable **EchoFlow automation**.
3. **Android 13+ with a sideloaded APK:** the toggle may be greyed out ("restricted setting"). Go to **Settings → Apps → EchoFlow → ⋮ (top right) → Allow restricted settings**, then repeat step 2.
4. **If Play Protect warns during install:** tap **More details → Install anyway**. EchoFlow declares itself an accessibility tool (it's operated by voice), and some apps, Swiggy for example, only show their screens to accessibility tools. See [LIMITATIONS.md](docs/LIMITATIONS.md) L8.

## Safety monitor (debug)
In EchoFlow's *Safety monitor (debug)* section you can turn on a strip that shows how SafetyGuard classifies every screen:
- **Green SAFE**
- **Blue CHECKOUT**: one tap from paying. Only taught steps run here; the pay button is never tapped.
- **Red PAYMENT / OTP / PASSWORD / LOGIN**: stop and hand over.
- **Orange OPAQUE_UNKNOWN**: can't read the screen, so don't act.

**Dump** saves a redacted screen snapshot for use as a test fixture.
