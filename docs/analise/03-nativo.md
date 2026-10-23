# 03 — Componentes nativos do GameGuardian 101.4

Análise **estática** dos recursos `res/raw/ydw*` (33 arquivos). Nenhum binário foi executado.
Foco em **arm64** (`ydw5`, `ydw05`, `ydw6`, `ydwg`, `ydwl`, `ydwr`); as demais ABIs são o mesmo
código recompilado (mesmos nomes de símbolo, mesmas strings, tamanhos equivalentes por ABI).

Toolchain de compilação (string `.comment` em todos): **clang 18.0.1** do AOSP
(`r522817`, `+pgo +bolt +lto +mlgo`), linker **LLD 18.0.1**. Todos são **PIE / BIND_NOW / RELRO**.

Convenção de marcação: **[C]** = confirmado por símbolo/string/relocação; **[H]** = hipótese
fundamentada (padrão conhecido do GG + evidência indireta); **[?]** = não verificado.

---

## 1. Papel de cada binário

### 1.1 Mapa das ABIs

Cada ABI tem o mesmo conjunto de 8–9 arquivos. Letras usadas pelo loader Java (`e0.java`):

| Função | x86 | x86_64 | arm64 | arm32 |
|---|---|---|---|---|
| Daemon principal (exec PIE) | `ydw0`,`ydw1` | `ydw2` | `ydw5` | `ydw7`,`ydw8` |
| Sonda de arquitetura / fixer (exec PIE) | `ydw00`,`ydw01` | `ydw02` | `ydw05` | `ydw07`,`ydw08` |
| Lib speedhack/tempo (SONAME `lib<x>.so`) | `ydw3` (lib3) | `ydw4` (lib4) | `ydw6` (**lib6**) | `ydw9` (lib9) |
| Lib stub ptrace/inotify "normal" | `ydwe` | `ydwf` | `ydwg` (**libg**) | `ydwh` |
| Lib stub variante "zygote" | `ydwj` | `ydwk` | `ydwl` (**libl**) | `ydwn` |
| Lib exec de processos (su/shell helper) | `ydwp` | `ydwq` | `ydwr` (**libr**) | `ydws` |

SONAMEs confirmados por `readelf -d`: `lib6.so`, `libg.so`, `libl.so`, `libr.so`. **[C]**
`ydw3a`/`ydw3x` têm 0 byte: são *placeholders* que o empacotador Java renomeia em tempo de
instalação para `lib/<abi>/libAndroid.so` (ver `b1.java:1785`, entradas `res/raw/ydw3a →
lib/arm64-v8a/libAndroid.so`). **[C]**
Extras não-ELF: `ydwsh` (JAR), `ydwfx`/`ydwfx`→`ydwhe` (APK do "gg_fixer"), `ydwhe` (ZIP). **[C]**

### 1.2 Por que dois executáveis por ABI

Confirmado via `e0.java` (classe de bootstrap do daemon):

* **`ydw0X` = sonda/fixer de arquitetura.** `e0.i()` percorre as letras `{'5','8','b','0','7','a','2','1'}`,
  extrai `ydw0X`, **executa** e lê o *exit code*. `e0.o()` valida o código esperado por ABI:
  `'5'→105` (arm64), `'7'/'8'→104` (arm32), `'2'→102` (x86_64), `'0'/'1'→101` (x86), `'a'/'b'→103`
  (provável VirtualSpace/emulação). Assim o app descobre, em runtime, **qual ABI realmente roda**
  no device (importante em devices ARM que emulam x86, ou 64-bit que roda target 32-bit). **[C]**
