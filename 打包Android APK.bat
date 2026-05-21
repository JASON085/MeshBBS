@echo off
chcp 65001 > nul
title MeshBBS - Android APK

echo.
echo  ================================================
echo   MeshBBS Android APK Build Tool
echo  ================================================
echo.

cd /d "%~dp0android\MeshtasticBBS"
if %errorlevel% neq 0 (
    echo  [Error] Cannot find android\MeshtasticBBS folder
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File ".\build_apk.ps1"
set BUILD_RESULT=%errorlevel%

echo.
if %BUILD_RESULT% neq 0 (
    echo  ================================================
    echo   Build FAILED. Check error messages above.
    echo  ================================================
) else (
    echo  ================================================
    echo   Done! You can close this window.
    echo  ================================================
)

echo.
pause