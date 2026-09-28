# End-to-end device check (debug build): send a command, answer EchoFlow's questions in order,
# wait for the run to finish and print the run record.
#   .\scripts\run.ps1 "order paneer tikka" -Answers "yes"
#   .\scripts\run.ps1 "what happened last time" -NoRun
param(
    [Parameter(Mandatory = $true)][string]$Command,
    [string[]]$Answers = @(),
    [int]$TimeoutSec = 150,
    [switch]$NoRun,
    [switch]$KeepApp
)
$adb = "C:\Android\Sdk\platform-tools\adb.exe"
if (-not $KeepApp) { & $adb shell input keyevent KEYCODE_HOME | Out-Null; Start-Sleep 2 }
& $adb shell run-as com.echoflow rm -r files/runs 2>$null | Out-Null
& $adb logcat -c
& $adb shell am broadcast -a com.echoflow.DEBUG_COMMAND --es text "'$Command'" | Out-Null
$answered = 0
$seenAsks = 0
$start = Get-Date
while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSec) {
    Start-Sleep 3
    $log = & $adb logcat -d -s EchoOrchestrator:I
    $asks = @($log | Select-String -Pattern " ask: ")
    if ($asks.Count -gt $seenAsks) {
        $seenAsks = $asks.Count
        "Q: " + ($asks[-1].Line -replace '^.* ask: ', '')
        if ($answered -lt $Answers.Count) {
            Start-Sleep 4
            "A: " + $Answers[$answered]
            & $adb shell am broadcast -a com.echoflow.DEBUG_COMMAND --es text "'$($Answers[$answered])'" | Out-Null
            $answered++
        }
    }
    if ($NoRun) {
        $says = @($log | Select-String -Pattern " say: ")
        if ($says.Count -gt 0) { break }
    } elseif (& $adb shell "run-as com.echoflow ls files/runs 2>/dev/null") { Start-Sleep 1; break }
}
"--- says:"
& $adb logcat -d -s EchoOrchestrator:I | Select-String -Pattern " say: | candidates: " | ForEach-Object { $_.Line -replace '^.*EchoOrchestrator: ', '' }
if (-not $NoRun) {
    "--- run:"
    & $adb shell "run-as com.echoflow sh -c 'cat files/runs/*.json'" | Select-String -Pattern '"status"|"message"|"stoppedAtStep"|"slots"'
    & $adb shell "run-as com.echoflow sh -c 'cat files/runs/*.json'" | Select-String -Pattern '^\s+"[a-z].*",?$' | Where-Object { $_.Line -notmatch ':' } | ForEach-Object { "  event: " + $_.Line.Trim() }
}
& $adb shell am broadcast -a com.echoflow.DEBUG_COMMAND --ez snap true | Out-Null
Start-Sleep 1
"--- screen: " + (& $adb shell run-as com.echoflow cat files/debug_snap.txt | Select-Object -First 1)
