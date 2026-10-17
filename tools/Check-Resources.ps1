#requires -Version 7.0
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resourceRoot = Join-Path $projectRoot 'src/main/resources'
$namespace = 'riichicraft'
$assetRoot = Join-Path $resourceRoot "assets/$namespace"
$errors = [System.Collections.Generic.List[string]]::new()
$documents = @{}
$references = [System.Collections.Generic.HashSet[string]]::new()

function Get-ProjectPath([string] $path) {
    return [System.IO.Path]::GetRelativePath($projectRoot, $path).Replace('\', '/')
}

function Add-Problem([string] $path, [string] $message) {
    $errors.Add("$(Get-ProjectPath $path): $message")
}

function Get-ResourcePath([string] $id, [string] $kind, [string] $source) {
    if (-not $id.StartsWith("${namespace}:", [System.StringComparison]::Ordinal)) { return $null }
    $resourcePath = $id.Substring($namespace.Length + 1)
    if ($resourcePath -cnotmatch '^[a-z0-9_./-]+$' -or @($resourcePath.Split('/') | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
        Add-Problem $source "Invalid $kind identifier '$id'."
        return $null
    }
    $directory = switch ($kind) {
        'model' { 'models' }
        'texture' { 'textures' }
        'item' { 'items' }
        'block' { 'blockstates' }
        default { throw "Unsupported resource kind '$kind'." }
    }
    $extension = if ($kind -eq 'texture') { '.png' } else { '.json' }
    return Join-Path $assetRoot "$directory/$resourcePath$extension"
}

function Test-ResourceReference([object] $value, [string] $kind, [string] $source) {
    if ($value -isnot [string]) { return }
    $target = Get-ResourcePath $value $kind $source
    if ($null -eq $target -or -not $references.Add("${kind}:$value")) { return }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        Add-Problem $source "Missing $kind '$value' ($(Get-ProjectPath $target))."
    }
}

# Follow semantic model/texture/item fields, including nested item model definitions.
# Minecraft's own namespace is intentionally left to the game/runtime resource pack.
function Test-References([object] $node, [string] $source) {
    if ($node -is [System.Collections.IDictionary]) {
        foreach ($key in $node.Keys) {
            $value = $node[$key]
            switch ($key) {
                'model' { Test-ResourceReference $value 'model' $source }
                'parent' {
                    if ((Get-ProjectPath $source).StartsWith("src/main/resources/assets/$namespace/models/")) {
                        Test-ResourceReference $value 'model' $source
                    }
                }
                'texture' { Test-ResourceReference $value 'texture' $source }
                'textures' {
                    if ($value -is [System.Collections.IDictionary]) {
                        foreach ($texture in $value.Values) { Test-ResourceReference $texture 'texture' $source }
                    }
                }
                'item' { Test-ResourceReference $value 'item' $source }
                'items' {
                    foreach ($item in @($value)) { Test-ResourceReference $item 'item' $source }
                }
                'block' { Test-ResourceReference $value 'block' $source }
                'name' {
                    if ($node.Contains('type') -and $node['type'] -eq 'minecraft:item') {
                        Test-ResourceReference $value 'item' $source
                    }
                }
            }
            Test-References $value $source
        }
    }
    elseif ($node -is [System.Collections.IEnumerable] -and $node -isnot [string]) {
        foreach ($child in $node) { Test-References $child $source }
    }
}

function Get-ModelTextures([string] $path, [System.Collections.Generic.HashSet[string]] $ancestors) {
    $textures = @{}
    if (-not $ancestors.Add($path)) {
        Add-Problem $path 'Cyclic local model inheritance.'
        return $textures
    }
    if ($documents.ContainsKey($path)) {
        $model = $documents[$path]
        if ($model -is [System.Collections.IDictionary]) {
            if ($model.Contains('parent') -and $model['parent'] -is [string]) {
                $parent = Get-ResourcePath $model['parent'] 'model' $path
                if ($null -ne $parent -and $documents.ContainsKey($parent)) {
                    $textures = Get-ModelTextures $parent $ancestors
                }
            }
            if ($model.Contains('textures') -and $model['textures'] -is [System.Collections.IDictionary]) {
                foreach ($key in $model['textures'].Keys) { $textures[$key] = $model['textures'][$key] }
            }
        }
    }
    $ancestors.Remove($path) | Out-Null
    return $textures
}

function Test-TextureVariable([object] $value, [hashtable] $textures, [string] $source) {
    if ($value -isnot [string] -or -not $value.StartsWith('#')) { return }
    $visited = [System.Collections.Generic.HashSet[string]]::new()
    while ($value.StartsWith('#')) {
        $key = $value.Substring(1)
        if (-not $visited.Add($key)) { Add-Problem $source "Cyclic texture variable '$value'."; return }
        if (-not $textures.ContainsKey($key)) { Add-Problem $source "Unresolved local texture variable '$value'."; return }
        $value = $textures[$key]
        if ($value -isnot [string]) { Add-Problem $source "Texture '$key' must be a string."; return }
    }
}

if (-not (Test-Path -LiteralPath $resourceRoot -PathType Container)) { throw "Missing resource directory: $resourceRoot" }
foreach ($file in Get-ChildItem -LiteralPath $resourceRoot -Filter '*.json' -Recurse -File) {
    try {
        $document = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
        if ($document -isnot [System.Collections.IDictionary]) { throw 'Minecraft resource JSON must have an object root.' }
        $documents[$file.FullName] = $document
    }
    catch { Add-Problem $file.FullName "Invalid JSON: $($_.Exception.Message)" }
}

foreach ($path in $documents.Keys) {
    $document = $documents[$path]
    Test-References $document $path
    $relative = Get-ProjectPath $path
    if ($relative -match '^src/main/resources/data/[a-z0-9_.-]+/tags/(?<kind>item|block)/') {
        $tagKind = $Matches['kind']
        if ($document.Contains('values')) {
            foreach ($entry in $document['values']) {
                $id = if ($entry -is [System.Collections.IDictionary]) { $entry['id'] } else { $entry }
                Test-ResourceReference $id $tagKind $path
            }
        }
    }
    if ($relative -match "^src/main/resources/assets/$namespace/models/") {
        $textures = Get-ModelTextures $path ([System.Collections.Generic.HashSet[string]]::new())
        foreach ($texture in $textures.Values) { Test-TextureVariable $texture $textures $path }
        if ($document.Contains('elements')) {
            foreach ($element in $document['elements']) {
                $validCoordinates = $true
                foreach ($endpoint in @('from', 'to')) {
                    if (-not $element.Contains($endpoint) -or @($element[$endpoint]).Count -ne 3) {
                        Add-Problem $path "Each model element needs three '$endpoint' coordinates."
                        $validCoordinates = $false
                        continue
                    }
                    foreach ($coordinate in $element[$endpoint]) {
                        if ($coordinate -isnot [System.ValueType] -or $coordinate -is [bool] -or -not [double]::IsFinite([double]$coordinate) -or $coordinate -lt -16 -or $coordinate -gt 32) {
                            Add-Problem $path "Model coordinates must be finite numbers in Minecraft's -16..32 range."
                            $validCoordinates = $false
                        }
                    }
                }
                if ($validCoordinates) {
                    foreach ($axis in 0..2) {
                        if ($element['from'][$axis] -gt $element['to'][$axis]) { Add-Problem $path "Model 'from' exceeds 'to' on axis $axis." }
                    }
                }
                if ($element.Contains('faces')) {
                    foreach ($face in $element['faces'].Values) {
                        if ($face.Contains('texture')) { Test-TextureVariable $face['texture'] $textures $path }
                    }
                }
            }
        }
    }
}

$tableStatePath = Join-Path $assetRoot 'blockstates/mahjong_table.json'
if (-not $documents.ContainsKey($tableStatePath)) { Add-Problem $tableStatePath 'Missing or invalid mahjong table blockstate.' }
elseif (-not $documents[$tableStatePath].Contains('variants')) { Add-Problem $tableStatePath 'Mahjong table needs four facing variants.' }
else {
    foreach ($facing in @('north', 'east', 'south', 'west')) {
        $variants = $documents[$tableStatePath]['variants']
        if (-not $variants.Contains("facing=$facing")) { Add-Problem $tableStatePath "Missing facing=$facing mapping." }
        else {
            $mapping = @($variants["facing=$facing"])
            if ($mapping.Count -eq 0) { Add-Problem $tableStatePath "Empty facing=$facing mapping." }
            foreach ($variant in $mapping) {
                if ($variant -isnot [System.Collections.IDictionary] -or -not $variant.Contains('model') -or $variant['model'] -isnot [string] -or [string]::IsNullOrWhiteSpace($variant['model'])) {
                    Add-Problem $tableStatePath "facing=$facing must map to a model."
                }
            }
        }
    }
}

$languagePaths = @((Join-Path $assetRoot 'lang/en_us.json'), (Join-Path $assetRoot 'lang/zh_cn.json'))
foreach ($furniture in @('large_mahjong_table', 'mahjong_chair', 'empty_chair')) {
    $statePath = Join-Path $assetRoot "blockstates/$furniture.json"
    if (-not $documents.ContainsKey($statePath) -or -not $documents[$statePath].Contains('variants')) {
        Add-Problem $statePath 'Furniture blockstate variants are missing.'
        continue
    }
    $variants = $documents[$statePath]['variants']
    $expectedCount = if ($furniture -eq 'large_mahjong_table') { 36 } else { 4 }
    if ($variants.Count -ne $expectedCount) { Add-Problem $statePath "Expected exactly $expectedCount furniture variants." }
    foreach ($facing in @('north', 'east', 'south', 'west')) {
        $parts = if ($furniture -eq 'large_mahjong_table') { @(0..8) } else { @(-1) }
        foreach ($part in $parts) {
            $key = "facing=$facing" + $(if ($part -ge 0) { ",part=$part" } else { '' })
            $model = "${namespace}:block/$furniture" + $(if ($part -ge 0) { "_$part" } else { '' })
            $angle = @{north=0; east=90; south=180; west=270}[$facing]
            if (-not $variants.Contains($key) -or $variants[$key]['model'] -cne $model -or $variants[$key]['y'] -ne $angle) {
                Add-Problem $statePath "Incorrect furniture model or rotation for $key."
            }
        }
    }
}

if (@($languagePaths | Where-Object { -not $documents.ContainsKey($_) }).Count -gt 0) {
    Add-Problem $assetRoot 'Both en_us.json and zh_cn.json must exist and contain valid JSON.'
}
else {
    $english = $documents[$languagePaths[0]]
    $chinese = $documents[$languagePaths[1]]
    foreach ($key in @($english.Keys) + @($chinese.Keys) | Sort-Object -Unique) {
        foreach ($index in 0..1) {
            $language = $documents[$languagePaths[$index]]
            if (-not $language.Contains($key)) { Add-Problem $languagePaths[$index] "Missing translation key '$key'." }
            elseif ($language[$key] -isnot [string] -or [string]::IsNullOrWhiteSpace($language[$key])) { Add-Problem $languagePaths[$index] "Translation '$key' must be a nonempty string." }
        }
    }
}

Add-Type -AssemblyName System.Drawing
$pngFiles = @(Get-ChildItem -LiteralPath $resourceRoot -Filter '*.png' -Recurse -File)
foreach ($file in $pngFiles) {
    $image = $null
    try {
        $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
        $signature = @(137, 80, 78, 71, 13, 10, 26, 10)
        if ($bytes.Length -lt 33) { throw 'PNG is shorter than its signature and IHDR chunk.' }
        foreach ($index in 0..7) { if ($bytes[$index] -ne $signature[$index]) { throw 'Invalid PNG signature.' } }
        if ([System.Text.Encoding]::ASCII.GetString($bytes, 12, 4) -ne 'IHDR') { throw 'PNG must start with IHDR.' }
        $dimensions = @()
        foreach ($offset in @(16, 20)) {
            [byte[]]$dimensionBytes = $bytes[$offset..($offset + 3)]
            if ([System.BitConverter]::IsLittleEndian) { [System.Array]::Reverse($dimensionBytes) }
            $dimensions += [System.BitConverter]::ToUInt32($dimensionBytes, 0)
        }
        if ($dimensions[0] -lt 1 -or $dimensions[1] -lt 1 -or $dimensions[0] -gt 16384 -or $dimensions[1] -gt 16384) { throw 'PNG dimensions must be between 1 and 16384 pixels.' }
        $image = [System.Drawing.Image]::FromFile($file.FullName)
        if ($image.Width -ne $dimensions[0] -or $image.Height -ne $dimensions[1]) { throw 'Decoded dimensions do not match IHDR.' }
    }
    catch { Add-Problem $file.FullName $_.Exception.Message }
    finally { if ($null -ne $image) { $image.Dispose() } }
}

if ($errors.Count -gt 0) {
    foreach ($problem in $errors) { Write-Output "FAIL: $problem" }
    throw "Resource checks failed with $($errors.Count) issue(s)."
}
Write-Output "PASS: $($documents.Count) JSON files; $($references.Count) local resource references; $($pngFiles.Count) valid PNG files; model bounds and texture variables; legacy table and all 44 furniture facing/part mappings; matching en_us/zh_cn keys."
