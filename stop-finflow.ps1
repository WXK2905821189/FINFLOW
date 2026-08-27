$ErrorActionPreference = 'Continue'

$ports = @(8080, 4173)
$processIds = [System.Collections.Generic.HashSet[int]]::new()

function Test-ListeningPort([int] $port) {
    $connection = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($connection) {
        return $true
    }

    $pattern = ':{0}\s+.*LISTENING\s+(\d+)\s*$' -f $port
    return $null -ne (netstat -ano | Select-String -Pattern $pattern)
}

function Add-PortOwners([int] $port) {
    $connections = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    foreach ($connection in $connections) {
        [void]$processIds.Add([int]$connection.OwningProcess)
    }

    # Fall back to netstat when the current PowerShell session cannot inspect
    # TCP connections created by another user or elevated process.
    $pattern = ':{0}\s+.*LISTENING\s+(\d+)\s*$' -f $port
    foreach ($line in (netstat -ano | Select-String -Pattern $pattern)) {
        $match = [regex]::Match($line.ToString(), $pattern)
        if ($match.Success) {
            [void]$processIds.Add([int]$match.Groups[1].Value)
        }
    }
}

foreach ($port in $ports) {
    Add-PortOwners $port
}

if ($processIds.Count -eq 0) {
    Write-Host 'No listener found on FINFLOW ports 8080 or 4173.' -ForegroundColor Green
    exit 0
}

Write-Host 'The following listener processes will be stopped:' -ForegroundColor Yellow
foreach ($processId in $processIds) {
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host ("PID {0}  {1}  {2}" -f $process.Id, $process.ProcessName, $process.Path)
    } else {
        Write-Host ("PID {0}  process details unavailable" -f $processId)
    }
}

foreach ($processId in $processIds) {
    try {
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Write-Host ("Stopped PID {0}." -f $processId) -ForegroundColor Green
    } catch {
        Write-Warning ("Could not stop PID {0}: {1}. Run this script as Administrator if the process belongs to an elevated session." -f $processId, $_.Exception.Message)
    }
}

Start-Sleep -Milliseconds 500
$remaining = foreach ($port in $ports) {
    if (Test-ListeningPort $port) { $port }
}

if ($remaining) {
    Write-Warning ("Still listening on port(s): {0}" -f ($remaining -join ', '))
    exit 1
}

Write-Host 'FINFLOW ports 8080 and 4173 are free.' -ForegroundColor Green
