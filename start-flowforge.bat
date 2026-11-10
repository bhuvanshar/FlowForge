@echo off
rem FlowForge launcher for Windows: starts the full stack in Docker and opens it in Google Chrome.
rem
rem   start-flowforge.bat          start (builds images the first time) and open the app
rem   start-flowforge.bat stop     stop the containers (data is kept)
rem   start-flowforge.bat status   show container health
rem   start-flowforge.bat logs     follow the backend log
rem
rem Double-clicking it in Explorer works too. Needs Docker Desktop. On first use it creates infra\.env with a random
rem database password and credential encryption key; an existing infra\.env is reused (keep it and back it up: the key
rem decrypts your stored credentials). The macOS equivalent is start-flowforge.sh.
setlocal EnableExtensions
pushd "%~dp0"
set "ENV_FILE=infra\.env"
set "COMPOSE=docker compose -f infra\docker-compose.yml --env-file %ENV_FILE% --profile app"
set "RC=0"
rem Started from Explorer: keep the window open at the end so the messages can be read.
set "DOUBLE_CLICKED="
echo %cmdcmdline% | findstr /i /c:"%~nx0" >nul && set "DOUBLE_CLICKED=1"

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=start"

call :ensure_docker
if errorlevel 1 goto :fail

if /i "%ACTION%"=="start" goto :start
if /i "%ACTION%"=="stop" goto :stop
if /i "%ACTION%"=="status" goto :status
if /i "%ACTION%"=="logs" goto :logs
echo Error: Unknown command "%ACTION%". Use: start ^| stop ^| status ^| logs
goto :fail

:start
call :ensure_env
if errorlevel 1 goto :fail
call :read_value FRONTEND_PORT 5173
set "FRONTEND_PORT=%VALUE%"
call :read_value BACKEND_PORT 8080
set "BACKEND_PORT=%VALUE%"
call :read_value POSTGRES_PORT 5432
set "POSTGRES_PORT=%VALUE%"
call :check_port %FRONTEND_PORT% frontend FRONTEND_PORT
if errorlevel 1 goto :fail
call :check_port %BACKEND_PORT% backend BACKEND_PORT
if errorlevel 1 goto :fail
call :check_port %POSTGRES_PORT% postgres POSTGRES_PORT
if errorlevel 1 goto :fail

echo ==^> Building and starting FlowForge (the first build takes a few minutes)...
%COMPOSE% up -d --build --wait --wait-timeout 600
if errorlevel 1 goto :start_failed

set "URL=http://localhost:%FRONTEND_PORT%"
echo ==^> FlowForge is running: %URL%   (API: http://localhost:%BACKEND_PORT%/api/v1/health)
call :open_browser
echo     Stop it with: start-flowforge.bat stop
goto :done

:start_failed
echo.
%COMPOSE% ps
echo Error: FlowForge did not start. Backend log: start-flowforge.bat logs   (see docs\TROUBLESHOOTING.md)
goto :fail

:stop
echo ==^> Stopping FlowForge (your data is kept)...
%COMPOSE% stop
if errorlevel 1 goto :fail
goto :done

:status
%COMPOSE% ps
goto :done

:logs
%COMPOSE% logs -f --tail 100 backend
goto :done

rem ---------------------------------------------------------------------------------------------------------------------

:ensure_docker
where docker >nul 2>&1
if errorlevel 1 goto :no_docker
docker compose version >nul 2>&1
if errorlevel 1 goto :no_compose
docker info >nul 2>&1
if not errorlevel 1 exit /b 0
set "DOCKER_DESKTOP=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" goto :docker_not_running
echo ==^> Starting Docker Desktop...
start "" "%DOCKER_DESKTOP%"
set /a WAITED=0
:wait_for_docker
docker info >nul 2>&1
if not errorlevel 1 exit /b 0
if %WAITED% GEQ 180 goto :docker_timeout
rem A 3-second pause that also works without a console (timeout /t fails when input is redirected).
ping -n 4 127.0.0.1 >nul
set /a WAITED+=3
goto :wait_for_docker

:no_docker
echo Error: Docker is not installed. Install Docker Desktop: https://www.docker.com/products/docker-desktop/
exit /b 1
:no_compose
echo Error: Docker Compose v2 is missing (update Docker Desktop).
exit /b 1
:docker_not_running
echo Error: Docker Desktop is not running. Start it and run this script again.
exit /b 1
:docker_timeout
echo Error: Docker did not become ready within 3 minutes.
exit /b 1

:ensure_env
if exist "%ENV_FILE%" goto :env_exists
echo ==^> Creating %ENV_FILE% with a random database password and credential key
powershell -NoProfile -ExecutionPolicy Bypass -Command "$rng = [Security.Cryptography.RandomNumberGenerator]::Create(); $p = New-Object byte[] 16; $k = New-Object byte[] 32; $rng.GetBytes($p); $rng.GetBytes($k); $password = -join ($p | ForEach-Object { $_.ToString('x2') }); $key = [Convert]::ToBase64String($k); $lines = Get-Content 'infra\.env.example' | ForEach-Object { if ($_ -like 'POSTGRES_PASSWORD=*') { 'POSTGRES_PASSWORD=' + $password } elseif ($_ -like 'FLOWFORGE_CREDENTIALS_KEYS=*') { 'FLOWFORGE_CREDENTIALS_KEYS=k1:' + $key } else { $_ } }; [IO.File]::WriteAllText((Join-Path (Get-Location) 'infra\.env'), ($lines -join [char]10) + [char]10)"
if errorlevel 1 exit /b 1
echo     Back up %ENV_FILE%: without its key, stored credentials cannot be decrypted.
exit /b 0
:env_exists
call :read_value FLOWFORGE_CREDENTIALS_KEYS ""
if not "%VALUE%"=="" exit /b 0
rem No key means credentials were disabled, so nothing is encrypted yet and adding one is safe.
echo ==^> Adding a credential encryption key to %ENV_FILE%
powershell -NoProfile -ExecutionPolicy Bypass -Command "$k = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($k); $nl = [string][char]10; [IO.File]::AppendAllText((Join-Path (Get-Location) 'infra\.env'), $nl + 'FLOWFORGE_CREDENTIALS_KEYS=k1:' + [Convert]::ToBase64String($k) + $nl)"
if errorlevel 1 exit /b 1
exit /b 0

rem Sets VALUE to KEY's value in infra\.env (last occurrence wins, like Compose), or to the given default.
:read_value
set "VALUE=%~2"
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do if /i "%%a"=="%~1" if not "%%b"=="" set "VALUE=%%b"
exit /b 0

rem Fails when another program (not FlowForge) already listens on a port FlowForge needs.
:check_port
set "RUNNING="
for /f %%i in ('%COMPOSE% ps --status running -q %~2 2^>nul') do set "RUNNING=1"
if defined RUNNING exit /b 0
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul
if errorlevel 1 exit /b 0
echo Error: Port %~1 is already in use by another program.
echo        Stop it, or choose another port: set %~3=^<port^> in %ENV_FILE%.
exit /b 1

:open_browser
reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe" >nul 2>&1 && goto :open_chrome
reg query "HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe" >nul 2>&1 && goto :open_chrome
echo     Google Chrome is not installed; opening your default browser.
start "" "%URL%"
exit /b 0
:open_chrome
start "" chrome "%URL%"
exit /b 0

rem ---------------------------------------------------------------------------------------------------------------------

:fail
set "RC=1"
:done
popd
if defined DOUBLE_CLICKED pause
exit /b %RC%
