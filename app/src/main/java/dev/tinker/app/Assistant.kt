package dev.tinker.app

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
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

/** Conversa com a IA: guarda as mensagens para "Continuar" seguir de onde parou (só cresce, nunca reescreve). */
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
Ao sugerir classes ou campos, use exatamente os nomes que aparecem no contexto e diga por que cada um parece guardar o valor.
Se o jogo for online e o servidor confere o valor, avise que editar não vai valer.
Você tem ferramentas que olham a memória do jogo agora: procurar classes, abrir os objetos de uma classe com os valores atuais,
ver a ficha de um endereço, buscar e refinar valores. Use-as antes de responder: não peça para a pessoa conferir o que você
mesmo pode ver. Se a pessoa disser um valor que vê no jogo (ex.: tenho 1250 de ouro), busque esse valor e veja os campos dos
resultados. Valores que andam juntos (ouro, gema, nível) dá para buscar em grupo: 1250;30;7::128.
Você não edita nada direto: quando tiver certeza do campo, use propor_edicao com o endereço e o tipo exatos (aparece um botão
Aplicar para a pessoa) e explique na resposta qual classe e campo é. Se não tiver certeza, diga o que falta para confirmar.
Quando precisar que o valor mude no jogo (para refinar ou comparar), peça para a pessoa fazer isso e tocar em Continuar: a conversa
segue de onde parou. Sem saber o número (barra de vida), use busca_sem_valor e depois comparar.
Use ver_salvos para saber o que já foi achado e se os ponteiros voltaram. Quando descobrir algo que vale para a próxima vez
(classe e campo de um recurso, tipo do valor, se é cifrado), use anotar. Para deixar várias edições prontas para toda vez que o
jogo abrir, use propor_receita com os salvos."""

    private const val MAX_ROUNDS = 12  // chamadas de ferramenta por pergunta, para não ficar em volta para sempre

    /** O provedor escolhido tem chave? */
    fun ready() = if (Session.aiProvider == "gemini") Session.geminiKey.isNotBlank() else Session.aiKey.isNotBlank()

    /** Na primeira mensagem vai o contexto da tela; nas seguintes (Continuar), só o que a pessoa disse. */
    suspend fun ask(chat: AiChat, context: suspend () -> String, question: String, tools: List<AiTool>, onStep: (String) -> Unit): String {
        if (chat.provider != Session.aiProvider) { chat.reset(); chat.provider = Session.aiProvider }
        val prompt = if (chat.started) question else "${context()}\n\n=== Pergunta ===\n$question"
        val answer = if (Session.aiProvider == "gemini") askGemini(Session.geminiKey, chat, prompt, tools, onStep)
        else askClaude(Session.aiKey, chat, prompt, tools, onStep)
        return answer
    }

    /** Roda a ferramenta pedida; erro vira texto para a IA (ela pode tentar de outro jeito). */
    private suspend fun runTool(tools: List<AiTool>, name: String, args: JSONObject, onStep: (String) -> Unit): String {
        val t = tools.firstOrNull { it.name == name } ?: return "Ferramenta $name não existe."
        val shown = t.params.keys.mapNotNull { k -> args.optString(k).takeIf { it.isNotEmpty() } }.joinToString(" ")
        onStep("🔧 $name $shown".trim())
        Diag.add("Assistente usou $name $shown")
        return runCatching { t.run(args) }.getOrElse { "Erro: ${it.message}" }.take(20_000)
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
            repeat(MAX_ROUNDS) {
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
                val r = client.beta().messages().create(params)
                if (r.stopReason().orElse(null) == BetaStopReason.REFUSAL) return "O assistente não respondeu essa pergunta."
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
                messages += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results).build()
            }
            "Parei depois de $MAX_ROUNDS consultas sem chegar a uma resposta. Tente uma pergunta mais específica."
        } catch (e: UnauthorizedException) {
            while (messages.size > before) messages.removeAt(messages.lastIndex)  // a pergunta que falhou não fica na conversa
            "Chave da API inválida. Confira em Ajustes → Assistente (IA)."
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
        return try {
            repeat(MAX_ROUNDS) {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                    .put("contents", contents)
                    .apply { if (tools.isNotEmpty()) put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls))) }
                    .toString()
                var (code, reply) = http("models/${Session.geminiModel.trim()}:generateContent", key, body)
                // modelo saiu de linha ou nome errado: escolhe sozinho o melhor da conta e tenta de novo
                if (code == 404) geminiModels(key).firstOrNull()?.let { m ->
                    Diag.add("Gemini: modelo ${Session.geminiModel} não existe mais, usando $m")
                    Session.geminiModel = m
                    http("models/$m:generateContent", key, body).let { code = it.first; reply = it.second }
                }
                val j = runCatching { JSONObject(reply) }.getOrNull() ?: return "Gemini respondeu $code sem conteúdo.".also { drop() }
                j.optJSONObject("error")?.let { e ->
                    drop()
                    return when (code) {
                        400, 401, 403 -> "Gemini recusou a chave ou o pedido: ${e.optString("message")}"
                        429 -> "Limite do Gemini atingido (no gratuito há limite por minuto e por dia). Espere e tente de novo."
                        else -> "Gemini respondeu com erro $code: ${e.optString("message")}"
                    }
                }
                j.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { drop(); return "O Gemini não respondeu essa pergunta ($it)." }
                val content = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content") ?: return "Sem resposta.".also { drop() }
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
                contents.put(JSONObject().put("role", "user").put("parts", answers))
            }
            "Parei depois de $MAX_ROUNDS consultas sem chegar a uma resposta. Tente uma pergunta mais específica."
        } catch (e: java.io.IOException) {
            drop()
            "Sem conexão com o Gemini: ${e.message}"
        }
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
        .sortedWith(compareBy<String>({ n -> listOf("preview", "exp").any { it in n } }, ::tier, { -version(it) }, { it.length }))
}
