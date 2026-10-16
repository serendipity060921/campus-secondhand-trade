@echo off
REM ---------------------------------------------------------------------------
REM Production database backup launcher (v0.18)
REM
REM Why a .bat wrapper: Windows blocks .ps1 execution by default
REM (ExecutionPolicy = Restricted, error "running scripts is disabled").
REM This launcher runs the script with -ExecutionPolicy Bypass so it works
REM by double-click and under Task Scheduler, WITHOUT changing the
REM machine-wide execution policy.
REM
REM Usage:
REM   backup-db.bat                 daily backup
REM   backup-db.bat -Verify         backup + restore drill
REM   backup-db.bat -DryRun         print actions only, touch nothing
REM
REM Register a daily task (run once as Administrator):
REM   schtasks /Create /TN "CampusTrade-DB-Backup" /SC DAILY /ST 02:30 ^
REM     /TR "\"D:\campus-secondhand-trade\tools\backup-db.bat\"" /RL HIGHEST /F
REM
REM Note: backup-db.ps1 must be saved as UTF-8 *with BOM*, otherwise
REM Windows PowerShell 5.1 reads it as GBK and Chinese text breaks the parser.
REM ---------------------------------------------------------------------------
setlocal
set "SCRIPT=%~dp0backup-db.ps1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
exit /b %ERRORLEVEL%
