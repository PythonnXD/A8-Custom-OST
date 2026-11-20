package com.pythonnxd.a8ostpatcher.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.launch
import kotlin.math.abs
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

/**
 * Dynamic scroll-aware edge feather for horizontal button/pill rows:
 * gently fades out partially off-screen buttons on the trailing (and scrolled leading) edges
 * using an offscreen DstIn alpha mask that blends cleanly over any backdrop or theme.
 */
fun Modifier.horizontalScrollEdgeFade(
    scrollState: ScrollState,
    edgeWidth: Dp = 28.dp,
): Modifier = composed {
    val reduceMotion = LocalReduceMotion.current
    val leftFade by animateFloatAsState(
        targetValue = if (scrollState.canScrollBackward) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(160),
        label = "hScrollLeftFade",
    )
    val rightFade by animateFloatAsState(
        targetValue = if (scrollState.canScrollForward) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(160),
        label = "hScrollRightFade",
    )

    if (leftFade <= 0.005f && rightFade <= 0.005f) {
        this
    } else {
        this
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val edgePx = edgeWidth.toPx().coerceAtMost(size.width * 0.35f)
                if (edgePx > 1f) {
                    if (leftFade > 0.005f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = (1f - leftFade).coerceIn(0f, 1f)),
                                    Color.Black,
                                ),
                                startX = 0f,
                                endX = edgePx,
                            ),
                            topLeft = Offset.Zero,
                            size = Size(edgePx, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    if (rightFade > 0.005f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black,
                                    Color.Black.copy(alpha = (1f - rightFade).coerceIn(0f, 1f)),
                                ),
                                startX = size.width - edgePx,
                                endX = size.width,
                            ),
                            topLeft = Offset(size.width - edgePx, 0f),
                            size = Size(edgePx, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
            }
    }
}

class LiquidGlassSheetSlot(
    val id: Int,
    val onDismissState: State<() -> Unit>,
    val contentState: State<@Composable ColumnScope.() -> Unit>,
)

class LiquidGlassSheetHostState {
    private var nextId = 1
    var activeSheet by mutableStateOf<LiquidGlassSheetSlot?>(null)
        internal set

    fun allocId(): Int = nextId++

    fun show(slot: LiquidGlassSheetSlot) {
        activeSheet = slot
    }

    fun hide(id: Int) {
        if (activeSheet?.id == id) {
            activeSheet = null
        }
    }
}

val LocalLiquidGlassSheetHost = compositionLocalOf<LiquidGlassSheetHostState?> { null }

/**
 * In-window Apple Liquid Glass Bottom Sheet rendered directly inside MainAppShell's overlay layer
 * above the live Haze backdrop source, so BOTH the dimmed scrim and the frosted sheet itself blur
 * the real screen behind them on GPU — with 1:1 draggable handle, velocity spring dismiss, and BackHandler.
 */
@Composable
fun LiquidGlassBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalLiquidGlassSheetHost.current ?: return
    val sheetId = remember { host.allocId() }
    val dismissState = rememberUpdatedState(onDismissRequest)
    val contentState = rememberUpdatedState(content)

    DisposableEffect(host, sheetId) {
        host.show(LiquidGlassSheetSlot(sheetId, dismissState, contentState))
        onDispose { host.hide(sheetId) }
    }
}

@Composable
fun LiquidGlassSheetOverlay(
    hostState: LiquidGlassSheetHostState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val hazeState = LocalChromeHazeState.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var lastSlot by remember { mutableStateOf(hostState.activeSheet) }
    LaunchedEffect(hostState.activeSheet) {
        if (hostState.activeSheet != null) lastSlot = hostState.activeSheet
    }
    val visible = hostState.activeSheet != null

    val offsetY = remember { Animatable(0f) }
    var sheetHeightPx by remember { mutableIntStateOf(1) }

    LaunchedEffect(hostState.activeSheet?.id) {
        offsetY.snapTo(0f)
    }

    BackHandler(enabled = visible) {
        hostState.activeSheet?.onDismissState?.value?.invoke()
    }

    val dragProgress = if (sheetHeightPx > 0) (offsetY.value / sheetHeightPx.toFloat()).coerceIn(0f, 1f) else 0f

    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(180)),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(150)),
        modifier = modifier.fillMaxSize(),
    ) {
        val scrimStyle = HazeStyle(
            backgroundColor = colors.ground,
            tint = HazeTint(Color.Black.copy(alpha = if (colors.isLight) 0.24f else 0.44f)),
            blurRadius = 14.dp,
            noiseFactor = 0.03f,
            fallbackTint = HazeTint(Color.Black.copy(alpha = if (colors.isLight) 0.34f else 0.56f)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (1f - dragProgress * 0.85f).coerceIn(0.1f, 1f) }
                .then(
                    if (hazeState != null) Modifier.hazeChild(state = hazeState, style = scrimStyle)
                    else Modifier.background(Color.Black.copy(alpha = if (colors.isLight) 0.34f else 0.56f))
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    hostState.activeSheet?.onDismissState?.value?.invoke()
                }
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None
        else slideInVertically(spring(dampingRatio = 0.84f, stiffness = 430f)) { it } + fadeIn(tween(170)),
        exit = if (reduceMotion) ExitTransition.None
        else slideOutVertically(tween(190)) { it } + fadeOut(tween(150)),
        modifier = modifier.fillMaxSize(),
    ) {
        val slot = hostState.activeSheet ?: lastSlot
        if (slot != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 620.dp)
                        .onSizeChanged { sheetHeightPx = max(1, it.height) }
                        .graphicsLayer { translationY = offsetY.value }
                        .liquidGlassChrome(
                            shape = sheetShape,
                            blurRadius = 34.dp,
                            noiseFactor = 0.08f,
                            shadowElevation = 24.dp,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { /* absorb touches inside sheet */ }
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    // 1:1 draggable grab handle area (tracks finger directly, velocity-seeded spring on release)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(sheetHeightPx, reduceMotion) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val vt = VelocityTracker()
                                    vt.addPosition(down.uptimeMillis, Offset(0f, offsetY.value))
                                    var dragged = false
                                    var accumY = 0f
                                    val slop = viewConfiguration.touchSlop * 0.4f

                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        val stepY = ch.position.y - ch.previousPosition.y
                                        accumY += stepY
                                        if (!dragged && abs(accumY) > slop) dragged = true
                                        if (dragged) {
                                            ch.consume()
                                            val raw = offsetY.value + stepY
                                            val clamped = if (raw < 0f) raw * 0.16f else raw
                                            vt.addPosition(ch.uptimeMillis, Offset(0f, clamped))
                                            scope.launch { offsetY.snapTo(clamped) }
                                        }
                                    }

                                    if (dragged) {
                                        val vy = vt.calculateVelocity().y
                                        val shouldDismiss = offsetY.value > sheetHeightPx * 0.28f || vy > 900f
                                        if (shouldDismiss) {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            slot.onDismissState.value.invoke()
                                        } else {
                                            scope.launch {
                                                if (reduceMotion) offsetY.snapTo(0f)
                                                else offsetY.animateTo(
                                                    targetValue = 0f,
                                                    initialVelocity = vy,
                                                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 460f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            .padding(top = 12.dp, bottom = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.5.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.muted.copy(alpha = 0.48f))
                        )
                    }

                    slot.contentState.value.invoke(this)
                }
            }
        }
    }
}
