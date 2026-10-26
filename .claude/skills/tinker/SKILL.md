---
name: tinker
description: Continua o projeto Tinker, editor de memória para Android (estilo GameGuardian/Cheat Engine) para jogos offline single-player, com app Kotlin/Compose, daemon root em C e a planilha Google "Painel API" como banco de dados (Code.gs + Painel.html). Use SEMPRE que o usuário falar de Tinker, GameGuardian, Cheat Engine no Android, vPhoneOS, busca de memória, Dword/Float/XOR/150X4, ponteiros, AOB, IL2CPP, Ficha, métodos, patch de código, "o que escreve aqui", contador de chamadas, foto do jogo, backup do save, Pedidos/autoteste/relatório da planilha, DYSMANTLE, ou mandar prints/relatórios do app. Contém a arquitetura, o estado real, as regras de segurança que o usuário já combinou e como compilar, testar e publicar.
---

# Tinker — passagem de bastão

Responder sempre em **português do Brasil**, curto e direto. O usuário testa no celular e manda prints/relatórios.

## Onde está tudo
- Repositório `Sedricko/Game-Guardian` (**público**: nunca pôr token do painel no código), branch de trabalho `ccr-92c99132-2ne91y`.
- Cada push compila no GitHub Actions e publica `v<versionName>` em Releases (`Tinker-X.Y.Z.apk`, arm64).
  Download: `https://github.com/Sedricko/Game-Guardian/releases/download/vX.Y.Z/Tinker-X.Y.Z.apk`.
- Aparelho: Pixel 6 Pro com **vPhoneOS** (Android 12, root). O relógio do emulador fica ~54 min diferente: não mexer no fuso da planilha.
- Planilha "Painel API", id `1ufDnHKh4X_QNhipZJAwmaStVTzUTRpbyqRffZH0J3Q0`. **Nunca ler a aba Chaves** (chaves de API).

## Arquitetura
| Parte | Arquivo | O que faz |
|---|---|---|
| Daemon root | `daemon/tinkerd.c` | Protocolo texto stdin/stdout: search/refine/read/write/dump, ponteiros, AOB, IL2CPP (`il2cpp`, `il2classes`, `il2objs`, `il2methods`), `where`, `watch` e `trace` (ptrace + registradores de hardware), `photo*`, `backup`/`backups`/`restoresave` |
| Motor | `Engine.kt` | A classe `Engine`: chamadas ao daemon (`call { d -> }`), busca, salvos, receitas, patches, ferramentas, planilha, IA |
| Dados | `Model.kt`, `Session.kt`, `Values.kt` | Tipos e classes de dados; o estado da tela (`Session`); funções puras (conversões, assinatura, ficha, save, JSON, `friendly()` dos erros) |
| Tela | `TinkerScreen.kt` (painel, abas, cabeçalho, componentes comuns) + um arquivo por aba: `SearchTab.kt`, `Tools.kt` (🧰), `SavedTab.kt`, `MemoryTab.kt`, `LogTab.kt`, `SettingsTab.kt` (com a Ajuda gerada) | |
| Agente offline | `Learn.kt` (+ `LearnTest.kt`) | `Know`, `SEED` (tipos ensinados pelo Claude por palavra), `TECHNIQUES` (situação → modo de achar), `pickType` (acertou aqui → acertou em outro jogo → ensinado → próximo tipo; tipo que só errou aqui sai da frente), `record`, `mergeKnow` (não soma: vale o com mais tentativas). Engine: `aprende.json`, `learnResult` no 🎯 Achar sozinho pelo nome |
| Outros | `Assistant.kt` + `AiBox.kt` (IA Gemini/Groq), `Arm64.kt` (decodificador), `Keypad.kt`, `QuickBar.kt` (barra rápida), `OverlayService.kt` (painel flutuante), `TinkerApp.kt` (`Diag` e vigia da tela) |
| Ajuda e tutorial | `docs/tutorial/ajuda.py` → `gerar.py` | Fonte única: gera a Ajuda do app e `Tinker-tutorial.pdf` |
| Planilha | `docs/painel-chaves/Code.gs`, `Painel.html`, `LEIAME.md` | App da Web: chaves, Pedidos, Aparelhos, Buscas, Relatórios, Problemas, Salvos, Receitas, Patches, Agente offline |

