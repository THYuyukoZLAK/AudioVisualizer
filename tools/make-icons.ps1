param(
  [string]$Source = 'F:\systemdefault\img\1\icon.jpg',
  [string]$ResDir = 'f:\.C1_Dev\AndroidApp\app\src\main\res'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# ---------- 1) 读源图并居中裁成正方形 ----------
$src = [System.Drawing.Image]::FromFile($Source)
$side = [Math]::Min($src.Width, $src.Height)
$offX = [int](($src.Width - $side) / 2)
$offY = [int](($src.Height - $side) / 2)

$square = New-Object System.Drawing.Bitmap $side, $side
$g = [System.Drawing.Graphics]::FromImage($square)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $side, $side), $offX, $offY, $side, $side, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()

# ---------- 2) 边缘四角平均色 -> 自适应图标的背景色 ----------
# ⚠️ 不能写成 @($side - 3, 2)：PowerShell 会把 `, $side - 3` 解析成
# 「数组减 3」而不是「数组的两个元素各是一个表达式」。先算好再引用。
$edge = $side - 3
$pts = @(@(2, 2), @($edge, 2), @(2, $edge), @($edge, $edge))
$r = 0; $gg = 0; $b = 0
foreach ($p in $pts) {
  $c = $square.GetPixel($p[0], $p[1])
  $r += $c.R; $gg += $c.G; $b += $c.B
}
$bgColor = [System.Drawing.Color]::FromArgb(255, [int]($r / 4), [int]($gg / 4), [int]($b / 4))
Write-Output ("source {0}x{1} -> square {2}, bg = #{3:X2}{4:X2}{5:X2}" -f $src.Width, $src.Height, $side, $bgColor.R, $bgColor.G, $bgColor.B)
$src.Dispose()

function New-Scaled([System.Drawing.Image]$image, [int]$w, [int]$h) {
  $bmp = New-Object System.Drawing.Bitmap $w, $h
  $gr = [System.Drawing.Graphics]::FromImage($bmp)
  $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $gr.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $gr.DrawImage($image, 0, 0, $w, $h)
  $gr.Dispose()
  return $bmp
}

# 圆形版本：用椭圆裁剪
function New-Round([System.Drawing.Image]$image, [int]$size) {
  $bmp = New-Object System.Drawing.Bitmap $size, $size
  $bmp.SetResolution(96, 96)
  $gr = [System.Drawing.Graphics]::FromImage($bmp)
  $gr.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $path.AddEllipse(0, 0, $size, $size)
  $gr.SetClip($path)
  $gr.DrawImage($image, 0, 0, $size, $size)
  $gr.Dispose()
  return $bmp
}

# 自适应图标前景：图片缩到安全区（108dp 画布里的 72dp），四周透明
function New-AdaptiveForeground([System.Drawing.Image]$image, [int]$canvas, [int]$content) {
  $bmp = New-Object System.Drawing.Bitmap $canvas, $canvas
  $gr = [System.Drawing.Graphics]::FromImage($bmp)
  $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $pad = [int](($canvas - $content) / 2)
  $gr.DrawImage($image, $pad, $pad, $content, $content)
  $gr.Dispose()
  return $bmp
}

function New-Solid([System.Drawing.Color]$color, [int]$size) {
  $bmp = New-Object System.Drawing.Bitmap $size, $size
  $gr = [System.Drawing.Graphics]::FromImage($bmp)
  $gr.Clear($color)
  $gr.Dispose()
  return $bmp
}

# ---------- 3) 逐密度输出 ----------
$densities = [ordered]@{ 'mdpi' = 1.0; 'hdpi' = 1.5; 'xhdpi' = 2.0; 'xxhdpi' = 3.0; 'xxxhdpi' = 4.0 }
foreach ($name in $densities.Keys) {
  $dir = Join-Path $ResDir "mipmap-$name"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null

  # 清掉模板自带的 webp，否则同名资源会冲突
  Get-ChildItem $dir -Filter 'ic_launcher*.webp' -ErrorAction SilentlyContinue | Remove-Item -Force

  $d = $densities[$name]
  $legacy = [int](48 * $d)
  $adaptive = [int](108 * $d)
  # 自适应图标可见区 = 中心 72/108
  $content = [int](72 * $d)

  (New-Scaled $square $legacy $legacy).Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
  (New-Round $square $legacy).Save((Join-Path $dir 'ic_launcher_round.png'), [System.Drawing.Imaging.ImageFormat]::Png)
  (New-AdaptiveForeground $square $adaptive $content).Save((Join-Path $dir 'ic_launcher_foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
  (New-Solid $bgColor $adaptive).Save((Join-Path $dir 'ic_launcher_background.png'), [System.Drawing.Imaging.ImageFormat]::Png)
  Write-Output ("  mipmap-$name : legacy=$legacy adaptive=$adaptive content=$content")
}
$square.Dispose()

# ---------- 4) 自适应图标清单 ----------
$anydpi = Join-Path $ResDir 'mipmap-anydpi-v26'
New-Item -ItemType Directory -Force -Path $anydpi | Out-Null
$xml = @'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'@
# 注意：不写 <monochrome>。主题图标会取 alpha 通道做单色描摹，
# 而这张图是照片式的插画，做成单色只会是一坨灰块。
[IO.File]::WriteAllText((Join-Path $anydpi 'ic_launcher.xml'), $xml, (New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllText((Join-Path $anydpi 'ic_launcher_round.xml'), $xml, (New-Object Text.UTF8Encoding($false)))
Write-Output "adaptive icon xml written"
