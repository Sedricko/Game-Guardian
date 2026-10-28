package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SearchTab(engine: Engine, run: (suspend () -> String) -> Unit) {
    val s = Session
    var editing by remember { mutableStateOf<String?>(null) }
    var editingAll by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var nearOpen by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf(mapOf<String, String>()) }
    var more by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    val enabled = s.process != null && !s.busy
    // valores ao vivo, como no Cheat Engine: relê os resultados mostrados a cada segundo
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (!s.busy && s.count != null) runCatching { s.results = engine.list().second }
        }
    }
    val list = rememberLazyListState()
    val listScope = rememberCoroutineScope()
    LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // a busca primeiro (é o que se usa toda hora); as ferramentas ficam numa fileira logo abaixo, uma aberta por vez
        item {
            Panel {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Auto ou qualquer combinação de tipos (ex.: só Float + Double)
                    TYPES.forEach { (code, label) ->
                        val on = if (code == "auto") s.types.isEmpty() else code in s.types
                        FilterChip(selected = on, onClick = {
                            s.types = when {
                                code == "auto" -> emptySet()
                                on -> s.types - code
                                else -> s.types + code
                            }
                        }, label = { Text(label) })
                    }
                    // jogo com Lua (xLua, ToLua): número do Lua, que tem o tipo do Lua logo depois (bem menos resultados falsos)
                    if (s.engine?.lua == true) FilterChip(selected = "li" in s.types, onClick = {
                        s.types = if ("li" in s.types) s.types - "li" else s.types + "li"
                    }, label = { Text("🌙 Lua") })
                }
                // teclado de texto: o numérico não tem o ~ da faixa (100~200)
                ValueField(s.value, "Valor", { s.value = it }, keyboard = KeyboardType.Ascii)
                if (s.value.isEmpty()) Text("100 · faixa 100~200 · vários 100;250 · grupo 100;250::64 · cifrado 150X4", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.history.isNotEmpty()) {
                    // últimas buscas: um toque repete valor e tipos
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.history.forEach { h ->
                            val types = if (h.types.isEmpty()) "" else " · " + h.types.joinToString("+") { TYPE_NAMES.getValue(it) }
                            AssistChip(onClick = { s.value = h.value; s.types = h.types }, label = { Text(h.value + types, maxLines = 1) })
                        }
                    }
                }
                // com resultados, a Nova busca pede confirmação: no meio do refino ela zerava tudo sem querer
                if (confirmNew) {
                    Text("Começar do zero? Os ${s.count} resultados de agora somem.", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(shape = MaterialTheme.shapes.medium, onClick = { confirmNew = false; run { engine.search(s.types, s.value) } }) { Text("Sim, nova busca") }
                        TextButton(onClick = { confirmNew = false }) { Text("Não, continuar refinando") }
                    }
                }
                // o mesmo número do último refino não filtra nada: o botão avisa em vez de refinar à toa
                val same = s.value.trim().isNotEmpty() && s.value.trim() == s.lastRefine
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.medium, onClick = {
                        if ((s.count ?: 0) > 0) confirmNew = true else run { engine.search(s.types, s.value) }
                    }, enabled = enabled && s.value.isNotBlank() && !confirmNew, modifier = Modifier.weight(1f), contentPadding = TIGHT) {
                        Text("Nova busca", maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(
                        shape = MaterialTheme.shapes.medium,
                        onClick = { run { engine.refine(s.value) } },
                        enabled = enabled && (s.count ?: 0) > 0 && s.value.isNotBlank() && !same,
                        modifier = Modifier.weight(1f), contentPadding = TIGHT,
                    ) { Text(if (same) "Mude no jogo" else "Refinar", maxLines = 1) }
                    // sem número na tela (barra de vida): começa já aqui, sem abrir "Mais buscas"
                    if (s.count == null) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.startUnknown(s.types) } }, enabled = enabled, contentPadding = TIGHT) { Text("Sem valor", maxLines = 1) }
                }
                // 🎯 lê o número na tela e refina sozinho a cada mudança; com "IA pode testar valores", acha o verdadeiro
                val finding = s.autoFind
                if (finding == null) {
                    // número que muda rápido (tempo, abates): pelo nome escrito ao lado dele na tela
                    var label by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TinkerTextField(label, { label = it }, "ou o nome (Moedas) ou um pedido (Moedas = 9999)", Modifier.weight(1f))
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { engine.autoFindStart(label.ifBlank { s.value }, s.types) },
                            enabled = enabled && (label.isNotBlank() || s.value.isNotBlank())) { Text("🎯 Achar sozinho") }
                    }
                }
                else Panel {
                    Text("🎯 $finding", style = MaterialTheme.typography.bodyMedium)
                    s.autoFindWinner?.let { w ->
                        FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { engine.addSaved(listOf(w)); s.status = "Salvo: 0x${w.addr}"; engine.autoFindStop() }) { Text("Salvar 0x${w.addr}") }
                    }
                    TextButton(onClick = { engine.autoFindStop() }) { Text(if (s.autoFindWinner != null || finding.startsWith("Não") || finding.startsWith("Nenhum")) "Fechar" else "⏹ Parar") }
                }
                ItemsPanel(engine, enabled, run)
                if (s.canUndo) TextButton(onClick = { run { engine.undo() } }, enabled = enabled) { Text("↶ Desfazer último refino") }
                // com resultados: refinar pelo jeito que mudou, sem precisar do número
                if ((s.count ?: 0) > 0) CompareButtons(engine, enabled, run)
                Text(s.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // busca deu 0: a IA investiga sozinha por quê (e tenta achar)
                if (s.count == 0L && s.process != null && Assistant.ready()) TextButton(onClick = {
                    s.aiAsk = "busca" to "A busca não achou o valor. Descubra por quê e tente achar você mesmo (outro tipo, faixa, grupo, sem valor ou pelo nome da classe)."
                }) { Text("✨ Por que não achou? A IA tenta achar") }
                // o resto fica recolhido: a tela pequena do menu flutuante sobra para os resultados
                TextButton(onClick = { more = !more }) { Text((if (more) "▾ " else "▸ ") + "Mais buscas") }
                if (more) {
                    UnknownSearch(engine, enabled, run)
                    AobSearch(engine, enabled, run)
                    PhotoPanel(engine, enabled, s.results.filter { it.addr + it.type in selected }.map { it.addr }, run)
                    ActivityPanel(engine, enabled)
                    SaveEditorPanel(engine, run)
                    TextButton(onClick = { s.view = null; s.tab = TAB_MEMORY }, enabled = s.process != null) { Text("Ir para endereço…") }
                }
                if (s.process != null) AiBox(
                    engine, "Perguntar à IA sobre esta busca",
                    listOf("Qual desses é o valor certo?", "Por que a busca não acha o valor?", "Qual o próximo passo?", "Me guie na busca sem valor (barra de vida)"),
                    context = { engine.searchContext(names) }, key = "busca",
                )
            }
        }
        // a ferramenta abre embaixo da busca: a lista rola até a fileira para o conteúdo dela aparecer sem rolar à mão
        item { Toolbox(engine, enabled, run, onOpen = { listScope.launch { list.animateScrollToItem(1) } }) }
        val count = s.count ?: return@LazyColumn
        val visible = s.results.map { it.addr + it.type }.toSet()
        val chosen = s.results.filter { it.addr + it.type in selected }
        val targets = chosen.ifEmpty { s.results }
        val label = if (chosen.isEmpty()) "todos" else "${chosen.size}"
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = visible.isNotEmpty() && selected.containsAll(visible), onCheckedChange = { selected = if (it) visible else emptySet() })
                Column(Modifier.weight(1f)) {
                    Text("$count resultados", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (count > s.results.size) "Mostrando ${s.results.size}. Refine para ver todos." else "Marque para agir só nos escolhidos.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (s.results.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { engine.addSaved(targets); s.status = "${targets.size} salvos na lista." }) { Text("Salvar $label") }
                    FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { engine.addSaved(targets, frozen = true); s.status = "${targets.size} congelados (veja Salvos)." }) { Text("Congelar $label") }
                    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { editingAll = !editingAll }, enabled = !s.busy) { Text("Editar $label") }
                    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { nearOpen = !nearOpen }, enabled = !s.busy) { Text("Buscar perto (${targets.size})") }
                    // para análise na planilha (aba Buscas): quem toca é a pessoa, e só vai o que está na tela
                    if (s.keySource.isNotBlank()) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.sendSearch() } }, enabled = !s.busy) { Text("Mandar para a planilha") }
                }
            }
        }
        if (nearOpen && targets.isNotEmpty()) {
            item {
                // procura em volta de cada resultado escolhido (ou de todos os mostrados)
                NearSearch(engine, targets.map { it.addr }, plain(targets.first().type), chooseType = true, onError = { s.status = it })
            }
        }
        if (editingAll) {
            item {
                Panel {
                    Text(
                        if (chosen.isEmpty()) "O novo valor vai para os $count resultados." else "O novo valor vai para os ${chosen.size} escolhidos.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InlineEditor("", "Novo valor", onSave = { v ->
                        editingAll = false
                        run { if (chosen.isEmpty()) engine.writeAll(v) else engine.writeMany(chosen.map { it.addr to it.type }, v) }
                    }, onCancel = { editingAll = false })
                    // só os que valem X agora (todos os inimigos com 100 de vida), sem refinar e perder a lista
                    var whereFrom by remember { mutableStateOf("") }
                    var whereTo by remember { mutableStateOf("") }
                    Text("Só os que valem agora:", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ValueField(whereFrom, "Valem", { whereFrom = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                        Text("→")
                        ValueField(whereTo, "Viram", { whereTo = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                        OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = whereFrom.isNotBlank() && whereTo.isNotBlank() && !s.busy,
                            onClick = { editingAll = false; run { engine.writeWhere(whereFrom, whereTo) } }) { Text("Mudar") }
                    }
                    // como no GameGuardian: cada um ganha um número (100, 101, 102…) e o que aparecer no jogo é o certo
                    val seqTargets = (if (chosen.isEmpty()) s.results else chosen).map { it.addr to it.type }
                    Text("Em sequência (${seqTargets.size} endereços, +1 em cada):", style = MaterialTheme.typography.labelLarge)
                    var seqStart by remember { mutableStateOf("") }
                    ValueField(seqStart, "Começar em (vazio: logo acima do valor de agora)", { seqStart = it }, keyboard = KeyboardType.Number)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                            run { engine.writeSequence(seqTargets, seqStart.ifBlank { ((s.results.firstOrNull()?.value?.toDoubleOrNull() ?: 99.0).toLong() + 1).toString() })
                                .let { w -> "${w.size} escritos (${w.values.firstOrNull()}…${w.values.lastOrNull()}): veja qual número apareceu no jogo e toque no resultado com ele." } }
                        }, enabled = !s.busy && seqTargets.isNotEmpty()) { Text("Escrever em sequência") }
                        FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.findBySequence(seqTargets, seqStart) } },
                            enabled = !s.busy && seqTargets.isNotEmpty()) { Text("🎯 Descobrir pela tela") }
                        TextButton(onClick = { run { engine.undoSequence() } }, enabled = !s.busy) { Text("Voltar como estava") }
                    }
                }
            }
        }
        // com poucos resultados, diz qual campo do jogo cada um é (IL2CPP): o dado de verdade x a cópia que aparece na tela
        item {
            // resultado novo: o editor do único que sobrou volta a abrir sozinho
            LaunchedEffect(s.results.firstOrNull()?.addr) { if (editing == "-") editing = null }
            LaunchedEffect(if (count <= 20) s.results.joinToString { it.addr } else "") {
                names = if (count in 1..20) runCatching { engine.il2Names(s.results.map { it.addr }) }.getOrDefault(emptyMap()) else emptyMap()
            }
        }
        // normal colado num 🔒 com o mesmo número (até 32 bytes, a mesma estrutura do antitrapaça): é cópia ou isca; editar o 🔒
        val cipher = s.results.filter { it.type.startsWith("x") }.map { it.addr.toLong(16) to readable(it.value, it.type) }
        items(s.results, key = { it.addr + it.type }) { hit ->
            val ctx = LocalContext.current
            val key = hit.addr + hit.type
            // sobrou um só: o editor já vem aberto, com Congelar (antes eram 4 toques e ir até os Salvos)
            val open = editing == key || (count == 1L && editing == null)
            val copy = !hit.type.startsWith("x") && readable(hit.value, hit.type).let { v ->
                cipher.any { (a, cv) -> cv == v && kotlin.math.abs(a - hit.addr.toLong(16)) <= 32 }
            }
            ResultCard(
                shown(hit.value, hit.type), "0x${hit.addr}" + if (copy) " · provável cópia: edite o 🔒" else "", "${TYPE_NAMES[hit.type]} · ${hit.region}",
                onClick = { editing = if (open) "-" else key },  // "-": fechado de propósito, não reabre sozinho
                checked = key in selected, onCheck = { selected = if (it) selected + key else selected - key }, title = names[hit.addr].orEmpty(),
            ) {
                if (open) {
                    InlineEditor(readable(hit.value, hit.type) ?: "", "Novo valor", onSave = { v -> editing = "-"; run { engine.write(hit.addr, hit.type, v) } }, onCancel = { editing = "-" }) {
                        TextButton(onClick = { engine.addSaved(listOf(hit), frozen = true); editing = "-" }) { Text("❄ Congelar") }
                        TextButton(onClick = { engine.addSaved(listOf(hit)); editing = "-" }) { Text("Salvar na lista") }
                        TextButton(onClick = { s.view = hit.addr to plain(hit.type); s.fichaMode = false; s.codeMode = false; s.tab = TAB_MEMORY; editing = null }) { Text("Ver próximos") }
                        TextButton(onClick = { s.view = hit.addr to plain(hit.type); s.fichaMode = true; s.codeMode = false; s.tab = TAB_MEMORY; editing = null }) { Text("Ver ficha") }
                        TextButton(onClick = { s.status = copyAddress(ctx, hit.addr) }) { Text("Copiar endereço") }
                        // ex.: a marca "recompensa pega" achada pela Foto do jogo: qual instrução marca ela (o resultado aparece em Salvos)
                        TextButton(onClick = { engine.watchLater(SavedItem(hit.addr, plain(hit.type), hit.region, hit.value)); editing = "-" }, enabled = !s.busy) { Text("O que escreve aqui?") }
                    }
                }
            }
        }
    }
}

