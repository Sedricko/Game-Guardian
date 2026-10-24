package dev.tinker.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

val TYPES = listOf("auto" to "Auto", "i32" to "Dword", "f32" to "Float", "i64" to "Qword", "f64" to "Double", "i16" to "Word", "i8" to "Byte")
val TYPE_NAMES = TYPES.toMap()
val TYPE_SIZES = mapOf("i8" to 1, "i16" to 2, "i32" to 4, "i64" to 8, "f32" to 4, "f64" to 8)
val TABS = listOf("Busca", "Salvos", "Memória", "Registro", "Ajustes")
const val TAB_MEMORY = 2
val PANEL_SIZES = listOf(0.5f to "Metade", 0.85f to "Grande", 1f to "Tela cheia")

val REGION_NAMES = mapOf(
    "Jh" to "Java heap", "J" to "Java", "Ch" to "C++ heap", "Ca" to "C++ alloc", "Cd" to "C++ .data",
    "Cb" to "C++ .bss", "A" to "Anônima", "S" to "Pilha", "As" to "Ashmem", "O" to "Outras", "V" to "Vídeo (pode travar o jogo)",
)

data class GameProcess(val pid: Int, val pkg: String, val label: String, val icon: ImageBitmap?)
// base: na busca perto, o endereço de partida em volta do qual este foi achado
data class Hit(val addr: String, val type: String, val region: String, val value: String, val base: String = "", val asDword: String = "")
data class Region(val code: String, val count: Long, val bytes: Long, val enabled: Boolean)
data class SavedItem(val addr: String, val type: String, val region: String, val value: String, val frozen: Boolean = false, val name: String = "")

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
    var panelSize by mutableIntStateOf(1)  // índice em PANEL_SIZES
    var peek by mutableStateOf(false)
    var autoPause by mutableStateOf(true)
    var canUndo by mutableStateOf(false)  // há um refino para desfazer  // pausa o jogo durante busca/refino, para o valor não mudar no meio
    var view by mutableStateOf<Pair<String, String>?>(null)
    var tab by mutableIntStateOf(0)  // índice em TABS  // visualizador de memória: endereço e tipo  // segurando o "olho": painel quase invisível
    val saved = mutableStateListOf<SavedItem>()
}

