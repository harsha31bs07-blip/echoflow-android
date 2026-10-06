"""Places the narration lines in the take's real silences and checks none overlaps a command or a reply."""
import json, os, subprocess
V8 = os.path.dirname(os.path.abspath(__file__))
LINES = {
    "n0": "Meet EchoFlow. Teach it once, then just ask.",
    "n1": "We show it once: search for garlic bread, add it, and open the cart.",
    "n2": "One demonstration, saved as a lesson. Now, the exact same request.",
    "n3": "It replays the lesson by itself, and stops at payment. Paying is always up to you.",
    "n4": "Now different words, for the same task.",
    "nC": "It understands the new wording on the phone, and sees the garlic bread is already in the cart.",
    "n5": "Next, a new item, and a quantity it was never shown.",
    "n6": "Two choco lava cakes, from a single garlic bread lesson. It sets the quantity in the cart itself.",
    "n7": "Finally, a dish that doesn't exist.",
    "n8": "It asks, instead of guessing, and stops without adding anything.",
    "n9": "Teach once. Ask any way. Payment stays with you.",
}
AT = {"n0": 0.5, "n1": 28.6, "n2": 59.4, "n3": 76.2, "n4": 109.4, "nC": 123.0, "n5": 157.0, "n6": 173.2,
      "n7": 207.6, "n8": 248.6, "n9": 277.4}

def dur(p):
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p],
                                capture_output=True, text=True).stdout)

def speech_end(p):
    """Where the speech ends (edge-tts adds ~0.85 s of silence at the end)."""
    import re
    log = subprocess.run(["ffmpeg", "-i", p, "-af", "silencedetect=n=-40dB:d=0.2", "-f", "null", "-"], capture_output=True, text=True).stderr
    starts = [float(x) for x in re.findall(r"silence_start: ([\d.]+)", log)]
    ends = [float(x) for x in re.findall(r"silence_end: ([\d.]+)", log)]
    total = dur(p)
    return starts[-1] if starts and ends and abs(ends[-1] - total) < 0.05 else total

ev = json.load(open(os.path.join(V8, "take9", "events.json")))
busy = [(c["feed"] + 0.25, c["feed"] + 0.25 + dur(os.path.join(V8, "cmd", c["key"] + ".mp3"))) for c in ev["cmds"]]
busy += [(r["start"], r["end"]) for r in ev["replies"]]
plan, bad = [], 0
for k, a in AT.items():
    mp3 = os.path.join(V8, "narr", k + ".mp3")
    if not os.path.exists(mp3) or os.path.getsize(mp3) == 0:
        subprocess.run(["python", "-m", "edge_tts", "--voice", "en-IN-PrabhatNeural", "--text", LINES[k], "--write-media", mp3], check=True)
    d = speech_end(mp3); b = a + d
    clash = [(round(x, 2), round(y, 2)) for x, y in busy if x < b + 0.3 and y > a - 0.3]
    bad += bool(clash)
    print(f"{k} {a:6.1f}-{b:6.1f} ({d:.2f}s) {'CLASH ' + str(clash) if clash else 'ok'}")
    plan.append({"key": k, "at": a, "dur": d, "text": LINES[k]})
json.dump(plan, open(os.path.join(V8, "narr", "plan.json"), "w"), indent=1)
print("clashes:", bad)
