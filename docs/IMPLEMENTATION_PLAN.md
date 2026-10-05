# EchoFlow: implementation plan to the finale shortlist

> **Demo-video update, 5 October 2026:** The current plan is the [V7 demo implementation plan](DEMO_INDUSTRY_IMPLEMENTATION_PLAN_V7.md); the delivered V7 video and its captions are in `demo-production/final-v7/`. Earlier demo versions, raw recordings and production files were deleted at the user's request. The milestones below remain historical project context.

**Deadline:** 30 Sept 2026. **Today:** 28 Sept (evening). **Team:** 4 people, plus Claude doing the coding.
**Goal:** a top-15 shortlist. That means **most of T1–T14 passing reliably on a real phone, zero T11 failures, a clean ≤5-minute demo video, and a correct tagged submission.**

## 0. What wins, and what we're deliberately not doing

**What shortlisting depends on:**
1. **Every rubric test demonstrably passes.** Competitors claim all 17 (see [RESEARCH.md](RESEARCH.md)). Judges will check the demo video against T1–T14, so robust beats clever.
2. **T11 never fails.** −10 is fatal. Our safety layer is already built and tested on real Swiggy screens. We protect that lead.
3. **The submission package is correct.** Samsung judges the commit tagged **`PRISM_GENAI_HACKATHON_Y2026`**. It must contain team details, the final PPT and the demo-video link in the README, and be pushed to `main` (per other teams' repos; **⚠️ confirm against your official participant kit today**).
4. **Differentiators judges will notice:**
   - safety tested on real screens, including the CHECKOUT verdict;
   - on-screen proof that EchoFlow can read the app (the diagnostics line);
   - a live "why I stopped" explanation;
   - honest limitations.

**Out of scope for this deadline:** Jev; Gemini Nano; the cross-app bonus (+4); Room/Compose migrations; screenshot/vision fallbacks. These go in "Future work" in the PPT.

## 1. Technical decisions (locked)

| Area | Decision | Why |
|---|---|---|
| **Language understanding** | Local matcher first: (1) regex from the taught utterance template, (2) slot-masked token similarity plus a synonym table. **Gemini Flash-Lite free tier** handles paraphrase and slot extraction, as JSON output with a 4 s timeout. If the network fails → local, then *confirm*. | Free. T2 works offline. Gemini only improves T3/T4/T9. |
| **Storage** | JSON files in `filesDir/flows/*.json` and `filesDir/runs/*.json`, via kotlinx.serialization (already a dependency). | No Room/KSP setup. The files double as the "inspectable" flow for T1. |
| **UI** | Framework Views (current pattern). Screens: Home (flows + last run), Flow Inspector, and a **floating overlay bubble** (mic / stop / status) usable inside any app. | No new dependencies. The bubble is essential because the demo happens inside Swiggy. |
| **Voice** | `SpeechRecognizer` push-to-talk from the bubble, plus TTS (the existing `Announcer`). | Proven, and free. |
| **Element matching** | SkillDroid-style weighted locator: id 0.40, text 0.20, content-desc 0.15, class 0.10, parent 0.10, sibling 0.05. Taps need a score ≥ 0.7. | From the literature (RESEARCH §2). Conservative on taps. |
| **Logic placement** | Compiler, matcher, decision layer, replay engine and resolver go in **`:core`** (pure Kotlin, unit-tested on the JVM with the real Swiggy fixtures). The app only adds capture, speech, UI and HTTP. | Fast iteration without the phone. Deterministic tests of the rubric logic. |

## 2. Milestones (Claude codes; each ends with tests passing, an install, and a device check)

### Day 0: 28 Sept evening
**M1: Teach mode → saved, inspectable flow (T1, B1).** Target: about 4 hours.
- `core/teach/`: `RawStep` (pre-snapshot id, target `ElementDescriptor`, action, typed text, post-snapshot fingerprint) and `TeachSession`.
- `core/compile/FlowCompiler`, passes in order:
  1. noise filter (B1): debounce taps under 350 ms, drop no-op taps, collapse A→B→A detours;
  2. bind slots from the teaching utterance (numbers via regex, other values via Gemini or the typed text);
  3. infer `SubmitIme`;
  4. insert `Boundary(PAYMENT/CHECKOUT)`.
- `app/teach/TeachingRecorder`, which listens in the service:
  - records `TYPE_VIEW_CLICKED` and `TYPE_VIEW_TEXT_CHANGED` against the last snapshot;
  - stops at the first sensitive screen, at a COMMIT tap, or when the user says "stop" / "done".
- `FlowStore` (JSON), `FlowInspectorActivity` (steps, slots, example utterances, delete), and the overlay bubble with a mic.
- **Device check:** say "teach: order garlic bread", tap through Swiggy to the cart, say "done". The Inspector shows the steps with `{item}` bound.

### Day 1: 29 Sept (the big day)
**M2: Exact replay to payment (T2, T11).** About 3 hours.
- `core/replay/ElementResolver` (weighted locator, list search by slot, scroll to find) and `ReplayEngine`:
  - each step: wait for idle → check the screen → resolve the target → `ActionGateway.perform(taught ctx)` → verify;
  - `LaunchApp` first, then press back until the start screen matches.
- At the end: `SafetyGuard.handOffAtCheckout()`, or the PAYMENT trip, speaks the hand-off.
- **Device check:** the exact utterance replays unattended to the Swiggy cart and hands off with "₹… ready at checkout".

**M3: Matcher + DecisionLayer (T3, T12, T13, T14).** About 3 hours.
- `core/nlu/IntentMatcher`:
  1. meta-intents (teach / stop / continue / cancel / "what happened last time");
  2. template regex (exact → 1.0);
  3. local similarity (capped at 0.75);
  4. the Gemini adapter in the app, `LlmClient` (JSON: flowId, confidence, slots).
- `core/decision/DecisionLayer`: the pre-run table from ARCHITECTURE §5, i.e. <0.45 offer to teach; margin <0.15 ask which; <0.8 or fallback → confirm.
- `core/runlog/RunLog` plus a spoken summary template (T14).
- Unit tests: one per decision-table row.

**M4: Slots and a second flow (T4, T5, T6, T8, T9, B3).** About 3 hours.
- `SelectFromList` (search results, saved address), `AdjustStepper` (+/− to reach qty), `TypeText({slot})`.
- Missing slot → `ASK_SLOT`, with choices read from the screen (B3).
- A second flow on the chosen second app (Amazon or Flipkart search, depending on the team's validation dumps).

**M5: Changed screens, being stuck, recovery (T7, T10).** About 3 hours.
- `core/decision/StateClassifier`, using SkillDroid deviation classes: None / Minor (relaxed threshold) / Moderate (dialog) / Major (other app).
- Popup → tap a dismiss button from a whitelist, matched by whole word. If none → ask.
- Swiggy's "Replace cart items?" dialog → **ask** (destructive: never automatic).
- Cart already has a different item → ask.
- 20 s no-progress watchdog → `HALT_REPORT`. Login → hand-off. Language changed (script ratio) → ask.

**Cut line, 29 Sept 22:00:** anything unfinished in M4/M5 is dropped and documented in LIMITATIONS.md. No new features after this.

### Day 2: 30 Sept (ship day)
| Time | Task | Owner |
|---|---|---|
| 08:00–11:00 | Full T1–T14 rehearsal on the phone using `docs/TEST_RUN.md`; Claude fixes bugs (fixes only) | Claude + P3 |
| 11:00 | **Code freeze.** Build the APK and attach it to a GitHub Release. Merge the branch into `main`. | Claude |
| 11:00–14:00 | Record the demo video (≤5 min) and upload it (YouTube unlisted / Drive) | P4 + P3 |
| 11:00–14:00 | Finish the PPT; add team details to the README | P2 |
| 14:00 | Add the video link and PPT to the repo → final README pass → **create and push tag `PRISM_GENAI_HACKATHON_Y2026`** | Claude + P1 |
| Buffer | Submit on the portal well before the cutoff | All |

## 3. Parallel human tasks (start now)
- **P1 (validation):** dump the cart and payment screens for **Amazon** and **Zomato** (see SAFETY_FIXTURES.md). Pick the second app. Get a **Gemini API key** (free, aistudio.google.com) and put it in `local.properties` as `GEMINI_API_KEY=` without sharing it. **Read the official participant kit and confirm the submission rules and deadline time.**
- **P2 (PPT):** build the deck from ARCHITECTURE.md diagrams, TEST_MATRIX.md, RESEARCH.md (competitors, SkillDroid) and LIMITATIONS.md. Add team details.
- **P3 (test phone):** Swiggy logged in, with **two saved addresses (Home, Work)**; a second phone to test the install, including the Play Protect prompt and restricted settings; write `docs/TEST_RUN.md` (one row per T-test: steps, expected, pass/fail).
- **P4 (demo):** write the video script. It must show, in this order:
  1. teach;
  2. exact replay;
  3. a paraphrased command;
  4. a changed slot;
  5. getting stuck and asking;
  6. the payment hand-off;
  7. "what happened last time".

  Plan screen recording with voice audio.

## 4. Rubric coverage at the cut line

| Test | Milestone | Confidence |
|---|---|---|
| T1, T2, T11, T14 | M1, M2, M3 | High |
| T3, T12, T13 | M3 | High (Gemini online) / Medium (offline → confirms) |
| T4, T8, T9 | M4 | High |
| T5, T6, B3 | M4 | Medium: depends on stepper/address UI (L5) |
| T7 (popup) | M5 | High. (Pre-existing cart item: Medium.) |
| T10 | M5 | High for logged-out and watchdog; Medium for language change |
| B1 | M1 | High |
| B2 (cross-app) | — | Skipped |

## 5. Verification
- **JVM:** `./gradlew :core:test`. Every milestone adds tests, and replay is tested against recorded Swiggy teaching traces.
- **Device:** after each milestone, `assembleDebug` → `adb install -r` → run the check on the S24 FE → pull the dumps and run logs over adb.
- **Final:** every row in `docs/TEST_RUN.md` passes on a *fresh install* on the second phone before tagging.
