package dev.tinker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KEYS = listOf(
    listOf("7", "8", "9", "⌫"),
    listOf("4", "5", "6", "~"),
    listOf("1", "2", "3", ";"),
    listOf("-", "0", ".", "OK"),
)

/** Uma tecla aplicada ao texto: ⌫ apaga a última, o resto escreve. */
internal fun applyKey(text: String, key: String) = if (key == "⌫") text.dropLast(1) else text + key

/**
 * Teclado do Tinker: números, faixa (~) e vários valores (;). Fica dentro do painel, bem menor que o do
 * telefone, que abria por cima do jogo e escondia o número que você queria ler.
 */
@Composable
fun NumPad(onKey: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KEYS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { k ->
                    val ok = k == "OK"
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { if (ok) onDone() else onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(k, fontSize = 18.sp, color = if (ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
