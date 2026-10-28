#!/usr/bin/env bash
# Testa o tinkerd no Linux do PC contra um processo alvo de verdade (precisa de root ou ptrace liberado).
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d); tpid=
trap 'kill -9 $tpid 2>/dev/null || true; rm -rf "$out"' EXIT  # -9: um alvo parado (SIGSTOP) ignora o SIGTERM
cc -O2 -Wall -Werror -pthread -o "$out/tinkerd" daemon/tinkerd.c -lm
cc -O2 -pthread -rdynamic -o "$out/target" tests/target.c  # -rdynamic: os nomes das funções ficam na tabela de símbolos, como numa lib
"$out/target" > "$out/info" & tpid=$!
for _ in $(seq 50); do [ -s "$out/info" ] && break; sleep 0.1; done
read -r pid iaddr faddr paddr xaddr oaddr yaddr zaddr qaddr waddr taddr caddr maddr kaddr ltaddr lnaddr ppaddr fraddr < "$out/info"

got=$("$out/tinkerd" <<CMDS
search i32 1
info
attach $pid
regions
search i32 123456789
write $iaddr i32 987654321
refine 987654321
list 10
read $iaddr i32
dump $iaddr i32 3
dump $iaddr i32 99999
search f64,f32 3.5
list 1000
writeall 7.25
refine 7.25
search i32 1.5
search i32 987654300~987654399
undo
search i32 987654000~987654999
refine 987654320~987654330
undo
undo
write $iaddr i32 1~2
ranges Xyz
ranges Ch,A
search i32 nada
pause
resume
CMDS
)
echo "$got"
check() { grep -qE "$1" <<<"$got" || { echo "FALHOU: esperava uma linha com /$1/"; exit 1; }; }
check "^err nenhum jogo escolhido$"
check "^ok uid=[0-9]+ kernel=.+ pagina=[0-9]+ selinux="
check "^(Ch|A) [0-9]+ [0-9]+ 1$"                # regions lista a região do malloc, marcada para busca
check "^$iaddr i32 (Ch|A) 987654321$"           # write + refine + list
check "^ok 987654321$"                          # read
check "^$iaddr 987654321$"                      # dump: primeiro valor
check "^[0-9a-f]+ 555$"                         # dump: o vizinho seguinte
[ "$(grep -c "^[0-9a-f]* " <<<"$got")" -ge 64 ] || { echo "FALHOU: dump com N inválido não usou 64"; exit 1; }
check "^$faddr f32 (Ch|A) 3.5$"                 # auto acha o float
check "^ok [1-9][0-9]* 0$"                      # writeall sem falhas
check "^err valor inválido: 1.5$"               # 1.5 não é Dword
check "^err nada para desfazer$"                # undo logo após uma busca
check "^err valor inválido: 1~2$"               # faixa não serve para escrever
check "^err região inválida$"
check "^err valor inválido: nada$"
[ "$(grep -c '^ok$' <<<"$got")" -ge 6 ] || { echo "FALHOU: oks"; exit 1; }

# Auto: um valor que também casa como Qword só pode aparecer uma vez, como Dword
auto=$(printf 'attach %s\nsearch auto 555\nlist 10\n' "$pid" | "$out/tinkerd")
echo "$auto"
grep -qx "ok 1" <<<"$auto" && grep -qE " i32 (Ch|A) 555$" <<<"$auto" || { echo "FALHOU: Auto duplicou o endereço"; exit 1; }

# Vários valores com ";": acha os dois, refina para um; um item que não é inteiro invalida o Dword
multi=$(printf 'attach %s\nsearch i32 555;987654321\nrefine 555\nsearch i32 555;1.5\n' "$pid" | "$out/tinkerd")
echo "$multi"
[ "$(sed -n 3p <<<"$multi")" = "ok 2" ] && [ "$(sed -n 4p <<<"$multi")" = "ok 1" ] && grep -qx "err valor inválido: 555;1.5" <<<"$multi" \
    || { echo "FALHOU: vários valores"; exit 1; }

# Desfazer: faixa acha vários, refino diminui, desfazer volta exatamente ao anterior
und=$(printf 'attach %s\nsearch i32 0~1000\nrefine 555\nundo\nundo\n' "$pid" | "$out/tinkerd" | grep '^ok [0-9]')
echo "$und"
mapfile -t n < <(sed 's/^ok //' <<<"$und")
[ "${n[0]}" -gt "${n[1]}" ] && [ "${n[2]}" = "${n[0]}" ] && [ "${n[3]}" = "${n[1]}" ] || { echo "FALHOU: desfazer/refazer"; exit 1; }

# Valor desconhecido: guarda a memória, muda o valor, "aumentou" acha; depois "diminuiu em 999"
unk=$(printf '%s\n' "attach $pid" "snapshot i32" "list 5" "writeall 1" "write $iaddr i32 987654999" "compare inc" "list 50" \
    "compare same" "write $iaddr i32 987654000" "compare decby 999" "list 5" "compare incby 1~5" "undo" "compare bla" | "$out/tinkerd")
echo "$unk"
mapfile -t u < <(grep -E '^(ok|err)' <<<"$unk")
[[ "${u[2]}" =~ ^ok\ [0-9]{4,}$ ]] && [ "${u[3]}" = "${u[2]}" ] && [ "${u[4]}" = "err refine mais antes de editar todos" ] \
    || { echo "FALHOU: snapshot"; exit 1; }
