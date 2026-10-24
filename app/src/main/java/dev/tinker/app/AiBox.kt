package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "✨ Perguntar à IA": pergunta livre ou uma das prontas, com o contexto da tela (montado só ao perguntar).
 * names: nomes de classe que, citados na resposta, viram atalhos (onOpen abre a ficha).
 */
@Composable
fun AiBox(
    engine: Engine, title: String, presets: List<String>, context: suspend () -> String,
    names: Set<String> = emptySet(), onOpen: (String) -> Unit = {},
) {
    val s = Session
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "✨ $title") }
    if (!open) return
    if (!Assistant.ready()) {
        Text("Coloque a chave do Claude ou do Gemini em Ajustes → Assistente (IA) para usar.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    fun ask(q: String) {
        if (loading || q.isBlank()) return
        question = q
        loading = true
        answer = null
        scope.launch {
            answer = runCatching { engine.askAi(context(), q) }.getOrElse { it.message ?: "Erro" }
            loading = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p -> AssistChip(onClick = { ask(p) }, enabled = !loading, label = { Text(p, maxLines = 1) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(question, { question = it }, label = { Text("Sua pergunta") }, modifier = Modifier.weight(1f))
            Button(onClick = { ask(question) }, enabled = !loading && question.isNotBlank()) { Text("Perguntar") }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Pensando… (pode levar até um minuto)", style = MaterialTheme.typography.bodySmall)
        }
        answer?.let { a ->
            SelectionContainer { Text(a, style = MaterialTheme.typography.bodyMedium) }
            // classes citadas pela IA viram atalho para abrir a ficha
            val cited = remember(a) {
                val words = Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(a).map { it.value }.toSet()
                names.filter { it.substringAfterLast('.') in words }.take(12)
            }
            if (cited.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cited.forEach { n -> AssistChip(onClick = { onOpen(n) }, label = { Text("Abrir ${n.substringAfterLast('.')}", maxLines = 1) }) }
            }
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Resposta da IA", a))
            }) { Text("⧉ Copiar resposta") }
        }
    }
}
