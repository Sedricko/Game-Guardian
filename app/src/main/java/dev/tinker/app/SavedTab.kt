package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SavedTab(engine: Engine, overlay: Boolean, onMinimize: () -> Unit, run: (suspend () -> String) -> Unit) {
    val s = Session
    val context = LocalContext.current
    var info by remember { mutableStateOf<String?>(null) }
    fun import(text: String) {
        info = runCatching { engine.importSaved(text) }.getOrElse { "Não importou: ${it.message}" }
    }
    // abrir arquivo só existe na tela principal: o painel flutuante não recebe resultado de outra tela
    val openFile = if (!overlay) androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { u -> runCatching { context.contentResolver.openInputStream(u)!!.use { it.reader().readText() } }.onSuccess(::import).onFailure { info = "Não leu o arquivo: ${it.message}" } } } else null
    var editing by remember { mutableStateOf<String?>(null) }
    var editingMany by remember { mutableStateOf(false) }
    var porting by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var gone by remember { mutableStateOf<List<SavedItem>?>(null) }  // apagados que a planilha guarda (Restaurar apagado)
    var compareText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.process) {
        while (true) {
            runCatching { engine.refreshSaved() }
            delay(1000)
        }
    }
    val all = s.saved.map { it.id }.toSet()
    val chosen = s.saved.filter { it.id in selected }
    val targets = chosen.ifEmpty { s.saved.toList() }
    val label = if (chosen.isEmpty()) "todos" else "${chosen.size}"
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
            // exportar/importar é raro: recolhido, os salvos e as ações do dia a dia ficam em cima
            TextButton(onClick = { porting = !porting }) { Text((if (porting) "▾ " else "▸ ") + "Exportar, importar ou restaurar apagado") }
            if (porting) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                    val file = File(context.cacheDir, "reports").apply { mkdirs() }
                        .let { File(it, "tinker-salvos-${System.currentTimeMillis() / 1000}.json") }.apply { writeText(engine.exportSaved()) }
                    info = saveToDownloads(context, file, "application/json")?.let { "Salvo em Download/Tinker/${file.name}" } ?: "Lista pronta: ${file.name}"
                    if (overlay) onMinimize()  // o seletor de apps abre atrás do painel
                    shareFile(context, file, "application/json")
                }, enabled = s.saved.isNotEmpty()) { Text("Exportar") }
                // tabela do Cheat Engine (.CT): os salvos com ponteiro abrem no CE e listas do CE abrem aqui (Colar ou Abrir arquivo)
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                    val file = File(context.cacheDir, "reports").apply { mkdirs() }
                        .let { File(it, "tinker-salvos-${System.currentTimeMillis() / 1000}.CT") }.apply { writeText(engine.exportCt()) }
                    info = saveToDownloads(context, file, "application/xml")?.let { "Salvo em Download/Tinker/${file.name}" } ?: "Tabela pronta: ${file.name}"
                    if (overlay) onMinimize()
                    shareFile(context, file, "application/xml")
                }, enabled = s.saved.isNotEmpty()) { Text("Exportar .CT (Cheat Engine)") }
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Lista do Tinker", engine.exportSaved()))
                    info = "Lista copiada. Cole onde quiser."
                }, enabled = s.saved.isNotEmpty()) { Text("Copiar lista") }
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text.isNullOrBlank()) info = "A área de transferência está vazia." else import(text)
                }) { Text("Colar lista") }
                if (openFile != null) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { openFile.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Abrir arquivo") }
                // a planilha guarda o que foi apagado aqui: dá para trazer de volta sem buscar de novo
                if (s.keySource.isNotBlank()) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                    info = "Procurando na planilha…"
                    scope.launch {
                        runCatching { engine.deletedInSheet() }
                            .onSuccess { gone = it; info = if (it.isEmpty()) "Nenhum salvo apagado deste jogo na planilha." else null }
                            .onFailure { info = "Não li a planilha: ${it.message}" }
                    }
                }) { Text("Restaurar apagado") }
            }
            gone?.takeIf { it.isNotEmpty() }?.let { list ->
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Apagados que a planilha guarda:", style = MaterialTheme.typography.titleSmall)
                    list.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("${item.name.ifEmpty { "0x${item.addr}" }} · ${item.type} · último ${item.value}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { info = engine.restoreDeleted(item); gone = list - item }) { Text("Restaurar") }
                        }
                    }
                }
            }
            info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }
            if (s.restoreInfo.isNotEmpty()) Text(s.restoreInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            // jogo atualizado e algum salvo não voltou: a IA conserta sozinha, uma vez
            LaunchedEffect(s.gameUpdated, s.restoreInfo) {
                if (!s.gameUpdated || s.process == null || !Assistant.ready()) return@LaunchedEffect
                delay(8000)  // a restauração automática vai primeiro
                if (s.saved.any(engine::stale)) {
                    s.gameUpdated = false
                    s.aiAsk = "salvos" to "O jogo atualizou e alguns salvos (⚠) não voltaram. Use as notas e a classe de cada um para achar de novo e proponha as edições."
                }
            }
            KnownFields(engine)
            if (s.process != null && s.saved.any(engine::stale)) {
                TextButton(onClick = { run { engine.retryNow() } }, enabled = !s.busy) { Text("↻ Procurar os salvos ⚠ agora") }
                if (Assistant.ready()) TextButton(onClick = {
                    s.aiAsk = "salvos" to "Alguns salvos (⚠) não voltaram ao reabrir o jogo. Ache cada um de novo (pela classe, ponteiro, grupo ou busca) e proponha as edições."
                }) { Text("✨ Achar de novo com a IA") }
            }
            Recipes(engine, run, onInfo = { info = it })
            if (s.process != null) AiBox(
                engine, "Perguntar à IA sobre os salvos",
                listOf("Os ponteiros estão bons?", "Crie uma receita com os salvos", "Por que algum salvo não voltou?"),
                context = { engine.savedContext() }, key = "salvos",
            )
            }
        }
        if (s.process == null) {
            item {
                Text(
                    "Escolha o jogo primeiro: o Tinker mostra só os salvos dele e traz de volta os da última vez." +
                        if (s.saved.isNotEmpty()) " (Há ${s.saved.size} salvos de outro jogo ou da última vez.)" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }
        if (s.saved.isEmpty()) {
            item {
                Text(
                    "Nenhum endereço salvo. Na aba Busca, marque resultados e toque em \"Salvar\" ou \"Congelar\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected.containsAll(all), onCheckedChange = { selected = if (it) all else emptySet() })
                Text("${s.saved.size} salvos · ${s.saved.count { it.frozen }} congelados", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // calculadora de offset: marque dois e veja a distância entre eles
            if (chosen.size == 2) {
                val (a, b) = chosen
                val d = b.addr.toLong(16) - a.addr.toLong(16)
                Text("${b.name.ifEmpty { "0x" + b.addr }} = ${a.name.ifEmpty { "0x" + a.addr }} ${offsetText(d)} ($d bytes)" +
                    if (kotlin.math.abs(d) <= SAME_STRUCT) " · mesma ficha" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { engine.setFrozen(targets, true) }) { Text("Congelar $label") }
                FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { engine.setFrozen(targets, false) }) { Text("Descongelar $label") }
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { editingMany = !editingMany }, enabled = !s.busy) { Text("Editar $label") }
                TextButton(onClick = { engine.removeSaved(targets); selected = emptySet() }) { Text("Remover $label") }
                // receita: guarda os campos marcados pelo nome (classe IL2CPP) para aplicar com um toque, toda vez
                if (chosen.isNotEmpty()) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { naming = true }) { Text("Criar receita (${chosen.size})") }
                // dois marcados: ficha de um ao lado da do outro, como o "Structure compare" do Cheat Engine
                if (chosen.size == 2) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                    scope.launch { compareText = runCatching { engine.compareObjects(chosen[0], chosen[1]) }.getOrElse { "Não comparou: ${it.message}" } }
                }) { Text("Comparar (2)") }
            }
            compareText?.let { t ->
                Panel {
                    // as diferenças em destaque (como o Dissect data do Cheat Engine): o campo que muda costuma ser o valor
                    var onlyDiff by remember { mutableStateOf(false) }
                    Text("≠ = muda de um para o outro", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(selected = onlyDiff, onClick = { onlyDiff = !onlyDiff }, label = { Text("Só as diferenças") })
                    t.lines().filterIndexed { i, l -> i == 0 || !onlyDiff || '≠' in l }.forEach { l ->
                        Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = if ('≠' in l) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if ('≠' in l) androidx.compose.ui.text.font.FontWeight.Bold else null)
                    }
                    TextButton(onClick = { compareText = null }) { Text("Fechar") }
                }
            }
            if (naming) {
                // nome tem letras: teclado do telefone (o do Tinker só tem números)
                var name by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TinkerTextField(name, { name = it }, "Nome da receita", Modifier.weight(1f))
                    Button(shape = MaterialTheme.shapes.medium, onClick = { naming = false; info = engine.createRecipe(chosen, name); selected = emptySet() }) { Text("Criar") }
                    TextButton(onClick = { naming = false }) { Text("✕") }
                }
            }
        }
        if (editingMany) {
            item {
                Panel {
                    Text("O novo valor vai para ${if (chosen.isEmpty()) "todos os ${s.saved.size} salvos" else "os ${chosen.size} escolhidos"}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InlineEditor("", "Novo valor", onSave = { v -> editingMany = false; run { engine.setSaved(targets, v) } }, onCancel = { editingMany = false })
                }
            }
        }
        s.watchHits?.let { (label, hits) ->
            item(key = "watch") {
                Panel {
                    Text("O que escreve em $label", style = MaterialTheme.typography.titleSmall)
                    if (hits.isEmpty()) Text("Nada escreveu enquanto observava. Toque de novo e mude o valor no jogo nesses segundos.", style = MaterialTheme.typography.bodySmall)
                    hits.forEach { (pc, n, where) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$where · $n×", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            TextButton(onClick = { s.view = pc to "i32"; s.fichaMode = false; s.codeMode = true; s.tab = TAB_MEMORY }) { Text("Ver código") }
                        }
                    }
                    TextButton(onClick = { s.watchHits = null }) { Text("Fechar") }
                }
            }
        }
        s.traceHits?.let { (label, r) ->
            item(key = "trace") {
                Panel {
                    Text("Contador de chamadas: $label rodou ${r.first} vezes", style = MaterialTheme.typography.titleSmall)
                    if (r.first == 0) Text("Não rodou enquanto contava. Faça no jogo a ação que deveria chamar esse método.", style = MaterialTheme.typography.bodySmall)
                    r.second.forEach { (pc, n, where) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("chamado de $where · $n×", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            TextButton(onClick = { s.view = pc to "i32"; s.fichaMode = false; s.codeMode = true; s.tab = TAB_MEMORY }) { Text("Ver código") }
                        }
                    }
                    TextButton(onClick = { s.traceHits = null }) { Text("Fechar") }
                }
            }
        }
        // pastas que a pessoa criou (Materiais, Status…): os sem pasta primeiro
        s.saved.groupBy { it.folder }.toSortedMap().forEach { (folder, group) ->
        if (folder.isNotEmpty()) item(key = "pasta-$folder") {
            Text("📁 $folder (${group.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        items(group, key = { it.id }) { item ->
            val key = item.id
            val old = engine.stale(item)
            val open = editing == key
            ResultCard(
                shown(item.value, item.type), "0x${item.addr}" + when {
                    // a linha do endereço é estreita no painel: marcas curtas, explicadas ao abrir o salvo e na Ajuda
                    item.temp -> "\ncópia temporária do jogo: não volta ao reabrir"
                    old -> "\n⚠ de outra vez do jogo: busque de novo"
                    item.pointer.isNotEmpty() -> if (item.verified) " · 🔗 ✓ confirmado" else " · 🔗 a confirmar"
                    item.anchor.isNotEmpty() -> "\n↳ segue " + (s.saved.firstOrNull { it.id == item.anchor }?.let { it.name.ifEmpty { "0x" + it.addr } } ?: "?") + " " + offsetText(item.offset)
                    else -> ""
                }, "${TYPE_NAMES[item.type]} · ${item.region}",
                onClick = { editing = if (open) null else key },
                checked = key in selected, onCheck = { selected = if (it) selected + key else selected - key }, title = item.name,
                trailing = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 12.dp)) {
                        Switch(checked = item.frozen, onCheckedChange = { engine.setFrozen(listOf(item), it) }, enabled = !old || item.frozen)
                        Text("Congelar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            ) {
                if (open) {
                    InlineEditor(readable(item.value, item.type) ?: "", "Novo valor", onSave = { v -> editing = null; run { engine.setSaved(listOf(item), v) } }, onCancel = { editing = null }) {
                        TextButton(onClick = { engine.removeSaved(listOf(item)); editing = null }) { Text("Remover") }
                        TextButton(onClick = { s.view = item.addr to plain(item.type); s.fichaMode = false; s.codeMode = false; s.tab = TAB_MEMORY; editing = null }) { Text("Ver próximos") }
                        TextButton(onClick = { s.view = item.addr to plain(item.type); s.fichaMode = true; s.codeMode = false; s.tab = TAB_MEMORY; editing = null }) { Text("Explorar ficha") }
                        // todos os objetos do mesmo formato (no DYSMANTLE, todos os slots do inventário) vão para a Busca
                        TextButton(onClick = { editing = null; run { engine.findSimilar(item).also { s.tab = 0 } } }, enabled = !old && !s.busy) { Text("Achar parecidos") }
                        // qual instrução do jogo muda este valor (o painel sai da frente para a pessoa jogar)
                        TextButton(onClick = { editing = null; engine.watchLater(item); if (overlay) onMinimize() }, enabled = !old && !s.busy) { Text("O que escreve aqui?") }
                    }
                    // congelado: fixo, ou com regra (só subir: o jogo não tira, o que ganhar fica; só descer: o contrário)
                    if (item.frozen) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "❄ Fixo", "up" to "⬆ Só subir", "down" to "⬇ Só descer").forEach { (r, l) ->
                            FilterChip(selected = item.freezeRule == r, onClick = { engine.setFreezeRule(item, r) }, label = { Text(l) })
                        }
                    }
                    NameEditor(item.name) { engine.rename(item, it) }
                    FolderEditor(item.folder, s.saved.map { it.folder }.filter { it.isNotEmpty() }.distinct()) { engine.setFolder(item, it) }
                    historyText(item, System.currentTimeMillis()).takeIf { it.isNotEmpty() }?.let {
                        Text("Histórico: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Ler como outro tipo:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TypeChips(item.type) { t -> engine.changeType(item, t); editing = null }
                    if (item.anchor.isNotEmpty()) {
                        Text("Segue outro salvo pelo offset ${offsetText(item.offset)}: muda de endereço junto com ele, sem buscar de novo.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { engine.unlink(item) }) { Text("Desligar") }
                    } else PointerPanel(engine, item, run)
                }
            }
        }
        }
        // patches de código ficam à parte: são instruções do jogo, não valores
        if (s.patches.isNotEmpty()) {
            item(key = "patches") { Text("🛠 Patches de código (${s.patches.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(s.patches, key = { "patch-" + it.id }) { p ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.where, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = p.enabled, onCheckedChange = { on -> run { engine.setPatch(p, on) } }, enabled = !s.busy && s.process != null)
                    }
                    val first = { h: String -> decodeArm64(Integer.reverseBytes(h.take(8).toLong(16).toInt()), 0) }
                    Text("${first(p.orig)}  →  ${first(p.new)}" + if (p.new.length > 8) " (+${p.new.length / 8 - 1})" else "", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (p.note.isNotEmpty()) Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.suspect) Text("⚠ O jogo fechou logo depois deste patch: ficou desligado e não volta sozinho.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { engine.removePatch(p) }, enabled = !p.enabled) { Text("Remover") }
                }
            }
        }
    }
}