grep -qE "^$iaddr i32 (Ch|A) 987654999$" <<<"$unk" || { echo "FALHOU: aumentou"; exit 1; }
grep -qE "^$iaddr i32 (Ch|A) 987654000$" <<<"$unk" && [ "${u[10]}" = "ok 1" ] && [ "${u[12]}" = "ok 0" ] && [ "${u[13]}" = "ok 1" ] \
    && [ "${u[14]}" = "err comparação inválida: bla" ] || { echo "FALHOU: diminuiu em / desfazer"; exit 1; }

# Muitos candidatos (aqui: mais de 10) ficam no arquivo: "não mudou" mantém, lista vazia, e o valor mudado sobra no fim
big=$(printf '%s\n' "attach $pid" "snapshot i32" "compare same" "list 5" "write $iaddr i32 987654321" "compare changed" "list 5" "undo" "write $iaddr i32 987654000" \
    | TINKER_LIST_MAX=10 "$out/tinkerd")
echo "$big"
mapfile -t b < <(grep -E '^(ok|err)' <<<"$big")
[[ "${b[3]}" =~ ^ok\ [0-9]{3,}$ ]] && [ "${b[4]}" = "${b[3]}" ] && [ "$(sed -n '5p' <<<"$big")" = "${b[4]}" ] \
    && grep -qE "^$iaddr i32 (Ch|A) 987654321$" <<<"$big" && [ "${b[8]}" = "err nada para desfazer" ] || { echo "FALHOU: candidatos no arquivo"; exit 1; }

# Depois de uma busca exata também dá para comparar com o valor anterior
cmpx=$(printf '%s\n' "attach $pid" "search i32 987654000" "write $iaddr i32 987654001" "compare incby 1" | "$out/tinkerd")
[ "$(tail -1 <<<"$cmpx")" = "ok 1" ] || { echo "FALHOU: comparar após busca exata"; echo "$cmpx"; exit 1; }

# Cifrado (XOR): acha 777777 pela chave do lado, edita trancando com a mesma chave, a chave não muda
xr=$(printf '%s\n' "attach $pid" "search i32,f32,xi32,xf32 777777" "list 20" "write $xaddr xi32 888888" "read $xaddr xi32" "read $xaddr i32" \
    "refine 888888" "writeall 999999" "read $xaddr xi32" "dump $xaddr xi32 2" "snapshot xi32" | "$out/tinkerd")
echo "$xr"
grep -qE "^$xaddr xi32 (Ch|A) 777777$" <<<"$xr" || { echo "FALHOU: busca cifrada"; exit 1; }
mapfile -t x < <(grep -E '^(ok|err)' <<<"$xr")
[ "${x[5]}" = "ok 888888" ] && [ "${x[6]}" = "ok $((0x5A3C9D21))" ] && [ "${x[7]}" = "ok 1" ] && [ "${x[9]}" = "ok 999999" ] \
    && [ "${x[10]}" = "err tipo inválido: xi32" ] && [ "${x[11]}" = "err tipo inválido: xi32" ] || { echo "FALHOU: edição cifrada"; exit 1; }

# Como o "150X8" do GameGuardian (chave 8 bytes ao lado) e inteiros em dobro (Dart/JavaScript)
gg=$(printf '%s\n' "attach $pid" "search xi32@8 31337" "list 5" "write $yaddr xi32@8 41414" "read $yaddr xi32@8" "read $yaddr i32" \
    "search i32x2 40404" "list 5" "write $zaddr i32x2 50505" "read $zaddr i32" "search i64x2 9000000000" "list 5" "search xi32@3 1" "search xi32@0 1" "dump $zaddr i32x2 2" | "$out/tinkerd")
echo "$gg"
grep -qE "^$yaddr xi32@8 (Ch|A) 31337$" <<<"$gg" && grep -qx "ok 41414" <<<"$gg" && grep -qx "ok $((41414 ^ 0x3B7E11C5))" <<<"$gg" \
    && grep -qE "^$zaddr i32x2 (Ch|A) 40404$" <<<"$gg" && grep -qx "ok 101010" <<<"$gg" && grep -qE "^$qaddr i64x2 (Ch|A) 9000000000$" <<<"$gg" \
    && grep -qx "err tipo inválido: xi32@3" <<<"$gg" && grep -qx "err tipo inválido: xi32@0" <<<"$gg" && grep -qx "err tipo inválido: i32x2" <<<"$gg" \
    || { echo "FALHOU: XOR com distância / dobro"; exit 1; }

# Cifrado de 8 bytes (ObscuredLong): acha, escreve trancando com a mesma chave
x8=$(printf '%s\n' "attach $pid" "search i64,xi64 7777777777" "list 5" "write $waddr xi64 8888888888" "read $waddr xi64" "frozen" | "$out/tinkerd")
echo "$x8"
grep -qE "^$waddr xi64 (Ch|A) 7777777777$" <<<"$x8" && grep -qx "ok 8888888888" <<<"$x8" || { echo "FALHOU: cifrado de 8 bytes"; exit 1; }

# O que escreve aqui: o ponto de observação acha a instrução da thread que muda o contador; o jogo continua rodando
wt=$(printf '%s\n' "attach $pid" "watch $taddr 1" "watch 1 1" "where $caddr" "read $taddr i32" | "$out/tinkerd")
echo "$wt"
grep -qE "^pc [0-9a-f]+ [1-9][0-9]* target\+[0-9a-f]+$" <<<"$wt" && grep -qE "^ok [1-9]" <<<"$wt" && grep -qx "err o endereço precisa ser múltiplo de 4" <<<"$wt" \
    && grep -qE "^ok target\+[0-9a-f]+$" <<<"$wt" || { echo "FALHOU: watch/where"; exit 1; }
