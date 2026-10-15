# Use project-local tools, caches, Java home and temporary directories.
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jdkDirectory = Join-Path $projectRoot '.tools/java/jdk-25.0.4.1+1'
if (-not ((Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin/java.exe')) -and
    (Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin/javac.exe')))) {
    throw 'Project-local JDK is missing or incomplete. Run tools/Initialize-Dev.ps1.'
}

$temporaryDirectory = Join-Path $projectRoot '.tmp'
$javaUserHome = Join-Path $projectRoot '.tools/home'
[System.IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null
[System.IO.Directory]::CreateDirectory($javaUserHome) | Out-Null
$env:JAVA_HOME = $jdkDirectory
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-home'
$env:TEMP = $temporaryDirectory
$env:TMP = $temporaryDirectory
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=`"$temporaryDirectory`" -Duser.home=`"$javaUserHome`""

$runtimeVersion = & (Join-Path $jdkDirectory 'bin/java.exe') -version 2>&1 | Out-String
$runtimeExitCode = $LASTEXITCODE
$compilerVersion = & (Join-Path $jdkDirectory 'bin/javac.exe') -version 2>&1 | Out-String
if ($runtimeExitCode -ne 0 -or $LASTEXITCODE -ne 0 -or
    $runtimeVersion -notmatch 'openjdk version "25\.0\.4\.1"' -or
    $compilerVersion -notmatch 'javac 25\.0\.4\.1') {
    throw 'Project-local JDK failed its version check. Run tools/Initialize-Dev.ps1.'
}

Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @args
    $gradleExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
exit $gradleExitCode
