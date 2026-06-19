@echo off
setlocal
cd /d "%~dp0"

set JAVA_HOME=C:\tmp\jdk-21.0.10
set PATH=C:\Program Files\Crypto Pro\CSP;%JAVA_HOME%\bin;%PATH%
set JAR_FILE=rfm-client.jar

if not exist "%JAR_FILE%" (
    echo ERROR: JAR file not found: %JAR_FILE%
::    pause
    exit /b 1
)

if not exist "libs" (
    echo ERROR: libs folder not found
::    pause
    exit /b 1
)

echo === Запуск RFM_mapper с CryptoPro JCP ===
echo.

"%JAVA_HOME%\bin\java.exe" ^
    -Djava.library.path="C:\Program Files\Crypto Pro\CSP" ^
    -Dcom.sun.net.ssl.checkRevocation=false ^
    -Djavax.net.ssl.trustStore=NONE ^
    -Djavax.net.ssl.trustStoreType=Windows-ROOT ^
    -cp "rfm-client.jar;libs\*" ^
    org.ikozmin.rfm.Main ^
    --config config.json ^
    --test ^
    --catalog mvk ^
    --out out-test-access

echo.
echo === Exit code: %ERRORLEVEL% ===

::pause
exit /b %ERRORLEVEL%