sleep 0.2; kill -0 "$pid" || { echo "FALHOU: o alvo morreu depois do watch"; exit 1; }
t1=$(printf '%s\n' "attach $pid" "read $taddr i32" | "$out/tinkerd" | tail -1); sleep 0.3
t2=$(printf '%s\n' "attach $pid" "read $taddr i32" | "$out/tinkerd" | tail -1)
[ "$t1" != "$t2" ] || { echo "FALHOU: a thread do alvo parou depois do watch"; exit 1; }

# Contador de chamadas: o "método" da thread roda várias vezes em 1 s, e quem chama é o próprio alvo
tr=$(printf '%s\n' "attach $pid" "trace $kaddr 1" | "$out/tinkerd")
echo "$tr"
grep -qE "^calls [1-9][0-9]*$" <<<"$tr" && grep -qE "^caller [0-9a-f]+ [1-9][0-9]* target\+[0-9a-f]+$" <<<"$tr" || { echo "FALHOU: contador de chamadas"; exit 1; }
sleep 0.2; kill -0 "$pid" || { echo "FALHOU: o alvo morreu depois do trace"; exit 1; }

# Métodos IL2CPP: pelo objeto, lista get_gold e Tick com o código e a lib
me=$(printf '%s\n' "attach $pid" "il2methods $oaddr" "il2methods $iaddr" | "$out/tinkerd")
echo "$me"
grep -qE "^method $caddr 08 target\+[0-9a-f]+ get_gold$" <<<"$me" && grep -qE "^method $kaddr 08 target\+[0-9a-f]+ Tick$" <<<"$me" \
    && grep -qx "ok 2" <<<"$me" && grep -q "^err não achei a classe" <<<"$me" || { echo "FALHOU: métodos IL2CPP"; exit 1; }

# Funções pelo nome (tabela de símbolos), a função de um endereço (eh_frame) e quem ela chama, sem IDA
fn=$(printf '%s\n' "attach $pid" "syms never_called" "syms tinker_calls" "func $kaddr" "syms zzz_nada" | TINKER_ALL_LIBS=1 "$out/tinkerd")
echo "$fn"
grep -qE "^sym $caddr [0-9]+ target never_called$" <<<"$fn" && grep -qE "^ok $kaddr [0-9a-f]+ target\+[0-9a-f]+ x86_64 tick_once$" <<<"$fn" \
    && grep -qx "ok 0" <<<"$fn" || { echo "FALHOU: funções pelo nome"; exit 1; }
ct=$(grep -m1 "^sym .* tinker_calls_tick$" <<<"$fn" | cut -d' ' -f2)
cl=$(printf '%s\n' "attach $pid" "calls $ct" | "$out/tinkerd")
echo "$cl"
grep -qE "^call [0-9a-f]+ $kaddr tick_once$" <<<"$cl" || { echo "FALHOU: quem a função chama"; exit 1; }

# Patch: escreve no código (página sem escrita) e lê de volta
pt=$(printf '%s\n' "attach $pid" "read $caddr i32" "write $caddr i32 -1869574000" "read $caddr i32" | "$out/tinkerd")
echo "$pt"
grep -qx "ok -1869574000" <<<"$pt" || { echo "FALHOU: escrita no código"; exit 1; }

# Foto do jogo: foto, o que muda sozinho (o contador), "pega a recompensa" (marca 1 e +100), diferença e volta como estava
m4=$(printf '%x' $((0x$maddr + 4)))
ph=$( { printf '%s\n' "attach $pid" "photo"; sleep 0.3; printf '%s\n' "photonoise" "write $maddr i32 1" "write $m4 i32 12445" "photodiff" "list 2000"; sleep 0.1
        printf '%s\n' "photorestore except $m4" "read $m4 i32" "photorestore all" "read $maddr i32" "read $m4 i32" "photodrop" "photodiff"; } | "$out/tinkerd")
echo "$ph" | grep -v '^[0-9a-f]* i32' | head -20
grep -qE "^$maddr i32 (Ch|A) 1$" <<<"$ph" && grep -qE "^$m4 i32 (Ch|A) 12445$" <<<"$ph" && ! grep -qE "^$taddr i32" <<<"$ph" \
    && grep -qx "ok 0" <<<"$ph" && grep -qx "ok 12345" <<<"$ph" && grep -qx "err tire a foto antes" <<<"$ph" \
    && grep -qxE "ok [1-9][0-9]? 0" <<<"$ph" && grep -qx "ok 12445" <<<"$ph" || { echo "FALHOU: foto do jogo"; exit 1; }

# 👁 Monitor: o contador (muda toda hora) vira ruído no photonoise2; a moeda que sobe 5 aparece com antes e depois;
# o salto grande vira lixo; na rodada seguinte, sem mudança, nada; e o photowin aceita centenas de faixas
wl=$(for k in $(seq 300); do printf '%x-%x,' $((0x10000000 + k * 0x10000)) $((0x10000000 + k * 0x10000 + 0x1000)); done)
st=$( { printf '%s\n' "attach $pid" "photowin ${wl%,}" "photowin off" "write $maddr i32 12345" "photo" "photonoise2" "write $maddr i32 12350" "write $m4 i32 99999999" "photostep"; sleep 0.1
        printf '%s\n' "photostep" "write $maddr i32 12345" "write $m4 i32 0" "photodrop"; } | "$out/tinkerd")
