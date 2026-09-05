package ai.moonlite.btdroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A survey-instrument palette rather than dynamic colour: the app should look
// the same on any phone, and match the project's written documentation.
private val Teal = Color(0xFF1F6F6B)
private val TealLight = Color(0xFF5FBFB3)
private val ContourBrown = Color(0xFF8C5A2B)
private val ContourAmber = Color(0xFFD89A5C)
private val WarnMagenta = Color(0xFFA8386B)
private val WarnPink = Color(0xFFE7799F)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = ContourBrown,
    onSecondary = Color.White,
    tertiary = WarnMagenta,
    background = Color(0xFFFBFAF7),
    onBackground = Color(0xFF221E1A),
    surface = Color(0xFFF2EFE9),
    onSurface = Color(0xFF221E1A),
    surfaceVariant = Color(0xFFE7E2D8),
    onSurfaceVariant = Color(0xFF4A433B),
    error = Color(0xFFB3261E),
    outline = Color(0xFFD9D2C5),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF04312D),
    secondary = ContourAmber,
    onSecondary = Color(0xFF33261A),
    tertiary = WarnPink,
    background = Color(0xFF15130F),
    onBackground = Color(0xFFEDE8DE),
    surface = Color(0xFF1E1B15),
    onSurface = Color(0xFFEDE8DE),
    surfaceVariant = Color(0xFF2A251D),
    onSurfaceVariant = Color(0xFFC6BEB1),
    error = Color(0xFFF2B8B5),
    outline = Color(0xFF38322A),
)

/**
 * Monospace for anything the device said: addresses, registers, counts,
 * transcript lines. Keeping machine values visually distinct from prose makes
 * a screen readable at a glance in bad light.
 */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    ),
)

@Composable
fun BtdroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
