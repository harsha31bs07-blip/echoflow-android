"""EchoFlow demo V8: composes the one continuous take (take9) into the presentation layout.

Nothing in the take is cut, reordered or retimed: output time == take time, 0 .. END.
Over the take: chapter headings, a lesson tracker (timings from EchoFlow's log + OCR of its
own status messages), focus zooms of the real screen, speaker captions, and V7's animated
title cards over the take's idle first/last seconds.

  python v8compose.py stills 12 40 95
  python v8compose.py render out.mp4 [start end]
"""
import json, os, subprocess, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
TAKE = os.path.join(HERE, "take9")
RAW = os.path.join(TAKE, "raw.mkv")
V7 = r"C:\dev\echoflow-android\demo-production\final-v7\EchoFlow_Demo_Premium_Voice_v7.mp4"
W, H, FPS = 2560, 1440, 30
SW, SH = 1080, 2340                       # recording size
END = 284.0
INTRO_END, INTRO_FADE = 5.75, 0.5         # V7 intro card over the idle home screen
OUTRO_START, OUTRO_FADE = 277.0, 0.5      # V7 outro card after the report
V7_OUTRO = 248.967

# ---- palette / fonts (V7.1) ----
BG1, BG2 = (246, 248, 251), (233, 239, 246)
NAVY, BLUE, ORANGE, MUTED, TEAL, LINE, RED = (18, 40, 75), (31, 111, 178), (214, 110, 22), (88, 102, 124), (15, 118, 110), (214, 222, 233), (190, 52, 48)
FD = r"C:\Windows\Fonts"
def font(n, s): return ImageFont.truetype(os.path.join(FD, n), s)
F_BRAND, F_CHAP, F_TITLE, F_SUB = font("segoeuib.ttf", 40), font("segoeuib.ttf", 34), font("segoeuib.ttf", 92), font("segoeui.ttf", 40)
F_CAP, F_CAP2, F_SPK, F_SMALL, F_PILL = font("segoeuib.ttf", 50), font("segoeuib.ttf", 43), font("segoeuib.ttf", 28), font("segoeui.ttf", 29), font("segoeuib.ttf", 30)
F_ROW, F_ROWB, F_HDR, F_NUM = font("segoeui.ttf", 34), font("segoeuib.ttf", 34), font("segoeuib.ttf", 28), font("segoeuib.ttf", 28)

# ---- layout ----
DEV = (92, 84, 696, 1344)
SCR_W, SCR_H = 565, 1224
SCR_X, SCR_Y = 102, 102
COL_X = 780
TRK = (780, 330, 1360, 1050)
FOC = (1420, 330, 2470, 1090)
CAP = (780, 1165, 2470, 1335)
DISCLOSURE = "One continuous recording of the phone, no cuts  ·  Commands: a synthesized voice fed to Android speech recognition"

CHAPTERS = [
    (0.0, "01 / TEACH", "Teach the task.", "One demonstration, by voice and taps, becomes a lesson."),
    (65.5, "02 / EXACT REQUEST", "Ask again.", "The same request replays the lesson."),
    (112.0, "03 / DIFFERENT WORDS", "Change the words.", "Different wording, the same lesson."),
    (161.5, "04 / NEW ITEM + QUANTITY", "Change the values.", "An item and a quantity it was never shown."),
    (210.5, "05 / STUCK: IT ASKS", "Keep control.", "An unknown dish: it asks, then stops."),
    (256.5, "06 / REPORT", "An honest report.", "What actually happened in the last run."),
]

# ---- lesson tracker ----
STEPS = ["Open Domino's", "Type the item", "Tap \u201cAdd\u201d", "Open the cart"]
SLOT_STEP = {1: "item"}
RUNS = [
    dict(t=0.0, mode="teach", vals={"item": "garlic bread", "qty": "1"}, changed=set()),
    dict(t=65.5, mode="run", vals={"item": "garlic bread", "qty": "1"}, changed=set()),
    dict(t=112.0, mode="run", vals={"item": "garlic bread", "qty": "1"}, changed=set()),
    dict(t=161.5, mode="run", vals={"item": "choco lava cake", "qty": "2"}, changed={"item", "qty"}),
    dict(t=210.5, mode="run", vals={"item": "unicorn pizza", "qty": "1"}, changed={"item"}),
    dict(t=256.5, mode="report", vals={"item": "unicorn pizza", "qty": "1"}, changed={"item"}),
]
# Teaching: the app opened (step 1), then EchoFlow's "Got it" captions; saved on "Learned".
TEACH_EV = [(21.8, 0), (32.5, 1), (39.0, 2), (45.5, 3)]
TEACH_SAVED = 50.6
# Replays: EchoFlow's own "Step n/4" status messages (OCR of the recording).
RUN_EV = {
    65.5: [(74.0, 0), (80.5, 1), (86.5, 2), (88.5, 3), (95.7, "done")],
    112.0: [(121.0, 0), (126.5, 1), (133.5, 2), (137.0, 3), (141.5, "done_existing")],
    161.5: [(171.0, 0), (176.5, 1), (183.5, 2), (185.0, 3), (194.2, "done")],
    210.5: [(220.0, 0), (227.0, 1), (232.9, "ask"), (241.1, "stopped")],
}

