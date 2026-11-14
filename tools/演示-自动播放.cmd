@echo off
chcp 65001 >nul
title Campus Trade - Auto Demo
cd /d "D:\campus-secondhand-trade"
if not exist "D:\campus-secondhand-trade\tools\demo-play.py" (
  echo [ERROR] demo-play.py not found at D:\campus-secondhand-trade\tools\
  pause
  exit /b 1
)
echo.
echo   Starting auto demo ... a browser window will open shortly.
echo   Narration will appear in this window.
echo.
"C:\Users\26859\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" "D:\campus-secondhand-trade\tools\demo-play.py" %*
if errorlevel 1 (
  echo.
  echo   [ERROR] demo exited with an error. See message above.
  pause
)