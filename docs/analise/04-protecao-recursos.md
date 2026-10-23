# 04 — Manifesto, proteção, rede e recursos (GameGuardian 101.4)

Análise só estática. `S` = `/tmp/claude-0/-home-user-Game-Guardian/9ac12f92-e845-5f12-994d-71efb8d20b9a/scratchpad`.
Caminhos `android/ext/X.java:N` são relativos a `S/src/sources`. O manifesto decodificado está em `S/dec/AndroidManifest.decoded.xml` (é igual a `S/man.xml`), as strings em inglês em `S/dec/strings_en.txt`, os layouts decodificados em `S/dec/layout/` e o mapa de IDs em `S/dec/public.xml`.

Legenda: **[C]** = confirmado no código ou nos recursos · **[H]** = hipótese (inferência razoável, não verificada).

---

## 1. Manifesto

### 1.1 Identidade
| Campo | Valor | Nota |
|---|---|---|
| package | `catch_.me_.if_.you_.can_` | É um *placeholder* de 24 caracteres, reescrito no próprio aparelho a cada instalação (ver §2.4) [C] |
| versionName / versionCode | 101.4 / 16155 | Também são trocados por valores aleatórios na reinstalação [C] |
| minSdk / targetSdk / compileSdk | 21 / **24** / 34 | O targetSdk baixo é proposital (ver abaixo) |
| label do app | `GameGuardian`; as **atividades se chamam "Game"** | Disfarce; `android:isGame="true"` [C] |
| outros | `allowBackup=true`, `extractNativeLibs=false`, `hardwareAccelerated=false`, `max_aspect=3.0` | |
| atributos estranhos | `cache="Application"`, `plague="black"`/`"white"` sem namespace | [H] lixo para confundir parsers e ferramentas de repack (o Android ignora) |

**Por que targetSdk 24** [C quanto às regras do Android, H quanto à intenção]: com target < 29 o app escapa da regra W^X do Android 10 (executar binários gravados em `/data/data/<pkg>`; o daemon é extraído de `res/raw/ydw*` e executado), do *scoped storage*, e com target < 26 também dos limites de serviço em segundo plano. Com target < 23 as permissões seriam pedidas na instalação; com 24 vale o modelo de tempo de execução, mas sem as restrições mais novas.

### 1.2 Permissões (manifesto linhas 3–23)
| Permissão | Uso provável | Evidência |
|---|---|---|
| ACCESS_SUPERUSER | Declaração legada do SuperSU, para pedir root | [H] |
| SYSTEM_ALERT_WINDOW | Ícone e painel flutuantes sobre o jogo | `FloatPanel`, `z.java:611` checa o appop `system_alert_window` [C] |
| READ_CLIPBOARD / WRITE_CLIPBOARD | Copiar valores e endereços (READ_CLIPBOARD não é permissão AOSP) | `Tools.java:159` [C] |
| READ/WRITE_EXTERNAL_STORAGE, WRITE_MEDIA_STORAGE | Scripts Lua, dumps, listas salvas, logcat em /sdcard; a WRITE_MEDIA é só de sistema | [C]/[H] |
| VIBRATE | Retorno tátil | [H] |
| INTERNET, ACCESS_NETWORK_STATE | Checagem de versão, telemetria, `gg.makeRequest` do Lua | `f1.java:268`, `z2.java:62`, `Script.java:3129` [C] |
| GET_TASKS, REAL_GET_TASKS, GET_DETAILED_TASKS | Listar processos/jogos em execução | `z1.java:750,872` [C] |
| INTERACT_ACROSS_USERS(_FULL) | Multiusuário, perfil de trabalho, espaços virtuais (só sistema; útil quando concedida via root/pm) | [H] |
| READ_LOGS | Gravar logcat para relatório de erro | `n0.java:430`, `h0.java:193` [C] |
| UPDATE_APP_OPS_STATS | `AppOpsManager.setMode` por reflexão para se dar o overlay (correção MIUI) | `q1.java:201` [C] |
| CHANGE_COMPONENT_ENABLED_STATE | Esconder ícones do launcher (`setComponentEnabledSetting`) | `Tools.java:3424-3436` [C] |
| REQUEST_INSTALL_PACKAGES / REQUEST_DELETE_PACKAGES | Instalar a cópia com nome aleatório e desinstalar o instalador | `b1.java:4011`, `b1.java:1333` [C] |
| POST_NOTIFICATIONS | Notificação opcional ("use-notification") | `Config.java:1179` [C] |

