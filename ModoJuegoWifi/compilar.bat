@echo off
cd /d "%~dp0"
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /optimize+ /codepage:65001 /win32manifest:ModoJuegoWifi.manifest /out:ModoJuegoWifi.exe /r:System.Windows.Forms.dll ModoJuegoWifi.cs
if %errorlevel%==0 (echo Compilado: ModoJuegoWifi.exe) else (echo Error al compilar)
pause
