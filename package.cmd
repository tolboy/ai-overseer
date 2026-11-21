@echo off
setlocal
cd /d "%~dp0" || exit /b 1
java tools\packaging\PortablePackage.java
