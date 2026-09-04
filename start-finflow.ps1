$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendPom = Join-Path $projectRoot 'backend\pom.xml'
$frontendRoot = Join-Path $projectRoot 'frontend'
$viteCli = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
$frontendUrl = 'http://localhost:4173/login'

function Test-ListeningPort([int] $port) {
    $connection = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($connection) {
        return $true
    }

    # Fall back to netstat when the current PowerShell session cannot inspect
    # TCP connections created by another user or elevated process.
    return $null -ne (netstat -ano | Select-String -Pattern (':{0}\s+.*LISTENING' -f $port))
}

function Find-Executable([string[]] $candidates, [string] $commandName) {
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $command = Get-Command $commandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    return $null
}

if (-not (Test-Path -LiteralPath $backendPom)) {
    throw "Backend project not found: $backendPom"
}
if (-not (Test-Path -LiteralPath $viteCli)) {
    throw "Frontend dependencies are missing: $viteCli"
}

$java = Find-Executable @(
    'D:\openjdk-26.0.2.1_windows-x64_bin\bin\java.exe',
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' })
) 'java'
$maven = Find-Executable @(
    'C:\Users\王小棵\AppData\Local\Temp\finflow-buildtools\apache-maven-3.9.11\bin\mvn.cmd',
    $(if ($env:MAVEN_HOME) { Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' })
) 'mvn.cmd'
$node = Find-Executable @(
    'C:\Users\王小棵\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe',
    $(if ($env:NODE_HOME) { Join-Path $env:NODE_HOME 'node.exe' })
) 'node'

if (-not $java) { throw 'Java was not found. Install Java 17+ or set JAVA_HOME.' }
if (-not $maven) { throw 'Maven was not found. Install Maven 3.9+ or set MAVEN_HOME.' }
if (-not $node) { throw 'Node.js was not found. Install Node.js 18+ or set NODE_HOME.' }

$javaHome = Split-Path (Split-Path $java -Parent) -Parent

Write-Host 'FINFLOW startup check' -ForegroundColor Cyan
Write-Host "Project: $projectRoot"

if (Test-ListeningPort 8080) {
    Write-Host 'Backend 8080 is already running; reuse it.' -ForegroundColor Green
} else {
    $backendCommand = "`$env:JAVA_HOME = '$javaHome'; & '$maven' -f '$backendPom' spring-boot:run"
    Start-Process -FilePath 'powershell.exe' -WorkingDirectory $projectRoot -ArgumentList @(
        '-NoLogo', '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', $backendCommand
    ) | Out-Null
    Write-Host 'Backend startup window opened on port 8080.' -ForegroundColor Yellow
}

if (Test-ListeningPort 4173) {
    Write-Host 'Frontend 4173 is already running; reuse it.' -ForegroundColor Green
} else {
    $frontendCommand = "Set-Location -LiteralPath '$frontendRoot'; & '$node' '$viteCli' --host 0.0.0.0 --port 4173"
    Start-Process -FilePath 'powershell.exe' -WorkingDirectory $frontendRoot -ArgumentList @(
        '-NoLogo', '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', $frontendCommand
    ) | Out-Null
    Write-Host 'Frontend startup window opened on port 4173.' -ForegroundColor Yellow
}

Start-Sleep -Seconds 2
Start-Process $frontendUrl | Out-Null
Write-Host "Login page opened: $frontendUrl" -ForegroundColor Green
Write-Host 'Default account: admin / Admin@123'
