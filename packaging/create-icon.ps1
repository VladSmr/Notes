<#
.SYNOPSIS
    Генерирует валидный многомерный .ico из исходного PNG (System.Drawing).

.DESCRIPTION
    Исходный "KP to IMDB logo.ico" на самом деле является PNG (магические байты 89 50 4E 47),
    а не настоящим ICO-контейнером — jpackage/WiX Resource Editor отвергает такой файл
    ("Failed to update icon ... system error 13"). Этот скрипт читает PNG, ресайзит в
    стандартные размеры и пишет корректный .ico с PNG-записями (Vista+, поддерживается jpackage).

    Использование:
        powershell -ExecutionPolicy Bypass -File create-icon.ps1
    Вход : packaging/logo-source.png (или packaging/app.ico, если это PNG)
    Выход: packaging/app.ico (валидный .ico)
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $root 'logo-source.png'
$out    = Join-Path $root 'app.ico'

# Если отдельного source-файла нет — берём текущий app.ico (это PNG).
if (-not (Test-Path $source)) {
    $source = $out
}
if (-not (Test-Path $source)) {
    throw "Не найден исходный PNG: ожидался $source или $out"
}

$src = [System.Drawing.Image]::FromFile($source)
if ($src.Width -ne $src.Height) {
    Write-Warning "Исходник не квадратный ($($src.Width)x$($src.Height)); центрируем в квадрат"
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)

# --- Собираем PNG-блобы для каждого размера ---
# Ключевой фикс: масштабируем ВЕСЬ квадратный исходник в каждый целевой размер,
# а не вырезаем центральный size×size-кроп. Раньше $side = Min($size, ...) давал
# для любого целевого размера $side = $size, т.е. DrawImage копировал из центра
# исходника прямоугольник size×size в size×size — чистый центр-кроп 1:1, а не
# уменьшение всего логотипа. Из-за этого каждая иконка была увеличенным
# микро-фрагментом центра → «размазанная». Теперь $side = полная сторона квадрата
# исходника, и DrawImage честно даунскейлит весь логотип (1254 → 16/24/32/.../256).
$pngBlobs = New-Object System.Collections.Generic.List[byte[]]

# Функция ступенчатого даунскейла: масштабируем поэтапно (не более чем ~2x за шаг),
# чтобы избежать алиасинга при экстремальном уменьшении (1254 → 16/24/32).
function ConvertTo-ScaledBitmap {
    param(
        [System.Drawing.Image]$Source,
        [int]$Side,          # полная сторона квадратной области исходника
        [int]$Sx,            # смещение по X центральной квадратной области
        [int]$Sy,            # смещение по Y центральной квадратной области
        [int]$TargetSize
    )
    # Первый шаг: вырезаем квадратную область ПОЛНОЙ стороны исходника
    # (центр-кроп до квадрата ДО масштабирования — допустим для не-квадратного
    # исходника, но НЕ до размера цели).
    $cur = New-Object System.Drawing.Bitmap($Side, $Side, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gCur = [System.Drawing.Graphics]::FromImage($cur)
    $gCur.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gCur.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $gCur.Clear([System.Drawing.Color]::Transparent)
    $gCur.DrawImage($Source,
        (New-Object System.Drawing.Rectangle(0, 0, $Side, $Side)),
        (New-Object System.Drawing.Rectangle($Sx, $Sy, $Side, $Side)),
        [System.Drawing.GraphicsUnit]::Pixel)
    $gCur.Dispose()

    $currentSize = $Side
    while ($currentSize -gt $TargetSize) {
        $nextSize = [Math]::Max($TargetSize, [int]($currentSize / 2))
        $next = New-Object System.Drawing.Bitmap($nextSize, $nextSize, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $gNext = [System.Drawing.Graphics]::FromImage($next)
        $gNext.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $gNext.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $gNext.Clear([System.Drawing.Color]::Transparent)
        $gNext.DrawImage($cur,
            (New-Object System.Drawing.Rectangle(0, 0, $nextSize, $nextSize)),
            (New-Object System.Drawing.Rectangle(0, 0, $currentSize, $currentSize)),
            [System.Drawing.GraphicsUnit]::Pixel)
        $gNext.Dispose()
        $cur.Dispose()
        $cur = $next
        $currentSize = $nextSize
    }

    return $cur
}

# Полная сторона квадрата исходника (для не-квадратного — меньшая сторона).
$fullSide = [Math]::Min($src.Width, $src.Height)
# Центрируем квадратную область полной стороны исходника.
$sx = [int](($src.Width - $fullSide) / 2)
$sy = [int](($src.Height - $fullSide) / 2)

foreach ($size in $sizes) {
    $bmp = ConvertTo-ScaledBitmap -Source $src -Side $fullSide -Sx $sx -Sy $sy -TargetSize $size

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngBlobs.Add($ms.ToArray())
    $ms.Dispose()
    $bmp.Dispose()
}
$src.Dispose()

# --- Пишем .ico (ICONDIR + ICONDIRENTRY + PNG-данные) ---
$count = $pngBlobs.Count
$fs = [System.IO.File]::Create($out)
$bw = New-Object System.IO.BinaryWriter($fs)

# ICONDIR
$bw.Write([uint16]0)        # reserved
$bw.Write([uint16]1)        # type = icon
$bw.Write([uint16]$count)   # count

# ICONDIRENTRY (16 байт на запись)
$offset = 6 + (16 * $count)
for ($i = 0; $i -lt $count; $i++) {
    $size = $sizes[$i]
    $bw.Write([byte]($(if ($size -ge 256) { 0 } else { $size })))  # width (0 = 256)
    $bw.Write([byte]($(if ($size -ge 256) { 0 } else { $size })))  # height
    $bw.Write([byte]0)      # color count
    $bw.Write([byte]0)      # reserved
    $bw.Write([uint16]1)    # planes
    $bw.Write([uint16]32)   # bit count
    $bw.Write([uint32]$pngBlobs[$i].Length)  # bytes in resource
    $bw.Write([uint32]$offset)               # image offset
    $offset += $pngBlobs[$i].Length
}

# PNG-данные
foreach ($blob in $pngBlobs) {
    $bw.Write($blob)
}
$bw.Flush()
$bw.Close()
$fs.Close()

Write-Host "Создан валидный .ico: $out ($((Get-Item $out).Length) байт, $count размеров: $($sizes -join ','))"
