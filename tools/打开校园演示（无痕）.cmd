@echo off
chcp 65001 >nul
title 打开校园二手交易平台（无痕·演示用）
echo.
echo   正在用无痕窗口打开 http://127.0.0.1:8081 ...
echo   无痕窗口不受扩展/缓存/站点数据影响，也不会被那个"抢窗口"的程序影响。
echo.
start "" msedge.exe --inprivate http://127.0.0.1:8081
timeout /t 2 >nul
exit
