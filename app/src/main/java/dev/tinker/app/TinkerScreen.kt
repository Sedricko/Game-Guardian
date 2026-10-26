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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
    // a IA vai tocar no jogo: o painel sai da frente (a conversa continua; a bolinha abre de novo para ver ou parar)
    LaunchedEffect(s.aiHidePanel) { if (s.aiHidePanel && overlay) onMinimize() }

    // o tamanho que vale é o do painel (metade, de lado, tela dividida), não o da tela: tudo se ajusta a ele
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { BoxWithConstraints {
        CompositionLocalProvider(LocalPanelSize provides DpSize(maxWidth, maxHeight)) {
        Column {
            // deitado (jogo na horizontal) a altura é pouca: cabeçalho e abas numa linha só, sobra mais para a lista
            val wide = landscape() && LocalPanelSize.current.width >= 600.dp  // painel estreito de lado: abas embaixo, senão não cabem
            if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
                Header(overlay, onPick = { picking = true }, onMinimize = onMinimize, onOpenOverlay = onOpenOverlay, onExit = onExit, run = run, onQuickBar = onQuickBar,
                    modifier = Modifier.weight(0.5f))
                if (!picking) TabBar(Modifier.weight(0.5f))
            } else Header(overlay, onPick = { picking = true }, onMinimize = onMinimize, onOpenOverlay = onOpenOverlay, onExit = onExit, run = run, onQuickBar = onQuickBar)
            error?.let { Banner(it) { error = null } }
            // pedido da aba Pedidos da planilha: aparece aqui e só acontece com o toque em Aplicar
            s.sheetRequests.firstOrNull()?.let { r ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val more = if (s.sheetRequests.size > 1) " (+${s.sheetRequests.size - 1})" else ""
                        Text("Pedido da planilha$more: ${REQUEST_TYPES[r.tipo]}${if (r.tipo == "dica") "" else ". Permitir?"}", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.titleSmall)
                        listOf(r.jogo, r.texto).filter { it.isNotEmpty() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                            Text(if (r.tipo == "dica") it.take(2000) else it.take(300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        // os outros que esperam, para "Permitir todos" saber o que vai acontecer
                        s.sheetRequests.drop(1).take(4).forEach { o ->
                            Text("• ${REQUEST_TYPES[o.tipo]}: ${listOf(o.jogo, o.texto).filter { it.isNotEmpty() }.joinToString(" · ").take(120)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        if (s.sheetRequests.size > 5) Text("… e mais ${s.sheetRequests.size - 5}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (r.tipo == "dica") Button(shape = MaterialTheme.shapes.medium, onClick = { engine.answerRequestLater(r, true) }) { Text("Entendi") }
                            else Button(shape = MaterialTheme.shapes.medium, onClick = { engine.answerRequestLater(r, true) }) { Text("Permitir só desta vez") }
                            if (s.sheetRequests.size > 1) Button(shape = MaterialTheme.shapes.medium, onClick = {
                                s.sheetRequests.toList().forEach { engine.answerRequestLater(it, true) }
                            }) { Text("Permitir todos (${s.sheetRequests.size})") }
                            if (r.tipo in TRUSTABLE_REQUESTS) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                                s.alwaysRequests = s.alwaysRequests + r.tipo
                                engine.saveSettings()
                                engine.answerRequestLater(r, true)
                            }) { Text("Sempre permitir") }
                            TextButton(onClick = { engine.answerRequestLater(r, false) }) { Text("Ignorar") }
                        }
                    }
                }
            }
            s.gameGone?.let { gone ->
                // como o GameGuardian: o jogo fechou, pergunta se reabre
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("${gone.label} fechou. Reabrir o jogo?", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.titleSmall)
                        Text(if (s.gameGoneByUser) "Ao reabrir, os salvos voltam sozinhos." else "O motivo da queda fica na aba Registro. Ao reabrir, os salvos voltam sozinhos.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(shape = MaterialTheme.shapes.medium, onClick = { s.status = engine.relaunch(); if (overlay) onMinimize() }) { Text("Reabrir") }
                            TextButton(onClick = { s.gameGone = null }) { Text("Agora não") }
                            // queda: a IA lê o registro e explica
                            if (!s.gameGoneByUser && Assistant.ready()) TextButton(onClick = {
                                s.tab = TABS.indexOf("Registro")
                                s.aiAsk = "registro" to "O jogo fechou sozinho. Veja no registro por quê, se o Tinker teve culpa e o que fazer."
                            }) { Text("✨ Por quê?") }
                        }
                    }
                }
            }
            s.update?.let { (version, url) ->
                val context = LocalContext.current
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
                if (!wide) TabBar()
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
    } }
}

/** Abas do Tinker: o Tab do Material tem padding fixo e cortava "Memória" e "Registro" em telas estreitas. */
@Composable
private fun TabBar(modifier: Modifier = Modifier) {
    val s = Session
    val primary = MaterialTheme.colorScheme.primary
    // abas e indicador numa Column: deitado a TabBar fica dentro da linha do cabeçalho, e as duas Rows soltas iam lado a lado
    // (o indicador empurrava o cabeçalho e as abas para fora da tela)
    Column(modifier) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TABS.forEachIndexed { i, title ->
            val on = s.tab == i
            Box(
                Modifier.weight(1f).sizeIn(minHeight = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { s.tab = i }.padding(vertical = 10.dp, horizontal = 2.dp),
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
}

@Composable
private fun Header(
    overlay: Boolean, onPick: () -> Unit, onMinimize: () -> Unit, onOpenOverlay: () -> Unit, onExit: () -> Unit, run: (suspend () -> String) -> Unit,
    onQuickBar: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val s = Session
    val engine = Engine.get(LocalContext.current)
    val p = s.process
    // uma linha só, como no GameGuardian: o resto da tela fica para a lista; tocar no nome troca o jogo
    Row(modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).sizeIn(minHeight = 48.dp).clickable(enabled = !s.busy, onClick = onPick).padding(4.dp),
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
        // dois toques: o ✕ fica ao lado do — e fechava o Tinker por engano; o segundo toque tem 3 s para vir
        var confirmExit by remember { mutableStateOf(false) }
        LaunchedEffect(confirmExit) { if (confirmExit) { delay(3000); confirmExit = false } }
        if (confirmExit) TextButton(onClick = onExit, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Sair?") }
        else HeaderButton("✕", "Sair", color = MaterialTheme.colorScheme.error, onClick = { confirmExit = true })
    }
}

/** Botão pequeno do cabeçalho: símbolo grande, nome para leitor de tela. */
@Composable
private fun HeaderButton(
    symbol: String, description: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary, onClick: (() -> Unit)? = null,
) {
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(50))  // 48 dp: área mínima de toque do Android
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, fontSize = 20.sp, color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun Banner(text: String, onClose: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
private fun ValueField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Decimal, hex: Boolean = false) {
    // teclado do Tinker (Ajustes): o campo não chama o teclado do telefone, que cobre o jogo; tocar abre o do Tinker
    val own = Session.ownKeypad
    var pad by remember { mutableStateOf(false) }
    val taps = remember { MutableInteractionSource() }
    val pressed by taps.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed && own) pad = true }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NoPhoneKeyboard(own) { OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            interactionSource = taps,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(keyboardType = if (hex) KeyboardType.Ascii else keyboard),
            modifier = Modifier.fillMaxWidth(),
        ) }
        if (own && pad) NumPad(onKey = { onChange(applyKey(value, it)) }, onDone = { pad = false }, compact = landscape(), hex = hex)
    }
}

