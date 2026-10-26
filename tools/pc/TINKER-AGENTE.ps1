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
    if (-not $asset.url -or -not (Hdr).ContainsKey("Authorization")) { Invoke-WebRequest $asset.browser_download_url -OutFile $out -UseBasicParsing; return }
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
            # script novo com erro de sintaxe derrubaria o agente ao reiniciar: fica com o de agora e avisa
            $errs = $null
            [void][System.Management.Automation.Language.Parser]::ParseInput((Norm $new), [ref]$null, [ref]$errs)
            if ($errs) { Say "versao nova do agente com erro (linha $($errs[0].Extent.StartLineNumber): $($errs[0].Message)); continuo com a atual"; return }
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

# so decide sobre o Magisk com o Android ligado de verdade: logo depois do "adb root" o pm ainda diz "Can't find service: package"
function Wait-Booted {
    for ($i = 0; $i -lt 36; $i++) {
        if (((& $adb shell getprop sys.boot_completed 2>$null) -match "1") -and ((& $adb shell pm list packages android 2>$null) -match "package:android")) { return $true }
        Start-Sleep 5
    }
    Say "o emulador ainda nao terminou de ligar (3 min): espero a proxima volta antes de mexer no Magisk"
    return $false
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
    $w = $(if ($img.Width -gt $img.Height) { 800 } else { 450 }); $h = [int]($img.Height * $w / $img.Width)
    $bmp = New-Object Drawing.Bitmap $img, $w, $h
    $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq "image/jpeg"
    $p = New-Object Drawing.Imaging.EncoderParameters 1
    $p.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality, [long]50)
    $bmp.Save($jpg, $codec, $p); $bmp.Dispose(); $img.Dispose()
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($jpg))
}

