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
REM
REM  TO STOP THE CABINET: closing this console window stops the WATCHDOG ONLY.
REM  `start` has already detached the game from it, so the game keeps running.
REM  Close the game's own window too, or Ctrl+C then Y in this window followed
REM  by closing the game separately.
REM ---------------------------------------------------------------------------

cd /d "%~dp0"

REM  Its own file, NOT the booth log: BoothLog tees the JVM's System.out, and these
REM  echoes are the shell's, not the JVM's. A cabinet has nowhere to show a cmd window
REM  anyway, so the restart history has to reach disk to be worth writing.
set "WDLOG=%USERPROFILE%\EnPustTil\logs\watchdog.log"
if not exist "%USERPROFILE%\EnPustTil\logs" mkdir "%USERPROFILE%\EnPustTil\logs"

:relaunch
REM  Leading redirection, deliberately: cmd expands %TIME% BEFORE it scans for
REM  redirection operators, and %TIME%'s last character is always a digit (it is
REM  HH-mm-ss-ff on every locale). "...%TIME%>>" would therefore be misread as a
REM  digit naming a redirection HANDLE, not the start of ">>" - silently truncating
REM  the echoed line and redirecting only what followed the swallowed digit.
REM  Putting ">>" first, with nothing ahead of it, cannot be misparsed this way.
>>"%WDLOG%" echo [watchdog] starting en-pust-til.exe at %DATE% %TIME%
REM  /wait matters exactly as much as stayAlive = true in build.gradle.kts does:
REM  launch4j's gui-header launcher returns as soon as it has spawned javaw unless
REM  told to stay alive, so /wait alone is not enough - both have to hold for this
REM  to actually wait for the JVM rather than the launcher.
start /wait "" "en-pust-til.exe"
>>"%WDLOG%" echo [watchdog] exited with code %ERRORLEVEL% at %DATE% %TIME%
REM  ping, not timeout: timeout refuses to run when stdin is not a console (e.g. a
REM  Scheduled Task set to run whether a user is logged on or not) - it prints an
REM  error and returns immediately even with /nobreak, and >nul hides that error.
REM  Combined with a launcher that does not actually wait, that turns into a
REM  restart loop at full speed. ping has no console/stdin dependency: six pings
REM  to the loopback address, one per second, is a ~5 second delay either way.
ping -n 6 127.0.0.1 >nul
goto :relaunch
