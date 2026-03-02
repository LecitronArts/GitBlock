param(
    [string]$LogPath = ".\\logs\\latest.log",
    [double]$MinTps = 19.0,
    [double]$MinAvgTps = 19.5,
    [double]$MinBlocksPerSec = 3500.0
)

Write-Host "Run this in-game before parsing:"
Write-Host "  /GitBlock bench run 1 minecraft:stone minecraft:andesite"
Write-Host ""

if (-not (Test-Path $LogPath)) {
    Write-Error "Log file not found: $LogPath"
    exit 1
}

$match = Select-String -Path $LogPath -Pattern "BENCH_RESULT" | Select-Object -Last 1
if (-not $match) {
    Write-Error "No BENCH_RESULT found in $LogPath"
    exit 1
}

$line = $match.Line
Write-Host "Latest result:"
Write-Host $line
Write-Host ""

$pairs = @{}
foreach ($part in ($line -split "\s+")) {
    if ($part -match "=") {
        $kv = $part -split "=", 2
        if ($kv.Count -eq 2) {
            $pairs[$kv[0]] = $kv[1]
        }
    }
}

function Get-DoubleOrZero([string]$value) {
    $out = 0.0
    [void][double]::TryParse($value, [ref]$out)
    return $out
}

function Get-IntOrZero([string]$value) {
    $out = 0
    [void][int]::TryParse($value, [ref]$out)
    return $out
}

$failed = Get-IntOrZero $pairs["failed"]
$minTpsVal = Get-DoubleOrZero $pairs["min_tps"]
$avgTpsVal = Get-DoubleOrZero $pairs["avg_tps"]
$bps = Get-DoubleOrZero $pairs["blocks_per_sec"]

$passFailed = ($failed -eq 0)
$passMinTps = ($minTpsVal -ge $MinTps)
$passAvgTps = ($avgTpsVal -ge $MinAvgTps)
$passBps = ($bps -ge $MinBlocksPerSec)

Write-Host "Baseline checks:"
Write-Host ("  failed==0           : {0}" -f $passFailed)
Write-Host ("  min_tps>={0}       : {1}" -f $MinTps, $passMinTps)
Write-Host ("  avg_tps>={0}      : {1}" -f $MinAvgTps, $passAvgTps)
Write-Host ("  blocks_per_sec>={0} : {1}" -f $MinBlocksPerSec, $passBps)

if ($passFailed -and $passMinTps -and $passAvgTps -and $passBps) {
    Write-Host ""
    Write-Host "BENCH_BASELINE: PASS"
    exit 0
}

Write-Host ""
Write-Host "BENCH_BASELINE: FAIL"
exit 2
