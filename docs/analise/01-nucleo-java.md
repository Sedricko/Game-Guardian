# 01 — Núcleo Java do GameGuardian 101.4 (build 16155)

Fonte: saída do jadx em `src/sources` (caminhos abaixo relativos a `src/sources/android/ext/` salvo indicação).
Legenda: **[C]** = confirmado (visto no código) · **[H]** = hipótese (inferência).
Strings de UI foram resolvidas com `dec/public.xml` + `dec/strings_en.txt` (script `tools/rid.sh`).

Mapa rápido das classes ofuscadas mais importantes:

| Classe | Papel real | Evidência |
|---|---|---|
| `f0` | **DaemonManager**: inicia o daemon, monta todos os comandos, despacha respostas | `f0.java:2693` (`O0`), `:3124` (`h0`) [C] |
| `InOut` | Transporte/serialização (framing, hex, XOR de ints) | `InOut.java` [C] |
| `w` | Leitor LE de pacotes de resposta (`e()` byte, `f()` int, `g()` ponteiro, `h()` long) | `w.java` [C] |
| `e0` | Seleção de ABI + extração de `res/raw/ydw*` | `e0.java:227` [C] |
| `e2` | Execução de comandos root (`su`) | `e2.java:381` [C] |
| `MainService` | "Serviço" lógico (não é `android.app.Service`), UI flutuante + estado | `MainService.java:59` [C] |
| `c` / `h2` | Item de memória (addr, value, flags) / item do saved list (+freeze) | `c.java:107`, `h2.java` [C] |
| `i2`, `t0`, `m1`, `w1`, `Searcher` | Diálogos de busca: conhecida, fuzzy, máscara, ponteiro, refine fuzzy | strings resolvidas [C] |
| `t1`, `j2` | Parser de números / "oneLiner" de busca | `t1.java`, `j2.java` [C] |
| `b0` | Filtro/paginação de resultados (cmd 39) | `b0.java:143` [C] |
| `c1`, `x2` | Load/Save list em arquivo (com correção de ASLR) | `c1.java:104` [C] |
| `n1` | Editor de memória (hex viewer) | `n1.java:888` [C] |
| `TimersEditor`, `y2`, `o2`, `d1` | Speedhack timers / unrandomizer / time jump / razão de velocidade | [C] |
| `Config` | Todas as opções (SharedPreferences) e bitmasks enviados ao daemon | `Config.java:1167-1213`, `:2706` [C] |

---

## 1. Fluxo de inicialização

### 1.1 Cadeia de entrada [C]
Os componentes do manifest (`catch_.me_.if_.you_.can_.*`) são cascas vazias de 7 linhas que só estendem classes de `android.ext`:

| Componente | Base | Função |
|---|---|---|
| `Application` | `l` (`l.java`) | log + `i.a()`, `n0.f()` (crash handler); `onTrimMemory` → `MainService.t0()` |
| `MainActivity` / `ActivityMain` | `r` (`r.java`, 1485 linhas) | Activity de boot |
| `Instrumentation` | `u` (`u.java`) | contexto alternativo para rodar a UI (`start()` via instrumentation) |
| `AnalyticsService` | `v` (`v.java`) | na verdade "BootstrapService" — serviço foreground que mantém o processo vivo |
| `Receiver` | `w2` | BroadcastReceiver |
| `FileProvider` | `c` (lib `a..g`) | compartilhamento de arquivos |

Sequência (`r.java:1278` onCreate):
1. Título `"# GameGuardian 101.4 (16155) HW|SW"` — o prefixo indica modo: `#` root, `@` virtual space, `$` "direct"/sem root (`Config.C`) [C] (`r.java:1313`).
2. `b1.i1(k)` → verificação/instalador (repack "hide" — fora do meu escopo).
3. `r.C()` (`r.java:~1071`): se `root-ok == 16155` em prefs, pula a sonda de root; senão `R(bundle)` → `e2.i()` thread "RootProbe" que roda `exec id` via su e procura `uid=0` (`e2.java:292`, timeout 20 s) [C].
4. `F()` → `t.c()` "Bootstrap startService": escolhe o Context (Instrumentation/Service/Activity — opção `context-source`) e faz `new MainService(ctx)` + `MainService.Z()` (`t.java:50`) [C].
5. `MainService.Z()` (`MainService.java:2480`): cria `TimersEditor("TE","timers",44)` e `TimersEditor("RE","rand",19)`, monta a UI, depois `this.i.N0()` → thread `"DaemonLoader"` (`f0.a0.run`, `f0.java:149`) e `this.i.G()` (cmd 57) [C].
6. Comandos enviados antes do daemon subir ficam enfileirados em `InOut.j` (até o flag `k` ficar true) e são descarregados por `InOut.m()` [C] (`InOut.java:409`).

