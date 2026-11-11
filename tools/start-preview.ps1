param(
    [Parameter(Mandatory=$true)][string]$NodePath,
    [Parameter(Mandatory=$true)][string]$TunnelPath
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$state = Join-Path $env:LOCALAPPDATA 'AppaEodiga\preview-8789'
$null = New-Item -ItemType Directory -Path $state -Force
$env:FAMILY_LOCAL_ARCHIVE_DIR = $state
$mutex = New-Object System.Threading.Mutex($false, 'Local\AppaEodigaPreviewSupervisor')
try { $acquired = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $acquired = $true }
if (!$acquired) { $mutex.Dispose(); return }
# A quick tunnel can lose its address on the Cloudflare side while the process keeps running ("Tunnel not found").
# Check that the server reaches itself through the tunnel; restart the tunnel after about a minute of failures.
$deadChecks = 0
try {
    while ($true) {
        try {
            $listener = Get-NetTCPConnection -State Listen -LocalPort 8789 -ErrorAction SilentlyContinue | Select-Object -First 1
            if (!$listener) {
                Start-Process -WindowStyle Hidden -FilePath $NodePath -WorkingDirectory $project -ArgumentList @('tools/preview-phone.mjs','8789') -RedirectStandardError (Join-Path $state 'server-error.log')
                Start-Sleep -Seconds 2
                $listener = Get-NetTCPConnection -State Listen -LocalPort 8789 -ErrorAction SilentlyContinue | Select-Object -First 1
            }
            if ($listener) {
                $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
                if ($owner.ExecutablePath -eq $NodePath -and $owner.CommandLine -match 'tools[/\\]preview-phone\.mjs\W+8789\b') {
                    $tunnel = Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" | Where-Object { $_.ExecutablePath -eq $TunnelPath -and $_.CommandLine -match '127\.0\.0\.1:8789\b' }
                    if ($tunnel) {
                        $origin = ''
                        try { $origin = (Invoke-RestMethod -Uri 'http://127.0.0.1:8789/api/invite-origin' -TimeoutSec 10).origin } catch {}
                        if ($origin) { $deadChecks = 0 } else { $deadChecks++ }
                        $age = (Get-Date) - ($tunnel | Select-Object -First 1).CreationDate
                        if ($deadChecks -ge 3 -and $age.TotalSeconds -gt 90) {
                            $tunnel | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
                            $tunnel = $null; $deadChecks = 0
                            "$(Get-Date -Format s) tunnel address unreachable, restarted" | Add-Content (Join-Path $state 'tunnel-restarts.log')
                        }
                    }
                    if (!$tunnel) {
                        Start-Process -WindowStyle Hidden -FilePath $TunnelPath -ArgumentList @('tunnel','--no-autoupdate','--protocol','http2','--metrics','127.0.0.1:20241','--url','http://127.0.0.1:8789') -RedirectStandardError (Join-Path $state 'tunnel-error.log')
                    }
                }
            }
        } catch { $_.Exception.Message | Set-Content (Join-Path $state 'startup-error.log') }
        Start-Sleep -Seconds 20
    }
} finally { $mutex.ReleaseMutex(); $mutex.Dispose() }
