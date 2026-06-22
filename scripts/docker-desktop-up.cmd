@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0docker-desktop-up.ps1" %*
