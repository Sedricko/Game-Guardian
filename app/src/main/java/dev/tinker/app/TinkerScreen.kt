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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Toda ação: trava os botões, roda no daemon, atualiza os resultados e passa o erro (null = sem erro). */
@Composable
fun rememberRun(engine: Engine, onError: (String?) -> Unit): (suspend () -> String) -> Unit {
    val s = Session
    val scope = rememberCoroutineScope()
    return { work ->
        scope.launch {
            s.busy = true
            onError(null)
            try {
                s.status = work()
                val (count, hits) = engine.list()
                s.count = count
                s.results = hits
            } catch (e: Exception) {
                val msg = e.message ?: "Erro desconhecido"
                onError(if (msg.startsWith("valor inválido")) "$msg. Use um número (20000 ou 20.000), uma faixa (100~200) ou vários com ; (20000;50000)." else msg)
            } finally {
                s.busy = false
            }
        }
    }
}

/**
 * A tela inteira do Tinker. Usada na Activity e no painel flutuante, por isso não abre
 * diálogos nem bottom sheets (janelas extras não funcionam dentro de um overlay).
 */
@Composable
fun TinkerPanel(
    engine: Engine, overlay: Boolean, onMinimize: () -> Unit = {}, onOpenOverlay: () -> Unit = {}, onResize: () -> Unit = {}, onExit: () -> Unit = {},
    onQuickBar: (() -> Unit)? = null,
) {
    val s = Session
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // "o jogo fechou" de um comando já está no cartão "Reabrir o jogo?": não repete em vermelho
    val run = rememberRun(engine) { error = if (it == "o jogo fechou") null else it }
    // abriu o Tinker com o jogo da última vez aberto: já escolhe ele (e os salvos voltam sozinhos)
    LaunchedEffect(Unit) { if (s.process == null) runCatching { engine.followGame(startDaemon = true) }.getOrNull()?.let { s.status = it } }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column {
            Header(overlay, onPick = { picking = true }, onMinimize = onMinimize, onOpenOverlay = onOpenOverlay, onExit = onExit, run = run, onQuickBar = onQuickBar)
            error?.let { Banner(it) { error = null } }
            s.gameGone?.let { gone ->
                // como o GameGuardian: o jogo fechou, pergunta se reabre
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("${gone.label} fechou. Reabrir o jogo?", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.titleSmall)
                        Text(if (s.gameGoneByUser) "Ao reabrir, os salvos voltam sozinhos." else "O motivo da queda fica na aba Registro. Ao reabrir, os salvos voltam sozinhos.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { s.status = engine.relaunch(); if (overlay) onMinimize() }) { Text("Reabrir") }
                            TextButton(onClick = { s.gameGone = null }) { Text("Agora não") }
                        }
                    }
                }
            }
            s.update?.let { (version, url) ->
                val context = LocalContext.current
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tinker $version disponível", Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            if (overlay) onMinimize()
                        }) { Text("Baixar") }
                        TextButton(onClick = { s.update = null }) { Text("Depois") }
                    }
                }
            }
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
private fun Header(
    overlay: Boolean, onPick: () -> Unit, onMinimize: () -> Unit, onOpenOverlay: () -> Unit, onExit: () -> Unit, run: (suspend () -> String) -> Unit,
    onQuickBar: (() -> Unit)?,
) {
    val s = Session
    val engine = Engine.get(LocalContext.current)
    val p = s.process
    // uma linha só, como no GameGuardian: o resto da tela fica para a lista; tocar no nome troca o jogo
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = !s.busy, onClick = onPick).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(p, 30)
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(p?.label ?: "Escolher jogo ▾", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        p == null -> "toque para escolher"
                        s.paused -> "pausado · toque para trocar"
                        else -> "PID ${p.pid}" + (s.engine?.let { " · $it" } ?: "") + " · toque para trocar"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (s.paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (overlay) {
            // como o olho do GameGuardian: segure para ver o jogo, solte para voltar
            HeaderButton("👁", "Espiar (segure)", Modifier.pointerInput(Unit) { detectTapGestures(onPress = { s.peek = true; tryAwaitRelease(); s.peek = false }) })
        }
        if (p != null) HeaderButton(if (s.paused) "▶" else "⏸", if (s.paused) "Continuar o jogo" else "Pausar o jogo", enabled = !s.busy) {
            run { if (s.paused) engine.resume() else engine.pause() }
        }
        // barra pequena sobre o jogo: refinar sem abrir tudo
        if (onQuickBar != null) HeaderButton("⚡", "Barra rápida", onClick = onQuickBar)
        if (!overlay) HeaderButton("⧉", "Menu flutuante", onClick = onOpenOverlay)
        if (overlay) HeaderButton("—", "Minimizar", onClick = onMinimize)
        HeaderButton("✕", "Sair", color = MaterialTheme.colorScheme.error, onClick = onExit)
    }
}

