# Builds Wireless Display Receiver with the Android SDK build-tools only (no Gradle).
#   powershell -ExecutionPolicy Bypass -File .\build.ps1          -> build\displayreceiver.apk
# Needs: JDK 17 (javac, keytool on PATH) and an Android SDK with platforms;android-34 and
# build-tools;34.0.0 (default C:\Android, or pass -Sdk <folder>).
# The first run creates a signing key outside the project ($HOME\.displayreceiver). Its random password is
# stored encrypted with Windows DPAPI and handed to keytool and apksigner through an environment
# variable, never on a command line. An update installed over an older build must be signed with the
# same key, so keep the key folder.
param(
    [string]$Sdk = "C:\Android",
    [string]$KeyDir = (Join-Path $HOME ".displayreceiver")
)

$ErrorActionPreference = "Continue"
$Root = $PSScriptRoot
$BT   = "$Sdk\build-tools\34.0.0"
$Jar  = "$Sdk\platforms\android-34\android.jar"
$B    = Join-Path $Root "build"
$MinSdk = 30

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

foreach ($f in @($Jar, "$BT\aapt2.exe", "$BT\d8.bat", "$BT\apksigner.bat", "$BT\zipalign.exe")) {
    if (-not (Test-Path $f)) { Fail "missing $f" }
}
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { Fail "javac not found (JDK 17)" }
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) { Fail "keytool not found (JDK 17)" }

if (Test-Path $B) { Remove-Item $B -Recurse -Force }
New-Item -ItemType Directory -Path "$B\classes", "$B\dex" | Out-Null

Write-Host "1/5 aapt2 compile + link"
& "$BT\aapt2.exe" compile --dir "$Root\res" -o "$B\res.zip"
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile failed" }
& "$BT\aapt2.exe" link -o "$B\base.apk" -I $Jar --manifest "$Root\AndroidManifest.xml" --java "$B\gen" --min-sdk-version $MinSdk --target-sdk-version 34 "$B\res.zip"
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link failed" }

Write-Host "2/5 javac"
$src = @("$Root\src", "$B\gen") | ForEach-Object { Get-ChildItem $_ -Recurse -Filter *.java } | ForEach-Object { $_.FullName }
& javac --release 8 -encoding UTF-8 -Xlint:-options -classpath $Jar -d "$B\classes" $src
if ($LASTEXITCODE -ne 0) { Fail "javac failed (see the errors above)" }

Write-Host "3/5 d8"
$classes = Get-ChildItem "$B\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --release --min-api $MinSdk --lib $Jar --output "$B\dex" $classes
if ($LASTEXITCODE -ne 0) { Fail "d8 failed" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$B\base.apk", [System.IO.Compression.ZipArchiveMode]::Update)
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$B\dex\classes.dex", "classes.dex")
$zip.Dispose()

Write-Host "4/5 zipalign"
& "$BT\zipalign.exe" -f -p 4 "$B\base.apk" "$B\aligned.apk"
if ($LASTEXITCODE -ne 0) { Fail "zipalign failed" }

Write-Host "5/5 signing"
$KS       = Join-Path $KeyDir "release.jks"
$PassFile = Join-Path $KeyDir "release-pass.dpapi"
if (-not (Test-Path $KS)) {
    New-Item -ItemType Directory -Force $KeyDir | Out-Null
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $plain = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
    ConvertTo-SecureString $plain -AsPlainText -Force | ConvertFrom-SecureString | Set-Content $PassFile -Encoding ascii
    $env:WDR_KS_PASS = $plain
    & keytool -genkeypair -keystore $KS -storetype PKCS12 -storepass:env WDR_KS_PASS -alias displayreceiver -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Wireless Display Receiver"
    if ($LASTEXITCODE -ne 0) { Remove-Item Env:WDR_KS_PASS; Fail "keytool failed" }
    Write-Host "   New key created: $KS" -ForegroundColor Yellow
}
if (-not (Test-Path $PassFile)) { Fail "missing $PassFile (the key password)" }
$sec = Get-Content $PassFile | ConvertTo-SecureString
$env:WDR_KS_PASS = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
& "$BT\apksigner.bat" sign --ks $KS --ks-key-alias displayreceiver --ks-pass env:WDR_KS_PASS --out "$B\displayreceiver.apk" "$B\aligned.apk"
$signed = $LASTEXITCODE
Remove-Item Env:WDR_KS_PASS
if ($signed -ne 0) { Fail "apksigner failed" }

Write-Host ""
Write-Host "Built: $B\displayreceiver.apk" -ForegroundColor Green