* Dentro das sondas há **duas gerações**: `ydw00`/`ydw07`/`ydw02`/`ydw05` são minúsculas
  (≈8 KB: só string `FIXP`, parse de `/proc/%d/maps`, `init %d …`, teste de FPU `FPU ok: %f` no ARM,
  e `_exit(code)`). `ydw01`/`ydw08` são maiores (≈24 KB) e contêm o **"linker manual"** completo
  (`Cannot find linker.`, `New linker:`, `soinfo32`, `getFirstInfoAddr`, `PTRACE_POKEDATA`,
  `linkerRegions`, `VM_FAIL`) — ou seja, o teste pesado que de fato exercita ptrace + resolução
  manual do `/system/bin/linker`. O par é "sonda rápida" vs "sonda que valida injeção". **[C/H]**
* **`ydw0`/`ydw1` e `ydw7`/`ydw8` (pares de daemon):** diferem só em `.text` (poucos KB), `.got`
  (0x1f8 vs 0x120) e `RELCOUNT` (406 vs 352). Mesmo conjunto de strings. É a clássica dupla GG de
  daemon **"novo" vs "legado"** (um com mais relocações/entradas GOT → provável suporte a Android
  mais antigo / caminho sem certas syscalls). Em arm64/x86_64 há só um (`ydw5`/`ydw2`), pois lá não
  há device antigo o suficiente para justificar a segunda variante. **[H]**

### 1.3 Imports / libs ligadas (todos os ELF)

`NEEDED`: `liblog.so`, `libc.so`, `libm.so`, `libstdc++.so`, `libdl.so`. **[C]**
Nenhuma dependência externa (sem OpenSSL, sem libcrypto): toda a lógica é própria.

Imports relevantes do **daemon `ydw5`** (dynsym UND): `ptrace`, `syscall`, `process_vm_*` via
`syscall` (não há símbolo direto), `mmap64`/`mremap`/`munmap`/`mprotect`, `waitpid`, `kill`,
`tgkill` (também **exportado** — reimplementação própria), `fork`, `execvp`, `dup2`, `pipe`,
`mkfifo`, `inotify_init`/`inotify_add_watch`, `poll`/`select`, `ioctl`, `prctl`, `dlopen`/`dlclose`,
`clock_gettime`/`gettimeofday`, `pthread_*`, `/proc` via `fopen`/`sscanf`. **[C]**

### 1.4 Strings/símbolos notáveis por lib

* **`ydw6` / lib6.so (speedhack + guarda anti-detach):** exporta `~200` símbolos. Núcleo de
  **manipulação de tempo**: classes C++ `GgClockGetTime`, `GgGetTimeOfDay`, `GgSystemTime`,
  `GgElapsedRealtime[Nano]`, `GgUptimeMillis`, `GgFtime`, `GgClock`, `WarpTimeBase::updateWarpTime_`,
  e famílias de stubs com sufixos de versão de libc Android `_clock_gettime_{a,b,j,o,s}`,
  `_gettimeofday_*`, `_systemTime_*`, `_uptimeMillis_*`. Também `nativeHook(JNIEnv*, …,
  JNINativeMethod*, …)` e PRNG stubs (`arc4random_*`, `drand48_*`, `nextInt/nextLong/nextDouble`
  deobfuscados). Mais `check_fork`/`check_ptrace`/`getTracerPid`/`prepare_fork` (anti-debug). **[C]**
* **`ydwg` / libg.so (stub ptrace/inotify "normal"):** pequeno; exporta `ptrace`, `inotify_init`,
  `inotify_add_watch`, `inotify_rm_watch`, `check_fork`, `getTracerPid`, `onload`/`onunload`.
  Strings `stub: ptrace %d…`, `stub: inotify_add_watch`, `fork: child/parent/prepare`,
  `PR_SET_DUMPABLE fail`, lê `/proc/%d/status` → `TracerPid:`. É um **shim injetável** que
  intercepta ptrace/inotify dentro do processo-alvo. **[C]**
* **`ydwl` / libl.so (variante "zygote"):** igual ao `ydwg` mas strings `zygote: …`, exporta
  `ptrace2`, `inotify_*_` (com sufixo), `allow()`, usa `getuid`. É a versão instalada via injeção
  no **zygote** (hook global de apps). **[C]**
