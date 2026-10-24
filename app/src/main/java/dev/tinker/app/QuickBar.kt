package dev.tinker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barra rápida sobre o jogo: buscar e refinar (com número ou ↑ ↓ = ≠) sem abrir o painel inteiro.
 * onKeyboard(true) deixa a janela receber o teclado só enquanto o campo é usado; no resto do tempo o jogo fica com o foco.
 */
@Composable
fun QuickBar(engine: Engine, onDrag: (Float, Float) -> Unit, onKeyboard: (Boolean) -> Unit, onOpenPanel: () -> Unit, onBubble: () -> Unit) {
    val s = Session
    var error by remember { mutableStateOf<String?>(null) }
    val run = rememberRun(engine) { error = it }
    val focus = LocalFocusManager.current
    val own = s.ownKeypad
    var pad by remember { mutableStateOf(false) }
    val enabled = s.process != null && !s.busy
    val n = s.count ?: 0
    val small = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    fun act(work: suspend () -> String) {
        pad = false
        focus.clearFocus()
        onKeyboard(false)
        run(work)
    }
    Column(
        Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            // Voltar com o teclado já fechado: devolve as teclas para o jogo
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back && e.type == KeyEventType.KeyUp) { focus.clearFocus(); onKeyboard(false); true } else false
            }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "⠿", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); onDrag(d.x, d.y) } }.padding(horizontal = 6.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                s.value, { s.value = it }, singleLine = true, placeholder = { Text("Valor") }, readOnly = own,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                // com o teclado do Tinker o toque só abre ele (a janela nem pega o foco); sem, pede o teclado do telefone
                // no primeiro toque, antes do campo tentar pegar o foco
                modifier = Modifier.weight(1f).pointerInput(own) {
                    awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial); if (own) pad = true else onKeyboard(true) }
                }
                    // saiu do campo (tocou fora, fechou o teclado): o jogo volta a receber as teclas
                    .onFocusChanged { if (!it.isFocused) onKeyboard(false) },
            )
            if (n > 0) FilledTonalButton(onClick = { act { engine.refine(s.value) } }, enabled = enabled && s.value.isNotBlank(), contentPadding = small) { Text("Refinar") }
            else Button(onClick = { act { engine.search(s.types, s.value) } }, enabled = enabled && s.value.isNotBlank(), contentPadding = small) { Text("Buscar") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (n > 0) {
                listOf("inc" to "↑", "dec" to "↓", "same" to "=", "changed" to "≠").forEach { (op, label) ->
                    OutlinedButton(onClick = { act { engine.compare(op) } }, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) {
                        Text(label, fontSize = 18.sp)
                    }
                }
            } else {
                OutlinedButton(onClick = { act { engine.startUnknown(s.types) } }, enabled = enabled, contentPadding = small) { Text("Sem valor") }
            }
            Text(
                when {
                    s.process == null -> "Escolha o jogo no painel ▢"
                    s.busy -> "…"
                    s.count == null -> "Digite e busque"
                    else -> "$n restantes"
                },
                Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2,
            )
            // ▢ abre o painel completo (editar, congelar, salvar); – volta para a bolinha
            TextButton(onClick = { onKeyboard(false); onOpenPanel() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("▢", fontSize = 20.sp) }
            TextButton(onClick = { onKeyboard(false); onBubble() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("–", fontSize = 22.sp) }
        }
        if (own && pad) NumPad(onKey = { s.value = applyKey(s.value, it) }, onDone = { pad = false })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
    }
}
