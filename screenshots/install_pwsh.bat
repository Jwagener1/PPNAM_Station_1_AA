@echo off
echo Installing PowerShell 7 via winget...
winget install --id Microsoft.PowerShell --source winget --accept-source-agreements --accept-package-agreements
if %ERRORLEVEL%==0 (
    echo.
    echo [OK] PowerShell 7 installed. Please CLOSE this window and re-open VS Code.
    echo      Then the SOP generator will be able to run automatically.
) else (
    echo.
    echo [WARN] winget install failed. Trying MSI download...
    echo Downloading PowerShell 7.4 MSI...
    curl -L -o "%TEMP%\pwsh.msi" "https://github.com/PowerShell/PowerShell/releases/download/v7.4.6/PowerShell-7.4.6-win-x64.msi"
    msiexec /i "%TEMP%\pwsh.msi" /quiet /norestart
    echo [OK] MSI install launched. Wait for it to complete then close and reopen VS Code.
)
pause
