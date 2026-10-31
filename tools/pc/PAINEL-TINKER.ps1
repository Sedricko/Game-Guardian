# Painel Tinker: uma janela (pagina local, so neste PC) para acompanhar e controlar o agente do Orecraft. Mostra a rodada
# ao vivo, a tela do emulador, o roteiro (quais testes rodam), cenarios prontos (missoes do Tinker), os logs e manda recado
# para o Claude pela planilha. Conversa com o agente pelos arquivos tinker-agente-* da pasta do usuario.
param([switch]$SemJanela)  # reiniciado pela atualizacao: a janela do painel ja esta aberta
$ErrorActionPreference = "Continue"
$PAINEL_VERSAO = "1.7"  # suba a cada mudanca no painel (aqui ou no painel-tinker.html): e o que a pessoa ve no topo
$ProgressPreference = "SilentlyContinue"
$dir = $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$app = "dev.tinker.app"
$jogo = "com.evrika.miner.camp"
$U = $env:USERPROFILE
$log = "$U\tinker-agente-log.txt"
$cfg = "$U\tinker-agente-painel.txt"     # link da planilha (o mesmo do agente)
$tok = "$U\tinker-agente-github.txt"     # token do GitHub (so leitura), opcional
$roteiroF = "$U\tinker-agente-roteiro.txt"
$pausaF = "$U\tinker-agente-pausa.txt"
$estadoF = "$U\tinker-agente-estado.json"
$mantidosF = "$U\tinker-mantidos-$jogo.txt"
$meusF = "$U\tinker-agente-meus-roteiros.json"
$ajustesF = "$U\tinker-agente-ajustes.json"
$port = 8766
$tokenF = "$dir\painel-token.txt"  # o PainelTinker.exe aberto de novo reabre a janela do painel que ja esta rodando
# o mesmo token entre um painel e o atualizado: a janela aberta segue funcionando depois da atualizacao
$token = (Get-Content $tokenF -ErrorAction SilentlyContinue | Select-Object -First 1)
if (-not $token) { $token = [guid]::NewGuid().ToString("N").Substring(0, 16) }  # so a janela aberta por este painel fala com ele
$raw = "https://raw.githubusercontent.com/Sedricko/Game-Guardian/ccr-92c99132-2ne91y/tools/pc"
$script:job = $null
$script:ultimo = ""

# o painel responde uma coisa por vez: o que e lento (adb, lista de processos do Windows) fica guardado uns segundos
$script:cache = @{}
function Lembrar($k, $secs, [scriptblock]$f) {
    $c = $script:cache[$k]
    if ($c -and ((Get-Date) - $c.t).TotalSeconds -lt $secs) { return $c.v }
    $v = & $f; $script:cache[$k] = @{ t = Get-Date; v = $v }; $v
}
function Serial { Lembrar "serial" 10 { SerialAgora } }
function SerialAgora {
    $ds = @((& $adb devices) | Select-String "^(emulator-\d+)\s+device" | ForEach-Object { $_.Matches.Groups[1].Value })
    foreach ($d in $ds) { if (((& $adb -s $d emu avd name) | Select-Object -First 1) -like "*Dysmantle_Play*") { return $d } }
    $ds | Select-Object -First 1
}

function Agentes { @(Lembrar "agentes" 5 { @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like "*TINKER-AGENTE.ps1*" }) }) }

# tela ao vivo: um job tira prints sem parar (~2 por segundo) enquanto alguem olha; o painel so entrega o ultimo
$telaF = "$env:TEMP\painel-tela.jpg"; $olhoF = "$env:TEMP\painel-tela-olho.txt"
$telaJob = {
    param($adb, $ser, $telaF, $olhoF)
    Add-Type -AssemblyName System.Drawing
    $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq "image/jpeg"
    $p = New-Object Drawing.Imaging.EncoderParameters 1
    $p.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality, [long]55)
    while (((Get-Date) - (Get-Item $olhoF).LastWriteTime).TotalSeconds -lt 15) {  # ninguem olhando ha 15 s: para
        $png = "$telaF.png"
        cmd /c "`"$adb`" -s $ser exec-out screencap -p > `"$png`""
        try {
            $img = [Drawing.Image]::FromFile($png)
            $w = $(if ($img.Width -gt $img.Height) { 900 } else { 420 }); $h = [int]($img.Height * $w / $img.Width)
            $bmp = New-Object Drawing.Bitmap $img, $w, $h
            $bmp.Save("$telaF.tmp", $codec, $p); $bmp.Dispose(); $img.Dispose()
            Move-Item -Force "$telaF.tmp" $telaF
        } catch { Start-Sleep -Milliseconds 500 }
    }
}
function Tela {
    Set-Content $olhoF "olhando"
    if (-not $script:telaJ -or $script:telaJ.State -ne "Running") {
        if ($script:telaJ) { Remove-Job $script:telaJ -Force -ErrorAction SilentlyContinue }
        $ser = Serial
        if ($ser) { $script:telaJ = Start-Job -ScriptBlock $telaJob -ArgumentList $adb, $ser, $telaF, $olhoF }
    }
    if (Test-Path $telaF) { try { return [IO.File]::ReadAllBytes($telaF) } catch { } }
    $null
}

