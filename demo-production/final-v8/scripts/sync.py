"""Aligns a take's EchoFlow log with its video (TTS starts vs. audio onsets) and writes <take>/events.json.

  python sync.py take11
"""
import json, os, re, subprocess, sys
import numpy as np

V8 = os.path.dirname(os.path.abspath(__file__))
KEYS = ["a1", "a2", "b1", "c1", "d1", "e1", "e2", "f1"]

def main(take):
    d = os.path.join(V8, take)
    sync = open(os.path.join(d, "sync.txt")).read()
    g = lambda k: float(re.search(k + r"=([\d.]+)", sync).group(1))
    base = g("recStartPc") + (g("phoneEpoch") - g("pcEpoch"))
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", os.path.join(d, "raw.mkv"), "-vn", "-ac", "1", "-ar", "16000", "-f", "f32le", "-"], capture_output=True).stdout
    x = np.frombuffer(raw, np.float32)
    hop = 160
    env = np.sqrt(np.convolve(x ** 2, np.ones(hop) / hop, mode="valid")[::hop])
    db = 20 * np.log10(env + 1e-6)
    lines = open(os.path.join(d, "logcat.txt"), encoding="utf-8", errors="ignore").read().splitlines()
    starts = [float(m.group(1)) for l in lines for m in [re.match(r"\s*(\d+\.\d+).*tts event=start owner=VoiceIO", l)] if m]
    offs = []
    for s in starts:
        p = s - base; lo = int((p - 1.0) * 100); seg = db[max(0, lo):int((p + 3) * 100)]
        if (seg > -35).any(): offs.append((lo + int(np.argmax(seg > -35))) / 100 - p)
    delay = float(np.median(offs))
    V = lambda e: round(e - base + delay, 3)
    feeds, tts, says, teach = [], {}, [], []
    for l in lines:
        m = re.match(r"\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+(\S+?):?\s(.*)$", l)
        if not m: continue
        t, tag, msg = float(m.group(1)), m.group(2).rstrip(":"), m.group(3)
        if "audio_feed event=start" in msg: feeds.append(V(t))
        mm = re.search(r"tts event=(start|done|stop) owner=VoiceIO id=(\S+)", msg)
        if mm: tts.setdefault(mm.group(2), {})[mm.group(1)] = V(t)
        mm = re.match(r"(say|ask): (.*?)( \| choices=.*)?$", msg)
        if tag == "EchoOrchestrator" and mm: says.append((V(t), mm.group(2).strip()))
        if tag == "EchoTeach" and "recorded" in msg: teach.append(V(t))
    spans = sorted((v.get("start"), v.get("done") or v.get("stop")) for v in tts.values())
    replies = [{"start": s, "end": e, "text": t[1]} for (s, e), t in zip(spans, says)]
    out = {"delay": delay, "base": base, "cmds": [{"key": k, "feed": f} for k, f in zip(KEYS, feeds)], "replies": replies, "teach": teach}
    json.dump(out, open(os.path.join(d, "events.json"), "w"), indent=1)
    print(f"delay {delay:.3f} (spread {np.ptp(offs):.2f})  feeds {len(feeds)}  tts {len(spans)}  says {len(says)}")
    for r in replies: print(f"{r['start']:7.2f}-{r['end']:7.2f} {r['text'][:80]}")

if __name__ == "__main__":
    main(sys.argv[1])
