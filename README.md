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
| "Book a cab to the airport." | *"I don't know how to … yet. Want to teach me?"* | T12 |
| "Order pizza." | *"Do you want me to order a pizza from a restaurant on zomato? I'll ask you which one."* | T13 |
| "Did the last run succeed?" | *"Yes, …"* or *"No, … stopped at step 5 of 6 (Tap "ADD") …"* | T14 |

## How to use
1. Install the APK, open **EchoFlow**, and enable the accessibility service (steps below). Allow the microphone.
2. A small floating bubble appears in every app:
   - **🎤** speak a command;
   - **✓ Done** finish teaching;
   - **■** stop;
   - **E** open EchoFlow;
   - **⇅** move the bubble.
3. **Teach:** tap 🎤 and say *"teach order garlic bread"*. Do the task yourself in the app, and **stop before paying**. Then tap **✓ Done**.
4. **Replay:** tap 🎤 and say the command, a paraphrase, or a different item, quantity or address.
5. **Inspect:** EchoFlow → *Learned flows* → tap a flow to see its steps and changeable values.

Optional: to match paraphrases without confirming first, put a free Gemini API key in `local.properties` as `GEMINI_API_KEY=…` before building. Without it, matching is fully on-device.

## Design and documentation
- [ARCHITECTURE.md](docs/ARCHITECTURE.md): modules, teach/replay pipelines, the DecisionLayer, SafetyGuard (diagrams)
- [TEST_MATRIX.md](docs/TEST_MATRIX.md): rubric → modules
- [TEST_RUN.md](docs/TEST_RUN.md): device results for T1–T14
- [LIMITATIONS.md](docs/LIMITATIONS.md): known limitations (honest list)
- [RESEARCH.md](docs/RESEARCH.md): prior art (SUGILITE, SkillDroid, …) and Android constraints
- [DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md): the 5-minute demo, phrase by phrase
- [SAFETY_FIXTURES.md](docs/SAFETY_FIXTURES.md): how the payment/OTP detector is validated on real apps

## Target apps (declared)

| Flow | App | Status |
|---|---|---|
| Food ordering: item, restaurant, quantity, saved address | **Zomato** | Verified end to end (T1–T5, T7, T10–T14) |
| Food ordering: item, quantity, saved address | **Swiggy** | Verified end to end (earlier run) |
| Shopping: search and add the first result to cart | **Amazon** | Verified (T8, T9) |

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
4. **If Play Protect warns during install:** tap **More details → Install anyway**. EchoFlow declares itself an accessibility tool (it's operated by voice), and some apps, Swiggy for example, only show their screens to accessibility tools. See [LIMITATIONS.md](docs/LIMITATIONS.md) L15.

## Safety monitor (debug)
In EchoFlow's *Safety monitor (debug)* section you can turn on a strip that shows how SafetyGuard classifies every screen:
- **Green SAFE**
- **Blue CHECKOUT**: one tap from paying. Only taught steps run here; the pay button is never tapped.
- **Red PAYMENT / OTP / PASSWORD / LOGIN**: stop and hand over.
- **Orange OPAQUE_UNKNOWN**: can't read the screen, so don't act.

**Dump** saves a redacted screen snapshot for use as a test fixture.
