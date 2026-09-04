@echo off
setlocal
set "BUNDLED_PYTHON=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
if exist "%BUNDLED_PYTHON%" (
  "%BUNDLED_PYTHON%" "%~dp0scripts\generate-group-pdfs.py" %*
) else (
  py -3 "%~dp0scripts\generate-group-pdfs.py" %*
)
exit /b %ERRORLEVEL%
