@echo off
chcp 65001 >nul
title 校园二手交易平台 · 自动演示
cd /d "%~dp0..\"
echo.
echo   正在打开浏览器并自动演示，请稍候...
echo   讲稿会显示在这个窗口里。
echo.
"C:\Users\26859\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" tools\demo-play.py
if errorlevel 1 pause
