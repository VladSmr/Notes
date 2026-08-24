<#
.SYNOPSIS
    Build the "KP IMDB Importer" desktop installer via jpackage.

.DESCRIPTION
    Problem with fat-JAR: Spring Boot fat-JAR (classes in BOOT-INF) is incompatible
    with jpackage. Here we use a thin-JAR (from .jar.original) + AppLauncher on a flat
    classpath (jpackage enumerates all jars from app/* into app.classpath itself).

    Steps:
      1. (optional) mvn clean package - thin jar + unit tests;
      2. assemble jpackage-input: notes.jar (thin) + app/* (runtime deps);
      3. jlink: runtime-image = JDK (java.se) + JavaFX modules (from local win jars,
         which contain module-info.class - no need to download jmods);
      4. jpackage: app-image (.exe) and, if WiX 3.x is present, .msi.

    Usage (from project root):
        powershell -ExecutionPolicy Bypass -File build-msi.ps1 [-Msi] [-SkipBuild] [-Console]

    Switches:
        -Msi        also build .msi (requires WiX Toolset 3.x)
        -SkipBuild  do not run mvn (used when invoked from pom.xml, build already done)
        -Console    keep a console window (diagnostics; off by default)
#>
[CmdletBinding()]
param(
    [switch]$Msi,
    [switch]$SkipBuild,
    [switch]$Console,

    # Optional overrides. When omitted, values are resolved dynamically:
    #   - JavaFxVersion: highest version dir present in the local m2 cache for each module;
    #   - AppVersion:    parsed from the built thin-jar filename (notes-<version>.jar.original).
    #   - Vendor:        organisation/author shown in the installer metadata.
    [string]$JavaFxVersion,
    [string]$AppVersion,
    [string]$Vendor
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

# ---------- Configuration ----------
$AppName      = 'KP IMDB Importer'
if (-not $Vendor) { $Vendor = 'KP IMDB Importer' }   # организация/автор; переопределяется через -Vendor
$MainClass    = 'ru.importer.notes.desktop.AppLauncher'
$IconPath     = Join-Path $ProjectRoot 'packaging\app.ico'

# AppVersion is resolved later from the built thin-jar filename (see step 2),
# unless explicitly overridden via -AppVersion.

$TargetDir    = Join-Path $ProjectRoot 'target'
$InputDir     = Join-Path $TargetDir 'jpackage-input'
$ImageDir     = Join-Path $TargetDir 'jpackage-image'
$DistDir      = Join-Path $TargetDir 'dist'

# JDK
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\jpackage.exe'))) {
    throw "JAVA_HOME does not point to a JDK with jpackage: '$JavaHome'"
}
$Jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
$Jlink    = Join-Path $JavaHome 'bin\jlink.exe'
$JdkJmods = Join-Path $JavaHome 'jmods'

# JavaFX modular jars (win classifier, contain module-info.class) from local m2 cache.
# Version is taken from the highest version directory present for each module, unless
# overridden via -JavaFxVersion (keeps the script in sync with pom.xml automatically).
$OpenJfxBase = Join-Path $env:USERPROFILE '.m2\repository\org\openjfx'
$JavaFxModules = @('javafx-base','javafx-graphics','javafx-controls','javafx-media','javafx-web')
$JavaFxJars = @()
foreach ($m in $JavaFxModules) {
    $moduleDir = Join-Path $OpenJfxBase $m
    if (-not (Test-Path $moduleDir)) { throw "JavaFX module dir not found: $moduleDir" }

    if ($JavaFxVersion) {
        $v = $JavaFxVersion
    } else {
        $versions = Get-ChildItem $moduleDir -Directory -ErrorAction SilentlyContinue |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } }
        if (-not $versions) { throw "No JavaFX versions found under $moduleDir" }
        $v = $versions[-1].Name
    }

    $jar = Join-Path $OpenJfxBase "$m\$v\$m-$v-win.jar"
    if (-not (Test-Path $jar)) { throw "JavaFX module not found: $jar" }
    $JavaFxJars += $jar
}

function Write-Step([string]$msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# ---------- 0. Ensure a valid .ico ----------
# The original "KP to IMDB logo.ico" is actually a PNG; jpackage rejects it.
# If app.ico does not start with the ICO header (00 00 01 00) - regenerate it.
if (Test-Path $IconPath) {
    $head = [System.IO.File]::ReadAllBytes($IconPath)[0..3]
    $isIco = ($head[0] -eq 0 -and $head[1] -eq 0 -and $head[2] -eq 1 -and $head[3] -eq 0)
    if (-not $isIco) {
        Write-Step "Icon is not a real .ico (it is PNG) - regenerating via create-icon.ps1"
        & powershell -ExecutionPolicy Bypass -File (Join-Path $ProjectRoot 'packaging\create-icon.ps1')
        if ($LASTEXITCODE -ne 0) { throw "create-icon.ps1 failed (exit $LASTEXITCODE)" }
    }
} else {
    Write-Warning "Icon not found: $IconPath (building without icon)"
}

# ---------- 1. Build project ----------
if (-not $SkipBuild) {
    Write-Step "Maven: clean package (tests enabled)"
    & .\mvnw.cmd clean package '-DskipTests=false'
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
} else {
    Write-Step "Skipping mvn (-SkipBuild mode)"
}

# ---------- 2. Thin JAR + dependencies ----------
Write-Step "Assembling jpackage-input (thin jar + app/*)"
# Resolve the thin jar produced by spring-boot-maven-plugin (notes-<version>.jar.original)
# and parse the artifact version from its filename instead of hardcoding it.
$thinJars = @(Get-ChildItem -Path $TargetDir -Filter 'notes-*.jar.original' -ErrorAction SilentlyContinue |
    ForEach-Object {
        if ($_.Name -match '^notes-(.+)\.jar\.original$') {
            [pscustomobject]@{ Path = $_.FullName; Version = $Matches[1]; LastWriteTime = $_.LastWriteTime }
        }
    })
if (-not $thinJars) { throw "Thin jar (notes-*.jar.original) not found in $TargetDir" }
$thinJar = $thinJars | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$OriginalJar = $thinJar.Path
$ParsedVersion = $thinJar.Version

if ($AppVersion) {
    $AppVersion = $AppVersion
} elseif ($ParsedVersion -match '^\d+(\.\d+)*$') {
    $AppVersion = $ParsedVersion
} else {
    # e.g. "0.0.1-SNAPSHOT" is not a valid jpackage --app-version; fall back to a safe value.
    Write-Warning "Artifact version '$ParsedVersion' is not numeric; using 1.0.0 for jpackage --app-version"
    $AppVersion = '1.0.0'
}
Write-Host "Using app-version: $AppVersion"

if (-not (Test-Path $OriginalJar)) { throw "Thin jar not found: $OriginalJar" }

if (Test-Path $InputDir) { Remove-Item $InputDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $InputDir 'app') | Out-Null
Copy-Item $OriginalJar (Join-Path $InputDir 'notes.jar') -Force

$CpFile = Join-Path $TargetDir 'cp.txt'
# NOTE: user property for build-classpath's includeScope is `includeScope`,
# NOT `mdep.includeScope` (that one is silently ignored -> test deps leak in).
# includeScope=runtime => compile + runtime only (excludes test AND provided).
& .\mvnw.cmd org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath `
    "-Dmdep.outputFile=$CpFile" '-DincludeScope=runtime' '-q'
if (-not (Test-Path $CpFile)) { throw 'Could not obtain dependency classpath' }

# Safety net: additionally drop any test-only artifact by name, in case a future
# dependency change reintroduces a test-scoped jar into the runtime classpath.
# Keep it conservative - only unmistakably test-only artifacts (incl. their
# test-only transitives). Do NOT touch e.g. guava / byte-buddy / gson-if-runtime,
# which Selenium legitimately needs at runtime.
$TestArtifactRegex = '(?i)^(?:' +
    'junit-|junit-jupiter|junit-platform|junit-vintage|opentest4j|apiguardian|' +
    'mockito-|byte-buddy-agent|objenesis|assertj-|hamcrest-|' +
    'spring-test|spring-boot-test|spring-boot-test-autoconfigure|' +
    'webdrivermanager|docker-java|json-path|json-smart|accessors-smart|' +
    'jsonassert|android-json|xmlunit|awaitility|jcl-over-slf4j|' +
    'gson-|jna-|dec-|commons-compress-' +
    ')'

$cp = Get-Content $CpFile -Raw
$copied = 0
$skipped = @()
foreach ($jar in ($cp -split ';')) {
    if (-not $jar -or -not (Test-Path $jar)) { continue }
    if ((Split-Path $jar -Leaf) -match $TestArtifactRegex) {
        $skipped += (Split-Path $jar -Leaf)
        continue
    }
    Copy-Item $jar (Join-Path $InputDir 'app') -Force
    $copied++
}
if ($skipped.Count -gt 0) {
    Write-Host "Safety-net filter removed $($skipped.Count) test artifact(s):"
    $skipped | ForEach-Object { Write-Host "  - $_" }
}
Write-Host "Dependencies in app/: $((Get-ChildItem (Join-Path $InputDir 'app') -Filter *.jar).Count)"

# ---------- 3. jlink runtime-image (JDK java.se + JavaFX) ----------
Write-Step "jlink: building runtime-image"
if (Test-Path $ImageDir) { Remove-Item $ImageDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $ImageDir | Out-Null

$ModulePath = "$JdkJmods;" + ($JavaFxJars -join ';')
# java.se covers all standard SE modules (needed by Spring Boot/Tomcat: java.management,
# java.naming, java.rmi, etc.). jdk.unsupported/jdk.crypto.ec are extra JDK modules.
$AddModules = 'java.se,jdk.unsupported,jdk.crypto.ec,' +
              'javafx.controls,javafx.graphics,javafx.base,javafx.media,javafx.web'

& $Jlink --module-path $ModulePath --add-modules $AddModules `
    --strip-debug --no-header-files --no-man-pages `
    --output (Join-Path $ImageDir 'image')
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }
Write-Host "Runtime-image built: $(Join-Path $ImageDir 'image')"

# ---------- 4. jpackage: app-image ----------
Write-Step "jpackage: app-image (.exe)"
if (Test-Path $DistDir) { Remove-Item $DistDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

$commonArgs = @(
    '--input', $InputDir,
    '--main-jar', 'notes.jar',
    '--main-class', $MainClass,
    '--name', $AppName,
    '--vendor', $Vendor,
    '--app-version', $AppVersion,
    '--runtime-image', (Join-Path $ImageDir 'image'),
    '--dest', $DistDir,
    # JavaFX is in the runtime image as a module; javafx.graphics does not export
    # com.sun.javafx.application to the unnamed module. AppLauncher uses LauncherImpl,
    # so open that package.
    '--java-options', '--add-exports javafx.graphics/com.sun.javafx.application=ALL-UNNAMED'
)
if (Test-Path $IconPath) { $commonArgs += @('--icon', $IconPath) }
if ($Console) { $commonArgs += '--win-console' }

# jpackage builds the app classpath itself: it enumerates all jars from app/* into app.classpath.
$appImageArgs = @('--type', 'app-image') + $commonArgs
& $Jpackage @appImageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image failed' }
Write-Host "App-image built: $(Join-Path $DistDir "$AppName")"

# ---------- 5. jpackage: .msi (if requested and WiX present) ----------
if ($Msi) {
    Write-Step "jpackage: .msi (requires WiX 3.x)"
    $msiArgs = @('--type', 'msi') + $commonArgs +
        @('--win-menu', '--win-shortcut', '--win-menu-group', $AppName)
    & $Jpackage @msiArgs
    if ($LASTEXITCODE -ne 0) { throw 'jpackage msi failed' }
    Write-Host "MSI built: $(Join-Path $DistDir "$AppName-$AppVersion.msi")"
} else {
    Write-Host "`nSkipped .msi (use -Msi). For .msi you need WiX Toolset 3.x."
}

Write-Host "`nDONE. Result in: $DistDir" -ForegroundColor Green
