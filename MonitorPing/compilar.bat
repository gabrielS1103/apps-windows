@echo off
cd /d "%~dp0"
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /optimize+ /codepage:65001 /win32manifest:MonitorPing.manifest /out:MonitorPing.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll MonitorPing.cs
if %errorlevel%==0 (echo Compilado: MonitorPing.exe) else (echo Error al compilar)
pause
