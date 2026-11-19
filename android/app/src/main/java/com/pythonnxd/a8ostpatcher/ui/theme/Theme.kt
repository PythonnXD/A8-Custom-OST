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
    // Apple Liquid Glass optical tokens
    val glassDockTint: Color,
    val glassDockFallback: Color,
    val glassCardBg: Color,
    val glassCardRaised: Color,
    val glassSpecularTop: Color,
    val glassSpecularMid: Color,
    val glassSpecularBottom: Color,
    val glassSheenTop: Color,
    val glassCapsuleDragBg: Color,
    val glassCapsuleBorder: Color,
    val ambientGlowA: Color,
    val ambientGlowB: Color,
    val isLight: Boolean,
)

val LocalAppColors = compositionLocalOf { darkExtendedColors() }
val LocalReduceMotion = compositionLocalOf { false }

private fun darkExtendedColors() = A8ExtendedColors(
    ground = Color(0xFF07090E),
    surface = Color(0xFF131722),
    raised = Color(0xFF1C2230),
    border = Color(0x22FFFFFF),
    rule = Color(0x14FFFFFF),
    text = Color(0xFFF6F8FC),
    muted = Color(0xFFAAB2C2),
    faint = Color(0xFF7D8698),
    accent = Color(0xFFE59417),
    accentInk = Color(0xFFFFB43B),
    accentSoft = Color(0x2EE59417),
    ok = Color(0xFF34D399),
    warn = Color(0xFFF59E0B),
    danger = Color(0xFFEF6464),
    glassBg = Color(0xB8141824),
    capsuleBg = Color(0xFFF4F7FD),
    capsuleInk = Color(0xFF0B0D12),
    glassDockTint = Color(0x72161B28),
    glassDockFallback = Color(0xEB151A26),
    glassCardBg = Color(0xA6161B26),
    glassCardRaised = Color(0xBA202736),
    glassSpecularTop = Color(0x4AFFFFFF),
    glassSpecularMid = Color(0x1CFFFFFF),
    glassSpecularBottom = Color(0x2BFFFFFF),
    glassSheenTop = Color(0x16FFFFFF),
    glassCapsuleDragBg = Color(0x66FFFFFF),
    glassCapsuleBorder = Color(0x88FFFFFF),
    ambientGlowA = Color(0x24E59417),
    ambientGlowB = Color(0x223B82F6),
    isLight = false,
)

private fun lightExtendedColors() = A8ExtendedColors(
    ground = Color(0xFFEEF2F8),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFE6EBF3),
    border = Color(0x1C0F172A),
    rule = Color(0x120F172A),
    text = Color(0xFF0E131D),
    muted = Color(0xFF4B5568),
    faint = Color(0xFF737E94),
    accent = Color(0xFFD97706),
    accentInk = Color(0xFFB45309),
    accentSoft = Color(0x24D97706),
    ok = Color(0xFF059669),
    warn = Color(0xFFD97706),
    danger = Color(0xFFDC2626),
    glassBg = Color(0xCCFFFFFF),
    capsuleBg = Color(0xFF111622),
    capsuleInk = Color(0xFFFFFFFF),
    glassDockTint = Color(0x8AFFFFFF),
    glassDockFallback = Color(0xF0F9FBFF),
    glassCardBg = Color(0xBAFFFFFF),
    glassCardRaised = Color(0xD4F2F5FA),
    glassSpecularTop = Color(0xEEFFFFFF),
    glassSpecularMid = Color(0x1E0F172A),
    glassSpecularBottom = Color(0x280F172A),
    glassSheenTop = Color(0x88FFFFFF),
    glassCapsuleDragBg = Color(0x99FFFFFF),
    glassCapsuleBorder = Color(0xFFFFFFFF),
    ambientGlowA = Color(0x24F59E0B),
    ambientGlowB = Color(0x2460A5FA),
    isLight = true,
)

private fun materialYouDarkFallback() = A8ExtendedColors(
    ground = Color(0xFF120F0B),
    surface = Color(0xFF201A14),
    raised = Color(0xFF2D251C),
    border = Color(0x28FFD8AD),
    rule = Color(0x16FFD8AD),
    text = Color(0xFFFAF1E7),
    muted = Color(0xFFD0C0AE),
    faint = Color(0xFF9C8B7A),
    accent = Color(0xFFFFB86B),
    accentInk = Color(0xFFFFCE94),
    accentSoft = Color(0x30FFB86B),
    ok = Color(0xFF4ADE80),
    warn = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    glassBg = Color(0xBA221C15),
    capsuleBg = Color(0xFFFFDFBA),
    capsuleInk = Color(0xFF261808),
    glassDockTint = Color(0x78241D16),
    glassDockFallback = Color(0xEC231C15),
    glassCardBg = Color(0xA6231D16),
    glassCardRaised = Color(0xBC30271E),
    glassSpecularTop = Color(0x4CFFECD6),
    glassSpecularMid = Color(0x1EFFD8AD),
    glassSpecularBottom = Color(0x2CFFD8AD),
    glassSheenTop = Color(0x18FFE8CC),
    glassCapsuleDragBg = Color(0x5EFFDFBA),
    glassCapsuleBorder = Color(0x88FFE6C7),
    ambientGlowA = Color(0x28FFB86B),
    ambientGlowB = Color(0x1E9A6B42),
    isLight = false,
)

