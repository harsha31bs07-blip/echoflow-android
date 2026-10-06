"""EchoFlow demo V7 revision: recomposes the delivered V7 export on its own timeline.

Source = the V7 master (only surviving material). The native phone screen is cut back out
of it (x166..734, y54..1278) and re-laid-out with hand-authored focus crops, a lesson
tracker driven by the app's own on-screen status text (OCR timeline), larger speaker-aware
captions and readable disclosures. Intro (<6.0 s) and outro (>=248.967 s) pass through.
Audio is copied untouched, so sync is preserved exactly.

  python v7r.py stills 12 40 185      -> stills/t_<sec>.png
  python v7r.py render out.mp4 [start end]
"""
import math, re, subprocess, sys, os
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

SRC = r"C:\dev\echoflow-android\demo-production\final-v7\EchoFlow_Demo_Premium_Voice_v7.mp4"
SRT = r"C:\dev\echoflow-android\demo-production\final-v7\EchoFlow_Demo_Premium_Voice_v7.srt"
W, H, FPS = 2560, 1440, 30
INTRO_END, OUTRO_START = 6.0, 248.967
PH_X, PH_Y, PH_W, PH_H = 166, 54, 568, 1224          # phone screen inside a V7 frame

# ---- palette (V7 pale / navy / blue / orange) ----
BG1, BG2 = (246, 248, 251), (233, 239, 246)
NAVY, BLUE, ORANGE, MUTED, TEAL, LINE = (18, 40, 75), (31, 111, 178), (214, 110, 22), (88, 102, 124), (15, 118, 110), (214, 222, 233)
RED = (190, 52, 48)
F = r"C:\Windows\Fonts"
def font(name, size): return ImageFont.truetype(os.path.join(F, name), size)
F_BRAND, F_CHAP, F_TITLE, F_SUB = font("segoeuib.ttf", 40), font("segoeuib.ttf", 34), font("segoeuib.ttf", 92), font("segoeui.ttf", 40)
F_CAP, F_CAP2, F_SPK, F_SMALL, F_PILL = font("segoeuib.ttf", 50), font("segoeuib.ttf", 43), font("segoeuib.ttf", 28), font("segoeui.ttf", 30), font("segoeuib.ttf", 30)
F_ROW, F_ROWB, F_HDR, F_NUM = font("segoeui.ttf", 32), font("segoeuib.ttf", 32), font("segoeuib.ttf", 28), font("segoeuib.ttf", 26)

# ---- layout ----
DEV = (92, 84, 696, 1344)                 # device bezel
SCR_X, SCR_Y = 110, 102                   # phone screen position in output
COL_X = 780                               # right column start
TRK = (780, 330, 1360, 1140)              # lesson tracker card
FOC = (1420, 330, 2470, 1090)             # focus panel max box
CAP = (780, 1165, 2470, 1335)             # caption lane
CHAPTERS = [  # (start, label, title, subtitle)  -- times from V7's own headings (OCR)
    (6.0, "01 / TEACH", "Teach the task.", "One demonstration becomes a saved lesson."),
    (72.0, "02 / EXACT REQUEST", "Ask again.", "The same request replays the saved lesson."),
    (110.5, "03 / DIFFERENT WORDS", "Change the words.", "A differently worded request, the same lesson."),
    (146.0, "04 / NEW DISH + QUANTITY", "Change the values.", "A dish and a quantity that were never taught."),
    (196.5, "05 / CLARIFY + STOP", "Keep control.", "Unavailable item: it asks, then stops."),
    (230.0, "06 / REPORT", "An honest report.", "What actually happened in the last run."),
]
# V7's own "Edited for brevity" intervals (its edit list is gone; these were its markers)
CUTS = [(46.5, 48.5), (50.0, 53.5), (55.0, 58.0), (92.0, 94.0), (100.5, 102.5), (127.5, 129.5),
        (131.5, 133.0), (134.5, 136.0), (177.5, 180.0), (209.5, 211.5), (213.0, 215.0)]

