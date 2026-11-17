# Local Compose only. Production uses a separately provisioned migration login.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$migrationRole = 'crud_migrate_' + [Guid]::NewGuid().ToString('N')
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $passwordBytes = New-Object byte[] 32
    $random.GetBytes($passwordBytes)
    $migrationPassword = [Convert]::ToBase64String($passwordBytes)
} finally {
    $random.Dispose()
}

function Invoke-LocalAdmin {
    param([string]$Sql)
    # Generated role/password contain no quotes. SQL identifiers and values are
    # passed through psql's quoted variables, never concatenated into SQL.
    $script = @'
set -euo pipefail
export PGPASSWORD="$(cat /run/secrets/postgres_password)"
export MIGRATION_ROLE='__ROLE__'
export MIGRATION_PASSWORD='__PASSWORD__'
psql -h 127.0.0.1 -U postgres -d crud_rest_apis -v ON_ERROR_STOP=1 <<'SQL'
\getenv migration_role MIGRATION_ROLE
\getenv migration_password MIGRATION_PASSWORD
__SQL__
SQL
'@
    $script = $script.Replace('__ROLE__', $migrationRole).Replace('__PASSWORD__', $migrationPassword).Replace('__SQL__', $Sql)
    # PowerShell's pipeline uses CRLF; normalize before Bash parses the heredoc.
    $script | docker compose exec -T postgres bash -c "tr -d '\r' | bash"
    if ($LASTEXITCODE -ne 0) {
        throw 'Local database administration failed.'
    }
}

$previousEnvironment = @{}
foreach ($name in @('MIGRATION_DB_URL', 'MIGRATION_DB_USERNAME', 'MIGRATION_DB_PASSWORD')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$roleCreated = $false
Push-Location $projectRoot
try {
    $endpoint = docker compose port postgres 5432
    if ($LASTEXITCODE -ne 0 -or $endpoint.Trim() -ne '127.0.0.1:5432') {
        throw 'Expected the local Compose PostgreSQL service on 127.0.0.1:5432. Start it first.'
    }

    Write-Host 'Preparing temporary migration access for local database crud_rest_apis.'
    $roleCreated = $true # Also clean up if the command fails after committing CREATE ROLE.
    Invoke-LocalAdmin @'
BEGIN;
CREATE ROLE :"migration_role" LOGIN PASSWORD :'migration_password'
    NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT CONNECT ON DATABASE crud_rest_apis TO :"migration_role";
GRANT crud_owner TO :"migration_role";
COMMIT;
'@
    $env:MIGRATION_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/crud_rest_apis?sslmode=disable&requireAuth=scram-sha-256&ApplicationName=notification-migration'
    $env:MIGRATION_DB_USERNAME = $migrationRole
    $env:MIGRATION_DB_PASSWORD = $migrationPassword

    & .\gradlew.bat --no-daemon migrateDatabase
    if ($LASTEXITCODE -ne 0) {
        throw 'Flyway migration failed. Review its error above; schema validation remains enabled.'
    }
    Write-Host 'Local database migrations completed.'
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    try {
        if ($roleCreated) {
            Invoke-LocalAdmin @'
BEGIN;
SELECT format('REVOKE CONNECT ON DATABASE crud_rest_apis FROM %I', rolname)
FROM pg_roles WHERE rolname = :'migration_role' \gexec
SELECT format('REVOKE crud_owner FROM %I', rolname)
FROM pg_roles WHERE rolname = :'migration_role' \gexec
SELECT format('DROP ROLE %I', rolname)
FROM pg_roles WHERE rolname = :'migration_role' \gexec
COMMIT;
'@
        }
    } finally {
        $migrationPassword = $null
        Pop-Location
    }
}
