package dev.tinker.testgame

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
 * - "Próxima fase" muda tudo para outra memória (o endereço antigo deixa de valer);
 * - XP escondido (não aparece na tela): sobe um tanto sorteado a cada ouro, só se acha por "valor desconhecido";
 * - madeira mostrada como inteiro mas guardada como Float (comum no Unity);
 * - poções num inventário de 40 posições com números pequenos repetidos (a busca acha centenas);
 * - tempo (Float, segundos) que corre 10 vezes por segundo e abates (Dword) que sobem de 1 em intervalos curtos e
 *   irregulares, como um cronômetro e um contador de mortes de verdade;
 * - "Atualizar jogo" troca a ordem dos campos, como uma versão nova do jogo (o que estava salvo deixa de valer).
 * Tudo em memória nativa (fora do heap do Java, que move objetos), como num motor de jogo. Salva em SharedPreferences.
 * Não tem anti-cheat de propósito: o Tinker não é feito para escapar de proteção.
 * Para o agente do PC: os botões também respondem a "am broadcast -a dev.tinker.testgame.ACAO --es acao '<texto do botão>'"
 * e o que a tela mostra vai para files/estado.txt (o leitor de tela do Android trava com a tela mudando sem parar).
 */
class GameActivity : Activity() {
    private var mem: ByteBuffer = fresh()
    private val decoys = List(6) { fresh() }  // cópias do ouro que não mandam em nada
    private lateinit var text: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var fase = 1
    private lateinit var prefs: android.content.SharedPreferences
    private val actions = LinkedHashMap<String, () -> Unit>()
    private var shown = ""
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { actions[i.getStringExtra("acao")]?.let { it(); save(); show() } }
    }
    private var base = 0  // onde começam os campos: "Atualizar jogo" muda, como uma versão nova
    private val inv = fresh()  // 40 posições do inventário (Dword cada)

    private fun fresh(): ByteBuffer = ByteBuffer.allocateDirect(160).order(ByteOrder.LITTLE_ENDIAN)

    // a cópia de verdade fica no meio dos campos, como num objeto de jogo
    private var gold: Int
        get() = mem.getInt(base)
        set(v) { mem.putInt(base, v); decoys.forEachIndexed { i, d -> d.putInt(4 * i, v) } }
    private var gems: Int
        get() = mem.getInt(base + 8) xor mem.getInt(base + 12)
        set(v) { val k = Random.nextInt(); mem.putInt(base + 8, k); mem.putInt(base + 12, v xor k) }
    private var hp: Float
        get() = mem.getFloat(base + 16)
        set(v) { mem.putFloat(base + 16, v) }
    private var energy: Int
        get() = mem.getInt(base + 20)
        set(v) { mem.putInt(base + 20, v) }
    private var points: Int
        get() = mem.getInt(base + 24) / 2
        set(v) { mem.putInt(base + 24, v * 2) }
    private var diamonds: Long
        get() = mem.getLong(base + 32)
        set(v) { mem.putLong(base + 32, v) }

    private var xp: Int
        get() = mem.getInt(base + 40)
        set(v) { mem.putInt(base + 40, v) }
    private var wood: Int
        get() = mem.getFloat(base + 44).toInt()
        set(v) { mem.putFloat(base + 44, v.toFloat()) }
    private var time: Float
        get() = mem.getFloat(base + 48)
        set(v) { mem.putFloat(base + 48, v) }
    private var kills: Int
        get() = mem.getInt(base + 52)
        set(v) { mem.putInt(base + 52, v) }
    private var potions: Int
        get() = inv.getInt(7 * 4)
        set(v) { inv.putInt(7 * 4, v) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jogo", MODE_PRIVATE)
        gold = prefs.getInt("gold", Random.nextInt(1000, 9000))
        gems = prefs.getInt("gems", Random.nextInt(10, 90))
        hp = prefs.getFloat("hp", 87.5f)
        energy = Random.nextInt(10, 50)
        points = prefs.getInt("points", Random.nextInt(100, 900))
        diamonds = prefs.getLong("diamonds", 5_000_000_000L + Random.nextInt(1000))
        xp = prefs.getInt("xp", Random.nextInt(100, 5000))
        wood = prefs.getInt("wood", Random.nextInt(5, 60))
        for (i in 0 until 40) inv.putInt(i * 4, Random.nextInt(0, 10))
        potions = prefs.getInt("potions", Random.nextInt(1, 9))
        text = TextView(this).apply { textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        actions["+1 ouro"] = { gold += 1; xp += Random.nextInt(3, 10) }
        actions["+1 gema"] = { gems += 1 }
        actions["-10 vida"] = { hp -= 10f }
        actions["+5 pontos"] = { points += 5 }
        actions["+1 diamante"] = { diamonds += 1 }
        actions["+1 madeira"] = { wood += 1 }
        actions["Usar poção"] = { if (potions > 0) potions -= 1 }
        actions["Achar poção"] = { potions += 1 }
        actions["Próxima fase"] = { nextLevel() }
        actions["Atualizar jogo"] = { update() }
        val grid = android.widget.GridLayout(this).apply { columnCount = 2 }
        actions.forEach { (label, act) -> grid.addView(Button(this).apply { text = label; setOnClickListener { act(); save(); show() } }) }
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.rgb(20, 30, 45))
            addView(LinearLayout(this@GameActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text)
                addView(grid)
            })
        })
        val filter = IntentFilter("dev.tinker.testgame.ACAO")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
        show()
        ui.post(object : Runnable { override fun run() { show(); ui.postDelayed(this, 500) } })
        ui.post(object : Runnable { override fun run() { energy += 1; ui.postDelayed(this, 1000) } })
        time = 0f
        kills = 0
        ui.post(object : Runnable { override fun run() { time += 0.1f; ui.postDelayed(this, 100) } })
        ui.post(object : Runnable { override fun run() { kills += 1; ui.postDelayed(this, Random.nextLong(300, 1200)) } })
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun save() = prefs.edit().putInt("gold", gold).putInt("gems", gems).putFloat("hp", hp)
        .putInt("points", points).putLong("diamonds", diamonds).putInt("xp", xp).putInt("wood", wood).putInt("potions", potions).apply()

    /** Fase nova: os valores vão para outra memória e a antiga é apagada, como quando o jogo recria o jogador. */
    private fun nextLevel() {
        val old = mem
        mem = fresh()
        for (i in 0 until 160) mem.put(i, old.get(i))
        gems = gems  // chave nova
        for (i in 0 until 160) old.put(i, 0)
        fase++
    }

    /** "Versão nova" do jogo: os campos andam 56 bytes (ida e volta) e vão para outra memória. */
    private fun update() {
        val vals = listOf(gold, gems, energy, points, xp, wood, kills); val h = hp; val dm = diamonds; val tm = time
        val old = mem
        mem = fresh()
        for (i in 0 until 160) old.put(i, 0)
        base = if (base == 0) 56 else 0
        gold = vals[0]; gems = vals[1]; energy = vals[2]; points = vals[3]; xp = vals[4]; wood = vals[5]; kills = vals[6]; hp = h; diamonds = dm; time = tm
    }

    private fun show() {
        val t = "Fase $fase · versão ${if (base == 0) 1 else 2}\nOuro: $gold\nGemas: $gems\nVida: ${"%.1f".format(hp)}\nEnergia: $energy\nPontos: $points\nDiamantes: $diamonds\nMadeira: $wood\nPoções: $potions\nTempo: ${"%.1f".format(java.util.Locale.ROOT, time)}\nAbates: $kills"
        if (t == shown) return  // a tela só muda quando um valor muda
        shown = t
        text.text = t
        java.io.File(filesDir, "estado.txt").writeText(t)
    }
}