Os `uses-feature` (linhas 24–29) incluem **nomes de permissão** (`ACCESS_FINE_LOCATION`, `RECORD_AUDIO`) como se fossem features, todos com `required=false`. Não têm efeito [C]; [H] servem de ruído ou para não filtrar o app em lojas.

O app **não** pede READ_SMS, mas checa se a tem (ver §2.6).

### 1.3 Componentes (todos são subclasses de 1 linha em `catch_/me_/if_/you_/can_/*.java`; a lógica fica em `android.ext`)
| Componente | Classe real | exported | Função |
|---|---|---|---|
| `Application` | `android.ext.l` | – | Inicialização |
| `MainActivity` (label "Game", **SW**, sem aceleração de hardware) | `android.ext.r` | **sim** (implícito: tem intent-filter e target < 31) | Lançador 1 |
| `ActivityMain` (label "Game", **HW**, com aceleração) | `android.ext.r` | **sim** | Lançador 2; também LEANBACK (TV) |
| `Receiver` | `android.ext.w2` | **sim**, ação = nome do pacote | Ações da notificação. Protegido por um *nonce* aleatório por processo (`check-hash`) e checa a instância viva (`w2.java:12,38-41`) [C] |
| `FileProvider` | `d.c` | não | Compartilhar arquivos/APK para instalar (`b1.java:2910`) |
| `AnalyticsService` | `android.ext.v` | não | Apesar do nome é o "BootstrapService" (serviço de primeiro plano que mantém o processo vivo). Também mata o app se achar apps de bloqueio (XPrivacy, Brevent etc.; `n0.java:333`, `v.java:33-46`) [C] |
| `<instrumentation>` | `android.ext.u` | – | O GG chama `startInstrumentation` em **si mesmo** para rodar o serviço principal num processo de instrumentação (`f1.java:73`, `f1.java:674`). [H] serve para sobreviver a *task killers* e às regras de background e para não aparecer como atividade |

---

## 2. Ofuscação e autoproteção

### 2.1 Nomes de classes [C]
- R8/ProGuard renomeia tudo para `a`…`z`, `a0`…`z3` dentro dos pacotes **`android.ext`, `android.fix`, `android.lang`, `android.app`**. O namespace falso "android.*" faz o código parecer framework em *stack traces* e em varreduras de classes [H].
- Sobraram nomes legíveis nas classes acessadas por reflexão ou JNI (`MainService`, `Config`, `Tools`, `InOut`, `Script`…).
- `catch_.me_.if_.you_.can_` contém só *stubs*. Os nomes precisam ficar fixos porque são **reescritos em binário** dentro do dex, do manifesto e do arsc (§2.4). O nome do pacote também é montado em tempo de execução para não aparecer como literal: `"catch_#me_#if_#you_#can_".replace('#','.')` e `"@ame@uardian".replace('@','G')` (`android/ext/i.java:12-31`).

### 2.2 Criptografia de strings [C]
- O decifrador é **`Tools.removeNewLinesChars(String)`** (`android/ext/Tools.java:3194-3211`). O nome é enganoso: o método só faz **César −3** (`c - 3` em cada caractere). Exemplo: `"kwws=22jdphjxdugldq1qhw"` vira `http://gameguardian.net`.
- São 56 chamadas diretas, mais strings `"kwws…"` passadas a outros helpers (`r.n(...)`, `ConfigListAdapter.java:60,1702`). Decifrei todas. Os destaques: URLs, nomes de classes e métodos para reflexão, comandos shell (`exec pm install -f`, `cp … && chmod 0644 … && pm install -f`, `umask 077; exec mkdir`), `/data/local/tmp/.`, `/data/GG.skip.install`, `res/raw/ydw`.
- Constantes numéricas ficam escondidas em aritmética de float: `(110.5f*1)-9.1f` = 101.4 e `(16577.3f-422.3f+0.5f)` = 16155 (`n0.java:365-369`, `ConfigListAdapter.java:1571`, `b1.java:1264`).

