@echo off
setlocal

:: ─────────────────────────────────────────────────────────────────────────────
::  PPNAM SOP Generator Launcher
::  Double-click this file to generate the full SOP document.
::
::  Output:
::    screenshots\sop_images\  — PNG screenshots
::    screenshots\SOP.md       — Assembled SOP document
:: ─────────────────────────────────────────────────────────────────────────────

cd /d "%~dp0"

echo.
echo =====================================================
echo   PPNAM Scanner App - SOP Generator
echo =====================================================
echo.

:: ── 1. Check ADB ──────────────────────────────────────────────────────────────
set ADB=C:\Users\JonathanSystemOne\AppData\Local\Android\Sdk\platform-tools\adb.exe

if not exist "%ADB%" (
    echo [ERROR] ADB not found at:
    echo         %ADB%
    echo.
    echo Please install Android SDK Platform Tools and update the ADB path in this file.
    pause
    exit /b 1
)

echo [1/5] Checking ADB device connection...
"%ADB%" devices
echo.

:: Verify at least one device is connected (non-header line after "List of devices")
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
    if /i "%%B"=="device" (
        set DEVICE_SERIAL=%%A
        goto :device_found
    )
)
echo [ERROR] No ADB device detected.
echo         - Make sure the scanner is connected via USB
echo         - Make sure USB Debugging is enabled on the device
echo         - Try unplugging and reconnecting the USB cable
echo         - Accept the "Allow USB Debugging" prompt on the device if shown
pause
exit /b 1

:device_found
echo [OK] Device found: %DEVICE_SERIAL%
echo.

:: ── 2. Keep screen on ─────────────────────────────────────────────────────────
echo [2/5] Keeping device screen awake for duration of run...
"%ADB%" shell settings put system screen_off_timeout 600000
"%ADB%" shell svc power stayon usb
echo [OK] Screen will stay on for 10 minutes.
echo.

:: ── 3. Clean up prior run artifacts ──────────────────────────────────────────
echo [3/5] Cleaning up previous run files...
if exist "sop_images" (
    rmdir /s /q "sop_images"
    echo       Removed old sop_images\
)
if exist "SOP.md" (
    del /q "SOP.md"
    echo       Removed old SOP.md
)
if exist "scenario.txt" (
    del /q "scenario.txt"
)
if exist "mqtt_log.txt" (
    del /q "mqtt_log.txt"
    echo       Cleared mqtt_log.txt
)
echo [OK] Clean.
echo.

:: ── 4. Start mock backend in a separate window ────────────────────────────────
echo [4/5] Starting mock backend (separate window)...
start "PPNAM Mock Backend" cmd /k "python mock_backend.py"
echo [OK] Mock backend window opened.
echo      Waiting 4 seconds for broker connection...
timeout /t 4 /nobreak > nul
echo.

:: ── 5. Run the SOP generator ─────────────────────────────────────────────────
echo [5/5] Running SOP generator - DO NOT touch the scanner device...
echo       (This takes approximately 5-8 minutes)
echo.
python generate_sop.py
set EXIT_CODE=%ERRORLEVEL%

echo.
echo =====================================================
if %EXIT_CODE%==0 (
    echo   SUCCESS - SOP generation complete!
    echo.
    echo   Output files:
    echo     screenshots\sop_images\  - Screenshots
    echo     screenshots\SOP.md       - Full SOP document
    echo.
    echo   You can open SOP.md in any Markdown viewer,
    echo   or paste it into Notion / Confluence.
) else (
    echo   FAILED - SOP generator exited with code %EXIT_CODE%
    echo.
    echo   Common fixes:
    echo     - Check the "PPNAM Mock Backend" window for errors
    echo     - Make sure the scanner app is in the foreground
    echo     - Re-run this file to try again
)
echo =====================================================
echo.

:: ── Verify output ──────────────────────────────────────────────────────────────
if %EXIT_CODE%==0 (
    echo.
    echo Verification:
    if exist "SOP.md" (
        echo   [OK] SOP.md created
    ) else (
        echo   [WARN] SOP.md not found - check generate_sop.py output above
    )
    if exist "sop_images" (
        set COUNT=0
        for %%f in (sop_images\*.png) do set /a COUNT+=1
        echo   [OK] Screenshots captured in sop_images\
    ) else (
        echo   [WARN] sop_images\ folder not found
    )
)

:: Restore normal screen timeout (2 minutes)
"%ADB%" shell settings put system screen_off_timeout 120000

pause
