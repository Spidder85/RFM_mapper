@echo off
setlocal
cd /d "%~dp0"

set JAVA_HOME=C:\Program Files\Java\jdk-21.0.10

"%JAVA_HOME%\bin\java.exe" ^
  -cp "zenith-processor.jar;libs\*" ^
  org.ikozmin.zenith.ZenithProcessorMain ^
  --config config\zenith-config.json ^
  --once

exit /b %ERRORLEVEL%