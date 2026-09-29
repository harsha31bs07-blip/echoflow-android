# Overnight plan (30 Sept, ~01:00 → morning)

Harsha is asleep and the phone is connected. Claude works through this list in order, one item at a time. Each item ends with the tests passing, a check on the phone, and a commit to `claude/elegant-einstein-c96kwl`.

## Ground rules
- **Never tap Pay or Place Order.** Empty every cart after a test.
- **No personal details** in commits, docs or screenshots saved to the repo.
- **Leave the public release alone.** `main`, the `PRISM_GENAI_HACKATHON_Y2026` tag and the GitHub release stay on v1.1.4 until Harsha says "release it". A v1.1.5 APK is built and waiting on the branch.
- **Keep changes small and safe.** Nothing that could break T1–T14. Every change gets the full unit-test run and a check on the phone.

## 1. Research (short)
- Look for new ideas in voice agents, teach-by-demonstration systems and screen-automation apps, and for what hackathon judges reward.
- Write down only ideas that fit tonight: small, safe, and visible in a demo.

## 2. Judge-style audit
Look at EchoFlow the way a Theme 3 judge would, in four passes:
1. **First run:** install → setup → teach → replay. Note every confusing moment.
2. **Every screen:** home, Flow Inspector, bubble in each mode, listening panel. Screenshot each and check it for clarity, polish, contrast and touch targets.
3. **Rubric:** walk T1–T14 and B1–B3 against the current build and note any weak spot.
4. **Code:** look for crash risks, races (like the ✕ bug), leaks, and error paths that give a vague message.

Findings go into `docs/AUDIT.md`, each with a severity. The fixes follow in severity order.

## 3. Debugging
Fix the audit's findings, starting with anything that could fail a judge's test or leave the UI stuck.

Known candidates:
- **Overlapping listening sessions:** tapping the handle while it's still listening.
- **Slow loading screens:** the AI helper can be asked during a slow interstitial. Wait longer before asking when the screen is only loading.
- **Collapse timing:** the bubble should stay up long enough to read, and never get stuck open or hidden.

## 4. Smoothness and friendliness
- Gentle animations: the bubble folds and unfolds with a fade and scale; the glow fades in and out instead of switching; everything respects "Remove animations".
- Haptic ticks on the main buttons (speak, ✕, Done).
- Flow Inspector polish: clearer steps, "Try it" examples, delete and rename.
- Friendlier messages where they're still technical.

## 5. Only if 1–4 are done
- A Quick Settings tile, "Talk to EchoFlow", in the notification shade.
- Other small, low-risk ideas from the research.

## Morning handover
- `docs/OVERNIGHT_LOG.md`: what changed, what was tested on the phone, what's left, and any decision that needs Harsha.
- A v1.1.5 release APK built on the branch, ready for "release it".
