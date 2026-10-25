# Prepara o emulador do Android Studio para os testes do Tinker: root para apps (Magisk, via rootAVD) e o Tinker instalado.
# Fala so com o emulador (emulator-5554): o celular no cabo nao e tocado.
# Rodar: botao direito no arquivo > "Executar com o PowerShell" (ou: powershell -ExecutionPolicy Bypass -File PREPARAR-EMULADOR.ps1)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"  # sem a barra, o download no PowerShell 5 fica 10x mais rapido
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$env:ANDROID_SERIAL = "emulator-5554"
$env:PATH += ";$sdk\platform-tools"
$dir = "C:\rootAVD"
function Passo($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

try {
    Passo "1. Emulador ligado?"
    if (-not ((& $adb devices) -match "emulator-5554\s+device")) { throw "Abra o emulador Pixel 6 Pro (API 31) no Android Studio e rode de novo." }
    Write-Host "ok"

    Passo "2. Root para apps (Magisk)"
    if (-not ((& $adb shell pm list packages com.topjohnwu.magisk) -match "magisk")) {
        if (-not (Test-Path "$dir\rootAVD-master")) {
            Write-Host "Baixando o rootAVD do projeto oficial (gitlab.com/newbit/rootAVD)..."
            Invoke-WebRequest "https://gitlab.com/newbit/rootAVD/-/archive/master/rootAVD-master.zip" -OutFile "$env:TEMP\rootAVD.zip"
            Expand-Archive "$env:TEMP\rootAVD.zip" $dir -Force
        }
        $bat = Get-ChildItem "$dir\rootAVD-master" -Filter "rootAVD.bat" | Select-Object -First 1
        if (-not $bat) { throw "O pacote do rootAVD nao tem rootAVD.bat: mande para o Claude o print de: dir $dir\rootAVD-master" }
        $ramdisk = "system-images\android-31\google_apis\x86_64\ramdisk.img"
        if (-not (Test-Path "$sdk\$ramdisk")) { throw "Nao achei $sdk\$ramdisk : o emulador precisa ser API 31, Google APIs, x86_64." }
        Set-Location $bat.DirectoryName
        Write-Host "Instalando o Magisk no emulador. Se perguntar a versao, aperte Enter (padrao). No fim o emulador desliga sozinho."
        & $bat.FullName $ramdisk
        Write-Host "`nAgora: no Android Studio ligue o emulador de novo (Cold Boot Now), abra o app Magisk uma vez e rode este script de novo." -ForegroundColor Yellow
        exit
    }
    Write-Host "Magisk instalado"

    Passo "3. Tinker mais novo"
    $rel = Invoke-RestMethod "https://api.github.com/repos/Sedricko/Game-Guardian/releases/latest" -Headers @{ "User-Agent" = "Tinker" }
    $apk = ($rel.assets | Where-Object name -like "*.apk" | Select-Object -First 1).browser_download_url
    Write-Host "Baixando $($rel.tag_name)..."
    Invoke-WebRequest $apk -OutFile "$env:TEMP\Tinker.apk"
    & $adb install -r "$env:TEMP\Tinker.apk"
    # permissoes que pediriam toque: menu flutuante e notificacao
    & $adb shell appops set dev.tinker.app SYSTEM_ALERT_WINDOW allow
    & $adb shell pm grant dev.tinker.app android.permission.POST_NOTIFICATIONS 2>$null
    & $adb shell am start -n dev.tinker.app/.MainActivity | Out-Null

    Passo "Pronto. Falta so, no emulador, uma vez:"
    Write-Host "  1. Quando o Magisk perguntar, toque em Permitir (root para o Tinker)."
    Write-Host "  2. Tinker > Ajustes > Assistente > Link do painel: cole o link da planilha."
    Write-Host "  3. Tinker > Ajustes: ligue 'Atualizar e testar sozinho'."
    Write-Host "  4. Rode o Autoteste uma vez e avise o Claude."
} catch {
    Write-Host "`nERRO: $($_.Exception.Message)" -ForegroundColor Red
}
Read-Host "`nEnter para fechar"
