[CmdletBinding()]
param(
    [ValidateSet('goods_extracted', 'inventory_applied', 'partial_payment_insert', 'committed')]
    [string[]] $Phase = @('goods_extracted', 'inventory_applied', 'partial_payment_insert', 'committed'),
    [int] $TimeoutSeconds = 150
)

$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$probePath = Join-Path $repoPath 'build\wal-hard-kill-probe'
$worldPath = Join-Path $repoPath 'run\world'
$walPath = Join-Path $worldPath 'scex_viscriptshop_ae2_transactions'
New-Item -ItemType Directory -Force -Path $probePath | Out-Null

function Stop-WalProcessTree([int] $RootId) {
    $processes = @(Get-CimInstance Win32_Process)
    $children = @{}
    foreach ($process in $processes) {
        $parent = [int] $process.ParentProcessId
        if (-not $children.ContainsKey($parent)) { $children[$parent] = [System.Collections.Generic.List[int]]::new() }
        $children[$parent].Add([int] $process.ProcessId)
    }
    function Stop-Descendants([int] $ProcessId) {
        if ($children.ContainsKey($ProcessId)) {
            foreach ($child in $children[$ProcessId]) { Stop-Descendants $child }
        }
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
    Stop-Descendants $RootId
}

function Start-WalGradle([string[]] $Arguments, [string] $Output, [string] $ErrorOutput) {
    return Start-Process -FilePath (Join-Path $repoPath 'gradlew.bat') -WorkingDirectory $repoPath `
        -ArgumentList $Arguments -RedirectStandardOutput $Output -RedirectStandardError $ErrorOutput `
        -WindowStyle Hidden -PassThru
}

function Wait-WalFile([System.Diagnostics.Process] $Process, [string] $Path, [int] $Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $Path) { return $true }
        if ($Process.HasExited) { return $false }
        Start-Sleep -Milliseconds 200
        $Process.Refresh()
    }
    return $false
}

foreach ($currentPhase in $Phase) {
    if (Test-Path -LiteralPath $walPath) {
        $pending = @(Get-ChildItem -LiteralPath $walPath -Filter '*.nbt' -File)
        if ($pending.Count -ne 0) {
            throw "Refusing to start phase $currentPhase with pending WAL files in $walPath"
        }
    }

    $phasePath = Join-Path $probePath $currentPhase
    New-Item -ItemType Directory -Force -Path $phasePath | Out-Null
    $killSentinel = Join-Path $phasePath 'kill.sentinel'
    $verifySentinel = Join-Path $phasePath 'verify.sentinel'
    Remove-Item -LiteralPath $killSentinel, $verifySentinel -Force -ErrorAction SilentlyContinue

    $killProcess = Start-WalGradle @(
        "-PwalKillPhase=$currentPhase",
        "-PwalSentinel=$killSentinel",
        'runGameTestServer',
        '--no-build-cache'
    ) (Join-Path $phasePath 'kill.stdout.log') (Join-Path $phasePath 'kill.stderr.log')

    if (-not (Wait-WalFile $killProcess $killSentinel $TimeoutSeconds)) {
        Stop-WalProcessTree $killProcess.Id
        throw "Phase $currentPhase did not reach its durable sentinel"
    }
    Stop-WalProcessTree $killProcess.Id
    Start-Sleep -Seconds 2

    $verifyProcess = Start-WalGradle @(
        "-PwalVerifyPhase=$currentPhase",
        "-PwalVerifySentinel=$verifySentinel",
        'runGameTestServer',
        '--no-build-cache'
    ) (Join-Path $phasePath 'verify.stdout.log') (Join-Path $phasePath 'verify.stderr.log')

    if (-not (Wait-WalFile $verifyProcess $verifySentinel $TimeoutSeconds)) {
        Stop-WalProcessTree $verifyProcess.Id
        throw "Phase $currentPhase restart did not reach its verification sentinel"
    }
    if (-not $verifyProcess.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-WalProcessTree $verifyProcess.Id
        throw "Phase $currentPhase verification server did not exit"
    }
    if ($verifyProcess.ExitCode -ne 0) {
        throw "Phase $currentPhase verification failed with exit code $($verifyProcess.ExitCode)"
    }

    if (Test-Path -LiteralPath $walPath) {
        $resolvedWorld = (Resolve-Path -LiteralPath $worldPath).Path
        $resolvedWal = (Resolve-Path -LiteralPath $walPath).Path
        if (-not $resolvedWal.StartsWith($resolvedWorld + [IO.Path]::DirectorySeparatorChar)) {
            throw 'Resolved WAL path escaped the test world'
        }
        $archivePath = Join-Path $phasePath 'transactions-after-verify'
        if (Test-Path -LiteralPath $archivePath) {
            throw "Archive already exists: $archivePath"
        }
        Move-Item -LiteralPath $resolvedWal -Destination $archivePath
    }
}

Write-Output "External hard-kill WAL matrix passed: $($Phase -join ', ')"
