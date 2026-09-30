# Debug helper for device testing (debug builds only).
#   .\scripts\echo.ps1 snap            -> print current screen elements (index, centre x,y, class, flags, id, label)
#   .\scripts\echo.ps1 say "order ..." -> send a command as if spoken
#   .\scripts\echo.ps1 done|stop       -> press Done / Stop
#   .\scripts\echo.ps1 tap X Y         -> tap the screen
#   .\scripts\echo.ps1 log             -> recent EchoFlow log lines
param([string]$cmd, [string]$a1, [string]$a2)
$adb = "C:\Android\Sdk\platform-tools\adb.exe"
$act = "com.echoflow.DEBUG_COMMAND"
switch ($cmd) {
    "snap" {
        & $adb shell am broadcast -a $act --ez snap true | Out-Null
        Start-Sleep -Milliseconds 900
        & $adb shell run-as com.echoflow cat files/debug_snap.txt
    }
    "say"  { & $adb shell am broadcast -a $act --es text "'$($a1 -replace "'", "'\''")'" | Out-Null }
    "done" { & $adb shell am broadcast -a $act --ez done true | Out-Null }
    "stop" { & $adb shell am broadcast -a $act --ez stop true | Out-Null }
    "tap"  { & $adb shell input tap $a1 $a2 }
    "find" {
        # Tap the first element whose view id or label contains $a1 (optionally only clickable: $a2 = "C").
        & $adb shell am broadcast -a $act --ez snap true | Out-Null
        Start-Sleep -Milliseconds 900
        $line = & $adb shell run-as com.echoflow cat files/debug_snap.txt | Select-Object -Skip 1 |
            Where-Object { $f = $_ -split "`t"; ($f[4] -like "*$a1*" -or $f[5] -like "*$a1*") -and (-not $a2 -or $f[3] -like "*$a2*") } |
            Select-Object -First 1
        if (-not $line) { "not found: $a1"; return }
        $xy = ($line -split "`t")[1] -split ","
        & $adb shell input tap $xy[0] $xy[1]
        "tapped: $line"
    }
    "shot" {
        $out = if ($a1) { $a1 } else { "$env:TEMP\echo_shot.png" }
        & $adb shell screencap -p /sdcard/echo_shot.png
        & $adb pull /sdcard/echo_shot.png $out | Out-Null
        & $adb shell rm /sdcard/echo_shot.png
        $out
    }
    "log"  { & $adb logcat -d -t 400 | Select-String -Pattern "EchoOrchestrator|AndroidRuntime|FATAL|echoflow" | Select-Object -Last 25 }
}
