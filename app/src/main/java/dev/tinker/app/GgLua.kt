package dev.tinker.app

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseIoLib
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.lib.jse.JseOsLib
import java.io.File
import java.io.OutputStream
import java.io.PrintStream

/** Uma pergunta do script para a pessoa (gg.alert, gg.choice, gg.prompt): a tela mostra e devolve a resposta. */
sealed class LuaAsk {
    /** Resposta: 1..n (o botão), 0 = fechou. */
    data class Alert(val text: String, val buttons: List<String>) : LuaAsk()
    /** Resposta: 1..n (a linha), null = fechou. */
    data class Choice(val items: List<String>, val title: String) : LuaAsk()
    /** Resposta: List<String> (checkbox = "true"/"false"), null = fechou. */
    data class Prompt(val labels: List<String>, val defaults: List<String>, val kinds: List<String>) : LuaAsk()
}

/** Um item da lista salva do GG (gg.getListItems / gg.addListItems). */
data class LuaItem(val address: Long, val flags: Int, val name: String, val freeze: Boolean, val value: String)

/** O que o script precisa do Tinker. Tudo bloqueante: o script roda numa thread só dele. */
interface GgHost {
    val pkg: String
    /** A única pasta que o script pode ler e escrever (o gg.EXT_STORAGE dele). */
    val dataDir: File
    /** Comando cru ao daemon: (status depois do "ok", linhas). Erro do daemon vira exceção. */
    fun send(cmd: String): Pair<String, List<String>>
    fun ask(q: LuaAsk): Any?
    fun toast(text: String)
    fun copy(text: String)
    fun listItems(): List<LuaItem>
    fun addItems(items: List<LuaItem>)
    fun removeItems(items: List<LuaItem>)
    fun http(url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
    fun log(line: String)
}

/** O script terminou pelo os.exit() ou pelo botão Parar: não é erro. */
class LuaStop : RuntimeException("parado")

/**
 * 📜 Scripts do GameGuardian no Tinker: Lua 5.2 (LuaJ, a mesma do GG) com os gg.* mais usados ligados ao daemon.
 * Trancado: arquivos só na pasta do script, internet só para a planilha (e com a pessoa permitindo), sem os.execute,
 * luajava, dofile, require. shortcut: um laço Lua sem nenhum gg.* não para no Parar; precisaria do DebugLib (hook).
 */
class GgLua(private val host: GgHost) {
    @Volatile var stopped = false
    /** "Abrir o menu do script": o próximo gg.isVisible() devolve true (scripts de menu em laço esperam o ícone do GG). */
    @Volatile var menuTap = false
    private var loaded: List<Triple<Long, Int, String>>? = null  // gg.loadResults: (endereço, flags, região)
    private var cleared = false
    private var ranges = -1
    private var netOk: Boolean? = null
    private var scriptName = ""

    /** Roda o código; devolve null se acabou bem ou a mensagem do erro (com a linha do script). */
    fun run(code: String, name: String): String? {
        scriptName = name
        return try {
            val g = globals()
            g.loader.load(noJumpClose(g.compilePrototype(java.io.StringReader(code), "@$name")), "@$name", g).call()
            // o GG deixa o gg.loadResults na tela de resultados; no Tinker eles vão para Salvos (lá dá para editar e congelar)
            loaded?.takeIf { it.size in 1..100 }?.let { l ->
                val have = host.listItems().map { it.address }.toSet()
                l.filter { it.first !in have }.takeIf { it.isNotEmpty() }?.let { n -> host.addItems(n.map { LuaItem(it.first, it.second, "resultado de $name", false, "") }) }
            }
            null
        } catch (e: LuaStop) {
            null
        } catch (e: LuaError) {
            if (e.cause is LuaStop) null else e.message ?: "erro no script"
        } finally {
            runCatching { if (ranges != -1) host.send("ranges all") }
        }
    }

    private fun check() { if (stopped) throw LuaStop() }

    /**
     * LuaJ 3.0.1 quebra (NullPointerException) num goto para trás que passa por um local ("goto XREGION" do SpeedHackFinder):
     * o JMP pede para fechar upvalues e a função sem funções internas não tem a lista deles. Sem funções internas não há
     * upvalue para fechar, então tirar esse pedido não muda nada no script.
     */
    private fun noJumpClose(p: org.luaj.vm2.Prototype): org.luaj.vm2.Prototype {
        if (p.p.isEmpty()) p.code.forEachIndexed { i, c -> if (c and 0x3f == org.luaj.vm2.Lua.OP_JMP) p.code[i] = c and (0xff shl 6).inv() }
        p.p.forEach(::noJumpClose)
        return p
    }

