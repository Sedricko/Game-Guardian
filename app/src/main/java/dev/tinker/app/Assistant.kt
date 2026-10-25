package dev.tinker.app

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.ensureActive

/** Conversa com a IA: guarda as mensagens para "Continuar" seguir de onde parou (respostas antigas de ferramentas encolhem). */
class AiChat {
    internal var provider = ""
    internal val claude = mutableListOf<BetaMessageParam>()
    internal var gemini = JSONArray()
    val started get() = claude.isNotEmpty() || gemini.length() > 0

    fun reset() {
        claude.clear()
        gemini = JSONArray()
    }
}

/** Ferramenta que a IA pode chamar sozinha: parâmetros são todos texto (nome → descrição). */
class AiTool(val name: String, val description: String, val params: Map<String, String>, val required: List<String>, val run: suspend (JSONObject) -> String)

/**
 * Assistente de IA (Claude ou Gemini, escolhido em Ajustes): uma pergunta por vez, com o contexto que a tela mandar
 * (classes, ficha ou relatório) e ferramentas para olhar a memória do jogo (procurar classes, abrir objetos, buscar valores).
 * Só roda com a chave da API que você colocou em Ajustes; nada sai do aparelho sem você tocar no botão.
 */
object Assistant {
    private const val SYSTEM = """Você é o assistente do Tinker, um editor de memória para Android usado em jogos single-player/offline.
Responda em português do Brasil, curto e direto, com passos que a pessoa faz no próprio Tinker.
O Tinker tem: busca por valor (Auto, Dword, Float, Double, cifrados 🔒, faixas 10~20), refino, busca sem valor (↑ ↓ = ≠),
ficha do objeto com nomes IL2CPP (Memória → Ficha → Ver todas as classes do jogo), salvos com congelar ❄, ponteiros 🎯, receitas e busca AOB.
Regras para acertar (siga sempre):
- Valor mudou depois de uma busca? Use refinar com o valor novo, nunca buscar_valor de novo (isso joga fora o progresso).
- A pessoa corrigiu o valor ("o certo é 1950")? Refine com ele.
- Só proponha edição quando sobrar 1 candidato depois de refinar por valor, ou quando testar_valor confirmar; com 2 a 5,
  refine de novo (peça para gastar/ganhar e diga o valor) ou teste cada um. Nunca proponha com vários candidatos sem confirmar.
- esperar_mudanca serve para quando a pessoa não sabe dizer o número; se ela sabe, peça o número e use refinar.
- Já sabe a classe mas não o campo (velocidade, tempo, vida)? Use o_que_muda no objeto enquanto a pessoa joga: mostra só os campos que mudaram.
- Diga com quantos candidatos e qual evidência você chegou à resposta.
- Não busque números pequenos e comuns (0 a 100, como 10) sozinhos: dão dezenas de milhares de resultados. Prefira o nome da
  classe/campo, busca em grupo com outros valores da tela, ou peça um número maior.
- Seja econômico: cada ferramenta que você chama reenvia a conversa inteira; procure com 1 a 3 palavras certeiras.
Ao sugerir classes ou campos, use exatamente os nomes que aparecem no contexto e diga por que cada um parece guardar o valor.
Se o jogo for online e o servidor confere o valor, avise que editar não vai valer.
Você tem ferramentas que olham a memória do jogo agora: procurar classes, abrir os objetos de uma classe com os valores atuais,
ver a ficha de um endereço, buscar e refinar valores. Use-as antes de responder: não peça para a pessoa conferir o que você
mesmo pode ver. Se a pessoa disser um valor que vê no jogo (ex.: tenho 1250 de ouro), busque esse valor e veja os campos dos
resultados. Valores que andam juntos (ouro, gema, nível) dá para buscar em grupo: 1250;30;7::128.
Depois de buscar, com até 50 resultados, olhe o [campo IL2CPP] de cada um antes de pedir para refinar: campos de dados
(PlayerData.Gold, Wallet.diamonds, <Gems>k__BackingField) são o valor verdadeiro; Text, Label, UI e "cópia temporária" são só a
tela. Se um campo de dados aparecer, proponha a edição direto. Se não der para saber, use ver_endereco nos resultados e só então
peça para mudar o valor. Procure classes com poucas palavras certeiras (gold gem diamond), não listas enormes.
Você não edita nada direto: quando tiver certeza do campo, use propor_edicao com o endereço e o tipo exatos (aparece um botão
Aplicar para a pessoa) e explique na resposta qual classe e campo é. Se não tiver certeza, diga o que falta para confirmar.
Quando precisar que o valor mude no jogo (para refinar ou comparar), prefira esperar_mudanca: diga o que a pessoa deve fazer
(ex.: "minimize e gaste um pouco de ouro") e chame esperar_mudanca, que refina sozinho quando o valor muda. Repita até sobrar poucos.
Com poucos candidatos e sem nome claro, use testar_valor (se a pessoa deixou) para confirmar qual é o verdadeiro antes de propor.
Se não puder esperar, peça para ela fazer e tocar em Continuar: a conversa segue de onde parou. Sem saber o número (barra de vida), use busca_sem_valor e depois comparar.
Para achar um recurso pelo nome: procurar_classes procura também nos campos (gold acha a classe que tem o campo Gold). Não pare
na lista de nomes: abra as classes candidatas e mostre o campo com o valor de agora (ex.: PlayerData.Gold = 550). Se a classe tem
muitos objetos, use abrir_classe com o valor que a pessoa vê (ou pergunte o valor) para achar o objeto certo. Sempre que recomendar
classes, chame mostrar_classes para elas aparecerem no topo da lista da pessoa.
Busca AOB: com ler_bytes você vê os bytes em volta de um valor e monta um padrão (troque por ?? os bytes que mudam, como o
próprio valor); buscar_bytes acha o lugar de novo quando o endereço muda, ou acha código no jogo.
Veja a tela: ver_tela te mostra um print do jogo. Use no começo para ler os números (ouro, gemas, vida) e já buscar, sem pedir
para a pessoa digitar; e depois de uma edição, para conferir se mudou na tela. Para ponteiros, preparar_ponteiros busca agora e
diz se ficaram bons (o Tinker também busca sozinho quando a edição é aplicada).
Modo autônomo (quando ligado em Ajustes): com "aplicar sozinha", propor_edicao e propor_receita já aplicam (diga o que fez);
com "controlar o jogo", você mesmo faz o valor mudar: ver_tela, ache um botão que gasta ou ganha o recurso, tocar nele, ver o
número novo no print e refinar com ele; repita até sobrar 1. Jogue com cuidado: prefira ações baratas e reversíveis, nunca
toque em compras com dinheiro real, preços em moeda real, assinaturas, Play Store, nem em "vender tudo"/"excluir". Se não achar
um jeito seguro de mudar o valor, pare e diga à pessoa o que fazer.
Aprenda e melhore: leia "Suas lições" e "Suas anotações" no contexto antes de agir e siga o que elas dizem (uma ERRO anotada é
um endereço que você já errou: não repita). No fim de cada tarefa, acertando ou errando, chame aprender com uma lição curta e geral
sobre como agir melhor (o que encurtou o caminho, o que te enganou), sem repetir lição que já existe; e anotar com o que vale só
para este jogo. Se "Sinais de que o servidor confere valores" aparecer, ou depois de editar o ver_logcat_jogo mostrar recusa,
avise que editar esse valor não vai valer (o jogo é conferido pelo servidor) e anote.
Use ver_salvos para saber o que já foi achado e se os ponteiros voltaram. Quando descobrir algo que vale para a próxima vez
(classe e campo de um recurso, tipo do valor, se é cifrado), use anotar. Para deixar várias edições prontas para toda vez que o
jogo abrir, use propor_receita com os salvos."""

