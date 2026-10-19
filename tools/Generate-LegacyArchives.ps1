# Historical fixture writer. Never called by builds/tests; never links current production sources.
param(
    [string]$BaselineJar = '.tmp/audit-fixes/baseline-v16.jar',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$expectedHash = '2dc248499ec8e60851dc4d73b0e91c63e9db8657f6f3ceef596b1f0e1e03d7a5'
$sourceRef = '51e06b0ff9b9f527938f004dbeb83cb9c4a55ea0'
function ProjectPath([string]$value) {
    $resolved = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $value))
    if (-not $resolved.StartsWith($projectRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Historical generation paths must remain inside the project.'
    }
    return $resolved
}
$baseline = ProjectPath $BaselineJar
if ((Get-FileHash -LiteralPath $baseline -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'The baseline JAR is not the exact frozen v0.0.16 build. Refusing generation.'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = '.tmp/legacy-v16-rebuilt-' + [Guid]::NewGuid().ToString('N')
}
$destination = ProjectPath $OutputDirectory
if ((Test-Path -LiteralPath $destination) -and @(Get-ChildItem -LiteralPath $destination -Force).Count -ne 0) {
    throw 'The destination must be empty. Frozen fixtures are never overwritten by this script.'
}
$run = ProjectPath ('.tmp/legacy-v16-writer-' + [Guid]::NewGuid().ToString('N'))
$sourcesDirectory = Join-Path $run 'sources'
$classesDirectory = Join-Path $run 'classes'
[System.IO.Directory]::CreateDirectory($sourcesDirectory) | Out-Null
[System.IO.Directory]::CreateDirectory($classesDirectory) | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$sourceFiles = @()
$historicalSources = @(
    @('ArchiveTests', 'src/rulesTest/java/dev/riichicraft/rules/ArchiveTests.java', '0ea128d9c421cf2369e13571ae3f4031b5735a1d'),
    @('ThreePlayerEngineTests', 'src/rulesTest/java/dev/riichicraft/rules/ThreePlayerEngineTests.java', '802ac87cec7146dfd499695087f2699f85b2bd5b'),
    @('RiichiNorthExhaustion', 'docs/audit/repro/RiichiNorthExhaustion.java', '5e004f2e4ad9b84c5711acbe409076c2d1287fa5')
)
foreach ($entry in $historicalSources) {
    $blob = & git -C $projectRoot rev-parse ($sourceRef + ':' + $entry[1])
    if ($LASTEXITCODE -ne 0 -or $blob -ne $entry[2]) { throw 'Historical fixture source blob guard failed.' }
    $historical = (& git -C $projectRoot show ($sourceRef + ':' + $entry[1])) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Could not read historical fixture source.' }
    $append = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot ('legacy-v16/' + $entry[0] + '.append.java')))
    $lastBrace = $historical.LastIndexOf('}')
    if ($lastBrace -lt 0) { throw 'Historical source has no class boundary.' }
    $sourceFile = Join-Path $sourcesDirectory ($entry[0] + '.java')
    [System.IO.File]::WriteAllText($sourceFile, $historical.Substring(0, $lastBrace) + $append + "`n}`n", $utf8)
    $sourceFiles += $sourceFile
}
$sourceFiles += Join-Path $projectRoot 'src/rulesTest/java/dev/riichicraft/rules/LegacyArchiveSummary.java'
$sourceFiles += Join-Path $PSScriptRoot 'legacy-v16/LegacyArchiveGenerator.java'
$jdk = Join-Path $projectRoot '.tools/java/jdk-25.0.4.1+1/bin'
$savedEnvironment = @{}
foreach ($name in @('JAVA_TOOL_OPTIONS', 'TEMP', 'TMP')) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:TEMP = Join-Path $projectRoot '.tmp'
    $env:TMP = $env:TEMP
    $env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=`"$env:TEMP`" -Duser.home=`"$(Join-Path $projectRoot '.tools/home')`""
    & (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 25 --class-path $baseline -d $classesDirectory @sourceFiles
    if ($LASTEXITCODE -ne 0) { throw 'Old-JAR-only generator compilation failed.' }
    & (Join-Path $jdk 'java.exe') -ea --class-path ($classesDirectory + [System.IO.Path]::PathSeparator + $baseline) dev.riichicraft.rules.LegacyArchiveGenerator $destination
    if ($LASTEXITCODE -ne 0) { throw 'Old-JAR-only generation failed.' }
}
finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
