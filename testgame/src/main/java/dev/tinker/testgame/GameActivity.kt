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
    // ouro + bônus na lib nativa: o Tinker acha jogo_bonus_ouro pelo nome e fixa o retorno (missão "função pelo nome")
    private external fun ganhaOuro(ouro: Int): Int
    private external fun tick()
    companion object { init { System.loadLibrary("jogo") } }

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
    // relógio como o do Unity: 0.99999994 no começo, dois tempos em segundos (Double) em +20 e +28 e a escala do
    // tempo (timeScale, 1.0) logo depois; o tempo do jogo corre na velocidade dela (missão "velocidade do jogo")
    private val clock = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN).apply {
        putFloat(0, Float.fromBits(0x3F7FFFFE)); putDouble(20, 100.0); putDouble(28, 100.02); putFloat(44, 1f)
    }
    private val speed get() = clock.getFloat(44)
    // construção: a hora de terminar (ms desde 1970) num Long, como os jogos de espera (missão "cronômetros")
    private val build = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
    // loja: itens de 16 bytes (código, preço, estoque); comprar a espada desconta o preço do ouro (missão "loja")
    private val shop = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN).apply {
        intArrayOf(150, 320, 75).forEachIndexed { i, p -> putInt(i * 16, i + 1); putInt(i * 16 + 4, p); putInt(i * 16 + 8, 9) }
    }
    // guardados diferente: nível como ×10+7 e moedas XOR uma chave fixa (missões "guardado diferente")
    private val odd = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(0, 3 * 10 + 7); putInt(16, 40 xor 0x5A17C3) }
    private val level get() = (odd.getInt(0) - 7) / 10
    private val coins get() = odd.getInt(16) xor 0x5A17C3
    // nomes que o agente offline nunca viu (nem o Claude ensinou), cada um guardado de um jeito: a curva de aprendizagem
    // mede se ele acha e aprende sozinho. Gabarito (Cristais = Dword ×10, disfarçado): Rubis = Double, Sementes = Qword (acima de 4 bilhões: um Dword não confunde), Honra = Dword, Brilho = Float
    private var unk = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN).apply {
        putDouble(0, Random.nextInt(20, 80).toDouble()); putLong(8, 9_000_000_000L + Random.nextInt(1000)); putInt(16, Random.nextInt(40, 99)); putFloat(20, Random.nextInt(10, 60).toFloat())
        putInt(24, Random.nextInt(10, 60) * 10)  // Cristais: guardado ×10 (disfarçado; a tela mostra /10)
        Random.nextInt().let { k -> putInt(32, k); putInt(28, Random.nextInt(20, 90) xor k) }  // Runas (aqui o unk ainda não existe)
    }
    // Runas (nível 4): cifrado como os jogos protegidos, valor XOR chave com a chave 4 bytes depois, chave nova a cada mudança
    private var runas: Int
        get() = unk.getInt(28) xor unk.getInt(32)
        set(v) { val k = Random.nextInt(); unk.putInt(32, k); unk.putInt(28, v xor k) }
    private val buildLeft get() = ((build.getLong(8) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

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
        // as gemas também ficam num save JSON na pasta externa (Android/data): o editor de save do Tinker acha como ext/files/progresso.json
        runCatching { org.json.JSONObject(java.io.File(getExternalFilesDir(null), "progresso.json").readText()).getInt("gems") }.onSuccess { gems = it }
        hp = prefs.getFloat("hp", 87.5f)
        energy = Random.nextInt(10, 50)
        points = prefs.getInt("points", Random.nextInt(100, 900))
        diamonds = prefs.getLong("diamonds", 5_000_000_000L + Random.nextInt(1000))
        xp = prefs.getInt("xp", Random.nextInt(100, 5000))
        wood = prefs.getInt("wood", Random.nextInt(5, 60))
        for (i in 0 until 40) inv.putInt(i * 4, Random.nextInt(0, 10))
        potions = prefs.getInt("potions", Random.nextInt(1, 9))
        text = TextView(this).apply { textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        actions["+1 ouro"] = { gold = ganhaOuro(gold); xp += Random.nextInt(3, 10) }  // o bônus vem da lib nativa (libjogo.so)
        actions["+1 gema"] = { gems += 1 }
        actions["-10 vida"] = { hp -= 10f }
        actions["+5 pontos"] = { points += 5 }
        actions["+1 diamante"] = { diamonds += 1 }
        actions["+1 madeira"] = { wood += 1 }
        actions["Usar poção"] = { if (potions > 0) potions -= 1 }
        actions["Achar poção"] = { potions += 1 }
        actions["Próxima fase"] = { nextLevel() }
        actions["+1 nível"] = { odd.putInt(0, (level + 1) * 10 + 7) }
        actions["+3 níveis"] = { odd.putInt(0, (level + 3) * 10 + 7) }
        actions["+1 moeda"] = { odd.putInt(16, (coins + 1) xor 0x5A17C3) }
        actions["+5 moedas"] = { odd.putInt(16, (coins + 5) xor 0x5A17C3) }
        actions["Comprar espada"] = { val p = shop.getInt(4); if (gold >= p) gold -= p }
        actions["Construir"] = { build.putLong(8, System.currentTimeMillis() + 2 * 3600_000L) }
        actions["Atualizar jogo"] = { update() }
        actions["+1 rubi"] = { unk.putDouble(0, unk.getDouble(0) + 1) }
        actions["+1 semente"] = { unk.putLong(8, unk.getLong(8) + 1) }
        actions["+1 honra"] = { unk.putInt(16, unk.getInt(16) + 1) }
        actions["+1 brilho"] = { unk.putFloat(20, unk.getFloat(20) + 1) }
        actions["+1 cristal"] = { unk.putInt(24, unk.getInt(24) + 10) }
        actions["+1 runa"] = { runas += 1 }
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
        save()  // o save JSON externo já existe desde a primeira abertura (o editor de save do Tinker acha ele)
        show()
        ui.post(object : Runnable { override fun run() { show(); ui.postDelayed(this, 500) } })
        ui.post(object : Runnable { override fun run() { energy += 1; ui.postDelayed(this, 1000) } })
        time = 0f
        kills = 0
        // como o Unity: o passo é o tempo real desde a última volta (com teto, como o maximumDeltaTime), não 0,1 fixo;
        // com o emulador ocupado o laço roda menos vezes e o tempo do jogo atrasava (a missão da velocidade falhava)
        var last = android.os.SystemClock.uptimeMillis()
        ui.post(object : Runnable { override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = ((now - last) / 1000.0).coerceAtMost(0.5)
            last = now
            clock.putDouble(20, clock.getDouble(20) + dt); clock.putDouble(28, clock.getDouble(20) + 0.02)
            time += dt.toFloat() * speed; tick(); ui.postDelayed(this, 100)
        } })
        ui.post(object : Runnable { override fun run() { kills += 1; ui.postDelayed(this, Random.nextLong(300, 1200)) } })
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun save() {
        prefs.edit().putInt("gold", gold).putInt("gems", gems).putFloat("hp", hp)
            .putInt("points", points).putLong("diamonds", diamonds).putInt("xp", xp).putInt("wood", wood).putInt("potions", potions).apply()
        runCatching { java.io.File(getExternalFilesDir(null), "progresso.json").writeText(org.json.JSONObject().put("gems", gems).toString()) }
    }

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
        // nível 5: os valores novos (Rubis, Honra, Cristais…) também mudam de lugar, como numa atualização de verdade
        val nu = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 64) { nu.put(i, unk.get(i)); unk.put(i, 0) }
        unk = nu
    }

    private fun show() {
        val t = "Fase $fase · versão ${if (base == 0) 1 else 2}\nOuro: $gold\nGemas: $gems\nVida: ${"%.1f".format(hp)}\nEnergia: $energy\nPontos: $points\nDiamantes: $diamonds\nMadeira: $wood\nPoções: $potions\nTempo: ${"%.1f".format(java.util.Locale.ROOT, time)}\nAbates: $kills\nVelocidade: ${"%.1f".format(java.util.Locale.ROOT, speed)}\nObra: $buildLeft\nEspada: ${shop.getInt(4)}\nNivel: $level\nMoedas: $coins" +
            "\nRubis: ${unk.getDouble(0).toLong()}\nSementes: ${unk.getLong(8)}\nHonra: ${unk.getInt(16)}\nBrilho: ${unk.getFloat(20).toInt()}\nCristais: ${unk.getInt(24) / 10}\nRunas: $runas"
        if (t == shown) return  // a tela só muda quando um valor muda
        shown = t
        text.text = t
        java.io.File(filesDir, "estado.txt").writeText(t)
    }
}
