@echo off
setlocal
cd /d "%~dp0"

set JAVA_HOME=C:\Program Files\Java\jdk-21.0.10
set PATH=C:\Program Files\Crypto Pro\CSP;%JAVA_HOME%\bin;%PATH%

"%JAVA_HOME%\bin\java.exe" ^
  -Djava.library.path="C:\Program Files\Crypto Pro\CSP" ^
  -Dcom.sun.net.ssl.checkRevocation=false ^
  -Djavax.net.ssl.trustStore=NONE ^
  -Djavax.net.ssl.trustStoreType=Windows-ROOT ^
  -cp "rfm-downloader.jar;libs\*" ^
  org.ikozmin.rfm.Main ^
  --config config.json ^
  --prod ^
  --catalog te21

exit /b %ERRORLEVEL%