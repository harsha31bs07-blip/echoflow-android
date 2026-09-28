# Research notes: prior art, competitors, platform constraints

Collected 2026-09-28. Each section ends with what it means for EchoFlow.

## 1. The problem statement and competing teams

The theme is **Samsung PRISM GenAI Hackathon 3.0, Theme 3: Teachable Voice Automation**: *"Teach the assistant a flow once; it replays it on command."* Other teams' public repos give a sense of the field:

| Project | Language understanding | Safety | Replay / recovery | Target apps |
|---|---|---|---|---|
| [FlowPilot](https://github.com/OdinStack/FlowPilot) | Gemini 2.0 Flash, plus `text-embedding-004` cosine similarity > 0.88 as a fast path | "3-layer credential boundary detection" | "Recoverable execution" (details not documented) | Calculator, Domino's, Zomato, Phone |
| [SaySo / PRISM](https://github.com/rizzit17/sayso) | On-device only: token Jaccard + Levenshtein | "5 defence-in-depth layers" in a `CredentialBoundaryDetector` | 8-signal weighted UI matcher; 5-stage recovery (re-snapshot → dismiss → relax threshold → alternate state → escalate) | Zomato, Domino's, Amazon, Swiggy |

Neither repo mentions `isAccessibilityTool`, or apps that hide their UI from accessibility services. We hit that on Swiggy (see §3).

**What this means for EchoFlow.** The judges will see several similar architectures, so these are the things that set EchoFlow apart:
- **Safety checked against real screens:** real-app fixtures, a CHECKOUT verdict, and a guard that fails closed on unreadable screens.
- **A deterministic replay-first design,** where the LLM only makes suggestions.
- **Honest documentation of limitations.**

The demo should *show* the guard stopping on a real payment screen, and the diagnostics line proving the app can read Swiggy.

## 2. Academic prior art

### SUGILITE (CMU, CHI 2017)
[SUGILITE](https://github.com/tobyli/Sugilite_development) is the original teach-by-demonstration system for Android. It also used the accessibility API. A spoken command that isn't recognised triggers a demonstration, and the script is generalized from three things: the spoken instruction, the recorded actions, and the UI hierarchy. It handles new situations by branching the script, with context checks.

**What this means for EchoFlow.** Our design (M6/M7/M9) follows the same pattern. SUGILITE's research papers are useful to cite in the architecture writeup.

### AutoDroid (MobiCom 2024)
[AutoDroid](https://arxiv.org/abs/2308.15272) explores an app in advance to build a UI transition graph and an "app memory", then uses an LLM to act. It reached 71.3% task success.

**What this means for EchoFlow.** We don't need to explore apps in advance: the teacher's demonstration *is* our app memory.

### SkillDroid (arXiv 2604.14872, April 2026): the closest match to our design
[SkillDroid](https://arxiv.org/abs/2604.14872) compiles a successful run into a **parameterized skill template** and replays it with **no LLM calls**. It reached 85.3% success, against 62% for a stateless LLM agent, with 49% fewer LLM calls. Pure template replay succeeded 100% of the time and ran 2.4× faster. The concrete design details worth copying:

- **Weighted element locator:**

  | Feature | Weight | How it matches |
  |---|---|---|
  | resourceId | 0.40 | exact |
  | text | 0.20 | substring |
  | contentDescription | 0.15 | substring |
  | className | 0.10 | exact |
  | parent context | 0.10 | |
  | sibling position | 0.05 | |

  The score is normalized over the features actually present. The **strict threshold is 0.5**, relaxed to **0.3** when the UI has drifted.
- **Matching cascade**, used to pick a skill:
  1. Regex: the intent template with slots turned into `(.+)`. This also extracts the slot values.
  2. Embeddings: all-MiniLM-L6-v2, cosine ≥ 0.40, followed by an LLM confirmation.
  3. A filter on the target app.
- **Deviation classes:**
  - **None:** strict threshold.
  - **Minor** (same app, elements shifted): relaxed threshold.
  - **Moderate** (unexpected dialog): dismiss it, using *word-boundary* regex so "ok" doesn't match "booking".
  - **Major** (different app): abort.
- **Step skipping:** if a later step's element is already on screen, skip the missing steps.
- **Failure learning:** a skill whose failure rate goes above 50% is recompiled, with at most 3 versions.
- **Known limits:** multi-field forms, text-only perception, English only.

**What this means for EchoFlow.**
- Adopt the locator weights in **M12 ElementResolver**. Our minimum confidence to *tap* stays higher (0.7) because a wrong tap costs more here.
- Adopt the matching cascade in **M9 IntentMatcher.** The regex step is exactly our "exact path"; for the LLM step we use Claude.
- Adopt the deviation classes in **M13 StateClassifier.**
- Use word-boundary matching for the dismiss whitelist.

### Benchmarks
- [AndroidWorld](https://arxiv.org/pdf/2512.19432): the best agents here are **vision-based** (screenshots). Agents that use only the accessibility tree rarely lead.
- [MobileWorld](https://arxiv.org/pdf/2512.19432): on harder, cross-app tasks the best framework reaches 51.7%.
- [AmbiBench](https://arxiv.org/pdf/2602.11750): about vague or underspecified instructions. The takeaway is to ask a structured clarifying question instead of guessing.

**What this means for EchoFlow.**
- Replay-first plus the accessibility tree is the right choice for reliability on *taught* flows.
- For the cross-app bonus and for opaque screens, `AccessibilityService.takeScreenshot()` (API 30+, still the Accessibility API) could give the LLM a screenshot. This is optional and comes late.
- T12 and T13 behaviour (ask, don't guess) matches the literature.

## 3. Android platform constraints

| Constraint | Source | What it means for EchoFlow |
|---|---|---|
| **`accessibilityDataSensitive`** (Android 16). Views marked with it are delivered only to services that declare `isAccessibilityTool="true"`. | [Android Developers blog, Dec 2025](https://android-developers.googleblog.com/2025/12/enhancing-android-security-stop-malware.html) | This almost certainly explains why Swiggy was unreadable in round 1, and why declaring the flag fixed it in round 2. |
| **Policy:** only screen readers, switch access, **voice-based input tools** and Braille tools may declare `isAccessibilityTool`. Automation tools and assistants may not. Wrongly declaring it gets an app rejected by Play, and **Play Protect may block it on devices**. | Same blog; [Play policy](https://support.google.com/googleplay/android-developer/answer/10964491) | EchoFlow is operated by voice, which is a defensible category. It still carries a **risk that Play Protect flags the sideloaded APK on a judge's phone**. The README must tell judges what to do. See LIMITATIONS L15. |
| **Android 17 Advanced Protection Mode** revokes accessibility from apps that aren't tools. | [The Hacker News, Mar 2026](https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html) | Only applies when that mode is on. Demo devices should have it off. |
| **Restricted settings** (Android 13+) and **Enhanced Confirmation Mode** (Android 15, allowlist-based). | [Android Authority](https://www.androidauthority.com/android-15-restricted-settings-sideloading-3481098/) | Judges must "Allow restricted settings". On devices with ECM enforced, sideloaded accessibility may be blocked outright. The Galaxy S24 FE used in testing works. |

## 4. Changes this research implies (backlog)

1. **README / LIMITATIONS:** document the Play Protect risk from `isAccessibilityTool`, and the steps judges should take.
2. **M12:** use SkillDroid-style weighted locators (weights above) plus our 0.7 minimum confidence for taps.
3. **M9:** a cascade of regex template → Claude intent matching (with confidence) → app filter.
4. **M13:** None / Minor / Moderate / Major deviation classes. Dismiss buttons are matched with word-boundary regex.
5. **Replay:** skip missing steps by looking ahead. Record failures per step as data for recompiling.
6. **Optional:** a screenshot fallback through `takeScreenshot()`, for opaque screens and the cross-app bonus.
