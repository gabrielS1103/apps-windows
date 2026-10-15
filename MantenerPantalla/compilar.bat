@echo off
cd /d "%~dp0"
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /optimize+ /out:MantenerPantalla.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll MantenerPantalla.cs
if %errorlevel%==0 (echo Compilado: MantenerPantalla.exe) else (echo Error al compilar)
pause
