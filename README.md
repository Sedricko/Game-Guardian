# Tinker

Editor de memória para Android, aberto, para jogos **single-player/offline**.
Inspirado no GameGuardian, com interface mais simples, menu flutuante e diagnóstico completo.

> Use só em jogos offline. Em jogos online, editar memória viola as regras do jogo e afeta outros jogadores.

## Baixar

Página de releases do repositório → **Tinker-X.Y.apk** da versão mais recente.
Cada versão instala por cima da anterior, sem perder os salvos.

## Requisitos

- Android 8 ou mais novo, processador **arm64**.
- **Root**: no aparelho (Magisk/KernelSU) ou dentro de uma máquina virtual com root, como o vPhoneOS.
  Sem root o Android não deixa um app ler a memória de outro.

## Instalação (vPhoneOS)

1. Ligue o root nas configurações da máquina virtual.
2. Instale o APK **dentro** da máquina virtual e instale o jogo lá também.
3. Abra o Tinker e toque em **Escolher jogo**; autorize o root quando pedir.
4. Na primeira vez, permita "Sobrepor a outros apps". Daí em diante, abrir o Tinker já abre o menu flutuante
   (a bolinha sobre o jogo), como no GameGuardian. Dá para desligar em **Ajustes**; os ajustes ficam guardados.

## Abas

| Aba | Para que serve |
|---|---|
| **Busca** | Achar um valor: tipo (Auto, Dword, Float…), **Nova busca**, **Refinar**, **Desfazer**, histórico das últimas buscas. Valor desconhecido: **Começar sem valor** e depois **Aumentou / Diminuiu / Mudou / Não mudou**. Marque resultados para salvar, congelar, editar ou **Buscar perto**. Os valores atualizam ao vivo. |
| **Salvos** | Endereços guardados, por jogo: nome, valor ao vivo, congelar, editar vários, ler como outro tipo, **ponteiros**, exportar/importar (.json). |
| **Memória** | **Endereços**: valores em volta de um endereço, em qualquer tipo (o que muda fica destacado), **Buscar perto**, **Ir para endereço**. **Ficha do objeto**: os campos com nome (IL2CPP) ou tipo adivinhado. |
| **Registro** | Tudo o que o Tinker fez, falhas do Android e logcat do jogo. **Compartilhar relatório** gera um .txt completo. |
| **Ajustes** | Por assunto: menu flutuante, busca, ponteiros e restauração, jogo, regiões de memória (Jh, Ch, Ca, Cd, Cb, A…) e, no fim, a Ajuda. |

O cabeçalho é uma linha só: toque no nome do jogo para trocar; **👁** (segure) espia o jogo através do painel,
**⏸/▶** pausa e continua, **⚡** abre a barra rápida, **—** minimiza e **✕** sai.
O **teclado do Tinker** (números, `~` e `;`) abre dentro do painel e da barra rápida, sem o teclado do telefone por cima
do jogo; dá para voltar ao do telefone em Ajustes. Opções menos usadas ficam recolhidas (**Mais formas de buscar**, **Buscar perto**), para a lista ter espaço.
**Barra rápida**: uma barra pequena sobre o jogo com valor, **Refinar** e **↑ ↓ = ≠**, para refinar sem abrir o painel; **▢** abre o painel completo.

## Passo a passo: achar um valor

1. Abra o jogo e depois o menu flutuante.
2. Digite o número que aparece na tela (ex.: moedas) e toque em **Nova busca**.
3. Faça o número mudar no jogo, digite o novo e toque em **Refinar**. Repita até sobrar 1 resultado.
4. Toque no resultado para editar, ou marque e toque em **Congelar**.

Em jogos Unity IL2CPP, com até 20 resultados cada um mostra o campo do jogo (`PlayerData.UpgradeStone`);
**cópia temporária (Int32)** é o número que o jogo cria só para a tela — salve o campo de verdade.

Dicas:
- Número arredondado ou abreviado (1.2K)? Busque uma faixa: `1150~1250`.
- Refinou errado e sobrou 0? **Desfazer**.
- Achou o ouro e quer a prata? Marque o ouro → **Buscar perto**.
- O jogo fechou depois de editar? O endereço não era o certo; refine mais antes de editar.

