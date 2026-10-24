@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
fun TinkerPanel(engine: Engine, overlay: Boolean, onMinimize: () -> Unit = {}, onOpenOverlay: () -> Unit = {}) {
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
            Header(overlay, onPick = { picking = true }, onMinimize = onMinimize, onOpenOverlay = onOpenOverlay, run = run)
            error?.let { Banner(it) { error = null } }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.padding(top = 4.dp))
            if (picking) {
                Picker(engine, onBack = { picking = false }) { p ->
                    picking = false
                    run { engine.attach(p) }
                }
                return@Column
            }
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

@Composable
private fun Header(overlay: Boolean, onPick: () -> Unit, onMinimize: () -> Unit, onOpenOverlay: () -> Unit, run: (suspend () -> String) -> Unit) {
    val s = Session
    val engine = Engine.get(LocalContext.current)
    val p = s.process
    var confirmKill by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(p, 40)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(p?.label ?: "Tinker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        p == null -> "Nenhum jogo escolhido"
                        s.paused -> "PID ${p.pid} · pausado"
                        else -> "PID ${p.pid}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onPick, enabled = !s.busy) { Text(if (p == null) "Escolher jogo" else "Trocar") }
            if (overlay) TextButton(onClick = onMinimize) { Text("Minimizar") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p != null) {
                OutlinedButton(onClick = { run { if (s.paused) engine.resume() else engine.pause() } }, enabled = !s.busy) {
                    Text(if (s.paused) "Continuar jogo" else "Pausar jogo")
                }
                OutlinedButton(onClick = {
                    if (confirmKill) run { engine.killGame() }
                    confirmKill = !confirmKill
                }, enabled = !s.busy) { Text(if (confirmKill) "Confirmar: fechar" else "Fechar jogo") }
            }
            if (!overlay) Button(onClick = onOpenOverlay) { Text("Menu flutuante") }
        }
        if (overlay) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transparência", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val enabled = s.process != null && !s.busy
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TYPES.forEachIndexed { i, (_, label) -> FilterChip(selected = s.type == i, onClick = { s.type = i }, label = { Text(label) }) }
                }
                ValueField(s.value, "Valor (ex.: 100)", { s.value = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { run { engine.search(s.type, s.value) } }, enabled = enabled && s.value.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Text("Nova busca")
                    }
                    FilledTonalButton(
                        onClick = { run { engine.refine(s.value) } },
                        enabled = enabled && (s.count ?: 0) > 0 && s.value.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Refinar") }
                }
                Text(s.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val count = s.count ?: return@LazyColumn
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$count resultados", style = MaterialTheme.typography.titleMedium)
                    if (count > s.results.size) {
                        Text("Mostrando ${s.results.size}. Refine para ver todos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { editingAll = !editingAll }, enabled = count > 0 && !s.busy) { Text("Editar todos") }
            }
        }
        if (editingAll) {
            item {
                Panel {
                    Text("O novo valor vai para os $count resultados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InlineEditor("", "Novo valor", onSave = { v -> editingAll = false; run { engine.writeAll(v) } }, onCancel = { editingAll = false })
                }
            }
        }
        items(s.results, key = { it.addr + it.type }) { hit ->
            val open = editing == hit.addr + hit.type
            ResultCard(hit.value, "0x${hit.addr}", "${TYPE_NAMES[hit.type]} · ${hit.region}", onClick = { editing = if (open) null else hit.addr + hit.type }) {
                if (open) {
                    InlineEditor(hit.value, "Novo valor", onSave = { v -> editing = null; run { engine.write(hit.addr, hit.type, v) } }, onCancel = { editing = null }) {
                        TextButton(onClick = { engine.addSaved(hit); editing = null }) { Text("Salvar na lista") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(value: String, addr: String, tag: String, onClick: () -> Unit, trailing: @Composable () -> Unit = {}, below: @Composable () -> Unit = {}) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
    LaunchedEffect(s.process) {
        while (true) {
            runCatching { engine.refreshSaved() }
            delay(1000)
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.saved.isEmpty()) {
            item {
                Text(
                    "Nenhum endereço salvo. Na aba Busca, toque num resultado e escolha \"Salvar na lista\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(s.saved, key = { it.addr + it.type }) { item ->
            val open = editing == item.addr + item.type
            ResultCard(
                item.value,
                "0x${item.addr}",
                "${TYPE_NAMES[item.type]} · ${item.region}",
                onClick = { editing = if (open) null else item.addr + item.type },
                trailing = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 12.dp)) {
                        Switch(checked = item.frozen, onCheckedChange = { engine.setFrozen(item, it) })
                        Text("Congelar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            ) {
                if (open) {
                    InlineEditor(item.value, "Novo valor", onSave = { v -> editing = null; run { engine.setSaved(item, v); "Valor salvo alterado." } }, onCancel = { editing = null }) {
                        TextButton(onClick = { engine.removeSaved(item); editing = null }) { Text("Remover") }
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
    fun fetch(work: suspend () -> Unit) = scope.launch { runCatching { work() }.onFailure { onError(it.message ?: "Erro") } }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Tudo que o Tinker faz fica registrado aqui. Se o jogo fechar, o motivo é anexado sozinho.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { fetch { engine.crashLog() } }) { Text("Falhas (crash)") }
            FilledTonalButton(onClick = { fetch { engine.gameLog() } }, enabled = Session.process != null) { Text("Logcat do jogo") }
            OutlinedButton(onClick = {
                if (overlay) onMinimize()  // o seletor de apps abre atrás do painel
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Registro do Tinker")
                    .putExtra(Intent.EXTRA_TEXT, Diag.export())
                context.startActivity(Intent.createChooser(send, "Compartilhar registro").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Compartilhar") }
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Registro do Tinker", Diag.export()))
            }) { Text("Copiar") }
            TextButton(onClick = { Diag.clear() }) { Text("Limpar") }
        }
        LazyColumn(Modifier.padding(top = 8.dp), reverseLayout = true) {
            items(Diag.lines.asReversed()) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
