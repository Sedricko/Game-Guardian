package dev.tinker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LearnTest {
    private val all = SEED + TECHNIQUES

    @Test fun jeitoQueEnganouPerdeParaOProvado() {
        var k = emptyList<Know>()
        repeat(4) { k = record(k, "jogo.a", "Cristais", "i32", ok = true, ms = 0, at = "") }  // cópia do número da tela
        repeat(3) { k = record(k, "jogo.a", "Cristais", "i32", ok = false, ms = 0, at = "") }  // o teste de escrita reprovou
        k = record(k, "jogo.a", "Cristais", "i32*10", ok = true, ms = 0, at = "")
        assertEquals("i32*10", pickType(all + k, "jogo.a", "Cristais").first)
    }

    @Test fun outroIdiomaUsaOQueSabeDoMesmoValor() {
        val k = record(emptyList(), "jogo.a", "Cristais", "i32*10", ok = true, ms = 0, at = "")
        assertEquals("i32*10", pickType(all + k, "jogo.a", "Crystals").first)
    }

    @Test fun disfarceAprendidoVoltaComOTipo() {
        val k = record(emptyList(), "jogo.a", "Cristais", "i32*10", ok = true, ms = 0, at = "")
        assertEquals("i32*10", pickType(all + k, "jogo.a", "cristais").first)
    }

    @Test fun nomeSemEmojiNemPontuacao() {
        assertEquals("velocidade", learnKey("⏩ Velocidade:"))
        assertEquals("pocoes", learnKey("Poções"))
    }

    @Test fun usaOQueOClaudeEnsinou() {
        assertEquals("i32", pickType(all, "jogo.a", "Moedas:").first)
        assertEquals("f32", pickType(all, "jogo.a", "Vida").first)
        assertEquals(null, pickType(all, "jogo.a", "Xyzzy").first)
    }

    @Test fun aprendeComOAcertoEComOErro() {
        // vida no jogo nativo é Dword: errou como Float, acertou como Dword
        var k = record(emptyList(), "jogo.a", "Vida", "f32", ok = false, ms = 0, at = "")
        assertEquals("i32", pickType(all + k, "jogo.a", "Vida").first)  // o Float que errou sai da frente
        k = record(k, "jogo.a", "Vida", "i32", ok = true, ms = 4000, at = "")
        assertEquals("i32", pickType(all + k, "jogo.a", "vida").first)
        // outro jogo aproveita o que este acertou
        assertEquals("i32", pickType(all + k, "jogo.b", "Vida").first)
        assertEquals(4000L, k.first { it.type == "i32" }.ms)
    }

    @Test fun juntarNaoSomaEmDobro() {
        val a = record(emptyList(), "jogo.a", "Ouro", "i32", ok = true, ms = 10, at = "")
        val b = record(a, "jogo.a", "Ouro", "i32", ok = true, ms = 10, at = "")
        assertEquals(2, mergeKnow(a, b).single().wins)
        assertEquals(2, mergeKnow(b, b).single().wins)
    }

    @Test fun daIASoGuardaOQueDeuCerto() {
        assertEquals(emptyList<Know>(), record(emptyList(), "jogo.a", "Ouro", "f32", ok = false, ms = 0, at = "", from = "IA"))
        val k = record(emptyList(), "jogo.a", "Ouro", "i64", ok = true, ms = 9, at = "", from = "IA", tips = "Qword")
        assertEquals("IA", k.single().from)
        assertEquals("i64", pickType(all + k, "jogo.a", "Ouro").first)
    }

    @Test fun campoDoSaveNaoViraTipoDeBusca() {
        val k = record(emptyList(), "jogo.a", "gold", "save", ok = true, ms = 0, at = "", tips = "arquivo shared_prefs/x.xml, campo gold")
        assertEquals("i32", pickType(all + k, "jogo.a", "gold").first)
    }

    // o mapa do jogo em português: gold = moeda, life = vida; e as funções com o que fixar
    @Test fun mapaDoJogoTraduzOsNomes() {
        assertEquals("moeda (dinheiro do jogo)", fieldMeaning("gold")?.second)
        assertEquals("moeda (dinheiro do jogo)", fieldMeaning("m_money")?.second)
        assertEquals("vida", fieldMeaning("life")?.second)
        assertEquals("vida", fieldMeaning("<Health>k__BackingField")?.second)
        assertEquals(null, fieldMeaning("transform"))
        assertEquals("tira vida", methodMeaning("TakeDamage")?.first)
        assertEquals("true", methodMeaning("CanAfford")?.second)
        assertEquals("0", methodMeaning("GetPrice")?.second)
        assertEquals("", methodMeaning("AddGold")?.third)
        assertEquals(null, methodMeaning("Update"))
    }
}
