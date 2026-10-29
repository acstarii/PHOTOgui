@echo off
cd /d "%~dp0"
call build.bat
if errorlevel 1 goto end
echo.
java -cp out PhotoServer
:end
echo.
pause
