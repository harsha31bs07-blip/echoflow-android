# Demo video script (≤ 5 min)

Record the phone screen (the Samsung screen recorder, **with microphone audio**) so the video captures both the spoken commands and EchoFlow's replies. Every phrase below has been run on the Galaxy S24 FE (see [TEST_RUN.md](TEST_RUN.md)).

## Before recording
- Swiggy logged in, delivery address **Hostel**, cart empty. (If the cart isn't empty, that's fine: EchoFlow asks before replacing it, which shows off T7.)
- EchoFlow: the accessibility service is on, and **Learned flows** is empty. Delete old flows in the Flow Inspector; keep the Amazon and Zomato ones only if you're skipping their teach segments.
- Volume up, Do Not Disturb on, the phone plugged in (the screen stays awake).
- **Speaking:** tap the **🎤** in the floating bubble, wait for "Listening…", then speak clearly.

## Timeline

| Time | Show | Say (tap 🎤 first) | Expected on screen / spoken | Rubric |
|---|---|---|---|---|
| 0:00–0:20 | EchoFlow home: "Accessibility service is on" | — | One line of voice-over: "EchoFlow learns a task from one demonstration and replays it by voice. It uses only Android accessibility, and never pays." | Intro |
| 0:20–1:20 | **Teach** in Swiggy | "teach order garlic bread" → tap the Swiggy search bar → type *garlic bread* → Enter → **Dishes** tab → **ADD** on Garlic Breadsticks → **View Cart** → tap **✓ Done** | Bubble shows **● REC**. On Done: "Saved. I learned … steps for order {item}…" | T1 |
| 1:20–1:40 | EchoFlow → Learned flows → tap the flow | — | The Flow Inspector: answers-to, `{item}` slot, steps, "Then: hand over to you at checkout. EchoFlow never pays." | T1 (inspectable) |
| 1:40–2:20 | Empty the cart (− on the item), go to the phone's home screen | "order garlic bread" | Swiggy opens, searches, taps ADD, opens the cart → "Everything is ready at checkout, total ₹…. I won't pay. Please check the order and pay yourself." | T2, T11 |
| 2:20–2:50 | Home screen | "can you get me some garlic bread" | "Do you want me to order garlic bread on Swiggy?" → say "yes" → replays to checkout | T3 |
| 2:50–3:30 | Home screen | "order 2 choco lava cake to home" | New item, quantity 2 at the cart, delivery address switched to Home → checkout hand-off. (If a "Replace cart item?" dialog appears, EchoFlow asks; say "yes". Avoid dishes with required options such as paneer tikka: EchoFlow correctly stops and asks you to choose them.) | T4, T5, T6, T7 |
| 3:30–3:55 | Home screen | "order zzqx unicorn waffles" | "I can't find 'zzqx unicorn waffles'. I can see: …. Which one should I pick?" → stay silent → it stops with a specific reason, having tapped nothing | T10 |
| 3:55–4:10 | Home screen | "book a cab to the airport" | "I don't know how to … yet. Want to teach me?" | T12 |
| 4:10–4:30 | Home screen *(needs the Zomato flow taught beforehand)* | "get me garlic bread" | "I know more than one way to do that. first: … Swiggy; second: … zomato. Which one?" | T13 |
| 4:30–4:45 | Home screen | "what happened last time" | The spoken summary of the last run | T14 |
| 4:45–5:00 | Swiggy Payment Options screen (open it by hand, don't pay) with the **Safety monitor** switched on in EchoFlow's debug section | — | The overlay turns red: **PAYMENT**. Voice-over: "Payment, OTP, password and login screens always stop EchoFlow." | T11 |

After recording, **set Swiggy's address back to Hostel** ("order garlic bread to hostel" does it) and empty the cart.

## If something goes wrong on camera
- **The bubble says "I didn't catch that":** tap 🎤 again, closer to the mic.
- **It stops with a reason:** that's a valid outcome (T10). Say "what happened last time" to show the report, then retry.
- **"You're on a payment screen":** press back to a normal screen first. EchoFlow won't start from a sensitive screen.