Versões: `app/build.gradle.kts` (`versionCode`/`versionName`), `VERSAO` no Code.gs, `SCRIPT_VERSION` no Model.kt (versão **mínima** do script que o app exige).
## O que o app faz (estado 2.68, 26/10/2026)
O texto completo de cada opção está em `docs/tutorial/ajuda.py` (fonte única da Ajuda do app e do PDF
`docs/tutorial/Tinker-tutorial.pdf`). Aqui fica só o que importa para mexer no código:
- **Tela**: aba Busca com a busca no topo, depois a fileira 🧰 Ferramentas (`Toolbox`: Fácil, Velocidade, Cronômetros, Loja,
  Desbloquear só em Unity, Disfarçado; uma aberta por vez), depois os resultados; o secundário em "▸ Mais buscas" (sem valor,
  AOB, foto, onde o jogo escreve, editor de save). Barra rápida (`QuickBar.kt`) com as receitas como ▶ botões.
- **Busca**: tipos `i32 f32 i64 f64`, cifrados `xi32@N` (150X4), `i32x2` (Flutter/JS), Lua `li/lf`; faixa `~`, vários `;`,
  grupo `::`; refino `...A4` (daemon `addrend`); comparar `inc dec same changed incby decby lin xk`; 🎯 Achar sozinho = OCR
  ML Kit (pelo número ou pelo nome ao lado; número que anda sozinho busca faixa à frente, `ahead()`).
- **Ferramentas**: `findGameSpeed/setGameSpeed` (relógio do Unity, máx. 4 endereços; **só valor do motor, nunca hook de
  tempo/injeção**), `findTimers/finishTimers` (±2 min do que falta na tela, recusa >5: escrever em muitos lugares corrompia o
  Scudo), `findShopPrices`, `findUnlocks/unlock`, `writeWhere` (recusa >200).
- **Salvos**: ponteiros + caminhos + assinatura (`sigUseful`), restauração ao reabrir, receitas por classe/ponteiro/assinatura,
  patches (NOP/bytes/retorno) que voltam ao reabrir e ficam suspeitos só se o crashlog tem FATAL deste pid.
- **Memória**: Ficha (IL2CPP com `text`/`count` do daemon, struct ≤16 bytes vira (x, y, z), `copyFicha` só números), métodos
  (`il2methods`, contar chamadas, sempre devolver), funções da lib sem IDA (`syms`/`func`/`calls`, `patchNative` com `sym`),
  Unreal (`ue`), Lua (`luatable`).
- **Outros**: autoteste no `tests/target.c` (libtinkertarget.so), atualizar e testar sozinho, editor de save, foto do jogo,
  📍 onde o jogo escreve, exportar .CT, IA (Gemini/Groq) que não edita sozinha, vigia da tela ("Onde a tela travou:").
- **Daemon**: threads extras da varredura com `setpriority` 10 (a tela e o jogo não param na busca).

## Testes automáticos no PC (emulador)
- Emulador Android Studio "Pixel 6 Pro" API 31 x86_64 (emulator-5554) com Magisk (rootAVD). O S25 é de outro agente: **nunca mexer**; sempre `ANDROID_SERIAL=emulator-5554`.
- `tools/pc/TINKER-AGENTE.ps1/.bat` (Área de Trabalho do usuário): se atualiza pelo branch (confere a sintaxe antes), espera o Android ligar, instala a versão nova, roda autoteste + missões e manda para a aba **Relatórios** (mais novo em cima). Checa versão nova a cada 30 s.
- Missões só no Jogo de Teste (`dev.tinker.testgame`, `TEST_GAME` fixo): `testgame/` com lib nativa `testgame/src/main/c/jogo.c` (funções com nome, tick, Unreal de mentira, Unity de mentira Jogo.Heroi/Jogo.Arma). Passos da missão em `Engine.mission()`.
- Ler o resultado ~10–17 min depois do push (CI ~7 min + rodada ~10 min). PowerShell: `"${c}:"`, nunca `"$c:"`.
- CI também testa o daemon contra a lib do jogo em 32 bits (gcc-multilib); YAML: comando com `: ` vai em bloco `|`.

