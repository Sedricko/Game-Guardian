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
$roteiroF = "$U\tinker-agente-roteiro.txt"
$pausaF = "$U\tinker-agente-pausa.txt"
$estadoF = "$U\tinker-agente-estado.json"
$mantidosF = "$U\tinker-mantidos-$jogo.txt"
$port = 8766
$token = [guid]::NewGuid().ToString("N").Substring(0, 16)  # so a janela aberta por este painel fala com ele
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
    $jogoAberto = $false; $tinker = $false
    if ($ser) {
        $jogoAberto = [bool]((& $adb -s $ser shell pidof $jogo) -join "")
        $tinker = [bool]((& $adb -s $ser shell pidof $app) -join "")
    }
    if ($script:job -and $script:job.State -ne "Running") { $script:ultimo = (Receive-Job $script:job -ErrorAction SilentlyContinue) -join "`n"; Remove-Job $script:job -Force; $script:job = $null }
    @{
        agente = ($ag.Count -gt 0); pausado = (Test-Path $pausaF); emulador = [string]$ser; jogoAberto = $jogoAberto; tinker = $tinker
        rodada = $rod; roteiro = @(Get-Content $roteiroF -ErrorAction SilentlyContinue | Where-Object { $_.Trim() })
        mantidos = @(Get-Content $mantidosF -Tail 15 -ErrorAction SilentlyContinue); planilha = (Test-Path $cfg)
        cenario = $(if ($script:job) { "rodando" } else { "" }); resultado = $script:ultimo
    }
}

function Acao($a) {
    switch ($a.acao) {
        "ligar" {
            Remove-Item $pausaF -ErrorAction SilentlyContinue
            if ((Agentes).Count) { return "o agente ja esta ligado" }
            Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$dir\TINKER-AGENTE.ps1`" -Jogo $jogo" -WindowStyle Minimized
            return "agente ligado (janela minimizada na barra de tarefas)"
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
try { $h.Start() } catch { Write-Host "A porta $port esta ocupada: o painel ja esta aberto? Feche a outra janela do painel." -ForegroundColor Yellow; pause; exit }
$url = "http://localhost:$port/?t=$token"
$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($edge) { Start-Process $edge "--app=$url --window-size=1400,900" } else { Start-Process $url }
Write-Host "Painel Tinker aberto ($url). Deixe esta janela aberta; fechar ela fecha o painel." -ForegroundColor Cyan

while ($h.IsListening) {
    $ctx = $h.GetContext()
    $req = $ctx.Request; $res = $ctx.Response
    try {
        if ($req.QueryString["t"] -ne $token) { Send $res 403 "text/plain" "abra pelo atalho Painel Tinker"; continue }
        $body = ""
        if ($req.HasEntityBody) { $r = New-Object IO.StreamReader($req.InputStream, [Text.Encoding]::UTF8); $body = $r.ReadToEnd(); $r.Close() }
        switch ($req.Url.AbsolutePath) {
            "/" { Send $res 200 "text/html" ((Get-Content "$dir\painel-tinker.html" -Raw -Encoding UTF8).Replace("__TOKEN__", $token)) }
            "/api/estado" { Send $res 200 "application/json" ((Estado) | ConvertTo-Json -Depth 6 -Compress) }
            "/api/log" { Send $res 200 "text/plain" ((Get-Content $log -Tail ([int]("0" + $req.QueryString["n"]) + 1) -Encoding UTF8 -ErrorAction SilentlyContinue) -join "`n") }
            "/api/acao" { Send $res 200 "application/json" (@{ ok = $true; msg = (Acao ($body | ConvertFrom-Json)) } | ConvertTo-Json -Compress) }
            default { Send $res 404 "text/plain" "nao achei" }
        }
    } catch {
        try { Send $res 200 "application/json" (@{ ok = $false; msg = $_.Exception.Message } | ConvertTo-Json -Compress) } catch { }
    }
}
