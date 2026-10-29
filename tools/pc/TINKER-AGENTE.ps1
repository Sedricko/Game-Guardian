# Agente do Tinker no PC: liga o emulador, se atualiza sozinho, instala cada versao nova do Tinker no emulador,
# roda o autoteste (so o jogo de teste, nenhum jogo de verdade) e manda o resultado para a planilha (aba Relatorios).
# Fala so com o emulador (emulator-5554): o celular no cabo nao e tocado. Deixe esta janela aberta; Ctrl+C para sair.
# -Jogo <pacote> (TINKER-DYSMANTLE.bat, TINKER-ORECRAFT.bat): uma rodada num jogo de verdade no emulador, so nos offline
# que a pessoa liberou (lista fechada no app: MISSION_GAMES); -Dysmantle e o mesmo que -Jogo com.the10tons.dysmantle
param([switch]$Dysmantle, [string]$Jogo)
if ($Dysmantle) { $Jogo = "com.the10tons.dysmantle" }
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
# comando com root: no emulador de sempre o adb ja e root; no de Play Store (adbd sem root) vai pelo su do Magisk
function Sh($c) { if ($script:su) { & $adb shell "su -c '$($c -replace "'", "'\''")'" } else { & $adb shell $c } }
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
            if ($Jogo) { & powershell -NoProfile -ExecutionPolicy Bypass -File $self -Jogo $Jogo } else { & powershell -NoProfile -ExecutionPolicy Bypass -File $self }
            exit
        }
    } catch { Say "nao consegui ver se o agente tem versao nova: $($_.Exception.Message)" }
}

function Start-Emulator($like = "*Pixel_6_Pro*", $gpu = "swiftshader_indirect", $style = "Minimized") {
    if ((& $adb devices) -match "emulator-5554\s+device") { return }
    $avd = (& $emu -list-avds) | Where-Object { $_ -like $like } | Select-Object -First 1
    if (-not $avd) { throw "nao achei o emulador $like no Android Studio" }
    Say "ligando o emulador $avd..."
    # placa de video por software: com a do PC e a janela minimizada a tela do emulador parava 10-30 s por quadro
    # (logcat "dequeueBuffer failed"), o Tinker lia a tela velha e a curva da 2.79.2 deu 0 de 16
    Start-Process $emu -ArgumentList "-avd $avd -no-snapshot-load -gpu $gpu" -WindowStyle $style
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

function Install-Magisk($ramdisk = "system-images\android-31\google_apis\x86_64\ramdisk.img") {
    Say "o emulador ainda nao tem Magisk (root para apps): instalando com o rootAVD"
    $dir = "C:\rootAVD"
    if (-not (Test-Path "$dir\rootAVD-master")) {
        Invoke-WebRequest "https://gitlab.com/newbit/rootAVD/-/archive/master/rootAVD-master.zip" -OutFile "$env:TEMP\rootAVD.zip"
        Expand-Archive "$env:TEMP\rootAVD.zip" $dir -Force
    }
    $bat = Get-ChildItem "$dir\rootAVD-master" -Filter "rootAVD.bat" | Select-Object -First 1
    Push-Location $bat.DirectoryName
    Write-Host "Se perguntar a versao do Magisk, digite 2 e Enter. O emulador desliga sozinho no fim." -ForegroundColor Yellow
    & $bat.FullName $ramdisk
    Pop-Location
    Start-Sleep 15
}

function Post-Sheet($motivo, $problemas, $texto, $versao, $jogo = "jogo de teste (emulador)") {
    $link = (Get-Content $cfg -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $link) { Say "sem link da planilha: resultado so no log ($log)"; return }
    $url = $link.Trim() + $(if ($link.Contains("?")) { "&" } else { "?" }) + "relatorio=1"
    $body = @{ jogo = $jogo; versao = $versao; aparelho = "Emulador PC API 31"; motivo = $motivo
               problemas = $problemas; texto = $texto.Substring(0, [Math]::Min($texto.Length, 400000)) } | ConvertTo-Json -Compress
    # sem internet: guarda na fila e manda junto da proxima vez que der (nenhum resultado se perde)
    $fila = "$env:USERPROFILE\tinker-agente-fila"
    New-Item -ItemType Directory -Force $fila | Out-Null
    [IO.File]::WriteAllText("$fila\$(Get-Date -Format 'yyyyMMdd-HHmmss-fff').json", $body, (New-Object Text.UTF8Encoding $false))
    foreach ($f in (Get-ChildItem $fila -Filter *.json | Sort-Object Name)) {
        try {
            Invoke-WebRequest $url -Method Post -Body ([IO.File]::ReadAllBytes($f.FullName)) -ContentType "application/json; charset=utf-8" -UseBasicParsing | Out-Null
            Remove-Item $f.FullName
            Say "resultado enviado para a planilha"
        } catch { Say "sem internet para a planilha: guardei e mando depois ($($_.Exception.Message))"; break }
    }
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
                # emulador lento: a print saia preta antes de a tela desenhar; espera aparecer conteudo (ate 15 s)
                for ($k = 0; $k -lt 15; $k++) { if (@(try { Ui-Nodes } catch { @() }).Count -gt 5) { break }; Start-Sleep 1 }
                Post-Sheet "agente PC: print $tab $($rot[1])" "" ("jpeg base64`n" + (Shot "$t-$($rot[0])")) $v
            }
        }
        & $adb shell settings put system user_rotation 0
    } catch { Say "nao tirei os prints: $($_.Exception.Message)" }
}

# a tela do Tinker como o uiautomator ve (os textos do Compose): cada no com texto e o retangulo dele
function Ui-Nodes {
    for ($k = 0; $k -lt 4; $k++) {
        & $adb shell uiautomator dump /sdcard/tinker-ui.xml 2>&1 | Out-Null
        $x = (& $adb shell cat /sdcard/tinker-ui.xml 2>$null) -join ""
        if ($x -match "<hierarchy") {
            # emulador lento: o Android mostra "isn't responding" sobre o Tinker; toca em Wait e le de novo
            if ($x -match "isn.t responding" -and $x -match 'text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
                & $adb shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2)); Start-Sleep 3; continue
            }
            return [regex]::Matches($x, '<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') | ForEach-Object {
                [pscustomobject]@{ t = [Net.WebUtility]::HtmlDecode($_.Groups[1].Value + " " + $_.Groups[2].Value).Trim()
                                   x = ([int]$_.Groups[3].Value + [int]$_.Groups[5].Value) / 2; y = ([int]$_.Groups[4].Value + [int]$_.Groups[6].Value) / 2 }
            }
        }
        Start-Sleep 1  # tela animando: o uiautomator nao le; tenta de novo
    }
    throw "nao consegui ler a tela (uiautomator)"
}
# tamanho da tela agora (em pe ou deitado): os deslizes sao proporcionais, valem nas duas posicoes
$script:W = 1440; $script:H = 3120; $script:SX = 30
function Set-Rot($r) {
    & $adb shell settings put system user_rotation $r
    Start-Sleep 3
    $sz = (& $adb shell wm size) -join " "
    if ($sz -match "(\d+)x(\d+)") { $a = [int]$Matches[1]; $b = [int]$Matches[2] } else { $a = 1440; $b = 3120 }
    if ($r -eq 1) { $script:W = [Math]::Max($a, $b); $script:H = [Math]::Min($a, $b) } else { $script:W = [Math]::Min($a, $b); $script:H = [Math]::Max($a, $b) }
    # deitado, a barra de navegacao do Android fica na lateral: deslizar em x=30 caia nela e a lista nao rolava
    $script:SX = if ($r -eq 1) { [int]($script:W * 0.12) } else { 30 }
}
# rola pela margem esquerda (x=30, fora dos botoes): no meio, com a lista ja no topo, o deslize virava toque num tipo (Dword)
function Scroll-Down { & $adb shell input swipe $script:SX ([int]($script:H * 0.62)) $script:SX ([int]($script:H * 0.3)) 300; Start-Sleep -Milliseconds 700 }
# acha um texto (regex) na tela; sem achar, rola para baixo e tenta de novo
function Find-Ui($re, $scrolls = 6) {
    for ($k = 0; $k -le $scrolls; $k++) {
        $n = Ui-Nodes | Where-Object { $_.t -match $re } | Select-Object -First 1
        if ($n) { return $n }
        if ($k -lt $scrolls) { Scroll-Down }
    }
    $null
}
# espera um texto aparecer (resultado de uma acao que demora), sem rolar
function Wait-Ui($re, $secs = 15) {
    for ($k = 0; $k -lt $secs; $k++) { $n = Find-Ui $re 0; if ($n) { return $n }; Start-Sleep 1 }
    $null
}
function Tap-At($n) { & $adb shell input tap ([int]$n.x) ([int]$n.y); Start-Sleep -Milliseconds 900 }
function Tap-Ui($re, $scrolls = 6) {
    $n = Find-Ui $re $scrolls
    if (-not $n) { throw "nao achei '$re' na tela" }
    Tap-At $n
}
# o teclado do Tinker (abre quando um campo pega o foco) cobre a metade de baixo: fecha pelo OK dele
function Close-Pad {
    $nodes = Ui-Nodes
    if ($nodes | Where-Object { $_.t -eq "⌫" }) {
        $ok = $nodes | Where-Object { $_.t -eq "OK" } | Select-Object -Last 1
        if ($ok) { Tap-At $ok }
    }
}
function Top-Ui { Close-Pad; for ($k = 0; $k -lt 4; $k++) { & $adb shell input swipe $script:SX ([int]($script:H * 0.22)) $script:SX ([int]($script:H * 0.62)) 200 }; Start-Sleep -Milliseconds 500 }
# botao da fileira de ferramentas (rola para o lado): sem achar na tela, desliza a fileira ate 3 vezes
function Tap-Chip($re) {
    Top-Ui
    # com jogo escolhido ou deitado a fileira fica abaixo da dobra: rola ate ela aparecer
    Find-Ui '(^|\s)(F.{1,2}cil|Velocidade|Cron.metros|Loja|Disfar.ado)$' 14 | Out-Null  # deitado, com a caixa da IA aberta, fica bem abaixo
    $n = Find-Ui $re 0
    for ($k = 0; $k -lt 3 -and -not $n; $k++) {
        $row = Find-Ui '(^|\s)(Loja|Velocidade|Cron.metros)$' 0
        if (-not $row) { break }
        & $adb shell input swipe ([int]($script:W * 0.9)) ([int]$row.y) 100 ([int]$row.y) 300; Start-Sleep -Milliseconds 700
        $n = Find-Ui $re 0
    }
    if (-not $n) { throw "nao achei o botao '$re' na fileira de ferramentas" }
    Tap-At $n
}
# abre a ferramenta (se ja nao estiver aberta) e confere o que ela mostra
function Open-Tool($chip, $shows) {
    Top-Ui
    if (-not (Find-Ui $shows 0)) { Tap-Chip $chip }
    if (-not (Find-Ui $shows 3)) { throw "abriu mas nao mostrou '$shows'" }
}
# janelas do Tinker sobre o jogo (bolinha, painel, barra rapida), pelo dumpsys: o uiautomator nao ve janela flutuante
function Overlay-Frames {
    $d = (& $adb shell dumpsys window windows) -join "`n"
    $d -split "Window #" | Where-Object { $_ -match "APPLICATION_OVERLAY|ty=2038" -and $_ -match "dev\.tinker\.app" -and $_ -notmatch "mHasSurface=false" } | ForEach-Object {
        # Android 12+ escreve "mFrame=[l,t][r,b]"; os mais antigos, "frame=[...]" (o -match do PowerShell nao liga para maiuscula)
        if ($_ -match "[\s{m]frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]") {
            $l = [int]$Matches[1]; $t = [int]$Matches[2]; $r = [int]$Matches[3]; $b = [int]$Matches[4]
            [pscustomobject]@{ x = ($l + $r) / 2; y = ($t + $b) / 2; w = $r - $l; h = $b - $t }
        }
    }
}
function Open-Bubble {
    & $adb shell am force-stop $app
    Step "aba 0" | Out-Null
    # o Tinker acabou de abrir (tela de abertura): espera o botao aparecer antes de tocar
    if (-not (Wait-Ui "^⧉$" 20)) { throw "o Tinker nao mostrou o botao do menu flutuante" }
    Tap-Ui "^⧉$" 0  # o botao do menu flutuante no topo do Tinker
    # a bolinha aparece quando o Tinker sai da frente: espera ela ficar visivel (com superficie)
    $b = $null
    for ($k = 0; $k -lt 10 -and -not $b; $k++) { Start-Sleep 1; $b = Overlay-Frames | Sort-Object { $_.w * $_.h } | Select-Object -First 1 }
    # o toque caiu enquanto a tela ainda mudava: toca de novo uma vez
    if (-not $b -and (Find-Ui "^⧉$" 0)) { Tap-Ui "^⧉$" 0; for ($k = 0; $k -lt 10 -and -not $b; $k++) { Start-Sleep 1; $b = Overlay-Frames | Sort-Object { $_.w * $_.h } | Select-Object -First 1 } }
    if (-not $b) {
        # o que o Android diz das janelas do Tinker, para saber por que nao achou
        $d = (& $adb shell dumpsys window windows) -split "`n" | Where-Object { $_ -match "tinker|ty=|frame=|mHasSurface" } | Select-Object -First 40
        throw "a bolinha nao apareceu (nenhuma janela do Tinker sobre o jogo); dumpsys: $(($d | ForEach-Object { $_.Trim() }) -join ' / ')"
    }
    $b
}

