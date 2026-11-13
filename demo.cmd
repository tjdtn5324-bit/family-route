@echo off
rem Clone-and-run demo launcher. Needs Node.js 24+ only (https://nodejs.org).
chcp 65001 >nul
where node >nul 2>nul
if errorlevel 1 (
  echo Node.js 24 or newer is required. Install it from https://nodejs.org and run this file again.
  pause
  exit /b 1
)
node "%~dp0tools\demo.mjs" %*
pause
