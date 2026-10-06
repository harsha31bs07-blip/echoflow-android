# One continuous V8 take on the Domino's app. Usage: take.ps1 <name>
param([string]$name = "take1")
$ErrorActionPreference = "Continue"
. "C:\Users\HARSHA B S\AppData\Local\Temp\claude\C--dev-echoflow-android\da9a8894-237a-4cfe-b4ec-86074de6fbd6\scratchpad\v8\v8drive.ps1"
$out = "$script:v8\$name"; New-Item -ItemType Directory -Force $out | Out-Null
$env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")

function Final($timeoutSec = 150) { WaitSay "Done\.|finished all|Your turn|ready for payment|couldn't|stopped|didn't|Do you want|Which|What should|already in your cart|not sure|can't" $timeoutSec }

# Clean start: home screen, EchoFlow idle.
& $adb shell input keyevent KEYCODE_HOME; Start-Sleep 2
& $adb shell am force-stop com.Dominos
# Preflight: phone connected, nothing queued, handle low on the edge (its panel opens near it and must not
# cover the app's results list, where the "Add" taps land).
if (-not (& $adb devices | Select-String "RZCY111PR0K\s+device")) { Write-Output "ABORT: phone not connected"; exit 1 }
& $adb shell "run-as com.echoflow find files/demo-audio -type f -delete"
Start-Sleep 3
$h = HandleXY
if ($h -and $h[1] -lt 1600) { & $adb shell input swipe $h[0] $h[1] $h[0] 1800 1200; Start-Sleep 3; $h = HandleXY }
if (-not $h -or $h[1] -lt 1600) { Write-Output "ABORT: EchoFlow handle not found low on the edge ($($h -join ','))"; exit 1 }
Write-Output "preflight ok: handle $($h -join ',')"
& $adb logcat -c
$phoneEpoch = [double](& $adb shell date +%s.%N); $pcEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0
$log = Start-Process $adb -ArgumentList "logcat", "-v", "epoch", "EchoOrchestrator:I", "EchoVoice:I", "EchoTeach:I", "EchoReplay:I", "EchoAccess:I", "EchoAct:I", "*:S" -RedirectStandardOutput "$out\logcat.txt" -PassThru -WindowStyle Hidden
$rec = Start-Process scrcpy -ArgumentList "--no-playback", "--no-window", "--record=`"$out\raw.mkv`"", "--audio-source=output", "--video-bit-rate=16M", "--max-fps=60", "--time-limit=320" -PassThru -WindowStyle Hidden -RedirectStandardError "$out\scrcpy.err"
$recStartPc = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0
$script:t0 = Get-Date
Mark "start"
Start-Sleep 6

# (a) Teach by voice + taps
Speak "a1" "Order a Garlic Bread on Domino's."
WaitConsumed | Out-Null
QueueNext "a2" "Yes."
WaitSay "teach me|Want to teach" 40 | Out-Null
WaitSay "show me" 40 | Out-Null
Start-Sleep 1
TapLabel "tv_suggestions|cl_inner" 450 760 15 -byId | Out-Null
Start-Sleep 2.5
TypeText "garlic%sbread"; Mark "type:garlic bread"
Start-Sleep 4
TapLabel "^btnAdd$" 600 1300 15 -byId | Out-Null
Start-Sleep 2
& $adb shell input keyevent KEYCODE_BACK; Mark "key:back (hide keyboard)"
Start-Sleep 2
TapLabel "^cart_btn_outer$" 1900 2300 15 -byId | Out-Null
Start-Sleep 4
TapDone | Out-Null
WaitSay "Learned|saved|nothing was saved" 40 | Out-Null
Start-Sleep 1
TapLabel "^tvReduceQuantity$" 0 2300 10 -byId | Out-Null
Start-Sleep 2.5
& $adb shell input keyevent KEYCODE_HOME; Mark "home"
Start-Sleep 2

# (b) Exact replay
Speak "b1" "Order a Garlic Bread on Domino's."
Final | Out-Null
Start-Sleep 2
& $adb shell input keyevent KEYCODE_HOME; Mark "home"
Start-Sleep 2

# (c) Paraphrase
Speak "c1" "Get me garlic bread from Domino's."
Final | Out-Null
Start-Sleep 2
& $adb shell input keyevent KEYCODE_HOME; Mark "home"
Start-Sleep 2

# (d) Changed values: new dish + quantity
Speak "d1" "Order two Choco Lava Cakes on Domino's."
Final | Out-Null
Start-Sleep 2
& $adb shell input keyevent KEYCODE_HOME; Mark "home"
Start-Sleep 2

# (e) Stuck -> asks
Speak "e1" "Order a unicorn pizza on Domino's."
WaitConsumed | Out-Null
QueueNext "e2" "Nothing."
$q = Final 120
if ($q -match "\?") { WaitSay "stopped|nothing|Okay" 40 | Out-Null }
Start-Sleep 2
& $adb shell input keyevent KEYCODE_HOME; Mark "home"
Start-Sleep 2

# Report
Speak "f1" "Did the last run succeed?"
WaitSay "last run" 40 | Out-Null
Start-Sleep 5
Mark "end"

$rec.WaitForExit(30000) | Out-Null
if (-not $rec.HasExited) { Mark "scrcpy still running" }
Stop-Process -Id $log.Id -ErrorAction SilentlyContinue
SaveTimeline "$out\timeline.csv"
"phoneEpoch=$phoneEpoch pcEpoch=$pcEpoch recStartPc=$recStartPc t0=$($script:t0.ToUniversalTime().ToString('o'))" | Set-Content "$out\sync.txt"