### 2.3 Anti-tamper de sabotagem silenciosa [C no Java; efeito no daemon = H]
**a) `h2.updateLocale()`** (`android/ext/h2.java:31-128`). O nome é disfarce: o método está numa classe de item salvo e roda no `static {}`. O que ele faz:
1. Calcula `(agora − lastUpdateTime) >> 26`, que só fica ≠ 0 depois de **~18,6 h** da instalação (linha 42). A punição é **atrasada** para que quem modificou o APK não ligue a causa ao efeito.
2. Varre o próprio APK. Qualquer entrada `assets/`, `res/raw/` (exceto `res/raw/ydw*`) ou `classes2.dex` indica um *mod menu* injetado. Se achar, faz XOR em `InOut.byteOrderMask` (linhas 43-77).
3. Confere se `ConfigListAdapter.DEVS` ainda contém `d2dyno, Enyby, Trasd, Aqua`, ou seja, se os créditos foram removidos (linhas 85, 95-105).
4. Procura nas strings de UI (download, front_text, help…) a marca de revendedores: `kirin`, `t.me/`, `senpai`, `discord.gg/`, `paypal`, `######`, `revo` (linhas 87-90). [H] Esse passo parece inerte: chama `Class.forName("android.ext.Re")`, que não existe no dex (provavelmente foi renomeada pelo R8), e o `catch` engole o erro.
- **Efeito**: `InOut.byteOrderMask` começa em **15** (`InOut.java:17`) e com o APK íntegro termina em **0**. Ele é aplicado como XOR em todo inteiro enviado ao daemon (`InOut.java:480-482`). Se alguém remover o bloco estático, a máscara fica em 15. Se o APK foi alterado, ela fica ≠ 0. Nos dois casos [H] o protocolo com o daemon passa a receber valores corrompidos e o app "funciona mal" sem mensagem de erro.

**b) Armadilha da permissão INTERNET** (`FloatPanel.java:100-111`). Por reflexão, o app chama `checkPermission(INTERNET, myPid, myUid)`. Com a permissão presente o resultado é 0, e a divisão `pid/0` lança exceção, que é engolida. Se alguém **removeu INTERNET** do manifesto para bloquear a telemetria, o resultado é −1 e, fora de espaço virtual, o código executa `MainService.instance = null` por reflexão. O app então quebra [C na lógica].

**c) Hash do APK** `b1.X()` (`b1.java:2674-2777`). Faz SHA-384 de cada entrada do APK mais tamanhos e CRC, e anexa o resultado ("Hash:") a todo relatório de crash ou log enviado ao servidor (`n0.java:387,496,570`). Assim os autores conseguem identificar builds modificados [C].

### 2.4 Reinstalação com identidade aleatória ("instalador") — `android/ext/b1.java` [C]
Fluxo (strings `install_help_intro`, `installing` e `help_faq_15/17/18`: "reinstala-se com dados aleatórios para evitar detecção por anti-cheats"):
1. **Gera a identidade** (`b1.java:1013-1049`, `f0()` em `3077-3119`):
   - pacote = `com.` + letras aleatórias com segmentos separados por ".", até o tamanho do placeholder (24);
   - label = palavras em Title Case aleatórias, com no máximo o tamanho de "GameGuardian";
   - versionName = dígitos aleatórios no formato de "101.4";
   - versionCode = aleatório entre 1 e 2×16155 (`E0()`, `b1.java:1263`);
   - o pacote não pode coincidir com um já instalado (`e0()`, `b1.java:3017`).
