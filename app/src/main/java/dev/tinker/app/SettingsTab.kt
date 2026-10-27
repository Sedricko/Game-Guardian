package dev.tinker.app

import android.content.ClipboardManager
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SettingsTab(engine: Engine, overlay: Boolean, onResize: () -> Unit, run: (suspend () -> String) -> Unit, onError: (String) -> Unit) {
    val s = Session
    val clipCtx = LocalContext.current
    var keyInfo by remember { mutableStateOf<String?>(null) }
    var newKey by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var confirmKill by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    // "Configurar IA" (das outras telas) abre os Ajustes já na parte da IA
    LaunchedEffect(s.settingsAt) {
        if (s.settingsAt == "ia") {
            // antes da IA: menu flutuante, busca, ponteiros, versão nova, jogo (com jogo escolhido), título das regiões e uma linha por região
            list.scrollToItem(4 + (if (s.process != null) 1 else 0) + 1 + s.regions.size)
            s.settingsAt = ""
        }
    }
    LaunchedEffect(s.process) {
        if (s.process != null) runCatching { s.regions = engine.regions() }.onFailure { onError(friendly(it.message)) }
    }
    LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // o do dia a dia primeiro (menu flutuante, busca, ponteiros, jogo, regiões); a IA e a biblioteca depois; a Ajuda no fim
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
        item {
            Panel {
                Text("Versão nova", style = MaterialTheme.typography.titleSmall)
                Toggle("Atualizar e testar sozinho", "Com versão nova, baixa e instala sozinho (root), reabre e roda o autoteste; o resultado vai para a planilha. Nunca mexe em jogo.", s.autoUpdate) {
                    s.autoUpdate = it; engine.saveSettings()
                }
                if (s.autoUpdate) OutlinedTextField(s.githubToken, { s.githubToken = it.trim(); engine.saveSettings() }, label = { Text("Token do GitHub (só se o repositório for privado)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
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
                        scope.launch { runCatching { s.regions = engine.setRegion(r.code, on) }.onFailure { onError(friendly(it.message)) } }
                    })
                }
            }
        }
        item(key = "ia") {
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
                    .map(engine::importMemory).getOrElse { "Não leu o arquivo. ${friendly(it.message)}" } } } else null
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
        // agente offline: depois da IA, para o "Configurar IA" continuar caindo na IA
        item(key = "offline") {
            Panel {
                Text("Agente offline", style = MaterialTheme.typography.titleSmall)
                Toggle("Usar o que o agente sabe", "No 🎯 Achar sozinho pelo nome (ex.: Moedas), vai direto no tipo que já acertou neste jogo e deixa de tentar " +
                    "primeiro o que errou. Sem internet e sem gastar a IA. Com um tipo marcado na busca, vale o seu.", s.offlineAgent) { s.offlineAgent = it; engine.saveSettings() }
                Toggle("Pedir ajuda à IA quando não souber", "Quando não acha, faz uma pergunta curta à IA (poucos tokens, precisa de internet e de chave) e tenta de novo no " +
                    "tipo que ela sugerir. Só guarda se der certo, com origem IA; o aprendizado da IA fica separado.", s.offlineBorrowAi) { s.offlineBorrowAi = it; engine.saveSettings() }
                val know = remember(s.offlineAgent, s.autoFind) { engine.knowAll() }
                val mine = know.filter { it.game != "*" }
                Section("O que ele sabe", "${mine.size} aprendidos jogando · ${know.count { it.game == "*" }} ensinados") {
                    mine.sortedByDescending { it.at }.take(30).forEach { k ->
                        Text(if (k.type == "save") "${k.name} · no save: ${k.tips.removePrefix("arquivo ")} · ${k.game}"
                            else "${k.name} · ${typeLabel(k.type)} · ${k.wins} acertos, ${k.fails} erros · ${k.game}" + if (k.from == "IA") " · da IA" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (mine.isEmpty()) Text("Ainda nada aprendido jogando: use o 🎯 Achar sozinho com o nome ao lado do número.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Técnicas que ele conhece", style = MaterialTheme.typography.bodyMedium)
                    TECHNIQUES.forEach { t -> Text("• ${t.name.removePrefix("#")}: ${t.tips}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { HelpPanel() }
    }
}

// AJUDA: gerada por docs/tutorial/gerar.py a partir de docs/tutorial/ajuda.py (edite lá, não aqui)
private val HELP = listOf(
    Triple("Começo", "Para que serve o Tinker", "Editor de memória para jogos offline de um jogador, como o GameGuardian e o Cheat Engine: acha onde o jogo guarda um número (moedas, vida, tempo), muda, congela e faz a mudança voltar sozinha da próxima vez. Precisa de root (Magisk). Não use em jogo online."),
    Triple("Começo", "Menu flutuante", "Abrir o Tinker já abre a bolinha sobre o jogo e tira o app da frente (dá para desligar em Ajustes). Toque na bolinha para abrir o painel; segure para abrir o outro modo (painel ↔ barra rápida). No topo: toque no nome do jogo para trocar; 👁 (segure) espia o jogo; ⏸/▶ pausa; ⚡ barra rápida; — minimiza; ✕ sai."),
    Triple("Começo", "Barra rápida", "Toque em ⚡: uma barra pequena fica sobre o jogo, com o campo de valor, ⏸/▶, Buscar/Refinar e ↑ ↓ = ≠ (aumentou, diminuiu, não mudou, mudou). Com até 50 resultados aparece Editar; com até 5, os valores ao vivo; com 1, ❄ congela e salva. As receitas do jogo aparecem como ▶ botões: um toque aplica. Arraste pelo ⠿; ▢ abre o painel; – volta para a bolinha."),
    Triple("Busca", "Como achar um valor", "1. Escolha o jogo. 2. Digite o número que aparece na tela e toque em Nova busca. 3. Faça o número mudar no jogo, digite o novo e toque em Refinar. 4. Repita até sobrar 1 ou poucos. Refinou errado e sobrou 0? ↶ Desfazer volta à lista anterior (tocar de novo refaz). Sem saber o número novo, use ↑ ↓ = ≠."),
    Triple("Busca", "O que digitar no valor", "100 = número exato. 100~200 = faixa (número arredondado ou abreviado: 1.2K = 1150~1250; só para buscar e refinar). 100;250 = vários de uma vez. 100;250;7::128 = grupo: todos a até 128 bytes um do outro, no mesmo objeto (corta milhares de resultados soltos). 150X4 = cifrado com a chave 4 bytes depois, como no GameGuardian. ...A4 no refino = só os endereços que terminam em A4."),
    Triple("Busca", "Tipos de valor", "Auto procura Dword, Float, Qword e Double de uma vez. Se souber o tipo, marque só ele: mais rápido e com menos resultados. Vida e velocidade costumam ser Float; moedas, Dword. Jogo com Lua (o motor diz · Lua): 🌙 Lua acha só números do Lua."),
    Triple("Busca", "Valores cifrados (🔒)", "Muitos jogos trancam moedas e vida com uma chave. A busca já procura esses valores e mostra o número real com 🔒. Ao editar ou congelar um 🔒, o Tinker tranca o valor novo com a mesma chave. Se aparecer o mesmo número normal e com 🔒, edite o 🔒: o normal costuma ser só uma cópia (ou isca do antitrapaça)."),
    Triple("Busca", "Valor desconhecido", "Para barras sem número (vida, energia): escolha o tipo (barra costuma ser Float) e toque em Sem valor. Faça a barra mudar e toque em ↑ ou ↓; sem mudar nada, =. Repita até sobrar pouco. Com um número no campo dá para dizer quanto mudou (↓ 10)."),
    Triple("Busca", "🎯 Achar sozinho", "Digite o número da tela (ou o nome escrito ao lado dele, ex.: Abates, para número que muda rápido) e toque em 🎯 Achar sozinho. O painel some, o Tinker lê a tela (no aparelho, sem internet) e refina sozinho a cada mudança enquanto você joga. Com até 3 e \"IA pode testar valores\" ligado em Ajustes, ele testa cada um e mostra Salvar no certo."),
    Triple("Busca", "Qual resultado salvar", "Com até 20 resultados, em jogo Unity, cada um mostra o campo do jogo (PlayerData.UpgradeStone). \"Cópia temporária (Int32)\" é só o número da tela: editar não muda nada. Salve o campo de verdade."),
    Triple("Busca", "Editar resultados", "Toque num resultado: Novo valor, ❄ Congelar, Salvar, Ver próximos, Ver ficha, O que escreve aqui. Editar (todos ou os marcados) muda vários de uma vez; \"Só os que valem agora: X → Y\" muda só os que valem X (todos os inimigos com 100 de vida), sem perder a lista. Em sequência escreve 100, 101, 102…: o número que aparecer no jogo mostra o endereço certo; 🎯 Descobrir pela tela faz isso sozinho e volta tudo."),
    Triple("Busca", "Mais buscas", "Recolhido embaixo da busca: Sem valor, Busca por bytes (AOB: pares hex com ?? de curinga, em Dados ou Código), Foto do jogo, Onde o jogo escreve, Editor de save e Ir para endereço."),
    Triple("Busca", "Foto do jogo", "Para pegar de novo uma recompensa (jogo offline): 1) Tirar foto, parado 4 s. 2) Pegue a recompensa. 3) Ver o que mudou: só o que mudou com a ação. 4) Voltar como estava: os valores voltam aos da foto e a recompensa fica para pegar de novo. Se travar, feche e abra o jogo."),
    Triple("Busca", "📍 Onde o jogo escreve", "Começar, carregue o jogo ou uma fase, Ver: mostra as regiões da memória onde o jogo escreveu. Usar nas buscas deste jogo faz a busca começar só nelas; se não achar, procura em tudo sozinha."),
    Triple("Busca", "💾 Editor de save", "Lista os arquivos de progresso do jogo, da pasta interna e da externa (Android/data, aparecem como ext/...). Os de texto (XML, PlayerPrefs do Unity, JSON) mostram cada número pelo nome; mude e toque em Gravar: o Tinker faz backup das duas pastas, fecha o jogo e devolve o arquivo. O agente offline guarda qual arquivo e campo você mudou em cada jogo. Save binário ou cifrado não dá. Só jogo offline."),
    Triple("Ferramentas", "🧰 Ferramentas", "Logo abaixo da busca, uma fileira de botões; só a escolhida abre embaixo. Toque de novo para fechar."),
    Triple("Ferramentas", "🧭 Fácil", "Passo a passo para quem não conhece Dword, Float ou cifrado: pergunta só o número da tela e se aumentou ou diminuiu. O tipo, o cifrado, o dobro e as cópias o Tinker resolve sozinho. Abre sozinho na primeira vez."),
    Triple("Ferramentas", "⏩ Velocidade (Unity)", "Fique no menu ou numa fase parada e toque em Achar a velocidade: o Tinker acha a escala de tempo do Unity pelo relógio do motor. Escolha 0.5x, 2x, 3x ou 5x; 1x volta ao normal. Muda só o valor do motor, sem entrar no jogo."),
    Triple("Ferramentas", "⏱️ Cronômetros", "Com uma construção ou espera rodando, digite quanto falta na tela (2h 30min, 45min, 90s) e toque em Achar cronômetros. Terminar agora (ou Terminar todos) põe a hora de terminar no agora. Recusa se achar lugares demais, para não estragar o jogo."),
    Triple("Ferramentas", "🛒 Loja", "Abra a loja do jogo, digite 2 a 6 preços que aparecem (ou Ler da tela) e toque em Achar preços: a lista de preços vira resultado. Tudo por 1 deixa todos por 1."),
    Triple("Ferramentas", "🔓 Desbloquear (Unity)", "Abra a tela dos itens (loja, inventário) e toque em Achar o que desbloquear: aparecem os campos isUnlocked, owned, purchased… com quantos objetos vivos. Desbloquear liga em todos (isLocked vira 0)."),
    Triple("Ferramentas", "🧮 Disfarçado", "Quando a busca normal não acha porque o jogo guarda o número de outro jeito (dobro, ×10, +100 ou cifrado com chave fixa): digite o número da tela e toque em Começar. A cada mudança, digite o novo e escolha ×k + c ou Cifrado. Mude de jeitos diferentes (ganhe 1, depois 3): subir sempre de 1 deixa contadores passarem."),
    Triple("Salvos", "Salvar e congelar", "Salvar põe o resultado na lista do jogo; Congelar reescreve o valor várias vezes por segundo. Em Salvos: dar nome, editar vários, exportar e importar. Marque dois salvos para ver a distância entre eles."),
    Triple("Salvos", "Ponteiros e reabrir o jogo", "Quando o jogo reinicia, os endereços mudam. Ao salvar, o Tinker já busca os ponteiros e aprende a assinatura; ao reabrir o jogo, cada salvo volta sozinho (ponteiro → caminhos → assinatura), e os da mesma ficha vêm junto pelo offset (↳ Ouro +0x8). Vários caminhos? Abra o salvo, digite o valor do jogo e Filtrar pelo valor. ⚠ = salvo de outra vez do jogo, não é lido nem escrito até voltar. ↻ procura na hora."),
    Triple("Salvos", "Receitas", "Marque os campos e toque em Criar receita: guarda cada campo pelo nome da classe (Unity), senão pelo ponteiro, senão pela assinatura. Aplicar escreve tudo de uma vez (e congela os ❄); \"ao abrir\" aplica sozinha quando o jogo carrega. Também na barra rápida. Copiar manda como texto; Colar lista recebe."),
    Triple("Salvos", "Código e patches", "Para o recurso não diminuir: no salvo, O que escreve aqui? e gaste o recurso em 15 s. Aparece a instrução que muda o valor; Ver código abre Memória → Código. Tocar numa instrução: Desligar esta instrução (NOP) ou trocar os bytes; vira um patch em Salvos → Patches, com liga/desliga, que volta sozinho ao reabrir. Se o jogo cair logo depois, o patch fica suspeito e desligado."),
    Triple("Memória", "Endereços e Buscar perto", "Ver próximos mostra os valores vizinhos; Ir para endereço aceita 7a0010+8. Buscar perto procura um valor (a prata) nas linhas em volta de um ou vários endereços (o ouro)."),
    Triple("Memória", "Ficha do objeto", "Num salvo, Ver ficha (ou Memória → Ficha): os campos em volta do valor, ao vivo; o que muda fica destacado. Em jogo Unity, com os nomes do jogo (Player.hp), o texto das strings (\"Herói\"), Vector3 como (x, y, z) e listas com quantos itens. Nos outros, com o tipo adivinhado. Abra pela classe: digite PlayerData e Abrir, ou Ver todas as classes. Copiar os números para outro objeto da mesma classe clona um item (ponteiros e textos não mudam)."),
    Triple("Memória", "Métodos (Unity)", "Na Ficha, ⚙ Métodos da classe: Contar chamadas (quantas vezes roda em 10 s) e Sempre devolver (número, sim/não ou Não fazer nada), que vira um patch desligado. Exportar a lista de classes gera um .cs com classes, campos e métodos."),
    Triple("Memória", "Funções da lib (sem IDA)", "Memória → Código: digite parte do nome (gold, AddCoins) e Procurar: as funções com nome das libs do jogo. Ver código, Quem ela chama, Contar chamadas, Sempre devolver ou Não fazer nada (patch). Quais rodam quando eu jogo? conta até 12 de uma vez."),
    Triple("Memória", "Unreal e Lua", "Unreal: na Ficha, digite o nome do objeto ou da classe (Player, BP_Player_C) e Procurar, pelas tabelas do próprio motor. Lua (xLua): abra um resultado 🌙 na Ficha: a tabela do Lua aparece com o nome de cada campo; \"tabela ›\" abre a outra."),
    Triple("Registro", "O jogo fechou", "Quase sempre é edição num endereço errado ou de outro tipo: refine até sobrar 1 antes de editar. O Tinker percebe a queda, pergunta se reabre e, reabrindo, os salvos voltam sozinhos. O motivo fica no Registro."),
    Triple("Registro", "Reportar um problema", "Registro → Compartilhar relatório e envie o .txt: tem tudo para investigar."),
    Triple("Ajustes", "Assistente (IA)", "Em Ajustes, escolha Gemini ou Groq e coloque as chaves (grátis em aistudio.google.com e console.groq.com) ou use o painel de chaves da planilha. Toque em ✨ na Busca, na Ficha ou no Registro. A IA procura classes, busca, refina e compara, mas não edita sozinha: quando acha, aparece Aplicar e ↶ Desfazer. Só vai o que está na tela, quando você toca em Perguntar. Precisa de internet."),
    Triple("Ajustes", "Agente offline", "Aprende a achar sem internet e sem gastar a IA. No 🎯 Achar sozinho pelo nome (ex.: Moedas, com o tipo Auto), ele vai direto no tipo que já acertou neste jogo; o que errou (sobrou 0) deixa de ser tentado primeiro. Também aprende com cada salvo que você nomeia (Ouro = Dword neste jogo) e com cada campo que muda no editor de save. Se o número da tela não segura na memória, ele tenta sozinho os disfarces comuns (×10, ×100, dobro) e o cifrado com a chave em outro lugar (como o 150X4), e aprende o jeito (Cristais = Dword ×10, Runas = Dword 🔒 4). Se nenhum jeito acha o número (ele é calculado, como base + bônus), segue a mudança: começa sem valor e refina por quanto a tela subiu ou desceu, até achar a base. Já vem sabendo o tipo comum de moedas, vida, energia, nível, itens e tempo, e as técnicas de achar de cada caso (aparecem como dica quando não acha). Ajustes → Agente offline mostra o que ele sabe; com o painel da planilha, vai para a aba Agente offline (só dele, separada do aprendizado da IA). Pedir ajuda à IA quando não souber (desligado no começo): uma pergunta curta à IA pelo tipo, tenta de novo e só guarda se der certo (origem IA)."),
    Triple("Ajustes", "Opções", "Menu flutuante: abrir ao iniciar, tamanho, lado com o jogo deitado e transparência. Busca: pausar o jogo durante a busca, procurar valores cifrados (🔒), Teclado do Tinker. Ponteiros: preparar ao salvar e só nas libs do jogo. Regiões de memória: onde a busca procura. Atualizar e testar sozinho: baixa a versão nova, instala (root) e roda o autoteste. Mandar relatório sozinho: a cada 10 min de jogo, para a planilha."),
    Triple("Ajustes", "Biblioteca e backup", "Cada jogo guarda notas da IA, salvos, receitas e patches. Fazer backup grava tudo em Download/Tinker; Exportar e Importar (colar) levam para outro aparelho; Juntar com a planilha mistura com o painel. Juntar nunca apaga o que já existe."),
)
// FIM AJUDA

/** Modelos do Groq que a chave pode usar (com ferramentas); o app escolhe sozinho, dá para trocar tocando. */
@Composable
private fun GroqModels(engine: Engine) {
    val s = Session
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Modelo: ${s.groqModel.ifEmpty { "escolhido sozinho" }}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { info = "Vendo os modelos…"; scope.launch { info = engine.detectGroqModels() } }, enabled = s.groqKeys.isNotEmpty()) { Text("Ver modelos") }
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
        scope.launch { info = runCatching { engine.detectGeminiModels() }.getOrElse { friendly(it.message) }; checking = false }
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
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = ::detect, enabled = !checking && s.geminiKey.isNotBlank()) { Text("Ver modelos") }
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
internal fun KnownFields(engine: Engine) {
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
        HELP.forEachIndexed { i, (section, title, body) ->
            // título da aba antes do primeiro item dela (a Ajuda segue a ordem do app)
            if (i == 0 || HELP[i - 1].first != section) Text(section, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = if (i == 0) 0.dp else 10.dp))
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
