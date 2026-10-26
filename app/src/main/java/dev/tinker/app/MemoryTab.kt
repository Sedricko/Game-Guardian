package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Visualizador de memória: valores em volta de um endereço, no tipo escolhido, ao vivo. */
@Composable
internal fun MemoryView(engine: Engine, run: (suspend () -> String) -> Unit) {
    val s = Session
    val (center, type) = s.view ?: ("0" to "i32")
    var shift by remember(center) { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(listOf<Hit>()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var goTo by remember(center) { mutableStateOf(if (center == "0") "" else center) }
    var error by remember { mutableStateOf<String?>(null) }
    var near by remember { mutableStateOf(false) }
    // o que mudou desde a leitura anterior fica destacado por 3 s: ajuda a ver o que reage ao que você faz no jogo
    val changed = remember(center, type) { mutableStateMapOf<String, Long>() }
    val last = remember(center, type) { HashMap<String, String>() }
    LaunchedEffect(center, type, shift, s.fichaMode, s.codeMode) {
        if (center == "0" || s.fichaMode || s.codeMode) return@LaunchedEffect
        while (true) {
            runCatching {
                val new = engine.nearby(center, type, shift)
                val now = System.currentTimeMillis()
                new.forEach { r ->
                    val before = last.put(r.addr, r.value)
                    if (before != null && before != r.value) changed[r.addr] = now
                }
                rows = new
                error = null
            }.onFailure { error = friendly(it.message) }
            delay(1000)
        }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = !s.fichaMode && !s.codeMode, onClick = { s.fichaMode = false; s.codeMode = false }, label = { Text("Endereços") })
            FilterChip(selected = s.fichaMode, onClick = { s.fichaMode = true; s.codeMode = false }, label = { Text("Ficha") })
            FilterChip(selected = s.codeMode, onClick = { s.fichaMode = false; s.codeMode = true }, label = { Text("Código") })
        }
        if (s.fichaMode) {
            FichaView(engine, center, run)
            return@Column
        }
        if (s.codeMode) {
            CodeView(engine, center, run)
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        Panel {
        Text("Ir para um endereço", style = MaterialTheme.typography.titleSmall)
        if (center == "0") {
            Text(
                if (s.process == null) "Escolha o jogo primeiro." else "Digite um endereço ou use \"Ver próximos\" num resultado da Busca ou dos Salvos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // endereço, Ir e ▲▼ numa linha; Buscar perto recolhido: a lista fica com o resto da tela
        // teclado do Tinker (como no valor da Busca): o do telefone cobria o jogo e o próprio campo
        val ownPad = s.ownKeypad
        var hexPad by remember { mutableStateOf(false) }
        val addrTaps = remember { MutableInteractionSource() }
        OnTap(addrTaps) { if (s.ownKeypad) hexPad = true }
        fun go() {
            val a = parseAddress(goTo)
            if (a != null) { s.view = a to type; hexPad = false } else error = "Endereço inválido: $goTo"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) { NoPhoneKeyboard(ownPad) { OutlinedTextField(
                value = goTo, onValueChange = { goTo = it.removePrefix("0x").trim() }, label = { Text("Endereço (7a0010+8)") },
                singleLine = true, interactionSource = addrTaps, modifier = Modifier.fillMaxWidth(),
            ) } }
            Button(shape = MaterialTheme.shapes.medium, onClick = { go() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Ir") }
            if (center != "0") {
                HeaderButton("▲", "Anteriores") { shift-- }
                HeaderButton("▼", "Seguintes") { shift++ }
            }
        }
        if (ownPad && hexPad) NumPad(onKey = { goTo = applyKey(goTo, it) }, onDone = { go() }, compact = landscape(), hex = true)
        TypeChips(type) { t -> s.view = center to t }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (center != "0") {
            // bytes crus com o texto ao lado, como o Memory View do Cheat Engine
            var hex by remember { mutableStateOf<String?>(null) }
            val hexScope = rememberCoroutineScope()
            TextButton(onClick = {
                if (hex != null) hex = null
                else hexScope.launch { hex = runCatching { engine.hexView(center, 16) }.getOrElse { "Não leu. ${friendly(it.message)}" } }
            }) { Text((if (hex != null) "▾ " else "▸ ") + "Ver bytes (hexadecimal)") }
            hex?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState())) }
            // editar direto nos bytes, como o hex editor do GG e do Cheat Engine
            if (hex != null) {
                var at by remember(center) { mutableStateOf(center) }
                var bytes by remember { mutableStateOf("") }
                ValueField(at, "Escrever a partir de (endereço)", { at = it.filter { c -> c.isLetterOrDigit() }.lowercase() }, hex = true)
                ValueField(bytes, "Bytes (ex.: 1F2003D5)", { bytes = it }, hex = true)
                Button(shape = MaterialTheme.shapes.medium, enabled = bytes.isNotBlank() && at.toLongOrNull(16) != null, onClick = {
                    run { engine.writeBytes(at, bytes).also { hex = runCatching { engine.hexView(center, 16) }.getOrElse { e -> "Não leu: ${e.message}" } } }
                }) { Text("Escrever bytes") }
            }
            TextButton(onClick = { near = !near }) { Text((if (near) "▾ " else "▸ ") + "Buscar perto deste endereço") }
            if (near) TopArea { NearSearch(engine, listOf(center), type, chooseType = false, onError = { error = it }) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(rows, key = { it.addr }) { row ->
                val ctx = LocalContext.current
                // Qword que parece endereço: dá para seguir, como o "Follow pointer" do Cheat Engine
                val ptr = if (type == "i64") readable(row.value, type)?.toLongOrNull()?.takeIf { it in 0x10000L..0x7fffffffffffL } else null
                val here = row.addr.toLong(16) == center.toLong(16) / TYPE_SIZES.getValue(type) * TYPE_SIZES.getValue(type)
                val hot = (changed[row.addr] ?: 0L) > System.currentTimeMillis() - 3000
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(when {
                            here -> MaterialTheme.colorScheme.primaryContainer
                            hot -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        })
                        .sizeIn(minHeight = 48.dp).clickable { editing = if (editing == row.addr) null else row.addr }.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.addr, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(shown(row.value, type), fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (row.asDword.isNotEmpty()) {
                        Text(
                            "Não é ${TYPE_NAMES[type]}. Como Dword: ${row.asDword}", style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (editing == row.addr) {
                        InlineEditor(readable(row.value, type) ?: "", "Novo valor (${TYPE_NAMES[type]})", onSave = { v -> editing = null; run { engine.write(row.addr, type, v) } }, onCancel = { editing = null }) {
                            TextButton(onClick = { engine.addSaved(listOf(row.copy(region = "?"))); editing = null }) { Text("Salvar na lista") }
                            TextButton(onClick = { s.status = copyAddress(ctx, row.addr) }) { Text("Copiar endereço") }
                            TextButton(onClick = { s.view = row.addr to type; s.fichaMode = true; s.codeMode = false; editing = null }) { Text("Ver ficha") }
                            if (ptr != null) {
                                TextButton(onClick = { s.view = ptr.toString(16) to type; editing = null }) { Text("Ir para 0x${ptr.toString(16)}") }
                                TextButton(onClick = { s.status = copyAddress(ctx, ptr.toString(16)) }) { Text("Copiar o valor") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Código (região Xa): as instruções do jogo em volta de um endereço, já traduzidas (ARM64). Tocar numa instrução deixa trocar
 * por NOP ou por outros bytes: vira um patch em Salvos → Patches de código (com os bytes de antes, para desfazer).
 */
@Composable
private fun CodeView(engine: Engine, center: String, run: (suspend () -> String) -> Unit) {
    val s = Session
    val ctx = LocalContext.current
    var rows by remember(center) { mutableStateOf<List<CodeRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var bytes by remember { mutableStateOf("") }
    var goTo by remember(center) { mutableStateOf(if (center == "0") "" else center) }
    var pad by remember { mutableStateOf(false) }
    val taps = remember { MutableInteractionSource() }
    OnTap(taps) { if (s.ownKeypad) pad = true }
    LaunchedEffect(center) {
        if (center == "0") return@LaunchedEffect
        runCatching { engine.codeAt(center) }.onSuccess { rows = it; error = null }.onFailure { error = friendly(it.message) }
    }
    Text("Instruções do jogo (ARM64). Ache o endereço com \"O que escreve aqui?\" num salvo, procure uma função pelo nome, ou digite um.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    NativeFunctions(engine, run)
    var fn by remember(center) { mutableStateOf<FnInfo?>(null) }
    LaunchedEffect(center) { if (center != "0") fn = runCatching { engine.functionAt(center) }.getOrNull() }
    fn?.let { f ->
        val off = center.toLong(16) - f.start.toLong(16)
        Text("Fica na função ${f.name.ifEmpty { "sem nome" }}" + (if (off > 0) " (+0x${off.toString(16)})" else "") + " · ${f.where} · ${f.arch}",
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    fun go() {
        val a = parseAddress(goTo)
        if (a != null) { s.view = a to "i32"; pad = false } else error = "Endereço inválido: $goTo"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) { NoPhoneKeyboard(s.ownKeypad) { OutlinedTextField(goTo, { goTo = it.removePrefix("0x").trim() }, label = { Text("Endereço do código") }, singleLine = true,
            interactionSource = taps, modifier = Modifier.fillMaxWidth()) } }
        Button(shape = MaterialTheme.shapes.medium, onClick = { go() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Ir") }
    }
    if (s.ownKeypad && pad) NumPad(onKey = { goTo = applyKey(goTo, it) }, onDone = { go() }, compact = landscape(), hex = true)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows, key = { it.addr }) { r ->
            val here = r.addr == center
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (here) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                    .sizeIn(minHeight = 48.dp).clickable { open = if (open == r.addr) null else r.addr; bytes = "" }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.word?.let { "%08x".format(Integer.reverseBytes(it)) } ?: "????????", Modifier.width(84.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.text, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (open == r.addr) {
                    Text("0x${r.addr}" + if (r.where.isNotEmpty()) " · ${r.where}" else "", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // NOP: a instrução deixa de fazer qualquer coisa (o jeito mais comum de "não descontar")
                        Button(shape = MaterialTheme.shapes.medium, onClick = { open = null; run { engine.createPatch(r.addr, "1f2003d5", "NOP em ${r.where.ifEmpty { r.addr }}", "era ${r.text}") } },
                            enabled = r.word != null && r.word != ARM64_NOP && !s.busy) { Text("Desligar esta instrução") }
                        TextButton(onClick = { s.status = copyAddress(ctx, r.addr) }) { Text("Copiar endereço") }
                        if (r.where.isNotEmpty()) TextButton(onClick = { s.status = copyAddress(ctx, r.where) }) { Text("Copiar posição na lib") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ValueField(bytes, "Bytes novos (8 dígitos por instrução)", { bytes = it.filter { c -> c.isLetterOrDigit() }.lowercase().take(32) },
                            Modifier.weight(1f), hex = true)
                        TextButton(onClick = { open = null; run { engine.createPatch(r.addr, bytes, "Patch em ${r.where.ifEmpty { r.addr }}", "era ${r.text}") } },
                            enabled = bytes.length >= 8 && bytes.length % 8 == 0 && !s.busy) { Text("Aplicar") }
                    }
                    if (bytes.length == 8) bytes.toLongOrNull(16)?.let { Text("= " + decodeArm64(Integer.reverseBytes(it.toInt()), r.addr.toLong(16)), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
        }
    }
}

/**
 * Funções das libs do jogo pelo nome (a tabela de símbolos da própria lib, sem IDA): ver o código, quem ela chama,
 * contar chamadas e fixar o retorno. Serve para Unreal, Cocos e motor próprio, que não têm os nomes do IL2CPP.
 */
@Composable
private fun NativeFunctions(engine: Engine, run: (suspend () -> String) -> Unit) {
    val s = Session
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Pair<Int, List<NativeFn>>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var calls by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    fun find() = scope.launch {
        runCatching { engine.nativeFunctions(filter) }.onSuccess { found = it; open = null }.onFailure { s.status = friendly(it.message) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TinkerTextField(filter, { filter = it }, "Função pelo nome (ex.: gold, AddCoins)", Modifier.weight(1f))
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { find() }, enabled = !s.busy, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Procurar") }
    }
    val (total, list) = found ?: return
    var running by remember(list) { mutableStateOf<List<Pair<NativeFn, Int>>?>(null) }
    if (list.isNotEmpty()) OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = !s.busy, onClick = {
        s.status = "Jogue normalmente: contando ${minOf(list.size, 12)} funções, 3 s cada…"
        scope.launch { running = runCatching { engine.whichRun(list) }.getOrDefault(emptyList()); s.status = "Rodaram ${running?.size ?: 0} das ${minOf(list.size, 12)}." }
    }) { Text("Quais rodam quando eu jogo? (até 12)") }
    running?.let { r ->
        if (r.isEmpty()) Text("Nenhuma rodou enquanto contava.", style = MaterialTheme.typography.bodySmall)
        r.forEach { (f, n) -> Text("▶ ${f.name}: ${n}x", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
    Text(if (total == 0) "Nenhuma função com esse nome nas libs do jogo (a lib pode vir sem nomes: use \"O que escreve aqui?\")."
        else "$total funções" + if (total > list.size) " (mostrando ${list.size}: escreva mais do nome)" else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.forEach { f ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .sizeIn(minHeight = 48.dp).clickable { open = if (open == f.addr) null else f.addr; calls = null }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(f.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (open == f.addr) {
                    Text("0x${f.addr} · ${f.lib}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { s.view = f.addr to "i32" }) { Text("Ver código") }
                        TextButton(onClick = { scope.launch { calls = runCatching { engine.callsOf(f.addr) }.getOrDefault(emptyList()) } }) { Text("Quem ela chama") }
                        TextButton(onClick = { engine.traceLater(f.addr, f.name) }) { Text("Contar chamadas (10 s)") }
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { open = null; run { engine.patchNative(f, "") } }) { Text("Não fazer nada") }
                    }
                    InlineEditor("", "Sempre devolver (número inteiro)", onSave = { v -> open = null; run { engine.patchNative(f, v) } }, onCancel = { open = null })
                    Text("Vira um patch desligado em Salvos → Patches de código: ligue lá para valer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    calls?.let { c ->
                        if (c.isEmpty()) Text("Não chama outra função desta lib.", style = MaterialTheme.typography.bodySmall)
                        c.forEach { (a, n) -> Text("→ ${n.ifEmpty { "sem nome" }} (0x$a)", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().clickable { s.view = a to "i32" }.padding(vertical = 4.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Ficha do objeto, como o "Dissect data structure" do Cheat Engine: os campos em volta do valor com tipo e valor ao vivo.
 * Em jogo Unity IL2CPP, com os nomes que o próprio jogo usa (Player.hp); nos outros, com o tipo adivinhado.
 */
@Composable
private fun FichaView(engine: Engine, center: String, run: (suspend () -> String) -> Unit) {
    val s = Session
    val e = s.engine
    if (e != null && !e.il2cpp) {
        // sem IL2CPP não há nomes de classe: a ficha mostra só os bytes em volta
        Text("${e.name}: este jogo não tem classes IL2CPP com nome. A ficha mostra os campos em volta do salvo; receitas vão pelo ponteiro ou pela assinatura.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (e.name == "Unreal") UnrealOpener(engine)
        if (center != "0") FichaBody(engine, center, run)
        return
    }
    // ClassOpener num lugar só: assim abrir um objeto não apaga o filtro, a lista, os outros objetos e a conversa com a IA
    val none = center == "0"
    var switching by remember { mutableStateOf(false) }
    if (!none) TextButton(onClick = { switching = !switching }) { Text((if (switching) "▾ " else "▸ ") + "Trocar objeto / classe") }
    // sem objeto aberto a tela inteira rola; com objeto, a parte de cima fica limitada e a ficha fica com o resto
    // recolhido = altura 0, mas continua na tela (sem perder o que tinha dentro)
    val max = when {
        none -> Dp.Unspecified
        switching -> LocalPanelSize.current.height * 0.45f
        else -> 0.dp
    }
    Column(Modifier.fillMaxWidth().heightIn(max = max).clipToBounds().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ClassOpener(engine)
        if (none) Text("Digite o nome de uma classe ou de um campo do jogo (ex.: PlayerData, gold) ou abra a ficha de um salvo (Salvos → Ver ficha).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!none) FichaBody(engine, center, run)
}

/** Copiar os números desta ficha para outro objeto da mesma classe (clonar a arma boa na ruim). */
@Composable
private fun CopyFicha(engine: Engine, c: Il2Class, run: (suspend () -> String) -> Unit) {
    var objs by remember(c.start) { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { scope.launch { objs = if (objs == null) runCatching { engine.classObjects(c) }.getOrDefault(emptyList()) else null } }) {
        Text((if (objs != null) "▾ " else "▸ ") + "Copiar os números para outro ${c.name.substringAfterLast('.')}")
    }
    objs?.let { list ->
        if (list.isEmpty()) Text("Não achei outro objeto desta classe vivo agora.", style = MaterialTheme.typography.bodySmall)
        else Text("Toque no objeto que vai receber os números (ponteiros, textos e listas não mudam):", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { a -> OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { objs = null; run { engine.copyFicha(c, a) } }) { Text("0x$a") } }
        }
    }
}

/** Unreal: acha objetos pelo nome ou pela classe (Jogador, BP_Player_C) nas tabelas do próprio motor e abre a Ficha. */
@Composable
private fun UnrealOpener(engine: Engine) {
    val s = Session
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Pair<String, List<UeObj>>?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TinkerTextField(filter, { filter = it }, "Objeto da Unreal (ex.: Player, Character)", Modifier.weight(1f))
        OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = !s.busy && filter.isNotBlank(), contentPadding = PaddingValues(horizontal = 12.dp),
            onClick = { scope.launch { found = runCatching { engine.unrealObjects(filter) }.getOrElse { friendly(it.message) to emptyList() } } }) { Text("Procurar") }
    }
    val (msg, list) = found ?: return
    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.forEach { o ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .sizeIn(minHeight = 48.dp).clickable { s.view = o.addr to "i32" }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(o.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${o.cls} · 0x${o.addr}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Abrir", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** "Abrir objeto pelo nome": digita a classe (PlayerData) e a ficha abre com todos os valores, sem buscar número nenhum. */
@Composable
private fun ClassOpener(engine: Engine) {
    val s = Session
    val scope = rememberCoroutineScope()
    var cls by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var objs by remember { mutableStateOf(listOf<String>()) }
    var all by remember(s.process) { mutableStateOf<List<GameClass>?>(null) }
    var listing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun copy(name: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Classe", name))
        info = "Copiado: $name"
    }
    fun open(name: String) {
        if (loading || name.isBlank()) return
        cls = name
        loading = true
        info = "Procurando objetos de $name… (lê a memória toda)"
        scope.launch {
            runCatching { engine.findObjects(name) }.onSuccess { found ->
                objs = if (found.size > 1) found else emptyList()
                info = when {
                    found.isEmpty() -> "Nenhum objeto $name agora."
                    found.size == 1 -> null
                    else -> "${found.size} objetos $name: escolha um."
                }
                // ficha pelo primeiro campo (+0x10, ou +0x8 em 32 bits): o começo do objeto não está dentro de nenhum campo
                found.singleOrNull()?.let { s.view = (it.toLong(16) + (s.engine?.header ?: 0x10L)).toString(16) to "i32" }
            }.onFailure { info = friendly(it.message) }
            loading = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TinkerTextField(cls, { cls = it }, "Classe ou campo", Modifier.weight(1f), placeholder = "PlayerData, gold")
        Button(shape = MaterialTheme.shapes.medium, onClick = { open(cls) }, enabled = !loading && s.process != null && cls.isNotBlank(), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Abrir") }
        if (cls.isNotBlank()) TextButton(onClick = { copy(cls) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("⧉", fontSize = 18.sp) }
    }
    // atalhos: classes abertas antes e as dos salvos que têm nome IL2CPP
    val shortcuts = (s.classes + s.saved.mapNotNull { it.il2.substringBeforeLast('+', "").ifEmpty { null } }).distinct().take(10)
    if (shortcuts.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shortcuts.forEach { c -> AssistChip(onClick = { open(c) }, label = { Text(c.substringAfterLast('.'), maxLines = 1) }) }
        }
    }
    // sem digitar: lista todas as classes do jogo; o campo acima vira filtro
    TextButton(onClick = {
        listing = !listing
        if (listing && all == null && !loading) {
            loading = true
            info = "Listando as classes do jogo…"
            scope.launch {
                runCatching { engine.listClasses() }.onSuccess { all = it; info = "${it.size} classes com campos (as do jogo primeiro). Digite acima para filtrar." }
                    .onFailure { info = friendly(it.message); listing = false }
                loading = false
            }
        }
    }, enabled = s.process != null) { Text((if (listing) "▾ " else "▸ ") + "Ver todas as classes do jogo") }
    // dump.cs: todas as classes do jogo com campos e métodos, num arquivo para ler com calma ou mandar para a IA
    TextButton(onClick = {
        loading = true
        info = "Montando o dump (classes, campos e métodos)… pode levar um minuto"
        scope.launch {
            runCatching { engine.dumpIl2cpp() }.onSuccess { (msg, file) -> info = msg; shareFile(context, file) }.onFailure { info = friendly(it.message) }
            loading = false
        }
    }, enabled = s.process != null && !loading) { Text("📄 Exportar a lista de classes (.cs)") }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val names = remember(all) { all?.map { it.name }?.toSet() ?: emptySet() }
    AiBox(
        engine, "Perguntar à IA onde está o valor",
        listOf("Ache o ouro e as gemas (com os valores de agora)", "Onde fica a vida do jogador?", "Onde ficam os itens do inventário?"),
        context = { engine.classesContext(all ?: engine.listClasses().also { all = it }) }, names = names, onOpen = { open(it) }, key = "classes",
    )
    // classes que a IA recomendou: no topo, um toque abre
    if (s.aiClasses.isNotEmpty()) {
        Text("✨ Sugeridas pela IA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        s.aiClasses.toList().forEach { gc -> ClassRow(gc, emptyList(), onOpen = { listing = false; open(gc.name) }, onCopy = { copy(gc.name) }) }
        TextButton(onClick = { s.aiClasses.clear() }) { Text("Limpar sugestões") }
    }
    // filtro: nome da classe ou de um campo (gold acha PlayerData pelo campo Gold)
    val words = searchWords(cls)
    val shownClasses = all?.takeIf { listing }?.mapNotNull { gc -> classMatch(gc, words)?.let { gc to it } }
        ?.sortedBy { (gc, f) -> if (words.isNotEmpty() && f.isEmpty() && gc.name.substringAfterLast('.').contains(cls, ignoreCase = true)) 0 else 1 }
    if (shownClasses != null) {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shownClasses.take(300).forEach { (gc, fields) -> ClassRow(gc, fields, onOpen = { listing = false; open(gc.name) }, onCopy = { copy(gc.name) }) }
            if (shownClasses.size > 300) Text("…e mais ${shownClasses.size - 300}: digite mais do nome (da classe ou do campo).", style = MaterialTheme.typography.bodySmall)
            if (shownClasses.isEmpty()) Text("Nenhuma classe ou campo com \"$cls\".", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (objs.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            objs.take(30).forEachIndexed { i, o ->
                FilterChip(selected = false, onClick = { s.view = (o.toLong(16) + (s.engine?.header ?: 0x10L)).toString(16) to "i32" }, label = { Text("#${i + 1} 0x$o", maxLines = 1) })
            }
        }
    }
}

/**
 * Parte de cima de uma tela que tem lista embaixo: rola sozinha e ocupa no máximo 45% da altura, para a lista nunca
 * ficar sem espaço (antes, IA aberta + lista de classes empurravam a ficha e o registro para fora da tela).
 */
@Composable
internal fun TopArea(content: @Composable ColumnScope.() -> Unit) {
    // deitado: só um quarto da altura para os controles (rolam), o resto fica para a lista
    val max = LocalPanelSize.current.height * if (landscape()) 0.25f else 0.45f
    Column(Modifier.fillMaxWidth().heightIn(max = max).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Tamanho do painel em dp (na Activity, a tela; no menu flutuante, o painel). */
internal val LocalPanelSize = androidx.compose.runtime.compositionLocalOf { DpSize(400.dp, 800.dp) }

/** Pouca altura para o que mostra (jogo deitado, painel de lado ou pela metade): cabeçalho compacto e controles menores. */
@Composable
internal fun landscape() = LocalPanelSize.current.let { it.width > it.height || it.height < 480.dp }

/** Uma classe da lista: nome, namespace, campos que bateram com o filtro; toque abre, ⧉ copia o nome. */
@Composable
private fun ClassRow(gc: GameClass, matched: List<String>, onOpen: () -> Unit, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .sizeIn(minHeight = 48.dp).clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(gc.name.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if ('.' in gc.name) Text(gc.name.substringBeforeLast('.'), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (matched.isNotEmpty()) Text("campos: " + matched.take(6).joinToString(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("${gc.fields} campos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onCopy, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("⧉", fontSize = 16.sp) }
    }
}

@Composable
private fun FichaBody(engine: Engine, center: String, run: (suspend () -> String) -> Unit) {
    val s = Session
    var filter by remember { mutableStateOf("") }
    var items by remember(center) { mutableStateOf<List<ListItem>?>(null) }
    var il2 by remember(center) { mutableStateOf<Il2Class?>(null) }
    var looked by remember(center) { mutableStateOf(false) }
    var page by remember(center) { mutableIntStateOf(0) }
    var rows by remember(center) { mutableStateOf(listOf<FichaRow>()) }
    var open by remember(center) { mutableStateOf<String?>(null) }
    var error by remember(center) { mutableStateOf<String?>(null) }
    val changed = remember(center) { mutableStateMapOf<String, Long>() }
    val last = remember(center) { HashMap<String, String>() }
    LaunchedEffect(center) {
        il2 = runCatching { engine.il2cpp(center) }.getOrNull()
        looked = true
        // lista do jogo (List<T>): mostra os itens, cada um abre a própria ficha
        val c = il2
        if (c != null && c.name.contains("List`1")) while (true) {
            items = runCatching { engine.listItems(c) }.getOrNull()
            delay(2000)
        }
    }
    LaunchedEffect(center, looked, page) {
        if (!looked) return@LaunchedEffect
        while (true) {
            val c = il2
            val start = c?.start?.toLong(16) ?: (center.toLong(16) - 0x40 + page * 0x80L).coerceAtLeast(0)
            val bytes = if (c != null) ((c.fields.maxOfOrNull { it.offset } ?: 0) + 8).coerceIn(16, 0x400) else 0x80
            runCatching { engine.ficha(start.toString(16), bytes, c, center) }.onSuccess { new ->
                val now = System.currentTimeMillis()
                new.forEach { r ->
                    val before = last.put(r.addr, r.value)
                    if (before != null && before != r.value) changed[r.addr] = now
                }
                rows = new
                error = null
            }.onFailure { error = friendly(it.message) }
            delay(1000)
        }
    }
    val c = il2
    TopArea {
    Text(
        when {
            !looked -> "Lendo o objeto…"
            c != null -> "${c.name} em 0x${c.start} · ${c.fields.size} campos (nomes do próprio jogo, IL2CPP)"
            else -> "Em volta de 0x$center · tipos adivinhados (o jogo não é IL2CPP, ou o valor não está num objeto)"
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (looked) AiBox(
        engine, "Perguntar à IA sobre esta ficha", listOf("O que cada campo faz?", "Quais campos vale editar, e com que valor?"),
        context = { engine.fichaContext(il2, rows) },
    )
    if (looked && c == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { page-- }) { Text("▲ Antes") }
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { page++ }) { Text("▼ Depois") }
        }
    }
    if (c != null) MethodsPanel(engine, c, run)
    if (c != null) CopyFicha(engine, c, run)
    if (looked && c == null && (s.engine?.lua == true || s.view?.second in setOf("li", "lf"))) LuaPanel(engine, center, run)
    if (c != null && c.fields.size > 6) {
        TinkerTextField(filter, { filter = it }, "Filtrar campo (ex.: gem)", Modifier.fillMaxWidth())
    }
    items?.let { list ->
        Text("Lista com ${list.size} itens${if (list.size == 200) " (mostrando os 200 primeiros)" else ""}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.take(40).forEach { it2 ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .sizeIn(minHeight = 48.dp).clickable(enabled = it2.isRef) { s.view = it2.addr to "i32" }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it2.label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (it2.isRef) "abrir ›" else it2.value, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (list.size > 40) Text("…e mais ${list.size - 40}.", style = MaterialTheme.typography.bodySmall)
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows.filter { filter.isBlank() || it.label.contains(filter.trim(), ignoreCase = true) }, key = { it.addr + it.label }) { r ->
            val ctx = LocalContext.current
            val a = r.addr.toLong(16)
            val here = center.toLong(16) in a until a + (if (r.type.isEmpty() || r.type == "xi32" || r.type == "i64" || r.type == "f64") 8 else 4)
            val hot = (changed[r.addr] ?: 0L) > System.currentTimeMillis() - 3000
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(when {
                        here -> MaterialTheme.colorScheme.primaryContainer
                        hot -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    })
                    .sizeIn(minHeight = 48.dp).clickable { open = if (open == r.addr) null else r.addr }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(offsetText(r.off), Modifier.width(64.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(r.label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.value, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (open == r.addr) {
                    Text("0x${r.addr}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { s.status = copyAddress(ctx, r.addr) }) { Text("Copiar endereço") }
                        TextButton(onClick = { s.view = r.addr to (r.type.ifEmpty { "i32" }).let(::plain); s.fichaMode = false; s.codeMode = false }) { Text("Ver na lista") }
                        if (r.value.startsWith("0x")) TextButton(onClick = { s.status = copyAddress(ctx, r.value.removePrefix("0x")) }) { Text("Copiar o valor") }
                        if (r.type.isNotEmpty()) {
                            // entra nos Salvos já ligado pelo offset aos outros da mesma ficha, com nome do campo quando é IL2CPP
                            FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { engine.addSaved(listOf(Hit(r.addr, r.type, "?", r.value))); s.status = "Salvo: ${r.label}"; open = null }) { Text("Salvar") }
                            // congela já no valor de agora (vira salvo congelado, com o nome do campo)
                            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { engine.addSaved(listOf(Hit(r.addr, r.type, "?", r.value)), frozen = true); s.status = "Congelado: ${r.label}"; open = null }) { Text("Congelar") }
                        }
                        if (r.value.startsWith("0x")) {
                            // ponteiro: abre a ficha do objeto para onde ele aponta
                            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { s.view = r.value.removePrefix("0x") to "i32"; open = null }) { Text("Abrir o objeto") }
                        }
                    }
                    if (r.type.isNotEmpty()) {
                        InlineEditor(r.value, "Novo valor (${TYPE_NAMES[r.type]})", onSave = { v -> open = null; run { engine.write(r.addr, r.type, v) } }, onCancel = { open = null })
                    }
                }
            }
        }
    }
}

/**
 * Métodos da classe da ficha: ver o código, contar quantas vezes rodam e fixar o retorno ("sempre devolve 999").
 * O patch nasce desligado em Salvos → Patches de código: a pessoa liga quando quiser.
 */
@Composable
private fun MethodsPanel(engine: Engine, c: Il2Class, run: (suspend () -> String) -> Unit) {
    val s = Session
    val scope = rememberCoroutineScope()
    var shown by remember(c.start) { mutableStateOf(false) }
    var methods by remember(c.start) { mutableStateOf<List<Il2Method>?>(null) }
    var error by remember(c.start) { mutableStateOf<String?>(null) }
    var open by remember(c.start) { mutableStateOf<String?>(null) }
    var filter by remember(c.start) { mutableStateOf("") }
    TextButton(onClick = {
        shown = !shown
        if (shown && methods == null) scope.launch {
            runCatching { engine.il2Methods(c.start) }.onSuccess { methods = it; error = null }.onFailure { error = friendly(it.message) }
        }
    }) { Text((if (shown) "▾ " else "▸ ") + "⚙ Métodos da classe" + (methods?.let { " (${it.size})" } ?: "")) }
    if (!shown) return
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val list = methods ?: return LinearProgressIndicator(Modifier.fillMaxWidth())
    if (list.size > 8) TinkerTextField(filter, { filter = it }, "Filtrar método (ex.: get_)", Modifier.fillMaxWidth())
    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }.take(200).forEach { m ->
            val ret = RET_NAMES[m.ret] ?: "objeto"
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .sizeIn(minHeight = 48.dp).clickable { open = if (open == m.code) null else m.code }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ret, Modifier.width(56.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(m.name + "()", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (open == m.code) {
                    Text(m.where, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { s.view = m.code to "i32"; s.fichaMode = false; s.codeMode = true }) { Text("Ver código") }
                        TextButton(onClick = { engine.traceLater(m.code, "${c.name.substringAfterLast('.')}.${m.name}") }) { Text("Contar chamadas (10 s)") }
                        when (m.ret) {
                            0x01 -> OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { open = null; run { engine.patchReturn(m, c.name, "") } }) { Text("Não fazer nada") }
                            0x02 -> {
                                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { open = null; run { engine.patchReturn(m, c.name, "true") } }) { Text("Sempre sim (true)") }
                                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { open = null; run { engine.patchReturn(m, c.name, "false") } }) { Text("Sempre não (false)") }
                            }
                        }
                    }
                    if (m.ret in 0x03..0x0d) InlineEditor("", "Sempre devolver ($ret)", onSave = { v -> open = null; run { engine.patchReturn(m, c.name, v) } }, onCancel = { open = null })
                    if (m.ret in 0x01..0x0d) Text("Vira um patch desligado em Salvos → Patches de código: ligue lá para valer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text("Devolve um objeto ou texto: dá para ver o código e contar chamadas, não para fixar o retorno.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (list.isEmpty()) Text("A classe não tem métodos com código próprio.", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Tabela do Lua (xLua, ToLua) em volta do endereço: cada campo com o nome que o jogo usa, ao vivo. Número dá para mudar e
 * salvar (tipo Lua, que confere a marca do Lua ao reabrir); campo que é outra tabela abre com um toque.
 */
@Composable
private fun LuaPanel(engine: Engine, center: String, run: (suspend () -> String) -> Unit) {
    val s = Session
    var ver by remember(center) { mutableStateOf("") }
    var fields by remember(center) { mutableStateOf<List<LuaField>?>(null) }
    var error by remember(center) { mutableStateOf<String?>(null) }
    var open by remember(center) { mutableStateOf<String?>(null) }
    LaunchedEffect(center) {
        while (true) {
            runCatching { engine.luaTable(center) }.onSuccess { (v, f) -> ver = v; fields = f; error = null }.onFailure { error = friendly(it.message); fields = emptyList() }
            delay(1500)
        }
    }
    val list = fields ?: return LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(if (list.isEmpty()) "🌙 Lua: ${error ?: "nenhum campo de tabela do Lua aqui"}" else "🌙 Tabela do Lua ($ver) · ${list.size} campos",
        style = MaterialTheme.typography.labelLarge, color = if (list.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.forEach { f ->
            val number = f.kind == "int" || f.kind == "num"
            val type = if (f.kind == "int") "li" else "lf"
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (f.addr == center) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                    .sizeIn(minHeight = 48.dp).clickable { if (f.kind == "table") s.view = f.value to "li" else open = if (open == f.addr) null else f.addr }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.key, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (f.kind == "table") "tabela ›" else if (number || f.kind == "str" || f.kind == "bool") f.value else f.kind,
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (f.kind == "table") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                if (open == f.addr && number) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { engine.addSaved(listOf(Hit(f.addr, type, "?", f.value)), name = f.key); s.status = "Salvo: ${f.key}"; open = null }) { Text("Salvar") }
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { engine.addSaved(listOf(Hit(f.addr, type, "?", f.value)), frozen = true, name = f.key); s.status = "Congelado: ${f.key}"; open = null }) { Text("Congelar") }
                    }
                    InlineEditor(f.value, "Novo valor (${TYPE_NAMES[type]})", onSave = { v -> open = null; run { engine.write(f.addr, type, v) } }, onCancel = { open = null })
                }
            }
        }
    }
}
