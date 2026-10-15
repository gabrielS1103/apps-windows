@echo off
cd /d "%~dp0"
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /optimize+ /codepage:65001 /out:ScrollRemoto.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll ScrollRemoto.cs
if %errorlevel%==0 (echo Compilado: ScrollRemoto.exe) else (echo Error al compilar)
pause