* **`ydwr` / libr.so (helper de execução de processos):** exporta `JNI_OnLoad`,
  `UNIXProcess_forkAndExec`, `UNIXProcess_destroyProcess`, `UNIXProcess_waitForProcessExit`,
  `ProcessEnvironment_environ`, `register_android_lang_UNIXProcess`,
  `register_android_lang_ProcessEnvironment`, `JNU_ThrowByName`. Strings `android/lang/UNIXProcess`,
  `forkAndExec`, `/proc/self/fd`, `:/bin:/usr/bin`, `PATH`, `Exec failed`. É uma
  **reimplementação nativa do `java.lang.UNIXProcess`**: deixa o app disparar `su`/shell sem passar
  pelo `Runtime.exec` da plataforma (burla restrições de `exec` no app process / executa binários
  do próprio diretório). **[C]**

---

## 2. Internos do daemon (`ydw5`)

`android-daemon start~~~~~ %d %zu %s %d %d %d %d` / `android-daemon stop------`. Processo nativo
longevo, rodado via `su` (ou, sem root, via `app_process`/VirtualSpace). Nome de thread via
`pthread_setname_np`. **[C]**

### 2.1 Leitura/escrita de memória — **três caminhos**, confirmados por string

| Método | Evidência | Marca |
|---|---|---|
| `ptrace(PTRACE_POKEDATA/PEEKDATA)` | `PTRACE_POKEDATA addr: %zx, check fail (%zx != %zx)`, `PTRACE_GETREGS/SETREGS error`, `PTRACE_CONT/ATTACH/DETACH/SETOPTIONS` | [C] |
| `/proc/<pid>/mem` (open+lseek64+read/write) | `/proc/%d/mem`, `mem (%s) read: %zx (%lx) =?= %zx`, `mem (%s) munmap`, `read_data/write_data fail: %p` | [C] |
| `process_vm_readv/writev` (via `syscall`) | `vm for %d error %zd %d`, `VM_FAIL %d`, `VM addr: %zx %zd, check fail` | [C/H] |
| `mmap` de `/proc/<pid>/mem` + **pagemap/PFN** | `mem (%s) mmap: %zx (%lx) =?= %zx`, `/proc/%d/pagemap`, `/proc/self/pagemap`, `mem: Can't read pfn on %zx` | [C/H] |

O "check fail (%zx != %zx)" indica que **toda escrita é verificada por releitura** — padrão GG para
não corromper o alvo. O caminho pagemap/PFN é usado para resolver páginas físicas (otimização de
leitura em massa ou bypass de `/proc/mem` protegido). **[H]**

### 2.2 Attach / stop

`PTRACE_ATTACH for %d`, `PTRACE_ATTACH2`, `waitForPause timeout`, `Wait trap`, `breakpoint:
status(%x) WIFSTOPPED… WSTOPSIG`, `Stop, but not a breakpoint!`, `detach: target %d code
unrestored, terminating instead of resuming`. Estratégia: **ATTACH → espera SIGSTOP (waitpid) →
opera → CONT/DETACH**. Há dois caminhos (`attach`/`attach2`, `ATTACH`/`ATTACH2`) — provavelmente
*seize* vs *attach* clássico, ou per-thread (lê `/proc/%d/task`, `getTids`). Se o código injetado
não puder ser restaurado no detach, o daemon **mata o alvo** em vez de resumir corrompido. **[C]**

### 2.3 Parse e classificação de regiões

`/proc/%s/maps` lido com `fopen`+`sscanf` no formato
`%zx-%zx %s %zx %*s %*s %n` (start-end perms offset … pathname). **[C]**
Classificação por pathname via lista de strings (muitas **ofuscadas com +1 por byte**, decodificadas
na análise): `[stack]`, `[anon:scudo:primary]`, `[anon:scudo:secondary]`, `[vdso]`,
`anon_inode:dmabuf`, `/dev/kgsl`, `/dev/galcore`, `/dev/mali`, `/dev/nv*`, `/dev/tegra`, `/dev/pvr`,
`/dev/render`, `/dev/graphics`, `/dev/fimg2d`, `/system/fonts/`, `/system/lib[64]`, `libc.so`,
`libRS.so`, `gralloc`, `dalvik-alloc`, `libicu*`. Serve para dar nomes amigáveis de região (Cb/Ca =
Code bank/alloc, Jh = Java heap, etc.) e para **pular memória de GPU/driver** que trava na leitura.
Reconhecimento explícito de **scudo** (allocator do Android moderno). **[C]**

