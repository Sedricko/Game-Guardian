package dev.tinker.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 🧰 Ferramentas do jogo, como um canivete: uma fileira de botões curtos e só a escolhida aberta embaixo, para a busca e
 * os resultados não ficarem lá no fim da tela. Desbloquear só aparece em jogo Unity (precisa das classes IL2CPP).
 */
@Composable
internal fun Toolbox(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit, onOpen: () -> Unit = {}) {
    val s = Session
    // primeira vez sem busca: o Modo fácil já vem aberto, como antes; depois fica fechado até a pessoa escolher
    var tool by remember { mutableStateOf(if (s.count == null && s.history.isEmpty()) "facil" else "") }
    val tools = buildList {
        add("facil" to "🧭 Fácil")
        add("juiz" to "🧠 Quero mudar")
        add("scripts" to "📜 Scripts")
        add("velocidade" to "⏩ Velocidade")
        add("cronometros" to "⏱️ Cronômetros")
        add("loja" to "🛒 Loja")
        if (s.engine?.il2cpp == true) add("desbloquear" to "🔓 Desbloquear")
        add("disfarcado" to "🧮 Disfarçado")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🧰 Ferramentas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tools.forEach { (key, label) -> FilterChip(selected = tool == key, onClick = { tool = if (tool == key) "" else key; if (tool.isNotEmpty()) onOpen() }, label = { Text(label) }) }
        }
        when (tool) {
            "facil" -> EasyMode(engine, enabled, run)
            "juiz" -> WantChange(engine, enabled, run)
            "scripts" -> ScriptsTool(engine, enabled)
            "velocidade" -> GameSpeed(engine, enabled, run)
            "cronometros" -> Timers(engine, enabled, run)
            "loja" -> ShopPrices(engine, enabled, run)
            "desbloquear" -> Unlocks(engine, enabled, run)
            "disfarcado" -> HiddenValue(engine, enabled, run)
        }
    }
}

/**
 * 🧮 Número disfarçado: a tela mostra 50 mas a memória guarda 100 (dobro), 507 (×10+7) ou 50 XOR uma chave.
 * Marca o número de agora; a cada mudança, diz o novo e o Tinker fica com o que mudou na mesma proporção (ou chave).
 */
@Composable
private fun HiddenValue(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var now by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<String?>(null) }
    Panel {
        Text("🧮 Número disfarçado", style = MaterialTheme.typography.titleMedium)
        Text(if (last == null) "Quando a busca normal não acha (o jogo guarda em dobro, ×10, +100 ou cifrado): digite o número da tela agora e toque em Começar."
            else "Era $last. Mude no jogo, digite o número novo e escolha o jeito. Mude de jeitos diferentes (ganhe 1, depois 3, ou gaste): subir sempre de 1 deixa contadores passarem.",
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
        Text("🛒 Loja", style = MaterialTheme.typography.titleMedium)
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

/** 🔓 Desbloquear: campos "tem o item" (isUnlocked, owned, purchased) das classes Unity, um toque liga em todos os objetos. */
@Composable
private fun Unlocks(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var found by remember { mutableStateOf<List<Unlock>?>(null) }
    Panel {
        Text("🔓 Desbloquear (Unity)", style = MaterialTheme.typography.titleMedium)
        Text("Acha campos como isUnlocked, owned e purchased nas classes do jogo. Abra a tela dos itens (loja, inventário) antes: só os objetos vivos aparecem.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = {
            run { engine.findUnlocks().let { found = it; if (it.isEmpty()) "Nenhum campo de desbloquear nos objetos vivos agora." else "Achei ${it.size} campo(s)." } }
        }) { Text("Achar o que desbloquear") }
        found?.take(12)?.forEach { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${u.cls.substringAfterLast('.')}.${u.field} · ${u.objs.size} objetos", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = { run { engine.unlock(u) } }) { Text("Desbloquear") }
            }
        }
    }
}

