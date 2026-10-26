package dev.tinker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
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

private val ABC = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYM = listOf("1234567890", "-_./:@#?!", "()+*=,'\"%")

/** Teclado de letras do Tinker: o do telefone abre por cima do jogo e esconde o que você quer ver. */
@Composable
fun TextPad(onKey: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var shift by remember { mutableStateOf(false) }
    var sym by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (if (sym) SYM else ABC).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { c ->
                    val k = if (shift) c.uppercase() else c.toString()
                    PadKey(k, Modifier.weight(1f)) { onKey(k); shift = false }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PadKey(if (sym) "ABC" else "123", Modifier.weight(1.4f)) { sym = !sym }
            if (!sym) PadKey(if (shift) "⇧ ✓" else "⇧", Modifier.weight(1.2f)) { shift = !shift }
            PadKey("espaço", Modifier.weight(3f)) { onKey(" ") }
            PadKey("⌫", Modifier.weight(1.2f)) { onKey("⌫") }
            PadKey("OK", Modifier.weight(1.4f), ok = true) { onDone() }
        }
    }
}

@Composable
private fun PadKey(label: String, modifier: Modifier, ok: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(MaterialTheme.shapes.small)
            .background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, color = if (ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
}

/**
 * Abre o teclado do Tinker num toque de verdade. Encostar o dedo e arrastar (rolar a lista por cima do campo) cancela o
 * toque e não abre: antes abria só de encostar, e rolar a Busca deixava o teclado cobrindo a tela.
 */
@Composable
fun OnTap(taps: MutableInteractionSource, action: () -> Unit) {
    LaunchedEffect(taps) { taps.interactions.collect { if (it is PressInteraction.Release) action() } }
}

/** Campo de texto com o teclado do Tinker (Ajustes → Teclado do Tinker); desligado, é o campo normal com o do telefone. */
@Composable
fun TinkerTextField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    placeholder: String = "", mono: Boolean = false, singleLine: Boolean = true,
) {
    val own = Session.ownKeypad
    var pad by remember { mutableStateOf(false) }
    val taps = remember { MutableInteractionSource() }
    OnTap(taps) { if (Session.ownKeypad) pad = true }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NoPhoneKeyboard(own) { OutlinedTextField(
            value, onChange, label = { Text(label) }, placeholder = if (placeholder.isEmpty()) null else { { Text(placeholder) } },
            singleLine = singleLine, interactionSource = taps,
            textStyle = if (mono) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
            modifier = Modifier.fillMaxWidth(),
        ) }
        if (own && pad) TextPad(onKey = { onChange(applyKey(value, it)) }, onDone = { pad = false })
    }
}

/**
 * Campo normal (cursor, segurar para Copiar e Colar do Android), mas sem o teclado do telefone: com o teclado do Tinker
 * ligado, o pedido de teclado do campo é engolido aqui, e as teclas vêm do teclado do Tinker.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun NoPhoneKeyboard(block: Boolean, content: @Composable () -> Unit) {
    if (!block) content()
    else androidx.compose.ui.platform.InterceptPlatformTextInput(interceptor = { _, _ -> kotlinx.coroutines.awaitCancellation() }) { content() }
}