/** Editor que abre dentro da própria lista, no lugar de um diálogo. */
@Composable
private fun InlineEditor(initial: String, label: String, onSave: (String) -> Unit, onCancel: () -> Unit, extra: @Composable () -> Unit = {}) {
    var text by remember { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueField(text, label, { text = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(shape = MaterialTheme.shapes.medium, onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Salvar") }
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
    var confirmNew by remember { mutableStateOf(false) }
    val enabled = s.process != null && !s.busy
    // valores ao vivo, como no Cheat Engine: relê os resultados mostrados a cada segundo
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (!s.busy && s.count != null) runCatching { s.results = engine.list().second }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { EasyMode(engine, enabled, run) }
        item { GameSpeed(engine, enabled, run) }
        item { Timers(engine, enabled, run) }
        item { ShopPrices(engine, enabled, run) }
        item { HiddenValue(engine, enabled, run) }
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
                    }, enabled = enabled && s.value.isNotBlank() && !confirmNew, modifier = Modifier.weight(1f)) {
                        Text("Nova busca", maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(
                        shape = MaterialTheme.shapes.medium,
                        onClick = { run { engine.refine(s.value) } },
                        enabled = enabled && (s.count ?: 0) > 0 && s.value.isNotBlank() && !same,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (same) "Mude no jogo" else "Refinar", maxLines = 1) }
                    // sem número na tela (barra de vida): começa já aqui, sem abrir "Mais formas de buscar"
                    if (s.count == null) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { run { engine.startUnknown(s.types) } }, enabled = enabled) { Text("Sem valor") }
                }
                // 🎯 lê o número na tela e refina sozinho a cada mudança; com "IA pode testar valores", acha o verdadeiro
                val finding = s.autoFind
                if (finding == null) {
                    // número que muda rápido (tempo, abates): pelo nome escrito ao lado dele na tela
                    var label by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TinkerTextField(label, { label = it }, "ou o nome ao lado (ex.: Moedas)", Modifier.weight(1f))
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
                if (s.canUndo) TextButton(onClick = { run { engine.undo() } }, enabled = enabled) { Text("↶ Desfazer último refino") }
                // com resultados: refinar pelo jeito que mudou, sem precisar do número
                if ((s.count ?: 0) > 0) CompareButtons(engine, enabled, run)
                Text(s.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // busca deu 0: a IA investiga sozinha por quê (e tenta achar)
                if (s.count == 0L && s.process != null && Assistant.ready()) TextButton(onClick = {
                    s.aiAsk = "busca" to "A busca não achou o valor. Descubra por quê e tente achar você mesmo (outro tipo, faixa, grupo, sem valor ou pelo nome da classe)."
                }) { Text("✨ Por que não achou? A IA tenta achar") }
                // o resto fica recolhido: a tela pequena do menu flutuante sobra para os resultados
                TextButton(onClick = { more = !more }) { Text((if (more) "▾ " else "▸ ") + "Mais formas de buscar") }
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
/**
 * Modo fácil: o passo a passo para quem não sabe o que é Dword, Float ou cifrado. Só pergunta o que qualquer pessoa sabe
 * (o número da tela, se aumentou ou diminuiu); o tipo, o cifrado, o dobro e as cópias o Tinker resolve sozinho.
 */
/**
 * 🧮 Número guardado diferente: a tela mostra 50 mas a memória guarda 100 (dobro), 507 (×10+7) ou 50 XOR uma chave.
 * Marca o número de agora; a cada mudança, diz o novo e o Tinker fica com o que mudou na mesma proporção (ou chave).
 */
@Composable
private fun HiddenValue(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var now by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<String?>(null) }
    Panel {
        Text("🧮 Número guardado diferente", style = MaterialTheme.typography.titleMedium)
        Text(if (last == null) "Quando a busca normal não acha (o jogo guarda em dobro, ×10, +100 ou cifrado): digite o número da tela agora e toque em Começar."
            else "Era $last. Mude no jogo, digite o número novo e escolha o jeito.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ValueField(now, if (last == null) "Número da tela agora" else "Número novo", { now = it }, Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (last == null) Button(shape = MaterialTheme.shapes.medium, enabled = enabled && now.isNotBlank(), onClick = {
                val v = now; run { engine.startUnknown(setOf("i32")).also { last = v; now = "" } }
            }) { Text("Começar") }
            else {
                listOf("lin" to "×k + c (dobro, ×10, +100)", "xk" to "Cifrado (chave fixa)").forEach { (op, l) ->
                    OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled && now.isNotBlank() && now != last, onClick = {
                        val a = last; val b = now; run { engine.compare(op, "$a;$b").also { last = b; now = "" } }
                    }) { Text(l) }
                }
                TextButton(onClick = { last = null; now = "" }) { Text("Recomeçar") }
            }
        }
    }
}

/** 🛒 Preços da loja: com a loja aberta, os preços que aparecem viram uma busca em grupo; depois "tudo por 1". */
@Composable
private fun ShopPrices(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    val s = Session
    val scope = rememberCoroutineScope()
    var prices by remember { mutableStateOf("") }
    Panel {
        Text("🛒 Preços da loja", style = MaterialTheme.typography.titleMedium)
        Text("Abra a loja do jogo e digite 2 a 6 preços que aparecem (ou leia da tela). O Tinker acha a lista de preços e deixa tudo por 1.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TinkerTextField(prices, { prices = it }, "Preços (ex.: 150 320 75)", Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = {
                s.aiHidePanel = true
                scope.launch {
                    delay(800)
                    prices = runCatching { engine.visibleNumbers().filter { it in 1..9_999_999 }.take(6).joinToString(" ") }.getOrDefault(prices)
                    s.aiHidePanel = false
                }
            }) { Text("Ler da tela") }
            Button(shape = MaterialTheme.shapes.medium, enabled = enabled && prices.isNotBlank(), onClick = { run { engine.findShopPrices(prices) } }) { Text("Achar preços") }
            OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled && (s.count ?: 0) in 1L..24L, onClick = { run { engine.writeAll("1") } }) { Text("Tudo por 1") }
        }
    }
}