/** Receitas do jogo: aplicar com um toque, aplicar sozinha ao abrir, mudar valores, copiar para outra pessoa. */
@Composable
private fun Recipes(engine: Engine, run: (suspend () -> String) -> Unit, onInfo: (String) -> Unit) {
    val s = Session
    val context = LocalContext.current
    var open by remember { mutableStateOf(Session.recipes.isNotEmpty()) }  // com receitas, já abertas: Aplicar a um toque
    var editing by remember { mutableStateOf<String?>(null) }
    if (s.recipes.isEmpty()) return
    TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "Receitas (${s.recipes.size})") }
    if (!open) return
    s.recipes.toList().forEach { r ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("ao abrir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = r.auto, onCheckedChange = { engine.updateRecipe(r.copy(auto = it)) })
            }
            r.steps.forEachIndexed { i, st ->
                val key = r.id + i
                Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).clickable { editing = if (editing == key) null else key }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${st.label} · ${st.how}", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(st.value + if (st.freeze) " ❄" else "", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                }
                if (editing == key) {
                    InlineEditor(st.value, "Valor de ${st.label}", onSave = { v ->
                        editing = null
                        engine.updateRecipe(r.copy(steps = r.steps.toMutableList().also { it[i] = st.copy(value = normalizeNumbers(v)) }))
                    }, onCancel = { editing = null }) {
                        TextButton(onClick = {
                            engine.updateRecipe(r.copy(steps = r.steps.toMutableList().also { it[i] = st.copy(freeze = !st.freeze) }))
                        }) { Text(if (st.freeze) "Não congelar" else "Congelar ❄") }
                        TextButton(onClick = {
                            editing = null
                            engine.updateRecipe(r.copy(steps = r.steps.filterIndexed { j, _ -> j != i }))
                        }) { Text("Tirar da receita") }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(shape = MaterialTheme.shapes.medium, onClick = { run { engine.applyRecipe(r) } }, enabled = !s.busy && s.process != null) { Text("Aplicar") }
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Receita do Tinker", engine.exportRecipe(r)))
                    onInfo("Receita copiada. Quem colar em Salvos → Colar lista recebe ela.")
                }) { Text("Copiar") }
                TextButton(onClick = { engine.deleteRecipe(r) }) { Text("Apagar", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun FolderEditor(current: String, existing: List<String>, onSave: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TinkerTextField(name, { name = it }, "Pasta (ex.: Materiais)", Modifier.weight(1f))
        TextButton(onClick = { onSave(name) }, enabled = name != current) { Text("Mover") }
    }
    // pastas que já existem: um toque move para ela
    if (existing.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        existing.filter { it != current }.forEach { f -> AssistChip(onClick = { onSave(f) }, label = { Text("📁 $f") }) }
        if (current.isNotEmpty()) AssistChip(onClick = { onSave("") }, label = { Text("Sem pasta") })
    }
}

@Composable
private fun NameEditor(current: String, onSave: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TinkerTextField(name, { name = it }, "Nome (ex.: Ouro)", Modifier.weight(1f))
        TextButton(onClick = { onSave(name) }, enabled = name != current) { Text("Salvar nome") }
    }
}

@Composable
internal fun TypeChips(current: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TYPES.filter { it.first != "auto" }.forEach { (code, label) ->
            FilterChip(selected = code == current, onClick = { onPick(code) }, label = { Text(label) })
        }
    }
}
