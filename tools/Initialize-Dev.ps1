param()

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolsDirectory = Join-Path $projectRoot '.tools'
$javaDirectory = Join-Path $toolsDirectory 'java'
$jdkDirectory = Join-Path $javaDirectory 'jdk-25.0.4.1+1'
$archivePath = Join-Path $toolsDirectory 'microsoft-jdk.zip'
$downloadUrl = 'https://aka.ms/download-jdk/microsoft-jdk-25.0.4.1-windows-x64.zip'
$expectedHash = '3c9099e60a82e17f5847052f50f3b107bfc1d32b366e1a20adbd1d0f303760e7'

[System.IO.Directory]::CreateDirectory($toolsDirectory) | Out-Null
if ((Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin/java.exe')) -and
    (Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin/javac.exe'))) {
    $runtimeVersion = & (Join-Path $jdkDirectory 'bin/java.exe') -version 2>&1 | Out-String
    $runtimeExitCode = $LASTEXITCODE
    $compilerVersion = & (Join-Path $jdkDirectory 'bin/javac.exe') -version 2>&1 | Out-String
    if ($runtimeExitCode -eq 0 -and $LASTEXITCODE -eq 0 -and
        $runtimeVersion -match 'openjdk version "25\.0\.4\.1"' -and
        $compilerVersion -match 'javac 25\.0\.4\.1') {
        Write-Output 'Project-local JDK 25.0.4.1 runtime and compiler verified.'
        exit 0
    }
}

if (-not (Test-Path -LiteralPath $archivePath)) {
    Write-Output 'Downloading Microsoft OpenJDK 25.0.4.1 into this project.'
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath
}

$actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $expectedHash) {
    throw "JDK archive checksum mismatch. Inspect $archivePath before retrying."
}

Expand-Archive -LiteralPath $archivePath -DestinationPath $javaDirectory -Force
Write-Output 'Project-local JDK ready. Run tools/Invoke-Gradle.ps1 build.'
