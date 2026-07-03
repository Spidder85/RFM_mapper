@echo off
chcp 65001 > nul
setlocal
cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JAVA_HOME=%APP_HOME%jdk-21.0.10"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java not found: "%JAVA_HOME%\bin\java.exe"
  exit /b 2
)

"%JAVA_HOME%\bin\java.exe" ^
  -Dapp.home="%APP_HOME%" ^
  -cp "zenith-processor.jar;libs\*" ^
  org.ikozmin.zenith.ZenithProcessorMain ^
  --config config\zenith-config.json ^
  --once ^
  %*

exit /b %ERRORLEVEL%