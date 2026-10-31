package dev.tinker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Um "jogo" de mentira: Dwords na memória e os comandos do daemon que o GgLua usa. */
private class FakeGame(val dir: File) : GgHost {
    val mem = HashMap<Long, Long>()
    var results = listOf<Long>()
    val saved = mutableListOf<LuaItem>()
    val answers = ArrayDeque<String>()  // a próxima resposta: texto de uma linha (choice), o que digitar (prompt)
    val asked = mutableListOf<String>()
    val sent = mutableListOf<String>()
    override val pkg = "com.evrika.miner.camp"
    override val dataDir = dir

    /** Abre o jogo de novo: a estrutura do valor vai para outro endereço, com os mesmos vizinhos; mais 4 cópias soltas do valor. */
    fun open(base: Long, value: Long): Long {
        mem.clear()
        for (i in 0 until 64) mem[base + 4 * i] = 1000L + 37 * i  // vizinhos fixos
        mem[base + 0x40] = 0x7a00_0000L + base % 4096 * 16        // ponteiro: muda a cada abertura
        val at = base + 0x60
        mem[at] = value
        for (k in 1..4) mem[base + 0x10000 * k] = value
        results = listOf(at)
        return at
    }

    override fun send(cmd: String): Pair<String, List<String>> {
        sent += cmd
        val w = cmd.split(' ')
        fun line(a: Long) = "${a.toString(16)} i32 A ${mem[a] ?: 0}"
        return when (w[0]) {
            "list" -> results.size.toString() to results.take(w[1].toInt()).map(::line)
            "search" -> { results = mem.filterValues { it == w[2].toLong() }.keys.sorted(); results.size.toString() to emptyList() }
            "refine" -> { results = results.filter { mem[it] == w[1].toLong() }; results.size.toString() to emptyList() }
            "read" -> (mem[w[1].toLong(16)] ?: 0).toString() to emptyList()
            "dump" -> "" to (0 until w[3].toInt()).map { i -> w[1].toLong(16) + 4 * i }.map { "${it.toString(16)} ${mem[it] ?: 0}" }
            "write" -> { mem[w[1].toLong(16)] = w[3].toLong(); "" to emptyList() }
            "ranges" -> "" to emptyList()
            else -> throw java.io.IOException("comando desconhecido: $cmd")
        }
    }

    override fun ask(q: LuaAsk): Any? {
        asked += q.toString()
        return when (q) {
            is LuaAsk.Alert -> 1
            is LuaAsk.Choice -> answers.removeFirst().let { want -> q.items.indexOfFirst { want in it }.takeIf { it >= 0 }?.plus(1) ?: error("sem \"$want\" em ${q.items}") }
            is LuaAsk.Prompt -> listOf(answers.removeFirst())
        }
    }

    override fun toast(text: String) {}
    override fun copy(text: String) {}
    override fun listItems() = saved.toList()
    override fun addItems(items: List<LuaItem>) { saved += items }
    override fun removeItems(items: List<LuaItem>) { saved.removeAll { s -> items.any { it.address == s.address } } }
    override fun http(url: String, headers: Map<String, String>, body: String?) = 200 to "ok"
    override fun log(line: String) { asked += "print: $line" }
}

class GgLuaTest {
    private fun game() = FakeGame(kotlin.io.path.createTempDirectory("gglua").toFile())

    @Test
    fun ggBasicoBuscaLeEscreveEPerguntas() {
        val g = game()
        val at = g.open(0x7100_0000, 80)
        g.answers += listOf("Dois", "joias")
        val err = GgLua(g).run("""
            gg.searchNumber("80", gg.TYPE_DWORD)
            assert(gg.getResultsCount() == 5, "contagem " .. gg.getResultsCount())
            gg.refineNumber("80", gg.TYPE_DWORD)
            local r = gg.getResults(10)
            assert(r[1].flags == gg.TYPE_DWORD and r[1].value == 80)
            local v = gg.getValues({ { address = r[1].address - 4, flags = gg.TYPE_DWORD }, { address = r[1].address + 4, flags = gg.TYPE_DWORD } })
            assert(v[1].value == 1000 + 37 * 23 and v[2].value == 1000 + 37 * 25, "vizinhos " .. tostring(v[1].value))
            gg.setValues({ { address = $at, flags = gg.TYPE_DWORD, value = 999 } })
            assert(gg.choice({ "Um", "Dois" }, nil, "?") == 2)
            assert(gg.prompt({ "nome" }, { "" }, { "text" })[1] == "joias")
            local f = io.open(gg.EXT_STORAGE .. "/x.txt", "w"); f:write("oi"); f:close()
            assert(not pcall(io.open, "/etc/passwd", "r"), "abriu arquivo fora da pasta")
            assert(os.execute == nil and luajava == nil and dofile == nil)
            assert(type(gg.makeRequest("https://example.com", {}, "x")) == "string", "internet fora da planilha")
            assert(string.format("%x", 0x71000060) == "71000060")
            os.exit()
            error("não parou no os.exit")
        """.trimIndent(), "teste.lua")
        assertNull(err)
        assertEquals(999L, g.mem[at])
        assertEquals("oi", File(g.dir, "x.txt").readText())
    }

    @Test
    fun funcaoQueFaltaDizONome() {
        val err = GgLua(game()).run("gg.processKill()", "x.lua")
        assertTrue(err, err!!.contains("gg.processKill ainda não existe no Tinker"))
    }

    /** O nosso assinatura-auto.lua inteiro: 3 testes de vizinhos (o jogo reabre em outro endereço) e gerar a assinatura. */
    @Test
    fun assinaturaAutoRodaNoTinker() {
        val code = File("../tools/gg/assinatura-auto.lua").readText()
        val g = game()
        listOf(0x7100_0000L, 0x7300_0000L, 0x7500_0000L).forEachIndexed { i, base ->
            g.open(base, 80L + i)
            g.answers += "Salvar vizinhos"
            assertNull(g.asked.joinToString("\n"), GgLua(g).run(code, "assinatura-auto.lua"))
        }
        val at = g.open(0x7700_0000, 95)
        g.answers += listOf("Gerar assinatura", "Joias", "")  // nome; link da planilha vazio = não manda
        assertNull(g.asked.joinToString("\n"), GgLua(g).run(code, "assinatura-auto.lua"))
        assertEquals(g.asked.joinToString("\n"), listOf("Joias" to at), g.saved.map { it.name to it.address })
        assertTrue(g.asked.last(), g.asked.last().contains("valor achado"))
    }
}