### 2.4 Algoritmos de busca

Enorme família de símbolos gerados por template, confirmando os tipos de busca do GG:

* `firstSearch` / `refineSearch` com sufixos combinatórios de 1–10 letras
  (`B W D X Q F E G N L e Z R`). As letras codificam **tipo de valor + operador de refino**;
  hipótese forte: `G`=greater, `L`=less, `E/e`=equal, `N`=not-equal/changed, `Z`=zero/unknown-base,
  `R`=range, e `B/W/D/X/Q/F`=byte/word/dword/xword(?)/qword/float (tamanhos). Isso implementa
  **busca exata, fuzzy/unknown-value e "increased/decreased/changed/unchanged"**. **[C tipos / H letras]**
* `firstGroupSearch_{8,16,32,64}`, `refineGroupSearch_*`, `getGroupSearchIndex`,
  `firstOrderedGroupSearch` → **group search** (vários valores próximos, ordenados ou não). **[C]**
* `firstMaskSearch`/`refineMaskSearch` (`searchMask: %zx:%zx, %x`) → **busca com máscara de bits/AND**. **[C]**
* `searchPointer` (`searchPointer: %x %x %zx-%zx`) → **pointer scan**. **[C]**
* `searchText` (`searchText: '%s' %d %x`), `searchNumber`, `firstTextSearch` → string/number. **[C]**
* XOR/encrypted-value: coberto pelas variantes de valor + `randUpdateBuf`/`randUpdate` na lib de tempo
  (valores embaralhados). XOR explícito **[?]** — não vi string dedicada, mas o esquema de letras
  comporta um decodificador por-valor. **[H]**

Progresso reportado: `Progress %d: %zu from %zu, found: %zu`, `::searchDone: %zu, %zu %d`,
`firstSearch started./ended.`. **[C]**

### 2.5 Armazenamento de resultados (diretório temp)

Layout de arquivos no dir de trabalho do daemon (`gDataDir`, derivado de
`/data/data/<pkg>/files`, `.../cache`, ou `/sdcard/Android/data/<pkg>/…` — string
`%s|%s|/data/data/%s/files|…|/sdcard`):

| Arquivo | Uso |
|---|---|
| `%s-%08zx-%08zx.bin` | **chunks de resultados** (range start-end por arquivo) [C] |
| `%s-tmp.bin`, `loadBuffer/saveBuffer` | buffer intermediário de busca [C] |
| `%s-maps.txt` | dump do maps [C] |
| `version.gg` | versão/handshake [C] |
| `sel.txt` | estado do SELinux salvo (`get sel`, `sel mark`, `sel set`) [C] |
| `helper.txt` | caminho/estado do helper [C] |
| `sh.jar` | o JAR `ydwsh` extraído (ponte Java para `su`) [C] |
| `stop`, `OOM`, `write_check.tmp`, `odex/` | sinal de parada, flag OOM, teste de escrita, odex [C] |
| fifos `i` / `o` / `e` | IPC (ver 2.6) [C] |

Gerência de memória própria: `goodMem realloc`, `alloc check 1/2/3`, `chunk failed`,
`OOM for %s`, `OOM from vmstat`/`client` (lê `/proc/meminfo`→`MemTotal:`) — o daemon monitora
pressão de memória e cai de volta para arquivo quando falta RAM. **[C]**

### 2.6 IPC (lado servidor)

Confirmado cruzando `ydw5` com `f0.java:2296-2345`:

* O daemon é lançado por um wrapper (`su`/`sh.jar`) e conversa **primeiro por stdin/stdout/stderr**
  (`android-daemon out`/`err`). Handshake: escreve `0`/`1`; `1` significa "mudar para FIFOs". **[C]**
* Em modo FIFO, cria `mkfifo` três pipes nomeados no dir de trabalho: **`i` (in), `o` (out),
  `e` (err)** — strings nativas `mkfifo %c`, `Fifo: %d`, `IF 0/1/2/3`, `IF S %c`; lado Java abre
  `new FileInputStream(file,"o")`, `"e"`, `FileOutputStream(file,"i")`. **[C]**
* Dispatch por códigos: `Send code: %d, %d`, `cmd error %d`, `Bad answer: %d`,
  `Protocol break: %d %d`, `EVENT: %d`, `KEY: %d`, `client dead? %d`, `helper call: %d`. Protocolo
  binário com comando + payload; eventos assíncronos (`EVENT`) para progresso/teclado. Os códigos
  numéricos exatos ficam no lado Java (`MainService`) — fora do escopo nativo. **[C/H]**

### 2.7 Resolução manual do linker (compartilhado com a sonda `ydw01/08`)

`Cannot find linker.`, `New linker: %zx`, `Linker manual: %zx => %zx`, `soinfo32`,
`getFirstInfoAddr`, `hook_reloc_slot32`, `elf_hook_sections32`, `section_by_name32`, `read_data32`,
`mem_read64`, `elf_hook_mem64`. O daemon lê e **re-parseia manualmente o `/system/bin/linker[64]`**
e os `soinfo` do alvo (sem depender de `dlopen` do alvo) para localizar `dlopen`/símbolos e aplicar
**hooks em slots de relocação (GOT/PLT)**. Suporta ELF 32 e 64. **[C]**

---

## 3. Injeção (técnica)

Strings-chave (ydw5): `Injection failed: shell write not applied at %zx`,
`Injection failed: backup read at %zx failed`, `Injection failed: no trap (see stop log above),
insert: %zx`, `Injection failed: shell restore at %zx could not be verified`,
`create shell code: %d %zx %zx`, `initShellPage: %zx`, `Load shell: %zx`, `Shell ranges: %d`,
`Shell run %d dropped past the %d-range cap`, `Failed find exec point`, `Failed find safe point`,
`Call point backup read failed`, `Change registers`, `Copy lib cp/chmod`, `JVM check/read`,
`android.permission.%s` (deobfuscado). **[C]**

Técnica reconstruída (padrão ptrace-shellcode-dlopen; **[C] nos passos com string, [H] no encadeamento**):

1. **ATTACH** no alvo e para uma thread em ponto seguro (`Failed find safe/exec point`,
   `Call point`). Faz **backup** dos bytes originais (`backup read at %zx`).
2. Escreve um **shellcode** numa página executável (`initShellPage`, `create shell code`,
   `shell write not applied`) via POKEDATA/`/proc/mem`, grava registradores (`PTRACE_SETREGS`,
   `Change registers`) para desviar o PC ao shellcode.
3. O shellcode chama **`dlopen`** (resolvido pelo linker manual) na lib a injetar — `libg.so`
   (normal) ou `libl.so` (zygote). A lib é copiada para um caminho acessível (`Copy lib cp/chmod`,
   `sh.jar`). A injeção é confirmada quando o alvo bate num **trap** (`no trap`, `Wait trap`,
   `breakpoint: …WSTOPSIG`).
4. **Restaura** bytes/registradores originais e verifica (`shell restore … could not be verified`),
   depois DETACH.
5. Para apps alvo, há também hook via **JNI** (`JVM check/read`, `nativeHook` em lib6) e caminho
   **zygote** (libl) para instalar o hook antes do app subir. **[C/H]**

O `Shell ranges`/`range cap` indica alocação de várias janelas de código executável reutilizáveis
(para não re-escrever o shellcode a cada chamada). **[C]**

