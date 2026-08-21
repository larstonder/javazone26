@echo off
REM ---------------------------------------------------------------------------
REM  En Pust Til - booth watchdog.
REM
REM  START THE CABINET WITH THIS, NOT WITH en-pust-til.exe DIRECTLY.
REM  Put a shortcut to this file in shell:startup so the cabinet comes back on
REM  its own after a power cut.
REM
REM  Why it exists: render/CallbackGuard stops OUR code from ending the process,
REM  but nothing in the JVM catches a GL driver fault, an unrecoverable OOM, or
REM  an attendee finding the power switch. Without this, the booth is dead from
REM  that moment until a human notices - which, in a hall, is a long time.
REM
REM  The delay is the important part: a game that fails during onCreate would
REM  otherwise be relaunched thousands of times a minute, filling the disk with
REM  logs (see booth/BoothLog.kt) and pinning a core.
REM ---------------------------------------------------------------------------

cd /d "%~dp0"

REM  Its own file, NOT the booth log: BoothLog tees the JVM's System.out, and these
REM  echoes are the shell's, not the JVM's. A cabinet has nowhere to show a cmd window
REM  anyway, so the restart history has to reach disk to be worth writing.
set "WDLOG=%USERPROFILE%\EnPustTil\logs\watchdog.log"
if not exist "%USERPROFILE%\EnPustTil\logs" mkdir "%USERPROFILE%\EnPustTil\logs"

:relaunch
echo [watchdog] starting en-pust-til.exe at %DATE% %TIME%>>"%WDLOG%"
start /wait "" "en-pust-til.exe"
echo [watchdog] exited with code %ERRORLEVEL% at %DATE% %TIME%>>"%WDLOG%"
echo [watchdog] restarting in 5 seconds - close this window to stop the cabinet
timeout /t 5 /nobreak >nul
goto :relaunch
