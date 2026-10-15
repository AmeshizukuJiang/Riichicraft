param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$textureDirectory = Join-Path $projectRoot 'src/main/resources/assets/riichicraft/textures/block'
[System.IO.Directory]::CreateDirectory($textureDirectory) | Out-Null

function New-Color([string] $hex) {
    return [System.Drawing.ColorTranslator]::FromHtml($hex)
}

$wood = @(New-Color '#97613B'; New-Color '#A96D42'; New-Color '#B77D4D'; New-Color '#C28A57')
$woodDark = New-Color '#75472E'
$cloth = @(New-Color '#246D54'; New-Color '#276F56'; New-Color '#297257')
$marker = @(New-Color '#DCC38A'; New-Color '#BA9C62')

function Write-Texture([string] $name, [scriptblock] $pixel) {
    $bitmap = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        for ($y = 0; $y -lt 16; $y++) {
            for ($x = 0; $x -lt 16; $x++) {
                $bitmap.SetPixel($x, $y, (& $pixel $x $y))
            }
        }
        $path = Join-Path $textureDirectory ($name + '.png')
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output "Generated $path (16 x 16 RGBA)"
    }
    finally {
        $bitmap.Dispose()
    }
}

# Top UV row 0 is north. The two brass pixels across the north rim mark facing.
Write-Texture 'mahjong_table_top' {
    param($x, $y)
    if (($x -eq 7 -or $x -eq 8) -and $y -lt 2) {
        return $marker[$y]
    }
    if ($x -lt 2 -or $x -ge 14 -or $y -lt 2 -or $y -ge 14) {
        if ($x -eq 1 -or $x -eq 14 -or $y -eq 1 -or $y -eq 14) {
            return $woodDark
        }
        return $wood[($x * 3 + $y * 5) % $wood.Length]
    }
    return $cloth[($x * 5 + $y * 3) % $cloth.Length]
}

# Lengthwise grain works on the narrow vertical legs and the tabletop edge.
Write-Texture 'mahjong_table_wood' {
    param($x, $y)
    if (($x * 3 + [int][System.Math]::Floor($y / 4)) % 11 -eq 0) {
        return $woodDark
    }
    return $wood[($x + [int][System.Math]::Floor($y / 7)) % $wood.Length]
}

Write-Texture 'mahjong_table_end' {
    param($x, $y)
    $distance = [System.Math]::Max([System.Math]::Abs($x - 7), [System.Math]::Abs($y - 7))
    return $wood[([int]$distance + [int][System.Math]::Floor($x / 5)) % $wood.Length]
}
