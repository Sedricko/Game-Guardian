package dev.tinker.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TinkerScreen(engine: Engine) {
    val s = Session
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Hit?>(null) }
    var editingAll by remember { mutableStateOf(false) }

    // Toda ação: trava os botões, roda no daemon, atualiza a lista e mostra o erro se houver.
    fun run(work: suspend () -> String) {
        scope.launch {
            s.busy = true
            try {
                s.status = work()
                val (count, hits) = engine.list()
                s.count = count
                s.results = hits
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Erro desconhecido")
            } finally {
                s.busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tinker", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GameCard(s.process, enabled = !s.busy) { picking = true } }
            item {
                SearchCard(
                    enabled = s.process != null && !s.busy,
                    canRefine = (s.count ?: 0) > 0,
                    onSearch = { run { engine.search(s.type, s.value) } },
                    onRefine = { run { engine.refine(s.value) } },
                )
            }
            s.count?.let { count ->
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$count resultados", style = MaterialTheme.typography.titleMedium)
                            if (count > s.results.size) {
                                Text(
                                    "Mostrando ${s.results.size}. Refine para ver todos.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { editingAll = true }, enabled = count > 0 && !s.busy) { Text("Editar todos") }
                    }
                }
                items(s.results, key = { it.addr }) { hit -> HitRow(hit) { editing = hit } }
            }
        }
    }

    if (picking) {
        ProcessSheet(engine, onDismiss = { picking = false }) { p ->
            picking = false
            s.process = p
            run { engine.attach(p) }
        }
    }
    editing?.let { hit ->
        ValueDialog("Novo valor", "Endereço 0x${hit.addr}", hit.value, onDismiss = { editing = null }) { v ->
            editing = null
            run { engine.write(hit.addr, v) }
        }
    }
    if (editingAll) {
        ValueDialog("Editar todos", "O valor vai para os ${s.count} resultados.", "", onDismiss = { editingAll = false }) { v ->
            editingAll = false
            run { engine.writeAll(v) }
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
    if (p?.icon != null) {
        Image(p.icon, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text("?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun GameCard(p: GameProcess?, enabled: Boolean, onPick: () -> Unit) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(p, 48)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p?.label ?: "Nenhum jogo", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                p?.let { "${it.pkg} · PID ${it.pid}" } ?: "Abra o jogo e depois escolha aqui",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(onClick = onPick, enabled = enabled) { Text(if (p == null) "Escolher" else "Trocar") }
    }
}

@Composable
private fun SearchCard(enabled: Boolean, canRefine: Boolean, onSearch: () -> Unit, onRefine: () -> Unit) = Panel {
    val s = Session
    Text("Tipo de valor", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TYPES.forEachIndexed { i, (_, label) ->
            FilterChip(selected = s.type == i, onClick = { s.type = i }, label = { Text(label) })
        }
    }
    OutlinedTextField(
        value = s.value,
        onValueChange = { s.value = it },
        label = { Text("Valor") },
        placeholder = { Text("ex.: 100") },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSearch, enabled = enabled && s.value.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Nova busca") }
        FilledTonalButton(onClick = onRefine, enabled = enabled && canRefine && s.value.isNotBlank(), modifier = Modifier.weight(1f)) {
            Text("Refinar")
        }
    }
    if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(s.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HitRow(hit: Hit, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(hit.value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text(
                    "0x${hit.addr}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Editar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessSheet(engine: Engine, onDismiss: () -> Unit, onPick: (GameProcess) -> Unit) {
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Escolha o jogo", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Buscar app") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val list = apps
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text("Erro: $error", color = MaterialTheme.colorScheme.error)
                list.isEmpty() -> Text("Nenhum app aberto. Abra o jogo primeiro.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(list.filter { filter.isBlank() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }) { p ->
                        ListItem(
                            headlineContent = { Text(p.label) },
                            supportingContent = { Text(p.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { AppIcon(p, 40) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(p) },
                        )
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun ValueDialog(title: String, subtitle: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
