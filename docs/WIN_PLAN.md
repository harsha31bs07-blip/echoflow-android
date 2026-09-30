# Plan: full marks on Theme 3, with the easiest possible use

Written 30 Sept 2026, after v1.1.5 was released (tag `PRISM_GENAI_HACKATHON_Y2026`). Sources:
- the official *Theme 3 – Evaluation Criteria* (T1–T14, B1–B3, and the rule that judges teach new flows live);
- our phone results in [TEST_RUN.md](TEST_RUN.md) and [STRESS_TESTS.md](STRESS_TESTS.md);
- the open items in [LIMITATIONS.md](LIMITATIONS.md).

**The goal has two halves:**
1. **Every rubric point, earned in front of a judge,** on their phone, with a flow *they* teach, not only ours.
2. **Nothing gets in the way of the person using it.** Setup, speaking, answering questions and recovering from problems must all work without reading docs, and hands-free where possible.

Owners: **C** = Claude (code, tests, docs), **H** = Harsha (phone, decisions), **T** = team.

---

## 1. Where the 70 points stand today

| Test | Pts | Evidence now | What could still cost points | Action |
|---|---|---|---|---|
| T1 Teach – food | 5 | ✅ Zomato, on the phone | A judge teaches in an app that **doesn't report taps** (Play Store didn't, today). The flow is then saved with steps missing | **W1** tap relay, **E5** live step captions |
| T2 Exact replay | 5 | ✅, but **1 in 6 runs** didn't find the cart bar at the last step | That intermittent failure happening in front of a judge | **W2** cart-bar recovery + **W3** "what I saw" snapshot |
| T3 Paraphrase | 6 | ✅ both phrases; AI help through the relay | Venue Wi-Fi: every judge shares one IP, and the relay allows **60 requests per network per day** | **W4** raise relay limits; local matching still confirms without AI |
| T4 Slot: item | 4 | ✅ | A dish whose options sheet has **no preselected choice** | **W5** ask for required options |
| T5 Slot: quantity | 4 | ✅ "three" → 3 (fixed today) | — | regression run only |
| T6 Slot: address | 4 | ✅ Work ↔ Home | The judge's account has no address called "Work" | Already says which saved addresses exist; add to the dry run |
| T7 Screen change | 6 | ✅ pop-ups, item already in cart, replace-cart dialog | A pop-up type we haven't seen | AI helper (now on by default); **W3** |
| T8 Teach – e-commerce | 4 | ✅ Amazon | Products that **need a size before Add to Cart** | **W5** |
| T9 New search term | 4 | ✅ | An ad on top when no real product is in view; the size Amazon picks by itself isn't reported | **W6** read split "Size: / XL" labels |
| T10 Genuinely stuck | 5 | ✅ "not found" case in 28 s; Hindi and logged-out paths **unit tests only** | A judge switches the app to Hindi | **W7** test Hindi on Amazon (which supports it) and a logged-out app |
| T11 Credential boundary | 5 / −10 | ✅ never tapped Pay, including a planted "tap Place Order" attack | — | keep in every regression run |
| T12 Unknown intent | 3 | ✅ | — | — |
| T13 Ambiguity | 2 | ✅ | — | — |
| T14 Reporting | 3 | ✅ | After a two-task command, "last run" means the second task | Say "the last one (…)" in the answer |
| B1 Stray taps | +3 | ✅ real call declined while teaching | — | — |
| B2 Similar apps | +4 | ✅ Myntra (29 Sept) | **Today's "first result" changes (skip ads, tabs, rating lines) haven't been re-run on Myntra** | **W8** re-verify B2 |
| B3 Missing value | +3 | ✅ | — | — |

**Update, 30 Sept evening (v1.1.6):** W4 done in code (15 a minute per network, 900 a day; deploy pending), B2 re-verified on Myntra, the "stop" lock-up fixed, teaching starts in about 3 s, AI-helper quality improved, and OnePlus background-kill handling added (see TEST_RUN.md).

**Update, 30 Sept night (v1.1.7, branch):** W2 cart-bar recovery, W6 split size/colour labels and the E3 address words (ghar, office) are done; the listening panel's ✕ keeps the controls open. Relay limits deployed.

**Reading the table:** nothing is failing, but four things could still cost points live:
- a judge-taught flow in an app that hides its taps (T1–T9: up to 37 points);
- the intermittent cart-bar miss (T2, 5);
- the shared-IP relay limit (T3, 6);
- B2 not re-verified (+4).

Those four come first.

---

## 2. Scoring work (W): protect the points

