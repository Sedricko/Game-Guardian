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
4. Para o menu flutuante, toque em **Menu flutuante** e permita "Sobrepor a outros apps".

## Abas

| Aba | Para que serve |
|---|---|
| **Busca** | Achar um valor: tipo (Auto, Dword, Float…), **Nova busca**, **Refinar**, **Desfazer**, histórico das últimas buscas. Valor desconhecido: **Começar sem valor** e depois **Aumentou / Diminuiu / Mudou / Não mudou**. Marque resultados para salvar, congelar, editar ou **Buscar perto**. Os valores atualizam ao vivo. |
| **Salvos** | Endereços guardados, por jogo: nome, valor ao vivo, congelar, editar vários, ler como outro tipo, **ponteiros**, exportar/importar (.json). |
| **Memória** | Valores em volta de um endereço, em qualquer tipo; **Buscar perto** e **Ir para endereço**. |
| **Registro** | Tudo o que o Tinker fez, falhas do Android e logcat do jogo. **Compartilhar relatório** gera um .txt completo. |
| **Ajustes** | Ajuda, pausa automática, tamanho e transparência do painel, fechar jogo, regiões de memória (Jh, Ch, Ca, Cd, Cb, A…). |

No menu flutuante, segure **👁 Espiar** para ver o jogo através do painel.
**Barra rápida**: uma barra pequena sobre o jogo com valor, **Refinar** e **↑ ↓ = ≠**, para refinar sem abrir o painel; **▢** abre o painel completo.

## Passo a passo: achar um valor

1. Abra o jogo e depois o menu flutuante.
2. Digite o número que aparece na tela (ex.: moedas) e toque em **Nova busca**.
3. Faça o número mudar no jogo, digite o novo e toque em **Refinar**. Repita até sobrar 1 resultado.
4. Toque no resultado para editar, ou marque e toque em **Congelar**.

Dicas:
- Número arredondado ou abreviado (1.2K)? Busque uma faixa: `1150~1250`.
- Refinou errado e sobrou 0? **Desfazer**.
- Achou o ouro e quer a prata? Marque o ouro → **Buscar perto**.
- O jogo fechou depois de editar? O endereço não era o certo; refine mais antes de editar.

## Valores cifrados (🔒)

Muitos jogos Unity guardam moedas e vida trancadas com uma chave ao lado (Anti-Cheat Toolkit).
A busca já procura esses valores sozinha e mostra o número real com **🔒**; editar ou congelar tranca o valor novo com a mesma chave.
Se o mesmo número aparecer normal e com 🔒, edite o 🔒. Dá para desligar em **Ajustes → Procurar valores cifrados**.

## Valor sem número (barra de vida)

1. Escolha o tipo (barra costuma ser **Float**) e toque em **Começar sem valor**.
2. Perca vida e toque em **Diminuiu**; sem mudar nada, toque em **Não mudou**. Repita até sobrar pouco.
3. Sabe quanto mudou? Digite o número e toque em **Diminuiu 10** / **Aumentou 10**.

## Ponteiros, offsets e assinatura (automático)

Quando o jogo reinicia, os endereços mudam. O Tinker cuida disso sozinho:

1. **Ao salvar** um valor, ele já busca os ponteiros (como o Pointer scan do Cheat Engine, com base só nas libs do jogo)
   e aprende a **assinatura**: os bytes em volta do valor que não mudam.
2. Salvos perto um do outro (até 4 KB, a mesma "ficha": ouro, gema, energia) ficam ligados pelo **offset** (**↳ Ouro +0x8**).
3. **Ao reabrir o jogo**, o Tinker escolhe ele sozinho (se estava aberto, ou ao abrir o Tinker) e cada salvo volta sozinho: ponteiro em uso → caminhos guardados
   (testados com o último valor) → assinatura. Os da mesma ficha vêm junto. O resultado aparece no topo da aba Salvos.
4. Sobrou dúvida entre vários caminhos? Abra o salvo, digite o valor que o jogo mostra e toque em **Filtrar pelo valor**.

Salvo com **⚠ de outra vez do jogo** não é lido nem escrito até voltar (escrever no endereço velho pode derrubar o jogo);
o que estava congelado volta congelado.
Marque dois salvos para ver a distância entre eles; na aba Memória dá para ir a `7a0010+8`.
Em **Ajustes**: "Preparar ponteiros ao salvar" e "Ponteiros só nas libs do jogo". Jogos 32 bits são reconhecidos sozinhos.

## Busca por bytes (AOB)

Aba **Busca → Busca por bytes**: pares hex com `??` como curinga, por exemplo `1F 20 03 D5 ?? ?? 00 B9`.
**Dados** procura na memória do jogo; **Código** procura nas instruções. Os lugares achados viram resultados normais.

## Reportar um problema

Aba **Registro** → **Compartilhar relatório** e envie o arquivo `.txt`.
Ele tem aparelho, versão, jogo, cada comando com o tempo que levou, resumo de problemas, falhas do Android e o logcat do jogo.

## Para desenvolvedores

- `daemon/tinkerd.c`: daemon root (C, sem dependências). Lê e escreve memória com `process_vm_*` (cai para `/proc/pid/mem`). Protocolo de texto por stdin/stdout.
  A busca usa todos os núcleos; a busca desconhecida guarda a memória em `/data/local/tmp/tinker-snap` (apagado ao sair).
- `app/`: app Kotlin + Jetpack Compose (Material 3). Só duas dependências diretas: Compose BOM e activity-compose.
- `tests/daemon_test.sh`: testa o daemon no Linux contra um processo de verdade. `app/src/test`: testes JVM.
- O GitHub Actions compila o daemon com o NDK, roda os testes, gera os APKs e publica a release.
- Análise do GameGuardian 101.4 usada como base: [docs/analise](docs/analise/README.md).
