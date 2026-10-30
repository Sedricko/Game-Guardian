package dev.tinker.v2.core.daemon

import java.io.BufferedReader
import java.io.IOException
import java.io.Writer

/**
 * Conversa com o tinkerd (daemon/tinkerd.c): um comando por linha; a resposta são as linhas antes do "ok", ou "err motivo".
 * Os fluxos entram de fora para o protocolo ser testado na JVM sem root (DaemonTest).
 */
class Daemon internal constructor(
    private val input: Writer,
    private val output: BufferedReader,
    private val onClose: () -> Unit = {},
) {
    class Reply(val lines: List<String>, val status: String) {
        /** Contagem das buscas e refinos ("ok 3"); 0 se não for número. */
        val count get() = status.toLongOrNull() ?: 0
    }

    init {
        val hello = output.readLine() ?: throw IOException("Sem root: o su recusou ou não existe.")
        if (!hello.startsWith("ok tinkerd")) throw IOException("O daemon não respondeu: $hello")
    }

    var isAttached = false
        private set

    @Synchronized
    fun call(command: String): Reply {
        input.write(command + "\n")
        input.flush()
        val lines = mutableListOf<String>()
        while (true) {
            val line = output.readLine() ?: throw IOException("O daemon fechou.")
            if (line.startsWith("err ")) throw IOException(line.removePrefix("err "))
            if (line == "ok" || line.startsWith("ok ")) {
                if (command.startsWith("attach ")) isAttached = true
                return Reply(lines, line.removePrefix("ok").trim())
            }
            lines += line
        }
    }

    fun close() = onClose()

    companion object {
        /** Abre o daemon como root (su). [path] é o libtinkerd.so em nativeLibraryDir, onde o Android deixa executar. */
        fun start(path: String, onStderr: (String) -> Unit = {}): Daemon {
            val p = ProcessBuilder("su").start()
            Thread({ runCatching { p.errorStream.bufferedReader().forEachLine(onStderr) } }, "tinkerd-stderr")
                .apply { isDaemon = true }.start()
            val w = p.outputStream.bufferedWriter()
            w.write("exec $path\n")
            w.flush()
            return try {
                Daemon(w, p.inputStream.bufferedReader()) { p.destroy() }
            } catch (e: IOException) {
                p.destroy()
                throw e
            }
        }
    }
}
