package dev.tinker.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Estado da tela; fica fora da Activity para a janela flutuante e a Activity verem o mesmo. */
object Session {
    var process by mutableStateOf<GameProcess?>(null)
    var types by mutableStateOf(setOf<String>())  // vazio = Auto
    var value by mutableStateOf("")
    var status by mutableStateOf("Escolha o jogo para começar.")
    var count by mutableStateOf<Long?>(null)
    var results by mutableStateOf(listOf<Hit>())
    var regions by mutableStateOf(listOf<Region>())
    var busy by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var alpha by mutableFloatStateOf(0.92f)
    var panelSize by mutableIntStateOf(2)  // índice em PANEL_SIZES; padrão: tela cheia
    var appVisible by mutableStateOf(false)  // a tela principal do Tinker está na frente: a bolinha some (ficava por cima dos Ajustes)
    var panelLeft by mutableStateOf(false)  // tela deitada: painel à esquerda (padrão: à direita)
    var panelWide by mutableStateOf(false)  // o painel está de lado agora (tela deitada, menor que a tela cheia)
    var peek by mutableStateOf(false)  // segurando o "olho": painel quase invisível
    var autoPause by mutableStateOf(true)  // pausa o jogo durante busca/refino, para o valor não mudar no meio
    var findEncrypted by mutableStateOf(true)  // a busca também procura valores cifrados (🔒)
    var useBar by mutableStateOf(false)  // a bolinha abre a barra rápida em vez do painel
    var autoOverlay by mutableStateOf(true)  // ao abrir o app, já abre o menu flutuante e sai da frente (como o GameGuardian)
    var githubToken by mutableStateOf("")  // token só de leitura do GitHub: precisa quando o repositório é privado
    var autoUpdate by mutableStateOf(false)  // baixa e instala a versão nova sozinho (root) e roda o autoteste depois
    var ownKeypad by mutableStateOf(true)  // teclado do Tinker no lugar do teclado do telefone
    var update by mutableStateOf<Pair<String, String>?>(null)  // versão nova na página de releases: nome e link do APK
    var gameGone by mutableStateOf<GameProcess?>(null)  // o jogo fechou sozinho (não pelo Tinker): pergunta se reabre
    var gameGoneByUser = false  // fechado pelo botão do Tinker: mostra "Reabrir", mas não abre o painel sozinho
    var lastRefine by mutableStateOf("")  // o número do último refino: refinar de novo com ele não muda nada
    var canUndo by mutableStateOf(false)  // há um refino para desfazer
    var view by mutableStateOf<Pair<String, String>?>(null)  // visualizador de memória: endereço e tipo
    var codeMode by mutableStateOf(false)  // aba Memória: instruções (Código)
    var watchHits by mutableStateOf<Pair<String, List<Triple<String, Int, String>>>?>(null)  // último "o que escreve aqui"
    var fichaMode by mutableStateOf(false)  // aba Memória: lista de endereços (false) ou ficha do objeto (true)
    var tab by mutableIntStateOf(0)  // índice em TABS
    var settingsAt by mutableStateOf("")  // "ia": os Ajustes abrem já na parte da IA
    val saved = mutableStateListOf<SavedItem>()
    val recipes = mutableStateListOf<Recipe>()
    val aiProposals = mutableStateListOf<AiProposal>()
    val sheetRequests = mutableStateListOf<SheetRequest>()
    val patches = mutableStateListOf<CodePatch>()
    var release by mutableStateOf<List<Pair<String, String>>>(emptyList())  // valores que liberaram a recompensa (Foto do jogo)
    var traceHits by mutableStateOf<Pair<String, Pair<Int, List<Triple<String, Int, String>>>>?>(null)  // último contador de chamadas
    var alwaysRequests by mutableStateOf(setOf<String>())  // tipos que a pessoa mandou lembrar
    var autoReports by mutableStateOf(true)  // relatório sozinho a cada 10 min de jogo (com atividade nova) e ao fechar o jogo pelo Tinker
    val aiUndo = mutableStateListOf<AiUndo>()
    val aiClasses = mutableStateListOf<GameClass>()  // classes que a IA recomendou: aparecem no topo da lista de classes
    var aiAsk by mutableStateOf<Pair<String, String>?>(null)  // pedido automático: (onde, pergunta) — a caixa ✨ daquela tela pergunta sozinha
    var engine by mutableStateOf<GameEngine?>(null)
    var aiProvider by mutableStateOf("groq")  // "gemini" ou "groq"; Groq de padrão: o Gemini grátis estava levando 80–110 s por resposta
    var aiReserve by mutableStateOf(true)  // sem cota ou servidor cheio: segue no outro provedor, se ele tiver chave
    var groqKeys by mutableStateOf(listOf<String>())
    var groqKeyIndex by mutableIntStateOf(0)
    val groqKey get() = groqKeys.getOrNull(groqKeyIndex) ?: groqKeys.firstOrNull().orEmpty()
    var groqModels by mutableStateOf(listOf<String>())  // os que a chave do Groq pode usar, o melhor primeiro
    var groqModel by mutableStateOf("")  // vazio: escolhido sozinho na primeira pergunta
    var sheetGroqKeys = setOf<String>()  // chaves do Groq que vieram do painel
    val pendingLessons = mutableStateListOf<String>()  // lições que a IA propôs, esperando a pessoa aprovar
    var autoFind by mutableStateOf<String?>(null)  // 🎯 Achar sozinho rodando: o que está fazendo agora (null = parado)
    var autoFindWinner by mutableStateOf<Hit?>(null)  // o que mudou o número da tela no teste
    var aiCanTest by mutableStateOf(false)  // a IA pode escrever um valor de teste (e voltar o original) para saber se é o verdadeiro
    var aiAuto by mutableStateOf(false)  // modo autônomo 1: a IA aplica, congela, testa e cria receitas sem esperar o toque
    var aiControl by mutableStateOf(false)  // modo autônomo 2: a IA toca e arrasta na tela do jogo para fazer o valor mudar
    var watchFields by mutableStateOf(true)  // enquanto joga, anota sozinho os campos das classes conhecidas que mudam
    var gameUpdated by mutableStateOf(false)  // o jogo mudou de versão desde a última vez: a IA conserta os salvos que não voltarem
    val geminiUseModel = java.util.concurrent.ConcurrentHashMap<String, Int>()  // "chave|modelo" → pedidos de hoje (a cota é por modelo)
    val geminiLimit = java.util.concurrent.ConcurrentHashMap<String, Int>()  // modelo → pedidos por dia aprendidos (quando a cota acabou)
    // a contagem de hoje começou na virada do dia? Instalado no meio do dia, o contador não viu os pedidos de antes e aprenderia
    // um limite baixo demais (25/10: "aguenta 2 pedidos" e trocava de chave a cada 2 perguntas)
    @Volatile var geminiCountFull = false
    val geminiUse = mutableStateMapOf<Int, Int>()  // pedidos de hoje por chave (índice); o dia vira à meia-noite do Pacífico, como a cota
    val geminiOut = mutableStateMapOf<Int, String>()  // chave → hora em que a cota acabou hoje
    var geminiUseDay = quotaDay()
    var keySource by mutableStateOf("")  // link do painel de chaves (planilha do Google com o script do Tinker)
    var sheetKeys = setOf<String>()  // chaves que vieram do painel: saem daqui quando saem de lá
    var aiHidePanel by mutableStateOf(false)  // a IA vai tocar no jogo: o painel se minimiza para os toques chegarem nele
    var geminiKeys by mutableStateOf(listOf<String>())  // em ordem: a cota de uma acabou, o Tinker passa para a próxima
    var geminiKeyIndex by mutableIntStateOf(0)
    val geminiKey get() = geminiKeys.getOrNull(geminiKeyIndex) ?: geminiKeys.firstOrNull().orEmpty()
    var geminiModels by mutableStateOf(listOf<String>())  // os que a chave do Gemini pode usar, o melhor primeiro
    var geminiModel by mutableStateOf("gemini-2.5-flash")  // se sair de linha, o assistente troca sozinho pelo Flash mais novo
    var geminiAutoModel by mutableStateOf(true)  // a cada conversa nova usa o mais rápido entre os bons (medido de verdade)
    val geminiSpeed = mutableStateMapOf<String, Long>()  // tempo médio de uma rodada por modelo, em ms
    val classes = mutableStateListOf<String>()  // classes IL2CPP abertas por nome neste jogo (atalhos)
    val history = mutableStateListOf<Recent>()  // últimas buscas, para repetir com um toque
    var pointerInfo by mutableStateOf("")
    var restoreInfo by mutableStateOf("")  // o que voltou sozinho ao escolher o jogo
    var gameLibsOnly by mutableStateOf(true)  // ponteiros só com base nas libs do jogo
    var autoPointers by mutableStateOf(true)  // ao salvar, já prepara os ponteiros
}
