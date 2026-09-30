# EchoFlow AI relay

A tiny Cloudflare Worker that lets every EchoFlow install use the optional AI help without
putting a Gemini key in the app. The key lives only in Cloudflare as a secret.

- Forwards only EchoFlow's own prompts (command matching, stuck-screen help, key test), with a
  fixed model and settings: it can't be used as a general chatbot.
- Caps requests per day: 300 in total and 60 per network (see `src/worker.js`).
- Stores nothing but those counters, keyed by a hash of the IP, for two days.
- A key pasted in the app (Advanced → AI help) still takes priority over the relay.

Use a Gemini key whose Google project has **billing turned off**. The worst abuse can do then
is use up the free daily quota; it can never cost money.

## Deploy (once)

```powershell
cd relay
npx wrangler login                        # opens the browser
npx wrangler deploy                       # prints the URL, https://echoflow-relay.<subdomain>.workers.dev
npx wrangler secret put GEMINI_API_KEY    # paste the key when asked; it isn't shown or saved anywhere else
```

Then build the app with the URL in `local.properties` (not committed):

```
GEMINI_RELAY_URL=https://echoflow-relay.<subdomain>.workers.dev
```

To turn the relay off, `npx wrangler secret delete GEMINI_API_KEY` (the app then works without AI
help), or delete the Worker in the Cloudflare dashboard.
