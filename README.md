# Tinker

Editor de memória para Android, aberto, para jogos **single-player/offline**.
Inspirado no GameGuardian, com interface mais simples, menu flutuante e diagnóstico completo.

> Use só em jogos offline. Em jogos online, editar memória viola as regras do jogo e afeta outros jogadores.

## Baixar

Página de releases do repositório → **Tinker-X.Y.Z.apk** da versão mais recente.
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

## Como usar

O tutorial completo, com todas as opções e para que servem, está em
**[docs/tutorial/Tinker-tutorial.pdf](docs/tutorial/Tinker-tutorial.pdf)**. É o mesmo texto da **Ajuda** do app
(Ajustes → Ajuda), gerado da mesma fonte: [docs/tutorial/ajuda.py](docs/tutorial/ajuda.py).

## Para desenvolvedores

- `daemon/tinkerd.c`: daemon root (C, sem dependências). Lê e escreve memória com `process_vm_*` (cai para `/proc/pid/mem`). Protocolo de texto por stdin/stdout.
  A busca usa todos os núcleos; a busca desconhecida guarda a memória em `/data/local/tmp/tinker-snap` (apagado ao sair).
- `app/`: app Kotlin + Jetpack Compose (Material 3). Só duas dependências diretas: Compose BOM e activity-compose.
- `tests/daemon_test.sh`: testa o daemon no Linux contra um processo de verdade. `app/src/test`: testes JVM.
- O GitHub Actions compila o daemon com o NDK, roda os testes, gera os APKs e publica a release.
- Ajuda e tutorial: edite `docs/tutorial/ajuda.py` e rode `python3 -I docs/tutorial/gerar.py` (atualiza a Ajuda em
  `TinkerScreen.kt` e o PDF). Não edite a Ajuda direto no `.kt`.
- Testes automáticos no PC: `tools/pc/TINKER-AGENTE.bat` (emulador do Android Studio com Magisk) roda o autoteste e as
  missões no Jogo de Teste (`testgame/`) a cada versão nova e manda o resultado para a aba Relatórios da planilha.
- Análise do GameGuardian 101.4 usada como base: [docs/analise](docs/analise/README.md).
