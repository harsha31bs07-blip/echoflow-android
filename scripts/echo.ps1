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
    "say"  { & $adb shell am broadcast -a $act --es text "'$a1'" | Out-Null }
    "done" { & $adb shell am broadcast -a $act --ez done true | Out-Null }
    "stop" { & $adb shell am broadcast -a $act --ez stop true | Out-Null }
    "tap"  { & $adb shell input tap $a1 $a2 }
    "log"  { & $adb logcat -d -t 400 | Select-String -Pattern "EchoOrchestrator|AndroidRuntime|FATAL|echoflow" | Select-Object -Last 25 }
}
