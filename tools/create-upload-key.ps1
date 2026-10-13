param(
    [string] $Directory = (Join-Path $env:LOCALAPPDATA 'ServiceLedger\signing')
)
$ErrorActionPreference = 'Stop'
$directoryPath = [IO.Path]::GetFullPath($Directory)
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if ($directoryPath.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
    $directoryPath.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The upload key must be stored outside the source repository.'
}
$keyFile = Join-Path $directoryPath 'upload-key.p12'
$passwordFile = Join-Path $directoryPath 'upload-key-password.dpapi'
if ((Test-Path $keyFile) -or (Test-Path $passwordFile)) {
    throw "Signing files already exist in $directoryPath. They will not be overwritten."
}
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { (Get-Command keytool -ErrorAction Stop).Source }
if (-not (Test-Path $keytool)) { throw 'JDK keytool was not found.' }
New-Item -ItemType Directory -Path $directoryPath -Force | Out-Null
$acl = Get-Acl $directoryPath
$acl.SetAccessRuleProtection($true, $false)
$user = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
    $user, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
    [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
Set-Acl -Path $directoryPath -AclObject $acl

$bytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$previous = $env:LEDGER_UPLOAD_PASSWORD
try {
    $random.GetBytes($bytes)
    $env:LEDGER_UPLOAD_PASSWORD = [Convert]::ToBase64String($bytes)
    $secure = ConvertTo-SecureString $env:LEDGER_UPLOAD_PASSWORD -AsPlainText -Force
    try {
        $protected = ConvertFrom-SecureString $secure
        [IO.File]::WriteAllText($passwordFile, $protected)
    } finally {
        $secure.Dispose()
    }
    & $keytool -genkeypair -keystore $keyFile -storetype PKCS12 -alias upload `
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Naman Singla' `
        -storepass:env LEDGER_UPLOAD_PASSWORD -keypass:env LEDGER_UPLOAD_PASSWORD -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Upload-key generation failed. Inspect the local signing directory before retrying.' }
    & $keytool -exportcert -keystore $keyFile -alias upload -storepass:env LEDGER_UPLOAD_PASSWORD `
        -file (Join-Path $directoryPath 'upload-certificate.der')
    if ($LASTEXITCODE -ne 0) { throw 'The key was created, but its public certificate could not be exported.' }
    Write-Host "Upload key created in $directoryPath. No password has been printed."
    Write-Host 'The DPAPI password file is tied to this Windows account/machine. Back up the key and password securely before publishing.'
} finally {
    $env:LEDGER_UPLOAD_PASSWORD = $previous
    [Array]::Clear($bytes, 0, $bytes.Length)
    $random.Dispose()
}
