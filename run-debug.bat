@echo off
setlocal

set JAVA_HOME=C:\tmp\jdk-21.0.10
set PATH=%JAVA_HOME%\bin;%PATH%
set JAR_FILE=target\rfm-client-1.0.0.jar

if not exist "%JAR_FILE%" (
    echo ERROR: JAR file not found: %JAR_FILE%
    echo.
    echo Сначала соберите проект: mvn clean package
    pause
    exit /b 1
)

if not exist "libs" (
    echo ERROR: libs folder not found
    pause
    exit /b 1
)

echo === RFM_mapper Debug Mode ===
echo.
echo Waiting for debugger to connect on port 5005...
echo Press F5 in VSCode and select "Attach to Remote Debug"
echo.

"%JAVA_HOME%\bin\java.exe" ^
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005 ^
    -cp "%JAR_FILE%;libs\*" ^
    org.ikozmin.rfm.Main ^
    --config config.json ^
    --prod ^
    --catalog te21 ^
    --out downloads

pause