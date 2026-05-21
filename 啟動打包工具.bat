@echo off
setlocal
cd /d "%~dp0"
set "LOG=build_tool_launch.log"
set "PYTHON_EXE="
set "PYROOT=C:\Users\JASON\AppData\Local\Programs\Python\Python313"
set "TCL_LIBRARY=C:\Users\JASON\.tcltk-python313\tcl8.6"
set "TK_LIBRARY=C:\Users\JASON\.tcltk-python313\tk8.6"

echo [%date% %time%] Start build tool > "%LOG%"

if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" --version >nul 2>> "%LOG%"
    if not errorlevel 1 set "PYTHON_EXE=.venv\Scripts\python.exe"
)

if "%PYTHON_EXE%"=="" if exist "%PYROOT%\python.exe" (
    set "PYTHON_EXE=%PYROOT%\python.exe"
)

if "%PYTHON_EXE%"=="" (
    where python >nul 2>nul
    if not errorlevel 1 set "PYTHON_EXE=python"
)

if "%PYTHON_EXE%"=="" (
    echo ERROR: Python was not found.
    echo ERROR: Python was not found. >> "%LOG%"
    pause
    exit /b 1
)

echo Using Python: %PYTHON_EXE%
echo Using Python: %PYTHON_EXE% >> "%LOG%"

if not exist "build_tool.py" (
    echo ERROR: build_tool.py not found.
    echo ERROR: build_tool.py not found. >> "%LOG%"
    pause
    exit /b 1
)

"%PYTHON_EXE%" -c "import tkinter as tk; r=tk.Tk(); r.destroy()" >nul 2>> "%LOG%"
if not errorlevel 1 (
    echo Starting GUI build tool...
    "%PYTHON_EXE%" "build_tool.py" >> "%LOG%" 2>&1
    goto :done
)

echo.
echo GUI cannot start because this Python install has broken Tcl/Tk.
echo Falling back to console build menu.
echo Details: %LOG%

:menu
echo.
echo ===== Meshtastic BBS Build Tool =====
echo 1. Build Server EXE
echo 2. Build Client EXE
echo 3. Build Both
echo 0. Exit
echo.
set /p "CHOICE=Select: "

if "%CHOICE%"=="1" goto :build_server
if "%CHOICE%"=="2" goto :build_client
if "%CHOICE%"=="3" goto :build_both
if "%CHOICE%"=="0" exit /b 0
goto :menu

:build_server
call :run_pyinstaller build_server.spec
goto :menu

:build_client
call :run_pyinstaller build_client.spec
goto :menu

:build_both
call :run_pyinstaller build_server.spec
call :run_pyinstaller build_client.spec
goto :menu

:run_pyinstaller
echo.
echo Building with %1 ...
echo Building with %1 ... >> "%LOG%"
"%PYTHON_EXE%" -m PyInstaller "%1" --noconfirm --clean
if errorlevel 1 (
    echo Build failed. See messages above.
    pause
) else (
    echo Build complete. Output is in dist\
    pause
)
exit /b 0

:done
set "EXITCODE=%errorlevel%"
echo.
if not "%EXITCODE%"=="0" (
    echo build_tool.py failed. Exit code: %EXITCODE%
    echo See: %LOG%
) else (
    echo build_tool.py has closed.
)
echo.
pause
exit /b %EXITCODE%