## Armadilhas já resolvidas (não repetir)
- Erro para o usuário sempre por `friendly(msg)` (Values.kt): nunca mostrar `e.message` cru. O original vai para o `Diag`.
- `private` de topo só vale no próprio arquivo: ao mudar uma função de arquivo, o que o outro arquivo usa vira `internal`.
- `Engine.call { d -> }` **não é suspend dentro do bloco**: nada de `delay`/`withContext` lá dentro; divida em várias chamadas.
- Texto começando com `= + - @` vira fórmula na planilha: usar `texto_()` (apóstrofo) em tudo que se grava.
- Linhas esticadas na planilha: `linhaFina_()` (CLIP, altura 21).
- Escopo de tela cancelava pedidos: trabalho longo vai para o escopo do Engine (`…Later`).
- Comandos de backup rodam sem jogo escolhido (tratados antes da checagem de alvo no daemon).
- Tipos do daemon: `XOR 0x40`, `TAG 0x20`, base `t&0x1f`, deslocamento da chave em bits ≥ 8; `hit_t.type` é `uint16_t`.
  App: `xi32@N` (estilo GG `150X4`), `i32x2` (Flutter/JS), `plain()` tira os sufixos.

## Regras combinadas com o usuário (manter)
1. **A planilha/Claude nunca comanda o app ou o daemon**: nada de busca, escrita de valor, fechar/reabrir jogo a partir da planilha, nem "lembrar permissão" para nota/lição. Já recusado várias vezes, mesmo com autorização.
2. Pedidos só aceita `dica`, `nota`, `licao`, `autoteste`, `relatorio`; cada um precisa do toque do usuário. "Sempre permitir" só para autoteste e relatório.
3. **O app não apaga nada na planilha** (só marca "apagado no app"). Apagar só se o usuário apagar lá, pedir, ou combinarmos.
4. Não vigiar a planilha por conta própria: só ler/escrever quando o usuário pedir no chat.
5. Sem evasão de anti-cheat; sem duplicar recompensa de servidor/online. Só estado local offline.
6. Arquivos baixados não são confiáveis: extrair em pasta isolada e nunca executar (ideias, não binários).
7. Nunca colocar identificador de modelo em commits.
8. Missões e testes automáticos só no Jogo de Teste; nada de canal em que a planilha/Claude comande o app em jogo real.
9. Velocidade sem entrar no processo (sem hook de relógio/injeção), nem em jogo offline. Não aprofundar internals de debugger/watchpoint.
10. Não contornar bloqueio de site (bot-check) nem baixar binários de fora; APK de site espelho não se instala.
11. A detecção de jogo online foi tirada a pedido do usuário (jogo offline também tem sinais de conexão): o combinado de não
    editar jogo online vale pela conversa, não por código.