# ---- focus shots (recording coordinates): (start, rect, highlights, note) ----
PANEL = (140, 1440, 940, 720)
SEARCH = (0, 130, 1080, 880)
CART = (0, 830, 1080, 420)
PAY = (0, 2090, 1080, 230)
ADD_BTN = (770, 838, 280, 132)
QTY_G, QTY_C = (600, 885, 210, 90), (600, 1077, 210, 90)
PAY_BTN = (480, 2122, 585, 155)
SHOTS = [
    (0.0, PANEL, [], None),
    (31.0, SEARCH, [], None),
    (37.0, SEARCH, [ADD_BTN], "Tapping “Add” by hand"),
    (45.4, SEARCH, [], None),
    (47.8, CART, [QTY_G], "Taught cart · Garlic Breadsticks × 1"),
    (50.6, PANEL, [], "Lesson saved · 4 steps"),
    (64.0, PANEL, [], None),
    (85.0, SEARCH, [ADD_BTN], None),
    (91.6, SEARCH, [], None),
    (94.0, CART, [QTY_G], "Actual cart · Garlic Breadsticks × 1"),
    (98.0, PAY, [PAY_BTN], "Stops before payment · you pay"),
    (103.5, PANEL, [], None),
    (131.6, SEARCH, [ADD_BTN], "Already in the cart: “− 1 +”"),
    (137.6, SEARCH, [], None),
    (140.0, CART, [QTY_G], "Still × 1 · not added again"),
    (145.0, PANEL, [], None),
    (181.6, SEARCH, [ADD_BTN], None),
    (188.2, SEARCH, [], None),
    (192.8, CART, [QTY_C], "Actual cart · Choco Lava Cake × 2"),
    (199.0, PAY, [PAY_BTN], "Stops before payment · you pay"),
    (203.5, PANEL, [], None),
    (230.6, SEARCH, [], "Search: no result for “unicorn pizza”"),
    (232.8, PANEL, [], "Asks instead of guessing"),
    (241.0, PANEL, [], "Stopped · nothing added"),
    (256.0, PANEL, [], None),
    (262.8, PANEL, [], "Actual report · last run did not succeed"),
]

def ease(x): x = min(1, max(0, x)); return x * x * (3 - 2 * x)
def at(seq, t, key=lambda e: e[0]):
    cur = seq[0]
    for e in seq:
        if t >= key(e): cur = e
    return cur

def tracker_state(t):
    run = at(RUNS, t, key=lambda r: r["t"])
    if run["mode"] == "teach":
        n = sum(1 for et, _ in TEACH_EV if t >= et)
        st = ["done"] * n + ["hidden"] * (4 - n)
        if n and t < TEACH_SAVED: st[n - 1] = "new"
        if t >= TEACH_SAVED: return "Saved \u00b7 4 steps", st, "teach_pay"
        if not n: return "Waiting for the demonstration", st, "hidden"
        taps = n - 1
        return ("Recording \u00b7 Domino's opened" if not taps else "Recording \u00b7 %d step%s so far" % (taps, "" if taps == 1 else "s")), st, "hidden"
    if run["mode"] == "report":
        return "Last run \u00b7 stopped at step 3 of 4", ["done", "done", "fail", "pend"], "pend"
    evs = RUN_EV[run["t"]]
    cur, final = -1, None
    for et, s in evs:
        if t >= et:
            if isinstance(s, int): cur = s
            else: final = s
    st, pay, status = ["pend"] * 4, "pend", "Waiting for the request"
    if cur >= 0:
        st = ["done" if i < cur else ("cur" if i == cur else "pend") for i in range(4)]
        status = "Replaying \u00b7 step %d of 4" % (cur + 1)
    if final in ("done", "done_existing"):
        st = ["done"] * 4
        if final == "done_existing": st[2] = "skip"
        status, pay = "Cart ready \u00b7 payment is yours", "you"
    elif final == "ask":
        st[2] = "ask"; status = "Not found \u00b7 asking you"
    elif final == "stopped":
        st[2] = "fail"; st[3] = "pend"; status = "Stopped \u00b7 nothing added"
    return status, st, pay

