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
// xi32/xf32: valor cifrado com a chave do lado (Anti-Cheat Toolkit do Unity); o daemon decifra ao ler e cifra ao escrever
val TYPE_NAMES = TYPES.toMap() + mapOf("xi32" to "Dword 🔒", "xf32" to "Float 🔒")
val TYPE_SIZES = mapOf("i8" to 1, "i16" to 2, "i32" to 4, "i64" to 8, "f32" to 4, "f64" to 8, "xi32" to 4, "xf32" to 4)

/** Tipo base: Dword 🔒 → Dword (a aba Memória mostra os bytes como estão). */
fun plain(type: String) = type.removePrefix("x")
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
// pointer: caminho "lib.so+1a2b 18 40"; com ele o addr é recalculado sozinho, mesmo depois de reiniciar o jogo
// anchor/offset: segue outro salvo (id) a offset bytes dele, como gema = ouro + 8; muda de endereço junto com ele
data class SavedItem(
    val addr: String, val type: String, val region: String, val value: String, val frozen: Boolean = false, val name: String = "", val pointer: String = "",
    val id: String = java.util.UUID.randomUUID().toString(), val anchor: String = "", val offset: Long = 0,
    // pid: processo do jogo em que addr vale (0 = desconhecido, versões antigas); chains: caminhos de ponteiro candidatos;
    // sig/sigOff: assinatura AOB dos bytes em volta (?? = muda) e onde o valor fica nela
    val pid: Int = 0, val chains: List<String> = emptyList(), val sig: String = "", val sigOff: Int = 0,
)

const val MAX_CHAINS = 200
const val SIG_SIDE = 40L  // bytes de cada lado do valor na assinatura

