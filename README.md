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

## What it does (all verified on a Galaxy S24 FE: [docs/TEST_RUN.md](docs/TEST_RUN.md))

| You say | EchoFlow does | Rubric |
|---|---|---|
| "teach order garlic bread", then you tap through Swiggy to the cart, then **Done** | Records the taps, drops accidental ones, turns "garlic bread" into a changeable `{item}`, saves an inspectable flow | T1, B1 |
| "order garlic bread" | Opens Swiggy, searches, adds the right dish, opens the cart, then: *"Everything is ready at checkout, total ₹153. I won't pay."* | T2, T11 |
| "can you get me some garlic bread" | Paraphrase → confirms, then replays (direct with a Gemini key) | T3 |
| "order 2 choco lava cake to home" | New dish, quantity 2 at the cart, delivery address switched to your saved **Home** | T4, T5, T6 |
| *(the cart already has another restaurant's food)* | *"Your cart already has other items… Should I replace them?"*; closes promo popups by itself | T7 |
| "search running shoes on amazon", then "search wireless earbuds on amazon" | A second flow in a second app, for any search term | T8, T9 |
| "order zzqx unicorn waffles" | *"I can't find it. I can see: …. Which one should I pick?"*, and never taps a wrong item | T10, B3 |
| "book a cab to the airport" | *"I don't know how to do that yet. Want to teach me?"* | T12 |
| "get me garlic bread" (with Swiggy and Zomato flows) | *"I know more than one way to do that… Which one?"* | T13 |
| "what happened last time" | A spoken summary of the last run and where it stopped | T14 |

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
| Food ordering: item, quantity, saved address | **Swiggy** | Verified end to end |
| Food search | **Zomato** | Verified (search flow; used for T13) |
| Shopping search | **Amazon** | Verified (search flow; T8/T9) |

## Project layout

```
core/     Pure Kotlin/JVM, unit-tested (100 tests incl. real Swiggy screen fixtures):
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
