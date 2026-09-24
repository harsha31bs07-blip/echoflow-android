# Known Limitations and Open Design Questions

These are the places where the architecture has **no clean answer yet**, or where it depends on heuristics that still need testing on real devices. Each entry states what fails, what the mitigation is, and what is still left open.

## L1. Opaque payment screens (T11)
**Problem.** Some payment screens are WebViews, use `FLAG_SECURE`, or draw their own UI. They may expose almost no accessibility nodes, so keyword and field checks see nothing.

**Mitigation.**
- A WebView-dominant or near-empty tree reached after the last taught step is classified `OPAQUE_UNKNOWN`, which fails closed.
- Payment-gateway and bank packages are on a denylist.
- Replay always ends at `Boundary(PAYMENT)`.

**Still open.** These are heuristics. Every declared app must pass the safety-monitor validation described in [ARCHITECTURE.md § Build order](ARCHITECTURE.md#build-order-risk-first). Apps that fail are removed from the declared list.

## L2. Cash-on-delivery commits without a payment screen (T11)
**Problem.** If cash on delivery is preselected, tapping "Place order" places a real order, and no payment screen ever appears.

**Mitigation.** COMMIT verbs are never tapped, even if one was demonstrated.

**Still open.** The verb list only covers English and Hindi, and wording differs between apps.

## L3. Item-dependent flow shape (T4)
**Problem.** Different items can lead through different screens. For example, Margherita may open a customization sheet while garlic bread doesn't, or opens a different one.

**Mitigation.** When a taught screen is *missing*, the engine skips ahead by fingerprint lookahead.

**Still open.** When an *untaught* screen appears, the app cannot handle it on its own. It asks instead: "Garlic bread has size options — use the defaults?"

## L4. Pre-existing cart detection (T7)
**Problem.** Detecting a changed cart relies on the "facts" recorded at checkpoints, which are line items extracted from list screens.

**Mitigation.**
- This works for simple cart lists.
- An LLM hint over a redacted element list helps on unfamiliar layouts.
- The app always asks the user and never removes items on its own.

**Still open.** Detection is weak when the cart is a collapsed summary or is drawn with custom views. Popup handling, the other half of T7, is clean.

## L5. Latent steps for default values (T5, T6)
**Problem.** If the teacher kept the default quantity of 1 or the preselected address, no tap was recorded. Without a recorded step, the app has nothing to parametrize.

**Mitigation.**
- "Visible but untouched" inference: a slot value that is shown on a recorded screen still gets a step.
- The teaching guide tells the judge to say slot values out loud ("…to Home"), so the value can be anchored to what is on screen.

**Still open.** This inference is new and has not been tested on real apps.

## L6. Language-changed recovery (T10)
**Problem.** Once the app's language changes, the text features used to match elements no longer match.

**Mitigation.**
- Detection is clean: the script/language ratio differs from the one seen during teaching.
- The app asks the user a specific question or reports the problem.

**Still open.** "Try matching by layout" (resource-id and structure only) is best-effort. Apps built with Jetpack Compose often have no resource-ids, which also weakens fingerprints in general.

## L7. Gaps in teaching capture
**Problem.** Some custom or Compose views don't emit `TYPE_VIEW_CLICKED` events. The IME search key emits no event at all.

**Mitigation.**
- The compiler infers `SubmitIme` from "text changed, then the screen changed".
- It infers "unknown transition" steps from differences between screens, and the teacher may need to confirm them.

**Still open.** Inferred steps are less reliable than directly observed ones.

## L8. App launch interpretation
**Problem.** `LaunchApp` uses `getLaunchIntentForPackage`, the same intent the home screen launcher sends. It is not a deep link.

**Mitigation.** If judges decide this is not allowed, there is a fallback: go to the home screen and tap the app icon through accessibility.

**Still open.** That fallback is fragile, because it depends on the launcher's layout.

## L9. The 30 s budget with a cloud LLM (T10)
**Problem.** Cloud LLM latency eats into the 30-second limit for reporting that the app is stuck.

**Mitigation.**
- During a run, at most one LLM call per deviation, with a 4 s timeout.
- The watchdog is purely local, so T10 holds even if the network goes down.

**Still open.** With the network down, paraphrase matching (T3) falls back to asking for confirmation.

## L10. Cross-app generalization (bonus)
**Problem.** Replaying a flow learned on Amazon in Myntra depends on the LLM picking the right element for each semantic step.

**Mitigation.** The user confirms first. The local resolver checks each pick, and every action still goes through the safety gate.

**Still open.** This is the least reliable feature. Only a Myntra search and add-to-bag flow will be demoed.

## L11. Installation friction
**Problem.** Android 13 and later blocks accessibility services in sideloaded APKs until the user enables **Allow restricted settings** (App info → ⋮).

**Mitigation.** The README and the in-app onboarding walk the judges through this step.

## L12. Continuous listening
**Problem.** `SpeechRecognizer` does not support always-on listening.

**Mitigation.** Voice input is push-to-talk, using the overlay bubble. There is no wake word.