### 1.2 Seleção de binário por ABI (`e0.i()`, `e0.java:227`) [C]
- Ordem de prova: `{'2','1','5','8','b','0','7','a'}`. Para cada letra *c* extrai o **probe** `ydw0c` como `lib0c.so` e executa `lib0c.so <hostpkg|"1">` (`e0.e()`, `:173`, timeout 30 s). O **exit code** identifica a arquitetura:

| exit | arquitetura aceita | daemon escolhido |
|---|---|---|
| 101 | x86 | `ydw1` (via probe `01`) ou `ydw0` (probe `00`) |
| 102 | x86_64 | `ydw2` |
| 103 | (letras `a`/`b`, ausentes neste APK) | `ydwa`/`ydwb` |
| 104 | ARM 32 | `ydw8` (probe `08`) ou `ydw7` (probe `07`) |
| 105 | ARM64 | `ydw5` |
(`e0.o()`, `:504`). Exit 105/102 ⇒ `InOut.p(true)` = protocolo 64 bits (ponteiros de 8 bytes) [C].
- Se nenhum passa e algum exit < 100, extrai `ydwfx` como `gg_fixer.apk` e oferece instalar [C] (`e0.java:290-305`).
- Todos os arquivos vão para um diretório "fix" (`e0.j()`, tenta files/cache do app, caminhos de virtual-space como `parallel_intl`, `vmos`, `gaia`…) com nome `lib<x>.so` [C].

### 1.3 Papel de cada `ydw*` (pelo uso no Java + tipo ELF/símbolos)

| recurso | tipo (`file`) | nome extraído | papel | status |
|---|---|---|---|---|
| ydw00, ydw01 | ELF x86 exec | lib00/lib01.so | probe de arquitetura (exit 101) | [C] |
| ydw02 | ELF x86_64 exec | lib02.so | probe (exit 102) | [C] |
| ydw05 | ELF aarch64 exec | lib05.so | probe (exit 105) | [C] |
| ydw07, ydw08 | ELF ARM exec | lib07/lib08.so | probe (exit 104) | [C] |
| ydw0, ydw1 | ELF x86 exec ~4.9 MB | lib0/lib1.so | **daemon** x86 | [C] |
| ydw2 | ELF x86_64 exec | lib2.so | **daemon** x86_64 | [C] |
| ydw5 | ELF aarch64 exec | lib5.so | **daemon** ARM64 | [C] |
| ydw7, ydw8 | ELF ARM exec | lib7/lib8.so | **daemon** ARM32 | [C] |
| ydw3 / ydw4 / ydw6 / ydw9 | .so ~150 KB (x86/x86_64/arm64/arm) | lib3/4/6/9.so | biblioteca de hooks de tempo/aleatório (exporta `_clock_gettime_a`, `GgElapsedRealtime`, `_drand48_j`, `_rand_r_o`…) = speedhack/unrandomizer injetado no jogo | símbolos [C], uso [H] |
| ydwe / ydwf / ydwg / ydwh | .so ~10 KB | libe/f/g/h.so | stub de **LD_PRELOAD** (exporta `onload`, `check_ptrace`, `getTracerPid`, `inotify_*`); `e0.k(z)` devolve `/lib{g|h ou f|e}.so` (64/32 bits) usado em `setprop wrap.<pkg> LD_PRELOAD=...` (`MainService.java:344-350`) | [C] |
| ydwj / ydwk / ydwl / ydwn | .so ~10 KB | libj/k/l/n.so | variante do stub (exporta `allow`, `ptrace2`, `inotify_add_watch_`) | [H] |
| ydwp / ydwq / ydwr / ydws | .so ~20 KB | libp/q/r/s.so | JNI do próprio GG: reimplementação de `java.lang.UNIXProcess`/`ProcessBuilder` (`android/lang/ProcessBuilder.java`, `e0.l()` faz `System.load`) — spawn de processos sem passar pelo framework | [C] |
| ydwsh | JAR | sh.jar | helper Java (classe `JVM`, mexe em `java.util.Random.nextInt/nextLong…`) — unrandomizer para jogos Java, carregado pelo daemon (`"%s/sh.jar"` nas strings de ydw5) | [C]/[H] |
| ydwfx | APK | gg_fixer.apk | app auxiliar quando nenhuma ABI funciona | [C] |
| ydwhe | ZIP | (help) | ajuda offline (`o0.java:168`) | [C] |
| ydw3a / ydw3x | vazios | — | placeholders renomeados p/ `lib/*/libAndroid.so` no repack (`b1.java:1785`) | [C] |

Detalhe do `e0.d(c)` (`e0.java:122`): após escolher o daemon extrai as libs auxiliares: x86_64 (`'2'`) → `01`,4,f,k **e cai** para 3,e,j (libs 32 bits); ARM64 (`'5'`) → `08`,6,g,l (e, pela simetria, 9,h,n [H] — o jadx quebrou o switch). `f728e`/`f729f` guardam a letra do stub 32/64 bits [C].

