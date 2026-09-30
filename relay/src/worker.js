// EchoFlow AI relay: lets every EchoFlow install use AI help without a key in the app.
// The Gemini key is a Cloudflare secret (GEMINI_API_KEY), never in the APK or this repo.
// It only forwards EchoFlow's own two prompts, with a fixed model and settings, and caps
// requests per day (in total and per phone network) so the free quota can't be drained.
// Stores nothing but those counters (keyed by a hash of the IP, kept two days).

const MODEL = "gemini-flash-lite-latest";
const PER_DAY = 300;
const PER_IP_PER_DAY = 60;
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

    const day = new Date().toISOString().slice(0, 10);
    const who = await hash(request.headers.get("cf-connecting-ip") || "unknown");
    const totalKey = `day:${day}`;
    const mineKey = `ip:${day}:${who}`;
    const [total, mine] = await Promise.all([count(env, totalKey), count(env, mineKey)]);
    if (total >= PER_DAY || mine >= PER_IP_PER_DAY) {
      return error(429, "EchoFlow's shared AI help has reached today's limit. It resets tomorrow, or paste your own free key under Advanced.");
    }
    const ttl = { expirationTtl: 2 * 24 * 3600 };
    await Promise.all([env.COUNTS.put(totalKey, String(total + 1), ttl), env.COUNTS.put(mineKey, String(mine + 1), ttl)]);

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