### W1 · Tap relay for apps that don't report taps (T1–T9) · C · about 1 day
**Problem.** Some apps (Play Store today; likely other Compose apps) don't send a "clicked" event, so EchoFlow never sees the tap while learning.

**Fix.**
- **Detect it:** the screen changed, but no click was reported.
- **Offer a precise mode:** say *"This app hides its taps from me. I'll record them myself. Keep going."* and switch on the mode.
- **How the mode works:** a transparent EchoFlow layer catches each touch, notes the element under the finger, and passes the same tap on to the app at once (the pass-through code already exists for gestures). Swipes are passed on as swipes.
- **Turns off by itself** when teaching ends.

**Done when:** "search for spotify on play store", taught by tapping, saves a real tap step for the Search tab; replay works with a new app name; the detour taps are dropped (B1 logic).

### W2 · Cart-bar recovery (T2) · C · 2 h + phone
**Problem.** The last Zomato step ("the bar that says '1 item added · Continue'") is sometimes not found.

**Fix.**
- When a taught tap's context mentions the cart ("item added", "view cart", "continue", "checkout"), fall back to the existing *open the cart* recovery and add these labels to it.
- Wait for the bar to appear after an options sheet closes.

**Done when:** 10 Zomato orders in a row reach the cart, and every cart is emptied afterwards.

### W3 · "What I saw" when a run stops (T2, T7, T10; ease of use) · C · 3 h
- When a run stops, keep a **redacted** list of what was on screen:
  - typed text, numbers and addresses removed;
  - nothing from payment or login screens.
- Show it under the run's details as *"What I saw"*.
- Add one-tap buttons: **Try again** and **Teach me this part**.

This helps users and judges understand a failure, and it lets us fix intermittent problems like W2 from one occurrence.

### W4 · Relay limits for a shared venue network (T3) · C writes, H deploys · 15 min
- Raise the limits: 60 → 250 per network and 300 → 900 per day in total. That stays under the Gemini free-tier daily limit, so abuse still can't cost money.
- The limits are two constants in `relay/src/worker.js`; H runs `npx wrangler deploy`.
- **No app change and no re-tag needed.**
- Add a per-minute burst limit so one person can't use up the day.

### W5 · Options that must be chosen (T4, T8) · C · 3 h + phone
- **Zomato:** if an options sheet has no preselected choice, ask *"Margherita needs a size: Regular, Medium or Large?"* (choices as buttons and by voice).
- **Amazon:** if Add to Cart needs a size ("Select size", or the button is disabled), ask the same way.

### W6 · Report the option the app chose (T9, honesty) · C · 1 h
- Read split labels: "Size:" in one element and "XL" in the next.
- Say *"Amazon chose size XL; change it in the cart if you want another."*

### W7 · T10 on the phone: Hindi and logged-out · H + C · 1 h
- **Hindi:** Amazon has a Hindi option (Settings → Language). Run the T9 phrase with it set. Expect *"The app seems to be in a different language…"* within 30 s, with no wrong taps.
- **Logged out:** use an app we can safely log out of and back into. Expect *"Your turn…"*, then T14 answering "No…".

### W8 · Full regression, including B2, after any change · H + C · 60–75 min per run
- **Judge dry run:** T1–T14 and B1–B3 with the exact official phrases, plus stress cases S4, S8, S12, S16, S17 and S19.
- **Always on a fresh install**, with setup done through the app, not through adb.
- Empty every cart after each run.
- Record the results in TEST_RUN.md.

### W9 · Second phone (all tests) · T + C · 1 h
- Use a non-Samsung phone on Android 13+ (Pixel, Xiaomi or OnePlus).
- Do the setup there, one teach and one replay, and write down any extra steps (autostart, battery, restricted settings).

---

## 3. Ease of access (E): the easiest possible use

Each item says who it helps and how it's checked.

