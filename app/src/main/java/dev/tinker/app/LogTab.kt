package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun LogTab(engine: Engine, overlay: Boolean, onMinimize: () -> Unit, onError: (String) -> Unit) {
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