function Foto($ser) {
    $png = "$env:TEMP\painel-tinker.png"; $jpg = "$env:TEMP\painel-tinker.jpg"
    cmd /c "`"$adb`" -s $ser exec-out screencap -p > `"$png`""
    Add-Type -AssemblyName System.Drawing
    $img = [Drawing.Image]::FromFile($png)
    $w = $(if ($img.Width -gt $img.Height) { 900 } else { 480 }); $h = [int]($img.Height * $w / $img.Width)
    $bmp = New-Object Drawing.Bitmap $img, $w, $h
    $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq "image/jpeg"
    $p = New-Object Drawing.Imaging.EncoderParameters 1
    $p.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality, [long]60)
    $bmp.Save($jpg, $codec, $p); $bmp.Dispose(); $img.Dispose()
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($jpg))
}

# missao do Tinker em segundo plano (algumas levam minutos): a janela segue respondendo
$missao = {
    param($adb, $ser, $app, $jogo, $cmds, $secs)
    [Console]::OutputEncoding = [Text.Encoding]::UTF8  # a resposta do Tinker vem em UTF-8 (sem isto "voce" virava "vocÛ")
    function Sh($c) {
        if (((& $adb -s $ser shell id -u) -join "").Trim() -eq "0") { & $adb -s $ser shell $c } else { & $adb -s $ser shell "su -c '$($c -replace "'", "'\''")'" }
    }
    $out = @()
    foreach ($cmd in $cmds) {
        if ($cmd -match '^esperar (\d+)$') { Start-Sleep ([int]$Matches[1]); continue }
        if (-not "$cmd".Trim() -or "$cmd".StartsWith("#")) { continue }
        Sh "rm -f /data/data/$app/files/missao.txt" | Out-Null
        & $adb -s $ser shell am start -f 0x10008000 -n "$app/.MainActivity" --es missao "'$cmd'" | Out-Null
        $t = $null
        for ($k = 0; $k -lt $secs * 2; $k++) {
            Start-Sleep -Milliseconds 500
            $t = Sh "cat /data/data/$app/files/missao.txt" 2>$null
            if ($LASTEXITCODE -eq 0 -and $t) { break }
        }
        $out += "> $cmd`n" + $(if ($t) { $t -join "`n" } else { "o Tinker nao respondeu em $secs s" })
    }
    & $adb -s $ser shell monkey -p $jogo -c android.intent.category.LAUNCHER 1 | Out-Null  # volta para o jogo
    $out -join "`n`n"
}

