@echo off
rem Duplo clique: uma rodada do Tinker num jogo offline de verdade no emulador Dysmantle_Play (TINKER-AGENTE.ps1 -Dysmantle)
rem Se o script da pasta nao abrir (erro de sintaxe), baixa o atual do GitHub antes
powershell -NoProfile -Command "$e=$null; [void][System.Management.Automation.Language.Parser]::ParseFile('%~dp0TINKER-AGENTE.ps1',[ref]$null,[ref]$e); if ($e) { Write-Host 'script com erro, baixando o atual...'; Invoke-WebRequest 'https://raw.githubusercontent.com/Sedricko/Game-Guardian/ccr-92c99132-2ne91y/tools/pc/TINKER-AGENTE.ps1' -UseBasicParsing -OutFile '%~dp0TINKER-AGENTE.ps1' }"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0TINKER-AGENTE.ps1" -Dysmantle
pause
