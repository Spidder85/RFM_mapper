@echo off
setlocal

set JAVA_HOME=C:\tmp\jdk-21.0.10
set PATH=C:\Program Files\Crypto Pro\CSP;%JAVA_HOME%\bin;%PATH%
set JAR_FILE=rfm-client-1.0.0.jar

if not exist "%JAR_FILE%" (
    echo ERROR: JAR file not found: %JAR_FILE%
    pause
    exit /b 1
)

if not exist "libs" (
    echo ERROR: libs folder not found
    pause
    exit /b 1
)

echo === Запуск RFM_mapper с CryptoPro JCP ===
echo.

"%JAVA_HOME%\bin\java.exe" ^
    -Djava.library.path="C:\Program Files\Crypto Pro\CSP" ^
    -Dru.CryptoPro.JCP.config=file:./jcp-config.xml ^
    -Djavax.net.ssl.trustStore=NONE ^
    -Djavax.net.ssl.trustStoreType=Windows-ROOT ^
    -Dcom.sun.net.ssl.checkRevocation=false ^
    -cp "rfm-client-1.0.0.jar;libs\*" ^
    org.ikozmin.rfm.Main ^
    --config config.json ^
    --prod ^
    --catalog te21 ^
    --out downloads

echo.
echo === Exit code: %ERRORLEVEL% ===

pause
exit /b %ERRORLEVEL%