    private fun globals(): Globals {
        val g = Globals()
        listOf(JseBaseLib(), PackageLib(), Bit32Lib(), TableLib(), StringLib(), CoroutineLib(), JseMathLib(), JseIoLib(), JseOsLib()).forEach { g.load(it) }
        LoadState.install(g)
        LuaC.install(g)
        g.STDOUT = PrintStream(object : OutputStream() {
            val buf = java.io.ByteArrayOutputStream()
            override fun write(b: Int) { if (b == '\n'.code) { host.log(buf.toString("UTF-8")); buf.reset() } else buf.write(b) }
        }, true, "UTF-8")
        listOf("dofile", "loadfile", "require", "luajava", "debug").forEach { g.set(it, LuaValue.NIL) }
        val io = g.get("io")
        val open = io.get("open")
        io.set("open", fn { a -> open.invoke(LuaValue.valueOf(inside(a.checkjstring(1))), a.arg(2)) })
        listOf("lines", "input", "output", "popen", "tmpfile").forEach { io.set(it, LuaValue.NIL) }
        val os = g.get("os")
        val remove = os.get("remove")
        val rename = os.get("rename")
        os.set("remove", fn { a -> remove.invoke(LuaValue.valueOf(inside(a.checkjstring(1)))) })
        os.set("rename", fn { a -> rename.invoke(LuaValue.valueOf(inside(a.checkjstring(1))), LuaValue.valueOf(inside(a.checkjstring(2)))) })
        os.set("exit", fn { throw LuaStop() })
        listOf("execute", "getenv", "tmpname", "setlocale").forEach { os.set(it, LuaValue.NIL) }
        g.set("gg", gg())
        return g
    }

    /** Caminho dentro da pasta do script (relativo vira de dentro dela); fora dela é recusado. */
    private fun inside(path: String): String {
        val base = host.dataDir.canonicalFile
        val f = (if (File(path).isAbsolute) File(path) else File(base, path)).canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) throw LuaError("o script só pode usar arquivos da pasta dele (${base.path}): $path")
        return f.path
    }