echo "$st" | head -20
grep -qx "ok 300" <<<"$st" && grep -qE "^$maddr 12345 12350 (Ch|A)$" <<<"$st" && ! grep -qE "^($m4|$taddr) " <<<"$st" \
    && [ "$(grep -cE "^$maddr " <<<"$st")" = 1 ] || { echo "FALHOU: monitor (photonoise2/photostep)"; exit 1; }

# Backup do save: guarda a pasta do jogo, muda o save, volta o backup e o save antigo reaparece
mkdir -p "$out/data/com.teste.jogo/files" "$out/data/com.teste.jogo/cache"; echo v1 > "$out/data/com.teste.jogo/files/save.dat"; echo lixo > "$out/data/com.teste.jogo/cache/x"
bk=$(printf '%s\n' "backup com.teste.jogo" "backup ../etc" | TINKER_DATA_DIR="$out/data" TINKER_SAVES_DIR="$out/saves" "$out/tinkerd")
echo "$bk"
name=$(grep -oE '^ok [0-9-]+\.tar' <<<"$bk" | cut -d' ' -f2)
echo v2 > "$out/data/com.teste.jogo/files/save.dat"
rs=$(printf '%s\n' "backups com.teste.jogo" "restoresave com.teste.jogo $name" "restoresave com.teste.jogo ../x.tar" | TINKER_DATA_DIR="$out/data" TINKER_SAVES_DIR="$out/saves" "$out/tinkerd")
echo "$rs"
[ -n "$name" ] && grep -qx "err pacote inválido" <<<"$bk" && grep -qx "ok 1" <<<"$rs" && grep -qx "err backup inválido" <<<"$rs" \
    && [ "$(cat "$out/data/com.teste.jogo/files/save.dat")" = v1 ] && ! tar -tf "$out/saves/com.teste.jogo/$name" | grep -q cache/x \
    || { echo "FALHOU: backup do save"; exit 1; }

# Editor de save: lista os arquivos (sem o cache), copia um para a pasta do Tinker, volta o editado e recusa caminho para fora
mkdir -p "$out/data/com.teste.jogo/shared_prefs" "$out/app"
echo '<map><int name="gold" value="250" /></map>' > "$out/data/com.teste.jogo/shared_prefs/com.teste.jogo.v2.playerprefs.xml"
sv=$( { printf '%s\n' "savefiles com.teste.jogo" "readsave com.teste.jogo shared_prefs/com.teste.jogo.v2.playerprefs.xml $out/app/p.xml"; sleep 0.2
        sed -i 's/250/9999/' "$out/app/p.xml" 2>/dev/null
        printf '%s\n' "writesave com.teste.jogo shared_prefs/com.teste.jogo.v2.playerprefs.xml $out/app/p.xml" "readsave com.teste.jogo ../../etc/passwd $out/app/x" "readsave com.teste.jogo files/save.dat /tmp/fora"; } \
    | TINKER_DATA_DIR="$out/data" TINKER_APP_DIR="$out/app" "$out/tinkerd")
echo "$sv"
grep -q "^file shared_prefs/com.teste.jogo.v2.playerprefs.xml " <<<"$sv" && ! grep -q "^file cache/" <<<"$sv" \
    && grep -q 'value="9999"' "$out/data/com.teste.jogo/shared_prefs/com.teste.jogo.v2.playerprefs.xml" \
    && [ "$(grep -c '^err caminho inválido' <<<"$sv")" = 2 ] || { echo "FALHOU: editor de save"; exit 1; }

# Editor de save na pasta externa (Android/data/PKG): aparece como ext/..., grava e o backup leva e devolve ela junto
mkdir -p "$out/ext/com.teste.jogo/files"
echo '{"gems": 12}' > "$out/ext/com.teste.jogo/files/progresso.json"
ex=$( { printf '%s\n' "savefiles com.teste.jogo" "backup com.teste.jogo" "readsave com.teste.jogo ext/files/progresso.json $out/app/e.json"; sleep 0.3
        sed -i 's/12/77/' "$out/app/e.json" 2>/dev/null
        printf '%s\n' "writesave com.teste.jogo ext/files/progresso.json $out/app/e.json" "readsave com.teste.jogo ext/../../x $out/app/y" "backups com.teste.jogo"; } \
    | TINKER_DATA_DIR="$out/data" TINKER_EXT_DIR="$out/ext" TINKER_SAVES_DIR="$out/saves" TINKER_APP_DIR="$out/app" "$out/tinkerd")
echo "$ex"
ename=$(grep -oE '^ok [0-9-]+\.tar' <<<"$ex" | tail -1 | cut -d' ' -f2)
grep -q "^file ext/files/progresso.json " <<<"$ex" && grep -q '"gems": 77' "$out/ext/com.teste.jogo/files/progresso.json" \
    && grep -qx "err caminho inválido" <<<"$ex" && ! grep -q -- "-ext.tar" <<<"$(grep '^save ' <<<"$ex")" && [ -f "$out/saves/com.teste.jogo/${ename%.tar}-ext.tar" ] \
    || { echo "FALHOU: editor de save na pasta externa"; exit 1; }
