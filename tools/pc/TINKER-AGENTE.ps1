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
    # sem token salvo: usa o login do GitHub CLI (gh auth login), o mesmo do outro agente
    if (-not $t -and (Get-Command gh -ErrorAction SilentlyContinue)) { $t = (& gh auth token 2>$null | Select-Object -First 1) }
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

# print da tela do emulador, menor e em JPEG (cabe numa linha da planilha), como texto base64
function Shot($name) {
    $png = "$env:TEMP\tinker-$name.png"; $jpg = "$env:TEMP\tinker-$name.jpg"
    cmd /c "`"$adb`" exec-out screencap -p > `"$png`""  # o > do PowerShell 5 estraga arquivo binario
    Add-Type -AssemblyName System.Drawing
    $img = [Drawing.Image]::FromFile($png)
    $w = $(if ($img.Width -gt $img.Height) { 960 } else { 540 }); $h = [int]($img.Height * $w / $img.Width)
    $bmp = New-Object Drawing.Bitmap $img, $w, $h
    $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq "image/jpeg"
    $p = New-Object Drawing.Imaging.EncoderParameters 1
    $p.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality, [long]55)
    $bmp.Save($jpg, $codec, $p); $bmp.Dispose(); $img.Dispose()
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($jpg))
}

function Send-Shots($v) {
    try {
        & $adb shell am start -n "$app/.MainActivity" | Out-Null
        Start-Sleep 4
        & $adb shell settings put system accelerometer_rotation 0
        & $adb shell settings put system user_rotation 0
        Start-Sleep 2
        Post-Sheet "agente PC: print em pe" "" ("jpeg base64`n" + (Shot "pe")) $v
        & $adb shell settings put system user_rotation 1
        Start-Sleep 4
        Post-Sheet "agente PC: print deitado" "" ("jpeg base64`n" + (Shot "deitado")) $v
        & $adb shell settings put system user_rotation 0
    } catch { Say "nao tirei os prints: $($_.Exception.Message)" }
}