/** Ponte entre a tela e o daemon; tudo roda fora da thread de UI. Uma instância por processo. */
class Engine private constructor(private val app: Context) {
    companion object {
        @Volatile private var instance: Engine? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: Engine(c.applicationContext).also { instance = it } }
    }

    private var daemon: Daemon? = null
    private var closedPid = 0
    private val freezeFailing = HashSet<String>()  // endereços congelados que estão falhando (avisa uma vez)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // uma lista de salvos por jogo; "saved.json" é a lista de antes de escolher um jogo
    private var savedPkg: String? = null
    private val savedFile get() = File(app.filesDir, savedPkg?.let { "saved-$it.json" } ?: "saved.json")

    init {
        loadSaved()
        scope.launch { freezeLoop() }
    }

    private fun Daemon.send(command: String, quiet: Boolean = false): Daemon.Reply {
        if (!quiet) Diag.add("> $command")
        val t0 = System.nanoTime()
        try {
            val r = call(command)
            if (!quiet) Diag.add("< ok ${r.status}".trimEnd() + " (${(System.nanoTime() - t0) / 1_000_000} ms)")
            return r
        } catch (e: IOException) {
            Diag.add("< erro: ${e.message} (comando: $command, ${(System.nanoTime() - t0) / 1_000_000} ms)")
            throw e
        }
    }

    private suspend fun <T> call(work: (Daemon) -> T): T = withContext(Dispatchers.IO) {
        val d = daemon ?: try {
            Daemon(app.applicationInfo.nativeLibraryDir + "/libtinkerd.so") { Diag.add("[daemon stderr] $it") }.also {
                daemon = it
                Diag.add("Daemon iniciado com root: ${runCatching { it.call("info").status }.getOrElse { "info indisponível (${it.message})" }}")
            }
        } catch (e: IOException) {
            Diag.add("Falha ao iniciar o daemon: ${e.message}")
            throw e
        }
        try {
            work(d)
        } catch (e: IOException) {
            if (e.message == "O daemon fechou.") daemon = null
            if (e.message == "o jogo fechou") gameClosed(d)
            throw e
        }
    }

    /** Guarda no registro o motivo da queda, uma vez por jogo. */
    private fun gameClosed(d: Daemon) {
        val p = Session.process ?: return
        if (closedPid == p.pid) return
        closedPid = p.pid
        Session.paused = false
        Session.saved.replaceAll { it.copy(frozen = false) }
        Session.status = "O jogo fechou. Veja a aba Registro para o motivo."
        Diag.add("=== O jogo ${p.label} (PID ${p.pid}) fechou. Falhas registradas pelo Android: ===")
        runCatching { dedupe(d.call("crashlog").lines).forEach(Diag::add) }
        Diag.add("=== Últimas linhas do logcat do jogo ===")
        runCatching { dedupe(d.call("logs").lines).forEach(Diag::add) }
        Diag.add("=== Fim do relatório da queda ===")
        Diag.add("Dica: se o jogo fechou logo depois de editar ou congelar, provavelmente o endereço não era o valor certo " +
            "(ou era de outro tipo). Refine mais vezes até sobrar 1 resultado antes de editar.")
    }

    /** Botão Sair: nunca deixa o jogo pausado e encerra o daemon root. */
    fun shutdown() {
        scope.launch {
            if (Session.paused) runCatching { resume() }
            daemon?.close()
            daemon = null
            Session.process = null
            Session.count = null
            Session.results = emptyList()
            Session.status = "Escolha o jogo para começar."
            Diag.add("Tinker encerrado pelo botão Sair")
        }
    }

    suspend fun apps(): List<GameProcess> = call { d ->
        val pm = app.packageManager
        d.send("ps", quiet = true).lines.mapNotNull { line ->
            val pid = line.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            val pkg = line.substringAfter(' ')
            // só o processo principal de apps instalados pelo usuário
            if (':' in pkg || '.' !in pkg || pkg.startsWith("/") || pkg == app.packageName) return@mapNotNull null
            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            GameProcess(pid, pkg, pm.getApplicationLabel(info).toString(), info.loadIcon(pm).toImageBitmap())
        }.sortedBy { it.label.lowercase() }
    }

    suspend fun attach(p: GameProcess): String = call { d ->
        Diag.add("Jogo escolhido: ${p.label} (${p.pkg}, PID ${p.pid})")
        d.send("attach ${p.pid}")
        Session.process = p
        Session.paused = false
        Session.canUndo = false
        switchSaved(p.pkg)
        "Pronto. Digite um valor que você vê no jogo e toque em Nova busca."
    }

    /** Roda work com o jogo pausado (se a opção estiver ligada) e sempre continua o jogo depois. */
    private fun <T> Daemon.frozenGame(work: () -> T): T {
        val auto = Session.autoPause && !Session.paused
        if (auto) send("pause", quiet = true)
        try {
            return work()
        } finally {
            if (auto) runCatching { send("resume", quiet = true) }
        }
    }

    /** types vazio = Auto. */
    suspend fun search(types: Set<String>, value: String): String = call { d ->
        val list = TYPES.map { it.first }.filter { it in types }.joinToString(",").ifEmpty { "auto" }
        val n = d.frozenGame { d.send("search $list ${value.arg()}") }.status
        Session.canUndo = false
        if (n == "0") "Nada encontrado. Dicas: use Auto; se o número da tela é arredondado (1.2K), busque uma faixa como 1150~1250; ou marque mais regiões em Ajustes."
        else "$n encontrados. Mude o valor no jogo e refine."
    }

    suspend fun refine(value: String): String = call { d ->
        val before = Session.count
        val n = d.frozenGame { d.send("refine ${value.arg()}") }.status
        Session.canUndo = true
        when {
            n == "0" -> "Nenhum restou. O valor pode ter mudado de novo antes do refino: toque em Desfazer e refine com o valor atual."
            // refinar com o mesmo número não filtra nada: o valor precisa mudar no jogo antes
            n == before?.toString() -> "Continuam $n: nenhum mudou. Faça o valor mudar no jogo (gaste ou ganhe) e refine com o número novo."
            else -> "$n restantes."
        }
    }

    /** Volta ao resultado de antes do último refino; tocar de novo refaz. */
    suspend fun undo(): String = call { d -> "${d.send("undo").status} resultados (desfeito; toque de novo para refazer)." }

    suspend fun write(addr: String, type: String, value: String): String = call { d ->
        d.send("write $addr $type ${value.arg()}")
        "Valor alterado."
    }

    suspend fun writeAll(value: String): String = call { d ->
        val (ok, fail) = d.send("writeall ${value.arg()}").status.split(" ")
        if (fail == "0") "$ok valores alterados." else "$ok alterados, $fail não couberam no tipo ou falharam."
    }

    suspend fun list(): Pair<Long?, List<Hit>> = call { d ->
        if (!d.isAttached) return@call null to emptyList()
        val r = d.send("list 200", quiet = true)
        r.status.toLongOrNull() to r.lines.map {
            val f = it.split(" ", limit = 4)
            Hit(f[0], f[1], f[2], f[3])
        }
    }

    suspend fun regions(): List<Region> = call { d ->
        d.send("regions", quiet = true).lines.map {
            val f = it.split(" ")
            Region(f[0], f[1].toLong(), f[2].toLong(), f[3] == "1")
        }
    }

    suspend fun setRegion(code: String, enabled: Boolean): List<Region> = call { d ->
        val on = Session.regions.filter { if (it.code == code) enabled else it.enabled }.map { it.code }
        d.send("ranges ${on.joinToString(",")}")
        Session.regions.map { if (it.code == code) it.copy(enabled = enabled) else it }
    }

    suspend fun pause(): String = call { d -> d.send("pause"); Session.paused = true; "Jogo pausado." }
    suspend fun resume(): String = call { d -> d.send("resume"); Session.paused = false; "Jogo continuando." }
    suspend fun killGame(): String = call { d ->
        d.send("kill")
        closedPid = Session.process?.pid ?: 0  // fechado de propósito: não é queda
        Session.paused = false
        "Jogo fechado."
    }

    suspend fun crashLog() = call { d ->
        Diag.add("=== Falhas registradas pelo Android (buffer crash) ===")
        dedupe(d.send("crashlog", quiet = true).lines).forEach(Diag::add)
        Diag.add("=== Fim ===")
    }

    suspend fun gameLog() = call { d ->
        Diag.add("=== Logcat do jogo ===")
        dedupe(d.send("logs", quiet = true).lines).forEach(Diag::add)
        Diag.add("=== Fim ===")
    }

    /** Relatório completo num .txt: aparelho, jogo, registro do Tinker, falhas e logcat do jogo. */
    suspend fun report(): File = withContext(Dispatchers.IO) {
        suspend fun section(title: String, cmd: String) = "=== $title ===\n" + runCatching {
            dedupe(call { d -> d.send(cmd, quiet = true).lines }).joinToString("\n")
        }.getOrElse { "(não foi possível ler: ${it.message})" }
        val p = Session.process
        val crash = section("Falhas registradas pelo Android (crash)", "crashlog")
        val game = if (p != null) section("Logcat do jogo", "logs") else "=== Logcat do jogo ===\n(nenhum jogo escolhido)"
        val text = buildString {
            appendLine("=== Relatório do Tinker ===")
            appendLine("Gerado em: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("App: Tinker ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()}")
            appendLine("Aparelho: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Jogo: ${p?.let { "${it.label} (${it.pkg}, PID ${it.pid})${if (Session.paused) " · pausado" else ""}" } ?: "nenhum"}")
            appendLine("Regiões ativas: ${Session.regions.filter { it.enabled }.joinToString { it.code }.ifEmpty { "padrão" }}")
            appendLine("Salvos: ${Session.saved.size} (congelados: ${Session.saved.count { it.frozen }})")
            val mem = android.app.ActivityManager.MemoryInfo().also { app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
            appendLine("Memória do aparelho: ${mem.availMem shr 20} MB livres de ${mem.totalMem shr 20} MB${if (mem.lowMemory) " (POUCA MEMÓRIA)" else ""}")
            appendLine("Pausa automática: ${if (Session.autoPause) "ligada" else "desligada"}")
            val log = Diag.export().lines()
            val problems = log.filter { l ->
                val msg = l.substringAfter(' ').substringAfter(' ')  // tira data e hora do registro
                // linhas com "| " vêm do logcat (do jogo ou do Android), não são problemas do Tinker
                !msg.startsWith("===") && !msg.startsWith("|") && PROBLEM_WORDS.any { msg.contains(it, ignoreCase = true) }
            }
            appendLine()
            appendLine("=== Resumo de problemas (${problems.size}) ===")
            appendLine(if (problems.isEmpty()) "Nenhum erro registrado." else problems.takeLast(20).joinToString("\n"))
            appendLine()
            appendLine("=== Registro do Tinker ===")
            appendLine(Diag.export())
            appendLine(crash)
            appendLine()
            appendLine(game)
        }
        val dir = File(app.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // só o último relatório fica no cache
        File(dir, "tinker-relatorio-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.txt")
            .apply { writeText(text) }
            .also { Diag.add("Relatório gerado: ${it.name} (${it.length() / 1024} KB)") }
    }

    // Lista de salvos

    /** Valores vizinhos de addr (24 antes, 24 depois), lidos como type; shift anda em blocos de 48. */
    suspend fun nearby(addr: String, type: String, shift: Int): List<Hit> = call { d ->
        val size = TYPE_SIZES.getValue(type)
        val center = addr.toLong(16) / size * size  // alinhado ao tipo
        val start = (center + (shift * 48L - 24L) * size).coerceAtLeast(0)
        val rows = d.send("dump ${start.toString(16)} $type 48", quiet = true).lines.map { it.split(" ", limit = 2) }
        // Float/Double com NaN: os bytes não são desse tipo; lê os mesmos bytes como Dword para mostrar o que são
        val dwords = if (rows.none { readable(it[1], type) == null }) emptyMap()
        else d.send("dump ${start.toString(16)} i32 ${48 * size / 4}", quiet = true).lines.associate { it.split(" ", limit = 2).let { (a, v) -> a.toLong(16) to v } }
        rows.map { (a, v) ->
            val dw = if (readable(v, type) == null) (0 until size / 4).mapNotNull { dwords[a.toLong(16) + it * 4] }.joinToString(" · ") else ""
            Hit(a, type, "", v, asDword = dw)
        }
    }

    /**
     * Busca perto: procura um ou mais valores ("50" ou "50;75") nas `radius` linhas acima e abaixo de addr,
     * no tipo dado. Ex.: achou o ouro, procura a prata ali do lado.
     */
    suspend fun searchNear(centers: List<String>, type: String, radius: Int, values: String): List<Hit> = call { d ->
        val wanted = parseValues(values)
        val size = TYPE_SIZES.getValue(type)
        val r = radius.coerceIn(1, 10000)
        val bases = centers.map { it.toLong(16) / size * size }.toSet()
        val found = LinkedHashMap<String, Hit>()  // um achado por endereço, mesmo se perto de dois pontos de partida
        d.frozenGame {
            centers.forEach { addr ->
                val center = addr.toLong(16) / size * size
                val start = (center - r.toLong() * size).coerceAtLeast(0)
                d.send("dump ${start.toString(16)} $type ${2 * r + 1}", quiet = true).lines.forEach {
                    val (a, v) = it.split(" ", limit = 2)
                    if (a !in found && a.toLong(16) !in bases && matchesAny(v, type, wanted)) found[a] = Hit(a, type, "", v, base = addr)
                }
            }
        }
        Diag.add("Busca perto de ${centers.size} endereço(s) (±$r ${TYPE_NAMES[type]}): $values → ${found.size} achados")
        found.values.toList()
    }

    /** Refino da busca perto: o valor mudou no jogo, fica só quem tem o valor novo. */
    suspend fun refineNear(hits: List<Hit>, values: String): List<Hit> = call { d ->
        val wanted = parseValues(values)
        val kept = d.frozenGame {
            hits.mapNotNull { h ->
                val v = runCatching { d.send("read ${h.addr} ${h.type}", quiet = true).status }.getOrNull() ?: return@mapNotNull null
                if (matchesAny(v, h.type, wanted)) h.copy(value = v) else null
            }
        }
        Diag.add("Refino perto: $values → ${kept.size} de ${hits.size}")
        kept
    }

    /** "Pegar Double e salvar em Float": muda o tipo de um salvo, mesmo endereço. */
    fun changeType(item: SavedItem, type: String) {
        if (Session.saved.any { it.addr == item.addr && it.type == type }) return
        Diag.add("Salvo 0x${item.addr}: ${TYPE_NAMES[item.type]} → ${TYPE_NAMES[type]}")
        replace(item) { it.copy(type = type, value = "?", frozen = false) }
    }

    /** Salva vários de uma vez; com frozen=true já entram congelados no valor atual. */
    fun addSaved(hits: List<Hit>, frozen: Boolean = false) {
        hits.forEach { hit ->
            val i = Session.saved.indexOfFirst { it.addr == hit.addr && it.type == hit.type }
            if (i < 0) Session.saved.add(SavedItem(hit.addr, hit.type, hit.region, hit.value, frozen))
            else if (frozen) Session.saved[i] = Session.saved[i].copy(frozen = true)
        }
        Diag.add("${if (frozen) "Congelados" else "Salvos"} ${hits.size} endereços")
        storeSaved()
    }

    fun removeSaved(items: List<SavedItem>) {
        val keys = items.map { it.addr + it.type }.toSet()
        Session.saved.removeAll { it.addr + it.type in keys }
        Diag.add("Removidos ${items.size} salvos")
        storeSaved()
    }

    /** Escreve o mesmo valor em vários endereços (selecionados), cada um no seu tipo. */
    suspend fun writeMany(targets: List<Pair<String, String>>, value: String): String = call { d ->
        val fail = targets.count { (addr, type) -> runCatching { d.send("write $addr $type ${value.arg()}", quiet = true) }.isFailure }
        Diag.add("Editados ${targets.size - fail} de ${targets.size} endereços para $value")
        if (fail == 0) "${targets.size} valores alterados." else "${targets.size - fail} alterados, $fail não couberam no tipo ou falharam."
    }

    suspend fun setSaved(items: List<SavedItem>, value: String): String {
        val msg = writeMany(items.map { it.addr to it.type }, value)
        items.forEach { item -> replace(item) { it.copy(value = value) } }
        return msg
    }

    fun rename(item: SavedItem, name: String) {
        Diag.add("Salvo 0x${item.addr} renomeado para \"$name\"")
        replace(item) { it.copy(name = name.trim()) }
    }

    /** Lista de salvos em JSON, para guardar ou passar para outra pessoa (base das tabelas de cheat). */
    fun exportSaved(): String = JSONObject()
        .put("tinker", 1)
        .put("jogo", Session.process?.pkg ?: "")
        .put("nome_do_jogo", Session.process?.label ?: "")
        .put("itens", JSONArray().apply { Session.saved.forEach { put(it.toJson()) } })
        .toString(2)

    /** Aceita o formato do exportSaved ou só a lista. Devolve uma mensagem para a tela. */
    fun importSaved(text: String): String {
        val root = runCatching { JSONObject(text) }.getOrNull()
        val a = root?.optJSONArray("itens") ?: runCatching { JSONArray(text) }.getOrNull()
            ?: throw IllegalArgumentException("Isso não parece uma lista do Tinker (JSON).")
        var added = 0
        for (i in 0 until a.length()) {
            val item = a.getJSONObject(i).toSaved()
            if (Session.saved.none { it.addr == item.addr && it.type == item.type }) { Session.saved.add(item); added++ }
        }
        storeSaved()
        val game = root?.optString("jogo").orEmpty()
        val other = game.isNotEmpty() && Session.process != null && game != Session.process?.pkg
        Diag.add("Importados $added de ${a.length()} salvos${if (game.isNotEmpty()) " (lista de $game)" else ""}")
        return "$added de ${a.length()} importados." + if (other) " Atenção: a lista é de outro jogo ($game)." else
            " Os endereços só valem se o jogo não reiniciou desde a exportação."
    }

    fun setFrozen(items: List<SavedItem>, frozen: Boolean) {
        Diag.add("${if (frozen) "Congelados" else "Descongelados"} ${items.size} salvos")
        items.forEach { item -> replace(item) { it.copy(frozen = frozen) } }
    }

    suspend fun refreshSaved() = call { d ->
        if (!d.isAttached) return@call
        Session.saved.toList().forEach { item ->
            if (item.frozen) return@forEach
            val v = runCatching { d.send("read ${item.addr} ${item.type}", quiet = true).status }.getOrDefault("?")
            if (v != item.value) replace(item) { it.copy(value = v) }
        }
    }

    private fun replace(item: SavedItem, change: (SavedItem) -> SavedItem) {
        val i = Session.saved.indexOfFirst { it.addr == item.addr && it.type == item.type }
        if (i >= 0) Session.saved[i] = change(Session.saved[i])
        storeSaved()
    }

    // Congelar = reescrever o valor a cada 200 ms, como o GameGuardian.
    private suspend fun freezeLoop() {
        while (true) {
            delay(200)
            val frozen = Session.saved.filter { it.frozen }
            val d = daemon
            if (frozen.isEmpty() || d == null || !d.isAttached) continue
            for (item in frozen) {
                val key = item.addr + item.type
                try {
                    d.send("write ${item.addr} ${item.type} ${item.value}", quiet = true)
                    if (freezeFailing.remove(key)) Diag.add("Congelamento voltou a funcionar em 0x${item.addr}")
                } catch (e: IOException) {
                    if (e.message == "o jogo fechou") { gameClosed(d); break }
                    if (freezeFailing.add(key)) Diag.add("AVISO: congelamento falhou em 0x${item.addr} (${TYPE_NAMES[item.type]} = ${item.value}): ${e.message}")
                }
            }
        }
    }

    /** Troca para a lista de salvos do jogo; a lista antiga sem jogo é adotada pelo primeiro jogo escolhido. */
    private fun switchSaved(pkg: String) {
        if (pkg == savedPkg) return
        storeSaved()
        val adopt = savedPkg == null && !File(app.filesDir, "saved-$pkg.json").exists()
        savedPkg = pkg
        if (adopt) {
            storeSaved()
        } else {
            Session.saved.clear()
            loadSaved()
        }
        Diag.add("Salvos do jogo $pkg: ${Session.saved.size}${if (adopt) " (lista anterior adotada)" else ""}")
    }

    private fun loadSaved() = runCatching {
        if (!savedFile.exists()) return@runCatching
        val a = JSONArray(savedFile.readText())
        for (i in 0 until a.length()) Session.saved.add(a.getJSONObject(i).toSaved().copy(frozen = false))
    }

    private fun storeSaved() = runCatching {
        val a = JSONArray()
        Session.saved.forEach { a.put(it.toJson()) }
        savedFile.writeText(a.toString())
    }
}

private fun String.arg() = normalizeNumbers(this).ifBlank { "x" }

/**
 * Aceita números como o jogo mostra: "20.000" é 20000 e "1,5" é 1.5 (padrão brasileiro).
 * Vários valores vão separados por ";" e faixas por "~"; espaços somem.
 */
internal fun normalizeNumbers(input: String): String =
    input.split(';').map { part ->
        part.split('~').joinToString("~") { n ->
            val x = n.replace(" ", "").trim()
            when {
                '.' in x && ',' in x -> x.replace(".", "").replace(',', '.')  // 1.234,5
                Regex("""-?\d{1,3}(\.\d{3})+""").matches(x) -> x.replace(".", "")  // 20.000
                ',' in x -> x.replace(',', '.')  // 1,5
                else -> x
            }
        }
    }.filter { it.isNotEmpty() }.joinToString(";")

private fun SavedItem.toJson() = JSONObject().put("addr", addr).put("type", type).put("region", region).put("value", value).put("name", name).put("frozen", frozen)

private fun JSONObject.toSaved(): SavedItem {
    val type = getString("type")
    require(type in TYPE_SIZES) { "Tipo desconhecido na lista: $type" }
    val addr = getString("addr").removePrefix("0x").lowercase()
    require(addr.toLongOrNull(16) != null) { "Endereço inválido na lista: $addr" }
    return SavedItem(addr, type, optString("region", "?"), optString("value", "?"), optBoolean("frozen", false), optString("name", ""))
}

/** "50;75" ou "100~200" ou misturado ("50;100~200"); cada item vira uma faixa (um número é a faixa dele mesmo). */
internal fun parseValues(values: String): List<ClosedFloatingPointRange<Double>> =
    normalizeNumbers(values).split(';').mapNotNull { part ->
        val p = part.trim()
        if ('~' in p) {
            val a = p.substringBefore('~').trim().toDoubleOrNull() ?: return@mapNotNull null
            val b = p.substringAfter('~').trim().toDoubleOrNull() ?: return@mapNotNull null
            minOf(a, b)..maxOf(a, b)
        } else p.toDoubleOrNull()?.let { it..it }
    }.also { require(it.isNotEmpty()) { "Digite um ou mais valores, separados por ; (faixa: 100~200)" } }

internal fun matchesAny(value: String, type: String, wanted: List<ClosedFloatingPointRange<Double>>): Boolean {
    val x = value.toDoubleOrNull() ?: return false
    // float guardado nunca é exato: 1.1 digitado vira 1.10000002 na memória
    val slack = if (type.startsWith("f")) 1e-4 else 0.0
    return wanted.any { r -> x >= r.start - slack * maxOf(1.0, kotlin.math.abs(r.start)) && x <= r.endInclusive + slack * maxOf(1.0, kotlin.math.abs(r.endInclusive)) }
}

/**
 * Valor para ler na tela: Float/Double sem casas de sobra (0.1, não 0.10000000000000001; 1e10 por extenso).
 * null = NaN/infinito: esses bytes não guardam um número desse tipo.
 */
internal fun readable(value: String, type: String): String? {
    if (type != "f32" && type != "f64") return value
    if (value.contains("nan", true) || value.contains("inf", true)) return null
    val d = value.toDoubleOrNull() ?: return value  // "?" = não deu para ler
    val short = if (type == "f32") d.toFloat().toString() else d.toString()
    val abs = kotlin.math.abs(d)
    return if (abs == 0.0 || abs in 1e-6..1e15) java.math.BigDecimal(short).stripTrailingZeros().toPlainString() else short
}

private val PROBLEM_WORDS = listOf("erro", "falha", "aviso", "fechou", "exception")

private val LOGCAT_PREFIX = Regex("""^\S+ \S+\s+\d+\s+\d+ """)

/** Tira o "| " do protocolo e corta linhas repetidas (ex.: o SDK de anúncios do jogo tentando conectar sem parar). */
private fun dedupe(lines: List<String>): List<String> {
    val seen = HashSet<String>()
    val out = lines.map { "| " + it.removePrefix("| ") }.filter { seen.add(it.removePrefix("| ").replace(LOGCAT_PREFIX, "")) }
    return if (out.size < lines.size) out + "(${lines.size - out.size} linhas repetidas omitidas)" else out
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, 96, 96)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