rx=$(printf '%s\n' "restoresave com.teste.jogo $ename" | TINKER_DATA_DIR="$out/data" TINKER_EXT_DIR="$out/ext" TINKER_SAVES_DIR="$out/saves" "$out/tinkerd")
[ "$(tail -1 <<<"$rx")" = ok ] && grep -q '"gems": 12' "$out/ext/com.teste.jogo/files/progresso.json" || { echo "FALHOU: backup da pasta externa ($rx)"; exit 1; }

# Ponteiros: acha root → +10 → +20 e o caminho volta a dar no mesmo endereço
ptr=$(printf '%s\n' "attach $pid" "pointers $paddr 256 3" | "$out/tinkerd")
echo "$ptr" | head -20
chain=$(grep -m1 -E '^target\+[0-9a-f]+ 10 20$' <<<"$ptr") || { echo "FALHOU: ponteiro não achado"; exit 1; }
res=$(printf '%s\n' "attach $pid" "resolve $chain" "resolve target+0" "resolve nada.so+10 8" "resolve xyz" "resolve target+8 0" | "$out/tinkerd")
echo "$res"
mapfile -t r < <(sed 1,2d <<<"$res")
[ "${r[0]}" = "ok $paddr" ] && [[ "${r[1]}" =~ ^ok\ [0-9a-f]+$ ]] && [ "${r[2]}" = "err módulo nada.so não carregado" ] \
    && [ "${r[3]}" = "err ponteiro inválido" ] && [[ "${r[4]}" =~ ^err\ ponteiro\ nulo ]] || { echo "FALHOU: resolve"; exit 1; }

# Ponteiros só em libs do jogo (/data/...): o alvo do teste está em /tmp, então nenhum caminho
only=$(printf '%s\n' "attach $pid" "pointers $paddr 256 3 1" | "$out/tinkerd" | tail -1)
[ "$only" = "ok 0 0" ] || { echo "FALHOU: só libs do jogo ($only)"; exit 1; }

# AOB: qualquer Dword (curinga) seguido de 555 e 0; no código acha um "mov rbp, rsp" comum
aob=$(printf '%s\n' "attach $pid" "aob ????????2b02000000000000 data" "list 50" "aobfind ????????2B02000000000000" "aob ZZ data" "aob ???? data" | "$out/tinkerd")
echo "$aob"
grep -qE "^$iaddr i32 (Ch|A) 987654001$" <<<"$aob" || { echo "FALHOU: aob na lista"; exit 1; }
mapfile -t ab < <(grep -E '^(ok|err)' <<<"$aob")
[[ "${ab[2]}" =~ ^ok\ [1-9] ]] && grep -qx "$iaddr" <<<"$aob" && [ "${ab[5]}" = "err padrão inválido: ZZ" ] && [ "${ab[6]}" = "err padrão inválido: ????" ] \
    || { echo "FALHOU: aob"; exit 1; }
code=$(printf '%s\n' "attach $pid" "aob 4889e5 code" | "$out/tinkerd" | tail -1)
[[ "$code" =~ ^ok\ [1-9] ]] || { echo "FALHOU: aob no código ($code)"; exit 1; }

# IL2CPP: do endereço do gold acha o objeto, a classe Game.Player e os campos (sem o estático)
il=$(printf '%s\n' "attach $pid" "il2cpp $(printf '%x' $((0x$oaddr + 0x14)))" "il2cpp $iaddr" | "$out/tinkerd")
echo "$il"
grep -qx "class Game.Player $oaddr" <<<"$il" && grep -qx "field 10 0c hp" <<<"$il" && grep -qx "field 14 08 gold" <<<"$il" \
    && grep -qx "field 18 0e nick" <<<"$il" && grep -qx "text 18 4 Ação" <<<"$il" && grep -qx "count 30 7" <<<"$il" \
    && ! grep -q instances <<<"$il" && grep -q "^err não achei a classe IL2CPP" <<<"$il" || { echo "FALHOU: il2cpp"; exit 1; }
objs=$(printf '%s\n' "attach $pid" "il2objs Game.Player" "il2objs Game.Ninguem" "il2objs Outro.Player" "il2objs Player" | "$out/tinkerd")
echo "$objs"
grep -qx "obj $oaddr" <<<"$objs" && [ "$(grep -c '^obj ' <<<"$objs")" -ge 2 ] && grep -q "^err classe não encontrada (o jogo" <<<"$objs" && grep -q "^err classe não encontrada (o nome existe" <<<"$objs" \
    || { echo "FALHOU: il2objs"; exit 1; }

cl=$(printf '%s\n' "attach $pid" "il2classes" | "$out/tinkerd")
grep -qE "^class Game.Player [0-9a-f]+ 6 hp,gold,nick,pos,bag,instances$" <<<"$cl" || { echo "FALHOU: il2classes"; echo "$cl" | head; exit 1; }

# Print da tela: no PC não há screencap → erro claro; com um screencap falso, o base64 volta igual ao arquivo
sc=$(printf 'screencap\n' | "$out/tinkerd" | tail -1)
[ "$sc" = "err o screencap não devolveu imagem" ] || { echo "FALHOU: screencap sem programa ($sc)"; exit 1; }
mkdir -p "$out/bin"; head -c 1000 /dev/urandom > "$out/img"; printf '#!/bin/sh\ncat %s\n' "$out/img" > "$out/bin/screencap"; chmod +x "$out/bin/screencap"
printf 'screencap\n' | PATH="$out/bin:$PATH" "$out/tinkerd" | grep '^| ' | cut -c3- | base64 -d > "$out/img2"
cmp -s "$out/img" "$out/img2" || { echo "FALHOU: screencap base64"; exit 1; }

