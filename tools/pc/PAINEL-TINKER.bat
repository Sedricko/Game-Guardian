@echo off
rem Duplo clique: abre o Painel Tinker (janela para acompanhar e controlar o agente do Orecraft). Baixa antes o painel e o
rem agente atuais do GitHub e, na 1a vez, cria o atalho "Painel Tinker" na area de trabalho (abre como administrador).
cd /d "%~dp0"
powershell -NoProfile -Command "$r='https://raw.githubusercontent.com/Sedricko/Game-Guardian/ccr-92c99132-2ne91y/tools/pc'; foreach ($f in 'PAINEL-TINKER.ps1','painel-tinker.html','TINKER-AGENTE.ps1') { try { Invoke-WebRequest \"$r/$f\" -UseBasicParsing -OutFile \"$f.novo\"; $e=$null; if ($f -like '*.ps1') { [void][System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path \"$f.novo\"),[ref]$null,[ref]$e) }; if (-not $e) { Move-Item -Force \"$f.novo\" $f } else { Remove-Item \"$f.novo\" } } catch { Write-Host \"sem internet para atualizar $($f): uso o que tenho\" } }"
powershell -NoProfile -Command "$l=[Environment]::GetFolderPath('Desktop')+'\Painel Tinker.lnk'; if (-not (Test-Path $l)) { $s=(New-Object -ComObject WScript.Shell).CreateShortcut($l); $s.TargetPath='%~dp0PAINEL-TINKER.bat'; $s.WorkingDirectory='%~dp0'; $s.WindowStyle=7; $s.IconLocation='%SystemRoot%\System32\imageres.dll,144'; $s.Save(); $b=[IO.File]::ReadAllBytes($l); $b[0x15]=$b[0x15] -bor 0x20; [IO.File]::WriteAllBytes($l,$b); Write-Host 'atalho Painel Tinker criado na area de trabalho' }"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0PAINEL-TINKER.ps1"
pause
