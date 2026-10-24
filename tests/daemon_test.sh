#!/usr/bin/env bash
# Testa o tinkerd no Linux do PC contra um processo alvo de verdade (precisa de root ou ptrace liberado).
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d); tpid=
trap 'kill $tpid 2>/dev/null || true; rm -rf "$out"' EXIT
cc -O2 -Wall -Werror -pthread -o "$out/tinkerd" daemon/tinkerd.c -lm
cc -O2 -o "$out/target" tests/target.c
"$out/target" > "$out/info" & tpid=$!
for _ in $(seq 50); do [ -s "$out/info" ] && break; sleep 0.1; done
read -r pid iaddr faddr paddr xaddr < "$out/info"

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

# Ponteiros: acha root → +10 → +20 e o caminho volta a dar no mesmo endereço
ptr=$(printf '%s\n' "attach $pid" "pointers $paddr 256 3" | "$out/tinkerd")
echo "$ptr" | head -20
chain=$(grep -m1 -E '^target\+[0-9a-f]+ 10 20$' <<<"$ptr") || { echo "FALHOU: ponteiro não achado"; exit 1; }
res=$(printf '%s\n' "attach $pid" "resolve $chain" "resolve target+0" "resolve nada.so+10 8" "resolve xyz" | "$out/tinkerd")
echo "$res"
mapfile -t r < <(sed 1,2d <<<"$res")
[ "${r[0]}" = "ok $paddr" ] && [[ "${r[1]}" =~ ^ok\ [0-9a-f]+$ ]] && [ "${r[2]}" = "err módulo nada.so não carregado" ] \
    && [ "${r[3]}" = "err ponteiro inválido" ] || { echo "FALHOU: resolve"; exit 1; }

kill $tpid; wait $tpid 2>/dev/null || true
dead=$(printf 'attach %s\n' "$pid" | "$out/tinkerd")
grep -q "^err processo $pid não existe$" <<<"$dead" || { echo "FALHOU: jogo fechado"; exit 1; }
echo "daemon ok"
