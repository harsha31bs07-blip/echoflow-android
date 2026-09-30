# Test clean-up: on a Zomato cart screen, tap each item's "−" until the cart is empty.
$adb = "C:\Android\Sdk\platform-tools\adb.exe"
for ($i = 0; $i -lt 8; $i++) {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $x = [xml](& $adb shell cat /sdcard/ui.xml)
    $n = $x.SelectNodes("//node[contains(@resource-id,'button_remove')]") | Select-Object -First 1
    if (-not $n) { break }
    $b = ($n.bounds -replace '[\[\]]', ' ' -split '[ ,]+' | Where-Object { $_ })
    & $adb shell input tap ([int](([int]$b[0] + [int]$b[2]) / 2)) ([int](([int]$b[1] + [int]$b[3]) / 2))
    Start-Sleep 2
}
& $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
$left = (& $adb shell cat /sdcard/ui.xml) -match 'button_remove|item added|items added'
if ($left) { "cart NOT empty" } else { "cart empty" }
