package dev.tinker.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture

/**
 * 📜 O script que está rodando: fica fora da tela (fechar a ferramenta ou o painel não perde a pergunta nem para o
 * script). As perguntas do gg.alert/choice/prompt aparecem dentro do painel Scripts, não numa janela (o menu flutuante
 * não pode abrir janela).
 */
internal object Scripts {
    var running by mutableStateOf<String?>(null)
    var question by mutableStateOf<LuaAsk?>(null)
    val log = mutableStateListOf<String>()
    @Volatile private var reply: CompletableFuture<Any?>? = null
    @Volatile private var lua: GgLua? = null
    private val main = Handler(Looper.getMainLooper())

    fun dir(ctx: Context) = File(ctx.filesDir, "scripts").apply { mkdirs() }
    fun dataDir(ctx: Context) = File(ctx.filesDir, "scripts-dados").apply { mkdirs() }

    fun answer(v: Any?) { question = null; reply?.complete(v) }
    fun stop() { lua?.stopped = true; answer(null) }
    fun openMenu() { lua?.menuTap = true }

    private fun say(line: String) = main.post { log += line; while (log.size > 200) log.removeAt(0) }

    fun start(engine: Engine, ctx: Context, file: File) {
        if (running != null) return
        val pkg = Session.process?.pkg ?: run { log += "Escolha um jogo antes de rodar o script."; return }
        val app = ctx.applicationContext
        val host = object : GgHost {
            override val pkg = pkg
            override val dataDir = dataDir(app)
            override fun send(cmd: String) = engine.luaSend(cmd)
            override fun ask(q: LuaAsk): Any? {
                val f = CompletableFuture<Any?>()
                reply = f
                main.post { question = q }
                return f.get()
            }
            override fun toast(text: String) { main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() } }
            override fun copy(text: String) { main.post { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Script", text)) } }
            override fun listItems() = engine.luaItems()
            override fun addItems(items: List<LuaItem>) = engine.luaAdd(items)
            override fun removeItems(items: List<LuaItem>) { engine.luaRemove(items) }
            override fun http(url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
                val c = URL(url).openConnection() as HttpURLConnection
                return try {
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
                    val code = c.responseCode
                    code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
                } finally { c.disconnect() }
            }
            override fun log(line: String) = say(line)
        }
        val l = GgLua(host)
        lua = l
        running = file.name
        say("▶ ${file.name}")
        Diag.add("Script Lua: ${file.name} em $pkg")
        Thread({
            val err = runCatching { l.run(file.readText(), file.name) }.getOrElse { it.message ?: it.toString() }
            say(if (err == null) "■ ${file.name} terminou" else "✖ ${file.name}: $err")
            err?.let { Diag.add("Script Lua ${file.name}: $it") }
            main.post { running = null; question = null; lua = null }
        }, "script-lua").start()
    }
}

/** 📜 Scripts: importar um .lua (do arquivo ou colado), rodar, responder o que ele pergunta e parar. */
@Composable
internal fun ScriptsTool(engine: Engine, enabled: Boolean) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(Scripts.dir(ctx).listFiles { f -> f.name.endsWith(".lua") }.orEmpty().sortedBy { it.name }) }
    var info by remember { mutableStateOf<String?>(null) }
    fun save(name: String, code: String) {
        val clean = name.substringAfterLast('/').replace(Regex("[^\\w.\\- ]"), "_").let { if (it.endsWith(".lua")) it else "$it.lua" }
        File(Scripts.dir(ctx), clean).writeText(code)
        files = Scripts.dir(ctx).listFiles { f -> f.name.endsWith(".lua") }.orEmpty().sortedBy { it.name }
        info = "Importado: $clean"
    }
    // abrir arquivo só existe na tela principal: o painel flutuante não recebe resultado de outra tela
    val openFile = if (androidx.activity.compose.LocalActivityResultRegistryOwner.current != null) androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { u ->
            runCatching {
                val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("_display_name")) else null } ?: "script.lua"
                name to ctx.contentResolver.openInputStream(u)!!.use { it.reader().readText() }
            }.onSuccess { (n, code) -> save(n, code) }.onFailure { info = "Não leu o arquivo. ${friendly(it.message)}" }
        }
    } else null
    Panel {
        Text("📜 Scripts (Lua do GameGuardian)", style = MaterialTheme.typography.titleMedium)
        Text("Roda scripts .lua do GameGuardian no jogo escolhido. Trancado: o script só usa a pasta dele e só fala com a planilha (pedindo antes). Script de outra pessoa pode ter código escondido: rode só o que você confia.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val running = Scripts.running
        if (running == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                openFile?.let { o -> OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { o.launch(arrayOf("*/*")) }) { Text("Importar .lua") } }
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = {
                    val t = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    if (t.isBlank()) info = "Copie o código do script e toque de novo." else save("colado-${System.currentTimeMillis() / 1000}.lua", t)
                }) { Text("Colar código") }
            }
            if (files.isEmpty()) Text("Nenhum script ainda.", style = MaterialTheme.typography.bodySmall)
            files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = enabled, onClick = { info = null; Scripts.start(engine, ctx, f) }) { Text("▶ Rodar") }
                    TextButton(onClick = { f.delete(); files = files - f }) { Text("Apagar") }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("▶ $running", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { Scripts.openMenu() }) { Text("Abrir menu") }
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { Scripts.stop() }) { Text("Parar") }
            }
            Scripts.question?.let { Question(it) }
        }
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Scripts.log.takeLast(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A pergunta do script, dentro do painel. */
@Composable
private fun Question(q: LuaAsk) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (q) {
            is LuaAsk.Alert -> {
                Text(q.text, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    q.buttons.forEachIndexed { i, b -> Button(shape = MaterialTheme.shapes.medium, onClick = { Scripts.answer(i + 1) }) { Text(b) } }
                }
            }
            is LuaAsk.Choice -> {
                if (q.title.isNotBlank()) Text(q.title, style = MaterialTheme.typography.titleSmall)
                q.items.forEachIndexed { i, item ->
                    OutlinedButton(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(), onClick = { Scripts.answer(i + 1) }) { Text(item) }
                }
                TextButton(onClick = { Scripts.answer(null) }) { Text("Cancelar") }
            }
            is LuaAsk.Prompt -> {
                val values = remember(q) { mutableStateListOf(*q.defaults.toTypedArray()) }
                q.labels.forEachIndexed { i, label ->
                    when (q.kinds.getOrElse(i) { "text" }) {
                        "checkbox" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(values[i] == "true", { values[i] = it.toString() })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                        "number" -> ValueField(values[i], label, { values[i] = it }, Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
                        else -> TinkerTextField(values[i], { values[i] = it }, label, Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.medium, onClick = { Scripts.answer(values.toList()) }) { Text("OK") }
                    TextButton(onClick = { Scripts.answer(null) }) { Text("Cancelar") }
                }
            }
        }
    }
}
