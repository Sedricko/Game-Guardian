# Agente do Tinker no PC: liga o emulador, se atualiza sozinho, instala cada versao nova do Tinker no emulador,
# roda o autoteste (so o jogo de teste, nenhum jogo de verdade) e manda o resultado para a planilha (aba Relatorios).
# Fala so com o emulador (emulator-5554): o celular no cabo nao e tocado. Deixe esta janela aberta; Ctrl+C para sair.
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$env:ANDROID_SERIAL = "emulator-5554"
$env:PATH += ";$sdk\platform-tools"
$self = $PSCommandPath
$raw = "https://raw.githubusercontent.com/Sedricko/Game-Guardian/ccr-92c99132-2ne91y/tools/pc"
$cfg = "$env:USERPROFILE\tinker-agente-painel.txt"   # link da planilha: fica so neste PC
$done = "$env:USERPROFILE\tinker-agente-testado.txt" # ultima versao testada
$log = "$env:USERPROFILE\tinker-agente-log.txt"
$tok = "$env:USERPROFILE\tinker-agente-github.txt"  # token so de leitura do GitHub (repositorio privado): fica so neste PC
$app = "dev.tinker.app"
function Say($t) { $l = "$(Get-Date -Format 'HH:mm:ss') $t"; Write-Host $l; Add-Content $log $l }
function Hdr($extra) {
    $h = @{ "User-Agent" = "Tinker"; "Cache-Control" = "no-cache" }
    $t = (Get-Content $tok -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($t) { $h["Authorization"] = "Bearer $($t.Trim())" }
    if ($extra) { $extra.Keys | ForEach-Object { $h[$_] = $extra[$_] } }
    $h
}

# APK do release: com token vai pela API (repositorio privado); o GitHub manda para o S3, que nao aceita o token junto
function Get-Apk($asset, $out) {
    if (-not (Hdr).ContainsKey("Authorization")) { Invoke-WebRequest $asset.browser_download_url -OutFile $out -UseBasicParsing; return }
    $r = $null; $loc = $null
    try { $r = Invoke-WebRequest $asset.url -Headers (Hdr @{ "Accept" = "application/octet-stream" }) -MaximumRedirection 0 -UseBasicParsing -ErrorAction SilentlyContinue }
    catch { $loc = $_.Exception.Response.Headers["Location"] }
    if ($r -and $r.StatusCode -eq 200) { [IO.File]::WriteAllBytes($out, $r.Content); return }
    if ($r) { $loc = $r.Headers.Location }
    if (-not $loc) { throw "o GitHub nao entregou o APK (token valido?)" }
    Invoke-WebRequest ([string]$loc) -OutFile $out -UseBasicParsing
}

function Norm($t) { ($t -replace "`r`n", "`n").TrimStart([char]0xFEFF) }

function Update-Self {
    try {
        $new = (Invoke-WebRequest "$raw/TINKER-AGENTE.ps1" -UseBasicParsing -Headers (Hdr)).Content
        if ($new -is [byte[]]) { $new = [Text.Encoding]::UTF8.GetString($new) }
        if ($new -and $new.Contains("Update-Self") -and (Norm $new) -ne (Norm (Get-Content $self -Raw -Encoding UTF8))) {
            [IO.File]::WriteAllText($self, ((Norm $new) -replace "`n", "`r`n"), (New-Object Text.UTF8Encoding $true))
            Say "agente atualizado, reiniciando..."
            & powershell -NoProfile -ExecutionPolicy Bypass -File $self
            exit
        }
    } catch { Say "nao consegui ver se o agente tem versao nova: $($_.Exception.Message)" }
}

function Start-Emulator {
    if ((& $adb devices) -match "emulator-5554\s+device") { return }
    $avd = (& $emu -list-avds) | Where-Object { $_ -like "*Pixel_6_Pro*" } | Select-Object -First 1
    if (-not $avd) { throw "nao achei o emulador Pixel 6 Pro no Android Studio" }
    Say "ligando o emulador $avd..."
    Start-Process $emu -ArgumentList "-avd $avd -no-snapshot-load" -WindowStyle Minimized
    & $adb wait-for-device
    do { Start-Sleep 5 } until ((& $adb shell getprop sys.boot_completed) -match "1")
    Say "emulador ligado"
}

function Install-Magisk {
    Say "o emulador ainda nao tem Magisk (root para apps): instalando com o rootAVD"
    $dir = "C:\rootAVD"
    if (-not (Test-Path "$dir\rootAVD-master")) {
        Invoke-WebRequest "https://gitlab.com/newbit/rootAVD/-/archive/master/rootAVD-master.zip" -OutFile "$env:TEMP\rootAVD.zip"
        Expand-Archive "$env:TEMP\rootAVD.zip" $dir -Force
    }
    $bat = Get-ChildItem "$dir\rootAVD-master" -Filter "rootAVD.bat" | Select-Object -First 1
    Push-Location $bat.DirectoryName
    Write-Host "Se perguntar a versao do Magisk, digite 2 e Enter. O emulador desliga sozinho no fim." -ForegroundColor Yellow
    & $bat.FullName "system-images\android-31\google_apis\x86_64\ramdisk.img"
    Pop-Location
    Start-Sleep 15
}

function Post-Sheet($motivo, $problemas, $texto, $versao) {
    $link = (Get-Content $cfg -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $link) { Say "sem link da planilha: resultado so no log ($log)"; return }
    $url = $link.Trim() + $(if ($link.Contains("?")) { "&" } else { "?" }) + "relatorio=1"
    $body = @{ jogo = "jogo de teste (emulador)"; versao = $versao; aparelho = "Emulador PC API 31"; motivo = $motivo
               problemas = $problemas; texto = $texto.Substring(0, [Math]::Min($texto.Length, 400000)) } | ConvertTo-Json -Compress
    try {
        Invoke-WebRequest $url -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json; charset=utf-8" -UseBasicParsing | Out-Null
        Say "resultado enviado para a planilha"
    } catch { Say "nao mandei para a planilha: $($_.Exception.Message)" }
}

function Test-Version {
    $rel = Invoke-RestMethod "https://api.github.com/repos/Sedricko/Game-Guardian/releases/latest" -Headers (Hdr)
    $v = $rel.tag_name.TrimStart("v")
    if ((Get-Content $done -ErrorAction SilentlyContinue) -eq $v) { return }
    Say "=== Tinker $v ==="
    Get-Apk ($rel.assets | Where-Object name -like "*.apk" | Select-Object -First 1) "$env:TEMP\Tinker.apk"
    $inst = (& $adb install -r "$env:TEMP\Tinker.apk") -join " "
    Say "instalado: $inst"
    & $adb shell appops set $app SYSTEM_ALERT_WINDOW allow | Out-Null
    & $adb shell pm grant $app android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
    # root para o Tinker sem precisar tocar em Permitir (politica do Magisk para o uid do app)
    $uid = ((& $adb shell dumpsys package $app) | Select-String "userId=(\d+)" | Select-Object -First 1).Matches.Groups[1].Value
    if ($uid) { & $adb shell "magisk --sqlite 'REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,1)'" 2>$null | Out-Null }
    & $adb logcat -b crash -c
    & $adb shell rm -f /data/data/$app/files/autoteste.txt
    & $adb shell am force-stop $app
    & $adb shell am start -n "$app/.MainActivity" --ez autoteste true | Out-Null
    Say "testando (ate 4 min)..."
    $out = $null
    for ($k = 0; $k -lt 48 -and -not $out; $k++) {
        Start-Sleep 5
        $t = & $adb shell cat /data/data/$app/files/autoteste.txt 2>$null
        if ($LASTEXITCODE -eq 0 -and $t) { $out = $t -join "`n" }
    }
    $crash = (& $adb logcat -d -b crash) -join "`n"
    if (-not $out) { $out = "o autoteste nao terminou em 4 min" }
    $first = ($out -split "`n")[0]
    $fails = (($out -split "`n") | Where-Object { $_ -like "FALHA*" }) -join "`n"
    if ($crash) { $fails += "`n=== app fechou ===`n" + $crash.Substring(0, [Math]::Min($crash.Length, 4000)) }
    Say $first
    if ($fails) { Say $fails }
    Post-Sheet "agente PC: $first" $fails ($out + "`n`n=== crash ===`n" + $crash) $v
    Set-Content $done $v
}

if (-not (Test-Path $cfg)) {
    Write-Host "Cole o link do painel da planilha (o mesmo de Tinker > Ajustes > Link do painel) e Enter." -ForegroundColor Yellow
    Write-Host "Ele fica so neste PC, em $cfg. Enter vazio = sem planilha (resultado so no log)."
    Set-Content $cfg (Read-Host "Link")
}
if (-not (Test-Path $tok)) {
    Write-Host "Token do GitHub (so leitura, github_pat_...). So precisa se o repositorio for privado; Enter vazio = sem token." -ForegroundColor Yellow
    Set-Content $tok (Read-Host "Token")
}
Say "agente ligado. Deixe esta janela aberta; Ctrl+C para sair."
while ($true) {
    Update-Self
    try {
        Start-Emulator
        & $adb root | Out-Null
        Start-Sleep 3
        & $adb wait-for-device
        if (-not ((& $adb shell pm list packages com.topjohnwu.magisk) -match "magisk")) { Install-Magisk; continue }
        Test-Version
    } catch { Say "erro: $($_.Exception.Message)" }
    Start-Sleep 120
}
