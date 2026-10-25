package dev.tinker.app

/**
 * Leitor de instruções ARM64 (as mais comuns em jogo): para a aba Código mostrar "SUB W8, W8, #1" em vez de 08 05 00 51,
 * e a IA entender o que um patch muda. O que não reconhece aparece como .word (os bytes continuam editáveis).
 */
internal const val ARM64_NOP = 0xd503201f.toInt()

private fun reg(r: Int, x: Boolean, sp: Boolean = false) = when {
    r == 31 && sp -> if (x) "SP" else "WSP"
    r == 31 -> if (x) "XZR" else "WZR"
    else -> (if (x) "X" else "W") + r
}

private fun hex(v: Long) = if (v < 0) "-0x" + (-v).toString(16) else "0x" + v.toString(16)

private val CONDS = listOf("EQ", "NE", "CS", "CC", "MI", "PL", "VS", "VC", "HI", "LS", "GE", "LT", "GT", "LE", "AL", "NV")

/** w: a instrução (4 bytes, little-endian já montado); pc: o endereço dela, para mostrar o destino dos pulos. */
fun decodeArm64(w: Int, pc: Long): String {
    val rd = w and 31
    val rn = (w ushr 5) and 31
    val sf = (w ushr 31) and 1 == 1
    fun signed(v: Int, bits: Int) = (v shl (32 - bits)) shr (32 - bits)
    return when {
        w == ARM64_NOP -> "NOP"
        w == 0xd65f03c0.toInt() -> "RET"
        w and 0xfc000000.toInt() == 0x14000000 -> "B ${hex(pc + signed(w and 0x3ffffff, 26) * 4L)}"
        w and 0xfc000000.toInt() == 0x94000000.toInt() -> "BL ${hex(pc + signed(w and 0x3ffffff, 26) * 4L)}"
        w and 0xff000010.toInt() == 0x54000000 -> "B.${CONDS[w and 15]} ${hex(pc + signed((w ushr 5) and 0x7ffff, 19) * 4L)}"
        w and 0x7e000000 == 0x34000000 ->
            (if ((w ushr 24) and 1 == 0) "CBZ" else "CBNZ") + " ${reg(rd, sf)}, ${hex(pc + signed((w ushr 5) and 0x7ffff, 19) * 4L)}"
        // somar/subtrair um número: o "tira 1 da madeira" costuma ser um destes
        w and 0x1f000000 == 0x11000000 -> {
            val sub = (w ushr 30) and 1 == 1
            val setFlags = (w ushr 29) and 1 == 1
            val imm = ((w ushr 10) and 0xfff).toLong() shl (if ((w ushr 22) and 1 == 1) 12 else 0)
            when {
                sub && setFlags && rd == 31 -> "CMP ${reg(rn, sf, true)}, #$imm"
                !sub && !setFlags && imm == 0L && (rd == 31 || rn == 31) -> "MOV ${reg(rd, sf, true)}, ${reg(rn, sf, true)}"
                else -> (if (sub) "SUB" else "ADD") + (if (setFlags) "S" else "") + " ${reg(rd, sf, !setFlags)}, ${reg(rn, sf, true)}, #$imm"
            }
        }
        w and 0x1f200000 == 0x0b000000 -> {
            val sub = (w ushr 30) and 1 == 1
            val setFlags = (w ushr 29) and 1 == 1
            val rm = (w ushr 16) and 31
            if (sub && setFlags && rd == 31) "CMP ${reg(rn, sf)}, ${reg(rm, sf)}"
            else (if (sub) "SUB" else "ADD") + (if (setFlags) "S" else "") + " ${reg(rd, sf)}, ${reg(rn, sf)}, ${reg(rm, sf)}"
        }
        w and 0x7fe0ffe0 == 0x2a0003e0 -> "MOV ${reg(rd, sf)}, ${reg((w ushr 16) and 31, sf)}"
        w and 0x1f800000 == 0x12800000 -> {
            val name = when ((w ushr 29) and 3) { 0 -> "MOVN"; 2 -> "MOVZ"; 3 -> "MOVK"; else -> return ".word 0x%08x".format(w) }
            val shift = ((w ushr 21) and 3) * 16
            "$name ${reg(rd, sf)}, #${(w ushr 5) and 0xffff}" + if (shift > 0) ", LSL #$shift" else ""
        }
        // ler/guardar na memória com deslocamento: o "guarda a madeira nova" costuma ser um STR
        w and 0x3b000000 == 0x39000000 -> {
            val size = (w ushr 30) and 3
            val vector = (w ushr 26) and 1 == 1
            val load = (w ushr 22) and 3 != 0
            val off = ((w ushr 10) and 0xfff).toLong() shl size
            val name = (if (load) "LDR" else "STR") + if (vector) "" else when (size) { 0 -> "B"; 1 -> "H"; else -> "" }
            val r = if (vector) (when (size) { 2 -> "S"; 3 -> "D"; 1 -> "H"; else -> "B" }) + rd else reg(rd, size == 3)
            "$name $r, [${reg(rn, true, true)}" + (if (off != 0L) ", #${hex(off)}" else "") + "]"
        }
        else -> ".word 0x%08x".format(w)
    }
}