/** Busca desconhecida: sem número na tela (barra de vida), compara como o valor mudou. */
@Composable
private fun UnknownSearch(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    val s = Session
    Text("Não sabe o número? (barra de vida, energia…) Comece sem valor e depois diga como ele mudou.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.startUnknown(s.types) } }, enabled = enabled) { Text("Começar sem valor") }
}

/** Refinar pelo jeito que o valor mudou: ↑ ↓ = ≠ e, com um número no campo, quanto mudou. */
@Composable
private fun CompareButtons(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    val s = Session
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("inc" to "↑ Aumentou", "dec" to "↓ Diminuiu", "changed" to "≠ Mudou", "same" to "= Não mudou").forEach { (op, label) ->
            FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.compare(op) } }, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label, maxLines = 1) }
        }
        // com um número no campo: quanto mudou (ex.: perdeu 10 de vida → Diminuiu 10)
        if (s.value.isNotBlank()) {
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.compare("incby", s.value) } }, enabled = enabled) { Text("+${s.value}", maxLines = 1) }
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.compare("decby", s.value) } }, enabled = enabled) { Text("−${s.value}", maxLines = 1) }
        }
    }
}

/**
 * Foto do jogo (só offline): foto antes de uma ação (pegar a recompensa do correio), ver o que mudou e voltar como estava,
 * para a recompensa ficar de novo para pegar. Os resultados vão para a lista da Busca, onde dá para refinar antes de voltar.
 */
