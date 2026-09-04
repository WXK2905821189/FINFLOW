[CmdletBinding()]
param(
    [ValidateSet('EmptyAndRepeat', 'Incremental')]
    [string]$Scenario = 'EmptyAndRepeat',
    [ValidateRange(1024, 65535)]
    [int]$Port = 18080
)

$ErrorActionPreference = 'Stop'

function Require-EnvironmentVariable([string]$name) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Missing required environment variable: $name"
    }
    return $value
}

function Wait-ForHealth([int]$listenPort, [System.Diagnostics.Process]$process, [string]$logPath) {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        if ($process.HasExited) {
            throw "Candidate process exited before health check. Review $logPath"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$listenPort/v3/api-docs" -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for the candidate health endpoint. Review $logPath"
}

function Start-ValidationRun([string]$name, [string]$flywayTarget, [string]$projectRoot, [string]$maven, [string]$repository, [int]$listenPort, [string]$logDirectory) {
    $logPath = Join-Path $logDirectory "$name.log"
    $arguments = @("-Dmaven.repo.local=$repository")
    $runArguments = "--server.port=$listenPort"
    if ($flywayTarget) {
        $runArguments = "$runArguments --spring.flyway.target=$flywayTarget"
    }
    $arguments += "-Dspring-boot.run.arguments=$runArguments"
    $arguments += 'spring-boot:run'

    $process = Start-Process -FilePath $maven -WorkingDirectory (Join-Path $projectRoot 'backend') `
        -ArgumentList $arguments -RedirectStandardOutput $logPath -RedirectStandardError "$logPath.stderr" -PassThru
    try {
        Wait-ForHealth $listenPort $process $logPath
    } finally {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force
            $process.WaitForExit()
        }
    }
    return $logPath
}

if ($env:FINFLOW_RDS_TEST_CONFIRM -ne 'RUN_NON_PRODUCTION_RDS_VALIDATION') {
    throw 'Set FINFLOW_RDS_TEST_CONFIRM=RUN_NON_PRODUCTION_RDS_VALIDATION before running this script.'
}

$jdbcUrl = Require-EnvironmentVariable 'FINFLOW_RDS_JDBC_URL'
$dbUsername = Require-EnvironmentVariable 'FINFLOW_RDS_USERNAME'
$dbPassword = Require-EnvironmentVariable 'FINFLOW_RDS_PASSWORD'
$jwtSecret = Require-EnvironmentVariable 'FINFLOW_RDS_JWT_SECRET'

if (-not $jdbcUrl.StartsWith('jdbc:mysql:', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'FINFLOW_RDS_JDBC_URL must be a MySQL JDBC URL.'
}
if ($jdbcUrl -notmatch '(?i)(verify|test|stage|sandbox)') {
    throw 'The JDBC URL must visibly identify a non-production validation database (verify, test, stage, or sandbox).'
}
if ($jwtSecret.Length -lt 32) {
    throw 'FINFLOW_RDS_JWT_SECRET must be at least 32 characters.'
}

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) { throw 'Maven 3.9+ is required on PATH.' }

$repository = Join-Path $projectRoot 'backend\.m2-local\repository'
$logDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('finflow-rds-validation-' + (Get-Date -Format 'yyyyMMddHHmmss'))
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

$previous = @{
    SPRING_PROFILES_ACTIVE = $env:SPRING_PROFILES_ACTIVE
    DB_URL = $env:DB_URL
    DB_USERNAME = $env:DB_USERNAME
    DB_PASSWORD = $env:DB_PASSWORD
    JWT_SECRET = $env:JWT_SECRET
}

try {
    $env:SPRING_PROFILES_ACTIVE = 'prod'
    $env:DB_URL = $jdbcUrl
    $env:DB_USERNAME = $dbUsername
    $env:DB_PASSWORD = $dbPassword
    $env:JWT_SECRET = $jwtSecret

    if ($Scenario -eq 'Incremental') {
        $baseline = Start-ValidationRun 'baseline-v5' '5' $projectRoot $mavenCommand.Source $repository $Port $logDirectory
        if (-not (Select-String -Path $baseline -Pattern 'version v5' -Quiet)) {
            throw "V1-V5 baseline evidence was not found. Review $baseline"
        }
    }

    $expectedApplied = if ($Scenario -eq 'Incremental') {
        'Successfully applied 4 migrations'
    } else {
        'Successfully applied 9 migrations'
    }

    $first = Start-ValidationRun 'full-v9' $null $projectRoot $mavenCommand.Source $repository $Port $logDirectory
    if (-not (Select-String -Path $first -Pattern $expectedApplied -Quiet)) {
        throw "Expected migration evidence '$expectedApplied' was not found. Review $first"
    }

    $second = Start-ValidationRun 'repeat-v9' $null $projectRoot $mavenCommand.Source $repository $Port $logDirectory
    if (-not (Select-String -Path $second -Pattern 'Successfully validated 9 migrations' -Quiet)) {
        throw "Repeat-start validation evidence was not found. Review $second"
    }

    Write-Host "RDS $Scenario validation completed. Logs: $logDirectory" -ForegroundColor Green
    Write-Host 'Next gate: retain a non-sensitive backup/restore record and verify key tables and indexes with a read-only database client.' -ForegroundColor Yellow
} finally {
    foreach ($name in $previous.Keys) {
        if ($null -eq $previous[$name]) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item "Env:$name" $previous[$name]
        }
    }
}