/** ⏱️ Cronômetros: acha as construções e esperas pela hora de terminar e termina agora. */
@Composable
private fun Timers(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var found by remember { mutableStateOf<List<GameTimer>?>(null) }
    Panel {
        Text("⏱️ Cronômetros (terminar agora)", style = MaterialTheme.typography.titleMedium)
        Text("Com uma construção ou espera rodando no jogo, acha a hora em que ela termina. Troca os resultados da busca.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = {
            run { engine.findTimers().let { found = it; if (it.isEmpty()) "Nenhum cronômetro (o jogo pode contar de outro jeito, como segundos que faltam)." else "Achei ${it.size} cronômetro(s)." } }
        }) { Text("Achar cronômetros") }
        found?.takeIf { it.isNotEmpty() }?.let { list ->
            list.take(10).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("termina em ${t.leftSec / 3600}h ${t.leftSec % 3600 / 60}min ${t.leftSec % 60}s", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = enabled, onClick = { run { engine.finishTimers(listOf(t)) } }) { Text("Terminar agora") }
                }
            }
            if (list.size > 1) OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = { run { engine.finishTimers(list) } }) { Text("Terminar todos") }
        }
    }
}

/** ⏩ Velocidade do jogo (Unity): acha a escala do tempo pelo relógio do Unity e muda com um toque. */
@Composable
private fun GameSpeed(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var addrs by remember { mutableStateOf<List<String>?>(null) }
    Panel {
        Text("⏩ Velocidade do jogo (Unity)", style = MaterialTheme.typography.titleMedium)
        val a = addrs
        if (a == null) {
            Text("Acha onde o jogo guarda a velocidade do tempo. Fique no menu ou numa fase parada. Troca os resultados da busca.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = {
                run { engine.findGameSpeed().let { addrs = it; if (it.isEmpty()) "Não achei a velocidade (jogo não é Unity, ou o relógio fica em outro formato)." else "Achei ${it.size} lugar(es) da velocidade." } }
            }) { Text("Achar a velocidade") }
        } else if (a.isEmpty()) {
            Text("Não achei. Abra uma fase e tente de novo.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = { addrs = null }) { Text("Tentar de novo") }
        } else {
            Text("${a.size} lugar(es). Escolha a velocidade (1x volta ao normal):", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("0.5", "1", "2", "3", "5").forEach { f ->
                    OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = { run { engine.setGameSpeed(a, f) } }) { Text("${f}x") }
                }
            }
        }
    }
}

