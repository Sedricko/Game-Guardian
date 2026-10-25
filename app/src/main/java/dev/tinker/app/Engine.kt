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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

val TYPES = listOf("auto" to "Auto", "i32" to "Dword", "f32" to "Float", "i64" to "Qword", "f64" to "Double", "i16" to "Word", "i8" to "Byte")
// xi32/xf32: valor cifrado com a chave do lado (Anti-Cheat Toolkit do Unity); o daemon decifra ao ler e cifra ao escrever
// i32x2/i64x2: inteiro guardado em dobro (Dart/Flutter e JavaScript: 100 fica 200)
// xi32@N/xf32@N: cifrado com a chave N bytes ao lado, como o "150X4" do GameGuardian (N de -64 a 64, de 4 em 4)
private val XOFF = Regex("""x[if]32@-?\d{1,2}""")
private val BASE_NAMES = TYPES.toMap() + mapOf("xi32" to "Dword 🔒", "xf32" to "Float 🔒", "xi64" to "Qword 🔒", "xf64" to "Double 🔒", "i32x2" to "Dword ×2", "i64x2" to "Qword ×2", "li" to "Lua inteiro", "lf" to "Lua decimal")
private val BASE_SIZES = mapOf("i8" to 1, "i16" to 2, "i32" to 4, "i64" to 8, "f32" to 4, "f64" to 8, "xi32" to 4, "xf32" to 4, "xi64" to 8, "xf64" to 8, "i32x2" to 4, "i64x2" to 8, "li" to 8, "lf" to 8)
val TYPE_NAMES: Map<String, String> = object : Map<String, String> by BASE_NAMES {
    override fun get(key: String) = BASE_NAMES[key] ?: if (XOFF.matches(key)) BASE_NAMES[key.substringBefore('@')] + " " + key.substringAfter('@') else null
    override fun containsKey(key: String) = get(key) != null
}
val TYPE_SIZES: Map<String, Int> = object : Map<String, Int> by BASE_SIZES {
    override fun get(key: String) = BASE_SIZES[key] ?: if (XOFF.matches(key)) 4 else null
    override fun containsKey(key: String) = get(key) != null
}

/** Tipo base: Dword 🔒 → Dword (a aba Memória mostra os bytes como estão). */
fun plain(type: String) = when (type) {
    "li" -> "i64"  // número do Lua: 8 bytes, o tipo do Lua fica no byte seguinte
    "lf" -> "f64"
    else -> type.removePrefix("x").substringBefore('@').removeSuffix("x2")
}

/** "150X4" (GameGuardian): valor 150 cifrado com a chave 4 bytes ao lado → (150, 4); null se não é desse jeito. */
internal fun ggXor(value: String): Pair<String, Int>? {
    val m = Regex("""^(.+?)[xX](-?\d{1,2})$""").matchEntire(value.trim()) ?: return null
    val n = m.groupValues[2].toInt()
    // "0x8" é número em hexadecimal, não cifrado
    return if (n != 0 && n % 4 == 0 && n in -64..64 && m.groupValues[1].trimStart('-') != "0") m.groupValues[1] to n else null
}
/** Chave do Gemini que já vem no app (repositório privado, só para uso próprio). */
// a chave que vinha fixa no app (versões até 2.19): sai da lista de quem ainda tem; só o hash fica, para reconhecer
private const val OLD_FIXED_KEY_SHA256 = "55c7b7cfa2a2f58d5da503db4b2d1188f423212cc586a53e75172c9720fc2e31"

// sem link embutido: o repositório é público e o token dá acesso às chaves; a pessoa cola o link em Ajustes
private const val DEFAULT_KEY_SOURCE = ""

private fun sha256(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

val TABS = listOf("Busca", "Salvos", "Memória", "Registro", "Ajustes")
const val TAB_MEMORY = 2
val PANEL_SIZES = listOf(0.5f to "Metade", 0.85f to "Grande", 1f to "Tela cheia")

val REGION_NAMES = mapOf(
    "Jh" to "Java heap", "J" to "Java", "Ch" to "C++ heap", "Ca" to "C++ alloc", "Cd" to "C++ .data",
    "Cb" to "C++ .bss", "A" to "Anônima", "S" to "Pilha", "As" to "Ashmem", "O" to "Outras", "V" to "Vídeo (pode travar o jogo)",
)

// Patch de código: troca bytes de instruções numa lib do jogo (ex.: o SUB que tira 1 da madeira vira NOP). where é
// "lib.so+deslocamento": o código fica sempre no mesmo lugar da lib, então o patch vale em qualquer vez que o jogo abrir.
// orig/new: os bytes em hexadecimal (orig para desfazer); suspect: o jogo fechou logo depois de aplicar, não reaplica sozinho.
data class CodePatch(
    val id: String = java.util.UUID.randomUUID().toString(), val name: String, val where: String, val orig: String, val new: String,
    val enabled: Boolean = false, val suspect: Boolean = false, val note: String = "",
    // ctx: os bytes do código em volta (4 instruções antes, as originais e 4 depois); com o jogo atualizado e a
    // instrução em outro lugar, o patch acha ela de novo por esses bytes e passa a valer no endereço novo
    val ctx: String = "",
)

// Um método IL2CPP: endereço do código, tipo de retorno (código do Unity: 01 void, 02 bool, 08 int, 0a long, 0c float,
// 0d double…), onde fica na lib e o nome.
// Campo de uma tabela do Lua: endereço do valor, tipo (int, num, str, table, bool, func, udata), nome e valor
data class LuaField(val addr: String, val kind: String, val key: String, val value: String)
data class Il2Method(val code: String, val ret: Int, val where: String, val name: String)

/** Tipos de retorno que dá para trocar por um valor fixo (MOV + RET). */
internal val RET_NAMES = mapOf(0x01 to "void", 0x02 to "bool", 0x03 to "char", 0x04 to "sbyte", 0x05 to "byte", 0x06 to "short", 0x07 to "ushort",
    0x08 to "int", 0x09 to "uint", 0x0a to "long", 0x0b to "ulong", 0x0c to "float", 0x0d to "double")

/**
 * Bytes (hex, como ficam na memória) de "o método sempre devolve value" em ARM64: carrega o valor em W0/X0 (ou S0/D0 para
 * float/double) e volta. void: só volta (o método deixa de fazer qualquer coisa). null: tipo que não dá (objeto, texto).
 */
internal fun returnPatch(ret: Int, value: String): String? {
    fun le(w: Int) = (0 until 4).joinToString("") { "%02x".format((w ushr (8 * it)) and 0xff) }
    fun w32(n: Int): List<Int> = when {
        n in 0..0xffff -> listOf(0x52800000 or (n shl 5))
        n.inv() in 0..0xffff -> listOf(0x12800000 or (n.inv() shl 5))  // MOVN: números negativos pequenos
        else -> listOf(0x52800000 or ((n and 0xffff) shl 5), 0x72a00000 or (((n ushr 16) and 0xffff) shl 5))
    }
    fun x64(n: Long): List<Int> {
        val parts = (0 until 4).map { ((n ushr (16 * it)) and 0xffff).toInt() }
        val out = mutableListOf(0xd2800000.toInt() or (parts[0] shl 5))
        val movk = listOf(0, 0xf2a00000.toInt(), 0xf2c00000.toInt(), 0xf2e00000.toInt())
        for (i in 1..3) if (parts[i] != 0) out += movk[i] or (parts[i] shl 5)
        return out
    }
    val ret0 = 0xd65f03c0.toInt()
    val v = value.trim().lowercase()
    val words: List<Int> = when (ret) {
        0x01 -> emptyList()
        0x02 -> w32(if (v in setOf("true", "sim", "1", "verdadeiro")) 1 else if (v in setOf("false", "não", "nao", "0", "falso")) 0 else return null)
        in 0x03..0x09 -> w32(v.toLongOrNull()?.toInt() ?: return null)
        0x0a, 0x0b -> x64(v.toLongOrNull() ?: return null)
        0x0c -> w32(java.lang.Float.floatToRawIntBits(v.toFloatOrNull() ?: return null)) + 0x1e270000  // FMOV S0, W0
        0x0d -> x64(java.lang.Double.doubleToRawLongBits(v.toDoubleOrNull() ?: return null)) + 0x9e670000.toInt()  // FMOV D0, X0
        else -> return null
    }
    return (words + ret0).joinToString("") { le(it) }
}

// Uma instrução na aba Código: endereço, onde fica na lib, os 4 bytes e o que ela faz.
data class CodeRow(val addr: String, val where: String, val word: Int?, val text: String)

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
    // verified=false: ponteiro escolhido sozinho, ainda não confirmado reabrindo o jogo; não é seguido na mesma vez do jogo
    val verified: Boolean = true,
    // history: últimas mudanças "segundos:valor"; seenMin/seenMax: a faixa que o valor já teve (NaN = ainda não sabe)
    val history: List<String> = emptyList(), val seenMin: Double = Double.NaN, val seenMax: Double = Double.NaN,
    // trusted=false: voltou por consenso/assinatura/faixa, sem conferir o valor; não ensina o histórico até ser confirmado
    // (no teste, a Arma voltou com o jogo carregando, leu 0, e a faixa 0–0 depois recusou o valor certo)
    val trusted: Boolean = true,
    // il2: "Namespace.Classe+offset" do objeto IL2CPP onde o valor mora; com ele o salvo volta procurando o objeto pela classe
    val il2: String = "",
    // il2Field: nome do campo ("<Gold>k__BackingField"); o jogo atualiza e o offset muda, o nome não: o salvo acha o offset novo
    val il2Field: String = "",
    // temp: cópia que o jogo cria só para a tela (número solto do C#); some ao fechar o jogo, então não se procura de volta
    val temp: Boolean = false,
    // folder: pasta que a pessoa escolheu (Materiais, Status…); vazio = sem pasta
    val folder: String = "",
    // freezeRule: "" fixo; "up" só deixa subir (o jogo não tira, o que ganhar fica); "down" só deixa descer
    val freezeRule: String = "",
)

const val MAX_CHAINS = 200
const val SIG_SIDE = 40L  // bytes de cada lado do valor na assinatura

/** Distância máxima para dois salvos serem da mesma "ficha" do jogo (estrutura/objeto). */
const val SAME_STRUCT = 4096L

/** Números "embrulhados" do C#: o jogo cria para mostrar o valor e descarta; não são o dado de verdade. */
val BOXED = setOf(
    "System.Int32", "System.Int64", "System.Int16", "System.Byte", "System.SByte", "System.UInt32", "System.UInt64",
    "System.UInt16", "System.Single", "System.Double", "System.Boolean", "System.String", "System.Decimal",
)

/** IL2CPP (Unity): classe de um objeto e os campos dele, lidos da memória do jogo. start = começo do objeto. */
data class Il2Field(val offset: Int, val code: Int, val name: String)
data class Il2Class(val name: String, val start: String, val fields: List<Il2Field>)

/** Classe IL2CPP do jogo (para a lista): nome completo e quantos campos tem. */
data class GameClass(val name: String, val fields: Int, val fieldNames: List<String> = emptyList())

/**
 * A classe bate com alguma das palavras? null: não; vazio: pelo nome da classe; senão os campos que bateram
 * (gold acha PlayerData pelo campo <Gold>k__BackingField).
 */
internal fun classMatch(c: GameClass, words: List<String>): List<String>? {
    if (words.isEmpty()) return emptyList()
    // por palavra do nome (camelCase, _ e .): "ring" acha Rings e m_ringCount, mas não String nem Recruiter;
    // "RunData" e "Cameras.CameraController" acham as palavras em sequência (antes davam 0 e a IA rodava em círculo)
    val queries = words.flatMap { listOf(listOf(it.lowercase()), nameWords(it).map(String::lowercase)) }.filter { it.isNotEmpty() }.distinct()
    fun hit(name: String): Boolean {
        val ws = wordsMemo.getOrPut(name) { nameWords(name).map(String::lowercase) }
        return queries.any { q -> (0..ws.size - q.size).any { i -> q.indices.all { k -> if (k == q.lastIndex) ws[i + k].startsWith(q[k]) else ws[i + k] == q[k] } } }
    }
    val fields = c.fieldNames.filter(::hit)
    return when {
        fields.isNotEmpty() -> fields.map { it.removePrefix("<").substringBefore(">k__BackingField") }
        hit(c.name) -> emptyList()
        else -> null
    }
}

// cada procura passava o regex em todos os campos de 18 mil classes (2,5 s); os nomes se repetem a cada procura
private val wordsMemo = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

