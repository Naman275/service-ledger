param(
    [string] $SigningDirectory = (Join-Path $env:LOCALAPPDATA 'ServiceLedger\signing'),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Tasks = @(':app:bundleRelease')
)
$ErrorActionPreference = 'Stop'
$keyFile = Join-Path $SigningDirectory 'upload-key.p12'
$passwordFile = Join-Path $SigningDirectory 'upload-key-password.dpapi'
if (-not (Test-Path $keyFile) -or -not (Test-Path $passwordFile)) {
    throw 'The local upload key/password is missing. Run tools\create-upload-key.ps1 once, then back up the signing material securely.'
}
$variables = @('LEDGER_KEYSTORE_PATH', 'LEDGER_STORE_PASSWORD', 'LEDGER_KEY_ALIAS', 'LEDGER_KEY_PASSWORD')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$secure = ConvertTo-SecureString ([IO.File]::ReadAllText($passwordFile).Trim())
$pointer = [IntPtr]::Zero
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $env:LEDGER_KEYSTORE_PATH = [IO.Path]::GetFullPath($keyFile)
    $env:LEDGER_KEY_ALIAS = 'upload'
    $env:LEDGER_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:LEDGER_KEY_PASSWORD = $env:LEDGER_STORE_PASSWORD
    & (Join-Path $PSScriptRoot 'build.ps1') @Tasks
} finally {
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $secure.Dispose()
}
