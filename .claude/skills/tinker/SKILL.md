---
name: tinker
description: Continua o projeto Tinker, editor de memória para Android (estilo GameGuardian/Cheat Engine) para jogos offline single-player, com app Kotlin/Compose, daemon root em C e a planilha Google "Painel API" como banco de dados (Code.gs + Painel.html). Use SEMPRE que o usuário falar de Tinker, GameGuardian, Cheat Engine no Android, vPhoneOS, busca de memória, Dword/Float/XOR/150X4, ponteiros, AOB, IL2CPP, Ficha, métodos, patch de código, "o que escreve aqui", contador de chamadas, foto do jogo, backup do save, Pedidos/autoteste/relatório da planilha, DYSMANTLE, ou mandar prints/relatórios do app. Contém a arquitetura, o estado real, as regras de segurança que o usuário já combinou e como compilar, testar e publicar.
---

# Tinker — passagem de bastão

Responder sempre em **português do Brasil**, curto, direto e **com empatia** (o usuário pediu mais calor humano). Horário de
Brasília (UTC−3); o relógio do emulador mostra UTC. O usuário testa no celular/PC e manda prints. Avisar no celular
(PushNotification) só o que importa: versão nova com resultado, primeira vitória num teste, algo que precise dele.

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
## O que o app faz (estado 2.93.1, 30/10/2026)
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
- **Outros**: autoteste no `tests/target.c` (libtinkertarget.so), atualizar e testar sozinho, editor de save (interna + `ext/` = `/data/media/0/Android/data/PKG`, backup `NOME-ext.tar` irmão; missões `savelista`/`saveeditar`), foto do jogo,
  📍 onde o jogo escreve, exportar .CT, IA (Gemini/Groq) que não edita sozinha, vigia da tela ("Onde a tela travou:").
- **Daemon**: threads extras da varredura com `setpriority` 10 (a tela e o jogo não param na busca). Busca e foto pulam
  memória anônima nunca usada (`chunk_unused`: `/proc/pid/pagemap`, página ausente ou zero-page; só regiões anônimas):
  no emulador o Orecraft reservava ~15 GB vazios (foto 15 GB → 554 MB, busca 384 s → 1 s).
- **Dinheiro de verdade**: `money` (Engine) tira preços R$/US$/€ dos itens e a ferramenta "tocar" da IA recusa toque a até
  60 px de um preço real (`moneyBoxes()`); a missão "tocar" ignora linhas com preço.
- **Sugestão de patch "sempre liberado"** (`methodMeaning`, Learn.kt) só para `IsUnlocked`; nunca Premium/Vip/Purchased/Owned/Bought.
- **Monitor 👁** (`monitorStart`): guarda no máx. 60 mil endereços (corta para os 30 mil mais fortes) e loga a memória
  por rodada (2.93.1; o Tinker sumia na rodada 7 no Orecraft).