# ---- focus shots: (start, rect in phone coords x,y,w,h, highlight rects, note) ----
LISTEN = (0, 560, 568, 520)
SHEET = (0, 860, 568, 300)
BUB_TOP = (52, 96, 516, 330)
BUB_BOT = (52, 770, 516, 340)
CART1 = (8, 462, 552, 150)
CART2 = (8, 462, 552, 270)
PAY = (8, 1062, 552, 150)
QTY_M, QTY_F = (414, 488, 116, 50), (414, 608, 116, 50)
SHOTS = [
    (6.0, LISTEN, [], None),
    (15.2, BUB_BOT, [], None),
    (32.0, BUB_TOP, [], None),
    (35.6, (0, 60, 568, 330), [], None),
    (41.5, BUB_TOP, [], None),
    (44.0, (0, 420, 568, 420), [], None),
    (45.2, BUB_BOT, [], None),
    (48.0, (0, 170, 568, 400), [], None),
    (51.2, (0, 300, 568, 640), [], None),
    (56.2, CART1, [QTY_M], "Taught cart · Margherita × 1"),
    (59.5, BUB_TOP, [], "Lesson saved · 10 steps"),
    (70.5, (20, 600, 528, 260), [], None),
    (72.0, LISTEN, [], None),
    (83.4, BUB_TOP, [], None),
    (100.5, CART1, [QTY_M], "Actual cart · Margherita × 1"),
    (106.5, PAY, [(214, 1068, 350, 124)], "Stops before payment · you pay"),
    (110.5, LISTEN, [], None),
    (121.0, BUB_TOP, [], None),
    (133.5, (0, 110, 568, 520), [QTY_M], "Already in the cart · still × 1"),
    (146.0, LISTEN, [], None),
    (155.4, BUB_TOP, [], None),
    (180.0, CART2, [QTY_F, QTY_M], "Actual cart · Farmhouse × 2 · Margherita × 1"),
    (190.0, PAY, [(214, 1068, 350, 124)], "Stops before payment · you pay"),
    (196.5, LISTEN, [], None),
    (207.6, BUB_TOP, [], None),
    (212.3, (0, 60, 568, 560), [], "Search for “unicorn”: no results"),
    (215.4, BUB_TOP, [], "Asks instead of guessing"),
    (220.8, SHEET, [], "Answer: “Nothing”"),
    (225.6, BUB_TOP, [], "Stopped · nothing added"),
    (230.0, LISTEN, [], None),
    (237.2, (20, 596, 528, 222), [], "Actual report · last run did not succeed"),
]

# ---- lesson tracker (labels = the app's own step names, shortened) ----
STEPS = ["Open Zomato", "Tap “Home”", "Tap the search bar", "Type restaurant", "Open the matching restaurant",
         "Tap “Search” in the menu", "Type dish", "Tap “ADD”", "Tap “Add item”", "Tap “Continue” to the cart"]
