package dev.tinker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuesTest {
    @Test
    fun parseValuesAceitaNumerosEFaixas() {
        val r = parseValues("50; 100~200 ;7")
        assertEquals(listOf(50.0..50.0, 100.0..200.0, 7.0..7.0), r)
        assertEquals(listOf(100.0..200.0), parseValues("200~100"))  // ordem invertida vira faixa normal
    }

    @Test
    fun normalizeNumbersEntendeOFormatoDoJogo() {
        assertEquals("20000;50000", normalizeNumbers("20.000;50.000"))
        assertEquals("1.5", normalizeNumbers("1,5"))
        assertEquals("1234.5", normalizeNumbers("1.234,5"))
        assertEquals("1.5", normalizeNumbers("1.5"))  // decimal comum continua decimal
        assertEquals("1150~1250", normalizeNumbers("1.150 ~ 1.250"))
        assertEquals("100;200", normalizeNumbers(" 100 ; ;200 "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseValuesSemNumeroFalha() {
        parseValues("abc; ~")
    }

    @Test
    fun matchesAnyInteiroExatoEFaixa() {
        assertTrue(matchesAny("150", "i32", parseValues("100~200")))
        assertFalse(matchesAny("201", "i32", parseValues("100~200")))
        assertTrue(matchesAny("75", "i32", parseValues("50;75")))
        assertFalse(matchesAny("74", "i32", parseValues("50;75")))
        assertFalse(matchesAny("?", "i32", parseValues("1")))  // leitura que falhou
    }

    @Test
    fun matchesAnyFloatTemFolga() {
        assertTrue(matchesAny("1.10000002", "f32", parseValues("1.1")))  // 1.1f guardado na memória
        assertFalse(matchesAny("1.2", "f32", parseValues("1.1")))
        assertFalse(matchesAny("1.10000002", "i32", parseValues("1.1")))  // inteiro não tem folga
    }

    @Test
    fun readableDeixaDecimalLegivelETiraNaN() {
        assertEquals("0.1", readable("0.10000000000000001", "f64"))
        assertEquals("0.1", readable("0.100000001", "f32"))
        assertEquals("10000000000", readable("10000000000", "f64"))
        assertEquals("100", readable("100", "f64"))
        assertEquals(null, readable("-nan", "f64"))
        assertEquals(null, readable("inf", "f32"))
        assertEquals("?", readable("?", "f64"))
        assertEquals("123456789", readable("123456789", "i32"))
    }

    @Test
    fun pointerTextMostraComoOCheatEngine() {
        assertEquals("[[libgame.so+1A2B30]+18]+40", pointerText("libgame.so+1a2b30 18 40"))
        assertEquals("base.apk@1f000+10", pointerText("base.apk@1f000+10"))
    }

    @Test
    fun cifradoUsaOTipoBase() {
        assertEquals("0.1", readable("0.100000001", "xf32"))
        assertEquals("777777", readable("777777", "xi32"))
        assertTrue(matchesAny("1.10000002", "xf32", parseValues("1.1")))
        assertEquals("i32", plain("xi32"))
    }

    @Test
    fun compactLogMantemAsAcoesDoTinker() {
        val log = listOf("24/10 01:00:00.000 > search i32 5") + List(100) { "24/10 01:00:01.000 | linha $it" } + "24/10 01:00:02.000 < ok 3"
        val out = compactLog(log, keep = 2)
        assertEquals(listOf(
            "24/10 01:00:00.000 > search i32 5", "24/10 01:00:01.000 | linha 0", "24/10 01:00:01.000 | linha 1",
            "(98 linhas do logcat omitidas aqui; veja a seção Logcat do jogo)", "24/10 01:00:02.000 < ok 3",
        ), out)
    }

    @Test
    fun enderecoComOffset() {
        assertEquals("7a0018", parseAddress("7A0010+8"))
        assertEquals("79fff0", parseAddress("0x7a0010-0x20"))
        assertEquals("7a0010", parseAddress(" 7a0010 "))
        assertEquals(null, parseAddress("xyz"))
        assertEquals(null, parseAddress("10-20"))
        assertEquals("+0x8", offsetText(8))
        assertEquals("-0x20", offsetText(-32))
    }

    @Test
    fun assinaturaTiraOValorEOsPonteiros() {
        // 8 bytes fixos, o valor (4 bytes) e um ponteiro de heap (0xb400007a12345678, com a marca do arm64)
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 9, 9, 0, 0, 0, 0) +
            byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x7a, 0, 0, 0xb4.toByte())
        val sig = signature(raw, off = 8, vlen = 4)
        assertEquals("0102030405060708" + "????????" + "00000000" + "????????????????", sig)
        assertEquals("01??03", mergeSig("010203", "01ff03"))
        assertFalse(sigUseful("????????00000000"))
        assertTrue(sigUseful("0102030405060708090a0b0c????"))
        assertFalse(sigUseful("000000000000000000000000000001"))  // só zeros não identifica nada
    }

    @Test
    fun versaoNova() {
        assertTrue(newer("1.10.0", "1.9.2"))
        assertTrue(newer("1.5", "1.4.1"))
        assertFalse(newer("1.4.1", "1.4.1"))
        assertFalse(newer("1.4.0", "1.4.1"))
    }

    @Test
    fun historicoEFaixa() {
        var item = SavedItem("10", "i32", "A", "1070")
        assertTrue(plausible(item, "999999"))  // sem histórico não descarta
        item = recordValue(item, "1070", 1_000)
        item = recordValue(item, "1200", 61_000)
        item = recordValue(item, "1200", 62_000)  // repetido não entra de novo
        item = recordValue(item, "1331", 121_000)
        assertEquals(listOf("1:1070", "61:1200", "121:1331"), item.history)
        assertEquals(1070.0, item.seenMin, 0.0)
        assertEquals(1331.0, item.seenMax, 0.0)
        assertTrue(plausible(item, "1500"))
        assertFalse(plausible(item, "4000000"))
        assertFalse(plausible(item, "?"))
        assertEquals("1070 → 1200 → 1331 (há 9s) · faixa 1070–1331", historyText(item, 130_000))
    }

    @Test
    fun fichaAdivinhaOsTipos() {
        fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        val raw = le(0xb400007a12345678uL.toLong(), 8) +                       // ponteiro (com a marca do arm64)
            le(1331, 4) + le(java.lang.Float.floatToIntBits(99.5f).toLong(), 4) +  // inteiro e decimal
            le(0x5A3C9D21, 4) + le((777777 xor 0x5A3C9D21).toLong(), 4) +        // cifrado
            le(0, 4) + le(0, 4)
        val rows = fichaRows(0x1000, raw, 0x1000, null)
        assertEquals(listOf("ponteiro", "inteiro", "decimal", "cifrado 🔒", "zero", "zero"), rows.map { it.label })
        assertEquals(listOf("0x7a12345678", "1331", "99.5", "777777", "0", "0"), rows.map { it.value })
        assertEquals(listOf(0L, 8L, 12L, 16L, 24L, 28L), rows.map { it.off })
        assertEquals("1010", rows[3].addr)
        // com IL2CPP: nome e tipo do jogo, offset contado do começo do objeto
        val il2 = Il2Class("Game.Player", "ff8", listOf(Il2Field(0x10, 0x08, "gold"), Il2Field(0x14, 0x0c, "hp"), Il2Field(0x18, 0x0e, "nome")))
        val named = fichaRows(0x1000, raw, 0xff8, il2)
        assertEquals(listOf("gold · int", "hp · float", "nome · string"), named.map { it.label })
        assertEquals(listOf("1331", "99.5"), named.take(2).map { it.value })
    }

    @Test
    fun crashesOnlyOfTheGame() {
        val log = listOf(
            "--------- beginning of crash", "F libc : Fatal signal 11 in tid 1 (UnityGfx)", "F DEBUG : >>> com.outro <<<",
            "F libc : Fatal signal 6 in tid 2 (main)", "F DEBUG : >>> com.jogo <<<",
        )
        assertEquals(listOf("F libc : Fatal signal 6 in tid 2 (main)", "F DEBUG : >>> com.jogo <<<", "(1 falhas de outros apps omitidas)"), crashesOf(log, "com.jogo"))
        assertEquals(listOf("Nenhuma falha de com.x (2 de outros apps omitidas)"), crashesOf(log, "com.x"))
        assertEquals(log, crashesOf(log, null))
    }
}
