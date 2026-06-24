$names = "Offloading","Sap_Lookup","Tag_Assignment","Product_Request"
$baseDir = "C:/Github/AndroidStudioProjects/PPNAM_Inbound_AS/src/main/res/drawable"
foreach ($name in $names) {
    $xmlPath = "$baseDir/$name.xml"
    $pngPath = "$baseDir/$name.png"
    if (Test-Path $xmlPath) {
        $content = Get-Content $xmlPath -Raw
        if ($content -match '<image mime="image/png">([\s\S]+?)</image>') {
            $base64 = $matches[1].Trim() -replace "\s", ""
            $bytes = [Convert]::FromBase64String($base64)
            [IO.File]::WriteAllBytes($pngPath, $bytes)
            Remove-Item $xmlPath
            Write-Output "Successfully converted $name"
        }
    }
}
