# Local iroh smoke test (PC only). Does NOT need phone.
# Usage:  powershell -File tools/test-iroh.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$bin = Join-Path $root "bridge\bin\cursor-tunnel.exe"
if (-not (Test-Path $bin)) { throw "missing $bin - build tunnel first" }

$tmp = Join-Path $env:TEMP ("iroh-test-" + [guid]::NewGuid().ToString("n").Substring(0,8))
New-Item -ItemType Directory -Path $tmp | Out-Null
$listenOut = Join-Path $tmp "listen-out.txt"
$listenErr = Join-Path $tmp "listen-err.txt"
$connOut = Join-Path $tmp "conn-out.txt"
$connErr = Join-Path $tmp "conn-err.txt"
$keyL = Join-Path $tmp "listen.key"
$keyC = Join-Path $tmp "conn.key"

Write-Host "== relay DNS/HTTP =="
foreach ($h in @(
  "https://euc1-1.relay.n0.iroh.link/",
  "https://use1-1.relay.n0.iroh.link/",
  "https://dns.iroh.link/pkarr"
)) {
  try {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-WebRequest -Uri $h -TimeoutSec 10 -UseBasicParsing
    Write-Host ("OK  {0}  {1} {2}ms" -f $h, $r.StatusCode, $sw.ElapsedMilliseconds)
  } catch {
    Write-Host ("FAIL {0}  {1}" -f $h, $_.Exception.Message)
  }
}

Write-Host "== listen + connect (same PC) =="
$listen = Start-Process -FilePath $bin -ArgumentList @(
  "listen","--target","127.0.0.1:9","--secret-file",$keyL,"--allow-any"
) -RedirectStandardOutput $listenOut -RedirectStandardError $listenErr -PassThru -WindowStyle Hidden

$ticket = $null
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline -and -not $ticket) {
  Start-Sleep -Milliseconds 300
  foreach ($line in (Get-Content $listenOut -ErrorAction SilentlyContinue)) {
    if ($line -match '"ticket"\s*:\s*"([^"]+)"') { $ticket = $Matches[1]; break }
  }
}
if (-not $ticket) {
  Write-Host "LISTEN ERR:"; Get-Content $listenErr -ErrorAction SilentlyContinue
  Stop-Process -Id $listen.Id -Force -ErrorAction SilentlyContinue
  throw "no ticket from listen"
}
Write-Host ("ticket ok ({0} chars)" -f $ticket.Length)

$conn = Start-Process -FilePath $bin -ArgumentList @(
  "connect","--ticket=$ticket","--listen","127.0.0.1:0","--secret-file",$keyC
) -RedirectStandardOutput $connOut -RedirectStandardError $connErr -PassThru -WindowStyle Hidden

$port = 0
$err = $null
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline -and $port -eq 0) {
  Start-Sleep -Milliseconds 400
  foreach ($line in (Get-Content $connOut -ErrorAction SilentlyContinue)) {
    if ($line -match '"event"\s*:\s*"ready".*"port"\s*:\s*(\d+)') { $port = [int]$Matches[1] }
    if ($line -match '"event"\s*:\s*"error"') { $err = $line }
    if ($line -match '"event"\s*:\s*"connected"') { Write-Host "iroh connected" }
  }
}

Write-Host "CONN_OUT:"; Get-Content $connOut -ErrorAction SilentlyContinue
if ($err) { Write-Host "saw error: $err" }
if ($port -eq 0) {
  Write-Host "CONN_ERR:"; Get-Content $connErr -ErrorAction SilentlyContinue
  Stop-Process -Id $conn.Id,$listen.Id -Force -ErrorAction SilentlyContinue
  throw "connect never became ready"
}
Write-Host ("PASS local tcp port {0}" -f $port)

Stop-Process -Id $conn.Id,$listen.Id -Force -ErrorAction SilentlyContinue
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "done"
