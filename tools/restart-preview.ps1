$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$node = 'C:\dev\tools\node-v26.13.0-win-x64\node.exe'
$tunnel = 'C:\dev\tools\cloudflared.exe'
$config = Join-Path $project 'account-config.local.json'
if (!(Test-Path -LiteralPath $config)) { throw 'Missing local account configuration.' }
$launcher = Join-Path $PSScriptRoot 'start-preview.ps1'
$supervisors = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape($launcher) }
$listener = Get-NetTCPConnection -LocalPort 8789 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $server = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($server.ExecutablePath -ne $node -or $server.CommandLine -notmatch 'tools[/\\]preview-phone\.mjs\W+8789\b') { throw 'Port 8789 belongs to another application.' }
}
foreach ($item in $supervisors) { Stop-Process -Id $item.ProcessId -ErrorAction SilentlyContinue }
if ($listener) { Stop-Process -Id $server.ProcessId -ErrorAction Stop }
Start-Process -WindowStyle Hidden -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -WorkingDirectory $project -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',('"'+$launcher+'"'),'-NodePath',('"'+$node+'"'),'-TunnelPath',('"'+$tunnel+'"'),'-AccountConfigPath',('"'+$config+'"'))
Write-Output 'Restart requested. The existing encrypted family data will be restored.'
