# EchoFlow demo V8: plan for the final hackathon video

Date: 5 October 2026. **Status (6 October 2026): delivered; see [§11](#11-what-was-delivered-6-october-2026).** The current submission is the V6 video linked from the README. V7.1 (`demo-production/final-v7/`) is the fallback.

## 1. Why V8, and what "perfect" means here

V6 and V7 have problems that editing can't fix:

1. **They break the stated rules.** Per [FINALS_PLAN.md](FINALS_PLAN.md) and [DEMO_SCRIPT.md](DEMO_SCRIPT.md), the rules ask for **one unedited video, at most 5 minutes**, showing (a) teach → (b) exact replay → (c) paraphrase → (d) changed value → (e) asking when stuck, **in that order**. V6 and V7 are cut together from recorded sections ("Edited for brevity").
2. **No real voice.** The commands were synthesized speech fed straight into Android speech recognition. Judges are scoring a *voice* assistant, and the video has to say so in a disclosure.
3. **The app's own voice sounds wrong.** `TtsPolicy` (app) forces **every** reply to **1.38× speed** and looks for a "Prabhat" voice. Prabhat is a Microsoft Edge narration voice that Android's speech engines don't have, so the app actually speaks with the engine's default voice, rushed by 38%. The narrator was also at +38%.
4. **Privacy strips over baked-in footage.** The masks are part of V7's only copy, and the home-screen address was never masked.

So "perfect" here means:
- **one continuous real take** of the release app;
- **a real person** speaking the commands;
- **a natural app voice**;
- narration only in the gaps;
- readable zooms and captions added *on top of* the uncut take: no cuts, no speed changes;
- addresses blurred neatly;
- **at most 5:00**.

V7.1's presentation work (large phone, focus zooms, lesson tracker, speaker captions) carries over. It will run on sharp 1080-pixel footage instead of V7's 568-pixel copy.

## 2. Phase 0: rules check (you, about 15 min, before anything else)

Open the official rules PDF and confirm three things:
- whether a **voiceover** and **on-screen captions or graphics** may be added to the unedited take (normally yes; the take itself must not be cut);
- the **maximum length** (5:00);
- **how the video is submitted** (GitHub link, YouTube, or Drive), plus any size or format limit. GitHub's own video attachments are size-limited, so plan on an unlisted YouTube link, or a ≤100 MB 1080p file as a backup.

If overlays turn out to be forbidden, V8 is the raw take plus narration only, and §6's graphics are dropped.

## 3. Phase 1: fix the app's voice (code, 1–1.5 h including phone checks)

| Step | Change | How it's verified |
| --- | --- | --- |
| 1.1 Audit | Connect the phone, build debug, read `EchoVoice` logcat. `TtsPolicy` already logs every engine and voice. Record 30 s of phone audio during a replay with scrcpy, and list every sound: TTS, recognizer start/stop beeps, Zomato sounds, notifications. | A list of what is "off", based on the recording rather than guesses. |
| 1.2 Speed | `RATE` 1.38 → **1.0** (natural). Add **Advanced → Speaking speed** (0.9 / 1.0 / 1.15, default 1.0) with a **Test voice** button. | A reply played at each setting; the rate shows up in the log. |
| 1.3 Voice | Drop the Prabhat lookup. Prefer an **offline, high-quality en-IN voice** (`quality ≥ QUALITY_HIGH`, no network needed). If there isn't one, prefer en-US/en-GB high quality; otherwise use the engine default. Install/enable **Speech Services by Google** on the phone and make it the default engine; its offline Indian-English voices are much more natural than Samsung's default. | `actualVoice=` in the log; a listening test on the phone speaker. |
| 1.4 Overlap | Speak with `USAGE_ASSISTANT` and ask for transient duck focus, so Zomato's sounds or the recognizer's beep never talk over a reply. If the beeps from 1.1 sound harsh, mute the recognizer's start/stop tones around listening. | No overlaps in the 1.1 recording. |
| 1.5 Privacy | Spoken transcripts are logged at Info level in release builds: limit them to debug builds. | Release logcat shows no transcript text. |
| 1.6 Release | Unit tests and lint; release build **v1.2.3** (key check: no `AIza` in the APK). Then a GitHub release, and the tag moves only with **your OK** (§9). | 193+ tests green; APK installed on the phone. |

**Why this goes first:** the judges install the release APK, so the video must show the voice they will hear.

## 4. Phase 2: set up the phone and recording (about 1 h)

**Phone.** Galaxy S24 FE over USB (wireless adb as a backup), with the release v1.2.3 installed.
- Do Not Disturb on; notifications hidden; battery above 60% and charging; brightness fixed; animations at the normal setting.
- Zomato: logged in, **cart empty**, Brik Oven open (check its hours). The pizza lesson is forgotten so (a) teaches from scratch.
- Saved address: use the **"Work"** label, or a non-personal address that's fine to show. That reduces blurring to a minimum.

**Video.** scrcpy records on the PC: native 1080×2340, 60 fps, high bitrate, phone audio captured with `--audio-source=output`.
- Stop it with `--time-limit` or a clean Ctrl+C, never a force-kill. Force-killing truncated the earlier recordings.
- Back-up recorder: Samsung's screen recorder (media sound plus mic) running at the same time, if the phone allows both.

**Your voice.** You speak the commands to the phone. A **USB or lapel mic on the PC** records your voice cleanly for the soundtrack, while the phone's own mic hears it for recognition. Recognition remains real.

**Run log.** `adb logcat -v epoch` is saved for the whole take. Every EchoFlow step, status message and spoken reply is in it with timestamps; this drives the tracker, zooms and captions in §6.

**Sync.** A clap or a "three, two, one" at the start gives one sync point between the scrcpy video, the PC mic and logcat. The software lines it up automatically.

**Optional camera.** A phone or webcam filming your hand on the phone, shown as a small picture-in-picture for authenticity. It's the same continuous moment, not an edit. Decide in §9.

## 5. Phase 3: record (about 1–1.5 h, best of 3 takes)

Script: [DEMO_SCRIPT.md](DEMO_SCRIPT.md) (a→e plus the "did the last run succeed?" report), shortened so the **real-time** run fits in 5:00:
- **Target 4:20–4:40.** No undo demonstration, and no optional extras unless a rehearsal finishes under 4:00.
- Measure one full rehearsal. If it runs over, the report question is the first thing to drop: (e) only needs the question and the "nothing" answer.
- **Three takes,** emptying the cart between them; the best one is used. A take that hits a hiccup (e.g. "I didn't catch that") is fine if it recovers quickly; honest recovery reads well.
- **After each take:** empty the Zomato cart. Never tap Pay or Place Order.
- **Takes are checked straight away,** by script: length, every step of (a)–(e) present in logcat, every reply audible. Pick the take, or record another.

## 6. Phase 4: narration and post-production (2–3 h, all on the uncut take)

| Layer | Plan |
| --- | --- |
| Base | The chosen take, **uncut and at real speed**, from start to finish. Intro and outro cards are allowed only if the rules allow them (§2), and kept to about 5 s each so the whole video stays ≤5:00. |
| Layout | The V7.1 compositor, adapted to 1080-pixel footage: large phone, chapter headings (a)–(e), and **focus zooms** on the request, search, cart quantities, Place Order and the question. The zooms are about 1.2–1.6×, so they stay sharp. |
| Lesson tracker | Driven by **logcat** instead of OCR: exact step text and times, no guessing. |
| Captions | Speaker-labelled (You / EchoFlow / Narrator). Generated with **faster-whisper** from the take's audio, checked against the logcat reply text, then corrected by hand. Matching SRT/VTT files. |
| Narration | Script written **after** recording to fit the real gaps, never over a command or a reply. Voice: a teammate, or **edge-tts en-IN-PrabhatNeural at its natural rate (+0–5%)**, not +38%. Short sentences, and only what isn't obvious from the screen. |
| Music | Optional: a quiet royalty-free bed (YouTube Audio Library / Pixabay licence) at least 20 dB under speech and ducked during speech, or none. Your choice (§9). |
| Privacy | Addresses get a **soft blur on just that text line**, tracked through zooms (OCR finds the line), rather than solid strips. Anything that doesn't need hiding stays visible. |
| Audio mix | Phone audio (app voice + Zomato) + your voice from the PC mic + narration. Loudness around −16 LUFS, true peak ≤ −1 dBTP. Listened to on headphones, laptop speakers and in mono. |

**Disclosure** (small and readable, intro and outro): "One continuous recording, no cuts · narration and captions added."

## 7. Phase 5: QA and delivery (about 45 min)

**Checklist:**
- ≤5:00;
- (a)→(e) in order;
- no cuts (frame-difference scan for jumps);
- every command and reply audible and captioned correctly;
- narration never overlaps;
- cart quantities and the payment hand-off readable at 720p;
- no address or other personal detail visible (OCR scan of every frame);
- full decode;
- loudness.

**Outputs:**
- 1440p master;
- 1080p upload copy (≤100 MB);
- SRT/VTT;
- a short QA report.

Then you update the README link and the submission form.

## 8. Tools (all free; installed only when the phase needs them)

| Already installed | To add |
| --- | --- |
| scrcpy 4.1, ffmpeg, Python + OpenCV/numpy/PIL, edge-tts, Node, Windows OCR | **faster-whisper** (captions), **pydub/soundfile** (audio alignment), optionally **Audacity** (listening) and **OBS** (camera picture-in-picture). |

The tools are open-source, from PyPI, GitHub or the vendors' own sites. Nothing needs a paid account.

## 9. Decisions needed from you

1. **Rules:** confirm what §2 asks, or share the PDF.
2. **Commands:** **you** speak them live (recommended), or a teammate.
3. **Narrator:** a teammate's voice, or Prabhat at its natural rate.
4. **App voice fix → release v1.2.3** and moving the `PRISM_GENAI_HACKATHON_Y2026` tag/release (needs your OK).
5. **Address:** a non-personal saved address shown on camera, or the personal one blurred.
6. **Music:** a quiet bed, or none.
7. **Camera picture-in-picture** of your hand: yes or no.
8. **Timing:** when the final check happens, so the phases can be scheduled. The whole plan is about 7–9 hours of work.

## 10. Risks and fallbacks

| Risk | Fallback |
| --- | --- |
| A take goes wrong (Zomato down, Brik Oven closed) | Another open pizza restaurant, with the script lines changed. Record on a different day or time if needed. |
| Recognition mishears live speech | Rehearse the phrasing; speak a little closer; the app's own "say again" recovery shows on camera and is fine. |
| The run takes longer than 5:00 in real time | Drop the report question; teach at a quicker but calm pace; no optional extras. |
| No time for the full plan | Minimum: §3.2 (speed fix) + §4 + §5 + narration and captions, without the zoom layout. Fallback: V7.1. |

## 11. What was delivered (6 October 2026)

**Rules** (Theme 3 evaluation criteria PDF): a video of at most 5 minutes showing, unedited and in order, (a) teaching one flow by voice + taps, (b) exact replay, (c) paraphrase, (d) changed slot value, (e) the assistant asking a question when stuck.

**Decisions taken by the user:** commands spoken by the same TTS as the narrator (Prabhat, natural rate); release v1.2.3 approved; the address may show; a technical music bed; no camera.

**The take.** Zomato's restaurants were closed or not delivering that night (rain), so the flow was taught in the **Domino's app**, which has a clean accessibility tree. The take is one continuous scrcpy recording (1080×2340, phone audio) on the Galaxy S24 FE, debug build of v1.2.3. Commands were synthesized (en-IN-PrabhatNeural, natural rate) and fed to Android's real speech recognizer through the debug-only supplied-audio source; the listening is started by tapping EchoFlow's handle, as a user would. Taps are shown with Android's touch indicator. Take 13 is used (see the voice fix below; take 9 was the first clean take).

| Beat | Command | What happens on screen (video time) |
| --- | --- | --- |
| (a) Teach | "Order a Garlic Bread on Domino's." → "Yes." | Asks to be taught (0:10), opens Domino's; search, Add, cart tapped by hand; Done → "Learned … I saved 4 steps" (0:51) |
| (b) Exact | same words | Replays steps 1–4, cart with Garlic Breadsticks ×1, "Your turn … I won't pay" (1:36) |
| (c) Paraphrase | "Get me garlic bread from Domino's." | Matched on the phone; "Garlic bread was already in your cart, so I didn't add another one" (2:22) |
| (d) New value | "Order two Choco Lava Cakes on Domino's." | Choco Lava Cake added, quantity set to 2 in the cart (3:12) |
| (e) Stuck | "Order a unicorn pizza on Domino's." → "Nothing." | "I searched for "unicorn pizza" but couldn't find it. What should I get instead?" then stops without adding (3:53–4:07) |
| Report | "Did the last run succeed?" | "No … stopped at step 3 of 4" (4:23) |

**App fixes found while recording** (all in v1.2.3, with tests): reply speed 1.38× → 1.0 and an offline high-quality en-IN voice; "… from Domino's on Zomato" opened the Domino's app (the app after "on" now wins); a spoken "two" heard as "to"; "from Domino's" for a lesson taught "on Domino's"; the keyboard hiding the cart bar during replays; unlabelled Add buttons and "reduce" quantity buttons; a plural item searched as singular; the reply naming the app twice; spoken text kept out of release logs.

**Post-production** (no cut, no speed change: output time = take time): V7.1 layout on the sharp take, chapter headings, a lesson tracker driven by EchoFlow's log and on-screen step messages, focus zooms with highlighted cart quantities and the Pay button, speaker-labelled captions (36 cues; SRT/VTT supplied), 11 narration lines placed only in measured silences (checked: no overlap with commands or replies), V7's animated title cards over the idle first and last seconds. Music: "Inspired" by Kevin MacLeod (CC BY 4.0; credit in `CREDITS.txt`), ducked under all speech.

**Checks:** 4:36 (275.8 s), 2560×1440 30 fps H.264, 48 kHz AAC; full decode clean; music mix −17.1 LUFS, no-music −16.0 LUFS, true peak −2.0 dB; frame timing verified against the raw take at the render joins. Files in `demo-production/final-v8/` (videos are git-ignored; scripts in `build/`).

**Voice fix after review (6 October).** The user heard EchoFlow's own voice as "weird and whisper-like". Measured on the take, its pitch was about 53 Hz with few voiced frames: the phone's system text-to-speech pitch was set to 25% (`tts_default_pitch=25`), and EchoFlow set its rate but not its pitch, so it inherited that. EchoFlow now always sets normal pitch (`TtsPolicy.PITCH = 1.0`), so the phone setting can't distort it; the phone's setting was also reset to 100% with the user's go-ahead. The demo was re-recorded (take 13: app voice about 140 Hz, normal) and the edit re-timed onto it automatically from matched events (`build/retime.py`, 60 anchors; narration re-checked with no overlaps). The same take also fixed the "what can you do" reply, which read the raw template ("order a {item} on dominos on Domino's"); it now says "order a garlic bread on dominos (you can change the item)".
