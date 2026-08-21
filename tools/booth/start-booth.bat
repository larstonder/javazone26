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
REM  Leading redirection, deliberately - and this is total loss, not truncation.
REM  cmd expands %TIME% BEFORE it scans for redirection operators, and a digit 0-9
REM  immediately before a redirection operator is consumed as a HANDLE SPECIFIER,
REM  never printed. %TIME%'s last character is always such a digit, so
REM  "...%TIME%>>" (or a regressed "...%TIME%>") redirects some handle other than
REM  echo's actual stdout - empirically stderr, handle 2, which echo never writes
REM  to - so the log receives NOTHING from that line, every time; the (still-
REM  mangled) text goes, unredirected, to the console, which a headless booth has
REM  nowhere to show. Putting ">>" first, with nothing ahead of it, cannot be
REM  misparsed this way at all.
>>"%WDLOG%" echo [watchdog] starting en-pust-til.exe at %DATE% %TIME%
REM  /wait matters exactly as much as stayAlive = true in build.gradle.kts does:
REM  launch4j's gui-header launcher returns as soon as it has spawned javaw unless
REM  told to stay alive, so /wait alone is not enough - both have to hold for this
REM  to actually wait for the JVM rather than the launcher.
start /wait "" "en-pust-til.exe"
>>"%WDLOG%" echo [watchdog] exited with code %ERRORLEVEL% at %DATE% %TIME%
REM  ping first: no console/stdin dependency (unlike timeout, which refuses to run
REM  when stdin is not a console - e.g. a Scheduled Task set to run whether a user
REM  is logged on or not - printing an error and returning immediately even with
REM  /nobreak) and no network/DNS dependency (127.0.0.1 needs neither and is exempt
REM  from firewall filtering). Six pings, one per second, is a ~5 second delay.
REM
REM  But ping.exe itself can still fail fast and silently: blocked by AppLocker/
REM  WDAC or an EDR agent, a broken PATH, or a hard transmit error all make it
REM  return immediately with no delay at all, and >nul hides the error either way -
REM  collapsing the delay to ~0 in exactly the boot-crash-loop scenario it exists
REM  for. Each "||" only runs when the command before it already failed, so this
REM  chain costs nothing when ping works: it falls back to timeout (fine from a
REM  real console), and finally to `waitfor /t 5 <name>`, which ships in System32,
REM  needs neither stdin nor a network, waits the full 5s for a signal that will
REM  never arrive, then returns 1 - harmless, since nothing after this line reads
REM  ERRORLEVEL.
ping -n 6 127.0.0.1 >nul 2>&1 || timeout /t 5 /nobreak >nul 2>&1 || waitfor /t 5 EptRestart >nul 2>&1
goto :relaunch
