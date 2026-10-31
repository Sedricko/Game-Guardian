package dev.tinker.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject

internal fun String.arg() = normalizeNumbers(this).ifBlank { "x" }

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

internal fun SavedItem.toJson() = JSONObject().put("addr", addr).put("type", type).put("region", region).put("value", value).put("name", name).put("frozen", frozen)
    .put("pointer", pointer).put("id", id).put("anchor", anchor).put("offset", offset).put("pid", pid)
    .put("chains", JSONArray(chains)).put("sig", sig).put("sigOff", sigOff).put("verified", verified)
    .put("trusted", trusted).put("il2", il2).put("il2Field", il2Field).put("temp", temp).put("folder", folder).put("freezeRule", freezeRule).put("history", JSONArray(history)).apply { if (!seenMin.isNaN()) put("seenMin", seenMin).put("seenMax", seenMax) }  // JSON não aceita NaN

/** Namespaces de bibliotecas: vão para o fim da lista de classes (o que interessa é o código do próprio jogo). */
internal val LIBS = listOf(
    "System", "Unity", "Mono", "TMPro", "Google", "Firebase", "Newtonsoft", "Microsoft", "AppsFlyer", "Facebook", "Spine", "DG.",
    "Cinemachine", "Sirenix", "Coffee", "I2.", "Zenject", "Internal", "JetBrains", "Bolt", "Photon", "Steamworks", "GooglePlayGames",
    "AOT", "Il2Cpp", "Applovin", "AppLovin", "IronSource", "com.", "Com.", "Xsolla", "Lofelt", "Sentry", "PlayFab", "Amazon", "Mediation",
)

internal val CLASS_NAME = Regex("""[A-Za-z0-9_.`<>]{1,120}""")  // vai para o daemon

internal fun Recipe.toJson() = JSONObject().put("id", id).put("name", name).put("auto", auto).put("steps", JSONArray().apply {
    steps.forEach {
        put(JSONObject().put("cls", it.cls).put("off", it.off).put("type", it.type).put("value", it.value).put("freeze", it.freeze).put("label", it.label)
            .apply { if (it.field.isNotEmpty()) put("field", it.field); if (it.ptr.isNotEmpty()) put("ptr", it.ptr); if (it.sig.isNotEmpty()) put("sig", it.sig).put("sigOff", it.sigOff) })
    }
})

/** Como o passo acha o lugar: para o registro e a tela. */
val Step.how get() = when { cls.isNotEmpty() -> "classe"; ptr.isNotEmpty() -> "ponteiro"; else -> "assinatura" }

/** Receita de arquivo ou colada: tudo validado, porque classe, tipo e valor vão para o daemon. */
internal fun JSONObject.toRecipe(): Recipe {
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

internal val FIELD = Regex("""[A-Za-z0-9_.<>`$]{1,120}""")  // nome de campo IL2CPP (ex.: <Gold>k__BackingField)
internal val IL2 = Regex("""[A-Za-z0-9_.<>`]{1,120}\+[0-9a-f]{1,6}""")  // vai para o daemon: sem espaço nem quebra de linha

internal val AOB = Regex("""([0-9a-f]{2}|\?\?){1,192}""")

/** Caminho de ponteiro aceito: vai direto para o daemon, então nada de espaço extra, quebra de linha ou outro comando. */
private val POINTER = Regex("""[^\s+]{1,100}\+[0-9a-f]{1,16}( [0-9a-f]{1,8}){0,7}""")

val COMPARE_NAMES = mapOf("inc" to "aumentou", "dec" to "diminuiu", "same" to "não mudou", "changed" to "mudou", "incby" to "aumentou", "decby" to "diminuiu",
    "lin" to "disfarçado (×k+c)", "xk" to "cifrado com chave fixa")

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
        // endereço (ponteiro) muda a cada vez que o jogo abre: até 47 bits (x86_64 e arm64 com 48 bits de endereço)
        if ((q and 0x00ffffffffffffffL) in 0x10000000L until 0x800000000000L) for (k in 0 until 8) wild[w * 8 + k] = true
    }
    return raw.indices.joinToString("") { if (wild[it]) "??" else "%02x".format(raw[it]) }
}

private val UNLOCK_FIELD = Regex("""(?i)unlock|locked|owned|purchas|bought|acquired|obtained|isbuy|isget""")
/** Nome de campo de "tem o item / desbloqueado". */
internal fun unlockField(name: String) = UNLOCK_FIELD.containsMatchIn(name)
/** isLocked, locked: aqui desbloquear é 0 (isUnlocked, owned: 1). */
internal fun lockedField(name: String) = Regex("""(?i)locked""").containsMatchIn(name) && !Regex("""(?i)unlocked""").containsMatchIn(name)

