# Plan: getting EchoFlow into the Theme 3 finals (top 15)

Written 29 Sept 2026, evening. The submission deadline is **30 Sept**. The demo video and the deck are handled by teammates, so they are left out of this plan except where the code or repo has to support them.

## 1. What we know about the hackathon

**Sources:**
- the official *Theme 3 – Evaluation Criteria* PDF from the participant kit;
- other teams' public repos (links in §2).

Anything else is marked *unverified*.

- **Event:** Samsung PRISM Generative AI Hackathon, 3rd edition (2026–27). The themes include:
  - 1: Agentic Code Intelligence;
  - 2: Smart Guided Troubleshooting;
  - **3: Teachable Voice Automation**;
  - 5: Interruptible Real-Time Agents.
- **Required for submission (from the PDF):**
  - an installable APK;
  - the source repo;
  - a demo video of 5 minutes or less (a→e, unedited, in order);
  - an **architecture write-up covering speech-to-intent, UI-tree capture, generalisation, slot extraction and replay, with diagrams**;
  - a **target apps declaration**;
  - **known limitations**.
- **What other teams' repos say is required** (Theme 1 team, *unverified for Theme 3*):
  - a public repo with a GitHub **Release tagged `PRISM_GENAI_HACKATHON_Y2026`**;
  - a Google Form submission;
  - a deck named `CollegeName_TeamName`;
  - an **AI Usage Disclosure form**.
  - One Theme 1 release was dated "30 September 2026", which matches our deadline.
- **Format** (from a search summary, *unverified*): the phases are online, and the Grand Finale is on-site in Bengaluru. How the top 15 are picked is not published. Assume judges install our APK, read the repo, and watch the video.
- **Scoring:**
  - **60 base points**, T1–T14. T11 fails at −10.
  - **+10 bonus**: B1 +3, B2 +4, B3 +3.
- **The rule that decides most of the score:**

  > *"No hard-coded flows. Judges will teach a new flow live; anything that only works on pre-baked scripts scores zero on generalisation (T2–T9)."*

  T2–T9 are 37 of the 60 points. If a judge teaches a flow we never tried and it fails, we lose most of the score, however good our own runs look.

## 2. The competition

These are the Theme 3 teams with public repos, compared on what their READMEs claim:

| Team / repo | Language understanding | Replay | Safety | Evidence of testing |
|---|---|---|---|---|
| [OdinStack/FlowPilot](https://github.com/OdinStack/FlowPilot) | Gemini 2.0 Flash (cloud), `text-embedding-004` similarity > 0.88 | "Recoverable execution", Room DB, audit log | "3-layer credential boundary detection" | No test results or video listed |
| [parthjaina2107/FlowPilot](https://github.com/parthjaina2107/FlowPilot) (SRM) | Gemini 2.0 Flash compiles a FlowGraph; Whisper STT; Sentence-BERT + ChromaDB | "Semantic Element Finder" | Not documented | None listed; needs a FastAPI/Docker backend |
| [Harishkrai7/teachable-voice-automation](https://github.com/Harishkrai7/teachable-voice-automation) | Gemini on a Google Cloud FastAPI backend | Semantic actions (SEARCH, SELECT…), `ActionVerifier` | Filters sensitive fields; cloud-side STOP | None listed |
| [rizzit17/sayso](https://github.com/rizzit17/sayso) | **On-device only**: Jaccard + Levenshtein, no LLM | 8-signal UI matcher, 5-stage recovery | 5-layer `CredentialBoundaryDetector`, 30 s stuck timeout | Claims **all 17 cases pass** in a unit `EvaluationTestSuite`; 84+ assertions; Zomato, Domino's, Amazon, Swiggy |
| [kalyx35/Teachable-Voice-Assistant](https://github.com/kalyx35/Teachable-Voice-Assistant), [jeffreysolomon123/Teachable-Voice-Automation](https://github.com/jeffreysolomon123/Teachable-Voice-Automation) | Not documented (early prototypes) | | | |

**Where EchoFlow is ahead:**
1. **Every official test run on a real phone**, with results per test:
   - T1–T14 and B2 on real Zomato, Amazon and Myntra, recorded in `docs/TEST_RUN.md`;
   - B3 on the phone too, and B1 unit-tested.

   The others show architecture, and SaySo shows unit tests only.
2. **A single APK that needs nothing else:** no backend and no API key. Three of the four documented rivals need a cloud backend or a key, which judges may not have.
3. **Safety built from real incidents:**
   - `GestureSafety` came from a real Place Order near-miss;
   - the CHECKOUT/PAYMENT screen checks were tuned on real apps;
   - unreadable screens are treated as unsafe.
4. **B2 verified on a real Myntra**, the rubric's own example. Nobody else claims that.

**Where we are behind:**
1. **The "GenAI" story.** This is a GenAI hackathon, and most rivals lead with Gemini. Our LLM path is optional and switched off in the release APK.
2. **Tested on one phone** (Galaxy S24 FE) and one account.
3. **Our public repo looks empty.** `main` has 3 commits and only docs, with no release and no tag. All our work is on `claude/elegant-einstein-c96kwl`.
4. **The architecture doc is out of date.** `docs/ARCHITECTURE.md` still says *"Status: design document. No implementation code exists yet."* It also describes Room storage and a cloud LLM as the main path, and lists Swiggy and Flipkart as targets.
5. **No real-voice test.** Every device test used the debug command broadcast, not the microphone.

## 3. The plan, in priority order

Owners: **C** = Claude (code and docs), **H** = Harsha (phone and decisions), **T** = the rest of the team.

### P0: submission blockers (tonight). Missing any of these could disqualify us or zero a section.

| # | Task | Why | Owner | Time |
|---|---|---|---|---|
| P0-1 | **Rewrite `docs/ARCHITECTURE.md` to match the code.** Remove the "no code exists" line. Add diagrams in Mermaid (GitHub renders it) for (a) the teach pipeline (accessibility events → noise filter → FlowCompiler → slots → JSON flow), (b) the command pipeline (speech → IntentMatcher cascade → DecisionLayer → ReplayEngine → ActionGateway/SafetyGuard), and (c) replay recovery. Cover speech-to-intent, UI-tree capture, generalisation, slot extraction and replay by name, since the PDF lists them. | Required item; judges read it first | C | 1.5 h |
| P0-2 | **Update the target apps declaration and `LIMITATIONS.md`.** Declare Zomato (T1–T7, T10–T13), Amazon (T8–T9), Myntra (B2). List honestly what's untested: Domino's itself (Brik Oven stood in), Hindi UI (Zomato ignores it), other phones, flows judges might teach in untried apps. | Required items | C | 30 min |
| P0-3 | **Make the repo judge-ready.** Merge the branch into `main`, create the `PRISM_GENAI_HACKATHON_Y2026` tag, publish a GitHub Release with `EchoFlow.apk` attached, and add a repo description and topics. **Needs Harsha's go-ahead** (merge and tag are never done without it). | Otherwise the public repo shows 3 commits | C after H approves | 20 min |
| P0-4 | **Confirm the rules** from the participant kit or email: the form link, the exact deadline time (IST), whether `main` or the tag is read, and the **AI Usage Disclosure**. We used Claude heavily, and the commits say so (`Co-Authored-By`). The disclosure must be honest and match. | Rule compliance | T | 30 min |

### P1: protect the 37 points judges test live (tonight and tomorrow morning)

| # | Task | Why | Owner | Time |
|---|---|---|---|---|
| P1-1 | **Fresh-install run with real voice.** Uninstall, install the *release* APK, go through onboarding (accessibility, restricted settings, mic), then **by voice**: T1 teach, T2, T3, T4, T12. No debug broadcasts. Fix whatever breaks. | We have never tested the mic path or first-run setup, and a judge's first minute is exactly that | H drives, C fixes | 1.5 h |
| P1-2 | **Teach flows we have never tried** the way a judge would, using only the bubble: (a) Zomato with a *different* restaurant and dish; (b) Amazon with a different query and "add the first result"; (c) one new app, e.g. Swiggy Instamart, Blinkit/Zepto, or Flipkart search → first result → cart. Replay each with a changed value. Log failures in `TEST_RUN.md`, fix the general cause (never add an app-specific rule), and re-run. | "Judges will teach a new flow live" | H + C | 2–3 h |
| P1-3 | **Second phone**, another brand on Android 13+ (a teammate's): install the release APK, set it up, one teach and one replay. Note any restricted-settings, battery or autostart steps in the README. | Judges may not use a Samsung | T + C | 45 min |
| P1-4 | **B1 on the phone (+3):** while teaching, take and decline an incoming call. The call's taps must not appear in the saved flow. Screenshot the Flow Inspector. | The last unverified bonus | H + T | 15 min |
| P1-5 | **T10 logged out** (if an account can safely be logged out and back in, e.g. a spare Amazon account): repeat T2 and confirm *"Your turn: please log in"* within 30 s, and that T14 then says "No…". | Hindi can't be tested on Zomato; logging out is the other judge option | H | 20 min |

### P2: stand out (tomorrow late morning, only once P0 and P1 are green)

| # | Task | Why | Owner | Time |
|---|---|---|---|---|
| P2-1 | **Let judges add their own Gemini key in the app** (a Settings field, stored on the phone, never committed). With a key, loose paraphrases match directly and the inspector shows *"matched by: Gemini"*. Without one, it works as now. Keep it advisory (the LLM suggests; local rules decide and safety gates everything). | Answers the "GenAI" question without a backend and without putting a key in the APK | C | 1.5 h |
| P2-2 | **Scorecard at the top of the README:** one table of T1–T14 and B1–B3 → ✅/⚠️ → a link to the evidence row in `TEST_RUN.md` and the unit test. Add a 3-step "judges' quick start" (install → enable → say the T1 sentence). | Judges skim, and the scorecard shows everything at once | C | 30 min |
| P2-3 | **Update `docs/TEST_MATRIX.md` to the official IDs and phrases**, mapping each test to classes and test methods. SaySo has this; we should too. | Traceability | C | 30 min |
| P2-4 | **Cropped screenshots as evidence** in `docs/img/`: the cart showing Farmhouse, qty 2, the Work address switch (crop out the address text), the hand-off bubble, the Myntra bag. **No personal details visible.** | Proof beats claims | H captures, C crops and links | 45 min |

### Code freeze and ship (30 Sept)
- **12:00: code freeze.** After that, only fixes to tests that fail on the phone.
- Build the release APK:
  - check `GEMINI_API_KEY` is absent from `local.properties` (the count must be 0);
  - bump the version;
  - run all core tests.
- Re-run T2, T4, T9 and B2 on the phone and empty the carts.
- After Harsha's go-ahead: merge into `main`, tag, release (P0-3).
- The team submits the form before the deadline, with a buffer of at least 3 hours.

## 4. What we will not do

- **Train or fine-tune an LLM.** It's impossible by tomorrow, and it wouldn't score more on this rubric (see the chat of 29 Sept).
- **Add app-specific rules for the judges' likely apps.** Every fix must be general, because a judge-taught flow will expose anything hard-coded.
- **Refactor, or move to Compose or Room.** They carry risk and no points.
- **Use on-device Gemini Nano.** It's unverified on our phone, and there's no time.

## 5. Scoring estimate

| Area | Points | Our status | Risk |
|---|---|---|---|
| T1–T9 | 40 | All pass on our phone | **High, if a judge teaches an app or flow shape we never tried** (P1-2 reduces this) |
| T10 | 5 | Stuck case passes; logged-out handled in code, unit-tested | Medium |
| T11 | 5 (−10) | Never tapped pay; GestureSafety | Low |
| T12–T14 | 8 | Pass | Low |
| B1 / B2 / B3 | 3 / 4 / 3 | Unit only / phone ✅ / phone ✅ | B1 until P1-4 |

If P0 and P1 are done, a realistic score is 60–70 out of 70. That should be comfortably top-15 **as long as the submission is complete and the repo is readable**. That's why P0 comes first.