/** Botão pequeno do cabeçalho: símbolo grande, nome para leitor de tela. */
@Composable
private fun HeaderButton(
    symbol: String, description: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary, onClick: (() -> Unit)? = null,
) {
    Box(
        modifier.size(42.dp).clip(RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, fontSize = 20.sp, color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant) }
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
    // teclado do Tinker (Ajustes): o campo não chama o teclado do telefone, que cobre o jogo; tocar abre o do Tinker
    val own = Session.ownKeypad
    var pad by remember { mutableStateOf(false) }
    val taps = remember { MutableInteractionSource() }
    val pressed by taps.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed && own) pad = true }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            readOnly = own,
            interactionSource = taps,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
        if (own && pad) NumPad(onKey = { onChange(applyKey(value, it)) }, onDone = { pad = false })
    }
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
    var names by remember { mutableStateOf(mapOf<String, String>()) }
    var more by remember { mutableStateOf(false) }
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
                // com resultados: refinar pelo jeito que mudou, sem precisar do número
                if ((s.count ?: 0) > 0) CompareButtons(engine, enabled, run)
                Text(s.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // o resto fica recolhido: a tela pequena do menu flutuante sobra para os resultados
                TextButton(onClick = { more = !more }) { Text((if (more) "▾ " else "▸ ") + "Mais formas de buscar") }
                if (more) {
                    UnknownSearch(engine, enabled, run)
                    AobSearch(engine, enabled, run)
                    TextButton(onClick = { s.view = null; s.tab = TAB_MEMORY }, enabled = s.process != null) { Text("Ir para endereço…") }
                }
                if (s.process != null) AiBox(
                    engine, "Perguntar à IA sobre esta busca",
                    listOf("Qual desses é o valor certo?", "Por que a busca não acha o valor?", "Qual o próximo passo?"),
                    context = { engine.searchContext(names) },
                )
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
                }
            }
        }
        // com poucos resultados, diz qual campo do jogo cada um é (IL2CPP): o dado de verdade x a cópia que aparece na tela
        item {
            LaunchedEffect(if (count <= 20) s.results.joinToString { it.addr } else "") {
                names = if (count in 1..20) runCatching { engine.il2Names(s.results.map { it.addr }) }.getOrDefault(emptyMap()) else emptyMap()
            }
        }
        // normal colado num 🔒 com o mesmo número (até 32 bytes, a mesma estrutura do antitrapaça): é cópia ou isca; editar o 🔒
        val cipher = s.results.filter { it.type.startsWith("x") }.map { it.addr.toLong(16) to readable(it.value, it.type) }
        items(s.results, key = { it.addr + it.type }) { hit ->
            val key = hit.addr + hit.type
            val open = editing == key
            val copy = !hit.type.startsWith("x") && readable(hit.value, hit.type).let { v ->
                cipher.any { (a, cv) -> cv == v && kotlin.math.abs(a - hit.addr.toLong(16)) <= 32 }
            }
            ResultCard(
                shown(hit.value, hit.type), "0x${hit.addr}" + if (copy) " · provável cópia: edite o 🔒" else "", "${TYPE_NAMES[hit.type]} · ${hit.region}",
                onClick = { editing = if (open) null else key },
                checked = key in selected, onCheck = { selected = if (it) selected + key else selected - key }, title = names[hit.addr].orEmpty(),
            ) {
                if (open) {
                    InlineEditor(readable(hit.value, hit.type) ?: "", "Novo valor", onSave = { v -> editing = null; run { engine.write(hit.addr, hit.type, v) } }, onCancel = { editing = null }) {
                        TextButton(onClick = { engine.addSaved(listOf(hit)); editing = null }) { Text("Salvar na lista") }
                        TextButton(onClick = { s.view = hit.addr to plain(hit.type); s.fichaMode = false; s.tab = TAB_MEMORY; editing = null }) { Text("Ver próximos") }
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
    OutlinedButton(onClick = { run { engine.startUnknown(s.types) } }, enabled = enabled) { Text("Começar sem valor") }
}

/** Refinar pelo jeito que o valor mudou: ↑ ↓ = ≠ e, com um número no campo, quanto mudou. */
@Composable
private fun CompareButtons(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    val s = Session
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("inc" to "↑ Aumentou", "dec" to "↓ Diminuiu", "changed" to "≠ Mudou", "same" to "= Não mudou").forEach { (op, label) ->
            FilledTonalButton(onClick = { run { engine.compare(op) } }, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label, maxLines = 1) }
        }
        // com um número no campo: quanto mudou (ex.: perdeu 10 de vida → Diminuiu 10)
        if (s.value.isNotBlank()) {
            OutlinedButton(onClick = { run { engine.compare("incby", s.value) } }, enabled = enabled) { Text("+${s.value}", maxLines = 1) }
            OutlinedButton(onClick = { run { engine.compare("decby", s.value) } }, enabled = enabled) { Text("−${s.value}", maxLines = 1) }
        }
    }
}

/** Busca por sequência de bytes (AOB), com ?? como curinga; nos dados ou no código do jogo. */
@Composable
private fun AobSearch(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "Busca por bytes (AOB)") }
    if (!open) return
    OutlinedTextField(
        pattern, { pattern = it }, label = { Text("Bytes (ex.: 1F 20 03 D5 ?? ?? 00 B9)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !code, onClick = { code = false }, label = { Text("Dados") })
        FilterChip(selected = code, onClick = { code = true }, label = { Text("Código") })
        Button(onClick = { run { engine.searchAob(pattern, code) } }, enabled = enabled && pattern.isNotBlank()) { Text("Buscar bytes") }
    }
}

/** Ponteiros de um salvo, como o "Pointer scan" do Cheat Engine: o valor é achado de novo mesmo depois de reiniciar o jogo. */
@Composable
private fun PointerPanel(engine: Engine, item: SavedItem, run: (suspend () -> String) -> Unit) {
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
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
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
            OutlinedTextField(maxOff, { maxOff = it.filter(Char::isDigit) }, label = { Text("Distância máx. (bytes)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(levels, { levels = it.filter(Char::isDigit).take(1) }, label = { Text("Níveis") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.6f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { run { engine.scanPointers(item, maxOff.toIntOrNull() ?: 4096, levels.toIntOrNull() ?: 4) } }, enabled = !s.busy && s.process != null && !engine.stale(item)) {
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
                OutlinedTextField(now, { now = it }, label = { Text("Valor agora no jogo") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { run { engine.filterPointersByValue(item, now) } }, enabled = !s.busy && s.process != null && now.isNotBlank()) {
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
                    Text(addr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
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
    var naming by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
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
            if (s.restoreInfo.isNotEmpty()) Text(s.restoreInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            if (s.process != null && s.saved.any(engine::stale)) {
                TextButton(onClick = { run { engine.retryNow() } }, enabled = !s.busy) { Text("↻ Procurar os salvos ⚠ agora") }
            }
            Recipes(engine, run, onInfo = { info = it })
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { engine.setFrozen(targets, true) }) { Text("Congelar $label") }
                FilledTonalButton(onClick = { engine.setFrozen(targets, false) }) { Text("Descongelar $label") }
                OutlinedButton(onClick = { editingMany = !editingMany }, enabled = !s.busy) { Text("Editar $label") }
                TextButton(onClick = { engine.removeSaved(targets); selected = emptySet() }) { Text("Remover $label") }
                // receita: guarda os campos marcados pelo nome (classe IL2CPP) para aplicar com um toque, toda vez
                if (chosen.isNotEmpty()) OutlinedButton(onClick = { naming = true }) { Text("Criar receita (${chosen.size})") }
            }
            if (naming) {
                // nome tem letras: teclado do telefone (o do Tinker só tem números)
                var name by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome da receita") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { naming = false; info = engine.createRecipe(chosen, name); selected = emptySet() }) { Text("Criar") }
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
        items(s.saved, key = { it.id }) { item ->
            val key = item.id
            val old = engine.stale(item)
            val open = editing == key
            ResultCard(
                shown(item.value, item.type), "0x${item.addr}" + when {
                    // a linha do endereço é estreita no painel: marcas curtas, explicadas ao abrir o salvo e na Ajuda
                    item.temp -> "\ncópia temporária do jogo: não volta ao reabrir"
                    old -> "\n⚠ de outra vez do jogo: busque de novo"
                    item.pointer.isNotEmpty() -> if (item.verified) " · 🔗" else " · 🔗 a confirmar"
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
                        TextButton(onClick = { s.view = item.addr to plain(item.type); s.fichaMode = false; s.tab = TAB_MEMORY; editing = null }) { Text("Ver próximos") }
                        TextButton(onClick = { s.view = item.addr to plain(item.type); s.fichaMode = true; s.tab = TAB_MEMORY; editing = null }) { Text("Explorar ficha") }
                    }
                    NameEditor(item.name) { engine.rename(item, it) }
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
}

/** Receitas do jogo: aplicar com um toque, aplicar sozinha ao abrir, mudar valores, copiar para outra pessoa. */
@Composable
private fun Recipes(engine: Engine, run: (suspend () -> String) -> Unit, onInfo: (String) -> Unit) {
    val s = Session
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
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
                Row(Modifier.fillMaxWidth().clickable { editing = if (editing == key) null else key }, verticalAlignment = Alignment.CenterVertically) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run { engine.applyRecipe(r) } }, enabled = !s.busy && s.process != null) { Text("Aplicar") }
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Receita do Tinker", engine.exportRecipe(r)))
                    onInfo("Receita copiada. Quem colar em Salvos → Colar lista recebe ela.")
                }) { Text("Copiar") }
                TextButton(onClick = { engine.deleteRecipe(r) }) { Text("Apagar", color = MaterialTheme.colorScheme.error) }
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
    var near by remember { mutableStateOf(false) }
    // o que mudou desde a leitura anterior fica destacado por 3 s: ajuda a ver o que reage ao que você faz no jogo
    val changed = remember(center, type) { mutableStateMapOf<String, Long>() }
    val last = remember(center, type) { HashMap<String, String>() }
    LaunchedEffect(center, type, shift, s.fichaMode) {
        if (center == "0" || s.fichaMode) return@LaunchedEffect
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
            }.onFailure { error = it.message }
            delay(1000)
        }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = !s.fichaMode, onClick = { s.fichaMode = false }, label = { Text("Endereços") })
            FilterChip(selected = s.fichaMode, onClick = { s.fichaMode = true }, label = { Text("Ficha do objeto") })
        }
        if (s.fichaMode) {
            FichaView(engine, center, run)
            return@Column
        }
        if (center == "0") {
            Text(
                if (s.process == null) "Escolha o jogo primeiro." else "Digite um endereço ou use \"Ver próximos\" num resultado da Busca ou dos Salvos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        // endereço, Ir e ▲▼ numa linha; Buscar perto recolhido: a lista fica com o resto da tela
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = goTo, onValueChange = { goTo = it.removePrefix("0x").trim() }, label = { Text("Endereço (7a0010+8)") },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                val a = parseAddress(goTo)
                if (a != null) s.view = a to type else error = "Endereço inválido: $goTo"
            }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Ir") }
            if (center != "0") {
                HeaderButton("▲", "Anteriores") { shift-- }
                HeaderButton("▼", "Seguintes") { shift++ }
            }
        }
        TypeChips(type) { t -> s.view = center to t }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (center != "0") {
            TextButton(onClick = { near = !near }) { Text((if (near) "▾ " else "▸ ") + "Buscar perto deste endereço") }
            if (near) NearSearch(engine, listOf(center), type, chooseType = false, onError = { error = it })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(rows, key = { it.addr }) { row ->
                val here = row.addr.toLong(16) == center.toLong(16) / TYPE_SIZES.getValue(type) * TYPE_SIZES.getValue(type)
                val hot = (changed[row.addr] ?: 0L) > System.currentTimeMillis() - 3000
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(when {
                            here -> MaterialTheme.colorScheme.primaryContainer
                            hot -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        })
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
    } else ClassOpener(engine)
    if (center == "0") {
        Text("Digite o nome de uma classe do jogo (ex.: PlayerData) ou abra a ficha de um salvo (Salvos → Explorar ficha).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    FichaBody(engine, center, run)
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
            }.onFailure { info = it.message }
            loading = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(cls, { cls = it.trim() }, label = { Text("Classe (ex.: PlayerData)") }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = { open(cls) }, enabled = !loading && s.process != null && cls.isNotBlank(), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Abrir") }
        if (cls.isNotBlank()) TextButton(onClick = { copy(cls) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("⧉", fontSize = 18.sp) }
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
                    .onFailure { info = it.message; listing = false }
                loading = false
            }
        }
    }, enabled = s.process != null) { Text((if (listing) "▾ " else "▸ ") + "Ver todas as classes do jogo") }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val names = remember(all) { all?.map { it.name }?.toSet() ?: emptySet() }
    AiBox(
        engine, "Perguntar à IA onde está o valor",
        listOf("Onde fica o ouro ou a moeda?", "Quais classes guardam vida, gemas e recursos?", "Onde ficam os itens do inventário?"),
        context = { engine.classesContext(all ?: engine.listClasses().also { all = it }) }, names = names, onOpen = { open(it) },
    )
    val shownClasses = all?.takeIf { listing }?.filter { cls.isBlank() || it.name.contains(cls, ignoreCase = true) }
    if (shownClasses != null) {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shownClasses.take(200).forEach { gc ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { listing = false; open(gc.name) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(gc.name.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if ('.' in gc.name) Text(gc.name.substringBeforeLast('.'), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${gc.fields} campos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { copy(gc.name) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("⧉", fontSize = 16.sp) }
                }
            }
            if (shownClasses.size > 200) Text("…e mais ${shownClasses.size - 200}: digite parte do nome para filtrar.", style = MaterialTheme.typography.bodySmall)
            if (shownClasses.isEmpty()) Text("Nenhuma classe com \"$cls\".", style = MaterialTheme.typography.bodySmall)
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
            }.onFailure { error = it.message }
            delay(1000)
        }
    }
    val c = il2
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { page-- }) { Text("▲ Antes") }
            OutlinedButton(onClick = { page++ }) { Text("▼ Depois") }
        }
    }
    if (c != null && c.fields.size > 6) {
        OutlinedTextField(filter, { filter = it }, label = { Text("Filtrar campo (ex.: gem)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    items?.let { list ->
        Text("Lista com ${list.size} itens${if (list.size == 200) " (mostrando os 200 primeiros)" else ""}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.take(40).forEach { it2 ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable(enabled = it2.isRef) { s.view = it2.addr to "i32" }.padding(horizontal = 12.dp, vertical = 6.dp),
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows.filter { filter.isBlank() || it.label.contains(filter.trim(), ignoreCase = true) }, key = { it.addr + it.label }) { r ->
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
                    .clickable { open = if (open == r.addr) null else r.addr }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(offsetText(r.off), Modifier.width(64.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(r.label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.value, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (open == r.addr) {
                    Text("0x${r.addr}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.type.isNotEmpty()) {
                            // entra nos Salvos já ligado pelo offset aos outros da mesma ficha, com nome do campo quando é IL2CPP
                            FilledTonalButton(onClick = { engine.addSaved(listOf(Hit(r.addr, r.type, "?", r.value))); s.status = "Salvo: ${r.label}"; open = null }) { Text("Salvar") }
                            // congela já no valor de agora (vira salvo congelado, com o nome do campo)
                            OutlinedButton(onClick = { engine.addSaved(listOf(Hit(r.addr, r.type, "?", r.value)), frozen = true); s.status = "Congelado: ${r.label}"; open = null }) { Text("Congelar") }
                        }
                        if (r.value.startsWith("0x")) {
                            // ponteiro: abre a ficha do objeto para onde ele aponta
                            OutlinedButton(onClick = { s.view = r.value.removePrefix("0x") to "i32"; open = null }) { Text("Abrir o objeto") }
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
        // organizado por assunto; a Ajuda fica no fim
        item {
            Panel {
                Text("Menu flutuante", style = MaterialTheme.typography.titleSmall)
                Toggle("Abrir o menu flutuante ao iniciar", "Como o GameGuardian: abrir o Tinker já deixa a bolinha sobre o jogo.", s.autoOverlay) {
                    s.autoOverlay = it; engine.saveSettings()
                }
                if (overlay) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tamanho do painel: ${PANEL_SIZES[s.panelSize].second}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onResize) { Text("Trocar") }
                    }
                    Text("Transparência", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = s.alpha, onValueChange = { s.alpha = it }, onValueChangeFinished = engine::saveSettings, valueRange = 0.35f..1f)
                }
            }
        }
        item {
            Panel {
                Text("Busca", style = MaterialTheme.typography.titleSmall)
                Toggle("Pausar o jogo durante a busca e o refino", "O valor não muda no meio da busca.", s.autoPause) { s.autoPause = it; engine.saveSettings() }
                Toggle("Teclado do Tinker", "Números, ~ e ; dentro do painel; o teclado do telefone abre por cima do jogo e esconde o valor.", s.ownKeypad) {
                    s.ownKeypad = it; engine.saveSettings()
                }
                Toggle("Procurar valores cifrados (🔒)", "Acha moedas e vida protegidas com chave (comum em jogos Unity). Deixa a busca um pouco mais lenta.", s.findEncrypted) {
                    s.findEncrypted = it; engine.saveSettings()
                }
            }
        }
        item {
            Panel {
                Text("Ponteiros e restauração", style = MaterialTheme.typography.titleSmall)
                Text("Ao reabrir o jogo, os salvos voltam sozinhos pelo ponteiro, pelo consenso dos caminhos, pelo histórico do valor e pela assinatura.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Preparar ponteiros ao salvar", "Para o salvo voltar sozinho quando o jogo reabrir. Pausa o jogo alguns segundos depois de salvar.", s.autoPointers) {
                    s.autoPointers = it; engine.saveSettings()
                }
                Toggle("Ponteiros só nas libs do jogo", "Base em libil2cpp, libunity e outras do jogo; as do sistema costumam dar caminhos que quebram.", s.gameLibsOnly) {
                    s.gameLibsOnly = it; engine.saveSettings()
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
        item {
            Panel {
                Text("Assistente (IA)", style = MaterialTheme.typography.titleSmall)
                Text("O botão ✨ na Busca, na Ficha, na lista de classes e no Registro manda o que está na tela para o Claude e mostra a resposta: " +
                    "onde está o valor, o que cada campo faz, o que deu errado. Precisa de internet e de uma chave da API (console.anthropic.com), " +
                    "que é paga por uso. Nada é enviado sem você tocar em Perguntar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    s.aiKey, { s.aiKey = it.trim(); engine.saveSettings() }, label = { Text("Chave da API (sk-ant-…)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                if (s.aiKey.isNotBlank()) TextButton(onClick = { s.aiKey = ""; engine.saveSettings() }) { Text("Apagar chave") }
            }
        }
        item { HelpPanel() }
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
    "Valores cifrados (🔒)" to "Muitos jogos guardam moedas e vida trancadas com uma chave, para ninguém achar o número. A busca já procura " +
        "esses valores sozinha e mostra o número real com 🔒. Ao editar ou congelar um 🔒, o Tinker tranca o valor novo com a mesma chave, " +
        "como o jogo faria. Se aparecer o mesmo número normal e com 🔒, edite o 🔒: o normal costuma ser só uma cópia (às vezes uma isca do antitrapaça).",
    "Menu flutuante" to "Abrir o Tinker já abre a bolinha sobre o jogo e tira o app da frente, como no GameGuardian (dá para desligar em " +
        "Ajustes). Toque na bolinha para abrir o painel. No topo: toque no nome do jogo para trocar; 👁 (segure) espia o jogo; ⏸/▶ pausa; " +
        "⚡ barra rápida; — minimiza; ✕ sai. Os ajustes ficam guardados.",
    "Barra rápida" to "No menu flutuante, toque em Barra rápida: uma barra pequena fica sobre o jogo, com o campo de valor, Refinar e ↑ ↓ = ≠ " +
        "(aumentou, diminuiu, não mudou, mudou). Arraste pelo ⠿. ▢ abre o painel completo para editar e congelar; – volta para a bolinha, " +
        "e a bolinha passa a abrir a barra.",
    "Offset e ficha" to "Valores do mesmo objeto do jogo (ouro, gema, energia) ficam sempre à mesma distância (offset). Quando um salvo ganha " +
        "ponteiro, os salvos a até 4 KB dele passam a segui-lo sozinhos (↳ Ouro +0x8): ao reabrir o jogo, achando um, os outros vêm juntos. " +
        "Marque dois salvos para ver a distância entre eles. Na aba Memória dá para ir a 7a0010+8.",
    "Busca por bytes (AOB)" to "Na aba Busca, abra Busca por bytes: digite pares hex com ?? como curinga (1F 20 ?? D5). Dados procura na memória " +
        "do jogo; Código procura nas instruções. Os lugares achados viram resultados normais.",
    "Qual resultado salvar" to "Com até 20 resultados, em jogo Unity IL2CPP, cada um mostra o campo do jogo (ex.: PlayerData.UpgradeStone). " +
        "\"Cópia temporária (Int32)\" é o número que o jogo cria só para mostrar na tela: editar não muda nada e some ao fechar. Salve o campo de verdade.",
    "Receitas" to "Nos Salvos, marque os campos e toque em Criar receita. A receita guarda cada campo pelo nome da classe (jogo Unity " +
        "IL2CPP), senão pelo ponteiro (🎯), senão pela assinatura que o Tinker aprende sozinho, nunca pelo endereço: Aplicar escreve todos os valores de uma vez (e congela os ❄), em qualquer vez que o " +
        "jogo abrir. Ligue \"ao abrir\" para aplicar sozinha quando o jogo carregar. Copiar manda a receita como texto; Colar lista recebe.",
    "Abrir objeto pelo nome" to "Memória → Ficha do objeto: toque em Ver todas as classes do jogo e escolha uma (as do jogo vêm primeiro; " +
        "digite parte do nome para filtrar), ou digite a classe (ex.: PlayerData) e toque em Abrir: aparecem todos os campos com " +
        "o valor de agora (Gem = 1000…); toque num campo para Editar, Salvar ou Congelar. Filtrar campo acha rápido (gem). " +
        "Campos de lista (OwnedWeapons) abrem a lista com cada item.",
    "Ficha do objeto" to "Num salvo, toque em Explorar ficha (ou na aba Memória, em Ficha do objeto): aparecem os campos em volta do valor, " +
        "com tipo e valor ao vivo; o que muda fica destacado. Em jogo Unity IL2CPP, com os nomes do próprio jogo (Player.hp); nos outros, " +
        "com o tipo adivinhado (inteiro, decimal, ponteiro, cifrado 🔒). Toque num campo para salvar ou editar; num ponteiro, para abrir o objeto. " +
        "Salvos dentro de um objeto IL2CPP ganham o nome do campo sozinhos.",
    "Endereços mudaram" to "Quando o jogo reinicia, os endereços mudam. Para um salvo continuar valendo, use um ponteiro (abaixo).",
    "Ponteiros" to "Como no Cheat Engine, mas sozinho: ao salvar, o Tinker já busca os ponteiros e aprende a assinatura (bytes em volta). " +
        "Ao reabrir o jogo e escolher ele no Tinker, cada salvo volta sozinho (ponteiro → caminhos guardados → assinatura); os da mesma ficha " +
        "vêm junto pelo offset. Sobrou dúvida entre vários caminhos? Abra o salvo, digite o valor que o jogo mostra e toque em Filtrar pelo valor. " +
        "Cada salvo guarda o histórico do valor (últimas mudanças e a faixa): ao reabrir, um caminho que chega num valor fora " +
        "da faixa é descartado, e um que chega dentro dela vale mesmo se o valor mudou. " +
        "Salvo com ⚠ é de outra vez do jogo e não é lido nem escrito até voltar; se estava congelado, volta congelado. " +
        "Com o Tinker aberto, quando o jogo reabre ele é escolhido de novo sozinho; enquanto o jogo carrega, o Tinker tenta de novo por 2 minutos " +
        "(e o botão ↻ nos Salvos procura na hora). " +
        "Sem nenhum caminho? Aumente os níveis ou a distância, ou desligue \"Ponteiros só nas libs do jogo\" em Ajustes.",
    "O jogo fechou" to "Quando o jogo fecha sozinho (queda ou fechado pelo Android), o Tinker percebe em alguns segundos, abre o menu " +
        "flutuante e pergunta se quer reabrir. Reabrindo, ele se liga ao jogo e os salvos voltam sozinhos. O motivo da queda fica na aba Registro.",
    "Assistente (IA)" to "Coloque a chave da API do Claude em Ajustes → Assistente (IA). Depois, toque em ✨: na Busca, qual resultado é o certo e o próximo passo; na lista de classes, pergunte onde " +
        "está o valor (as classes citadas viram atalho para abrir); na ficha, o que cada campo faz; no Registro, o que deu errado. " +
        "Só o que está na tela é enviado, e só quando você toca em Perguntar. Precisa de internet; cada pergunta é cobrada na sua conta da API.",
    "Reportar um problema" to "Na aba Registro, toque em Compartilhar relatório e envie o arquivo .txt. Ele tem tudo que é preciso para investigar.",
)

/** Linha de ajuste: título, explicação curta e o interruptor. */
@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

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
    var confirmClear by remember { mutableStateOf(false) }
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
            // dois toques: apagar o registro não tem volta
            if (confirmClear) {
                Button(onClick = { Diag.clear(); info = "Registro apagado."; confirmClear = false }) { Text("Apagar mesmo") }
                TextButton(onClick = { confirmClear = false }) { Text("Cancelar") }
            } else OutlinedButton(onClick = { confirmClear = true }) { Text("Limpar registro") }
        }
        AiBox(
            engine, "Analisar o registro com IA",
            listOf("O que aconteceu? Tem algum problema?", "Por que a busca não achou o valor?", "Que melhoria o Tinker precisa com base nisso?"),
            context = { engine.reportContext() },
        )
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
