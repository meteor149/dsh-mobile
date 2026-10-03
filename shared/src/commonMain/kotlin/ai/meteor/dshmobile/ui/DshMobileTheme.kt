package ai.meteor.dshmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val DeepSeekBlue = Color(0xFF5E7198)
internal val DeepSeekBlueSoft = Color(0xFFEEF1F7)
internal val Ink = Color(0xFF181A1B)
internal val SecondaryInk = Color(0xFF73777B)
internal val CaptionInk = Color(0xFF767A7E)
internal val Canvas = Color(0xFFF5F5F3)
internal val Layer = Color(0xFFF0F0EE)
internal val Hairline = Color(0x10000000)
internal val Success = Color(0xFF337354)
internal val Warning = Color(0xFF95621F)

private val DshColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = DeepSeekBlue,
    onSecondary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = Layer,
    onSurfaceVariant = SecondaryInk,
    outline = Hairline,
    error = Color(0xFFEC1313),
)

private val DshTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.8).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun DshMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DshColorScheme,
        typography = DshTypography,
        content = content,
    )
}