    @Volatile private var lowThinking = true

    /** Modelos cuja cota do dia acabou em todas as chaves (a cota é por modelo): até quando ficam de fora. */
    private val tired = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun tiredModels() = tired.filterValues { it > System.currentTimeMillis() }.keys

    // gasto da última pergunta, para a caixa mostrar (consultas, tempo, tokens)
    @Volatile private var statRounds = 0
    @Volatile private var statIn = 0L
    @Volatile private var statOut = 0L
    @Volatile var lastStats = ""
        private set

    private const val MAX_ROUNDS = 20  // chamadas de ferramenta por pergunta, para não ficar em volta para sempre

    /** Imagem (JPEG em base64) que uma ferramenta pediu para mandar junto da resposta dela (ver_tela). */
    @Volatile internal var pendingImage: String? = null

    // controlando o jogo cada toque é uma rodada: precisa de mais
    private fun rounds() = if (Session.aiControl) 40 else MAX_ROUNDS

    /** O provedor escolhido tem chave? */
    fun ready() = if (Session.aiProvider == "gemini") Session.geminiKey.isNotBlank() else Session.aiKey.isNotBlank()

    /** Na primeira mensagem vai o contexto da tela; nas seguintes (Continuar), só o que a pessoa disse. */
    suspend fun ask(chat: AiChat, context: suspend () -> String, question: String, tools: List<AiTool>, onStep: (String) -> Unit): String {
        // Claude sem chave e Gemini com chave: vai pelo Gemini em vez de só responder "chave inválida"
        if (Session.aiProvider == "claude" && Session.aiKey.isBlank() && Session.geminiKeys.isNotEmpty()) Session.aiProvider = "gemini"
        if (chat.provider != Session.aiProvider) { chat.reset(); chat.provider = Session.aiProvider }
        // a cada pergunta (começo de um turno): o modelo mais rápido entre os bons. Só no começo da conversa nunca trocava:
        // a pessoa segue "continuando" por horas. No meio de um turno não troca (o Gemini confere o pensamento do turno atual).
        if (Session.aiProvider == "gemini" && Session.geminiAutoModel) {
            if (Session.geminiModels.isEmpty()) Session.geminiModels = geminiModels(Session.geminiKey)
            pickGemini(Session.geminiModels, Session.geminiSpeed, tiredModels())?.takeIf { it != Session.geminiModel.trim() }?.let { m ->
                Diag.ai("Gemini: usando $m nesta conversa (rodada média: ${Session.geminiSpeed.entries.joinToString { "${it.key} ${it.value / 1000} s" }.ifEmpty { "sem medidas ainda" }})")
                Session.geminiModel = m
            }
        }
        val prompt = if (chat.started) question else "${context()}\n\n=== Pergunta ===\n$question"
        Diag.ai("==== PERGUNTA (${Session.aiProvider}, ${if (Session.aiProvider == "gemini") Session.geminiModel else "claude-opus-5-5"}${if (chat.started) ", continuando" else ""}): $question")
        if (!chat.started) Diag.ai("contexto (${prompt.length} letras):\n${prompt.take(6000)}${if (prompt.length > 6000) "\n…(cortado)" else ""}")
        val t0 = System.currentTimeMillis()
        statRounds = 0; statIn = 0; statOut = 0
        val answer = if (Session.aiProvider == "gemini") askGemini(Session.geminiKey, chat, prompt, tools, onStep)
        else askClaude(Session.aiKey, chat, prompt, tools, onStep).let { a ->
            // chave do Claude recusada e há Gemini: troca sozinho e pergunta de novo
            if (a != CLAUDE_BAD_KEY || Session.geminiKeys.isEmpty()) a
            else { Diag.add("Chave do Claude recusada: usando o Gemini"); onStep("🔁 Chave do Claude recusada: usando o Gemini"); Session.aiProvider = "gemini"; return ask(chat, context, question, tools, onStep) }
        }
        lastStats = "$statRounds consultas · ${(System.currentTimeMillis() - t0) / 1000} s · ${(statIn + statOut + 500) / 1000} mil tokens"
        Diag.ai("==== RESPOSTA ($lastStats):\n$answer")
        return answer
    }

