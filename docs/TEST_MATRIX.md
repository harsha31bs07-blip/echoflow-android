# Rubric Traceability Matrix

This file maps every judged test case and bonus to the modules that make it pass. Module IDs refer to [ARCHITECTURE.md](ARCHITECTURE.md#2-module-overview).

Confidence levels:

- **Clean:** the design fully covers the test.
- **Partial:** the design covers the common case, and some known cases fall back to asking the user.
- **Risky:** depends on heuristics that have not been validated yet.

Every Partial or Risky row points to an entry in [LIMITATIONS.md](LIMITATIONS.md).

## Test cases

| Test | Requirement (short) | Owning modules | Mechanism | Confidence |
|---|---|---|---|---|
| T1 | Teach by voice and taps, stop before payment, saved and inspectable | M6 TeachingRecorder, M7 FlowCompiler, M4 SafetyGuard, M8 FlowRepository, M17 UI | The teach meta-intent starts a session. SafetyGuard stops recording at the first sensitive screen and inserts `Boundary(PAYMENT)`. The compiled flow appears in the Flow Inspector with per-step descriptions, slots and example utterances, and can be exported as JSON | Clean |
| T2 | Exact utterance replays unattended up to payment | M9 exact path, M10, M11, M12, M5, M4 | A normalized exact match scores 1.0 with no LLM call. The DecisionLayer returns `PROCEED`, the replay runs, and SafetyGuard hands off on the PAYMENT screen | Clean |
| T3 | Two paraphrases both replay | M9 LLM path, M14, M10 | The LLM maps each paraphrase to the flow. The local fallback confirms instead of proceeding. A successful paraphrase is saved as a new example utterance | Clean with network. Partial offline (asks for confirmation) |
| T4 | Different item | M7 slot binding, M12 `SelectFromList`, M11 lookahead skip | The search text and the result pick are bound to `{item}`. No match or several matches → asks | Partial — [L3](LIMITATIONS.md#l3-item-dependent-flow-shape-t4) |
| T5 | Different quantity | M7 latent/stepper binding, M12 `AdjustStepper`, M10 plausibility confirm | Taps +/− until the displayed count equals `qty`, checking after each tap | Partial — [L5](LIMITATIONS.md#l5-latent-steps-for-default-values-t5-t6) |
| T6 | Different saved address (list selection) | M7 latent list step, M12 `SelectFromList`, M10 `ASK_SLOT` | Picks one of the list's children by the `{address}` value. If none match, asks with the live choices | Partial — [L5](LIMITATIONS.md#l5-latent-steps-for-default-values-t5-t6) |
| T7 | Screen state changed since teaching (popup, pre-existing cart) | M13 StateClassifier, M10 in-run table, M5 recovery whitelist | Popup → `OVERLAY_BLOCKING` → tap a whitelisted dismiss button, or ask. Cart → the checkpoint facts differ → a specific question. Items are never removed automatically | Popup: Clean. Cart: Partial — [L4](LIMITATIONS.md#l4-pre-existing-cart-detection-t7) |
| T8 | Teach a second flow in a second app | M6, M7 (nothing app-specific), M8 | Taught the same way as T1 | Clean |
| T9 | Generalize across search terms | M7 `{query}` binding, M12, M9 slot extraction | `TypeText({query})`, then `SelectFromList` on results | Clean |
| T10 | Stuck (language changed, logged out) → clear question or specific failure within 30 s, no destructive taps | M13, M10 (watchdog, `HALT_REPORT`), M4 `LOGIN`, M12 confidence floor, M5 | Logged out → hand-off with the reason. Language changed → a specific question. The 20 s watchdog stops with no progress. The engine never taps below 0.7 confidence, and recovery is limited to back, scroll and dismiss | Detection: Clean. Language recovery: Partial — [L6](LIMITATIONS.md#l6-language-changed-recovery-t10) |
| T11 | Payment/OTP → full stop, explicit hand-off | M4 SafetyGuard (gate and watcher), M5 ActionGateway | OR-ed screen signals, COMMIT verbs blocked, fail-closed on opaque screens, frozen gateway, fixed hand-off message | Risky until validated on each app — [L1](LIMITATIONS.md#l1-opaque-payment-screens-t11), [L2](LIMITATIONS.md#l2-cash-on-delivery-commits-without-a-payment-screen-t11) |
| T12 | Unknown command → say so, offer to teach, don't guess | M9, M10 | A top score under 0.45 gives `UNKNOWN_OFFER_TEACH`. Replay never starts | Clean |
| T13 | Ambiguous command → ask which, or confirm | M10 | If the top two scores are within 0.15, `DISAMBIGUATE`. If the top score is mid-range, `CONFIRM` | Clean |
| T14 | Report the last run's outcome and where it stopped | M15 RunLog, M9 meta-intent | A template-based summary of the latest `RunRecord`. The UI also has a Runs list | Clean |

## Bonuses

| Bonus | Owning modules | Mechanism | Confidence |
|---|---|---|---|
| +3 Discard accidental/irrelevant touches | M7 noise pass | Debounce, drop no-op taps, remove detour cycles, cancel scroll pairs, filter the overlay. The drop count is reported to the teacher | Clean |
| +4 Generalize across similar apps (Amazon → Myntra) | M7 semantic plan, M14, M12, M10 cross-app confirm | The user confirms first. Then, for each semantic step, the LLM picks an element from a redacted list, the local resolver validates it, and every action is still gated | Risky — [L10](LIMITATIONS.md#l10-cross-app-generalization-bonus) |
| +3 Mid-flow clarification for a missing parameter | M10 `ASK_SLOT`, M12 | Slots are resolved when needed. The question lists the live on-screen choices | Clean |

## Core requirements

| Requirement | Enforced by |
|---|---|
| Android Accessibility APIs only; no SDKs, deep links or web fallbacks | M2 is the only device interface. `LaunchApp` uses the launcher intent (see [L8](LIMITATIONS.md#l8-app-launch-interpretation)) |
| No hard-coded flows | Flows exist only as data in M8, produced by M7 from a live demonstration |
| No credential capture, hand-off on payment/OTP/password/login | M4 and M5, plus invariant 5 (sensitive content is never persisted) |
| Parametrized flows | M7 slot binding, M12 slot-aware resolution |
| Detect UI/state changes, recover or ask | M13 and the M10 in-run table |
