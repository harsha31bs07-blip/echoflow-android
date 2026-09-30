# Demo video script (≤ 5 min, one unedited take)

The rules ask for one unedited video showing, **in this order**:
- (a) teaching one flow by voice and taps;
- (b) replaying it with the exact utterance;
- (c) replaying with a paraphrase;
- (d) replaying with a changed value;
- (e) the assistant asking a question when stuck.

This script does exactly that on Zomato in about 4 min 40 s, with v1.2.0. Every command in it was run on the Galaxy S24 FE on 30 Sept 2026 ([TEST_RUN.md](TEST_RUN.md)). It is built to show judges the three things the rubric rewards most:
- **it generalises** (a new dish, a new quantity, a new wording, from one demonstration);
- **it asks instead of guessing**;
- **it never pays**.

## How to make it: record first, then the voiceover
1. **Record the screen in one continuous take** (no cuts, no speed-ups; the rules ask for an unedited video). The operator speaks only the commands; EchoFlow's spoken replies must be audible in the recording.
2. **The teammate records the voiceover afterwards**, timed to the recording, using the narration lines below. Put narration in the gaps: **never over EchoFlow's own voice or the operator's commands**, and turn the recording's sound down only slightly under the narration, never off.
3. Keep the final video **one continuous piece**, 5 minutes or less. The times below are targets; match the voiceover to the real recording.

> Check the rules once: a voiceover laid over an uncut screen recording is normally fine for "unedited", but cutting or reordering is not.

## Before recording (10 minutes)
1. **Zomato:** logged in; delivery address **Home**; the cart **empty**; *Brik Oven* open and delivering (check its hours; use any open restaurant with a pizza and change the lines below).
2. **EchoFlow:** v1.2.0 from the GitHub release.
   - Say or type **"forget the pizza one"** → yes, so (a) teaches from scratch.
   - Under **Advanced → AI help**, tap **Test AI help**: it should answer in under 2 s (only the optional Hinglish extra needs it).
3. **Phone:**
   - Do Not Disturb **on**; volume about 70%; plugged in;
   - the EchoFlow handle where the operator's thumb reaches it (drag it);
   - home screen showing.
4. **Screen recorder:** Samsung screen recorder with **media sounds and mic** on, so both the operator and EchoFlow's voice are heard.
5. **Rehearse once, fully.** Then empty the cart and start recording.

## Timeline

| Time | Part | Operator says / does | What viewers see and hear |
|---|---|---|---|
| 0:00–0:12 | Intro | Home screen. | **Narrator:** *"This is EchoFlow. You teach your phone a task once, by voice and by doing it, and after that you just ask. It only uses Android accessibility, and it never pays."* |
| 0:12–0:20 | **(a) Teach** | Tap the handle → say **"Order a Margherita pizza from Brik Oven on Zomato."** | *"I don't know how to … yet. Want to teach me?"* |
| 0:20–0:25 | | Say **"yes"**. | **Zomato opens by itself**; the panel shows **● Recording**. |
| 0:25–1:25 | | Do it by hand, at a calm pace:<br>1. search → type **Brik Oven** → open it;<br>2. the menu's **Search** → type **margherita** → **ADD**;<br>3. on the options sheet → **Add item**;<br>4. **Continue** (the cart opens) → tap **✓ Done**.<br>**Show undo once:** before searching the menu, tap a filter chip (e.g. *Veg*), then say **"undo"**, then tap the chip again to switch it off and say **"undo"** once more. | Live captions after each step: *"Got it: tapped ADD · 4 steps so far"*; *"Okay, I removed: tapped …"*.<br>On Done: *"Learned: order a margherita pizza from brik oven on zomato. I saved 6 steps. You can change the item, restaurant."* |
| 1:25–1:35 | | In the cart, tap **−** to empty it (EchoFlow isn't recording any more). Go home. | **Narrator:** *"One demonstration. Now it can repeat it, reworded, or with a different dish."* |
| 1:35–2:20 | **(b) Exact replay** | Handle → **"Order a Margherita pizza from Brik Oven on Zomato."** | It opens Zomato, finds Brik Oven and Margherita, adds it and opens the cart. A **mint outline** appears around **Place Order**, and it says: *"… Your turn. Everything is ready for payment, total ₹…. I won't pay."*<br>**Narrator:** *"It stops at payment every time. The outline shows what's left for you; EchoFlow never taps it."* |
| 2:20–3:05 | **(c) Paraphrase** | Go home → handle → **"Get me a margherita from Brik Oven."** | *"Okay, order a margherita pizza from brik oven on zomato."* → it replays → *"Margherita was already in your cart, so I didn't add another one. Your turn…"*<br>**Narrator:** *"Different words, same task: it understood the paraphrase on the phone, without the cloud. And it noticed the pizza was already in the cart."* |
| 3:05–3:55 | **(d) Changed values** | Home → handle → **"Order two Farmhouse pizzas from Brik Oven on Zomato."** | It adds **Farmhouse**, sets the quantity to **2** with the cart's + button, and hands over with the new total.<br>**Narrator:** *"A new dish and a quantity it was never shown. Nothing about Farmhouse was taught."* |
| 3:55–4:25 | **(e) Stuck → asks** | Home → handle → **"Order a zzqx unicorn pizza from Brik Oven on Zomato."** | *"I searched for "zzqx unicorn" at brik oven but couldn't find it. What should I get instead?"* → say **"nothing"** → *"… so I stopped at step … without adding anything."*<br>**Narrator:** *"When it can't find something, it asks. It doesn't guess and it doesn't tap anything wrong."* |
| 4:25–4:40 | Close | Handle → **"Did the last run succeed?"** | *"No, the last run didn't succeed … stopped at step … "*<br>**Narrator:** *"Teach once, ask any way, and payment always stays with you. That's EchoFlow."* |

**After recording:** empty the Zomato cart (Margherita and 2 × Farmhouse). Nothing is ordered or paid at any point.

## If something goes wrong on camera (don't stop recording: the video must be unedited)
- **"I didn't catch that":** tap the handle again and speak a little closer.
- **It asks "Do you want me to order …?"**: say **"yes"**. It confirms when it isn't sure, which is the behaviour judges want.
- **Zomato shows a loading quote or "Something went wrong":** wait; EchoFlow waits for it or taps *Try again* by itself.
- **A run stops with a reason:** that's a valid, honest outcome. Say **"did the last run succeed?"** (it explains), then continue with the next part.
- **You end up on a payment screen:** press Back. EchoFlow never acts there.

## Optional extras (only if the take is well under 5 minutes)
- **Hinglish:** *"Brik Oven se margherita pizza mangwao."* Needs AI help to answer quickly (run **Test AI help** first; if it takes more than 2 s, skip this).
- **Bonus B2 (+4):** with an Amazon flow taught, say *"Search for sunglasses on Myntra and add the first result to cart."* → *"I learned this on Amazon. Do you want me to try the same steps on Myntra?"* → **yes**. Empty the Myntra bag afterwards.
- **Bonus B3:** *"I want to order margherita pizza on zomato"* (no restaurant) → *"Which restaurant should I order from? Last time it was brik oven."* → **"same"**.
