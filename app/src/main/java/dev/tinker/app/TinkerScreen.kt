@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
    var tab by remember { mutableIntStateOf(0) }
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
                error = e.message ?: "Erro desconhecido"
            } finally {
                s.busy = false
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column {
            Header(overlay, onPick = { picking = true }, onMinimize = onMinimize, onOpenOverlay = onOpenOverlay, onResize = onResize, onExit = onExit, run = run)
            error?.let { Banner(it) { error = null } }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.padding(top = 4.dp))
            if (picking) {
                Picker(engine, onBack = { picking = false }) { p ->
                    picking = false
                    run { engine.attach(p) }
                }
            } else if (s.view != null) {
                MemoryView(engine, run)
            } else {
                PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    listOf("Busca", "Salvos", "Regiões", "Registro").forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> SearchTab(engine, run)
                    1 -> SavedTab(engine, run)
                    2 -> RegionsTab(engine) { error = it }
                    else -> LogTab(engine, overlay, onMinimize) { error = it }
                }
            }
        }
    }
}

@Composable
private fun Header(overlay: Boolean, onPick: () -> Unit, onMinimize: () -> Unit, onOpenOverlay: () -> Unit, onResize: () -> Unit, onExit: () -> Unit, run: (suspend () -> String) -> Unit) {
    val s = Session
    val engine = Engine.get(LocalContext.current)
    val p = s.process
    var confirmKill by remember { mutableStateOf(false) }
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
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                OutlinedButton(onClick = {
                    if (confirmKill) run { engine.killGame() }
                    confirmKill = !confirmKill
                }, enabled = !s.busy, contentPadding = small) { Text(if (confirmKill) "Confirmar" else "Fechar jogo", maxLines = 1) }
            }
            if (overlay) {
                OutlinedButton(onClick = onResize, contentPadding = small) { Text("Tamanho: ${PANEL_SIZES[s.panelSize].second}", maxLines = 1) }
            } else {
                Button(onClick = onOpenOverlay, contentPadding = small) { Text("Menu flutuante", maxLines = 1) }
            }
        }
        if (overlay) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transparência", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Slider(value = s.alpha, onValueChange = { s.alpha = it }, valueRange = 0.35f..1f, modifier = Modifier.padding(start = 12.dp).weight(1f))
            }
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
private fun ValueField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Editor que abre dentro da própria lista, no lugar de um diálogo. */
@Composable
private fun InlineEditor(initial: String, label: String, onSave: (String) -> Unit, onCancel: () -> Unit, extra: @Composable () -> Unit = {}) {
    var text by remember { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueField(text, label, { text = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                ValueField(s.value, "Valor (ex.: 100)", { s.value = it })
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
                Text(s.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { s.view = "0" to "i32" }, enabled = s.process != null) { Text("Ir para endereço…") }
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { engine.addSaved(targets); s.status = "${targets.size} salvos na lista." }) { Text("Salvar $label") }
                    FilledTonalButton(onClick = { engine.addSaved(targets, frozen = true); s.status = "${targets.size} congelados (veja Salvos)." }) { Text("Congelar $label") }
                    OutlinedButton(onClick = { editingAll = !editingAll }, enabled = !s.busy) { Text("Editar $label") }
                }
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
                hit.value, "0x${hit.addr}", "${TYPE_NAMES[hit.type]} · ${hit.region}",
                onClick = { editing = if (open) null else key },
                checked = key in selected, onCheck = { selected = if (it) selected + key else selected - key },
            ) {
                if (open) {
                    InlineEditor(hit.value, "Novo valor", onSave = { v -> editing = null; run { engine.write(hit.addr, hit.type, v) } }, onCancel = { editing = null }) {
                        TextButton(onClick = { engine.addSaved(listOf(hit)); editing = null }) { Text("Salvar na lista") }
                        TextButton(onClick = { s.view = hit.addr to hit.type; editing = null }) { Text("Ver próximos") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    value: String, addr: String, tag: String, onClick: () -> Unit,
    checked: Boolean, onCheck: (Boolean) -> Unit,
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
                    Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    Text(addr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                trailing()
            }
            below()
        }
    }
}

@Composable
private fun SavedTab(engine: Engine, run: (suspend () -> String) -> Unit) {
    val s = Session
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
                Text("${s.saved.size} salvos · ${s.saved.count { it.frozen }} congelados", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                item.value, "0x${item.addr}", "${TYPE_NAMES[item.type]} · ${item.region}",
                onClick = { editing = if (open) null else key },
                checked = key in selected, onCheck = { selected = if (it) selected + key else selected - key },
                trailing = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 12.dp)) {
                        Switch(checked = item.frozen, onCheckedChange = { engine.setFrozen(listOf(item), it) })
                        Text("Congelar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            ) {
                if (open) {
                    InlineEditor(item.value, "Novo valor", onSave = { v -> editing = null; run { engine.setSaved(listOf(item), v) } }, onCancel = { editing = null }) {
                        TextButton(onClick = { engine.removeSaved(listOf(item)); editing = null }) { Text("Remover") }
                        TextButton(onClick = { s.view = item.addr to item.type; editing = null }) { Text("Ver próximos") }
                    }
                    Text("Ler como outro tipo:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TypeChips(item.type) { t -> engine.changeType(item, t); editing = null }
                }
            }
        }
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
    val (center, type) = s.view ?: return
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Memória", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { s.view = null }) { Text("Voltar") }
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
        if (center != "0") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row {
                        Text(row.addr, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.value, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    }
                    if (editing == row.addr) {
                        InlineEditor(row.value, "Novo valor (${TYPE_NAMES[type]})", onSave = { v -> editing = null; run { engine.write(row.addr, type, v) } }, onCancel = { editing = null }) {
                            TextButton(onClick = { engine.addSaved(listOf(row.copy(region = "?"))); editing = null }) { Text("Salvar") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionsTab(engine: Engine, onError: (String) -> Unit) {
    val s = Session
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.process) {
        if (s.process != null) runCatching { s.regions = engine.regions() }.onFailure { onError(it.message ?: "Erro") }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                if (s.process == null) "Escolha o jogo para ver as regiões de memória."
                else "Regiões de memória do jogo. Marque onde a busca procura; menos regiões = busca mais rápida.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(s.regions, key = { it.code }) { r ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.code, Modifier.width(40.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(REGION_NAMES[r.code] ?: r.code, style = MaterialTheme.typography.bodyLarge)
                        Text("${r.count} blocos · ${formatBytes(r.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked = r.enabled, onCheckedChange = { on ->
                        scope.launch { runCatching { s.regions = engine.setRegion(r.code, on) }.onFailure { onError(it.message ?: "Erro") } }
                    })
                }
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
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun saveToDownloads(context: Context, file: File): Uri? {
    if (Build.VERSION.SDK_INT < 29) return null
    return runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Tinker")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        uri
    }.onFailure { Diag.add("Não salvou em Downloads: ${it.message}") }.getOrNull()
}

private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Relatório do Tinker")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, "Compartilhar relatório").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
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
