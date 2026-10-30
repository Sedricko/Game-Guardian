package dev.tinker.v2.core.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringWriter

class DaemonTest {
    private fun daemon(replies: String, sent: StringWriter = StringWriter()) = Daemon(sent, replies.reader().buffered())

    @Test fun linhasAntesDoOk() {
        val sent = StringWriter()
        val d = daemon("ok tinkerd 1\n10 i32 5\n20 i32 5\nok 2\n", sent)
        val r = d.call("search i32 5")
        assertEquals(listOf("10 i32 5", "20 i32 5"), r.lines)
        assertEquals(2L, r.count)
        assertEquals("search i32 5\n", sent.toString())
    }

    @Test fun anexarMarcaOJogo() {
        val d = daemon("ok tinkerd 1\nok\n")
        assertFalse(d.isAttached)
        d.call("attach 42")
        assertTrue(d.isAttached)
    }

    @Test fun erroViraExcecaoComOMotivo() {
        val e = runCatching { daemon("ok tinkerd 1\nerr o jogo fechou\n").call("read 10 i32") }.exceptionOrNull()
        assertEquals("o jogo fechou", (e as IOException).message)
    }

    @Test fun semRootOuDaemonErrado() {
        assertEquals("Sem root: o su recusou ou não existe.", runCatching { daemon("") }.exceptionOrNull()?.message)
        assertTrue(runCatching { daemon("Permission denied\n") }.exceptionOrNull()?.message!!.startsWith("O daemon não respondeu"))
    }

    @Test fun daemonFechouNoMeio() {
        assertEquals("O daemon fechou.", runCatching { daemon("ok tinkerd 1\nlinha\n").call("list 5") }.exceptionOrNull()?.message)
    }
}
