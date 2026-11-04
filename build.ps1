# Gradle CLI 빌드 (Android Studio 없이). Studio 번들 JBR 25는 Gradle 8.10.2를 실행할 수 없어 Temurin 21 사용.
# 사용: powershell -File build.ps1 <task...>   예) build.ps1 test assembleDebug
$env:JAVA_HOME = (Get-ChildItem 'C:\dev\tools' -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
Set-Location $PSScriptRoot
& .\gradlew.bat --console=plain @args
$code = $LASTEXITCODE
& .\gradlew.bat --stop | Out-Null
exit $code
