@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run-accelerated.ps1" %*
exit /b %errorlevel%