private fun materialYouLightFallback() = A8ExtendedColors(
    ground = Color(0xFFF7EFE5),
    surface = Color(0xFFFFFBF6),
    raised = Color(0xFFEBDCCB),
    border = Color(0x2B82562C),
    rule = Color(0x1882562C),
    text = Color(0xFF24180D),
    muted = Color(0xFF634C38),
    faint = Color(0xFF8B715A),
    accent = Color(0xFFA85208),
    accentInk = Color(0xFF8E4304),
    accentSoft = Color(0x26A85208),
    ok = Color(0xFF15803D),
    warn = Color(0xFFB45309),
    danger = Color(0xFFB91C1C),
    glassBg = Color(0xD2FFFBF6),
    capsuleBg = Color(0xFF26180C),
    capsuleInk = Color(0xFFFFF9F2),
    glassDockTint = Color(0x90FFF8F0),
    glassDockFallback = Color(0xF2FAF1E6),
    glassCardBg = Color(0xBCFFFDF9),
    glassCardRaised = Color(0xD4F2E5D5),
    glassSpecularTop = Color(0xF2FFFFFF),
    glassSpecularMid = Color(0x2282562C),
    glassSpecularBottom = Color(0x2E82562C),
    glassSheenTop = Color(0x8AFFFFFF),
    glassCapsuleDragBg = Color(0x9EFFFBF6),
    glassCapsuleBorder = Color(0xFFFFFFFF),
    ambientGlowA = Color(0x22D97706),
    ambientGlowB = Color(0x1EC28A5B),
    isLight = true,
)

private fun fromDynamicScheme(scheme: ColorScheme, isLight: Boolean): A8ExtendedColors {
    return A8ExtendedColors(
        ground = scheme.background,
        surface = scheme.surfaceContainerLow,
        raised = scheme.surfaceContainerHigh,
        border = scheme.outlineVariant.copy(alpha = 0.62f),
        rule = scheme.outlineVariant.copy(alpha = 0.36f),
        text = scheme.onBackground,
        muted = scheme.onSurfaceVariant,
        faint = scheme.onSurfaceVariant.copy(alpha = 0.74f),
        accent = scheme.primary,
        accentInk = scheme.primary,
        accentSoft = scheme.primaryContainer.copy(alpha = if (isLight) 0.78f else 0.46f),
        ok = if (isLight) Color(0xFF15803D) else Color(0xFF34D399),
        warn = if (isLight) Color(0xFFB45309) else Color(0xFFF59E0B),
        danger = scheme.error,
        glassBg = scheme.surfaceContainerHigh.copy(alpha = if (isLight) 0.80f else 0.72f),
        capsuleBg = scheme.primary,
        capsuleInk = scheme.onPrimary,
        glassDockTint = scheme.surfaceContainerHigh.copy(alpha = if (isLight) 0.62f else 0.50f),
        glassDockFallback = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
        glassCardBg = scheme.surfaceContainer.copy(alpha = if (isLight) 0.76f else 0.68f),
        glassCardRaised = scheme.surfaceContainerHigh.copy(alpha = if (isLight) 0.86f else 0.76f),
        glassSpecularTop = if (isLight) Color(0xEEFFFFFF) else Color(0x46FFFFFF),
        glassSpecularMid = scheme.outlineVariant.copy(alpha = if (isLight) 0.34f else 0.30f),
        glassSpecularBottom = if (isLight) scheme.outline.copy(alpha = 0.22f) else Color(0x28FFFFFF),
        glassSheenTop = if (isLight) Color(0x80FFFFFF) else Color(0x16FFFFFF),
        glassCapsuleDragBg = if (isLight) Color(0x9EFFFFFF) else scheme.primary.copy(alpha = 0.44f),
        glassCapsuleBorder = if (isLight) Color(0xFFFFFFFF) else Color(0x8CFFFFFF),
        ambientGlowA = scheme.primary.copy(alpha = if (isLight) 0.16f else 0.20f),
        ambientGlowB = scheme.tertiary.copy(alpha = if (isLight) 0.15f else 0.18f),
        isLight = isLight,
    )
}

/**
 * Universal DPI typography scale tuned for crisp mobile & tablet legibility
 * while respecting Android system font-scale accessibility settings.
 */
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    titleMedium = TextStyle(fontSize = 16.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
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
        glassDockTint = animateColorAsState(baseExtended.glassDockTint, spec, label = "glassDockTint").value,
        glassDockFallback = animateColorAsState(baseExtended.glassDockFallback, spec, label = "glassDockFallback").value,
        glassCardBg = animateColorAsState(baseExtended.glassCardBg, spec, label = "glassCardBg").value,
        glassCardRaised = animateColorAsState(baseExtended.glassCardRaised, spec, label = "glassCardRaised").value,
        glassSpecularTop = animateColorAsState(baseExtended.glassSpecularTop, spec, label = "glassSpecularTop").value,
        glassSpecularMid = animateColorAsState(baseExtended.glassSpecularMid, spec, label = "glassSpecularMid").value,
        glassSpecularBottom = animateColorAsState(baseExtended.glassSpecularBottom, spec, label = "glassSpecularBottom").value,
        glassSheenTop = animateColorAsState(baseExtended.glassSheenTop, spec, label = "glassSheenTop").value,
        glassCapsuleDragBg = animateColorAsState(baseExtended.glassCapsuleDragBg, spec, label = "glassCapsuleDragBg").value,
        glassCapsuleBorder = animateColorAsState(baseExtended.glassCapsuleBorder, spec, label = "glassCapsuleBorder").value,
        ambientGlowA = animateColorAsState(baseExtended.ambientGlowA, spec, label = "ambientGlowA").value,
        ambientGlowB = animateColorAsState(baseExtended.ambientGlowB, spec, label = "ambientGlowB").value,
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
