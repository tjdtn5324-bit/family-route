$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$node = 'C:\dev\tools\node-v26.13.0-win-x64\node.exe'
$tunnel = 'C:\dev\tools\cloudflared.exe'
$launcher = Join-Path $PSScriptRoot 'start-preview.ps1'
$supervisors = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape($launcher) }
$listener = Get-NetTCPConnection -LocalPort 8789 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $server = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($server.ExecutablePath -ne $node -or $server.CommandLine -notmatch 'tools[/\\]preview-phone\.mjs\W+8789\b') { throw 'Port 8789 belongs to another application.' }
}
foreach ($item in $supervisors) { Stop-Process -Id $item.ProcessId -ErrorAction SilentlyContinue }
if ($listener) { Stop-Process -Id $server.ProcessId -ErrorAction Stop }
$cmd = "`"$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe`" -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$launcher`" -NodePath `"$node`" -TunnelPath `"$tunnel`""
$startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
$null = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $project; ProcessStartupInformation = $startup }
Write-Output 'Restart requested. The existing encrypted family data will be restored.'
