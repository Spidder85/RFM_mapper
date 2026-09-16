@echo off
chcp 65001 > nul
setlocal DisableDelayedExpansion
cd /d "%~dp0"

set "APP_HOME=%~dp0"
set "JAVA_HOME=%APP_HOME%jdk-21.0.10"

rem Keep an explicitly supplied password; otherwise read the local secret file.
rem The file contains the container password on its first line (not a SET command).
if defined RFM_KEY_PASSWORD goto :password_ready
if not exist "%APP_HOME%config\rfm-key-password.local" goto :password_ready
set /p "RFM_KEY_PASSWORD=" < "%APP_HOME%config\rfm-key-password.local"
if defined RFM_KEY_PASSWORD goto :password_ready
echo ERROR: Cannot read a non-empty password from config\rfm-key-password.local.
exit /b 2

:password_ready

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java not found: "%JAVA_HOME%\bin\java.exe"
  exit /b 2
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

"%JAVA_HOME%\bin\java.exe" ^
  -Dcom.sun.net.ssl.checkRevocation=false ^
  -Djavax.net.ssl.trustStore=NONE ^
  -Djavax.net.ssl.trustStoreType=Windows-ROOT ^
  -cp "rfm-downloader.jar;libs\*" ^
  org.ikozmin.rfm.Main ^
  --config config\config.json ^
  --prod

exit /b %ERRORLEVEL%
