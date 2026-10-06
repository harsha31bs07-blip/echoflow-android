"""V8 audio: phone audio (EchoFlow's real voice) + the command clips where they were fed to the recognizer
+ Prabhat narration in the gaps + a quiet music bed ducked under all speech. Writes music and no-music mixes."""
import json, os, subprocess, sys
import numpy as np

V8 = os.path.dirname(os.path.abspath(__file__))
TAKE = sys.argv[1] if len(sys.argv) > 1 else "take9"
END = float(sys.argv[2]) if len(sys.argv) > 2 else 284.0
SR = 48000
N = int(SR * END)

def load(path, start=0.0, dur=None):
    cmd = ["ffmpeg", "-v", "error", "-ss", f"{start}", "-i", path] + (["-t", f"{dur}"] if dur else []) + \
          ["-vn", "-ac", "2", "-ar", str(SR), "-f", "f32le", "-"]
    return np.frombuffer(subprocess.run(cmd, capture_output=True).stdout, np.float32).reshape(-1, 2).copy()

def place(buf, clip, t, gain=1.0):
    i = int(round(t * SR)); j = min(N, i + len(clip))
    if j > i: buf[i:j] += clip[:j - i] * gain

def db(x): return 10 ** (x / 20)

ev = json.load(open(os.path.join(V8, TAKE, "events.json")))
narr = json.load(open(os.path.join(V8, "narr", "plan.json" if TAKE == "take9" else f"plan_{TAKE}.json")))

phone = np.zeros((N, 2), np.float32)
p = load(os.path.join(V8, TAKE, "raw.mkv"), 0, END); phone[:len(p)] = p[:N]

cmds = np.zeros((N, 2), np.float32); speech = []
for c in ev["cmds"]:
    clip = load(os.path.join(V8, "cmd", c["key"] + ".mp3"))
    t = c["feed"] + 0.25            # the clip carries 0.25 s of lead-in silence when fed; the mp3 doesn't
    place(cmds, clip, t); speech.append((t, t + len(clip) / SR))
for r in ev["replies"]: speech.append((r["start"], r["end"]))

vo = np.zeros((N, 2), np.float32)
for n in narr:
    clip = load(os.path.join(V8, "narr", n["key"] + ".mp3"))
    place(vo, clip, n["at"]); speech.append((n["at"], n["at"] + n["dur"]))

def rms_db(x):
    return 20 * np.log10(np.sqrt(np.mean(x[np.abs(x).max(1) > 1e-3] ** 2)) + 1e-9)
# Level-match the three voices: EchoFlow (phone) is the reference.
ref = rms_db(phone)
cmds *= db(ref - rms_db(cmds) - 1.0)
vo *= db(ref - rms_db(vo) + 0.5)
speech_mix = phone + cmds + vo

# Music: one track, no loops (284 s fits), fades, ducked -12 dB under any speech with 0.25 s ramps.
music = load(os.path.join(V8, "music", "Inspired.mp3"), 0, END)
m = np.zeros((N, 2), np.float32); m[:len(music)] = music[:N]
env = np.ones(N, np.float32) * db(-18)
for a, b in speech:
    i, j = max(0, int((a - 0.3) * SR)), min(N, int((b + 0.4) * SR)); env[i:j] = db(-30)
k = int(0.25 * SR)                                                  # moving average via cumulative sum
c = np.concatenate([[0.0], np.cumsum(env, dtype=np.float64)])
idx = np.arange(N); lo = np.clip(idx - k // 2, 0, N); hi = np.clip(idx + k // 2, 0, N)
env = ((c[hi] - c[lo]) / np.maximum(1, hi - lo)).astype(np.float32)
env[:int(5.7 * SR)] = np.maximum(env[:int(5.7 * SR)], db(-24))     # intro card: a little lift
fade = int(2.0 * SR); env[:fade] *= np.linspace(0, 1, fade); env[-fade:] *= np.linspace(1, 0, fade)
m *= (env * db(ref - rms_db(m)))[:, None]

def write(sig, path):
    peak = np.abs(sig).max()
    if peak > 0.89: sig = sig * (0.89 / peak)
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "2", "-i", "-",
                          "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", str(SR), "-c:a", "pcm_s16le", path], stdin=subprocess.PIPE)
    p.stdin.write(sig.astype(np.float32).tobytes()); p.stdin.close(); p.wait()

os.makedirs(os.path.join(V8, "out"), exist_ok=True)
write(speech_mix + m, os.path.join(V8, "out", f"mix_music_{TAKE}.wav"))
write(speech_mix, os.path.join(V8, "out", f"mix_nomusic_{TAKE}.wav"))
print("speech spans", len(speech), "ref", round(ref, 1))