2. **Reescreve o APK em binário**:
   - manifesto: package, label, versão e os 7 componentes (`M()`, `b1.java:2069-2085`, via `android.ext.s`);
   - ícone: pode trocar por variantes alternativas `icon_gg/icon_hw/icon_sw` (byte `y`);
   - **dex**: `fixDex` troca, na tabela de strings, `catch_/me_/if_/you_/can_` pelo novo nome. O novo nome não pode ser mais longo que o original (`b1.java:1520-1523`). Depois recalcula a assinatura SHA-1 e o checksum Adler32 do cabeçalho dex (`b1.java:1655-1668`);
   - copia as bibliotecas `ydw3a`/`ydw3x` para `lib/<abi>/libAndroid.so` (`b1.java:1785-1786`).
3. **Assina com chave nova**: gera um par **RSA-2048** a cada instalação, com certificado X.509 autoassinado, CN = hex aleatório e validade de 0001 a 2099 (`m0.java:247-280`). Escreve MANIFEST.MF, CERT.SF e CERT.RSA (v1 / JAR signing) (`b1.java:2372-2388`). Os modos de teste `tamper-cert-sf`/`tamper-cert-rsa` invertem um bit de propósito (`b1.java:1807-1813`).
4. **Verifica o APK gerado** (`b1.java:1990-2066`): pacote, versão, label, que haja exatamente 1 assinatura, que a assinatura **não seja a oficial** (`signature-official`), os nomes dos componentes, a instrumentação e o provider.
5. **Instala**: com root usa `cp … /data/local/tmp/<uuid>/base.apk && chmod 0644 && pm install -f`. Sem root usa o `ACTION_INSTALL_PACKAGE` normal via FileProvider. Em seguida `pm uninstall catch_.me_.if_.you_.can_` remove o instalador original (`b1.java:1333`, strings decifradas em `b1.java:3328-3354`).
- **Para pular a reinstalação**: crie o arquivo `/data/GG.skip.install` (`s1.java:628`, `b1.java:1176`), ou rode dentro de um espaço virtual.
- Resultado: cada aparelho tem um GG com pacote, nome, versão, assinatura e hash diferentes. Isso derruba detecção por nome de pacote, por certificado ou por lista de pacotes conhecidos.

### 2.5 Outras formas de esconder o GG
- **"Hide GG from the game"** (config `hide`, `Config.java:1213`, multiescolha de 4 itens). Os bits 1/2/4/8 viram as flags 128/256/512/32768 de `Config.A`, enviadas ao daemon (`Config.java:2745-2757`). O mecanismo em si é nativo [H: esconder `/proc`, mapas e ptrace do jogo]. As strings mostram os efeitos: o item 1 pode causar lag com *freeze*, e o item 4 desliga speedhack, unrandomizer e allocate page (`hide_1_warn`, `hide_4_warn`). Por isso [H] o item 4 deve evitar ptrace e injeção no jogo.
- **Esconder ícones do launcher**: são dois launchers ("Game (SW)" e "Game (HW)"), e cada um pode ser desativado por `setComponentEnabledSetting` (`Tools.w1`, `Tools.java:3424`; strings `hide_icons`, `cannot_hide_launcher_icon`).
- **Ícone flutuante** com transparência e "esconder após N s" (`service_config_icon.xml`, `config_icon_text`).
- **`service_config_hide.xml` não é "esconder o GG"**. É um contêiner de **opções de configuração ocultas da UI** (todas com `visibility=gone`): `selinux`, `xor-key`, `context-source`, `float-type`, `float-flags`, `kbd-small`, `time-jump-last`, `copy-params`, `record-logcat`. Elas correspondem às entradas com label `empty` em `Config.java:1167-1182` [C]. O layout não é referenciado no Java [C]; [H] é um resquício ou serve só para gerar os IDs.
- **Espaço virtual com root** (`vspace-root`, `Config.java:1204`), e o guia "vspace_hide": Virtual Xposed, App Hider, "Calculator", "Notepad" etc.
- Nome do processo: o GG não troca o nome do processo no Java [C, nada encontrado]. Ele depende do pacote aleatório e do processo de instrumentação.

