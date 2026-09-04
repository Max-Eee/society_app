@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-apk.ps1" %*
exit /b %errorlevel%
