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

/**
 * Assistente de IA (Claude): uma pergunta por vez, com o contexto que a tela mandar (classes, ficha ou relatório).
 * Só roda com a chave da API que você colocou em Ajustes; nada sai do aparelho sem você tocar no botão.
 */
object Assistant {
    private const val SYSTEM = """Você é o assistente do Tinker, um editor de memória para Android usado em jogos single-player/offline.
Responda em português do Brasil, curto e direto, com passos que a pessoa faz no próprio Tinker.
O Tinker tem: busca por valor (Auto, Dword, Float, Double, cifrados 🔒, faixas 10~20), refino, busca sem valor (↑ ↓ = ≠),
ficha do objeto com nomes IL2CPP (Memória → Ficha → Ver todas as classes do jogo), salvos com congelar ❄, ponteiros 🎯, receitas e busca AOB.
Ao sugerir classes ou campos, use exatamente os nomes que aparecem no contexto e diga por que cada um parece guardar o valor.
Se o jogo for online e o servidor confere o valor, avise que editar não vai valer."""

    /** Faz a pergunta; erros viram texto para a tela (chave errada, sem internet, limite). */
    fun ask(key: String, context: String, question: String): String {
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
}