SLOT_STEP = {3: "restaurant", 6: "dish"}
RUNS = [  # chapter-level slot values; changed values drawn orange
    dict(t=6.0, mode="teach", vals={"restaurant": "Brik Oven", "dish": "margherita", "qty": "1"}, changed=set()),
    dict(t=72.0, mode="run", vals={"restaurant": "brik oven", "dish": "margherita", "qty": "1"}, changed=set()),
    dict(t=110.5, mode="run", vals={"restaurant": "brik oven", "dish": "margherita", "qty": "1"}, changed=set()),
    dict(t=146.0, mode="run", vals={"restaurant": "brik oven", "dish": "farmhouse", "qty": "2"}, changed={"dish", "qty"}),
    dict(t=196.5, mode="run", vals={"restaurant": "brik oven", "dish": "unicorn", "qty": "1"}, changed={"dish"}),
    dict(t=230.0, mode="report", vals={"restaurant": "brik oven", "dish": "unicorn", "qty": "1"}, changed={"dish"}),
]
# (time, step index reached [0-based], extra) from OCR of the app's "Got it"/"Step n/10" messages
TEACH_EV = [(16.0, 0), (32.5, 1), (36.0, 2), (38.0, 3), (41.5, 4), (45.0, 5), (48.0, 6), (51.0, 7), (53.5, 8), (56.0, 9)]
TEACH_SAVED = 59.5
RUN_EV = {
    72.0: [(84.0, 0), (85.5, 1), (87.5, 2), (89.5, 3), (92.0, 4), (92.5, 5), (95.0, 6), (97.0, 7), (98.5, 8), (99.5, 9), (101.5, "done")],
    110.5: [(121.0, 0), (123.5, 1), (125.5, 2), (127.5, 4), (128.0, 5), (130.0, 6), (131.5, 9), (135.0, "done_existing")],
    146.0: [(159.0, 0), (162.0, 2), (163.0, 3), (164.5, 4), (172.0, 5), (174.0, 6), (176.0, 7), (178.0, 8), (180.0, 9), (184.5, "done")],
    196.5: [(208.0, 0), (209.5, 5), (212.0, 6), (214.0, 7), (215.5, "ask"), (226.0, "stopped")],
}

def ease(x): x = min(1, max(0, x)); return x * x * (3 - 2 * x)

def chapter_at(t):
    c = CHAPTERS[0]
    for ch in CHAPTERS:
        if t >= ch[0]: c = ch
    return c

def run_at(t):
    r = RUNS[0]
    for x in RUNS:
        if t >= x["t"]: r = x
    return r

def tracker_state(t):
    """-> (header_status, per-step states, payment_state). states: pend/done/cur/skip/unseen/fail"""
    run = run_at(t)
    st = ["pend"] * 10
    if run["mode"] == "teach":
        n = sum(1 for et, _ in TEACH_EV if t >= et)
        st = ["done"] * n + ["hidden"] * (10 - n)
        if n and t < TEACH_SAVED: st[n - 1] = "new"
        if t >= TEACH_SAVED: return "Saved · 10 steps", st, "teach_pay"
        taps = n - 1   # the app counts taps; "Open Zomato" is the starting app
        if not n: return "Waiting for the demonstration", st, "hidden"
        if not taps: return "Recording · starts in Zomato", st, "hidden"
        return "Recording · %d step%s so far" % (taps, "" if taps == 1 else "s"), st, "hidden"
    if run["mode"] == "report":
        st = ["done"] * 7 + ["fail", "pend", "pend"]
        return "Last run · stopped at step 8 of 10", st, "pend"
    evs = RUN_EV[run["t"]]
    cur, final = -1, None
    for et, s in evs:
        if t >= et:
            if isinstance(s, int): cur = s
            else: final = s
    status = "Waiting for the request"
    pay = "pend"
    if cur >= 0:
        for i in range(10):
            st[i] = "done" if i < cur else ("cur" if i == cur else "pend")
        # steps passed without an observed status message (edited out) stay neutral
        seen = {s for et, s in evs if isinstance(s, int) and t >= et}
        for i in range(cur):
            if i not in seen: st[i] = "unseen"
        status = "Replaying · step %d of 10" % (cur + 1)
    if final in ("done", "done_existing"):
        st = [x if x != "cur" else "done" for x in st]
        if final == "done_existing":
            st[7], st[8] = "skip", "skip"
        status, pay = "Cart ready · payment is yours", "you"
    elif final == "ask":
        st[7] = "ask"; status = "Not found · asking you"
    elif final == "stopped":
        st[7] = "fail"; st[8] = st[9] = "pend"; status = "Stopped at step 8 · nothing added"
    return status, st, pay

