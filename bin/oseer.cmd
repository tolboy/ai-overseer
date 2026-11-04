@echo off
REM Launcher. Conductor and Orca call this; neither needs to know it is Java.
setlocal
set OSEER_HOME=%~dp0..
java -cp "%OSEER_HOME%\out\classes" dev.oseer.Main %*