@Composable
private fun PhotoPanel(engine: Engine, enabled: Boolean, chosen: List<String>, run: (suspend () -> String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "📸 Foto do jogo (antes e depois de uma ação)") }
    if (!open) return
    Text("Antes: faça um backup do save. 1) Tire a foto (fique parado 4 s). 2) Pegue a recompensa no jogo. 3) Veja o que mudou; marque o " +
        "contador que aumentou (os presentes) e volte todos menos ele: o que você ganhou fica e a recompensa volta. Deu certo? Pegue de novo e " +
        "use Liberar de novo quantas vezes quiser. Só jogo offline.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // backup antes de experimentar: se algo der errado, o progresso volta
    var backups by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.backupSave() } }, enabled = enabled) { Text("💾 Backup do save") }
        TextButton(onClick = { scope.launch { backups = runCatching { engine.listBackups() }.getOrDefault(emptyList()) } }, enabled = enabled) { Text("Voltar um backup…") }
    }
    backups?.let { list ->
        if (list.isEmpty()) Text("Nenhum backup deste jogo ainda.", style = MaterialTheme.typography.bodySmall)
        list.take(8).forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name.removeSuffix(".tar"), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                TextButton(onClick = { backups = null; run { engine.restoreSave(name) } }) { Text("Voltar este (fecha o jogo)") }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(shape = MaterialTheme.shapes.medium, onClick = { run { engine.photoTake() } }, enabled = enabled) { Text("1. Tirar foto") }
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.photoDiff() } }, enabled = enabled) { Text("2. Ver o que mudou") }
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.photoRestore(chosen.ifEmpty { null }) } }, enabled = enabled) {
            Text(if (chosen.isEmpty()) "3. Voltar como estava (todos)" else "3. Voltar os ${chosen.size} escolhidos")
        }
        // marque o contador da recompensa (o que aumentou): ele fica, o resto volta e a recompensa fica para pegar de novo
        if (chosen.isNotEmpty()) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.photoRestore(chosen, except = true) } }, enabled = enabled) {
            Text("3. Voltar todos, menos os ${chosen.size} escolhidos")
        }
        if (Session.release.isNotEmpty()) Button(shape = MaterialTheme.shapes.medium, onClick = { run { engine.releaseAgain() } }, enabled = enabled) {
            Text("🔁 Liberar de novo (${Session.release.size})")
        }
    }
}

