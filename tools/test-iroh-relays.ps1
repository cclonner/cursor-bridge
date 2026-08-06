# PC-side n0 relay HTTPS ping + optional iroh-doctor
# Usage: powershell -File tools/test-iroh-relays.ps1

$ErrorActionPreference = "Continue"
Write-Host "== HTTPS ping n0 relays (PC) =="
$relays = @(
  "https://euc1-1.relay.n0.iroh.link/",
  "https://use1-1.relay.n0.iroh.link/",
  "https://usw1-1.relay.n0.iroh.link/",
  "https://aps1-1.relay.n0.iroh.link/",
  "https://dns.iroh.link/"
)
foreach ($u in $relays) {
  try {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-WebRequest -Uri $u -TimeoutSec 10 -UseBasicParsing
    Write-Host ("OK  {0}  {1} {2}ms" -f $u, $r.StatusCode, $sw.ElapsedMilliseconds)
  } catch {
    Write-Host ("FAIL {0}  {1}" -f $u, $_.Exception.Message)
  }
}

$doctorCmd = $null
$which = Get-Command iroh-doctor -ErrorAction SilentlyContinue
if ($which) { $doctorCmd = $which.Source }
elseif (Test-Path (Join-Path $env:USERPROFILE ".cargo\bin\iroh-doctor.exe")) {
  $doctorCmd = Join-Path $env:USERPROFILE ".cargo\bin\iroh-doctor.exe"
}

if ($doctorCmd) {
  Write-Host ""
  Write-Host "== iroh-doctor relay-urls =="
  & $doctorCmd relay-urls 2>&1
  Write-Host ""
  Write-Host "== iroh-doctor report =="
  & $doctorCmd report 2>&1
} else {
  Write-Host ""
  Write-Host "(iroh-doctor missing: cargo install iroh-doctor)"
}