@Composable
private fun EasyMode(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    val s = Session
    var open by remember { mutableStateOf(s.count == null) }
    var step by remember { mutableIntStateOf(0) }  // 0 número; 1 número mudou; 2 escondido (aumentou/diminuiu); 3 achou
    var v by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<Long?>(null) }
    var stuck by remember { mutableIntStateOf(0) }
    val count = s.count ?: 0L
    // a cada refino: se a contagem não cai, as que sobraram mudam juntas (cópias); com 1, achou
    LaunchedEffect(s.count) {
        if (step !in 1..2 || s.count == null) return@LaunchedEffect
        stuck = if (s.count == last) stuck + 1 else 0
        last = s.count
        if (s.count == 1L) step = 3
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🧭 Modo fácil", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { open = !open }) { Text(if (open) "Fechar" else "Abrir") }
        }
        if (!open) return@Panel
        when (step) {
            0 -> {
                Text("1. Qual número você quer mudar? Digite o que aparece no jogo.")
                ValueField(v, "Número da tela", { v = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.medium, enabled = enabled && v.isNotBlank(), onClick = { step = 1; last = null; stuck = 0; run { engine.search(emptySet(), v) } }) { Text("Procurar") }
                    TextButton(enabled = enabled, onClick = { step = 2; last = null; stuck = 0; run { engine.startUnknown(setOf("i32")) } }) { Text("O número não aparece na tela") }
                }
            }
            1 -> {
                Text("2. Faça esse número mudar no jogo (ganhe ou gaste) e digite o número de agora.")
                Text("$count lugares com esse número", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ValueField(v, "Número de agora", { v = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.medium, enabled = enabled && v.isNotBlank(), onClick = { run { engine.refine(v) } }) { Text("Continuar") }
                    // as que sobraram mudam juntas: muda uma de cada vez e vê qual aparece na tela do jogo
                    if (stuck >= 1 && count in 2L..300L) OutlinedButton(enabled = enabled, onClick = { run { engine.findBySequence(s.results.map { it.addr to it.type }, "") } }) { Text("Descobrir qual é o verdadeiro") }
                    if (count in 2L..10L) TextButton(onClick = { step = 3 }) { Text("Usar esses $count") }
                }
            }
            2 -> {
                Text("2. Faça o valor mudar no jogo e diga o que aconteceu com ele.")
                Text("$count candidatos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = { run { engine.compare("inc") } }) { Text("Aumentou") }
                    Button(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = { run { engine.compare("dec") } }) { Text("Diminuiu") }
                    OutlinedButton(enabled = enabled, onClick = { run { engine.compare("same") } }) { Text("Não mudou") }
                    if (count in 1L..10L) TextButton(onClick = { step = 3 }) { Text("Usar esses $count") }
                }
            }
            else -> {
                Text("✅ Achei${if (count > 1) " ($count lugares que mudam juntos)" else ""}! 3. Para quanto quer mudar?")
                ValueField(target, "Novo valor", { target = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.medium, enabled = enabled && target.isNotBlank(), onClick = { run { engine.writeAll(target) } }) { Text("Mudar") }
                    OutlinedButton(enabled = enabled && target.isNotBlank(), onClick = {
                        run {
                            val r = engine.writeAll(target)
                            val hits = engine.list().second
                            withContext(Dispatchers.Main) { engine.addSaved(hits, frozen = true, name = "Modo fácil") }
                            "$r Travado: o jogo não consegue mudar."
                        }
                    }) { Text("Travar nesse valor") }
                    TextButton(enabled = s.results.isNotEmpty(), onClick = {
                        engine.addSaved(s.results, name = "Modo fácil")
                        s.status = "Salvo em Salvos: quando você fechar e abrir o jogo, o Tinker acha de novo sozinho."
                    }) { Text("Salvar") }
                }
            }
        }
        if (step > 0) TextButton(onClick = { step = 0; v = ""; target = ""; stuck = 0; last = null }) { Text("Começar de novo") }
    }
}

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
private fun ResultCard(
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
            }.onFailure { error = it.message }
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
        val addrPressed by addrTaps.collectIsPressedAsState()
        LaunchedEffect(addrPressed) { if (addrPressed && ownPad) hexPad = true }
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
                else hexScope.launch { hex = runCatching { engine.hexView(center, 16) }.getOrElse { "Não leu: ${it.message}" } }
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
    val pressed by taps.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed && s.ownKeypad) pad = true }
    LaunchedEffect(center) {
        if (center == "0") return@LaunchedEffect
        runCatching { engine.codeAt(center) }.onSuccess { rows = it; error = null }.onFailure { error = it.message }
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
                            enabled = r.word != null && r.word != ARM64_NOP && !s.busy) { Text("Trocar por NOP") }
                        TextButton(onClick = { s.status = copyAddress(ctx, r.addr) }) { Text("Copiar endereço") }
                        if (r.where.isNotEmpty()) TextButton(onClick = { s.status = copyAddress(ctx, r.where) }) { Text("Copiar lib+desloc.") }
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
        runCatching { engine.nativeFunctions(filter) }.onSuccess { found = it; open = null }.onFailure { s.status = it.message ?: "erro" }
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
        if (none) Text("Digite o nome de uma classe ou de um campo do jogo (ex.: PlayerData, gold) ou abra a ficha de um salvo (Salvos → Explorar ficha).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!none) FichaBody(engine, center, run)
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
            onClick = { scope.launch { found = runCatching { engine.unrealObjects(filter) }.getOrElse { (it.message ?: "erro") to emptyList() } } }) { Text("Procurar") }
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
            }.onFailure { info = it.message }
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
                    .onFailure { info = it.message; listing = false }
                loading = false
            }
        }
    }, enabled = s.process != null) { Text((if (listing) "▾ " else "▸ ") + "Ver todas as classes do jogo") }
    // dump.cs: todas as classes do jogo com campos e métodos, num arquivo para ler com calma ou mandar para a IA
    TextButton(onClick = {
        loading = true
        info = "Montando o dump (classes, campos e métodos)… pode levar um minuto"
        scope.launch {
            runCatching { engine.dumpIl2cpp() }.onSuccess { (msg, file) -> info = msg; shareFile(context, file) }.onFailure { info = it.message }
            loading = false
        }
    }, enabled = s.process != null && !loading) { Text("📄 Exportar dump das classes (.cs)") }
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
private fun TopArea(content: @Composable ColumnScope.() -> Unit) {
    // deitado: só um quarto da altura para os controles (rolam), o resto fica para a lista
    val max = LocalPanelSize.current.height * if (landscape()) 0.25f else 0.45f
    Column(Modifier.fillMaxWidth().heightIn(max = max).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Tamanho do painel em dp (na Activity, a tela; no menu flutuante, o painel). */
private val LocalPanelSize = androidx.compose.runtime.compositionLocalOf { DpSize(400.dp, 800.dp) }

/** Pouca altura para o que mostra (jogo deitado, painel de lado ou pela metade): cabeçalho compacto e controles menores. */
@Composable
private fun landscape() = LocalPanelSize.current.let { it.width > it.height || it.height < 480.dp }

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
            }.onFailure { error = it.message }
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
            runCatching { engine.il2Methods(c.start) }.onSuccess { methods = it; error = null }.onFailure { error = it.message }
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
                                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { open = null; run { engine.patchReturn(m, c.name, "true") } }) { Text("Sempre true") }
                                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { open = null; run { engine.patchReturn(m, c.name, "false") } }) { Text("Sempre false") }
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
            runCatching { engine.luaTable(center) }.onSuccess { (v, f) -> ver = v; fields = f; error = null }.onFailure { error = it.message; fields = emptyList() }
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
        if (open && files == null) scope.launch { runCatching { engine.saveFiles() }.onSuccess { files = it }.onFailure { info = it.message } }
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
                            runCatching { engine.readSave(f) }.onSuccess { file = f; text = it; info = null }.onFailure { info = it.message }
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
                scope.launch { info = runCatching { engine.activityStart() }.getOrElse { it.message }; rows = emptyList() }
            }) { Text("Começar") }
            OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = {
                scope.launch {
                    runCatching { engine.activityResult() }.onSuccess { (how, r) ->
                        rows = r
                        info = if (r.sumOf { it.second } == 0L) "Nada escrito desde Começar: carregue algo no jogo e veja de novo."
                            else if (how == "soft") "Páginas escritas desde Começar:" else "Memória nova escrita desde Começar (este aparelho não marca cada página):"
                    }.onFailure { info = it.message }
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
private fun copyAddress(context: android.content.Context, addr: String): String {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Endereço", addr))
    return "Copiado: $addr"
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

@Composable
private fun SettingsTab(engine: Engine, overlay: Boolean, onResize: () -> Unit, run: (suspend () -> String) -> Unit, onError: (String) -> Unit) {
    val s = Session
    val clipCtx = LocalContext.current
    var keyInfo by remember { mutableStateOf<String?>(null) }
    var newKey by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var confirmKill by remember { mutableStateOf(false) }
    LaunchedEffect(s.process) {
        if (s.process != null) runCatching { s.regions = engine.regions() }.onFailure { onError(it.message ?: "Erro") }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // organizado por assunto; a Ajuda fica no fim
        // a IA primeiro: é o que se configura uma vez e o botão "Configurar IA" das outras telas traz para cá
        item {
            Panel {
                Text("Assistente (IA)", style = MaterialTheme.typography.titleSmall)
                Text("O botão ✨ na Busca, na Ficha, na lista de classes e no Registro manda o que está na tela para a IA e mostra a resposta: " +
                    "onde está o valor, o que cada campo faz, o que deu errado. Ela também pode tirar um print do jogo para ler os números da tela " +
                    "(📷 Ler a tela e achar). Precisa de internet. Nada é enviado sem você tocar em Perguntar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gemini" to "Gemini", "groq" to "Groq").forEach { (id, label) ->
                        FilterChip(selected = s.aiProvider == id, onClick = { s.aiProvider = id; engine.saveSettings() }, label = { Text(label) })
                    }
                }
                val gemini = s.aiProvider == "gemini"
                Text(
                    if (gemini) "Chave grátis em aistudio.google.com (Get API key). No nível gratuito há limite por dia, e o Google pode usar o que for enviado para melhorar os modelos."
                    else "Chave grátis em console.groq.com (API Keys). Bem mais rápido; no gratuito há limite por minuto e por dia, e só alguns modelos leem o print da tela.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                fun clipText(): String {
                    val clip = clipCtx.getSystemService(ClipboardManager::class.java).primaryClip
                    return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(clipCtx)?.toString().orEmpty().filterNot { it.isWhitespace() }
                }
                // várias chaves coladas de uma vez: mantém as quebras de linha para separar
                fun clipRaw(): String {
                    val clip = clipCtx.getSystemService(ClipboardManager::class.java).primaryClip
                    return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(clipCtx)?.toString().orEmpty().trim()
                }
                val noClip = "Não consegui ler a área de transferência. Copie a chave de novo, ou abra o Tinker pelo ícone e cole na tela principal."
                Section("Chaves do ${if (gemini) "Gemini" else "Groq"}", "${(if (gemini) s.geminiKeys else s.groqKeys).size} chaves", startOpen = (if (gemini) s.geminiKeys else s.groqKeys).isEmpty()) {
                    // várias chaves por provedor: quando a cota de uma acaba (ou ela é recusada), o Tinker passa para a próxima sozinho
                    val keys = if (gemini) s.geminiKeys else s.groqKeys
                    val current = if (gemini) s.geminiKeyIndex else s.groqKeyIndex
                    Text("Quando a cota de uma acaba, o Tinker passa para a próxima.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    keys.forEachIndexed { i, k ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // a chave numa linha e o resto embaixo, menor: tudo junto em letra de máquina quebrava em 3 linhas
                            Column(Modifier.weight(1f)) {
                                Text("${i + 1}. ${k.take(4)}…${k.takeLast(4)}" + if (i == current) "  · em uso" else "",
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                                    color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(listOfNotNull(
                                    if (gemini) "${s.geminiUse[i] ?: 0} pedidos hoje" else null,
                                    if (gemini) s.geminiOut[i]?.let { "cota acabou às $it" } else null,
                                    if (k in s.sheetKeys || k in s.sheetGroqKeys) "do painel" else "posta no app",
                                ).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (i != current) TextButton(onClick = { if (gemini) s.geminiKeyIndex = i else s.groqKeyIndex = i; engine.saveSettings() }) { Text("Usar") }
                            TextButton(onClick = { engine.removeKey(s.aiProvider, i) }) { Text("✕") }
                        }
                    }
                    if (keys.isEmpty()) Text(if (gemini) "Nenhuma chave ainda." else "Nenhuma chave ainda. Crie em console.groq.com (API Keys).", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(newKey, { newKey = it }, label = { Text("Novas chaves do ${if (gemini) "Gemini" else "Groq"} (pode colar várias)") },
                            placeholder = { Text(if (gemini) "AIza… ou AQ.…" else "gsk_…") }, maxLines = 3,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                        // no menu flutuante o Android não mostra "Colar" ao segurar o campo: o botão lê a área de transferência direto
                        Button(shape = MaterialTheme.shapes.medium, onClick = { val t = clipRaw(); if (t.isEmpty()) keyInfo = noClip else newKey = t }) { Text("Colar") }
                    }
                    Button(shape = MaterialTheme.shapes.medium, onClick = { keyInfo = engine.addKeys(s.aiProvider, newKey); newKey = "" }, enabled = newKey.isNotBlank()) { Text("+ Adicionar") }
                }
                Section("Painel de chaves (planilha)", if (s.keySource.isBlank()) "desligado" else "ligado: chaves e aprendizado vêm de lá", startOpen = s.keySource.isBlank()) {
                    // painel de chaves numa planilha do Google (docs/painel-chaves): o app pega as chaves de lá sozinho
                    Text("Painel de chaves (Google Planilhas): o Tinker pega as chaves ativas do Gemini e do Groq ao abrir e quando a cota de todas acaba.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(s.keySource, { s.keySource = it.trim(); engine.saveSettings() }, label = { Text("Link do painel") },
                            placeholder = { Text("https://script.google.com/…") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                        Button(shape = MaterialTheme.shapes.medium, onClick = { val t = clipText(); if (t.isEmpty()) keyInfo = noClip else { s.keySource = t; engine.saveSettings(); scope.launch { keyInfo = engine.syncKeys() } } }) { Text("Colar") }
                    }
                    if (s.keySource.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { keyInfo = "Lendo o painel…"; scope.launch { keyInfo = engine.syncKeys() } }) { Text("Atualizar do painel") }
                        TextButton(onClick = { s.keySource = ""; engine.saveSettings() }) { Text("Desligar painel") }
                }
                }
                keyInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Section("Modelo", if (gemini) s.geminiModel else s.groqModel.ifEmpty { "escolhido sozinho" }) {
                    if (gemini) GeminiModels(engine) else GroqModels(engine)
                    Toggle("Trocar de provedor sozinho", "Sem cota ou com o servidor cheio, segue no outro provedor (Gemini ↔ Groq), se ele tiver chave.", s.aiReserve) {
                        s.aiReserve = it; engine.saveSettings()
                }
                    if (s.keySource.isNotBlank()) Toggle("Mandar relatório sozinho", "Enquanto joga, a cada 10 min (só se algo mudou) e quando você fecha o jogo pelo Tinker: o registro vai para a aba Relatórios da planilha.", s.autoReports) {
                        s.autoReports = it; engine.saveSettings()
                    }
                }
                Section("Autonomia", listOfNotNull("testa".takeIf { s.aiCanTest }, "aplica sozinha".takeIf { s.aiAuto }, "controla o jogo".takeIf { s.aiControl }).joinToString(" · ").ifEmpty { "só sugere" }) {
                    Toggle("IA pode testar valores", "Para saber se um resultado é o verdadeiro, ela escreve um valor de teste por 2 segundos e depois volta o original. " +
                        "Desligado: ela só olha.", s.aiCanTest) { s.aiCanTest = it; engine.saveSettings() }
                    Toggle("Modo autônomo: aplicar sozinha", "A IA aplica, congela, testa e cria receitas sem esperar o seu toque. " +
                        "O ↶ Desfazer continua na tela.", s.aiAuto) { s.aiAuto = it; engine.saveSettings() }
                    Toggle("Modo autônomo: controlar o jogo", "A IA toca e arrasta na tela do jogo para fazer o valor mudar e acha sozinha. " +
                        "Toque bloqueado se outra coisa (compra na Play Store, anúncio) estiver na frente; mesmo assim, use em jogos sem compras " +
                        "ou com senha na Play Store. ⏹ Parar a qualquer momento.", s.aiControl) { s.aiControl = it; engine.saveSettings() }
                }
                // o que a IA aprendeu (lições gerais + notas deste jogo): ver e apagar
                var showLessons by remember { mutableStateOf(false) }
                // abrir arquivo só na tela principal: o painel flutuante não recebe resultado de outra tela
                val openMemory = if (!overlay) androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let { u -> keyInfo = runCatching { clipCtx.contentResolver.openInputStream(u)!!.use { it.reader().readText() } }
                    .map(engine::importMemory).getOrElse { "Não leu o arquivo: ${it.message}" } } } else null
                TextButton(onClick = { showLessons = !showLessons }) {
                    Text((if (showLessons) "▾ " else "▸ ") + "O que a IA aprendeu" + if (Session.pendingLessons.isNotEmpty()) " (${Session.pendingLessons.size} para aprovar)" else "")
                }
                // lição nova vale para todos os jogos: só entra com o toque da pessoa
                if (showLessons) Session.pendingLessons.toList().forEach { l ->
                    Panel {
                        Text("A IA quer aprender: $l", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { engine.decideLesson(l, true) }) { Text("Aprovar") }
                            TextButton(onClick = { engine.decideLesson(l, false) }) { Text("Descartar") }
                        }
                    }
                }
                if (showLessons) {
                    val text = remember(showLessons) { engine.lessons() }
                    Text(text.ifBlank { "Nada ainda: ela aprende uma lição no fim de cada tarefa." }, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                    if (text.isNotBlank()) TextButton(onClick = { engine.clearLessons(); showLessons = false }) { Text("Apagar o aprendizado") }
                    // biblioteca: o perfil de cada jogo, que cresce a cada conversa, salvo e receita
                    val games = remember(showLessons, keyInfo) { engine.library() }
                    var openGame by remember { mutableStateOf<String?>(null) }
                    Text("Biblioteca dos jogos (${games.size})", style = MaterialTheme.typography.titleSmall)
                    games.forEach { g ->
                        TextButton(onClick = { openGame = if (openGame == g.pkg) null else g.pkg }) {
                            Text("${if (openGame == g.pkg) "▾" else "▸"} ${g.name} · ${g.notes.lines().count { it.isNotBlank() }} notas · ${g.recipes} receitas · ${g.saved} salvos")
                        }
                        if (openGame == g.pkg) Text(g.notes.ifBlank { "Sem notas ainda." }, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
                    }
                    // backup: sozinho em Download/Tinker a cada coisa aprendida; volta sozinho numa instalação nova
                    Text("Backup automático em Download/Tinker/$MEMORY_FILE" + (if (engine.lastMemoryBackup.isEmpty()) "" else " (último: ${engine.lastMemoryBackup})") +
                        if (s.keySource.isNotBlank()) ". Também vai para a planilha do painel (aba Aprendizado), que junta o de todas as instâncias." else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { keyInfo = if (engine.backupMemory()) "Backup feito em Download/Tinker." else "Backup não deu certo (veja o Registro)." }) { Text("Fazer backup") }
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                            val file = File(clipCtx.cacheDir, "reports").apply { mkdirs() }.let { File(it, MEMORY_FILE) }.apply { writeText(engine.memoryJson()) }
                            shareFile(clipCtx, file, "application/json")
                        }) { Text("Exportar") }
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                            val clip = clipCtx.getSystemService(ClipboardManager::class.java).primaryClip
                            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(clipCtx)?.toString().orEmpty()
                            keyInfo = if (t.isBlank()) "Copie o conteúdo do arquivo $MEMORY_FILE e toque de novo." else engine.importMemory(t)
                        }) { Text("Importar (colar)") }
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { keyInfo = engine.restoreMemory() }) { Text("Restaurar do backup") }
                        // banco comum na planilha do painel: junta o que todas as instâncias aprenderam
                        if (s.keySource.isNotBlank()) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { keyInfo = "Juntando com a planilha…"; scope.launch { keyInfo = engine.cloudPull() } }) { Text("Juntar com a planilha") }
                        // "Sempre permitir" de um pedido da planilha: daqui volta a perguntar
                        if (s.alwaysRequests.isNotEmpty()) OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { s.alwaysRequests = emptySet(); engine.saveSettings() }) {
                            Text("Voltar a perguntar (sempre permitido: ${s.alwaysRequests.joinToString { REQUEST_TYPES[it].orEmpty().lowercase() }})")
                        }
                        // backup de outro aparelho ou outra instância (copiado para cá): escolher o arquivo
                        openMemory?.let { l -> OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { l.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Abrir arquivo") } }
                    }
                }
            }
        }
        item {
            Panel {
                Text("Menu flutuante", style = MaterialTheme.typography.titleSmall)
                Toggle("Abrir o menu flutuante ao iniciar", "Como o GameGuardian: abrir o Tinker já deixa a bolinha sobre o jogo.", s.autoOverlay) {
                    s.autoOverlay = it; engine.saveSettings()
                }
                if (overlay) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tamanho do painel: ${PANEL_SIZES[s.panelSize].second}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onResize) { Text("Trocar") }
                    }
                    Text("Em pé o tamanho é a altura; com o jogo deitado é a largura, e o painel fica de lado (o jogo continua à vista).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lado com o jogo deitado: ${if (s.panelLeft) "esquerda" else "direita"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { s.panelLeft = !s.panelLeft; engine.saveSettings() }) { Text("Trocar") }
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
                Toggle("Atualizar e testar sozinho", "Com versão nova, baixa e instala sozinho (root), reabre e roda o autoteste; o resultado vai para a planilha. Nunca mexe em jogo.", s.autoUpdate) {
                    s.autoUpdate = it; engine.saveSettings()
                }
                if (s.autoUpdate) OutlinedTextField(s.githubToken, { s.githubToken = it.trim(); engine.saveSettings() }, label = { Text("Token do GitHub (só se o repositório for privado)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Toggle("Teclado do Tinker", "Números e letras dentro do painel; o teclado do telefone abre por cima do jogo e esconde o valor.", s.ownKeypad) {
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
                    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
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
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
        "Ajustes). Toque na bolinha para abrir o painel; segure para abrir o outro modo (painel ↔ barra rápida). No topo: toque no nome do jogo para trocar; 👁 (segure) espia o jogo; ⏸/▶ pausa; " +
        "⚡ barra rápida; — minimiza; ✕ sai. Os ajustes ficam guardados.",
    "Barra rápida" to "No menu flutuante, toque em ⚡: uma barra pequena fica sobre o jogo, com o campo de valor (e o teclado do Tinker), " +
        "⏸/▶ para pausar, Buscar/Refinar e ↑ ↓ = ≠ (aumentou, diminuiu, não mudou, mudou). Com até 50 resultados aparece Editar (todos " +
        "passam para o valor do campo); com até 5, os valores ao vivo, para acompanhar o jogo. Arraste pelo ⠿. ▢ abre o painel completo para editar e congelar; – volta para a bolinha, " +
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
    "Foto do jogo" to "Para pegar de novo uma recompensa (jogo offline): Busca → Mais formas → Foto do jogo. 1) Tirar foto antes, parado 4 s. " +
        "2) Pegue a recompensa no jogo. 3) Ver o que mudou: a lista mostra só o que mudou com a ação (o que muda sozinho e os ponteiros ficam fora); " +
        "refine se quiser (ex.: pelo número de presentes). 4) Voltar como estava: os valores da lista (ou os escolhidos) voltam aos da foto, " +
        "e a recompensa fica para pegar de novo. Se o jogo travar, feche e abra: o jogo volta pelo save dele.",
    "Código e patches" to "Para o recurso não diminuir: em Salvos, toque no salvo → \"O que escreve aqui?\" e gaste o recurso no jogo nos 15 s. " +
        "Aparece a instrução que muda o valor; \"Ver código\" abre Memória → Código, com as instruções traduzidas (SUB, STR…). Tocar numa " +
        "instrução deixa trocar por NOP (não faz nada) ou por outros bytes: vira um patch em Salvos → Patches de código, com liga/desliga. " +
        "Os bytes de antes ficam guardados; o patch volta sozinho quando o jogo reabre. Se o jogo fechar logo depois, o patch fica suspeito e desligado.",
    "📍 Onde o jogo escreve" to "Em Busca → Mais formas: Começar, carregue o jogo ou uma fase, Ver. Mostra em que regiões da memória o jogo " +
        "escreveu. Usar nas buscas deste jogo faz a busca começar só nelas (bem menos resultados); se não achar, ela procura em tudo sozinha. " +
        "Os salvos também ensinam: a região de cada salvo entra junto.",
    "💾 Editor de save" to "Busca → Mais formas → Editor de save: lista os arquivos onde o jogo guarda o progresso. Os de texto (XML das " +
        "preferências, PlayerPrefs do Unity, JSON) mostram cada número pelo nome; mude e toque em Gravar: o Tinker faz backup do save, fecha o " +
        "jogo e devolve o arquivo. Ao abrir de novo, o jogo lê o valor novo. Save binário ou cifrado não dá: use a busca na memória. Só jogo offline.",
    "Editar em sequência" to "Como no GameGuardian: em Busca → Editar, \"Em sequência\" escreve 100, 101, 102… um em cada resultado (ou nos " +
        "marcados). O número que aparecer no jogo mostra qual endereço é o certo. 🎯 Descobrir pela tela faz isso sozinho: escreve, lê a " +
        "tela, diz qual foi e volta tudo como estava. Voltar como estava desfaz a sequência. Só em jogo offline.",
    "🎯 Achar sozinho" to "Digite o número que está na tela do jogo (ex.: 25 moedas) e toque em 🎯 Achar sozinho. O painel some, o Tinker lê esse " +
        "número na tela (no próprio aparelho, sem internet) e, a cada vez que ele muda enquanto você joga, refina sozinho. Com até 3 resultados, " +
        "se \"IA pode testar valores\" estiver ligado em Ajustes, ele muda cada um por 1 segundo: o que muda o número da tela é o verdadeiro, " +
        "e aparece o botão Salvar. Número que não fica guardado na memória (calculado na hora) não é achado: ele avisa.",
    "Lua (xLua)" to "Jogos com Lua (o motor mostra \"· Lua\"): na Busca, o tipo 🌙 Lua acha só números do Lua (o Lua guarda o tipo ao lado " +
        "do número), com bem menos resultados. Abra um resultado na Ficha: aparece a tabela do Lua com o nome de cada campo (gold, level…), " +
        "ao vivo. Número dá para mudar, salvar e congelar; campo \"tabela ›\" abre a outra tabela. Edite só em jogo offline.",
    "Métodos (IL2CPP)" to "Na Ficha de um objeto do jogo, \"⚙ Métodos da classe\" lista as funções com o tipo que devolvem. Toque numa: " +
        "\"Contar chamadas\" mostra quantas vezes ela roda em 10 s e de onde (em Salvos); \"Sempre devolver\" (número, true/false ou \"Não fazer nada\") " +
        "vira um patch desligado em Salvos → Patches de código. Em Memória → Ficha, onde se abre pela classe, \"Exportar dump\" gera um .cs com todas as classes, campos e métodos.",
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
    "Busca em grupo" to "Valores que ficam juntos no mesmo objeto (ouro, gema, nível) dá para buscar de uma vez: digite os valores com ; " +
        "e, depois de ::, a distância máxima em bytes (ex.: 1250;30;7::128). Ficam só os que aparecem todos perto um do outro, " +
        "o que corta milhares de resultados soltos. Funciona em qualquer jogo, com ou sem IL2CPP.",
    "Assistente (IA)" to "Em Ajustes → Assistente (IA), escolha Gemini ou Groq e coloque as chaves (as duas são grátis: aistudio.google.com e console.groq.com), ou use o painel de chaves numa planilha do Google. Depois, toque em ✨: na Busca, qual resultado é o certo e o próximo passo; na lista de classes, pergunte onde " +
        "está o valor (as classes citadas viram atalho para abrir); na ficha, o que cada campo faz; no Registro, o que deu errado. " +
        "A IA também olha a memória sozinha (🔧): procura classes, abre objetos com os valores, busca (inclusive em grupo e sem valor), " +
        "refina, compara e vê os salvos. Ela não edita sozinha: quando acha o campo, aparece Aplicar (escreve e salva, com ponteiros; ❄ congela) " +
        "e depois ↶ Desfazer; para receitas, Criar. Quando ela pedir para mexer no jogo, faça e toque em Continuar. Ela anota o que descobre " +
        "de cada jogo e usa na próxima vez. Também aparece sozinha: busca deu 0, jogo fechou, salvo não voltou. " +
        "Só o que está na tela e o que ela consulta é enviado, e só quando você toca em Perguntar. Precisa de internet; no gratuito do Gemini e do Groq há limite por minuto e por dia.",
    "Reportar um problema" to "Na aba Registro, toque em Compartilhar relatório e envie o arquivo .txt. Ele tem tudo que é preciso para investigar.",
)

/** Modelos do Groq que a chave pode usar (com ferramentas); o app escolhe sozinho, dá para trocar tocando. */
@Composable
private fun GroqModels(engine: Engine) {
    val s = Session
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Modelo: ${s.groqModel.ifEmpty { "escolhido sozinho" }}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { info = "Vendo os modelos…"; scope.launch { info = engine.detectGroqModels() } }, enabled = s.groqKeys.isNotEmpty()) { Text("Detectar") }
    }
    info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    if (s.groqModels.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        s.groqModels.take(12).forEach { m ->
            FilterChip(selected = m == s.groqModel, onClick = { s.groqModel = m; engine.saveSettings() }, label = { Text(m, maxLines = 1) })
        }
    }
}

private var detectedKey = ""  // a chave já detectada nesta abertura: abrir Ajustes de novo não consulta de novo

/** Modelos do Gemini: ao colar a chave, pergunta ao Google quais ela pode usar e escolhe o melhor; dá para trocar tocando. */
@Composable
private fun GeminiModels(engine: Engine) {
    val s = Session
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    fun detect() {
        if (checking || s.geminiKey.isBlank()) return
        checking = true
        info = "Vendo quais modelos a chave pode usar…"
        scope.launch { info = runCatching { engine.detectGeminiModels() }.getOrElse { it.message }; checking = false }
    }
    // chave nova (colada ou digitada): espera parar de mudar e detecta sozinho
    LaunchedEffect(s.geminiKey) {
        if (s.geminiKey.length < 20 || (s.geminiModels.isNotEmpty() && detectedKey == s.geminiKey)) return@LaunchedEffect
        detectedKey = s.geminiKey
        delay(1200)
        detect()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Modelo: ${s.geminiModel}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = ::detect, enabled = !checking && s.geminiKey.isNotBlank()) { Text("Detectar") }
    }
    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
    info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    if (s.geminiModels.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        s.geminiModels.take(12).forEach { m ->
            // escolher na mão desliga a escolha automática
            FilterChip(selected = m == s.geminiModel, onClick = { s.geminiModel = m; s.geminiAutoModel = false; engine.saveSettings() },
                label = { Text(m + (s.geminiSpeed[m]?.let { " · ${it / 1000} s" } ?: ""), maxLines = 1) })
        }
    }
    Toggle("Mapa de campos enquanto joga", "A cada minuto o Tinker olha as classes que você já salvou ou abriu e anota sozinho os campos que mudam " +
        "jogando (velocidade, distância, vida). Em qualquer jogo, vigia também 100 Dwords acima e abaixo de cada salvo e manda o que mudou no relatório. Sem IA e sem gastar cota.", s.watchFields) { s.watchFields = it; engine.saveSettings() }
    Toggle("Escolher sozinho o modelo mais rápido", "A cada conversa nova usa, entre os 3 melhores (sem Lite), o que respondeu mais rápido. " +
        "O tempo de cada um aparece ao lado do nome.", s.geminiAutoModel) { s.geminiAutoModel = it; engine.saveSettings() }
}

/**
 * Atalhos sem IA: cada salvo com classe IL2CPP mostra os objetos vivos dessa classe e o valor do campo em cada um;
 * tocar passa o salvo (e o congelamento) para aquele objeto. Ex.: corrida nova, RunData novo.
 */
@Composable
private fun KnownFields(engine: Engine) {
    val s = Session
    val known = s.saved.filter { it.il2.isNotEmpty() && !it.temp }
    if (s.process == null || known.isEmpty()) return
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var values by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var msg by remember { mutableStateOf<String?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        known.forEach { item ->
            AssistChip(onClick = {
                if (open == item.id) { open = null; return@AssistChip }
                open = item.id; values = emptyList(); msg = "Lendo os objetos…"
                scope.launch { values = runCatching { engine.liveValues(item) }.getOrDefault(emptyList()); msg = if (values.isEmpty()) "Nenhum objeto vivo dessa classe agora." else null }
            }, label = { Text("🎯 ${item.name.ifEmpty { item.il2 }}", maxLines = 1) })
        }
    }
    val item = known.firstOrNull { it.id == open } ?: return
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    // os com valor diferente de zero primeiro: o objeto da corrida atual costuma ser o que tem número
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        values.sortedBy { it.second == "0" }.take(16).forEach { (addr, v) ->
            FilterChip(selected = addr == item.addr, onClick = { msg = engine.retarget(item, addr); open = null }, label = { Text(v, maxLines = 1) })
        }
    }
}