# Toques: só números chegam ao input (com um input falso que grava os argumentos); texto é recusado
printf '#!/bin/sh\necho "$@" > %s\n' "$out/args" > "$out/bin/input"; chmod +x "$out/bin/input"
tp=$(printf '%s\n' "input tap 100 200" "input tap 1;reboot 2" "input swipe 1 2 3 4 300" "input bla 1" | PATH="$out/bin:$PATH" "$out/tinkerd")
mapfile -t t < <(grep -E '^(ok|err)' <<<"$tp")
[ "${t[1]}" = "ok" ] && [[ "${t[2]}" =~ ^err\ número\ inválido ]] && [ "${t[3]}" = "ok" ] && [[ "${t[4]}" =~ ^err\ uso ]] \
    && [ "$(cat "$out/args")" = "swipe 1 2 3 4 300" ] || { echo "FALHOU: input ($tp)"; exit 1; }
printf '#!/bin/sh\necho "  mCurrentFocus=Window{1 u0 com.jogo/com.jogo.Main}"\n' > "$out/bin/dumpsys"; chmod +x "$out/bin/dumpsys"
fc=$(printf 'focus\n' | PATH="$out/bin:$PATH" "$out/tinkerd")
grep -q "^| *mCurrentFocus=Window{1 u0 com.jogo/com.jogo.Main}" <<<"$fc" || { echo "FALHOU: focus ($fc)"; exit 1; }

# Busca em grupo: hp (99.5) e gold (4321) do mesmo objeto, a 4 bytes; distância curta demais não acha; um valor só vira busca normal
grp=$(printf '%s\n' "attach $pid" "group i32,f32 4321;99.5 16" "list 10" "group i32,f32 4321;99.5 2" "group i32 4321 16" "group i32 4321;1 0" | "$out/tinkerd")
# itens repetidos: 4321;4321 precisa de dois 4321 perto (só há um) → 0; item que nenhum tipo entende (abc) não é exigido
dup=$(printf '%s\n' "attach $pid" "group i32 4321;4321 16" "group i32,f32 4321;99.5;abc 16" | "$out/tinkerd" | grep '^ok [0-9]')
[ "$dup" = "$(printf 'ok 0\nok 2')" ] || { echo "FALHOU: grupo com itens repetidos ($dup)"; exit 1; }
# e com dois iguais perto (vida e vida máxima) o grupo acha os dois
i8=$(printf '%x' $((0x$iaddr + 4)))
two=$(printf '%s\n' "attach $pid" "write $iaddr i32 777001" "write $i8 i32 777001" "group i32 777001;777001 8" "write $i8 i32 555" | "$out/tinkerd" | grep '^ok [0-9]')
[ "$two" = "ok 2" ] || { echo "FALHOU: grupo com dois iguais ($two)"; exit 1; }
echo "$grp"
mapfile -t g < <(grep -E '^(ok|err)' <<<"$grp")
[ "${g[2]}" = "ok 2" ] && grep -qE "^$(printf '%x' $((0x$oaddr + 0x14))) i32 (Ch|A) 4321$" <<<"$grp" && grep -qE "^$(printf '%x' $((0x$oaddr + 0x10))) f32 (Ch|A) 99.5$" <<<"$grp" \
    && [ "${g[4]}" = "ok 0" ] && [[ "${g[5]}" =~ ^ok\ [1-9] ]] && [[ "${g[6]}" =~ ^err\ distância ]] || { echo "FALHOU: busca em grupo"; exit 1; }

# Valor que não cabe no tipo não é escrito cortado (10000000000 num Dword virava 1410065408)
big2=$(printf '%s\n' "attach $pid" "write $iaddr i32 10000000000" "write $iaddr i32 4294967295" "write $iaddr i8 300" "read $iaddr i32" "write $iaddr i32 987654000" | "$out/tinkerd")
echo "$big2"
mapfile -t w < <(sed 1,2d <<<"$big2")
[[ "${w[0]}" =~ ^err\ valor\ fora\ do\ tipo:\ 10000000000\ não\ cabe\ em\ Dword ]] && [ "${w[1]}" = "ok" ] && [[ "${w[2]}" =~ ^err\ valor\ fora\ do\ tipo ]] \
    && [ "${w[3]}" = "ok -1" ] || { echo "FALHOU: valor fora do tipo"; exit 1; }

# Congelar dentro do daemon: o jogo (aqui, um write por fora) muda o valor e a thread põe de volta em até 100 ms
frz=$( { printf '%s\n' "attach $pid" "freeze $iaddr i32 424242" "frozen" "write $iaddr i32 1"; sleep 0.4
         printf '%s\n' "read $iaddr i32" "unfreeze all" "write $iaddr i32 5"; sleep 0.4
         printf '%s\n' "read $iaddr i32" "frozen" "freeze $iaddr i32 1.5" "write $iaddr i32 987654000"; } | "$out/tinkerd")
echo "$frz"
mapfile -t fz < <(grep -E '^(ok|err)' <<<"$frz")
[ "${fz[2]}" = "ok" ] && [ "${fz[3]}" = "ok 1" ] && grep -qE "^$iaddr i32 0$" <<<"$frz" && [ "${fz[5]}" = "ok 424242" ] \
    && [ "${fz[8]}" = "ok 5" ] && [ "${fz[9]}" = "ok 0" ] && [ "${fz[10]}" = "err valor inválido: 1.5" ] || { echo "FALHOU: congelar no daemon"; exit 1; }

