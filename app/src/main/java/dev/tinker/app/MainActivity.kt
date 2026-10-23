package dev.tinker.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val types = listOf("i32" to "Dword", "f32" to "Float", "i64" to "Qword", "f64" to "Double", "i16" to "Word", "i8" to "Byte")
    private val worker = Executors.newSingleThreadExecutor()
    private var daemon: Daemon? = null
    private var results = listOf<Pair<String, String>>()

    private lateinit var process: Button
    private lateinit var type: Spinner
    private lateinit var value: EditText
    private lateinit var status: TextView
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        process = findViewById(R.id.process)
        type = findViewById(R.id.type)
        value = findViewById(R.id.value)
        status = findViewById(R.id.status)
        list = findViewById(R.id.results)
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types.map { it.second })

        process.setOnClickListener { chooseProcess() }
        findViewById<Button>(R.id.search).setOnClickListener {
            run { d -> d.call("search ${types[type.selectedItemPosition].first} ${typed()}").status + " resultados" }
        }
        findViewById<Button>(R.id.refine).setOnClickListener { run { d -> d.call("refine ${typed()}").status + " resultados" } }
        findViewById<Button>(R.id.editAll).setOnClickListener {
            ask("Novo valor para todos") { v -> run { d -> d.call("writeall $v").status.split(" ")[0] + " valores alterados" } }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val (addr, old) = results[pos]
            ask("Novo valor em $addr", old) { v -> run { d -> d.call("write $addr $v"); "Valor alterado" } }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun typed() = value.text.toString().trim().ifEmpty { "x" }

    /** Roda no daemon fora da UI; depois mostra a mensagem e recarrega a lista. */
    private fun run(action: (Daemon) -> String) {
        status.text = "Trabalhando…"
        worker.execute {
            try {
                val d = daemon ?: Daemon(applicationInfo.nativeLibraryDir + "/libtinkerd.so").also { daemon = it }
                val msg = action(d)
                val rows = if (d.isAttached) d.call("list 200").lines.map { it.substringBefore(' ') to it.substringAfter(' ') } else listOf()
                runOnUiThread {
                    status.text = msg
                    results = rows
                    list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.map { "${it.first}   →   ${it.second}" })
                }
            } catch (e: Exception) {
                if (e.message == "O daemon fechou.") daemon = null
                runOnUiThread { status.text = "Erro: ${e.message}"; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun chooseProcess() = run { d ->
        // apps têm nome de pacote; filtra serviços do sistema e o próprio Tinker
        val apps = d.call("ps").lines.map { it.substringBefore(' ') to it.substringAfter(' ') }
            .filter { '.' in it.second && !it.second.startsWith("/") && !it.second.startsWith(packageName) }
            .sortedBy { it.second }
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("Escolha o jogo")
                .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                    run { dd -> dd.call("attach ${apps[i].first}"); runOnUiThread { process.text = apps[i].second }; "Pronto. Digite um valor e toque em Nova busca." }
                }.show()
        }
        "${apps.size} apps abertos"
    }

    private fun ask(title: String, initial: String = "", then: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); inputType = value.inputType }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> then(input.text.toString().trim().ifEmpty { "x" }) }
            .setNegativeButton("Cancelar", null).show()
    }
}