### 2.6 Detecção de ambiente [C]
- **Espaço virtual**: checa se tem READ_SMS (`r.java:669,900`). O GG não declara essa permissão, então tê-la significa que está rodando dentro de um app hospedeiro, como Parallel Space, que a possui [H na intenção]. Também lê `/proc/self/exe` e a profundidade de `filesDir`.
- **Apps hostis** que matam o GG ou bloqueiam permissões: XPrivacy, Brevent, Prevent Running, MIUI Whetstone, Nubia NeoSafe, `com.chaozhuo.permission.controller` (`n0.java:333`, `f1.java:1043`).
- **Bug de locale com `%e`** que afeta a formatação de floats (`f1.java:1025-1035`).

---

## 3. Rede [C]
Todos os destinos ficam em **`gameguardian.net`**, a maioria via **HTTP sem TLS**:

| URL (decifrada) | Quando | O que envia ou recebe |
|---|---|---|
| `http://gameguardian.net/GG_logs/version.php?s=%d&v=%s&l=%s&t=%s&a=%d&o=%s&u=%s` + `&m0..m2=` + `&mc=` | Na inicialização, thread "checkNewVersion" (`f1.java:1040`, `f1.java:233-323`) | **Na query**: SDK, versão, locale, `lang_code`, arquitetura do daemon (`e0.f()`), tela (`f1.v()`), `su`/vspace (`f1.x()`), contadores `m-*` (`p1.java:46`) e `mc` (`ConfigListAdapter.java:1189`). **No POST, em gzip**: os contadores de **uso de recursos** `used-0..3`, que o daemon informa via `MainService.S()` → `a3`, ou seja, telemetria de quais funções foram usadas. **Resposta**: lista de versões/changelog. Uma linha com "ARMv5" atualiza `i.f999a`, a última versão que suporta ARMv5 |
| `http://gameguardian.net/GG_logs/usage.php` | Crash ou erro (`n0` → `z2.d`) | POST com texto em gzip+base64: versão, Android, SDK, vspace, stack trace, **hash do APK** e às vezes o logcat. Não envia se já houver versão nova (log "NLV") (`z2.java:60-104`) |
| `http://gameguardian.net/version.txt` | Botão de changelog (`ConfigListAdapter.java:107`) | Só lê |
| `/download`, `/forum`, `/help/`, `/noroot`, `forum/topic/29861-donate/`, ~40 tutoriais da galeria, 2 vídeos do YouTube (`q1.java:259`) | Links abertos no navegador | – |
| `support.google.com/android/answer/6088895` | Ajuda de "fontes desconhecidas" (`r.java:140`) | – |
| URLs arbitrárias via Lua `gg.makeRequest` | Scripts (`Script.java:3129-3200`, HttpURLConnection com GET/POST, headers e redirecionamentos) | Outro agente cobre |

- **Sem SDK de anúncios ou analytics de terceiros** (nada de AdMob, Firebase ou similares). O "Donate" só abre o fórum (`ConfigListAdapter.java:1702`). O "AnalyticsService" é o serviço de bootstrap.
- Não há pinning nem TLS. [H] Um MITM poderia injetar changelog falso ou alterar `f999a`, mas não código, porque o update é só um aviso com link para o site.

---

## 4. Payloads embutidos

### 4.1 `res/raw/ydwfx` = "GG Fixer 64-bit Virtual Space" (`S/fxsrc`) [C]
- Pacote `gg.fixer`, 1 atividade de 54 linhas (`fxsrc/sources/gg/fixer/MainActivity.java`).
- Se `filesDir` tiver menos de 7 níveis, ele mostra "Run this application in virtual space". Senão roda `Os.chmod(dir, 0755)` **recursivamente em todos os diretórios pai** do seu `filesDir` (linhas 15-28, 42-47). Depois se oferece para desinstalar (`UNINSTALL_PACKAGE`).
- Contexto: o GG extrai o fixer para `gg_fixer.apk` quando **nenhum binário de teste do daemon consegue executar** (código < 100, como permissão negada nos diretórios do espaço virtual) (`e0.java:276-302`). O fixer roda dentro do mesmo espaço virtual e abre as permissões dos diretórios.
- `lib/armeabi-v7a/libapp.so` tem 2,5 KB e é um stub vazio (só `__cxa_*`). [H] Serve para forçar o espaço virtual a rodar o fixer no modo/processo 32-bit e consertar o lado certo.