# ---- captions ----
def load_srt():
    raw = open(SRT, encoding="utf-8").read().strip().split("\n\n")
    cues = []
    def ts(s):
        h, m, r = s.split(":"); sec, ms = r.split(",")
        return int(h) * 3600 + int(m) * 60 + int(sec) + int(ms) / 1000
    for blk in raw:
        lines = blk.strip().splitlines()
        a, b = [ts(x.strip()) for x in lines[1].split("-->")]
        text = " ".join(lines[2:])
        spk, _, body = text.partition(": ")
        cues.append([a, b, spk, body])
    # merge consecutive EchoFlow fragments of one reply into one caption (max ~2 lines)
    merged = []
    for c in cues:
        open_end = merged and not re.search(r'[.?!]["”]?$', merged[-1][3])
        if merged and c[2] == merged[-1][2] == "EchoFlow" and c[0] - merged[-1][1] < 0.05 and (open_end or len(merged[-1][3]) + len(c[3]) < 110):
            merged[-1][1] = c[1]; merged[-1][3] += " " + c[3]
        else:
            merged.append(list(c))
    return merged
CUES = load_srt()
SPK = {"Narration": ("NARRATOR", BLUE), "Request": ("SPOKEN REQUEST", ORANGE), "EchoFlow": ("ECHOFLOW REPLY", TEAL)}

def wrap(draw, text, fnt, maxw):
    words, lines, cur = text.split(), [], ""
    for w_ in words:
        trial = (cur + " " + w_).strip()
        if draw.textlength(trial, font=fnt) <= maxw: cur = trial
        else: lines.append(cur); cur = w_
    if cur: lines.append(cur)
    return lines

# ---- static drawing helpers ----
def background():
    y = np.linspace(0, 1, H)[:, None, None]; x = np.linspace(0, 1, W)[None, :, None]
    g = np.clip(0.65 * y + 0.35 * x, 0, 1)
    img = (np.array(BG1) * (1 - g) + np.array(BG2) * g).astype(np.uint8)
    return img

BASE_BG = background()

