# Build the debug APK, install it, and turn EchoFlow's accessibility service back on
# (reinstalling disables it). Usage: .\scripts\install.ps1 [-Test]
param([switch]$Test)
$env:JAVA_HOME = "C:\Users\HARSHA B S\.jdks\jbr-21.0.11"
$adb = "C:\Android\Sdk\platform-tools\adb.exe"
$tasks = @(":app:assembleDebug")
if ($Test) { $tasks = @(":core:test") + $tasks }
& .\gradlew.bat @tasks --console=plain *> "$env:TEMP\echoflow-build.log"
if ($LASTEXITCODE -ne 0) {
    "BUILD FAILED"
    Get-Content "$env:TEMP\echoflow-build.log" | Select-String -Pattern "\.kt:\d+|FAILED|AssertionFailed|expected" | Select-Object -First 15
    exit 1
}
"build ok"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk | Select-Object -Last 1
# Off, then on: a service Android marked "crashed" (after a force-stop) only rebinds on a change.
& $adb shell settings delete secure enabled_accessibility_services
Start-Sleep 1
& $adb shell settings put secure enabled_accessibility_services com.echoflow/com.echoflow.app.accessibility.EchoAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
& $adb shell pm grant com.echoflow android.permission.RECORD_AUDIO
& $adb shell svc power stayon usb
Start-Sleep 3