### 4.2 `res/raw/ydwsh` = `sh.jar` com `android.ext.JVM` (`S/shsrc`) [C]
- É extraído para `sh.jar` (`e0.java:149-160`).
- `JVM.load()` busca 7 métodos de `java.util.Random` (`nextInt`, `nextInt(int)`, `nextLong`, `nextBoolean`, `nextFloat`, `nextDouble`, `nextGaussian`) e chama o nativo `hook(id, original, substituto)`. O substituto `RandomImpl` passa cada resultado por `randQ`/`randD` (nativos) (`JVM.java:79-108`).
- É o **Unrandomizer para jogos Java/ART**: dentro do processo do jogo [H: carregado pelo agente nativo injetado], ele troca o RNG Java pelos valores que o usuário fixou no diálogo `unrand.xml` (qword/double + incremento).

---

## 5. Pacote `android/fix` [C]
São 21 subclasses de widgets (`Button`, `TextView`, `EditText`, `ListView`, `ScrollView`, `ProgressBar`, `SeekBar`, `TabHost`…), usadas em todos os layouts. Não são correções de lógica: são **blindagens contra bugs de firmware ou ROM**, envolvendo métodos do framework em `try/catch`:
- `onDraw`, `onMeasure`, `drawableStateChanged`, `invalidateDrawable` com `NoSuchFieldError` (ROMs com framework modificado), `onInitializeAccessibilityNodeInfo` (NPE), `onDragEvent` (ClassCastException), `onKeyShortcut`, `onTextContextMenuItem`, `performLongClick` e `playSoundEffect`/`sendAccessibilityEvent`, que falham quando a UI é desenhada de dentro de um **Service/overlay** sem atividade.
- `TextView.onMeasure` tem um **medidor manual de reserva** quando o nativo falha (`android/fix/TextView.java:105-150`).
- `getSecClipboardEnabled()` retorna false. É um método que a firmware Samsung (TouchWiz) chama nos campos de texto e trava em apps que rodam fora de uma Activity.
- `ListView`: captura `StackOverflowError` em `layoutChildren`, `IllegalStateException` (adapter mudou sem notificação), além de erros em `setFastScrollEnabled`/`setOverScrollMode`.
- `android/app/ANRManagerProxy.java`: stub de uma classe de ROM **MediaTek** (`notifyWNR`, `notifyLightWeightANR`) para evitar `ClassNotFound` ou ANR.
- `android/lang/ProcessBuilder/UNIXProcess`: reimplementação própria de `exec` (outro agente cobre).

---

## 6. Recursos

### 6.1 Idiomas [C]
**54 locales + o padrão em inglês = 55 conjuntos** de cerca de 862 strings: ar, az, bg, bn, bs, bs-rBA, ca, cs, da, de, el, es, et, fa, fi, fr, hi, hr, hu, in, it, iw, ja, jw, ka, km, ko, la, lo, lt, lv, mk, ms, my, nl, pl, **pt-rBR**, pt-rPT, ro, ru, sk, sl, sq, sr, sv, th, tl, tr, uk, ur, uz, vi, zh-rCN, zh-rTW.

As strings usam **macros `__nome__`**, expandidas em tempo de execução por `b2.g()`, por exemplo `__app_name__`, `__hide_from_game__`. É assim que o app é renomeado inteiro sem mexer nas traduções.

