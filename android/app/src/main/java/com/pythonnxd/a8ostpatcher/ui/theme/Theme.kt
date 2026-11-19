package com.pythonnxd.a8ostpatcher.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pythonnxd.a8ostpatcher.engine.AppThemeMode

@Immutable
data class A8ExtendedColors(
    val ground: Color,
    val surface: Color,
    val raised: Color,
    val border: Color,
    val rule: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val glassBg: Color,
    val capsuleBg: Color,
    val capsuleInk: Color,
    val isLight: Boolean,
)

val LocalAppColors = compositionLocalOf { darkExtendedColors() }
val LocalReduceMotion = compositionLocalOf { false }

private fun darkExtendedColors() = A8ExtendedColors(
    ground = Color(0xFF090A0E),
    surface = Color(0xFF13161D),
    raised = Color(0xFF1B1F29),
    border = Color(0x18FFFFFF),
    rule = Color(0x10FFFFFF),
    text = Color(0xFFF5F7FA),
    muted = Color(0xFFA1A8B5),
    faint = Color(0xFF7B8290),
    accent = Color(0xFFE58F12),
    accentInk = Color(0xFFFFAA2B),
    accentSoft = Color(0x28E58F12),
    ok = Color(0xFF34D399),
    warn = Color(0xFFF59E0B),
    danger = Color(0xFFEB6060),
    glassBg = Color(0xD8161922),
    capsuleBg = Color(0xFFFFFFFF),
    capsuleInk = Color(0xFF0B0C0F),
    isLight = false,
)

private fun lightExtendedColors() = A8ExtendedColors(
    ground = Color(0xFFF6F7FA),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFECEFF5),
    border = Color(0x18000000),
    rule = Color(0x10000000),
    text = Color(0xFF11141C),
    muted = Color(0xFF525A6A),
    faint = Color(0xFF7A8396),
    accent = Color(0xFFD97706),
    accentInk = Color(0xFFB45309),
    accentSoft = Color(0x1FD97706),
    ok = Color(0xFF059669),
    warn = Color(0xFFD97706),
    danger = Color(0xFFDC2626),
    glassBg = Color(0xE6FFFFFF),
    capsuleBg = Color(0xFF11141C),
    capsuleInk = Color(0xFFFFFFFF),
    isLight = true,
)

private fun materialYouDarkFallback() = A8ExtendedColors(
    ground = Color(0xFF14110D),
    surface = Color(0xFF201B15),
    raised = Color(0xFF2B241C),
    border = Color(0x22FFD7A8),
    rule = Color(0x14FFD7A8),
    text = Color(0xFFF9EFE4),
    muted = Color(0xFFCBBBA9),
    faint = Color(0xFF978776),
    accent = Color(0xFFFFB86B),
    accentInk = Color(0xFFFFCA8C),
    accentSoft = Color(0x2EFFB86B),
    ok = Color(0xFF4ADE80),
    warn = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    glassBg = Color(0xDE221C16),
    capsuleBg = Color(0xFFFFDDB8),
    capsuleInk = Color(0xFF261808),
    isLight = false,
)

private fun materialYouLightFallback() = A8ExtendedColors(
    ground = Color(0xFFFDF6EE),
    surface = Color(0xFFF3E7D8),
    raised = Color(0xFFE7D7C4),
    border = Color(0x2E82562C),
    rule = Color(0x1A82562C),
    text = Color(0xFF24180D),
    muted = Color(0xFF68503C),
    faint = Color(0xFF8C725B),
    accent = Color(0xFFA85208),
    accentInk = Color(0xFF8E4304),
    accentSoft = Color(0x24A85208),
    ok = Color(0xFF15803D),
    warn = Color(0xFFB45309),
    danger = Color(0xFFB91C1C),
    glassBg = Color(0xE8F2E6D7),
    capsuleBg = Color(0xFF24180D),
    capsuleInk = Color(0xFFFDF6EE),
    isLight = true,
)