/** Busca por sequência de bytes (AOB), com ?? como curinga; nos dados ou no código do jogo. */
@Composable
private fun AobSearch(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("") }
    var where by remember { mutableStateOf("data") }
    TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "Busca por bytes (AOB) ou texto") }
    if (!open) return
    TinkerTextField(pattern, { pattern = it }, "Bytes (ex.: 1F 20 03 D5 ?? ?? 00 B9)", Modifier.fillMaxWidth(), mono = true)
    // Xa = código do jogo; Xs = código do sistema (como no GameGuardian); quase tudo que importa fica no Xa
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("data" to "Dados", "xa" to "Código do jogo (Xa)", "xs" to "Código do sistema (Xs)").forEach { (k, l) ->
            FilterChip(selected = where == k, onClick = { where = k }, label = { Text(l) })
        }
    }
    Button(shape = MaterialTheme.shapes.medium, onClick = { run { engine.searchAob(pattern, where) } }, enabled = enabled && pattern.isNotBlank()) { Text("Buscar bytes") }
    // texto (nome de item, apelido): tenta UTF-8 e, se não achar, UTF-16; texto tem letras, então teclado do telefone
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TinkerTextField(text, { text = it }, "Texto (ex.: Madeira)", Modifier.weight(1f))
        Button(shape = MaterialTheme.shapes.medium, onClick = { run { engine.searchText(text) } }, enabled = enabled && text.length >= 3) { Text("Buscar texto") }
    }
}

