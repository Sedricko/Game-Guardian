package dev.tinker.app

import androidx.compose.ui.graphics.ImageBitmap

const val TEST_GAME = "dev.tinker.testgame"  // Jogo de Teste (módulo testgame)
// onde o agente do PC pode fazer missões: o Jogo de Teste e jogos offline que a pessoa liberou (DYSMANTLE, single-player sem
// servidor). Nunca um jogo online: lista fechada aqui no app, não vem da planilha
val MISSION_GAMES = mapOf(TEST_GAME to "Jogo de Teste", "com.the10tons.dysmantle" to "DYSMANTLE")
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
internal const val OLD_FIXED_KEY_SHA256 = "55c7b7cfa2a2f58d5da503db4b2d1188f423212cc586a53e75172c9720fc2e31"

// sem link embutido: o repositório é público e o token dá acesso às chaves; a pessoa cola o link em Ajustes
internal const val DEFAULT_KEY_SOURCE = ""

internal fun sha256(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

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
    // endereço de verdade na vez do jogo em que foi criado (não vai para o arquivo): nessa vez não precisa recalcular
    val addr: String = "", val pid: Int = 0,
    // função com nome (tabela de símbolos da lib): ao reabrir, acha pelo nome em vez de lib+deslocamento
    val sym: String = "",
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
internal fun returnPatch(ret: Int, value: String, x86: Boolean = false): String? {
    if (x86) return x86Return(ret, value)
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

/** O mesmo no x86_64 (emulador do PC): MOV EAX/RAX com o valor (MOVD/MOVQ para XMM0 se decimal) e RET, NOPs até 4 bytes. */
private fun x86Return(ret: Int, value: String): String? {
    fun le(n: Long, bytes: Int) = (0 until bytes).joinToString("") { "%02x".format((n ushr (8 * it)) and 0xff) }
    fun i32(n: Int) = "b8" + le(n.toLong(), 4)
    fun i64(n: Long) = "48b8" + le(n, 8)
    val v = value.trim().lowercase()
    val body = when (ret) {
        0x01 -> ""
        0x02 -> i32(if (v in setOf("true", "sim", "1", "verdadeiro")) 1 else if (v in setOf("false", "não", "nao", "0", "falso")) 0 else return null)
        in 0x03..0x09 -> i32(v.toLongOrNull()?.toInt() ?: return null)
        0x0a, 0x0b -> i64(v.toLongOrNull() ?: return null)
        0x0c -> i32(java.lang.Float.floatToRawIntBits(v.toFloatOrNull() ?: return null)) + "660f6ec0"
        0x0d -> i64(java.lang.Double.doubleToRawLongBits(v.toDoubleOrNull() ?: return null)) + "66480f6ec0"
        else -> return null
    } + "c3"
    return body + "90".repeat((8 - body.length % 8) % 8 / 2)
}

/** Função de uma lib do jogo, achada pelo nome na tabela de símbolos da própria lib (sem IDA nem Ghidra). */
const val SPEED_NAME = "⏩ velocidade"
// começo do bloco 0 de nomes da Unreal 4.23+ e 5: "None" e "ByteProperty", cada um com 2 bytes de cabeçalho antes
const val UE_NAMES_AOB = "??014e6f6e65??034279746550726f7065727479"

/** Cronômetro do jogo: onde guarda a hora de terminar (em ms ou s desde 1970), e quanto falta agora. */
data class GameTimer(val addr: String, val type: String, val ms: Boolean, val leftSec: Long)

/** Objeto da Unreal achado pelo nome: endereço, classe e nome (como nos dumpers). */
data class UeObj(val addr: String, val cls: String, val name: String)

data class NativeFn(val addr: String, val size: Long, val lib: String, val name: String)

/** Onde fica um endereço de código: começo e fim da função, lib+deslocamento, arquitetura e nome (vazio se a lib não traz). */
data class FnInfo(val start: String, val end: String, val where: String, val arch: String, val name: String)

// Uma instrução na aba Código: endereço, onde fica na lib, os 4 bytes e o que ela faz.
data class CodeRow(val addr: String, val where: String, val word: Int?, val text: String)

data class GameProcess(val pid: Int, val pkg: String, val label: String, val icon: ImageBitmap?)
// base: na busca perto, o endereço de partida em volta do qual este foi achado
/** Um item que o "📋 Itens do jogo" achou sozinho na tela: nome, valor, ícone e o endereço (hit) quando sobrou 1. */
data class GameItem(
    val label: String, val value: String, val icon: String, val type: String, val count: Long, val hit: Hit?, val status: String,
    // função do mapa do jogo (Unity): o método, a classe e o que fixar ("" = não faz nada, "true", "0"); "o que é" já vai no status
    val method: Il2Method? = null, val cls: String = "", val fix: String? = null, val fixLabel: String = "",
)

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
/** Campo bool de "tem / desbloqueado" numa classe do jogo, com os objetos vivos dela. on: o valor que desbloqueia (isLocked = 0). */
data class Unlock(val cls: String, val field: String, val offset: Int, val on: Int, val objs: List<String>)
// extra: o que um campo aponta, por offset (texto de string, "7 itens" de array/lista)
data class Il2Class(val name: String, val start: String, val fields: List<Il2Field>, val extra: Map<Int, String> = emptyMap())

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
data class GameEngine(val name: String, val bits: Int, val lua: Boolean = false, val x86: Boolean = false) {
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
internal fun screenNumber(text: String): Long? {
    // "1+16" (DYSMANTLE: o equipado + o do inventário) virava 116 e nunca batia com 16: vale o da direita
    val t = Regex("""\d+\s*\+\s*(\d+)""").find(text)?.groupValues?.get(1) ?: text
    return Regex("""\d[\d.,]*""").find(t)?.value?.filter(Char::isDigit)?.take(15)?.toLongOrNull()
}

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
