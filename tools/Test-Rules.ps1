# Compile and test only the pure Java rules; do not start Gradle, Fabric or Minecraft.
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jdkDirectory = Join-Path $projectRoot '.tools/java/jdk-25.0.4.1+1'
$javaExecutable = Join-Path $jdkDirectory 'bin/java.exe'
$javacExecutable = Join-Path $jdkDirectory 'bin/javac.exe'
if (-not ((Test-Path -LiteralPath $javaExecutable) -and (Test-Path -LiteralPath $javacExecutable))) {
    throw 'Project-local JDK is missing. Run tools/Initialize-Dev.ps1.'
}

$temporaryDirectory = Join-Path $projectRoot '.tmp'
$javaUserHome = Join-Path $projectRoot '.tools/home'
$testRunDirectory = Join-Path $temporaryDirectory ('rules-tests-' + [Guid]::NewGuid().ToString('N'))
$classesDirectory = Join-Path $testRunDirectory 'classes'
[System.IO.Directory]::CreateDirectory($classesDirectory) | Out-Null
[System.IO.Directory]::CreateDirectory($javaUserHome) | Out-Null

$savedEnvironment = @{}
foreach ($variableName in @('JAVA_TOOL_OPTIONS', 'TEMP', 'TMP')) {
    $savedEnvironment[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
}
try {
    $env:TEMP = $temporaryDirectory
    $env:TMP = $temporaryDirectory
    $env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=`"$temporaryDirectory`" -Duser.home=`"$javaUserHome`""

    $compilerVersion = & $javacExecutable -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $compilerVersion -notmatch 'javac 25\.0\.4\.1') {
        throw 'Project-local compiler failed its pinned version check.'
    }

    $sources = @((Join-Path $projectRoot 'src/main/java/dev/riichicraft/game/TileCatalog.java'))
    $sources += Join-Path $projectRoot 'src/main/java/dev/riichicraft/table/TableWallLayout.java'
    $sources += Join-Path $projectRoot 'src/main/java/dev/riichicraft/table/TileMotion.java'
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main/java/dev/riichicraft/rules') -Filter '*.java' -File -Recurse | ForEach-Object FullName)
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main/java/dev/riichicraft/persistence') -Filter '*.java' -File -Recurse | ForEach-Object FullName)
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/rulesTest/java') -Filter '*.java' -File -Recurse | ForEach-Object FullName)

    # A fresh, empty classpath ensures that no game or globally installed test library is needed.
    & $javacExecutable -encoding UTF-8 --release 25 --class-path $classesDirectory -d $classesDirectory @sources
    if ($LASTEXITCODE -ne 0) { throw 'Standalone rules compilation failed.' }
    & $javaExecutable -ea --class-path $classesDirectory dev.riichicraft.rules.RulesTests
    if ($LASTEXITCODE -ne 0) { throw 'Standalone rules verification failed.' }
}
finally {
    foreach ($variableName in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($variableName, $savedEnvironment[$variableName], 'Process')
    }
}