/** Faixa da busca para um número que anda sozinho: do que a tela mostrou até um pouco à frente (ex.: 19 → 19~24). */
internal fun ahead(v: Long) = "$v~${v + maxOf(5L, kotlin.math.abs(v) / 20)}"

/** "...A4" (ou "…a4") no refino = endereço termina em A4; null se não for isso. */
internal fun addrEnd(text: String) = Regex("""(?:\.\.\.|…)\s*([0-9a-fA-F]{1,8})""").matchEntire(text.trim())?.groupValues?.get(1)?.lowercase()

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
        il2.fields.forEachIndexed { n, f ->
            val i = (base + f.offset - start).toInt()
            val t = il2Tinker(f.code)
            if (i < 0 || i + 8 > raw.size) return@forEachIndexed
            // struct pequeno até o próximo campo (Vector2/3, Color, Quaternion): os decimais lado a lado, se parecem decimais
            val size = (il2.fields.getOrNull(n + 1)?.offset ?: (f.offset + 16)) - f.offset
            val floats = if (f.code == 0x11 && size in 8..16 && i + size <= raw.size)
                List(size / 4) { java.lang.Float.intBitsToFloat(u32(i + 4 * it).toInt()) }.takeIf { v -> v.all { it.isFinite() && kotlin.math.abs(it) < 1e7f } }
                    // Vector3 + 4 bytes de alinhamento até o próximo ponteiro: o 0 do fim não é campo (Quaternion termina em 1)
                    ?.let { v -> if (v.size == 4 && v[3] == 0f && u32(i + 12) == 0L) v.take(3) else v }
            else null
            val v = il2.extra[f.offset] ?: floats?.joinToString(", ", "(", ")") { readable(it.toString(), "f32") ?: "?" } ?: when (t) {
                "f32" -> java.lang.Float.intBitsToFloat(u32(i).toInt()).toString()
                "f64" -> java.lang.Double.longBitsToDouble(u64(i)).toString()
                "i64" -> u64(i).toString()
                "i16" -> u32(i).toInt().toShort().toString()
                "i8" -> raw[i].toString()
                "i32" -> u32(i).toInt().toString()
                else -> "0x" + (u64(i) and 0x00ffffffffffffffL).toString(16)
            }
            val shown = if (il2.extra[f.offset] != null || floats != null) v else readable(v, t ?: "i64") ?: "NaN"
            rows += FichaRow(at(i), f.offset.toLong(), t ?: "", "${f.name} · ${il2TypeName(f.code)}", shown)
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

internal fun JSONObject.toSaved(): SavedItem {
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

internal fun xml(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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

/**
 * Onde está o timeScale (1.0) a partir do 0.99999994 do relógio do Unity (`w` = 48 Dwords a partir dele): 16 a 32 bytes
 * depois vêm dois tempos que andam juntos, e o 1.0 fica até 140 bytes depois do primeiro. Os tempos podem ser Double ou
 * Qword inteiro: o SpeedHackFinder do GG lê Qword, e era o que achava no Orecraft (o Tinker só lia Double e achava 0).
 */
internal fun speedOffsets(w: List<Int>): List<Int> {
    fun q(off: Int) = (w[off / 4].toLong() and 0xffffffffL) or (w[off / 4 + 1].toLong() shl 32)
    fun ok(t1: Double, t2: Double) = t1 > 100 && t1 < 1e6 && t2 > t1 && t2 < t1 + 250
    for ((o1, o2) in listOf(20 to 28, 16 to 24, 24 to 32)) {
        val d1 = java.lang.Double.longBitsToDouble(q(o1))
        val d2 = java.lang.Double.longBitsToDouble(q(o2))
        if (!ok(d1, d2) && !ok(q(o1).toDouble(), q(o2).toDouble())) continue
        return (1..35).map { o1 + 4 * it }.filter { it / 4 < w.size && w[it / 4] == 0x3f800000 }
    }
    return emptyList()
}

// ---- o juiz: qual forma de mudar um número da tela é a melhor ----

/** Nomes de campo com cara de moeda/recurso do jogo: vão primeiro na fila de candidatos. */
internal val COIN = Regex("gold|coin|gem|crystal|diamond|currenc|resource|money|soft|hard|amount|balance|ouro|joia", RegexOption.IGNORE_CASE)

/** Uma forma testada pelo juiz: mudou a tela, o jogo não voltou sozinho em 5 s, continuou depois de reabrir o jogo. */
data class Route(val name: String, val worked: Boolean, val held: Boolean, val survives: Boolean)

/** Nota de 0 a 5: mudar a tela vale 2, segurar 1, durar ao reabrir 2 (o que não muda a tela não pontua). */
internal fun routeScore(r: Route) = if (!r.worked) 0 else 2 + (if (r.held) 1 else 0) + (if (r.survives) 2 else 0)

/** A de maior nota; no empate, a testada antes (a mais simples). */
internal fun bestRoute(rs: List<Route>) = rs.filter { it.worked }.maxByOrNull(::routeScore)

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
internal val AI_ERRORS = listOf("Gemini respondeu", "Gemini recusou", "Groq respondeu", "O Groq recusou", "Sem conexão", "O limite grátis", "A cota grátis", "Sem resposta")
internal val GEMINI_KEY = Regex("""[A-Za-z0-9._-]{20,200}""")
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

internal val WATCH_TYPES = setOf("i8", "i16", "i32", "i64", "f32", "f64", "xi32", "xf32", "li", "lf")
internal val PKG = Regex("""[A-Za-z0-9._]{1,120}""")  // nome de pacote que vira nome de arquivo

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
internal val SERVER_WORDS = listOf("PBC_", "cheat", "hack", "tamper", "invalid value", "validation", "mismatch", "rejected", "banned", "integrity", "speedhack")

internal val PROBLEM_WORDS = listOf("erro", "falha", "aviso", "fechou", "exception")

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

internal fun dedupe(lines: List<String>): List<String> {
    val seen = HashSet<String>()
    val out = lines.filterNot(::isLogNoise).map { "| " + it.removePrefix("| ") }.filter { seen.add(it.removePrefix("| ").replace(LOGCAT_PREFIX, "")) }
    return if (out.size < lines.size) out + "(${lines.size - out.size} linhas repetidas omitidas)" else out
}

internal fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, 96, 96)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

private val WHERE = Regex("""[A-Za-z0-9_.@+-]{1,120}\+[0-9a-f]{1,10}""")

internal fun CodePatch.toJson() = JSONObject().put("id", id).put("name", name).put("where", where).put("orig", orig).put("new", new)
    .put("enabled", enabled).put("suspect", suspect).put("note", note).put("ctx", ctx).put("sym", sym)

internal fun JSONObject.toPatch(): CodePatch? = runCatching {
    val w = getString("where")
    val o = getString("orig").lowercase()
    val n = getString("new").lowercase()
    // vai para o daemon: só lib+deslocamento e hex
    require(WHERE.matches(w) && Regex("""([0-9a-f]{8}){1,16}""").matches(o) && o.length == n.length && Regex("""[0-9a-f]+""").matches(n))
    CodePatch(optString("id").ifEmpty { java.util.UUID.randomUUID().toString() }, optString("name", w).take(60), w, o, n,
        optBoolean("enabled", false), optBoolean("suspect", false), optString("note", "").take(300),
        optString("ctx", "").lowercase().takeIf { Regex("""([0-9a-f]{8}){9,24}""").matches(it) }.orEmpty(),
        sym = optString("sym", "").takeIf { Regex("""[A-Za-z0-9_.$@:]{1,160}""").matches(it) }.orEmpty())
}.getOrNull()

/**
 * Erro técnico (daemon, su, Android) em frase para quem joga: o que houve e o que fazer. A mensagem original vai para o
 * Registro, para investigar.
 */
internal fun friendly(msg: String?): String {
    val m = msg?.trim().orEmpty()
    return when {
        m.isEmpty() -> "Algo deu errado. Tente de novo; se repetir, veja o motivo na aba Registro."
        m.startsWith("valor inválido") || m.startsWith("número inválido") ->
            "Não entendi o valor. Use um número (20000 ou 20.000), uma faixa (100~200) ou vários com ; (20000;50000)."
        m.startsWith("Sem root") -> "O Tinker precisa de root. Abra o Magisk, permita o Tinker e tente de novo."
        m == "O daemon fechou." || m == "daemon parado" || "Broken pipe" in m || "Stream closed" in m ->
            "A conexão com o root caiu. Tente de novo; se continuar, feche e abra o Tinker."
        (m.startsWith("processo ") && m.endsWith("não existe")) || m == "o jogo fechou" ->
            "O jogo fechou. Abra o jogo e escolha ele de novo no Tinker."
        m == "nenhum jogo escolhido" -> "Escolha o jogo primeiro: toque no nome no topo do painel."
        m == "sem memória" -> "Faltou memória no aparelho para isso. Refine mais ou busque em menos regiões (Ajustes)."
        m.startsWith("sem espaço") -> "Falta espaço no aparelho (${m.substringAfter(": ")}). Libere espaço e tente de novo."
        m.startsWith("refine mais antes") -> "São resultados demais para mudar de uma vez. Refine mais um pouco."
        "timed out" in m.lowercase() || "timeout" in m.lowercase() -> "Demorou demais para responder. Tente de novo com o jogo aberto."
        else -> m.replaceFirstChar { it.uppercase() }.let { if (it.last() in ".!?)") it else "$it." }
    }
}