def rounded_mask(w, h, r):
    m = Image.new("L", (w, h), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    return np.array(m, dtype=np.float32)[..., None] / 255.0
SCR_MASK = rounded_mask(PH_W, PH_H, 44)

_cache = {}
def static_layer(t):
    """Everything that only changes at discrete events, as an RGB array."""
    ch = chapter_at(t); status, st, pay = tracker_state(t); run = run_at(t)
    key = (ch[0], status, tuple(st), pay, run["t"])
    if key in _cache: return _cache[key]
    im = Image.fromarray(BASE_BG.copy()); d = ImageDraw.Draw(im)
    # brand + device
    d.text((COL_X, 40), "EchoFlow", font=F_BRAND, fill=NAVY)
    d.rounded_rectangle(DEV, 70, fill=(14, 27, 51))
    # chapter heading
    d.text((COL_X, 112), ch[1], font=F_CHAP, fill=BLUE)
    d.text((COL_X, 150), ch[2], font=F_TITLE, fill=NAVY)
    d.text((COL_X + 4, 262), ch[3], font=F_SUB, fill=MUTED)
    idx = [c[0] for c in CHAPTERS].index(ch[0])
    for i in range(6):
        cx = 2250 + i * 38
        d.ellipse((cx - 9, 60 - 9, cx + 9, 60 + 9), fill=BLUE if i <= idx else LINE)
    # tracker card
    x0, y0, x1, y1 = TRK
    d.rounded_rectangle((x0 + 4, y0 + 6, x1 + 4, y1 + 6), 30, fill=(222, 229, 238))
    d.rounded_rectangle(TRK, 30, fill=(255, 255, 255), outline=LINE, width=2)
    d.text((x0 + 34, y0 + 26), "SAVED LESSON", font=F_HDR, fill=BLUE)
    scol = ORANGE if ("Not found" in status or "Stopped" in status or "stopped" in status) else (TEAL if ("ready" in status or "Saved" in status) else NAVY)
    d.text((x0 + 34, y0 + 62), status, font=F_ROWB, fill=scol)
    # slot chips
    cx = x0 + 34; cy = y0 + 114
    for k in ("restaurant", "dish", "qty"):
        if run["mode"] == "teach" and k == "dish" and all(s == "hidden" for s in st[:7]): pass
        label = "×" + run["vals"][k] if k == "qty" else run["vals"][k]
        ch_col = ORANGE if k in run["changed"] else NAVY
        tw = d.textlength(label, font=F_ROWB)
        d.rounded_rectangle((cx, cy, cx + tw + 28, cy + 46), 23, fill=(253, 240, 228) if k in run["changed"] else (238, 243, 249))
        d.text((cx + 14, cy + 4), label, font=F_ROWB, fill=ch_col)
        cx += tw + 40
    ry = y0 + 184
    for i, name in enumerate(STEPS):
        s = st[i]; y = ry + i * 54
        if s == "hidden":
            continue
        if s in ("cur", "new"):
            d.rounded_rectangle((x0 + 18, y - 6, x1 - 18, y + 48), 14, fill=(225, 237, 250))
        if s == "ask":
            d.rounded_rectangle((x0 + 18, y - 6, x1 - 18, y + 48), 14, fill=(253, 240, 228))
        if s == "fail":
            d.rounded_rectangle((x0 + 18, y - 6, x1 - 18, y + 48), 14, fill=(250, 230, 228))
        circ = {"done": BLUE, "new": BLUE, "cur": BLUE, "pend": LINE, "unseen": LINE, "skip": LINE, "ask": ORANGE, "fail": RED}[s]
        d.ellipse((x0 + 34, y + 2, x0 + 72, y + 40), fill=circ)
        num = str(i + 1)
        d.text((x0 + 53 - d.textlength(num, font=F_NUM) / 2, y + 5), num, font=F_NUM, fill=(255, 255, 255) if s not in ("pend", "unseen", "skip") else MUTED)
        txt = name
        if i in SLOT_STEP:
            txt = name + "  " + run["vals"][SLOT_STEP[i]]
        col = NAVY if s in ("done", "new", "cur") else (ORANGE if s == "ask" else (RED if s == "fail" else MUTED))
        fnt = F_ROWB if s in ("cur", "new", "ask", "fail") else F_ROW
        if i in SLOT_STEP:
            base, val = name + "  ", run["vals"][SLOT_STEP[i]]
            d.text((x0 + 88, y + 2), base, font=fnt, fill=col)
            vx = x0 + 88 + d.textlength(base, font=fnt)
            vcol = ORANGE if (SLOT_STEP[i] in run["changed"] and s not in ("pend",)) else col
            d.text((vx, y + 2), val, font=F_ROWB, fill=vcol)
        else:
            d.text((x0 + 88, y + 2), txt, font=fnt, fill=col)
        tag = {"skip": "already in cart", "fail": "stopped here", "ask": "not found"}.get(s)
        if tag:
            tw = d.textlength(tag, font=F_NUM)
            d.text((x1 - 34 - tw, y + 6), tag, font=F_NUM, fill=RED if s == "fail" else (ORANGE if s == "ask" else MUTED))
    # payment row
    if pay != "hidden":
        y = ry + 10 * 54 + 10
        d.line((x0 + 34, y - 8, x1 - 34, y - 8), fill=LINE, width=2)
        on = pay in ("you", "teach_pay")
        d.text((x0 + 40, y + 6), "Payment", font=F_ROWB, fill=ORANGE if on else MUTED)
        msg = "yours · never automated" if pay != "pend" else "left to you"
        d.text((x0 + 200, y + 6), msg, font=F_ROW, fill=NAVY if on else MUTED)
    # disclosure footer
    disc = "Recorded phone sections, edited for length  ·  Prerecorded speech supplied to Android speech recognition"
    tw = d.textlength(disc, font=F_SMALL)
    d.text(((CAP[0] + CAP[2]) / 2 - tw / 2, 1378), disc, font=F_SMALL, fill=MUTED)
    arr = np.array(im)
    _cache[key] = arr
    if len(_cache) > 64: _cache.pop(next(iter(_cache)))
    return arr

_capcache = {}
def caption_layer(cue):
    k = id(cue)
    if k in _capcache: return _capcache[k]
    x0, y0, x1, y1 = CAP
    im = Image.new("RGBA", (x1 - x0, y1 - y0), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    label, col = SPK.get(cue[2], (cue[2].upper(), MUTED))
    fnt, lh = F_CAP, 62
    lines = wrap(d, cue[3], fnt, x1 - x0 - 80)
    if len(lines) > 2:
        fnt, lh = F_CAP2, 54
        lines = wrap(d, cue[3], fnt, x1 - x0 - 80)
    th = 40 + lh * len(lines)
    top = (y1 - y0 - th) // 2
    lw = d.textlength(label, font=F_SPK)
    d.text(((x1 - x0) / 2 - lw / 2, top), label, font=F_SPK, fill=col + (255,))
    for i, ln in enumerate(lines):
        w_ = d.textlength(ln, font=fnt)
        d.text(((x1 - x0) / 2 - w_ / 2, top + 40 + i * lh), ln, font=fnt, fill=NAVY + (255,))
    a = np.array(im).astype(np.float32)
    _capcache[k] = a
    return a

def pill(text, fill, fg, fnt=F_PILL):
    tmp = ImageDraw.Draw(Image.new("RGB", (4, 4)))
    tw = int(tmp.textlength(text, font=fnt))
    im = Image.new("RGBA", (tw + 48, 52), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, tw + 47, 51), 26, fill=fill + (255,))
    d.text((24, 6), text, font=fnt, fill=fg + (255,))
    return np.array(im).astype(np.float32)
_pills = {}
def get_pill(text, fill, fg):
    k = (text, fill, fg)
    if k not in _pills: _pills[k] = pill(text, fill, fg)
    return _pills[k]

def blend(dst, src_rgba, x, y, alpha=1.0):
    h, w = src_rgba.shape[:2]
    x0, y0 = max(0, x), max(0, y); x1, y1 = min(W, x + w), min(H, y + h)
    if x1 <= x0 or y1 <= y0: return
    s = src_rgba[y0 - y:y1 - y, x0 - x:x1 - x]
    a = s[..., 3:4] / 255.0 * alpha
    reg = dst[y0:y1, x0:x1].astype(np.float32)
    dst[y0:y1, x0:x1] = (reg * (1 - a) + s[..., :3] * a).astype(np.uint8)

# ---- focus panel geometry ----
def fit(rect):
    _, _, w, h = rect
    mw, mh = FOC[2] - FOC[0], FOC[3] - FOC[1]
    s = min(mw / w, mh / h)
    pw, ph = w * s, h * s
    cx, cy = (FOC[0] + FOC[2]) / 2, FOC[1] + ph / 2 + (mh - ph) * 0.0
    return s, (cx - pw / 2, FOC[1], pw, ph)

def clamp(r):
    x, y, w, h = r
    x0, x1 = max(x, 8), min(x + w, 560)
    return (x0, y, x1 - x0, h)
SHOTS = [(a, clamp(r), hl, n) for a, r, hl, n in SHOTS]

def shot_at(t):
    i = 0
    for j, s in enumerate(SHOTS):
        if t >= s[0]: i = j
    return i

TRANS = 0.5
def focus_geom(t):
    i = shot_at(t); cur = SHOTS[i]
    rect = np.array(cur[1], dtype=float)
    if i > 0 and t - cur[0] < TRANS:
        prev = np.array(SHOTS[i - 1][1], dtype=float)
        k = ease((t - cur[0]) / TRANS)
        rect = prev * (1 - k) + rect * k
    return i, rect


# ---- V7's baked privacy strips: solid RGB(16,34,56) bars. The pixels under them are gone, so each strip is
# filled by interpolating the rows just above and below it (reads as empty UI, reveals nothing).
STRIP_LO, STRIP_HI = np.array([12, 30, 52]), np.array([20, 38, 60])   # RGB (16,34,56) ±4; Zomato's Gold banner (21,32,59) must not match
def clean_strips(scr):
    m = cv2.inRange(scr, STRIP_LO, STRIP_HI)
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, _, st, _ = cv2.connectedComponentsWithStats(m, 8)
    if n <= 1: return scr
    scr = scr.copy()
    hh, ww = scr.shape[:2]
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if not (w >= 60 and 12 <= h <= 90 and a >= 0.85 * w * h): continue
        x0, x1 = max(0, x - 4), min(ww, x + w + 4)
        y0, y1 = max(1, y - 4), min(hh - 2, y + h + 4)
        top = np.median(scr[max(0, y0 - 6):y0, x0:x1], axis=0).astype(np.float32)
        bot = np.median(scr[y1:min(hh, y1 + 6), x0:x1], axis=0).astype(np.float32)
        # per column, take the side that looks like background (closest to the band's dominant colour),
        # so buttons/icons touching a strip edge are not smeared into it
        dom = np.median(np.concatenate([top, bot]), axis=0)
        dt = np.abs(top - dom).sum(1); db = np.abs(bot - dom).sum(1)
        k = np.linspace(0, 1, y1 - y0)[:, None, None]
        lerp = top[None] * (1 - k) + bot[None] * k
        side = np.where((dt <= db)[:, None], top, bot)
        uniform = np.mean((dt < 45) & (db < 45)) > 0.15   # the background shows on both sides somewhere
        mixed = (uniform & (np.minimum(dt, db) < 45) & (np.abs(dt - db) > 60))[None, :, None]       # one side is foreground: use the background side
        fill = np.where(mixed, side[None].repeat(y1 - y0, 0), lerp).astype(np.uint8)
        # glyph edges above/below would streak vertically; a wide median keeps the background, not the strokes
        pad = 20
        blk = np.concatenate([np.repeat(fill[:, :1], pad, 1), fill, np.repeat(fill[:, -1:], pad, 1)], 1)
        blk = cv2.medianBlur(blk, 31)[:, pad:-pad]
        scr[y0:y1, x0:x1] = blk
    return scr

def compose(frame, t):
    out = static_layer(t).copy()
    src = clean_strips(frame[PH_Y:PH_Y + PH_H, PH_X:PH_X + PH_W])
    scr = src.astype(np.float32)
    reg = out[SCR_Y:SCR_Y + PH_H, SCR_X:SCR_X + PH_W].astype(np.float32)
    out[SCR_Y:SCR_Y + PH_H, SCR_X:SCR_X + PH_W] = (reg * (1 - SCR_MASK) + scr * SCR_MASK).astype(np.uint8)
    # focus panel
    i, rect = focus_geom(t)
    s, (px, py, pw, ph) = fit(rect)
    px, py, pw, ph = int(round(px)), int(round(py)), int(round(pw)), int(round(ph))
    M = np.array([[s, 0, -rect[0] * s], [0, s, -rect[1] * s]], dtype=np.float32)
    zoom = cv2.warpAffine(src, M, (pw, ph), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_REPLICATE)
    m = rounded_mask(pw, ph, 26)
    # soft shadow + border
    cv2.rectangle(out, (px + 6, py + 10), (px + pw + 6, py + ph + 10), (222, 229, 238), -1)
    reg = out[py:py + ph, px:px + pw].astype(np.float32)
    out[py:py + ph, px:px + pw] = (reg * (1 - m) + zoom.astype(np.float32) * m).astype(np.uint8)
    # highlights and note (only once the shot has settled)
    shot = SHOTS[i]
    settle = ease((t - shot[0] - TRANS) / 0.35)
    if settle > 0:
        for (hx, hy, hw, hh) in shot[2]:
            a = (int(px + (hx - rect[0]) * s), int(py + (hy - rect[1]) * s))
            b = (int(px + (hx + hw - rect[0]) * s), int(py + (hy + hh - rect[1]) * s))
            ov = out.copy()
            cv2.rectangle(ov, a, b, ORANGE, 7, lineType=cv2.LINE_AA)
            out[:] = cv2.addWeighted(ov, settle, out, 1 - settle, 0)
        if shot[3]:
            p = get_pill(shot[3], ORANGE if shot[2] or "Stop" in shot[3] else NAVY, (255, 255, 255))
            blend(out, p, px + pw // 2 - p.shape[1] // 2, py + ph + 22, settle)
    lbl = get_pill("Zoom of the recorded phone screen", (236, 241, 247), MUTED)
    if not shot[3] or settle <= 0:
        blend(out, lbl, px + pw // 2 - lbl.shape[1] // 2, py + ph + 22, 1.0)
    # edited marker
    for a_, b_ in CUTS:
        if a_ <= t < b_:
            al = min(1, (t - a_) / 0.2, (b_ - t) / 0.2)
            p = get_pill("Edited for length · waiting removed", (255, 255, 255), MUTED)
            blend(out, p, SCR_X + PH_W // 2 - p.shape[1] // 2, DEV[3] + 20, al)
    # caption
    for c in CUES:
        if c[0] - 0.12 <= t < c[1] + 0.12:
            al = min(1, (t - (c[0] - 0.12)) / 0.12, (c[1] + 0.12 - t) / 0.12)
            blend(out, caption_layer(c), CAP[0], CAP[1], al)
            break
    return out

# ---- intro / outro: pass through, add a readable disclosure ----
DISC_INTRO = pill("Recorded phone sections, edited for length · Prerecorded speech supplied to Android speech recognition", (255, 255, 255), NAVY, F_SMALL)
def passthrough(frame, t):
    out = frame.copy()
    # replace V7's small baked caption (label y1284-1295, text y1329-1363) with the large caption style
    band = out[1255:1262].astype(np.float32).mean(0)
    out[1265:1400] = band.astype(np.uint8)[None]
    if t < INTRO_END:
        blend(out, DISC_INTRO, 150, 1190, 1.0)   # covers V7's 18-px disclosure line (y~1205-1228)
    else:
        al = min(1, max(0, (t - OUTRO_START - 0.8) / 0.6))
        blend(out, DISC_INTRO, 150, 1190, al)
    for c in CUES:
        if c[0] - 0.12 <= t < c[1] + 0.12:
            al = min(1, (t - (c[0] - 0.12)) / 0.12, (c[1] + 0.12 - t) / 0.12)
            lay = caption_layer(c)
            blend(out, lay, W // 2 - lay.shape[1] // 2, 1236, al)
            break
    return out

def frames(start, end):
    cmd = ["ffmpeg", "-v", "error", "-ss", f"{start:.3f}", "-i", SRC, "-t", f"{end - start:.3f}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=W * H * 3 * 2)
    n = 0
    while True:
        buf = p.stdout.read(W * H * 3)
        if len(buf) < W * H * 3: break
        yield start + n / FPS, np.frombuffer(buf, np.uint8).reshape(H, W, 3)
        n += 1

def render_frame(fr, t):
    return passthrough(fr, t) if (t < INTRO_END or t >= OUTRO_START) else compose(fr, t)

if __name__ == "__main__":
    mode = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    if mode == "stills":
        os.makedirs(os.path.join(here, "stills"), exist_ok=True)
        for a in sys.argv[2:]:
            t = float(a)
            fr = next(frames(t, t + 0.05))[1]
            Image.fromarray(render_frame(fr, t)).save(os.path.join(here, "stills", f"t_{a}.png"))
            print("still", a)
    elif mode == "render":
        out = sys.argv[2]
        start = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
        end = float(sys.argv[4]) if len(sys.argv) > 4 else 255.0
        enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
                                "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "17", "-pix_fmt", "yuv420p",
                                "-profile:v", "high", "-movflags", "+faststart", out], stdin=subprocess.PIPE)
        n = 0
        for t, fr in frames(start, end):
            enc.stdin.write(render_frame(fr, t).tobytes())
            n += 1
            if n % 300 == 0: print(f"{t:7.1f}s  frames={n}", flush=True)
        enc.stdin.close(); enc.wait()
        print("done", n, "frames")
