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
}