---

## 4. Speedhack / time jump (`ydw6` = lib6.so)

Mecanismo: **hook das funções de tempo da libc/runtime dentro do processo-alvo**, retornando um
relógio "deformado" (warp). Evidência de símbolo (todos exportados, **[C]**):

* Classes C++ com vtable (`_ZTV…`): `GgClockGetTime::do_clock_gettime(timespec*)`, `GgGetTimeOfDay`,
  `GgSystemTime`, `GgElapsedRealtime`, `GgElapsedRealtimeNano`, `GgUptimeMillis`, `GgFtime`,
  `GgClock`, e base `WarpTimeBase::updateWarpTime_(unsigned long)`.
* Stubs por **versão de libc Android** com sufixo `_a _b _j _o _s` (ex.: `_clock_gettime_o`,
  `_gettimeofday_o`, `_systemTime_o`, `_uptimeMillis_o`, `_time_o`, `_ftime_o`). Os sufixos mapeiam
  gerações do Android (provável: `o`=Oreo+, `s`=S/12, `j`=JellyBean, `a`/`b` antigos) porque a
  assinatura/ABI dessas funções mudou entre versões. O daemon escolhe o stub certo conforme a
  versão detectada. **[C sufixos / H significado das letras]**
* Funções livres equivalentes às da runtime Android: `_Z10systemTimei`, `_Z12uptimeMillisv`,
  `_Z15elapsedRealtimev`, `_Z19elapsedRealtimeNanov`, `_ZN7android12uptimeMillisEv`,
  `_ZN7android15elapsedRealtimeEv`, `currentTimeMillis` (deob). Também PRNG (`arc4random_*`,
  `drand48_*`, `nextInt/Long/Double`) → o speedhack também **acelera/sincroniza RNG** quando o jogo
  usa tempo como semente. **[C/H]**

Como: a lib é injetada (§3) e seus `_clock_gettime_o` etc. substituem (via hook de GOT/PLT em
`elf_hook_*`, ou patch inline) as chamadas reais; `updateWarpTime_` aplica um multiplicador/offset
sobre o tempo real lido do kernel. `_config`/`initConfig`/`getUseType`/`setUseType` guardam o fator
de velocidade. O daemon lê o tempo-base real por `clock_gettime`/`gettimeofday` (imports de ydw6). **[C/H]**

---

## 5. SELinux, root, versão do Android, scudo/MTE, anti-debug

* **SELinux [C]:** daemon lê/grava enforce. Strings (deob +1): `getenforce`, `setenforce`,
  `Enforcing`, binário `/system/bin/debuggerd`; salva estado em `sel.txt` (`get sel`, `sel mark`,
  `sel set`, `sel skip`). Tipicamente faz `setenforce 0` (permissive) com root para poder ler
  `/proc/<pid>/mem` e injetar, restaurando depois. **[C/H]**
* **Root / su [C]:** caminho primário é via `su` + `sh.jar` (`ydwsh`, JAR ponte). Sem root, usa
  `libr.so`/`UNIXProcess_forkAndExec` para spawnar e o fluxo VirtualSpace (`vSpaceReal`,
  `parallel_intl/parallel_lite` em `e0.java`) — roda o alvo clonado dentro do próprio app. **[C]**
* **Versão do Android [C/H]:** detecção embutida (`getprop`/`setprop` deob, auxv `/proc/%d/auxv`,
  `package name: %s`); os sufixos `_a/_b/_j/_o/_s` dos stubs e as duas gerações de daemon
  (`ydw0`/`ydw1`) são o mecanismo de compatibilidade entre versões.
* **scudo / MTE / tagged pointers [C/H]:** reconhece regiões `[anon:scudo:primary/secondary]` no
  maps (allocator moderno). `g_kernelPageSize`/`g_kernelPageShift`/`g_kernelPageMask` (objetos
  exportados em todas as libs) tratam **páginas de 4 KB vs 16 KB** (devices novos). Não achei string
  explícita de MTE/`tag`; o tratamento de ponteiros top-byte provavelmente é feito mascarando com
  `kernelPageMask`/aritmética — **limpeza da tag não confirmada por string [?]**.
