param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Paths)
foreach ($p in $Paths) {
  $bytes = [System.IO.File]::ReadAllBytes($p)
  $bom = if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { 'BOM-OK' } else { 'BOM-MISSING' }
  $err = $null
  [void][System.Management.Automation.Language.Parser]::ParseFile($p, [ref]$null, [ref]$err)
  if ($err -and $err.Count -gt 0) {
    "$bom  PARSE_FAIL  $p"
    $err | Select-Object -First 5 | ForEach-Object { '    ' + $_.Message }
  } else {
    "$bom  PARSE_OK    $p"
  }
}
