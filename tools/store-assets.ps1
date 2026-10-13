param(
    [string] $ScreenshotDirectory = (Join-Path $env:LOCALAPPDATA 'ServiceLedger\store-captures')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$output = Join-Path (Split-Path $PSScriptRoot -Parent) 'store-listing'
$phoneOutput = Join-Path $output 'phone'
$names = @('01-home', '02-customers', '03-service', '04-payments', '05-receipts', '06-home-dark')
foreach ($name in $names) {
    if (-not (Test-Path (Join-Path $ScreenshotDirectory "$name.png"))) {
        throw "Missing $name.png. Run the store listing screenshot tests first; see store-listing\README.txt."
    }
}
New-Item -ItemType Directory -Path $phoneOutput -Force | Out-Null

function Fill-Rectangle($graphics, [string] $color, [float] $x, [float] $y, [float] $width, [float] $height) {
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($color))
    try { $graphics.FillRectangle($brush, $x, $y, $width, $height) } finally { $brush.Dispose() }
}

function Fill-RoundedRectangle($graphics, [string] $color, [float] $x, [float] $y, [float] $width, [float] $height, [float] $radius) {
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($color))
    try {
        $d = 2 * $radius
        $path.AddArc($x, $y, $d, $d, 180, 90)
        $path.AddArc($x + $width - $d, $y, $d, $d, 270, 90)
        $path.AddArc($x + $width - $d, $y + $height - $d, $d, $d, 0, 90)
        $path.AddArc($x, $y + $height - $d, $d, $d, 90, 90)
        $path.CloseFigure()
        $graphics.FillPath($brush, $path)
    } finally {
        $path.Dispose()
        $brush.Dispose()
    }
}

function Draw-Text($graphics, [string] $text, [float] $size, [string] $color, [float] $x, [float] $y, [bool] $bold = $false) {
    $style = if ($bold) { [Drawing.FontStyle]::Bold } else { [Drawing.FontStyle]::Regular }
    $font = [Drawing.Font]::new('Segoe UI', $size, $style, [Drawing.GraphicsUnit]::Pixel)
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($color))
    try { $graphics.DrawString($text, $font, $brush, $x, $y) } finally { $font.Dispose(); $brush.Dispose() }
}

function Draw-Ledger($graphics, [float] $x, [float] $y, [float] $size) {
    $state = $graphics.Save()
    try {
        $graphics.TranslateTransform($x, $y)
        $graphics.ScaleTransform($size / 512, $size / 512)
        Fill-RoundedRectangle $graphics '#00564D' 128 108 256 320 18
        Fill-RoundedRectangle $graphics '#FFFFFF' 128 96 256 320 18
        Fill-RoundedRectangle $graphics '#00695C' 181 171 150 24 6
        Fill-RoundedRectangle $graphics '#00695C' 181 245 150 24 6
        Fill-RoundedRectangle $graphics '#00695C' 181 320 96 24 6
    } finally { $graphics.Restore($state) }
}

function Write-Artwork([string] $name, [int] $width, [int] $height, [scriptblock] $draw) {
    $scale = 3
    $large = [Drawing.Bitmap]::new($width * $scale, $height * $scale, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [Drawing.Graphics]::FromImage($large)
    $result = [Drawing.Bitmap]::new($width, $height, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $resample = [Drawing.Graphics]::FromImage($result)
    $attributes = [Drawing.Imaging.ImageAttributes]::new()
    try {
        $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $graphics.ScaleTransform($scale, $scale)
        & $draw $graphics
        $resample.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $resample.DrawImage($large, [Drawing.Rectangle]::new(0, 0, $width, $height),
            0, 0, $large.Width, $large.Height, [Drawing.GraphicsUnit]::Pixel, $attributes)
        $result.Save((Join-Path $output $name), [Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $attributes.Dispose(); $resample.Dispose(); $result.Dispose(); $graphics.Dispose(); $large.Dispose()
    }
}

Write-Artwork 'icon-512.png' 512 512 {
    param($g)
    Fill-Rectangle $g '#00695C' 0 0 512 512
    Draw-Ledger $g 0 0 512
}

Write-Artwork 'feature-graphic.png' 1024 500 {
    param($g)
    Fill-Rectangle $g '#F3F7F4' 0 0 1024 500
    Fill-RoundedRectangle $g '#E4F4EC' 666 60 282 380 44
    Fill-RoundedRectangle $g '#00695C' 715 93 184 184 32
    Draw-Ledger $g 715 93 184
    Fill-RoundedRectangle $g '#EBF3FD' 687 302 240 42 14
    Draw-Text $g 'Services recorded' 18 '#163954' 729 308
    Fill-RoundedRectangle $g '#FFF3DC' 687 354 240 42 14
    Draw-Text $g 'Payments tracked' 18 '#513B17' 729 360
    Draw-Text $g 'Service Ledger' 52 '#153D35' 72 96 $true
    Draw-Text $g 'Your work. Your records.' 31 '#153D35' 75 186
    Draw-Text $g 'One simple ledger.' 31 '#153D35' 75 229
    Draw-Text $g 'Customers, services and payments.' 22 '#52605A' 76 307
    Draw-Text $g 'Kept on your device.' 22 '#52605A' 76 343
}

foreach ($name in $names) {
    $source = [Drawing.Bitmap]::new((Join-Path $ScreenshotDirectory "$name.png"))
    try {
        if ($source.Width -ne 1512 -or $source.Height -ne 2688) {
            throw "$name.png must be a native 1512 x 2688 capture, not a resized preview."
        }
        $rgb = $source.Clone([Drawing.Rectangle]::new(0, 0, $source.Width, $source.Height),
            [Drawing.Imaging.PixelFormat]::Format24bppRgb)
        try { $rgb.Save((Join-Path $phoneOutput "$name.png"), [Drawing.Imaging.ImageFormat]::Png) }
        finally { $rgb.Dispose() }
    } finally { $source.Dispose() }
}
Write-Host "Generated Play artwork and six full-resolution phone screenshots in $output."
