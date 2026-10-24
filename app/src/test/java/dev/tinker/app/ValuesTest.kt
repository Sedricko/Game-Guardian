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
}
