# 에뮬레이터 안 차량 앱의 가족 폰 서버(8765)를 같은 Wi-Fi의 폰에서 열 수 있게 한다.
# 실제 헤드유닛은 필요 없음(기기 IP로 바로 접속). 관리자 PowerShell에서 실행.
#   powershell -ExecutionPolicy Bypass -File tools\phone-link.ps1          # 연결
#   powershell -ExecutionPolicy Bypass -File tools\phone-link.ps1 -Remove  # 해제
param([switch]$Remove, [int]$Port = 8765)
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if ($Remove) {
    & $adb forward --remove tcp:$Port | Out-Null
    netsh interface portproxy delete v4tov4 listenaddress=0.0.0.0 listenport=$Port | Out-Null
    Remove-NetFirewallRule -DisplayName "family-route-demo $Port" -ErrorAction SilentlyContinue
    Write-Host "해제했습니다"
    exit 0
}
# 1) 노트북 127.0.0.1:포트 → 에뮬레이터 포트
& $adb forward tcp:$Port tcp:$Port
# 2) 노트북 LAN 주소:포트 → 127.0.0.1:포트 (adb forward는 127.0.0.1에만 열림)
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=$Port connectaddress=127.0.0.1 connectport=$Port | Out-Null
# 3) 방화벽(개인 네트워크만)
if (-not (Get-NetFirewallRule -DisplayName "family-route-demo $Port" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName "family-route-demo $Port" -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private | Out-Null
}
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notmatch 'vEthernet|Loopback' -and $_.PrefixOrigin -ne 'WellKnown' } | Select-Object -First 1).IPAddress
Write-Host "폰 브라우저에서 열기: http://${ip}:$Port/?k=<개발자 패널의 접속 코드>"
