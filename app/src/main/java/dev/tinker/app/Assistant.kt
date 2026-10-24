package dev.tinker.app

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Assistente de IA (Claude ou Gemini, escolhido em Ajustes): uma pergunta por vez, com o contexto que a tela mandar (classes, ficha ou relatório).
 * Só roda com a chave da API que você colocou em Ajustes; nada sai do aparelho sem você tocar no botão.
 */
object Assistant {
    private const val SYSTEM = """Você é o assistente do Tinker, um editor de memória para Android usado em jogos single-player/offline.
Responda em português do Brasil, curto e direto, com passos que a pessoa faz no próprio Tinker.
O Tinker tem: busca por valor (Auto, Dword, Float, Double, cifrados 🔒, faixas 10~20), refino, busca sem valor (↑ ↓ = ≠),
ficha do objeto com nomes IL2CPP (Memória → Ficha → Ver todas as classes do jogo), salvos com congelar ❄, ponteiros 🎯, receitas e busca AOB.
Ao sugerir classes ou campos, use exatamente os nomes que aparecem no contexto e diga por que cada um parece guardar o valor.
Se o jogo for online e o servidor confere o valor, avise que editar não vai valer."""

    /** O provedor escolhido tem chave? */
    fun ready() = if (Session.aiProvider == "gemini") Session.geminiKey.isNotBlank() else Session.aiKey.isNotBlank()

    fun ask(context: String, question: String): String =
        if (Session.aiProvider == "gemini") askGemini(Session.geminiKey, context, question) else askClaude(Session.aiKey, context, question)

    /** Claude pelo SDK oficial; erros viram texto para a tela (chave errada, sem internet, limite). */
    private fun askClaude(key: String, context: String, question: String): String {
        if (key.isBlank()) return "Coloque a chave da API do Claude em Ajustes → Assistente (IA)."
        val client = AnthropicOkHttpClient.builder().apiKey(key.trim()).build()
        return try {
            val params = MessageCreateParams.builder()
                .model("claude-opus-5-5")
                .maxTokens(16000L)
                .system(SYSTEM)
                // o contexto (lista de classes, relatório) repete entre perguntas: fica em cache e sai mais barato
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .addUserMessage("$context\n\n=== Pergunta ===\n$question")
                // recusa do filtro de segurança: o servidor tenta de novo noutro modelo em vez de parar
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val r = client.beta().messages().create(params)
            if (r.stopReason().orElse(null) == BetaStopReason.REFUSAL) return "O assistente não respondeu essa pergunta."
            r.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim().ifEmpty { "Sem resposta." }
        } catch (e: UnauthorizedException) {
            "Chave da API inválida. Confira em Ajustes → Assistente (IA)."
        } catch (e: RateLimitException) {
            "Limite de uso da API atingido. Espere um pouco e tente de novo."
        } catch (e: AnthropicServiceException) {
            "A API respondeu com erro ${e.statusCode()}: ${e.message}"
        } catch (e: AnthropicIoException) {
            "Sem conexão com a API: ${e.message}"
        } finally {
            client.close()
        }
    }

    // Gemini: HTTP direto (sem biblioteca); o Google troca os nomes dos modelos, então o nome fica em Ajustes
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/"

    private fun askGemini(key: String, context: String, question: String): String {
        if (key.isBlank()) return "Coloque a chave do Gemini em Ajustes → Assistente (IA)."
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "$context\n\n=== Pergunta ===\n$question")))))
            .toString()
        return try {
            var (code, reply) = http("models/${Session.geminiModel.trim()}:generateContent", key, body)
            // modelo saiu de linha ou nome errado: escolhe sozinho o Flash mais novo da conta e tenta de novo
            if (code == 404) newestFlash(key)?.let { m ->
                Diag.add("Gemini: modelo ${Session.geminiModel} não existe mais, usando $m")
                Session.geminiModel = m
                http("models/$m:generateContent", key, body).let { code = it.first; reply = it.second }
            }
            val j = runCatching { JSONObject(reply) }.getOrNull() ?: return "Gemini respondeu $code sem conteúdo."
            j.optJSONObject("error")?.let { e ->
                return when (code) {
                    400, 401, 403 -> "Gemini recusou a chave ou o pedido: ${e.optString("message")}"
                    429 -> "Limite do Gemini atingido (no gratuito há limite por minuto e por dia). Espere e tente de novo."
                    else -> "Gemini respondeu com erro $code: ${e.optString("message")}"
                }
            }
            j.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { return "O Gemini não respondeu essa pergunta ($it)." }
            val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            (0 until (parts?.length() ?: 0)).joinToString("\n") { parts!!.getJSONObject(it).optString("text") }.trim().ifEmpty { "Sem resposta." }
        } catch (e: java.io.IOException) {
            "Sem conexão com o Gemini: ${e.message}"
        }
    }

    /** O "gemini-N-flash" de maior versão que a chave pode usar. */
    private fun newestFlash(key: String): String? {
        val (code, reply) = http("models?pageSize=1000", key, null)
        if (code != 200) return null
        val ms = runCatching { JSONObject(reply).optJSONArray("models") }.getOrNull() ?: return null
        return (0 until ms.length()).map { ms.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true }
            .map { it.getString("name").removePrefix("models/") }
            .mapNotNull { n -> Regex("""gemini-(\d+(?:\.\d+)?)-flash""").matchEntire(n)?.let { n to it.groupValues[1].toDouble() } }
            .maxByOrNull { it.second }?.first
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
