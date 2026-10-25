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
    listOf("-", "0", ".", "::", "X", "OK"),  // X: cifrado como no GameGuardian (150X4)
)

// deitado: as mesmas teclas em 2 linhas largas (4 linhas de 48 dp tomavam a altura toda da tela)
private val KEYS_WIDE = listOf(
    listOf("7", "8", "9", "4", "5", "6", "⌫", "~"),
    listOf("1", "2", "3", "0", "-", ".", ";", "::", "X", "OK"),
)

// endereço (hexadecimal): letras a–f e o + para "7a0010+8"; o campo de endereço usava o teclado do telefone
private val KEYS_HEX = listOf(
    listOf("a", "b", "7", "8", "9", "⌫"),
    listOf("c", "d", "4", "5", "6", "+"),
    listOf("e", "f", "1", "2", "3", "0"),
    listOf("OK"),
)
private val KEYS_HEX_WIDE = listOf(
    listOf("a", "b", "c", "d", "e", "f", "+", "⌫"),
    listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "OK"),
)

/** Uma tecla aplicada ao texto: ⌫ apaga a última, o resto escreve. */
internal fun applyKey(text: String, key: String) = if (key == "⌫") text.dropLast(1) else text + key

/**
 * Teclado do Tinker: números, faixa (~), vários valores (;) e grupo (::distância). Fica dentro do painel, bem menor que o do
 * telefone, que abria por cima do jogo e escondia o número que você queria ler.
 */
@Composable
fun NumPad(onKey: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, hex: Boolean = false) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (if (hex) (if (compact) KEYS_HEX_WIDE else KEYS_HEX) else if (compact) KEYS_WIDE else KEYS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val ok = k == "OK"
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(MaterialTheme.shapes.small)
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
