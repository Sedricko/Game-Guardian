package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Uma conversa com a IA por caixa (busca, classes, salvos…): guardada enquanto o app está aberto. */
class AiConversation {
    val chat = AiChat()
    var open by mutableStateOf(false)
    var question by mutableStateOf("")
    var turns by mutableStateOf(listOf<Pair<String, String>>())  // (pergunta, resposta)
    var loading by mutableStateOf(false)
    var steps by mutableStateOf(listOf<String>())
    var applied by mutableStateOf<String?>(null)
    var job: Job? = null  // a pergunta em andamento, para ⏹ Parar
    var stats by mutableStateOf("")  // gasto da última resposta: consultas, tempo, tokens
}

object AiConversations {
    private val all = HashMap<String, AiConversation>()
    fun of(key: String) = all.getOrPut(key) { AiConversation() }

    /** Outro jogo (ou o mesmo reaberto): endereços e contexto da conversa não valem mais. */
    fun clear() = all.clear()
}

private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * "✨ Perguntar à IA": pergunta livre ou uma das prontas, com o contexto da tela (montado só na primeira mensagem).
 * A conversa continua (Continuar / nova pergunta) até tocar em Nova conversa. key: onde a caixa está, para os pedidos
 * automáticos (Session.aiAsk, ex.: busca deu 0). names: nomes de classe que, citados na resposta, viram atalhos.
 */
