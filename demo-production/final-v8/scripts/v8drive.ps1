# V8 demo driver: spoken commands (Prabhat, natural rate) fed to Android speech recognition, taps by on-screen text.
$script:sp = "C:\Users\HARSHA B S\AppData\Local\Temp\claude\C--dev-echoflow-android\da9a8894-237a-4cfe-b4ec-86074de6fbd6\scratchpad"
. "$script:sp\drive.ps1"
$script:v8 = "$script:sp\v8"
$script:cmdDir = "$script:v8\cmd"
$script:HANDLE = @(1068, 1800)
$script:timeline = New-Object System.Collections.Generic.List[string]
$script:t0 = Get-Date

function Now { ((Get-Date) - $script:t0).TotalSeconds }
function Mark($what) {
    $line = "{0:N3},{1}" -f (Now), $what
    $script:timeline.Add($line); Write-Output ("{0,7:N1}s {1}" -f (Now), $what)
}

# Makes (once) the command clip: Prabhat at its natural rate, as 16 kHz mono PCM for the recognizer.
function CmdClip($key, $text) {
    $mp3 = "$script:cmdDir\$key.mp3"; $pcm = "$script:cmdDir\$key.pcm"
    if (-not (Test-Path $mp3)) { python -m edge_tts --voice en-IN-PrabhatNeural --text $text --write-media $mp3 2>$null | Out-Null }
    if (-not (Test-Path $pcm)) { ffmpeg -v error -y -i $mp3 -af "adelay=250:all=1,apad=pad_dur=0.9" -ar 16000 -ac 1 -f s16le $pcm }
    return $pcm
}

# Speaks a command: the clip is queued for the recognizer, then the handle is tapped like a user would.
function Speak($key, $text, [switch]$noTap) {
    $pcm = CmdClip $key $text
    & $script:adb push $pcm /data/local/tmp/next.pcm | Out-Null
    & $script:adb shell "run-as com.echoflow sh -c 'mkdir -p files/demo-audio && cp /data/local/tmp/next.pcm files/demo-audio/next.pcm'"
    Mark "cmd:$key|$text"
    if (-not $noTap) {
        # Start listening with the handle; if the clip isn't being fed within a few seconds, use the panel's mic.
        & $script:adb logcat -c
        $h = HandleXY
        if ($h) { & $script:adb shell input tap $h[0] $h[1]; Mark ("tap:handle@{0},{1}" -f $h[0], $h[1]) } else { TapMic }
        if (-not (WaitFeedStart 4)) { TapMic; if (-not (WaitFeedStart 4)) { $h = HandleXY; if ($h) { & $script:adb shell input tap $h[0] $h[1]; Mark "tap:handle-retry" }; WaitFeedStart 4 | Out-Null } }
    }
}

# Queues an answer clip without tapping: EchoFlow consumes it when it next listens (after asking a question).
function QueueNext($key, $text) {
    $pcm = CmdClip $key $text
    & $script:adb push $pcm /data/local/tmp/next.pcm | Out-Null
    & $script:adb shell "run-as com.echoflow sh -c 'mkdir -p files/demo-audio && cp /data/local/tmp/next.pcm files/demo-audio/next.pcm'"
    Mark "queued:$key|$text"
}
# The edge handle: EchoFlow's small overlay window (the user can drag it), read from the window list.
function HandleXY {
    $line = & $script:adb shell dumpsys window windows | Select-String "com\.echoflow, frame=\[Rect\((\d+), (\d+) - (\d+), (\d+)\)\]" | Select-Object -First 1
    if ($line) {
        $m = $line.Matches[0].Groups
        $x0 = [int]$m[1].Value; $y0 = [int]$m[2].Value; $x1 = [int]$m[3].Value; $y1 = [int]$m[4].Value
        if (($x1 - $x0) -lt 300) { return @([int]($x1 - 20), [int](($y0 + $y1) / 2)) }
    }
    return $null   # the panel is open (wide window): use its mic button instead
}

