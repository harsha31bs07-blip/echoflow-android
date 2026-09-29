# Overnight log (30 Sept 2026, ~00:45 → morning)

Everything below is on the branch `claude/elegant-einstein-c96kwl`. **`main`, the `PRISM_GENAI_HACKATHON_Y2026` tag and the GitHub release are still v1.1.4.** Say "release it" to move them to v1.1.5. The v1.1.5 APK is already built on the branch and installed on the phone.

## What changed tonight

### Bugs found and fixed (all checked on the phone)
1. **The panel blocked EchoFlow's own scrolling (High).** On Amazon's product page, the scroll swipe started on top of the (taller, redesigned) floating panel, so the page never moved and T9 failed. Now the panel lets touches through while EchoFlow performs a gesture. T9 passes again.
2. **✕ on the listening panel could leave nothing on screen (High).** Pressing ✕ and quickly tapping the handle again let the old session close the new one. Fixed; ✕ now brings the handle back instantly.
3. **Two listening sessions at once (High).** An older session ending late could reset a newer one ("I didn't catch that" while still listening). Each press is now its own session.
4. **AI helper asked during loading (Medium).** It now waits 7 s, not 5, and never asks while a loading spinner is showing.
5. **The panel covered the notification shade (Low).** The bubble now steps aside while the shade or lock screen is open.

### Friendlier and smoother
- **Flow Inspector:**
  - steps grouped into **named phases** (Open Zomato → Find the restaurant → Find the item → Add to cart → Go to the cart), after a June 2026 study showing people read demonstrations better that way;
  - "the search box" instead of internal names like "rs search";
  - a clear footer explaining recorded vs saved steps.
- **Home screen:**
  - flow cards show the taught values (*Order a **margherita** pizza from **brik oven** on Zomato*);
  - a **sound-wave speak button** next to the text box, since there was no way to speak from inside the app.
- **Motion:**
  - the panel grows out of its corner;
  - the handle and the edge glow fade in and out;
  - a **haptic tick** on every button;
  - all of it turns off with "Remove animations".
- **Listening panel:** its hint suggests one of your own learned commands ("Try “Search for lofi music on youtube”").
- **Messages:**
  - progress shows real values ("Type brik oven into the text box");
  - the stuck message no longer nests quotes;
  - a cancelled run records the step it stopped at.
- **Quick Settings tile, "Talk to EchoFlow":** tap it in the notification shade to speak from anywhere. It builds and installs, but **I couldn't test a tap on your phone**: Samsung's One UI ignores the command-line test commands, and adding it by hand would have changed your Quick Settings layout.

### Regression run (see [TEST_RUN.md](TEST_RUN.md#overnight-regression-run-30-sept-0100-0130-branch-build))
- **Passed:** T9, B2, a live teach, T12, T13, T14, and ✕ mid-run.
- **Carts:** every cart and bag was emptied afterwards (Amazon, Myntra, Zomato).
- **Not repeated:** Brik Oven was closed, so no Zomato orders tonight.

## Needs you
1. **"release it"** → I'll move `main`, the tag and the GitHub release to v1.1.5.
2. **A leftover entry in your Quick Settings list.** When I tested the tile, Samsung kept an entry for it in its tile list; One UI rewrites it back if removed. It's **not shown anywhere** in your shade. To use the tile: pull down the shade → ✏ Edit → add "Talk to EchoFlow".
3. **Record the demo while Brik Oven is open.** It closes late at night.

## Files
- [OVERNIGHT_PLAN.md](OVERNIGHT_PLAN.md): the plan.
- [AUDIT.md](AUDIT.md): the judge-style audit, every finding and its status.