    /** Roda a ferramenta pedida; erro vira texto para a IA (ela pode tentar de outro jeito). */
    private suspend fun runTool(tools: List<AiTool>, name: String, args: JSONObject, onStep: (String) -> Unit): String {
        val t = tools.firstOrNull { it.name == name } ?: return "Ferramenta $name não existe."
        val shown = t.params.keys.mapNotNull { k -> args.optString(k).takeIf { it.isNotEmpty() } }.joinToString(" ")
        onStep("🔧 $name $shown".trim())
        Diag.add("Assistente usou $name $shown")
        // cancelado (a pessoa saiu da tela): para a conversa, em vez de mandar "erro" para a API até o limite de rodadas
        val t0 = System.currentTimeMillis()
        val out = runCatching { t.run(args) }.onFailure { if (it is kotlinx.coroutines.CancellationException) { Diag.ai("ferramenta $name cancelada"); throw it } }
            .getOrElse { Diag.ai("ferramenta $name ERRO: ${it.stackTraceToString().take(1500)}"); "Erro: ${it.message}" }.take(20_000)
        Diag.ai("ferramenta $name $args (${System.currentTimeMillis() - t0} ms) →\n${out.take(3000)}${if (out.length > 3000) "\n…(${out.length} letras)" else ""}")
        return out
    }

