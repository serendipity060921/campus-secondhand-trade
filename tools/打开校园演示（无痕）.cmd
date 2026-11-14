@echo off
chcp 65001 >nul
title Campus Trade - InPrivate
start "" msedge.exe --inprivate http://127.0.0.1:8081
timeout /t 2 >nul
exit