/** Distância máxima para dois salvos serem da mesma "ficha" do jogo (estrutura/objeto). */
const val SAME_STRUCT = 4096L
data class Recent(val types: Set<String>, val value: String)

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
    var findEncrypted by mutableStateOf(true)
    var useBar by mutableStateOf(false)  // a bolinha abre a barra rápida em vez do painel  // a busca também procura valores cifrados (🔒)
    var canUndo by mutableStateOf(false)  // há um refino para desfazer  // pausa o jogo durante busca/refino, para o valor não mudar no meio
    var view by mutableStateOf<Pair<String, String>?>(null)
    var tab by mutableIntStateOf(0)  // índice em TABS  // visualizador de memória: endereço e tipo  // segurando o "olho": painel quase invisível
    val saved = mutableStateListOf<SavedItem>()
    val history = mutableStateListOf<Recent>()  // últimas buscas, para repetir com um toque
    var pointerInfo by mutableStateOf("")
    var restoreInfo by mutableStateOf("")  // o que voltou sozinho ao escolher o jogo
    var gameLibsOnly by mutableStateOf(true)  // ponteiros só com base nas libs do jogo
    var autoPointers by mutableStateOf(true)  // ao salvar, já prepara os ponteiros
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
    private val historyFile get() = File(app.filesDir, "history.txt")
    private var inFile = false  // busca desconhecida ainda no arquivo do daemon
    private var steps = 0  // etapas da busca atual, para o registro

    init {
        loadSaved()
        loadHistory()
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
        Session.restoreInfo = d.restoreAll(p.pid)
        "Pronto. Digite um valor que você vê no jogo e toque em Nova busca." + if (Session.restoreInfo.isEmpty()) "" else " " + Session.restoreInfo
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
        val base = TYPES.map { it.first }.filter { it in types }.ifEmpty { listOf("i32", "f32", "i64", "f64") }
        // cifrados junto, sem o usuário precisar saber: Dword 🔒 quando busca Dword, Float 🔒 quando busca Float
        val list = (base + if (Session.findEncrypted) base.filter { it == "i32" || it == "f32" }.map { "x$it" } else emptyList()).joinToString(",")
        val n = d.frozenGame { d.send("search $list ${value.arg()}") }.status
        Session.canUndo = false
        addRecent(Recent(types, value.trim()))
        inFile = false
        step(true, value.trim(), n)
        if (n == "0") "Nada encontrado. Dicas: use Auto; se o número da tela é arredondado (1.2K), busque uma faixa como 1150~1250; ou marque mais regiões em Ajustes."
        else "$n encontrados. Mude o valor no jogo e refine."
    }

    suspend fun refine(value: String): String = call { d ->
        val before = Session.count
        val n = d.frozenGame { d.send("refine ${value.arg()}") }.status
        Session.canUndo = true
        step(false, value.trim(), n)
        when {
            n == "0" -> "Nenhum restou. O valor pode ter mudado de novo antes do refino: toque em Desfazer e refine com o valor atual."
            // refinar com o mesmo número não filtra nada: o valor precisa mudar no jogo antes
            n == before?.toString() -> "Continuam $n: nenhum mudou. Faça o valor mudar no jogo (gaste ou ganhe) e refine com o número novo."
            else -> "$n restantes."
        }
    }

    /**
     * Busca desconhecida: guarda a memória inteira para comparar depois (barra de vida, energia…).
     * Um tipo só: Auto vira Dword.
     */
    suspend fun startUnknown(types: Set<String>): String = call { d ->
        val type = TYPES.map { it.first }.firstOrNull { it in types } ?: "i32"
        val n = d.frozenGame { d.send("snapshot $type") }.status
        Session.canUndo = false
        inFile = true
        step(true, "desconhecido ${TYPE_NAMES[type]}", n)
        "Memória guardada: $n posições como ${TYPE_NAMES[type]}" + (if (types.isEmpty()) " (barra de vida costuma ser Float: escolha Float e comece de novo)" else "") +
            ". Agora faça o valor mudar no jogo e toque em Aumentou, Diminuiu, Mudou ou Não mudou."
    }

    /** Refino comparando com o valor anterior: inc, dec, same, changed; incby/decby com quanto mudou. */
    suspend fun compare(op: String, value: String = ""): String = call { d ->
        val before = Session.count
        val n = d.frozenGame { d.send("compare $op ${if (value.isBlank()) "" else value.arg()}".trimEnd()) }.status
        // enquanto os candidatos estão no arquivo do daemon (milhões) não há desfazer
        Session.canUndo = !inFile
        inFile = inFile && n.toLong() > 2_000_000
        step(false, COMPARE_NAMES.getValue(op) + if (value.isBlank()) "" else " $value", n)
        when {
            n == "0" -> "Nenhum restou. Toque em Desfazer, ou comece de novo sem valor."
            n.toLong() > 2_000_000 -> "$n candidatos. Continue: mude o valor no jogo e compare de novo."
            // os que sobram não mudam com "não mudou": o valor precisa mudar no jogo antes
            n == before?.toString() && op == "same" -> "Continuam $n. \"Não mudou\" não tira mais nenhum: faça o valor mudar no jogo e toque em Aumentou ou Diminuiu."
            n == before?.toString() -> "Continuam $n: todos mudaram desse jeito. Mude o valor de novo, de outro jeito (ex.: gaste em vez de ganhar)."
            else -> "$n restantes."
        }
    }

    private fun step(new: Boolean, what: String, n: String) {
        steps = if (new) 1 else steps + 1
        if (steps > 1) Diag.add("Etapa $steps da busca: $what → $n")
    }

    private fun addRecent(r: Recent) {
        if (r.value.isEmpty()) return
        Session.history.remove(r)
        Session.history.add(0, r)
        while (Session.history.size > 12) Session.history.removeAt(Session.history.lastIndex)
        runCatching { historyFile.writeText(Session.history.joinToString("\n") { it.types.joinToString(",") + "\t" + it.value }) }
    }

    private fun loadHistory() = runCatching {
        if (!historyFile.exists()) return@runCatching
        historyFile.readLines().mapNotNull { l ->
            val (t, v) = l.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            Recent(t.split(',').filter { it in TYPE_SIZES }.toSet(), v)
        }.let(Session.history::addAll)
    }

    // Ponteiros, offsets e assinatura: como um salvo volta a valer depois de reiniciar o jogo

    private val currentPid get() = Session.process?.pid ?: 0

    /** O endereço é de outra vez do jogo: ler ou escrever ali pode derrubar o jogo. */
    fun stale(item: SavedItem) = item.pid != 0 && item.pid != currentPid

    /** Procura caminhos de ponteiro até o salvo, como o "Pointer scan" do Cheat Engine; ficam guardados nele. */
    suspend fun scanPointers(item: SavedItem, maxOff: Int = 4096, levels: Int = 4): String = call { d ->
        val r = d.frozenGame { d.send("pointers ${item.addr} $maxOff $levels ${if (Session.gameLibsOnly) 1 else 0}") }
        val limited = r.status.split(" ").getOrNull(1) == "1"
        replace(item) { it.copy(chains = r.lines.take(MAX_CHAINS)) }
        Session.pointerInfo = if (r.lines.isEmpty()) "Nenhum caminho. Tente mais níveis, uma distância maior, ou desligue \"Ponteiros só nas libs do jogo\" em Ajustes."
        else "${minOf(r.lines.size, MAX_CHAINS)} caminhos${if (limited) " (há mais; guardados os melhores)" else ""}. Ao reabrir o jogo, o Tinker testa e escolhe sozinho."
        Session.pointerInfo
    }

    /** Fica quem chega num endereço com o valor que o jogo mostra agora; sobrando um, já passa a usá-lo. */
    suspend fun filterPointersByValue(item: SavedItem, values: String): String = call { d ->
        val wanted = parseValues(values)
        val before = item.chains.size
        val kept = d.matching(item.chains, item.type) { matchesAny(it, item.type, wanted) }
        replace(item) { it.copy(chains = kept.map { it.first }) }
        Diag.add("Ponteiros de ${label(item)} filtrados pelo valor $values: ${kept.size} de $before")
        val one = kept.map { it.second }.distinct().size == 1
        if (kept.isNotEmpty() && one) d.usePointer(item, kept.first().first)
        Session.pointerInfo = when {
            kept.isEmpty() -> "Nenhum caminho chega nesse valor. Confira o número, ou busque ponteiros de novo com mais níveis."
            one -> "Pronto: ${label(item)} agora segue o ponteiro e volta sozinho quando o jogo reabrir."
            else -> "${kept.size} de $before chegam nesse valor. Mude o valor no jogo e filtre de novo."
        }
        Session.pointerInfo
    }

    /** Caminhos (e o endereço onde cada um chega) cujo valor lido passa no teste. */
    private fun Daemon.matching(chains: List<String>, type: String, ok: (String) -> Boolean): List<Pair<String, String>> = chains.mapNotNull { c ->
        val a = runCatching { send("resolve $c", quiet = true).status }.getOrNull() ?: return@mapNotNull null
        val v = runCatching { send("read $a $type", quiet = true).status }.getOrNull() ?: return@mapNotNull null
        if (ok(v)) c to a else null
    }

    fun setPointer(item: SavedItem, chain: String) {
        Diag.add("Salvo ${label(item)} ${if (chain.isEmpty()) "sem ponteiro" else "agora segue o ponteiro $chain"}")
        replace(item) { it.copy(pointer = chain, anchor = "", offset = 0) }
        if (chain.isNotEmpty()) linkNear(item.copy(pointer = chain))
    }

    private fun Daemon.usePointer(item: SavedItem, chain: String) {
        setPointer(item, chain)
        Session.saved.firstOrNull { it.id == item.id }?.let { resolve(it) }
    }

    private fun label(item: SavedItem) = item.name.ifEmpty { "0x" + item.addr }

    /**
     * Liga os salvos soltos perto de `anchor` (mesma ficha do jogo, mesmo processo): passam a segui-lo pelo offset.
     * Só no mesmo processo: o offset entre dois endereços de outra vez do jogo não quer dizer nada.
     */
    private fun linkNear(anchor: SavedItem) {
        if (anchor.pid == 0 || anchor.anchor.isNotEmpty()) return
        val base = anchor.addr.toLong(16)
        val linked = Session.saved.filter {
            it.id != anchor.id && it.pid == anchor.pid && it.pointer.isEmpty() && it.anchor.isEmpty() &&
                Session.saved.none { f -> f.anchor == it.id } && kotlin.math.abs(it.addr.toLong(16) - base) <= SAME_STRUCT
        }
        linked.forEach { item -> replace(item) { it.copy(anchor = anchor.id, offset = it.addr.toLong(16) - base) } }
        if (linked.isNotEmpty()) Diag.add("${linked.size} salvos agora seguem ${label(anchor)}: " +
            linked.joinToString { "${label(it)} ${offsetText(it.addr.toLong(16) - base)}" })
    }

    fun unlink(item: SavedItem) {
        Diag.add("Salvo ${label(item)} não segue mais outro salvo")
        replace(item) { it.copy(anchor = "", offset = 0) }
    }

    /** Recalcula o endereço de um salvo com ponteiro ou offset; devolve o salvo atualizado (pid = processo atual se deu certo). */
    private fun Daemon.resolve(item: SavedItem, depth: Int = 0): SavedItem {
        if (!isAttached || depth > 4) return item
        val a = if (item.anchor.isNotEmpty()) {
            // a âncora precisa valer neste processo: o offset da âncora velha cairia em lugar errado
            val anchor = Session.saved.firstOrNull { it.id == item.anchor }?.let { resolve(it, depth + 1) } ?: return item
            if (anchor.pid != currentPid) return item
            (anchor.addr.toLong(16) + item.offset).toString(16)
        } else {
            if (item.pointer.isEmpty()) return item
            runCatching { send("resolve ${item.pointer}", quiet = true).status }.getOrNull() ?: return item
        }
        return moveTo(item, a, if (item.anchor.isNotEmpty()) "Offset" else "Ponteiro")
    }

    /** Põe o salvo em addr neste processo; outro salvo do mesmo tipo já ali: não duplica. */
    private fun moveTo(item: SavedItem, addr: String, how: String): SavedItem {
        if (addr != item.addr && Session.saved.any { it.id != item.id && it.addr == addr && it.type == item.type }) return item
        if (addr == item.addr && item.pid == currentPid) return item
        if (addr != item.addr) Diag.add("$how ${label(item)}: 0x${item.addr} → 0x$addr")
        val moved = item.copy(addr = addr, pid = currentPid)
        replace(item) { it.copy(addr = addr, pid = currentPid) }
        return moved
    }

    /**
     * Ao escolher o jogo: cada salvo de outra vez do jogo tenta voltar sozinho, nesta ordem:
     * ponteiro em uso → caminhos guardados (pelo último valor) → assinatura. Quem segue outro pelo offset vem por último.
     */
    private fun Daemon.restoreAll(pid: Int): String {
        val back = mutableListOf<String>()
        val lost = mutableListOf<String>()
        Session.saved.toList().sortedBy { it.anchor.isNotEmpty() }.forEach { old ->
            val item = Session.saved.firstOrNull { it.id == old.id } ?: return@forEach
            if (item.pid == pid) return@forEach  // mesmo processo: o endereço ainda vale
            val how = when {
                item.pointer.isNotEmpty() -> "ponteiro".takeIf { resolve(item).pid == pid }
                item.anchor.isNotEmpty() -> "offset".takeIf { resolve(item).pid == pid }
                else -> null
            } ?: fromChains(item, pid) ?: fromSignature(item, pid)
            when {
                how != null -> back += "${label(item)} ($how)"
                item.pid != 0 || item.chains.isNotEmpty() || item.sig.isNotEmpty() -> lost += label(item)
            }
        }
        if (back.isEmpty() && lost.isEmpty()) return ""
        val msg = (if (back.isEmpty()) "" else "Voltaram sozinhos: ${back.joinToString()}. ") +
            (if (lost.isEmpty()) "" else "Não achados (busque de novo): ${lost.joinToString()}.")
        Diag.add("Restauração dos salvos: $msg")
        return msg.trim()
    }

    /** Caminhos guardados: fica quem chega no último valor conhecido; se todos chegam no mesmo endereço, usa o melhor. */
    private fun Daemon.fromChains(item: SavedItem, pid: Int): String? {
        if (item.chains.isEmpty()) return null
        val wanted = runCatching { parseValues(readable(item.value, item.type) ?: return null) }.getOrNull() ?: return null
        val kept = matching(item.chains, item.type) { matchesAny(it, item.type, wanted) }
        if (kept.isEmpty() || kept.map { it.second }.distinct().size != 1) {
            if (kept.isNotEmpty()) replace(item) { it.copy(chains = kept.map { k -> k.first }) }
            return null
        }
        usePointer(item, kept.first().first)
        return "ponteiro".takeIf { Session.saved.firstOrNull { it.id == item.id }?.pid == pid }
    }

    /** Assinatura: procura os bytes em volta; vale se sobrar um lugar só (com o último valor, ou o único achado). */
    private fun Daemon.fromSignature(item: SavedItem, pid: Int): String? {
        if (!sigUseful(item.sig)) return null
        val places = runCatching { send("aobfind ${item.sig}", quiet = true).lines }.getOrNull()
            ?.map { (it.toLong(16) + item.sigOff).toString(16) } ?: return null
        val wanted = runCatching { parseValues(readable(item.value, item.type) ?: "x") }.getOrNull()
        val same = if (wanted == null) emptyList() else places.filter { a ->
            matchesAny(runCatching { send("read $a ${item.type}", quiet = true).status }.getOrDefault("?"), item.type, wanted)
        }
        val addr = same.singleOrNull() ?: places.singleOrNull() ?: return null
        return "assinatura".takeIf { moveTo(item, addr, "Assinatura").pid == pid }
    }

    /** Aprende a assinatura enquanto o jogo roda: bytes em volta que mudaram viram ??. */
    private fun Daemon.learnSig(item: SavedItem) {
        val vlen = if (item.type.startsWith("x")) 8 else TYPE_SIZES.getValue(item.type)  // cifrado: chave + valor
        val a = item.addr.toLong(16)
        val start = (a - SIG_SIDE).coerceAtLeast(0) / 8 * 8
        val words = ((a + vlen + SIG_SIDE - start + 7) / 8).toInt()
        val lines = runCatching { send("dump ${start.toString(16)} i64 $words", quiet = true).lines }.getOrNull() ?: return
        val raw = ByteArray(words * 8)
        if (lines.size != words) return
        lines.forEachIndexed { i, l ->
            val v = l.substringAfter(' ').toLongOrNull() ?: return  // "?" = parte sem mapa: tenta de novo depois
            for (k in 0 until 8) raw[i * 8 + k] = (v shr (8 * k)).toByte()
        }
        val off = (a - start).toInt()
        val fresh = signature(raw, off, vlen)
        val merged = if (item.sig.length != fresh.length || item.sigOff != off) fresh else mergeSig(item.sig, fresh)
        if (merged != item.sig || off != item.sigOff) replace(item) { it.copy(sig = merged, sigOff = off) }
    }

    /** Busca AOB do usuário: os achados viram resultados (como Dword) para ver, salvar e editar. */
    suspend fun searchAob(pattern: String, code: Boolean): String = call { d ->
        val p = pattern.replace(" ", "").lowercase()
        require(AOB.matches(p) && p.chunked(2).any { it != "??" }) { "Padrão inválido: use pares hex e ?? (ex.: 1F 20 ?? D5)" }
        val n = d.frozenGame { d.send("aob $p ${if (code) "code" else "data"}") }.status
        Session.canUndo = false
        inFile = false
        step(true, "AOB $pattern", n)
        if (n == "0") "Nenhum lugar com esses bytes${if (code) " no código" else ""}." else "$n lugares com esses bytes."
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
        val added = mutableListOf<SavedItem>()
        hits.forEach { hit ->
            val i = Session.saved.indexOfFirst { it.addr == hit.addr && it.type == hit.type }
            if (i < 0) SavedItem(hit.addr, hit.type, hit.region, hit.value, frozen, pid = currentPid).also { Session.saved.add(it); added += it }
            else if (frozen) Session.saved[i] = Session.saved[i].copy(frozen = true)
        }
        // mesma ficha: os salvos perto do primeiro (deste processo) passam a segui-lo pelo offset
        added.forEach { new ->
            val cur = Session.saved.firstOrNull { it.id == new.id } ?: return@forEach
            if (cur.anchor.isNotEmpty()) return@forEach
            Session.saved.firstOrNull {
                it.id != cur.id && it.pid == cur.pid && it.anchor.isEmpty() && kotlin.math.abs(it.addr.toLong(16) - cur.addr.toLong(16)) <= SAME_STRUCT
            }?.let(::linkNear)
        }
        // ponteiros já preparados para quando o jogo reabrir (pausa o jogo por alguns segundos)
        if (Session.autoPointers && added.size in 1..3) scope.launch {
            added.mapNotNull { a -> Session.saved.firstOrNull { it.id == a.id && it.anchor.isEmpty() } }.forEach { item ->
                Session.pointerInfo = "Preparando ponteiros de ${label(item)}…"
                runCatching { scanPointers(item) }.onFailure { Session.pointerInfo = "Ponteiros de ${label(item)}: ${it.message}" }
            }
        }
        Diag.add("${if (frozen) "Congelados" else "Salvos"} ${hits.size} endereços")
        storeSaved()
    }

    fun removeSaved(items: List<SavedItem>) {
        val gone = items.map { it.id }.toSet()
        Session.saved.removeAll { it.id in gone }
        // quem seguia um removido fica solto, no endereço atual
        Session.saved.replaceAll { if (it.anchor in gone) it.copy(anchor = "", offset = 0) else it }
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
        val live = items.filterNot(::stale)
        if (live.isEmpty()) return "Esses salvos são de outra vez do jogo: busque o valor de novo (ou use ponteiro) antes de editar."
        val msg = writeMany(live.map { it.addr to it.type }, value)
        live.forEach { item -> replace(item) { it.copy(value = value) } }
        return msg + if (live.size < items.size) " ${items.size - live.size} de outra vez do jogo ficaram de fora." else ""
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
        Session.saved.toList().forEach { old ->
            val item = d.resolve(old)
            if (stale(item)) return@forEach  // endereço de outra vez do jogo: não lê lixo
            if (item.pid == currentPid) d.learnSig(item)
            if (item.frozen) return@forEach
            val v = runCatching { d.send("read ${item.addr} ${item.type}", quiet = true).status }.getOrDefault("?")
            if (v != item.value) replace(item) { it.copy(value = v) }
        }
    }

    private fun replace(item: SavedItem, change: (SavedItem) -> SavedItem) {
        val i = Session.saved.indexOfFirst { it.id == item.id }
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
            for (old in frozen) {
                val item = d.resolve(old)
                if (stale(item)) continue  // escrever no endereço velho pode derrubar o jogo
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
    .put("pointer", pointer).put("id", id).put("anchor", anchor).put("offset", offset).put("pid", pid)
    .put("chains", JSONArray(chains)).put("sig", sig).put("sigOff", sigOff)

private val AOB = Regex("""([0-9a-f]{2}|\?\?){1,192}""")

/** Caminho de ponteiro aceito: vai direto para o daemon, então nada de espaço extra, quebra de linha ou outro comando. */
private val POINTER = Regex("""[^\s+]{1,100}\+[0-9a-f]{1,16}( [0-9a-f]{1,8}){0,7}""")

val COMPARE_NAMES = mapOf("inc" to "aumentou", "dec" to "diminuiu", "same" to "não mudou", "changed" to "mudou", "incby" to "aumentou", "decby" to "diminuiu")

/** Bytes em volta do valor → assinatura hex, com ?? no próprio valor e em tudo que parece ponteiro (muda a cada vez que o jogo abre). */
internal fun signature(raw: ByteArray, off: Int, vlen: Int): String {
    val wild = BooleanArray(raw.size) { it in off until off + vlen }
    for (w in 0 until raw.size / 8) {
        var q = 0L
        for (k in 7 downTo 0) q = (q shl 8) or (raw[w * 8 + k].toLong() and 0xff)
        if ((q and 0x00ffffffffffffffL) in 0x10000000L until 0x10000000000L) for (k in 0 until 8) wild[w * 8 + k] = true
    }
    return raw.indices.joinToString("") { if (wild[it]) "??" else "%02x".format(raw[it]) }
}

/** Junta duas leituras da assinatura: o byte que mudou vira ??. */
internal fun mergeSig(old: String, new: String) = old.chunked(2).zip(new.chunked(2)).joinToString("") { (a, b) -> if (a == b) a else "??" }

/** Assinatura que serve para achar de novo: bytes fixos suficientes, e não só zeros. */
internal fun sigUseful(sig: String): Boolean {
    val fixed = sig.chunked(2).filter { it != "??" }
    return fixed.size >= 12 && fixed.count { it != "00" } >= 4
}

fun offsetText(o: Long) = (if (o < 0) "-0x" else "+0x") + kotlin.math.abs(o).toString(16).uppercase()

/** "7a0010", "7a0010+8", "0x7a0010-0x20": endereço com offset (hex) para a aba Memória; null se não for válido. */
fun parseAddress(text: String): String? {
    val t = text.replace(" ", "").lowercase()
    val m = Regex("""(?:0x)?([0-9a-f]+)(?:([+-])(?:0x)?([0-9a-f]+))?""").matchEntire(t) ?: return null
    val base = m.groupValues[1].toLongOrNull(16) ?: return null
    val off = m.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull(16) ?: 0
    val a = if (m.groupValues[2] == "-") base - off else base + off
    return if (a < 0) null else a.toString(16)
}

/** "lib.so+1a2b 18 40" como o Cheat Engine mostra: [[lib.so+1A2B]+18]+40 */
fun pointerText(chain: String): String {
    val parts = chain.split(' ')
    return parts.drop(1).fold(parts[0].substringBeforeLast('+') + "+" + parts[0].substringAfterLast('+').uppercase()) { acc, o -> "[$acc]+${o.uppercase()}" }
}

private fun JSONObject.toSaved(): SavedItem {
    val type = getString("type")
    require(type in TYPE_SIZES) { "Tipo desconhecido na lista: $type" }
    val addr = getString("addr").removePrefix("0x").lowercase()
    require(addr.toLongOrNull(16) != null) { "Endereço inválido na lista: $addr" }
    val pointer = optString("pointer", "")
    require(pointer.isEmpty() || POINTER.matches(pointer)) { "Ponteiro inválido na lista: $pointer" }
    val base = SavedItem(addr, type, optString("region", "?"), optString("value", "?"), optBoolean("frozen", false), optString("name", ""), pointer)
    val chains = optJSONArray("chains")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty().filter { POINTER.matches(it) }.take(MAX_CHAINS)
    val sig = optString("sig", "").takeIf { AOB.matches(it) }.orEmpty()  // vai direto para o daemon: só hex e ??
    return base.copy(
        id = optString("id", "").ifEmpty { base.id }, anchor = optString("anchor", ""), offset = optLong("offset", 0),
        pid = optInt("pid", 0), chains = chains, sig = sig, sigOff = optInt("sigOff", 0).coerceIn(0, 191),
    )
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
    val slack = if (plain(type).startsWith("f")) 1e-4 else 0.0
    return wanted.any { r -> x >= r.start - slack * maxOf(1.0, kotlin.math.abs(r.start)) && x <= r.endInclusive + slack * maxOf(1.0, kotlin.math.abs(r.endInclusive)) }
}

/**
 * Valor para ler na tela: Float/Double sem casas de sobra (0.1, não 0.10000000000000001; 1e10 por extenso).
 * null = NaN/infinito: esses bytes não guardam um número desse tipo.
 */
internal fun readable(value: String, cipherType: String): String? {
    val type = plain(cipherType)
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