# Lua (xLua): busca do inteiro Lua (valor + tipo ao lado), tabela pelo valor e pela própria tabela, editar
lu=$(printf '%s\n' "attach $pid" "search li 31415" "list 5" "luatable $lnaddr" "luatable $ltaddr" "write $lnaddr li 999" "read $lnaddr li" "search i64 31415" | "$out/tinkerd")
echo "$lu"
mapfile -t lo < <(grep -E '^(ok|err)' <<<"$lu")
[ "${lo[2]}" = "ok 1" ] && grep -q "^$lnaddr li " <<<"$lu" && grep -q "^lua $lnaddr int gold 31415$" <<<"$lu" \
    && grep -q " num leaf 2.5$" <<<"$lu" && grep -q " str name hero$" <<<"$lu" && grep -q " table bag " <<<"$lu" \
    && [ "$(grep -c '^lua ' <<<"$lu")" = 8 ] && [ "${lo[7]}" = "ok 999" ] || { echo "FALHOU: Lua"; exit 1; }

# Ponteiro não vira Dword 🔒: metade de baixo como "chave", de cima como "valor cifrado"
pl=$(printf '%s\n' "attach $pid" "read $ppaddr i32" "read $(printf '%x' $((0x$ppaddr + 4))) i32" | "$out/tinkerd" | grep '^ok -\?[0-9]' | cut -d' ' -f2 | tr '\n' ' ')
read -r plo phi <<<"$pl"
pv=$(( (plo ^ phi) )); pv=$(( pv > 2147483647 ? pv - 4294967296 : (pv < -2147483648 ? pv + 4294967296 : pv) ))
px=$(printf '%s\n' "attach $pid" "search xi32 $pv" "list 100000" | "$out/tinkerd")
! grep -q "^$ppaddr xi32" <<<"$px" || { echo "FALHOU: ponteiro achado como Dword 🔒"; exit 1; }

# Onde o jogo escreve: marca as páginas, o ticker do alvo escreve, e conta por região (em KB); aparelho sem soft-dirty: erro claro
ac=$( { printf '%s\n' "attach $pid" "activity start"
        for k in 0 1 2 3 4 5 6 7; do printf 'write %x i32 7\n' $((0x$fraddr + k * 4096)); done; sleep 0.3; printf '%s\n' "activity"; } | "$out/tinkerd")
echo "$ac"
grep -qE "^act A ([3-9][0-9]|[1-9][0-9]{2,}) [0-9]+$" <<<"$ac" && grep -qE "^how (soft|smaps)$" <<<"$ac" && grep -qE "^ok [0-9]+$" <<<"$ac" \
    || { echo "FALHOU: onde o jogo escreve"; exit 1; }

# Congelar com regra "up": o jogo não tira (500 volta a 1000), mas o que ganhar fica (2000 vira o novo congelado)
fu=$( { printf '%s\n' "attach $pid" "freeze $iaddr i32 1000 up" "write $iaddr i32 500"; sleep 0.4
        printf '%s\n' "read $iaddr i32" "write $iaddr i32 2000"; sleep 0.4
        printf '%s\n' "read $iaddr i32" "write $iaddr i32 1500"; sleep 0.4
        printf '%s\n' "read $iaddr i32" "unfreeze all"; } | "$out/tinkerd")
echo "$fu"
[ "$(grep -cx 'ok 1000' <<<"$fu")" = 1 ] && [ "$(grep -cx 'ok 2000' <<<"$fu")" = 2 ] || { echo "FALHOU: congelar com regra"; exit 1; }

# AOB no código por região: o código do alvo fica fora de /data, então aparece em "code" e não em "xa" (só o do jogo)
cw=$(printf '%s\n' "attach $pid" "dump $caddr i32 4" | "$out/tinkerd" | grep "^$caddr\|^[0-9a-f]* -\?[0-9]" | head -4 | awk '{printf "%08x", ($2 < 0 ? $2 + 4294967296 : $2)}')
ch=$(echo "$cw" | sed 's/\(..\)\(..\)\(..\)\(..\)/\4\3\2\1/g')
cx=$(printf '%s\n' "attach $pid" "aobfind $ch code" "aobfind $ch xa" | "$out/tinkerd")
echo "$cx"
grep -qE "^ok [1-9]" <<<"$cx" && grep -qx "ok 0" <<<"$cx" || { echo "FALHOU: AOB no código por região"; exit 1; }

# Base do speedhack: o daemon consegue se ligar a um processo filho de teste (resposta sim ou não, nunca erro)
ca=$(printf '%s\n' "canattach" | "$out/tinkerd")
echo "$ca"
grep -qE "^ok (sim|não)" <<<"$ca" || { echo "FALHOU: canattach"; exit 1; }

# Autoteste do app: o daemon se liga a ele mesmo e acha os próprios valores de teste (não toca em outro processo)
sp="$out/self.fifo"; mkfifo "$sp"
"$out/tinkerd" < "$sp" > "$out/self.out" & spid=$!
exec 7> "$sp"; echo info >&7; sleep 0.3
dpid=$(grep -o 'pid=[0-9]*' "$out/self.out" | cut -d= -f2)
printf '%s\n' "attach $dpid" "search i32 271828182" "aobfind $(printf 'TINKER-AUTOTESTE-AOB' | od -An -tx1 | tr -d ' \n')" >&7; sleep 0.5
exec 7>&-; wait $spid 2>/dev/null || true
cat "$out/self.out"
mapfile -t sf < <(grep -E '^(ok|err)' "$out/self.out")
[ -n "$dpid" ] && [ "${sf[2]}" = "ok" ] && [[ "${sf[3]}" =~ ^ok\ [1-9] ]] && [[ "${sf[4]}" =~ ^ok\ [1-9] ]] || { echo "FALHOU: autoteste no próprio daemon"; exit 1; }