private fun fromDynamicScheme(scheme: ColorScheme, isLight: Boolean): A8ExtendedColors {
    return A8ExtendedColors(
        ground = scheme.background,
        surface = scheme.surfaceContainerLow,
        raised = scheme.surfaceContainerHigh,
        border = scheme.outlineVariant.copy(alpha = 0.65f),
        rule = scheme.outlineVariant.copy(alpha = 0.38f),
        text = scheme.onBackground,
        muted = scheme.onSurfaceVariant,
        faint = scheme.onSurfaceVariant.copy(alpha = 0.72f),
        accent = scheme.primary,
        accentInk = scheme.primary,
        accentSoft = scheme.primaryContainer.copy(alpha = if (isLight) 0.75f else 0.45f),
        ok = if (isLight) Color(0xFF15803D) else Color(0xFF34D399),
        warn = if (isLight) Color(0xFFB45309) else Color(0xFFF59E0B),
        danger = scheme.error,
        glassBg = scheme.surfaceContainerHigh.copy(alpha = 0.90f),
        capsuleBg = scheme.primary,
        capsuleInk = scheme.onPrimary,
        isLight = isLight,
    )
}

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun A8PatcherTheme(
    themeMode: AppThemeMode,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamic = themeMode.isMaterialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseExtended = when {
        useDynamic && themeMode.isLight -> fromDynamicScheme(dynamicLightColorScheme(context), isLight = true)
        useDynamic && !themeMode.isLight -> fromDynamicScheme(dynamicDarkColorScheme(context), isLight = false)
        themeMode == AppThemeMode.MATERIAL_YOU_LIGHT -> materialYouLightFallback()
        themeMode == AppThemeMode.MATERIAL_YOU_DARK -> materialYouDarkFallback()
        themeMode == AppThemeMode.LIGHT -> lightExtendedColors()
        else -> darkExtendedColors()
    }

    val spec: AnimationSpec<Color> = if (reduceMotion) snap() else tween(durationMillis = 220)
    val animated = A8ExtendedColors(
        ground = animateColorAsState(baseExtended.ground, spec, label = "ground").value,
        surface = animateColorAsState(baseExtended.surface, spec, label = "surface").value,
        raised = animateColorAsState(baseExtended.raised, spec, label = "raised").value,
        border = animateColorAsState(baseExtended.border, spec, label = "border").value,
        rule = animateColorAsState(baseExtended.rule, spec, label = "rule").value,
        text = animateColorAsState(baseExtended.text, spec, label = "text").value,
        muted = animateColorAsState(baseExtended.muted, spec, label = "muted").value,
        faint = animateColorAsState(baseExtended.faint, spec, label = "faint").value,
        accent = animateColorAsState(baseExtended.accent, spec, label = "accent").value,
        accentInk = animateColorAsState(baseExtended.accentInk, spec, label = "accentInk").value,
        accentSoft = animateColorAsState(baseExtended.accentSoft, spec, label = "accentSoft").value,
        ok = baseExtended.ok,
        warn = baseExtended.warn,
        danger = baseExtended.danger,
        glassBg = animateColorAsState(baseExtended.glassBg, spec, label = "glassBg").value,
        capsuleBg = animateColorAsState(baseExtended.capsuleBg, spec, label = "capsuleBg").value,
        capsuleInk = animateColorAsState(baseExtended.capsuleInk, spec, label = "capsuleInk").value,
        isLight = baseExtended.isLight,
    )

    val m3Scheme = if (useDynamic) {
        if (themeMode.isLight) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
    } else if (themeMode.isLight) {
        lightColorScheme(
            primary = animated.accent,
            onPrimary = Color.White,
            background = animated.ground,
            onBackground = animated.text,
            surface = animated.surface,
            onSurface = animated.text,
            surfaceVariant = animated.raised,
            onSurfaceVariant = animated.muted,
            error = animated.danger,
            outline = animated.border,
        )
    } else {
        darkColorScheme(
            primary = animated.accent,
            onPrimary = Color(0xFF0B0C0F),
            background = animated.ground,
            onBackground = animated.text,
            surface = animated.surface,
            onSurface = animated.text,
            surfaceVariant = animated.raised,
            onSurfaceVariant = animated.muted,
            error = animated.danger,
            outline = animated.border,
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides animated,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = AppTypography,
            content = content,
        )
    }
}
