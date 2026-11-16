@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "GIT_EXE=C:\Users\june\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\git\cmd\git.exe"
if not exist "%GIT_EXE%" set "GIT_EXE=git"

set "GH_EXE=%~dp0.build-tools\gh\bin\gh.exe"
if not exist "%GH_EXE%" set "GH_EXE=gh"

echo ========================================================
echo  OttocastDimmer - GitHub 푸시 및 Vercel 자동 배포
echo ========================================================
echo.

"%GH_EXE%" auth status >nul 2>&1
if errorlevel 1 (
    echo [1/2] GitHub 로그인이 필요합니다.
    echo       잠시 후 나타나는 8자리 인증 코드를 복사한 뒤,
    echo       브라우저(https://github.com/login/device)에 붙여넣고 승인해 주세요.
    echo.
    "%GH_EXE%" auth login --hostname github.com --git-protocol https --web
    if errorlevel 1 (
        echo.
        echo [오류] GitHub 로그인에 실패했습니다.
        pause
        exit /b 1
    )
)

echo.
echo [2/2] GitHub 저장소(main)로 업로드 중...
"%GIT_EXE%" config --local credential.helper ""
"%GIT_EXE%" config --local --add credential.helper "!D:/00_VibeCoding/00_Gemini/04_APP/.build-tools/gh/bin/gh.exe auth git-credential"
"%GIT_EXE%" push -u origin main
if errorlevel 1 (
    echo.
    echo [오류] GitHub 푸시에 실패했습니다. 저장소 권한을 확인해 주세요.
    pause
    exit /b 1
)

echo.
echo ========================================================
echo  완료! GitHub에 업로드되었으며 Vercel이 자동으로 배포합니다.
echo ========================================================
pause