# volta por todo o app, como uma pessoa: cada aba, cada ferramenta, cada modo, com e sem jogo, em pe e deitado, o menu
# flutuante e a barra rapida; confere que cada tela abriu e que o app nao fechou
function Ui-Tour($v) {
    function Check($name, [scriptblock]$b) {
        try { $r = & $b; $script:tourRes += "OK $name$(if ($r) { ": $r" })" }
        catch {
            # o que estava na tela, em texto (a print pode nao caber na celula da planilha)
            $seen = try { (Ui-Nodes | ForEach-Object { $_.t } | Where-Object { $_ }) -join " | " } catch { "nao li a tela" }
            $script:tourRes += "FALHA $name`: $($_.Exception.Message) [na tela: $($seen.Substring(0, [Math]::Min($seen.Length, 1500)))]"
            $script:tourShots += "`n=== $name ===`njpeg base64`n" + (Shot "tour")
        }
    }
    $script:tourRes = @(); $script:tourShots = ""
    # print de cada tela de dentro, uma linha por print na planilha
    function Snap($name) { try { Post-Sheet "agente PC: print $name" "" ("jpeg base64`n" + (Shot "tour")) $v } catch { } }
    $tools = @(@('(^|\s)F.{1,2}cil$', "Modo f", "Facil"), @('(^|\s)Velocidade$', "Achar a velocidade|Escolha a velocidade", "Velocidade"),
               @('(^|\s)Cron.metros$', "Achar cron", "Cronometros"), @('(^|\s)Loja$', "Achar pre", "Loja"),
               @('(^|\s)Disfar.ado$', "mero disfar", "Disfarcado"))
    Set-Rot 0
    & $adb logcat -b crash -c
    Step "aba 0" | Out-Null; Start-Sleep 2; Top-Ui

    # --- sem jogo: cada tela abre ---
    Check "Busca: botoes principais no topo" {
        foreach ($t in @("Nova busca", "Refinar", "Ferramentas")) { if (-not (Find-Ui $t 0)) { throw "'$t' nao aparece sem rolar" } }
    }
    foreach ($tool in $tools) {
        Check "Ferramenta $($tool[2])" { Open-Tool $tool[0] $tool[1]; Snap "Ferramenta $($tool[2])"; Tap-Chip $tool[0] }
    }
    Check "Mais buscas" {
        Top-Ui; Tap-Ui "Mais buscas"
        foreach ($t in @("Come.ar sem valor", "Busca por bytes")) { if (-not (Find-Ui $t 4)) { throw "nao mostrou '$t'" } }
        Snap "Mais buscas"
        Top-Ui; Tap-Ui "Mais buscas"
    }
    Check "Salvos" { Step "aba 1" | Out-Null; Start-Sleep 2; if (-not (Find-Ui "Exportar|Colar lista|Receitas" 6)) { throw "a aba nao mostrou a lista nem Exportar" } }
    Check "Memoria: Enderecos, Ficha e Codigo" {
        Step "aba 2" | Out-Null; Start-Sleep 2; Top-Ui
        Tap-Ui "^Ficha$" 0; if (-not (Find-Ui "classe" 3)) { throw "a Ficha nao mostrou as classes" }
        Snap "Memoria Ficha"
        Top-Ui; Tap-Ui "^C.digo$" 0; if (-not (Find-Ui "Procurar|Fun..o pelo nome" 3)) { throw "o Codigo nao mostrou a busca de funcoes" }
        Snap "Memoria Codigo"
        Top-Ui; Tap-Ui "^Endere.os$" 0; if (-not (Find-Ui "Ir para um endere" 3)) { throw "Enderecos nao mostrou Ir para um endereco" }
    }
    Check "Registro" { Step "aba 3" | Out-Null; Start-Sleep 2; if (-not (Find-Ui "Compartilhar relat" 4)) { throw "nao mostrou Compartilhar relatorio" } }
    Check "Ajustes: o dia a dia antes da IA" {
        Step "aba 4" | Out-Null; Start-Sleep 2; Top-Ui
        if (-not (Find-Ui "^Menu flutuante$" 0)) { throw "Menu flutuante nao e a primeira coisa dos Ajustes" }
        if (-not (Find-Ui "^Assistente \(IA\)$" 12)) { throw "nao achei o Assistente (IA) rolando" }
        "Menu flutuante no topo, IA mais abaixo"
    }
    Check "Ajuda" {
        Tap-Ui "Para que serve o Tinker" 24  # os Ajustes cresceram (Agente offline): rola mais
        if (-not (Find-Ui "Editor de mem" 2)) { throw "o item da Ajuda nao abriu" }
        Snap "Ajuda"
    }
    # teste 3: "Configurar IA" abre os Ajustes ja na parte da IA, sem a pessoa rolar
    Check "Configurar IA leva ate a IA" {
        Step "aba 0" | Out-Null; Start-Sleep 1
        Step "ajustesia" | Out-Null; Start-Sleep 2
        if (-not (Find-Ui "^Assistente \(IA\)$" 0)) { throw "os Ajustes abriram, mas a IA nao esta na tela" }
        Snap "Configurar IA"
    }

    # --- teste 1: com o Jogo de Teste escolhido, as ferramentas de verdade ---
    Front-Game; Start-Sleep 2
    Check "Com jogo: escolher o Jogo de Teste" { Step "escolher" | Out-Null; Step "aba 0" | Out-Null; Start-Sleep 2; if (Find-Ui "Escolha o jogo para come" 0) { throw "o Tinker nao ficou com o jogo" } }
    Check "Com jogo: Velocidade 2x e volta a 1x" {
        Open-Tool $tools[1][0] $tools[1][1]
        if (-not (Find-Ui "Escolha a velocidade" 0)) { Tap-Ui "^Achar a velocidade$" 3 }
        if (-not (Wait-Ui "Escolha a velocidade|N.o achei" 20)) { throw "Achar a velocidade nao terminou em 20 s" }
        if (Find-Ui "N.o achei" 0) { throw "nao achou a velocidade no Jogo de Teste" }
        Tap-Ui "^2x$" 2; Start-Sleep 2; Snap "Com jogo Velocidade"
        Tap-Ui "^1x$" 2
        Tap-Chip $tools[1][0]
    }
    Check "Com jogo: Cronometros acha a obra" {
        Tap "Construir"  # o Jogo de Teste comeca uma obra de 2 h
        Step "aba 0" | Out-Null; Start-Sleep 2
        Open-Tool $tools[2][0] $tools[2][1]
        Tap-Ui "^Achar cron" 3
        if (-not (Wait-Ui "termina em|Nenhum cron" 25)) { throw "Achar cronometros nao terminou em 25 s" }
        if (Find-Ui "Nenhum cron" 0) { throw "nao achou a obra do Jogo de Teste" }
        Snap "Com jogo Cronometros"
        Tap-Chip $tools[2][0]
    }
    Check "Com jogo: Ficha" {
        Step "aba 2" | Out-Null; Start-Sleep 2; Top-Ui
        Tap-Ui "^Ficha$" 0
        # o Jogo de Teste e motor proprio (sem IL2CPP): a Ficha tem de dizer isso em vez de mostrar classes
        if (Find-Ui "n.o tem classes IL2CPP" 2) { Snap "Com jogo Ficha"; return "motor proprio: a Ficha explica que nao ha classes" }
        Tap-Ui "Ver todas as classes" 3
        Tap-Ui "Heroi" 8
        if (-not (Wait-Ui "nome|ouro|Her.i" 10)) {
            $o = Find-Ui "^0x[0-9a-f]+" 4  # a classe abre a lista de objetos: abre o primeiro
            if ($o) { Tap-At $o }
            if (-not (Wait-Ui "nome|ouro" 10)) { throw "a ficha do Jogo.Heroi nao mostrou os campos" }
        }
        Snap "Com jogo Ficha Heroi"
    }
    # teste 5: valor que nao da para buscar mostra frase amigavel (friendly), nao a mensagem tecnica
    Check "Erro em frase amigavel" {
        Step "aba 0" | Out-Null; Start-Sleep 2; Top-Ui
        Tap-Ui "^Valor$" 0
        foreach ($k in @("1", "~")) { $n = Ui-Nodes | Where-Object { $_.t -eq $k } | Select-Object -Last 1; if (-not $n) { throw "o teclado do Tinker nao abriu" }; Tap-At $n }
        Close-Pad
        Tap-Ui "^Nova busca$" 0
        $sim = Find-Ui "Sim, nova busca" 0; if ($sim) { Tap-At $sim }
        $m = Wait-Ui "N.o entendi o valor|inv.lid" 10
        if (-not $m) { throw "nao apareceu mensagem de erro" }
        if ($m.t -notmatch "N.o entendi o valor") { throw "mensagem tecnica em vez da amigavel: $($m.t)" }
        Snap "Erro amigavel"
        Tap-Ui "^Valor$" 0
        foreach ($k in 1..3) { $n = Ui-Nodes | Where-Object { $_.t -eq "⌫" } | Select-Object -Last 1; if ($n) { Tap-At $n } }
        Close-Pad
        $m.t
    }

    # --- teste 4: deitado ---
    Set-Rot 1
    Step "aba 0" | Out-Null; Start-Sleep 2
    foreach ($tool in $tools) {
        Check "Deitado: ferramenta $($tool[2])" { Open-Tool $tool[0] $tool[1]; Snap "Deitado Ferramenta $($tool[2])"; Tap-Chip $tool[0] }
    }
    Check "Deitado: Mais buscas" { Top-Ui; Tap-Ui "Mais buscas"; if (-not (Find-Ui "Come.ar sem valor" 8)) { throw "nao mostrou 'Comecar sem valor'" }; Snap "Deitado Mais buscas"; Top-Ui; Tap-Ui "Mais buscas" }
    Check "Deitado: Ficha" { Step "aba 2" | Out-Null; Start-Sleep 2; Top-Ui; Tap-Ui "^Ficha$" 0; if (-not (Find-Ui "classe|nome|ouro" 3)) { throw "a Ficha nao abriu" }; Snap "Deitado Ficha" }
    Set-Rot 0

    # --- teste 2: menu flutuante e barra rapida (janelas sobre o jogo, vistas pelo dumpsys) ---
    Check "Menu flutuante: bolinha e painel" {
        $b = Open-Bubble
        Snap "Menu flutuante bolinha"
        & $adb shell input tap ([int]$b.x) ([int]$b.y); Start-Sleep 3
        $p = Overlay-Frames | Where-Object { $_.w * $_.h -gt $script:W * $script:H * 0.3 }
        if (-not $p) { throw "tocar na bolinha nao abriu o painel" }
        Snap "Menu flutuante painel"
        "bolinha $([int]$b.w)x$([int]$b.h), painel $([int]$p[0].w)x$([int]$p[0].h)"
    }
    Check "Barra rapida (segurar a bolinha)" {
        $b = Open-Bubble
        & $adb shell input swipe ([int]$b.x) ([int]$b.y) ([int]$b.x) ([int]$b.y) 1200; Start-Sleep 3
        $bar = Overlay-Frames | Where-Object { $_.w -gt $script:W * 0.6 -and $_.h -lt $script:H * 0.5 }
        if (-not $bar) { throw "segurar a bolinha nao abriu a barra rapida" }
        Snap "Barra rapida"
        "barra $([int]$bar[0].w)x$([int]$bar[0].h)"
    }
    & $adb shell am force-stop $app  # fecha a bolinha/barra; as missoes abrem o Tinker de novo

    Check "Tinker continua aberto e sem queda" {
        Step "aba 0" | Out-Null
        if (-not (& $adb shell pidof $app)) { throw "o Tinker nao abriu de novo" }
        $c = (& $adb logcat -d -b crash) -join "`n"
        if ($c -match "dev\.tinker\.app") { throw "queda registrada: " + $c.Substring(0, [Math]::Min($c.Length, 1500)) }
    }
    $ok = @($script:tourRes | Where-Object { $_ -like "OK*" }).Count
    $fails = ($script:tourRes | Where-Object { $_ -like "FALHA*" }) -join "`n"
    Say "volta pelo app: $ok de $($script:tourRes.Count) ok"
    if ($fails) { Say $fails }
    Post-Sheet "agente PC: volta pelo app $ok de $($script:tourRes.Count) ok" $fails (($script:tourRes -join "`n") + $script:tourShots) $v
}