/** Ponteiros de um salvo, como o "Pointer scan" do Cheat Engine: o valor é achado de novo mesmo depois de reiniciar o jogo. */
@Composable
internal fun PointerPanel(engine: Engine, item: SavedItem, run: (suspend () -> String) -> Unit) {
    val s = Session
    var maxOff by remember { mutableStateOf("4096") }
    var levels by remember { mutableStateOf("4") }
    var now by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // fechado por padrão: o salvo aberto fica curto; o resumo diz o essencial
        var expanded by remember { mutableStateOf(false) }
        Text(
            (if (expanded) "▾ " else "▸ ") + "Ponteiro e restauração · " + when {
                item.pointer.isNotEmpty() && item.verified -> "🔗 confirmado"
                item.pointer.isNotEmpty() -> "🔗 a confirmar"
                item.anchor.isNotEmpty() -> "segue outro salvo"
                else -> "sem ponteiro"
            } + " · ${item.chains.size} reservas · assinatura ${if (sigUseful(item.sig)) "pronta" else "aprendendo"}",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).clickable { expanded = !expanded },
        )
        if (!expanded) return@Column
        if (item.pointer.isNotEmpty()) {
            Text(pointerText(item.pointer), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(
                if (item.verified) "Confirmado: o Tinker segue esse caminho sozinho a cada leitura."
                else "Escolhido sozinho. Na próxima vez que o jogo abrir, o Tinker confere com o último valor e confirma (ou troca por um de reserva).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { engine.setPointer(item, "") }) { Text("Parar de usar o ponteiro") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ValueField(maxOff, "Distância máx. (bytes)", { maxOff = it.filter(Char::isDigit) }, Modifier.weight(1f), KeyboardType.Number)
            ValueField(levels, "Níveis", { levels = it.filter(Char::isDigit).take(1) }, Modifier.weight(0.6f), KeyboardType.Number)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.scanPointers(item, maxOff.toIntOrNull() ?: 4096, levels.toIntOrNull() ?: 4) } }, enabled = !s.busy && s.process != null && !engine.stale(item)) {
                Text(if (item.chains.isEmpty()) "Buscar ponteiros" else "Buscar de novo")
            }
        }
        if (item.chains.isNotEmpty()) {
            Text(
                "${item.chains.size} caminhos de reserva. Enquanto você joga, os que erram saem sozinhos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // se sobrarem vários: diga o valor que o jogo mostra agora
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueField(now, "Valor agora no jogo", { now = it }, Modifier.weight(1f))
                FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.filterPointersByValue(item, now) } }, enabled = !s.busy && s.process != null && now.isNotBlank()) {
                    Text("Filtrar pelo valor")
                }
            }
        }
        if (s.pointerInfo.isNotEmpty()) Text(s.pointerInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        item.chains.take(20).forEach { chain ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pointerText(chain), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { engine.setPointer(item, chain) }, enabled = chain != item.pointer) { Text(if (chain == item.pointer) "Em uso" else "Usar") }
            }
        }
        if (item.chains.size > 20) Text("…e mais ${item.chains.size - 20}. Filtre para sobrar menos.", style = MaterialTheme.typography.bodySmall)
        Text(
            if (sigUseful(item.sig)) "Assinatura pronta (${item.sig.length / 2} bytes): plano B se nenhum ponteiro servir."
            else "Assinatura: aprendendo os bytes em volta enquanto o jogo roda (deixe a aba Salvos aberta um pouco).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ResultCard(
    value: String, addr: String, tag: String, onClick: () -> Unit,
    checked: Boolean, onCheck: (Boolean) -> Unit, title: String = "",
    trailing: @Composable () -> Unit = {}, below: @Composable () -> Unit = {},
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),  // linha de lista: só um relevo leve
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheck)
                Column(Modifier.weight(1f)) {
                    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(addr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                trailing()
            }
            below()
        }
    }
}

