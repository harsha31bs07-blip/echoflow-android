# Judge-style audit (30 Sept 2026, overnight)

EchoFlow was checked the way a Theme 3 judge would meet it: the first run, every screen on the phone, the rubric, and a code pass for crash risks and races. Each finding has a severity and a status, and fixes are made in severity order. The phone was a Galaxy S24 FE, running the branch build.

Severity: **High** can fail a judge's test or leave the UI stuck · **Medium** makes EchoFlow look unfinished or confusing · **Low** is polish.

| # | Sev | Finding | Status |
|---|---|---|---|
| H1 | High | Pressing ✕ on the listening panel and tapping the handle again within about 1 s left nothing on screen while the mic was on: the old session's delayed close shut the new panel, and the bubble stayed hidden for it. | ✅ Fixed (235045a), checked on the phone |
| H2 | High | Starting to listen while already listening runs two listening sessions at once. When the older one ends it resets the state ("I didn't catch that", mode Ready) while the newer one is still listening. | ✅ Fixed (see log) |
| H3 | High | Found in the overnight regression run: on Amazon's product page the scroll swipe (down the middle of the page) started on top of EchoFlow's own floating panel, which is taller since the redesign, so the page never moved and T9 stopped at "couldn't find Add to cart". A fallback centre-tap could land on the panel the same way. | ✅ Fixed: the panel lets touches through while EchoFlow performs a gesture; T9 passes again on the phone |
| M1 | Medium | The AI helper can be asked during a slow loading screen (Zomato's "Step back. Grab a snack." interstitial). That's one wasted call (about 4 s) and some quota. It should wait longer, and skip screens that show a progress spinner. | ✅ Fixed |
| M2 | Medium | Flow Inspector shows internal field names: *Type item into "rs search"* (Amazon's view id). It should say *the search box*. | ✅ Fixed |
| M3 | Medium | Flow Inspector footer: *"Recorded 1 actions, kept 4 steps"*, a grammar slip that also seems to contradict itself. The extra steps came from the spoken command, because Amazon doesn't report those taps. | ✅ Fixed |
| M4 | Medium | Flow cards read *"Order an **item** pizza from **restaurant** on Zomato"*: accurate, but form-like. The taught values (still highlighted as changeable) read far better. | ✅ Fixed |
| L5 | Low | The floating panel draws over the notification shade and Quick Settings (seen in Samsung's tile editor). | ✅ Fixed: the bubble steps aside while a system window covers the screen (shade, lock screen), and comes back when it closes |
| L1 | Low | A long flow's steps are a flat list. A June 2026 study found that people read demonstrations better grouped into named phases. Group them: *Open the app → Find the restaurant → Pick the dish → Checkout*. | ✅ Done |
| L2 | Low | The bubble folds and unfolds, and the glow switches on and off, without any transition. Add short fades (respecting "Remove animations"). | ✅ Done |
| L3 | Low | No haptic feedback on the main buttons. Add a light tick on speak, ✕ and Done. | ✅ Done |
| L4 | Low | The only ways to start are the handle and the app. Add a Quick Settings tile, "Talk to EchoFlow", in the notification shade. | See log |

## Rubric walk-through (current build)
- **T1–T14, B1–B3:** all shown on the phone (see [TEST_RUN.md](TEST_RUN.md)). Nothing tonight changes matching, compiling or safety, apart from H2 (listening only) and M1 (AI-helper timing, which only applies with a key).
- **Weak spot to rehearse, not code:** Brik Oven (the stand-in restaurant) is closed at night. Record the demo during its opening hours.
- **Safety:** unchanged. Every change tonight is UI, listening, or the timing of when the AI helper is asked.

## Research notes
- [How Should Agents Read Demonstrations? (arXiv 2606.20978)](https://arxiv.org/abs/2606.20978): hierarchical, named subgoals beat flat action logs (76.7% → 90.7% on vaguely worded tasks). This led to L1.
- [EchoPath (arXiv 2609.16635)](https://arxiv.org/abs/2609.16635): recordings kept as reusable "callable memories". Replay rebinds only the declared inputs and does bounded repair instead of replanning, cutting token cost by a median 90%. It's the same philosophy as EchoFlow (deterministic replay; the AI helper as bounded repair), so it's worth citing in the deck.
- [SkillDroid (arXiv 2604.14872)](https://arxiv.org/abs/2604.14872): already the basis of EchoFlow's matcher and replay.
- Voice UX guides ([Eleken](https://www.eleken.co/blog-posts/voice-ui-design), [Fuselab](https://fuselabcreative.com/voice-user-interface-design-guide-2026/)): caption every voice response (EchoFlow already shows everything it says), handle silence gracefully, and prove quickly that the words were understood. The listening panel's live words do this.
- Demo advice ([Devpost](https://info.devpost.com/blog/6-tips-for-making-a-hackathon-demo-video)): one polished core feature beats five half-working ones. Open with the overview.