# emulador pelo painel: ligar, desligar, root (Magisk pelo rootAVD), Tinker novo, abrir o jogo. Roda em segundo plano
$emuOp = {
    param($op, $sdk, $app, $jogo, $tok, $arg)
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    $adb = "$sdk\platform-tools\adb.exe"; $emu = "$sdk\emulator\emulator.exe"
    # sdkmanager/avdmanager (Android SDK Command-line Tools) precisam de Java: o do Android Studio serve
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:ProgramFiles\Android\Android Studio\jre") | Where-Object { Test-Path $_ } | Select-Object -First 1 }
    $tools = @("$sdk\cmdline-tools\latest\bin") + @(Get-ChildItem "$sdk\cmdline-tools" -Directory -ErrorAction SilentlyContinue | ForEach-Object { "$($_.FullName)\bin" }) | Where-Object { Test-Path "$_\sdkmanager.bat" } | Select-Object -First 1
    function Sdk() { if (-not $tools) { throw "falta o 'Android SDK Command-line Tools': no Android Studio, More Actions > SDK Manager > SDK Tools, marque e toque em OK" }; & "$tools\sdkmanager.bat" @args }
    $ser = @((& $adb devices) | Select-String "^(emulator-\d+)\s+device" | ForEach-Object { $_.Matches.Groups[1].Value }) | Select-Object -First 1
    function Su($c) { & $adb -s $ser shell "su -c '$($c -replace "'", "'\''")'" }
    # o ramdisk que o rootAVD corrige e o da imagem do emulador (config.ini do AVD)
    function Ramdisk {
        $avd = ((& $adb -s $ser emu avd name) | Select-Object -First 1).Trim()
        $ini = "$env:USERPROFILE\.android\avd\$avd.avd\config.ini"
        $d = ((Get-Content $ini -ErrorAction SilentlyContinue) | Where-Object { $_ -like "image.sysdir.1*" } | Select-Object -First 1) -replace "^[^=]*=\s*", ""
        $(if ($d) { ($d.TrimEnd("\", "/") -replace "/", "\") + "\ramdisk.img" } else { "system-images\android-36\google_apis_playstore\x86_64\ramdisk.img" })
    }
    function Op($op, $arg) {
    switch ($op) {
        "ligar" {
            if ($ser) { return "o emulador ja esta ligado ($ser)" }
            $avd = (& $emu -list-avds) | Where-Object { $_ -like "*Dysmantle_Play*" } | Select-Object -First 1
            if (-not $avd) { return "nao achei o emulador Dysmantle_Play no Android Studio" }
            # DNS fixo (sem ele o emulador nao resolvia nomes) e a placa do PC (o Orecraft precisa)
            Start-Process $emu -ArgumentList "-avd $avd -no-snapshot-load -gpu host -dns-server 8.8.8.8,1.1.1.1"
            & $adb wait-for-device
            for ($i = 0; $i -lt 60; $i++) { if ((& $adb shell getprop sys.boot_completed 2>$null) -match "1") { return "emulador $avd ligado" }; Start-Sleep 5 }
            return "o emulador abriu mas o Android ainda nao terminou de ligar (5 min)"
        }
        "desligar" { if (-not $ser) { return "ja estava desligado" }; & $adb -s $ser emu kill | Out-Null; return "emulador desligado" }
        "reiniciar" { if (-not $ser) { return "emulador desligado" }; & $adb -s $ser reboot; & $adb -s $ser wait-for-device; return "reiniciando: em 1-2 min liga de novo" }
        "root" {
            if (-not $ser) { return "ligue o emulador antes" }
            if ((Su "id") -match "uid=0") { return "o emulador ja tem root (Magisk funcionando)" }
            $dir = "C:\rootAVD"
            if (-not (Test-Path "$dir\rootAVD-master")) {
                Invoke-WebRequest "https://gitlab.com/newbit/rootAVD/-/archive/master/rootAVD-master.zip" -OutFile "$env:TEMP\rootAVD.zip" -UseBasicParsing
                Expand-Archive "$env:TEMP\rootAVD.zip" $dir -Force
            }
            $bat = Get-ChildItem "$dir\rootAVD-master" -Filter "rootAVD.bat" | Select-Object -First 1
            if (-not $bat) { return "o rootAVD baixado nao tem rootAVD.bat" }
            # janela visivel: o rootAVD pergunta a versao do Magisk (responder 2 e Enter); no fim o emulador desliga sozinho
            Start-Process cmd -ArgumentList "/k cd /d `"$($bat.DirectoryName)`" && rootAVD.bat $(Ramdisk)"
            return "abri a janela do root: se perguntar a versao do Magisk, digite 2 e Enter. No fim o emulador desliga; ligue de novo aqui e toque em Permitir no pedido de Superusuario"
        }
        "tinker" {
            if (-not $ser) { return "ligue o emulador antes" }
            $h = @{ "User-Agent" = "Tinker" }
            $t = (Get-Content $tok -ErrorAction SilentlyContinue | Select-Object -First 1); if ($t -and $t.Trim()) { $h["Authorization"] = "Bearer $($t.Trim())" }
            $rel = Invoke-RestMethod "https://api.github.com/repos/Sedricko/Game-Guardian/releases/latest" -Headers $h
            $a = $rel.assets | Where-Object name -like "Tinker-*.apk" | Select-Object -First 1
            $apk = "$env:TEMP\Tinker-painel.apk"
            if ($h.ContainsKey("Authorization") -and $a.url) {
                $r = Invoke-WebRequest $a.url -Headers ($h + @{ "Accept" = "application/octet-stream" }) -UseBasicParsing; [IO.File]::WriteAllBytes($apk, $r.Content)
            } else { Invoke-WebRequest $a.browser_download_url -OutFile $apk -UseBasicParsing }
            $inst = (& $adb -s $ser install -r $apk) -join " "
            & $adb -s $ser shell appops set $app SYSTEM_ALERT_WINDOW allow | Out-Null
            & $adb -s $ser shell pm grant $app android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
            $uid = ((& $adb -s $ser shell dumpsys package $app) | Select-String "(?:userId|appId)=(\d+)" | Select-Object -First 1).Matches.Groups[1].Value
            if ($uid) { Su "magisk --sqlite 'REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,1)'" 2>$null | Out-Null }
            return "Tinker $($rel.tag_name): $inst (root para o Tinker liberado no Magisk)"
        }
        # imagens do Android para o emulador, com o que serve para o Tinker + Orecraft (o Orecraft e arm64: precisa da traducao
        # ARM das imagens x86_64 do Google, API 30+; Play Store para instalar o jogo; Magisk pelo rootAVD)
        "imagens" {
            $inst = $true; $vistos = @{}; $out = @()
            foreach ($l in (Sdk --list 2>$null)) {
                if ($l -match "Available Packages") { $inst = $false }
                if ($l -notmatch "^\s*system-images;android-(\d+)(?:-ext\d+)?;([^;|\s]+);([^\s|]+)") { continue }
                $id = ($l.Trim() -split "\s*\|\s*")[0]
                if ($vistos[$id]) { continue }; $vistos[$id] = 1
                $api = [int]$Matches[1]; $tag = $Matches[2]; $abi = $Matches[3]
                if ($api -lt 28 -or $tag -notmatch "^google_apis") { continue }
                $nivel = "ruim"; $por = ""
                if ($abi -ne "x86_64") { $por = "$abi fica muito lento no PC" }
                elseif ($api -lt 30) { $por = "sem traducao ARM: o Orecraft (arm64) nao abre" }
                elseif ($tag -like "*ps16k*") { $nivel = "aviso"; $por = "paginas de 16 KB: o Tinker ainda nao foi testado assim" }
                elseif ($tag -eq "google_apis_playstore") { $nivel = $(if ($api -ge 33) { "otimo" } else { "bom" }); $por = "Play Store para instalar o Orecraft, root pelo Magisk" + $(if ($api -eq 36) { " (o mesmo do Dysmantle_Play de hoje)" } else { "" }) }
                elseif ($tag -eq "google_apis") { $nivel = "aviso"; $por = "sem Play Store: o Orecraft so por APK" }
                $out += [pscustomobject]@{ id = $id; api = $api; tag = $tag; abi = $abi; instalada = $inst; nivel = $nivel; por = $por }
            }
            return "IMAGENS:" + (@($out | Sort-Object @{ e = { @{ otimo = 0; bom = 1; aviso = 2; ruim = 3 }[$_.nivel] } }, @{ e = { $_.api }; Descending = $true }) | ConvertTo-Json -Compress)
        }
        "criar" {
            if ($arg -notmatch "^system-images;android-(\d+)[^;]*;google_apis[^;]*;x86_64$") { return "imagem invalida: $arg" }
            $api = $Matches[1]
            Write-Output "baixando a imagem $arg (pode levar varios minutos)..."
            "y`ny`ny`ny`ny`ny`n" | Sdk --install $arg "platform-tools" "emulator" | Out-Null
            $nome = $(if ((& $emu -list-avds) -contains "Dysmantle_Play") { "Dysmantle_Play_API$api" } else { "Dysmantle_Play" })  # o agente usa o *Dysmantle_Play*
            "no" | & "$tools\avdmanager.bat" create avd -n $nome -k $arg -d pixel_6_pro --force | Out-Null
            $ini = "$env:USERPROFILE\.android\avd\$nome.avd\config.ini"
            if (-not (Test-Path $ini)) { return "o avdmanager nao criou o $nome" }
            # o que o Orecraft e o Tinker precisam: memoria, espaco, placa de video do PC, teclado do PC
            $c = (Get-Content $ini) | Where-Object { $_ -notmatch "^(hw\.ramSize|disk\.dataPartition\.size|hw\.gpu\.enabled|hw\.gpu\.mode|hw\.keyboard|vm\.heapSize)\s*=" }
            $c += "hw.ramSize=4096", "vm.heapSize=512", "disk.dataPartition.size=16G", "hw.gpu.enabled=yes", "hw.gpu.mode=host", "hw.keyboard=yes"
            Set-Content $ini $c
            return "emulador $nome criado (API $api, 4 GB de RAM, 16 GB de espaco). Agora: Ligar, entrar na conta Google, instalar o Orecraft pela Play Store e Fazer o root"
        }
        # ajustes do Android para o bot: nao congelar o Tinker em segundo plano, tela sempre ligada, sem animacoes
        "configurar" {
            if (-not $ser) { return "ligue o emulador antes" }
            & $adb -s $ser shell settings put global cached_apps_freezer disabled
            & $adb -s $ser shell settings put global stay_on_while_plugged_in 7
            & $adb -s $ser shell settings put system screen_off_timeout 2147483647
            foreach ($k in "window_animation_scale", "transition_animation_scale", "animator_duration_scale") { & $adb -s $ser shell settings put global $k 0 }
            & $adb -s $ser shell cmd connectivity set-package-networking-enabled true $jogo 2>&1 | Out-Null
            return "configurado: o Tinker nao e congelado em segundo plano, a tela nao apaga, sem animacoes, internet do jogo ligada (o congelamento so vale depois de Reiniciar)"
        }
        "jogo" { if (-not $ser) { return "ligue o emulador antes" }; & $adb -s $ser shell monkey -p $jogo -c android.intent.category.LAUNCHER 1 | Out-Null; return "Orecraft aberto" }
        "abrirtinker" { if (-not $ser) { return "ligue o emulador antes" }; & $adb -s $ser shell am start -n "$app/.MainActivity" | Out-Null; return "Tinker aberto" }
        default { return "operacao desconhecida: $op" }
    }
    }
    if ($op -ne "tudo") { return (Op $op $arg) }

    # 🚀 Preparar tudo: cada toque segue de onde parou; o que so a pessoa pode fazer (conta Google, responder o rootAVD,
    # Permitir o root) para com o que fazer. No fim devolve PRONTO e o painel liga o bot
    $feito = @()
    if (-not ((& $emu -list-avds) -like "*Dysmantle_Play*")) {
        $feito += Op "criar" "system-images;android-36;google_apis_playstore;x86_64"
    }
    if (-not $ser) {
        $feito += Op "ligar"
        $ser = @((& $adb devices) | Select-String "^(emulator-\d+)\s+device" | ForEach-Object { $_.Matches.Groups[1].Value }) | Select-Object -First 1
        if (-not $ser) { return (($feito + "o emulador nao ligou: veja o Android Studio") -join "`n") }
    }
    $frz = ((& $adb -s $ser shell settings get global cached_apps_freezer) -join "") -match "disabled"
    $feito += Op "configurar"
    if (-not $frz) { $feito += Op "reiniciar"; & $adb -s $ser wait-for-device; for ($i = 0; $i -lt 60 -and -not ((& $adb -s $ser shell getprop sys.boot_completed 2>$null) -match "1"); $i++) { Start-Sleep 5 } }
    if (-not (((& $adb -s $ser shell pm list packages $jogo) -join "") -match [regex]::Escape($jogo))) {
        & $adb -s $ser shell am start -a android.intent.action.VIEW -d "market://details?id=$jogo" | Out-Null
        return (($feito + "FALTA VOCE: no emulador, entre na conta Google e toque em Instalar no Orecraft (abri a pagina dele na Play Store). Depois toque em Preparar tudo de novo.") -join "`n")
    }
    if (-not ((Su "id") -match "uid=0")) {
        if ((& $adb -s $ser shell "ls /debug_ramdisk/su /system/bin/su /sbin/su 2>/dev/null") -join "") {
            return (($feito + "FALTA VOCE: o Magisk esta instalado; toque em Permitir no pedido de Superusuario do emulador (ou abra o app Magisk uma vez) e toque em Preparar tudo de novo.") -join "`n")
        }
        $feito += Op "root"
        return (($feito + "FALTA VOCE: na janela do rootAVD digite 2 e Enter; quando o emulador desligar, toque em Preparar tudo de novo.") -join "`n")
    }
    # o link da planilha: sem ele no PC, pega o que o Tinker do emulador ja tem (Ajustes > Link do painel, keySource)
    $cfgF = "$env:USERPROFILE\tinker-agente-painel.txt"
    if (-not ((Get-Content $cfgF -ErrorAction SilentlyContinue) -join "").Trim()) {
        $xml = (Su "cat /data/data/$app/shared_prefs/tinker.xml") -join "`n"
        if ($xml -match '<string name="keySource">(https://script\.google\.com/[^<]+)</string>') {
            Set-Content $cfgF ([Net.WebUtility]::HtmlDecode($Matches[1])); $feito += "link da planilha copiado do Tinker do emulador"
        }
    }
    $feito += Op "tinker"
    $feito += Op "jogo"
    if (-not ((Get-Content $cfgF -ErrorAction SilentlyContinue) -join "").Trim()) {
        return (($feito + "FALTA VOCE: cole o link da planilha em Opcoes (Tinker > Ajustes > Link do painel) e toque em Preparar tudo de novo.") -join "`n")
    }
    "PRONTO`n" + ($feito -join "`n")
}

function Planilha($motivo, $texto) {
    $link = (Get-Content $cfg -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $link) { return "sem o link da planilha (rode o agente uma vez para ele pedir)" }
    $url = $link.Trim() + $(if ($link.Contains("?")) { "&" } else { "?" }) + "relatorio=1"
    $body = @{ jogo = "Orecraft (emulador)"; versao = "painel"; aparelho = "Painel Tinker (PC)"; motivo = $motivo; problemas = ""
               texto = $texto.Substring(0, [Math]::Min($texto.Length, 400000)) } | ConvertTo-Json -Compress
    Invoke-WebRequest $url -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json; charset=utf-8" -UseBasicParsing | Out-Null
    "enviado para a planilha: o Claude le na proxima passada"
}

function Estado { Lembrar "estado" 3 { EstadoAgora } }
function EstadoAgora {
    $ser = Serial
    $ag = Agentes
    $rod = $null
    if (Test-Path $estadoF) { try { $rod = Get-Content $estadoF -Raw -Encoding UTF8 | ConvertFrom-Json } catch { } }
    $jogoAberto = $false; $tinker = $false; $root = $false; $tinkerVer = ""; $jogoInstalado = $false
    if ($ser) {
        $jogoAberto = [bool]((& $adb -s $ser shell pidof $jogo) -join "")
        $tinker = [bool]((& $adb -s $ser shell pidof $app) -join "")
        # o root e a versao mudam pouco: confere a cada 30 s (o painel pergunta o estado a cada 4 s)
        if (-not $script:emuInfo -or ((Get-Date) - $script:emuInfo.quando).TotalSeconds -gt 30 -or $script:emuInfo.ser -ne $ser) {
            $script:emuInfo = @{ quando = Get-Date; ser = $ser
                root = (((& $adb -s $ser shell "su -c id" 2>&1) -join "") -match "uid=0")
                ver = ((& $adb -s $ser shell dumpsys package $app) | Select-String "versionName=(\S+)" | Select-Object -First 1).Matches.Groups[1].Value
                instalado = [bool](((& $adb -s $ser shell pm list packages $jogo) -join "") -match [regex]::Escape($jogo)) }
        }
        $root = $script:emuInfo.root; $tinkerVer = [string]$script:emuInfo.ver; $jogoInstalado = $script:emuInfo.instalado
    }
    if ($script:job -and $script:job.State -ne "Running") {
        $script:ultimo = (Receive-Job $script:job -ErrorAction SilentlyContinue) -join "`n"; Remove-Job $script:job -Force; $script:job = $null
        # Preparar tudo terminou: liga o bot (sem o link da planilha, avisa)
        if ($script:ultimo -like "PRONTO*") { $script:ultimo += "`n" + $(try { Acao @{ acao = "ligar" } } catch { $_.Exception.Message }) + "`nTudo pronto: o bot comeca os testes." }
    }
    @{
        agente = ($ag.Count -gt 0); pausado = (Test-Path $pausaF); emulador = [string]$ser; jogoAberto = $jogoAberto; tinker = $tinker
        root = $root; tinkerVer = $tinkerVer; jogoInstalado = $jogoInstalado
        # [string]: no PowerShell 5 a linha do Get-Content vira objeto no JSON (o painel mostrava [object Object])
        rodada = $rod; roteiro = @(Get-Content $roteiroF -ErrorAction SilentlyContinue | Where-Object { $_.Trim() } | ForEach-Object { [string]$_ })
        mantidos = @(Get-Content $mantidosF -Tail 15 -Encoding UTF8 -ErrorAction SilentlyContinue | ForEach-Object { [string]$_ })
        planilha = [bool]((Get-Content $cfg -ErrorAction SilentlyContinue) -join "").Trim(); token = [bool]((Get-Content $tok -ErrorAction SilentlyContinue) -join "").Trim()
        cenario = $(if ($script:job) { "rodando" } else { "" }); resultado = $script:ultimo
        # sem o arquivo ainda (antes de o bot rodar a 1a vez): vazio, sem a tela vermelha de erro a cada leitura
        meus = @(if (Test-Path $meusF) { try { Get-Content $meusF -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json } catch { } })
        ajustes = $(if (Test-Path $ajustesF) { try { Get-Content $ajustesF -Raw -ErrorAction Stop | ConvertFrom-Json } catch { $null } } else { $null })
        painelVersao = $PAINEL_VERSAO; conferido = $script:conferido; novaVersao = $script:novaVersao
        ver = [string](Get-Item "$dir\painel-tinker.html").LastWriteTimeUtc.Ticks; vigia = $script:vigia
    }
}

function Acao($a) {
    switch ($a.acao) {
        "ligar" {
            Remove-Item $pausaF -ErrorAction SilentlyContinue
            if ((Agentes).Count) { return "o agente ja esta ligado" }
            # tudo pelo painel: sem o link o agente perguntaria numa janela escondida
            if (-not ((Get-Content $cfg -ErrorAction SilentlyContinue) -join "").Trim()) { throw "falta o link da planilha: cole em Opcoes" }
            if (-not (Test-Path $tok)) { Set-Content $tok "" }
            Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$dir\TINKER-AGENTE.ps1`" -Jogo $jogo" -WindowStyle Hidden
            return "agente ligado (roda escondido; acompanhe aqui e em Logs)"
        }
        # parar marca a pausa: senao o vigia do painel religava o bot em 1 min
        "parar" { Set-Content $pausaF "parado pelo painel $(Get-Date)"; $n = 0; foreach ($p in Agentes) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; $n++ }; return "agente parado ($n); so volta quando tocar em Ligar o bot" }
        "pausar" { Set-Content $pausaF "pausado pelo painel $(Get-Date)"; return "pausa marcada: o agente termina a rodada de agora e espera" }
        "continuar" { Remove-Item $pausaF -ErrorAction SilentlyContinue; return "continuando: a proxima rodada comeca em ate 1 min" }
        "roteiro" {
            $ids = @($a.testes)
            if ($ids.Count) { Set-Content $roteiroF ($ids -join "`r`n") } else { Remove-Item $roteiroF -ErrorAction SilentlyContinue }
            return $(if ($ids.Count) { "roteiro salvo: $($ids -join ', ') (vale a partir do proximo teste)" } else { "roteiro: todos os testes" })
        }
        "foto" { $ser = Serial; if (-not $ser) { throw "emulador desligado" }; return (Foto $ser) }
        "reabrir" {
            $ser = Serial; if (-not $ser) { throw "emulador desligado" }
            & $adb -s $ser shell am force-stop $jogo; Start-Sleep 2
            & $adb -s $ser shell monkey -p $jogo -c android.intent.category.LAUNCHER 1 | Out-Null
            return "Orecraft reaberto"
        }
        "missao" {
            $ser = Serial; if (-not $ser) { throw "emulador desligado" }
            $rod = $null; if (Test-Path $estadoF) { try { $rod = Get-Content $estadoF -Raw | ConvertFrom-Json } catch { } }
            # o agente no meio de um teste usa o Tinker: missao junto atrapalharia os dois
            if ((Agentes).Count -and $rod -and $rod.atual) { throw "o agente esta no teste '$($rod.atual)': toque em Pausar e espere a rodada acabar" }
            if ($script:job) { throw "ja tem um cenario rodando" }
            $cmds = @("jogo $jogo") + @($a.cmds)
            $script:ultimo = ""
            $script:job = Start-Job -ScriptBlock $missao -ArgumentList $adb, $ser, $app, $jogo, $cmds, ([int]$a.secs)
            return "rodando: $($a.cmds -join ' ; ')"
        }
        "emu" {
            if ($script:job) { throw "ja tem uma tarefa rodando: espere ela acabar" }
            $script:ultimo = ""; $script:emuInfo = $null
            $script:job = Start-Job -ScriptBlock $emuOp -ArgumentList $a.op, $sdk, $app, $jogo, $tok, ([string]$a.arg)
            return "comecei: $($a.op) (o resultado aparece aqui embaixo)"
        }
        "config" {
            if ($a.link) { if ($a.link -notmatch '^https://script\.google\.com/') { throw "o link precisa comecar com https://script.google.com/" }; Set-Content $cfg $a.link.Trim() }
            if ($a.token) { Set-Content $tok $a.token.Trim() }
            return "guardado neste PC"
        }
        "meus" {
            # a lista inteira vem do painel; nome so com letras, numeros e espaco (vira o nome do teste na planilha)
            $l = @($a.lista | Where-Object { $_.nome } | ForEach-Object {
                [pscustomobject]@{ nome = ($_.nome -replace '[^\w ]', '').Trim(); ativo = [bool]$_.ativo; passos = @($_.passos | ForEach-Object { "$_" }) } })
            [IO.File]::WriteAllText($meusF, (ConvertTo-Json @($l) -Depth 4), (New-Object Text.UTF8Encoding $false))
            return "roteiros salvos: $($l.Count) ($(@($l | Where-Object ativo).Count) rodam em cada rodada do bot)"
        }
        "ajustes" {
            $f = [double]$a.folgaMin
            if ($f -lt 1 -or $f -gt 1440) { throw "a folga entre rodadas vai de 1 a 1440 minutos" }
            [IO.File]::WriteAllText($ajustesF, (@{ folgaMin = $f } | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
            return "ajustes guardados: $f min entre uma rodada e outra"
        }
        "sair" { $script:sair = $true; return "painel fechando (o bot continua)" }
        "claude" { return (Planilha "painel: recado para o Claude" ([string]$a.texto)) }
        default { throw "acao desconhecida: $($a.acao)" }
    }
}

# atualiza o painel pelo GitHub (como o agente faz): a pagina nova vale ao recarregar; o script novo reinicia o painel
function Atualizar {
    try {
        $html = (Invoke-WebRequest "$raw/painel-tinker.html" -UseBasicParsing -Headers @{ "Cache-Control" = "no-cache" }).Content
        if ($html -is [byte[]]) { $html = [Text.Encoding]::UTF8.GetString($html) }
        if ($html -and $html.Contains("__TOKEN__") -and $html -ne (Get-Content "$dir\painel-tinker.html" -Raw -Encoding UTF8)) {
            [IO.File]::WriteAllText("$dir\painel-tinker.html", $html, (New-Object Text.UTF8Encoding $false))
        }
        $ps = (Invoke-WebRequest "$raw/PAINEL-TINKER.ps1" -UseBasicParsing -Headers @{ "Cache-Control" = "no-cache" }).Content
        if ($ps -is [byte[]]) { $ps = [Text.Encoding]::UTF8.GetString($ps) }
        $ps = $ps.TrimStart([char]0xFEFF)
        $script:conferido = Get-Date -Format "HH:mm"
        $script:novaVersao = $(if ($ps -match '\$PAINEL_VERSAO = "([^"]+)"') { $Matches[1] } else { "" })
        if ($ps -and $ps.Contains("function Atualizar") -and $ps -ne (Get-Content $PSCommandPath -Raw -Encoding UTF8).TrimStart([char]0xFEFF)) {
            $e = $null; [void][System.Management.Automation.Language.Parser]::ParseInput($ps, [ref]$null, [ref]$e)
            if (-not $e) { [IO.File]::WriteAllText($PSCommandPath, $ps, (New-Object Text.UTF8Encoding $true)); $script:reiniciar = $true }
        }
    } catch { }
}

# vigia (a cada 5 s): sem pausa e com o link da planilha, o bot fica sempre ligado; se cair, volta em ate 1 min
function Vigiar {
    if ((Test-Path $pausaF) -or -not ((Get-Content $cfg -ErrorAction SilentlyContinue) -join "").Trim()) { return }
    if ((Agentes).Count) { return }
    if ($script:ultimoLigar -and ((Get-Date) - $script:ultimoLigar).TotalSeconds -lt 60) { return }
    $script:ultimoLigar = Get-Date
    try { [void](Acao @{ acao = "ligar" }); $script:vigia = "religuei o bot $(Get-Date -Format 'HH:mm:ss')" } catch { $script:vigia = "nao consegui ligar o bot: $($_.Exception.Message)" }
}

function SendBytes($res, $type, $b) {
    $res.StatusCode = 200; $res.ContentType = $type; $res.ContentLength64 = $b.Length
    $res.Headers["Cache-Control"] = "no-store"; $res.OutputStream.Write($b, 0, $b.Length); $res.OutputStream.Close()
}

function Send($res, $code, $type, $text) {
    $b = [Text.Encoding]::UTF8.GetBytes($text)
    $res.StatusCode = $code; $res.ContentType = "$type; charset=utf-8"; $res.ContentLength64 = $b.Length
    $res.OutputStream.Write($b, 0, $b.Length); $res.OutputStream.Close()
}

$h = New-Object Net.HttpListener
$h.Prefixes.Add("http://localhost:$port/")
$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
function Abrir($u) { if ($edge) { Start-Process $edge "--app=$u --window-size=1400,900" } else { Start-Process $u } }
try { $h.Start() } catch {
    # o painel ja esta rodando (escondido): so abre a janela dele de novo
    $old = (Get-Content $tokenF -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($old) { Abrir "http://localhost:$port/?t=$old" } else { Write-Host "A porta $port esta ocupada por outro programa." }
    exit
}
Set-Content $tokenF $token
$script:vigia = ""; $script:conferido = ""; $script:novaVersao = ""; $script:reiniciar = $false; $script:proximaAtualizacao = Get-Date
if (-not $SemJanela) { Abrir "http://localhost:$port/?t=$token" }
Write-Host "Painel Tinker aberto ($url). Deixe esta janela aberta; fechar ela fecha o painel." -ForegroundColor Cyan

$script:sair = $false
while ($h.IsListening -and -not $script:sair -and -not $script:reiniciar) {
    $ar = $h.BeginGetContext($null, $null)
    while (-not $ar.AsyncWaitHandle.WaitOne(5000)) {
        Vigiar
        if ((Get-Date) -ge $script:proximaAtualizacao) { $script:proximaAtualizacao = (Get-Date).AddMinutes(15); Atualizar }
        if ($script:reiniciar) { break }
    }
    if ($script:reiniciar) { break }
    $ctx = $h.EndGetContext($ar)
    $req = $ctx.Request; $res = $ctx.Response
    try {
        if ($req.QueryString["t"] -ne $token) { Send $res 403 "text/plain" "abra pelo atalho Painel Tinker"; continue }
        $body = ""
        if ($req.HasEntityBody) { $r = New-Object IO.StreamReader($req.InputStream, [Text.Encoding]::UTF8); $body = $r.ReadToEnd(); $r.Close() }
        switch ($req.Url.AbsolutePath) {
            "/" { Send $res 200 "text/html" ((Get-Content "$dir\painel-tinker.html" -Raw -Encoding UTF8).Replace("__TOKEN__", $token)) }
            "/api/tela" { $b = Tela; if ($b) { SendBytes $res "image/jpeg" $b } else { Send $res 404 "text/plain" "sem tela ainda" } }
            "/api/estado" { Send $res 200 "application/json" ((Estado) | ConvertTo-Json -Depth 6 -Compress) }
            "/api/log" {
                $n = [int]("0" + $req.QueryString["n"]) + 1
                $txt = switch ($req.QueryString["fonte"]) {
                    "tinker" { $ser = Serial; if ($ser) { (& $adb -s $ser shell "su -c 'tail -n $n /data/data/$app/files/tinker.log' 2>/dev/null || tail -n $n /data/data/$app/files/tinker.log") -join "`n" } else { "emulador desligado" } }
                    "falhas" { $ser = Serial; if ($ser) { (& $adb -s $ser logcat -b crash -d -t $n) -join "`n" } else { "emulador desligado" } }
                    default { (Get-Content $log -Tail $n -Encoding UTF8 -ErrorAction SilentlyContinue) -join "`n" }
                }
                Send $res 200 "text/plain" ([string]$txt)
            }
            "/api/acao" { Send $res 200 "application/json" (@{ ok = $true; msg = (Acao ($body | ConvertFrom-Json)) } | ConvertTo-Json -Compress) }
            default { Send $res 404 "text/plain" "nao achei" }
        }
    } catch {
        try { Send $res 200 "application/json" (@{ ok = $false; msg = $_.Exception.Message } | ConvertTo-Json -Compress) } catch { }
    }
}
# script novo baixado: solta a porta e abre o novo no lugar, sem abrir outra janela (a aberta segue: o token e o mesmo)
if ($script:reiniciar) {
    $h.Stop()
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`" -SemJanela" -WindowStyle Hidden
}
