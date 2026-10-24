@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A tela inteira do Tinker. Usada na Activity e no painel flutuante, por isso não abre
 * diálogos nem bottom sheets (janelas extras não funcionam dentro de um overlay).
 */
@Composable
fun TinkerPanel(engine: Engine, overlay: Boolean, onMinimize: () -> Unit = {}, onOpenOverlay: () -> Unit = {}, onResize: () -> Unit = {}, onExit: () -> Unit = {}) {
    val s = Session
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Toda ação: trava os botões, roda no daemon, atualiza os resultados e mostra o erro se houver.
    val run: (suspend () -> String) -> Unit = { work ->
        scope.launch {
            s.busy = true
            error = null
            try {
                s.status = work()
                val (count, hits) = engine.list()
                s.count = count
                s.results = hits
            } catch (e: Exception) {
                val msg = e.message ?: "Erro desconhecido"
                error = if (msg.startsWith("valor inválido")) "$msg. Use um número (20000 ou 20.000), uma faixa (100~200) ou vários com ; (20000;50000)." else msg
            } finally {
                s.busy = false
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column {
            Header(overlay, onPick = { picking = true }, onMinimize = onMinimize, onOpenOverlay = onOpenOverlay, onExit = onExit, run = run)
            error?.let { Banner(it) { error = null } }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.padding(top = 4.dp))
            if (picking) {
                Picker(engine, onBack = { picking = false }) { p ->
                    picking = false
                    run { engine.attach(p) }
                }
            } else {
                TabBar()
                when (s.tab) {
                    0 -> SearchTab(engine, run)
                    1 -> SavedTab(engine, overlay, onMinimize, run)
                    TAB_MEMORY -> MemoryView(engine, run)
                    3 -> LogTab(engine, overlay, onMinimize) { error = it }
                    else -> SettingsTab(engine, overlay, onResize, run) { error = it }
                }
            }
        }
    }
}

/** Abas do Tinker: o Tab do Material tem padding fixo e cortava "Memória" e "Registro" em telas estreitas. */
@Composable
private fun TabBar() {
    val s = Session
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TABS.forEachIndexed { i, title ->
            val on = s.tab == i
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { s.tab = i }.padding(vertical = 10.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    title,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 14.sp),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = if (on) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
            }
        }
    }
    // indicador da aba ativa
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TABS.indices.forEach { i ->
            Box(Modifier.weight(1f).padding(horizontal = 14.dp).fillMaxWidth().height(3.dp)
                .background(if (s.tab == i) primary else MaterialTheme.colorScheme.background, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun Header(overlay: Boolean, onPick: () -> Unit, onMinimize: () -> Unit, onOpenOverlay: () -> Unit, onExit: () -> Unit, run: (suspend () -> String) -> Unit) {
    val s = Session
    val engine = Engine.get(LocalContext.current)
    val p = s.process
    val small = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(p, 32)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(p?.label ?: "Tinker", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        p == null -> "Nenhum jogo escolhido"
                        s.paused -> "PID ${p.pid} · pausado"
                        else -> "PID ${p.pid}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (s.paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // a linha 1 só tem o nome e Minimizar: botões aqui espremiam o nome em uma letra por linha
            if (overlay) TextButton(onClick = onMinimize, contentPadding = small) { Text("Minimizar") }
            TextButton(onClick = onExit, contentPadding = small) { Text("Sair", color = MaterialTheme.colorScheme.error) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (overlay) {
                // como o olho do GameGuardian: segure para ver o jogo, solte para voltar
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
                        .pointerInput(Unit) { detectTapGestures(onPress = { s.peek = true; tryAwaitRelease(); s.peek = false }) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("👁 Espiar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1) }
            }
            FilledTonalButton(onClick = onPick, enabled = !s.busy, contentPadding = small) { Text(if (p == null) "Escolher jogo" else "Trocar jogo", maxLines = 1) }
            if (p != null) {
                OutlinedButton(onClick = { run { if (s.paused) engine.resume() else engine.pause() } }, enabled = !s.busy, contentPadding = small) {
                    Text(if (s.paused) "Continuar" else "Pausar", maxLines = 1)
                }
            }
            if (!overlay) Button(onClick = onOpenOverlay, contentPadding = small) { Text("Menu flutuante", maxLines = 1) }
        }
    }
}

@Composable
private fun Banner(text: String, onClose: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onClose) { Text("OK") }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}

@Composable
private fun AppIcon(p: GameProcess?, size: Int) {
    val modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp))
    val icon = p?.icon
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text("T", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ValueField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Decimal) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Editor que abre dentro da própria lista, no lugar de um diálogo. */
@Composable
private fun InlineEditor(initial: String, label: String, onSave: (String) -> Unit, onCancel: () -> Unit, extra: @Composable () -> Unit = {}) {
    var text by remember { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueField(text, label, { text = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Salvar") }
            TextButton(onClick = onCancel) { Text("Cancelar") }
            extra()
        }
    }
}

@Composable
private fun SearchTab(engine: Engine, run: (suspend () -> String) -> Unit) {
    val s = Session
    var editing by remember { mutableStateOf<String?>(null) }
    var editingAll by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var nearOpen by remember { mutableStateOf(false) }
    val enabled = s.process != null && !s.busy
    // valores ao vivo, como no Cheat Engine: relê os resultados mostrados a cada segundo
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (!s.busy && s.count != null) runCatching { s.results = engine.list().second }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                }
                // teclado de texto: o numérico não tem o ~ da faixa (100~200)
                ValueField(s.value, "Valor (ex.: 100 ou faixa 100~200)", { s.value = it }, keyboard = KeyboardType.Ascii)
                if (s.history.isNotEmpty()) {
                    // últimas buscas: um toque repete valor e tipos
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.history.forEach { h ->
                            val types = if (h.types.isEmpty()) "" else " · " + h.types.joinToString("+") { TYPE_NAMES.getValue(it) }
                            AssistChip(onClick = { s.value = h.value; s.types = h.types }, label = { Text(h.value + types, maxLines = 1) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { run { engine.search(s.types, s.value) } }, enabled = enabled && s.value.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Text("Nova busca")
                    }
                    FilledTonalButton(
                        onClick = { run { engine.refine(s.value) } },
                        enabled = enabled && (s.count ?: 0) > 0 && s.value.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Refinar") }
                }
                if (s.canUndo) TextButton(onClick = { run { engine.undo() } }, enabled = enabled) { Text("↶ Desfazer último refino") }
                UnknownSearch(engine, enabled, run)
                Text(s.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { s.view = null; s.tab = TAB_MEMORY }, enabled = s.process != null) { Text("Ir para endereço…") }
            }
        }
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { engine.addSaved(targets); s.status = "${targets.size} salvos na lista." }) { Text("Salvar $label") }
                    FilledTonalButton(onClick = { engine.addSaved(targets, frozen = true); s.status = "${targets.size} congelados (veja Salvos)." }) { Text("Congelar $label") }
                    OutlinedButton(onClick = { editingAll = !editingAll }, enabled = !s.busy) { Text("Editar $label") }
                    OutlinedButton(onClick = { nearOpen = !nearOpen }, enabled = !s.busy) { Text("Buscar perto (${targets.size})") }
                }
            }
        }
        if (nearOpen && targets.isNotEmpty()) {
            item {
                // procura em volta de cada resultado escolhido (ou de todos os mostrados)
                NearSearch(engine, targets.map { it.addr }, targets.first().type, chooseType = true, onError = { s.status = it })
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
                }
            }
        }
        items(s.results, key = { it.addr + it.type }) { hit ->
            val key = hit.addr + hit.type
            val open = editing == key
            ResultCard(
                shown(hit.value, hit.type), "0x${hit.addr}", "${TYPE_NAMES[hit.type]} · ${hit.region}",
                onClick = { editing = if (open) null else key },
                checked = key in selected, onCheck = { selected = if (it) selected + key else selected - key },
            ) {
                if (open) {
                    InlineEditor(readable(hit.value, hit.type) ?: "", "Novo valor", onSave = { v -> editing = null; run { engine.write(hit.addr, hit.type, v) } }, onCancel = { editing = null }) {
                        TextButton(onClick = { engine.addSaved(listOf(hit)); editing = null }) { Text("Salvar na lista") }
                        TextButton(onClick = { s.view = hit.addr to hit.type; s.tab = TAB_MEMORY; editing = null }) { Text("Ver próximos") }
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
    Text("Não sabe o número? (barra de vida, energia…)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { run { engine.startUnknown(s.types) } }, enabled = enabled) { Text("Começar sem valor") }
        if ((s.count ?: 0) > 0) {
            listOf("inc" to "↑ Aumentou", "dec" to "↓ Diminuiu", "changed" to "≠ Mudou", "same" to "= Não mudou").forEach { (op, label) ->
                FilledTonalButton(onClick = { run { engine.compare(op) } }, enabled = enabled) { Text(label) }
            }
            // com um número no campo: quanto mudou (ex.: perdeu 10 de vida → Diminuiu 10)
            if (s.value.isNotBlank()) {
                OutlinedButton(onClick = { run { engine.compare("incby", s.value) } }, enabled = enabled) { Text("Aumentou ${s.value}", maxLines = 1) }
                OutlinedButton(onClick = { run { engine.compare("decby", s.value) } }, enabled = enabled) { Text("Diminuiu ${s.value}", maxLines = 1) }
            }
        }
    }
}

/** Ponteiros de um salvo, como o "Pointer scan" do Cheat Engine: o valor é achado de novo mesmo depois de reiniciar o jogo. */
@Composable
private fun PointerPanel(engine: Engine, item: SavedItem, run: (suspend () -> String) -> Unit) {
    val s = Session
    var maxOff by remember { mutableStateOf("4096") }
    var levels by remember { mutableStateOf("4") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Ponteiro (endereço que não muda ao reiniciar)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (item.pointer.isNotEmpty()) {
            Text(pointerText(item.pointer), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text("O Tinker segue esse caminho sozinho a cada leitura.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { engine.setPointer(item, "") }) { Text("Parar de usar o ponteiro") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(maxOff, { maxOff = it.filter(Char::isDigit) }, label = { Text("Distância máx. (bytes)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(levels, { levels = it.filter(Char::isDigit).take(1) }, label = { Text("Níveis") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.6f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { run { engine.scanPointers(item.addr, maxOff.toIntOrNull() ?: 4096, levels.toIntOrNull() ?: 4) } }, enabled = !s.busy && s.process != null) {
                Text("Buscar ponteiros")
            }
            // a lista é de outra vez do jogo (outro endereço): filtra pelo endereço novo do mesmo valor
            if (s.pointers.isNotEmpty() && s.pointersFor != item.addr) {
                OutlinedButton(onClick = { run { engine.filterPointers(item.addr) } }, enabled = !s.busy && s.process != null) { Text("Filtrar ${s.pointers.size} por este endereço") }
            }
        }
        if (s.pointerInfo.isNotEmpty()) Text(s.pointerInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.pointersFor == item.addr) {
            s.pointers.take(30).forEach { chain ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pointerText(chain), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { engine.setPointer(item, chain) }, enabled = chain != item.pointer) { Text(if (chain == item.pointer) "Em uso" else "Usar") }
                }
            }
            if (s.pointers.size > 30) Text("…e mais ${s.pointers.size - 30}. Filtre para sobrar menos.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResultCard(
    value: String, addr: String, tag: String, onClick: () -> Unit,
    checked: Boolean, onCheck: (Boolean) -> Unit, title: String = "",
    trailing: @Composable () -> Unit = {}, below: @Composable () -> Unit = {},
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheck)
                Column(Modifier.weight(1f)) {
                    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(addr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                trailing()
            }
            below()
        }
    }
}

@Composable
private fun SavedTab(engine: Engine, overlay: Boolean, onMinimize: () -> Unit, run: (suspend () -> String) -> Unit) {
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
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(s.process) {
        while (true) {
            runCatching { engine.refreshSaved() }
            delay(1000)
        }
    }
    val all = s.saved.map { it.addr + it.type }.toSet()
    val chosen = s.saved.filter { it.addr + it.type in selected }
    val targets = chosen.ifEmpty { s.saved.toList() }
    val label = if (chosen.isEmpty()) "todos" else "${chosen.size}"
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    val file = File(context.cacheDir, "reports").apply { mkdirs() }
                        .let { File(it, "tinker-salvos-${System.currentTimeMillis() / 1000}.json") }.apply { writeText(engine.exportSaved()) }
                    info = saveToDownloads(context, file, "application/json")?.let { "Salvo em Download/Tinker/${file.name}" } ?: "Lista pronta: ${file.name}"
                    if (overlay) onMinimize()  // o seletor de apps abre atrás do painel
                    shareFile(context, file, "application/json")
                }, enabled = s.saved.isNotEmpty()) { Text("Exportar") }
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Lista do Tinker", engine.exportSaved()))
                    info = "Lista copiada. Cole onde quiser."
                }, enabled = s.saved.isNotEmpty()) { Text("Copiar lista") }
                OutlinedButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text.isNullOrBlank()) info = "A área de transferência está vazia." else import(text)
                }) { Text("Colar lista") }
                if (openFile != null) OutlinedButton(onClick = { openFile.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Abrir arquivo") }
            }
            info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }
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
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { engine.setFrozen(targets, true) }) { Text("Congelar $label") }
                FilledTonalButton(onClick = { engine.setFrozen(targets, false) }) { Text("Descongelar $label") }
                OutlinedButton(onClick = { editingMany = !editingMany }, enabled = !s.busy) { Text("Editar $label") }
                TextButton(onClick = { engine.removeSaved(targets); selected = emptySet() }) { Text("Remover $label") }
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
        items(s.saved, key = { it.addr + it.type }) { item ->
            val key = item.addr + item.type
            val open = editing == key
            ResultCard(
                shown(item.value, item.type), "0x${item.addr}" + if (item.pointer.isNotEmpty()) " · 🔗 ponteiro" else "", "${TYPE_NAMES[item.type]} · ${item.region}",
                onClick = { editing = if (open) null else key },
                checked = key in selected, onCheck = { selected = if (it) selected + key else selected - key }, title = item.name,
                trailing = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 12.dp)) {
                        Switch(checked = item.frozen, onCheckedChange = { engine.setFrozen(listOf(item), it) })
                        Text("Congelar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            ) {
                if (open) {
                    InlineEditor(readable(item.value, item.type) ?: "", "Novo valor", onSave = { v -> editing = null; run { engine.setSaved(listOf(item), v) } }, onCancel = { editing = null }) {
                        TextButton(onClick = { engine.removeSaved(listOf(item)); editing = null }) { Text("Remover") }
                        TextButton(onClick = { s.view = item.addr to item.type; s.tab = TAB_MEMORY; editing = null }) { Text("Ver próximos") }
                    }
                    NameEditor(item.name) { engine.rename(item, it) }
                    Text("Ler como outro tipo:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TypeChips(item.type) { t -> engine.changeType(item, t); editing = null }
                    PointerPanel(engine, item, run)
                }
            }
        }
    }
}

