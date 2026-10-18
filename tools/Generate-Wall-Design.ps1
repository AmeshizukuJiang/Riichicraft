# Documentation diagram only; not an in-game wall renderer.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$repoRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repoRoot 'docs/images/table-wall-options.png'
$bitmap = [Drawing.Bitmap]::new(1560, 660)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$owned = [Collections.Generic.List[IDisposable]]::new()
function New-Brush([string]$color) {
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($color))
    $owned.Add($brush)
    return $brush
}
$paper = New-Brush '#f3f1ea'; $ink = New-Brush '#263d37'; $muted = New-Brush '#5c6e67'
$wood = New-Brush '#665047'; $cloth = New-Brush '#236155'; $back = New-Brush '#0b4136'
$ivory = New-Brush '#f4efdd'; $pale = New-Brush '#cbd7ca'; $empty = New-Brush '#a2aaa2'
$titleFont = [Drawing.Font]::new('Microsoft YaHei', 19)
$bodyFont = [Drawing.Font]::new('Microsoft YaHei', 13)
$smallFont = [Drawing.Font]::new('Microsoft YaHei', 11)
$owned.Add($titleFont); $owned.Add($bodyFont); $owned.Add($smallFont)
$graphics.Clear([Drawing.ColorTranslator]::FromHtml('#f3f1ea'))
$graphics.DrawString('牌墙排布候选 · 完整牌墙，配牌前', $titleFont, $ink, 24, 18)
$panels = @(
    @{ Title='四麻：四边各17墩'; Counts=@(17,17,17,17); Total=136; Empty=-1; Note='四边都有牌墙' },
    @{ Title='三麻A：四边交替14／13墩'; Counts=@(14,13,14,13); Total=108; Empty=2; Note='未采用；保留作方案比较' },
    @{ Title='三麻B：三边各18墩'; Counts=@(18,18,0,18); Total=108; Empty=2; Note='用户已选：空椅一侧不排牌墙' }
)
for ($panelIndex=0; $panelIndex -lt $panels.Count; $panelIndex++) {
    $panel = $panels[$panelIndex]; $cx = [single](260 + 520*$panelIndex); $cy = [single]310
    $sum = ($panel.Counts | Measure-Object -Sum).Sum * 2
    if ($sum -ne $panel.Total) { throw 'Wall diagram tile count mismatch' }
    $graphics.DrawString($panel.Title, $bodyFont, $ink, [single]($cx-232), 77)
    $graphics.FillRectangle($wood, $cx-185, $cy-185, 370, 370)
    $graphics.FillRectangle($cloth, $cx-175, $cy-175, 350, 350)
    for ($side=0; $side -lt 4; $side++) {
        $graphics.TranslateTransform($cx,$cy); $graphics.RotateTransform([single](-90*$side))
        $chairBrush = if ($side -eq $panel.Empty) { $empty } else { $back }
        $graphics.FillRectangle($wood, -25, 193, 50, 33)
        $graphics.FillRectangle($chairBrush, -20, 198, 40, 23)
        $count = $panel.Counts[$side]
        for ($stack=0; $stack -lt $count; $stack++) {
            $x = [single](($stack-($count-1)/2.0)*14-6)
            # Offset ivory underside makes the two-layer convention visible in plan view.
            $graphics.FillRectangle($ivory, $x, 122, 12, 23)
            $graphics.FillRectangle($back, $x, 119, 12, 21)
            $graphics.FillRectangle($pale, $x, 119, 12, 2)
        }
        $graphics.ResetTransform()
    }
    $graphics.DrawString([string]$panel.Total+'张 · 每墩2张', $bodyFont, $ivory, $cx-85, $cy-12)
    $graphics.DrawString($panel.Note, $bodyFont, $ink, $cx-220, 555)
}
$graphics.DrawString('示意图，不是游戏截图。实际开局已配出手牌：四麻墙内83张，三麻68张；不另复制完整牌墙。', $smallFont, $muted, 24, 617)
try { $bitmap.Save($output, [Drawing.Imaging.ImageFormat]::Png) }
finally {
    $graphics.Dispose(); $bitmap.Dispose()
    foreach ($resource in $owned) { $resource.Dispose() }
}
Write-Output 'Generated documentation wall-layout comparison (136 / 108 / 108 tiles).'