### 1.4 Como o root é obtido e o daemon executado [C]
`f0.K()` (`f0.java:1601`) monta argv do daemon:
```
<dir>/lib5.so  <pacote GG>  <filesDir>  <SHA-384 da assinatura/APK: f0.T()>  <"1" se Config.C senão "0">
```
- **Com root** (`Config.C == false`): vira uma única linha `"exec lib5.so ..."` e `e2.r()` (`e2.java:381`) inicia `su` (pref `su`, default `"su"`; fallback absoluto em `/debug_ramdisk,/sbin,/su/bin,/magisk/.core/bin,/magisk/phh/bin,/system/xbin`, `e2.java:312`) com PATH aumentado, e escreve a linha no **stdin do su**. O `exec` substitui o shell → stdin/stdout/stderr do `su` passam a ser os pipes do daemon. Opção pref `sh`: roda `sh` e escreve `exec su\n<cmd>`.
- **Direct** (`Config.C`, virtual space/sem root): `Tools.I(argv)` executa direto.
- Lista de su sugeridos na UI: `su, ku.sud, /system/bin/su, /system/xbin/su, /su/bin/su, /system/xbin/bstk/su, /sbin/su, /magisk/.core/bin/su, /magisk/phh/bin/su` (`ConfigListAdapter.java:885`).
- Falha ⇒ "Test 1": roda `exec id`, `chmod 0755` no binário e tenta de novo; depois tenta copiar o binário para `/system/bin`, `/sbin/.magisk/...` (`f0.S0`, `:2179`).

---

## 2. IPC Java ⇄ daemon (a parte mais valiosa)

### 2.1 Transporte [C]
- **Pipes stdio** do processo daemon (`Process.getOutputStream/InputStream/ErrorStream`) — `f0.O0` `:2723-2729`.
- **Handshake textual** (`f0.j()`/`k()`, `:2297`): o daemon escreve `"android-daemon err"` no stderr e `"android-daemon out"` no stdout; em seguida um byte `'1'` ou `'0'` + `\n`.
  - `'1'` ⇒ o daemon criou **FIFOs** `i`, `o`, `e` em `filesDir`; o Java abre `o`/`e` para leitura e `i` para escrita, responde `'Y'\n` (ou `'N'\n` se falhar) no pipe original e seta `InOut.o = true` (modo binário puro).
  - `'0'` ⇒ continua nos pipes com **codificação hex** (abaixo).
- Sucesso grava pref `root-ok = 16155` (`(1*16338.7f-183.7f)+0.5`).
- Sinais fora de banda via arquivos em `filesDir` (escrita atômica `.tmp` + rename), conteúdo = int LE `f796e` (id/pid recebido no handshake):
  - `stop` → cancelar busca em andamento (`f0.h()`/`v()`, `:2448`; botão de cancelar `MainService.java:3798`).
  - `OOM` → pouca memória (`f0.z()`, `:3639`).

### 2.2 Framing [C] (`InOut.java`)
**Java → daemon** (um pacote por `InOut.l()`):
```
buffer lógico: [cmd:u8][seq:u8][payload...]
modo hex (InOut.o=false):  ";;" + para cada byte: ('a'+low_nibble) ('a'+high_nibble) + "\n\n"
modo FIFO (InOut.o=true):  bytes crus, sem tamanho
```
(`InOut.b.a()`, `:144`; `q()` `:451` põe cmd em [0] e seq em [1]).

**Daemon → Java** (`InOut.k()`, `:339`):
```
... lixo/linhas ignorados até byte 0x02 (STX)
len:int32 LE (2 .. 10 MiB)
payload[len]  — último byte DEVE ser 0x03 (ETX)
payload = [type:u8][seq:u8][dados...]
```
No modo hex o leitor decodifica pares `a..p` e deixa passar qualquer outro byte cru.

**Tipos primitivos** (`InOut.u/v/w`, `:480-497`), sempre little-endian:
- `u(int)` = **int32 XOR `byteOrderMask`** (default 15). ⚠️ `h2.updateLocale()` altera esse mask via reflexão se o APK foi modificado (checagem de `classes.dex`/`res/raw`) → protocolo quebra silenciosamente em APK adulterado [C] (`h2.java`, strings Caesar-3 `"dqgurlg1h{w1LqRxw"`→`android.ext.InOut`, `"e|whRughuPdvn"`→`byteOrderMask`).
- `v(long)` = "ponteiro": 8 bytes crus se daemon 64 bits, senão `u((int)x)` (XOR!).
- `w(long)` = 8 bytes crus sempre (valores, doubles via `doubleToRawLongBits`).
- `s(byte)`, `t(bytes,len)`.
- Na resposta: `w.g()` = ponteiro (8 ou 4 bytes conforme `InOut.m`), sem XOR.

**seq**: 0 = UI; scripts Lua usam `Script.t3()` (≠0) e respostas com seq≠0 são roteadas para o Script (ex.: tipo 22 → `Script.C3`, 31 → `Script.Y2`) [C].

**Handshake binário** após a troca text