## Testes automáticos no PC (emulador)
- Emulador Android Studio "Pixel 6 Pro" API 31 x86_64 (emulator-5554) com Magisk (rootAVD). O S25 é de outro agente: **nunca mexer**; sempre `ANDROID_SERIAL=emulator-5554`.
- `tools/pc/TINKER-AGENTE.ps1/.bat` (Área de Trabalho do usuário): se atualiza pelo branch (confere a sintaxe antes), espera o Android ligar, instala a versão nova, roda autoteste + missões e manda para a aba **Relatórios** (mais novo em cima). Checa versão nova a cada 30 s.
- Missões só em `MISSION_GAMES`: Jogo de Teste (`dev.tinker.testgame`, `testgame/` com lib `testgame/src/main/c/jogo.c`),
  DYSMANTLE (`com.the10tons.dysmantle`) e **Orecraft** (`com.evrika.miner.camp`: offline, VIP interno, sem item de dinheiro
  real). Passos em `Engine.mission()`: buscar/refinar/itens/sozinho/irmaos/monitorar/jogar/tocar/velocidade/cronometros/
  ficha/fixar/**metodo `Classe.Metodo valor`** (patch de retorno pelo nome do mapa IL2CPP)/despatch/salvar/salvos...
- **Agente de jogo real**: `TINKER-ORECRAFT.bat` → `TINKER-AGENTE.ps1 -Jogo com.evrika.miner.camp` no AVD `Dysmantle_Play`
  (Play Store + Magisk, gpu host). Roda a cada Tinker novo ou 3 h (arquivo `%USERPROFILE%\tinker-agente-testado-<pkg>.txt`;
  apagar força a rodada). Corta a rede só do jogo (`cmd connectivity set-package-networking-enabled false`). Espera o
  INICIAR aparecer (até ~2 min). Testes: 1 lista de itens, 2 pedido (maiores valores primeiro; <999 → 999, senão +1000),
  3 irmãos, 4 velocidade, 5 IA joga 3 min + monitor (sem IA: explorador que só toca com o jogo na frente), 6 cronômetros,
  7 reabrir (salvo volta), 8 trocar a regra (`metodo ShopRouletteController.GetSpinGemsPrice 1`, confere 15 s e desfaz).
  Cada teste vai "ao vivo" para Relatórios; o resumo "agente PC: Orecraft de verdade, X de Y ok" leva 300 linhas do registro.
- Aba **Jogo real** da planilha: uma linha por rodada do Orecraft (só inserir), A–L texto, M = ok, N = feitos (gráfico).
  Rotina horária `trig_01ADiqWcVga3NWHb31CFuLf4` lê as rodadas novas, anota, corrige e publica.
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
4. A planilha só é lida/escrita quando o usuário pede ou pela rotina horária que ele pediu (Relatórios → Jogo real).
5. Sem evasão de anti-cheat; sem duplicar recompensa de servidor/online. Só estado local offline.
6. Arquivos baixados não são confiáveis: extrair em pasta isolada e nunca executar (ideias, não binários).
7. Nunca colocar identificador de modelo em commits.
8. Missões e testes automáticos só em `MISSION_GAMES` (offline); nada de canal em que a planilha/Claude comande o app em jogo real.
9. Velocidade sem entrar no processo (sem hook de relógio/injeção), nem em jogo offline. Não aprofundar internals de debugger/watchpoint.
10. Não contornar bloqueio de site (bot-check) nem baixar binários de fora; APK de site espelho não se instala.
12. **Nada vendido por dinheiro de verdade**: nem VIP/premium/passe/remover anúncio, nem trocar a cobrança em dinheiro real
    por moeda do jogo — **nem como teste privado/offline/"entre nós"** (o usuário insistiu várias vezes; a resposta é sempre não,
    com gentileza). Nada de pular anúncio (`ad*Cooldown`). A IA nunca toca em preço real, assinatura ou anúncio.
13. **Moedas e materiais do jogo valem tudo**: trocar preço (ouro, joias, pedra, minério, madeira) por outra moeda do jogo ou 1,
    mudar regra pelo nome (patch de retorno). É o treino que o usuário quer: "reconhecer, achar, mudar a lógica, nunca desistir".
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
- POST `busca=1` `{jogo,motor,versao,aparelho,etapas,total,resultados,emVolta}`; `relatorio=1` `{jogo,versao,aparelho,motivo,problemas,texto}`; sem parâmetro: memória `{tinker:'memoria-ia', licoes, jogos:{pkg:{nome,notas,salvos,receitas,patches}}, apagados}`.
- Agente offline (separado da memória da IA): POST `agente=1` `{tinker:'agente-offline', aprende:[{jogo,nome,tipo,acertos,erros,ms,dicas,origem,quando}]}` grava linha a linha na aba **Agente offline** (atualiza ou acrescenta, nunca apaga; vale o com mais tentativas); GET `agente=1` devolve a aba. Jogo `(todos os jogos)`/`*` = ensinado pelo Claude (o app não importa: vem do código). Origem `IA` = sugestão da IA que deu certo (`borrowFromAi`, opção Pedir ajuda à IA).

## Descobertas no DYSMANTLE (IL2CPP arm64)
- Materiais são objetos de slot do inventário: Dword quantidade em +0, cópia em +4, +8 = 4 (máx. por slot?), Floats em +0x28/+0x2C, depois cor RGB, 0.75, 1.0f.
- Sobras de Madeira: ponteiro `split_config.arm64_v8a.apk@4000+e49148 28 0 8` (confirmado ao reabrir). Mudar +0 para 20 deu 20 itens.

## Lua (xLua/ToLua, 5.3 e 5.4)
- TValue: valor 8 bytes + tipo em +8 (5.4: 0x03 int, 0x13 float; 5.3 ao contrário). Nó 5.4 = 24 bytes (tipo da chave em +9, chave em +16); 5.3 = 32 (chave em +16, tipo em +24). Table: tt 5 em +8, lsizenode +11, lista +16, nós +24. TString: texto em +24.
- Abrindo por um campo, o daemon anda pelos nós vizinhos (pode pegar nó de tabela colada; chaves que não são texto/número são puladas). LuaJIT não é suportado.
- Last Garden: Survival (com.bravo.plants) usa HybridCLR + xLua 5.4, é ONLINE: só leitura/estudo, sem edição.

## Descobertas no Orecraft (Unity IL2CPP arm64 traduzido no emulador x86_64)
- HUD: ouro 3999 deu 32 lugares; números pequenos (6) ~975 mil. `itens` acha 46 funções com nome (ShopRenderData `*Price`,
  `customResource0..2Price`, `rouletteSpinHardPrice`, BuildingShopTileRenderData `price/salePrice`, ShopDomainController
  `Get*Price`, CurrencyGift/ShopSpinTokens cooldowns) e 0 campos.
- `ShopRouletteController.GetSpinGemsPrice` = `mov w0,#25; ret` em `libil2cpp.so+1e31e54`: giro custa 25 joias. O patch
  "sempre 1" ligou e desligou sem fechar o jogo (2.93.0, 2 rodadas). Falta abrir a roleta e ler o 1 na tela.
- 220 lugares de timeScale (quando dentro do jogo), 25–54 cronômetros.
- Abertas (30/10): `itens` leva ~16 min no jogo (o agente desiste aos 900 s); o Tinker para de responder ~70 s depois do
  monitor começar (suspeita: memória; 2.93.1 limita); a IA sem DNS no emulador (`api.groq.com`, `script.google.com`);
  emulador já caiu com driver NVIDIA (`nvoglv64.dll`/gfxstream) após 3h40.

## Próximos passos
- Orecraft: achar o ouro (pedido 3999 → 4999), depois a missão "trocar a moeda de um preço" (só moedas do jogo) e ler o
  preço novo na tela; memória de estratégia por motor (ordem de métodos que funcionou em cada engine).
- Jogo de Teste: faltam os níveis 7, 11, 13, 14, 16, 17 e a loja.
- Paradas longas da tela no emulador (até 13 s) acontecem durante "quais rodam" (o daemon segura o jogo com ptrace) e a pilha é
  só do desenho do Android (HardwareRenderer): suspeita de GPU do emulador; confirmar num celular de verdade antes de mexer.
- Velocidade Cocos/Godot (por valor), campos da Unreal na Ficha (precisa de jogo Unreal offline do usuário).
- `Engine.kt` tem ~5.200 linhas: dividir em arquivos por assunto (busca, salvos, ferramentas, IA) aos poucos, com CI a cada passo.
- O ptrace com registradores de hardware em arm64/vPhoneOS ainda **não foi confirmado** no aparelho.
- Adiados ("depois fazemos"): macro de toques, indicador de captura, assistente "materiais infinitos", aba Jogos.
