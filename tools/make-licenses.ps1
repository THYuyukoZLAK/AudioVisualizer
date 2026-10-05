# Assemble app/src/main/res/raw/third_party_licenses.txt from licenses/.
#
# NOTE: this script is deliberately PURE ASCII.
# PowerShell 5.1 parses a BOM-less .ps1 as ANSI (GBK on this machine), which
# mangles any non-ASCII literal inside the script itself. All Chinese text
# lives in the licenses/ data files, which are read with -Encoding UTF8.
#
# Re-run after editing anything under licenses/:
#   .\tools\make-licenses.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$lic = Join-Path $root 'licenses'
$outDir = Join-Path $root 'app\src\main\res\raw'
$out = Join-Path $outDir 'third_party_licenses.txt'

function Read-Part([string]$name) {
  $path = Join-Path $lic $name
  if (-not (Test-Path $path)) { throw "missing license part: $path" }
  (Get-Content $path -Encoding UTF8 -Raw).TrimEnd()
}

# Our own MIT text is the same standard MIT body as LLin's, only the
# copyright line differs -- so derive it instead of keeping a second copy
# that could drift out of sync.
$mitLlin = Read-Part 'mit-llin.txt'
$copyright = (Read-Part 'self-copyright.txt').Trim()
$mitSelf = $mitLlin -replace '(?m)^Copyright \(c\) 2025 MATRIX-feather\s*$', $copyright

if ($mitSelf -eq $mitLlin) {
  throw 'copyright substitution did not apply -- check licenses/self-copyright.txt and mit-llin.txt'
}

$parts = @(
  (Read-Part 'header-self.txt'), $mitSelf,
  (Read-Part 'header-llin.txt'), $mitLlin,
  (Read-Part 'header-ofl.txt'), (Read-Part 'JetBrainsMono-OFL.txt'),
  (Read-Part 'header-apache.txt'), (Read-Part 'Apache-2.0.txt')
)

$sb = New-Object System.Text.StringBuilder
foreach ($p in $parts) {
  [void]$sb.AppendLine($p)
  [void]$sb.AppendLine()
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
[IO.File]::WriteAllText($out, $sb.ToString(), (New-Object Text.UTF8Encoding($false)))
Write-Output ("wrote {0}  ({1} bytes)" -f $out, (Get-Item $out).Length)