    /** Claude pelo SDK oficial, com as ferramentas; erros viram texto para a tela (chave errada, sem internet, limite). */
    private suspend fun askClaude(key: String, chat: AiChat, prompt: String, tools: List<AiTool>, onStep: (String) -> Unit): String {
        if (key.isBlank()) return "Coloque a chave da API do Claude em Ajustes → Assistente (IA)."
        val client = AnthropicOkHttpClient.builder().apiKey(key.trim()).build()
        val defs = tools.map { t ->
            BetaTool.builder().name(t.name).description(t.description).inputSchema(
                BetaTool.InputSchema.builder().properties(BetaTool.InputSchema.Properties.builder().apply {
                    t.params.forEach { (k, d) -> putAdditionalProperty(k, JsonValue.from(mapOf("type" to "string", "description" to d))) }
                }.build()).required(t.required).build(),
            ).build()
        }
        // só cresce (mensagens novas no fim): o pensamento do modelo e o cache continuam valendo entre as rodadas
        val messages = chat.claude
        val before = messages.size
        messages += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(prompt).build()
        return try {
            repeat(rounds()) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val params = MessageCreateParams.builder()
                    .model("claude-opus-5-5")
                    .maxTokens(16000L)
                    .system(SYSTEM)
                    // o contexto (lista de classes, relatório) repete entre rodadas e perguntas: fica em cache e sai mais barato
                    .cacheControl(BetaCacheControlEphemeral.builder().build())
                    .apply { defs.forEach { addTool(it) } }
                    .messages(messages)
                    // recusa do filtro de segurança: o servidor tenta de novo noutro modelo em vez de parar
                    .addBeta("server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .build()
                val c0 = System.currentTimeMillis()
                val r = client.beta().messages().create(params)
                statRounds++; statIn += r.usage().inputTokens(); statOut += r.usage().outputTokens()
                Diag.ai("API Claude rodada ${it + 1}: ${System.currentTimeMillis() - c0} ms, parada ${r.stopReason().orElse(null)}, tokens entrada ${r.usage().inputTokens()} " +
                    "(cache ${r.usage().cacheReadInputTokens().orElse(0L)}), saída ${r.usage().outputTokens()}")
                if (r.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                    while (messages.size > before) messages.removeAt(messages.lastIndex)  // a pergunta recusada não fica na conversa
                    return "O assistente não respondeu essa pergunta."
                }
                messages += r.toParam()
                val uses = r.content().mapNotNull { it.toolUse().orElse(null) }
                if (uses.isEmpty() || r.stopReason().orElse(null) != BetaStopReason.TOOL_USE) {
                    return r.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim().ifEmpty { "Sem resposta." }
                }
                // todas as respostas das ferramentas numa mensagem só
                val results = uses.map { u ->
                    val args = runCatching { JSONObject(u._input().convert(Map::class.java) as Map<*, *>) }.getOrDefault(JSONObject())
                    BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder().toolUseId(u.id()).content(runTool(tools, u.name(), args, onStep)).build())
                }
                // print da tela (ver_tela): vai na mesma mensagem, depois das respostas das ferramentas
                val image = pendingImage?.also { pendingImage = null }?.let {
                    BetaContentBlockParam.ofImage(BetaImageBlockParam.builder().source(
                        BetaBase64ImageSource.builder().data(it).mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).build(),
                    ).build())
                }
                messages += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results + listOfNotNull(image)).build()
            }
            "Parei depois de ${rounds()} consultas sem chegar a uma resposta. Tente uma pergunta mais específica."
        } catch (e: UnauthorizedException) {
            while (messages.size > before) messages.removeAt(messages.lastIndex)  // a pergunta que falhou não fica na conversa
            CLAUDE_BAD_KEY
        } catch (e: RateLimitException) {
            while (messages.size > before) messages.removeAt(messages.lastIndex)
            "Limite de uso da API atingido. Espere um pouco e tente de novo."
        } catch (e: AnthropicServiceException) {
            while (messages.size > before) messages.removeAt(messages.lastIndex)
            "A API respondeu com erro ${e.statusCode()}: ${e.message}"
        } catch (e: AnthropicIoException) {
            while (messages.size > before) messages.removeAt(messages.lastIndex)
            "Sem conexão com a API: ${e.message}"
        } finally {
            client.close()
        }
    }

    // Gemini: HTTP direto (sem biblioteca); o Google troca os nomes dos modelos, então o nome fica em Ajustes
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/"

    private suspend fun askGemini(key: String, chat: AiChat, prompt: String, tools: List<AiTool>, onStep: (String) -> Unit): String {
        if (key.isBlank()) return "Coloque a chave do Gemini em Ajustes → Assistente (IA)."
        val decls = JSONArray().apply {
            tools.forEach { t ->
                val props = JSONObject().apply { t.params.forEach { (k, d) -> put(k, JSONObject().put("type", "STRING").put("description", d)) } }
                put(JSONObject().put("name", t.name).put("description", t.description)
                    .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(t.required))))
            }
        }
        val contents = chat.gemini
        val before = contents.length()
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        fun drop() { while (contents.length() > before) contents.remove(contents.length() - 1) }  // pergunta que falhou sai da conversa
        // erro no meio do trabalho: guarda o que a IA já olhou (17 rodadas se perdiam num 503) e Continuar segue dali
        fun fail(msg: String): String {
            if (contents.length() <= before + 1) { drop(); return msg }
            contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "(fui interrompido por um erro do servidor; continuo daqui)"))))
            return "$msg\n\nO que já fiz ficou guardado: toque em Continuar para seguir de onde parei."
        }
        return try {
            repeat(rounds()) {
                kotlin.coroutines.coroutineContext.ensureActive()
                shrinkOld(contents)
                fun body(think: Boolean) = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                    .put("contents", contents)
                    .apply { if (tools.isNotEmpty()) put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls))) }
                    // pensar pouco a cada passo: com o padrão, cada rodada levava até 90 s (8 min numa pergunta)
                    .apply { if (think) put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))) }
                    .toString()
                var body = body(lowThinking)
                val sent = System.currentTimeMillis()
                var (code, reply) = http("models/${Session.geminiModel.trim()}:generateContent", Session.geminiKey, body)
                noteSpeed(Session.geminiModel.trim(), code, System.currentTimeMillis() - sent)
                // modelo que não conhece o nível de pensamento (2.x): manda sem e lembra
                if (code == 400 && "think" in reply.lowercase()) {
                    lowThinking = false
                    body = body(false)
                    http("models/${Session.geminiModel.trim()}:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                }
                // servidor cheio (503/500): é passageiro, espera cada vez mais antes de mostrar erro
                for (wait in listOf(3000L, 6000L, 12_000L, 20_000L)) {
                    if (code != 503 && code != 500) break
                    kotlinx.coroutines.delay(wait)
                    http("models/${Session.geminiModel.trim()}:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                }
                // ainda cheio: tenta este pedido num outro modelo da conta (o escolhido continua o mesmo)
                if (code == 503 || code == 500) Session.geminiModels.firstOrNull { it != Session.geminiModel.trim() }?.let { m ->
                    Diag.ai("Gemini: ${Session.geminiModel} cheio (código $code); tentando esta rodada com $m")
                    http("models/$m:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                }
                // modelo saiu de linha ou nome errado: escolhe sozinho o melhor da conta e tenta de novo
                if (code == 404) geminiModels(Session.geminiKey).firstOrNull()?.let { m ->
                    Diag.add("Gemini: modelo ${Session.geminiModel} não existe mais, usando $m")
                    Session.geminiModel = m
                    http("models/$m:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                }
                // cota acabou ou chave recusada: passa para a próxima chave da lista e refaz o mesmo pedido (cada uma uma vez)
                var spare = Session.geminiKeys.size - 1
                while (spare-- > 0 && keyProblem(code, reply) && nextGeminiKey("código $code")) {
                    http("models/${Session.geminiModel.trim()}:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                }
                // cota do dia acabou neste modelo em todas as chaves: cada modelo tem a sua, segue no próximo bom
                var swaps = 0
                while (code == 429 && geminiWait(code, reply) == null && swaps++ < 3) {
                    tired[Session.geminiModel.trim()] = System.currentTimeMillis() + 3_600_000L
                    val m = pickGemini(Session.geminiModels, Session.geminiSpeed, tiredModels()) ?: break
                    Diag.ai("Gemini: cota do dia de ${Session.geminiModel} acabou; seguindo com $m")
                    onStep("🔁 Cota do ${Session.geminiModel} acabou: seguindo com $m")
                    Session.geminiModel = m
                    http("models/$m:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                    var left = Session.geminiKeys.size - 1
                    while (left-- > 0 && keyProblem(code, reply) && nextGeminiKey("código $code")) {
                        http("models/$m:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                    }
                }
                // todas as chaves no limite por minuto, ou servidor cheio: espera e segue sozinho, sem precisar de Continuar
                var waits = 0
                while (waits++ < 5) {
                    val secs = geminiWait(code, reply) ?: break
                    onStep("⏳ Gemini ${if (code == 429) "no limite em todas as chaves" else "cheio"}: espero $secs s e continuo sozinho")
                    Diag.ai("Gemini: código $code, esperando $secs s para continuar (vez $waits de 5)")
                    kotlinx.coroutines.delay(secs * 1000L)
                    http("models/${Session.geminiModel.trim()}:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                    var left = Session.geminiKeys.size - 1
                    while (left-- > 0 && keyProblem(code, reply) && nextGeminiKey("código $code")) {
                        http("models/${Session.geminiModel.trim()}:generateContent", Session.geminiKey, body).let { code = it.first; reply = it.second }
                    }
                }
                val j = runCatching { JSONObject(reply) }.getOrNull() ?: return fail("Gemini respondeu $code sem conteúdo.").also { Diag.ai("API Gemini: código $code sem JSON: ${reply.take(500)}") }
                statRounds++
                j.optJSONObject("usageMetadata")?.let { u -> statIn += u.optInt("promptTokenCount"); statOut += u.optInt("candidatesTokenCount") }
                Diag.ai("API Gemini rodada ${it + 1} (${Session.geminiModel}): código $code, tokens ${j.optJSONObject("usageMetadata")?.let { u -> "entrada ${u.optInt("promptTokenCount")}, saída ${u.optInt("candidatesTokenCount")}, pensamento ${u.optInt("thoughtsTokenCount")}" } ?: "?"}" +
                    (j.optJSONObject("error")?.let { e -> ", erro ${e.optString("status")}: ${e.optString("message").take(300)}" } ?: "") +
                    (j.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")?.let { f -> ", fim $f" } ?: ""))
                j.optJSONObject("error")?.let { e ->
                    return fail(when (code) {
                        400, 401, 403 -> "Gemini recusou a chave ou o pedido: ${e.optString("message")}"
                        429 -> if ("quota" in e.optString("message").lowercase() || e.optString("status") == "RESOURCE_EXHAUSTED")
                            "A cota grátis do Gemini acabou em ${if (Session.geminiKeys.size > 1) "todas as ${Session.geminiKeys.size} chaves" else "a chave"} " +
                                "(limite por dia ou por minuto). Espere (a diária volta à meia-noite do horário do Pacífico) ou adicione outra chave em Ajustes."
                        else "Limite do Gemini atingido (no gratuito há limite por minuto e por dia). Espere e tente de novo."
                        else -> "Gemini respondeu com erro $code: ${e.optString("message")}"
                    })
                }
                j.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { return fail("O Gemini não respondeu essa pergunta ($it).") }
                val content = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content") ?: return fail("Sem resposta.")
                // a resposta volta inteira (com a assinatura do pensamento), senão o Gemini perde o fio
                contents.put(content)
                val parts = content.optJSONArray("parts") ?: JSONArray()
                val calls = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
                if (calls.isEmpty()) {
                    return (0 until parts.length()).joinToString("\n") { parts.getJSONObject(it).optString("text") }.trim().ifEmpty { "Sem resposta." }
                }
                val answers = JSONArray()
                calls.forEach { c ->
                    val out = runTool(tools, c.optString("name"), c.optJSONObject("args") ?: JSONObject(), onStep)
                    answers.put(JSONObject().put("functionResponse", JSONObject().put("name", c.optString("name")).put("response", JSONObject().put("result", out))))
                }
                pendingImage?.also { pendingImage = null }?.let { answers.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", it))) }
                contents.put(JSONObject().put("role", "user").put("parts", answers))
            }
            "Parei depois de ${rounds()} consultas sem chegar a uma resposta. Tente uma pergunta mais específica."
        } catch (e: java.io.IOException) {
            fail("Sem conexão com o Gemini: ${e.message}")
        }
    }

    /** Média do tempo de uma rodada (as últimas pesam mais); servidor cheio conta como lento. */
    private fun noteSpeed(model: String, code: Int, ms: Long) {
        val t = when (code) { 200 -> ms; 503, 500 -> 120_000L; else -> return }
        Session.geminiSpeed[model] = Session.geminiSpeed[model]?.let { (it * 2 + t) / 3 } ?: t
    }

    /**
     * Cada rodada manda a conversa inteira de novo (chegava a 18 mil tokens e 110 s por rodada): respostas de
     * ferramentas mais antigas que as 3 últimas ficam curtas e prints antigos saem. A IA já tirou delas o que precisava.
     */
    private fun shrinkOld(contents: JSONArray) {
        var users = 0
        for (i in contents.length() - 1 downTo 0) {
            val m = contents.getJSONObject(i)
            if (m.optString("role") != "user" || ++users <= 3) continue
            val parts = m.optJSONArray("parts") ?: continue
            for (k in 0 until parts.length()) {
                val p = parts.getJSONObject(k)
                if (p.has("inlineData")) { parts.put(k, JSONObject().put("text", "(print antigo da tela)")); continue }
                val r = p.optJSONObject("functionResponse")?.optJSONObject("response") ?: continue
                val t = r.optString("result")
                if (t.length > 500) r.put("result", t.take(500) + "\n…(resposta antiga encurtada)")
            }
        }
    }

    private fun keyProblem(code: Int, reply: String) =
        code == 429 || code == 401 || code == 403 || (code == 400 && "api key" in reply.lowercase())

    /** Troca para a próxima chave do Gemini; false se só há uma. */
    private fun nextGeminiKey(why: String): Boolean {
        val n = Session.geminiKeys.size
        if (n < 2) return false
        val old = Session.geminiKeyIndex
        Session.geminiKeyIndex = (old + 1) % n
        val msg = "Gemini: chave ${old + 1} sem uso agora ($why); passando para a chave ${Session.geminiKeyIndex + 1} de $n"
        Diag.add(msg)
        Diag.ai(msg)
        return true
    }

    /** Modelos Gemini que a chave pode usar para texto, o melhor primeiro (vazio: chave errada ou sem internet). */
    fun geminiModels(key: String): List<String> {
        val (code, reply) = runCatching { http("models?pageSize=1000", key, null) }.getOrNull() ?: return emptyList()
        if (code != 200) return emptyList()
        val ms = runCatching { JSONObject(reply).optJSONArray("models") }.getOrNull() ?: return emptyList()
        return rankGemini((0 until ms.length()).map { ms.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true }
            .map { it.getString("name").removePrefix("models/") })
    }

    private fun http(path: String, key: String, body: String?): Pair<Int, String> {
        val c = URL(GEMINI + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 180_000
            c.setRequestProperty("x-goog-api-key", key.trim())  // no cabeçalho, não na URL: a URL pode ir para logs
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Ordem de escolha: estáveis antes de preview/experimental; Flash (rápido, com nível grátis) antes de Pro e de Lite;
 * versão mais nova primeiro. Só modelos de texto "gemini-…" (fora imagem, áudio, embedding).
 */
internal fun rankGemini(names: List<String>): List<String> {
    val skip = listOf("image", "tts", "audio", "live", "embedding", "vision", "robotics", "computer")
    fun version(n: String) = Regex("""gemini-(\d+(?:\.\d+)?)""").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    fun tier(n: String) = when {
        "-flash-lite" in n -> 2
        "-flash" in n -> 0
        "-pro" in n -> 1
        else -> 3
    }
    return names.filter { n -> n.startsWith("gemini-") && skip.none { it in n } }.distinct()
        // primeiro o tipo (Flash antes de Pro e de Lite), depois estável antes de preview: um Flash preview acerta mais que um Lite
        .sortedWith(compareBy<String>(::tier, { n -> listOf("preview", "exp").any { it in n } }, { -version(it) }, { it.length }))
}

/**
 * Quantos segundos esperar antes de refazer um pedido ao Gemini sozinho; null: não adianta esperar.
 * Limite por minuto (429) volta logo: usa o retryDelay que o Gemini manda, ou 60 s. Cota do dia não volta hoje.
 */
internal fun geminiWait(code: Int, reply: String): Int? = when {
    code == 503 || code == 500 -> 30
    code != 429 || "perday" in reply.lowercase() -> null
    else -> (Regex(""""retryDelay"\s*:\s*"(\d+)""").find(reply)?.groupValues?.get(1)?.toIntOrNull() ?: 60).coerceIn(5, 90)
}

/**
 * Modelo para uma conversa nova: entre os 3 melhores da conta (sem Lite, que erra mais), o de rodada mais rápida.
 * Sem medida conta como 30 s: um modelo ainda não usado é experimentado quando o atual está mais lento que isso.
 */
internal fun pickGemini(ranked: List<String>, speed: Map<String, Long>, skip: Set<String> = emptySet()): String? =
    ranked.filter { "-lite" !in it && it !in skip }.take(3).minByOrNull { speed[it] ?: 30_000L }

private const val CLAUDE_BAD_KEY = "Chave da API inválida. Confira em Ajustes → Assistente (IA)."
