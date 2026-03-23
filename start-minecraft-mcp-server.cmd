@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
if exist "%SCRIPT_DIR%minecraft-mcp-server.local.cmd" call "%SCRIPT_DIR%minecraft-mcp-server.local.cmd"
set "MINECRAFT_HOST=%MINECRAFT_HOST%"
set "MINECRAFT_PORT=%MINECRAFT_PORT%"
set "MINECRAFT_USERNAME=%MINECRAFT_USERNAME%"

if "%MINECRAFT_HOST%"=="" set "MINECRAFT_HOST=localhost"
if "%MINECRAFT_PORT%"=="" set "MINECRAFT_PORT=25565"
if "%MINECRAFT_USERNAME%"=="" set "MINECRAFT_USERNAME=CodexBot"

cd /d "%SCRIPT_DIR%"
node dist\main.js --host "%MINECRAFT_HOST%" --port "%MINECRAFT_PORT%" --username "%MINECRAFT_USERNAME%"
