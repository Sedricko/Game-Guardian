@echo off
rem Duplo clique: uma rodada do Tinker no DYSMANTLE de verdade, no emulador (TINKER-AGENTE.ps1 -Dysmantle, na mesma pasta)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0TINKER-AGENTE.ps1" -Dysmantle
pause