## Valores cifrados (🔒)

Muitos jogos Unity guardam moedas e vida trancadas com uma chave ao lado (Anti-Cheat Toolkit).
A busca já procura esses valores sozinha e mostra o número real com **🔒**; editar ou congelar tranca o valor novo com a mesma chave.
Os 🔒 aparecem primeiro; se o mesmo número aparecer normal, ele vem marcado como **provável cópia**: edite o 🔒. Dá para desligar em **Ajustes → Procurar valores cifrados**.

## Valor sem número (barra de vida)

1. Escolha o tipo (barra costuma ser **Float**) e toque em **Começar sem valor**.
2. Perca vida e toque em **Diminuiu**; sem mudar nada, toque em **Não mudou**. Repita até sobrar pouco.
3. Sabe quanto mudou? Digite o número e toque em **Diminuiu 10** / **Aumentou 10**.

## Ponteiros, offsets e assinatura (automático)

Quando o jogo reinicia, os endereços mudam. O Tinker cuida disso sozinho:

1. **Ao salvar** um valor, ele já busca os ponteiros (como o Pointer scan do Cheat Engine, com base só nas libs do jogo),
   **escolhe e guarda o melhor** (os outros ficam de reserva) e aprende a **assinatura**: os bytes em volta do valor que não mudam.
   Enquanto você joga, os caminhos que param de chegar no valor saem sozinhos.
2. Salvos perto um do outro (até 4 KB, a mesma "ficha": ouro, gema, energia) ficam ligados pelo **offset** (**↳ Ouro +0x8**).
3. **Ao reabrir o jogo**, o Tinker escolhe ele sozinho (se estava aberto, ou ao abrir o Tinker) e cada salvo volta sozinho:
   ponteiro guardado (confirmado com o último valor e daí em diante fixo) → caminhos de reserva
   (testados com o último valor) → assinatura. Os da mesma ficha vêm junto. O resultado aparece no topo da aba Salvos.
   Enquanto o jogo ainda carrega o save, o Tinker tenta de novo sozinho por 2 minutos; o botão **↻** nos Salvos procura na hora.
   Se o valor mudou com o jogo fechado (ou enquanto você joga), vale o consenso dos caminhos de ponteiro e o **histórico**:
   cada salvo guarda as últimas mudanças e a faixa que o valor já teve; caminho que cai fora da faixa é descartado.
   Em jogos Unity IL2CPP, o salvo guarda também a classe e o offset (`PlayerData+30`): se os caminhos quebram,
   o Tinker procura os objetos dessa classe pelo nome e vai ao mesmo campo.
4. Sobrou dúvida entre vários caminhos? Abra o salvo, digite o valor que o jogo mostra e toque em **Filtrar pelo valor**.

Salvo com **⚠ de outra vez do jogo** não é lido nem escrito até voltar (escrever no endereço velho pode derrubar o jogo);
o que estava congelado volta congelado.
Marque dois salvos para ver a distância entre eles; na aba Memória dá para ir a `7a0010+8`.
Em **Ajustes**: "Preparar ponteiros ao salvar" e "Ponteiros só nas libs do jogo". Jogos 32 bits são reconhecidos sozinhos.

## Ficha do objeto (como o "Dissect data structure" do Cheat Engine)

Num salvo, **Explorar ficha** (ou aba **Memória → Ficha do objeto**) mostra os campos em volta do valor, com tipo e valor ao vivo;
o que muda fica destacado por 3 s. Em jogos **Unity IL2CPP** os campos vêm com os nomes do próprio jogo (`Player.hp`, `Weapon.damage`),
lidos da memória do jogo; nos outros, o tipo é adivinhado (inteiro, decimal, ponteiro, cifrado 🔒). Toque num campo para salvar
(já entra ligado pelo offset) ou editar; num ponteiro, para abrir o objeto para onde ele aponta. Salvos dentro de um objeto IL2CPP
ganham o nome do campo sozinhos. Na lista de endereços da aba Memória, o que muda também fica destacado.

## Assistente de IA (Claude ou Gemini)

