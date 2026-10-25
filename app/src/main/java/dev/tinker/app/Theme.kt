package dev.tinker.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Tema escuro fixo: o app fica sobre jogos, e a identidade não deve mudar com o papel de parede.
private val Colors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF134E48),
    onPrimaryContainer = Color(0xFFB2F5EA),
    secondaryContainer = Color(0xFF253041),
    onSecondaryContainer = Color(0xFFD6E2F0),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF9AA5B4),
    surfaceContainerLow = Color(0xFF131922),
    surfaceContainer = Color(0xFF161D27),
    surfaceContainerHigh = Color(0xFF1C2430),
    surfaceContainerHighest = Color(0xFF232C39),
    outline = Color(0xFF3B4655),
    outlineVariant = Color(0xFF2A3340),
    error = Color(0xFFFF8A80),
)

// cantos arredondados em tudo: campos de texto (extraSmall), chips (small), cards (medium) e painéis
private val TinkerShapes = Shapes(
    extraSmall = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TinkerTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, shapes = TinkerShapes, content = content)
