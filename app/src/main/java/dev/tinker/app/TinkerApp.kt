package dev.tinker.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TinkerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
    }
}

/** Registro de diagnóstico: tudo que o Tinker faz, erros, falhas do app e do jogo. Fica em files/tinker.log. */
object Diag {
    val lines = mutableStateListOf<String>()
    private lateinit var file: File
    private lateinit var aiFile: File  // rastro completo da IA (perguntas, cada ferramenta com entrada e saída, cada chamada à API)
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.US)

    fun init(c: Context) {
        file = File(c.filesDir, "tinker.log")
        aiFile = File(c.filesDir, "ia.log")
        // o registro nunca pode derrubar o app: numa instalação nova o arquivo ainda não existe
        runCatching {
            if (file.length() > 2_000_000) file.writeText(file.readText().takeLast(1_000_000))
            if (file.exists()) lines.addAll(file.readLines().takeLast(500))
        }
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        add("Tinker $version iniciado · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.joinToString()}")
        watchUi()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // grava direto no arquivo: o processo vai morrer antes de qualquer post na UI
            runCatching { file.appendText("${clock.format(Date())} FALHA DO TINKER (thread ${t.name}):\n${e.stackTraceToString()}\n") }
            previous?.uncaughtException(t, e)
        }
    }

    /** Vigia a thread da tela: se ela não responder em 700 ms, registra por quanto tempo ficou travada. */
    private fun watchUi() = Thread({
        while (true) {
            val sent = SystemClock.uptimeMillis()
            val done = CountDownLatch(1)
            main.post { done.countDown() }
            if (!done.await(700, TimeUnit.MILLISECONDS)) {
                done.await()
                add("AVISO: a tela travou por ${SystemClock.uptimeMillis() - sent} ms")
            }
            Thread.sleep(1000)
        }
    }, "tinker-vigia").apply { isDaemon = true }.start()

    @Synchronized
    fun add(msg: String) {
        val line = "${clock.format(Date())} $msg"
        runCatching { file.appendText(line + "\n") }
        main.post {
            lines.add(line)
            if (lines.size > 1000) lines.removeRange(0, lines.size - 1000)
        }
    }

    /** Rastro da IA: vai inteiro para ia.log (o registro principal guarda só um resumo de cada passo). */
    @Synchronized
    fun ai(msg: String) {
        runCatching {
            if (aiFile.length() > 1_500_000) aiFile.writeText(aiFile.readText().takeLast(700_000))
            aiFile.appendText("${clock.format(Date())} $msg\n")
        }
    }

    fun aiTrace(max: Int = 80_000): String = runCatching { aiFile.readText().takeLast(max) }.getOrDefault("").ifEmpty { "(a IA ainda não foi usada)" }

    /** Logcat do próprio Tinker (o app lê o dele sem root): erros internos do Android, do Compose e das bibliotecas. */
    fun ownLogcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "300", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
    }.getOrElse { "(não deu para ler: ${it.message})" }

    /** O registro para o relatório: o logcat copiado no meio é encurtado, senão ele empurra as ações do Tinker para fora. */
    fun export(): String = runCatching { compactLog(file.readLines()).takeLast(3000).joinToString("\n") }.getOrDefault("")

    @Synchronized
    fun clear() {
        runCatching { file.writeText(""); aiFile.writeText("") }
        main.post { lines.clear() }
    }
}

/** Encurta cada trecho seguido de linhas "| " (logcat) para as primeiras `keep`; o logcat inteiro já tem seção própria no relatório. */
internal fun compactLog(lines: List<String>, keep: Int = 40): List<String> {
    val out = ArrayList<String>(lines.size)
    var run = 0
    var cut = 0
    for (line in lines) {
        val logcat = line.substringAfter(' ').substringAfter(' ').startsWith("|")  // depois de data e hora
        if (!logcat && cut > 0) { out += "($cut linhas do logcat omitidas aqui; veja a seção Logcat do jogo)"; cut = 0 }
        run = if (logcat) run + 1 else 0
        if (logcat && run > keep) cut++ else out += line
    }
    if (cut > 0) out += "($cut linhas do logcat omitidas aqui; veja a seção Logcat do jogo)"
    return out
}
