package dev.tinker.v2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tinker.v2.core.daemon.Autoteste
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val version get() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // agente do PC: am start -n dev.tinker.v2/.MainActivity --ez autoteste true; ele lê files/autoteste.txt pelo adb
        if (savedInstanceState == null && intent?.getBooleanExtra("autoteste", false) == true) thread {
            val r = runCatching { Autoteste.run(applicationInfo.nativeLibraryDir, version) }.getOrElse { "FALHA autoteste: $it" }
            File(filesDir, "autoteste.tmp").apply { writeText(r + "\n") }.renameTo(File(filesDir, "autoteste.txt"))
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Tinker 2 · $version", style = MaterialTheme.typography.titleLarge)
                        DaemonSection()
                    }
                }
            }
        }
    }

    // uma seção por módulo; por enquanto só o núcleo (core/daemon)
    @androidx.compose.runtime.Composable
    private fun DaemonSection() {
        var result by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Núcleo · daemon", style = MaterialTheme.typography.titleMedium)
                Button(enabled = !busy, onClick = {
                    busy = true
                    result = "Testando…"
                    thread {
                        val r = runCatching { Autoteste.run(applicationInfo.nativeLibraryDir, version) }.getOrElse { "FALHA: ${it.message}" }
                        runOnUiThread { result = r; busy = false }
                    }
                }) { Text("Rodar autoteste") }
                if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
