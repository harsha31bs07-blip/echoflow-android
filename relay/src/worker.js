// EchoFlow AI relay: lets every EchoFlow install use AI help without a key in the app.
// The Gemini key is a Cloudflare secret (GEMINI_API_KEY), never in the APK or this repo.
// It only forwards EchoFlow's own two prompts, with a fixed model and settings, and caps
// requests per minute per network (Cloudflare's rate limiter, keyed by a hash of the IP) and per
// day in total (one KV counter, kept two days), so the free quota can't be drained.
// Stores nothing else. (One KV write per request keeps the total inside KV's free 1,000 writes a
// day; a venue full of judges on one Wi-Fi shares the per-minute limit.)

const MODEL = "gemini-flash-lite-latest";
const PER_DAY = 900; // under the Gemini free tier's daily requests, and KV's free daily writes
const MAX_PROMPT_CHARS = 30_000;
// The beginnings of the prompts EchoFlow sends (command matching, stuck-screen help, key test).
const PROMPTS = ["You route a spoken command", "You help a phone automation", "Reply with JSON only: {\"ok\": true}"];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/generate") return error(404, "Not found.");
    if (request.headers.get("x-echoflow-client") !== "1") return error(403, "Not an EchoFlow request.");

    let body;
    try {
      body = await request.json();
    } catch {
      return error(400, "Bad request.");
    }
    const prompt = typeof body?.prompt === "string" ? body.prompt.trim() : "";
    if (!prompt || prompt.length > MAX_PROMPT_CHARS || !PROMPTS.some((p) => prompt.startsWith(p))) {
      return error(400, "Not an EchoFlow request.");
    }

    const who = await hash(request.headers.get("cf-connecting-ip") || "unknown");
    // Per network: a burst limit (see wrangler.toml), no storage.
    if (env.PER_NETWORK) {
      const { success } = await env.PER_NETWORK.limit({ key: who });
      if (!success) return error(429, "Too many AI requests from this network right now. Try again in a minute.");
    }
    const day = new Date().toISOString().slice(0, 10);
    const totalKey = `day:${day}`;
    const total = await count(env, totalKey);
    if (total >= PER_DAY) {
      return error(429, "EchoFlow's shared AI help has reached today's limit. It resets tomorrow, or paste your own free key under Advanced.");
    }
    await env.COUNTS.put(totalKey, String(total + 1), { expirationTtl: 2 * 24 * 3600 });

    const upstream = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${env.MODEL || MODEL}:generateContent`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { responseMimeType: "application/json", temperature: 0, maxOutputTokens: 1024 },
      }),
    });
    // Gemini's own reply (or error), unchanged: the app reads it the same way as a direct call.
    return new Response(await upstream.text(), { status: upstream.status, headers: { "content-type": "application/json" } });
  },
};

async function count(env, key) {
  return parseInt((await env.COUNTS.get(key)) || "0", 10) || 0;
}

async function hash(text) {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
  return [...bytes.slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function error(status, message) {
  return new Response(JSON.stringify({ error: { code: status, message } }), { status, headers: { "content-type": "application/json" } });
}
