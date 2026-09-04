@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\restore-device.ps1" %*
exit /b %errorlevel%