@Composable
fun AiBox(
    engine: Engine, title: String, presets: List<String>, context: suspend () -> String,
    names: Set<String> = emptySet(), onOpen: (String) -> Unit = {}, key: String = title,
) {
    val s = Session
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // a conversa mora fora da tela: minimizar o painel ou trocar de aba destruía tudo e voltava ao começo
    val c = AiConversations.of(key)
    var open by c::open
    var question by c::question
    var turns by c::turns
    var loading by c::loading
    var steps by c::steps
    var applied by c::applied
    val chat = c.chat
    fun ask(q: String, fresh: Boolean) {
        if (loading || q.isBlank() || !Assistant.ready()) return
        if (fresh) { chat.reset(); turns = emptyList(); s.aiClasses.clear() }
        question = ""
        loading = true
        steps = emptyList()
        applied = null
        // fora da tela também: a resposta chega mesmo com o painel minimizado
        c.job = aiScope.launch {
            val a = runCatching { engine.askAi(chat, context, q) { st -> c.steps = c.steps + st } }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) "⏹ Parado." else it.message ?: "Erro" }
            c.turns = (c.turns + (q to a)).takeLast(6)
            c.stats = Assistant.lastStats
            c.loading = false
        }
    }
    // pedido automático de outra parte do app (busca deu 0, jogo fechou, salvo não voltou): abre e pergunta sozinha
    LaunchedEffect(s.aiAsk) {
        val (where, q) = s.aiAsk ?: return@LaunchedEffect
        if (where != key) return@LaunchedEffect
        s.aiAsk = null
        open = true
        ask(q, fresh = true)
    }
    TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "✨ $title") }
    if (!open) return
    if (!Assistant.ready()) {
        Text("Para usar, coloque uma chave do Gemini (grátis) ou do Claude.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { s.tab = TABS.indexOf("Ajustes") }) { Text("Configurar IA") }
        return
    }
    val going = turns.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // a IA lê os números na tela do jogo e já busca: nada para digitar
            AssistChip(onClick = { ask("Veja a tela do jogo (ver_tela), leia os valores de recursos que aparecem, busque e ache os campos certos.", fresh = !going) },
                enabled = !loading && s.process != null, label = { Text("📷 Ler a tela e achar", maxLines = 1) })
            // missão num toque: o nome do recurso vai no campo de pergunta; ela faz o ciclo inteiro sem perguntar
            AssistChip(onClick = {
                ask("Missão: ache e salve \"${question.trim()}\" sozinha, do começo ao fim, sem me perguntar nada: ver_tela, buscar_valor, fazer o valor mudar " +
                    "(tocar no jogo se puder, senão esperar_mudanca), refinar até sobrar poucos, testar_valor, propor_edicao com congelar só se eu pedir, " +
                    "criar_assinatura se não tiver classe IL2CPP, propor_receita e anotar. Só pare quando estiver salvo ou for impossível (diga por quê).", fresh = !going)
            }, enabled = !loading && s.process != null && question.isNotBlank(), label = { Text(if (question.isBlank()) "🎯 Missão (escreva o recurso)" else "🎯 Achar e salvar: ${question.trim().take(20)}", maxLines = 1) })
            presets.forEach { p -> AssistChip(onClick = { ask(p, fresh = true) }, enabled = !loading, label = { Text(p, maxLines = 1) }) }
        }
        // a conversa rola dentro da própria caixa: em telas sem rolagem (Memória → Ficha) o texto ficava cortado
        val talk = rememberScrollState()
        LaunchedEffect(turns) { talk.animateScrollTo(talk.maxValue) }  // a lista guarda só as 6 últimas: o tamanho para de mudar
        if (turns.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(talk), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        turns.forEachIndexed { i, (q, a) ->
            Text("Você: $q", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            // sem os ** e ` do markdown, que apareciam crus na tela
            SelectionContainer { Text(remember(a) { a.replace("**", "").replace("`", "") }, style = MaterialTheme.typography.bodyMedium) }
            if (i == turns.lastIndex) {
                // classes citadas pela IA viram atalho para abrir a ficha
                val cited = remember(a) {
                    val words = Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(a).map { it.value }.toSet()
                    names.filter { it.substringAfterLast('.') in words }.take(12)
                }
                if (cited.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cited.forEach { n -> AssistChip(onClick = { onOpen(n) }, label = { Text("Abrir ${n.substringAfterLast('.')}", maxLines = 1) }) }
                }
                if (c.stats.isNotEmpty()) Text(c.stats, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Resposta da IA", a))
                }) { Text("⧉ Copiar resposta") }
            }
        }
        }
        // edições e receitas sugeridas: só acontecem com o toque
        if (!loading) s.aiProposals.toList().forEach { p ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (p.recipe.isNotEmpty()) "Receita ${p.label}${if (p.freeze) " (ao abrir)" else ""}: ${p.why}"
                        else "${p.label.ifEmpty { "0x" + p.addr }} → ${p.value}${if (p.freeze) " ❄" else ""}",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { scope.launch { applied = runCatching { engine.applyProposal(p) }.getOrElse { it.message } } }) {
                        Text(if (p.recipe.isNotEmpty()) "Criar" else "Aplicar")
                    }
                }
                if (p.recipe.isEmpty() && p.why.isNotBlank()) Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        applied?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        // o que a IA aplicou, com volta
        s.aiUndo.take(3).forEach { u ->
            TextButton(onClick = { scope.launch { applied = runCatching { engine.undoAi(u) }.getOrElse { it.message } } }) {
                Text("↶ Desfazer ${u.label} (volta a ${u.old})")
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.aiControl) "Trabalhando (pode tocar no jogo)…" else "Pensando… (pode levar até um minuto)", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { c.job?.cancel() }) { Text("⏹ Parar") }
            }
        }
        // o que a IA olhou sozinha na memória (procurar classe, abrir objeto, buscar)
        steps.takeLast(8).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(question, { question = it }, label = { Text(if (going) "Responder ou perguntar mais" else "Sua pergunta") }, modifier = Modifier.weight(1f))
            Button(onClick = { ask(question, fresh = false) }, enabled = !loading && question.isNotBlank()) { Text(if (going) "Enviar" else "Perguntar") }
        }
        if (going && !loading) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // a IA pediu para mexer no jogo (gastar, ganhar, levar dano): a pessoa faz e toca aqui
            Button(onClick = { ask("Pronto, fiz o que você pediu no jogo. Continue.", fresh = false) }) { Text("Continuar") }
            TextButton(onClick = { chat.reset(); turns = emptyList(); steps = emptyList(); applied = null }) { Text("Nova conversa") }
        }
    }
}