## Como trabalhar
1. Mudou o daemon → `bash tests/daemon_test.sh` (compila `tests/target.c` em x86 e testa tudo; tem que terminar com `daemon ok`).
2. Mudou Kotlin → não há Android SDK local: compila no CI. Testes unitários em `app/src/test/.../ValuesTest.kt`.
3. Mudou o Code.gs → `node -e "new Function(require('fs').readFileSync('docs/painel-chaves/Code.gs','utf8'))"` para sintaxe; subir `VERSAO`.
4. Subir `versionCode`/`versionName`, commit, `git push -u origin ccr-92c99132-2ne91y`, esperar o CI (~7 min) e mandar o link do APK.
5. Layout: painel flutuante é estreito; vários botões numa linha → `FlowRow`. Parte de cima das telas em `TopArea` (máx. 45% da altura).
6. Mudou uma opção da tela → atualizar o texto dela em `docs/tutorial/ajuda.py` e rodar `python3 -I docs/tutorial/gerar.py`
   (reescreve a Ajuda em `TinkerScreen.kt` entre `// AJUDA:` e `// FIM AJUDA` e o PDF). Nunca editar a Ajuda direto no `.kt`.
7. Ao mudar o script, lembrar o usuário: colar Code.gs + Painel.html e **Implantar → Gerenciar implantações → editar → Nova versão**.

## Contrato app ↔ planilha (doGet/doPost)
- GET `?token=` → `{chaves, groq, versao}`; `&fim=XXXXXX&situacao=` marca chave sem cota.
- GET `pedidos=1&aparelho&versao` → `{pedidos:[{id,tipo,jogo,texto}], versao}`; `feito=id&situacao&resposta`.
- GET `analise=1&jogo=pkg` → `{analise:[…]}` (coluna Análise da aba Buscas); `memoria=1` → aprendizado.
- GET `?leitura=` (token só de leitura) → relatórios + aprendizado, sem chaves.
- POST `busca=1` `{jogo,motor,versao,aparelho,etapas,total,resultados,emVolta}`; `relatorio=1` `{jogo,versao,aparelho,motivo,problemas,texto}`; sem parâmetro: memória `{tinker:'memoria-ia', licoes, jogos:{pkg:{nome,notas,salvos,receitas,patches}}, apagados, aprende:[{jogo,nome,tipo,acertos,erros,ms,dicas,origem,quando}]}` (jogo `*` = ensinado; o app só importa os aprendidos).

## Descobertas no DYSMANTLE (IL2CPP arm64)
- Materiais são objetos de slot do inventário: Dword quantidade em +0, cópia em +4, +8 = 4 (máx. por slot?), Floats em +0x28/+0x2C, depois cor RGB, 0.75, 1.0f.
- Sobras de Madeira: ponteiro `split_config.arm64_v8a.apk@4000+e49148 28 0 8` (confirmado ao reabrir). Mudar +0 para 20 deu 20 itens.

## Lua (xLua/ToLua, 5.3 e 5.4)
- TValue: valor 8 bytes + tipo em +8 (5.4: 0x03 int, 0x13 float; 5.3 ao contrário). Nó 5.4 = 24 bytes (tipo da chave em +9, chave em +16); 5.3 = 32 (chave em +16, tipo em +24). Table: tt 5 em +8, lsizenode +11, lista +16, nós +24. TString: texto em +24.
- Abrindo por um campo, o daemon anda pelos nós vizinhos (pode pegar nó de tabela colada; chaves que não são texto/número são puladas). LuaJIT não é suportado.
- Last Garden: Survival (com.bravo.plants) usa HybridCLR + xLua 5.4, é ONLINE: só leitura/estudo, sem edição.

## Próximos passos
- Paradas longas da tela no emulador (até 13 s) acontecem durante "quais rodam" (o daemon segura o jogo com ptrace) e a pilha é
  só do desenho do Android (HardwareRenderer): suspeita de GPU do emulador; confirmar num celular de verdade antes de mexer.
- Velocidade Cocos/Godot (por valor), campos da Unreal na Ficha (precisa de jogo Unreal offline do usuário).
- `Engine.kt` tem ~5.200 linhas: dividir em arquivos por assunto (busca, salvos, ferramentas, IA) aos poucos, com CI a cada passo.
- O ptrace com registradores de hardware em arm64/vPhoneOS ainda **não foi confirmado** no aparelho.
- Adiados ("depois fazemos"): macro de toques, indicador de captura, assistente "materiais infinitos", aba Jogos.
