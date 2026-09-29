# Demo video script (≤ 5 min, one unedited take)

The rules ask for one unedited video showing, **in this order**:
- (a) teaching one flow by voice and taps;
- (b) replaying it with the exact utterance;
- (c) replaying with a paraphrase;
- (d) replaying with a changed value;
- (e) the assistant asking a question when stuck.

This script does exactly that on Zomato, then adds two short extras if time allows. Every line below has been run on the Galaxy S24 FE (see [TEST_RUN.md](TEST_RUN.md)).

Record the phone screen with the Samsung screen recorder, **with microphone and media sound on**, so both your commands and EchoFlow's spoken replies are in the video.

## Before recording
- **Zomato:** logged in, a restaurant that delivers to you and is open (the test used *Brik Oven*; use *Domino's* if it delivers to you), and its cart empty.
- **EchoFlow:** home screen says *"You're all set"*, and nothing is learned yet. Delete old flows in the Flow Inspector.
- **Phone:** volume up, Do Not Disturb on, and plugged in.
- **Speaking:** tap **🎤** on the floating bubble, wait for *"Listening…"*, then speak clearly.
- **Timing:** each replay takes about 40–50 s, so the whole script fits in about 4½ minutes. Don't pause between parts.

## Timeline

| Time | Part | Say (tap 🎤 first) / do | What EchoFlow says or does |
|---|---|---|---|
| 0:00–0:15 | Intro | Show EchoFlow's home screen | Voice-over: *"EchoFlow learns a task from one demonstration and replays it by voice. It only uses Android accessibility, and it never pays."* |
| 0:15–1:30 | **(a) Teach** | "Order a Margherita pizza from Brik Oven on Zomato." → *"Want to teach me?"* → say **"yes"**. Then do it by hand: open Zomato → (close the location pop-up if it appears) → search → type **Brik Oven** → open the restaurant → the menu's **Search** → type **margherita** → **ADD** → **Add item** → **Continue** (the cart opens) → tap **✓ Done** on the bubble. **Do not tap Place Order.** | Bubble shows **● Recording**. On Done: *"Learned: order a margherita pizza from brik oven on zomato. I saved 6 steps. You can change the item, restaurant."* |
| 1:30–1:40 | (show it) | Open EchoFlow → the new card under *What I've learned* | The Flow Inspector: what you can say, what can change, every step, and *"stops and hands over to you before paying"*. Go back to the phone's home screen. |
| 1:40–2:30 | **(b) Exact replay** | "Order a Margherita pizza from Brik Oven on Zomato." | Opens Zomato, finds the restaurant and the dish, then *"Margherita was already in your cart, so I didn't add another one. Your turn. Everything is ready for payment…"* (the dish from teaching is still in the cart; to show a fresh add instead, tap − on it before this step). |
| 2:30–3:20 | **(c) Paraphrase** | "I want to order margherita pizza on zomato" | Recognises the same flow. The restaurant wasn't said, so it asks: *"Which restaurant should I order from? Last time it was brik oven."* → say **"Brik Oven"** → continues to the cart and hands over (bonus: a missing value asked mid-flow) |
| 3:20–4:10 | **(d) Changed value** | "Order a Farmhouse pizza from Brik Oven on Zomato." | Adds **Farmhouse** (not Margherita) → *"Your turn. Everything is ready for payment, total ₹…. I won't pay."* |
| 4:10–4:40 | **(e) Stuck → asks** | "Order a zzqx unicorn pizza from Brik Oven on Zomato." | *"I searched for "zzqx unicorn" at brik oven but couldn't find it. What should I get instead?"* → say **"nothing"** → *"…so I stopped at step 5 without adding anything."* No wrong taps |
| 4:40–5:00 | Extras (if time) | "Book a cab to the airport." then "Did the last run succeed?" | *"I don't know how to … yet. Want to teach me?"* (say no) · *"No, the last run didn't succeed … stopped at step 5 …"* |

**Optional bonus clip (B2), only if the Amazon flow is taught on the phone:** say "Search for sunglasses on Myntra and add the first result to cart." → *"I learned this on Amazon. Do you want me to try the same steps on Myntra…?"* → **"yes"** → it searches Myntra and puts the first product in the bag. Empty the Myntra bag afterwards.

**After recording:** empty the Zomato cart. Nothing is ever ordered or paid, because EchoFlow stops at the cart every time.

## If something goes wrong on camera
- **The bubble says "I didn't catch that":** tap 🎤 again and speak closer to the mic.
- **Zomato shows "Something went wrong":** EchoFlow taps *Try again* by itself. Just wait.
- **It stops with a reason:** that's a valid outcome. Say *"did the last run succeed?"* to show the report, then carry on.
- **You land on a payment screen:** press Back. EchoFlow never acts on payment screens.