/** Palavras de um nome de código: PlayerData.m_ringCount → Player, Data, m, ring, Count. */
internal fun nameWords(name: String): List<String> = Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+""").findAll(name).map { it.value }.toList()

internal fun searchWords(text: String) = text.split(Regex("""[\s,;]+""")).map { it.trim() }.filter { it.length >= 2 }

/** Item de uma lista do jogo (List<T>): referência a outro objeto (abre a ficha dele) ou um número. */
data class ListItem(val index: Int, val addr: String, val label: String, val value: String, val isRef: Boolean)

/**
 * Receita: um passo por campo, guardado pelo nome (classe IL2CPP + offset), não pelo endereço; por isso vale
 * toda vez que o jogo abre e dá para passar para outra pessoa. Jogo sem IL2CPP: o passo vai pelo ponteiro (ptr)
 * ou pela assinatura (sig + sigOff); cls fica vazio.
 */
data class Step(
    val cls: String, val off: Long, val type: String, val value: String, val freeze: Boolean, val label: String,
    val ptr: String = "", val sig: String = "", val sigOff: Int = 0,
    val field: String = "",  // nome do campo IL2CPP: o offset certo é achado por ele se o jogo atualizar
)

/** Motor do jogo, reconhecido pelas bibliotecas do APK; bits do processo (32 ou 64). */
data class GameEngine(val name: String, val bits: Int, val lua: Boolean = false) {
    val il2cpp get() = name == "Unity IL2CPP"
    val header get() = if (bits == 32) 0x8L else 0x10L  // onde começam os campos de um objeto IL2CPP
    override fun toString() = "$name · $bits bits" + if (lua) " · Lua" else ""
}
/** Pedido escrito na aba Pedidos da planilha: só vira ação depois que a pessoa toca em Aplicar no aparelho. */
data class SheetRequest(val id: String, val tipo: String, val jogo: String, val texto: String)

// tipos fixos que a aba Pedidos aceita: anotações e conferências, nada de comando livre nem escrita no jogo
// os únicos que podem ficar "sempre permitidos": só conferem e mandam informação; nota e lição mudam o que a IA faz, pedem o toque toda vez
// versão do Code.gs que este app espera (a mesma constante VERSAO lá): sobe junto quando o script muda
const val SCRIPT_VERSION = 16

val TRUSTABLE_REQUESTS = setOf("autoteste", "relatorio")
val REQUEST_TYPES = mapOf("dica" to "Dica para você", "nota" to "Anotar no jogo", "licao" to "Guardar lição da IA", "autoteste" to "Rodar o autoteste", "relatorio" to "Mandar o relatório")

data class Recipe(val id: String = java.util.UUID.randomUUID().toString(), val name: String, val steps: List<Step>, val auto: Boolean = false)

/** Uma linha da ficha: um campo (ou 4 bytes) em volta do valor, com o tipo provável e o valor lido do jeito certo. */
data class FichaRow(val addr: String, val off: Long, val type: String, val label: String, val value: String)
data class Recent(val types: Set<String>, val value: String)

/** Edição sugerida pela IA: só acontece quando você toca em Aplicar. recipe: ids dos salvos (sugestão de receita, não de edição). */
// pid: a vez do jogo em que o endereço vale; em outra (jogo reabriu), escrever ali pode derrubar o jogo
data class AiProposal(
    val addr: String, val type: String, val value: String, val freeze: Boolean, val label: String, val why: String,
    val recipe: List<String> = emptyList(), val pid: Int = 0,
)

/** O que a IA aplicou, para desfazer: valor de antes, o salvo que a edição criou (sai junto) e como um salvo que já existia estava. */
data class AiUndo(
    val addr: String, val type: String, val old: String, val label: String, val savedId: String?, val pid: Int,
    val before: SavedItem? = null,
)

/**
 * Busca que deu 0: outras tentativas, em ordem — todos os tipos com cifrados; depois o número como faixa (a tela
 * mostra 1250, o jogo guarda 1250.37; ou 12.5 vira 12~13). Cada uma: (tipos, valor, como achou).
 */
internal fun retryPlans(types: String, value: String): List<Triple<String, String, String>> {
    val all = "i32,f32,i64,f64,xi32,xf32"
    val plans = mutableListOf<Triple<String, String, String>>()
    if (types != all) plans += Triple(all, value, "procurando em todos os tipos e nos cifrados")
    // Flutter (Dart) e jogos em JavaScript guardam inteiro em dobro: 100 fica 200
    if (value.toLongOrNull() != null) plans += Triple("i32x2,i64x2", value, "como inteiro em dobro (jogos em Flutter ou JavaScript)")
    // cifrado com a chave em outra posição (o "X4" do GameGuardian), sem a pessoa precisar saber a distância
    if (value.toDoubleOrNull() != null) plans += Triple("xi32@4,xi32@-4,xi32@8,xi32@-8,xf32@4,xf32@-4,xf32@8,xf32@-8", value, "como valor cifrado (chave ao lado)")
    val v = value.toDoubleOrNull()
    if (v != null) {
        val d = maxOf(0.5, kotlin.math.abs(v) * 0.005)
        fun f(x: Double) = java.math.BigDecimal(x).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        plans += Triple("f32,f64", "${f(v - d)}~${f(v + d)}", "como número com casas decimais (${f(v - d)}~${f(v + d)})")
    }
    return plans
}

/** Valor do teste da IA: diferente do atual e plausível (inteiro +1; decimal +1.5), para a tela mostrar a mudança. */
/** Número de um texto lido na tela: "1.234" e "1,234" (milhar) viram 1234; "x23" vira 23; sem dígito, null. */
internal fun screenNumber(text: String): Long? =
    Regex("""\d[\d.,]*""").find(text)?.value?.filter(Char::isDigit)?.take(15)?.toLongOrNull()

/** Refino depois que o número da tela foi de last para cur: faixa do mesmo tamanho para frente, porque até o refino ele anda mais. */
internal fun followRange(last: Long, cur: Long): String {
    val d = kotlin.math.abs(cur - last).coerceAtLeast(1)
    return if (cur >= last) "$cur~${cur + d}" else "${cur - d}~$cur"
}

internal fun testValue(current: Double, type: String): String =
    if (type.endsWith("f32") || type.endsWith("f64")) java.math.BigDecimal(current + 1.5).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    else (current.toLong() + 1).toString()

/** Jeito de comparar pedido pela IA (aumentou, diminuiu, igual, mudou, com ou sem quanto) no comando do daemon. */
internal fun compareOp(how: String, by: String): String? {
    val h = how.trim().lowercase()
    val op = when {
        h.startsWith("aument") || h == "inc" -> "inc"
        h.startsWith("diminu") || h == "dec" -> "dec"
        h.startsWith("igual") || h.startsWith("não mud") || h.startsWith("nao mud") || h == "same" -> "same"
        h.startsWith("mud") || h == "changed" -> "changed"
        else -> return null
    }
    return if (by.isNotBlank() && (op == "inc" || op == "dec")) op + "by" else op
}

/** Salvos citados pela IA: por nome (sem diferença de maiúsculas), endereço, campo IL2CPP ou "todos". */
internal fun matchSaved(wanted: String, saved: List<SavedItem>): List<SavedItem> {
    val keys = wanted.split(',', ';').map { it.trim().lowercase().removePrefix("0x") }.filter { it.isNotEmpty() }
    if (keys.any { it == "todos" }) return saved.filter { !it.temp }
    return saved.filter { s -> !s.temp && keys.any { k -> k == s.name.lowercase() || k == s.addr || k == s.il2.lowercase() || k == s.il2.substringBeforeLast('+').substringAfterLast('.').lowercase() } }
}

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
    var peek by mutableStateOf(false)
    var autoPause by mutableStateOf(true)
    var findEncrypted by mutableStateOf(true)
    var useBar by mutableStateOf(false)
    var autoOverlay by mutableStateOf(true)
    var githubToken by mutableStateOf("")  // token só de leitura do GitHub: precisa quando o repositório é privado
    var autoUpdate by mutableStateOf(false)  // baixa e instala a versão nova sozinho (root) e roda o autoteste depois
    var ownKeypad by mutableStateOf(true)  // teclado do Tinker no lugar do teclado do telefone  // ao abrir o app, já abre o menu flutuante e sai da frente (como o GameGuardian)
    var update by mutableStateOf<Pair<String, String>?>(null)
    var gameGone by mutableStateOf<GameProcess?>(null)
    var gameGoneByUser = false  // fechado pelo botão do Tinker: mostra "Reabrir", mas não abre o painel sozinho  // o jogo fechou sozinho (não pelo Tinker): pergunta se reabre  // versão nova na página de releases: nome e link do APK  // a bolinha abre a barra rápida em vez do painel  // a busca também procura valores cifrados (🔒)
    var lastRefine by mutableStateOf("")  // o número do último refino: refinar de novo com ele não muda nada
    var canUndo by mutableStateOf(false)  // há um refino para desfazer  // pausa o jogo durante busca/refino, para o valor não mudar no meio
    var view by mutableStateOf<Pair<String, String>?>(null)
    var codeMode by mutableStateOf(false)  // aba Memória: instruções (Código)
    var watchHits by mutableStateOf<Pair<String, List<Triple<String, Int, String>>>?>(null)  // último "o que escreve aqui"
    var fichaMode by mutableStateOf(false)  // aba Memória: lista de endereços (false) ou ficha do objeto (true)
    var tab by mutableIntStateOf(0)  // índice em TABS  // visualizador de memória: endereço e tipo  // segurando o "olho": painel quase invisível
    val saved = mutableStateListOf<SavedItem>()
    val recipes = mutableStateListOf<Recipe>()
    val aiProposals = mutableStateListOf<AiProposal>()
    val sheetRequests = mutableStateListOf<SheetRequest>()
    val patches = mutableStateListOf<CodePatch>()
    var release by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var traceHits by mutableStateOf<Pair<String, Pair<Int, List<Triple<String, Int, String>>>>?>(null)  // último contador de chamadas  // valores que liberaram a recompensa (Foto do jogo)
    var alwaysRequests by mutableStateOf(setOf<String>())
    var autoReports by mutableStateOf(true)  // relatório sozinho a cada 10 min de jogo (com atividade nova) e ao fechar o jogo pelo Tinker  // tipos que a pessoa mandou lembrar
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

/** Ponte entre a tela e o daemon; tudo roda fora da thread de UI. Uma instância por processo. */
class Engine private constructor(private val app: Context) {
    companion object {
        @Volatile private var instance: Engine? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: Engine(c.applicationContext).also { instance = it } }
    }

    @Volatile private var daemon: Daemon? = null
    private val daemonStart = Any()
    private var closedPid = 0
    private val freezeFailing = HashSet<String>()  // endereços congelados que estão falhando (avisa uma vez)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // uma lista de salvos por jogo; "saved.json" é a lista de antes de escolher um jogo
    private var savedPkg: String? = null
    private val savedFile get() = File(app.filesDir, savedPkg?.let { "saved-$it.json" } ?: "saved.json")
    private val historyFile get() = File(app.filesDir, "history.txt")
    private val lastGameFile get() = File(app.filesDir, "last-game.txt")
    private var inFile = false  // busca desconhecida ainda no arquivo do daemon
    private var steps = 0  // etapas da busca atual, para o registro
    private val stepLog = mutableListOf<String>()  // as mesmas etapas, para mandar a busca à planilha

    private val prefs = app.getSharedPreferences("tinker", Context.MODE_PRIVATE)

    /** Ajustes ficam guardados: antes voltavam ao padrão toda vez que o app abria. */
    fun saveSettings() {
        prefs.edit().putBoolean("autoPause", Session.autoPause).putBoolean("findEncrypted", Session.findEncrypted)
            .putBoolean("autoPointers", Session.autoPointers).putBoolean("gameLibsOnly", Session.gameLibsOnly)
            .putBoolean("autoOverlay", Session.autoOverlay).putBoolean("ownKeypad", Session.ownKeypad).putBoolean("autoUpdate", Session.autoUpdate).putString("githubToken", Session.githubToken).putFloat("alpha", Session.alpha).putInt("panelSize2", Session.panelSize).putBoolean("panelLeft", Session.panelLeft)
            .remove("aiKey").putString("aiProvider", Session.aiProvider).putBoolean("aiReserve", Session.aiReserve)
            .putString("groqKeys", Session.groqKeys.joinToString("\n")).putInt("groqKeyIndex", Session.groqKeyIndex).putString("groqModel", Session.groqModel)
            .putString("sheetGroqKeys", Session.sheetGroqKeys.joinToString("\n")).putBoolean("aiCanTest", Session.aiCanTest).putBoolean("aiAuto", Session.aiAuto).putBoolean("aiControl", Session.aiControl)
            .putString("geminiKeys", Session.geminiKeys.joinToString("\n")).putInt("geminiKeyIndex", Session.geminiKeyIndex)
            .putString("keySource", Session.keySource).putString("sheetKeys", Session.sheetKeys.joinToString("\n"))
            .putString("geminiModel", Session.geminiModel).putBoolean("geminiAuto", Session.geminiAutoModel).putBoolean("watchFields", Session.watchFields)
            .putString("alwaysRequests", Session.alwaysRequests.joinToString(",")).putBoolean("autoReports", Session.autoReports)
            .putString("geminiUse", quotaDay() + ";" + Session.geminiUse.entries.joinToString(",") { "${it.key}=${it.value}" } + ";" + Session.geminiOut.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putString("geminiUseModel", quotaDay() + ";" + Session.geminiUseModel.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putString("geminiLimit2", Session.geminiLimit.entries.joinToString(",") { "${it.key}=${it.value}" }).putBoolean("geminiCountFull", Session.geminiCountFull)
            .putString("geminiSpeed", Session.geminiSpeed.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
    }

    /** Pede a permissão de sobreposição sozinho só na primeira vez; recusou, o app abre normal nas próximas. */
    fun firstOverlayAsk(): Boolean = !prefs.getBoolean("askedOverlay", false).also { prefs.edit().putBoolean("askedOverlay", true).apply() }

    init {
        Session.autoPause = prefs.getBoolean("autoPause", true)
        Session.findEncrypted = prefs.getBoolean("findEncrypted", true)
        Session.autoPointers = prefs.getBoolean("autoPointers", true)
        Session.gameLibsOnly = prefs.getBoolean("gameLibsOnly", true)
        Session.autoOverlay = prefs.getBoolean("autoOverlay", true)
        Session.ownKeypad = prefs.getBoolean("ownKeypad", true)
        Session.autoUpdate = prefs.getBoolean("autoUpdate", false)
        Session.githubToken = prefs.getString("githubToken", "").orEmpty()
        // o Claude saiu do app (chave paga): quem usava passa para o Gemini
        Session.aiProvider = prefs.getString("aiProvider", "groq").orEmpty().takeIf { it == "gemini" || it == "groq" } ?: "groq"
        // uma vez só: quem já usava passa para o Groq (dá para voltar ao Gemini em Ajustes)
        if (!prefs.getBoolean("groqDefaultGiven", false)) { Session.aiProvider = "groq"; prefs.edit().putBoolean("groqDefaultGiven", true).putString("aiProvider", "groq").apply() }
        Session.aiReserve = prefs.getBoolean("aiReserve", true)
        Session.groqKeys = prefs.getString("groqKeys", "").orEmpty().split('\n').filter { it.isNotBlank() }
        Session.groqKeyIndex = prefs.getInt("groqKeyIndex", 0).coerceIn(0, (Session.groqKeys.size - 1).coerceAtLeast(0))
        Session.groqModel = prefs.getString("groqModel", "").orEmpty()
        Session.sheetGroqKeys = prefs.getString("sheetGroqKeys", "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
        Session.aiCanTest = prefs.getBoolean("aiCanTest", false)
        Session.aiAuto = prefs.getBoolean("aiAuto", false)
        Session.aiControl = prefs.getBoolean("aiControl", false)
        // antes era uma chave só ("geminiKey"): vira a primeira da lista
        Session.geminiKeys = (prefs.getString("geminiKeys", null) ?: prefs.getString("geminiKey", "")).orEmpty().split('\n').filter { it.isNotBlank() }
        Session.geminiKeyIndex = prefs.getInt("geminiKeyIndex", 0).coerceIn(0, (Session.geminiKeys.size - 1).coerceAtLeast(0))
        // a chave que vinha fixa no app sai: as chaves agora vêm do painel (planilha) ou de Ajustes
        Session.geminiKeys.filter { sha256(it) == OLD_FIXED_KEY_SHA256 }.takeIf { it.isNotEmpty() }?.let { old ->
            Session.geminiKeys = Session.geminiKeys - old.toSet()
            Session.geminiKeyIndex = 0
            prefs.edit().putString("geminiKeys", Session.geminiKeys.joinToString("\n")).putInt("geminiKeyIndex", 0).apply()
        }
        Session.geminiModel = prefs.getString("geminiModel", Session.geminiModel).orEmpty().ifBlank { "gemini-2.5-flash" }
        Session.geminiAutoModel = prefs.getBoolean("geminiAuto", true)
        Session.keySource = prefs.getString("keySource", "").orEmpty()
        // implantação arquivada (dava 404) ou token antigo do painel: o padrão novo entra no lugar
        // link padrão antigo (token já trocado, estava no código público): sai, a pessoa cola o novo em Ajustes
        if (Session.keySource.contains("token=f0a0f8a4099e4dbe")) { Session.keySource = ""; prefs.edit().putString("keySource", "").apply() }
        if (Session.keySource.contains("AKfycbyZGlMdkrGm")) { Session.keySource = DEFAULT_KEY_SOURCE; prefs.edit().putString("keySource", Session.keySource).apply() }
        if (!prefs.getBoolean("defaultSourceGiven", false)) {
            if (Session.keySource.isBlank()) Session.keySource = DEFAULT_KEY_SOURCE
            prefs.edit().putBoolean("defaultSourceGiven", true).putString("keySource", Session.keySource).apply()
        }
        Session.sheetKeys = prefs.getString("sheetKeys", "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
        if (Session.keySource.isNotBlank()) scope.launch {
            Diag.add(syncKeys()); Diag.add(cloudPull())
            // o Tinker fechou com erro da última vez: o relatório (com a falha no registro) vai para a planilha
            if (Diag.takeCrashFlag()) sendReport("o Tinker fechou com erro na última vez")
            // jogando: o relatório vai sozinho a cada 10 min, se aconteceu algo novo (a análise na planilha fica em dia)
            launch {
                var sent = Diag.activity
                while (true) {
                    delay(600_000)
                    if (!Session.autoReports || Session.process == null || Diag.activity == sent) continue
                    sent = Diag.activity
                    sendReport("a cada 10 min de jogo")
                }
            }
            // a aba Pedidos é lida a cada 3 min; o que chega só aparece no painel, esperando o toque da pessoa
            while (true) { fetchRequests().takeIf { it.isNotEmpty() }?.let(Diag::add); delay(180_000) }
        }
        Session.watchFields = prefs.getBoolean("watchFields", true)
        Session.autoReports = prefs.getBoolean("autoReports", true)
        Session.alwaysRequests = prefs.getString("alwaysRequests", "").orEmpty().split(',').filter { it in TRUSTABLE_REQUESTS }.toSet()
        prefs.getString("geminiUse", "").orEmpty().split(';').takeIf { it.size == 3 && it[0] == quotaDay() }?.let { (_, use, out) ->
            use.split(',').forEach { e -> e.split('=').takeIf { it.size == 2 }?.let { (k, n) -> k.toIntOrNull()?.let { Session.geminiUse[it] = n.toIntOrNull() ?: 0 } } }
            out.split(',').forEach { e -> e.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, t) -> k.toIntOrNull()?.let { Session.geminiOut[it] = t } } }
        }
        val useDay = prefs.getString("geminiUseModel", null)?.substringBefore(';')
        Session.geminiCountFull = if (useDay == quotaDay()) prefs.getBoolean("geminiCountFull", false) else useDay != null  // dia novo: conta do zero
        prefs.getString("geminiUseModel", "").orEmpty().split(';').takeIf { it.size == 2 && it[0] == quotaDay() }?.get(1)?.split(',')
            ?.forEach { e -> e.split('=').takeIf { it.size == 2 }?.let { (k, n) -> n.toIntOrNull()?.let { Session.geminiUseModel[k] = it } } }
        prefs.getString("geminiLimit2", "").orEmpty().split(',')
            .forEach { e -> e.split('=').takeIf { it.size == 2 }?.let { (m, n) -> n.toIntOrNull()?.let { Session.geminiLimit[m] = it } } }
        prefs.getString("geminiSpeed", "").orEmpty().lines().forEach { l -> l.split('=').takeIf { it.size == 2 }?.let { (m, ms) -> ms.toLongOrNull()?.let { Session.geminiSpeed[m] = it } } }
        Session.alpha = prefs.getFloat("alpha", Session.alpha)
        Session.panelLeft = prefs.getBoolean("panelLeft", false)
        Session.panelSize = prefs.getInt("panelSize2", Session.panelSize).coerceIn(0, PANEL_SIZES.lastIndex)  // chave nova: quem já usava volta uma vez para tela cheia, o novo padrão
        loadSaved()
        loadPendingLessons()
        loadHistory()
        // instalação nova (ou dados limpos): a memória da IA volta sozinha do backup em Download/Tinker
        if (!lessonsFile.exists() && noteFiles.isEmpty()) scope.launch(Dispatchers.IO) {
            val r = restoreMemory()
            if (r.startsWith("Memória importada")) Diag.add("Memória da IA restaurada do backup: $r")
        }
        scope.launch { freezeLoop() }
        scope.launch { watchFields() }
        scope.launch { watchNeighbors() }
        // aberto por horas: confere de novo a cada 30 min (antes só ao abrir, e a versão nova passava despercebida)
        // com "Atualizar e testar sozinho" confere a cada 5 min: o ciclo corrigir → publicar → testar fica rápido
        scope.launch { while (true) { checkUpdate(); delay((if (Session.autoUpdate) 5 else 30) * 60_000L) } }
        // instalou sozinho: roda o autoteste (só leituras, no programa de teste do daemon) e o resultado vai para a planilha
        prefs.getString("testarApos", "").orEmpty().takeIf { it.isNotEmpty() && it == appVersion() }?.let { v ->
            prefs.edit().remove("testarApos").apply()
            scope.launch { delay(15_000); Diag.add("Atualizado sozinho para $v: rodando o autoteste"); runCatching { selfTest() } }
        }
        // o jogo reabriu (PID novo): escolhe de novo sozinho e os salvos voltam pela restauração
        scope.launch {
            while (true) {
                delay(3000)
                if (!Session.busy) runCatching { followGame(startDaemon = false) }.getOrNull()?.let { Session.status = it }
                if (!Session.busy) runCatching { retryRestore() }
                if (!Session.busy) runCatching { autoRecipes() }
            }
        }
    }

    private fun Daemon.send(command: String, quiet: Boolean = false): Daemon.Reply {
        if (!quiet) Diag.add("> $command")
        val t0 = System.nanoTime()
        try {
            val r = call(command)
            if (!quiet) Diag.add("< ok ${r.status}".trimEnd() + " (${(System.nanoTime() - t0) / 1_000_000} ms)")
            return r
        } catch (e: IOException) {
            // comandos de rotina (quiet) falham muito de propósito (ex.: testar caminhos de ponteiro): quem chama decide se é problema
            if (!quiet) Diag.add("< erro: ${e.message} (comando: $command, ${(System.nanoTime() - t0) / 1_000_000} ms)")
            throw e
        }
    }

    private suspend fun <T> call(start: Boolean = true, work: (Daemon) -> T): T = withContext(Dispatchers.IO) {
        // laços de fundo nunca abrem um su novo: o usuário pode ter acabado de tocar em Sair
        // uma trava só para abrir: duas chamadas juntas abriam dois daemons (o registro mostrava "Daemon iniciado" duas vezes)
        val d = daemon ?: if (!start) throw IOException("daemon parado") else synchronized(daemonStart) {
            daemon ?: try {
                Daemon(app.applicationInfo.nativeLibraryDir + "/libtinkerd.so") { Diag.add("[daemon stderr] $it") }.also {
                    daemon = it
                    Diag.add("Daemon iniciado com root: ${runCatching { it.call("info").status }.getOrElse { "info indisponível (${it.message})" }}")
                }
            } catch (e: IOException) {
                Diag.add("Falha ao iniciar o daemon: ${e.message}")
                throw e
            }
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
        Session.gameGoneByUser = false
        Session.gameGone = p
        // salvos com pid continuam congelados: ficam parados até voltar sozinhos quando o jogo reabrir
        Session.saved.replaceAll { if (it.pid == 0) it.copy(frozen = false) else it }
        Session.status = "O jogo fechou. Veja a aba Registro para o motivo."
        // fechou menos de 1 min depois de ligar um patch: o patch é suspeito e não volta sozinho
        if (System.currentTimeMillis() - lastPatchAt < 60_000 && Session.patches.any { it.enabled }) {
            for (i in Session.patches.indices) if (Session.patches[i].enabled) Session.patches[i] = Session.patches[i].copy(enabled = false, suspect = true)
            storePatches()
            Diag.add("O jogo fechou logo depois de um patch de código: os patches ligados ficaram como suspeitos e desligados")
        }
        sendReport("o jogo fechou sozinho", auto = true)
        Diag.add("=== O jogo ${p.label} (PID ${p.pid}) fechou. Falhas registradas pelo Android: ===")
        runCatching { dedupe(crashesOf(d.call("crashlog").lines, p.pkg)).forEach(Diag::add) }
        Diag.add("=== Últimas linhas do logcat do jogo ===")
        runCatching { dedupe(d.call("logs").lines).forEach(Diag::add) }
        Diag.add("=== Fim do relatório da queda ===")
        Diag.add("Dica: se o jogo fechou logo depois de editar ou congelar, provavelmente o endereço não era o valor certo " +
            "(ou era de outro tipo). Refine mais vezes até sobrar 1 resultado antes de editar.")
    }

    /**
     * Versão nova na página de releases? Só avisa; sem internet ou com o GitHub fora do ar, não aparece nada
     * e o Tinker funciona igual.
     */
    @Volatile private var updateSeen = ""
    @Volatile private var apkApiUrl = ""  // repositório privado: o APK só vem pela API, com o token

    /** Botão "Procurar atualização" (aba Registro): confere agora e diz o resultado, mesmo que o aviso já tenha sido fechado. */
    suspend fun checkUpdateNow(): String = withContext(Dispatchers.IO) {
        updateSeen = ""
        checkUpdate().fold(
            { Session.update?.let { (v, _) -> "Versão nova: $v. O aviso com o botão para baixar está no alto do painel." } ?: "Você já está na versão mais nova (${appVersion()})." },
            { "Não consegui ver agora (${it.message ?: "sem internet?"}). Tente de novo." },
        )
    }

    private fun checkUpdate() = runCatching {
        val c = java.net.URL("https://api.github.com/repos/Sedricko/Game-Guardian/releases/latest").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "Tinker")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        Session.githubToken.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val latest = json.getString("tag_name").removePrefix("v")
        val mine = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        val assets = json.optJSONArray("assets")
        val apkAsset = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull { it.optString("name").startsWith("Tinker-") && it.optString("name").endsWith(".apk") }  // o release também leva o JogoTeste.apk
        val apk = apkAsset?.optString("browser_download_url") ?: json.getString("html_url")
        apkApiUrl = apkAsset?.optString("url").orEmpty()
        // a mesma versão avisa uma vez só: se a pessoa fechou o aviso, ele não volta a cada 30 min
        if (mine.isNotEmpty() && newer(latest, mine) && latest != updateSeen) {
            updateSeen = latest
            Session.update = latest to apk
            Diag.add("Versão nova disponível: $latest (instalada: $mine)")
            if (Session.autoUpdate) runCatching { autoInstall(latest, apk) }.onFailure { Diag.add("Atualizar sozinho falhou: ${it.message}") }
        }
    }

    /**
     * Atualizar sozinho (Ajustes, desligado de fábrica): baixa o APK do Releases, confere que é o Tinker dessa versão e
     * instala com o root. O Android recusa sozinho um APK com assinatura diferente da instalada.
     */
    private fun autoInstall(version: String, url: String) {
        require(url.endsWith(".apk")) { "o release não tem APK" }
        val f = File(app.cacheDir, "update.apk")
        val token = Session.githubToken.takeIf { it.isNotBlank() && apkApiUrl.isNotEmpty() }
        val c = java.net.URL(if (token != null) apkApiUrl else url).openConnection() as java.net.HttpURLConnection
        token?.let { c.setRequestProperty("Authorization", "Bearer $it"); c.setRequestProperty("Accept", "application/octet-stream") }
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "Tinker")
        c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
        val info = app.packageManager.getPackageArchiveInfo(f.path, 0)
        require(info?.packageName == app.packageName && info?.versionName == version) { "o APK baixado não é o Tinker $version" }
        prefs.edit().putString("testarApos", version).commit()
        Diag.add("Instalando sozinho o Tinker $version")
        val tmp = "/data/local/tmp/tinker-update.apk"
        // am start no fim: o pm install fecha o Tinker; o UpdatedReceiver também reabre caso este comando morra junto
        ProcessBuilder("su", "-c", "cat '${f.path}' > $tmp && chmod 644 $tmp && pm install -r $tmp; rm -f $tmp '${f.path}'; " +
            "am start -n ${app.packageName}/.MainActivity").redirectErrorStream(true).start()
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

    /**
     * O jogo da última vez está aberto, ou reabriu com outro PID? Escolhe ele sozinho.
     * startDaemon=false só olha se o daemon já está rodando (não pede root sem o usuário abrir o Tinker).
     */
    /** Motor pelas bibliotecas: as extraídas na pasta lib e as de dentro dos APKs (quando o Android não extrai). */
    private fun detectEngine(pkg: String): GameEngine? {
        val info = runCatching { app.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val libs = HashSet<String>()
        info.nativeLibraryDir?.let { File(it).list()?.let(libs::addAll) }
        (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).forEach { apk ->
            runCatching { java.util.zip.ZipFile(apk).use { z -> z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }.forEach { libs += it.substringAfterLast('/') } } }
        }
        val dir = info.nativeLibraryDir.orEmpty().substringAfterLast('/')
        val bits = if (dir == "arm" || dir == "x86") 32 else 64
        fun has(vararg n: String) = libs.any { l -> n.any { l.startsWith(it) } }
        val name = when {
            has("libil2cpp.so") -> "Unity IL2CPP"
            has("libmonobdwgc", "libmono.so") -> "Unity Mono"
            has("libunity.so") -> "Unity"
            has("libUE4.so", "libUnreal.so", "libUE5") -> "Unreal"
            has("libcocos") -> "Cocos"
            has("libgodot") -> "Godot"
            has("libyoyo.so") -> "GameMaker"
            has("libflutter.so") -> "Flutter"
            has("libgdx.so") -> "libGDX (Java)"
            libs.none { it.endsWith(".so") } -> "Java/Kotlin"
            else -> "motor próprio"
        }
        // Lua 5.3/5.4 (xLua, ToLua, SLua): a Ficha Lua lê as tabelas; LuaJIT guarda diferente e fica de fora
        return GameEngine(name, bits, has("libxlua", "libtolua", "libslua", "libulua", "liblua5", "liblua.so"))
    }

    suspend fun followGame(startDaemon: Boolean): String? {
        if (daemon == null && !startDaemon) return null
        // o app e o menu flutuante chamam juntos ao abrir: um só escolhe o jogo (a restauração leva alguns segundos)
        if (!following.compareAndSet(false, true)) return null
        try {
            return followOnce(startDaemon)
        } finally {
            following.set(false)
        }
    }

    private val following = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun followOnce(startDaemon: Boolean): String? {
        val pkg = Session.process?.pkg ?: runCatching { lastGameFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val pid = call(start = startDaemon) { d ->
            val pid = d.send("ps", quiet = true).lines.firstNotNullOfOrNull { l -> l.substringBefore(' ').toIntOrNull()?.takeIf { l.substringAfter(' ') == pkg } }
            // o jogo sumiu sem nenhum comando falhar (estava só aberto): percebe aqui e pergunta se reabre
            if (pid == null && Session.process != null) gameClosed(d)
            pid
        } ?: return null
        if (pid == Session.process?.pid) return null
        val pm = app.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        Diag.add(if (Session.process == null) "O jogo da última vez está aberto: escolhido sozinho" else "O jogo reabriu (PID $pid): escolhido de novo sozinho")
        val msg = attach(GameProcess(pid, pkg, pm.getApplicationLabel(info).toString(), info.loadIcon(pm).toImageBitmap()))
        Session.count = null
        Session.results = emptyList()
        return msg
    }

    /** Reabre o jogo que fechou; o laço de 3 s se liga a ele e os salvos voltam sozinhos. */
    fun relaunch(): String {
        val p = Session.gameGone ?: Session.process ?: return "Nenhum jogo para reabrir."
        Session.gameGone = null
        val intent = app.packageManager.getLaunchIntentForPackage(p.pkg) ?: return "Não achei como abrir ${p.label}."
        // com a permissão de sobreposição o Android deixa abrir outro app mesmo do menu flutuante
        return runCatching {
            app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Diag.add("Reabrindo ${p.label} pelo Tinker")
            "Abrindo ${p.label}… os salvos voltam sozinhos."
        }.getOrElse { "Não abriu ${p.label}: ${it.message}" }
    }

    suspend fun attach(p: GameProcess): String = call { d ->
        Diag.add("Jogo escolhido: ${p.label} (${p.pkg}, PID ${p.pid})")
        Session.gameGone = null
        runCatching { lastGameFile.writeText(p.pkg) }
        attachedAt = System.currentTimeMillis()
        retries = 0
        gaveUp = false
        lastSeen.clear()
        d.send("attach ${p.pid}")
        Session.process = p
        Session.paused = false
        Session.canUndo = false
        switchSaved(p.pkg)
        Session.aiProposals.clear()  // endereços da vez anterior do jogo
        scope.launch { if (Session.keySource.isNotBlank()) Diag.add(cloudPull()) }  // o que outro aparelho achou deste jogo já chega
        scope.launch { delay(3000); reapplyPatches() }  // patches de código ligados voltam sozinhos
        Session.aiUndo.clear()
        AiConversations.clear()
        Session.engine = detectEngine(p.pkg)?.also { Diag.add("Motor do jogo: $it") }
        // a restauração roda em segundo plano (a cada 3 s): aqui ela segurava a escolha do jogo por até 45 s
        Session.restoreInfo = if (Session.saved.any(::stale)) "Procurando os salvos da última vez…" else ""
        "Pronto. Digite um valor que você vê no jogo e toque em Nova busca."
    }

    /** Roda work com o jogo pausado (se a opção estiver ligada) e sempre continua o jogo depois. */
    private fun <T> Daemon.frozenGame(few: Boolean = false, work: () -> T): T {
        // poucos candidatos (refino rápido, em milissegundos): não pausa; pausar e continuar o jogo dava um tranco à toa
        val auto = Session.autoPause && !Session.paused && !few
        if (auto) send("pause", quiet = true)
        try {
            return work()
        } finally {
            if (auto) runCatching { send("resume", quiet = true) }
        }
    }

    /** types vazio = Auto. */
    suspend fun search(types: Set<String>, value: String): String = call { d ->
        val lua = if ("li" in types) listOf("li", "lf") else emptyList()  // número do Lua (inteiro e decimal juntos)
        val base = TYPES.map { it.first }.filter { it in types }.ifEmpty { if (lua.isEmpty()) listOf("i32", "f32", "i64", "f64") + engineTypes() else emptyList() }
        // cifrados junto, sem o usuário precisar saber: Dword 🔒 quando busca Dword, Float 🔒 quando busca Float
        // "150X4" (como no GameGuardian): só o cifrado com a chave nessa distância
        val gg = ggXor(value)
        val list = if (gg != null) base.filter { it == "i32" || it == "f32" }.ifEmpty { listOf("i32", "f32") }.joinToString(",") { "x$it@${gg.second}" }
            // Auto começa sem os 🔒 (eram a maior parte dos falsos); se não achar, a nova tentativa inclui eles
            else (base + (if (Session.findEncrypted && types.isNotEmpty()) base.filter { it != "i8" && it != "i16" }.map { "x$it" } else emptyList()) + lua).joinToString(",")
        val term = gg?.first ?: value
        // "100;250::64": busca em grupo, os valores todos a até 64 bytes um do outro (como no GameGuardian)
        val group = term.substringAfter("::", "").trim()
        if ("::" in term) require(Regex("""\d{1,5}""").matches(group) && ';' in term.substringBefore("::")) {
            "Busca em grupo: valores separados por ; e a distância em bytes depois de ::, ex.: 100;250::64"
        }
        fun run() = d.frozenGame {
            if ("::" in term) d.send("group $list ${term.substringBefore("::").arg()} $group") else d.send("search $list ${term.arg()}")
        }.status
        // regiões onde este jogo guarda os valores (salvos e "onde o jogo escreve"): começa só nelas; nada lá, busca em tudo
        val focus = if (Session.regions.any { !it.enabled }) emptySet() else focusRegions()
        var where = ""
        var n = if (focus.isEmpty()) run() else {
            d.send("ranges ${focus.joinToString(",")}", quiet = true)
            try { run() } finally { d.send("ranges all", quiet = true) }
        }
        if (focus.isNotEmpty()) {
            if (n == "0") n = run() else where = " (só em ${focus.joinToString()}: as regiões deste jogo)"
        }
        // nada: tenta sozinho do jeito que a pessoa tentaria (todos os tipos, cifrados, número arredondado na tela)
        var found = n
        var how = ""
        if (n == "0" && "::" !in value && gg == null) for ((l, v, why) in retryPlans(list, normalizeNumbers(value))) {
            found = d.frozenGame { d.send("search $l $v") }.status
            if (found != "0") { how = why; break }
        }
        Session.canUndo = false
        Session.lastRefine = ""
        addRecent(Recent(types, value.trim()))
        inFile = false
        step(true, value.trim() + if (how.isEmpty()) "" else " ($how)", found)
        if (how.isNotEmpty()) "Não achei como pedido; achei $found $how. Mude o valor no jogo e refine."
        else if (found == "0") "Nada encontrado (tentei também todos os tipos, cifrados com a chave em qualquer lado, em dobro e o número arredondado). Dicas: use Auto; se o número da tela é arredondado (1.2K), busque uma faixa como 1150~1250; ou marque mais regiões em Ajustes." + byName
        else "$found encontrados$where. Mude o valor no jogo e refine."
    }

    suspend fun refine(value: String): String = call { d ->
        val before = Session.count
        val n = d.frozenGame(few = (before ?: Long.MAX_VALUE) < 20_000) { d.send("refine ${value.arg()}") }.status
        Session.canUndo = true
        Session.lastRefine = value.trim()
        step(false, value.trim(), n)
        when {
            n == "0" -> "Nenhum restou. O valor pode ter mudado de novo antes do refino: toque em Desfazer e refine com o valor atual." + byName +
                focusRegions().takeIf { it.isNotEmpty() && Session.regions.none { r -> !r.enabled } }?.let {
                    " A busca começou só em ${it.joinToString()}; se o valor estiver em outra região, esqueça as regiões em Mais formas → Onde o jogo escreve."
                }.orEmpty()
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
        val n = d.frozenGame(few = !inFile && (before ?: Long.MAX_VALUE) < 20_000) { d.send("compare $op ${if (value.isBlank()) "" else value.arg()}".trimEnd()) }.status
        // enquanto os candidatos estão no arquivo do daemon (milhões) não há desfazer
        Session.canUndo = !inFile
        inFile = inFile && n.toLong() > 2_000_000
        step(false, COMPARE_NAMES.getValue(op) + if (value.isBlank()) "" else " $value", n)
        when {
            n == "0" -> "Nenhum restou. Toque em Desfazer, ou comece de novo sem valor." + byName
            n.toLong() > 2_000_000 -> "$n candidatos. Continue: mude o valor no jogo e compare de novo."
            // os que sobram não mudam com "não mudou": o valor precisa mudar no jogo antes
            n == before?.toString() && op == "same" -> "Continuam $n. \"Não mudou\" não tira mais nenhum: faça o valor mudar no jogo e toque em Aumentou ou Diminuiu."
            n == before?.toString() -> "Continuam $n: todos mudaram desse jeito. Mude o valor de novo, de outro jeito (ex.: gaste em vez de ganhar)."
            else -> "$n restantes."
        }
    }

    // Unity IL2CPP: o valor pode estar guardado de outro jeito (casas decimais, número grande dividido); pelo nome sempre acha
    private val byName get() = if (Session.engine?.il2cpp == true)
        " Ou ache pelo nome: Memória → Ficha → Ver todas as classes do jogo, filtre por gold, coin, money ou o nome do recurso." else ""

    private fun step(new: Boolean, what: String, n: String) {
        steps = if (new) 1 else steps + 1
        if (new) stepLog.clear()
        stepLog += "$what → $n"
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

    // Ficha e IL2CPP

    /** Classe IL2CPP do objeto que contém addr (null: o jogo não é IL2CPP ou o valor não está num objeto). */
    suspend fun il2cpp(addr: String): Il2Class? = call { d -> d.il2(addr) }

    private fun Daemon.il2(addr: String): Il2Class? {
        val r = runCatching { send("il2cpp $addr", quiet = true) }.getOrNull() ?: return null
        val head = r.lines.firstOrNull { it.startsWith("class ") }?.removePrefix("class ")?.split(" ") ?: return null
        val fields = r.lines.filter { it.startsWith("field ") }.mapNotNull { l ->
            val f = l.removePrefix("field ").split(" ", limit = 3)
            Il2Field(f[0].toIntOrNull(16) ?: return@mapNotNull null, f[1].toIntOrNull(16) ?: 0, f.getOrElse(2) { "?" })
        }.sortedBy { it.offset }
        return Il2Class(head[0], head.getOrElse(1) { addr }, fields)
    }

    /** Lê a ficha: `bytes` a partir de start, numa leitura só; os tipos vêm do IL2CPP quando há, senão são adivinhados. */
    suspend fun ficha(start: String, bytes: Int, il2: Il2Class?, center: String): List<FichaRow> = call { d ->
        val s0 = start.toLong(16) / 8 * 8
        val words = (bytes + 7) / 8
        val raw = ByteArray(words * 8)
        d.send("dump ${s0.toString(16)} i64 $words", quiet = true).lines.forEachIndexed { i, l ->
            val v = l.substringAfter(' ').toLongOrNull() ?: 0L
            for (k in 0 until 8) raw[i * 8 + k] = (v shr (8 * k)).toByte()
        }
        // sem IL2CPP, o offset conta do próprio valor (0 = ele; + depois, - antes)
        val base = il2?.start?.toLong(16) ?: center.toLong(16)
        fichaRows(s0, raw, base, il2)
    }

    /** Salvo novo dentro de um objeto IL2CPP: ganha o nome do campo (ex.: Player.hp) se ainda não tem nome. */
    private suspend fun nameFromIl2cpp(item: SavedItem) = call { d -> d.tagIl2(item) }

    /** Nome do campo de um endereço ("PlayerData.UpgradeStone"), ou "cópia temporária (Int32)" quando é número solto do C#. */
    suspend fun il2Names(addrs: List<String>): Map<String, String> = call { d ->
        val lua = Session.engine?.lua == true
        addrs.associateWith { a ->
            d.il2(a)?.let { c -> fieldLabel(c, a) }
                // jogo com Lua: o nome do campo da tabela do Lua em que o número está (kills, gold…), sem abrir a Ficha
                ?: if (lua) runCatching { d.send("luatable $a", quiet = true).lines }.getOrDefault(emptyList())
                    .firstOrNull { it.startsWith("lua $a ") }?.split(' ', limit = 5)?.getOrNull(3)?.let { "🌙 $it" }.orEmpty() else ""
        }.filterValues { it.isNotEmpty() }
    }

    private fun fieldLabel(c: Il2Class, addr: String): String {
        if (c.name in BOXED) return "cópia temporária (${c.name.substringAfterLast('.')})"
        val off = addr.toLong(16) - c.start.toLong(16)
        val f = c.fields.firstOrNull { it.offset.toLong() == off } ?: c.fields.lastOrNull { it.offset <= off && off < it.offset + 8 }
        return "${c.name.substringAfterLast('.')}.${f?.name ?: "+0x" + off.toString(16)}"
    }

    /**
     * Offset de agora do campo pelo nome, lido no próprio objeto: jogo atualizado mudou o campo de lugar e o salvo
     * (ou a receita) acompanha sozinho, sem IA. Sem nome ou sem achar, fica o offset guardado.
     */
    private fun Daemon.liveOffset(obj: Long, field: String, old: Long): Long {
        if (field.isEmpty()) return old
        val head = Session.engine?.header ?: 0x10L
        val c = il2((obj + head).toString(16)) ?: return old
        return c.fields.firstOrNull { it.name == field }?.offset?.toLong() ?: old
    }

    /** Marca o salvo com a classe e o offset IL2CPP (e o nome do campo, se ele não tem nome). */
    private fun Daemon.tagIl2(item: SavedItem) {
        // número solto do C# (System.Int32...): é cópia que o jogo cria para mostrar na tela e joga fora; não volta pela classe
        val c = il2(item.addr) ?: return
        if (c.name in BOXED) {
            if (!item.temp) Diag.add("Salvo 0x${item.addr} é cópia temporária (${c.name}): mostra o valor na tela, não volta ao reabrir")
            replace(item) { it.copy(temp = true) }
            return
        }
        val off = item.addr.toLong(16) - c.start.toLong(16)
        val f = c.fields.firstOrNull { it.offset.toLong() == off } ?: c.fields.lastOrNull { it.offset <= off && off < it.offset + 8 } ?: return
        val name = "${c.name.substringAfterLast('.')}.${f.name.removePrefix("<").substringBefore(">k__BackingField")}"
        val tag = "${c.name}+${off.toString(16)}"
        if (!IL2.matches(tag)) return
        if (item.name.isEmpty()) Diag.add("Salvo 0x${item.addr} é o campo $name (IL2CPP)")
        replace(item) { it.copy(name = it.name.ifEmpty { name }, il2 = tag, il2Field = f.name.takeIf { n -> FIELD.matches(n) }.orEmpty()) }
        // a IA fica sabendo nas próximas conversas, sem depender dela lembrar de anotar (se ainda não sabe desse campo)
        notesFile?.let { nf ->
            val known = nf.takeIf { it.exists() }?.readText().orEmpty()
            if (name !in known && "${c.name.substringAfterLast('.')}.${f.name}" !in known)
                appendNote(nf, if (item.name.isEmpty()) "Campo salvo: $name (${item.type})" else "${item.name} = $name (${item.type})", 8000)
        }
    }

    /**
     * IL2CPP: procura os objetos da classe pelo nome e vai ao mesmo offset. Não depende de caminho de ponteiro
     * (no teste, o UpgradeStone tinha só 6 caminhos e nenhum sobreviveu ao reabrir). Vale o único objeto com o
     * último valor, ou o único dentro do histórico, ou o único objeto da classe (as classes de dados do jogador costumam ter um só).
     */
    private fun Daemon.fromIl2cpp(item: SavedItem, pid: Int, why: MutableList<String>): String? {
        if (item.il2.isEmpty()) return null
        val cls = item.il2.substringBeforeLast('+')
        val saved = item.il2.substringAfterLast('+').toLongOrNull(16) ?: return null
        val objs = runCatching { send("il2objs $cls", quiet = true).lines }.getOrElse { return null.also { _ -> why += "IL2CPP: ${it.message}" } }
            .mapNotNull { it.removePrefix("obj ").toLongOrNull(16) }
        if (objs.isEmpty()) return null.also { why += "IL2CPP: nenhum objeto $cls agora" }
        val off = liveOffset(objs[0], item.il2Field, saved)
        if (off != saved) {
            Diag.add("${label(item)}: o campo ${item.il2Field} mudou de +${saved.toString(16)} para +${off.toString(16)} (jogo atualizado); salvo corrigido")
            replace(item) { it.copy(il2 = "$cls+${off.toString(16)}") }
        }
        val at = objs.map { (it + off).toString(16) }
        fun read(a: String) = runCatching { send("read $a ${item.type}", quiet = true).status }.getOrDefault("?")
        val wanted = telling(item)
        val same = if (wanted == null) emptyList() else at.filter { matchesAny(read(it), item.type, wanted) }
        val fits = at.filter { a -> read(a).let { it != "?" && plausible(item, it) } }
        val addr = same.singleOrNull() ?: fits.singleOrNull() ?: at.singleOrNull()
            ?: return null.also { why += "IL2CPP: ${objs.size} objetos $cls (${same.size} com o último valor, ${fits.size} dentro do histórico)" }
        if (moveTo(item, addr, "IL2CPP").pid != pid) return null
        replace(item) { it.copy(trusted = same.singleOrNull() == addr) }
        Diag.add("${label(item)} achado pela classe IL2CPP $cls em 0x$addr")
        return "classe $cls"
    }

    /**
     * Todas as classes do jogo com campos, as do próprio jogo primeiro (as de bibliotecas — System, Unity, anúncios… — no fim);
     * genéricas e geradas pelo compilador (`, <) ficam de fora. Sem digitar nome: é só escolher.
     */
    private var classCache: Triple<Int, Long, List<GameClass>>? = null

    /**
     * Classes do jogo (leva alguns segundos): a tela e a IA usam a mesma lista, guardada por 2 minutos no mesmo processo
     * (o jogo carrega classes novas ao trocar de tela).
     */
    suspend fun listClasses(): List<GameClass> {
        // mesma vez do jogo: responde na hora com a lista que tem; passou de 10 min, relê por trás (classes novas carregadas)
        classCache?.takeIf { it.first == currentPid }?.let { c ->
            if (System.currentTimeMillis() - c.second > 600_000 && classRefresh?.isActive != true) classRefresh = scope.launch {
                runCatching { readClasses() }.onSuccess { if (currentPid == c.first) { classCache = Triple(c.first, System.currentTimeMillis(), it); classesToDisk(it) } }
            }
            return c.third
        }
        // primeira vez nesta abertura do jogo: vale a lista guardada da mesma versão do jogo (ler da memória leva 8 a 33 s)
        if (classCache?.first != currentPid) classesFromDisk()?.let { classCache = Triple(currentPid, System.currentTimeMillis(), it); return it }
        return readClasses().also { classCache = Triple(currentPid, System.currentTimeMillis(), it); classesToDisk(it) }
    }

    private var classRefresh: kotlinx.coroutines.Job? = null

    /** Atalho sem IA: cada objeto vivo da classe do salvo e o valor do campo nele (ex.: o jogo cria um RunData novo a cada corrida). */
    suspend fun liveValues(item: SavedItem): List<Pair<String, String>> = call { d ->
        val cls = item.il2.substringBeforeLast('+')
        val off = item.il2.substringAfterLast('+').toLongOrNull(16) ?: return@call emptyList()
        runCatching { d.send("il2objs $cls", quiet = true).lines }.getOrDefault(emptyList()).mapNotNull { it.removePrefix("obj ").toLongOrNull(16) }
            .take(100).map { (it + off).toString(16) }
            .map { a -> a to runCatching { d.send("read $a ${item.type}", quiet = true).status }.getOrDefault("?") }
            .filter { it.second != "?" }
    }

    /** Passa o salvo para outro objeto da mesma classe; o congelamento continua nele. */
    fun retarget(item: SavedItem, addr: String): String {
        val moved = moveTo(item, addr, "Atalho")
        return if (moved.addr == addr) "${label(item)} agora no objeto 0x$addr" else "Outro salvo já está nesse endereço."
    }

    /**
     * Mapa de campos: com o jogo aberto, a cada minuto lê duas vezes (3 s) um objeto das classes conhecidas (dos salvos e das
     * abertas) e anota o campo que mudou em 3 olhadas. Sem IA, sem cota; a próxima conversa já sabe o que mexe jogando.
     */
    private suspend fun watchFields() {
        val seen = HashMap<String, Int>()
        while (true) {
            delay(60_000)
            if (!Session.watchFields || Session.process == null || Session.busy || Session.engine?.il2cpp != true) continue
            val head = Session.engine?.header ?: 0x10L
            val classes = (Session.saved.filter { it.il2.isNotEmpty() && !stale(it) }.map { it.il2.substringBeforeLast('+') } + Session.classes).distinct().take(4)
            for (cls in classes) runCatching {
                if (!CLASS_NAME.matches(cls)) return@runCatching
                val obj = call { d -> d.send("il2objs $cls", quiet = true).lines }.firstNotNullOfOrNull { it.removePrefix("obj ").toLongOrNull(16) } ?: return@runCatching
                val (c, center, before) = objectRows((obj + head).toString(16))
                if (c == null) return@runCatching
                delay(3000)
                val old = before.associateBy { it.addr }
                objectRows(center).third.filter { r -> r.type in WATCH_TYPES && old[r.addr]?.let { it.value != r.value } == true }.forEach { r ->
                    val key = "${c.name.substringAfterLast('.')}.${r.label.substringBefore(" · ").removePrefix("<").substringBefore(">k__BackingField")}"
                    if (seen.merge(key, 1, Int::plus) == 3) notesFile?.let { appendNote(it, "Muda enquanto joga: $key (+${r.off.toString(16)}, ${r.type})", 8000) }
                }
            }
        }
    }

    /**
     * Vizinhos dos salvos: com o jogo aberto, a cada 5 s lê 100 Dwords acima e 100 abaixo de cada salvo (até 4) e guarda o
     * que mudou (quantas vezes e os valores). Vale para qualquer jogo (o mapa de campos acima é só IL2CPP); vai no relatório.
     */
    private class Neighbor(var changes: Int = 0, val values: MutableList<String> = mutableListOf())
    private val neighbors = java.util.concurrent.ConcurrentHashMap<String, java.util.TreeMap<Long, Neighbor>>()
    private var neighborPid = 0

    private suspend fun watchNeighbors() {
        val last = HashMap<String, List<Long?>>()
        while (true) {
            delay(5000)
            if (!Session.watchFields || Session.process == null || Session.busy) continue
            if (currentPid != neighborPid) { neighborPid = currentPid; neighbors.clear(); last.clear() }  // jogo reaberto: endereços novos
            for (item in Session.saved.filter { !stale(it) }.take(4)) runCatching {
                val a = item.addr.toLong(16)
                val start = (a - 400).coerceAtLeast(0)
                val now = call { d -> d.send("dump ${start.toString(16)} i32 201", quiet = true).lines }.map { it.substringAfter(' ').toLongOrNull() }
                val key = "${item.name.ifEmpty { "0x${item.addr}" }} (0x${item.addr})"
                val before = last.put(key, now) ?: return@runCatching
                if (before.size != now.size) return@runCatching
                val map = neighbors.getOrPut(key) { java.util.TreeMap() }
                now.forEachIndexed { i, v ->
                    if (v == null || before[i] == null || v == before[i]) return@forEachIndexed
                    val n = map.getOrPut(start + i * 4L - a) { Neighbor(values = mutableListOf(shown(before[i]!!))) }
                    n.changes++
                    shown(v).let { if (n.values.lastOrNull() != it) n.values += it }
                    if (n.values.size > 8) n.values.removeAt(0)
                }
            }
        }
    }

    // número que parece Float (ex.: 1065353216 = 1.0) aparece como Float; o resto como inteiro
    private fun shown(v: Long): String {
        val i = v.toInt()
        val f = java.lang.Float.intBitsToFloat(i)
        return if (i in -1_000_000..1_000_000 || !f.isFinite() || kotlin.math.abs(f) !in 1e-4f..1e7f) i.toString() else "%.3g".format(java.util.Locale.US, f) + "f"
    }

    private fun neighborsText(): String = buildString {
        neighbors.forEach { (key, map) ->
            if (map.isEmpty()) return@forEach
            appendLine("$key:")
            map.entries.sortedByDescending { it.value.changes }.take(40).sortedBy { it.key }.forEach { (off, n) ->
                appendLine("  ${if (off < 0) "-0x${(-off).toString(16)}" else "+0x${off.toString(16)}"}: ${n.values.joinToString(" → ")} (${n.changes}x)")
            }
        }
    }

    private val classesFile get() = savedPkg?.let { File(app.filesDir, "classes-$it.txt") }
    private fun gameVersion() = savedPkg?.let { p -> runCatching { app.packageManager.getPackageInfo(p, 0).longVersionCode }.getOrNull() }

    /** Lista guardada: primeira linha é a versão do jogo (atualizou, a lista muda e é lida de novo). */
    private fun classesFromDisk(): List<GameClass>? = runCatching {
        val f = classesFile?.takeIf { it.exists() } ?: return null
        val v = gameVersion() ?: return null
        val lines = f.readLines()
        if (lines.firstOrNull() != "v$v") return null
        lines.drop(1).mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 2) null else GameClass(p[0], p[1].toIntOrNull() ?: 0, p.getOrNull(2)?.split(',')?.filter { it.isNotEmpty() }.orEmpty())
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun classesToDisk(list: List<GameClass>) {
        val f = classesFile ?: return
        val v = gameVersion() ?: return
        runCatching { f.writeText("v$v\n" + list.joinToString("\n") { "${it.name}\t${it.fields}\t${it.fieldNames.joinToString(",")}" }) }
    }

    private suspend fun readClasses(): List<GameClass> = call { d ->
        d.send("il2classes").lines.mapNotNull { l ->
            val f = l.removePrefix("class ").split(" ")
            if (f.size < 3 || '`' in f[0] || '<' in f[0] || !CLASS_NAME.matches(f[0])) null else GameClass(f[0], f[2].toIntOrNull() ?: 0, f.getOrNull(3)?.split(',')?.filter { it.isNotEmpty() }.orEmpty())
        }.groupBy { it.name }.map { (_, same) -> same.maxBy { it.fields } }
            .sortedWith(compareBy<GameClass> { c -> LIBS.any { c.name.startsWith(it) } }.thenBy { it.name.substringAfterLast('.').lowercase() })
    }

    // Assistente de IA

    /**
     * Pergunta ao assistente com o contexto da tela e as ferramentas (ele mesmo procura classes, abre objetos e busca valores).
     * chat guarda a conversa para Continuar. O registro guarda a pergunta, as ferramentas usadas e o começo da resposta.
     */
    suspend fun askAi(chat: AiChat, context: suspend () -> String, question: String, onStep: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        Diag.add("Assistente (${Session.aiProvider}${if (chat.started) ", continuando" else ""}): ${question.take(200)}")
        withContext(Dispatchers.Main) { Session.aiProposals.clear() }
        val tools = if (Session.process != null) aiTools() else emptyList()
        // na primeira mensagem: o que a IA anotou deste jogo em outras conversas, para não descobrir tudo de novo
        val full: suspend () -> String = {
            val notes = notesFile?.takeIf { it.exists() }?.readText()?.takeLast(4000).orEmpty()
            val lessons = lessonsFile.takeIf { it.exists() }?.readText()?.takeLast(3000).orEmpty()
            val server = runCatching { serverHints() }.getOrDefault("")
            val sheet = sheetAnalysis()
            withContext(Dispatchers.Main) { context() } +
                (if (lessons.isBlank()) "" else "\n\n=== Suas lições (aprendidas em conversas anteriores, valem para qualquer jogo) ===\n$lessons") +
                (if (notes.isBlank()) "" else "\n\n=== Suas anotações deste jogo (de outras conversas) ===\n$notes") +
                (if (server.isBlank()) "" else "\n\n=== Sinais de que o servidor ou o antitrapaça confere valores (logcat do jogo) ===\n$server") +
                (if (sheet.isBlank()) "" else "\n\n=== Análises feitas na planilha para este jogo (as mais novas primeiro) ===\n$sheet")
        }
        // Gemini lento demais (o relatório mostrou 40–100 s por rodada): esta pergunta vai no Groq; a medida do Gemini
        // cai um terço a cada pulo, então ele volta a ser tentado depois de umas perguntas
        val back = slowGeminiSkip(onStep)
        Assistant.ask(chat, full, question, tools, onStep).also { if (back) withContext(Dispatchers.Main) { Session.aiProvider = "gemini" } }.let { a ->
            if (!Assistant.lastLimitFail) return@let a
            // a cota acabou em todas: o painel de chaves pode ter chave nova; se tiver, segue de onde parou
            if (Session.keySource.isNotBlank()) {
                val before = (Session.geminiKeys + Session.groqKeys).toSet()
                Diag.add(syncKeys())
                val mine = if (Session.aiProvider == "groq") Session.groqKeys else Session.geminiKeys
                if (mine.any { it !in before }) {
                    onStep("🔑 Chave nova do painel: continuando")
                    return@let Assistant.ask(chat, full, "Continue de onde parou.", tools, onStep)
                }
            }
            // sem cota ou servidor cheio: o outro provedor (com chave) faz a mesma pergunta
            val other = if (Session.aiProvider == "groq") "gemini" else "groq"
            val otherKeys = if (other == "groq") Session.groqKeys else Session.geminiKeys
            if (!Session.aiReserve || otherKeys.isEmpty()) return@let a
            val name = if (other == "groq") "Groq" else "Gemini"
            Diag.add("${if (other == "groq") "Gemini" else "Groq"} sem cota ou cheio: seguindo com o $name")
            onStep("🔁 Seguindo com o $name")
            withContext(Dispatchers.Main) { Session.aiProvider = other }
            Assistant.ask(chat, full, question, tools, onStep)
        }.also {
            saveSettings()  // o Gemini pode ter trocado de modelo sozinho
            Diag.add("Assistente respondeu: ${it.take(600).replace('\n', ' ')}")
            // a IA parou por erro (cota, servidor, chave, conexão): relatório na planilha, no máximo um a cada 10 min
            if (Assistant.lastLimitFail || AI_ERRORS.any { e -> it.startsWith(e) }) sendReport("a IA deu erro: ${it.take(100).replace('\n', ' ')}", auto = true)
        }
    }

    private suspend fun slowGeminiSkip(onStep: (String) -> Unit): Boolean {
        if (Session.aiProvider != "gemini" || !Session.aiReserve || Session.groqKeys.isEmpty() || !Session.geminiAutoModel) return false
        val m = pickGemini(Session.geminiModels, Session.geminiSpeed) ?: return false
        val ms = Session.geminiSpeed[m] ?: return false
        if (ms < 60_000) return false
        withContext(Dispatchers.Main) {
            Session.geminiSpeed[m] = ms * 2 / 3
            Session.aiProvider = "groq"
        }
        Diag.add("Gemini lento (${m}: ${ms / 1000} s por rodada): esta pergunta vai no Groq")
        onStep("⚡ Gemini lento: esta pergunta vai no Groq")
        return true
    }

    /** As análises escritas na planilha (aba Buscas → Análise) para o jogo aberto: a IA do app parte do que já foi descoberto. */
    private suspend fun sheetAnalysis(): String = withContext(Dispatchers.IO) {
        val pkg = savedPkg ?: return@withContext ""
        val url = panelUrl("analise=1&jogo=${enc(pkg)}") ?: return@withContext ""
        val text = panelGet(url).getOrNull() ?: return@withContext ""
        val a = runCatching { JSONObject(text).optJSONArray("analise") }.getOrNull() ?: return@withContext ""
        (0 until a.length()).joinToString("\n") { "- " + a.optString(it).take(800) }.take(3000)
    }

    private val notesFile get() = savedPkg?.let { File(app.filesDir, "notas-$it.txt") }

    /** Lições gerais da IA (de todos os jogos): o que funcionou e o que errou, para agir melhor na próxima. */
    private val lessonsFile get() = File(app.filesDir, "licoes-ia.txt")

    fun lessons(): String = lessonsFile.takeIf { it.exists() }?.readText().orEmpty()

    private fun storePendingLessons() = prefs.edit().putString("licoesPendentes", Session.pendingLessons.joinToString("\n")).apply()

    fun loadPendingLessons() {
        Session.pendingLessons.clear()
        Session.pendingLessons.addAll(prefs.getString("licoesPendentes", "").orEmpty().lines().filter { it.isNotBlank() })
    }

    /** ok: a lição entra (e vai para a planilha); senão é descartada. */
    fun decideLesson(l: String, ok: Boolean) {
        Session.pendingLessons.remove(l)
        storePendingLessons()
        if (ok) { appendNote(lessonsFile, l, 6000); backupMemorySoon() }
        Diag.add("Lição ${if (ok) "aprovada" else "descartada"}: ${l.take(200)}")
    }

    fun clearLessons() {
        lessonsFile.delete()
        notesFile?.delete()
        Diag.add("Aprendizado da IA apagado")
        backupMemorySoon()
    }

    // Backup da memória da IA: Download/Tinker/tinker-memoria-ia.json, atualizado sozinho a cada coisa aprendida.
    // Atualizar o app não apaga os dados (mesma assinatura); o backup cobre desinstalar, limpar dados ou outro aparelho.

    private val noteFiles get() = app.filesDir.listFiles { f -> f.name.startsWith("notas-") && f.name.endsWith(".txt") }.orEmpty()

    // Biblioteca: um perfil por jogo (notas da IA, receitas e salvos que voltam sozinhos), tudo no mesmo backup.

    /** Jogos que têm alguma coisa guardada: notas, receitas ou salvos. */
    private fun libraryPkgs(): List<String> = app.filesDir.listFiles().orEmpty().mapNotNull { f ->
        Regex("""(?:notas-(.+)\.txt|recipes-(.+)\.json|saved-(.+)\.json)""").matchEntire(f.name)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
    }.filter { it != "sem-jogo" && PKG.matches(it) }.distinct().sorted()

    private fun gameName(pkg: String) = runCatching { app.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

    private fun jsonFile(name: String) = runCatching { JSONArray(File(app.filesDir, name).readText()) }.getOrDefault(JSONArray())

    /** Salvos que valem em outra abertura ou outro aparelho: os que voltam pela classe, ponteiro ou assinatura (endereço solto não volta). */
    private fun portableSaved(pkg: String): List<JSONObject> = jsonFile("saved-$pkg.json").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .filter { o -> !o.optBoolean("temp") && (listOf("il2", "pointer", "sig").any { o.optString(it).isNotEmpty() } || (o.optJSONArray("chains")?.length() ?: 0) > 0) }

    data class GameProfile(val pkg: String, val name: String, val notes: String, val recipes: Int, val saved: Int)

    fun library(): List<GameProfile> = libraryPkgs().map { p ->
        GameProfile(p, gameName(p), File(app.filesDir, "notas-$p.txt").takeIf { it.exists() }?.readText().orEmpty(), jsonFile("recipes-$p.json").length(), portableSaved(p).size)
    }.filter { it.notes.isNotBlank() || it.recipes > 0 || it.saved > 0 }

    /** Toda a memória (lições + perfil de cada jogo) em JSON, para backup e exportação. */
    fun memoryJson(): String = JSONObject().put("tinker", "memoria-ia").put("versao", 2).put("apagados", JSONArray(deleted().toList()))
        .put("criado", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
        .put("licoes", lessons())
        .put("jogos", JSONObject().apply {
            library().forEach { g ->
                put(g.pkg, JSONObject().put("nome", g.name).put("notas", g.notes).put("receitas", jsonFile("recipes-${g.pkg}.json"))
                    .put("patches", jsonFile("patches-${g.pkg}.json"))
                    // pid -1 (de outra vez do jogo): na restauração o salvo é procurado de novo (classe, ponteiro ou assinatura), nunca usado no endereço velho
                    .put("salvos", JSONArray().apply { portableSaved(g.pkg).forEach { o ->
                        // 200 caminhos de reserva por salvo deixavam o backup enorme: os 20 primeiros bastam para voltar
                        o.optJSONArray("chains")?.let { c -> o.put("chains", JSONArray((0 until minOf(c.length(), 20)).map { c.getString(it) })) }
                        put(o.put("pid", -1).put("frozen", false))
                    } }))
            }
        })
        .toString(2)

    /** Junta uma memória exportada com a de agora (não apaga nada, não repete linha, receita nem salvo). */
    fun importMemory(text: String): String {
        val j = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("tinker") == "memoria-ia" }
            ?: return "Não é uma memória do Tinker (arquivo tinker-memoria-ia.json)."
        lessonsFile.writeText(mergeLines(lessons(), j.optString("licoes")))
        // apagado em outra instância: apaga aqui também (e não volta)
        val gone = j.optJSONArray("apagados")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotEmpty() }
        if (gone.isNotEmpty()) {
            prefs.edit().putStringSet("apagados", (deleted() + gone).toList().takeLast(2000).toSet()).apply()
            val g = gone.toSet()
            if (Session.saved.any { it.id in g }) { Session.saved.removeAll { it.id in g }; storeSaved() }
            if (Session.recipes.any { it.id in g }) { Session.recipes.removeAll { it.id in g }; storeRecipes() }
            libraryPkgs().filter { it != savedPkg }.forEach { pkg ->
                val sf = File(app.filesDir, "saved-$pkg.json")
                val a = jsonFile("saved-$pkg.json")
                val keep = JSONArray().apply { for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("id") !in g }?.let { put(it) } }
                if (keep.length() != a.length()) sf.writeText(keep.toString())
            }
        }
        // lições e notas apagadas na planilha (o texto sem a data): saem daqui também, senão o app mandava de volta
        val goneLines = j.optJSONArray("linhasApagadas")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty().filter { it.isNotEmpty() }.toSet()
        if (goneLines.isNotEmpty()) {
            lessonsFile.takeIf { it.exists() }?.let { f -> f.writeText(dropLines(f.readText(), goneLines)) }
            app.filesDir.listFiles { f -> f.name.startsWith("notas-") }?.forEach { f -> f.writeText(dropLines(f.readText(), goneLines)) }
        }
        // versão 1 tinha só "notas" (texto por jogo); a 2 tem "jogos" com notas, receitas e salvos
        val games = j.optJSONObject("jogos") ?: JSONObject().apply { j.optJSONObject("notas")?.let { n -> n.keys().forEach { put(it, JSONObject().put("notas", n.optString(it))) } } }
        var count = 0; var recipes = 0; var saved = 0
        games.keys().forEach { pkg ->
            if (!PKG.matches(pkg)) return@forEach  // vira nome de arquivo: nada de / ou ..
            val g = games.optJSONObject(pkg) ?: return@forEach
            val f = File(app.filesDir, "notas-$pkg.txt")
            val notes = dropLines(mergeLines(f.takeIf { it.exists() }?.readText().orEmpty(), g.optString("notas")), goneLines)
            if (notes.isNotEmpty()) f.writeText(notes)
            recipes += mergeRecipes(pkg, g.optJSONArray("receitas") ?: JSONArray())
            mergePatches(pkg, g.optJSONArray("patches") ?: JSONArray())
            saved += mergeSaved(pkg, g.optJSONArray("salvos") ?: JSONArray())
            count++
        }
        backupMemorySoon()
        val n = lessons().lines().count { it.isNotBlank() }
        Diag.add("Memória da IA importada: $n lições, $count jogos, +$recipes receitas, +$saved salvos")
        return "Memória importada: $n lições e $count jogos (+$recipes receitas, +$saved salvos novos)."
    }

    /** Patches de outra instância (planilha): entram desligados; a pessoa liga quando quiser. */
    private fun mergePatches(pkg: String, a: JSONArray) {
        val current = pkg == savedPkg
        val have = if (current) Session.patches.toList() else jsonFile("patches-$pkg.json").let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it)?.toPatch() } }
        val new = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toPatch()?.copy(enabled = false) }.filter { n -> have.none { it.id == n.id } }
        if (new.isEmpty()) return
        if (current) { Session.patches.addAll(new); storePatches() }
        else File(app.filesDir, "patches-$pkg.json").writeText(JSONArray().apply { (have + new).forEach { put(it.toJson()) } }.toString())
    }

    /** Receitas novas do backup (validadas como as coladas à mão); mesma id ou mesmo nome já existe, fica a de agora. */
    private fun mergeRecipes(pkg: String, a: JSONArray): Int {
        val current = pkg == savedPkg
        val have = if (current) Session.recipes.toList() else jsonFile("recipes-$pkg.json").let { x -> (0 until x.length()).mapNotNull { runCatching { x.getJSONObject(it).toRecipe() }.getOrNull() } }
        val gone = deleted()
        val new = (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it).toRecipe() }.getOrNull() }
            .filter { it.id !in gone }
            .filter { r -> have.none { it.id == r.id || it.name == r.name } }.distinctBy { it.name }
        if (new.isEmpty()) return 0
        if (current) { Session.recipes.addAll(new); storeRecipes() }
        else File(app.filesDir, "recipes-$pkg.json").writeText(JSONArray().apply { (have + new).forEach { put(it.toJson()) } }.toString())
        return new.size
    }

    /** Salvos novos do backup: entram como "de outra vez do jogo" e voltam pela restauração quando o jogo abrir. */
    /** Salvos deste jogo guardados na planilha que foram apagados no app (a planilha não apaga nada: ficam marcados). */
    suspend fun deletedInSheet(): List<SavedItem> = withContext(Dispatchers.IO) {
        val pkg = savedPkg ?: error("escolha o jogo primeiro")
        val url = panelUrl("memoria=1") ?: error("sem link da planilha em Ajustes")
        val j = JSONObject(panelGet(url).getOrThrow())
        val gone = deleted()
        val a = j.optJSONObject("jogos")?.optJSONObject(pkg)?.optJSONArray("salvos") ?: return@withContext emptyList()
        (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it).toSaved() }.getOrNull() }.filter { it.id in gone }
    }

    /**
     * Volta um salvo apagado: entra com id nova (a antiga continua apagada, então não some de novo na próxima junção)
     * e é reencontrado pelo ponteiro ou pela AOB, como um salvo de outra vez do jogo.
     */
    fun restoreDeleted(item: SavedItem): String {
        val back = item.copy(id = java.util.UUID.randomUUID().toString(), pid = -1, frozen = false)
        Session.saved.add(back)
        storeSaved()
        backupMemorySoon()
        val label = item.name.ifEmpty { "0x${item.addr}" }
        Diag.add("Salvo restaurado da planilha: $label (${item.type})")
        return "$label voltou para os salvos: o Tinker procura pelo ponteiro ou pela AOB."
    }

    private fun mergeSaved(pkg: String, a: JSONArray): Int {
        val current = pkg == savedPkg
        val have = if (current) Session.saved.toList() else jsonFile("saved-$pkg.json").let { x -> (0 until x.length()).mapNotNull { runCatching { x.getJSONObject(it).toSaved() }.getOrNull() } }
        val gone = deleted()
        val new = (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it).toSaved().copy(pid = -1, frozen = false) }.getOrNull() }
            .filter { it.id !in gone }
            .filter { s -> have.none { it.id == s.id || (s.il2.isNotEmpty() && it.il2 == s.il2) || (s.name.isNotEmpty() && it.name == s.name && it.type == s.type) } }
        if (new.isEmpty()) return 0
        if (current) { Session.saved.addAll(new); storeSaved() }
        else File(app.filesDir, "saved-$pkg.json").writeText(JSONArray().apply { (have + new).forEach { put(it.toJson()) } }.toString())
        return new.size
    }

    private fun memoryUri(create: Boolean, name: String = MEMORY_FILE, mime: String = "application/json"): android.net.Uri? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        app.contentResolver.query(
            col, arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME}=? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "Download/Tinker%"), null,
        )?.use { c -> if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(col, c.getLong(0)) }
        if (!create) return null
        return app.contentResolver.insert(col, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Tinker")
        })
    }

    var lastMemoryBackup by mutableStateOf("")
        private set

    /** Grava o backup agora (sobrescreve o mesmo arquivo). */
    fun backupMemory(): Boolean = runCatching {
        cloudPush()
        val uri = memoryUri(create = true) ?: return false
        app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(memoryJson().toByteArray()) }
        lastMemoryBackup = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date())
        true
    }.onFailure { Diag.add("Backup da memória da IA falhou: ${it.message}") }.getOrDefault(false)

    private var backupJob: kotlinx.coroutines.Job? = null

    /** Várias coisas aprendidas seguidas viram um backup só (no máximo um a cada 3 s, mesmo com salvos mudando sem parar). */
    private fun backupMemorySoon() {
        if (backupJob?.isActive == true) return
        backupJob = scope.launch(Dispatchers.IO) { delay(3000); backupMemory() }
    }

    // Banco comum na planilha do painel: cada instância manda o que a IA aprendeu e a planilha junta tudo (aba Aprendizado).

    private fun panelUrl(extra: String = "") = Session.keySource.trim().takeIf { it.startsWith("https://script.google.com/") }
        ?.let { it + extra.takeIf { e -> e.isNotEmpty() }?.let { e -> (if ('?' in it) "&" else "?") + e }.orEmpty() }

    /**
     * GET no script do painel com o motivo certo quando falha: antes a mensagem era só o link (com o token na tela).
     * O Google responde com uma página de login ou de erro (não com a lista) quando a implantação está errada.
     */
    private fun panelGet(url: String): Result<String> = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val html = body.trimStart().startsWith("<")
            when {
                code == 404 -> error("o Google diz que esse link não existe (código 404). A implantação foi apagada ou trocada: " +
                    "no painel da planilha, copie o link de novo; se criou uma Nova implantação, use o link dela")
                code == 401 || code == 403 || (html && ("accounts.google" in body || "ServiceLogin" in body)) ->
                    error("o Google pediu login (código $code). Na implantação, \"Quem pode acessar\" precisa ser Qualquer pessoa")
                code >= 400 -> error("o Google respondeu com erro $code")
                html -> error("o Google respondeu uma página, não a lista: o script deu erro ou a implantação está na versão antiga " +
                    "(Implantar → Gerenciar implantações → editar → Nova versão)")
                else -> body
            }
        } finally {
            c.disconnect()
        }
    }

    /** POST de JSON no painel; se a rede falhar (timeout), tenta mais uma vez 5 s depois. 302 também é certo: o Google redireciona depois de guardar. */
    private fun panelPost(url: String, body: String): Int {
        fun once() = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            try {
                connectTimeout = 15_000; readTimeout = 60_000; requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray()) }
                responseCode
            } finally { disconnect() }
        }
        return runCatching { once() }.getOrElse { Thread.sleep(5000); once() }
    }

    /** Manda a memória para a planilha (lá ela é juntada, não substituída: nada que outra instância aprendeu se perde). */
    private fun cloudPush() {
        val url = panelUrl() ?: return
        // "Fazer backup" chama da tela: rede nunca na linha principal
        scope.launch(Dispatchers.IO) { runCatching {
            val code = panelPost(url, memoryJson())
            if (code >= 400) Diag.add("Aprendizado não foi para a planilha: código $code") else pushPending = false
        }.onFailure { pushPending = true; Diag.add("Aprendizado não foi para a planilha: ${it.message} (vai quando a internet voltar)") } }
    }

    // sem internet nada se perde: o relatório fica numa fila e o aprendizado é mandado de novo quando a planilha responder
    @Volatile private var pushPending = false
    private val pendingDir get() = File(app.filesDir, "pendentes").apply { mkdirs() }

    private fun queueReport(body: String) = runCatching {
        File(pendingDir, "relatorio-${System.currentTimeMillis()}.json").writeText(body)
        pendingDir.listFiles()?.sortedBy { it.name }?.dropLast(20)?.forEach { it.delete() }  // guarda os 20 mais novos
    }

    /** A planilha respondeu: manda o que ficou na fila (do mais velho para o mais novo). */
    private fun flushPending() {
        if (pushPending) cloudPush()
        val url = panelUrl("relatorio=1") ?: return
        val files = pendingDir.listFiles()?.sortedBy { it.name }.orEmpty()
        var sent = 0
        for (f in files) {
            val ok = runCatching { panelPost(url, f.readText()) < 400 }.getOrDefault(false)
            if (!ok) break
            f.delete(); sent++
        }
        if (sent > 0) Diag.add("Internet de volta: $sent relatório(s) da fila foram para a planilha")
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
    private fun appVersion() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    /**
     * A pessoa tocou em "Mandar para a planilha" nos resultados: etapas da busca, os primeiros resultados e o que há em volta
     * dos 5 primeiros vão para a aba Buscas, para análise (só leitura do que já está na tela; nada é escrito no jogo).
     */
    suspend fun sendSearch(): String {
        val url = panelUrl("busca=1") ?: return "Sem painel de chaves: ligue o link da planilha em Ajustes."
        val p = Session.process ?: return "Escolha o jogo primeiro."
        val hits = Session.results.take(30)
        if (hits.isEmpty()) return "Sem resultados para mandar."
        val around = JSONObject()
        hits.take(5).forEach { h -> runCatching { objectText(h.addr) }.getOrNull()?.let { around.put(h.addr, it.take(1500)) } }
        val body = JSONObject().put("jogo", "${p.label} (${p.pkg})").put("motor", Session.engine?.toString() ?: "?")
            .put("versao", appVersion()).put("aparelho", android.os.Build.MODEL)
            .put("etapas", stepLog.joinToString(" | ")).put("total", Session.count ?: hits.size)
            .put("resultados", hits.joinToString("\n") { "${it.addr} ${it.type} ${it.region} = ${it.value}" + if (it.base.isNotEmpty()) " · ${it.base}" else "" })
            .put("emVolta", around.keys().asSequence().joinToString("\n\n") { "[$it]\n" + around.getString(it) })
        return withContext(Dispatchers.IO) {
            runCatching { panelPost(url, body.toString()) }.fold(
                { code -> if (code < 400) "Busca mandada para a planilha (aba Buscas): ${hits.size} resultados, ${around.length()} com o que há em volta.".also(Diag::add) else "A planilha recusou a busca: código $code" },
                { "A busca não foi para a planilha: ${it.message}" })
        }
    }

    /** Lê os pedidos pendentes da aba Pedidos (e a planilha marca este aparelho na aba Aparelhos). Nada roda aqui. */
    suspend fun fetchRequests(): String = withContext(Dispatchers.IO) {
        val url = panelUrl("pedidos=1&aparelho=${enc(android.os.Build.MODEL)}&versao=${enc(appVersion())}") ?: return@withContext ""
        val text = panelGet(url).getOrElse { return@withContext "" }  // sem rede: tenta de novo na próxima volta
        flushPending()
        val arr = runCatching { JSONObject(text).optJSONArray("pedidos") }.getOrNull() ?: return@withContext ""
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { SheetRequest(it.optString("id"), it.optString("tipo").lowercase().replace("ç", "c").replace("õ", "o"), it.optString("jogo").trim(), it.optString("texto").trim().take(2000)) }
            .filter { it.id.isNotEmpty() && it.tipo in REQUEST_TYPES }
        val (auto, ask) = list.partition { it.tipo in Session.alwaysRequests && it.tipo in TRUSTABLE_REQUESTS }
        val msg = withContext(Dispatchers.Main) {
            val fresh = ask.any { r -> Session.sheetRequests.none { it.id == r.id } }
            Session.sheetRequests.clear(); Session.sheetRequests.addAll(ask)
            if (fresh) "Planilha: ${ask.size} pedido(s) esperando você no painel" else ""
        }
        if (msg.isNotEmpty()) notifyRequests(ask)
        // a pessoa marcou "Sempre permitir" para este tipo: acontece sem perguntar (um de cada tipo por volta)
        auto.distinctBy { it.tipo }.forEach { answerRequest(it, true, remembered = true) }
        auto.groupBy { it.tipo }.values.flatMap { it.drop(1) }.forEach { answerRequest(it, false) }
        msg
    }

    /** Aviso no celular quando chega pedido novo: o toque abre o Tinker, onde a pessoa permite ou ignora. */
    private fun notifyRequests(list: List<SheetRequest>) = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@runCatching
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("pedidos", "Pedidos da planilha", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(app, 0,
            android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, android.app.Notification.Builder(app, "pedidos")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (list.size == 1) "Pedido da planilha" else "${list.size} pedidos da planilha")
            .setContentText(list.joinToString(" · ") { REQUEST_TYPES[it.tipo].orEmpty() }.take(200))
            .setContentIntent(open).setAutoCancel(true)
            .build())
    }

    /** Pelo toque no painel: roda no escopo do app, e não no da tela (fechar o painel no meio cancelava o autoteste e a resposta não voltava). */
    fun answerRequestLater(r: SheetRequest, apply: Boolean) {
        scope.launch { val res = answerRequest(r, apply); if (apply) withContext(Dispatchers.Main) { Session.status = res } }
    }

    /** A pessoa tocou em Aplicar (ou Ignorar) num pedido da planilha: faz e devolve a resposta para a aba Pedidos. */
    suspend fun answerRequest(r: SheetRequest, apply: Boolean, remembered: Boolean = false): String {
        withContext(Dispatchers.Main) { Session.sheetRequests.remove(r) }
        val result = if (!apply) "ignorado" else runCatching {
            when (r.tipo) {
                "dica" -> "lida"  // só texto para a pessoa: o que buscar e como; ela faz no app se quiser
                "nota" -> {
                    val pkg = r.jogo.takeIf { Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+").matches(it) } ?: savedPkg ?: error("escreva o pacote do jogo na coluna Jogo ou escolha o jogo antes")
                    require(r.texto.isNotEmpty()) { "nota vazia" }
                    appendNote(File(app.filesDir, "notas-$pkg.txt"), r.texto, 8000); "anotado em $pkg"
                }
                "licao" -> { require(r.texto.length >= 10) { "lição curta demais" }; appendNote(lessonsFile, r.texto, 6000); "lição guardada" }
                "autoteste" -> selfTest().lineSequence().first()
                "relatorio" -> { sendReport("pedido da planilha"); "relatório enviado" }
                else -> error("tipo desconhecido")
            }
        }.getOrElse { "não deu: ${it.message}" }
        Diag.add("Pedido da planilha (${REQUEST_TYPES[r.tipo]}${if (remembered) ", sempre permitido" else ""}): $result")
        withContext(Dispatchers.IO) {
            panelUrl("feito=${enc(r.id)}&situacao=${if (!apply) "ignorado" else if (remembered) "automático" else "aplicado"}&resposta=${enc(result.take(300))}")?.let { u ->
                val back = panelGet(u)
                // a resposta precisa aparecer na aba Pedidos; se não foi, o motivo fica no registro
                if (back.getOrNull()?.contains("\"ok\":true") != true) Diag.add("Pedido da planilha: a resposta não voltou para a aba Pedidos (${back.exceptionOrNull()?.message ?: back.getOrNull()?.take(120)})")
            }
        }
        return result
    }

    /**
     * Autoteste: confere as partes do Tinker neste aparelho, só com leituras (nada é escrito no jogo), e manda o
     * resultado junto do relatório para a planilha. Bom depois de cada atualização.
     */
    // no escopo do app: com o painel fechando no meio, o teste da tela era cancelado ("rememberCoroutineScope left the composition")
    suspend fun selfTest(): String = scope.async { selfTestIn() }.await()

    /** Para o agente do PC: resultado e registro em files/autoteste.txt (ele lê pelo adb e manda para a planilha). */
    fun selfTestToFile() {
        scope.launch(Dispatchers.IO) {
            val r = runCatching { selfTest() }.getOrElse { "FALHA autoteste: ${it.message}" }
            val f = File(app.filesDir, "autoteste.txt")
            File(app.filesDir, "autoteste.tmp").apply { writeText(r + "\n\n" + runCatching { report().readText() }.getOrDefault("")) }.renameTo(f)
        }
    }

    private suspend fun selfTestIn(): String = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        suspend fun check(name: String, work: suspend () -> String) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { work() }
            val line = (if (r.isSuccess) "OK" else "FALHA") + " $name: " + (r.getOrNull() ?: r.exceptionOrNull()?.message ?: "?") +
                " (${System.currentTimeMillis() - t0} ms)"
            out += line
            Diag.add("Autoteste $line")
        }
        check("daemon e root") { call { d -> d.send("info", quiet = true).status }.also { require("uid=0" in it) { "sem root: $it" } } }
        check("jogo") { Session.process?.let { "${it.label} (PID ${it.pid}), ${Session.engine ?: "motor ?"}" } ?: "nenhum escolhido (escolha um para testar a memória)" }
        if (Session.process != null && daemon?.isAttached == true) {
            check("regiões de memória") { "${call { d -> d.send("regions", quiet = true).lines.size }} tipos" }
            check("congelamento no daemon") { "${call { d -> d.send("frozen", quiet = true).status }} valores congelados" }
            if (Session.engine?.il2cpp == true) check("classes IL2CPP") { "${listClasses().size} classes" }
        }
        // memória de verdade, sem tocar no jogo: um daemon à parte se liga a ele mesmo e faz o ciclo todo nos valores de teste dele
        check("memória: busca, refino, escrita, congelamento e AOB") {
            val t = Daemon(app.applicationInfo.nativeLibraryDir + "/libtinkerd.so") {}
            try {
                val pid = t.call("info").status.substringAfter("pid=", "").substringBefore(' ').toIntOrNull() ?: error("daemon sem pid (versão antiga)")
                t.call("attach $pid")
                require((t.call("search i32 271828182").status.toLongOrNull() ?: 0) >= 1) { "a busca não achou o valor de teste" }
                val hits = t.call("list 20").lines
                val addr = (hits.firstOrNull { " Cd " in it } ?: hits.firstOrNull())?.substringBefore(' ') ?: error("lista vazia")
                t.call("write $addr i32 314159")
                require(t.call("read $addr i32").status == "314159") { "a escrita não gravou" }
                require((t.call("refine 314159").status.toLongOrNull() ?: 0) >= 1) { "o refino perdeu o valor" }
                t.call("freeze $addr i32 777")
                t.call("write $addr i32 1")
                Thread.sleep(400)
                require(t.call("read $addr i32").status == "777") { "o congelamento não segurou o valor" }
                t.call("unfreeze all")
                val hex = "TINKER-AUTOTESTE-AOB".toByteArray().joinToString("") { "%02x".format(it) }
                require((t.call("aobfind $hex").status.toLongOrNull() ?: 0) >= 1) { "a AOB não achou o marcador" }
                "tudo certo (testado no próprio daemon, o jogo não foi tocado)"
            } finally {
                t.close()
            }
        }
        // as funções novas, cada uma no próprio daemon (o jogo não é tocado): assim se sabe o que funciona neste aparelho
        check("congelar com regra (só subir)") {
            withSelf { t ->
                require((t.call("search i32 271828182").status.toLongOrNull() ?: 0) >= 1) { "sem valor de teste" }
                val addr = t.call("list 20").lines.firstOrNull()?.substringBefore(' ') ?: error("lista vazia")
                t.call("freeze $addr i32 1000 up"); t.call("write $addr i32 500"); Thread.sleep(400)
                require(t.call("read $addr i32").status == "1000") { "não segurou o mínimo" }
                t.call("write $addr i32 2000"); Thread.sleep(400)
                require(t.call("read $addr i32").status == "2000") { "não deixou subir" }
                t.call("unfreeze all")
                "ok"
            }
        }
        check("Lua (tabelas e busca 🌙)") {
            withSelf { t ->
                val lua = t.call("selftestdata").lines.firstOrNull { it.startsWith("lua ") }?.substringAfter(' ') ?: error("daemon sem dados de teste")
                require(t.call("luatable $lua").lines.any { it.endsWith(" int gold 31415") }) { "não leu o campo gold da tabela" }
                require((t.call("search li 31415").status.toLongOrNull() ?: 0) >= 1) { "a busca 🌙 não achou" }
                "ok"
            }
        }
        check("onde o jogo escreve (📍)") {
            withSelf { t ->
                val fresh = t.call("selftestdata").lines.firstOrNull { it.startsWith("fresh ") }?.substringAfter(' ')?.toLongOrNull(16) ?: error("sem memória de teste")
                t.call("activity start")
                for (k in 0 until 8) t.call("write ${(fresh + k * 4096L).toString(16)} i32 7")
                val r = t.call("activity")
                val a = r.lines.firstOrNull { it.startsWith("act A ") }?.split(' ')?.getOrNull(2)?.toLongOrNull() ?: 0
                require(a >= 28) { "não contou a memória escrita ($a KB)" }
                "ok (${if (r.lines.any { it == "how soft" }) "marca cada página" else "pela memória nova: este aparelho não marca cada página"})"
            }
        }
        check("foto do jogo") {
            withSelf { t ->
                require((t.call("search i32 271828182").status.toLongOrNull() ?: 0) >= 1) { "sem valor de teste" }
                val addr = t.call("list 20").lines.firstOrNull()?.substringBefore(' ') ?: error("lista vazia")
                val mb = t.call("photo").status
                t.call("write $addr i32 123")
                require((t.call("photodiff").status.toLongOrNull() ?: 0) >= 1) { "a diferença não achou a mudança" }
                t.call("photorestore $addr")
                require(t.call("read $addr i32").status == "271828182") { "não voltou como estava" }
                t.call("photodrop")
                "ok ($mb MB)"
            }
        }
        check("leitor de texto (🎯 Achar sozinho)") {
            val bmp = Bitmap.createBitmap(640, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            Canvas(bmp).drawText("48213", 40f, 140f, android.graphics.Paint().apply { textSize = 110f; color = android.graphics.Color.BLACK; isAntiAlias = true })
            val text = com.google.android.gms.tasks.Tasks.await(ocr.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))).text
            require("48213" in text.filter { !it.isWhitespace() }) { "leu \"${text.take(40)}\" em vez de 48213" }
            "ok"
        }
        // jogo de teste: o programa tests/target.c (vai no APK) imita um jogo; o Tinker faz nele o que faria num jogo de verdade
        val bench = app.applicationInfo.nativeLibraryDir + "/libtinkertarget.so"
        if (File(bench).exists()) withTarget(bench) { t, a ->
            val (iaddr, faddr, paddr, xaddr, oaddr) = a.subList(0, 5)
            val (caddr, maddr) = a[10] to a[11]
            check("jogo de teste: Dword, Float e refino") {
                require(t.call("search i32 123456789").status == "1") { "não achou o Dword" }
                t.call("write $iaddr i32 987654321")
                require(t.call("refine 987654321").status == "1" && t.call("read $iaddr i32").status == "987654321") { "a escrita ou o refino falhou" }
                require(t.call("search f64,f32 3.5").let { t.call("list 1000").lines.any { l -> l.startsWith("$faddr f32") } }) { "não achou o Float" }
                "ok"
            }
            check("jogo de teste: cifrado 🔒 (XOR)") {
                t.call("search i32,f32,xi32,xf32 777777")
                require(t.call("list 20").lines.any { it.startsWith("$xaddr xi32") }) { "não achou o valor cifrado" }
                t.call("write $xaddr xi32 888888")
                require(t.call("read $xaddr xi32").status == "888888") { "a escrita cifrada falhou" }
                "ok"
            }
            check("jogo de teste: ponteiros") {
                // o caminho que sai da lib do jogo de teste (root fica no .data dele); outros podem passar por memória que muda
                val paths = t.call("pointers $paddr 256 3").lines.filter { Regex("""\S+\+[0-9a-f]+ 10 20""").matches(it) }
                require(paths.isNotEmpty()) { "não achou o caminho" }
                // vale se algum caminho volta ao valor; senão mostra o que cada um deu, para achar o erro
                val tried = paths.take(8).map { it to runCatching { t.call("resolve $it").status }.getOrElse { e -> "erro: ${e.message}" } }
                tried.firstOrNull { it.second == paddr }?.let { "ok (${it.first}, ${paths.size} caminhos)" }
                    ?: error("nenhum caminho volta a $paddr: " + tried.joinToString("; ") { "${it.first} → ${it.second}" })
            }
            check("jogo de teste: congelar") {
                t.call("freeze $iaddr i32 424242")
                t.call("write $iaddr i32 1")
                Thread.sleep(400)
                val v = t.call("read $iaddr i32").status
                t.call("unfreeze all")
                require(v == "424242") { "não segurou (leu $v)" }
                "ok"
            }
            check("jogo de teste: AOB e grupo") {
                require((t.call("aob ????????2b02000000000000 data").status.toLongOrNull() ?: 0) >= 1) { "a AOB não achou" }
                require(t.call("group i32,f32 4321;99.5 16").status == "2") { "a busca em grupo não achou vida e ouro juntos" }
                "ok"
            }
            check("jogo de teste: IL2CPP (nomes dos campos)") {
                val gold = (oaddr.toLong(16) + 0x14).toString(16)
                val r = t.call("il2cpp $gold").lines
                require("class Game.Player $oaddr" in r && "field 14 08 gold" in r) { "não deu nome ao campo: ${r.take(3)}" }
                "ok"
            }
            check("jogo de teste: patch no código") {
                t.call("write $caddr i32 -1869574000")
                require(t.call("read $caddr i32").status == "-1869574000") { "não escreveu no código" }
                "ok"
            }
            check("jogo de teste: foto (o que mudou)") {
                t.call("photo")
                t.call("photonoise")
                t.call("write $maddr i32 1")
                val n = t.call("photodiff").status.toLongOrNull() ?: 0
                val found = t.call("list 2000").lines.any { it.startsWith("$maddr i32") }
                t.call("photodrop")
                require(found) { "a foto não mostrou a mudança ($n)" }
                "ok"
            }
        }?.let { msg -> check("jogo de teste") { error(msg) } } else check("jogo de teste") { error("não veio no APK") }
        // speedhack e unrandomizer precisam rodar código dentro do jogo; isto só confere se o sistema permite a base disso
        check("base do speedhack e unrandomizer") {
            val r = withSelf { t -> t.call("canattach").status }
            require(r.startsWith("sim")) { "o sistema não deixa ($r): speedhack e unrandomizer não funcionariam neste aparelho" }
            "o sistema permite: dá para construir os dois aqui"
        }
        check("depurador (O que escreve aqui?)") {
            val v = prefs.getString("depurador", null) ?: return@check "ainda não testado: use \"O que escreve aqui?\" uma vez"
            require(v == "funciona") { v }
            v
        }
        check("print da tela") { call { d -> d.send("screencap", quiet = true).lines.size }.let { require(it > 0) { "veio vazio" }; "ok" } }
        check("app em primeiro plano") { call { d -> d.send("focus", quiet = true).lines.firstOrNull()?.removePrefix("| ")?.trim()?.take(120) ?: "?" } }
        // sem internet, os testes de rede diziam OK com a mensagem de erro dentro (visto no relatório de 25/10 05:03)
        check("internet") { java.net.InetAddress.getByName("script.google.com").hostAddress?.let { "ok" } ?: error("sem resposta do DNS") }
        check("painel de chaves") { if (Session.keySource.isBlank()) "sem link (cole em Ajustes → Assistente)" else syncKeys().also { require(it.startsWith("Painel de chaves")) { it } } }
        check("planilha (aprendizado)") { if (Session.keySource.isBlank()) "sem link (cole em Ajustes → Assistente)" else cloudPull().also { require(it.startsWith("Planilha")) { it } } }
        // uma pergunta curtinha de verdade em cada IA (1 pedido da cota de cada): responde e quanto demora
        check("Gemini") { if (Session.geminiKeys.isEmpty()) "sem chave" else Assistant.pingGemini() }
        check("Groq") { if (Session.groqKeys.isEmpty()) "sem chave" else Assistant.pingGroq() }
        check("menu flutuante") { if (android.provider.Settings.canDrawOverlays(app)) "permitido" else error("sem permissão de sobreposição") }
        val ok = out.count { it.startsWith("OK") }
        sendReport("autoteste: $ok de ${out.size} ok")
        "Autoteste: $ok de ${out.size} ok (o resultado foi para a planilha)\n" + out.joinToString("\n")
    }

    /** Um daemon à parte, ligado a ele mesmo: as funções são testadas na memória dele, sem tocar no jogo. */
    /** Abre o jogo de teste como root, um daemon ligado nele, roda block e fecha os dois. Devolve o erro, se não abriu. */
    private suspend fun withTarget(path: String, block: suspend (Daemon, List<String>) -> Unit): String? {
        val proc = ProcessBuilder("su", "-c", "exec $path").start()
        var pid = ""
        try {
            val info = withTimeout(5000) { runInterruptible { proc.inputStream.bufferedReader().readLine() } }?.trim()?.split(' ').orEmpty()
            require(info.size >= 18) { "o jogo de teste não abriu" }
            pid = info[0]
            val t = Daemon(app.applicationInfo.nativeLibraryDir + "/libtinkerd.so") {}
            try {
                t.call("attach $pid")
                // ordem do target.c: i f p x o y z q w ticks código correio …
                block(t, info.drop(1))
            } finally { t.close() }
            return null
        } catch (e: Exception) {
            return e.message ?: e.toString()
        } finally {
            if (pid.isNotEmpty()) runCatching { ProcessBuilder("su", "-c", "kill -9 $pid").start().waitFor() }
            proc.destroy()
        }
    }

    private fun <T> withSelf(block: (Daemon) -> T): T {
        val t = Daemon(app.applicationInfo.nativeLibraryDir + "/libtinkerd.so") {}
        try {
            val pid = t.call("info").status.substringAfter("pid=", "").substringBefore(' ').toIntOrNull() ?: error("daemon sem pid")
            t.call("attach $pid")
            return block(t)
        } finally {
            t.close()
        }
    }

    @Volatile private var lastAutoReport = 0L

    /**
     * Manda o relatório para a aba Relatórios do painel. auto (o jogo fechou sozinho): no máximo um a cada 10 min,
     * 3 s depois, para a falha do Android já estar no registro.
     */
    fun sendReport(reason: String, auto: Boolean = false, ready: String? = null) {
        val url = panelUrl("relatorio=1") ?: return
        val now = System.currentTimeMillis()
        if (auto && now - lastAutoReport < 600_000) return
        if (auto) lastAutoReport = now
        val p = Session.process
        scope.launch(Dispatchers.IO) {
            if (auto) delay(3000)
            runCatching {
                val text = ready ?: report().readText()  // quem acabou de gerar o relatório manda ele (antes saíam dois iguais)
                val problems = text.substringAfter("=== Resumo de problemas", "").substringAfter("===").substringBefore("=== Registro do Tinker").trim().take(5000)
                val body = JSONObject().put("jogo", p?.let { "${it.label} (${it.pkg})" } ?: "nenhum")
                    .put("versao", appVersion()).put("aparelho", android.os.Build.MODEL)
                    .put("motivo", reason).put("problemas", problems).put("texto", text.take(400_000)).toString()  // a planilha guarda até 405 mil letras
                val code = runCatching { panelPost(url, body) }.getOrElse { e ->
                    queueReport(body)
                    Diag.add("Relatório não foi para a planilha: ${e.message} (ficou na fila; vai quando a internet voltar)")
                    return@runCatching
                }
                Diag.add(if (code < 400) "Relatório enviado para a planilha ($reason)" else "Relatório não foi para a planilha: código $code")
            }.onFailure { Diag.add("Relatório não foi para a planilha: ${it.message}") }
        }
    }

    /** Traz o aprendizado de todas as instâncias da planilha e junta com o daqui (depois manda o resultado de volta). */
    suspend fun cloudPull(): String = withContext(Dispatchers.IO) {
        val url = panelUrl("memoria=1") ?: return@withContext "Sem painel de chaves: o aprendizado fica só neste aparelho e no backup."
        val text = panelGet(url).getOrElse { return@withContext "Não li o aprendizado da planilha: ${it.message}" }
        if (runCatching { JSONObject(text).optString("tinker") }.getOrNull() != "memoria-ia") {
            backupMemorySoon()  // planilha ainda vazia (ou script antigo): manda o daqui
            return@withContext "Planilha sem aprendizado ainda: mandei o deste aparelho."
        }
        withContext(Dispatchers.Main) { "Planilha: " + importMemory(text) }
    }

    /** Lê o backup de Download/Tinker e junta com a memória de agora. */
    fun restoreMemory(): String {
        val uri = memoryUri(create = false) ?: return "Nenhum backup em Download/Tinker/$MEMORY_FILE."
        val text = runCatching { app.contentResolver.openInputStream(uri)!!.use { it.reader().readText() } }.getOrElse { return "Não li o backup: ${it.message}" }
        return importMemory(text)
    }

    private fun appendNote(f: File, text: String, max: Int) = runCatching {
        val line = "${java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).format(java.util.Date())} ${text.replace('\n', ' ').trim().take(300)}"
        if (f.exists() && f.readLines().any { it.substringAfter(' ') == line.substringAfter(' ') }) return@runCatching  // já sabe
        f.appendText(line + "\n")
        if (f.length() > max) f.writeText(f.readText().takeLast(max / 2).substringAfter('\n'))
        backupMemorySoon()
    }

    /** Linhas do logcat do jogo que indicam servidor/antitrapaça recusando valores (ex.: PBC_SHOULD_BE_LESS_THAN_EQUAL no Sonic Forces). */
    private suspend fun serverHints(): String = call { d -> d.send("logs", quiet = true).lines }
        .filter { l -> SERVER_WORDS.any { l.contains(it, ignoreCase = true) } }.takeLast(10).joinToString("\n") { it.removePrefix("| ").take(300) }

    /** Salvos, ponteiros, receitas e o que voltou ao reabrir: para a IA saber o que já foi achado. */
    fun savedContext(): String = buildString {
        appendLine(gameLine())
        appendLine("Restauração: ${Session.restoreInfo.ifBlank { "nada pendente" }} · ponteiros: ${Session.pointerInfo.ifBlank { "-" }}")
        appendLine("Salvos (${Session.saved.size}; ⚠ = de outra vez do jogo, ainda não voltou):")
        Session.saved.forEach { appendLine((if (stale(it)) "⚠ " else "") + savedLine(it)) }
        appendLine("Receitas (${Session.recipes.size}):")
        Session.recipes.forEach { r -> appendLine("${r.name}${if (r.auto) " (ao abrir)" else ""}: " + r.steps.joinToString { "${it.label} = ${it.value} (${it.how})" }) }
    }


    /**
     * Print do jogo para a IA ler os números da tela: o painel fica quase invisível por um instante (o "olho") para não sair
     * na foto; a imagem vai reduzida (cerca de 1/3) em JPEG, o bastante para ler textos e bem mais leve para enviar.
     */
    /** Tamanho do último print mandado para a IA e da tela de verdade. */
    private data class Shot(val w: Int, val h: Int, val screenW: Int, val screenH: Int)
    @Volatile private var shot: Shot? = null

    /** Toque da IA: só com o jogo em primeiro plano (uma compra da Play Store ou outro app na frente bloqueia). */
    private suspend fun aiInput(args: String): String {
        if (!Session.aiControl) return "A pessoa não ligou \"IA controla o jogo\" em Ajustes. Peça para ela fazer no jogo."
        val pkg = Session.process?.pkg ?: return "Nenhum jogo escolhido."
        // painel em cima do jogo pegaria o toque: minimiza (a bolinha fica para a pessoa abrir e tocar em Parar)
        withContext(Dispatchers.Main) { Session.aiHidePanel = true }
        delay(700)
        withContext(Dispatchers.Main) { Session.aiHidePanel = false }
        val focus = call { d -> runCatching { d.send("focus", quiet = true).lines.joinToString(" ") }.getOrDefault("") }
        if (pkg !in focus) return "Toque bloqueado: o que está na frente não é o jogo ($focus). Pode ser uma compra (Play Store), um anúncio ou outro app. " +
            "Peça para a pessoa voltar ao jogo; nunca toque em compras."
        call { d -> d.send("input $args") }
        Diag.add("IA tocou no jogo: input $args")
        delay(900)
        Assistant.pendingImage = screenshotJpeg()  // a IA já vê o resultado do toque, sem gastar outra rodada
        return "Feito (input $args). Print de depois do toque anexado."
    }

    private fun screenPoint(x: String, y: String): Pair<Int, Int>? {
        val s = shot ?: return null
        val px = x.toDoubleOrNull() ?: return null
        val py = y.toDoubleOrNull() ?: return null
        if (px < 0 || py < 0 || px > s.w || py > s.h) return null
        return (px * s.screenW / s.w).toInt() to (py * s.screenH / s.h).toInt()
    }

    /** Print da tela em PNG, com o painel quase invisível por um instante (o "olho"). */
    private suspend fun screenPng(): ByteArray {
        withContext(Dispatchers.Main) { Session.peek = true }
        val r = try {
            delay(350)
            call { d -> d.send("screencap", quiet = true) }
        } finally {
            withContext(Dispatchers.Main) { Session.peek = false }
        }
        return withContext(Dispatchers.Default) { android.util.Base64.decode(r.lines.joinToString("") { it.removePrefix("| ") }, android.util.Base64.DEFAULT) }
    }

    private suspend fun screenshotJpeg(): String {
        val png = screenPng()
        return withContext(Dispatchers.Default) {
            val full = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, it) }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
                ?: throw IOException("o print veio vazio")
            val scale = minOf(1f, 1400f / maxOf(bmp.width, bmp.height))
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            // a IA toca em coordenadas da imagem que viu; o toque vai na tela de verdade
            shot = Shot(small.width, small.height, full.outWidth, full.outHeight)
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 75, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }

    // ---- 🎯 Achar sozinho: lê o número na tela do jogo (reconhecimento de texto do aparelho, sem internet) e refina sozinho ----

    private val ocr by lazy { com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Números na tela com o lugar de cada um (em pixels da tela de verdade). */
    private suspend fun screenNumbers(): List<Pair<Long, android.graphics.Rect>> {
        val png = screenPng()
        return withContext(Dispatchers.Default) {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw IOException("o print veio vazio")
            val text = com.google.android.gms.tasks.Tasks.await(ocr.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)))
            text.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                .mapNotNull { e -> val n = screenNumber(e.text) ?: return@mapNotNull null; val b = e.boundingBox ?: return@mapNotNull null; n to b }
        }
    }

    /** O número que está dentro da área (um pouco maior que onde estava: o número cresce para os lados). */
    private fun readAt(nums: List<Pair<Long, android.graphics.Rect>>, at: android.graphics.Rect): Long? {
        val area = android.graphics.Rect(at).apply { inset(-maxOf(at.width(), 40), -at.height() / 2) }
        return nums.firstOrNull { (_, b) -> area.contains(b.centerX(), b.centerY()) }?.first
    }

    @Volatile private var autoJob: kotlinx.coroutines.Job? = null

    fun autoFindStop() {
        autoJob?.cancel()
        autoJob = null
        Session.autoFind = null
    }

    /**
     * value: o número que a pessoa vê agora. Acha ele na tela, busca, e a cada mudança na tela refina sozinho. Com até 3
     * resultados (e "IA pode testar valores" ligado), testa cada um: o que faz o número da tela mudar é o verdadeiro.
     */
    fun autoFindStart(value: String, types: Set<String>) {
        autoFindStop()
        Session.autoFindWinner = null
        val target = screenNumber(value) ?: run { Session.autoFind = null; Session.status = "Digite o número que aparece na tela."; return }
        fun say(t: String) { scope.launch(Dispatchers.Main) { if (autoJob != null) Session.autoFind = t } }
        autoJob = scope.launch {
            try {
                withContext(Dispatchers.Main) { Session.autoFind = "Procurando $target na tela…"; Session.aiHidePanel = true }
                delay(600)
                withContext(Dispatchers.Main) { Session.aiHidePanel = false }
                val spots = screenNumbers().filter { it.first == target }.map { it.second }.toMutableList()
                if (spots.isEmpty()) { say("Não vi $target na tela. Deixe o número visível (painel minimizado) e tente de novo."); return@launch }
                aiBusy { resultsText(search(types, target.toString())) }
                Diag.add("Achar sozinho: $target em ${spots.size} lugar(es) da tela, ${Session.count} resultados")
                var place = spots.first()
                var last = target
                val end = System.currentTimeMillis() + 10 * 60_000L
                while (System.currentTimeMillis() < end) {
                    val count = Session.count ?: 0L
                    if (count in 1L..3L) break
                    say("$count resultados · na tela: $last · jogue para o número mudar")
                    delay(1200)
                    val nums = screenNumbers()
                    // vários lugares com o mesmo número: vale o que mudou
                    val now = spots.map { it to readAt(nums, it) }.firstOrNull { it.second != null && it.second != last }
                    if (now != null && spots.size > 1) { place = now.first; spots.retainAll { it == place } }
                    val cur = readAt(nums, place) ?: continue
                    if (cur == last) continue
                    val r = aiBusy { resultsText(refine(followRange(last, cur))) }
                    Diag.add("Achar sozinho: $last → $cur, ${Session.count} resultados")
                    if ((Session.count ?: 0L) == 0L) aiBusy { resultsText(undo()) }.also { Diag.add("Achar sozinho: nenhum ficou ($r), desfeito") }
                    last = cur
                }
                val finalists = list().second
                if (finalists.size > 3) { say("10 minutos e ainda ${Session.count} resultados: o número mudou pouco. Continue pela busca normal."); return@launch }
                if (finalists.isEmpty()) { say("Nenhum resultado ficou: o número da tela pode não estar guardado como número. Tente outro valor."); return@launch }
                if (!Session.aiCanTest) { say("Sobraram ${finalists.size}. Ligue \"IA pode testar valores\" em Ajustes para eu descobrir o verdadeiro, ou abra cada um na Ficha."); return@launch }
                for (h in finalists) {
                    say("Testando 0x${h.addr}…")
                    val old = call { d -> runCatching { d.send("read ${h.addr} ${h.type}", quiet = true).status }.getOrNull() } ?: continue
                    val test = (old.toLongOrNull() ?: continue) + 7
                    write(h.addr, h.type, test.toString())
                    delay(1200)
                    val seen = readAt(screenNumbers(), place)
                    write(h.addr, h.type, old)  // volta como estava
                    Diag.add("Achar sozinho: teste 0x${h.addr} = $test, a tela mostrou $seen")
                    if (seen == test) {
                        withContext(Dispatchers.Main) { Session.autoFindWinner = h }
                        say("Achei: 0x${h.addr} muda o número da tela. Toque em Salvar.")
                        return@launch
                    }
                }
                say("Nenhum dos ${finalists.size} mudou a tela no teste: o número da tela é calculado de outro lugar. Abra cada um na Ficha.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Parou: ${e.message}")
                Diag.add("Achar sozinho parou: ${e.javaClass.simpleName}: ${e.message} em ${e.stackTrace.take(3).joinToString(" < ")}")
            }
        }
    }

    /** Busca feita pela IA: espera a sua terminar e trava os botões e as rotinas de fundo enquanto roda, como uma busca sua. */
    private suspend fun aiBusy(work: suspend () -> String): String {
        while (Session.busy) delay(200)
        withContext(Dispatchers.Main) { Session.busy = true }
        return try { work() } finally { withContext(Dispatchers.Main) { Session.busy = false } }
    }

    /** Busca/refino feitos pela IA aparecem na aba Busca, como se fossem seus. */
    private suspend fun resultsText(status: String): String {
        val (count, hits) = list()
        withContext(Dispatchers.Main) {
            Session.count = count
            Session.results = hits
            Session.status = status
        }
        // até 50: a IA vê de qual classe e campo é cada resultado e acha o verdadeiro sem precisar refinar
        val names = if ((count ?: 0L) in 1L..50L) runCatching { il2Names(hits.take(50).map { it.addr }) }.getOrDefault(emptyMap()) else emptyMap()
        return "$status\nResultados: ${count ?: 0}" + hits.take(50).joinToString("") { "\n0x${it.addr} ${it.type} ${it.region} = ${it.value}" + (names[it.addr]?.let { n -> " [$n]" } ?: "") }
    }

    private suspend fun objectText(center0: String): String {
        val (c, center, rows) = objectRows(center0)
        // sem as linhas "zero": metade da ficha era vazia e cada rodada reenviava tudo (estourou a cota do Gemini)
        val useful = rows.filterNot { it.value == "0" && (it.label.startsWith("zero") || c == null) }
        return (c?.let { "${it.name} em 0x${it.start}" } ?: "Sem classe IL2CPP em volta de 0x$center (tipos adivinhados)") +
            useful.take(120).joinToString("") { "\n+${it.off.toString(16)} ${it.label} = ${it.value} (0x${it.addr})" } +
            if (useful.size < rows.size) "\n(${rows.size - useful.size} campos com 0 omitidos)" else ""
    }

    /** Classe IL2CPP em volta do endereço (se houver), o endereço usado e os campos com o valor de agora. */
    private suspend fun objectRows(center0: String): Triple<Il2Class?, String, List<FichaRow>> {
        // o começo do objeto nem sempre cai dentro de um campo (primeiro campo em +0x18…): tenta alguns passos à frente
        var center = center0
        var found = il2cpp(center)
        for (step in listOf(8L, 16L, 24L, 32L)) {
            if (found != null) break
            val next = (center0.toLong(16) + step).toString(16)
            found = il2cpp(next)
            if (found != null) center = next
        }
        val c = found
        val rows = if (c != null) ficha(c.start, ((c.fields.maxOfOrNull { it.offset } ?: 0) + 8).coerceIn(16, 0x400), c, center)
        else ficha((center.toLong(16) - 0x40).coerceAtLeast(0).toString(16), 0x80, null, center)
        return Triple(c, center, rows)
    }

    /** O que a IA pode fazer sozinha: só olhar e buscar; editar e congelar ficam com você. */
    private fun aiTools(): List<AiTool> = listOf(
        AiTool("procurar_classes", "Procura classes do jogo pelo nome da classe E pelo nome dos campos (ex.: gold acha PlayerData que tem o campo Gold). " +
            "Devolve até 80 (as do próprio jogo primeiro), com os campos que bateram.",
            mapOf("palavras" to "palavras separadas por espaço, em inglês como no código do jogo (gold coin gem hp health)"), listOf("palavras")) { a ->
            // motor próprio (C++): não há classes; no DYSMANTLE a IA gastou 3 rodadas procurando
            if (Session.engine?.il2cpp != true) return@AiTool "Este jogo não é Unity IL2CPP (${Session.engine ?: "motor ?"}): não há classes nem campos com nome. " +
                "Use ver_endereco e o_que_muda em volta de um valor achado, buscas por valor e AOB."
            val words = searchWords(a.optString("palavras"))
            val all = listClasses()
            val hit = all.mapNotNull { c -> classMatch(c, words)?.let { c to it } }
                .sortedBy { (c, f) -> (if (LIBS.any { c.name.startsWith(it) }) 2 else 0) + (if (f.isEmpty()) 1 else 0) }
            // palavra que não existe neste jogo vira nota: a IA não procura de novo em outra conversa (gastou 8 rodadas assim)
            if (hit.isEmpty() && words.isNotEmpty()) notesFile?.let { appendNote(it, "Não existe classe nem campo com: ${words.joinToString(" ")}", 8000) }
            "${hit.size} de ${all.size} classes" + hit.take(80).joinToString("") { (c, f) ->
                "\n${c.name} (${c.fields} campos)" + if (f.isEmpty()) "" else ": " + f.take(8).joinToString()
            }
        },
        AiTool("abrir_classe", "Acha os objetos vivos de uma classe (ex.: PlayerData) e mostra os campos com o valor de agora. Com valor: procura, entre " +
            "todos os objetos (até 100), os campos que têm esse valor agora (ex.: o ouro que a pessoa vê) — use isso quando há muitos objetos.",
            mapOf("classe" to "nome da classe, com ou sem namespace", "valor" to "opcional: valor que a pessoa vê no jogo", "objeto" to "opcional: número do objeto (1, 2, …) para abrir um só"),
            listOf("classe")) { a ->
            val objs = findObjects(a.optString("classe"))
            val head = Session.engine?.header ?: 0x10L
            val want = normalizeNumbers(a.optString("valor")).toDoubleOrNull()
            val pick = a.optString("objeto").toIntOrNull()
            when {
                objs.isEmpty() -> "Nenhum objeto ${a.optString("classe")} agora (a classe pode não estar carregada nesta tela do jogo)."
                want != null -> {
                    // cada objeto: os campos com o valor procurado (o ouro de verdade costuma estar num objeto só)
                    val found = objs.take(100).mapIndexedNotNull { i, o ->
                        val text = objectText((o.toLong(16) + head).toString(16))
                        val lines = text.lines().drop(1).filter { l ->
                            l.substringAfter(" = ", "").substringBefore(" (0x").trim().toDoubleOrNull()?.let { kotlin.math.abs(it - want) < 0.01 } == true
                        }
                        if (lines.isEmpty()) null else "--- objeto #${i + 1} (0x$o)\n" + lines.joinToString("\n")
                    }
                    "${objs.size} objetos; ${found.size} com um campo = ${a.optString("valor")}" + if (found.isEmpty()) "" else "\n" + found.take(15).joinToString("\n")
                }
                pick != null -> objs.getOrNull(pick - 1)?.let { o -> "objeto #$pick de ${objs.size} (0x$o)\n" + objectText((o.toLong(16) + head).toString(16)) }
                    ?: "Só há ${objs.size} objetos."
                else -> "${objs.size} objetos" + (if (objs.size > 3) " (mostrando 3; use valor para achar o certo, ou objeto para abrir outro)" else "") +
                    objs.take(3).mapIndexed { i, o -> "\n--- objeto #${i + 1} (0x$o)\n" + objectText((o.toLong(16) + head).toString(16)) }.joinToString("")
            }
        },
        AiTool("mostrar_classes", "Mostra na tela da pessoa, no topo da lista de classes, as classes que você recomenda (ela toca para abrir). " +
            "Use sempre que recomendar classes.",
            mapOf("classes" to "nomes completos das classes, separados por vírgula, a melhor primeiro"), listOf("classes")) { a ->
            val known = listClasses().associateBy { it.name }
            val picked = a.optString("classes").split(',').map { it.trim() }
                .mapNotNull { n -> known[n] ?: known.values.firstOrNull { it.name.substringAfterLast('.') == n } }.distinct().take(15)
            withContext(Dispatchers.Main) { Session.aiClasses.clear(); Session.aiClasses.addAll(picked) }
            if (picked.isEmpty()) "Nenhuma dessas classes existe; use os nomes que procurar_classes devolveu." else "Mostradas: ${picked.joinToString { it.name }}."
        },
        AiTool("o_que_muda", "Lê os campos de um objeto duas vezes, com um intervalo, e mostra só os que mudaram. Acha velocidade, tempo, " +
            "distância e vida sem saber o valor: peça para a pessoa jogar durante a espera (correr, levar dano) ou toque no jogo antes.",
            mapOf("endereco" to "endereço do objeto ou de um campo dele (ex.: de abrir_classe)", "classe" to "opcional, no lugar do endereço: usa o primeiro objeto da classe",
                "segundos" to "intervalo entre as leituras, 1 a 30 (padrão 3)"), emptyList()) { a ->
            val head = Session.engine?.header ?: 0x10L
            val addr = parseAddress(a.optString("endereco"))
                ?: a.optString("classe").takeIf { it.isNotBlank() }?.let { cls -> findObjects(cls).firstOrNull()?.let { (it.toLong(16) + head).toString(16) } }
                ?: return@AiTool "Diga o endereço ou uma classe com objeto vivo."
            val secs = (a.optString("segundos").toIntOrNull() ?: 3).coerceIn(1, 30)
            val (c, center, before) = objectRows(addr)
            delay(secs * 1000L)
            val old = before.associateBy { it.addr }
            val changed = objectRows(center).third.filter { r -> old[r.addr]?.let { it.value != r.value } == true }
            (c?.let { "${it.name} em 0x${it.start}" } ?: "Sem classe IL2CPP em volta de 0x$center") + ": ${changed.size} de ${before.size} campos mudaram em $secs s" +
                changed.take(60).joinToString("") { r -> "\n+${r.off.toString(16)} ${r.label}: ${old.getValue(r.addr).value} → ${r.value} (0x${r.addr})" }
        },
        AiTool("o_que_escreve", "Descobre qual instrução do jogo escreve num endereço (como o 'Find out what writes' do Cheat Engine). " +
            "Antes, diga à pessoa para mudar o valor no jogo (gastar/ganhar) durante os segundos. Devolve lib+deslocamento de cada instrução.",
            mapOf("endereco" to "endereço do valor em hexadecimal (de um salvo)", "segundos" to "de 5 a 30 (padrão 15)"), listOf("endereco")) { a ->
            val addr = parseAddress(a.optString("endereco")) ?: return@AiTool "Endereço inválido."
            val hits = watchWrites(addr, a.optString("segundos").toIntOrNull() ?: 15)
            if (hits.isEmpty()) "Nada escreveu em 0x$addr nesse tempo. A pessoa precisa mudar o valor no jogo enquanto observa."
            else hits.joinToString("\n") { (pc, n, w) -> "0x$pc ($w) escreveu $n vez(es)" } + "\nUse ver_codigo no endereço para ler a instrução."
        },
        AiTool("ver_codigo", "Mostra as instruções ARM64 em volta de um endereço de código (de o_que_escreve), já traduzidas (SUB, STR, ADD…).",
            mapOf("endereco" to "endereço da instrução em hexadecimal"), listOf("endereco")) { a ->
            val addr = parseAddress(a.optString("endereco")) ?: return@AiTool "Endereço inválido."
            codeAt(addr, 8, 24).joinToString("\n") { r -> "${if (r.addr == addr) "→" else " "} 0x${r.addr} ${r.where} ${r.word?.let { "%08x".format(it) } ?: "????????"} ${r.text}" }
        },
        AiTool("ler_tabela_lua", "Jogos com Lua (xLua/ToLua): lê a tabela do Lua em volta de um endereço (um resultado da busca) ou a própria " +
            "tabela (o valor de um campo do tipo table), com o nome de cada campo. Mostra o endereço de cada valor, para salvar ou mudar.",
            mapOf("endereco" to "endereço em hexadecimal"), listOf("endereco")) { a ->
            val addr = parseAddress(a.optString("endereco")) ?: return@AiTool "Endereço inválido."
            val (ver, fields) = luaTable(addr)
            if (fields.isEmpty()) "Nenhum campo do Lua nesse endereço." else "Lua $ver, ${fields.size} campos:\n" +
                fields.take(150).joinToString("\n") { "0x${it.addr} ${it.kind} ${it.key} = ${it.value}" } +
                "\n(int: mude com o tipo li; num: tipo lf; table: abra com este mesmo comando no endereço do valor)"
        },
        AiTool("propor_patch", "Propõe trocar instruções do jogo (ex.: o SUB que tira 1 do recurso vira NOP 1f2003d5). O patch fica guardado " +
            "DESLIGADO em Salvos → Patches de código; só a pessoa liga. Explique em uma frase o que muda no jogo.",
            mapOf("endereco" to "endereço da primeira instrução", "bytes" to "bytes novos em hex, 8 dígitos por instrução, como na memória (NOP = 1f2003d5)",
                "nome" to "nome curto (ex.: Madeira não diminui)", "motivo" to "o que a instrução fazia e o que passa a fazer"),
            listOf("endereco", "bytes", "nome")) { a ->
            val addr = parseAddress(a.optString("endereco")) ?: return@AiTool "Endereço inválido."
            createPatch(addr, a.optString("bytes"), a.optString("nome").take(60), a.optString("motivo"), apply = false)
        },
        AiTool("ver_endereco", "Mostra a ficha em volta de um endereço (de um resultado da busca): classe e campos com valores.",
            mapOf("endereco" to "endereço em hexadecimal, ex.: 7197b90978"), listOf("endereco")) { a ->
            val addr = parseAddress(a.optString("endereco")) ?: return@AiTool "Endereço inválido."
            objectText(addr)
        },
        AiTool("buscar_valor", "Nova busca na memória pelo valor que a pessoa vê no jogo. Substitui os resultados atuais. " +
            "Busca em grupo: vários valores que ficam juntos no mesmo objeto (ouro;gema;nível) com ::distância em bytes, ex.: 1250;30;7::128.",
            mapOf("valor" to "número, faixa (100~200), vários com ; (100;250) ou grupo (100;250;7::128)", "tipos" to "opcional: i32,f32,i64,f64 separados por vírgula; vazio = Auto. Jogo com Lua (o motor diz · Lua): li acha só números do Lua, bem menos resultados"),
            listOf("valor")) { a ->
            val types = a.optString("tipos").split(',').map { it.trim() }.filter { it in TYPE_SIZES && !it.startsWith("x") }.toSet()
            aiBusy { resultsText(search(types, a.optString("valor"))) }
        },
        AiTool("refinar", "Refina os resultados atuais pelo valor novo (depois que a pessoa mudou o valor no jogo). Valor que muda rápido " +
            "(mortes, pontos, tempo) já mudou de novo quando você refina: use uma faixa a partir do que a pessoa disse (48~70) ou comparar aumentou.",
            mapOf("valor" to "o valor de agora, ou uma faixa (48~70)"), listOf("valor")) { a -> aiBusy { resultsText(refine(a.optString("valor"))) } },
        AiTool("propor_edicao", "Sugere à pessoa uma edição (aparece um botão Aplicar; nada muda sem ela tocar). Use o endereço exato " +
            "que você viu numa ficha ou nos resultados, com o tipo dele.",
            mapOf("endereco" to "endereço em hexadecimal", "tipo" to "i32, f32, i64, f64, xi32 ou xf32 (o tipo que a ficha ou o resultado mostrou)",
                "valor" to "valor novo", "congelar" to "sim para manter o valor fixo, não para só escrever", "nome" to "nome curto do campo (ex.: Ouro)",
                "motivo" to "por que este é o campo certo, em uma frase"),
            listOf("endereco", "tipo", "valor")) { a ->
            val addr = parseAddress(a.optString("endereco")) ?: return@AiTool "Endereço inválido."
            val type = a.optString("tipo").trim()
            val value = normalizeNumbers(a.optString("valor"))
            if (type !in TYPE_SIZES) return@AiTool "Tipo inválido: use i32, f32, i64, f64, xi32 ou xf32."
            if (!Regex("""-?[0-9.eE+-]{1,40}""").matches(value)) return@AiTool "Valor inválido: um número só."
            val p = AiProposal(addr, type, value, a.optString("congelar").trim().lowercase().startsWith("s"), a.optString("nome").take(40), a.optString("motivo").take(200), pid = currentPid)
            withContext(Dispatchers.Main) { Session.aiProposals.add(p) }
            // modo autônomo: aplica já (o ↶ Desfazer fica na tela)
            if (Session.aiAuto) "Aplicado sozinho (modo autônomo): " + withContext(Dispatchers.Main) { applyProposal(p) }
            else "Sugestão mostrada para a pessoa com o botão Aplicar."
        },
        AiTool("busca_sem_valor", "Começa uma busca sem saber o número (barra de vida, energia): guarda a memória para comparar depois com comparar.",
            mapOf("tipo" to "i32 (inteiro) ou f32 (decimal; barras costumam ser f32)"), emptyList()) { a ->
            val t = a.optString("tipo").trim().takeIf { it in TYPE_SIZES && !it.startsWith("x") }
            aiBusy { resultsText(startUnknown(setOfNotNull(t))) }
        },
        AiTool("descobrir_pela_tela", "Com poucos resultados (até ~300), descobre qual é o valor da tela: escreve números em sequência (um em cada " +
            "resultado), lê a tela e vê qual apareceu; depois volta tudo como estava. Só funciona se a pessoa ligou \"IA pode testar valores\".",
            emptyMap(), emptyList()) {
            if (!Session.aiCanTest && !Session.aiAuto) return@AiTool "A pessoa não deixou a IA testar valores (Ajustes → Assistente → IA pode testar valores)."
            val hits = list().second
            if (hits.isEmpty()) return@AiTool "Não há resultados: busque primeiro."
            aiBusy { findBySequence(hits.map { it.addr to it.type }, "") }
        },
        AiTool("comparar", "Refina a busca pelo jeito que o valor mudou desde a última busca ou comparação (depois que a pessoa mexeu no jogo).",
            mapOf("como" to "aumentou, diminuiu, igual ou mudou", "quanto" to "opcional: quanto aumentou ou diminuiu (ex.: 10)"), listOf("como")) { a ->
            val op = compareOp(a.optString("como"), a.optString("quanto")) ?: return@AiTool "Use aumentou, diminuiu, igual ou mudou."
            aiBusy { resultsText(compare(op, a.optString("quanto"))) }
        },
        AiTool("buscar_bytes", "Busca AOB: procura uma sequência de bytes (hex, ?? = qualquer byte) nos dados ou no código do jogo. " +
            "Os lugares viram os resultados da busca. Bom para achar de novo um valor pelos bytes em volta (assinatura) quando o endereço muda.",
            mapOf("padrao" to "pares hex com ?? de curinga, ex.: 1F 20 ?? D5 00 00", "onde" to "dados (memória do jogo, padrão) ou codigo (instruções)"),
            listOf("padrao")) { a ->
            aiBusy { resultsText(searchAob(a.optString("padrao"), if (a.optString("onde").trim().lowercase().startsWith("c")) "xa" else "data")) }
        },
        AiTool("ler_bytes", "Mostra os bytes crus (hex) a partir de um endereço, para montar um padrão AOB em volta de um valor.",
            mapOf("endereco" to "endereço em hexadecimal (ex.: de um resultado, menos alguns bytes)", "quantidade" to "quantos bytes, até 64 (padrão 32)"),
            listOf("endereco")) { a ->
            val addr = parseAddress(a.optString("endereco")) ?: return@AiTool "Endereço inválido."
            val n = (a.optString("quantidade").toIntOrNull() ?: 32).coerceIn(1, 64)
            // byte que não deu para ler (página sem mapa) vira ??
            val bytes = call { d -> d.send("dump $addr i8 $n", quiet = true).lines.map { l -> l.substringAfter(' ').toIntOrNull()?.let { "%02x".format(it and 0xff) } ?: "??" } }
            "0x$addr: " + bytes.joinToString(" ")
        },
        AiTool("criar_assinatura", "Monta agora a assinatura AOB de salvos: lê os bytes em volta do valor várias vezes, o que muda vira ?? e confere " +
            "em quantos lugares ela aparece. Achando um lugar só, o salvo volta pelos bytes quando o jogo reabrir, mesmo sem classe nem ponteiro. " +
            "Leva uns 4 s com o jogo rodando.",
            mapOf("salvos" to "nomes ou endereços dos salvos, separados por vírgula, ou todos"), listOf("salvos")) { a ->
            val items = matchSaved(a.optString("salvos"), Session.saved.toList()).filter { it.pid == currentPid && !it.temp }.take(5)
            if (items.isEmpty()) return@AiTool "Nenhum salvo desta vez do jogo com esses nomes."
            // várias leituras: cada byte que mexe (contador, ponteiro, tempo) vira ?? e a assinatura sobrevive ao reabrir
            repeat(6) { k ->
                call { d -> items.forEach { i -> Session.saved.firstOrNull { it.id == i.id }?.let { d.learnSig(it) } } }
                if (k < 5) delay(700)
            }
            items.mapNotNull { i -> Session.saved.firstOrNull { it.id == i.id } }.map { item ->
                val fixed = item.sig.chunked(2).count { it != "??" }
                "${label(item)}: " + when {
                    !sigUseful(item.sig) -> "só $fixed bytes fixos em volta (muita coisa muda ou é tudo zero); não serve como assinatura"
                    else -> {
                        val r = call { d -> runCatching { d.send("aobfind ${item.sig}", quiet = true) }.getOrNull() }
                        val places = r?.lines?.map { (it.toLong(16) + item.sigOff).toString(16) }.orEmpty()
                        when {
                            r == null -> "não deu para conferir agora"
                            places == listOf(item.addr) -> "assinatura de ${item.sig.length / 2} bytes ($fixed fixos) acha só este lugar: volta sozinho ao reabrir ✓"
                            item.addr in places -> "assinatura acha ${r.status} lugares (este entre eles): ao reabrir vale o que tiver o último valor"
                            else -> "assinatura acha ${r.status} lugares e nenhum é este; jogue um pouco e tente de novo"
                        }
                    }
                }
            }.joinToString("\n")
        },
        AiTool("ver_salvos", "Mostra os salvos (com ponteiro, assinatura, histórico e se voltaram ao reabrir) e as receitas.", emptyMap(), emptyList()) { savedContext() },
        AiTool("propor_receita", "Sugere criar uma receita com salvos (aplica tudo de uma vez, e sozinha ao abrir o jogo). Aparece um botão para a pessoa.",
            mapOf("nome" to "nome da receita", "salvos" to "nomes ou endereços dos salvos, separados por vírgula, ou todos", "ao_abrir" to "sim para aplicar sozinha quando o jogo abrir"),
            listOf("nome", "salvos")) { a ->
            val items = matchSaved(a.optString("salvos"), Session.saved.toList())
            if (items.isEmpty()) return@AiTool "Nenhum salvo com esses nomes. Veja os nomes com ver_salvos (ou aplique as edições antes)."
            val p = AiProposal("", "", "", a.optString("ao_abrir").trim().lowercase().startsWith("s"), a.optString("nome").take(40).ifBlank { "Receita" },
                items.joinToString { label(it) }, recipe = items.map { it.id })
            withContext(Dispatchers.Main) { Session.aiProposals.add(p) }
            if (Session.aiAuto) "Criada sozinha (modo autônomo): " + withContext(Dispatchers.Main) { applyProposal(p) }
            else "Sugestão de receita mostrada para a pessoa (${items.size} salvos)."
        },
        AiTool("aprender", "Guarda uma lição geral (vale para qualquer jogo) sobre como agir melhor: o que funcionou, o que deu errado e o que fazer " +
            "diferente. Use no fim de cada tarefa, acertando ou errando. Ex.: 'contagem da corrida reinicia a cada partida: procure o total na classe da conta'.",
            mapOf("licao" to "uma frase curta e geral"), listOf("licao")) { a ->
            val l = a.optString("licao").trim()
            if (l.length < 10) return@AiTool "Lição curta demais."
            // vale para todos os jogos: só entra quando a pessoa aprovar (25/10 duas lições erradas se espalharam pela planilha)
            withContext(Dispatchers.Main) { if (l !in Session.pendingLessons) Session.pendingLessons.add(l.take(400)) }
            storePendingLessons()
            Diag.add("IA quer aprender (esperando aprovação): ${l.take(200)}")
            "Guardado para a pessoa aprovar em Ajustes → O que a IA aprendeu."
        },
        AiTool("ver_logcat_jogo", "Mostra as últimas linhas do logcat do jogo (opcionalmente só as que têm certas palavras). Use depois de uma edição para ver se o " +
            "servidor ou o antitrapaça recusou (erros de validação, 'cheat', 'invalid', 'mismatch').",
            mapOf("palavras" to "opcional: palavras para filtrar, separadas por espaço"), emptyList()) { a ->
            val words = searchWords(a.optString("palavras"))
            val lines = call { d -> d.send("logs", quiet = true).lines }.map { it.removePrefix("| ") }.filterNot(::isLogNoise)
            val shown = if (words.isEmpty()) lines.takeLast(40) else lines.filter { l -> words.any { l.contains(it, ignoreCase = true) } }.takeLast(40)
            if (shown.isEmpty()) "Nada no logcat do jogo${if (words.isEmpty()) "" else " com essas palavras"}." else shown.joinToString("\n") { it.take(300) }
        },
        AiTool("anotar", "Guarda uma anotação sobre este jogo para as próximas conversas (ex.: Ouro = PlayerData.Gold, Float, cifrado).",
            mapOf("nota" to "uma linha curta"), listOf("nota")) { a ->
            val f = notesFile ?: return@AiTool "Nenhum jogo escolhido."
            val note = a.optString("nota").replace('\n', ' ').trim().take(300)
            if (note.isEmpty()) return@AiTool "Nota vazia."
            appendNote(f, note, 8000)
            "Anotado."
        },
        AiTool("esperar_mudanca", "Espera a pessoa mexer no jogo (gastar, ganhar, levar dano) e refina sozinho quando os resultados mudarem — " +
            "sem ela digitar o valor novo. Diga antes, na resposta ou no começo, o que ela deve fazer no jogo; ela minimiza o Tinker e joga.",
            mapOf("segundos" to "quanto esperar, até 90 (padrão 45)", "como" to "opcional: aumentou, diminuiu ou mudou (padrão mudou)"), emptyList()) { a ->
            val secs = (a.optString("segundos").toIntOrNull() ?: 45).coerceIn(5, 90)
            val op = compareOp(a.optString("como").ifBlank { "mudou" }, "") ?: "changed"
            aiBusy {
                val (count0, _) = list()
                if ((count0 ?: 0L) == 0L) return@aiBusy "Não há resultados para vigiar: busque primeiro."
                // primeiro tira o que muda sozinho (cronômetro, contador, animação): senão qualquer um deles "mudou" antes de a
                // pessoa mexer, e o refino ficava com lixo (no Police Chief sobraram 4 endereços errados em 2 segundos)
                // menos quando o valor sobe ou desce o tempo todo enquanto a pessoa joga (mortes, pontos): o "não mudou" tirava
                // justamente ele (25/10, Last Garden: o contador de mortes sumia a cada rodada)
                val calm = if (op == "changed") { delay(2500); compare("same") } else "não precisou"
                val (count1, first) = list()
                if ((count1 ?: 0L) == 0L) return@aiBusy "Todos os resultados mudavam sozinhos: nenhum é o valor que fica parado até você mexer. Busque de novo."
                val before = first.associate { it.addr + it.type to it.value }
                val end = System.currentTimeMillis() + secs * 1000L
                var changed = false
                while (!changed && System.currentTimeMillis() < end) {
                    delay(1500)
                    changed = list().second.any { h -> before[h.addr + h.type]?.let { it != h.value } == true }
                }
                if (!changed) "Tirei os que mudam sozinhos ($calm). Nada mudou em $secs s nos ${before.size} vigiados. Peça para a pessoa mexer no valor e chame de novo."
                else {
                    delay(800)  // espera o jogo terminar de atualizar antes de comparar
                    "Tirei os que mudam sozinhos ($calm). " + resultsText(compare(op)) +
                        "\nSe a pessoa disser o valor novo, use refinar com ele para confirmar (o refino por valor é o mais certeiro)."
                }
            }
        },
        AiTool("testar_valor", "Confirma se um resultado é o valor verdadeiro: escreve um valor de teste, espera 2 s e lê de novo, e depois volta o " +
            "valor original. Se o jogo desfez o teste, é cópia (ou é recalculado); se ficou, é o verdadeiro. Só funciona se a pessoa ligou " +
            "\"IA pode testar valores\" em Ajustes.",
            mapOf("endereco" to "endereço em hexadecimal", "tipo" to "i32, f32, i64, f64, xi32 ou xf32"), listOf("endereco", "tipo")) { a ->
            if (!Session.aiCanTest && !Session.aiAuto) return@AiTool "A pessoa não deixou a IA testar valores (Ajustes → Assistente → IA pode testar valores). Use ver_endereco ou peça para ela conferir."
            val addr = parseAddress(a.optString("endereco")) ?: return@AiTool "Endereço inválido."
            val type = a.optString("tipo").trim().takeIf { it in TYPE_SIZES } ?: return@AiTool "Tipo inválido."
            val old = call { d -> runCatching { d.send("read $addr $type", quiet = true).status }.getOrNull() } ?: return@AiTool "Não deu para ler 0x$addr."
            val n = old.toDoubleOrNull() ?: return@AiTool "0x$addr não tem um número agora ($old)."
            val test = testValue(n, type)
            try {
                write(addr, type, test)
                Diag.add("IA testando 0x$addr: $old → $test")
                delay(2000)
                val now = call { d -> runCatching { d.send("read $addr $type", quiet = true).status }.getOrDefault("?") }
                when (now.toDoubleOrNull()) {
                    test.toDouble() -> "0x$addr ficou em $test: o jogo não desfez, provável valor verdadeiro (a tela deve mostrar $test enquanto o teste dura). Voltei para $old."
                    n -> "0x$addr voltou sozinho para $old: o jogo reescreve, é cópia ou valor recalculado. Não é este."
                    else -> "0x$addr virou $now (era $old, testei $test): o jogo mexe nele; pode ser derivado de outro campo."
                }
            } finally {
                write(addr, type, old)  // sempre volta o original, mesmo se der erro no meio
            }
        },
        AiTool("ver_tela", "Tira um print do jogo agora e te mostra a imagem: leia os números que aparecem (ouro, gemas, vida, nível) para " +
            "buscar sem a pessoa digitar, ou para conferir se uma edição apareceu na tela.", emptyMap(), emptyList()) {
            Assistant.pendingImage = screenshotJpeg()
            "Print da tela anexado logo abaixo (${shot?.w}x${shot?.h}; tocar e arrastar usam coordenadas desta imagem). Leia os valores que aparecem e diga o que é cada um."
        },
        AiTool("tocar", "Toca na tela do jogo num ponto do último print (x, y em pixels da imagem). Use para gastar ou ganhar o recurso e fazer o valor " +
            "mudar. Depois do toque vem um print novo. NUNCA toque em compras com dinheiro real, preços em R$/US$, assinaturas ou na Play Store.",
            mapOf("x" to "x no último print", "y" to "y no último print", "motivo" to "o que esse toque faz (ex.: botão Melhorar, custa 150 ouro)"), listOf("x", "y", "motivo")) { a ->
            val (x, y) = screenPoint(a.optString("x"), a.optString("y")) ?: return@AiTool "Coordenada fora do último print. Use ver_tela antes e coordenadas dele."
            aiInput("tap $x $y")
        },
        AiTool("arrastar", "Arrasta na tela do jogo de um ponto a outro do último print (rolar lista, mover mapa).",
            mapOf("x1" to "x inicial", "y1" to "y inicial", "x2" to "x final", "y2" to "y final"), listOf("x1", "y1", "x2", "y2")) { a ->
            val (x1, y1) = screenPoint(a.optString("x1"), a.optString("y1")) ?: return@AiTool "Ponto inicial fora do último print."
            val (x2, y2) = screenPoint(a.optString("x2"), a.optString("y2")) ?: return@AiTool "Ponto final fora do último print."
            aiInput("swipe $x1 $y1 $x2 $y2 400")
        },
        AiTool("voltar", "Aperta o botão Voltar do Android (fechar uma janela do jogo).", emptyMap(), emptyList()) { aiInput("keyevent 4") },
        AiTool("preparar_ponteiros", "Procura agora os ponteiros de salvos (o caminho que acha o valor de novo quando o jogo reabre) e diz se ficaram bons. " +
            "Leva alguns segundos por salvo e pausa o jogo nesse tempo.",
            mapOf("salvos" to "nomes ou endereços dos salvos, separados por vírgula, ou todos"), listOf("salvos")) { a ->
            val items = matchSaved(a.optString("salvos"), Session.saved.toList()).filter { it.anchor.isEmpty() }.take(5)
            if (items.isEmpty()) return@AiTool "Nenhum salvo com esses nomes (os que seguem outro pelo offset já vêm junto com ele)."
            aiBusy {
                // map (inline) e não joinToString { }: o lambda do joinToString não pode chamar função suspend
                items.map { item -> "${label(item)}: " + runCatching { scanPointers(item) }.getOrElse { "erro: ${it.message}" } }.joinToString("\n") +
                    "\n\nEstado agora:\n" + items.mapNotNull { i -> Session.saved.firstOrNull { it.id == i.id } }.joinToString("\n") { savedLine(it) }
            }
        },
        AiTool("ver_resultados", "Mostra os resultados atuais da busca, com o campo IL2CPP quando há até 20.", emptyMap(), emptyList()) { resultsText(Session.status) },
    )

    /** Mais uma chave do Gemini no fim da lista (a de agora continua sendo usada até a cota acabar). */
    /** Uma ou várias chaves (uma por linha, ou separadas por espaço/vírgula) para o provedor escolhido. */
    fun addKeys(provider: String, raw: String): String {
        val groq = provider == "groq"
        val list = raw.split(Regex("""[\s,;]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return "Chave vazia."
        val have = if (groq) Session.groqKeys else Session.geminiKeys
        val bad = list.filterNot { GEMINI_KEY.matches(it) }
        val new = list.filter { GEMINI_KEY.matches(it) && it !in have }.distinct()
        if (groq) Session.groqKeys = have + new else Session.geminiKeys = have + new
        saveSettings()
        val other = new.count { it.startsWith("gsk_") != groq }
        return "${new.size} chave(s) adicionada(s)" + (if (list.size - bad.size - new.size > 0) ", ${list.size - bad.size - new.size} já estavam" else "") +
            (if (bad.isNotEmpty()) ", ${bad.size} inválida(s)" else "") +
            (if (other > 0) ". Atenção: $other não parece(m) do ${if (groq) "Groq (gsk_…)" else "Gemini (AIza… ou AQ.…)"}" else "") + "."
    }

    fun removeKey(provider: String, i: Int) {
        fun fix(index: Int, size: Int) = if (index > i) index - 1 else index.coerceAtMost((size - 1).coerceAtLeast(0))
        if (provider == "groq") {
            Session.groqKeys = Session.groqKeys.filterIndexed { j, _ -> j != i }
            Session.groqKeyIndex = fix(Session.groqKeyIndex, Session.groqKeys.size)
        } else {
            Session.geminiKeys = Session.geminiKeys.filterIndexed { j, _ -> j != i }
            Session.geminiKeyIndex = fix(Session.geminiKeyIndex, Session.geminiKeys.size)
        }
        saveSettings()
    }

    /** Pergunta ao Groq quais modelos a chave pode usar; se o escolhido não está entre eles, troca pelo melhor. */
    suspend fun detectGroqModels(): String = withContext(Dispatchers.IO) {
        val ms = Assistant.groqModels(Session.groqKey)
        if (ms.isEmpty()) return@withContext "Não consegui ver os modelos: confira a chave do Groq e a internet."
        Session.groqModels = ms
        if (Session.groqModel !in ms) Session.groqModel = ms.first()
        saveSettings()
        "Chave ok: ${ms.size} modelos. Usando ${Session.groqModel}."
    }

    /**
     * Chaves do painel (planilha do Google com o script do Tinker, docs/painel-chaves): junta com as de agora; as que
     * saíram de lá (apagadas ou desligadas) saem daqui. As postas à mão no app ficam.
     */
    suspend fun syncKeys(): String = withContext(Dispatchers.IO) {
        val url = Session.keySource.trim()
        if (url.isEmpty()) return@withContext "Sem link do painel de chaves."
        if (!url.startsWith("https://script.google.com/")) return@withContext "O link do painel começa com https://script.google.com/ (copie no painel da planilha)."
        val text = panelGet(url).getOrElse { return@withContext "Não li o painel de chaves: ${it.message}" }
        val j = runCatching { JSONObject(text) }.getOrNull()
            ?: return@withContext "O painel não respondeu a lista. Confira a implantação: App da Web, Executar como Eu, Qualquer pessoa."
        if (j.has("erro")) return@withContext "O painel recusou o link (token antigo?). Copie o link de novo no painel."
        fun list(name: String) = j.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            .filter { GEMINI_KEY.matches(it) }.distinct()
        val got = list("chaves")
        val gotGroq = list("groq")
        val added = withContext(Dispatchers.Main) {
            val old = Session.geminiKeys + Session.groqKeys
            val current = Session.geminiKey
            Session.geminiKeys = (Session.geminiKeys.filter { it !in Session.sheetKeys } + got).distinct()
            Session.sheetKeys = got.toSet()
            Session.geminiKeyIndex = Session.geminiKeys.indexOf(current).coerceAtLeast(0)
            val currentGroq = Session.groqKey
            Session.groqKeys = (Session.groqKeys.filter { it !in Session.sheetGroqKeys } + gotGroq).distinct()
            Session.sheetGroqKeys = gotGroq.toSet()
            Session.groqKeyIndex = Session.groqKeys.indexOf(currentGroq).coerceAtLeast(0)
            saveSettings()
            (got + gotGroq).count { it !in old }
        }
        // versão do Code.gs na planilha: abaixo da que este app espera, as abas novas (Pedidos, Buscas…) não funcionam
        val v = j.optInt("versao", 0)
        "Painel de chaves: ${got.size} do Gemini e ${gotGroq.size} do Groq" + (if (added > 0) ", $added novas" else "") +
            " · script v$v" + (if (v < SCRIPT_VERSION) " (desatualizado: este app precisa da v$SCRIPT_VERSION; cole o Code.gs novo e faça Nova versão)" else "") + "."
    }

    /** Pergunta ao Google quais modelos a chave pode usar; se o escolhido não está entre eles, troca pelo melhor. */
    suspend fun detectGeminiModels(): String = withContext(Dispatchers.IO) {
        val ms = Assistant.geminiModels(Session.geminiKey)
        Session.geminiModels = ms
        if (ms.isEmpty()) return@withContext "Não consegui ver os modelos: confira a chave e a internet."
        val before = Session.geminiModel
        // lite é o mais fraco: quem caiu nele sozinho volta para o melhor da lista
        if (before !in ms || ("-lite" in before && "-lite" !in ms.first())) Session.geminiModel = ms.first()
        saveSettings()
        Diag.add("Gemini: ${ms.size} modelos na chave; usando ${Session.geminiModel}")
        if (before != Session.geminiModel) "Chave ok: ${ms.size} modelos. Escolhido ${Session.geminiModel}." else "Chave ok: ${ms.size} modelos. Mantido ${Session.geminiModel}."
    }

    /**
     * Aplica a sugestão da IA. Edição: guarda o valor de antes (para desfazer), escreve e salva (congelado se pedido), e o
     * salvo já ganha ponteiros e assinatura como qualquer outro. Receita: cria com os salvos sugeridos.
     */
    suspend fun applyProposal(p: AiProposal): String {
        if (p.recipe.isNotEmpty()) {
            val items = Session.saved.filter { it.id in p.recipe }
            if (items.isEmpty()) return "Os salvos dessa receita não existem mais."
            val count = Session.recipes.size
            val msg = createRecipe(items, p.label)
            // não criou (salvos sem classe, ponteiro ou assinatura ainda): a sugestão fica, e nenhuma receita sua vira "ao abrir"
            if (Session.recipes.size == count) return msg
            if (p.freeze) Session.recipes.lastOrNull()?.let { updateRecipe(it.copy(auto = true)) }
            Session.aiProposals.remove(p)
            Diag.add("Receita sugerida pela IA criada: ${p.label} (${items.size} salvos)")
            return msg
        }
        if (p.pid != currentPid) { Session.aiProposals.remove(p); return "O jogo reabriu depois dessa sugestão: o endereço mudou. Pergunte de novo." }
        val old = call { d -> runCatching { d.send("read ${p.addr} ${p.type}", quiet = true).status }.getOrDefault("?") }
        write(p.addr, p.type, p.value)
        val before = Session.saved.firstOrNull { it.addr == p.addr && it.type == p.type }
        addSaved(listOf(Hit(p.addr, p.type, "?", p.value)), frozen = p.freeze)
        val i = Session.saved.indexOfFirst { it.addr == p.addr && it.type == p.type }
        // salvo que já existia: o valor novo entra nele, senão o congelamento voltaria o valor antigo em 200 ms
        // salvo novo fica com o nome da IA ("Anéis na corrida"), não com o do campo que o IL2CPP pôs um instante antes
        if (i >= 0) Session.saved[i] = Session.saved[i].copy(value = p.value, name = if (before == null && p.label.isNotEmpty()) p.label else Session.saved[i].name.ifEmpty { p.label })
        if (old != "?") Session.aiUndo.add(0, AiUndo(p.addr, p.type, old, p.label.ifEmpty { "0x" + p.addr },
            if (before == null) Session.saved.getOrNull(i)?.id else null, currentPid, before))
        while (Session.aiUndo.size > 10) Session.aiUndo.removeAt(Session.aiUndo.lastIndex)
        storeSaved()
        Session.aiProposals.remove(p)
        Diag.add("Sugestão da IA aplicada: ${p.label.ifEmpty { p.addr }} = ${p.value}${if (p.freeze) " ❄" else ""} (0x${p.addr} ${p.type}, antes $old)")
        return "${p.label.ifEmpty { "0x" + p.addr }} = ${p.value}" + (if (p.freeze) " ❄ congelado" else "") + " e salvo (os ponteiros vêm sozinhos)."
    }

    /** Volta o valor de antes da edição da IA; o salvo que ela criou sai junto (e para de congelar). */
    suspend fun undoAi(u: AiUndo): String {
        Session.aiUndo.remove(u)
        if (u.pid != currentPid) return "O jogo reabriu depois dessa edição: não dá para desfazer no endereço antigo."
        u.savedId?.let { id -> markDeleted(listOf(id)); Session.saved.removeAll { it.id == id } }
        // salvo que já existia volta como estava (congelado ou não, com o valor dele)
        if (u.before != null) Session.saved.replaceAll { if (it.id == u.before.id) it.copy(frozen = u.before.frozen, value = u.before.value) else it }
        write(u.addr, u.type, u.old)
        storeSaved()
        Diag.add("Desfeita a edição da IA em ${u.label}: voltou para ${u.old}")
        // erro dela: fica anotado para não repetir
        notesFile?.let { appendNote(it, "ERRO: a pessoa desfez a edição de ${u.label} (0x${u.addr} ${u.type}); não era o valor certo", 8000) }
        return "${u.label} voltou para ${u.old}."
    }

    private fun gameLine() = "Jogo: ${Session.process?.let { "${it.label} (${it.pkg})" } ?: "nenhum"} · motor ${Session.engine ?: "?"}"

    /** Classes do próprio jogo (sem as de bibliotecas como Unity e System), para a IA apontar onde está o valor. */
    fun classesContext(all: List<GameClass>): String {
        val game = all.filterNot { c -> LIBS.any { c.name.startsWith(it) } }
        // com a ferramenta procurar_classes a IA acha o resto; mandar milhares de nomes a cada rodada estoura o limite do plano grátis
        val shown = game.sortedByDescending { it.fields }.take(400)
        return gameLine() + "\nO jogo tem ${all.size} classes, ${game.size} do próprio jogo. As ${shown.size} do jogo com mais campos " +
            "(use procurar_classes para as outras; ela procura também nos nomes dos campos):\n" + shown.joinToString("\n") { "${it.name} (${it.fields})" }
    }

    /** Ficha aberta: classe e cada campo com o valor de agora. */
    fun fichaContext(c: Il2Class?, rows: List<FichaRow>): String =
        gameLine() + "\nFicha ${c?.name ?: "(sem nome IL2CPP)"}:\n" + rows.joinToString("\n") { "+${it.off.toString(16)} ${it.label} = ${it.value}" }

    /** Busca em andamento: passos (comando e resposta do registro), resultados mostrados e salvos. */
    fun searchContext(names: Map<String, String>): String = buildString {
        val s = Session
        appendLine(gameLine())
        appendLine("Tipos: ${if (s.types.isEmpty()) "Auto" else s.types.joinToString()} · valor digitado: ${s.value.ifBlank { "(vazio)" }} · cifrados: ${if (s.findEncrypted) "sim" else "não"}")
        appendLine("Agora: ${s.status} · restantes: ${s.count ?: "nenhuma busca"}")
        appendLine("Passos da busca (registro, comando e resposta):")
        // só desde que este jogo foi escolhido: antes levava as buscas do jogo anterior
        val all = Diag.lines.toList()
        val log = all.drop(all.indexOfLast { "Jogo escolhido:" in it }.coerceAtLeast(0))
        log.indices.filter { i -> listOf("> search", "> refine", "> compare", "> snapshot", "> undo", "> aob").any { it in log[i] } }.takeLast(30).forEach { i ->
            appendLine(log[i])
            log.getOrNull(i + 1)?.takeIf { "< " in it }?.let(::appendLine)
        }
        appendLine("Resultados mostrados (${s.results.size}): endereço tipo região valor [campo IL2CPP]")
        s.results.take(100).forEach { appendLine("0x${it.addr} ${it.type} ${it.region} ${it.value}" + (names[it.addr]?.let { n -> " [$n]" } ?: "")) }
        appendLine("Salvos (${s.saved.size}):")
        s.saved.forEach { appendLine(savedLine(it)) }
    }

    suspend fun reportContext(): String = gameLine() + "\n" + report().readText()

    /** Objetos de uma classe IL2CPP pelo nome (PlayerData, ou Namespace.PlayerData); lê a memória toda, leva alguns segundos. */
    suspend fun findObjects(cls: String): List<String> = call { d ->
        val c = cls.trim()
        require(CLASS_NAME.matches(c)) { "Nome de classe inválido: letras, números, ponto e _ (ex.: PlayerData)" }
        val objs = d.send("il2objs $c").lines.mapNotNull { it.removePrefix("obj ").takeIf { a -> a.toLongOrNull(16) != null } }
        if (objs.isNotEmpty()) {
            Session.classes.remove(c)
            Session.classes.add(0, c)
            while (Session.classes.size > 8) Session.classes.removeAt(Session.classes.lastIndex)
            prefs.edit().putString("classes-$savedPkg", Session.classes.joinToString(",")).apply()
        }
        objs
    }

    /** Itens de uma lista do jogo (List<T>: campos _items e _size); referências viram atalhos para a ficha de cada item. */
    suspend fun listItems(c: Il2Class): List<ListItem>? = call { d ->
        val start = c.start.toLong(16)
        val itemsF = c.fields.firstOrNull { it.name == "_items" } ?: return@call null
        val sizeF = c.fields.firstOrNull { it.name == "_size" } ?: return@call null
        val b32 = Session.engine?.bits == 32
        val pt = if (b32) "i32" else "i64"
        val mask = if (b32) 0xffffffffL else 0x00ffffffffffffffL
        val head = Session.engine?.header ?: 0x10L
        val items = (d.send("read ${(start + itemsF.offset).toString(16)} $pt", quiet = true).status.toLongOrNull() ?: 0L) and mask
        val size = (d.send("read ${(start + sizeF.offset).toString(16)} i32", quiet = true).status.toIntOrNull() ?: 0).coerceIn(0, 200)
        if (items < 0x10000 || size == 0) return@call emptyList()
        // array do IL2CPP: klass, monitor, bounds, tamanho; os itens começam em +0x20 (64 bits) ou +0x10 (32 bits)
        val first = items + 2 * head
        val words = d.send("dump ${first.toString(16)} $pt $size", quiet = true).lines.map { (it.substringAfter(' ').toLongOrNull() ?: 0L) and mask }
        if (words.all { it == 0L || it in 0x10000L until (if (b32) 0x100000000L else 0x1000000000000L) }) {
            words.mapIndexed { i, p ->
                // ficha do item pelo primeiro campo: o próprio começo do objeto não está dentro de nenhum campo
                val name = if (p == 0L) "vazio" else d.il2((p + head).toString(16))?.name?.substringAfterLast('.') ?: "objeto"
                ListItem(i, (p + head).toString(16), "#$i $name", if (p == 0L) "nulo" else "0x" + p.toString(16), p != 0L)
            }
        } else {
            d.send("dump ${first.toString(16)} i32 $size", quiet = true).lines.mapIndexed { i, l ->
                val (a, v) = l.split(" ", limit = 2)
                ListItem(i, a, "#$i", v, false)
            }
        }
    }

    // Receitas

    private val recipesFile get() = File(app.filesDir, "recipes-${savedPkg ?: "sem-jogo"}.json")

    /**
     * Receita com os salvos marcados. Cada campo vai pelo que sobrevive a reabrir o jogo: classe IL2CPP, senão o
     * ponteiro, senão a assinatura; sem nenhum dos três, fica de fora.
     */
    fun createRecipe(items: List<SavedItem>, name: String): String {
        val steps = items.filter { !it.temp }.mapNotNull {
            val v = readable(it.value, it.type) ?: it.value
            val label = it.name.ifEmpty { it.il2.ifEmpty { it.addr } }
            when {
                it.il2.isNotEmpty() -> Step(it.il2.substringBeforeLast('+'), it.il2.substringAfterLast('+').toLong(16), it.type, v, it.frozen, label, field = it.il2Field)
                it.pointer.isNotEmpty() -> Step("", 0, it.type, v, it.frozen, label, ptr = it.pointer)
                sigUseful(it.sig) -> Step("", 0, it.type, v, it.frozen, label, sig = it.sig, sigOff = it.sigOff)
                else -> null
            }
        }
        if (steps.isEmpty()) return "Nenhum dos marcados tem classe, ponteiro ou assinatura ainda. Toque em 🎯 Ponteiros no salvo, ou espere uns minutos jogando (a assinatura aprende sozinha)."
        Session.recipes.add(Recipe(name = name.trim().ifEmpty { "Receita ${Session.recipes.size + 1}" }, steps = steps))
        storeRecipes()
        Diag.add("Receita criada com ${steps.size} campos: ${steps.joinToString { "${it.label} = ${it.value} (${it.how})" }}")
        return "Receita criada com ${steps.size} campos (dos ${items.size} marcados). Toque em Aplicar."
    }

    fun updateRecipe(r: Recipe) {
        val i = Session.recipes.indexOfFirst { it.id == r.id }
        if (i >= 0) Session.recipes[i] = r
        storeRecipes()
    }

    fun deleteRecipe(r: Recipe) {
        markDeleted(listOf(r.id))
        Session.recipes.removeAll { it.id == r.id }
        storeRecipes()
    }

    /**
     * Aplica: acha o lugar de cada passo (objeto da classe pelo nome, ponteiro ou assinatura), escreve o valor e, nos
     * marcados com ❄, congela (vira salvo congelado, que volta sozinho ao reabrir). Mais de um lugar possível fica de fora.
     */
    suspend fun applyRecipe(r: Recipe): String {
        val frozen = mutableListOf<Pair<Hit, Step>>()
        val notes = mutableListOf<String>()
        val ok = call { d ->
            val objs = HashMap<String, List<Long>>()
            fun place(st: Step): String? = when {
                st.cls.isNotEmpty() -> {
                    val o = objs.getOrPut(st.cls) {
                        runCatching { d.send("il2objs ${st.cls}", quiet = true).lines.mapNotNull { it.removePrefix("obj ").toLongOrNull(16) } }.getOrDefault(emptyList())
                    }
                    when {
                        o.isEmpty() -> null.also { notes += "${st.label}: classe ainda não carregou" }
                        o.size > 1 -> null.also { notes += "${st.label}: ${o.size} objetos, não sei qual" }
                        else -> (o[0] + d.liveOffset(o[0], st.field, st.off)).toString(16)  // campo mudou de lugar: pelo nome
                    }
                }
                st.ptr.isNotEmpty() -> runCatching { d.send("resolve ${st.ptr}", quiet = true).status }.getOrNull()
                    ?: null.also { notes += "${st.label}: ponteiro ainda não resolve" }
                else -> {
                    val f = runCatching { d.send("aobfind ${st.sig}", quiet = true) }.getOrNull()
                    when {
                        f == null || f.lines.isEmpty() -> null.also { notes += "${st.label}: assinatura não achada" }
                        f.lines.size > 1 -> null.also { notes += "${st.label}: assinatura em ${f.status} lugares" }
                        else -> (f.lines[0].toLong(16) + st.sigOff).toString(16)
                    }
                }
            }
            r.steps.count { st ->
                val a = place(st) ?: return@count false
                val w = runCatching { d.send("write $a ${st.type} ${st.value.arg()}", quiet = true) }
                if (w.isFailure) notes += "${st.label}: ${w.exceptionOrNull()?.message}"
                else if (st.freeze) frozen += Hit(a, st.type, "?", normalizeNumbers(st.value)) to st
                w.isSuccess
            }
        }
        if (frozen.isNotEmpty()) {
            addSaved(frozen.map { it.first }, frozen = true)
            // o salvo congelado guarda como foi achado, para voltar sozinho ao reabrir
            frozen.forEach { (h, st) ->
                val i = Session.saved.indexOfFirst { it.addr == h.addr && it.type == h.type }
                if (i >= 0) Session.saved[i] = Session.saved[i].let {
                    it.copy(
                        name = it.name.ifEmpty { st.label },
                        il2 = if (st.cls.isNotEmpty()) "${st.cls}+${st.off.toString(16)}" else it.il2,
                        il2Field = it.il2Field.ifEmpty { st.field },
                        pointer = it.pointer.ifEmpty { st.ptr }, chains = if (st.ptr.isNotEmpty() && st.ptr !in it.chains) it.chains + st.ptr else it.chains,
                        sig = it.sig.ifEmpty { st.sig }, sigOff = if (it.sig.isEmpty()) st.sigOff else it.sigOff,
                    )
                }
            }
        }
        Diag.add("Receita ${r.name}: $ok de ${r.steps.size} aplicados" + if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})")
        return "${r.name}: $ok de ${r.steps.size} aplicados." + if (notes.isEmpty()) "" else " " + notes.joinToString("; ")
    }

    // receitas com "aplicar ao abrir": tenta a cada 3 s até o jogo carregar as classes (até 3 minutos)
    private val autoDone = HashSet<String>()

    private suspend fun autoRecipes() {
        val pid = currentPid
        if (pid == 0 || daemon == null || System.currentTimeMillis() - attachedAt > 3 * 60_000 || retries % 2 != 0) return
        Session.recipes.filter { it.auto && "$pid:${it.id}" !in autoDone }.forEach { r ->
            val msg = applyRecipe(r)
            if (msg.startsWith("${r.name}: ${r.steps.size} de")) {
                autoDone += "$pid:${r.id}"
                Session.restoreInfo = "Receita aplicada sozinha: $msg"
            }
        }
    }

    private fun storeRecipes() = runCatching { recipesFile.writeText(JSONArray().apply { Session.recipes.forEach { put(it.toJson()) } }.toString()); backupMemorySoon() }

    private fun loadRecipes() {
        Session.recipes.clear()
        runCatching {
            val a = JSONArray(recipesFile.readText())
            for (i in 0 until a.length()) Session.recipes.add(a.getJSONObject(i).toRecipe())
        }
        Session.classes.clear()
        prefs.getString("classes-$savedPkg", null)?.split(',')?.filter { CLASS_NAME.matches(it) }?.let(Session.classes::addAll)
    }

    /** Receita em texto (JSON) para copiar e mandar; Colar lista nos Salvos aceita de volta. */
    fun exportRecipe(r: Recipe): String = JSONObject().put("tinker_receita", 1).put("jogo", Session.process?.pkg ?: "").put("receita", r.toJson()).toString(2)

    // Ponteiros, offsets e assinatura: como um salvo volta a valer depois de reiniciar o jogo

    private val currentPid get() = Session.process?.pid ?: 0

    /** O endereço é de outra vez do jogo: ler ou escrever ali pode derrubar o jogo. */
    fun stale(item: SavedItem) = item.pid != 0 && item.pid != currentPid

    /** Procura caminhos de ponteiro até o salvo, como o "Pointer scan" do Cheat Engine; ficam guardados nele. */
    suspend fun scanPointers(item: SavedItem, maxOff: Int = 4096, levels: Int = 4): String = call { d ->
        // endereço de outra vez do jogo: buscaria lixo e jogaria fora os caminhos de reserva bons
        if (stale(Session.saved.firstOrNull { it.id == item.id } ?: item)) return@call "Esse salvo é de outra vez do jogo: ache o valor de novo antes de buscar ponteiros."
        val r = d.frozenGame { d.send("pointers ${item.addr} $maxOff $levels ${if (Session.gameLibsOnly) 1 else 0}") }
        val limited = r.status.split(" ").getOrNull(1) == "1"
        val chains = r.lines.take(MAX_CHAINS)
        // já escolhe o melhor (base na lib do jogo, caminho curto); os outros ficam de reserva
        val cur = Session.saved.firstOrNull { it.id == item.id } ?: item
        val pick = if (cur.pointer.isEmpty() || !cur.verified) chains.firstOrNull() else null
        replace(item) { if (pick != null) it.copy(chains = chains, pointer = pick, verified = false) else it.copy(chains = chains) }
        if (pick != null) Diag.add("Ponteiro de ${label(item)} escolhido sozinho: $pick (${chains.size} de reserva)")
        Session.pointerInfo = if (r.lines.isEmpty()) "Nenhum caminho. Tente mais níveis, uma distância maior, ou desligue \"Ponteiros só nas libs do jogo\" em Ajustes."
        else "Ponteiro guardado: ${pointerText(chains.first())} (+${chains.size - 1} de reserva${if (limited) ", há mais" else ""}). " +
            "Enquanto você joga, o Tinker descarta os que erram; ao reabrir o jogo, ele confirma sozinho."
        Session.pointerInfo
    }

    /** Fica quem chega num endereço com o valor que o jogo mostra agora; sobrando um, já passa a usá-lo. */
    suspend fun filterPointersByValue(item: SavedItem, values: String): String = call { d ->
        val wanted = parseValues(values)
        val before = item.chains.size
        val kept = d.matching(item.chains, item.type) { matchesAny(it, item.type, wanted) }
        if (kept.isNotEmpty()) replace(item) { it.copy(chains = kept.map { k -> k.first }) }  // valor errado não apaga as reservas
        Diag.add("Ponteiros de ${label(item)} filtrados pelo valor $values: ${kept.size} de $before")
        val one = kept.map { it.second }.distinct().size == 1
        if (kept.isNotEmpty() && one) d.usePointer(item, kept.first().first)
        Session.pointerInfo = when {
            kept.isEmpty() -> "Nenhum caminho chega nesse valor (as reservas foram mantidas). Confira o número ou espere o jogo carregar."
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
        replace(item) { it.copy(pointer = chain, anchor = "", offset = 0, verified = true) }
        if (chain.isNotEmpty()) linkNear(item.copy(pointer = chain, anchor = "", offset = 0))
    }

    private fun Daemon.usePointer(item: SavedItem, chain: String) {
        setPointer(item, chain)
        replace(item) { it.copy(trusted = true) }  // só é chamado quando o valor bateu
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
            // ponteiro ainda não confirmado: na vez do jogo em que foi achado, o endereço achado é o certo
            // ponteiro não confirmado nunca é seguido aqui: na vez do jogo em que foi achado o endereço achado é o certo,
            // e numa vez nova só confirmPointer (que confere o último valor) pode mudar o salvo de lugar
            if (item.pointer.isEmpty() || !item.verified) return item
            runCatching { send("resolve ${item.pointer}", quiet = true).status }.getOrNull() ?: return item
        }
        return moveTo(item, a, if (item.anchor.isNotEmpty()) "Offset" else "Ponteiro")
    }

    /** Põe o salvo em addr neste processo; outro salvo do mesmo tipo já ali: não duplica. */
    private fun moveTo(item: SavedItem, addr: String, how: String): SavedItem {
        if ((addr.toLongOrNull(16) ?: 0) < 0x10000) return item  // endereço sem sentido (ponteiro nulo + offset)
        if (addr != item.addr && Session.saved.any { it.id != item.id && it.addr == addr && it.type == item.type && !stale(it) }) return item
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
    private fun Daemon.restoreAll(pid: Int, signature: Boolean = true, retry: Boolean = false): String {
        val back = mutableListOf<String>()
        val lost = mutableListOf<String>()
        Session.saved.toList().sortedBy { it.anchor.isNotEmpty() }.forEach { old ->
            val item = Session.saved.firstOrNull { it.id == old.id } ?: return@forEach
            if (item.pid == pid || item.temp) return@forEach  // mesmo processo: vale; cópia temporária: não existe mais
            val why = mutableListOf<String>()  // por que não voltou: vai para o registro
            val how = when {
                item.pointer.isNotEmpty() -> confirmPointer(item, pid, why)
                item.anchor.isNotEmpty() -> "offset".takeIf { resolve(item).pid == pid }.also { if (it == null) why += "o salvo que ele segue não voltou" }
                else -> null
            } ?: fromChains(item, pid, why) ?: (if (signature) fromIl2cpp(item, pid, why) ?: fromSignature(item, pid, why) else null)
            when {
                how != null -> back += "${label(item)} ($how)"
                item.pid != 0 || item.chains.isNotEmpty() || item.sig.isNotEmpty() -> {
                    lost += label(item)
                    if (!retry) Diag.add("Não voltou: ${label(item)} (último valor ${item.value}): ${why.joinToString("; ").ifEmpty { "nada para seguir" }}")
                }
            }
        }
        if (back.isEmpty() && (lost.isEmpty() || retry)) return ""  // nova tentativa sem novidade: não repete o aviso
        val msg = (if (back.isEmpty()) "" else "Voltaram sozinhos: ${back.joinToString()}. ") +
            (if (lost.isEmpty()) "" else "Ainda não achados (o Tinker tenta de novo por 2 minutos): ${lost.joinToString()}.")
        Diag.add("Restauração dos salvos: $msg")
        return msg.trim()
    }

    /**
     * O jogo acabou de abrir e ainda carrega o save: o ponteiro não chega no valor ainda. Tenta de novo a cada 3 s
     * (assinatura a cada 15 s, que lê a memória toda) durante 2 minutos depois de escolher o jogo; no fim registra o motivo de cada um.
     */
    private suspend fun retryRestore() {
        val pid = currentPid
        if (pid == 0 || daemon == null) return
        if (Session.saved.none { stale(it) && !it.temp && (it.pointer.isNotEmpty() || it.anchor.isNotEmpty() || it.chains.isNotEmpty() || sigUseful(it.sig) || it.il2.isNotEmpty()) }) return
        retries++
        val late = System.currentTimeMillis() - attachedAt > 2 * 60_000
        if (late && !gaveUp) {
            // 2 minutos: registra uma vez por que cada um não voltou, com o que o jogo tem agora; depois segue tentando devagar
            gaveUp = true
            call(start = false) { d -> d.restoreAll(pid) }.let { if (it.isNotEmpty()) Session.restoreInfo = "$it O Tinker continua procurando a cada 30 s; ↻ procura na hora." }
            return
        }
        if (late && retries % 10 != 0) return  // devagar: a cada 30 s, assinatura a cada 2 min
        val msg = call(start = false) { d -> d.restoreAll(pid, signature = retries % (if (late) 40 else 5) == 0, retry = true) }
        if (msg.isNotEmpty()) Session.restoreInfo = msg
        else if (!late && Session.saved.any(::stale)) Session.restoreInfo = "Esperando o jogo carregar para trazer os salvos de volta… (${retries * 3} s)"
    }

    /** Botão ↻ nos Salvos: procura agora os que estão de outra vez do jogo (com assinatura) e diz por que não voltaram. */
    suspend fun retryNow(): String = call { d ->
        d.restoreAll(currentPid).ifEmpty { "Nada para procurar." }.also { Session.restoreInfo = it }
    }

    private var gaveUp = false

    // endereço achado na última tentativa, por salvo: um achado sem conferir o valor só vale se repetir
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun steady(key: String, addr: String): Boolean = (lastSeen.put(key, addr) == addr)

    private var attachedAt = 0L
    private var retries = 0

    /** Último valor bom para reconhecer o salvo: 0, 1 e -1 aparecem em todo lugar e dariam falso positivo. */
    private fun telling(item: SavedItem): List<ClosedFloatingPointRange<Double>>? {
        val v = readable(item.value, item.type) ?: return null
        if (v in setOf("0", "1", "-1", "?")) return null
        return runCatching { parseValues(v) }.getOrNull()
    }

    /** Ponteiro em uso ao reabrir: segue; se ainda não estava confirmado, só vale se chegar no último valor (aí fica confirmado). */
    private fun Daemon.confirmPointer(item: SavedItem, pid: Int, why: MutableList<String>): String? {
        if (item.verified) return "ponteiro".takeIf { resolve(item).pid == pid }.also { if (it == null) why += "ponteiro confirmado não resolve agora" }
        val wanted = telling(item)
        val a = runCatching { send("resolve ${item.pointer}", quiet = true).status }.getOrNull() ?: return null.also { why += "ponteiro escolhido não resolve agora" }
        val v = runCatching { send("read $a ${item.type}", quiet = true).status }.getOrDefault("?")
        if (wanted != null && matchesAny(v, item.type, wanted)) {
            if (moveTo(item, a, "Ponteiro").pid != pid) return null
            replace(item) { it.copy(verified = true, trusted = true) }
            Diag.add("Ponteiro de ${label(item)} confirmado ao reabrir o jogo: ${item.pointer}")
            return "ponteiro confirmado"
        }
        // o valor mudou (você jogou, ou o jogo carregou outro save), mas cabe no histórico: segue o escolhido, ainda a confirmar
        // (quando o mesmo endereço repete em duas tentativas: com o jogo carregando, o caminho ainda muda de lugar)
        if (!item.seenMin.isNaN() && v != "?" && v !in setOf("0", "-1") && plausible(item, v) && steady("h" + item.id, a)) {
            if (moveTo(item, a, "Ponteiro").pid != pid) return null
            replace(item) { it.copy(value = v, trusted = false) }
            Diag.add("Ponteiro de ${label(item)} seguido pelo histórico: chega em 0x$a com $v (faixa ${item.seenMin}–${item.seenMax})")
            return "ponteiro, valor dentro do histórico"
        }
        why += "ponteiro escolhido chega em 0x$a com valor $v" + if (item.seenMin.isNaN()) "" else " (fora da faixa ${item.seenMin}–${item.seenMax})"
        return null
    }

    /** Na mesma vez do jogo: tira os caminhos que não chegam mais no salvo; o escolhido passa a ser o melhor que sobrou. */
    private fun Daemon.pruneChains(item: SavedItem) {
        // sai só quem chega em OUTRO endereço; quem falhou agora (troca de cena) fica para a próxima
        val kept = item.chains.filter { c -> runCatching { send("resolve $c", quiet = true).status }.getOrNull().let { it == null || it == item.addr } }
        if (kept.size == item.chains.size) return
        // ponteiro tirado pelo usuário continua tirado; confirmado ou ainda certo continua; senão o melhor que sobrou
        val pick = if (item.pointer.isEmpty() || item.pointer in kept) item.pointer else kept.firstOrNull().orEmpty()
        replace(item) { it.copy(chains = kept, pointer = pick, verified = if (pick != item.pointer) false else it.verified) }
        Diag.add("Ponteiros de ${label(item)}: ${item.chains.size - kept.size} pararam de chegar nele e saíram; sobraram ${kept.size}" +
            if (pick != item.pointer) " (escolhido agora: ${pick.ifEmpty { "nenhum" }})" else "")
    }

    /** Caminhos guardados: fica quem chega no último valor conhecido; se todos chegam no mesmo endereço, usa o melhor. */
    /**
     * Caminhos guardados. 1) Quem chega no último valor; se todos chegam no mesmo endereço, usa o melhor.
     * 2) Consenso: o valor pode ter mudado com o jogo fechado (o jogo carregou outro save, o valor estava congelado);
     *    se vários caminhos, de pelo menos duas bases diferentes, chegam no mesmo endereço e quase nenhum discorda, é ele.
     */
    private fun Daemon.fromChains(item: SavedItem, pid: Int, why: MutableList<String>): String? {
        if (item.chains.isEmpty()) return null
        val at = item.chains.mapNotNull { c -> runCatching { send("resolve $c", quiet = true).status }.getOrNull()?.let { c to it } }
        val wanted = telling(item)
        if (wanted != null) {
            val same = at.filter { (_, a) -> matchesAny(runCatching { send("read $a ${item.type}", quiet = true).status }.getOrDefault("?"), item.type, wanted) }
            if (same.isNotEmpty() && same.map { it.second }.distinct().size == 1) {
                usePointer(item, same.first().first)
                if (Session.saved.firstOrNull { it.id == item.id }?.pid == pid) return "ponteiro"
            }
            if (same.size > 1) why += "${same.size} caminhos chegam no último valor, em ${same.map { it.second }.distinct().size} lugares"
        }
        val groups = at.groupBy { it.second }.values.sortedByDescending { it.size }
        val best = groups.firstOrNull().orEmpty()
        val second = groups.getOrNull(1)?.size ?: 0
        val bases = best.map { it.first.substringBefore(' ') }.distinct().size
        // o ponteiro escolhido concorda com outro caminho: dois caminhos independentes chegando no mesmo lugar já bastam
        val withChosen = item.pointer.isNotEmpty() && best.any { it.first == item.pointer } && best.size >= 2 && best.size > second
        val there = best.firstOrNull()?.let { runCatching { send("read ${it.second} ${item.type}", quiet = true).status }.getOrNull() }
        if (there != null && !plausible(item, there)) why += "consenso chega em valor $there, fora do histórico"
        // último valor era bom e agora lê 0: o jogo ainda está carregando; tenta de novo depois
        else if (there == "0" && telling(item) != null) why += "consenso chega em valor 0 (jogo carregando?)"
        else if (((best.size >= 3 && bases >= 2 && best.size >= 2 * second) || withChosen) && !steady(item.id, best.first().second)) {
            // jogo carregando: os caminhos ainda mudam de lugar entre uma tentativa e outra; espera repetir
            why += "consenso em 0x${best.first().second}, esperando confirmar na próxima tentativa"
        }
        else if ((best.size >= 3 && bases >= 2 && best.size >= 2 * second) || withChosen) {
            val moved = moveTo(item, best.first().second, "Consenso")
            if (moved.pid == pid) {
                // ainda não confirmado: o próximo descarte (enquanto você joga) e a próxima abertura conferem;
                // o valor guardado passa a ser o de agora (o do jogo fechado não serve mais para conferir)
                val now = runCatching { send("read ${moved.addr} ${item.type}", quiet = true).status }.getOrNull()
                val pick = if (withChosen) item.pointer else best.first().first
                replace(item) { it.copy(pointer = pick, verified = false, trusted = false, value = now ?: it.value) }
                Diag.add("${label(item)} achado por consenso: ${best.size} caminhos de $bases bases chegam em 0x${moved.addr} (valor mudou com o jogo fechado)")
                return "${best.size} caminhos concordam"
            }
        }
        why += "caminhos: ${at.size} de ${item.chains.size} resolvem, maior grupo ${best.size} ($bases bases), segundo $second"
        return null
    }

    /** Assinatura: procura os bytes em volta; vale se sobrar um lugar só (com o último valor, ou o único achado). */
    private fun Daemon.fromSignature(item: SavedItem, pid: Int, why: MutableList<String>): String? {
        if (!sigUseful(item.sig)) return null.also { why += "assinatura ainda aprendendo" }
        val r = runCatching { send("aobfind ${item.sig}", quiet = true) }.getOrNull() ?: return null
        // mais de 50 lugares: a assinatura não identifica
        if (r.status.toLongOrNull() != r.lines.size.toLong()) return null.also { why += "assinatura em ${r.status} lugares" }
        val places = r.lines.map { (it.toLong(16) + item.sigOff).toString(16) }
        val wanted = telling(item)
        val same = if (wanted == null) emptyList() else places.filter { a ->
            matchesAny(runCatching { send("read $a ${item.type}", quiet = true).status }.getOrDefault("?"), item.type, wanted)
        }
        // nenhum com o último valor: fica o único que cabe no histórico
        val fits = if (same.isNotEmpty()) same else places.filter { a -> plausible(item, runCatching { send("read $a ${item.type}", quiet = true).status }.getOrDefault("?")) }
        val addr = same.singleOrNull() ?: fits.singleOrNull() ?: places.singleOrNull()
            ?: return null.also { why += "assinatura em ${places.size} lugares (${same.size} com o último valor, ${fits.size} dentro do histórico)" }
        return "assinatura".takeIf { moveTo(item, addr, "Assinatura").pid == pid }?.also { replace(item) { it.copy(trusted = false) } }
    }

    /** Aprende a assinatura enquanto o jogo roda: bytes em volta que mudaram viram ??. */
    private fun Daemon.learnSig(item: SavedItem) {
        val vlen = TYPE_SIZES.getValue(item.type) * if (item.type.startsWith("x") && '@' !in item.type) 2 else 1  // cifrado: chave + valor
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
    /** where: data (dados), xa (código do jogo) ou xs (código do sistema), como as regiões Xa e Xs do GameGuardian. */
    suspend fun searchAob(pattern: String, where: String): String = call { d ->
        val p = pattern.replace(" ", "").lowercase()
        require(AOB.matches(p) && p.chunked(2).any { it != "??" }) { "Padrão inválido: use pares hex e ?? (ex.: 1F 20 ?? D5)" }
        val n = d.frozenGame { d.send("aob $p ${where.takeIf { it == "xa" || it == "xs" } ?: "data"}") }.status
        Session.canUndo = false
        inFile = false
        step(true, "AOB $pattern", n)
        if (n == "0") "Nenhum lugar com esses bytes${if (where == "xa" || where == "xs") " no código" else ""}." else "$n lugares com esses bytes."
    }

    /**
     * Achar parecidos: os objetos com o mesmo formato do salvo (no DYSMANTLE, todos os slots do inventário). Lê 0x60 bytes a
     * partir do valor; o valor, a cópia ao lado e os ponteiros viram curinga, e o resto é o "formato" procurado como AOB.
     * Se só ele mesmo casar, tenta de novo guardando só zeros, 1 e 1.0f (o que costuma ser igual em objetos do mesmo tipo).
     */
    suspend fun findSimilar(item: SavedItem): String = call { d ->
        require(!stale(item)) { "o salvo ainda não voltou nesta vez do jogo" }
        val a = item.addr.toLong(16)
        val w = d.send("dump ${a.toString(16)} i32 24", quiet = true).lines.map { it.substringAfter(' ').toLongOrNull()?.toInt() }
        require(w.size == 24 && w.all { it != null }) { "não li a memória em volta do salvo" }
        val words = w.map { it!! }
        val wild = BooleanArray(24)
        val vw = maxOf(1, TYPE_SIZES.getValue(item.type) / 4)
        for (i in 0 until minOf(24, vw * 2)) wild[i] = true  // o valor e a cópia ao lado mudam de objeto para objeto
        for (i in 0 until 23) if ((a + 4L * i) % 8 == 0L) {
            val q = (words[i].toLong() and 0xffffffffL) or (words[i + 1].toLong() shl 32)
            if (q in 0x10000L..0x7fffffffffffL && words[i + 1] != 0) { wild[i] = true; wild[i + 1] = true }
        }
        fun pattern(loose: Boolean) = words.indices.joinToString("") { i ->
            val x = words[i]
            if (wild[i] || (loose && x != 0 && x != 1 && x != 0x3f800000)) "????????"
            else (0 until 4).joinToString("") { k -> "%02x".format((x shr (8 * k)) and 0xff) }
        }
        var n = d.frozenGame { d.send("aob ${pattern(false)} data") }.status
        var how = ""
        if ((n.toLongOrNull() ?: 0) < 2) { n = d.frozenGame { d.send("aob ${pattern(true)} data") }.status; how = " (formato mais solto)" }
        Session.canUndo = false
        inFile = false
        val label = item.name.ifEmpty { "0x${item.addr}" }
        step(true, "parecidos com $label", n)
        Diag.add("Achar parecidos com $label: $n objetos$how")
        "$n objetos com o mesmo formato de $label$how. Veja os valores na Busca; os resultados aparecem como Dword."
    }

    /** Comparar fichas: 0x40 bytes antes e depois de dois salvos, lado a lado; ≠ marca o que muda de um para o outro. */
    suspend fun compareObjects(x: SavedItem, y: SavedItem): String = call { d ->
        require(!stale(x) && !stale(y)) { "os dois salvos precisam ter voltado nesta vez do jogo" }
        fun read(i: SavedItem) = d.send("dump ${(i.addr.toLong(16) - 0x40).toString(16)} i32 33", quiet = true).lines.map { it.substringAfter(' ') }
        val ax = read(x)
        val ay = read(y)
        buildString {
            appendLine("pos      ${x.name.ifEmpty { x.addr }.take(12).padEnd(12)} ${y.name.ifEmpty { y.addr }.take(12)}")
            for (i in ax.indices) {
                val off = i * 4 - 0x40
                val pos = (if (off < 0) "-0x" + (-off).toString(16) else "+0x" + off.toString(16)).padEnd(7)
                val vx = ax[i].toLongOrNull()?.let { shown(it) } ?: "?"
                val vy = ay.getOrNull(i)?.toLongOrNull()?.let { shown(it) } ?: "?"
                appendLine("$pos ${if (vx == vy) " " else "≠"} ${vx.padEnd(12)} $vy")
            }
        }
    }

    /** Bytes crus em hexadecimal, 16 por linha, com o texto ao lado (como o Memory View do Cheat Engine). */
    suspend fun hexView(addr: String, lines: Int = 8): String = call { d ->
        val start = (addr.toLong(16) - 16L * (lines / 2)).coerceAtLeast(0) / 16 * 16
        val bytes = d.send("dump ${start.toString(16)} i8 ${16 * lines}", quiet = true).lines.map { it.substringAfter(' ').toIntOrNull()?.and(0xff) }
        bytes.chunked(16).mapIndexed { r, row ->
            val hex = row.joinToString(" ") { b -> b?.let { "%02x".format(it) } ?: "??" }
            val txt = row.joinToString("") { b -> if (b != null && b in 32..126) b.toChar().toString() else "." }
            "${(start + 16L * r).toString(16)}  $hex  $txt"
        }.joinToString("\n")
    }

    /** Editar no visor hexadecimal: escreve os bytes ("1F 20 03 D5") a partir de addr, um por um. */
    suspend fun writeBytes(addr: String, hex: String): String = call { d ->
        val h = hex.replace(" ", "").lowercase()
        require(Regex("""([0-9a-f]{2}){1,64}""").matches(h)) { "Bytes inválidos: pares hexadecimais, ex.: 1F 20 03 D5" }
        val a = addr.toLong(16)
        h.chunked(2).forEachIndexed { i, b -> d.send("write ${(a + i).toString(16)} i8 ${b.toInt(16).toByte()}", quiet = true) }
        Diag.add("Bytes escritos em 0x$addr: $hex")
        "${h.length / 2} bytes escritos em 0x$addr."
    }

    /** Busca por texto: primeiro como UTF-8 (C++, Lua), se não achar como UTF-16 (Java, C#). Os achados aparecem como Dword. */
    suspend fun searchText(text: String): String {
        require(text.length >= 3) { "Digite pelo menos 3 letras." }
        val utf8 = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val r = searchAob(utf8, "data")
        if (!r.startsWith("Nenhum")) return "\"$text\" (UTF-8): $r"
        val utf16 = text.toByteArray(Charsets.UTF_16LE).joinToString("") { "%02x".format(it) }
        return "\"$text\" (UTF-16): " + searchAob(utf16, "data")
    }

    // ---- foto do jogo: antes/depois de uma ação (ex.: pegar a recompensa do correio) e voltar como estava ----

    /** Tira a foto (a memória toda vai para um arquivo) e, parado 4 s, marca o que muda sozinho para não confundir depois. */
    suspend fun photoTake(): String {
        Diag.add("Foto do jogo: tirando…")  // se o Android fechar tudo no meio, o registro mostra que foi aqui
        val mb = call { d -> d.send("photo").status }
        delay(4000)  // fora do call: o daemon fica livre enquanto espera
        val noise = call { d -> d.send("photonoise").status }
        Diag.add("Foto do jogo: $mb MB; $noise Dwords mudam sozinhos (ficam fora)")
        return "Foto tirada ($mb MB). Agora faça a ação no jogo (ex.: pegue a recompensa) e toque em Ver o que mudou."
    }

    /** O que mudou desde a foto (fora o que muda sozinho e os ponteiros): vai para a lista da Busca, onde dá para refinar. */
    suspend fun photoDiff(): String {
        val n = call { d ->
            val n = d.send("photodiff").status
            Session.canUndo = false
            inFile = false
            step(true, "o que mudou desde a foto", n)
            n
        }
        // muitos: espera parado e tira o que continua mudando sozinho (a foto dava 2 milhões no Police Chief)
        if ((n.toLongOrNull() ?: 0) <= 1000) return "$n valores mudaram desde a foto. Refine para ficar só com os da ação (ex.: o número de presentes) e toque em Voltar como estava."
        delay(3000)
        compare("same")
        val m = Session.count ?: list().first ?: 0
        Diag.add("Foto do jogo: $n mudaram; $m depois de tirar o que muda sozinho")
        return "$n mudaram desde a foto; tirei os que continuam mudando sozinhos: sobraram $m. Refine pelo valor da ação e toque em Voltar como estava."
    }

    /**
     * Põe de volta o valor da foto: em todos os resultados da lista, só nos escolhidos, ou em todos menos os escolhidos
     * (except: o contador de presentes fica, o resto volta, e a recompensa fica para pegar de novo sem perder o que ganhou).
     * O que voltou fica guardado em Session.release: "Liberar de novo" escreve os mesmos valores outra vez.
     */
    suspend fun photoRestore(addrs: List<String>?, except: Boolean = false): String {
        val (msg, values) = call { d -> photoRestoreIn(d, addrs, except) }
        withContext(Dispatchers.Main) { Session.release = values }
        return msg
    }

    private fun photoRestoreIn(d: Daemon, addrs: List<String>?, except: Boolean): Pair<String, List<Pair<String, String>>> {
        val (ok, fail) = when {
            addrs == null -> d.send("photorestore all").status
            except -> d.send("photorestore except ${addrs.joinToString(",")}").status
            else -> addrs.map { d.send("photorestore $it").status.split(' ') }.let { r -> "${r.count { it.firstOrNull() == "1" }} ${r.count { it.firstOrNull() != "1" }}" }
        }.split(' ').let { (it.getOrNull(0) ?: "0") to (it.getOrNull(1) ?: "0") }
        // o que voltou (lido agora, já com o valor da foto): para liberar de novo sem tirar foto
        val back = if (addrs != null && !except) addrs else d.send("list 2000", quiet = true).lines.map { it.substringBefore(' ') }.filter { a -> addrs?.contains(a) != true }
        val values = back.take(500).mapNotNull { a -> runCatching { a to d.send("read $a i32", quiet = true).status }.getOrNull() }
        Diag.add("Foto do jogo: voltaram $ok valores ($fail falharam)" + if (except) ", menos ${addrs?.size} escolhidos" else "")
        return ("$ok valores voltaram como estavam na foto" + (if (fail != "0") " ($fail não deu)" else "") + ". Se a recompensa reapareceu, pegue e use Liberar de novo.") to values
    }

    /** Escreve outra vez os valores que liberaram a recompensa (sem tirar foto de novo). Vale nesta vez do jogo. */
    suspend fun releaseAgain(): String = call { d ->
        val list = Session.release
        require(list.isNotEmpty()) { "use Voltar como estava primeiro" }
        val ok = list.count { (a, v) -> runCatching { d.send("write $a i32 $v").status }.isSuccess }
        Diag.add("Liberar de novo: $ok de ${list.size} valores")
        "Liberado de novo: $ok de ${list.size} valores voltaram. Pegue a recompensa no jogo."
    }

    // ---- backup do save do jogo (pasta de dados do app, sem cache) ----

    private fun gamePkg() = Session.process?.pkg ?: savedPkg ?: error("escolha o jogo primeiro")

    suspend fun backupSave(): String = call { d ->
        val r = d.send("backup ${gamePkg()}").status.split(' ')
        val kb = (r.getOrNull(1)?.toLongOrNull() ?: 0) / 1024
        Diag.add("Backup do save: ${r[0]} ($kb KB)")
        "Backup do save guardado: ${r[0]} ($kb KB). Se algo der errado, volte ele aqui."
    }

    // ---- editor de save: os arquivos de progresso do jogo (preferências XML, JSON), campos numéricos com nome ----

    suspend fun saveFiles(): List<Pair<String, Long>> = call { d ->
        d.send("savefiles ${gamePkg()}", quiet = true).lines.filter { it.startsWith("file ") }.map { it.split(' ') }.filter { it.size == 3 }
            .map { it[1] to (it[2].toLongOrNull() ?: 0) }
            // os que dá para editar primeiro: preferências (PlayerPrefs do Unity) e JSON
            .sortedBy { (f, _) -> if (f.endsWith(".xml") || f.endsWith(".json")) 0 else 1 }
    }

    /** O texto do arquivo, ou erro se for binário/cifrado (aí o editor não ajuda). */
    suspend fun readSave(rel: String): String {
        val tmp = File(app.cacheDir, "save-edit.tmp").apply { delete() }
        call { d -> d.send("readsave ${gamePkg()} $rel ${tmp.absolutePath}") }
        val bytes = tmp.readBytes()
        val odd = bytes.count { b -> b == 0.toByte() || (b in 1..8) }
        require(odd * 50 < bytes.size.coerceAtLeast(1)) { "É binário ou cifrado: o editor só lê texto (XML, JSON). Use a busca na memória." }
        return String(bytes, Charsets.UTF_8)
    }

    /** Grava as edições: backup do save antes, fecha o jogo (aberto, ele salvaria por cima) e devolve o arquivo. */
    suspend fun writeSave(rel: String, original: String, edits: Map<SaveField, String>): String {
        val text = applySaveEdits(original, edits)
        require(text != original) { "Nada mudou." }
        val backup = backupSave()
        if (Session.process != null) runCatching { killGame() }
        val tmp = File(app.cacheDir, "save-edit.tmp").apply { writeText(text) }
        call { d -> d.send("writesave ${gamePkg()} $rel ${tmp.absolutePath}") }
        Diag.add("Editor de save: $rel com ${edits.size} campo(s) mudado(s)")
        return "Save gravado (${edits.size} campo(s)). $backup Abra o jogo de novo."
    }

    suspend fun listBackups(): List<String> = call { d ->
        d.send("backups ${gamePkg()}", quiet = true).lines.filter { it.startsWith("save ") }.map { it.split(' ')[1] }.sortedDescending()
    }

    /** Volta um backup: o jogo é fechado antes (aberto, ele salvaria por cima). */
    suspend fun restoreSave(name: String): String = call { d ->
        d.send("restoresave ${gamePkg()} $name")
        Diag.add("Backup do save voltou: $name")
        "Save de $name de volta. Abra o jogo de novo."
    }

    // ---- código: "o que escreve aqui", ver as instruções e patches ----

    /** Métodos da classe do objeto (ou da classe, pelo endereço dela): nome, retorno e código. */
    suspend fun il2Methods(addr: String): List<Il2Method> = call { d ->
        d.send("il2methods $addr", quiet = true).lines.filter { it.startsWith("method ") }.map { it.split(' ', limit = 5) }.filter { it.size == 5 }
            .map { Il2Method(it[1], it[2].toIntOrNull(16) ?: 0, it[3], it[4]) }
    }

    /**
     * "Sempre devolve X": troca o começo do método por carregar o valor e voltar (vira um patch, com os bytes de antes guardados).
     * Método curto demais para as instruções (um getter de 2 instruções com um número grande) é recusado: escreveria no próximo.
     */
    suspend fun patchReturn(m: Il2Method, cls: String, value: String): String {
        require(Session.engine?.bits != 32) { "só jogos de 64 bits por enquanto" }
        val hex = returnPatch(m.ret, value) ?: return "Esse método devolve ${RET_NAMES[m.ret] ?: "um objeto"}: não dá para fixar com \"$value\"."
        val n = hex.length / 8
        val orig = codeAt(m.code, 0, n)
        if (orig.dropLast(1).any { it.word == 0xd65f03c0.toInt() }) return "O método ${m.name} é curto demais para esse valor (termina antes): use um número até 65535."
        val label = if (m.ret == 0x01) "não faz nada" else "sempre $value"
        return createPatch(m.code, hex, "$cls.${m.name} → $label", "${RET_NAMES[m.ret]} ${m.name}() passa a ${if (m.ret == 0x01) "voltar sem fazer nada" else "devolver $value"}", apply = false)
    }

    /**
     * Tabela do Lua (xLua, ToLua) em volta de um número, ou a própria tabela: os campos com nome, como estão no jogo.
     * Cabeçalho: "table ADDR lua54" (abriu pela tabela) ou "nodes ADDR lua54" (abriu por um campo).
     */
    suspend fun luaTable(addr: String): Pair<String, List<LuaField>> = call { d ->
        val r = d.send("luatable $addr", quiet = true)
        val head = r.lines.firstOrNull { it.startsWith("table ") || it.startsWith("nodes ") }.orEmpty()
        head.substringAfterLast(' ') to r.lines.filter { it.startsWith("lua ") }.map { it.split(' ', limit = 5) }.filter { it.size == 5 }
            .map { LuaField(it[1], it[2], it[3], it[4]) }
    }

    // ---- regiões deste jogo: onde os salvos estão e onde o jogo escreve ao carregar; a busca começa por elas ----

    private fun learnedKey() = "regioes-" + (Session.process?.pkg ?: savedPkg ?: "")

    fun learnedRegions(): Set<String> = prefs.getStringSet(learnedKey(), emptySet()).orEmpty().filter { it in REGION_CODES }.toSet()

    fun setLearnedRegions(codes: Set<String>) {
        prefs.edit().putStringSet(learnedKey(), codes.filter { it in REGION_CODES }.toSet()).apply()
        Diag.add("Regiões deste jogo: ${codes.joinToString().ifEmpty { "esquecidas (a busca volta a olhar tudo)" }}")
    }

    private fun engineTypes() = engineTypes(Session.engine?.name)

    /** As aprendidas mais as dos salvos deste jogo (região que o daemon informou ao salvar). */
    fun focusRegions(): Set<String> = learnedRegions() + Session.saved.map { it.region }.filter { it in REGION_CODES }

    suspend fun activityStart(): String = call { d ->
        d.send("activity start")
        Diag.add("Onde o jogo escreve: começou a marcar")
        "Marcando. Agora carregue o jogo, uma fase ou faça a ação, e toque em Ver."
    }

    /** Quanto foi escrito por região desde activityStart (KB), do maior para o menor; how: soft (exato) ou smaps (páginas novas). */
    suspend fun activityResult(): Pair<String, List<Triple<String, Long, Long>>> = call { d ->
        val r = d.send("activity")
        val rows = r.lines.filter { it.startsWith("act ") }.map { it.split(' ') }.filter { it.size == 4 }
            .map { Triple(it[1], it[2].toLongOrNull() ?: 0, it[3].toLongOrNull() ?: 0) }.sortedByDescending { it.second }
        val how = r.lines.firstOrNull { it.startsWith("how ") }?.substringAfter(' ').orEmpty()
        Diag.add("Onde o jogo escreve ($how): " + rows.filter { it.second > 0 }.joinToString { "${it.first} ${it.second / 1024} MB" }.ifEmpty { "nada" })
        how to rows
    }

    /** Contador de chamadas: quantas vezes o código em addr roda em secs segundos, e de onde é chamado. */
    suspend fun traceCalls(addr: String, secs: Int = 10): Pair<Int, List<Triple<String, Int, String>>> = call { d ->
        val r = d.send("trace $addr ${secs.coerceIn(3, 30)}")
        val calls = r.lines.firstOrNull { it.startsWith("calls ") }?.substringAfter(' ')?.toIntOrNull() ?: 0
        calls to r.lines.filter { it.startsWith("caller ") }.map { it.split(' ') }.filter { it.size >= 4 }.map { Triple(it[1], it[2].toIntOrNull() ?: 0, it[3]) }
    }

    /** Pela tela: o painel sai da frente e o resultado fica em Session.traceHits. */
    /** Lembra se o "O que escreve aqui?" funciona neste aparelho: o autoteste mostra, e assim dá para saber antes de tentar. */
    private fun noteDebugger(e: Throwable?) {
        val v = when {
            e == null -> "funciona"
            "não deixou observar" in (e.message ?: "") -> "não funciona neste aparelho (${e.message})"
            else -> return
        }
        prefs.edit().putString("depurador", v).apply()
    }

    fun traceLater(addr: String, label: String, secs: Int = 10) {
        Session.status = "Contando chamadas de $label por $secs s: jogue normalmente…"
        scope.launch {
            val r = runCatching { traceCalls(addr, secs) }
            noteDebugger(r.exceptionOrNull())
            withContext(Dispatchers.Main) {
                r.onSuccess { Session.traceHits = label to it }
                Session.status = r.fold({ "$label rodou ${it.first} vezes em $secs s." }, { "Não deu para contar: ${it.message}" })
                Diag.add("Contador de chamadas de $label: " + r.fold({ "${it.first} vezes" }, { "erro ${it.message}" }))
            }
        }
    }

    /** Dump das classes do próprio jogo (campos e métodos com onde ficam), como o dump.cs: vai para Download/Tinker. */
    suspend fun dumpIl2cpp(): Pair<String, File> {
        val pkg = gamePkg()
        val lines = call { d -> d.send("il2classes", quiet = true).lines }.filter { it.startsWith("class ") }.map { it.split(' ', limit = 5) }
            .filter { it.size >= 4 && LIBS.none { l -> it[1].startsWith(l) } }.take(600)
        val text = StringBuilder("// Dump do Tinker · $pkg · ${lines.size} classes do jogo\n")
        for (c in lines) {
            text.append("\nclass ${c[1]} {  // ${c[3]} campos\n")
            c.getOrNull(4)?.split(',')?.filter { it.isNotEmpty() }?.forEach { text.append("    $it;\n") }
            runCatching { il2Methods(c[2]) }.getOrDefault(emptyList()).forEach { m ->
                text.append("    ${RET_NAMES[m.ret] ?: "object"} ${m.name}();  // ${m.where}\n")
            }
            text.append("}\n")
        }
        val name = "tinker-dump-$pkg.cs"
        val file = File(File(app.cacheDir, "reports").apply { mkdirs() }, name).apply { writeText(text.toString()) }  // reports/: a pasta que dá para compartilhar
        val uri = runCatching { memoryUri(true, name, "text/plain") }.getOrNull()
        if (uri != null) app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.readBytes()) }
        Diag.add("Dump IL2CPP: ${lines.size} classes em Download/Tinker/$name")
        return "Dump pronto: ${lines.size} classes" + (if (uri != null) " em Download/Tinker/$name" else "") to file
    }

    /** "O que escreve aqui": observa o endereço por alguns segundos e devolve as instruções que escreveram nele (pc, vezes, lib+desloc.). */
    suspend fun watchWrites(addr: String, secs: Int = 15): List<Triple<String, Int, String>> = call { d ->
        val r = d.send("watch $addr ${secs.coerceIn(3, 30)}")
        r.lines.filter { it.startsWith("pc ") }.map { it.split(' ') }.filter { it.size >= 4 }
            .map { Triple(it[1], it[2].toIntOrNull() ?: 0, it[3]) }
            .also { Diag.add("O que escreve em 0x$addr: " + it.joinToString { (pc, n, w) -> "$w ($n×)" }.ifEmpty { "nada em $secs s" }) }
    }

    /** Pela tela: roda no escopo do app (o painel some enquanto a pessoa joga) e o resultado fica em Session.watchHits. */
    fun watchLater(item: SavedItem, secs: Int = 15) {
        val label = item.name.ifEmpty { "0x${item.addr}" }
        Session.status = "Mude $label no jogo nos próximos $secs s (gaste ou ganhe)…"
        scope.launch {
            val r = runCatching { watchWrites(item.addr, secs) }
            noteDebugger(r.exceptionOrNull())
            withContext(Dispatchers.Main) {
                r.onSuccess { Session.watchHits = label to it }
                Session.status = r.fold({ if (it.isEmpty()) "Nada escreveu em $label: mude o valor no jogo enquanto observa." else "${it.size} instrução(ões) escrevem em $label: veja em Salvos." },
                    { "Não deu para observar: ${it.message}" })
            }
        }
    }

    /** As instruções em volta de um endereço de código, já lidas (ARM64). */
    suspend fun codeAt(addr: String, before: Int = 8, count: Int = 32): List<CodeRow> = call { d ->
        val start = (addr.toLong(16) - before * 4L).coerceAtLeast(0) / 4 * 4
        val where = runCatching { d.send("where ${start.toString(16)}", quiet = true).status }.getOrNull()
        val lib = where?.substringBeforeLast('+')
        val off = where?.substringAfterLast('+')?.toLongOrNull(16)
        d.send("dump ${start.toString(16)} i32 $count", quiet = true).lines.mapIndexed { i, l ->
            val a = start + 4L * i
            val w = l.substringAfter(' ').toLongOrNull()?.toInt()
            CodeRow(a.toString(16), if (lib != null && off != null) "$lib+${(off + 4L * i).toString(16)}" else "", w,
                w?.let { decodeArm64(it, a) } ?: "?")
        }
    }

    private fun wordsHex(words: List<Int>) = words.joinToString("") { w -> (0 until 4).joinToString("") { "%02x".format((w ushr (8 * it)) and 0xff) } }
    private fun hexWords(hex: String) = hex.chunked(8).map { c -> c.chunked(2).reversed().joinToString("").toLong(16).toInt() }

    /** Cria o patch (bytes novos no lugar das instruções em addr) e já aplica; os bytes de agora ficam guardados para desfazer. */
    suspend fun createPatch(addr: String, newHex: String, name: String, note: String = "", apply: Boolean = true): String {
        val hex = newHex.replace(" ", "").lowercase()
        require(Regex("""([0-9a-f]{8}){1,16}""").matches(hex)) { "bytes inválidos: use 8 dígitos por instrução (ex.: 1f2003d5 = NOP)" }
        require(savedPkg != null) { "escolha o jogo primeiro" }
        val (where, orig, ctx) = call { d ->
            val w = d.send("where $addr", quiet = true).status
            val n = hex.length / 8
            val cur = d.send("dump $addr i32 $n", quiet = true).lines.map { it.substringAfter(' ').toLong().toInt() }
            val around = runCatching {
                d.send("dump ${(addr.toLong(16) - 16).toString(16)} i32 ${n + 8}", quiet = true).lines.map { it.substringAfter(' ').toLong().toInt() }
            }.getOrDefault(emptyList())
            Triple(w, wordsHex(cur), if (around.size == n + 8) wordsHex(around) else "")
        }
        require(orig != hex) { "esses já são os bytes de agora" }
        val p = CodePatch(name = name.ifBlank { where }, where = where, orig = orig, new = hex, note = note.take(300), ctx = ctx)
        withContext(Dispatchers.Main) { Session.patches.add(p); storePatches() }
        Diag.add("Patch criado: ${p.name} em $where ($orig → $hex)")
        return if (apply) setPatch(p, true) else "Patch \"${p.name}\" guardado (desligado): ligue em Salvos → Patches de código."
    }

    @Volatile private var lastPatchAt = 0L

    /** Liga (bytes novos) ou desliga (bytes originais) um patch; confere antes que o código ainda é o esperado. */
    suspend fun setPatch(p: CodePatch, on: Boolean): String {
        var relocated: String? = null
        val msg = call { d ->
            val addr = d.send("resolve ${p.where}", quiet = true).status
            val n = p.new.length / 8
            val cur = wordsHex(d.send("dump $addr i32 $n", quiet = true).lines.map { it.substringAfter(' ').toLong().toInt() })
            val want = if (on) p.new else p.orig
            when (cur) {
                want -> "já estava ${if (on) "ligado" else "desligado"}"
                (if (on) p.orig else p.new) -> {
                    hexWords(want).forEachIndexed { i, w -> d.send("write ${(addr.toLong(16) + 4L * i).toString(16)} i32 $w") }
                    if (on) lastPatchAt = System.currentTimeMillis()
                    if (on) "ligado" else "desligado"
                }
                else -> {
                    // jogo atualizado: a instrução foi para outro lugar; acha pelos bytes em volta, no código do jogo (Xa)
                    val moved = if (p.ctx.isEmpty()) null else runCatching { d.send("aobfind ${p.ctx} xa", quiet = true) }.getOrNull()
                        ?.takeIf { it.status == "1" }?.lines?.firstOrNull()?.trim()?.toLongOrNull(16)?.let { (it + 16).toString(16) }
                    if (moved == null) error("o código ali mudou (jogo atualizado?): esperava ${if (on) p.orig else p.new}, achei $cur, e não achei a instrução em outro lugar")
                    val w = d.send("where $moved", quiet = true).status
                    relocated = w
                    hexWords(want).forEachIndexed { i, word -> d.send("write ${(moved.toLong(16) + 4L * i).toString(16)} i32 $word") }
                    if (on) lastPatchAt = System.currentTimeMillis()
                    "o jogo atualizou e a instrução mudou de lugar: achei em $w e ${if (on) "liguei" else "desliguei"} lá"
                }
            }
        }
        withContext(Dispatchers.Main) {
            val i = Session.patches.indexOfFirst { it.id == p.id }
            if (i >= 0) Session.patches[i] = Session.patches[i].copy(enabled = on, suspect = if (on) false else Session.patches[i].suspect,
                where = relocated ?: Session.patches[i].where)
            storePatches()
        }
        Diag.add("Patch ${p.name}: $msg")
        return "Patch \"${p.name}\": $msg."
    }

    fun removePatch(p: CodePatch) {
        Session.patches.removeAll { it.id == p.id }
        storePatches()
        Diag.add("Patch removido: ${p.name}")
    }

    /** Jogo reaberto: liga de novo os patches que estavam ligados (menos os suspeitos). */
    private suspend fun reapplyPatches() {
        Session.patches.filter { it.enabled && !it.suspect }.forEach { p ->
            runCatching { setPatch(p, true) }.onFailure { Diag.add("Patch ${p.name} não voltou: ${it.message}") }
        }
    }

    private val patchesFile get() = savedPkg?.let { File(app.filesDir, "patches-$it.json") }

    private fun loadPatches() {
        Session.patches.clear()
        val a = patchesFile?.takeIf { it.exists() }?.let { f -> runCatching { JSONArray(f.readText()) }.getOrNull() } ?: return
        for (i in 0 until a.length()) a.optJSONObject(i)?.toPatch()?.let { Session.patches.add(it) }
    }

    private fun storePatches() = runCatching {
        patchesFile?.writeText(JSONArray().apply { Session.patches.forEach { put(it.toJson()) } }.toString())
        backupMemorySoon()
    }

    /** Volta ao resultado de antes do último refino; tocar de novo refaz. */
    suspend fun undo(): String = call { d -> Session.lastRefine = ""; "${d.send("undo").status} resultados (desfeito; toque de novo para refazer)." }

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
        }.sortedBy { !it.type.startsWith("x") }  // cifrados primeiro: o normal com o mesmo número costuma ser só cópia
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
        Session.gameGoneByUser = true
        Session.gameGone = Session.process  // oferece "Reabrir" (o GameGuardian também oferece reiniciar)
        if (Session.autoReports) sendReport("você fechou o jogo pelo Tinker")
        "Jogo fechado."
    }

    suspend fun crashLog() = call { d ->
        Diag.add("=== Falhas registradas pelo Android (buffer crash) ===")
        dedupe(crashesOf(d.send("crashlog", quiet = true).lines, Session.process?.pkg)).forEach(Diag::add)
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
            dedupe(call { d -> d.send(cmd, quiet = true).lines }.let { if (cmd == "crashlog") crashesOf(it, Session.process?.pkg) else it }).joinToString("\n")
        }.getOrElse { "(não foi possível ler: ${it.message})" }
        val p = Session.process
        val crash = section("Falhas registradas pelo Android (crash)", "crashlog")
        val game = if (p != null) section("Logcat do jogo", "logs") else "=== Logcat do jogo ===\n(nenhum jogo escolhido)"
        val text = buildString {
            appendLine("Relatório do Tinker")  // sem "=" na frente: a planilha lia como fórmula (#ERROR!)
            appendLine("Gerado em: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("App: Tinker ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()}")
            appendLine("Aparelho: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Jogo: ${p?.let { "${it.label} (${it.pkg}, PID ${it.pid})${if (Session.paused) " · pausado" else ""}" } ?: "nenhum"}")
            appendLine("Motor: ${Session.engine ?: "?"}")
            appendLine("Regiões ativas: ${Session.regions.filter { it.enabled }.joinToString { it.code }.ifEmpty { "padrão" }}")
            appendLine("Salvos: ${Session.saved.size} (congelados: ${Session.saved.count { it.frozen }})")
            val mem = android.app.ActivityManager.MemoryInfo().also { app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
            appendLine("Memória do aparelho: ${mem.availMem shr 20} MB livres de ${mem.totalMem shr 20} MB${if (mem.lowMemory) " (POUCA MEMÓRIA)" else ""}")
            appendLine("Pausa automática: ${if (Session.autoPause) "ligada" else "desligada"} · cifrados: ${if (Session.findEncrypted) "sim" else "não"} · " +
                "ponteiros ao salvar: ${if (Session.autoPointers) "sim" else "não"} · só libs do jogo: ${if (Session.gameLibsOnly) "sim" else "não"}")
            if (Session.saved.isNotEmpty()) {
                appendLine()
                appendLine("=== Salvos (${Session.saved.size}) ===")
                Session.saved.forEach { appendLine(savedLine(it)) }
                neighborsText().takeIf { it.isNotBlank() }?.let { appendLine("\n=== Vizinhos dos salvos que mudaram (100 Dwords acima e abaixo, a cada 5 s) ===\n$it") }
            }
            // só desde que o Tinker abriu: o relatório inteiro do dia passava de 140 mil letras
            val all = Diag.export().lines()
            val log = all.drop(all.indexOfLast { " iniciado · " in it }.coerceAtLeast(0))
            val problems = log.filter { l ->
                val msg = l.substringAfter(' ').substringAfter(' ')  // tira data e hora do registro
                // linhas com "| " vêm do logcat (do jogo ou do Android), não são problemas do Tinker
                !msg.startsWith("===") && !msg.startsWith("|") && PROBLEM_WORDS.any { msg.contains(it, ignoreCase = true) }
            }
            appendLine()
            appendLine("=== Resumo de problemas (${problems.size}) ===")
            appendLine(if (problems.isEmpty()) "Nenhum erro registrado." else problems.takeLast(20).joinToString("\n"))
            appendLine()
            appendLine("=== Registro do Tinker (desde que o Tinker abriu; o anterior fica no aparelho) ===")
            appendLine(log.joinToString("\n"))
            appendLine(crash)
            appendLine()
            appendLine(game)
            appendLine()
            appendLine("=== Rastro da IA (perguntas, ferramentas com entrada e saída, chamadas à API) ===")
            appendLine(Diag.aiTrace())
            appendLine()
            appendLine("=== Logcat do Tinker (erros internos do app) ===")
            appendLine(Diag.ownLogcat())
        }
        val dir = File(app.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // só o último relatório fica no cache
        File(dir, "tinker-relatorio-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.txt")
            .apply { writeText(text) }
            .also { Diag.add("Relatório gerado: ${it.name} (${it.length() / 1024} KB)") }
    }

    // Lista de salvos

    /** Uma linha por salvo no relatório: onde está, se vale nesta vez do jogo e como volta quando o jogo reabre. */
    private fun savedLine(it: SavedItem) = buildString {
        append("${label(it)}: ${TYPE_NAMES[it.type]} 0x${it.addr} = ${it.value}")
        append(if (it.pid < 0) " · do backup (volta quando o jogo abrir)" else if (stale(it)) " · de outra vez do jogo (PID ${it.pid})" else if (it.pid == 0) " · PID desconhecido" else "")
        if (it.frozen) append(" · congelado")
        if (it.pointer.isNotEmpty()) append(" · ponteiro ${it.pointer}${if (it.verified) "" else " (a confirmar)"}")
        if (it.anchor.isNotEmpty()) append(" · segue ${Session.saved.firstOrNull { a -> a.id == it.anchor }?.let(::label) ?: "?"} ${offsetText(it.offset)}")
        append(" · reservas ${it.chains.size} · assinatura ${if (sigUseful(it.sig)) "pronta" else "aprendendo"}")
        if (it.il2.isNotEmpty()) append(" · IL2CPP ${it.il2}")
        if (it.temp) append(" · cópia temporária")
        historyText(it, System.currentTimeMillis()).takeIf { h -> h.isNotEmpty() }?.let { h -> append(" · histórico $h") }
    }

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
    fun addSaved(hits: List<Hit>, frozen: Boolean = false, name: String = "") {
        val added = mutableListOf<SavedItem>()
        hits.forEach { hit ->
            val i = Session.saved.indexOfFirst { it.addr == hit.addr && it.type == hit.type }
            when {
                i < 0 -> recordValue(SavedItem(hit.addr, hit.type, hit.region, hit.value, frozen, pid = currentPid, name = name), hit.value, System.currentTimeMillis())
                    .also { Session.saved.add(it); added += it }
                // o salvo antigo está de novo nesse endereço: passa a valer nesta vez do jogo
                stale(Session.saved[i]) -> Session.saved[i] = Session.saved[i].copy(pid = currentPid, value = hit.value, frozen = frozen || Session.saved[i].frozen, trusted = true)
                frozen -> Session.saved[i] = Session.saved[i].copy(frozen = true)
            }
        }
        // mesma ficha: os salvos perto do primeiro (deste processo) passam a segui-lo pelo offset
        added.forEach { new ->
            val cur = Session.saved.firstOrNull { it.id == new.id } ?: return@forEach
            if (cur.anchor.isNotEmpty()) return@forEach
            Session.saved.firstOrNull {
                it.id != cur.id && it.pid == cur.pid && it.anchor.isEmpty() && kotlin.math.abs(it.addr.toLong(16) - cur.addr.toLong(16)) <= SAME_STRUCT
            }?.let(::linkNear)
        }
        // nome do campo pelo IL2CPP (leitura rápida, não pausa o jogo)
        if (added.size in 1..20) scope.launch {
            added.forEach { a -> Session.saved.firstOrNull { it.id == a.id }?.let { runCatching { nameFromIl2cpp(it) } } }
        }
        // ponteiros já preparados para quando o jogo reabrir (pausa o jogo por alguns segundos)
        if (Session.autoPointers && added.size in 1..3) scope.launch {
            // espera a busca em andamento: as duas pausam/continuam o jogo e se atrapalhariam
            while (Session.busy) delay(300)
            Session.busy = true
            try {
                added.mapNotNull { a -> Session.saved.firstOrNull { it.id == a.id && it.anchor.isEmpty() } }.forEach { item ->
                    Session.pointerInfo = "Preparando ponteiros de ${label(item)}…"
                    runCatching { scanPointers(item) }.onFailure { Session.pointerInfo = "Ponteiros de ${label(item)}: ${it.message}" }
                }
            } finally {
                Session.busy = false
            }
        }
        Diag.add("${if (frozen) "Congelados" else "Salvos"} ${hits.size} endereços")
        storeSaved()
    }

    /**
     * Apagados (salvos e receitas, pela id): vão junto no banco para a planilha e as outras instâncias apagarem também.
     * Sem isso, o que você apagava voltava na próxima vez que o app juntava com a planilha.
     */
    private fun deleted(): Set<String> = prefs.getStringSet("apagados", emptySet()).orEmpty()

    private fun markDeleted(ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs.edit().putStringSet("apagados", (deleted() + ids).toList().takeLast(2000).toSet()).apply()
        backupMemorySoon()
    }

    fun removeSaved(items: List<SavedItem>) {
        val gone = items.map { it.id }.toSet()
        markDeleted(gone)
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

    // ---- editar em sequência (como no GameGuardian): cada resultado ganha um número (100, 101, 102…); o que aparecer
    // no jogo diz qual endereço é o certo. "Descobrir pela tela" lê a tela sozinho e volta tudo como estava. ----

    private val seqOld = mutableListOf<Triple<String, String, String>>()  // endereço, tipo, valor de antes

    /** start + i·step em cada um (até 300); guarda os valores de antes para Voltar como estava. */
    suspend fun writeSequence(targets: List<Pair<String, String>>, start: String, step: Double = 1.0): Map<String, Long> = call { d ->
        val base = normalizeNumbers(start).toDoubleOrNull() ?: error("Digite o número inicial (ex.: 100)")
        seqOld.clear()
        val written = LinkedHashMap<String, Long>()
        targets.take(300).forEachIndexed { i, (addr, type) ->
            val old = runCatching { d.send("read $addr $type", quiet = true).status }.getOrNull() ?: return@forEachIndexed
            val v = (base + i * step).toLong()
            if (runCatching { d.send("write $addr $type $v", quiet = true) }.isSuccess) { seqOld += Triple(addr, type, old); written[addr] = v }
        }
        Diag.add("Em sequência: ${written.size} endereços com ${base.toLong()}…${(base + (written.size - 1) * step).toLong()}")
        written
    }

    suspend fun undoSequence(): String = call { d ->
        val n = seqOld.count { (a, t, v) -> runCatching { d.send("write $a $t $v", quiet = true) }.isSuccess }
        seqOld.clear()
        Diag.add("Em sequência: $n valores voltaram")
        "$n valores voltaram como estavam."
    }

    fun hasSequence() = seqOld.isNotEmpty()

    /**
     * Escreve em sequência, lê a tela e acha o número que apareceu: o dono dele é o endereço certo. Volta tudo depois.
     * Melhor que o do GG: começa logo acima do valor de agora (número absurdo pode derrubar o jogo), ignora os números
     * que já estavam na tela, lê duas vezes (o jogo pode demorar a redesenhar), mostra todos que mudaram a tela (o certo
     * e as cópias) e vai de 300 em 300 quando há mais resultados.
     */
    suspend fun findBySequence(targets: List<Pair<String, String>>, start: String): String {
        if (targets.isEmpty()) return "Sem resultados."
        withContext(Dispatchers.Main) { Session.aiHidePanel = true }
        delay(800)
        withContext(Dispatchers.Main) { Session.aiHidePanel = false }
        val before = runCatching { screenNumbers().map { it.first }.toSet() }.getOrElse { return "Não li a tela: ${it.message}" }
        val found = mutableListOf<Pair<String, Long>>()
        for ((round, batch) in targets.chunked(300).withIndex()) {
            // começa acima do valor de agora (ou do número digitado), pulando os números que já estão na tela
            val now = call { d -> runCatching { d.send("read ${batch.first().first} ${batch.first().second}", quiet = true).status.toDouble().toLong() }.getOrNull() }
            var base = start.trim().toLongOrNull() ?: ((now ?: 99L) + 1)
            base += round * 300L
            while ((base until base + batch.size).any { it in before }) base += batch.size
            val written = writeSequence(batch, base.toString())
            var seen = emptySet<Long>()
            for (tryN in 0 until 2) {
                delay(1200)
                seen = runCatching { screenNumbers().map { it.first }.toSet() }.getOrDefault(emptySet())
                if (written.values.any { it in seen }) break
            }
            undoSequence()
            found += written.entries.filter { it.value in seen }.map { it.key to it.value }
            if (found.isNotEmpty()) break
        }
        Diag.add("Em sequência pela tela: " + found.joinToString { "0x${it.first} (${it.second})" }.ifEmpty { "nenhum dos números apareceu" })
        return when (found.size) {
            0 -> "Nenhum dos números apareceu na tela: o certo não está entre estes, ou o jogo só redesenha quando o valor muda (faça ele mudar e tente de novo). Tudo voltou como estava."
            1 -> "É o 0x${found[0].first}: o ${found[0].second} apareceu no jogo. Tudo voltou como estava; salve esse resultado."
            else -> "${found.size} mudaram a tela: " + found.joinToString { "0x${it.first} (${it.second})" } + ". Um é o valor e os outros, cópias: edite cada um e veja qual o jogo mantém. Tudo voltou como estava."
        }
    }

    suspend fun setSaved(items: List<SavedItem>, value: String): String {
        val live = items.filterNot(::stale)
        if (live.isEmpty()) return "Esses salvos são de outra vez do jogo: busque o valor de novo (ou use ponteiro) antes de editar."
        // só guarda o valor nos que o jogo aceitou: antes um valor recusado (ex.: grande demais para Dword) ia para o
        // salvo e o congelamento ficava reescrevendo ele
        var error: String? = null
        val ok = call { d ->
            live.filter { item ->
                runCatching { d.send("write ${item.addr} ${item.type} ${value.arg()}", quiet = true) }.onFailure { error = it.message }.isSuccess
            }
        }
        Diag.add("Editados ${ok.size} de ${live.size} salvos para $value" + (error?.let { " ($it)" } ?: ""))
        // o congelamento reescreve o valor guardado: tem que ser o número já normalizado ("20.000" → 20000), nunca faixa
        val v = normalizeNumbers(value)
        if ('~' !in v && ';' !in v) ok.forEach { item -> replace(item) { recordValue(it.copy(trusted = true), v, System.currentTimeMillis()) } }
        val msg = if (ok.size == live.size) "${ok.size} valores alterados." else "${ok.size} de ${live.size} alterados. ${error.orEmpty()}"
        return msg + if (live.size < items.size) " ${items.size - live.size} de outra vez do jogo ficaram de fora." else ""
    }

    fun setFolder(item: SavedItem, folder: String) = replace(item) { it.copy(folder = folder.trim().take(40)) }

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

    /** Tabela do Cheat Engine (.CT) com os salvos: endereço, tipo, nome e o ponteiro quando tem. */
    fun exportCt(): String = ctFromSaved(Session.saved.toList())

    /** Aceita o formato do exportSaved, só a lista, ou uma tabela .CT do Cheat Engine. Devolve uma mensagem para a tela. */
    fun importSaved(text: String): String {
        if ("<CheatTable" in text) {
            val items = savedFromCt(text)
            require(items.isNotEmpty()) { "A tabela não tem endereços que o Tinker entenda (tipos numéricos com endereço)." }
            val new = items.filter { n -> Session.saved.none { it.pointer.isNotEmpty() && it.pointer == n.pointer || it.addr == n.addr && it.type == n.type } }
            Session.saved.addAll(new)
            storeSaved()
            Diag.add("Tabela do Cheat Engine: ${new.size} de ${items.size} importados")
            return "${new.size} de ${items.size} importados da tabela do Cheat Engine. Os com ponteiro (lib+deslocamento) voltam sozinhos; " +
                "os de endereço fixo só valem se o jogo não reiniciou."
        }
        val root = runCatching { JSONObject(text) }.getOrNull()
        root?.optJSONObject("receita")?.let { j ->
            val r = j.toRecipe().copy(id = java.util.UUID.randomUUID().toString(), auto = false)
            Session.recipes.add(r)
            storeRecipes()
            Diag.add("Receita importada: ${r.name} (${r.steps.size} campos)")
            return "Receita \"${r.name}\" importada com ${r.steps.size} campos."
        }
        val a = root?.optJSONArray("itens") ?: runCatching { JSONArray(text) }.getOrNull()
            ?: throw IllegalArgumentException("Isso não parece uma lista do Tinker (JSON).")
        var added = 0
        for (i in 0 until a.length()) {
            // pid 0 (lista de fora): endereço de vez desconhecida, não pode sair congelando
            val item = a.getJSONObject(i).toSaved().let { if (it.pid == 0) it.copy(frozen = false) else it }
            if (Session.saved.none { it.id == item.id || (it.addr == item.addr && it.type == item.type) }) { Session.saved.add(item); added++ }
        }
        storeSaved()
        val game = root?.optString("jogo").orEmpty()
        val other = game.isNotEmpty() && Session.process != null && game != Session.process?.pkg
        Diag.add("Importados $added de ${a.length()} salvos${if (game.isNotEmpty()) " (lista de $game)" else ""}")
        return "$added de ${a.length()} importados." + if (other) " Atenção: a lista é de outro jogo ($game)." else
            " Os endereços só valem se o jogo não reiniciou desde a exportação."
    }

    fun setFreezeRule(item: SavedItem, rule: String) {
        Diag.add("Congelar ${label(item)}: " + when (rule) { "up" -> "só deixa subir"; "down" -> "só deixa descer"; else -> "valor fixo" })
        replace(item) { it.copy(frozen = true, freezeRule = rule) }
    }

    fun setFrozen(items: List<SavedItem>, frozen: Boolean) {
        Diag.add("${if (frozen) "Congelados" else "Descongelados"} ${items.size} salvos")
        items.forEach { item -> replace(item) { it.copy(frozen = frozen) } }
    }

    suspend fun refreshSaved() = call { d ->
        // a aba Salvos pode estar aberta no app e no menu flutuante ao mesmo tempo: uma leitura por segundo basta
        val now = System.currentTimeMillis()
        if (!d.isAttached || now - lastRefresh < 800) return@call
        lastRefresh = now
        Session.saved.toList().forEach { old ->
            val item = d.resolve(old)
            if (stale(item)) return@forEach  // endereço de outra vez do jogo: não lê lixo
            if (item.pid == currentPid) d.learnSig(item)
            if (item.pid == currentPid && item.il2.isEmpty() && !item.temp && (refreshTick + item.id.hashCode()) % 30 == 0) d.tagIl2(item)
            // confere os caminhos de reserva de tempos em tempos e quando o valor muda (o jogo mudou de estado)
            val check = item.chains.isNotEmpty() && item.pid == currentPid
            if (item.frozen) {
                if (check && refreshTick % 20 == 0) d.pruneChains(item)
                return@forEach
            }
            val v = runCatching { d.send("read ${item.addr} ${item.type}", quiet = true).status }.getOrDefault("?")
            // "?" = não leu (o jogo fechou): guarda o último valor bom, que serve para reconhecer o salvo quando o jogo reabrir
            if (v == "?") return@forEach
            if (v != item.value) replace(item) { if (it.trusted) recordValue(it, v, now) else it.copy(value = v) }
            if (check && ((v != item.value && refreshTick % 5 == 0) || refreshTick % 20 == 0)) d.pruneChains(item.copy(value = v))
        }
        refreshTick++
    }

    private var refreshTick = 0
    @Volatile private var lastRefresh = 0L

    private fun replace(item: SavedItem, change: (SavedItem) -> SavedItem) {
        val i = Session.saved.indexOfFirst { it.id == item.id }
        if (i >= 0) Session.saved[i] = change(Session.saved[i])
        storeSaved()
    }

    // Congelar = reescrever o valor a cada 200 ms, como o GameGuardian.
    // o que está congelado no daemon agora ("endereço tipo" → valor): só vai pelo pipe o que mudou
    private val sentFreeze = HashMap<String, String>()
    private var freezeOwner: Pair<Daemon, Int>? = null
    private var freezeTick = 0

    /**
     * Congelar = o daemon reescreve a cada 100 ms (só o que o jogo mudou). Aqui só se mantém a lista dele em dia:
     * antes ia um "write" por salvo a cada 200 ms pelo pipe, o que dava lag com muitos salvos e atrasava os outros comandos.
     */
    private suspend fun freezeLoop() {
        while (true) {
            delay(200)
            // cópia: a tela adiciona e remove salvos enquanto isto roda; qualquer erro fica no registro, nunca derruba o app
            val frozen = runCatching { Session.saved.toList().filter { it.frozen } }.getOrDefault(emptyList())
            val d = daemon
            if (d == null || !d.isAttached) { sentFreeze.clear(); continue }
            // daemon novo ou outro processo do jogo: a lista do daemon começou vazia
            if (freezeOwner != d to currentPid) { sentFreeze.clear(); freezeOwner = d to currentPid }
            if (frozen.isEmpty() && sentFreeze.isEmpty()) continue
            try {
                val want = HashMap<String, SavedItem>()
                for (old in frozen) {
                    val item = runCatching { d.resolve(old) }.getOrNull() ?: continue
                    if (!stale(item)) want["${item.addr} ${item.type}"] = item  // escrever no endereço velho pode derrubar o jogo
                }
                // a regra vai junto: mudar de "fixo" para "só subir" manda de novo
                for (k in sentFreeze.keys.toList()) if (want[k]?.let { it.value + it.freezeRule } != sentFreeze[k]) { d.send("unfreeze $k", quiet = true); sentFreeze.remove(k) }
                for ((k, item) in want) if (sentFreeze[k] != item.value + item.freezeRule) try {
                    d.send("freeze $k ${item.value} ${item.freezeRule}".trim(), quiet = true)
                    sentFreeze[k] = item.value + item.freezeRule
                    if (freezeFailing.remove(k)) Diag.add("Congelamento voltou a funcionar em 0x${item.addr}")
                } catch (e: IOException) {
                    if (e.message == "o jogo fechou") throw e
                    if (freezeFailing.add(k)) Diag.add("AVISO: congelamento falhou em 0x${item.addr} (${TYPE_NAMES[item.type]} = ${item.value}): ${e.message}")
                }
                // a cada 2 s: o que o daemon tirou da lista porque o endereço parou de aceitar escrita
                if (++freezeTick % 10 == 0 && sentFreeze.isNotEmpty()) {
                    val live = d.send("frozen", quiet = true).lines.map { it.substringBeforeLast(' ') }.toSet()
                    for (k in sentFreeze.keys.toList()) if (k !in live) {
                        sentFreeze.remove(k)
                        if (freezeFailing.add(k)) Diag.add("AVISO: congelamento parou em 0x${k.substringBefore(' ')}: o endereço não aceita mais escrita (o objeto pode ter sido liberado)")
                    }
                }
            } catch (e: IOException) {
                if (e.message == "o jogo fechou") { sentFreeze.clear(); gameClosed(d) }
            } catch (e: Exception) {
                Diag.add("AVISO: erro no congelamento: ${e.message}")
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
        loadRecipes()
        loadPatches()
        // jogo atualizado: classes e offsets podem ter mudado; os salvos que não voltarem vão para a IA consertar
        gameVersion()?.let { v ->
            val last = prefs.getLong("ver-$pkg", -1L)
            Session.gameUpdated = last != -1L && last != v && Session.saved.isNotEmpty()
            if (Session.gameUpdated) Diag.add("Jogo $pkg atualizou (versão $last → $v): os salvos que não voltarem vão para a IA")
            prefs.edit().putLong("ver-$pkg", v).apply()
        }
        Diag.add("Salvos do jogo $pkg: ${Session.saved.size}${if (adopt) " (lista anterior adotada)" else ""} · receitas: ${Session.recipes.size}")
    }

    private fun loadSaved() = runCatching {
        if (!savedFile.exists()) return@runCatching
        val a = JSONArray(savedFile.readText())
        for (i in 0 until a.length()) Session.saved.add(a.getJSONObject(i).toSaved().let { if (it.pid == 0) it.copy(frozen = false) else it })
    }

    private fun storeSaved() = runCatching {
        val a = JSONArray()
        Session.saved.forEach { a.put(it.toJson()) }
        savedFile.writeText(a.toString())
        if (savedPkg != null) backupMemorySoon()  // o perfil do jogo no backup acompanha os salvos
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
    .put("chains", JSONArray(chains)).put("sig", sig).put("sigOff", sigOff).put("verified", verified)
    .put("trusted", trusted).put("il2", il2).put("il2Field", il2Field).put("temp", temp).put("folder", folder).put("freezeRule", freezeRule).put("history", JSONArray(history)).apply { if (!seenMin.isNaN()) put("seenMin", seenMin).put("seenMax", seenMax) }  // JSON não aceita NaN

/** Namespaces de bibliotecas: vão para o fim da lista de classes (o que interessa é o código do próprio jogo). */
private val LIBS = listOf(
    "System", "Unity", "Mono", "TMPro", "Google", "Firebase", "Newtonsoft", "Microsoft", "AppsFlyer", "Facebook", "Spine", "DG.",
    "Cinemachine", "Sirenix", "Coffee", "I2.", "Zenject", "Internal", "JetBrains", "Bolt", "Photon", "Steamworks", "GooglePlayGames",
    "AOT", "Il2Cpp", "Applovin", "AppLovin", "IronSource", "com.", "Com.", "Xsolla", "Lofelt", "Sentry", "PlayFab", "Amazon", "Mediation",
)

private val CLASS_NAME = Regex("""[A-Za-z0-9_.`<>]{1,120}""")  // vai para o daemon

private fun Recipe.toJson() = JSONObject().put("id", id).put("name", name).put("auto", auto).put("steps", JSONArray().apply {
    steps.forEach {
        put(JSONObject().put("cls", it.cls).put("off", it.off).put("type", it.type).put("value", it.value).put("freeze", it.freeze).put("label", it.label)
            .apply { if (it.field.isNotEmpty()) put("field", it.field); if (it.ptr.isNotEmpty()) put("ptr", it.ptr); if (it.sig.isNotEmpty()) put("sig", it.sig).put("sigOff", it.sigOff) })
    }
})

/** Como o passo acha o lugar: para o registro e a tela. */
val Step.how get() = when { cls.isNotEmpty() -> "classe"; ptr.isNotEmpty() -> "ponteiro"; else -> "assinatura" }

/** Receita de arquivo ou colada: tudo validado, porque classe, tipo e valor vão para o daemon. */
private fun JSONObject.toRecipe(): Recipe {
    val a = getJSONArray("steps")
    val steps = List(a.length()) { i ->
        val j = a.getJSONObject(i)
        val cls = j.optString("cls", "")
        val type = j.getString("type")
        val ptr = j.optString("ptr", "")
        val sig = j.optString("sig", "")
        require((cls.isEmpty() || CLASS_NAME.matches(cls)) && type in TYPE_SIZES) { "Receita inválida: $cls / $type" }
        require((ptr.isEmpty() || POINTER.matches(ptr)) && (sig.isEmpty() || AOB.matches(sig))) { "Receita inválida: ponteiro ou assinatura" }
        require(cls.isNotEmpty() || ptr.isNotEmpty() || sig.isNotEmpty()) { "Receita inválida: passo sem classe, ponteiro ou assinatura" }
        val v = normalizeNumbers(j.getString("value"))
        require(Regex("""-?[0-9.eE+-]{1,40}""").matches(v)) { "Receita inválida: valor $v" }
        Step(cls, j.optLong("off", 0).coerceIn(0L, 0x10000L), type, v, j.optBoolean("freeze", false), j.optString("label", cls).take(60), ptr, sig, j.optInt("sigOff", 0).coerceIn(0, 191),
            field = j.optString("field", "").takeIf { FIELD.matches(it) }.orEmpty())
    }
    return Recipe(optString("id", "").ifEmpty { java.util.UUID.randomUUID().toString() }, optString("name", "Receita").take(40), steps, optBoolean("auto", false))
}

private val FIELD = Regex("""[A-Za-z0-9_.<>`$]{1,120}""")  // nome de campo IL2CPP (ex.: <Gold>k__BackingField)
private val IL2 = Regex("""[A-Za-z0-9_.<>`]{1,120}\+[0-9a-f]{1,6}""")  // vai para o daemon: sem espaço nem quebra de linha

private val AOB = Regex("""([0-9a-f]{2}|\?\?){1,192}""")

/** Caminho de ponteiro aceito: vai direto para o daemon, então nada de espaço extra, quebra de linha ou outro comando. */
private val POINTER = Regex("""[^\s+]{1,100}\+[0-9a-f]{1,16}( [0-9a-f]{1,8}){0,7}""")

val COMPARE_NAMES = mapOf("inc" to "aumentou", "dec" to "diminuiu", "same" to "não mudou", "changed" to "mudou", "incby" to "aumentou", "decby" to "diminuiu")

/** "1.10.0" é mais nova que "1.9.2"? Compara número por número. */
internal fun newer(latest: String, mine: String): Boolean {
    val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
    val b = mine.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

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

/** Nome curto do tipo de um campo IL2CPP (Il2CppTypeEnum). */
fun il2TypeName(code: Int) = when (code) {
    0x02 -> "bool"; 0x03 -> "char"; 0x04 -> "sbyte"; 0x05 -> "byte"; 0x06 -> "short"; 0x07 -> "ushort"
    0x08 -> "int"; 0x09 -> "uint"; 0x0a -> "long"; 0x0b -> "ulong"; 0x0c -> "float"; 0x0d -> "double"
    0x0e -> "string"; 0x11 -> "struct"; 0x12 -> "classe"; 0x14, 0x1d -> "array"; 0x15 -> "genérico"; 0x1c -> "object"
    else -> "tipo $code"
}

/** Tipo do Tinker para ler um campo IL2CPP; null = não é número (string, classe, struct). */
fun il2Tinker(code: Int) = when (code) {
    0x08, 0x09 -> "i32"; 0x0c -> "f32"; 0x0d -> "f64"; 0x0a, 0x0b -> "i64"; 0x06, 0x07 -> "i16"; 0x02, 0x04, 0x05 -> "i8"
    else -> null
}

/**
 * Linhas da ficha a partir dos bytes lidos. Com IL2CPP, um campo por linha com nome e tipo do jogo.
 * Sem, de 4 em 4 bytes com o tipo adivinhado: ponteiro (8 bytes), decimal que parece número de jogo, cifrado (chave + valor) ou inteiro.
 */
internal fun fichaRows(start: Long, raw: ByteArray, base: Long, il2: Il2Class?): List<FichaRow> {
    fun u32(i: Int) = (0 until 4).fold(0L) { acc, k -> acc or ((raw[i + k].toLong() and 0xff) shl (8 * k)) }
    fun u64(i: Int) = u32(i) or (u32(i + 4) shl 32)
    fun at(i: Int) = (start + i).toString(16)
    val rows = mutableListOf<FichaRow>()
    if (il2 != null) {
        il2.fields.forEach { f ->
            val i = (base + f.offset - start).toInt()
            val t = il2Tinker(f.code)
            if (i < 0 || i + 8 > raw.size) return@forEach
            val v = when (t) {
                "f32" -> java.lang.Float.intBitsToFloat(u32(i).toInt()).toString()
                "f64" -> java.lang.Double.longBitsToDouble(u64(i)).toString()
                "i64" -> u64(i).toString()
                "i16" -> u32(i).toInt().toShort().toString()
                "i8" -> raw[i].toString()
                "i32" -> u32(i).toInt().toString()
                else -> "0x" + (u64(i) and 0x00ffffffffffffffL).toString(16)
            }
            rows += FichaRow(at(i), f.offset.toLong(), t ?: "", "${f.name} · ${il2TypeName(f.code)}", readable(v, t ?: "i64") ?: "NaN")
        }
        return rows
    }
    var i = 0
    while (i + 4 <= raw.size) {
        val x = u32(i).toInt()
        val q = if (i % 8 == 0 && i + 8 <= raw.size) u64(i) and 0x00ffffffffffffffL else 0
        val f = java.lang.Float.intBitsToFloat(x)
        val off = start + i - base
        // decimal de jogo: finito, nem minúsculo nem gigante, e não é um inteiro pequeno lido como float
        val floaty = f.isFinite() && kotlin.math.abs(f) in 1e-3f..1e9f && (x and 0x7fffffff) > 0x00800000
        // cifrado (chave grande aqui, valor ^ chave logo depois dando um número pequeno); a chave às vezes parece decimal
        val y = if (i + 8 <= raw.size) u32(i + 4).toInt() else 0
        val cipher = i + 8 <= raw.size && (x and 0x7fffffff) >= 0x10000 && x != y && kotlin.math.abs(x xor y) < 1_000_000
        when {
            q in 0x10000000L until 0x10000000000L -> { rows += FichaRow(at(i), off, "", "ponteiro", "0x" + q.toString(16)); i += 8; continue }
            x == 0 -> rows += FichaRow(at(i), off, "i32", "zero", "0")
            cipher && !floaty -> { rows += FichaRow(at(i), off, "xi32", "cifrado 🔒", (x xor y).toString()); i += 8; continue }
            floaty -> rows += FichaRow(at(i), off, "f32", if (cipher) "decimal (ou 🔒 ${x xor y})" else "decimal", readable(f.toString(), "f32") ?: "")
            else -> rows += FichaRow(at(i), off, "i32", "inteiro", x.toString())
        }
        i += 4
    }
    return rows
}

/** Guarda o valor novo no salvo e no histórico (últimas 20 mudanças) e alarga a faixa que ele já teve. */
internal fun recordValue(item: SavedItem, v: String, nowMs: Long): SavedItem {
    val x = readable(v, item.type)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return item.copy(value = v)
    val hist = if (item.history.lastOrNull()?.substringAfter(':') == v) item.history else (item.history + "${nowMs / 1000}:$v").takeLast(20)
    return item.copy(
        value = v, history = hist,
        seenMin = if (item.seenMin.isNaN()) x else minOf(item.seenMin, x),
        seenMax = if (item.seenMax.isNaN()) x else maxOf(item.seenMax, x),
    )
}

/**
 * O valor cabe no que o salvo já teve? Folga larga (o jogo pode ter evoluído com ele fechado), mas descarta o
 * absurdo: um caminho errado costuma cair em 0, em lixo ou em números de outra ordem de grandeza.
 */
internal fun plausible(item: SavedItem, v: String): Boolean {
    // sem histórico (salvo antigo), ou faixa 0–0 (lida com o jogo carregando): não dá para descartar
    if (item.seenMin.isNaN() || (item.seenMin == 0.0 && item.seenMax == 0.0)) return true
    val x = readable(v, item.type)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return false
    val margin = maxOf(item.seenMax - item.seenMin, kotlin.math.abs(item.seenMax) * 0.5, 10.0)
    return x >= item.seenMin - margin && x <= item.seenMax + margin
}

/** "1070 → 1200 → 1331 (há 2 min) · faixa 980–1331" para o salvo aberto e o relatório. */
fun historyText(item: SavedItem, nowMs: Long): String {
    if (item.history.isEmpty()) return ""
    val last = item.history.last().substringBefore(':').toLongOrNull()
    val age = last?.let { (nowMs / 1000 - it).coerceAtLeast(0) }
    val ago = when {
        age == null -> ""
        age < 60 -> " (há ${age}s)"
        age < 3600 -> " (há ${age / 60} min)"
        else -> " (há ${age / 3600} h)"
    }
    val range = if (item.seenMin.isNaN()) "" else " · faixa ${readable(item.seenMin.toString(), "f64")}–${readable(item.seenMax.toString(), "f64")}"
    return item.history.takeLast(6).joinToString(" → ") { it.substringAfter(':') } + ago + range
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
        pid = optInt("pid", 0), chains = chains, sig = sig, sigOff = optInt("sigOff", 0).coerceIn(0, 191), verified = optBoolean("verified", true),
        history = optJSONArray("history")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty().takeLast(20),
        seenMin = optDouble("seenMin", Double.NaN), seenMax = optDouble("seenMax", Double.NaN), trusted = optBoolean("trusted", true),
        il2 = optString("il2", "").takeIf { IL2.matches(it) }.orEmpty(), temp = optBoolean("temp", false),
        il2Field = optString("il2Field", "").takeIf { FIELD.matches(it) }.orEmpty(),
        folder = optString("folder", "").take(40),
        freezeRule = optString("freezeRule", "").takeIf { it == "up" || it == "down" }.orEmpty(),
    )
}

// ---- tabela do Cheat Engine (.CT, XML) ----
private val CT_TYPES = mapOf("Byte" to "i8", "2 Bytes" to "i16", "4 Bytes" to "i32", "8 Bytes" to "i64", "Float" to "f32", "Double" to "f64")

private fun xml(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Nosso ponteiro "lib+base o1 o2" vira Address lib+base com os Offsets do CE (lá a lista vem do último para o primeiro). */
internal fun ctFromSaved(items: List<SavedItem>): String = buildString {
    appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
    appendLine("""<CheatTable CheatEngineTableVersion="45">""")
    appendLine("  <CheatEntries>")
    items.forEachIndexed { i, it ->
        val ce = CT_TYPES.entries.firstOrNull { e -> e.value == plain(it.type) }?.key ?: return@forEachIndexed
        val parts = it.pointer.split(' ').filter { p -> p.isNotEmpty() }
        appendLine("    <CheatEntry>")
        appendLine("      <ID>$i</ID>")
        appendLine("      <Description>\"${xml(it.name.ifEmpty { "0x" + it.addr })}\"</Description>")
        appendLine("      <VariableType>$ce</VariableType>")
        appendLine("      <Address>${xml(parts.firstOrNull() ?: it.addr.uppercase())}</Address>")
        if (parts.size > 1) {
            appendLine("      <Offsets>")
            parts.drop(1).reversed().forEach { o -> appendLine("        <Offset>${o.uppercase()}</Offset>") }
            appendLine("      </Offsets>")
        }
        appendLine("    </CheatEntry>")
    }
    appendLine("  </CheatEntries>")
    appendLine("</CheatTable>")
}

/** Cada CheatEntry com endereço e tipo numérico vira um salvo; grupos (sem endereço) e scripts ficam de fora. */
internal fun savedFromCt(text: String): List<SavedItem> {
    fun tag(block: String, name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim()
    val out = mutableListOf<SavedItem>()
    var i = text.indexOf("<CheatEntry>")
    while (i >= 0) {
        val next = text.indexOf("<CheatEntry>", i + 1)
        val end = text.indexOf("</CheatEntry>", i).let { if (it < 0) text.length else it }
        val block = text.substring(i, if (next in 0 until end) next else end)
        i = next
        val type = CT_TYPES[tag(block, "VariableType") ?: continue] ?: continue
        val address = tag(block, "Address")?.replace("\"", "")?.trim() ?: continue
        val name = tag(block, "Description")?.trim('"')?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&").orEmpty().take(40)
        val offsets = Regex("<Offset>(.*?)</Offset>").findAll(block).map { it.groupValues[1].trim().lowercase() }.toList().reversed()
        val m = Regex("""(.+)\+([0-9a-fA-F]{1,16})""").matchEntire(address)
        if (m != null) {
            val pointer = (listOf(m.groupValues[1] + "+" + m.groupValues[2].lowercase()) + offsets).joinToString(" ")
            if (!POINTER.matches(pointer) || offsets.any { it.toLongOrNull(16) == null }) continue
            out += SavedItem("0", type, "?", "?", name = name, pointer = pointer, pid = 0, verified = false)
        } else if (address.toLongOrNull(16) != null && offsets.isEmpty()) {
            out += SavedItem(address.lowercase(), type, "?", "?", name = name, pid = 0)
        }
    }
    return out
}

// ---- campos de um save em texto ----

/** Um número num save: o nome (como o jogo chama), o valor e onde ele está no texto (para trocar só ele). */
data class SaveField(val key: String, val value: String, val start: Int, val end: Int)

private val PREF_NUM = Regex("""<(?:int|long|float) name="([^"]{1,120})" value="(-?[0-9.]+)"""")
private val PREF_STR = Regex("""<string name="([^"]{1,120})">(-?\d+(?:\.\d+)?)</string>""")
private val JSON_NUM = Regex(""""([^"\\]{1,80})"\s*:\s*(-?\d+(?:\.\d+)?)""")

/** Campos numéricos: preferências do Android e PlayerPrefs do Unity (XML) e JSON. Nome das PlayerPrefs vem com %xx. */
internal fun saveFields(text: String): List<SaveField> {
    val regs = if (text.trimStart().startsWith("<")) listOf(PREF_NUM, PREF_STR) else listOf(JSON_NUM)
    return regs.flatMap { r ->
        r.findAll(text).map { m ->
            val g = m.groups[2]!!
            val key = runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(m.groupValues[1])
            SaveField(key, g.value, g.range.first, g.range.last + 1)
        }
    }.sortedBy { it.start }.take(500)
}

internal fun applySaveEdits(text: String, edits: Map<SaveField, String>): String {
    var out = text
    // de trás para frente: trocar um valor não mexe na posição dos anteriores
    edits.entries.sortedByDescending { it.key.start }.forEach { (f, v) ->
        val n = normalizeNumbers(v).trim()
        require(Regex("""-?\d+(\.\d+)?""").matches(n)) { "Valor inválido para ${f.key}: $v" }
        if (out.substring(f.start, f.end) == f.value) out = out.substring(0, f.start) + n + out.substring(f.end)
    }
    return out
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

const val MEMORY_FILE = "tinker-memoria-ia.json"
private val AI_ERRORS = listOf("Gemini respondeu", "Gemini recusou", "Groq respondeu", "O Groq recusou", "Sem conexão", "O limite grátis", "A cota grátis", "Sem resposta")
private val GEMINI_KEY = Regex("""[A-Za-z0-9._-]{20,200}""")
/** No Auto, os tipos que o motor do jogo usa além dos comuns: Flutter (Dart) e JavaScript guardam inteiro em dobro. */
internal fun engineTypes(name: String?): List<String> = when (name) {
    "Flutter", "Cocos" -> listOf("i32x2", "i64x2")
    else -> emptyList()
}

// regiões que a busca conhece (o V, vídeo, nunca entra)
internal val REGION_CODES = setOf("Jh", "J", "Ch", "Ca", "Cd", "Cb", "A", "S", "As", "O")

/** As regiões com pelo menos 5% do que foi escrito: as que valem a busca começar nelas. */
internal fun busyRegions(rows: List<Triple<String, Long, Long>>): Set<String> {
    val total = rows.sumOf { it.second }
    if (total <= 0) return emptySet()
    return rows.filter { it.first in REGION_CODES && it.second * 20 >= total }.map { it.first }.toSet()
}

private val WATCH_TYPES = setOf("i8", "i16", "i32", "i64", "f32", "f64", "xi32", "xf32", "li", "lf")
private val PKG = Regex("""[A-Za-z0-9._]{1,120}""")  // nome de pacote que vira nome de arquivo

/** Linhas de memória: as de agora mais as novas que ainda não estavam (a data do começo não conta). */
/** Tira as linhas cujo texto (sem a data do começo) está em gone. */
internal fun dropLines(text: String, gone: Set<String>): String {
    if (gone.isEmpty()) return text
    val keep = text.lines().filter { it.isNotBlank() && it.substringAfter(' ').trim() !in gone }
    return if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n")
}

internal fun mergeLines(old: String, new: String): String {
    val have = old.lines().filter { it.isNotBlank() }
    val seen = have.map { it.substringAfter(' ') }.toHashSet()
    val add = new.lines().filter { it.isNotBlank() && seen.add(it.substringAfter(' ')) }
    return (have + add).joinToString("\n", postfix = if (have.isEmpty() && add.isEmpty()) "" else "\n")
}

/** Logcat do jogo: sinais de servidor ou antitrapaça conferindo valores. */
private val SERVER_WORDS = listOf("PBC_", "cheat", "hack", "tamper", "invalid value", "validation", "mismatch", "rejected", "banned", "integrity", "speedhack")

private val PROBLEM_WORDS = listOf("erro", "falha", "aviso", "fechou", "exception")

private val LOGCAT_PREFIX = Regex("""^\S+ \S+\s+\d+\s+\d+ """)

/** Tira o "| " do protocolo e corta linhas repetidas (ex.: o SDK de anúncios do jogo tentando conectar sem parar). */
/** Do buffer de falhas, só as do jogo (pkg); as de outros apps viram uma linha de contagem. Sem jogo: tudo. */
internal fun crashesOf(lines: List<String>, pkg: String?): List<String> {
    if (pkg == null) return lines
    val blocks = mutableListOf<MutableList<String>>(mutableListOf())
    lines.forEach { l -> if ("Fatal signal" in l || "FATAL EXCEPTION" in l) blocks += mutableListOf(l) else blocks.last() += l }
    val (mine, other) = blocks.drop(1).partition { b -> b.any { pkg in it } }
    val out = mine.flatten()
    return when {
        other.isEmpty() -> out.ifEmpty { lines }
        out.isEmpty() -> listOf("Nenhuma falha de $pkg (${other.size} de outros apps omitidas)")
        else -> out + "(${other.size} falhas de outros apps omitidas)"
    }
}

private fun dedupe(lines: List<String>): List<String> {
    val seen = HashSet<String>()
    val out = lines.filterNot(::isLogNoise).map { "| " + it.removePrefix("| ") }.filter { seen.add(it.removePrefix("| ").replace(LOGCAT_PREFIX, "")) }
    return if (out.size < lines.size) out + "(${lines.size - out.size} linhas repetidas omitidas)" else out
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, 96, 96)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

private val WHERE = Regex("""[A-Za-z0-9_.@+-]{1,120}\+[0-9a-f]{1,10}""")

private fun CodePatch.toJson() = JSONObject().put("id", id).put("name", name).put("where", where).put("orig", orig).put("new", new)
    .put("enabled", enabled).put("suspect", suspect).put("note", note).put("ctx", ctx)

private fun JSONObject.toPatch(): CodePatch? = runCatching {
    val w = getString("where")
    val o = getString("orig").lowercase()
    val n = getString("new").lowercase()
    // vai para o daemon: só lib+deslocamento e hex
    require(WHERE.matches(w) && Regex("""([0-9a-f]{8}){1,16}""").matches(o) && o.length == n.length && Regex("""[0-9a-f]+""").matches(n))
    CodePatch(optString("id").ifEmpty { java.util.UUID.randomUUID().toString() }, optString("name", w).take(60), w, o, n,
        optBoolean("enabled", false), optBoolean("suspect", false), optString("note", "").take(300),
        optString("ctx", "").lowercase().takeIf { Regex("""([0-9a-f]{8}){9,24}""").matches(it) }.orEmpty())
}.getOrNull()
