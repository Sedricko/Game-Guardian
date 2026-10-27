# Fonte única da Ajuda do Tinker: gera a Ajuda do app (SettingsTab.kt, entre as marcas AJUDA) e o tutorial em PDF.
# Mudou um texto? Edite aqui e rode: python3 -I docs/tutorial/gerar.py
AJUDA = [
("Começo", "Para que serve o Tinker",
 "Editor de memória para jogos offline de um jogador, como o GameGuardian e o Cheat Engine: acha onde o jogo guarda um número "
 "(moedas, vida, tempo), muda, congela e faz a mudança voltar sozinha da próxima vez. Precisa de root (Magisk). Não use em jogo online."),
("Começo", "Menu flutuante",
 "Abrir o Tinker já abre a bolinha sobre o jogo e tira o app da frente (dá para desligar em Ajustes). Toque na bolinha para abrir o "
 "painel; segure para abrir o outro modo (painel ↔ barra rápida). No topo: toque no nome do jogo para trocar; 👁 (segure) espia o "
 "jogo; ⏸/▶ pausa; ⚡ barra rápida; — minimiza; ✕ sai."),
("Começo", "Barra rápida",
 "Toque em ⚡: uma barra pequena fica sobre o jogo, com o campo de valor, ⏸/▶, Buscar/Refinar e ↑ ↓ = ≠ (aumentou, diminuiu, não "
 "mudou, mudou). Com até 50 resultados aparece Editar; com até 5, os valores ao vivo; com 1, ❄ congela e salva. As receitas do jogo "
 "aparecem como ▶ botões: um toque aplica. Arraste pelo ⠿; ▢ abre o painel; – volta para a bolinha."),
("Busca", "Como achar um valor",
 "1. Escolha o jogo. 2. Digite o número que aparece na tela e toque em Nova busca. 3. Faça o número mudar no jogo, digite o novo e "
 "toque em Refinar. 4. Repita até sobrar 1 ou poucos. Refinou errado e sobrou 0? ↶ Desfazer volta à lista anterior (tocar de novo "
 "refaz). Sem saber o número novo, use ↑ ↓ = ≠."),
("Busca", "O que digitar no valor",
 "100 = número exato. 100~200 = faixa (número arredondado ou abreviado: 1.2K = 1150~1250; só para buscar e refinar). 100;250 = vários "
 "de uma vez. 100;250;7::128 = grupo: todos a até 128 bytes um do outro, no mesmo objeto (corta milhares de resultados soltos). "
 "150X4 = cifrado com a chave 4 bytes depois, como no GameGuardian. ...A4 no refino = só os endereços que terminam em A4."),
("Busca", "Tipos de valor",
 "Auto procura Dword, Float, Qword e Double de uma vez. Se souber o tipo, marque só ele: mais rápido e com menos resultados. Vida e "
 "velocidade costumam ser Float; moedas, Dword. Jogo com Lua (o motor diz · Lua): 🌙 Lua acha só números do Lua."),
("Busca", "Valores cifrados (🔒)",
 "Muitos jogos trancam moedas e vida com uma chave. A busca já procura esses valores e mostra o número real com 🔒. Ao editar ou "
 "congelar um 🔒, o Tinker tranca o valor novo com a mesma chave. Se aparecer o mesmo número normal e com 🔒, edite o 🔒: o normal "
 "costuma ser só uma cópia (ou isca do antitrapaça)."),
("Busca", "Valor desconhecido",
 "Para barras sem número (vida, energia): escolha o tipo (barra costuma ser Float) e toque em Sem valor. Faça a barra mudar e toque em "
 "↑ ou ↓; sem mudar nada, =. Repita até sobrar pouco. Com um número no campo dá para dizer quanto mudou (↓ 10)."),
("Busca", "🎯 Achar sozinho",
 "Digite o número da tela (ou o nome escrito ao lado dele, ex.: Abates, para número que muda rápido) e toque em 🎯 Achar sozinho. O "
 "painel some, o Tinker lê a tela (no aparelho, sem internet) e refina sozinho a cada mudança enquanto você joga. Com até 3 e \"IA "
 "pode testar valores\" ligado em Ajustes, ele testa cada um e mostra Salvar no certo."),
("Busca", "Qual resultado salvar",
 "Com até 20 resultados, em jogo Unity, cada um mostra o campo do jogo (PlayerData.UpgradeStone). \"Cópia temporária (Int32)\" é só o "
 "número da tela: editar não muda nada. Salve o campo de verdade."),
("Busca", "Editar resultados",
 "Toque num resultado: Novo valor, ❄ Congelar, Salvar, Ver próximos, Ver ficha, O que escreve aqui. Editar (todos ou os marcados) "
 "muda vários de uma vez; \"Só os que valem agora: X → Y\" muda só os que valem X (todos os inimigos com 100 de vida), sem perder a "
 "lista. Em sequência escreve 100, 101, 102…: o número que aparecer no jogo mostra o endereço certo; 🎯 Descobrir pela tela faz isso "
 "sozinho e volta tudo."),
("Busca", "Mais buscas",
 "Recolhido embaixo da busca: Sem valor, Busca por bytes (AOB: pares hex com ?? de curinga, em Dados ou Código), Foto do jogo, Onde o "
 "jogo escreve, Editor de save e Ir para endereço."),
("Busca", "Foto do jogo",
 "Para pegar de novo uma recompensa (jogo offline): 1) Tirar foto, parado 4 s. 2) Pegue a recompensa. 3) Ver o que mudou: só o que "
 "mudou com a ação. 4) Voltar como estava: os valores voltam aos da foto e a recompensa fica para pegar de novo. Se travar, feche e "
 "abra o jogo."),
("Busca", "📍 Onde o jogo escreve",
 "Começar, carregue o jogo ou uma fase, Ver: mostra as regiões da memória onde o jogo escreveu. Usar nas buscas deste jogo faz a busca "
 "começar só nelas; se não achar, procura em tudo sozinha."),
("Busca", "💾 Editor de save",
 "Lista os arquivos de progresso do jogo, da pasta interna e da externa (Android/data, aparecem como ext/...). Os de texto (XML, "
 "PlayerPrefs do Unity, JSON) mostram cada número pelo nome; mude e toque em Gravar: o Tinker faz backup das duas pastas, fecha o "
 "jogo e devolve o arquivo. O agente offline guarda qual arquivo e campo você mudou em cada jogo. Save binário ou cifrado não dá. "
 "Só jogo offline."),
("Ferramentas", "🧰 Ferramentas",
 "Logo abaixo da busca, uma fileira de botões; só a escolhida abre embaixo. Toque de novo para fechar."),
("Ferramentas", "🧭 Fácil",
 "Passo a passo para quem não conhece Dword, Float ou cifrado: pergunta só o número da tela e se aumentou ou diminuiu. O tipo, o "
 "cifrado, o dobro e as cópias o Tinker resolve sozinho. Abre sozinho na primeira vez."),
("Ferramentas", "⏩ Velocidade (Unity)",
 "Fique no menu ou numa fase parada e toque em Achar a velocidade: o Tinker acha a escala de tempo do Unity pelo relógio do motor. "
 "Escolha 0.5x, 2x, 3x ou 5x; 1x volta ao normal. Muda só o valor do motor, sem entrar no jogo."),
("Ferramentas", "⏱️ Cronômetros",
 "Com uma construção ou espera rodando, digite quanto falta na tela (2h 30min, 45min, 90s) e toque em Achar cronômetros. Terminar "
 "agora (ou Terminar todos) põe a hora de terminar no agora. Recusa se achar lugares demais, para não estragar o jogo."),
("Ferramentas", "🛒 Loja",
 "Abra a loja do jogo, digite 2 a 6 preços que aparecem (ou Ler da tela) e toque em Achar preços: a lista de preços vira resultado. "
 "Tudo por 1 deixa todos por 1."),
("Ferramentas", "🔓 Desbloquear (Unity)",
 "Abra a tela dos itens (loja, inventário) e toque em Achar o que desbloquear: aparecem os campos isUnlocked, owned, purchased… com "
 "quantos objetos vivos. Desbloquear liga em todos (isLocked vira 0)."),
("Ferramentas", "🧮 Disfarçado",
 "Quando a busca normal não acha porque o jogo guarda o número de outro jeito (dobro, ×10, +100 ou cifrado com chave fixa): digite o "
 "número da tela e toque em Começar. A cada mudança, digite o novo e escolha ×k + c ou Cifrado. Mude de jeitos diferentes (ganhe 1, "
 "depois 3): subir sempre de 1 deixa contadores passarem."),
("Salvos", "Salvar e congelar",
 "Salvar põe o resultado na lista do jogo; Congelar reescreve o valor várias vezes por segundo. Em Salvos: dar nome, editar vários, "
 "exportar e importar. Marque dois salvos para ver a distância entre eles."),
("Salvos", "Ponteiros e reabrir o jogo",
 "Quando o jogo reinicia, os endereços mudam. Ao salvar, o Tinker já busca os ponteiros e aprende a assinatura; ao reabrir o jogo, "
 "cada salvo volta sozinho (ponteiro → caminhos → assinatura), e os da mesma ficha vêm junto pelo offset (↳ Ouro +0x8). Vários "
 "caminhos? Abra o salvo, digite o valor do jogo e Filtrar pelo valor. ⚠ = salvo de outra vez do jogo, não é lido nem escrito até "
 "voltar. ↻ procura na hora."),
("Salvos", "Receitas",
 "Marque os campos e toque em Criar receita: guarda cada campo pelo nome da classe (Unity), senão pelo ponteiro, senão pela "
 "assinatura. Aplicar escreve tudo de uma vez (e congela os ❄); \"ao abrir\" aplica sozinha quando o jogo carrega. Também na barra "
 "rápida. Copiar manda como texto; Colar lista recebe."),
("Salvos", "Código e patches",
 "Para o recurso não diminuir: no salvo, O que escreve aqui? e gaste o recurso em 15 s. Aparece a instrução que muda o valor; Ver "
 "código abre Memória → Código. Tocar numa instrução: Desligar esta instrução (NOP) ou trocar os bytes; vira um patch em Salvos → Patches, com "
 "liga/desliga, que volta sozinho ao reabrir. Se o jogo cair logo depois, o patch fica suspeito e desligado."),
("Memória", "Endereços e Buscar perto",
 "Ver próximos mostra os valores vizinhos; Ir para endereço aceita 7a0010+8. Buscar perto procura um valor (a prata) nas linhas em "
 "volta de um ou vários endereços (o ouro)."),
("Memória", "Ficha do objeto",
 "Num salvo, Ver ficha (ou Memória → Ficha): os campos em volta do valor, ao vivo; o que muda fica destacado. Em jogo Unity, com "
 "os nomes do jogo (Player.hp), o texto das strings (\"Herói\"), Vector3 como (x, y, z) e listas com quantos itens. Nos outros, com o "
 "tipo adivinhado. Abra pela classe: digite PlayerData e Abrir, ou Ver todas as classes. Copiar os números para outro objeto da mesma "
 "classe clona um item (ponteiros e textos não mudam)."),
("Memória", "Métodos (Unity)",
 "Na Ficha, ⚙ Métodos da classe: Contar chamadas (quantas vezes roda em 10 s) e Sempre devolver (número, sim/não ou Não fazer "
 "nada), que vira um patch desligado. Exportar a lista de classes gera um .cs com classes, campos e métodos."),
("Memória", "Funções da lib (sem IDA)",
 "Memória → Código: digite parte do nome (gold, AddCoins) e Procurar: as funções com nome das libs do jogo. Ver código, Quem ela "
 "chama, Contar chamadas, Sempre devolver ou Não fazer nada (patch). Quais rodam quando eu jogo? conta até 12 de uma vez."),
("Memória", "Unreal e Lua",
 "Unreal: na Ficha, digite o nome do objeto ou da classe (Player, BP_Player_C) e Procurar, pelas tabelas do próprio motor. Lua (xLua): "
 "abra um resultado 🌙 na Ficha: a tabela do Lua aparece com o nome de cada campo; \"tabela ›\" abre a outra."),
("Registro", "O jogo fechou",
 "Quase sempre é edição num endereço errado ou de outro tipo: refine até sobrar 1 antes de editar. O Tinker percebe a queda, pergunta "
 "se reabre e, reabrindo, os salvos voltam sozinhos. O motivo fica no Registro."),
("Registro", "Reportar um problema",
 "Registro → Compartilhar relatório e envie o .txt: tem tudo para investigar."),
("Ajustes", "Assistente (IA)",
 "Em Ajustes, escolha Gemini ou Groq e coloque as chaves (grátis em aistudio.google.com e console.groq.com) ou use o painel de chaves "
 "da planilha. Toque em ✨ na Busca, na Ficha ou no Registro. A IA procura classes, busca, refina e compara, mas não edita sozinha: "
 "quando acha, aparece Aplicar e ↶ Desfazer. Só vai o que está na tela, quando você toca em Perguntar. Precisa de internet."),
("Ajustes", "Agente offline",
 "Aprende a achar sem internet e sem gastar a IA. No 🎯 Achar sozinho pelo nome (ex.: Moedas, com o tipo Auto), ele vai direto no "
 "tipo que já acertou neste jogo; o que errou (sobrou 0) deixa de ser tentado primeiro. Também aprende com cada salvo que você "
 "nomeia (Ouro = Dword neste jogo) e com cada campo que muda no editor de save. Já vem sabendo o tipo comum de moedas, vida, energia, "
 "nível, itens e tempo, e as técnicas de achar de cada caso (aparecem como dica quando não acha). Ajustes → Agente offline mostra o "
 "que ele sabe; com o painel da planilha, vai para a aba Agente offline (só dele, separada do aprendizado da IA). Pedir ajuda à IA "
 "quando não souber (desligado no começo): uma pergunta curta à IA pelo tipo, tenta de novo e só guarda se der certo (origem IA)."),
("Ajustes", "Opções",
 "Menu flutuante: abrir ao iniciar, tamanho, lado com o jogo deitado e transparência. Busca: pausar o jogo durante a busca, "
 "procurar valores cifrados (🔒), Teclado do Tinker. Ponteiros: preparar ao salvar e só nas libs do jogo. Regiões de memória: onde "
 "a busca procura. Atualizar e testar sozinho: baixa a versão nova, instala (root) e roda o autoteste. Mandar relatório sozinho: a "
 "cada 10 min de jogo, para a planilha."),
("Ajustes", "Biblioteca e backup",
 "Cada jogo guarda notas da IA, salvos, receitas e patches. Fazer backup grava tudo em Download/Tinker; Exportar e Importar (colar) "
 "levam para outro aparelho; Juntar com a planilha mistura com o painel. Juntar nunca apaga o que já existe."),
]
