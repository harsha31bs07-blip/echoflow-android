# EchoFlow V7: researched demo improvement plan

Date: 4 October 2026. **Status: plan only; no video editing, rendering, recording or tool installation started.**

> **Cleanup, 5 October 2026:** the V7 video was delivered (music and no-music versions, 4:15, technical checks passed; visual review still pending) and is kept in [`demo-production/final-v7/`](../demo-production/final-v7/) with its SRT/VTT captions. At the user's request every other demo file was deleted: V1–V6 videos, raw recordings, audio stems, build scripts and review records. Links below to `demo-production/output/` or `audio/` are historical and no longer resolve, and V7 can't be re-rendered from source.

## 1. Recommendation and scope

Make the next version easier to understand and verify. V6 already has a consistent visual style, genuine recorded results and technically validated exports. Its largest remaining weaknesses are a small native phone view, competing explanations, repetitive navigation and a very brief changed-quantity result card. More decoration alone will not resolve these.

Keep the user's choices: a motion-graphics intro before the screen recording, the Samaya-inspired pale/navy/blue/orange palette, narration as bottom captions, Prabhat narration at **+38% applied once**, genuine command/phone speech and restrained technical background music. Preserve the current V5 and V6 exports.

“Industry ready” here means a polished, understandable demo with auditable evidence and verified delivery quality. It does not certify the app's production reliability or establish performance across other apps.

## 2. Online benchmark comparison

The online review used official published demo pages and transcripts. The local review used V6's rendered frames, contact sheet, captions, timeline and delivery records. Competitor video playback and listening were not performed, so the comparisons below concern their published story structure and stated outcomes, rather than measured editing, sound quality or animation quality. Recommendations are editorial judgments drawn from those sources.

