package dev.tinker.app

import android.content.ClipData
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
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
                Diag.add("Erro na ação: ${e.javaClass.simpleName}: ${e.message}")
                onError(friendly(e.message))
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
    val run = rememberRun(engine) { error = if (it == friendly("o jogo fechou")) null else it }  // o jogo fechou: a pergunta "Reabrir?" já avisa
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
internal fun HeaderButton(
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
internal fun Panel(content: @Composable () -> Unit) {
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
internal fun ValueField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Decimal, hex: Boolean = false) {
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
internal fun InlineEditor(initial: String, label: String, onSave: (String) -> Unit, onCancel: () -> Unit, extra: @Composable () -> Unit = {}) {
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

/** Cópia em Download/Tinker para achar no gerenciador de arquivos. Só Android 10+, onde não precisa de permissão. */
internal fun saveToDownloads(context: Context, file: File, mime: String = "text/plain"): Uri? {
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
    }.onFailure { Diag.add("Não salvou em Downloads. ${friendly(it.message)}") }.getOrNull()
}

internal fun shareFile(context: Context, file: File, mime: String = "text/plain") {
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
internal fun shown(value: String, type: String) = readable(value, type) ?: "NaN (não é ${TYPE_NAMES[type]})"