### 6.2 Os 47 layouts
| Layout | Propósito |
|---|---|
| alloc | Alocar página de memória (R/W/X, endereço, região) |
| dialog_title | Título customizado dos diálogos |
| dump | Dump de memória (de/até, caminho, pular libs do sistema) |
| execute | Executar script Lua (arquivo, exemplos, baixar scripts, debug: desassemblar/logar chamadas) |
| history_item | Item do histórico de buscas e valores (fixar, nota, apagar) |
| hot_point_view | Ícone flutuante com controles de speedhack (−/+, velocidade, progresso) |
| keyboard | Teclado numérico interno |
| list_speeds_config | Lista de velocidades do speedhack |
| logcat | Visualizar logcat |
| main | Tela inicial: loader, **instalador** (progresso, abortar), iniciar, idioma, ajuda, "fix it" |
| main_new_version | Changelog de nova versão |
| memory_copy | Copiar bloco de memória |
| memory_filter | Filtro do editor de memória (começa/termina com) |
| memory_range | Faixa de memória para busca (tudo, de/até, perto de endereço) |
| option_edit | Editar opção numérica |
| option_seek | Opção com slider |
| path_item | Item do seletor de arquivos |
| path_selector | Seletor de arquivos/pastas |
| path_suggestion | Sugestão de caminho |
| prompt | Diálogo `gg.prompt` dos scripts |
| prompt_item | Campo do prompt (check, texto, caminho, número, seek) |
| save | Salvar/carregar lista (texto ou binário, modos) |
| select_dialog_item | Item de lista em diálogo |
| service_address_editor | Editar endereço salvo (nome, offset, tipo, cópia) |
| service_address_item | Linha de resultado (hex, rhex, string, java, ARM, Thumb, ARM64) |
| service_busy_dialog | Progresso da busca (encontrados, tempo, dicas) |
| service_config | Aba de configurações/ações (ajuda, doar, reset, sair, matar jogo, ranges, speedhack, time jump, unrandomizer…) |
| service_config_hide | Contêiner das opções ocultas (§2.5) |
| service_config_icon | Transparência/tamanho/esconder do ícone flutuante |
| service_dialog | **Janela principal** em abas: config, busca, salvos, memória (+ ícone do jogo) |
| service_editor | Editar valor (incremento, adicionar sem substituir, preencher, congelar) |
| service_filter_search | Filtro de resultados (endereço, valor, tipo, fracionário, limite) |
| service_fuzzy_equal | Contagem para busca fuzzy "igual" |
| service_goto | Ir para endereço (histórico, atalhos de região) |
| service_memory_item | Linha do editor hex (dword, float, double, ARM…) |
| service_offset | Calculadora de offset |
| service_saved_item | Item salvo com checkbox de congelar |
| service_searcher | Diálogo de busca (valor, sinal, máscara, offset, tipo) |
| show_app | Seletor de processo/app |
| tab_indicator_holo | Indicador de aba |
| temp_path_config | Pasta de arquivos temporários, opção "use sh" |
| time_jump | Pular tempo (segundos, exemplos, mostrar painel) |
| time_jump_panel | Painel flutuante de time jump |
| timer_header / timer_row / timer_table | Editor de timers interceptados (app/java/sistema/outros) |
| unrand | Configurar o Unrandomizer (qword/double + incremento) |

### 6.3 Strings que revelam recursos (seleção, de `S/dec/strings_en.txt`)
- **Proteção e anti-anti-cheat**: `hide_from_game` (4 níveis), `ptrace_bypass_mode` (restore/nothing/freeze/off), `disable_protection` ("para todos os apps até reiniciar"), `restart_game_without_protection`, `eperm_error_defend` ("Game is protected", com menção a SecNeo), `prevent_unload` (níveis 1-3), `vspace_root`, `selinux`, `waitpid` (modo alternativo).
- **Recursos de edição**: `unrandomizer`, `time_jump` (inclusive negativo), speedhack com `speeds-params` e timers, `allocate_page`, `dump_memory`, `memory_copy`, busca XOR/máscara/fuzzy/grupo/desalinhada/big-endian, `help_formula_calculator`, `help_lasm` (assembler/desassembler de Lua), `record_script`, `record_logcat`.
- **Distribuição**: `install_mode`, `for32`/`for64` (duas cópias para jogos 32 e 64 bits num espaço virtual 64-bit), `fixer`, `fast_emulators` (Nox, Memu, LDPlayer), `vspace_list` (Parallel Space, VirtualXposed…).

