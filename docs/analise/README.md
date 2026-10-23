# Análise do GameGuardian 101.4

Análise estática do APK `GameGuardian-101.4.apk` para aprender como ele funciona e
planejar um app próprio, aberto, para jogos single-player/offline.
Nada foi executado; o código veio do jadx (Java) e de readelf/strings (nativo).
Marcações: **[C]** confirmado no código, **[H]** hipótese.

| Arquivo | Conteúdo |
|---|---|
| [01-nucleo-java.md](01-nucleo-java.md) | Mapa das classes ofuscadas, inicialização, root, papel de cada `ydw*`, início do IPC. **Incompleto**: falta tabela de comandos, pipeline de busca, recursos e Config. |
| [02-lua-api.md](02-lua-api.md) | API `gg.*` completa, constantes, fork do LuaJ, sandbox, LASM, ajuda oficial. Apêndice A traz os códigos de comando do daemon. |
| [03-nativo.md](03-nativo.md) | Binários nativos por ABI, leitura/escrita de memória, busca, injeção, speedhack. |
| [04-protecao-recursos.md](04-protecao-recursos.md) | Manifesto, permissões, ofuscação, rede/telemetria, APKs embutidos, layouts, idiomas. |

## Arquitetura em uma página

```
App Java (UI flutuante + LuaJ)  ──3 FIFOs / stdio, protocolo binário──►  daemon nativo (root, via su)
        │                                                                 │
        │                                                                 ├─ lê/escreve: process_vm_*, /proc/pid/mem, ptrace
        └─ scripts gg.* (bloqueiam na UI thread)                          ├─ busca em templates por tipo, resultados em .bin
                                                                          └─ injeta lib (ptrace+shellcode+dlopen) p/ speedhack
```

## O que mais aprendemos

1. A interface Java é casca; o daemon nativo faz tudo. Uma sonda por ABI devolve a arquitetura pelo código de saída (101–105).
2. Leitura e escrita têm três caminhos, e toda escrita é conferida por releitura.
3. O speedhack é uma lib injetada que intercepta `clock_gettime`/`gettimeofday`/`elapsedRealtime`.
4. `targetSdk 24` existe só para poder executar binários a partir de `/data`.
5. O app manda telemetria por HTTP sem TLS e tem sabotagem silenciosa contra APKs modificados (máscara XOR atrasada ~18 h).
6. A API Lua tem constantes estranhas (`PROT_READ=2`), que precisam ser copiadas para manter compatibilidade.

## Plano para o nosso app (resumo das "Lições")

- Daemon único por ABI, empacotado como `lib/<abi>/lib*.so` (funciona com targetSdk atual).
- `process_vm_readv/writev` por padrão (não para o jogo), busca com SIMD, page size 4K/16K, limpar tag MTE.
- IPC por socket Unix SEQPACKET com mensagens versionadas.
- Lua com API `gg.*` compatível, corrotinas, interrupção confiável e sandbox por allow-list.
- Sem telemetria, sem ofuscação hostil e com permissões mínimas (overlay, notificação, serviço em primeiro plano).
- Diferenciais: pointer scanner multinível, dissecador de struct, AOB scan, diff de snapshots, IL2CPP.
- Fora de escopo: burlar anti-cheat de jogos online.
