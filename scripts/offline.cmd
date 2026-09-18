@echo off
setlocal
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -cp "%~dp0lib\*" dev.kekaop.ServerBootstrap.OfflineMain %*
) else (
  java -cp "%~dp0lib\*" dev.kekaop.ServerBootstrap.OfflineMain %*
)
exit /b %ERRORLEVEL%
