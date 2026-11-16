$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$secretRoot = Join-Path $projectRoot '.secrets'
$appSecretRoot = Join-Path $secretRoot 'app'
[System.IO.Directory]::CreateDirectory($appSecretRoot) | Out-Null
$secretPaths = @(
    (Join-Path $secretRoot 'postgres_password'),
    (Join-Path $appSecretRoot 'spring.datasource.password')
)
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    foreach ($secretPath in $secretPaths) {
        if (Test-Path -LiteralPath $secretPath) {
            if ([string]::IsNullOrWhiteSpace([System.IO.File]::ReadAllText($secretPath))) {
                throw "Existing secret file is empty: $secretPath"
            }
            continue
        }
        $bytes = New-Object byte[] 32
        $rng.GetBytes($bytes)
        [System.IO.File]::WriteAllText($secretPath, [Convert]::ToBase64String($bytes), [System.Text.UTF8Encoding]::new($false))
    }
} finally {
    $rng.Dispose()
}
Write-Host 'Database secrets are ready. Existing passwords were preserved.'
