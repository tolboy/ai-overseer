@echo off
REM Launcher. Conductor and Orca call this; neither needs to know it is Java.
setlocal
set "OSEER_HOME=%~dp0.."
set "OSEER_CLASSES=%OSEER_HOME%\out\classes"
if exist "%OSEER_HOME%\lib\oseer.jar" set "OSEER_CLASSES=%OSEER_HOME%\lib\oseer.jar"
java -cp "%OSEER_CLASSES%" dev.oseer.Main %*