# o link da planilha (salvo so neste PC) vai para o Tinker do emulador pelo adb root, com o app fechado
function Set-PanelLink {
    $link = (Get-Content $cfg -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $link) { return }
    $f = "/data/data/$app/shared_prefs/tinker.xml"
    $xml = (Sh "cat $f" 2>$null) -join "`n"
    if (-not $xml.Contains("</map>") -or $xml.Contains($link.Trim())) { return }
    $xml = $xml -replace '\s*<string name="keySource"\s*/>', '' -replace '\s*<string name="keySource">[^<]*</string>', ''
    $xml = $xml.Replace("</map>", "    <string name=`"keySource`">$([Security.SecurityElement]::Escape($link.Trim()))</string>`n</map>")
    $tmp = "$env:TEMP\tinker-prefs.xml"
    [IO.File]::WriteAllText($tmp, $xml, (New-Object Text.UTF8Encoding $false))
    & $adb push $tmp /data/local/tmp/tinker-prefs.xml | Out-Null
    Sh "cat /data/local/tmp/tinker-prefs.xml > $f; rm /data/local/tmp/tinker-prefs.xml"
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
function Step($cmd, $secs = 340) {
    $rm = (Sh "rm -f /data/data/$app/files/missao.txt" 2>&1) -join " "
    # o adbd do emulador reiniciou sem root (Permission denied): pede o root de novo, senao nenhuma resposta do Tinker e lida
    if ($rm -match "Permission denied") {
        Say "o adb perdeu o root no emulador: pedindo de novo"
        & $adb root | Out-Null; Start-Sleep 3; & $adb wait-for-device
        Sh "rm -f /data/data/$app/files/missao.txt"
    }
    $o = (& $adb shell am start -f 0x10008000 -n "$app/.MainActivity" --es missao "'$cmd'" 2>&1) -join " "
    # o Android do emulador reiniciou (o sistema ainda nao acha os apps): espera ligar e deixa a missao tentar de novo
    if ($o -match "does not exist|Can't find service") {
        Say "o Android do emulador reiniciou no meio das missoes: esperando ligar"
        & $adb wait-for-device; Wait-Booted | Out-Null
        throw "o Android do emulador reiniciou no meio do passo '$cmd'"
    }
    for ($k = 0; $k -lt $secs * 2; $k++) {  # ate 340 s: o sozinho espera ate 300 s (num jogo grande o "itens" leva mais)
        Start-Sleep -Milliseconds 500
        $t = Sh "cat /data/data/$app/files/missao.txt" 2>$null
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
    $xml = (Sh "cat $f" 2>$null) -join "`n"
    if (-not $xml.Contains("</map>")) { return }
    $xml = $xml -replace '\s*<boolean name="autoOverlay" value="[a-z]+"\s*/>', ''
    $xml = $xml.Replace("</map>", "    <boolean name=`"autoOverlay`" value=`"false`" />`n</map>")
    $tmp = "$env:TEMP\tinker-prefs.xml"
    [IO.File]::WriteAllText($tmp, $xml, (New-Object Text.UTF8Encoding $false))
    & $adb push $tmp /data/local/tmp/tinker-prefs.xml | Out-Null
    Sh "cat /data/local/tmp/tinker-prefs.xml > $f; rm /data/local/tmp/tinker-prefs.xml"
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
    # agente offline: com Auto, o Tinker escolhe sozinho o tipo pelo que ja sabe de "Abates" e guarda o acerto
    Mission "agente offline aprende (abates, tipo Auto)" {
        $c = Step "sozinho Abates auto"
        $msg = ($script:last -split "`n")[0]
        if ($c -lt 1 -or $c -gt 3 -or $msg -match "vi |Parou|Nenhum") { throw "sobraram $c ($msg)" }
        Step "aprendido Abates" | Out-Null
        $f = (($script:last -split "`n")[0]) -split ' '
        if ($f[1] -ne "i32" -or [int]$f[2] -lt 1) { throw "nao guardou o acerto ($($script:last -replace "`n", ' | '))" }
        "sobraram $c; o agente sabe: Abates e Dword, $($f[2]) acertos e $($f[3]) erros"
    }
    # treino do agente offline em outros nomes da tela: o numero muda sozinho (energia) ou por um botao tocado ao fundo
    function Train($label, $button) {
        $job = $null
        if ($button) {
            $job = Start-Job -ArgumentList $adb, $game, $button -ScriptBlock {
                param($adb, $game, $b)
                for ($i = 0; $i -lt 80; $i++) { Start-Sleep 4; & $adb shell am broadcast -p $game -a dev.tinker.testgame.ACAO --es acao "'$b'" | Out-Null }
            }
        }
        try { $c = Step "sozinho $label auto"; $msg = ($script:last -split "`n")[0] } finally { if ($job) { Stop-Job $job; Remove-Job $job -Force } }
        Step "aprendido $label" | Out-Null
        $f = (($script:last -split "`n")[0]) -split ' '
        [pscustomobject]@{ c = $c; msg = $msg; type = $f[1]; wins = [int]$f[2]; fails = [int]$f[3] }
    }
    Mission "agente offline treina: energia (muda sozinha)" {
        $t = Train "Energia" $null
        if ($t.wins -lt 1) { throw "nao aprendeu ($($t.msg); sabe: $($t.type), $($t.wins) acertos, $($t.fails) erros)" }
        "aprendeu Energia como $($t.type): $($t.wins) acertos, $($t.fails) erros"
    }
    # Madeira aparece inteira, mas o jogo guarda Float: o que o Claude ensinou (Dword) erra; o agente tem de aprender com o erro
    Mission "agente offline aprende com o erro: madeira (ensinado Dword, e Float)" {
        $t = Train "Madeira" "+1 madeira"
        if ($t.type -ne "f32" -or $t.wins -lt 1) { $t = Train "Madeira" "+1 madeira" }
        if ($t.type -ne "f32" -or $t.wins -lt 1) { throw "nao aprendeu que Madeira e Float ($($t.msg); sabe: $($t.type), $($t.wins) acertos, $($t.fails) erros)" }
        "aprendeu Madeira como Float depois de $($t.fails) erro(s) com Dword"
    }
    # curva de aprendizagem: 4 nomes que o agente nunca viu (o Claude nao ensinou), 3 vezes cada. So o que foi medido:
    # o tipo que ele ia usar antes de cada vez, o tempo, o que sobrou, o que ele guardou e a conferencia na tela do jogo
    $gab = [ordered]@{ "Rubis" = @("+1 rubi", "f64"); "Sementes" = @("+1 semente", "i64"); "Honra" = @("+1 honra", "i32"); "Brilho" = @("+1 brilho", "f32"); "Cristais" = @("+1 cristal", "i32*10"); "Runas" = @("+1 runa", "i32"); "Vigor" = @("+1 vigor", "i32^"); "Crystals" = @("+1 crystal", "i32*10") }  # Runas: XOR e simetrico, o cifrado comum (🔒, guardado como i32) acha
    $script:curva = @()
    foreach ($nome in $gab.Keys) {
        Mission "curva de aprendizagem: $nome (gabarito $($gab[$nome][1]))" {
            $linha = "$nome | gabarito $($gab[$nome][1])"
            for ($vez = 1; $vez -le 3; $vez++) {
                Step "aprendido $nome" | Out-Null
                $ia = ((($script:last -split "`n")[0]) -split ' ')[1]
                $t0 = Get-Date
                $t = Train $nome $gab[$nome][0]
                $seg = [int]((Get-Date) - $t0).TotalSeconds
                $linha += " | vez ${vez}: ia usar $ia, $seg s, sobraram $($t.c), guardou $($t.type) ($($t.wins) acertos, $($t.fails) erros)"
            }
            # conferencia: escreve um numero nos que sobraram e le a tela do jogo
            # [double]: Sementes passa de 4 bilhoes ([int] estourava)
            $want = [double](Val $nome) + 50
            # disfarcado (i32*10): na memoria vai o numero da tela vezes 10
            $k = if ($gab[$nome][1] -match "\*(\d+)$") { [double]$Matches[1] } else { 1 }
            # numero da tela calculado (i32^): soma 50 na base que ele achou; a tela tem de subir 50
            if ($gab[$nome][1] -like "*^") { Step "somar 50" | Out-Null } else { Step "escrever $($want * $k)" | Out-Null }
            Start-Sleep 2
            $escreveu = ($script:last -split "`n" | Select-Object -First 5) -join " / "  # o que o Tinker escreveu e onde (addr tipo valor)
            $now = Val $nome
            $linha += " | conferido na tela: escrevi $want, a tela mostra $now | resultados: $escreveu"
            $script:curva += $linha
            if ($now -ne $want) {
                # o teste de escrita aprovou um endereco que nao muda a tela: anexa o passo a passo dele (registro do Tinker)
                Step "diag 10" | Out-Null
                throw "a tela nao mudou ($linha) | registro: $((($script:last -split "`n") | Select-Object -Skip 1) -join ' / ')"
            }
            if ($t.type -ne $gab[$nome][1]) { throw "guardou o tipo errado ($linha)" }
            $linha
        }
    }
    if ($script:curva) { Post-Sheet "agente PC: curva de aprendizagem" "" ($script:curva -join "`n") $v }
    # pedido da pessoa "Honra = 500": o agente acha pelo nome e ja poe o valor; conferido na tela (os toques podem somar uns 3)
    Mission "pedido: Honra = 500 (acha e poe o valor pedido)" {
        $t = Train "Honra=500" "+1 honra"
        $logo = Val "Honra"  # logo depois, sem esperar
        Start-Sleep 2
        $now = Val "Honra"
        # o que ficou no endereco que o agente usou: separa "o valor voltou" de "o jogo reiniciou e o endereco mudou"
        $mem = if ($t.msg -match "0x([0-9a-f]+)") { Step "ler $($Matches[1]) i32" | Out-Null; ($script:last -split "`n")[0] } else { "?" }
        $pid2 = (& $adb shell pidof $game) -join ""
        if ($now -lt 500 -or $now -gt 505) { throw "a tela mostra $now (logo depois: $logo; no endereco: $mem; pid do jogo: $pid2) ($($t.msg))" }
        "a tela mostra $now ($($t.msg))"
    }
    # nivel 17: 👁 monitor de memoria, sem nome: o agente toca "+1 lenha" em momentos soltos (3 a 14 s, como quem joga;
    # num ritmo fixo a lenha pareceria um relogio e seria descartada como estrutura); o monitor tem de achar o
    # endereco da lenha entre os que mudaram por evento (afunilando). Conferido: escreve +5 em cada candidato e le a tela
    Mission "nivel 17: monitor de memoria acha a lenha sem tela (afunilando)" {
        $job = Start-Job -ArgumentList $adb, $game -ScriptBlock {
            param($adb, $game)
            for ($i = 0; $i -lt 40; $i++) { Start-Sleep (Get-Random -Minimum 3 -Maximum 15); & $adb shell am broadcast -p $game -a dev.tinker.testgame.ACAO --es acao "'+1 lenha'" | Out-Null }
        }
        try { Step "monitorar 180" | Out-Null } finally { Stop-Job $job; Remove-Job $job -Force }
        $linhas = $script:last -split "`n"
        $cands = @($linhas | Where-Object { $_ -like "mudou *" } | ForEach-Object { $f = $_ -split ' '; [pscustomobject]@{ addr = $f[1]; v = [double]$f[2]; reg = $f[3] } })
        $log = ($linhas | Where-Object { $_ -like "log *" }) -join ' / '
        $lenha = [double](Val "Lenha")
        $certo = $null
        foreach ($c in ($cands | Where-Object { $_.v -eq $lenha } | Select-Object -First 5)) {
            Step "endereco $($c.addr) i32 $($lenha + 5)" | Out-Null; Start-Sleep 2
            $depois = Val "Lenha"
            Step "endereco $($c.addr) i32 $lenha" | Out-Null
            if ($depois -eq $lenha + 5) { $certo = $c.addr; break }
        }
        $linha = "$($cands.Count) mudaram por evento; lenha ($lenha) " + $(if ($certo) { "achada em 0x$certo e conferida na tela" } else { "nao estava entre eles" }) + " | $log"
        if (-not $certo) { throw $linha }
        $linha
    }
    # nivel 18, como o Orecraft (o que quebrou no jogo de verdade): HUD so com icone + numero, um deles 0, e "≡ TRIBO" na
    # barra. A lista de itens tem de trazer o ouro do acampamento com endereco certo (pedido 999 conferido na tela), nao
    # pode ter a "TRIBO" como item, e nao pode buscar o 0 (milhoes na memoria: travava o Tinker no Orecraft)
    Mission "nivel 18: como o Orecraft (HUD so com icone e numero, um 0, TRIBO na barra)" {
        $ouro = [double](Val "Campouro")
        Step "itens" 900 | Out-Null
        $l = @(($script:last -split "`n") | Where-Object { $_ -like "item *" } | ForEach-Object { $f = $_ -split ' '; [pscustomobject]@{ nome = $f[1]; v = $f[2]; addr = $f[4] } })
        if ($l | Where-Object { $_.nome -match "TRIB" }) { throw "a barra 'TRIBO' entrou como item ($($l.Count) itens)" }
        $o = $l | Where-Object { [double]$_.v -eq $ouro } | Select-Object -First 1
        if (-not $o) { throw "o ouro do HUD ($ouro) nao veio na lista ($($l.Count) itens)" }
        Step "sozinho $($o.nome)=999 auto" | Out-Null
        $msg = ($script:last -split "`n")[0]
        Start-Sleep 2
        $now = Val "Campouro"
        Step "escrever $ouro" | Out-Null
        if ($now -ne 999) { throw "pedido $($o.nome)=999: a tela mostra $now ($msg)" }
        "ouro do HUD ($ouro) achado como $($o.nome), 999 na tela e voltou; $($l.Count) itens, sem TRIBO"
    }
    # nivel 16, como o DYSMANTLE: o numero da lenha fica embaixo do icone, sem nome, como "1+N"; o agente acha pelo
    # numero (o da direita), prova, e depois acha a folha pelo formato (achou um, acha todos). Conferido na tela
    Mission "nivel 16: como o DYSMANTLE (numero sem nome, 1+N, e o vizinho pelo formato)" {
        $v = [double](Val "Lenha")
        $job = Start-Job -ArgumentList $adb, $game -ScriptBlock {
            param($adb, $game)
            for ($i = 0; $i -lt 80; $i++) { Start-Sleep 4; & $adb shell am broadcast -p $game -a dev.tinker.testgame.ACAO --es acao "'+1 lenha'" | Out-Null }
        }
        try { Step "sozinho $v auto" | Out-Null } finally { Stop-Job $job; Remove-Job $job -Force }
        $msg = ($script:last -split "`n")[0]
        $want = [double](Val "Lenha") + 50
        Step "escrever $want" | Out-Null; Start-Sleep 2
        $now = Val "Lenha"
        if ($now -ne $want) { throw "lenha: escrevi $want, a tela mostra $now ($msg)" }
        Step "irmaos" | Out-Null
        $irm = @($script:last -split "`n" | Where-Object { $_ -like "irmao *" } | ForEach-Object { $f = $_ -split ' '; [pscustomobject]@{ addr = $f[1]; v = [double]$f[2] } })
        $folha = [double](Val "Folha")
        $c = $irm | Where-Object { $_.v -eq $folha } | Select-Object -First 1
        if (-not $c) { throw "lenha certa ($want); a folha ($folha) nao estava entre os $($irm.Count) parecidos ($msg)" }
        Step "endereco $($c.addr) i32 $($folha + 5)" | Out-Null; Start-Sleep 2
        $f2 = Val "Folha"
        Step "endereco $($c.addr) i32 $folha" | Out-Null
        if ($f2 -ne $folha + 5) { throw "lenha certa; folha: escrevi $($folha + 5), a tela mostra $f2" }
        "lenha achada pelo numero sem nome ($want na tela) e a folha pelo formato ($($folha + 5) na tela)"
    }
    # dificuldade x3 (niveis 13 a 15): jeitos de guardar que jogos de verdade usam e o agente ainda nao resolve
    # nivel 13: a Prata fica na memoria como texto ("000123" em UTF-16), nao como numero
    Mission "nivel 13: numero guardado como texto (Prata)" {
        $t = Train "Prata" "+1 prata"
        $want = [double](Val "Prata") + 50
        Step "escrever $want" | Out-Null; Start-Sleep 2
        $now = Val "Prata"
        if ($now -ne $want) { throw "escrevi $want, a tela mostra $now ($($t.msg))" }
        "escrevi $want, a tela mostra $now ($($t.msg))"
    }
    # nivel 14: as Esmeraldas mudam de lugar a cada 8 s (o antigo e apagado); o pedido tem de valer depois da mudanca
    Mission "nivel 14: valor que muda de lugar (Esmeraldas = 500)" {
        $t = Train "Esmeraldas=500" "+1 esmeralda"
        Start-Sleep 10  # passa por uma mudanca de lugar
        $now = Val "Esmeraldas"
        if ($now -lt 500 -or $now -gt 505) { throw "a tela mostra $now ($($t.msg))" }
        "a tela mostra $now depois de mudar de lugar ($($t.msg))"
    }
    # nivel 15: o Poder da tela e ataque + arma + anel (3 lugares, o anel em Float); somar 50 no provado tem de subir a tela 50
    Mission "nivel 15: soma de 3 partes (Poder)" {
        $t = Train "Poder" "+1 poder"
        $antes = [double](Val "Poder")
        Step "somar 50" | Out-Null; Start-Sleep 2
        $now = Val "Poder"
        if ($now -ne $antes + 50) { throw "antes $antes, depois de somar 50 a tela mostra $now ($($t.msg))" }
        "antes $antes, depois $now ($($t.msg))"
    }
    # nivel 12: achou um, acha todos. A mochila tem 4 espacos com o mesmo formato (quantidade, copia em +4, maximo, codigo);
    # o agente acha as Flechas pela tela e os outros 3 so pela memoria. Cada um e conferido escrevendo +5 e lendo a tela
    Mission "nivel 12: achou um, acha todos (mochila, sem olhar a tela)" {
        $t = Train "Flechas" "+1 flecha"
        Step "irmaos" | Out-Null
        $irm = @($script:last -split "`n" | Where-Object { $_ -like "irmao *" } | ForEach-Object { $f = $_ -split ' '; [pscustomobject]@{ addr = $f[1]; v = [double]$f[2] } })
        $ok = @(); $err = @()
        foreach ($nome in @("Pães", "Tochas", "Cordas")) {
            $v = [double](Val $nome)
            $c = $irm | Where-Object { $_.v -eq $v } | Select-Object -First 1
            if (-not $c) { $err += "$nome ($v nao estava entre os $($irm.Count))"; continue }
            Step "endereco $($c.addr) i32 $($v + 5)" | Out-Null; Start-Sleep 2
            $depois = Val $nome
            Step "endereco $($c.addr) i32 $v" | Out-Null
            if ($depois -eq $v + 5) { $ok += $nome } else { $err += "$nome (escrevi $($v + 5), a tela mostra $depois)" }
        }
        $linha = "Flechas achada pela tela ($($t.msg)); pela memoria: $($irm.Count) parecidos; conferidos na tela: $($ok.Count) de 3 ($($ok -join ', '))" + $(if ($err) { "; falhou: $($err -join '; ')" } else { "" })
        if ($ok.Count -lt 3) { throw $linha }
        $linha
    }
    # nivel 11: 📋 Itens do jogo - sem pista nenhuma, le a tela, acha o que da para editar e monta a lista. Cada item com
    # endereco (da memoria Java: nunca escreve na nativa) e conferido: escreve +7, le a tela do jogo, volta como estava
    Mission "nivel 11: itens do jogo sozinho (lista do que da para editar)" {
        Step "itens" | Out-Null
        $itens = @($script:last -split "`n" | Where-Object { $_ -like "item *" })
        $ok = @(); $err = @()
        foreach ($l in $itens) {
            $f = $l -split ' '
            $nome = $f[1] -replace '_', ' '; $addr = $f[4]; $tipo = $f[5]; $reg = $f[6]
            if ($addr -eq "-" -or $reg -notlike "J*" -or $tipo -notmatch '^(i32|i64|f32|f64)$') { continue }
            try { $antes = [double](Val $nome) } catch { continue }
            Step "endereco $addr $tipo $($antes + 7)" | Out-Null; Start-Sleep 2
            $depois = Val $nome
            Step "endereco $addr $tipo $antes" | Out-Null
            if ($depois -eq $antes + 7) { $ok += $nome } else { $err += "$nome ($antes -> $depois)" }
        }
        $linha = "$($itens.Count) itens na lista; com endereco conferido na tela: $($ok.Count) certos ($($ok -join ', ')), $($err.Count) errados ($($err -join ', '))"
        if ($itens.Count -lt 10 -or $ok.Count -lt 5 -or $err.Count -gt 0) { throw $linha }
        $linha
    }
    # nivel 10: decidir sem escrever. O Ouro tem 6 copias (iscas) que seguem cada mudanca; so lendo, o agente aponta o
    # provavel pela vizinhanca (o verdadeiro mora junto de outros numeros da tela). A conferencia escreve so no palpite
    Mission "nivel 10: decidir sem escrever (Ouro com 6 copias, so lendo)" {
        $job = Start-Job -ArgumentList $adb, $game -ScriptBlock {
            param($adb, $game)
            for ($i = 0; $i -lt 80; $i++) { Start-Sleep 4; & $adb shell am broadcast -p $game -a dev.tinker.testgame.ACAO --es acao "'+1 ouro'" | Out-Null }
        }
        try { Step "sozinho Ouro auto leitura" | Out-Null } finally { Stop-Job $job; Remove-Job $job -Force }
        $msg = ($script:last -split "`n")[0]
        if ($msg -notmatch "0x([0-9a-f]+) \(fica junto") { throw "sem palpite ($msg)" }
        $g = $Matches[1]
        $want = [double](Val "Ouro") + 77
        Step "endereco $g i32 $want" | Out-Null; Start-Sleep 2
        $now = Val "Ouro"
        if ($now -ne $want) { throw "palpite 0x$g errado: escrevi $want, a tela mostra $now ($msg)" }
        "palpite 0x$g certo: escrevi $want, a tela mostra $now ($msg)"
    }
    # nivel 9: o valor nunca muda (ninguem toca no Ouro) e ha copias dele (iscas); o agente tem de se virar sozinho
    # (busca em grupo com outro numero da tela) e por o valor pedido, sem pedir nada
    Mission "nivel 9: pedido sem o valor mudar (Ouro = 9999, ninguem toca)" {
        $c = Step "sozinho Ouro=9999 auto"
        $msg = ($script:last -split "`n")[0]
        Start-Sleep 2
        $now = Val "Ouro"
        if ($now -ne 9999) { throw "a tela mostra $now, sobraram $c ($msg)" }
        "a tela mostra 9999, sobraram $c ($msg)"
    }
    # nivel 5: o jogo atualiza e tudo muda de lugar; o agente tem de reachar pelo que ja aprendeu (uma vez cada, conferido na tela)
    $script:curva5 = @()
    # comeca limpo: a curva escreve no jogo e o Vigor deixa a busca pela mudanca grande; o nivel 5 nao herda nada disso
    & $adb shell am force-stop $game; Start-Sleep 2; Front-Game; Start-Sleep 3; Step "escolher" | Out-Null
    Tap "Atualizar jogo"; Start-Sleep 2
    foreach ($nome in @("Rubis", "Honra", "Cristais")) {
        Mission "nivel 5, depois de atualizar o jogo: $nome (gabarito $($gab[$nome][1]))" {
            Step "aprendido $nome" | Out-Null
            $ia = ((($script:last -split "`n")[0]) -split ' ')[1]
            $t0 = Get-Date
            $t = Train $nome $gab[$nome][0]
            $seg = [int]((Get-Date) - $t0).TotalSeconds
            $want = [double](Val $nome) + 50
            $k = if ($gab[$nome][1] -match "\*(\d+)$") { [double]$Matches[1] } else { 1 }
            Step "escrever $($want * $k)" | Out-Null; Start-Sleep 2
            $escreveu = ($script:last -split "`n" | Select-Object -First 4) -join " / "
            $now = Val $nome
            $linha = "$nome (depois de atualizar) | gabarito $($gab[$nome][1]) | ia usar $ia, $seg s, sobraram $($t.c), guardou $($t.type) ($($t.wins) acertos, $($t.fails) erros) | conferido na tela: escrevi $want, a tela mostra $now | resultados: $escreveu"
            $script:curva5 += $linha
            if ($now -ne $want) { throw "a tela nao mudou ($linha)" }
            $linha
        }
    }
    if ($script:curva5) { Post-Sheet "agente PC: curva nivel 5 (depois de atualizar o jogo)" "" ($script:curva5 -join "`n") $v }
    # a conferencia escreve nos que sobraram: se algum nao era o certo, o jogo ficou mexido; reabre limpo para as outras missoes
    & $adb shell am force-stop $game; Start-Sleep 2; Front-Game; Start-Sleep 3; Step "escolher" | Out-Null
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
        # emulador lento (a tela trava 3 a 5 s): o Tinker pode levar mais que 10 s para religar; espera ate 30 s mais
        for ($i = 0; $i -lt 4; $i++) {
            $o = Val "Ouro"; Tap "+1 ouro"; Start-Sleep 1
            $d = (Val "Ouro") - $o
            if ($d -eq 5) { break }
            Start-Sleep 8
        }
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
    # editor de save: troca um campo no arquivo do jogo (o Tinker faz backup e fecha o jogo), reabre e confere na tela
    function Save-Mission($rel, $field, $label) {
        Step "savelista" | Out-Null
        if ($script:last -notmatch "file $([regex]::Escape($rel)) ") { throw "o editor nao listou $rel ($((($script:last -split "`n") | Select-Object -First 8) -join ' | '))" }
        $want = [double](Val $label) + 3
        Step "saveeditar $rel $field $want" | Out-Null
        Start-Sleep 2; Front-Game; Start-Sleep 4
        $now = Val $label
        Step "escolher" | Out-Null
        if ($now -ne $want) { throw "a tela mostra $now, esperava $want" }
        "$label $want pelo arquivo $rel, a tela mostra igual"
    }
    Mission "editor de save: pocoes no arquivo da pasta interna" { Save-Mission "shared_prefs/jogo.xml" "potions" "Poções" }
    Mission "editor de save: gemas no JSON da pasta externa (Android/data)" { Save-Mission "ext/files/progresso.json" "gems" "Gemas" }
    $ok = ($script:mres | Where-Object { $_ -like "OK*" }).Count
    $fails = ($script:mres | Where-Object { $_ -like "FALHA*" }) -join "`n"
    # o Tinker ou o jogo fecharam durante as missoes: o Android guarda o motivo no buffer de crash
    $crash = (& $adb logcat -d -b crash) -join "`n"
    # so queda do Tinker ou do Jogo de Teste (o Magisk e outros apps do emulador tambem caem as vezes)
    if ($crash -match "dev\.tinker") { $fails += "`n=== fechou durante as missoes ===`n" + $crash.Substring(0, [Math]::Min($crash.Length, 3000)) }
    Post-Sheet "agente PC: missoes $ok de $($script:mres.Count) ok" $fails ("$health`n" + ($script:mres -join "`n")) $v
}

function Install-Tinker($rel) {
    Get-Apk ($rel.assets | Where-Object name -like "Tinker-*.apk" | Select-Object -First 1) "$env:TEMP\Tinker.apk"
    $inst = (& $adb install -r "$env:TEMP\Tinker.apk") -join " "
    Say "instalado: $inst"
    & $adb shell appops set $app SYSTEM_ALERT_WINDOW allow | Out-Null
    & $adb shell pm grant $app android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
    # root para o Tinker sem precisar tocar em Permitir (politica do Magisk para o uid do app)
    $uid = ((& $adb shell dumpsys package $app) | Select-String "(?:userId|appId)=(\d+)" | Select-Object -First 1).Matches.Groups[1].Value
    if ($uid) { Sh "magisk --sqlite 'REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,1)'" 2>$null | Out-Null }
}

function Get-Release {
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
    $rel
}

function Test-Version {
    $rel = Get-Release
    $v = $rel.tag_name.TrimStart("v")
    if ((Get-Content $done -ErrorAction SilentlyContinue) -eq $v) {
        if ((Get-Date).Minute % 10 -lt 2) { Say "sem versao nova (ultima testada: $v)" }  # sinal de vida a cada ~10 min
        return
    }
    Say "=== Tinker $v ==="
    # jogo de teste com tela (vem no mesmo release): fica instalado para os testes de tela
    $jt = $rel.assets | Where-Object name -eq "JogoTeste.apk" | Select-Object -First 1
    if ($jt) { Get-Apk $jt "$env:TEMP\JogoTeste.apk"; Say ("jogo de teste: " + ((& $adb install -r "$env:TEMP\JogoTeste.apk") -join " ")) }
    Install-Tinker $rel
    & $adb logcat -b crash -c
    Sh "rm -f /data/data/$app/files/autoteste.txt"
    & $adb shell am force-stop $app
    Set-PanelLink
    & $adb shell am start -n "$app/.MainActivity" --ez autoteste true | Out-Null
    Say "testando (ate 4 min)..."
    $out = $null
    for ($k = 0; $k -lt 48 -and -not $out; $k++) {
        Start-Sleep 5
        $t = Sh "cat /data/data/$app/files/autoteste.txt" 2>$null
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
    try { Ui-Tour $v } catch { Say "a volta pelo app parou: $($_.Exception.Message)" }
    if ((& $adb shell pm list packages $game) -match $game) {
        # parou no meio: o motivo vai para a planilha (antes ficava so nesta janela e o resultado das missoes sumia)
        try { Run-Missions $v } catch {
            # o Android do emulador reiniciou no meio: espera ligar e roda as missoes de novo (uma vez)
            if ($_.Exception.Message -match "reiniciou") {
                Say "o emulador reiniciou durante as missoes: esperando e rodando de novo"
                & $adb wait-for-device; Wait-Booted | Out-Null; & $adb root | Out-Null; Start-Sleep 5; & $adb wait-for-device
                $err = $null
                try { Run-Missions $v } catch { $err = $_ }
            } else { $err = $_ }
            if ($err) {
                $why = "$($err.Exception.Message) (linha $($err.InvocationInfo.ScriptLineNumber): $($err.InvocationInfo.Line.Trim()))"
                Say "missoes pararam: $why"
                Post-Sheet "agente PC: missoes pararam" $why (($script:mres + $script:curva) -join "`n") $v
            }
        }
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
# 🎬 toques gravados ("espera_ms tap x y" ou "espera_ms swipe x1 y1 x2 y2 ms", em pixels da tela), se houver um arquivo
# repete o gravado em volta, por $secs segundos, num job (o passo do Tinker roda ao mesmo tempo)
function Start-Replay($file, $secs) {
    Start-Job -ArgumentList $adb, $env:ANDROID_SERIAL, @(Get-Content $file), $secs -ScriptBlock {
        param($adb, $serial, $lines, $secs)
        $env:ANDROID_SERIAL = $serial
        Start-Sleep 5  # o passo do Tinker poe o jogo na frente antes
        $end = (Get-Date).AddSeconds($secs)
        while ((Get-Date) -lt $end) {
            foreach ($l in $lines) {
                if ((Get-Date) -ge $end) { break }
                $f = $l -split ' '
                Start-Sleep -Milliseconds ([math]::Min([int]$f[0], 5000))
                if ($f[1] -eq "tap") { & $adb shell input tap $f[2] $f[3] } else { & $adb shell input swipe $f[2] $f[3] $f[4] $f[5] $f[6] }
            }
        }
    }
}

# 🧭 explorador: sem ninguem jogando, toca sozinho em pontos da tela do jogo (minas, construcoes, menus) por $secs segundos,
# fecha o que abriu de vez em quando e traz o jogo de volta se ele sair da frente. O jogo e offline e o modo aviao esta ligado
function Start-Explore($dg, $secs) {
    $sz = (& $adb shell wm size) -join " "
    if ($sz -match "Override size: (\d+)x(\d+)" -or $sz -match "(\d+)x(\d+)") { $w = [int]$Matches[1]; $h = [int]$Matches[2] } else { $w = 1080; $h = 2340 }
    Start-Job -ArgumentList $adb, $env:ANDROID_SERIAL, $dg, $w, $h, $secs -ScriptBlock {
        param($adb, $serial, $dg, $w, $h, $secs)
        $env:ANDROID_SERIAL = $serial
        Start-Sleep 5  # o passo do Tinker poe o jogo na frente antes
        $end = (Get-Date).AddSeconds($secs); $n = 0
        while ((Get-Date) -lt $end) {
            $n++
            # toque aleatorio so com o jogo na frente: a tela de pagamento da Play Store (ou qualquer outra) nunca recebe toque
            if (-not ((& $adb shell "dumpsys activity activities | grep -i topResumed") -match $dg)) {
                & $adb shell input keyevent 4; Start-Sleep 1
                & $adb shell monkey -p $dg -c android.intent.category.LAUNCHER 1 | Out-Null; Start-Sleep 4; continue
            }
            # da faixa do meio para baixo: o cenario e a barra de acoes (Construir, Coletar...); o topo so mostra os numeros
            & $adb shell input tap (Get-Random -Minimum ([int]($w * 0.08)) -Maximum ([int]($w * 0.92))) (Get-Random -Minimum ([int]($h * 0.18)) -Maximum ([int]($h * 0.95)))
            Start-Sleep -Milliseconds (Get-Random -Minimum 900 -Maximum 2500)
            if ($n % 6 -eq 0) { & $adb shell input keyevent 4; Start-Sleep 1 }  # fecha a janela que abriu
        }
    }
}

# jogos de verdade liberados (offline, single-player, sem loja de dinheiro real): nome e video do emulador de cada um
$realGames = @{
    # o DYSMANTLE (motor NEXUS) so desenha com Vulkan, e o Vulkan da placa no emulador nao mostra a imagem
    # ("failed to present a swapchain image"): Vulkan por software e tela menor
    "com.the10tons.dysmantle" = @{ nome = "DYSMANTLE"; gpu = "swiftshader_indirect"; small = $true }
    "com.evrika.miner.camp" = @{ nome = "Orecraft"; gpu = "host"; small = $false }
}
# jogo de verdade (offline, single-player): o que nao depende de jogar sozinho. A pessoa deixa os numeros na tela;
# o Tinker monta a lista de itens, poe 999 em dois deles (conferido lendo a tela de novo) e volta o valor de antes; depois
# a pessoa joga 3 min e o 👁 monitor procura o que mudou. O app so aceita os jogos da lista fechada dele (MISSION_GAMES)
function Run-RealGame($dg) {
    $g = $realGames[$dg]
    if (-not $g) { Say "jogo $dg nao liberado (so os offline da lista)"; return }
    $nome = $g.nome
    # o emulador com Play Store (Dysmantle_Play): o DYSMANTLE confere a compra pela Play Store; video pela placa do PC
    # o Dysmantle_Play ja ligado (pode estar em emulator-5556 se o outro ocupou a 5554): usa esse
    foreach ($d in ((& $adb devices) | Select-String "^(emulator-\d+)\s+device" | ForEach-Object { $_.Matches.Groups[1].Value })) {
        if (((& $adb -s $d emu avd name) | Select-Object -First 1) -like "*Dysmantle_Play*") { $env:ANDROID_SERIAL = $d }
    }
    if ($env:ANDROID_SERIAL -eq "emulator-5554") { Start-Emulator "*Dysmantle_Play*" $g.gpu "Normal" }
    & $adb wait-for-device
    if ($g.small) { & $adb shell wm size 720x1560; & $adb shell wm density 320 } else { & $adb shell wm size reset; & $adb shell wm density reset }
    if (-not (Wait-Booted)) { Say "o emulador nao ligou"; return }
    $avd = ((& $adb emu avd name) | Select-Object -First 1)
    if ($avd -notlike "*Dysmantle_Play*") { Say "o emulador ligado e o $($avd): feche ele e rode de novo (os jogos de verdade ficam no Dysmantle_Play)"; return }
    if (-not ((& $adb shell pm list packages $dg) -match $dg)) { Say "o $nome nao esta instalado no Dysmantle_Play: instale pela Play Store dele e rode de novo"; return }
    $script:su = $true  # imagem com Play Store: o adbd nao vira root, o root e o do Magisk
    Write-Host "Se aparecer no emulador um pedido de Superusuario para 'Shell', toque em PERMITIR (Grant)." -ForegroundColor Yellow
    if (-not ((& $adb shell "su -c id" 2>&1) -match "uid=0")) {
        # o app do Magisk sozinho nao da root: o rootAVD instala o Magisk na inicializacao do emulador
        if ((& $adb shell "ls /system/bin/su /sbin/su /debug_ramdisk/su 2>/dev/null") -or ((& $adb shell pm list packages com.topjohnwu.magisk) -match "magisk" -and (& $adb shell "magisk -v" 2>$null))) {
            Say "o Magisk esta la mas o su nao deu root: toque em Permitir no pedido de Superusuario e rode de novo"; return
        }
        Install-Magisk "system-images\android-36\google_apis_playstore\x86_64\ramdisk.img"
        Say "o emulador reinicia com o root: rode o .bat de novo quando ele ligar"; return
    }
    $rel = Get-Release
    $v = $rel.tag_name.TrimStart("v")
    $have = ((& $adb shell dumpsys package $app) | Select-String "versionName=(\S+)" | Select-Object -First 1).Matches.Groups[1].Value
    if ($have -ne $v) { Install-Tinker $rel }
    Set-NoOverlay
    Set-PanelLink  # as chaves da IA (painel da planilha): sem elas a IA que joga dizia "coloque uma chave do Groq"
    & $adb shell monkey -p $dg -c android.intent.category.LAUNCHER 1 | Out-Null
    # sem ninguem: espera o jogo carregar e o Tinker le a tela e toca sozinho nos botoes da entrada (INICIAR, CONTINUAR...)
    Say "abrindo o $nome; passo a tela de entrada sozinho"
    Start-Sleep 25
    Step "jogo $dg" | Out-Null
    for ($k = 0; $k -lt 5; $k++) {
        # so botoes de entrada ("TOQUE PARA MOSTRAR" e a dica do tutorial: quem segue o tutorial e a IA que joga)
        try { $n = Step "tocar INICIAR|CONTINUAR|JOGAR|TOQUE PARA (COME|CONTIN|JOGAR)|PULAR|FECHAR|^OK$|TAP TO|START|PLAY" 120 } catch { Say "  $($_.Exception.Message)"; break }
        if (-not $n) { break }
        Say "  $((($script:last -split "`n")[0]) -replace '^ok ', '')"
        Start-Sleep 8
    }
    # offline de verdade durante o teste: so o JOGO fica sem internet (firewall pelo uid dele); o Tinker continua com a dele,
    # para a IA que joga. Nada do que o Tinker escreve chega a um servidor do jogo (o bloqueio sai no fim)
    # Android 14+: o proprio sistema corta a rede de um app (o iptables com --uid-owner nao existe no kernel do Android 16)
    $net = (& $adb shell cmd connectivity set-package-networking-enabled false $dg 2>&1) -join " "
    if ($net -match "Unknown|unknown|rror|xception") {
        $guid = $null
        & $adb shell cmd connectivity airplane-mode enable; Say "modo aviao ligado no emulador durante o teste (a IA que joga fica sem internet: entra o explorador)"
    } else { $guid = "pkg"; Say "o $nome esta sem internet durante o teste (so ele; o Tinker tem internet)" }
    $script:rr = @()
    function Items { Step "itens" 900 | Out-Null; @(($script:last -split "`n") | Where-Object { $_ -like "item *" } | ForEach-Object { $f = $_ -split ' '; [pscustomobject]@{ nome = $f[1]; v = $f[2]; addr = $f[4] } }) }
    # um teste da lista: falha de um nao para os outros; cada um vai para a planilha com OK/FALHA e o tempo
    function T($name, [scriptblock]$b) {
        $t0 = Get-Date
        try { $r = & $b; $script:rr += "OK $name`: $r ($([int]((Get-Date) - $t0).TotalSeconds) s)" }
        catch { $script:rr += "FALHA $name`: $($_.Exception.Message) ($([int]((Get-Date) - $t0).TotalSeconds) s)" }
        Say $script:rr[-1]
        # ao vivo: cada teste vai para a planilha assim que termina (o resumo da rodada vai no fim)
        Post-Sheet "agente PC: $nome ao vivo" "" $script:rr[-1] $v "$nome (emulador)"
    }
    try {
        Step "jogo $dg" | Out-Null
        Step "limpar" | Out-Null  # salvos de rodadas antigas fora: o teste de reabrir confere so os desta
        $script:it = @()
        # 1. lista de itens: os numeros da tela e o mapa do jogo (Unity), com endereco quando sobrou 1
        T "1 lista de itens (tela + mapa do jogo)" {
            $script:it = Items
            $com = @($script:it | Where-Object { $_.addr -ne "-" }).Count
            if (-not $script:it.Count) { throw "nenhum item" }
            "$($script:it.Count) itens, $com com endereco: " + (($script:it | Select-Object -First 30 | ForEach-Object { "$($_.nome)=$($_.v)" }) -join ", ")
        }
        # 2. pedido "Item = 999" em ate 4 itens: o Tinker prova sozinho (escreve e le a tela); o agente le a tela de novo e
        # volta o valor de antes. O primeiro provado fica salvo para os testes 3 (irmaos) e 7 (reabrir o jogo)
        $script:salvo = $null
        # os maiores primeiro: o 3999 de ouro do Orecraft dava 32 lugares na memoria, o 6 dava 975 mil (e ficava de fora por passar de 999)
        foreach ($i in ($script:it | Where-Object { $_.v -match '^\d+$' -and [double]$_.v -ge 2 } | Sort-Object { [double]$_.v } -Descending | Select-Object -First 4)) {
            $alvo = if ([double]$i.v -lt 999) { "999" } else { "$([long]$i.v + 1000)" }
            T "2 pedido $($i.nome) = $alvo (era $($i.v))" {
                Step "sozinho $($i.nome)=$alvo auto" 420 | Out-Null  # o sozinho tenta ate 5 min; com 340 s o passo estourava antes dele terminar
                $msg = ($script:last -split "`n")[0]
                # logo depois do pedido (a lista de resultados ainda e a dele; o "itens" busca de novo e troca a lista)
                if ($msg -match "Pronto" -and -not $script:salvo) {
                    Step "salvar $($i.nome)" | Out-Null; $script:salvo = $i.nome
                    Step "irmaos" | Out-Null
                    $script:irmaos = (($script:last -split "`n") | Where-Object { $_ -like "irmao *" }).Count
                }
                $tela = (Items | Where-Object nome -eq $i.nome | Select-Object -First 1).v
                Step "escrever $($i.v)" | Out-Null
                $volta = (Items | Where-Object nome -eq $i.nome | Select-Object -First 1).v
                if ($tela -ne $alvo) { throw "a tela mostrou $tela ($msg)" }
                "a tela mostrou $alvo; voltei para $($i.v), a tela mostra $volta"
            }
        }
        # 3. achou um, acha todos: vizinhos com o mesmo formato do primeiro provado (outros recursos do mesmo objeto)
        T "3 achou um, acha todos (vizinhos do $($script:salvo))" {
            if (-not $script:salvo) { throw "nenhum pedido provado para comecar" }
            "$($script:irmaos) parecidos na memoria"
        }
        # 4. velocidade do jogo (Unity timeScale): acha e poe 2x por 10 s, depois volta a 1x
        T "4 velocidade do jogo" {
            $n = Step "velocidade"
            if (-not $n) { throw "nao achei o timeScale" }
            Step "velocidade 2" | Out-Null; Start-Sleep 10; Step "velocidade 1" | Out-Null
            "$n lugar(es); 2x por 10 s e voltei a 1x"
        }
        # 5. a IA joga 3 min sozinha (abre menus e a loja, compra com moeda do jogo) enquanto o monitor olha a memoria;
        # sem IA (sem chave/cota): toques gravados ou o explorador
        T "5 IA joga 3 min + monitor de memoria" {
            $ia = 0
            # a IA sem conexao ("Sem conexão com o Groq") nao jogou: entra o explorador
            try { Step "jogar 180" | Out-Null; $ia = @(($script:last -split "`n") | Where-Object { $_ -like "ia IA:*" -and $_ -notmatch "Sem conex|Unable to resolve|chave" }).Count } catch { Say "a IA nao jogou: $($_.Exception.Message)" }
            # internet do emulador (para saber se a falha da IA e do emulador ou so do Tinker)
            $dns = if ((& $adb shell "ping -c 1 -W 3 api.groq.com" 2>&1) -match "bytes from") { "emulador alcanca o Groq" } else { "emulador sem DNS/internet" }
            if (-not $ia) {
                $macro = "$env:USERPROFILE\tinker-macro-$dg.txt"
                $job = if (Test-Path $macro) { Start-Replay $macro 180 } else { Start-Explore $dg 180 }
                try { Step "monitorar 180" | Out-Null } finally { Stop-Job $job; Remove-Job $job -Force }
            }
            $l = $script:last -split "`n"
            $mon = @($l | Where-Object { $_ -like "mudou *" })
            $agora = Items
            # os que mudaram e tem o valor de um item da tela agora: candidatos de verdade
            $bate = @($mon | Where-Object { $m = ($_ -split ' ')[2]; $agora | Where-Object v -eq $m })
            $quem = if ($ia) { "a IA jogou ($ia vezes): " + (($l | Where-Object { $_ -like "ia IA:*" } | Select-Object -Last 2) -join " / ") } else { "sem IA (explorador; $dns)" }
            if (-not $mon.Count) { throw "$quem | o monitor nao viu nada mudar" }
            "$quem | monitor: $($mon.Count) mudaram por evento, $($bate.Count) com o valor de um item da tela: " + ($bate -join ", ") + " | " + (($l | Where-Object { $_ -like "log *" }) -join " / ")
        }
        # 6. cronometros (construcao, recarga): depois da IA jogar costuma haver algo construindo
        T "6 cronometros (construcao)" {
            $n = Step "cronometros"
            if (-not $n) { throw "nenhum cronometro achado (nada construindo agora?)" }
            "$n achado(s): " + ((($script:last -split "`n") | Where-Object { $_ -like "timer *" } | Select-Object -First 5) -join ", ")
        }
        # 7. reabrir o jogo: o salvo do teste 2 tem de voltar sozinho no endereco novo (ponteiro), sem procurar de novo
        T "7 reabrir o jogo (o salvo volta sozinho)" {
            if (-not $script:salvo) { throw "nenhum salvo (nenhum pedido provado)" }
            & $adb shell am force-stop $dg; Start-Sleep 2
            & $adb shell monkey -p $dg -c android.intent.category.LAUNCHER 1 | Out-Null; Start-Sleep 35
            Step "jogo $dg" | Out-Null; Start-Sleep 20
            Step "salvos" | Out-Null
            $l = ($script:last -split "`n") | Where-Object { $_ -like "salvo *" }
            if (-not ($l -match " voltou ")) { throw "nao voltou: $($l -join ' / ')" }
            ($l -join " / ")
        }
    } catch { $script:rr += "parou: $($_.Exception.Message)"; Say $script:rr[-1] }
    $res = $script:rr
    $ok = @($res | Where-Object { $_ -like "OK *" }).Count
    # o registro do Tinker vai junto: mostra onde cada passo gastou o tempo e o que achou
    $res += "=== registro do Tinker ===`n" + ((Sh "tail -n 80 /data/data/$app/files/tinker.log") -join "`n")
    if ($guid) { & $adb shell cmd connectivity set-package-networking-enabled true $dg | Out-Null } else { & $adb shell cmd connectivity airplane-mode disable }
    $fails = ($res | Where-Object { $_ -like "FALHA*" -or $_ -like "parou*" }) -join "`n"
    Post-Sheet "agente PC: $nome de verdade, $ok de $(@($script:rr | Where-Object { $_ -match '^(OK|FALHA) ' }).Count) ok" $fails ($res -join "`n") $v "$nome (emulador)"
    Say "rodada pronta (resultado na planilha). Espero a proxima versao do Tinker."
}

# jogo de verdade: fica ligado como o agente de sempre; a cada versao nova do Tinker roda uma rodada sozinho no jogo
if ($Jogo) {
    $doneJ = "$env:USERPROFILE\tinker-agente-testado-$Jogo.txt"
    Say "agente do $($realGames[$Jogo].nome) ligado: a cada versao nova do Tinker (e a cada 3 h) faco uma rodada sozinho. Deixe esta janela aberta; Ctrl+C para sair."
    while ($true) {
        Update-Self
        try {
            $v = (Get-Release).tag_name.TrimStart("v")
            # versao nova, ou 3 h desde a ultima rodada: o agente offline aprende um pouco mais a cada rodada (acertos e erros)
            $velha = -not (Test-Path $doneJ) -or ((Get-Date) - (Get-Item $doneJ).LastWriteTime).TotalHours -ge 3
            if ((Get-Content $doneJ -ErrorAction SilentlyContinue) -ne $v -or $velha) { Run-RealGame $Jogo; Set-Content $doneJ $v }
        } catch { Say "erro: $($_.Exception.Message)" }
        Start-Sleep 60
    }
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
