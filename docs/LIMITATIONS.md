# Known limitations

This is an honest list of what EchoFlow can't do yet, or has only partly verified. Each entry says what fails, what EchoFlow does about it, and what is still open. Updated 29 Sept 2026, after the full run of the official tests on the phone ([TEST_RUN.md](TEST_RUN.md)).

## How far testing went

### L1. One phone, one account, stand-in restaurant
**What was tested.** Everything on the phone was run on one **Samsung Galaxy S24 FE** (Android 15) with one Zomato, Amazon and Myntra account each.
- **Domino's doesn't deliver to the test address,** so **Brik Oven** stood in for it in T1–T7. The flow is the same shape (search the restaurant → menu search → ADD → size sheet → cart).
- **Other phones and brands** are untested. See L12 for known setup steps.

**Still open.** A judge's account, restaurant or app version may show screens we haven't seen. Every recovery is general (no app or restaurant names in the code), but untested layouts can still stop a run. When that happens it stops with a specific message rather than tapping something wrong.

### L2. Flows taught in apps we never tried
**Problem.** Judges will teach new flows live. EchoFlow has been taught on Zomato, Amazon and Swiggy, and has replayed an Amazon flow on Myntra. Other apps may:
- not report some taps to accessibility services (L7);
- hide their screens from accessibility services (L8);
- use pickers EchoFlow doesn't know (L10).

**What EchoFlow does.** After teaching, it says how many steps it saved and which values can change. The Flow Inspector shows every step in plain words, so a missing step is visible at once and the flow can be taught again.

### L3. T10 "change the account language to Hindi"
**Problem.** Zomato kept its UI in English when the app language was set to Hindi, so this exact judge action couldn't be reproduced.

**What EchoFlow does.**
- If most on-screen text is in another script, the stuck message says so: *"The app seems to be in a different language, so I can't find "…" (step N of M). Please switch the app back to English."*
- A logged-out app trips the login check and stops with *"Your turn…"*. It is reported as **not** succeeded (T14).

**Still open.** Both behaviours are unit-tested. On the phone, T10 was shown with the "dish can't be found" case, which asked within 30 s. Neither the Hindi path nor the logged-out path has been run on the phone.

## Safety

### L4. Opaque payment screens (T11)
**Problem.** Some payment screens are WebViews, use `FLAG_SECURE`, or draw their own UI, and expose almost no accessibility nodes.

**What EchoFlow does.**
- A screen with no readable content that stays that way for 6 s is `OPAQUE_UNKNOWN`, and EchoFlow stops (fail closed).
- Payment-gateway and wallet packages trip PAYMENT on sight.
- Pay and order buttons are never tapped (L5).

**Still open.** These checks are heuristics. They were checked on real Zomato, Amazon, Swiggy and Myntra screens only.

### L5. Pay and order buttons are recognised by their words (T11)
**Problem.** With cash on delivery preselected, "Place order" places a real order without any payment screen.

**What EchoFlow does.** Every tap target is classified from its label, view id and child labels.
- **Never tapped:** "Pay…", "Place order", "Confirm order", "Buy now", slide-to-pay controls. This holds even if one was demonstrated.
- **Allowed but checked on the next screen:** "Proceed to pay", "Checkout".
- **Gesture taps:** the tap-at-the-centre fallback is refused if *anything* at that spot could pay, order or delete (`GestureSafety`). That rule came from a real near-miss on Zomato, where the menu's Continue bar sat under Place Order.

**Still open.** The word lists cover English and some Hindi. A pay button with unusual wording, on a screen with no other payment signal, would not be recognised.

### L6. Menu and product pages that mention payments
**Problem.** Offers such as "10% off with HDFC Credit Card", "Amazon Pay" or "Buy for ₹1,734 with Axis Bank card" mention payments on ordinary pages.

**What EchoFlow does.**
- Strong payment phrases only count in short labels.
- Weaker ones (UPI, Cards…) need three distinct hits.
- Offer rows are never treated as addresses or results.
- Unit tests cover real menu, product, bag and cart pages.

**Still open.** A page made of several short payment tiles could stop a replay early. That's safe, but it's a false stop.

## Teaching and replay

### L7. Taps some apps never report
**Observed.** These taps produce no click event, so they aren't recorded while teaching:
- Amazon's search box and search results;
- Amazon's Add to Cart;
- Zomato's search suggestions and result cards;
- Swiggy's checkout bar.

**What EchoFlow does.** Replay fills these gaps with general recoveries:
- opens the search bar when the typing step's box is hidden;
- presses Enter after typing;
- opens the result matching what was typed;
- when the command says "first result" or "to cart", the compiler adds those steps itself;
- opens the cart at the end of a flow taught to finish there.