# ---- captions: commands (as spoken), EchoFlow replies (logged text, spoken span), narration ----
EV = json.load(open(os.path.join(TAKE, "events.json"), encoding="utf-8"))
CMD_TEXT = {"a1": "Order a Garlic Bread on Domino's.", "a2": "Yes.", "b1": "Order a Garlic Bread on Domino's.",
            "c1": "Get me garlic bread from Domino's.", "d1": "Order two Choco Lava Cakes on Domino's.",
            "e1": "Order a unicorn pizza on Domino's.", "e2": "Nothing.", "f1": "Did the last run succeed?"}

def dur(path):
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True).stdout)

def chunks(text, start, end, limit=104):
    """Split a long reply into caption-sized parts by sentence, timed by length."""
    import re
    parts, cur = [], ""
    for s in re.split(r"(?<=[.?!])\s+", text.strip()):
        if cur and len(cur) + 1 + len(s) > limit: parts.append(cur); cur = s
        else: cur = (cur + " " + s).strip()
    if cur: parts.append(cur)
    total = sum(len(p) for p in parts); out = []; t = start
    for p in parts:
        d = (end - start) * len(p) / total
        out.append((t, t + d, p)); t += d
    return out

NARR = json.load(open(os.path.join(HERE, "narr", "plan.json"), encoding="utf-8"))
def build_cues():
    cues = []
    for c in EV["cmds"]:
        mp3 = os.path.join(HERE, "cmd", c["key"] + ".mp3")
        s = c["feed"] + 0.25
        cues.append([s + 0.1, s + dur(mp3) - 0.1, "You", CMD_TEXT[c["key"]]])
    for r in EV["replies"]:
        for a, b, p in chunks(r["text"], r["start"], r["end"]):
            cues.append([a, b, "EchoFlow", p])
    for n in NARR:
        cues.append([n["at"] + 0.05, n["at"] + n["dur"] - 0.05, "Narration", n["text"]])
    return sorted(cues)
CUES = build_cues()
SPK = {"Narration": ("NARRATOR", BLUE), "You": ("SPOKEN REQUEST", ORANGE), "EchoFlow": ("ECHOFLOW REPLY", TEAL)}

def wrap(d, text, fnt, maxw):
    words, lines, cur = text.split(), [], ""
    for w_ in words:
        tr = (cur + " " + w_).strip()
        if d.textlength(tr, font=fnt) <= maxw: cur = tr
        else: lines.append(cur); cur = w_
    if cur: lines.append(cur)
    return lines

# ---- drawing ----
def background():
    y = np.linspace(0, 1, H)[:, None, None]; x = np.linspace(0, 1, W)[None, :, None]
    g = np.clip(0.65 * y + 0.35 * x, 0, 1)
    return (np.array(BG1) * (1 - g) + np.array(BG2) * g).astype(np.uint8)
BASE_BG = background()

