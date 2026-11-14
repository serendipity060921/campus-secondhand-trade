@echo off
chcp 65001 >nul
title 启动演示环境
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0启动演示环境.ps1"