/**
 * 💾 Editor de save: os arquivos de progresso do jogo (preferências e PlayerPrefs do Unity em XML, JSON) com cada número
 * pelo nome. Gravar faz backup do save, fecha o jogo e devolve o arquivo; ao abrir de novo, o jogo lê o valor novo.
 */
@Composable
private fun SaveEditorPanel(engine: Engine, run: (suspend () -> String) -> Unit) {
    val s = Session
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var files by remember(s.process) { mutableStateOf<List<Pair<String, Long>>?>(null) }
    var file by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    val edits = remember(file) { mutableStateMapOf<SaveField, String>() }
    TextButton(onClick = {
        open = !open
        if (open && files == null) scope.launch { runCatching { engine.saveFiles() }.onSuccess { files = it }.onFailure { info = friendly(it.message) } }
    }, enabled = s.process != null) { Text((if (open) "▾ " else "▸ ") + "💾 Editor de save (jogo offline)") }
    if (!open) return
    Panel {
        Text("Os arquivos onde o jogo guarda o progresso. Gravar faz backup do save, fecha o jogo e devolve o arquivo editado.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        val t = text
        if (file == null || t == null) {
            val list = files ?: return@Panel LinearProgressIndicator(Modifier.fillMaxWidth())
            if (list.isEmpty()) Text("Nenhum arquivo de save achado.", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                list.take(80).forEach { (f, size) ->
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { engine.readSave(f) }.onSuccess { file = f; text = it; info = null }.onFailure { info = friendly(it.message) }
                        }
                    }) { Text("$f · ${if (size >= 1024) "${size / 1024} KB" else "$size B"}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        } else {
            val fields = remember(t) { saveFields(t) }
            Text("$file · ${fields.size} números", style = MaterialTheme.typography.labelLarge)
            if (fields.isEmpty()) Text("Sem números legíveis neste arquivo.", style = MaterialTheme.typography.bodySmall)
            if (fields.size > 8) TinkerTextField(filter, { filter = it }, "Filtrar (ex.: gold)", Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                fields.filter { filter.isBlank() || it.key.contains(filter.trim(), ignoreCase = true) }.take(200).forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.key, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ValueField(edits[f] ?: f.value, "", { v -> if (v == f.value) edits.remove(f) else edits[f] = v }, Modifier.width(120.dp))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(shape = MaterialTheme.shapes.medium, enabled = edits.isNotEmpty(), onClick = {
                    val rel = file!!
                    val changes = edits.toMap()
                    run { engine.writeSave(rel, t, changes).also { file = null; text = null } }
                }) { Text("Gravar (${edits.size}) e fechar o jogo") }
                TextButton(onClick = { file = null; text = null }) { Text("Voltar à lista") }
            }
        }
    }
}

/**
 * 📍 Onde o jogo escreve: marca a memória, a pessoa carrega o jogo ou uma fase, e o Tinker mostra por região quanto foi
 * escrito. As regiões com mais escrita viram as "deste jogo": a próxima busca começa só nelas (menos resultados).
 */
@Composable
private fun ActivityPanel(engine: Engine, enabled: Boolean) {
    val s = Session
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<Triple<String, Long, Long>>>(emptyList()) }
    var learned by remember(s.process) { mutableStateOf(engine.learnedRegions()) }
    TextButton(onClick = { open = !open }, enabled = s.process != null) {
        Text((if (open) "▾ " else "▸ ") + "📍 Onde o jogo escreve" + if (learned.isNotEmpty()) " (${learned.joinToString()})" else "")
    }
    if (!open) return
    Panel {
        Text("Toque em Começar, carregue o jogo ou uma fase (ou faça a ação do valor) e toque em Ver. As regiões onde o jogo mais escreveu " +
            "viram as deste jogo: a busca começa só nelas, com bem menos resultados; se não achar lá, procura em tudo sozinha.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = {
                scope.launch { info = runCatching { engine.activityStart() }.getOrElse { friendly(it.message) }; rows = emptyList() }
            }) { Text("Começar") }
            OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = {
                scope.launch {
                    runCatching { engine.activityResult() }.onSuccess { (how, r) ->
                        rows = r
                        info = if (r.sumOf { it.second } == 0L) "Nada escrito desde Começar: carregue algo no jogo e veja de novo."
                            else if (how == "soft") "Páginas escritas desde Começar:" else "Memória nova escrita desde Começar (este aparelho não marca cada página):"
                    }.onFailure { info = friendly(it.message) }
                }
            }) { Text("Ver") }
        }
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val total = rows.sumOf { it.second }.coerceAtLeast(1)
        rows.filter { it.second > 0 }.take(8).forEach { (code, kb, all) ->
            Text("$code: ${if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"} escritos (${kb * 100 / total}%) · região de ${all / 1024} MB",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        val busy = busyRegions(rows)
        if (busy.isNotEmpty()) FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = {
            engine.setLearnedRegions(busy); learned = busy; s.status = "Buscas deste jogo começam em ${busy.joinToString()}."
        }) { Text("Usar ${busy.joinToString()} nas buscas deste jogo") }
        if (learned.isNotEmpty()) TextButton(onClick = { engine.setLearnedRegions(emptySet()); learned = emptySet() }) { Text("Esquecer as regiões deste jogo") }
    }
}

