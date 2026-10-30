package dev.tinker.v2.core.daemon

import java.io.File
import kotlin.concurrent.thread

/**
 * Autoteste do módulo core/daemon: root, o ciclo de memória no próprio daemon e o jogo de teste (tests/target.c).
 * Não toca em nenhum jogo. Primeira linha = resumo (o agente do PC usa como título na aba Relatórios); falhas começam com "FALHA".
 */
object Autoteste {
    fun run(libDir: String, version: String): String {
        val lib = "$libDir/libtinkerd.so"
        val out = mutableListOf<String>()
        fun check(name: String, work: () -> String) {
            val t0 = System.currentTimeMillis()
            val r = runCatching(work)
            out += (if (r.isSuccess) "OK" else "FALHA") + " $name: " +
                (r.getOrNull() ?: r.exceptionOrNull()?.let { it.message ?: it.toString() }) + " (${System.currentTimeMillis() - t0} ms)"
        }

        check("daemon e root") {
            val d = Daemon.start(lib)
            try { d.call("info").status.also { require("uid=0" in it) { "sem root: $it" } } } finally { d.close() }
        }
        check("memória: busca, escrita, refino, congelamento e AOB (no próprio daemon)") {
            val d = Daemon.start(lib)
            try {
                val pid = d.call("info").status.substringAfter("pid=", "").substringBefore(' ')
                require(pid.isNotEmpty()) { "o daemon não disse o pid" }
                d.call("attach $pid")
                require(d.call("search i32 271828182").count >= 1) { "a busca não achou o valor de teste" }
                val hits = d.call("list 20").lines
                val addr = (hits.firstOrNull { " Cd " in it } ?: hits.firstOrNull())?.substringBefore(' ') ?: error("lista vazia")
                d.call("write $addr i32 314159")
                require(d.call("read $addr i32").status == "314159") { "a escrita não gravou" }
                require(d.call("refine 314159").count >= 1) { "o refino perdeu o valor" }
                d.call("freeze $addr i32 777")
                d.call("write $addr i32 1")
                Thread.sleep(400)
                require(d.call("read $addr i32").status == "777") { "o congelamento não segurou o valor" }
                d.call("unfreeze all")
                val hex = "TINKER-AUTOTESTE-AOB".toByteArray().joinToString("") { "%02x".format(it) }
                require(d.call("aobfind $hex").count >= 1) { "a AOB não achou o marcador" }
                "ok"
            } finally { d.close() }
        }
        val target = "$libDir/libtinkertarget.so"
        if (File(target).exists()) check("jogo de teste: anexar, buscar, escrever e refinar") { onTarget(lib, target) }
        else out += "FALHA jogo de teste: libtinkertarget.so não veio no APK"

        val ok = out.count { it.startsWith("OK") }
        return "Tinker 2 $version, núcleo daemon: $ok de ${out.size} ok\n" + out.joinToString("\n")
    }

    /** Abre o tests/target.c como root; a 1ª linha dele é "pid endereço-do-Dword endereço-do-Float …". */
    private fun onTarget(lib: String, target: String): String {
        val proc = ProcessBuilder("su", "-c", "exec $target").start()
        var info: List<String> = emptyList()
        thread { info = proc.inputStream.bufferedReader().readLine()?.trim()?.split(' ').orEmpty() }.join(5000)
        val pid = info.firstOrNull().orEmpty()
        try {
            require(info.size >= 3) { "o jogo de teste não abriu" }
            val addr = info[1]
            val d = Daemon.start(lib)
            try {
                d.call("attach $pid")
                require(d.call("search i32 123456789").count == 1L) { "não achou o Dword com 1 endereço" }
                d.call("write $addr i32 987654321")
                require(d.call("refine 987654321").count == 1L) { "o refino não ficou com 1 endereço" }
                require(d.call("read $addr i32").status == "987654321") { "a escrita não gravou" }
            } finally { d.close() }
            return "ok (pid $pid)"
        } finally {
            if (pid.isNotEmpty()) runCatching { ProcessBuilder("su", "-c", "kill -9 $pid").start().waitFor() }
            proc.destroy()
        }
    }
}