Em **Ajustes → Assistente (IA)**, escolha **Claude** (chave em console.anthropic.com, paga por uso) ou **Gemini** (chave grátis
em aistudio.google.com, com limite por dia; ao colar a chave, o Tinker pergunta ao Google quais modelos ela pode usar, escolhe o melhor e mostra os outros para trocar). Os botões **✨**
mandam o que está na tela e mostram a resposta: na Busca, *qual desses é o valor certo?* e *qual o próximo passo?*; na lista de classes, *onde fica o ouro?* (as classes citadas viram atalho para
abrir a ficha); na ficha, *o que cada campo faz?*; no Registro, *o que deu errado?*. Nada é enviado sem tocar em **Perguntar**.

## Motor do jogo

Ao escolher o jogo, o Tinker reconhece o motor pelas bibliotecas do APK (Unity IL2CPP, Unity Mono, Unreal, Cocos, Godot,
GameMaker, Flutter, Java…) e se ele roda em 32 ou 64 bits; aparece no topo (`PID 1234 · Unity IL2CPP · 64 bits`) e no relatório.
Classes e fichas por nome funcionam no IL2CPP de 32 e 64 bits; nos outros motores a ficha mostra os campos em volta.

## Abrir objeto pelo nome e receitas

**Memória → Ficha do objeto**: toque em **Ver todas as classes do jogo** e escolha uma (as do próprio jogo vêm primeiro;
digite parte do nome para filtrar), ou digite a classe (`PlayerData`) e toque em **Abrir** — aparecem todos os campos com o valor
de agora (`Gem = 1000`, `UpgradeStone = …`); toque num campo para **Editar**, **Salvar** ou **Congelar**. **Filtrar campo**
acha rápido (`gem`). Campos que são listas (`OwnedWeapons`) abrem a lista, com a ficha de cada item.

**Receitas** (qualquer jogo): nos Salvos, marque os campos e toque em **Criar receita**. A receita guarda cada campo pelo nome
da classe (Unity IL2CPP), senão pelo ponteiro 🎯, senão pela assinatura de bytes que o Tinker aprende sozinho — nunca pelo
endereço: **Aplicar** escreve tudo de uma vez e congela os ❄, em qualquer vez que o jogo abrir;
**ao abrir** aplica sozinha quando o jogo carrega; **Copiar** manda como texto, e **Colar lista** recebe.

## Busca por bytes (AOB)

Aba **Busca → Busca por bytes**: pares hex com `??` como curinga, por exemplo `1F 20 03 D5 ?? ?? 00 B9`.
**Dados** procura na memória do jogo; **Código** procura nas instruções. Os lugares achados viram resultados normais.

## O jogo fechou

Quando o jogo fecha sozinho, o Tinker percebe em alguns segundos, abre o menu flutuante e pergunta **"Reabrir o jogo?"**.
Reabrindo, ele se liga ao jogo e os salvos voltam sozinhos. Fechar pelo botão do Tinker não pergunta.

## Versão nova

Ao abrir, o Tinker olha a página de releases e mostra **"Tinker X disponível → Baixar"**. Sem internet, não aparece nada e tudo funciona igual.

## Reportar um problema

Aba **Registro** → **Compartilhar relatório** e envie o arquivo `.txt`.
Ele tem aparelho, versão, jogo, cada salvo (onde está, ponteiro, reservas, assinatura), cada comando com o tempo que levou,
resumo de problemas, falhas do Android e o logcat do jogo.

## Para desenvolvedores

- `daemon/tinkerd.c`: daemon root (C, sem dependências). Lê e escreve memória com `process_vm_*` (cai para `/proc/pid/mem`). Protocolo de texto por stdin/stdout.
  A busca usa todos os núcleos; a busca desconhecida guarda a memória em `/data/local/tmp/tinker-snap` (apagado ao sair).
- `app/`: app Kotlin + Jetpack Compose (Material 3). Só duas dependências diretas: Compose BOM e activity-compose.
- `tests/daemon_test.sh`: testa o daemon no Linux contra um processo de verdade. `app/src/test`: testes JVM.
- O GitHub Actions compila o daemon com o NDK, roda os testes, gera os APKs e publica a release.
- Análise do GameGuardian 101.4 usada como base: [docs/analise](docs/analise/README.md).