/** ⏱️ Cronômetros: acha as construções e esperas pela hora de terminar e termina agora. */
@Composable
private fun Timers(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var found by remember { mutableStateOf<List<GameTimer>?>(null) }
    var left by remember { mutableStateOf("") }
    Panel {
        Text("⏱️ Cronômetros", style = MaterialTheme.typography.titleMedium)
        TinkerTextField(left, { left = it }, "Quanto falta na tela (ex.: 2h 30min, 45min, 90s)", Modifier.fillMaxWidth())
        Text("Com uma construção ou espera rodando no jogo, acha a hora em que ela termina. Troca os resultados da busca.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(shape = MaterialTheme.shapes.medium, enabled = enabled, onClick = {
            run { engine.findTimers(durationSec(left)).let { found = it; if (it.isEmpty()) "Nenhum cronômetro (o jogo pode contar de outro jeito, como segundos que faltam)." else "Achei ${it.size} cronômetro(s)." } }
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

/**
 * 🧠 Quero mudar isto: o número da tela e o novo; o juiz testa texto do JSON, número na memória e save, confere na tela
 * e reabrindo o jogo, e deixa aplicada a forma de maior nota. Leva de 1 a 4 minutos (reabre o jogo até 3 vezes).
 */
@Composable
private fun WantChange(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var now by remember { mutableStateOf("") }
    var want by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Panel {
        Text("🧠 Quero mudar isto", style = MaterialTheme.typography.titleMedium)
        Text("Deixe o número aparecendo no jogo. O Tinker testa cada forma (texto, memória, save), confere na tela e reabre o jogo para ver se dura. A melhor fica aplicada. Leva até 4 minutos.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ValueField(now, "Número na tela agora", { now = it }, Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
        ValueField(want, "Número que você quer", { want = it }, Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
        TinkerTextField(name, { name = it }, "Nome (opcional, ex.: Joias)", Modifier.fillMaxWidth())
        val a = normalizeNumbers(now).toLongOrNull()
        val b = normalizeNumbers(want).toLongOrNull()
        Button(shape = MaterialTheme.shapes.medium, enabled = enabled && a != null && b != null && a != b, onClick = {
            run { engine.wantChange(a!!, b!!, name.trim()) }
        }) { Text("Testar e aplicar") }
    }
}

/** "2h 30min", "45min", "90s", "1:20:00" → segundos (null: vazio ou não entendeu). */
internal fun durationSec(text: String): Long? {
    val t = text.lowercase().replace(" ", "")
    if (t.isEmpty()) return null
    Regex("""(\d+):(\d{1,2})(?::(\d{1,2}))?""").matchEntire(t)?.let { m ->
        val (a, b, c) = m.destructured
        return if (c.isEmpty()) a.toLong() * 60 + b.toLong() else a.toLong() * 3600 + b.toLong() * 60 + c.toLong()
    }
    var sec = 0L
    var any = false
    Regex("""(\d+)(d|h|min|m|s)""").findAll(t).forEach { m ->
        any = true
        sec += m.groupValues[1].toLong() * when (m.groupValues[2]) { "d" -> 86400L; "h" -> 3600L; "s" -> 1L; else -> 60L }
    }
    return if (any) sec else t.toLongOrNull()?.times(60)
}

/** ⏩ Velocidade do jogo (Unity): acha a escala do tempo pelo relógio do Unity e muda com um toque. */
@Composable
private fun GameSpeed(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    var addrs by remember { mutableStateOf<List<String>?>(null) }
    Panel {
        Text("⏩ Velocidade (Unity)", style = MaterialTheme.typography.titleMedium)
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

/**
 * Modo fácil: o passo a passo para quem não sabe o que é Dword, Float ou cifrado. Só pergunta o que qualquer pessoa sabe
 * (o número da tela, se aumentou ou diminuiu); o tipo, o cifrado, o dobro e as cópias o Tinker resolve sozinho.
 */
@Composable
private fun EasyMode(engine: Engine, enabled: Boolean, run: (suspend () -> String) -> Unit) {
    val s = Session
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
        Text("🧭 Modo fácil", style = MaterialTheme.typography.titleMedium)
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
