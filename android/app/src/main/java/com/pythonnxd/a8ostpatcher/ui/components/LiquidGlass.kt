package com.pythonnxd.a8ostpatcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.max

/**
 * Shared live GPU HazeState captured from the full app backdrop + screen content layer.
 * Floating chrome (Pill Nav Bar, Now Playing Bar, floating Status Banner) blurs this state live.
 */
val LocalChromeHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Restrained Apple Liquid Glass atmospheric backdrop behind all screens so frosted cards
 * and floating glass surfaces refract real depth and tonal warmth across all 4 themes.
 */
@Composable
fun AmbientLiquidBackdrop(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = colors.ground)

        val maxDim = max(size.width, size.height).coerceAtLeast(1f)
        // Upper-right subtle warm atmospheric light pool
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.ambientGlowA, Color.Transparent),
                center = Offset(size.width * 0.86f, size.height * 0.14f),
                radius = maxDim * 0.62f,
            ),
            radius = maxDim * 0.62f,
            center = Offset(size.width * 0.86f, size.height * 0.14f),
        )
        // Lower-left cool/tertiary atmospheric depth pool
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.ambientGlowB, Color.Transparent),
                center = Offset(size.width * 0.12f, size.height * 0.78f),
                radius = maxDim * 0.68f,
            ),
            radius = maxDim * 0.68f,
            center = Offset(size.width * 0.12f, size.height * 0.78f),
        )
        // Subtle top skylight edge sheen
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    colors.glassSheenTop.copy(alpha = if (colors.isLight) 0.42f else 0.05f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height * 0.28f,
            ),
        )
    }
}

/**
 * Real-time GPU frosted backdrop blur modifier (Apple Liquid Glass chrome).
 * Blurs live scrolled content underneath on Android 12L+ via Haze RenderEffect,
 * with graceful high-contrast frosted fallback on Android 8-12.
 */
fun Modifier.liquidGlassChrome(
    shape: Shape,
    blurRadius: Dp = 26.dp,
    noiseFactor: Float = 0.07f,
    shadowElevation: Dp = 18.dp,
    borderAccent: Color? = null,
    tintOverride: Color? = null,
): Modifier = composed {
    val colors = LocalAppColors.current
    val hazeState = LocalChromeHazeState.current
    val style = HazeStyle(
        backgroundColor = colors.ground,
        tint = HazeTint(tintOverride ?: colors.glassDockTint),
        blurRadius = blurRadius,
        noiseFactor = noiseFactor,
        fallbackTint = HazeTint(tintOverride ?: colors.glassDockFallback),
    )
    val rimBrush = if (borderAccent != null) {
        Brush.verticalGradient(
            listOf(
                borderAccent.copy(alpha = 0.72f),
                borderAccent.copy(alpha = 0.30f),
                borderAccent.copy(alpha = 0.45f),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                colors.glassSpecularTop,
                colors.glassSpecularMid,
                colors.glassSpecularBottom,
            )
        )
    }
    val sheenBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to colors.glassSheenTop,
            0.46f to Color.Transparent,
            1.0f to Color.Transparent,
        )
    )

    this
        .then(
            if (shadowElevation > 0.dp) {
                Modifier.shadow(
                    elevation = shadowElevation,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = if (colors.isLight) 0.22f else 0.65f),
                    spotColor = Color.Black.copy(alpha = if (colors.isLight) 0.24f else 0.75f),
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .then(
            if (hazeState != null) {
                Modifier.hazeChild(state = hazeState, style = style)
            } else {
                Modifier.background(tintOverride ?: colors.glassDockFallback)
            }
        )
        .background(sheenBrush)
        .border(width = 1.dp, brush = rimBrush, shape = shape)
}

/**
 * Cohesive Apple Liquid Glass card container for in-screen surfaces (Settings groups,
 * track cards, inspector panes, empty states, backup & profile cards, sheets).
 * Uses translucent tinted glass over AmbientLiquidBackdrop + curved top specular sheen + refraction rim.
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    elevated: Boolean = false,
    tintColor: Color? = null,
    accentBorder: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    val fill = tintColor ?: if (elevated) colors.glassCardRaised else colors.glassCardBg
    val borderBrush = if (accentBorder != null) {
        Brush.verticalGradient(
            listOf(
                accentBorder.copy(alpha = 0.70f),
                accentBorder.copy(alpha = 0.32f),
                accentBorder.copy(alpha = 0.46f),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                colors.glassSpecularTop,
                colors.glassSpecularMid,
                colors.glassSpecularBottom,
            )
        )
    }
    val innerSheen = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to colors.glassSheenTop.copy(alpha = if (colors.isLight) 0.52f else 0.08f),
            0.38f to Color.Transparent,
            1.0f to Color.Transparent,
        )
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .background(innerSheen)
            .border(width = 1.dp, brush = borderBrush, shape = shape),
        content = content,
    )
}

/**
 * Tactile spring press scale modifier wired to an existing InteractionSource so
 * clickable / Button elements compress instantly on finger-down and spring back on release.
 * Becomes static when Settings -> Reduce motion is enabled.
 */
fun Modifier.interactivePressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
): Modifier = composed {
    val reduceMotion = LocalReduceMotion.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) pressedScale else 1f,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.56f, stiffness = 540f),
        label = "pressScale",
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
