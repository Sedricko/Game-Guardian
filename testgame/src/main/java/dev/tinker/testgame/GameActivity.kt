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
 * Jogo de teste: ouro (Dword normal), gemas (Dword cifrado como o ObscuredInt do Unity: chave + valor ^ chave) e vida
 * (Float). Ficam em memória nativa (fora do heap do Java, que move objetos), como num motor de jogo; a tela relê a cada
 * meio segundo, então uma edição do Tinker aparece nela. Salva em SharedPreferences, para o editor de save.
 */
class GameActivity : Activity() {
    private val mem: ByteBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
    private lateinit var text: TextView
    private val ui = Handler(Looper.getMainLooper())

    private var gold: Int
        get() = mem.getInt(0)
        set(v) { mem.putInt(0, v) }
    private var gems: Int
        get() = mem.getInt(8) xor mem.getInt(12)
        set(v) { mem.putInt(12, v xor mem.getInt(8)) }
    private var hp: Float
        get() = mem.getFloat(16)
        set(v) { mem.putFloat(16, v) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jogo", MODE_PRIVATE)
        mem.putInt(8, Random.nextInt())  // chave sorteada a cada abertura, como no Anti-Cheat Toolkit
        gold = prefs.getInt("gold", Random.nextInt(1000, 9000))
        gems = prefs.getInt("gems", Random.nextInt(10, 90))
        hp = prefs.getFloat("hp", 100f)
        fun save() = prefs.edit().putInt("gold", gold).putInt("gems", gems).putFloat("hp", hp).apply()
        text = TextView(this).apply { textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        fun button(label: String, act: () -> Unit) = Button(this).apply { text = label; setOnClickListener { act(); save(); show() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 30, 45))
            addView(text)
            addView(button("+1 ouro") { gold += 1 })
            addView(button("+1 gema") { gems += 1 })
            addView(button("-10 vida") { hp -= 10f })
        })
        show()
        ui.post(object : Runnable { override fun run() { show(); ui.postDelayed(this, 500) } })
    }

    private fun show() {
        text.text = "Ouro: $gold\nGemas: $gems\nVida: ${"%.1f".format(hp)}"
    }
}
