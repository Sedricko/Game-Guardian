package dev.tinker.app

import java.io.IOException

/** Conversa com o tinkerd, que roda como root via su. Cada comando devolve as linhas antes do "ok". */
class Daemon(path: String, onStderr: (String) -> Unit) {
    private val process = ProcessBuilder("su").start()
    private val input = process.outputStream.bufferedWriter()
    private val output = process.inputStream.bufferedReader()

    init {
        // o que o su/daemon escreve em stderr (ex.: recusa de root, erro do linker) vai para o registro
        Thread({ runCatching { process.errorStream.bufferedReader().forEachLine(onStderr) } }, "tinkerd-stderr").apply { isDaemon = true }.start()
        input.write("exec $path\n")
        input.flush()
        val hello = output.readLine() ?: throw IOException("Sem root: o su recusou ou não existe.")
        if (!hello.startsWith("ok tinkerd")) throw IOException("Daemon não respondeu: $hello")
    }

    fun close() = process.destroy()

    var isAttached = false
        private set

    class Reply(val lines: List<String>, val status: String)

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
}
