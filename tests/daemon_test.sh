#!/usr/bin/env bash
# Testa o tinkerd no Linux do PC contra um processo alvo de verdade (precisa de root ou ptrace liberado).
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d); tpid=
trap 'kill $tpid 2>/dev/null || true; rm -rf "$out"' EXIT
cc -O2 -Wall -Werror -o "$out/tinkerd" daemon/tinkerd.c -lm
cc -O2 -o "$out/target" tests/target.c
"$out/target" > "$out/info" & tpid=$!
for _ in $(seq 50); do [ -s "$out/info" ] && break; sleep 0.1; done
read -r pid iaddr faddr < "$out/info"

got=$("$out/tinkerd" <<CMDS
search i32 1
attach $pid
regions
search i32 123456789
write $iaddr i32 987654321
refine 987654321
list 10
read $iaddr i32
search f64,f32 3.5
list 1000
writeall 7.25
refine 7.25
search i32 1.5
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
check "^(Ch|A) [0-9]+ [0-9]+ 1$"                # regions lista a região do malloc, marcada para busca
check "^$iaddr i32 (Ch|A) 987654321$"           # write + refine + list
check "^ok 987654321$"                          # read
check "^$faddr f32 (Ch|A) 3.5$"                 # auto acha o float
check "^ok [1-9][0-9]* 0$"                      # writeall sem falhas
check "^err valor inválido: 1.5$"               # 1.5 não é Dword
check "^err região inválida$"
check "^err valor inválido: nada$"
[ "$(grep -c '^ok$' <<<"$got")" -ge 6 ] || { echo "FALHOU: oks"; exit 1; }

# Auto: um valor que também casa como Qword só pode aparecer uma vez, como Dword
auto=$(printf 'attach %s\nsearch auto 555\nlist 10\n' "$pid" | "$out/tinkerd")
echo "$auto"
grep -qx "ok 1" <<<"$auto" && grep -qE " i32 (Ch|A) 555$" <<<"$auto" || { echo "FALHOU: Auto duplicou o endereço"; exit 1; }

kill $tpid; wait $tpid 2>/dev/null || true
dead=$(printf 'attach %s\n' "$pid" | "$out/tinkerd")
grep -q "^err processo $pid não existe$" <<<"$dead" || { echo "FALHOU: jogo fechado"; exit 1; }
echo "daemon ok"
