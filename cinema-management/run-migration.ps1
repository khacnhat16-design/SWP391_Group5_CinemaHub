# Run UploadThingMigration as a standalone Java process.
# Usage: .\run-migration.ps1
# Must be run from cinema-management/ directory.

$ErrorActionPreference = "Stop"
$BASE = $PSScriptRoot

# Build classpath from Maven target/classes and .m2 repository
$CP = @("$BASE\target\classes")

# Add Maven dependencies
$M2 = "$env:USERPROFILE\.m2\repository"
$DEPS = @(
    "com\microsoft.sqlserver\mssql-jdbc\12.6.1.jre11\mssql-jdbc-12.6.1.jre11.jar"
)

foreach ($dep in $DEPS) {
    $jar = Join-Path $M2 $dep
    if (Test-Path $jar) { $CP += $jar }
}

# Also add tomcat-embed jars from Maven repo (same approach as CinemaApplication)
$tomcats = Get-ChildItem -Path "$M2\org\apache\tomcat\embed" -Directory -ErrorAction SilentlyContinue
foreach ($tc in $tomcats) {
    $jars = Get-ChildItem -Path $tc.FullName -Filter "*.jar" -Recurse -Depth 2 -ErrorAction SilentlyContinue
    foreach ($j in $jars) { $CP += $j.FullName }
}

# Add jackson
$jacksonDeps = @(
    "com\fasterxml\jackson\core\jackson-databind\2.13.2\jackson-databind-2.13.2.jar",
    "com\fasterxml\jackson\core\jackson-core\2.13.2\jackson-core-2.13.2.jar",
    "com\fasterxml\jackson\core\jackson-annotations\2.13.2\jackson-annotations-2.13.2.jar"
)
foreach ($dep in $jacksonDeps) {
    $jar = Join-Path $M2 $dep
    if (Test-Path $jar) { $CP += $jar }
}

# Load .env into environment
$envFile = "$BASE\.env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            $idx = $line.IndexOf("=")
            if ($idx -gt 0) {
                $k = $line.Substring(0, $idx).Trim()
                $v = $line.Substring($idx + 1).Trim()
                if (-not [System.Environment]::GetEnvironmentVariable($k)) {
                    [System.Environment]::SetEnvironmentVariable($k, $v)
                }
            }
        }
    }
    Write-Host "[run-migration] Loaded .env from $envFile"
}

$classpath = $CP -join ";"
Write-Host "[run-migration] Classpath has $($CP.Count) entries"

java -cp $classpath com.cinema.upload.UploadThingMigration