### 6.4 Assinatura (META-INF) [C]
- `CERT.RSA`: RSA **4096**, sha256WithRSA, Subject e Issuer `C=US, O=GameGuardian, CN=GameGuardian` (autoassinado), válido de **15/09/2026** a 24/07/2059.
- SHA-256 do certificado: `77:04:45:48:55:F7:BB:0A:83:D5:BA:32:DA:6F:65:7D:17:B1:19:65:30:9C:58:EC:19:EB:B5:05:68:0D:88:0D`.
- `MANIFEST.MF` tem 771 entradas e diz `Built-By: Signflinger`, `Created-By: Android Gradle 8.2.2`. `CERT.SF` tem `X-Android-APK-Signed: 2`, então também havia assinatura v2 (o bloco v2 não está no diretório descompactado, então não verifiquei).
- Esse certificado só vale para o **instalador**. A cópia instalada leva uma chave RSA-2048 descartável (§2.4), e o código **rejeita** que ela seja igual à oficial.

---

## Lições para o nosso app

1. **Nada de telemetria escondida.** O GG manda contadores de uso, hash do APK e logcat por HTTP puro. No nosso app: zero rede por padrão. Checagem de update opcional, via HTTPS para releases do GitHub; relatório de crash só com consentimento e mostrando o conteúdo.
2. **Permissões que cortaríamos**:
   - ACCESS_SUPERUSER (legada; root via `su` não precisa dela);
   - READ_CLIPBOARD (não existe no AOSP);
   - WRITE_MEDIA_STORAGE, READ_LOGS, INTERACT_ACROSS_USERS(_FULL), UPDATE_APP_OPS_STATS, GET_DETAILED_TASKS, REAL_GET_TASKS (de sistema/assinatura; com root listamos `/proc` direto);
   - CHANGE_COMPONENT_ENABLED_STATE (não é necessária para os próprios componentes);
   - INTERNET e ACCESS_NETWORK_STATE, se não houver update/HTTP de scripts; senão, só INTERNET;
   - REQUEST_INSTALL/DELETE_PACKAGES (sem reinstalação aleatória);
   - GET_TASKS e VIBRATE, se não usarmos;
   - os `uses-feature` falsos.
   - **Manteríamos**: SYSTEM_ALERT_WINDOW (overlay), POST_NOTIFICATIONS (serviço em primeiro plano) e FOREGROUND_SERVICE (+ o tipo `specialUse` no Android 14). Armazenamento via SAF em vez de permissões amplas.
3. **targetSdk atual.** Em vez de target 24 para executar binários de `/data`, empacote o daemon como `lib/<abi>/libxxx.so` com `extractNativeLibs=true` e execute de `nativeLibraryDir`, que é permitido com qualquer target.
4. **Sem sabotagem nem ofuscação hostil.** Como somos open source, código legível vale mais que César −3, XOR atrasado em 18 h e armadilhas de reflexão. Esses truques só atrapalham a depuração e geram bugs "fantasma".
5. **Esconder do jogo é opcional e explícito.** Para jogos single-player, anti-cheat raramente importa. Se oferecermos, que seja um modo claro (ex.: build com `applicationIdSuffix` gerado no CI), sem reescrever dex e assinatura no aparelho. Reescrever binários e gerar chaves no aparelho é complexo e frágil, e se parece com comportamento de malware para o Play Protect.
6. **Vale copiar**:
   - a ideia do `android/fix`: um `try/catch` defensivo em widgets usados num overlay de Service, mais `getSecClipboardEnabled` para Samsung;
   - as macros `__nome__` nas strings;
   - o Receiver protegido por nonce;
   - o Fixer como diagnóstico de espaço virtual (melhor: mostrar o erro e o caminho, sem chmod 0755 recursivo, que enfraquece o isolamento);
   - o Unrandomizer Java via hook de `java.util.Random`, como recurso avançado opcional.
7. **Componentes**: um único launcher. Marcar `exported` explicitamente. O serviço com nome honesto (`OverlayService`). A instrumentação só se for mesmo necessária para manter o processo vivo; preferir serviço em primeiro plano.
