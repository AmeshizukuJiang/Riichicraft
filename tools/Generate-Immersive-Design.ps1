# Documentation mockup only. Does not modify game assets or implement a camera/HUD.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$repoRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repoRoot 'docs/images/immersive-table-concept.png'
$bitmap = [Drawing.Bitmap]::new(1560, 930)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$owned = [Collections.Generic.List[IDisposable]]::new()
function Brush([string]$color) {
    $value = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($color))
    $owned.Add($value)
    return $value
}
function Font([single]$size, [Drawing.FontStyle]$style = [Drawing.FontStyle]::Regular) {
    $value = [Drawing.Font]::new('Microsoft YaHei', $size, $style)
    $owned.Add($value)
    return $value
}
$paper = Brush '#f4f2e9'; $ink = Brush '#203c34'; $muted = Brush '#566b62'
$wood = Brush '#705448'; $cloth = Brush '#286554'; $deep = Brush '#103e34'
$ivory = Brush '#f4efdc'; $line = Brush '#b4cbb9'; $gold = Brush '#e9c975'
$panel = Brush '#dce5d9'; $night = Brush '#193c33'; $button = Brush '#31594b'
$title = Font 23 ([Drawing.FontStyle]::Bold); $heading = Font 18 ([Drawing.FontStyle]::Bold)
$body = Font 13; $small = Font 11; $tileImages = @{}
function Text([string]$value, [single]$x, [single]$y, $font = $body, $brush = $ink) {
    $graphics.DrawString($value, $font, $brush, $x, $y)
}
function Rect($brush, [single]$x, [single]$y, [single]$w, [single]$h) {
    $graphics.FillRectangle($brush, $x, $y, $w, $h)
}
function Face([string]$key, [single]$x, [single]$y, [single]$w, [single]$h, [bool]$selected = $false) {
    if (-not $tileImages.ContainsKey($key)) {
        $path = Join-Path $repoRoot ('src/main/resources/assets/riichicraft/textures/item/mahjong_tiles/' + $key + '.png')
        $tileImages[$key] = [Drawing.Image]::FromFile($path)
        $owned.Add($tileImages[$key])
    }
    if ($selected) { Rect $gold ($x-3) ($y-3) ($w+6) ($h+6) }
    Rect $deep $x ($y+4) $w $h
    Rect $ivory $x $y $w $h
    $graphics.DrawImage($tileImages[$key], [Drawing.RectangleF]::new($x+2,$y+1,$w-4,$h-3),
            [Drawing.RectangleF]::new(0,0,64,88), [Drawing.GraphicsUnit]::Pixel)
}
function Back([single]$x,[single]$y,[single]$w,[single]$h) {
    Rect $ivory $x $y $w ($h+3)
    Rect $deep ($x+1) ($y+1) ($w-2) ($h-1)
}
function Button([string]$label,[single]$x,[single]$y,[single]$w = 65) {
    Rect $button $x $y $w 31
    Text $label ($x+12) ($y+4) $small $ivory
}
function Hand([single]$x,[single]$y,[single]$w,[single]$h) {
    $keys = @('1m','2m','3m','4p','0p','6p','1s','2s','3s','5s','5s','1z','1z')
    for ($i=0; $i -lt $keys.Count; $i++) {
        $lift = if ($i -eq 4) { 8 } else { 0 }
        Face $keys[$i] ($x + $i*($w+3)) ($y-$lift) $w $h ($i -eq 4)
    }
}
function Rivers([single]$cx,[single]$cy,[single]$size) {
    for ($side=0; $side -lt 4; $side++) {
        $graphics.TranslateTransform($cx,$cy)
        $graphics.RotateTransform([single](90*$side))
        for ($i=0; $i -lt 6; $i++) { Face ([string]($i+1)+'p') (-65+$i*22) 42 18 $size }
        $graphics.ResetTransform()
    }
}
try {
    $graphics.Clear([Drawing.ColorTranslator]::FromHtml('#f4f2e9'))
    Text 'Riichicraft · 把操作放回牌桌' 28 20 $title
    Text '交互布局草图 / 非游戏截图 / 镜头与操作方案尚未实现' 30 65 $body $muted

    Rect $panel 24 114 744 690
    Rect $panel 792 114 744 690
    Text '坐姿视角' 48 132 $heading
    Text '自己的牌竖放，仍能环顾周围世界' 48 169 $body $muted
    Text '俯瞰视角' 816 132 $heading
    Text '自己的牌平放，画面以本人所在一侧为下方' 816 169 $body $muted

    # A perspective tabletop silhouette: explanatory drawing, not camera projection math.
    $points = [Drawing.PointF[]]@([Drawing.PointF]::new(165,280),[Drawing.PointF]::new(627,280),[Drawing.PointF]::new(724,644),[Drawing.PointF]::new(68,644))
    $graphics.FillPolygon($wood,$points)
    $points = [Drawing.PointF[]]@([Drawing.PointF]::new(174,291),[Drawing.PointF]::new(618,291),[Drawing.PointF]::new(707,627),[Drawing.PointF]::new(85,627))
    $graphics.FillPolygon($cloth,$points)
    for ($i=0; $i -lt 13; $i++) { Back (231+$i*25) 277 22 34 }
    for ($i=0; $i -lt 10; $i++) {
        Back (152-$i*4) (340+$i*21) 17 19
        Back (623+$i*4) (340+$i*21) 17 19
    }
    for ($side=0; $side -lt 2; $side++) {
        for ($i=0; $i -lt 6; $i++) { Face ([string]($i+1)+'s') (300+$i*28) (383+$side*50) 24 32 }
    }
    Text '东1局  ·  0本场' 305 335 $body $ivory
    Hand 143 570 36 52
    Text '选中反馈' 267 546 $small $gold
    Button '碰' 467 518
    Button '杠' 536 518
    Button '跳过' 605 518 76
    Text '合法动作出现时，按钮贴近手牌右上方' 90 669 $body
    Text '多种组合直接展开牌图；不另开整页菜单' 90 697 $body $muted
    Button '切换视角' 87 744 118
    Text '可重绑按键 + 可点击入口' 220 749 $small $muted

    # Overhead tabletop; other participants' concealed hands stay anonymous backs.
    Rect $wood 921 233 486 486
    Rect $cloth 931 243 466 466
    for ($i=0; $i -lt 13; $i++) {
        Back (986+$i*27) 252 23 31
        Back 943 (300+$i*26) 20 22
        Back 1365 (300+$i*26) 20 22
    }
    Rivers 1164 454 24
    Rect $night 1101 428 127 49
    Text '东1局 · 69枚' 1110 440 $small $ivory
    Hand 972 650 26 36
    Button '碰' 1183 601
    Button '杠' 1252 601
    Button '跳过' 1321 601 76
    Text '25,000 点 · 自己' 977 694 $small $ivory
    Text '其他参与者保持牌背；本人牌面清楚可选' 846 748 $body

    Text '参与者：只见本人暗手　　观战：可看全员手牌，不占椅位' 30 830 $body
    Text '仅示意手牌、牌河与按钮；实际保留牌墙／副露。按钮不表示本手牌必有这些合法动作。' 30 869 $small $muted
    Text '后续外观槽：牌面 / 牌背 / 桌布 / 声音 / 立直音乐；规则与外观分开。' 30 895 $small $muted
    $bitmap.Save($output,[Drawing.Imaging.ImageFormat]::Png)
}
finally {
    $graphics.Dispose()
    $bitmap.Dispose()
    foreach ($resource in $owned) { $resource.Dispose() }
}
Write-Output 'Generated immersive-table interaction concept (documentation only).'
