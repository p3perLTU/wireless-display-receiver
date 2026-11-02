# Runs the unit tests; needs only JDK 17 (javac, java on PATH).
#   powershell -ExecutionPolicy Bypass -File .\tests\run-tests.ps1
# Exit code 0 when everything passed.
#
#   1. Java       - the plain-Java parts of the app (ADB wire format, RTSP reading, the capabilities it
#                   announces, the country code check), compiled WITHOUT android.jar, which also proves
#                   these classes need no Android
#   2. PowerShell - every script in the repository parses

$ErrorActionPreference = "Continue"
$Tests = $PSScriptRoot
$Root = Split-Path $Tests
$Out = Join-Path $Tests "out"
$failed = @()

function Step($msg) { Write-Host ""; Write-Host "== $msg" -ForegroundColor Cyan }

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Force "$Out\java" | Out-Null

Step "Java"
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    $failed += "Java (javac not found, JDK 17 needed)"
} else {
    $pure = @("AdbWire.java", "RtspText.java", "WfdCaps.java", "CountryCode.java") | ForEach-Object { Join-Path $Root "src\lt\tvcast\displayreceiver\$_" }
    $src = @(Get-ChildItem (Join-Path $Tests "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }) + $pure
    & javac --release 8 -encoding UTF-8 -Xlint:-options -d "$Out\java" $src
    if ($LASTEXITCODE -ne 0) {
        $failed += "Java (compile)"
    } else {
        $classes = Get-ChildItem (Join-Path $Tests "java") -Recurse -Filter *Test.java | ForEach-Object {
            $_.FullName.Substring((Join-Path $Tests "java").Length + 1).Replace('\', '.') -replace '\.java$', ''
        }
        & java -cp "$Out\java" lt.tvcast.testing.TestRunner $classes
        if ($LASTEXITCODE -ne 0) { $failed += "Java" }
    }
}

Step "PowerShell scripts parse"
Get-ChildItem $Root -Recurse -Filter *.ps1 | Where-Object { $_.FullName -notlike "*\build\*" } | ForEach-Object {
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$null, [ref]$errors)
    if ($errors) { $failed += "parse: $($_.Name)"; Write-Host "  FAIL $($_.Name): $($errors[0].Message)" -ForegroundColor Red }
    else { Write-Host "  ok    $($_.FullName.Substring($Root.Length + 1))" }
}

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host ("FAILED: " + ($failed -join ", ")) -ForegroundColor Red
    exit 1
}
Write-Host "All tests passed." -ForegroundColor Green
