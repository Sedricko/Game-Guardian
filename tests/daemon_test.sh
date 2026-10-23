#!/usr/bin/env bash
# Testa o tinkerd no Linux do PC contra um processo alvo de verdade (precisa de root ou ptrace liberado).
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d); tpid=
trap 'kill $tpid 2>/dev/null; rm -rf "$out"' EXIT
cc -O2 -Wall -Werror -o "$out/tinkerd" daemon/tinkerd.c -lm
cc -O2 -o "$out/target" tests/target.c
"$out/target" > "$out/info" & tpid=$!
for _ in $(seq 50); do [ -s "$out/info" ] && break; sleep 0.1; done
read -r pid iaddr faddr < "$out/info"

got=$("$out/tinkerd" <<CMDS
attach $pid
search i32 123456789
write $iaddr 987654321
refine 987654321
list 10
search f32 3.5
list 1000
writeall 7.25
refine 7.25
search i32 nada
CMDS
)
echo "$got"
check() { grep -qxF "$1" <<<"$got" || { echo "FALHOU: esperava a linha '$1'"; exit 1; }; }
check "$iaddr 987654321"
check "$faddr 3.5"
check "err valor inválido: nada"
grep -qE '^ok [1-9][0-9]* 0$' <<<"$got" || { echo "FALHOU: writeall"; exit 1; }
echo "daemon ok"