function WaitFeedStart($timeoutSec = 4) {
    $t = Get-Date
    while (((Get-Date) - $t).TotalSeconds -lt $timeoutSec) {
        if (& $script:adb logcat -d -s EchoVoice:I | Select-String "audio_feed event=start") { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}
# Waits until the clip queued last has been fed to the recognizer.
function WaitConsumed($timeoutSec = 20) {
    $t = Get-Date
    while (((Get-Date) - $t).TotalSeconds -lt $timeoutSec) {
        if (& $script:adb logcat -d -s EchoVoice:I | Select-String "audio_feed event=end") { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

function SayLines { & $script:adb logcat -d -s EchoOrchestrator:I | Select-String "say:|ask:" }
# Waits for a new EchoFlow reply matching $pattern and for it to finish being spoken.
function WaitSay($pattern, $timeoutSec = 90) {
    $t = Get-Date
    while (((Get-Date) - $t).TotalSeconds -lt $timeoutSec) {
        $hit = SayLines | Where-Object { $_.Line -match $pattern } | Select-Object -Last 1
        if ($hit) {
            $text = $hit.Line -replace '^.*(say|ask): ', '' -replace '\|.*$', ''
            Mark "echo:$text"
            WaitSpoken
            & $script:adb logcat -c
            return $text
        }
        Start-Sleep -Milliseconds 400
    }
    Mark "TIMEOUT:$pattern"; & $script:adb logcat -c; return $null
}
# Returns once no utterance is in progress (tts start without a matching done).
function WaitSpoken($timeoutSec = 30) {
    $t = Get-Date
    Start-Sleep -Milliseconds 600
    while (((Get-Date) - $t).TotalSeconds -lt $timeoutSec) {
        $ev = & $script:adb logcat -d -s EchoVoice:I | Select-String "tts event=(start|done|stop|error)" | ForEach-Object { $_.Line }
        $starts = @($ev | Where-Object { $_ -match "event=start" }).Count
        $ends = @($ev | Where-Object { $_ -match "event=(done|stop|error)" }).Count
        if ($starts -le $ends) { return }
        Start-Sleep -Milliseconds 300
    }
}

function Shot($name) { & $script:adb shell screencap -p /sdcard/s.png; & $script:adb pull /sdcard/s.png "$script:v8\$name.png" | Out-Null; "$script:v8\$name.png" }

# Starts listening like a user: the panel's orange mic button when the panel is open, else the edge handle.
function TapMic {
    $p = Shot "mic_probe"
    $xy = python -c @"
import cv2, numpy as np
im = cv2.imread(r'$p')
# EchoFlow's panel: a large dark-navy area (BGR ~ (68,42,39)); the mic is the orange circle inside it.
pm = cv2.inRange(im, np.array([55, 30, 28]), np.array([82, 55, 52]))
pn, _, pst, _ = cv2.connectedComponentsWithStats(pm, 8)
panels = [pst[i] for i in range(1, pn) if pst[i][4] > 120000]
out = ''
if panels:
    x, y, w, h, _ = max(panels, key=lambda r: r[4])
    sub = im[y:y + h, x:x + w]
    m = cv2.inRange(sub, np.array([55, 90, 195]), np.array([105, 140, 250]))   # BGR orange ~ (78,113,223)
    n, _, st, c = cv2.connectedComponentsWithStats(m, 8)
    ok = [i for i in range(1, n) if st[i][4] > 4000 and 0.7 < st[i][2] / max(1, st[i][3]) < 1.4]
    if ok:
        cx, cy = c[max(ok, key=lambda i: st[i][4])]
        out = '%d %d' % (x + cx, y + cy)
print(out)
"@
    if ($xy) { $a = $xy -split ' '; & $script:adb shell input tap $a[0] $a[1]; Mark "tap:mic@$xy" }
    else { $h = HandleXY; if ($h) { & $script:adb shell input tap $h[0] $h[1]; Mark "tap:handle" } else { Mark "MISS:mic" } }
}

# Taps EchoFlow's teal "Done" button, found by colour in a screenshot (it is an overlay, not in the app's tree).
function TapDone {
    $p = Shot "done_probe"
    $xy = python -c @"
import cv2, numpy as np, sys
im = cv2.imread(r'$p'); hsv = cv2.cvtColor(im, cv2.COLOR_BGR2HSV)
m = cv2.inRange(im, np.array([95, 105, 40]), np.array([135, 150, 95]))   # BGR teal ~ (110,116,53)
n, _, st, c = cv2.connectedComponentsWithStats(m, 8)
best = max(range(1, n), key=lambda i: st[i][4], default=None)
print('' if best is None or st[best][4] < 2000 else f'{int(c[best][0])} {int(c[best][1])}')
"@
    if ($xy) { $a = $xy -split ' '; & $script:adb shell input tap $a[0] $a[1]; Mark "tap:Done@$xy"; return $true }
    Mark "MISS:Done"; return $false
}

# Taps the first element in EchoFlow's snapshot whose label/id matches, optionally within a Y band.
function TapLabel($pattern, $minY = 0, $maxY = 9999, $timeoutSec = 15, [switch]$byId) {
    $t = Get-Date
    while (((Get-Date) - $t).TotalSeconds -lt $timeoutSec) {
        $hit = Snap | Where-Object { (($byId -and $_.Id -match $pattern) -or (-not $byId -and $_.Label -match $pattern)) -and $_.Y -ge $minY -and $_.Y -le $maxY } | Select-Object -First 1
        if ($hit) { & $script:adb shell input tap $hit.X $hit.Y; Mark ("tap:{0}@{1},{2}" -f $pattern, $hit.X, $hit.Y); return $true }
        Start-Sleep -Milliseconds 500
    }
    Mark "MISS:$pattern"; return $false
}

function SaveTimeline($path) { [IO.File]::WriteAllLines($path, $script:timeline) }


# Empties the Domino's cart (test hygiene: nothing is ever ordered) and returns the items left.
function EmptyDominosCart {
    & $script:adb shell am force-stop com.Dominos
    & $script:adb shell monkey -p com.Dominos -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null; Start-Sleep 8
    & $script:adb shell input tap 394 604; Start-Sleep 3; TypeText "garlic"; Start-Sleep 4
    & $script:adb shell input keyevent KEYCODE_BACK; Start-Sleep 1
    $c = Snap | Where-Object { $_.Id -eq 'cart_btn_outer' } | Select-Object -First 1
    if ($c) {
        & $script:adb shell input tap $c.X $c.Y; Start-Sleep 5
        for ($i = 0; $i -lt 30; $i++) {
            $r = Snap | Where-Object { $_.Id -eq 'tvReduceQuantity' } | Select-Object -First 1
            if (-not $r) { break }
            & $script:adb shell input tap $r.X $r.Y; Start-Sleep 2
        }
    }
    $left = @(Snap | Where-Object { $_.Id -eq 'tvItemTitle' }).Count
    & $script:adb shell am force-stop com.Dominos; & $script:adb shell input keyevent KEYCODE_HOME
    return $left
}

# Fresh state for a take: no lessons, no runs, service restarted.
function ResetEcho {
    & $script:adb shell "run-as com.echoflow find files/flows files/runs -type f -delete"
    & $script:adb shell am force-stop com.echoflow
    & $script:adb shell settings put secure enabled_accessibility_services '""'; Start-Sleep 1
    & $script:adb shell settings put secure enabled_accessibility_services com.echoflow/com.echoflow.app.accessibility.EchoAccessibilityService
    & $script:adb shell settings put secure accessibility_enabled 1; Start-Sleep 4
    & $script:adb shell input keyevent KEYCODE_HOME
}

# True when the Domino's cart is verifiably empty: a fresh search shows no cart bar and an "Add" button.
function CartVerifiedEmpty {
    & $script:adb shell am force-stop com.Dominos
    & $script:adb shell monkey -p com.Dominos -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null; Start-Sleep 8
    & $script:adb shell input tap 394 604; Start-Sleep 3; TypeText "garlic%sbread"; Start-Sleep 4
    & $script:adb shell input keyevent KEYCODE_BACK; Start-Sleep 1.5
    $s = Snap
    $bar = $s | Where-Object { $_.Id -eq 'tvCartCount' }
    $add = $s | Where-Object { $_.Id -eq 'btnAdd' }
    & $script:adb shell am force-stop com.Dominos; & $script:adb shell input keyevent KEYCODE_HOME
    return (-not $bar) -and [bool]$add
}
function EnsureEmptyCart {
    for ($k = 0; $k -lt 3; $k++) {
        if (CartVerifiedEmpty) { return $true }
        EmptyDominosCart | Out-Null
    }
    return (CartVerifiedEmpty)
}
