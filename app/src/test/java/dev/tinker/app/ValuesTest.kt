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

    @Test
    fun geminiBestModelFirst() {
        val got = rankGemini(listOf("gemini-2.0-flash", "gemini-3.0-pro", "gemini-3.0-flash-preview", "gemini-2.5-flash", "gemini-2.5-flash-lite",
            "gemini-2.5-flash-image", "text-embedding-004", "gemini-2.5-pro"))
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-3.0-flash-preview", "gemini-3.0-pro", "gemini-2.5-pro", "gemini-2.5-flash-lite"), got)
    }

    @Test
    fun aiCompareWords() {
        assertEquals("inc", compareOp("aumentou", ""))
        assertEquals("decby", compareOp("Diminuiu", "10"))
        assertEquals("same", compareOp("não mudou", ""))
        assertEquals("same", compareOp("igual", "5"))  // "quanto" só vale para aumentou/diminuiu
        assertEquals("changed", compareOp("mudou", ""))
        assertEquals(null, compareOp("talvez", ""))
    }

    @Test
    fun aiFindsSavedByNameAddressOrField() {
        val gold = SavedItem("7a10", "i32", "A", "100", name = "Ouro", il2 = "Game.PlayerData+18")
        val gem = SavedItem("7a14", "i32", "A", "5", name = "Gema")
        val copy = SavedItem("7b00", "i32", "A", "100", name = "Ouro tela", temp = true)
        val all = listOf(gold, gem, copy)
        assertEquals(listOf(gold, gem), matchSaved("ouro, 0x7a14", all))
        assertEquals(listOf(gold), matchSaved("PlayerData", all))
        assertEquals(listOf(gold, gem), matchSaved("todos", all))  // cópia de tela nunca entra
        assertEquals(emptyList<SavedItem>(), matchSaved("vida", all))
    }

    @Test
    fun classFilterFindsFieldNames() {
        val pd = GameClass("Game.PlayerData", 3, listOf("<Gold>k__BackingField", "gems", "level"))
        val ui = GameClass("Game.UI.GoldLabel", 1, listOf("text"))
        assertEquals(listOf("Gold"), classMatch(pd, searchWords("gold")))   // pelo campo, sem o k__BackingField
        assertEquals(emptyList<String>(), classMatch(ui, searchWords("gold")))  // pelo nome da classe
        assertEquals(listOf("gems"), classMatch(pd, searchWords("gem, hp")))
        assertEquals(null, classMatch(ui, searchWords("gem")))
        assertEquals(emptyList<String>(), classMatch(ui, searchWords("")))   // sem filtro: tudo aparece
    }

    @Test
    fun zeroResultsRetries() {
        val p = retryPlans("i32", "1250")
        assertEquals("i32,f32,i64,f64,xi32,xf32", p[0].first)
        assertEquals(Triple("i32x2,i64x2", "1250", "como inteiro em dobro (jogos em Flutter ou JavaScript)"), p[1])
        assertEquals("como valor cifrado (chave ao lado)", p[2].third)
        assertEquals(Triple("f32,f64", "1243.75~1256.25", "como número com casas decimais (1243.75~1256.25)"), p[3])
        assertEquals("12~13", retryPlans("i32,f32,i64,f64,xi32,xf32", "12.5").last().second)  // já tinha todos os tipos
        assertEquals(emptyList<Triple<String, String, String>>(), retryPlans("i32,f32,i64,f64,xi32,xf32", "100;200"))
    }

    @Test
    fun arm64Decoder() {
        assertEquals("SUB W8, W8, #1", decodeArm64(0x51000508, 0))
        assertEquals("STR W8, [X19, #0x10]", decodeArm64(0xb9001268.toInt(), 0))
        assertEquals("LDR X0, [X1]", decodeArm64(0xf9400020.toInt(), 0))
        assertEquals("MOV W0, W1", decodeArm64(0x2a0103e0, 0))
        assertEquals("B.NE 0x1008", decodeArm64(0x54000041, 0x1000))
        assertEquals("BL 0x1040", decodeArm64(0x94000010.toInt(), 0x1000))
        assertEquals("MOVZ W0, #5", decodeArm64(0x528000a0, 0))
        assertEquals("CMP W8, #0", decodeArm64(0x7100011f, 0))
        assertEquals("NOP", decodeArm64(ARM64_NOP, 0))
        assertEquals("RET", decodeArm64(0xd65f03c0.toInt(), 0))
        assertEquals(".word 0x00000000", decodeArm64(0, 0))
    }

    @Test
    fun groqResetHeader() {
        assertEquals(7.66, groqSecs("7.66s"), 0.001)
        assertEquals(62.5, groqSecs("1m2.5s"), 0.001)
        assertEquals(7380.0, groqSecs("2h3m"), 0.001)
        assertEquals(0.25, groqSecs("250ms"), 0.001)
        assertEquals(0.0, groqSecs(null), 0.001)
    }

    @Test
    fun returnPatchBytes() {
        val ret = "c0035fd6"
        assertEquals(ret, returnPatch(0x01, ""))                               // void: só RET
        assertEquals("20008052$ret", returnPatch(0x02, "true"))                // MOVZ W0, #1
        assertEquals("e07c8052$ret", returnPatch(0x08, "999"))
        assertEquals("00008012$ret", returnPatch(0x08, "-1"))                  // MOVN W0, #0
        assertEquals("0000805200f0a7720000271e$ret", returnPatch(0x0c, "1"))   // 1.0f: MOVZ+MOVK W0, FMOV S0, W0
        assertEquals("MOVK W0, #16256, LSL #16", decodeArm64(0x72a7f000, 0))
        assertEquals(null, returnPatch(0x08, "muito"))
        assertEquals(null, returnPatch(0x0e, "1"))                             // string
    }

    @Test
    fun ggXorSyntax() {
        assertEquals("150" to 4, ggXor("150X4"))
        assertEquals("100~200" to -8, ggXor("100~200x-8"))
        assertEquals(null, ggXor("150X3"))   // a chave fica de 4 em 4 bytes
        assertEquals(null, ggXor("0x8"))     // hexadecimal, não cifrado
        assertEquals(null, ggXor("150"))
        assertEquals("i32", plain("xi32@8"))
        assertEquals("i64", plain("i64x2"))
        assertEquals(4, TYPE_SIZES.getValue("xi32@-12"))
        assertEquals("Dword 🔒 8", TYPE_NAMES["xi32@8"])
        assertEquals("Qword ×2", TYPE_NAMES["i64x2"])
        assertEquals(false, "xi32@7x" in TYPE_SIZES)
    }

    @Test
    fun aiTestValueDiffersFromCurrent() {
        assertEquals("15106", testValue(15105.0, "i32"))
        assertEquals("101.5", testValue(100.0, "f32"))
        assertEquals("2.25", testValue(0.75, "xf32"))
    }

    @Test
    fun classFilterByWholeWords() {
        assertEquals(listOf("RingCount", "m_ringClip"), classMatch(GameClass("RunData", 3, listOf("<RingCount>k__BackingField", "m_ringClip", "m_titleString")), searchWords("ring")))
        assertEquals(null, classMatch(GameClass("AppInfo", 2, listOf("InfoString", "m_runnersRequired")), searchWords("ring")))  // String não é ring
        assertEquals(listOf("Player", "Data", "m", "ring", "Count", "UI"), nameWords("PlayerData.m_ringCount UI"))
        // nome inteiro em camelCase ou com pacote: as palavras em sequência
        assertEquals(emptyList<String>(), classMatch(GameClass("RunData", 3), searchWords("RunData")))
        assertEquals(emptyList<String>(), classMatch(GameClass("SonicDash.Cameras.CameraController", 9), searchWords("CameraController")))
        assertEquals(emptyList<String>(), classMatch(GameClass("SonicDash.UI.ResourceView", 9), searchWords("SonicDash.UI.ResourceView")))
        assertEquals(null, classMatch(GameClass("DataRun", 3), searchWords("RunData")))  // fora de ordem não vale
        assertEquals(listOf("FreeRedstarringsTotal"), classMatch(GameClass("Ev", 1, listOf("FreeRedstarringsTotal")), searchWords("RedStarRings")))
    }

    @Test
    fun memoryMergeKeepsAndAddsWithoutRepeating() {
        val old = "01/10 lição A\n02/10 lição B\n"
        assertEquals("01/10 lição A\n02/10 lição B\n05/10 lição C\n", mergeLines(old, "03/10 lição B\n05/10 lição C"))  // B já tinha, com outra data
        assertEquals("09/10 só nova\n", mergeLines("", "09/10 só nova"))
        assertEquals("", mergeLines("", ""))
    }

    @Test
    fun geminiWaitsOnlyWhenItComesBack() {
        assertEquals(37, geminiWait(429, """{"error":{"details":[{"retryDelay": "37s"}]}}"""))
        assertEquals(60, geminiWait(429, """{"error":{"message":"rate limit"}}"""))
        assertEquals(null, geminiWait(429, """{"error":{"message":"GenerateRequestsPerDayPerProjectPerModel"}}"""))  // cota do dia
        assertEquals(30, geminiWait(503, ""))
        assertEquals(null, geminiWait(400, ""))
    }

    @Test
    fun picksFastestGoodModel() {
        val ranked = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.8-pro", "gemini-3.8-flash-lite", "gemini-2.5-flash")
        assertEquals("gemini-3.8-flash", pickGemini(ranked, emptyMap()))  // sem medidas: o melhor
        assertEquals("gemini-3.5-flash", pickGemini(ranked, mapOf("gemini-3.8-flash" to 60_000L)))  // lento: experimenta o próximo
        assertEquals("gemini-3.8-flash", pickGemini(ranked, mapOf("gemini-3.8-flash" to 20_000L, "gemini-3.5-flash" to 25_000L)))
        assertEquals("gemini-3.8-pro", pickGemini(ranked, mapOf("gemini-3.8-flash" to 60_000L, "gemini-3.5-flash" to 50_000L)))
        assertEquals("gemini-3.5-flash", pickGemini(listOf("gemini-3.8-flash-lite", "gemini-3.5-flash"), emptyMap()))  // Lite fica de fora
        assertEquals("gemini-3.5-flash", pickGemini(ranked, emptyMap(), setOf("gemini-3.8-flash")))  // cota do dia acabou nele
    }

    @Test
    fun groqModelsAndWaits() {
        assertEquals(listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            rankGroq(listOf("whisper-large-v3", "llama-3.1-8b-instant", "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "meta-llama/llama-guard-4-12b")))
        assertEquals(true, groqVision("meta-llama/llama-4-scout-17b-16e-instruct"))
        assertEquals(false, groqVision("llama-3.3-70b-versatile"))
        assertEquals(8, groqWait(429, """{"error":{"message":"Rate limit reached ... tokens per minute (TPM) ... Please try again in 7.5s."}}"""))
        assertEquals(63, groqWait(429, "Please try again in 1m2.1s"))
        assertEquals(null, groqWait(429, "Rate limit reached on tokens per day (TPD): Limit 100000. Please try again in 12m3s"))
        assertEquals(20, groqWait(429, "rate limited"))
        assertEquals(10, groqWait(503, ""))
        assertEquals(null, groqWait(401, ""))
    }
}