@Composable
private fun NameEditor(current: String, onSave: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome (ex.: Ouro)") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { onSave(name) }, enabled = name != current) { Text("Salvar nome") }
    }
}

@Composable
private fun TypeChips(current: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TYPES.filter { it.first != "auto" }.forEach { (code, label) ->
            FilterChip(selected = code == current, onClick = { onPick(code) }, label = { Text(label) })
        }
    }
}

/** Visualizador de memória: valores em volta de um endereço, no tipo escolhido, ao vivo. */
@Composable
private fun MemoryView(engine: Engine, run: (suspend () -> String) -> Unit) {
    val s = Session
    val (center, type) = s.view ?: ("0" to "i32")
    var shift by remember(center) { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(listOf<Hit>()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var goTo by remember(center) { mutableStateOf(if (center == "0") "" else center) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(center, type, shift) {
        if (center == "0") return@LaunchedEffect
        while (true) {
            runCatching { rows = engine.nearby(center, type, shift); error = null }.onFailure { error = it.message }
            delay(1000)
        }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (center == "0") {
            Text(
                if (s.process == null) "Escolha o jogo primeiro." else "Digite um endereço ou use \"Ver próximos\" num resultado da Busca ou dos Salvos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = goTo, onValueChange = { goTo = it.removePrefix("0x").trim() }, label = { Text("Endereço (hex)") },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (goTo.toLongOrNull(16) != null) s.view = goTo.lowercase() to type else error = "Endereço inválido: $goTo" }) { Text("Ir") }
        }
        TypeChips(type) { t -> s.view = center to t }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (center != "0") NearSearch(engine, listOf(center), type, chooseType = false, onError = { error = it })
        if (center != "0") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shift-- }) { Text("▲ Anteriores") }
                OutlinedButton(onClick = { shift++ }) { Text("▼ Seguintes") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(rows, key = { it.addr }) { row ->
                val here = row.addr.toLong(16) == center.toLong(16) / TYPE_SIZES.getValue(type) * TYPE_SIZES.getValue(type)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (here) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { editing = if (editing == row.addr) null else row.addr }.padding(horizontal = 12.dp, vertical = 6.dp),
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
                        }
                    }
                }
            }
        }
    }
}