# Dados do autoteste do app: o daemon ligado a ele mesmo acha a tabela Lua de teste e conta a memória nova escrita
sp2="$out/self2.fifo"; mkfifo "$sp2"
"$out/tinkerd" < "$sp2" > "$out/self2.out" & spid=$!
exec 8> "$sp2"; echo info >&8; sleep 0.3
dpid=$(grep -o 'pid=[0-9]*' "$out/self2.out" | cut -d= -f2)
printf '%s\n' "attach $dpid" "selftestdata" >&8; sleep 0.3
lt=$(grep '^lua ' "$out/self2.out" | cut -d' ' -f2); fr=$(grep '^fresh ' "$out/self2.out" | cut -d' ' -f2)
printf '%s\n' "luatable $lt" "activity start" >&8
for k in 0 1 2 3 4 5 6 7; do printf 'write %x i32 7\n' $((0x$fr + k * 4096)) >&8; done
printf '%s\n' "activity" >&8; sleep 0.5
exec 8>&-; wait $spid 2>/dev/null || true
cat "$out/self2.out"
grep -q " int gold 31415$" "$out/self2.out" && grep -qE "^act A ([3-9][0-9]|[1-9][0-9]{2,}) " "$out/self2.out" || { echo "FALHOU: dados do autoteste"; exit 1; }

# Daemon saindo com o jogo pausado (app caiu no meio da busca): o jogo tem que voltar a rodar
printf '%s\n' "attach $pid" "pause" | "$out/tinkerd" >/dev/null
grep -q '^State:.*S (sleeping)' /proc/$pid/status || { echo "FALHOU: jogo ficou pausado após EOF"; grep State /proc/$pid/status; exit 1; }
{ printf '%s\n' "attach $pid" "pause"; sleep 1; } | "$out/tinkerd" >/dev/null & dpid=$!
sleep 0.5; pkill -TERM -f "^$out/tinkerd$" || { echo "FALHOU: daemon não encontrado"; exit 1; }; wait $dpid 2>/dev/null || true
grep -q '^State:.*S (sleeping)' /proc/$pid/status || { echo "FALHOU: jogo ficou pausado após SIGTERM"; grep State /proc/$pid/status; exit 1; }

# "termina em": dos achados do valor de iaddr fica só o do final de endereço pedido; undo volta
end=${iaddr: -3}
iv=$(printf '%s\n' "attach $pid" "read $iaddr i32" | "$out/tinkerd" | tail -1 | cut -d' ' -f2)
ae=$(printf '%s\n' "attach $pid" "search i32 $iv" "addrend $end" "list 5" "addrend fff0000" "undo" "addrend zz" | "$out/tinkerd")
echo "$ae"
mapfile -t ar < <(grep -E '^(ok|err)' <<<"$ae")
[ "${ar[3]}" = "ok 1" ] && grep -q "^$iaddr " <<<"$ae" && [ "${ar[5]}" = "ok 0" ] && [ "${ar[6]}" = "ok 1" ] && [ "${ar[7]}" = "err final de endereço inválido: zz" ] \
    || { echo "FALHOU: addrend"; exit 1; }

kill $tpid; wait $tpid 2>/dev/null || true
dead=$(printf 'attach %s\n' "$pid" | "$out/tinkerd")
grep -q "^err processo $pid não existe$" <<<"$dead" || { echo "FALHOU: jogo fechado"; exit 1; }
# Unity 32 bits (ponteiro de 4 bytes): a lib do Jogo de Teste compilada em 32 bits, carregada por um programa 32 bits
jni=$(dirname "$(dirname "$(readlink -f "$(command -v javac || command -v java)")")")/include
printf '#include <dlfcn.h>\n#include <unistd.h>\nint main(int c,char**v){(void)c;if(!dlopen(v[1],RTLD_NOW))return 1;pause();}\n' > "$out/host.c"
if [ -f "$jni/jni.h" ] && cc -m32 -o "$out/host32" "$out/host.c" -ldl 2>/dev/null && cc -m32 -O1 -shared -fPIC -I"$jni" -I"$jni/linux" -o "$out/libjogo32.so" testgame/src/main/c/jogo.c; then
    "$out/host32" "$out/libjogo32.so" & p32=$!; sleep 0.5
    h=$(printf '%s\n' "attach $p32" "search i32 987654" "list 5" | "$out/tinkerd" | grep -m1 ' i32 ' | cut -d' ' -f1)
    u=$(printf '%s\n' "attach $p32" "il2cpp $h" | "$out/tinkerd")
    kill $p32
    echo "$u"
    grep -q "^class Jogo.Heroi " <<<"$u" && grep -qx "text 18 5 Herói" <<<"$u" && grep -qx "count 30 7" <<<"$u" && grep -qx "field 20 11 pos" <<<"$u" \
        || { echo "FALHOU: unity 32 bits"; exit 1; }
else
    echo "sem compilador 32 bits: pulei o Unity 32 bits"
fi
echo "daemon ok"