/** Copia o endereço (como no Cheat Engine) e devolve o aviso para a linha de status. */
internal fun copyAddress(context: android.content.Context, addr: String): String {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Endereço", addr))
    return "Copiado: $addr"
}

/**
 * Busca perto: valores (um ou vários com ;) nas N linhas acima e abaixo de cada endereço de partida.
 * Na Memória o tipo é o da tela; na Busca dá para escolher.
 */
@Composable
internal fun NearSearch(engine: Engine, centers: List<String>, initialType: String, chooseType: Boolean, onError: (String) -> Unit) {
    val s = Session
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }
    var chosen by remember { mutableStateOf(initialType) }
    val type = if (chooseType) chosen else initialType
    var found by remember(centers, type) { mutableStateOf<List<Hit>?>(null) }
    var working by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    val size = TYPE_SIZES.getValue(type)
    fun go(work: suspend () -> List<Hit>) = scope.launch {
        working = true
        runCatching { found = work() }.onFailure { onError(friendly(it.message)) }
        working = false
    }
    Panel {
        Text(if (centers.size > 1) "Buscar perto de ${centers.size} endereços" else "Buscar perto", style = MaterialTheme.typography.titleSmall)
        if (chooseType) TypeChips(chosen) { chosen = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ValueField(values, "Valores (ex.: 50;75)", { values = it }, Modifier.weight(2f))
            ValueField(radius, "± linhas", { radius = it.filter(Char::isDigit) }, Modifier.weight(1f), KeyboardType.Number)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(shape = MaterialTheme.shapes.medium, onClick = { go { engine.searchNear(centers, type, radius.toIntOrNull() ?: 100, values) } }, enabled = values.isNotBlank() && !working) {
                Text("Buscar ${TYPE_NAMES[type]}")
            }
            // o valor mudou no jogo: digite o novo e a lista diminui
            if (!found.isNullOrEmpty()) FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { go { engine.refineNear(found.orEmpty(), values) } }, enabled = values.isNotBlank() && !working) {
                Text("Refinar")
            }
            if (found != null) TextButton(onClick = { found = null }) { Text("Limpar") }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        found?.let { list ->
            Text(
                when {
                    list.isEmpty() -> "Nada encontrado. Tente outro tipo ou aumente as linhas."
                    list.size > 50 -> "${list.size} encontrados; mostrando os 50 mais perto."
                    else -> "${list.size} encontrados:"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            fun delta(h: Hit) = (h.addr.toLong(16) - h.base.toLong(16) / size * size) / size
            list.sortedBy { kotlin.math.abs(delta(it)) }.take(50).forEach { hit ->
                val d = delta(hit)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .sizeIn(minHeight = 48.dp).clickable { open = if (open == hit.addr) null else hit.addr }.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(hit.addr, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1)
                        Text(shown(hit.value, hit.type), fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "${if (d > 0) "+" else ""}$d linhas" + if (centers.size > 1) " de 0x${hit.base}" else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                    if (open == hit.addr) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { engine.addSaved(listOf(hit.copy(region = "?"))); open = null }) { Text("Salvar") }
                            TextButton(onClick = { s.view = hit.addr to type; s.tab = TAB_MEMORY }) { Text("Ir para") }
                        }
                    }
                }
            }
        }
    }
}