/** Bloco de Ajustes que abre e fecha: título, resumo do que está escolhido e o conteúdo. A lista de Ajustes estava enorme. */
@Composable
private fun Section(title: String, summary: String = "", startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(startOpen) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (open) "▾" else "▸", color = MaterialTheme.colorScheme.primary)
            }
            if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

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
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).sizeIn(minHeight = 48.dp).clickable { open = if (open == i) null else i }.padding(vertical = 6.dp)) {
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
    var logFilter by remember { mutableStateOf(LOG_FILTERS[0].first) }
    fun fetch(work: suspend () -> Unit) = scope.launch {
        working = true
        runCatching { work() }.onFailure { onError(it.message ?: "Erro") }
        working = false
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        TopArea {
        Spacer(Modifier.height(8.dp))
        Panel {
        Text("Relatório e testes", style = MaterialTheme.typography.titleSmall)
        Text(
            "Tudo que o Tinker faz fica registrado. Se o jogo fechar, o motivo é anexado sozinho. " +
                "O relatório junta registro, falhas do Android e logcat do jogo num arquivo .txt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(shape = MaterialTheme.shapes.medium, onClick = {
                fetch {
                    val file = engine.report()
                    info = saveToDownloads(context, file)?.let { "Salvo em Download/Tinker/${file.name}" } ?: "Relatório pronto: ${file.name}"
                    engine.sendReport("enviado por você", ready = file.readText())  // também vai para a aba Relatórios da planilha
                    if (overlay) onMinimize()  // o seletor de apps abre atrás do painel
                    shareFile(context, file)
                }
            }, enabled = !working) { Text("Compartilhar relatório") }
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                fetch {
                    val text = engine.report().readText()
                    // a área de transferência do Android aguenta ~1 MB; fica o final, que tem o mais recente
                    val clip = if (text.length > 400_000) "(início cortado)\n" + text.takeLast(400_000) else text
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Relatório do Tinker", clip))
                    info = "Relatório copiado (${clip.length / 1024} KB). Cole onde quiser."
                }
            }, enabled = !working) { Text("Copiar tudo") }
            // confere tudo neste aparelho (só leituras) e manda o resultado para a planilha
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { fetch { info = "Testando…"; info = engine.selfTest() } }, enabled = !working) { Text("Autoteste") }
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { fetch { info = "Procurando…"; info = engine.checkUpdateNow() } }, enabled = !working) { Text("Procurar atualização") }
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { fetch { engine.crashLog() } }, enabled = !working) { Text("Ver falhas") }
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { fetch { engine.gameLog() } }, enabled = !working && Session.process != null) { Text("Ver logcat do jogo") }
            // dois toques: apagar o registro não tem volta
            if (confirmClear) {
                Button(shape = MaterialTheme.shapes.medium, onClick = { Diag.clear(); info = "Registro apagado."; confirmClear = false }) { Text("Apagar mesmo") }
                TextButton(onClick = { confirmClear = false }) { Text("Cancelar") }
            } else OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { confirmClear = true }) { Text("Limpar registro") }
        }
        AiBox(
            engine, "Analisar o registro com IA",
            listOf("O que aconteceu? Tem algum problema?", "Por que a busca não achou o valor?", "Que melhoria o Tinker precisa com base nisso?"),
            context = { engine.reportContext() }, key = "registro",
        )
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        }
        // filtro: acompanhar só o que interessa (erros, a IA, a busca) sem rolar por tudo
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LOG_FILTERS.forEach { (name, _) -> FilterChip(selected = logFilter == name, onClick = { logFilter = name }, label = { Text(name) }) }
        }
        val match = LOG_FILTERS.first { it.first == logFilter }.second
        val shown = Diag.lines.filter(match)
        LazyColumn(Modifier.padding(top = 8.dp), reverseLayout = true) {
            items(shown.asReversed()) { line ->
                val bad = LOG_FILTERS[1].second(line)
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Filtros do registro: nome e quais linhas entram. Problemas ficam em vermelho em qualquer filtro. */
private val LOG_FILTERS: List<Pair<String, (String) -> Boolean>> = listOf(
    "Tudo" to { _ -> true },
    "Problemas" to { l -> listOf("erro", "falha", "aviso", "fechou", "exception", "bloqueado").any { l.contains(it, ignoreCase = true) } && " | " !in l },
    "IA" to { l -> "Assistente" in l || "IA " in l || "Gemini" in l || "Sugestão da IA" in l },
    "Busca" to { l -> listOf("> search", "> refine", "> compare", "> group", "> aob", "> snapshot", "Etapa", "< ok").any { it in l } },
    "Salvos e ponteiros" to { l -> listOf("Salvo", "salvos", "Ponteiro", "ponteiro", "> pointers", "Receita", "Congel").any { it in l } },
)

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
        TinkerTextField(filter, { filter = it }, "Buscar app", Modifier.fillMaxWidth())
        val list = apps
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text("Erro: $error", color = MaterialTheme.colorScheme.error)
            list.isEmpty() -> Text("Nenhum app aberto. Abra o jogo primeiro.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list.filter { filter.isBlank() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).sizeIn(minHeight = 48.dp).clickable { onPick(p) }.padding(8.dp),
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