| Reference | What the published demo establishes | Comparison with EchoFlow V6 | Decision for V7 |
| --- | --- | --- | --- |
| [Browser Use: DoorDash grocery cart](https://browser-use.com/showcase/add-groceries-to-cart) | A specific shopping request, additions to a real cart and a stop before checkout. The page labels the demo 39 seconds; this is a clip length, not a verified task runtime. | This is the closest shopping-automation comparison. EchoFlow needs more time because it also demonstrates teaching and several reuse cases, but its repeated route can obscure what each case proves. | Give each case a clear request and visible native outcome. Condense only verified waiting/repetition; preserve all required proof. Do not infer relative agent speed from clip lengths. |
| [Google DeepMind: Project Mariner](https://www.linkedin.com/posts/googledeepmind_project-mariner-demo-taking-action-in-your-activity-7272696947180818432-tDRG) | Its transcript sets a concrete research-to-shopping goal, explains decisions as the agent works, then asks about checkout and returns control. It explicitly identifies the system as a research prototype. | EchoFlow also has a real cart handoff and a stronger teaching/reuse story, but the opening emphasizes general capability before that benefit becomes concrete. | Open with a plain user benefit. Connect explanations to visible evidence, and make the checkout handoff an intentional payoff. Retain accurate capability/input disclosures. |
| [Google DeepMind: Project Astra London demo](https://www.linkedin.com/posts/googledeepmind_project-astra-exploring-the-future-capabilities-activity-7272657815184322560-1h__) | The transcript presents practical spoken exchanges, returns to an earlier remembered door code at the end, and describes minimalist music. This is an adjacent phone-assistant benchmark, not the same automation task. | EchoFlow has authentic recognition and phone replies, but its external caption track covers narration and supplied requests rather than all audible app replies. | Let the actual request/reply exchange carry the demo. Caption the real replies, keep music secondary, and close by restating what the demonstrated lesson achieved. |
| User's local Samaya explainer | Sampled frames show a light palette, technical diagrams and conventional bottom narration captions. This is a visual reference rather than an automation benchmark. | V6 already follows this direction. | Refine hierarchy, spacing and motion within the existing palette instead of changing the visual identity again. |

The shared pattern worth adopting is **clear goal → visible action → inspectable result → user control**. This is a synthesis of the reviewed examples, not a universal industry rule.

## 3. Evidence-based priorities

All timecodes below refer to the delivered V6 export.

| Priority | Observed V6 issue | Planned change | Acceptance evidence |
| --- | --- | --- | --- |
| P0 | Native phone width is 590 of 2560 pixels, approximately 23%. At 720p playback it becomes 295 pixels wide. | Alternate full-phone context with focused crops of the actual search, saved lesson, cart and report. Remove competing left-side copy. | Important item names, quantity and stopping point readable at 720p without pausing or player zoom. |
| P0 | The Farmhouse checkout result card lasts about **0.50 seconds**, 03:45.719–03:46.217, largely during its fade. | Give the real cart a 2–3 second readable proof beat. Highlight the actual Farmhouse quantity 2 and preserve the existing Margherita quantity 1 context. | A viewer can identify the changed item and count from the native UI. An annotation supplements the UI; it never substitutes for proof. |
| P0 | Teaching lasts 77 seconds after the seven-second intro; exact replay begins at 01:24.045. | Make the opening promise concrete and map the teaching/replay actions before cutting waits. | The viewer understands the use case in the first ten seconds; the saved lesson remains visible and complete. |
| P1 | Chapter/detail text, the persistent “IN ACTION” card, request cards, native overlay and subtitles sometimes compete. | Remove the persistent explanation card. Use one brief chapter label and one useful annotation at a time, alongside speech captions. | No simultaneous duplicate explanation across title, card and narration. Captions never cover cart counters or important controls. |
| P1 | The SRT has 29 cues for narration/supplied requests; audible app replies lack complete external captions. | Add accurate bottom captions for the retained real EchoFlow replies, with concise speaker labels when needed. | All intelligible speech captioned and checked against the audio, with no invented or shortened-as-verbatim replies. |
| P1 | The input/editing disclosure is 18px at 1440p, effectively 9px at 720p. | Include a readable opening/end disclosure and a compact label during the demo. | Viewers can read “Recorded sections; prerecorded speech supplied to Android ASR.” No claim of verified acoustic microphone input or a continuous take. |
| P2 | Loudness and sync passed numerical checks; a real listening pass has not been completed. | Check the revised mix through headphones, laptop speakers and mono playback. | Complete commands/replies are clear, the +38% narrator remains understandable, and music never masks speech. |

Five speech-free intervals are review candidates: **00:44.454–01:11.346**, **01:41.794–01:59.919**, **02:25.428–02:41.321**, **03:18.063–03:34.238**, and **03:58.237–04:18.408**. These are not proven empty footage: teaching and agent actions occur within them. Map meaningful actions first, then cut waiting only where it does not hide execution or alter the outcome.

## 4. Submission rules and proposed story

[DEMO_SCRIPT.md](DEMO_SCRIPT.md) describes a five-minute limit, a continuous unedited take and a required teach → exact replay → paraphrase → changed value → clarification order. It is a local script, not an independently verified organizer rulebook. V6 already uses recorded sections and disclosed cuts. Before calling V7 submission compliant, obtain/check the actual applicable organizer rules.

The default story keeps that scenario order and keeps the motion intro before the screen recording. If cuts are permitted, aim for approximately **4:10**, adjusting to retain complete genuine speech and evidence. This is a provisional editing budget, not a promised duration:

| Proposed time | Beat | What must survive the edit |
| --- | --- | --- |
| 00:00–00:06 | Motion intro | Brand plus the concrete promise: “Teach a phone task once. Reuse it with new words and values.” Readable input/editing disclosure. |
| 00:06–01:11 | Teach | Actual teaching request, recording acknowledgement, necessary native actions, Done and saved lesson. |
| 01:11–01:55 | Exact replay | Actual request/recognition, meaningful execution milestones, real cart and complete payment handoff. |
| 01:55–02:31 | Different wording | Actual paraphrase, observed reuse and existing-item/duplicate-prevention proof. Explain only the demonstrated case. |
| 02:31–03:16 | New values | Actual Farmhouse request, real quantity change, native cart with Farmhouse 2/Margherita 1 and readable handoff. |
| 03:16–03:50 | Clarification and control | Unavailable-item evidence, complete clarification question, actual “Nothing” request and genuine halt. |
| 03:50–04:04 | Honest report | Actual report request and full response accurately describing the halted run. Extend this budget if the complete exchange requires it. |
| 04:04–04:10 | Close | Three demonstrated benefits, final disclosure and a clean EchoFlow brand end frame. |

Keep all retained command/reply speech at its real speed. If navigation is accelerated, label the accelerated interval and do not imply real-time performance. If a native result needs a freeze hold, label it “Paused for clarity.” The held frame must be from the actual observed result.

If important evidence cannot fit the target, keep a longer cut within the verified submission limit rather than sacrificing proof. If the actual rules prohibit editing, stop the submission-cut branch and plan a new continuous compliant capture; the existing edited exports cannot be turned back into an unedited take. A separate 90–120 second promotional cut is optional only if wanted later, not a replacement for required judge evidence.

## 5. Visual, caption and audio treatment

**Native footage and motion.** Use three visual states: full-phone overview, a close-up of the actual relevant UI region, and a result view combining phone context with that same frame's magnified proof. Portrait geometry limits how large a whole phone can fit in a landscape canvas; solve readability with focused views, not by stretching the phone. Keep enough context to distinguish real cart rows from menu recommendations.

Use motion to guide attention: a gentle crop transition into a real control/result, a short outline on an observed quantity, and a restrained chapter transition. Reveal success annotations only after the native result. Do not add simulated recognition, synthetic UI states or decorative activity indicators that imply unrecorded behavior. Keep privacy masks attached to the underlying footage through every crop and transition.

**Palette and text.** Keep pale backgrounds, navy primary text, blue navigation emphasis and orange value-change emphasis. Use consistent typography, margins and spacing. Maintain the requested bottom-caption format; narration will not return to an explanation box. Use a reserved caption lane, a two-line target and readable speaker identification. Validate contrast and text size on downscaled delivery frames rather than treating font size alone as proof of readability.

**Narration.** Replace redundant tap descriptions with short explanations of the observed benefit. Draft around the actual exchanges before generating anything. Suggested opening: “Teach your phone one shopping task. Then ask again with different words or new values.” Suggested handoff explanation: “Here, EchoFlow prepares the cart and leaves payment to you.” Avoid universal claims such as working across every app or never failing. Keep **en-IN-PrabhatNeural at +38%, applied once**; reuse existing clips where they fit and regenerate only revised lines.

**Captions.** Build a structured, speaker-aware timing master from the retained requests, narration and genuine TTS logs, then derive burned-in captions and SRT/VTT. If rendered through Remotion, use its `Caption` JSON structure, with separate speaker metadata. Cross-check text against actual audio and recognized words; a supplied request and an ASR result are different evidence and should not be conflated.

**Audio.** Rebuild the mix from independent lossless stems, not the mixed MP4 audio. Remap command/device timing with the video edit; narration cannot cover either. Re-duck the original technical score against the new speech schedule, with a modest intro lift and restrained transitions. Retain the established project targets of approximately **−16 to −18 LUFS** and encoded true peak **≤ −1 dBTP**, subject to listening quality; these are project targets, not a certification standard. Check complete replies, word endings, transitions, stereo/mono compatibility and sync after encoding.

## 6. Feasible implementation using retained assets

The user-authorized cleanup deleted 91 other videos, including raw takes and render intermediates. Only the V5/V6 music and no-music exports remain. Scripts, graphic sources, audio stems, captions and evidence records remain. **Do not run the old raw-dependent builder unchanged or promise restored raw detail.**

Recommended phone-free source:

- Native video from [V5_NoMusic](../demo-production/output/EchoFlow_Demo_Premium_Voice_v5_NoMusic.mp4), muted: body **00:07–04:52.195**, crop **650×1408 at x1810/y16**. It has about 10% more horizontal native-image pixels than V6.
- V6 fallback: body at the same times, crop **590×1278 at x1840/y32**. Use V6's retained light-theme graphics/manifest as the style reference.
- Both are compressed derivatives with baked native overlays/privacy masks. Validate the crop boundaries visually; avoid aggressive enlargement that exposes compression. A sharper native retake is a later option only if required, not necessary to start this edit route.
- Retained audio in `demo-production/audio/v5-voice-final/`: `device-stem-48k.wav`, `command-stem-48k.wav`, `narration-stem-48k.wav`, `music-original/music-original.wav`, `timeline.json`, `mix-report.json` and `audio-finalization.json`.
- Timing/evidence: V5 and V6 `manifest.json` files, original retained voice/run logs and [delivery-voice-v6.json](../demo-production/output/delivery-voice-v6.json).
- Existing tools: FFmpeg/ffprobe, project Python utilities and Remotion motion sources in `demo-production/motion-v4/`. No additional plugin is presently needed. Any later dependency change must solve an identified requirement.

## 7. Execution sequence after editing is authorized

1. **Lock evidence and rules.** Inventory the retained source/captions/stems, verify keepers, check actual submission requirements and record allowed cuts/speed changes.
2. **Create the edit decision list.** Map every retained section to a native proof, actual request/reply and old-to-new timestamp. Inspect the five candidate intervals; remove only verified waiting or permitted repetition. Calculate the real resulting duration before finalizing narration.
3. **Preview the visual system.** Create three style frames: full-phone teaching, cart quantity close-up and clarification/report. Build a 15–20 second preview containing native speech, narrator captions, a focus transition and a result hold. Use it to resolve readability/mix issues before a full render.
4. **Recompose and caption.** Add the shortened motion intro, minimal chapter labels, truthful result annotations and complete speaker-aware bottom captions. Keep the chosen palette and native evidence.
5. **Retiming and mix.** Cut all stems using the same edit map, fit/rewrite only necessary narration, re-duck music, and preserve complete spoken exchanges.
6. **Render and verify.** Render a draft, perform the checklist below, then fix identified issues and export the final pair. Avoid repeated full renders without a specific defect and proposed correction.

Use durable checkpoints and logs at each stage. Give progress updates every 30–45 seconds during execution. Assign deadlines appropriate to each process; a long render can be healthy if its log/progress continues. On failure, record the last completed stage and cause. Allow one retry after evidence establishes a changed condition; a repeated identical failure returns to diagnosis rather than an open-ended loop. Do not connect/control a phone for the edit-only route.

## 8. Acceptance checklist and planned deliverables

- A first-time viewer can explain the task, what teaching saves, what changes in the reuse cases and where control returns to the user.
- All five required scenarios remain in the verified order, with real commands, outcomes, clarification and halt/report visible and audible.
- Saved lesson, existing-item check, Farmhouse quantity 2 and handoff are readable at 720p and on a small display. Important results have at least a 2-second readable proof beat, extended when necessary.
- All intelligible narrator/request/EchoFlow speech has accurate captions, correct speaker context and synchronized timing. Captions do not obstruct native evidence.
- No narrator overlap with requests or app replies; headphone/speaker/mono listening checks pass after the edit.
- Measured encoded loudness/peak, distributed speech sync, full-file decode, duration and stream format pass. Recheck privacy through zooms, transitions and every output, not just old sample frames.
- The disclosure is readable and matches the actual input/editing method. Unsupported performance, universality and physical-microphone claims are absent.
- Final runtime/editing method matches independently verified submission rules. Successful software unit tests are not presented as a reliability percentage for this demo.

Planned delivery: **V7 music master**, **V7 voice-only master**, accurate **SRT/VTT**, review player, edit/timing manifest and a concise validation report. Retain V5/V6. Target the current delivery format, **2560×1440, 30fps H.264 with 48kHz stereo AAC**, unless the organizer specifies otherwise. Record any remaining limitation, particularly source-image resolution and any listening check that could not be performed.

Local evidence reviewed: [V6 manifest](../demo-production/output/edit-v6-voice-samaya/manifest.json), [V6 contact sheet](../demo-production/output/edit-v6-voice-samaya/final-contact-sheet.jpg), [V6 delivery record](../demo-production/output/delivery-voice-v6.json), [cleanup manifest](../demo-production/output/video-cleanup-20261004.json), [original demo script](DEMO_SCRIPT.md), and retained audio/timing records. **This document is the implementation plan; the V7 work described here has not begun.**
