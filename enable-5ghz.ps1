# Gives the TV a Wi-Fi country code so Wi-Fi Direct can use 5 GHz (Wireless Display Receiver: 2560x1440 instead of
# 1080p). Without a country code the TV only allows passive scanning on 5 GHz, so a Wi-Fi Direct group
# can only start on 2.4 GHz and the phone picks the lower quality. The code lives in memory: run this
# again after every TV restart (the app cannot do it, the TV only accepts it from the shell).
#
#   powershell -ExecutionPolicy Bypass -File .\enable-5ghz.ps1 -Ip <TV IP>
#   powershell -ExecutionPolicy Bypass -File .\enable-5ghz.ps1            (IP from tv-ip.txt)
#   ... -Country DE                                                                       (another country)
#
# Before: TV Settings -> System -> Developer options -> USB debugging ON, and the TV on Wi-Fi.
# What it does: sends the telephony "network country changed" broadcast (the Wi-Fi stack takes its
# country from there on a TV without a SIM), then restarts the TV's Wi-Fi so the driver applies it.
# The Wi-Fi restart drops adb for about 20 seconds; the script waits and connects again.
param(
    [string]$Ip,
    [string]$Country = "LT"
)

$ErrorActionPreference = "Continue"
$Root = $PSScriptRoot
$IpFile = Join-Path $Root "tv-ip.txt"
if (-not $Ip -and (Test-Path $IpFile)) { $Ip = (Get-Content $IpFile -TotalCount 1).Trim() }
if (-not $Ip) {
    Write-Host "ERROR: give the TV address: -Ip <TV IP> (or write it into $IpFile)" -ForegroundColor Red
    exit 1
}
if ($Country -notmatch '^[A-Za-z]{2}$') {
    Write-Host "ERROR: -Country must be a two-letter code, e.g. LT" -ForegroundColor Red
    exit 1
}
$Cc = $Country.ToUpper()
$Dev = "${Ip}:5555"

function Connect-Tv {
    param([int]$Tries = 3)
    for ($i = 0; $i -lt $Tries; $i++) {
        $out = (& adb connect $Dev 2>&1 | Out-String)
        if ($out -match "connected to") { return $true }
        Start-Sleep -Seconds 4
    }
    return $false
}
function Tv { param([string]$Cmd) (& adb -s $Dev shell $Cmd 2>&1 | Out-String).Trim() }

if (-not (Connect-Tv)) {
    Write-Host "ERROR: cannot reach $Dev (USB debugging on? same network?)" -ForegroundColor Red
    exit 1
}

$now = Tv "cmd wifi get-country-code"
if ($now -match "= $Cc") {
    Write-Host "Already set: $now" -ForegroundColor Green
    exit 0
}

Write-Host "1/3 sending the country code $Cc"
$null = Tv ("am broadcast -a android.telephony.action.NETWORK_COUNTRY_CHANGED " +
    "--es android.telephony.extra.NETWORK_COUNTRY $($Cc.ToLower()) --ei android.telephony.extra.SLOT_INDEX 0")
Start-Sleep -Seconds 2

Write-Host "2/3 restarting the TV's Wi-Fi so the driver applies it (adb drops for ~20 s)"
# runs on the TV itself, detached: this connection dies while Wi-Fi is off
$null = & adb -s $Dev shell "setsid nohup sh -c 'cmd wifi set-wifi-enabled disabled; sleep 5; cmd wifi set-wifi-enabled enabled' >/dev/null 2>&1 &"
Start-Sleep -Seconds 25
& adb disconnect $Dev 2>&1 | Out-Null
if (-not (Connect-Tv -Tries 10)) {
    Write-Host "ERROR: the TV did not come back on Wi-Fi. Turn Wi-Fi on in its settings, then run this again." -ForegroundColor Red
    exit 1
}

Write-Host "3/3 checking"
$driver = Tv "dumpsys wifi | grep -E 'mDriverCountryCode'"
Write-Host (Tv "cmd wifi get-country-code")
Write-Host $driver
if ($driver -match "mDriverCountryCode: $Cc") {
    Write-Host "OK: Wi-Fi Direct may use 5 GHz now. Start Wireless Display Receiver on the TV, then connect from the phone." -ForegroundColor Green
} else {
    Write-Host "The driver has not taken the code yet. Run this once more." -ForegroundColor Yellow
    exit 2
}
