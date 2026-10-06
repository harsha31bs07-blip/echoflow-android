$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(-1) | Out-Null; $task.Result }

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
$dir = $args[0]; $out = $args[1]
$rows = New-Object System.Collections.Generic.List[string]
Get-ChildItem $dir -Filter 'o_*.png' | Sort-Object Name | ForEach-Object {
    $idx = [int]($_.BaseName.Substring(2))
    $t = ($idx - 1) / 2.0 
    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($_.FullName)) ([Windows.Storage.StorageFile])
    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $dec = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bmp = Await ($dec.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $res = Await ($engine.RecognizeAsync($bmp)) ([Windows.Media.Ocr.OcrResult])
    foreach ($line in $res.Lines) {
        $xs = $line.Words | ForEach-Object { $_.BoundingRect.X }; $ys = $line.Words | ForEach-Object { $_.BoundingRect.Y }
        $x2 = $line.Words | ForEach-Object { $_.BoundingRect.X + $_.BoundingRect.Width }; $y2 = $line.Words | ForEach-Object { $_.BoundingRect.Y + $_.BoundingRect.Height }
        $x = [int](($xs | Measure-Object -Minimum).Minimum / 2); $y = [int](($ys | Measure-Object -Minimum).Minimum / 2)
        $xe = [int](($x2 | Measure-Object -Maximum).Maximum / 2); $ye = [int](($y2 | Measure-Object -Maximum).Maximum / 2)
        $rows.Add(("{0:F2}`t{1}`t{2}`t{3}`t{4}`t{5}" -f $t, $x, $y, $xe, $ye, ($line.Text -replace "`t", ' ')))
    }
    $stream.Dispose()
}
[IO.File]::WriteAllLines($out, $rows, (New-Object System.Text.UTF8Encoding($false)))
"lines: $($rows.Count)"