# print de cada aba do Tinker, em pe e deitado: para revisar o layout de tudo
function Send-Shots($v) {
    try {
        & $adb shell settings put system accelerometer_rotation 0
        foreach ($rot in @(@(0, "em pe"), @(1, "deitado"))) {
            & $adb shell settings put system user_rotation $rot[0]
            Start-Sleep 3
            foreach ($t in 0..4) {
                $tab = @("Busca", "Salvos", "Memoria", "Registro", "Ajustes")[$t]
                Step "aba $t" | Out-Null
                Start-Sleep 3
                Post-Sheet "agente PC: print $tab $($rot[1])" "" ("jpeg base64`n" + (Shot "$t-$($rot[0])")) $v
            }
        }
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
# o jogo grava o que a tela mostra em files/estado.txt (o leitor de tela do Android trava com a tela mudando)
function Val($label) {
    $t = (& $adb shell cat /data/data/$game/files/estado.txt 2>$null) -join "`n"
    if ($t -match "$label`: (-?[\d.]+)") { [double]$Matches[1] } else { throw "nao li '$label' na tela do jogo" }
}
function Front-Game { & $adb shell am start -n "$game/.GameActivity" 2>&1 | Out-Null; Start-Sleep 1 }  # sem o aviso "brought to the front"
# "tocar" no botao: o jogo recebe o mesmo comando do botao (com a tela do jogo na frente, como uma pessoa)
function Tap($text) {
    Front-Game
    & $adb shell am broadcast -p $game -a dev.tinker.testgame.ACAO --es acao "'$text'" | Out-Null
    Start-Sleep -Milliseconds 700
}
# um passo do Tinker (escolher/buscar/refinar/desconhecido/comparar/escrever/congelar); devolve a contagem
function Step($cmd) {
    & $adb shell rm -f /data/data/$app/files/missao.txt
    & $adb shell am start -f 0x10008000 -n "$app/.MainActivity" --es missao "'$cmd'" | Out-Null
    for ($k = 0; $k -lt 360; $k++) {
        Start-Sleep -Milliseconds 500
        $t = & $adb shell cat /data/data/$app/files/missao.txt 2>$null
        if ($LASTEXITCODE -eq 0 -and $t) {
            $t = $t -join "`n"
            $script:last = $t
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
# uma missao que falha tenta de novo uma vez (o emulador as vezes trava segundos); as duas vao para o relatorio
function Mission($name, [scriptblock]$body) {
    $t0 = Get-Date
    try { $r = & $body; $script:mres += "OK $name`: $r ($([int]((Get-Date) - $t0).TotalSeconds) s)" }
    catch {
        $e1 = $_.Exception.Message
        Say "  falhou ($e1), tentando de novo"
        $died = -not (& $adb shell pidof $app)
        try {
            Front-Game; Start-Sleep 2
            Step "escolher" | Out-Null
            $r = & $body
            $script:mres += "OK $name`: $r (na 2a tentativa; 1a: $e1$(if ($died) { '; o Tinker tinha fechado' })) ($([int]((Get-Date) - $t0).TotalSeconds) s)"
        } catch { $script:mres += "FALHA $name`: $($_.Exception.Message) (1a: $e1) ($([int]((Get-Date) - $t0).TotalSeconds) s)" }
    }
    Say ($script:mres[-1])
}

# emulador sobrecarregado derruba as missoes: mede quanto um comando simples demora e espera o PC aliviar (ate 5 min)
function Wait-Responsive {
    for ($k = 0; $k -lt 10; $k++) {
        $ms = (Measure-Command { & $adb shell "dumpsys window displays > /dev/null" }).TotalMilliseconds
        if ($ms -lt 1500) { return "emulador respondendo em $([int]$ms) ms" }
        Say "emulador lento ($([int]$ms) ms para um comando simples); esperando 30 s. Feche programas pesados no PC."
        Start-Sleep 30
    }
    "emulador lento ($([int]$ms) ms) mesmo depois de 5 min"
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
    $health = Wait-Responsive
    Say $health
    & $adb logcat -b crash -c
    Set-NoOverlay
    & $adb shell am force-stop $game
    & $adb shell pm clear $game | Out-Null  # valores novos sorteados a cada rodada
    Front-Game
    Start-Sleep 2
    Step "escolher" | Out-Null
    Step "limpar" | Out-Null
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
        Step "escrever 10" | Out-Null; Step "congelar 10" | Out-Null; Start-Sleep 4
        $e = Val "Energia"
        if ($e -gt 11) { throw "congelou mas a energia foi a $e" }
        "sobraram $c em $n refinos; congelada em $e"
    }
    $inv = [Globalization.CultureInfo]::InvariantCulture
    Mission "tempo (Float que corre 10x por segundo)" {
        $t = Val "Tempo"; $c = Step "buscar f32 $(($t).ToString($inv))~$(($t + 3).ToString($inv))"; $n = 0
        while ($c -gt 1 -and $n -lt 6) { Start-Sleep 1; $t = Val "Tempo"; $c = Step "refinar $(($t).ToString($inv))~$(($t + 3).ToString($inv))"; $n++ }
        if ($c -lt 1) { throw "nao sobrou nenhum" }
        Step "escrever 5" | Out-Null; Step "congelar 5" | Out-Null; Start-Sleep 3
        $t = Val "Tempo"
        if ($t -gt 6) { throw "sobraram $c em $n refinos, mas congelado o tempo foi a $t" }
        "sobraram $c em $n refinos; congelado em $t"
    }
    Mission "abates (sobem rapido e irregular)" {
        $k = Val "Abates"; $c = Step "buscar i32 $k~$($k + 4)"; $n = 0
        while ($c -gt 1 -and $n -lt 6) { Start-Sleep 1; $k = Val "Abates"; $c = Step "refinar $k~$($k + 4)"; $n++ }
        if ($c -lt 1) { throw "nao sobrou nenhum" }
        Step "escrever 999" | Out-Null; Start-Sleep 1
        if ((Val "Abates") -lt 999) { throw "sobraram $c em $n refinos, mas a tela nao mudou" }
        "sobraram $c em $n refinos"
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
    Mission "fechar e reabrir o jogo (o salvo volta sozinho)" {
        $r = Narrow "i32" "Ouro" "+1 ouro" $int
        Step "salvar ouro" | Out-Null
        Start-Sleep 8  # o Tinker procura o ponteiro do salvo
        Tap "+1 ouro"  # grava o ouro no save do jogo, que volta igual ao reabrir
        & $adb shell am force-stop $game
        Start-Sleep 2; Front-Game; Start-Sleep 12  # o Tinker percebe o jogo novo e restaura os salvos
        # a assinatura roda a cada 15 s enquanto o jogo carrega: espera ate 45 s, como uma pessoa esperaria
        for ($w = 0; $w -lt 4; $w++) {
            Step "salvos" | Out-Null
            $line = ($script:last -split "`n") | Where-Object { $_ -like "salvo ouro *" } | Select-Object -First 1
            if ($line -and ($line -split " ")[3] -eq "voltou") { break }
            Start-Sleep 11
        }
        $why = ($script:last -split "`n") | Where-Object { ($_ -like "*voltou:*" -or $_ -like "*salvos:*") -and $_ -notlike "salvo *" } | Select-Object -Last 1
        if ($why) { $line = "$line | $why" }
        if (-not $line) { throw "o salvo sumiu da lista" }
        $f = $line -split " "
        if ($f[3] -ne "voltou") { throw "nao voltou sozinho depois de reabrir ($line)" }
        if ([double]$f[4] -ne (Val "Ouro")) { throw "voltou num endereco errado: le $($f[4]), a tela mostra $(Val 'Ouro')" }
        "voltou sozinho pelo caminho $($f[5..($f.Count - 1)] -join ' ')"
    }
    Mission "funcao pelo nome (lib nativa, sem IDA)" {
        Step "funcoes bonus_ouro" | Out-Null
        if ($script:last -notmatch "fn [0-9a-f]+ jogo_bonus_ouro") { throw "nao achou jogo_bonus_ouro ($($script:last -replace "`n", ' | '))" }
        Step "chama jogo_ganha_ouro" | Out-Null
        if ($script:last -notmatch "call [0-9a-f]+ jogo_bonus_ouro") { throw "nao viu que jogo_ganha_ouro chama jogo_bonus_ouro ($($script:last -replace "`n", ' | '))" }
        Step "fixar jogo_bonus_ouro 5" | Out-Null
        $o = Val "Ouro"; Tap "+1 ouro"; Start-Sleep 1
        $d = (Val "Ouro") - $o
        Step "despatch" | Out-Null
        if ($d -ne 5) { throw "com o patch o ouro subiu $d, esperava 5 ($($script:last -replace "`n", ' | '))" }
        $o = Val "Ouro"; Tap "+1 ouro"; Start-Sleep 1
        if (((Val "Ouro") - $o) -ne 1) { throw "desfez o patch mas o ouro nao voltou a subir de 1" }
        "achou pelo nome, viu a chamada, o patch deu +5 e desfazer voltou ao +1"
    }
    Mission "velocidade do jogo (timeScale do Unity)" {
        Step "limpar" | Out-Null  # solta o tempo congelado da missao do tempo
        $c = Step "velocidade"
        if ($c -lt 1) { throw "nao achou a velocidade ($($script:last -replace "`n", ' | '))" }
        Step "velocidade 3" | Out-Null
        $t0 = Val "Tempo"; Start-Sleep 4; $d = (Val "Tempo") - $t0
        Step "velocidade 1" | Out-Null; Start-Sleep 1
        if ((Val "Velocidade") -ne 1) { throw "a velocidade nao voltou ao normal" }
        if ($d -lt 8) { throw "em 3x o tempo andou so $d em 4 s (normal seria 4)" }
        "achou $c lugar(es); em 3x o tempo andou $([math]::Round($d, 1)) em 4 s"
    }
    Mission "achar sozinho lendo a tela (abates)" {
        $c = Step "sozinho Abates"  # pelo nome na tela: o numero muda rapido demais para digitar
        $msg = ($script:last -split "`n")[0]
        if ($c -lt 1 -or $c -gt 3 -or $msg -match "vi |Parou|Nenhum") { throw "sobraram $c ($msg)" }
        "sobraram $c so lendo a tela ($msg)"
    }
    Mission "unreal: objeto pelo nome (tabelas de nomes e objetos)" {
        $c = Step "unreal jogador"
        if ($script:last -notmatch "ueobj [0-9a-f]+ BP_Jogador_C Jogador") { throw "nao achou Jogador da classe BP_Jogador_C ($($script:last -replace "`n", ' | '))" }
        "achou $c objeto(s): $((($script:last -split "`n") | Select-Object -First 1))"
    }
    Mission "ficha unity: texto, Vector3 e quantos itens" {
        Step "ficha 987654" | Out-Null
        $f = $script:last -replace "`n", ' | '
        # so ASCII nos padroes: o acento do "Heroi" pode chegar trocado pelo adb
        foreach ($e in @('Jogo\.Heroi', 'nome .+ = "Her.{1,3}i"', 'pos .+ = \(1\.5, 2, -3\)', 'mochila .+ = 7 itens', 'ouro .+ = 987654')) {
            if ($script:last -notmatch $e) { throw "faltou /$e/ na ficha ($f)" }
        }
        "ficha certa: $f"
    }
    Mission "copiar ficha: os numeros de um Heroi para outro" {
        Step "copiarficha 987654 111111" | Out-Null
        if ($script:last -notmatch "destino 987654") { throw "o destino nao ficou com o ouro da origem ($($script:last -replace "`n", ' | '))" }
        (($script:last -split "`n") | Select-Object -First 1)
    }
    Mission "desbloquear: isUnlocked das armas (Unity)" {
        Step "desbloquear Jogo.Arma" | Out-Null
        if ($script:last -notmatch "campo isUnlocked" -or $script:last -notmatch "depois 1,1") { throw "nao desbloqueou as 2 armas ($($script:last -replace "`n", ' | '))" }
        (($script:last -split "`n") | Select-Object -First 1)
    }
    Mission "cronometro: terminar a construcao agora" {
        Tap "Construir"
        if ((Val "Obra") -lt 3600) { throw "a construcao nao comecou" }
        $c = Step "cronometros $(Val 'Obra')"  # com quanto falta, como a pessoa ve na tela
        if ($c -lt 1) { throw "nao achou o cronometro ($($script:last -replace "`n", ' | '))" }
        Step "terminar" | Out-Null; Start-Sleep 2
        $left = Val "Obra"
        if ($left -gt 1) { throw "terminou $c, mas faltam $left s na tela" }
        "achou $c; a construcao de 2 h terminou na hora"
    }
    Mission "quais funcoes rodam (sem tocar em nada)" {
        $c = Step "quaisrodam jogo_"
        if ($script:last -notmatch "roda jogo_tick \d+") { throw "nao viu o jogo_tick rodando ($($script:last -replace "`n", ' | '))" }
        if ($script:last -match "roda jogo_bonus_ouro") { throw "disse que o bonus rodou sem ninguem tocar no ouro" }
        "rodaram ${c}: $((($script:last -split "`n") | Where-Object { $_ -like 'roda *' }) -join ', ')"
    }
    Mission "loja: precos por 1 (busca em grupo)" {
        $c = Step "loja 150 320 75"
        if ($c -lt 1) { throw "nao achou os precos ($($script:last -split "`n" | Select-Object -First 1))" }
        Step "escrever 1" | Out-Null; Start-Sleep 1
        $o = Val "Ouro"; Tap "Comprar espada"; Start-Sleep 1
        $d = $o - (Val "Ouro")
        if ($d -ne 1) { throw "a espada custou $d (esperava 1)" }
        "achou $c; a espada saiu por 1"
    }
    Mission "disfarcado: nivel x10+7 e moedas com chave fixa" {
        # mudando de jeitos diferentes (+1 e depois +3): subir sempre igual deixa contadores passarem
        foreach ($p in @(@("Nivel", "+1 nível", "lin", "+3 níveis"), @("Moedas", "+1 moeda", "xk", "+5 moedas"))) {
            $a = Val $p[0]
            Step "desconhecido i32" | Out-Null
            Tap $p[1]; $b = Val $p[0]
            Step "comparar $($p[2]) $a;$b" | Out-Null
            Tap $p[3]; $c2 = Val $p[0]
            $c = Step "comparar $($p[2]) $b;$c2"
            # ainda muitos: mais mudancas, alternando o jeito (como uma pessoa faria)
            for ($k = 0; $c -gt 3 -and $k -lt 3; $k++) {
                Tap $p[(1, 3)[$k % 2]]; $c3 = Val $p[0]; $c = Step "comparar $($p[2]) $c2;$c3"; $c2 = $c3
            }
            if ($c -lt 1 -or $c -gt 3) { throw "$($p[0]): sobraram $c depois de 2 mudancas" }
        }
        "achou os dois (x10+7 e chave fixa) com 2 mudancas cada"
    }
    Mission "patch volta sozinho ao reabrir o jogo" {
        Step "fixar jogo_bonus_ouro 5" | Out-Null
        & $adb shell am force-stop $game
        Start-Sleep 2; Front-Game; Start-Sleep 10  # o Tinker escolhe o jogo de novo e liga os patches salvos
        $o = Val "Ouro"; Tap "+1 ouro"; Start-Sleep 1
        $d = (Val "Ouro") - $o
        Step "escolher" | Out-Null
        Step "despatch" | Out-Null
        if ($d -ne 5) { throw "depois de reabrir o ouro subiu $d (esperava 5: o patch nao voltou)" }
        $o = Val "Ouro"; Tap "+1 ouro"; Start-Sleep 1
        if (((Val "Ouro") - $o) -ne 1) { throw "desfez o patch mas o ouro nao voltou a subir de 1" }
        "o patch voltou sozinho pelo nome da funcao e desfez"
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
    # o Tinker ou o jogo fecharam durante as missoes: o Android guarda o motivo no buffer de crash
    $crash = (& $adb logcat -d -b crash) -join "`n"
    # so queda do Tinker ou do Jogo de Teste (o Magisk e outros apps do emulador tambem caem as vezes)
    if ($crash -match "dev\.tinker") { $fails += "`n=== fechou durante as missoes ===`n" + $crash.Substring(0, [Math]::Min($crash.Length, 3000)) }
    Post-Sheet "agente PC: missoes $ok de $($script:mres.Count) ok" $fails ("$health`n" + ($script:mres -join "`n")) $v
}

function Test-Version {
    try { $rel = Invoke-RestMethod "https://api.github.com/repos/Sedricko/Game-Guardian/releases/latest" -Headers (Hdr) }
    catch {
        # api.github.com fora (visto: DNS do PC nao resolvia so ele): a pagina do release em github.com diz a versao
        $page = "https://github.com/Sedricko/Game-Guardian/releases/latest"
        try { $loc = (Invoke-WebRequest $page -MaximumRedirection 0 -UseBasicParsing -ErrorAction SilentlyContinue).Headers.Location }
        catch { $loc = $_.Exception.Response.Headers["Location"] }
        if (-not ($loc -match "/tag/(v[^/]+)$")) { throw }
        $t = $Matches[1]; $d = "https://github.com/Sedricko/Game-Guardian/releases/download/$t"
        $rel = [pscustomobject]@{ tag_name = $t; assets = @(
            [pscustomobject]@{ name = "Tinker-$($t.TrimStart('v')).apk"; browser_download_url = "$d/Tinker-$($t.TrimStart('v')).apk"; url = $null },
            [pscustomobject]@{ name = "JogoTeste.apk"; browser_download_url = "$d/JogoTeste.apk"; url = $null }) }
    }
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
    if ($crash -match "dev\.tinker") { $fails += "`n=== app fechou ===`n" + $crash.Substring(0, [Math]::Min($crash.Length, 4000)) }
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
        if (-not (Wait-Booted)) { continue }
        if (-not ((& $adb shell pm list packages com.topjohnwu.magisk) -match "magisk")) { Install-Magisk; continue }
        Test-Version
    } catch { Say "erro: $($_.Exception.Message)" }
    Start-Sleep 30  # versão nova é testada em até 30 s depois de publicada
}
