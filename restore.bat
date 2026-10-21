@echo off
rem Undoes install.bat: brings back removed apps, re-enables stock launchers.
rem Usage: restore.bat <TV_IP>
setlocal
set "IP=%~1"
if "%IP%"=="" (echo usage: restore.bat ^<TV_IP^> & exit /b 1)
set "DIR=%~dp0"
adb connect %IP%:5555 >nul
set "T=adb -s %IP%:5555"
for /f "usebackq eol=# tokens=*" %%p in ("%DIR%debloat\disable.txt") do (
  echo   enable %%p
  %T% shell pm enable %%p
)
for /f "usebackq eol=# tokens=*" %%p in ("%DIR%debloat\remove.txt") do (
  echo   restore %%p
  %T% shell cmd package install-existing %%p
)
%T% shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity >nul 2>&1
echo Restored. Optionally uninstall the launcher: adb -s %IP%:5555 uninstall dev.lite.launcher
