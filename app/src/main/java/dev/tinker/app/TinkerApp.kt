package dev.tinker.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.US)

    fun init(c: Context) {
        file = File(c.filesDir, "tinker.log")
        // o registro nunca pode derrubar o app: numa instalação nova o arquivo ainda não existe
        runCatching {
            if (file.length() > 2_000_000) file.writeText(file.readText().takeLast(1_000_000))
            if (file.exists()) lines.addAll(file.readLines().takeLast(500))
        }
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        add("Tinker $version iniciado · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.joinToString()}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // grava direto no arquivo: o processo vai morrer antes de qualquer post na UI
            runCatching { file.appendText("${clock.format(Date())} FALHA DO TINKER (thread ${t.name}):\n${e.stackTraceToString()}\n") }
            previous?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun add(msg: String) {
        val line = "${clock.format(Date())} $msg"
        runCatching { file.appendText(line + "\n") }
        main.post {
            lines.add(line)
            if (lines.size > 1000) lines.removeRange(0, lines.size - 1000)
        }
    }

    fun export(): String = runCatching { file.readText().takeLast(100_000) }.getOrDefault("")

    @Synchronized
    fun clear() {
        runCatching { file.writeText("") }
        main.post { lines.clear() }
    }
}
