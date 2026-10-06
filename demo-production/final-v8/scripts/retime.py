"""Moves every hand-set time in v8compose.py (chapters, tracker, zooms) and the narration plan from take9 to a
new take of the same script, by matching anchors (commands, replies, on-screen step messages).

  python retime.py take11      -> v8compose_take11.py + narr/plan_take11.json, with a clash report
"""
import json, os, re, subprocess, sys

V8 = os.path.dirname(os.path.abspath(__file__))
KEYS = ["a1", "a2", "b1", "c1", "d1", "e1", "e2", "f1"]
RUNS = ["a1", "b1", "c1", "d1", "e1", "f1"]

def ocr_spans(take):
    rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(V8, take, "ocr.tsv"), encoding="utf-8")]
    return [(float(t), int(y), txt) for t, x, y, xe, ye, txt in rows]

def anchors(take):
    ev = json.load(open(os.path.join(V8, take, "events.json")))
    a = {}
    for c in ev["cmds"]: a["cmd_" + c["key"]] = c["feed"]
    for i, r in enumerate(ev["replies"]): a[f"rep{i}_s"] = r["start"]; a[f"rep{i}_e"] = r["end"]
    feeds = {c["key"]: c["feed"] for c in ev["cmds"]}
    bounds = [feeds[k] for k in RUNS] + [1e9]
    rows = ocr_spans(take)
    def first(pattern, lo, hi, ymax=None):
        for t, y, txt in rows:
            if lo <= t < hi and re.match(pattern, txt) and (ymax is None or y < ymax): return t
        return None
    for r, k in enumerate(RUNS):
        lo, hi = bounds[r], bounds[r + 1]
        for n in range(1, 5):
            t = first(rf"Step {n}/4", lo, hi)
            if t is not None: a[f"{k}_step{n}"] = t
        for name, pat, ymax in (("results", r"(Showing \d+ results|Oops)", 200), ("cart", r"Cart$", 120),
                                ("typed", r"Got it: typed", None), ("added", r"Got it: tapped btn add", None),
                                ("carttap", r"Got it: tapped cart", None)):
            t = first(pat, lo, hi, ymax)
            if t is not None: a[f"{k}_{name}"] = t
    return a

def mapper(a9, a1):
    pairs = sorted((a9[n], a1[n]) for n in a9 if n in a1)
    def m(t):
        prev = [p for p in pairs if p[0] <= t + 1e-6]
        if not prev: return round(t, 2)
        t9, t1 = prev[-1]
        return round(t1 + (t - t9), 2)
    return m, pairs

def main(take):
    a9, a1 = anchors("take9"), anchors(take)
    missing = [n for n in a9 if n not in a1]
    if missing: print("anchors missing in", take, ":", missing)
    m, pairs = mapper(a9, a1)
    src = open(os.path.join(V8, "v8compose.py"), encoding="utf-8").read()
    src = src.replace('TAKE = os.path.join(HERE, "take9")', f'TAKE = os.path.join(HERE, "{take}")')

    def retime_block(start_marker, end_marker, pattern):
        nonlocal src
        i = src.index(start_marker); j = src.index(end_marker, i)
        block = src[i:j]
        block = re.sub(pattern, lambda mm: mm.group(1) + f"{m(float(mm.group(2))):.2f}", block)
        src = src[:i] + block + src[j:]
    # (t, ...) tuples at line starts and (t, n) pairs inside lists
    retime_block("CHAPTERS = [", "]\n\n# ---- lesson tracker", r"(\(\s*)(\d+\.\d+)")
    retime_block("RUNS = [", "]\n# Teaching", r"(t=)(\d+\.\d+)")
    retime_block("TEACH_EV = [", "\n", r"(\(\s*)(\d+\.\d+)")
    retime_block("TEACH_SAVED = ", "\n", r"(= )(\d+\.\d+)")
    retime_block("RUN_EV = {", "}\n\n# ----", r"(\(\s*|\n    )(\d+\.\d+)")
    retime_block("SHOTS = [", "]\n\ndef ease", r"(\(\s*)(\d+\.\d+)")
    # The report's end decides the outro and the length.
    ev1 = json.load(open(os.path.join(V8, take, "events.json")))
    rep_end = ev1["replies"][-1]["end"]
    outro = round(rep_end + 0.6, 2); end = round(outro + 7.0, 1)
    src = re.sub(r"END = \d+\.\d+", f"END = {end}", src, count=1)
    src = re.sub(r"OUTRO_START, OUTRO_FADE = \d+\.\d+", f"OUTRO_START, OUTRO_FADE = {outro}", src, count=1)
    src = src.replace('os.path.join(HERE, "narr", "plan.json")', f'os.path.join(HERE, "narr", "plan_{take}.json")')
    open(os.path.join(V8, f"v8compose_{take}.py"), "w", encoding="utf-8").write(src)

    # Narration: same lines, moved, then checked against the new take's speech.
    plan = json.load(open(os.path.join(V8, "narr", "plan.json")))
    for n in plan:
        n["at"] = m(n["at"]) if n["key"] != "n9" else round(outro + 0.4, 2)
    dur = lambda p: float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p], capture_output=True, text=True).stdout)
    busy = [(c["feed"] + 0.25, c["feed"] + 0.25 + dur(os.path.join(V8, "cmd", c["key"] + ".mp3"))) for c in ev1["cmds"]]
    busy += [(r["start"], r["end"]) for r in ev1["replies"]]
    bad = 0
    for n in plan:
        a, b = n["at"], n["at"] + n["dur"]
        clash = [(round(x, 1), round(y, 1)) for x, y in busy if x < b + 0.3 and y > a - 0.3]
        if clash:
            # Slide it into the free gap just before the clashing speech, if it fits.
            first = min(x for x, y in clash)
            prev_end = max([y for x, y in busy if y <= first - 0.3] + [0])
            if first - 0.35 - n["dur"] >= prev_end + 0.3:
                n["at"] = round(first - 0.35 - n["dur"], 2); clash = []
            else: bad += 1
        print(f'{n["key"]} {n["at"]:7.2f} {"CLASH " + str(clash) if clash else "ok"}')
    json.dump(plan, open(os.path.join(V8, "narr", f"plan_{take}.json"), "w"), indent=1)
    print("length", end, "outro", outro, "narration clashes", bad, "anchors matched", len(pairs))

if __name__ == "__main__":
    main(sys.argv[1])
