@echo off
chcp 65001 >nul
title 抢焦点诊断（60 秒）
echo.
echo   ★ 现在请切到校园页面，按几次 Win+D（每次间隔 2 秒）
echo   ★ 60 秒后窗口会自动关闭
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0诊断-抢焦点.ps1"
pause
