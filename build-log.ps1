# SMS Tech -- build helper that captures the FULL gradle output to a file Claude can read.
#
# Usage from the project root:
#     .\build-log.ps1 :app:kspDebugKotlin
#     .\build-log.ps1 :app:assembleDebug
#     .\build-log.ps1 clean :app:assembleDebug
#
# Output files (both at project root, .gitignored):
#     build.log         - full stdout + stderr of the gradle run (verbose)
#     build_errors.log  - filtered to compile-error lines only
#
# The helper auto-detects gradle in this order:
#   1. Project wrapper       ./gradlew.bat
#   2. System gradle on PATH (gradle or gradle.bat)
#   3. Android Studio bundled gradle
#
# IMPORTANT: ASCII-only file. Some Windows PowerShell builds read .ps1 as CP1252 unless a UTF-8
# BOM is present, and UTF-8 em-dashes get mis-decoded as CP1252 quote characters that close the
# enclosing string and break parsing.

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Continue'
$root      = $PSScriptRoot
$logFile   = Join-Path $root 'build.log'
$errorFile = Join-Path $root 'build_errors.log'

function Find-Gradle {
    # 1. Project wrapper
    $wrapper = Join-Path $root 'gradlew.bat'
    if (Test-Path $wrapper) { return $wrapper }

    # 2. System gradle on PATH
    $sys = Get-Command 'gradle.bat' -ErrorAction SilentlyContinue
    if ($null -eq $sys) { $sys = Get-Command 'gradle' -ErrorAction SilentlyContinue }
    if ($null -ne $sys) { return $sys.Source }

    # 3. Android Studio bundled gradle (most common location on Windows)
    $candidates = @(
        "$env:ProgramFiles\Android\Android Studio\gradle",
        "${env:ProgramFiles(x86)}\Android\Android Studio\gradle",
        "$env:LOCALAPPDATA\Programs\Android Studio\gradle",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\AndroidStudio\ch-0\*\plugins\gradle\lib\gradle"
    )
    foreach ($c in $candidates) {
        $hits = Get-ChildItem -Path $c -Directory -Filter 'gradle-*' -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending
        if ($hits) {
            $bin = Join-Path $hits[0].FullName 'bin\gradle.bat'
            if (Test-Path $bin) { return $bin }
        }
    }

    return $null
}

$gradle = Find-Gradle
if (-not $gradle) {
    Write-Host "ERROR: no gradle executable found." -ForegroundColor Red
    Write-Host "Fix: either generate the wrapper (gradle wrapper) or install Gradle on PATH." -ForegroundColor Yellow
    exit 1
}

Write-Host "=== Using gradle: $gradle" -ForegroundColor Cyan
Write-Host "=== Args:        $($GradleArgs -join ' ')" -ForegroundColor Cyan
Write-Host "=== Full log:    $logFile" -ForegroundColor Cyan
Write-Host "=== Errors:      $errorFile" -ForegroundColor Cyan
Write-Host ""

# Truncate previous run so Claude always reads the latest build only.
Set-Content -Path $logFile -Value '' -Encoding utf8
Set-Content -Path $errorFile -Value '' -Encoding utf8

# --console=plain keeps the output free of carriage returns / progress bars so the log file
# stays grep-friendly. --stacktrace ensures the FIRST error has full context.
$allArgs = @('--console=plain', '--stacktrace') + $GradleArgs

# Tee-Object pipes stdout AND stderr to both the terminal and the log file.
& $gradle @allArgs 2>&1 | Tee-Object -FilePath $logFile
$exit = $LASTEXITCODE

# Compile-error lines are typically:
#   e: file:///J:/applications/.../Foo.kt:42:7 message
#   error: something
#   Unresolved reference: Bar
#   Caused by: java.lang.SomeException
# Plus the line that says which task FAILED.
$pattern = '^e: file:|^error:|Unresolved reference|Cannot find|Caused by:|FAILED'
Select-String -Path $logFile -Pattern $pattern -CaseSensitive:$false |
    ForEach-Object { "$($_.LineNumber): $($_.Line)" } |
    Out-File -FilePath $errorFile -Encoding utf8

Write-Host ""
if ($exit -eq 0) {
    Write-Host "=== Build succeeded (exit 0)" -ForegroundColor Green
} else {
    Write-Host "=== Build FAILED (exit $exit) -- see $errorFile" -ForegroundColor Red
    if ((Get-Item $errorFile).Length -gt 0) {
        Write-Host ""
        Write-Host "--- First 40 error lines ---" -ForegroundColor Yellow
        Get-Content $errorFile -TotalCount 40 | ForEach-Object { Write-Host $_ }
    }
}

exit $exit
