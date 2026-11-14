@echo off
chcp 65001 >nul
title Start Demo Environment
cd /d "D:\campus-secondhand-trade"
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\campus-secondhand-trade\tools\??????.ps1"
if errorlevel 1 pause