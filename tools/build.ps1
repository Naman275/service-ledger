param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (Test-Path "$root\.tools\android-sdk") {
    $env:ANDROID_HOME = "$root\.tools\android-sdk"
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:ANDROID_USER_HOME = "$root\.tools\android-user"
} elseif (-not $env:ANDROID_HOME -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platforms\android-36")) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
Push-Location $root
try {
    $outputRoot = Join-Path $env:LOCALAPPDATA 'ServiceLedger\build'
    & "$root\gradlew.bat" @Tasks "-PledgerBuildRoot=$outputRoot" --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}