* **Anti-debug / auto-proteção [C]:** `check_ptrace`, `getTracerPid` (lê `/proc/<pid>/status →
  TracerPid:`), `check_fork`/`prepare_fork`/`child_fork`/`parent_fork` com `pthread_atfork`,
  `PR_SET_DUMPABLE` (`prctl`), `protect self span overflow` (protege o próprio código). As libs
  `libg`/`libl` interceptam `ptrace`/`inotify` no alvo para esconder o daemon de anti-cheats. **[C]**
* **Ofuscação de strings [C]:** muitos caminhos sensíveis (`/proc/%d/mem`, `/proc/%d/pagemap`,
  `/proc/%d/maps`, `/system/bin/debuggerd`, `getenforce`, `android.permission.%s`, nomes de lib)
  são armazenados com **cada byte +1** e decodificados em runtime, para escapar de scanners de
  string simples. Decodificados nesta análise com um script de −1.

---

## Lições para o nosso app (daemon melhor, single-player)

1. **Leitura/escrita: priorize `process_vm_readv`/`process_vm_writev`** como caminho rápido (uma
   syscall, sem parar o alvo, lê milhares de páginas em lote via `iovec`). Caia para `/proc/pid/mem`
   (open+pread/pwrite) e só use `ptrace POKE/PEEK` como último recurso (1 word por syscall, lento e
   intrusivo). O GG mantém os três — nós podemos fazer igual, mas com `process_vm_*` como default.
2. **Não pare o processo para buscar.** `process_vm_readv` não exige PTRACE_STOP; só pare (ATTACH)
   para escrita atômica/injeção. Isso dá busca sem travar o jogo.
3. **Busca com SIMD.** O GG usa templates por tipo; nós podemos usar **NEON/AVX2** (`memchr`/compare
   vetorizado) para o primeiro-scan e um bitmap/índice compacto de candidatos para o refino,
   evitando reescrever `.bin` a cada passo (mmap de arquivo de resultados + mmap anônimo).
4. **Classifique regiões como o GG** (pular `/dev/*` de GPU, `[anon:scudo:*]`, dmabuf) para não
   travar lendo memória de driver; respeite **page size 4K/16K** desde o início
   (`sysconf(_SC_PAGESIZE)`), nunca hardcode 4096.
5. **Limpe tags de ponteiro (MTE/top-byte-ignore)** ao comparar endereços no Android 12+/arm64
   (`addr & ((1<<56)-1)`), algo que o GG trata de forma implícita e frágil.
6. **IPC simples e tipado:** em vez de 3 FIFOs + protocolo binário ad-hoc, use **um socket de
   domínio Unix (SEQPACKET)** com mensagens versionadas (length-prefixed, ou protobuf/flatbuffer).
   Mais fácil de evoluir e de depurar que `Send code/Bad answer/Protocol break`.
7. **Verifique toda escrita por releitura** (como o `check fail %zx != %zx` do GG) e ofereça
   *freeze* por re-escrita periódica.
8. **Para single-player só precisamos de root OU ptrace do próprio app** — podemos dispensar toda a
   maquinaria zygote/anti-detect/SELinux-flip, que existe no GG para anti-cheat de jogos online.
   Menos binário, menos superfície, mais estabilidade.
9. **Speedhack**: a abordagem de hookar `clock_gettime`/`gettimeofday`/`elapsedRealtime` por GOT/PLT
   é sólida; basta um fator (offset+escala) e um stub por família de função — não precisamos dos
   múltiplos sufixos de versão se mirarmos Android recente.
10. **Um único binário por ABI** (não daemon-velho/novo + 2 sondas): detecte page size, versão e
    capacidade de ptrace em runtime no próprio daemon. Simplicidade > a matriz de 8 arquivos do GG.
