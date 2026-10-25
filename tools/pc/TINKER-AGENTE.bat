@echo off
rem Duplo clique: liga o agente do Tinker (TINKER-AGENTE.ps1, na mesma pasta)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0TINKER-AGENTE.ps1"
pause