    private fun fn(body: (Varargs) -> Varargs) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs { check(); return body(args) }
    }

    private fun num(v: Long) = LuaValue.valueOf(v.toDouble())
    private fun value(text: String): LuaValue = text.toLongOrNull()?.let(::num) ?: text.toDoubleOrNull()?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL
    private fun plainNumber(v: LuaValue): String = if (v.isnumber()) v.todouble().let { d -> if (d == Math.floor(d) && Math.abs(d) < 9.2e18) d.toLong().toString() else d.toString() } else v.tojstring()

    private fun hitTable(addr: Long, flags: Int, value: String) = LuaTable().apply {
        set("address", num(addr)); set("flags", flags); set("value", value(value))
    }

    private fun items(t: LuaValue): List<LuaTable> = (1..t.length()).map { t.get(it).checktable() }

    private fun gg(): LuaTable {
        val gg = LuaTable()
        TYPE_FLAGS.forEach { (k, v) -> gg.set(k, v) }
        REGION_FLAGS.forEach { (k, v) -> gg.set(k, v) }
        SIGNS.forEach { (k, v) -> gg.set(k, v) }
        val dir = host.dataDir.apply { mkdirs() }.path
        listOf("EXT_STORAGE", "EXT_FILES_DIR", "EXT_CACHE_DIR", "FILES_DIR", "CACHE_DIR").forEach { gg.set(it, dir) }
        gg.set("VERSION", "101.1")
        gg.set("VERSION_INT", 16142)
        gg.set("BUILD", 16142)
        gg.set("PACKAGE", "dev.tinker.app")

        gg.set("getTargetPackage", fn { LuaValue.valueOf(host.pkg) })
        gg.set("getTargetInfo", fn { LuaTable().apply { set("packageName", host.pkg) } })
        gg.set("getFile", fn { LuaValue.valueOf(File(host.dataDir, scriptName).path) })
        gg.set("isVisible", fn { LuaValue.valueOf(menuTap.also { menuTap = false }) })
        gg.set("setVisible", fn { LuaValue.NONE })
        gg.set("sleep", fn { a ->
            var left = a.optlong(1, 0)
            while (left > 0) { check(); Thread.sleep(minOf(left, 200)); left -= 200 }
            LuaValue.NONE
        })
        gg.set("toast", fn { a -> host.toast(a.checkjstring(1)); LuaValue.NONE })
        gg.set("copyText", fn { a -> host.copy(a.checkjstring(1)); LuaValue.NONE })

        // ---- perguntas ----
        gg.set("alert", fn { a ->
            val buttons = listOf(a.optjstring(2, "OK"), a.optjstring(3, null), a.optjstring(4, null)).filterNotNull()
            LuaValue.valueOf((host.ask(LuaAsk.Alert(a.checkjstring(1), buttons)) as? Int ?: 0).also { check() })
        })
        gg.set("choice", fn { a ->
            val t = a.checktable(1)
            val keys = keysOf(t)
            val i = host.ask(LuaAsk.Choice(keys.map { t.get(it).tojstring() }, a.optjstring(3, ""))) as? Int
            check()
            if (i == null || i !in 1..keys.size) LuaValue.NIL else keys[i - 1]
        })
        // varias opcoes de uma vez: as mesmas caixinhas do prompt; devolve {[chave]=true} das marcadas
        gg.set("multiChoice", fn { a ->
            val t = a.checktable(1)
            val sel = a.opttable(2, LuaTable())
            val keys = keysOf(t)
            @Suppress("UNCHECKED_CAST")
            val r = host.ask(LuaAsk.Prompt(keys.map { t.get(it).tojstring() }, keys.map { sel.get(it).toboolean().toString() }, keys.map { "checkbox" })) as? List<String>
            check()
            if (r == null) LuaValue.NIL else LuaTable().apply { keys.forEachIndexed { i, k -> if (r.getOrNull(i) == "true") set(k, LuaValue.TRUE) } }
        })
        gg.set("prompt", fn { a ->
            val p = a.checktable(1)
            val d = a.opttable(2, LuaTable())
            val k = a.opttable(3, LuaTable())
            val keys = keysOf(p)
            val kinds = keys.map { k.get(it).optjstring("text") }
            @Suppress("UNCHECKED_CAST")
            val r = host.ask(LuaAsk.Prompt(keys.map { p.get(it).tojstring() }, keys.map { d.get(it).let { v -> if (v.isnil()) "" else v.tojstring() } }, kinds)) as? List<String>
            check()
            if (r == null) LuaValue.NIL else LuaTable().apply {
                keys.forEachIndexed { i, key -> set(key, if (kinds[i] == "checkbox") LuaValue.valueOf(r[i] == "true") else LuaValue.valueOf(r[i])) }
            }
        })

        // ---- busca ----
        gg.set("setRanges", fn { a ->
            ranges = a.checkint(1)
            val letters = REGION_LETTERS.filter { (bit, _) -> ranges and bit != 0 }.map { it.second }.distinct()
            host.send(if (ranges == -1 || letters.isEmpty()) "ranges all" else "ranges ${letters.joinToString(",")}")
            LuaValue.NONE
        })
        gg.set("getRanges", fn { LuaValue.valueOf(ranges) })
        gg.set("searchNumber", fn { a ->
            val types = typesOf(a.optint(2, TYPE_AUTO))
            val text = a.checkjstring(1).trim()
            loaded = null
            cleared = false
            if (';' in text) {
                val dist = text.substringAfter("::", text.substringAfter(':', "")).trim().ifEmpty { "512" }
                host.send("group $types ${text.substringBefore(':').split(';').joinToString(";") { ggNumber(it) }} $dist")
            } else host.send("search $types ${ggNumber(text)}")
            LuaValue.TRUE
        })
        gg.set("refineNumber", fn { a ->
            val text = a.checkjstring(1).trim()
            if (a.optint(4, SIGN_EQUAL) != SIGN_EQUAL) throw LuaError("gg.refineNumber: no Tinker só com SIGN_EQUAL por enquanto")
            val l = loaded
            if (l != null) {
                val now = readValues(l.map { Pair(it.first, it.second) })
                val want = ggNumber(text).toDoubleOrNull()
                loaded = l.filterIndexed { i, _ -> now[i]?.toDoubleOrNull() == want }
            } else if (!cleared) host.send("refine ${ggNumber(text)}")
            LuaValue.TRUE
        })
        gg.set("getResultsCount", fn {
            LuaValue.valueOf(when { cleared -> 0.0; loaded != null -> loaded!!.size.toDouble(); else -> host.send("list 0").first.toDoubleOrNull() ?: 0.0 })
        })
        gg.set("getResults", fn { a ->
            val max = a.checkint(1)
            val skip = a.optint(2, 0)
            val out = LuaTable()
            when {
                cleared -> {}
                loaded != null -> {
                    val l = loaded!!.drop(skip).take(max)
                    val v = readValues(l.map { Pair(it.first, it.second) })
                    l.forEachIndexed { i, h -> out.set(i + 1, hitTable(h.first, h.second, v[i] ?: "")) }
                }
                else -> host.send("list ${max + skip}").second.drop(skip).forEachIndexed { i, line ->
                    val f = line.split(' ', limit = 4)
                    out.set(i + 1, hitTable(f[0].toLong(16), flagsOf(f[1]), f.getOrElse(3) { "" }))
                }
            }
            out
        })
        gg.set("clearResults", fn { cleared = true; loaded = null; LuaValue.NONE })
        gg.set("loadResults", fn { a ->
            loaded = items(a.checktable(1)).map { Triple(it.get("address").tolong(), it.get("flags").optint(TYPE_DWORD), "") }
            cleared = false
            LuaValue.TRUE
        })
        // o "aplicar" dos scripts de menu (SpeedHackFinder: loadResults e depois editAll com a velocidade): escreve em cada
        // resultado carregado; sem loadResults, nos resultados da busca do daemon
        gg.set("editAll", fn { a ->
            val v = ggNumber(a.checkjstring(1))
            val l = loaded
            if (cleared) return@fn LuaValue.valueOf(0)
            if (l == null) { host.send("writeall $v"); return@fn LuaValue.TRUE }
            val t = a.optint(2, 0)
            l.forEach { (addr, flags, _) -> host.send("write ${addr.toString(16)} ${typeOf(if (t != 0) t else flags)} $v") }
            LuaValue.valueOf(l.size)
        })

        // ---- memória ----
        gg.set("getValues", fn { a ->
            val list = items(a.checktable(1))
            val v = readValues(list.map { Pair(it.get("address").tolong(), it.get("flags").optint(TYPE_DWORD)) })
            LuaTable().apply {
                list.forEachIndexed { i, t ->
                    val copy = LuaTable()
                    var k = LuaValue.NIL
                    while (true) { val n = t.next(k); if (n.arg1().isnil()) break; k = n.arg1(); copy.set(k, n.arg(2)) }
                    copy.set("value", value(v[i] ?: ""))
                    set(i + 1, copy)
                }
            }
        })
        gg.set("setValues", fn { a ->
            val bad = items(a.checktable(1)).mapNotNull { t ->
                val addr = t.get("address").tolong()
                runCatching { host.send("write ${addr.toString(16)} ${typeOf(t.get("flags").optint(TYPE_DWORD))} ${plainNumber(t.get("value"))}") }
                    .exceptionOrNull()?.let { "0x${addr.toString(16)}: ${it.message}" }
            }
            if (bad.isEmpty()) LuaValue.TRUE else LuaValue.valueOf(bad.joinToString("\n"))
        })
        gg.set("getValuesRange", fn { a ->
            val list = items(a.checktable(1))
            val known = (loaded?.associate { it.first to it.third } ?: emptyMap()) +
                host.send("list 2000").second.associate { l -> l.split(' ').let { it[0].toLong(16) to it.getOrElse(2) { "" } } }
            LuaTable().apply { list.forEachIndexed { i, t -> set(i + 1, known[t.get("address").tolong()].orEmpty()) } }
        })
        // shortcut: só o começo de uma lib pelo nome ("libil2cpp.so"); a lista de regiões inteira precisa de um comando no daemon
        gg.set("getRangesList", fn { a ->
            val name = a.optjstring(1, "").trim()
            val out = LuaTable()
            if (name.isNotEmpty() && !name.contains('*')) runCatching { host.send("resolve $name+0").first.toLong(16) }.getOrNull()?.let { base ->
                out.set(1, LuaTable().apply { set("start", num(base)); set("end", num(base)); set("name", name); set("internalName", name); set("state", "Xa"); set("type", "r-xp") })
            }
            out
        })

        // ---- lista salva ----
        fun luaItems(t: LuaValue) = items(t).map {
            LuaItem(it.get("address").tolong(), it.get("flags").optint(TYPE_DWORD), it.get("name").optjstring(""), it.get("freeze").optboolean(false),
                it.get("value").let { v -> if (v.isnil()) "" else plainNumber(v) })
        }
        gg.set("addListItems", fn { a -> host.addItems(luaItems(a.checktable(1))); LuaValue.TRUE })
        gg.set("removeListItems", fn { a -> host.removeItems(luaItems(a.checktable(1))); LuaValue.TRUE })
        gg.set("clearList", fn { host.removeItems(host.listItems()); LuaValue.TRUE })
        gg.set("getListItems", fn {
            LuaTable().apply {
                host.listItems().forEachIndexed { i, it ->
                    set(i + 1, hitTable(it.address, it.flags, it.value).apply { set("name", it.name); set("freeze", LuaValue.valueOf(it.freeze)) })
                }
            }
        })

        // ---- internet: só a planilha (Google Apps Script), e a pessoa permite uma vez por execução ----
        gg.set("makeRequest", fn { a ->
            val url = a.checkjstring(1)
            val host0 = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            if (!(host0 == "script.google.com" || host0.endsWith(".googleusercontent.com")))
                return@fn LuaValue.valueOf("Bloqueado pelo Tinker: o script só pode falar com a planilha (script.google.com), não com $host0")
            val ok = netOk ?: (host.ask(LuaAsk.Alert("O script quer mandar dados para a planilha ($host0). Permitir nesta execução?", listOf("Permitir", "Não"))) == 1).also { netOk = it }
            check()
            if (!ok) return@fn LuaValue.valueOf("Você não permitiu a internet para este script.")
            val headers = mutableMapOf<String, String>()
            a.opttable(2, LuaTable()).let { t -> var k = LuaValue.NIL; while (true) { val n = t.next(k); if (n.arg1().isnil()) break; k = n.arg1(); headers[k.tojstring()] = n.arg(2).tojstring() } }
            runCatching { host.http(url, headers, a.optjstring(3, null)) }.fold(
                { (code, body) -> LuaTable().apply { set("code", code); set("content", body); set("url", url) } },
                { LuaValue.valueOf("Falha na internet: ${it.message}") })
        })

        // o resto do GG: avisa o nome em vez de "attempt to call nil"
        gg.setmetatable(LuaTable().apply {
            set("__index", fn { a -> throw LuaError("gg.${a.arg(2).tojstring()} ainda não existe no Tinker") })
        })
        return gg
    }

    /** Lê vários endereços de uma vez: os perto um do outro (mesmo tipo) vão num "dump" só, não um "read" cada. */
    private fun readValues(list: List<Pair<Long, Int>>): List<String?> {
        val out = arrayOfNulls<String>(list.size)
        list.indices.groupBy { typeOf(list[it].second) }.forEach { (type, idx) ->
            val size = TYPE_SIZE[type] ?: 4
            val sorted = idx.sortedBy { list[it].first }
            var i = 0
            while (i < sorted.size) {
                val start = list[sorted[i]].first
                var j = i
                // mesmo alinhamento a partir do começo e até 1 KB: um dump cobre todos
                while (j + 1 < sorted.size && list[sorted[j + 1]].first - start <= 1024 && (list[sorted[j + 1]].first - start) % size == 0L) j++
                if (j > i && !type.startsWith("x")) {
                    val n = ((list[sorted[j]].first - start) / size + 1).toInt()
                    val got = runCatching { host.send("dump ${start.toString(16)} $type $n").second }.getOrDefault(emptyList())
                        .associate { l -> l.substringBefore(' ').toLong(16) to l.substringAfter(' ') }
                    for (k in i..j) out[sorted[k]] = got[list[sorted[k]].first]?.takeIf { it != "?" }
                } else for (k in i..j) {
                    out[sorted[k]] = runCatching { host.send("read ${list[sorted[k]].first.toString(16)} $type").first }.getOrNull()
                }
                i = j + 1
            }
        }
        return out.toList()
    }

    /** As chaves da tabela na ordem do GG: 1..n primeiro, depois as outras pela ordem do nome. */
    private fun keysOf(t: LuaTable): List<LuaValue> {
        val n = t.length()
        val rest = mutableListOf<LuaValue>()
        var k = LuaValue.NIL
        while (true) {
            val next = t.next(k)
            if (next.arg1().isnil()) break
            k = next.arg1()
            if (!(k.isint() && k.toint() in 1..n)) rest += k
        }
        return (1..n).map { LuaValue.valueOf(it) as LuaValue } + rest.sortedBy { it.tojstring() }
    }

    companion object {
        const val TYPE_DWORD = 4
        const val TYPE_AUTO = 127
        const val SIGN_EQUAL = 536870912
        val TYPE_FLAGS = linkedMapOf("TYPE_BYTE" to 1, "TYPE_WORD" to 2, "TYPE_DWORD" to 4, "TYPE_XOR" to 8, "TYPE_FLOAT" to 16, "TYPE_QWORD" to 32, "TYPE_DOUBLE" to 64, "TYPE_AUTO" to 127)
        private val FLAG_TYPE = linkedMapOf(4 to "i32", 16 to "f32", 32 to "i64", 64 to "f64", 1 to "i8", 2 to "i16", 8 to "xi32")
        private val TYPE_SIZE = mapOf("i8" to 1, "i16" to 2, "i32" to 4, "xi32" to 4, "f32" to 4, "i64" to 8, "f64" to 8)
        val REGION_FLAGS = linkedMapOf(
            "REGION_C_HEAP" to 1, "REGION_JAVA_HEAP" to 2, "REGION_C_ALLOC" to 4, "REGION_C_DATA" to 8, "REGION_C_BSS" to 16, "REGION_ANONYMOUS" to 32,
            "REGION_STACK" to 64, "REGION_CODE_APP" to 16384, "REGION_CODE_SYS" to 32768, "REGION_JAVA" to 65536, "REGION_BAD" to 131072,
            "REGION_PPSSPP" to 262144, "REGION_ASHMEM" to 524288, "REGION_VIDEO" to 1048576, "REGION_OTHER" to -2080896)
        /** Bit do GG → letra do daemon (o daemon não separa código, PPSSPP nem "bad": esses não entram). */
        private val REGION_LETTERS = listOf(1 to "Ch", 2 to "Jh", 4 to "Ca", 8 to "Cd", 16 to "Cb", 32 to "A", 64 to "S", 65536 to "J", 524288 to "As", 1048576 to "V", -2080896 to "O")
        val SIGNS = linkedMapOf("SIGN_EQUAL" to SIGN_EQUAL, "SIGN_NOT_EQUAL" to 268435456, "SIGN_GREATER_OR_EQUAL" to 134217728, "SIGN_LESS_OR_EQUAL" to 67108864)

        /** Flags do GG (pode ser soma, como Dword + Float) → lista de tipos do daemon. */
        fun typesOf(flags: Int): String = if (flags == TYPE_AUTO) "i32,f32,i64,f64" else FLAG_TYPE.filter { (bit, _) -> flags and bit != 0 && bit != 8 }.values.joinToString(",").ifEmpty { "i32" }
        fun typeOf(flags: Int): String = FLAG_TYPE[flags] ?: FLAG_TYPE.entries.firstOrNull { flags and it.key != 0 }?.value ?: "i32"
        fun flagsOf(type: String): Int = FLAG_TYPE.entries.firstOrNull { it.value == type.substringBefore('@').removeSuffix("x2") }?.key
            ?: FLAG_TYPE.entries.firstOrNull { it.value == type.substringBefore('@').removePrefix("x").removeSuffix("x2") }?.key ?: TYPE_DWORD
    }
}

/** Número escrito como no GG para o daemon: "3F7FFFFEh" (hexa) vira decimal (Dword acima de 7FFFFFFF vira negativo, como
 * o GG mostra); "100D" ou "1.5F" perde a letra do tipo. */
internal fun ggNumber(text: String): String {
    val s = text.trim()
    Regex("^(-?)([0-9A-Fa-f]{1,16})[hH][A-Za-z]?$").matchEntire(s)?.let { m ->
        val v = m.groupValues[2].toULong(16).toLong()
        val n = if (m.groupValues[2].length <= 8 && v >= 0x8000_0000L) v - 0x1_0000_0000L else v
        return (if (m.groupValues[1] == "-") -n else n).toString()
    }
    return Regex("^(-?[0-9.~]+)[A-Za-z]$").matchEntire(s)?.groupValues?.get(1) ?: s
}
