package dev.tinker.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

val TYPES = listOf("i32" to "Dword", "f32" to "Float", "i64" to "Qword", "f64" to "Double", "i16" to "Word", "i8" to "Byte")

data class GameProcess(val pid: Int, val pkg: String, val label: String, val icon: ImageBitmap?)
data class Hit(val addr: String, val value: String)

/** Estado da tela; fica fora da Activity para sobreviver a rotação e troca de tema. */
object Session {
    var process by mutableStateOf<GameProcess?>(null)
    var type by mutableIntStateOf(0)
    var value by mutableStateOf("")
    var status by mutableStateOf("Escolha o jogo para começar.")
    var count by mutableStateOf<Long?>(null)
    var results by mutableStateOf(listOf<Hit>())
    var busy by mutableStateOf(false)
}

/** Ponte entre a tela e o daemon; tudo roda fora da thread de UI. */
class Engine(private val app: Context) {
    private var daemon: Daemon? = null

    private suspend fun <T> call(work: (Daemon) -> T): T = withContext(Dispatchers.IO) {
        val d = daemon ?: Daemon(app.applicationInfo.nativeLibraryDir + "/libtinkerd.so").also { daemon = it }
        try {
            work(d)
        } catch (e: IOException) {
            if (e.message == "O daemon fechou.") daemon = null
            throw e
        }
    }

    suspend fun apps(): List<GameProcess> = call { d ->
        val pm = app.packageManager
        d.call("ps").lines.mapNotNull { line ->
            val pid = line.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            val pkg = line.substringAfter(' ')
            // só o processo principal de apps instalados pelo usuário
            if (':' in pkg || '.' !in pkg || pkg.startsWith("/") || pkg == app.packageName) return@mapNotNull null
            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            GameProcess(pid, pkg, pm.getApplicationLabel(info).toString(), info.loadIcon(pm).toImageBitmap())
        }.sortedBy { it.label.lowercase() }
    }

    suspend fun attach(p: GameProcess): String = call { d ->
        d.call("attach ${p.pid}")
        "Pronto. Digite um valor que você vê no jogo e toque em Nova busca."
    }

    suspend fun search(type: Int, value: String): String = call { d ->
        "${d.call("search ${TYPES[type].first} ${value.ifBlank { "x" }}").status} encontrados. Mude o valor no jogo e refine."
    }

    suspend fun refine(value: String): String = call { d -> "${d.call("refine ${value.ifBlank { "x" }}").status} restantes." }

    suspend fun write(addr: String, value: String): String = call { d -> d.call("write $addr ${value.ifBlank { "x" }}"); "Valor alterado." }

    suspend fun writeAll(value: String): String = call { d ->
        val (ok, fail) = d.call("writeall ${value.ifBlank { "x" }}").status.split(" ")
        if (fail == "0") "$ok valores alterados." else "$ok alterados, $fail falharam."
    }

    suspend fun list(): Pair<Long?, List<Hit>> = call { d ->
        if (!d.isAttached) return@call null to emptyList()
        val r = d.call("list 200")
        r.status.toLongOrNull() to r.lines.map { Hit(it.substringBefore(' '), it.substringAfter(' ')) }
    }
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, 96, 96)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
