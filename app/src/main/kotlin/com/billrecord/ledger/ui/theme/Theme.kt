package com.billrecord.ledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val Ink = Color(0xFF172033)
val Paper = Color(0xFFF7F9FC)
val Jade = Color(0xFF0B7E75)
val Rust = Color(0xFFEB5A66)
val Brass = Color(0xFFE8A13A)
val Slate = Color(0xFF2F6FED)
val Hairline = Color(0xFFE5EAF0)
val Moss = Color(0xFF18A56F)
val WarmWhite = Color(0xFFFFFFFF)
val SoftJade = Color(0xFFE4F7F4)
val SoftRust = Color(0xFFFFEEF0)
val SoftBrass = Color(0xFFFFF5E5)
val SoftBlue = Color(0xFFEAF2FF)

@Immutable
data class LedgerSemanticColors(
    val income: Color,
    val expense: Color,
    val warning: Color,
    val info: Color,
    val incomeContainer: Color,
    val expenseContainer: Color,
    val warningContainer: Color,
    val infoContainer: Color,
)

private val LightLedgerColors = LedgerSemanticColors(
    income = Moss,
    expense = Rust,
    warning = Brass,
    info = Slate,
    incomeContainer = Color(0xFFE8F7F0),
    expenseContainer = SoftRust,
    warningContainer = SoftBrass,
    infoContainer = SoftBlue,
)

private val DarkLedgerColors = LedgerSemanticColors(
    income = Color(0xFF5DD6A1),
    expense = Color(0xFFFF8D96),
    warning = Color(0xFFFFC56B),
    info = Color(0xFF80A9FF),
    incomeContainer = Color(0xFF163C31),
    expenseContainer = Color(0xFF47252D),
    warningContainer = Color(0xFF49371F),
    infoContainer = Color(0xFF22385E),
)

private val LocalLedgerSemanticColors = staticCompositionLocalOf { LightLedgerColors }

val MaterialTheme.ledgerColors: LedgerSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalLedgerSemanticColors.current

private val LightColors = lightColorScheme(
    primary = Jade,
    onPrimary = Color.White,
    primaryContainer = SoftJade,
    onPrimaryContainer = Color(0xFF07564F),
    secondary = Slate,
    onSecondary = Color.White,
    tertiary = Brass,
    background = Paper,
    onBackground = Ink,
    surface = WarmWhite,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF4F8),
    onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFF9AA5B5),
    outlineVariant = Hairline,
    error = Color(0xFFC93F4C),
    errorContainer = SoftRust,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF52D2C6),
    onPrimary = Color(0xFF043E39),
    primaryContainer = Color(0xFF174C48),
    onPrimaryContainer = Color(0xFFB7F4ED),
    secondary = Color(0xFF80A9FF),
    onSecondary = Color(0xFF102D63),
    tertiary = Color(0xFFFFC56B),
    background = Color(0xFF101827),
    onBackground = Color(0xFFF4F7FB),
    surface = Color(0xFF182235),
    onSurface = Color(0xFFF4F7FB),
    surfaceVariant = Color(0xFF223047),
    onSurfaceVariant = Color(0xFFAEB9C9),
    outline = Color(0xFF75849A),
    outlineVariant = Color(0xFF2B394D),
    error = Color(0xFFFF8D96),
    errorContainer = Color(0xFF47252D),
)

private val LedgerTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun LedgerTheme(darkTheme: Boolean = isSystemInDarkTheme(), accent: String = "JADE", content: @Composable () -> Unit) {
    val accentColor = when (accent) { "RUST" -> Color(0xFFC44754); "BRASS" -> Color(0xFF9B6B15); "SLATE" -> Slate; else -> Jade }
    val darkAccent = when (accent) { "RUST" -> Color(0xFFFF8D96); "BRASS" -> Color(0xFFFFC56B); "SLATE" -> Color(0xFF80A9FF); else -> Color(0xFF52D2C6) }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLedgerSemanticColors provides if (darkTheme) DarkLedgerColors else LightLedgerColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors.copy(primary = darkAccent) else LightColors.copy(primary = accentColor),
            typography = LedgerTypography,
            shapes = LedgerShapes,
            content = content,
        )
    }
}