// três botões numa linha (Nova busca, Refinar, Sem valor): o espaço interno padrão (24 dp) cortava "Nova busca" em pé
private val TIGHT = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

/** 📋 Itens do jogo: o que ele achou sozinho na tela para editar e congelar; procura sozinho ao escolher um jogo. */
@Composable
private fun ItemsPanel(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    val s = Session
    // ao escolher um jogo (não o de teste, que as missões comandam), procura sozinho depois de o jogo carregar
    // uma vez por vez do jogo: o efeito roda de novo a cada vez que o menu abre, e procurar de novo escondia o menu
    // (ele sai da frente para ler a tela) — a pessoa abria e ele fechava sozinho 6 s depois, sem parar
    LaunchedEffect(s.process?.pid) {
        val p = s.process ?: return@LaunchedEffect
        if (s.itemsPid == p.pid) return@LaunchedEffect
        s.itemsPid = p.pid
        s.items = emptyList()
        if (p.pkg != TEST_GAME) { delay(6000); run { engine.scanItems() } }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📋 Itens do jogo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { run { engine.scanItems() } }, enabled = enabled) { Text(if (s.items.isEmpty()) "Procurar" else "↻ De novo") }
        }
        if (s.items.isEmpty()) Text(
            "Ele lê a tela e acha sozinho o que dá para editar e congelar (dinheiro, vida, diamantes…), mesmo num jogo que nunca viu. " +
                "Deixe na tela do jogo a parte que mostra os números (inventário, loja…) e toque em Procurar.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${item.icon} ${item.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (item.value.isNotEmpty()) Text(item.value, style = MaterialTheme.typography.titleSmall)
            }
            Text(item.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val hit = item.hit
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hit != null) {
                    // salvo com o nome: a aba Salvos edita, congela e vira receita; o agente aprende o tipo deste nome
                    TextButton(onClick = { engine.addSaved(listOf(hit), name = item.label); s.status = "${item.label} salvo: edite o valor em Salvos."; s.tab = TABS.indexOf("Salvos") }) { Text("✏️ Editar") }
                    TextButton(onClick = { engine.addSaved(listOf(hit), frozen = true, name = item.label); s.status = "${item.label} congelado em ${item.value}." }) { Text("❄️ Congelar") }
                } else if (item.method != null && item.fix != null) {
                    // fixar o retorno vira um patch (desfaz com um toque em Memória → Código); o nome da função volta sozinho ao reabrir
                    TextButton(onClick = { run { engine.patchReturn(item.method, item.cls, item.fix) } }, enabled = enabled) { Text("🔧 ${item.fixLabel}") }
                } else if (item.method == null && (item.count > 1 || item.type.endsWith("^"))) TextButton(onClick = { engine.autoFindStart(item.label, emptySet()) }, enabled = enabled) { Text("🎯 Achar sozinho") }
            }
        }
    }
}