**Still open.** A missing tap that fits none of these patterns breaks that flow. It shows in the Flow Inspector, and teaching again fixes it.

### L8. Apps that hide their screen from accessibility services
**Observed.** Swiggy showed only empty containers until the service declared `android:isAccessibilityTool="true"` (0 → 70 readable nodes). EchoFlow is a voice-control tool, a category Google's policy lists as eligible for that flag.

**Still open.**
- An app that still hides its UI can't be taught; EchoFlow says the screen can't be read.
- Play Protect may warn when installing a sideloaded app with this flag. Choose **More details → Install anyway**.

### L9. Item-dependent screens
**Problem.** Different items can lead through different screens. A Margherita opens a size sheet; another dish may not.

**What EchoFlow does.**
- A sheet that appears only for the new item is handled: the preselected options are kept, and the message says so (*"I added farmhouse with the options that were already selected, ₹260."*).
- A taught screen that doesn't appear is skipped by looking ahead.
- A size sheet after "Add to bag" (Myntra) picks the only size or asks which one.

**Still open.** Required choices with no default (for example "choose 2 toppings") make EchoFlow ask, not choose.

### L10. Quantity and address need a readable stepper and address list (T5, T6)
**What EchoFlow does.**
- **Quantity:** set at the cart with the item's − 1 + stepper. The count is re-read after each tap, and it never goes below 1.
- **Address:** EchoFlow opens the app's delivery bar and picks the saved address named in the command.

**Still open.** Two cases don't work:
- **A picker in a secure window:** Swiggy's cart-page picker shows black in screenshots, so EchoFlow can't read it. It uses the home-screen bar instead.
- **Address names that don't match:** the saved label must match what's said ("work" ↔ "Work").

### L11. Cross-app runs (B2)
**What EchoFlow does.** EchoFlow offers an Amazon-taught flow in Myntra or Flipkart only when the command names that app. It always confirms first, and runs the same steps with the same general recoveries. No LLM is involved.

**Still open.**
- Only verified on Myntra (search → first product → Add to Bag).
- App kinds come from a small fixed map (shopping, food, grocery). A new app must be added to that map to be offered.

## Setup and voice

### L12. Installation friction
- **Android 11 or newer** is required. EchoFlow presses a search box's Enter key through an accessibility action that Android 11 introduced.
- **Android 13 and later** block accessibility services in sideloaded APKs until **Allow restricted settings** is turned on (App info → ⋮). The README walks through it.
- **Some brands (OnePlus, Xiaomi, Oppo, Vivo, Realme)** stop an app completely when it's swiped away from recent apps, and a stopped accessibility service stays off until it's switched off and on. EchoFlow now:
  - stays out of the recent-apps list, so there's no card to swipe away;
  - asks, in setup step 3 ("Keep EchoFlow running"), to be left out of battery optimisation, and points to App info → Battery for background activity and auto-launch;
  - says *"Your phone switched EchoFlow off"* (or *"stopped EchoFlow"*) with the fix, instead of silently losing the edge handle.

  Reported on a OnePlus phone; the fixes were checked on the Galaxy S24 FE (force-stop), not yet on a OnePlus.

### L13. Voice
- **Push-to-talk only.** Android's `SpeechRecognizer` has no always-on mode, so there is no wake word.
- **Speech recognition** uses the phone's recognition service (usually the Google app's). Every question can also be answered by tapping a choice or typing.
- **English commands only.** The matcher's word rules are English; a Hindi command won't match a taught flow.

### L14. The optional LLM
**What it does.** With a Gemini API key, loosely worded commands can match a flow without a confirmation question. Without a key, those commands still work, but EchoFlow asks "Do you want me to …?" first.

With a key, it also helps a replay that's stuck on a screen it wasn't taught ([ARCHITECTURE.md §5b](ARCHITECTURE.md)).

**Limits.**
- **No key built in.** The release APK has none; a key can be pasted in the app (Advanced → AI help) and stays on the phone.
- **What Gemini sees when stuck.** A list of the screen's button and text labels, with typed text dropped and numbers and emails masked. It's never asked about payment, OTP, password, login or cart screens.
- **Suggestions only.** Its suggestions are checked and gated like any other action, and it never decides safety.
- **Suggestions can be wrong.** A suggestion is only followed if it's safe to tap, and at most twice per step. A wrong but safe suggestion (closing the wrong pop-up) costs a little time before the specific stuck message.
- **Slow loading screens.** Gemini is asked only after a step has been stuck 7 s, and never while a loading spinner is showing. A slow screen without a spinner can still cost one call, which usually answers "still loading".

### L15. Launching apps
`LaunchApp` uses the app's launcher intent, the same one the home-screen icon sends. It's not a deep link, and it always starts from the app's home screen.
