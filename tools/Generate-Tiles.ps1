param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceRoot = Join-Path $projectRoot 'art/tiles/mahjong_graphic'
$outputRoot = Join-Path $projectRoot 'src/main/resources/assets/riichicraft/textures/item/mahjong_tiles'
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$manifest = Get-Content -LiteralPath (Join-Path $sourceRoot 'manifest.json') -Raw | ConvertFrom-Json
foreach ($asset in $manifest.assets) {
    foreach ($format in @('png', 'svg')) {
        $entry = $asset.files.$format
        $path = Join-Path $sourceRoot $entry.path
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
            throw "Source checksum mismatch: $($entry.path)"
        }
    }
}

$ivory = [Drawing.ColorTranslator]::FromHtml('#F5F0DE')
$green = [Drawing.ColorTranslator]::FromHtml('#205440')
$keys = @($manifest.assets | ForEach-Object { $_.key }) + @('5z')
foreach ($key in $keys) {
    # Power-of-two atlas sprite; only the top 64 x 88 is the portrait face.
    # Model UV [0,0,16,11] and the hand UI crop the same 8:11 region.
    $bitmap = [Drawing.Bitmap]::new(64, 128, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([Drawing.Color]::White)
        if ($key -ne '5z') {
            $inputImage = [Drawing.Bitmap]::new((Join-Path $sourceRoot "png/$key.png"))
            try {
                $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                # Retain a blank rim. No redraw, recoloring or substituted font.
                $graphics.DrawImage($inputImage, [Drawing.Rectangle]::new(4, 5, 56, 77),
                    0, 0, $inputImage.Width, $inputImage.Height, [Drawing.GraphicsUnit]::Pixel)
            } finally { $inputImage.Dispose() }
        }
        $bitmap.Save((Join-Path $outputRoot "$key.png"), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
foreach ($material in @(@{name='ivory';color=$ivory}, @{name='back';color=$green})) {
    $bitmap = [Drawing.Bitmap]::new(16, 16, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear($material.color)
        $bitmap.Save((Join-Path $outputRoot ($material.name + '.png')), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
Write-Output 'Generated 37 shared face textures (64x128; face 64x88), ivory body and solid deep-green back.'