# o link da planilha (salvo so neste PC) vai para o Tinker do emulador pelo adb root, com o app fechado
function Set-PanelLink {
    $link = (Get-Content $cfg -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $link) { return }
    $f = "/data/data/$app/shared_prefs/tinker.xml"
    $xml = (& $adb shell cat $f 2>$null) -join "`n"
    if (-not $xml.Contains("</map>") -or $xml.Contains($link.Trim())) { return }
    $xml = $xml -replace '\s*<string name="keySource"\s*/>', '' -replace '\s*<string name="keySource">[^<]*</string>', ''
    $xml = $xml.Replace("</map>", "    <string name=`"keySource`">$([Security.SecurityElement]::Escape($link.Trim()))</string>`n</map>")
    $tmp = "$env:TEMP\tinker-prefs.xml"
    [IO.File]::WriteAllText($tmp, $xml, (New-Object Text.UTF8Encoding $false))
    & $adb push $tmp /data/local/tmp/tinker-prefs.xml | Out-Null
    & $adb shell "cat /data/local/tmp/tinker-prefs.xml > $f; rm /data/local/tmp/tinker-prefs.xml"
    Say "link da planilha colocado no Tinker do emulador"
}

# ===== Missoes: o agente usa o Tinker no Jogo de Teste como uma pessoa faria e mede o que resolveu =====
$game = "dev.tinker.testgame"
function Ui { & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null; [xml](((& $adb shell cat /sdcard/ui.xml) -join "") -replace '^[^<]*', '') }
function Val($label) {
    Front-Game
    $t = ((Ui).SelectNodes("//node") | ForEach-Object { $_.text }) -join "`n"
    if ($t -match "$label`: (-?[\d.]+)") { [double]$Matches[1] } else { $null }
}
function Front-Game { & $adb shell am start -n "$game/.GameActivity" | Out-Null; Start-Sleep 1 }
function Tap($text) {
    Front-Game
    $n = (Ui).SelectNodes("//node") | Where-Object { $_.text -eq $text } | Select-Object -First 1
    if (-not $n) { throw "botao '$text' nao achado" }
    $b = [regex]::Matches($n.bounds, "\d+") | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
    Start-Sleep -Milliseconds 700
}
# um passo do Tinker (escolher/buscar/refinar/desconhecido/comparar/escrever/congelar); devolve a contagem
function Step($cmd) {
    & $adb shell rm -f /data/data/$app/files/missao.txt
    & $adb shell am start -f 0x10008000 -n "$app/.MainActivity" --es missao "'$cmd'" | Out-Null
    for ($k = 0; $k -lt 120; $k++) {
        Start-Sleep -Milliseconds 500
        $t = & $adb shell cat /data/data/$app/files/missao.txt 2>$null
        if ($LASTEXITCODE -eq 0 -and $t) {
            $t = $t -join "`n"
            if ($t -like "erro*") { throw "Tinker: $t" }
            if ($t -match "count (\d+)") { return [long]$Matches[1] } else { return 0 }
        }
    }
    throw "o Tinker nao respondeu ao passo '$cmd'"
}
# busca, muda no jogo, refina ate sobrar 1 (ou 6 rodadas); devolve [contagem final, refinos, primeira contagem]
function Narrow($type, $label, $button, $fmt) {
    $v = Val $label
    $first = Step "buscar $type $(& $fmt $v)"
    $c = $first; $n = 0
    while ($c -gt 1 -and $n -lt 6) { Tap $button; $v = Val $label; $c = Step "refinar $(& $fmt $v)"; $n++ }
    @($c, $n, $first)
}
function Mission($name, [scriptblock]$body) {
    $t0 = Get-Date
    try { $r = & $body; $script:mres += "OK $name`: $r ($([int]((Get-Date) - $t0).TotalSeconds) s)" }
    catch { $script:mres += "FALHA $name`: $($_.Exception.Message) ($([int]((Get-Date) - $t0).TotalSeconds) s)" }
    Say ($script:mres[-1])
}

# menu flutuante desligado nas missoes: a bolinha/painel cobriria os botoes do jogo
function Set-NoOverlay {
    & $adb shell am force-stop $app
    $f = "/data/data/$app/shared_prefs/tinker.xml"
    $xml = (& $adb shell cat $f 2>$null) -join "`n"
    if (-not $xml.Contains("</map>")) { return }
    $xml = $xml -replace '\s*<boolean name="autoOverlay" value="[a-z]+"\s*/>', ''
    $xml = $xml.Replace("</map>", "    <boolean name=`"autoOverlay`" value=`"false`" />`n</map>")
    $tmp = "$env:TEMP\tinker-prefs.xml"
    [IO.File]::WriteAllText($tmp, $xml, (New-Object Text.UTF8Encoding $false))
    & $adb push $tmp /data/local/tmp/tinker-prefs.xml | Out-Null
    & $adb shell "cat /data/local/tmp/tinker-prefs.xml > $f; rm /data/local/tmp/tinker-prefs.xml"
}

function Run-Missions($v) {
    $script:mres = @()
    Set-NoOverlay
    & $adb shell am force-stop $game
    & $adb shell pm clear $game | Out-Null  # valores novos sorteados a cada rodada
    Front-Game
    Start-Sleep 2
    Step "escolher" | Out-Null
    $int = { param($x) [long]$x }
    Mission "ouro (Dword com 6 copias iguais)" {
        $r = Narrow "i32" "Ouro" "+1 ouro" $int
        Step "escrever 7777" | Out-Null; Start-Sleep 1
        if ((Val "Ouro") -ne 7777) { throw "editou $($r[0]) enderecos mas a tela nao mudou" }
        "achou $($r[2]), sobraram $($r[0]) em $($r[1]) refinos; a tela mudou"
    }
    Mission "gemas (cifrado, chave nova a cada mudanca)" {
        $r = Narrow "i32" "Gemas" "+1 gema" $int
        if ($r[0] -lt 1) { throw "nao achou (comecou com $($r[2]))" }
        Step "escrever 55" | Out-Null; Start-Sleep 1
        if ((Val "Gemas") -ne 55) { throw "sobraram $($r[0]) mas a tela nao mudou" }
        "sobraram $($r[0]) em $($r[1]) refinos"
    }
    Mission "vida (Float com decimal)" {
        $r = Narrow "f32" "Vida" "-10 vida" { param($x) ([double]$x).ToString([Globalization.CultureInfo]::InvariantCulture) }
        Step "escrever 999" | Out-Null; Start-Sleep 1
        if ((Val "Vida") -ne 999) { throw "sobraram $($r[0]) mas a tela nao mudou" }
        "sobraram $($r[0]) em $($r[1]) refinos"
    }
    Mission "pontos (guardados em dobro, Auto)" {
        $r = Narrow "auto" "Pontos" "+5 pontos" $int
        if ($r[0] -lt 1) { throw "Auto nao achou valor em dobro" }
        Step "escrever 4321" | Out-Null; Start-Sleep 1
        if ((Val "Pontos") -ne 4321) { throw "sobraram $($r[0]) mas a tela nao mudou" }
        "sobraram $($r[0]) em $($r[1]) refinos"
    }
    Mission "diamantes (64 bits, Auto)" {
        $r = Narrow "auto" "Diamantes" "+1 diamante" $int
        Step "escrever 6000000000" | Out-Null; Start-Sleep 1
        if ((Val "Diamantes") -ne 6000000000) { throw "sobraram $($r[0]) mas a tela nao mudou" }
        "sobraram $($r[0]) em $($r[1]) refinos"
    }
    Mission "madeira (mostrada inteira, guardada Float, Auto)" {
        $r = Narrow "auto" "Madeira" "+1 madeira" $int
        Step "escrever 500" | Out-Null; Start-Sleep 1
        if ((Val "Madeira") -ne 500) { throw "sobraram $($r[0]) mas a tela nao mudou" }
        "sobraram $($r[0]) em $($r[1]) refinos"
    }
    Mission "pocoes (numero pequeno num inventario cheio)" {
        $r = Narrow "i32" "Poções" "Achar poção" $int
        Step "escrever 9" | Out-Null; Start-Sleep 1
        if ((Val "Poções") -ne 9) { throw "sobraram $($r[0]) mas a tela nao mudou" }
        "comecou com $($r[2]), sobraram $($r[0]) em $($r[1]) refinos"
    }
    Mission "energia (muda sozinha; faixa + congelar)" {
        $e = Val "Energia"; $c = Step "buscar i32 $e~$($e + 3)"; $n = 0
        while ($c -gt 1 -and $n -lt 6) { Start-Sleep 2; $e = Val "Energia"; $c = Step "refinar $e~$($e + 3)"; $n++ }
        Step "escrever 10" | Out-Null; Step "congelar" | Out-Null; Start-Sleep 4
        $e = Val "Energia"
        if ($e -gt 11) { throw "congelou mas a energia foi a $e" }
        "sobraram $c em $n refinos; congelada em $e"
    }
    Mission "xp (escondido: valor desconhecido)" {
        $c = Step "desconhecido i32"; $n = 0
        while ($c -gt 1 -and $n -lt 8) {
            if ($n % 2) { Start-Sleep 1; $c = Step "comparar same" } else { Tap "+1 ouro"; $c = Step "comparar inc" }
            $n++
        }
        if ($c -lt 1) { throw "nenhum candidato sobrou" }
        "sobraram $c em $n comparacoes"
    }
    Mission "jogo atualizado (campos mudam de lugar)" {
        Tap "Atualizar jogo"
        $r = Narrow "i32" "Ouro" "+1 ouro" $int
        Step "escrever 8888" | Out-Null; Start-Sleep 1
        if ((Val "Ouro") -ne 8888) { throw "na versao nova a tela nao mudou" }
        "achou de novo: sobraram $($r[0]) em $($r[1]) refinos"
    }
    $ok = ($script:mres | Where-Object { $_ -like "OK*" }).Count
    $fails = ($script:mres | Where-Object { $_ -like "FALHA*" }) -join "`n"
    Post-Sheet "agente PC: missoes $ok de $($script:mres.Count) ok" $fails ($script:mres -join "`n") $v
}

function Test-Version {
    $rel = Invoke-RestMethod "https://api.github.com/repos/Sedricko/Game-Guardian/releases/latest" -Headers (Hdr)
    $v = $rel.tag_name.TrimStart("v")
    if ((Get-Content $done -ErrorAction SilentlyContinue) -eq $v) {
        if ((Get-Date).Minute % 10 -lt 2) { Say "sem versao nova (ultima testada: $v)" }  # sinal de vida a cada ~10 min
        return
    }
    Say "=== Tinker $v ==="
    Get-Apk ($rel.assets | Where-Object name -like "Tinker-*.apk" | Select-Object -First 1) "$env:TEMP\Tinker.apk"
    # jogo de teste com tela (vem no mesmo release): fica instalado para os testes de tela
    $jt = $rel.assets | Where-Object name -eq "JogoTeste.apk" | Select-Object -First 1
    if ($jt) { Get-Apk $jt "$env:TEMP\JogoTeste.apk"; Say ("jogo de teste: " + ((& $adb install -r "$env:TEMP\JogoTeste.apk") -join " ")) }
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
    Set-PanelLink
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
    Send-Shots $v
    if ((& $adb shell pm list packages $game) -match $game) {
        try { Run-Missions $v } catch { Say "missoes pararam: $($_.Exception.Message)" }
    }
    Set-Content $done $v
}

if (-not (Test-Path $cfg)) {
    Write-Host "Cole o link do painel da planilha (o mesmo de Tinker > Ajustes > Link do painel) e Enter." -ForegroundColor Yellow
    Write-Host "Ele fica so neste PC, em $cfg. Enter vazio = sem planilha (resultado so no log)."
    Set-Content $cfg (Read-Host "Link")
}
if (-not (Test-Path $tok) -and -not ((Get-Command gh -ErrorAction SilentlyContinue) -and (& gh auth token 2>$null))) {
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