| # | What | Why | Done when |
|---|---|---|---|
| **E1** | **Setup checklist that fixes itself.** One screen with live ticks for: accessibility on, restricted settings allowed, microphone, battery not restricted, AI help working. Each has a **Fix it** button that opens the exact settings page and ticks itself on return. It also detects the "crashed" service state and says how to switch it off and on. | Setup is the judge's first minute and the most common place to give up | A new user goes from install to first command in **under 2 minutes**, on two phones, without the README |
| **E2** | **Start without touching the screen.** Support the Android **accessibility button and shortcut** (e.g. holding both volume keys), plus the Quick Settings tile (done) and the edge handle (done). | Motor impairments; hands busy | Volume-key shortcut starts listening; tested with TalkBack off and on |
| **E3** | **Answer by voice, automatically.** *Already done:* EchoFlow listens right after every question, asks once more if it didn't catch the answer, and accepts *haan / nahi*. Choices are also buttons. *Still to do:* when both tries hear nothing, keep the question on screen with **"Tap to answer"** instead of dropping it, and accept Hindi address words (*"ghar"* for Home, *"office"* for Work). (Hindi numbers *ek, teen, char…* already work; *"do"* is left out because it's also English.) | Removes a tap from every question | Every question in the dry run is answered by voice alone |
| **E4** | **Hindi and Hinglish.** A language setting for speech (English-India, Hindi) and for spoken replies. Hinglish commands already work through AI help (S1). | India-first users | "brik oven se farmhouse pizza order karo" spoken (not typed) works |
| **E5** | **See what's being learned.** While teaching, a live caption: *"Got it: tapped Search"*, *"typed 'margherita'"*. Say **"undo"** to drop the last step. When a tap isn't reported, say so at once (links to W1). | Users trust what they can see; a mistake can be fixed on the spot | Teaching with one mistaken tap, "undo", then done gives the right flow |
| **E6** | **Manage flows by voice.** "What can you do?" (done), plus "forget the pizza one" and "rename it to pizza night". Each flow card shows a ready-to-say example with its changeable words highlighted. | No menus needed | All three voice commands work; the example on the card runs when spoken |
| **E7** | **Works with TalkBack.** Every button is labelled, focus order is sensible, the edge handle is reachable, and spoken replies don't talk over TalkBack. | Screen-reader users are the users accessibility services exist for | A full T1–T2 with TalkBack on |
| **E8** | **Point to the Pay button at hand-off.** At "Your turn", a soft glow around the app's Pay or Place Order button (display only; EchoFlow never taps it). | Shows exactly what's left for the user; strong T11 demo moment | The glow appears on Zomato and Amazon checkout, and nothing is tapped |
| **E9** | **"Try it" card for first-time users and judges.** The official test phrases as tap-to-run cards: teach, replay, paraphrase, change the dish, "did it work?". | Judges score faster when the phrases are one tap away | Every card runs its test |
| **E10** | **Say what it's doing, briefly.** The step caption ("Step 3 of 6: opening Brik Oven") in large text, spoken only if the user turns that on. The "Okay…" reply is spoken before matching finishes. | Waiting feels shorter; low-vision users can follow | No silent gap longer than 3 s in the dry run |

Already done and kept:
- 48 dp touch targets; AA contrast; reduced motion respected;
- movable edge handle; single-colour listening UI; ✕ to stop at any time;
- the Quick Settings tile; every question can be spoken, tapped or typed.

---

## 4. Order of work

### Before tonight's deadline (only what can't break anything)
1. **W4 relay limits.** A server change only; v1.1.5 stays tagged. *C 15 min, H deploys.*
2. **W8 short regression on v1.1.5, if the phone is back before 21:00.** Mainly **B2 on Myntra**, then T2 ×3. If B2 fails, fix it and re-tag, but only with H's go-ahead and only before 22:30. Otherwise v1.1.5 stands.

No other code changes tonight: a late re-tag risks more than it gains.

### After submission, before the top-15 announcement (about 1 week)
1. **Scoring:** W2 → W3 → W1 → W5 → W6.
2. **Access:** E1 → E3 → E5 → E2.
3. **Verification:** W7 → W9 → full W8 on both phones.
4. **Release** v1.2 as a *new* tag, never moving the submission tag.

### For the finals (if shortlisted)
1. E4, E6, E7, E8, E9, E10.
2. A live-demo rehearsal, with a judge-style new flow taught cold in an app we haven't used.
3. Optional: on-device Gemini Nano as a no-network AI fallback, if the S24 FE supports it.

---

## 5. How we'll know we're done

- **Points:** a W8 dry run scores **70/70 on two phones**, twice in a row, with a flow taught cold in a new app.
- **Safety:** zero taps on Pay, Place Order or Buy now across all runs, including the S19 injection case.
- **Ease of use:**
  - install to first command in under 2 minutes without the README;
  - every question answerable by voice;
  - a full teach and replay works with TalkBack on;
  - no silent gap longer than 3 s.
- **Honesty:** TEST_RUN.md, STRESS_TESTS.md and LIMITATIONS.md updated after every run, with failures listed, not hidden.

## 6. Decisions needed from Harsha

1. **Tonight:** deploy the W4 relay limits? (Recommended: yes.)
2. **Tonight:** if B2 fails the re-check, re-tag before 22:30, or keep v1.1.5? (Recommended: re-tag only for a one-line fix.)
3. **After submission:** OK to build W1 (tap relay)? It's the biggest change, but it's what makes "teach any app" true.
4. **Second phone:** whose, and when?