def rounded_mask(w, h, r):
    m = Image.new("L", (w, h), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    return np.array(m, dtype=np.float32)[..., None] / 255.0
SCR_MASK = rounded_mask(SCR_W, SCR_H, 48)

_cache = {}
def static_layer(t):
    ch = at(CHAPTERS, t); status, st, pay = tracker_state(t); run = at(RUNS, t, key=lambda r: r["t"])
    key = (ch[0], status, tuple(st), pay, run["t"])
    if key in _cache: return _cache[key]
    im = Image.fromarray(BASE_BG.copy()); d = ImageDraw.Draw(im)
    d.text((COL_X, 40), "EchoFlow", font=F_BRAND, fill=NAVY)
    d.rounded_rectangle(DEV, 70, fill=(14, 27, 51))
    d.text((COL_X, 112), ch[1], font=F_CHAP, fill=BLUE)
    d.text((COL_X, 150), ch[2], font=F_TITLE, fill=NAVY)
    d.text((COL_X + 4, 262), ch[3], font=F_SUB, fill=MUTED)
    idx = [c[0] for c in CHAPTERS].index(ch[0])
    for i in range(6):
        cx = 2250 + i * 38
        d.ellipse((cx - 9, 51, cx + 9, 69), fill=BLUE if i <= idx else LINE)
    x0, y0, x1, y1 = TRK
    d.rounded_rectangle((x0 + 4, y0 + 6, x1 + 4, y1 + 6), 30, fill=(222, 229, 238))
    d.rounded_rectangle(TRK, 30, fill=(255, 255, 255), outline=LINE, width=2)
    d.text((x0 + 34, y0 + 26), "SAVED LESSON", font=F_HDR, fill=BLUE)
    warn = any(w in status for w in ("Not found", "Stopped", "stopped"))
    d.text((x0 + 34, y0 + 62), status, font=F_ROWB, fill=ORANGE if warn else (TEAL if ("ready" in status or "Saved" in status) else NAVY))
    cx, cy = x0 + 34, y0 + 116
    for k in ("item", "qty"):
        label = "\u00d7" + run["vals"][k] if k == "qty" else run["vals"][k]
        tw = d.textlength(label, font=F_ROWB)
        d.rounded_rectangle((cx, cy, cx + tw + 28, cy + 48), 24, fill=(253, 240, 228) if k in run["changed"] else (238, 243, 249))
        d.text((cx + 14, cy + 4), label, font=F_ROWB, fill=ORANGE if k in run["changed"] else NAVY)
        cx += tw + 40
    ry = y0 + 200
    for i, name in enumerate(STEPS):
        s = st[i]; y = ry + i * 74
        if s == "hidden": continue
        bg = {"cur": (225, 237, 250), "new": (225, 237, 250), "ask": (253, 240, 228), "fail": (250, 230, 228)}.get(s)
        if bg: d.rounded_rectangle((x0 + 18, y - 8, x1 - 18, y + 52), 14, fill=bg)
        circ = {"done": BLUE, "new": BLUE, "cur": BLUE, "pend": LINE, "skip": LINE, "ask": ORANGE, "fail": RED}[s]
        d.ellipse((x0 + 34, y + 1, x0 + 76, y + 43), fill=circ)
        num = str(i + 1)
        d.text((x0 + 55 - d.textlength(num, font=F_NUM) / 2, y + 5), num, font=F_NUM, fill=(255, 255, 255) if s not in ("pend", "skip") else MUTED)
        col = NAVY if s in ("done", "new", "cur") else (ORANGE if s == "ask" else (RED if s == "fail" else MUTED))
        fnt = F_ROWB if s in ("cur", "new", "ask", "fail") else F_ROW
        d.text((x0 + 92, y + 2), name, font=fnt, fill=col)
        if i in SLOT_STEP and s != "pend":
            vx = x0 + 92 + d.textlength(name + "  ", font=fnt)
            d.text((vx, y + 2), run["vals"][SLOT_STEP[i]], font=F_ROWB, fill=ORANGE if SLOT_STEP[i] in run["changed"] else col)
        tag = {"skip": "already in cart", "fail": "stopped here", "ask": "not found"}.get(s)
        if tag:
            tw = d.textlength(tag, font=F_NUM)
            d.text((x1 - 34 - tw, y + 46), tag, font=F_NUM, fill=RED if s == "fail" else (ORANGE if s == "ask" else MUTED))
    if pay != "hidden":
        y = ry + 4 * 74 + 14
        d.line((x0 + 34, y - 10, x1 - 34, y - 10), fill=LINE, width=2)
        on = pay in ("you", "teach_pay")
        d.text((x0 + 40, y + 6), "Payment", font=F_ROWB, fill=ORANGE if on else MUTED)
        d.text((x0 + 210, y + 6), "yours \u00b7 never automated" if on else "left to you", font=F_ROW, fill=NAVY if on else MUTED)
    tw = d.textlength(DISCLOSURE, font=F_SMALL)
    d.text(((CAP[0] + CAP[2]) / 2 - tw / 2, 1380), DISCLOSURE, font=F_SMALL, fill=MUTED)
    arr = np.array(im)
    _cache[key] = arr
    if len(_cache) > 64: _cache.pop(next(iter(_cache)))
    return arr

_capcache = {}
def caption_layer(cue, width=CAP[2] - CAP[0], height=CAP[3] - CAP[1]):
    k = (cue[0], cue[3], width)
    if k in _capcache: return _capcache[k]
    im = Image.new("RGBA", (width, height), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    label, col = SPK[cue[2]]
    fnt, lh = F_CAP, 62
    lines = wrap(d, cue[3], fnt, width - 80)
    if len(lines) > 2: fnt, lh = F_CAP2, 54; lines = wrap(d, cue[3], fnt, width - 80)
    top = (height - (40 + lh * len(lines))) // 2
    d.text((width / 2 - d.textlength(label, font=F_SPK) / 2, top), label, font=F_SPK, fill=col + (255,))
    for i, ln in enumerate(lines):
        d.text((width / 2 - d.textlength(ln, font=fnt) / 2, top + 40 + i * lh), ln, font=fnt, fill=NAVY + (255,))
    a = np.array(im).astype(np.float32); _capcache[k] = a
    return a

def pill(text, fill, fg, fnt=F_PILL):
    tw = int(ImageDraw.Draw(Image.new("RGB", (4, 4))).textlength(text, font=fnt))
    im = Image.new("RGBA", (tw + 48, 52), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, tw + 47, 51), 26, fill=fill + (255,)); d.text((24, 6), text, font=fnt, fill=fg + (255,))
    return np.array(im).astype(np.float32)
_pills = {}
def get_pill(text, fill, fg, fnt=F_PILL):
    k = (text, fill, fg, id(fnt))
    if k not in _pills: _pills[k] = pill(text, fill, fg, fnt)
    return _pills[k]

def blend(dst, src, x, y, alpha=1.0):
    h, w = src.shape[:2]
    x0, y0, x1, y1 = max(0, x), max(0, y), min(W, x + w), min(H, y + h)
    if x1 <= x0 or y1 <= y0: return
    s = src[y0 - y:y1 - y, x0 - x:x1 - x]; a = s[..., 3:4] / 255.0 * alpha
    dst[y0:y1, x0:x1] = (dst[y0:y1, x0:x1].astype(np.float32) * (1 - a) + s[..., :3] * a).astype(np.uint8)

def fit(rect):
    _, _, w, h = rect
    mw, mh = FOC[2] - FOC[0], FOC[3] - FOC[1]
    s = min(mw / w, mh / h); pw, ph = w * s, h * s
    return s, ((FOC[0] + FOC[2]) / 2 - pw / 2, FOC[1], pw, ph)

TRANS = 0.5
def focus_geom(t):
    i = max(j for j, s in enumerate(SHOTS) if t >= s[0])
    rect = np.array(SHOTS[i][1], dtype=float)
    if i > 0 and t - SHOTS[i][0] < TRANS:
        k = ease((t - SHOTS[i][0]) / TRANS)
        rect = np.array(SHOTS[i - 1][1], dtype=float) * (1 - k) + rect * k
    return i, rect

def caption_at(t):
    for c in CUES:
        if c[0] - 0.12 <= t < c[1] + 0.12:
            return c, min(1, (t - (c[0] - 0.12)) / 0.12, (c[1] + 0.12 - t) / 0.12)
    return None, 0

def compose(src, t):
    out = static_layer(t).copy()
    phone = cv2.resize(src, (SCR_W, SCR_H), interpolation=cv2.INTER_AREA).astype(np.float32)
    reg = out[SCR_Y:SCR_Y + SCR_H, SCR_X:SCR_X + SCR_W].astype(np.float32)
    out[SCR_Y:SCR_Y + SCR_H, SCR_X:SCR_X + SCR_W] = (reg * (1 - SCR_MASK) + phone * SCR_MASK).astype(np.uint8)
    i, rect = focus_geom(t)
    s, (px, py, pw, ph) = fit(rect)
    px, py, pw, ph = int(round(px)), int(round(py)), int(round(pw)), int(round(ph))
    M = np.array([[s, 0, -rect[0] * s], [0, s, -rect[1] * s]], dtype=np.float32)
    zoom = cv2.warpAffine(src, M, (pw, ph), flags=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    m = rounded_mask(pw, ph, 26)
    cv2.rectangle(out, (px + 6, py + 10), (px + pw + 6, py + ph + 10), (222, 229, 238), -1)
    reg = out[py:py + ph, px:px + pw].astype(np.float32)
    out[py:py + ph, px:px + pw] = (reg * (1 - m) + zoom.astype(np.float32) * m).astype(np.uint8)
    shot = SHOTS[i]
    settle = ease((t - shot[0] - TRANS) / 0.35)
    if settle > 0:
        for (hx, hy, hw, hh) in shot[2]:
            a = (int(px + (hx - rect[0]) * s), int(py + (hy - rect[1]) * s))
            b = (int(px + (hx + hw - rect[0]) * s), int(py + (hy + hh - rect[1]) * s))
            ov = out.copy(); cv2.rectangle(ov, a, b, ORANGE, 7, lineType=cv2.LINE_AA)
            out[:] = cv2.addWeighted(ov, settle, out, 1 - settle, 0)
    if shot[3] and settle > 0:
        p = get_pill(shot[3], ORANGE if (shot[2] or "Stop" in shot[3]) else NAVY, (255, 255, 255))
        blend(out, p, px + pw // 2 - p.shape[1] // 2, py + ph + 22, settle)
    else:
        lbl = get_pill("Zoom of the same recording", (236, 241, 247), MUTED)
        blend(out, lbl, px + pw // 2 - lbl.shape[1] // 2, py + ph + 22, 1.0)
    c, al = caption_at(t)
    if c: blend(out, caption_layer(c), CAP[0], CAP[1], al)
    return out

# ---- title cards (V7's animated intro/outro), with this video's disclosure and captions ----
DISC_PILL = pill(DISCLOSURE, (255, 255, 255), NAVY, F_SMALL)
def card(frame, t):
    out = frame.copy()
    band = out[1255:1262].astype(np.float32).mean(0)
    out[1265:1400] = band.astype(np.uint8)[None]        # V7's own small caption line
    blend(out, DISC_PILL, 150, 1190, 1.0)               # covers V7's small disclosure line
    c, al = caption_at(t)
    if c:
        lay = caption_layer(c, 1690, 170)
        blend(out, lay, W // 2 - lay.shape[1] // 2, 1236, al)
    return out

def reader(path, start, dur_, size, extra=""):
    vf = f"fps={FPS}" + extra
    # Output-side seek after the fps filter: every chunk lands on the same frame grid as one pass.
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", path, "-vf", vf, "-ss", f"{start:.3f}", "-t", f"{dur_:.3f}",
                          "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE, bufsize=10**8)
    w, h = size; n = w * h * 3; last = None
    while True:
        b = p.stdout.read(n)
        if len(b) < n: break
        last = np.frombuffer(b, np.uint8).reshape(h, w, 3)
        yield last
    while last is not None: yield last                 # hold the last frame if asked for more

def render_frames(start, end):
    nf = int(round((end - start) * FPS))
    take = reader(RAW, start, end - start + 1, (SW, SH))
    intro = reader(V7, start, max(0.1, INTRO_END - start), (W, H)) if start < INTRO_END else None
    outro = None
    for k in range(nf):
        t = start + k / FPS
        src = next(take)
        frame = compose(src, t)
        if t < INTRO_END:
            cardf = card(next(intro), t)
            a = 1 - ease((t - (INTRO_END - INTRO_FADE)) / INTRO_FADE)
            frame = cardf if a >= 1 else cv2.addWeighted(cardf, a, frame, 1 - a, 0)
        if t >= OUTRO_START:
            if outro is None:
                outro = reader(V7, V7_OUTRO + (t - OUTRO_START), END - t + 1, (W, H))
            cardf = card(next(outro), t)
            a = ease((t - OUTRO_START) / OUTRO_FADE)
            frame = cardf if a >= 1 else cv2.addWeighted(cardf, a, frame, 1 - a, 0)
        yield t, frame

if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "stills":
        os.makedirs(os.path.join(HERE, "stills"), exist_ok=True)
        for a in sys.argv[2:]:
            t = float(a)
            _, fr = next(render_frames(t, t + 1.0 / FPS))
            Image.fromarray(fr).save(os.path.join(HERE, "stills", f"t_{a}.png")); print("still", a)
    elif mode == "render":
        out = sys.argv[2]
        start = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
        end = float(sys.argv[4]) if len(sys.argv) > 4 else END
        enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
                                "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "17", "-pix_fmt", "yuv420p",
                                "-profile:v", "high", "-movflags", "+faststart", out], stdin=subprocess.PIPE)
        n = 0
        for t, fr in render_frames(start, end):
            enc.stdin.write(fr.tobytes()); n += 1
            if n % 300 == 0: print(f"{t:7.1f}s frames={n}", flush=True)
        enc.stdin.close(); enc.wait(); print("done", n, "frames")