/**
 * Busca perto: valores (um ou vários com ;) nas N linhas acima e abaixo de cada endereço de partida.
 * Na Memória o tipo é o da tela; na Busca dá para escolher.
 */
@Composable
private fun NearSearch(engine: Engine, centers: List<String>, initialType: String, chooseType: Boolean, onError: (String) -> Unit) {
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
        runCatching { found = work() }.onFailure { onError(it.message ?: "Erro") }
        working = false
    }
    Panel {
        Text(if (centers.size > 1) "Buscar perto de ${centers.size} endereços" else "Buscar perto", style = MaterialTheme.typography.titleSmall)
        if (chooseType) TypeChips(chosen) { chosen = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = values, onValueChange = { values = it }, label = { Text("Valores (ex.: 50;75)") },
                singleLine = true, modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = radius, onValueChange = { radius = it.filter(Char::isDigit) }, label = { Text("± linhas") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { go { engine.searchNear(centers, type, radius.toIntOrNull() ?: 100, values) } }, enabled = values.isNotBlank() && !working) {
                Text("Buscar ${TYPE_NAMES[type]}")
            }
            // o valor mudou no jogo: digite o novo e a lista diminui
            if (!found.isNullOrEmpty()) FilledTonalButton(onClick = { go { engine.refineNear(found.orEmpty(), values) } }, enabled = values.isNotBlank() && !working) {
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
                        .clickable { open = if (open == hit.addr) null else hit.addr }.padding(horizontal = 12.dp, vertical = 6.dp),
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { engine.addSaved(listOf(hit.copy(region = "?"))); open = null }) { Text("Salvar") }
                            TextButton(onClick = { s.view = hit.addr to type; s.tab = TAB_MEMORY }) { Text("Ir para") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(engine: Engine, overlay: Boolean, onResize: () -> Unit, run: (suspend () -> String) -> Unit, onError: (String) -> Unit) {
    val s = Session
    val scope = rememberCoroutineScope()
    var confirmKill by remember { mutableStateOf(false) }
    LaunchedEffect(s.process) {
        if (s.process != null) runCatching { s.regions = engine.regions() }.onFailure { onError(it.message ?: "Erro") }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { HelpPanel() }
        item {
            Panel {
                Text("Busca", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pausar o jogo durante a busca e o refino", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = s.autoPause, onCheckedChange = { s.autoPause = it })
                }
            }
        }
        if (overlay) {
            item {
                Panel {
                    Text("Painel flutuante", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tamanho: ${PANEL_SIZES[s.panelSize].second}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onResize) { Text("Trocar") }
                    }
                    Text("Transparência", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = s.alpha, onValueChange = { s.alpha = it }, valueRange = 0.35f..1f)
                }
            }
        }
        if (s.process != null) {
            item {
                Panel {
                    Text("Jogo", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = {
                        if (confirmKill) run { engine.killGame() }
                        confirmKill = !confirmKill
                    }, enabled = !s.busy) { Text(if (confirmKill) "Toque de novo para fechar o jogo" else "Fechar jogo", maxLines = 1) }
                }
            }
        }
        item {
            Text("Regiões de memória", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                if (s.process == null) "Escolha o jogo para ver as regiões de memória."
                else "Marque onde a busca procura; menos regiões = busca mais rápida.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(s.regions, key = { it.code }) { r ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.code, Modifier.width(40.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
                    Column(Modifier.weight(1f)) {
                        Text(REGION_NAMES[r.code] ?: r.code, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.count} blocos · ${formatBytes(r.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Checkbox(checked = r.enabled, onCheckedChange = { on ->
                        scope.launch { runCatching { s.regions = engine.setRegion(r.code, on) }.onFailure { onError(it.message ?: "Erro") } }
                    })
                }
            }
        }
    }
}

private val HELP = listOf(
    "Como achar um valor" to "1. Escolha o jogo. 2. Digite o número que aparece na tela (ex.: moedas) e toque em Nova busca. " +
        "3. Faça o número mudar no jogo, digite o novo e toque em Refinar. 4. Repita até sobrar 1 ou poucos resultados.",
    "Tipos de valor" to "Auto procura Dword, Float, Qword e Double de uma vez. Se souber o tipo, marque só ele: a busca fica mais rápida " +
        "e com menos resultados. Vida e velocidade costumam ser Float; moedas, Dword.",
    "Faixa (100~200)" to "Use quando o jogo mostra o número arredondado ou abreviado (1.2K = 1150~1250) ou quando você não sabe o valor exato. " +
        "Faixas servem para buscar e refinar, não para escrever.",
    "Desfazer refino" to "Refinou com o número errado e sobrou 0? Toque em Desfazer para voltar à lista anterior. Tocar de novo refaz.",
    "Salvar e congelar" to "Marque os resultados e toque em Salvar ou Congelar. Congelar reescreve o valor várias vezes por segundo. " +
        "Na aba Salvos dá para dar nome, editar vários, exportar e importar. Cada jogo tem sua lista.",
    "Memória e Buscar perto" to "Em um resultado, toque em Ver próximos para ver os valores vizinhos. Buscar perto procura um valor " +
        "(ex.: a prata) nas linhas em volta de um ou vários endereços (ex.: o ouro).",
    "O jogo fechou" to "Quase sempre é edição num endereço que não era o valor certo, ou de outro tipo. Refine até sobrar 1 resultado " +
        "antes de editar e evite Qword/Double se o valor for Dword. O motivo da queda fica na aba Registro.",
    "Valor desconhecido" to "Para barras sem número (vida, energia): escolha o tipo (barra costuma ser Float) e toque em Começar sem valor. " +
        "Faça a barra mudar e toque em Aumentou ou Diminuiu; sem mudar nada, toque em Não mudou. Repita até sobrar pouco. " +
        "Com um número no campo dá para dizer quanto mudou (Diminuiu 10).",
    "Endereços mudaram" to "Quando o jogo reinicia, os endereços mudam. Para um salvo continuar valendo, use um ponteiro (abaixo).",
    "Ponteiros" to "Como no Cheat Engine. 1. Abra o salvo e toque em Buscar ponteiros. 2. Feche e abra o jogo, ache o mesmo valor de novo " +
        "e salve. 3. Nesse novo salvo, toque em Filtrar: ficam só os caminhos que continuam certos. 4. Toque em Usar. " +
        "Daí em diante o Tinker acha o endereço sozinho toda vez que o jogo abrir. Sem resultado? Aumente os níveis ou a distância.",
    "Reportar um problema" to "Na aba Registro, toque em Compartilhar relatório e envie o arquivo .txt. Ele tem tudo que é preciso para investigar.",
)

@Composable
private fun HelpPanel() {
    var open by remember { mutableStateOf<Int?>(null) }
    Panel {
        Text("Ajuda", style = MaterialTheme.typography.titleSmall)
        HELP.forEachIndexed { i, (title, body) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = if (open == i) null else i }.padding(vertical = 6.dp)) {
                Text((if (open == i) "▾ " else "▸ ") + title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                if (open == i) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun formatBytes(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

@Composable
private fun LogTab(engine: Engine, overlay: Boolean, onMinimize: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    fun fetch(work: suspend () -> Unit) = scope.launch {
        working = true
        runCatching { work() }.onFailure { onError(it.message ?: "Erro") }
        working = false
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Tudo que o Tinker faz fica registrado. Se o jogo fechar, o motivo é anexado sozinho. " +
                "O relatório junta registro, falhas do Android e logcat do jogo num arquivo .txt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                fetch {
                    val file = engine.report()
                    info = saveToDownloads(context, file)?.let { "Salvo em Download/Tinker/${file.name}" } ?: "Relatório pronto: ${file.name}"
                    if (overlay) onMinimize()  // o seletor de apps abre atrás do painel
                    shareFile(context, file)
                }
            }, enabled = !working) { Text("Compartilhar relatório") }
            FilledTonalButton(onClick = {
                fetch {
                    val text = engine.report().readText()
                    // a área de transferência do Android aguenta ~1 MB; fica o final, que tem o mais recente
                    val clip = if (text.length > 400_000) "(início cortado)\n" + text.takeLast(400_000) else text
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Relatório do Tinker", clip))
                    info = "Relatório copiado (${clip.length / 1024} KB). Cole onde quiser."
                }
            }, enabled = !working) { Text("Copiar tudo") }
            OutlinedButton(onClick = { fetch { engine.crashLog() } }, enabled = !working) { Text("Ver falhas") }
            OutlinedButton(onClick = { fetch { engine.gameLog() } }, enabled = !working && Session.process != null) { Text("Ver logcat do jogo") }
            TextButton(onClick = { Diag.clear(); info = null }) { Text("Limpar") }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        LazyColumn(Modifier.padding(top = 8.dp), reverseLayout = true) {
            items(Diag.lines.asReversed()) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Cópia em Download/Tinker para achar no gerenciador de arquivos. Só Android 10+, onde não precisa de permissão. */
private fun saveToDownloads(context: Context, file: File, mime: String = "text/plain"): Uri? {
    if (Build.VERSION.SDK_INT < 29) return null
    return runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Tinker")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        uri
    }.onFailure { Diag.add("Não salvou em Downloads: ${it.message}") }.getOrNull()
}

private fun shareFile(context: Context, file: File, mime: String = "text/plain") {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Relatório do Tinker")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, "Compartilhar ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

@Composable
private fun Picker(engine: Engine, onBack: () -> Unit, onPick: (GameProcess) -> Unit) {
    var apps by remember { mutableStateOf<List<GameProcess>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = try {
            engine.apps()
        } catch (e: Exception) {
            error = e.message
            emptyList()
        }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Escolha o jogo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Voltar") }
        }
        OutlinedTextField(value = filter, onValueChange = { filter = it }, placeholder = { Text("Buscar app") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val list = apps
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text("Erro: $error", color = MaterialTheme.colorScheme.error)
            list.isEmpty() -> Text("Nenhum app aberto. Abra o jogo primeiro.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list.filter { filter.isBlank() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(p) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(p, 40)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.label, style = MaterialTheme.typography.bodyLarge)
                            Text(p.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Valor como aparece na tela; NaN vira texto claro em vez do "-nan" cru. */
private fun shown(value: String, type: String) = readable(value, type) ?: "NaN (não é ${TYPE_NAMES[type]})"
