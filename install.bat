@echo off
rem Installs MiTV Lite Launcher, makes it the home screen and debloats the TV.
rem Usage: install.bat <TV_IP> [apk]      (set KEEP_BLOAT=1 to skip debloating)
setlocal enabledelayedexpansion
set "IP=%~1"
if "%IP%"=="" (echo usage: install.bat ^<TV_IP^> [apk] & exit /b 1)
set "APK=%~2"
if "%APK%"=="" set "APK=mitv-lite-launcher-v1.0.apk"
set "DIR=%~dp0"
where adb >nul 2>&1 || (echo adb not found. Install Android platform-tools first, see GUIDE.md. & exit /b 1)
if not exist "%APK%" (echo APK not found: %APK% & exit /b 1)

adb connect %IP%:5555 >nul
adb -s %IP%:5555 get-state >nul 2>&1 || (echo Cannot reach %IP%:5555. Enable network debugging and accept the prompt on the TV. & exit /b 1)
set "T=adb -s %IP%:5555"

echo Installing launcher...
%T% install -r "%APK%" || exit /b 1
%T% shell cmd package set-home-activity dev.lite.launcher/.MainActivity
%T% shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | findstr /c:"dev.lite.launcher" >nul || (echo Launcher did not register as a home app; stopping before touching anything else. & exit /b 1)

if "%KEEP_BLOAT%"=="1" goto stock
echo Removing bloatware...
for /f "usebackq eol=# tokens=*" %%p in ("%DIR%debloat\remove.txt") do (
  echo   %%p
  %T% shell pm uninstall -k --user 0 %%p
)
:stock
rem The stock launchers outrank this one, so they must be disabled for it to become home.
echo Disabling stock launchers...
for /f "usebackq eol=# tokens=*" %%p in ("%DIR%debloat\disable.txt") do (
  echo   %%p
  %T% shell pm disable-user --user 0 %%p
  %T% shell am force-stop %%p >nul 2>&1
)
%T% shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | findstr /c:"dev.lite.launcher" >nul && goto done
echo Launcher is not the home app after disabling the stock launchers. Rolling back.
for /f "usebackq eol=# tokens=*" %%p in ("%DIR%debloat\disable.txt") do %T% shell pm enable %%p >nul 2>&1
exit /b 1
:done
echo Done. Press Home on the remote.
