package dev.tinker.testgame

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Jogo de teste com as dificuldades de um jogo de verdade, para medir o Tinker (os valores certos são conhecidos):
 * - ouro (Dword) com 6 cópias falsas iguais (texto da tela, recorde, histórico), só uma é a de verdade;
 * - energia que sobe sozinha a cada segundo;
 * - gemas cifradas (chave + valor ^ chave) com chave nova a cada mudança, como o ObscuredInt do Unity;
 * - pontos guardados em dobro (como Dart/JavaScript), vida Float com decimal, diamantes em 64 bits;
 * - "Próxima fase" muda tudo para outra memória (o endereço antigo deixa de valer).
 * Tudo em memória nativa (fora do heap do Java, que move objetos), como num motor de jogo. Salva em SharedPreferences.
 * Não tem anti-cheat de propósito: o Tinker não é feito para escapar de proteção.
 */
class GameActivity : Activity() {
    private var mem: ByteBuffer = fresh()
    private val decoys = List(6) { fresh() }  // cópias do ouro que não mandam em nada
    private lateinit var text: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var fase = 1

    private fun fresh(): ByteBuffer = ByteBuffer.allocateDirect(96).order(ByteOrder.LITTLE_ENDIAN)

    // a cópia de verdade fica no meio dos campos, como num objeto de jogo
    private var gold: Int
        get() = mem.getInt(0)
        set(v) { mem.putInt(0, v); decoys.forEachIndexed { i, d -> d.putInt(4 * i, v) } }
    private var gems: Int
        get() = mem.getInt(8) xor mem.getInt(12)
        set(v) { val k = Random.nextInt(); mem.putInt(8, k); mem.putInt(12, v xor k) }
    private var hp: Float
        get() = mem.getFloat(16)
        set(v) { mem.putFloat(16, v) }
    private var energy: Int
        get() = mem.getInt(20)
        set(v) { mem.putInt(20, v) }
    private var points: Int
        get() = mem.getInt(24) / 2
        set(v) { mem.putInt(24, v * 2) }
    private var diamonds: Long
        get() = mem.getLong(32)
        set(v) { mem.putLong(32, v) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jogo", MODE_PRIVATE)
        gold = prefs.getInt("gold", Random.nextInt(1000, 9000))
        gems = prefs.getInt("gems", Random.nextInt(10, 90))
        hp = prefs.getFloat("hp", 87.5f)
        energy = Random.nextInt(10, 50)
        points = prefs.getInt("points", Random.nextInt(100, 900))
        diamonds = prefs.getLong("diamonds", 5_000_000_000L + Random.nextInt(1000))
        fun save() = prefs.edit().putInt("gold", gold).putInt("gems", gems).putFloat("hp", hp)
            .putInt("points", points).putLong("diamonds", diamonds).apply()
        text = TextView(this).apply { textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        fun button(label: String, act: () -> Unit) = Button(this).apply { text = label; setOnClickListener { act(); save(); show() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 30, 45))
            addView(text)
            addView(button("+1 ouro") { gold += 1 })
            addView(button("+1 gema") { gems += 1 })
            addView(button("-10 vida") { hp -= 10f })
            addView(button("+5 pontos") { points += 5 })
            addView(button("+1 diamante") { diamonds += 1 })
            addView(button("Próxima fase") { nextLevel() })
        })
        show()
        ui.post(object : Runnable { override fun run() { show(); ui.postDelayed(this, 500) } })
        ui.post(object : Runnable { override fun run() { energy += 1; ui.postDelayed(this, 1000) } })
    }

    /** Fase nova: os valores vão para outra memória e a antiga é apagada, como quando o jogo recria o jogador. */
    private fun nextLevel() {
        val old = mem
        mem = fresh()
        for (i in 0 until 48) mem.put(i, old.get(i))
        gems = gems  // chave nova
        for (i in 0 until 48) old.put(i, 0)
        fase++
    }

    private fun show() {
        text.text = "Fase $fase\nOuro: $gold\nGemas: $gems\nVida: ${"%.1f".format(hp)}\nEnergia: $energy\nPontos: $points\nDiamantes: $diamonds"
    }
}
