---
name: tinker
description: Continua o projeto Tinker, editor de memória para Android (estilo GameGuardian/Cheat Engine) para jogos offline single-player, com app Kotlin/Compose, daemon root em C e a planilha Google "Painel API" como banco de dados (Code.gs + Painel.html). Use SEMPRE que o usuário falar de Tinker, GameGuardian, Cheat Engine no Android, vPhoneOS, busca de memória, Dword/Float/XOR/150X4, ponteiros, AOB, IL2CPP, Ficha, métodos, patch de código, "o que escreve aqui", contador de chamadas, foto do jogo, backup do save, Pedidos/autoteste/relatório da planilha, DYSMANTLE, ou mandar prints/relatórios do app. Contém a arquitetura, o estado real, as regras de segurança que o usuário já combinou e como compilar, testar e publicar.
---

# Tinker — passagem de bastão

Responder sempre em **português do Brasil**, curto e direto. O usuário testa no celular e manda prints/relatórios.

## Onde está tudo
- Repositório `Sedricko/Game-Guardian` (privado), branch de trabalho `ccr-92c99132-2ne91y`.
- Cada push compila no GitHub Actions e publica `v<versionName>` em Releases (`Tinker-X.Y.Z.apk`, arm64).
  Download: `https://github.com/Sedricko/Game-Guardian/releases/download/vX.Y.Z/Tinker-X.Y.Z.apk`.
- Aparelho: Pixel 6 Pro com **vPhoneOS** (Android 12, root). O relógio do emulador fica ~54 min diferente: não mexer no fuso da planilha.
- Planilha "Painel API", id `1ufDnHKh4X_QNhipZJAwmaStVTzUTRpbyqRffZH0J3Q0`. **Nunca ler a aba Chaves** (chaves de API).

## Arquitetura
| Parte | Arquivo | O que faz |
|---|---|---|
| Daemon root | `daemon/tinkerd.c` | Protocolo texto stdin/stdout: search/refine/read/write/dump, ponteiros, AOB, IL2CPP (`il2cpp`, `il2classes`, `il2objs`, `il2methods`), `where`, `watch` e `trace` (ptrace + registradores de hardware), `photo*`, `backup`/`backups`/`restoresave` |
| Motor | `app/.../Engine.kt` | Sessão (`Session`), chamadas ao daemon (`call { d -> }`), salvos, receitas, patches, planilha, IA |
| Tela | `TinkerScreen.kt` | Abas Busca, Salvos, Memória (Endereços / Ficha / Código), Registro, Ajustes + Ajuda |
| Outros | `Assistant.kt` (Gemini/Groq), `Arm64.kt` (decodificador), `Keypad.kt`, `OverlayService.kt` (painel flutuante), `TinkerApp.kt` (`Diag`) |
| Planilha | `docs/painel-chaves/Code.gs`, `Painel.html`, `LEIAME.md` | App da Web: chaves, Pedidos, Aparelhos, Buscas, Relatórios, Problemas, Salvos, Receitas, Patches |

Versões: `app/build.gradle.kts` (`versionCode`/`versionName`), `VERSAO` no Code.gs, `SCRIPT_VERSION` no Engine.kt (versão **mínima** do script que o app exige).
Estado em 2026-10: app **2.36.0** (code 107), script **v15**, app exige v14.

## Armadilhas já resolvidas (não repetir)
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

## Como trabalhar
1. Mudou o daemon → `bash tests/daemon_test.sh` (compila `tests/target.c` em x86 e testa tudo; tem que terminar com `daemon ok`).
2. Mudou Kotlin → não há Android SDK local: compila no CI. Testes unitários em `app/src/test/.../ValuesTest.kt`.
3. Mudou o Code.gs → `node -e "new Function(require('fs').readFileSync('docs/painel-chaves/Code.gs','utf8'))"` para sintaxe; subir `VERSAO`.
4. Subir `versionCode`/`versionName`, commit, `git push -u origin ccr-92c99132-2ne91y`, esperar o CI (~4 min) e mandar o link do APK.
5. Layout: painel flutuante é estreito; vários botões numa linha → `FlowRow`. Parte de cima das telas em `TopArea` (máx. 45% da altura).
6. Ao mudar o script, lembrar o usuário: colar Code.gs + Painel.html e **Implantar → Gerenciar implantações → editar → Nova versão**.

## Contrato app ↔ planilha (doGet/doPost)
- GET `?token=` → `{chaves, groq, versao}`; `&fim=XXXXXX&situacao=` marca chave sem cota.
- GET `pedidos=1&aparelho&versao` → `{pedidos:[{id,tipo,jogo,texto}], versao}`; `feito=id&situacao&resposta`.
- GET `analise=1&jogo=pkg` → `{analise:[…]}` (coluna Análise da aba Buscas); `memoria=1` → aprendizado.
- GET `?leitura=` (token só de leitura) → relatórios + aprendizado, sem chaves.
- POST `busca=1` `{jogo,motor,versao,aparelho,etapas,total,resultados,emVolta}`; `relatorio=1` `{jogo,versao,aparelho,motivo,problemas,texto}`; sem parâmetro: memória `{tinker:'memoria-ia', licoes, jogos:{pkg:{nome,notas,salvos,receitas,patches}}, apagados}`.

## Descobertas no DYSMANTLE (IL2CPP arm64)
- Materiais são objetos de slot do inventário: Dword quantidade em +0, cópia em +4, +8 = 4 (máx. por slot?), Floats em +0x28/+0x2C, depois cor RGB, 0.75, 1.0f.
- Sobras de Madeira: ponteiro `split_config.arm64_v8a.apk@4000+e49148 28 0 8` (confirmado ao reabrir). Mudar +0 para 20 deu 20 itens.

## Próximos passos
- Testar no aparelho: métodos da classe (Ver código / Contar chamadas / Sempre devolver), dump .cs, foto do jogo, backup do save, "O que escreve aqui?", e se algum menu corta em pé/deitado.
- O ptrace com registradores de hardware em arm64/vPhoneOS ainda **não foi confirmado** no aparelho.
- Adiados ("depois fazemos"): macro de toques, indicador de captura (contagem na bolinha, vibração, Parar agora), assistente "materiais infinitos", aba Jogos, atalho na bolinha.
