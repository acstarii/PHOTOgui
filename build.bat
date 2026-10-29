@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
  echo.
  echo Build failed. Make sure a JDK 17 or newer is installed ^(javac must work^).
  exit /b 1
)
echo Build OK.
