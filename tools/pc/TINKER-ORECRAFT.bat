@echo off
rem Duplo clique: uma rodada do Tinker no Orecraft de verdade, no emulador Dysmantle_Play (offline, modo aviao no teste)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0TINKER-AGENTE.ps1" -Jogo com.evrika.miner.camp
pause
