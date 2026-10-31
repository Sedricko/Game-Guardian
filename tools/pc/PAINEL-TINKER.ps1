# Painel Tinker: uma janela (pagina local, so neste PC) para acompanhar e controlar o agente do Orecraft. Mostra a rodada
# ao vivo, a tela do emulador, o roteiro (quais testes rodam), cenarios prontos (missoes do Tinker), os logs e manda recado
# para o Claude pela planilha. Conversa com o agente pelos arquivos tinker-agente-* da pasta do usuario.
$ErrorActionPreference = "Continue"
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
$port = 8766
$token = [guid]::NewGuid().ToString("N").Substring(0, 16)  # so a janela aberta por este painel fala com ele
$tokenF = "$dir\painel-token.txt"  # o PainelTinker.exe aberto de novo reabre a janela do painel que ja esta rodando
$script:job = $null
$script:ultimo = ""

function Serial {
    $ds = @((& $adb devices) | Select-String "^(emulator-\d+)\s+device" | ForEach-Object { $_.Matches.Groups[1].Value })
    foreach ($d in $ds) { if (((& $adb -s $d emu avd name) | Select-Object -First 1) -like "*Dysmantle_Play*") { return $d } }
    $ds | Select-Object -First 1
}

function Agentes { @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like "*TINKER-AGENTE.ps1*" }) }

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
    function Sh($c) {
        if (((& $adb -s $ser shell id -u) -join "").Trim() -eq "0") { & $adb -s $ser shell $c } else { & $adb -s $ser shell "su -c '$($c -replace "'", "'\''")'" }
    }
    $out = @()
    foreach ($cmd in $cmds) {
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
    param($op, $sdk, $app, $jogo, $tok)
    $adb = "$sdk\platform-tools\adb.exe"; $emu = "$sdk\emulator\emulator.exe"
    $ser = @((& $adb devices) | Select-String "^(emulator-\d+)\s+device" | ForEach-Object { $_.Matches.Groups[1].Value }) | Select-Object -First 1
    function Su($c) { & $adb -s $ser shell "su -c '$($c -replace "'", "'\''")'" }
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
            Start-Process cmd -ArgumentList "/k cd /d `"$($bat.DirectoryName)`" && rootAVD.bat system-images\android-36\google_apis_playstore\x86_64\ramdisk.img"
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
        "jogo" { if (-not $ser) { return "ligue o emulador antes" }; & $adb -s $ser shell monkey -p $jogo -c android.intent.category.LAUNCHER 1 | Out-Null; return "Orecraft aberto" }
        "abrirtinker" { if (-not $ser) { return "ligue o emulador antes" }; & $adb -s $ser shell am start -n "$app/.MainActivity" | Out-Null; return "Tinker aberto" }
        default { return "operacao desconhecida: $op" }
    }
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

function Estado {
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
    if ($script:job -and $script:job.State -ne "Running") { $script:ultimo = (Receive-Job $script:job -ErrorAction SilentlyContinue) -join "`n"; Remove-Job $script:job -Force; $script:job = $null }
    @{
        agente = ($ag.Count -gt 0); pausado = (Test-Path $pausaF); emulador = [string]$ser; jogoAberto = $jogoAberto; tinker = $tinker
        root = $root; tinkerVer = $tinkerVer; jogoInstalado = $jogoInstalado
        rodada = $rod; roteiro = @(Get-Content $roteiroF -ErrorAction SilentlyContinue | Where-Object { $_.Trim() })
        mantidos = @(Get-Content $mantidosF -Tail 15 -ErrorAction SilentlyContinue)
        planilha = [bool]((Get-Content $cfg -ErrorAction SilentlyContinue) -join "").Trim(); token = [bool]((Get-Content $tok -ErrorAction SilentlyContinue) -join "").Trim()
        cenario = $(if ($script:job) { "rodando" } else { "" }); resultado = $script:ultimo
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
        "parar" { $n = 0; foreach ($p in Agentes) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; $n++ }; return "agente parado ($n)" }
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
            $script:job = Start-Job -ScriptBlock $emuOp -ArgumentList $a.op, $sdk, $app, $jogo, $tok
            return "comecei: $($a.op) (o resultado aparece aqui embaixo)"
        }
        "config" {
            if ($a.link) { if ($a.link -notmatch '^https://script\.google\.com/') { throw "o link precisa comecar com https://script.google.com/" }; Set-Content $cfg $a.link.Trim() }
            if ($a.token) { Set-Content $tok $a.token.Trim() }
            return "guardado neste PC"
        }
        "sair" { $script:sair = $true; return "painel fechando (o bot continua)" }
        "claude" { return (Planilha "painel: recado para o Claude" ([string]$a.texto)) }
        default { throw "acao desconhecida: $($a.acao)" }
    }
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
Abrir "http://localhost:$port/?t=$token"
Write-Host "Painel Tinker aberto ($url). Deixe esta janela aberta; fechar ela fecha o painel." -ForegroundColor Cyan

$script:sair = $false
while ($h.IsListening -and -not $script:sair) {
    $ctx = $h.GetContext()
    $req = $ctx.Request; $res = $ctx.Response
    try {
        if ($req.QueryString["t"] -ne $token) { Send $res 403 "text/plain" "abra pelo atalho Painel Tinker"; continue }
        $body = ""
        if ($req.HasEntityBody) { $r = New-Object IO.StreamReader($req.InputStream, [Text.Encoding]::UTF8); $body = $r.ReadToEnd(); $r.Close() }
        switch ($req.Url.AbsolutePath) {
            "/" { Send $res 200 "text/html" ((Get-Content "$dir\painel-tinker.html" -Raw -Encoding UTF8).Replace("__TOKEN__", $token)) }
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
