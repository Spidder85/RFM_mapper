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

set PATH=C:\Program Files\Crypto Pro\CSP;%JAVA_HOME%\bin;%PATH%

"%JAVA_HOME%\bin\java.exe" ^
  -Djava.library.path="C:\Program Files\Crypto Pro\CSP" ^
  -Dcom.sun.net.ssl.checkRevocation=false ^
  -Djavax.net.ssl.trustStore=NONE ^
  -Djavax.net.ssl.trustStoreType=Windows-ROOT ^
  -cp "rfm-downloader.jar;libs\*" ^
  org.ikozmin.rfm.Main ^
  --config config\config.json ^
  --prod ^
  --catalog te21

exit /b %ERRORLEVEL%