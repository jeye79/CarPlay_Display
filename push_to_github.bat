@echo off
cd /d "%~dp0"

set "GIT_EXE=C:\Users\june\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\git\cmd\git.exe"
if not exist "%GIT_EXE%" set "GIT_EXE=git"

set "GH_EXE=%~dp0.build-tools\gh\bin\gh.exe"
if not exist "%GH_EXE%" set "GH_EXE=gh"

echo ========================================================
echo  OttocastDimmer - GitHub Push and Vercel Auto Deploy
echo ========================================================
echo.

"%GH_EXE%" auth status >nul 2>&1
if errorlevel 1 (
    echo [1/2] GitHub login is required.
    echo       Copy the 8-digit one-time code below,
    echo       press Enter to open https://github.com/login/device,
    echo       and approve in your browser.
    echo.
    "%GH_EXE%" auth login --hostname github.com --git-protocol https --web
    if errorlevel 1 (
        echo.
        echo [ERROR] GitHub login failed.
        pause
        exit /b 1
    )
)

echo.
echo [2/2] Pushing main branch to GitHub...
"%GIT_EXE%" config --local credential.helper ""
"%GIT_EXE%" config --local --add credential.helper "!D:/00_VibeCoding/00_Gemini/04_APP/.build-tools/gh/bin/gh.exe auth git-credential"
"%GIT_EXE%" push -u origin main
if errorlevel 1 (
    echo.
    echo [ERROR] Git push failed. Check repository permissions.
    pause
    exit /b 1
)

echo.
echo ========================================================
echo  SUCCESS! Pushed to GitHub. Vercel will deploy shortly.
echo ========================================================
pause
