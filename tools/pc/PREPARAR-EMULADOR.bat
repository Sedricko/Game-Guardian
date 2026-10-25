@echo off
rem Duplo clique: roda o PREPARAR-EMULADOR.ps1 que esta na mesma pasta (o Windows nao roda .ps1 com duplo clique)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0PREPARAR-EMULADOR.ps1"
