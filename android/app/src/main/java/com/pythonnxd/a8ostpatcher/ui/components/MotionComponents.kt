package com.pythonnxd.a8ostpatcher.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonnxd.a8ostpatcher.engine.AudioPipeline
import com.pythonnxd.a8ostpatcher.state.AppTab
import com.pythonnxd.a8ostpatcher.state.NowPlayingState
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.QUEUE -> Icons.Rounded.Download
    AppTab.LIBRARY -> Icons.Rounded.LibraryMusic
    AppTab.TOOLS -> Icons.Rounded.Build
    AppTab.SETTINGS -> Icons.Rounded.Settings
}

private fun rubberBand(distance: Float, dimension: Float, coeff: Float = 0.45f): Float {
    if (distance <= 0f || dimension <= 0f) return 0f
    return (distance * coeff * dimension) / (dimension + coeff * distance)
}

/**
 * Floating Apple Liquid Glass Pill Navigation Bar:
 * - Real-time GPU frosted backdrop blur over scrolled content (Haze RenderEffect on Android 12L+)
 * - Sculpted universal phone & tablet proportions (68dp phone / 62dp tablet, crisp 22dp icons + 11.5sp labels)
 * - 1:1 draggable Liquid Glass highlight capsule for fidgeting across tabs with rubber-band edges,
 *   luminous lens swell while dragging, live tick haptics, and velocity-projected momentum spring settle.
 */
@Composable
fun FloatingPillNavBar(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val tabs = AppTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

    val capsulePos = remember { Animatable(selectedIndex.toFloat()) }
    var barWidthPx by remember { mutableIntStateOf(0) }
    var isPressing by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragHoverIndex by remember { mutableIntStateOf(selectedIndex) }

    val currentSelectedIdx by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)

    LaunchedEffect(selectedIndex, reduceMotion) {
        if (!isDragging) {
            dragHoverIndex = selectedIndex
            if (reduceMotion) {
                capsulePos.snapTo(selectedIndex.toFloat())
            } else {
                capsulePos.animateTo(
                    targetValue = selectedIndex.toFloat(),
                    animationSpec = spring(dampingRatio = 0.76f, stiffness = 430f),
                )
            }
        }
    }

    val capsuleScaleX by animateFloatAsState(
        targetValue = when {
            reduceMotion -> 1f
            isDragging -> 1.09f
            isPressing -> 1.03f
            else -> 1f
        },
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.60f, stiffness = 460f),
        label = "capsuleScaleX",
    )
    val capsuleScaleY by animateFloatAsState(
        targetValue = when {
            reduceMotion -> 1f
            isDragging -> 1.07f
            isPressing -> 1.02f
            else -> 1f
        },
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.60f, stiffness = 460f),
        label = "capsuleScaleY",
    )
    val capsuleFill by animateColorAsState(
        targetValue = if (isDragging && !reduceMotion) colors.glassCapsuleDragBg else colors.capsuleBg,
        animationSpec = if (reduceMotion) snap() else tween(140),
        label = "capsuleFill",
    )

    val pillShape = RoundedCornerShape(999.dp)
    val activeVisualIndex = if (isDragging) dragHoverIndex else selectedIndex

    Box(
        modifier = modifier
            .then(
                if (isTablet) {
                    Modifier.width(480.dp).height(62.dp)
                } else {
                    Modifier.widthIn(max = 408.dp).fillMaxWidth().height(68.dp)
                }
            )
            .liquidGlassChrome(
                shape = pillShape,
                blurRadius = 28.dp,
                noiseFactor = 0.08f,
                shadowElevation = 20.dp,
            )
            .padding(5.dp)
            .onSizeChanged { barWidthPx = it.width }
            .pointerInput(tabs.size, barWidthPx) {
                if (barWidthPx <= 0) return@pointerInput
                val slotW = barWidthPx.toFloat() / tabs.size.toFloat()
                val maxSlot = (tabs.size - 1).toFloat()
                val slop = viewConfiguration.touchSlop * 0.35f

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressing = true
                    val startX = down.position.x
                    val activeLeft = capsulePos.value * slotW
                    val activeCenter = activeLeft + slotW * 0.5f
                    val grabOffset = if (abs(startX - activeCenter) <= slotW * 0.65f) {
                        startX - activeLeft
                    } else {
                        slotW * 0.5f
                    }

                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var dragged = false
                    var lastTickIdx = currentSelectedIdx

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        if (!dragged && abs(change.position.x - startX) > slop) {
                            dragged = true
                            isDragging = true
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }

                        if (dragged) {
                            change.consume()
                            val rawSlot = (change.position.x - grabOffset) / slotW
                            val rubberSlot = when {
                                rawSlot < 0f -> -rubberBand(-rawSlot, 0.50f, 0.42f)
                                rawSlot > maxSlot -> maxSlot + rubberBand(rawSlot - maxSlot, 0.50f, 0.42f)
                                else -> rawSlot
                            }
                            scope.launch { capsulePos.snapTo(rubberSlot) }
                            val hovered = rawSlot.roundToInt().coerceIn(0, tabs.lastIndex)
                            dragHoverIndex = hovered
                            if (hovered != lastTickIdx) {
                                lastTickIdx = hovered
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }

                    isPressing = false
                    if (dragged) {
                        val vxPx = velocityTracker.calculateVelocity().x
                        val vSlots = (vxPx / slotW).coerceIn(-14f, 14f)
                        val projected = (capsulePos.value + vSlots * 0.085f).roundToInt().coerceIn(0, tabs.lastIndex)
                        isDragging = false
                        dragHoverIndex = projected
                        if (projected != currentSelectedIdx) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            currentOnSelect(tabs[projected])
                        }
                        scope.launch {
                            if (currentReduceMotion) {
                                capsulePos.snapTo(projected.toFloat())
                            } else {
                                capsulePos.animateTo(
                                    targetValue = projected.toFloat(),
                                    initialVelocity = vSlots,
                                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 380f),
                                )
                            }
                        }
                    } else {
                        val tapped = (startX / slotW).toInt().coerceIn(0, tabs.lastIndex)
                        dragHoverIndex = tapped
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if (tapped != currentSelectedIdx) {
                            currentOnSelect(tabs[tapped])
                        }
                    }
                }
            }
    ) {
        if (barWidthPx > 0) {
            val slotW = barWidthPx.toFloat() / tabs.size.toFloat()
            val slotWDp = with(density) { slotW.toDp() }
            val capsuleRim = Brush.verticalGradient(
                if (isDragging && !reduceMotion) {
                    listOf(
                        colors.glassCapsuleBorder,
                        colors.glassSpecularMid,
                        colors.glassCapsuleBorder.copy(alpha = 0.65f),
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = if (colors.isLight) 0.36f else 0.55f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.14f),
                    )
                }
            )
            Box(
                modifier = Modifier
                    .width(slotWDp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = capsulePos.value * slotW
                        scaleX = capsuleScaleX
                        scaleY = capsuleScaleY
                    }
                    .shadow(
                        elevation = if (isDragging) 12.dp else 4.dp,
                        shape = pillShape,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.45f),
                    )
                    .clip(pillShape)
                    .background(capsuleFill)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = if (isDragging) 0.30f else 0.14f),
                                0.52f to Color.Transparent,
                                1.0f to Color.Transparent,
                            )
                        )
                    )
                    .border(1.dp, capsuleRim, pillShape)
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { idx, tab ->
                val isVisualActive = idx == activeVisualIndex
                val targetTint = when {
                    isVisualActive && isDragging && !reduceMotion -> colors.text
                    isVisualActive -> colors.capsuleInk
                    else -> colors.muted
                }
                val contentColor by animateColorAsState(
                    targetValue = targetTint,
                    animationSpec = if (reduceMotion) snap() else tween(130),
                    label = "tabColor$idx",
                )
                val iconPop = remember { Animatable(1f) }
                LaunchedEffect(isVisualActive, reduceMotion) {
                    if (isVisualActive && !reduceMotion) {
                        iconPop.snapTo(0.86f)
                        iconPop.animateTo(1.06f, spring(dampingRatio = 0.46f, stiffness = 540f))
                        iconPop.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 460f))
                    } else {
                        iconPop.snapTo(1f)
                    }
                }
                val dragFocusScale by animateFloatAsState(
                    targetValue = if (isDragging && isVisualActive && !reduceMotion) 1.08f else 1f,
                    animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.65f, stiffness = 500f),
                    label = "tabDragScale$idx",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = dragFocusScale
                            scaleY = dragFocusScale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isTablet) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                tabIcon(tab),
                                contentDescription = tab.label,
                                tint = contentColor,
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        scaleX = iconPop.value
                                        scaleY = iconPop.value
                                    },
                            )
                            Text(
                                tab.label,
                                color = contentColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                tabIcon(tab),
                                contentDescription = tab.label,
                                tint = contentColor,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer {
                                        scaleX = iconPop.value
                                        scaleY = iconPop.value
                                    },
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                tab.label,
                                color = contentColor,
                                fontSize = 11.5.sp,
                                fontWeight = if (isVisualActive) FontWeight.Bold else FontWeight.SemiBold,
                                letterSpacing = (-0.1).sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

data class CategoryFilterItem(val key: String, val label: String)

/**
 * Animated frosted Liquid Glass category selector with a sliding spring pill indicator
 * that smoothly glides and morphs width to match each category label.
 */
@Composable
fun AnimatedCategoryFilterRow(
    items: List<CategoryFilterItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val activeBounds = bounds[selectedIndex]
    val targetX = activeBounds?.first ?: 0f
    val targetW = activeBounds?.second ?: 0f

    val animX by animateFloatAsState(
        targetValue = targetX,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.74f, stiffness = 460f),
        label = "catPillX",
    )
    val animW by animateFloatAsState(
        targetValue = targetW,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.74f, stiffness = 460f),
        label = "catPillW",
    )

    LaunchedEffect(selectedIndex, activeBounds) {
        if (activeBounds != null && scrollState.maxValue > 0) {
            val targetScroll = (activeBounds.first - 48f).roundToInt().coerceIn(0, scrollState.maxValue)
            if (reduceMotion) scrollState.scrollTo(targetScroll) else scrollState.animateScrollTo(targetScroll)
        }
    }

    LiquidGlassCard(
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(4.dp),
        ) {
            if (animW > 1f) {
                val widthDp = with(density) { animW.toDp() }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                ) {
                    Box(
                        modifier = Modifier
                            .width(widthDp)
                            .fillMaxHeight()
                            .graphicsLayer { translationX = animX }
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.accentSoft)
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to colors.glassSheenTop.copy(alpha = if (colors.isLight) 0.45f else 0.16f),
                                        0.55f to Color.Transparent,
                                        1.0f to Color.Transparent,
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        colors.accent.copy(alpha = 0.80f),
                                        colors.accent.copy(alpha = 0.40f),
                                    )
                                ),
                                shape = RoundedCornerShape(999.dp),
                            )
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { idx, item ->
                    val isSelected = idx == selectedIndex
                    val interaction = remember { MutableInteractionSource() }
                    val fg by animateColorAsState(
                        targetValue = if (isSelected) colors.accentInk else colors.muted,
                        animationSpec = if (reduceMotion) snap() else tween(160),
                        label = "catFg$idx",
                    )
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 38.dp)
                            .onGloballyPositioned { coords ->
                                bounds[idx] = coords.positionInParent().x to coords.size.width.toFloat()
                            }
                            .interactivePressScale(interaction, pressedScale = 0.94f)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) {
                                if (!isSelected) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onSelect(idx)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.label,
                            color = fg,
                            fontSize = 13.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

data class SegmentItem(val label: String, val icon: ImageVector? = null)

/**
 * Draggable & tappable frosted Liquid Glass segmented control with spring thumb physics.
 */
@Composable
fun SlidingSegmentedControl(
    items: List<SegmentItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val thumbPos = remember { Animatable(selectedIndex.toFloat()) }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var dragHoverIdx by remember { mutableIntStateOf(selectedIndex) }

    val curSelected by rememberUpdatedState(selectedIndex)
    val curOnSelect by rememberUpdatedState(onSelect)
    val curReduce by rememberUpdatedState(reduceMotion)

    LaunchedEffect(selectedIndex, reduceMotion) {
        if (!isDragging) {
            dragHoverIdx = selectedIndex
            if (reduceMotion) thumbPos.snapTo(selectedIndex.toFloat())
            else thumbPos.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.78f, stiffness = 460f))
        }
    }

    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging && !reduceMotion) 1.05f else 1f,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.65f, stiffness = 480f),
        label = "segScale",
    )
    val visualIndex = if (isDragging) dragHoverIdx else selectedIndex

    LiquidGlassCard(
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.defaultMinSize(minHeight = 46.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .onSizeChanged { trackWidthPx = it.width }
                .pointerInput(items.size, trackWidthPx) {
                    if (trackWidthPx <= 0 || items.isEmpty()) return@pointerInput
                    val slotW = trackWidthPx.toFloat() / items.size.toFloat()
                    val maxSlot = (items.size - 1).toFloat()
                    val slop = viewConfiguration.touchSlop * 0.35f

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val activeLeft = thumbPos.value * slotW
                        val grabOffset = if (abs(startX - (activeLeft + slotW * 0.5f)) <= slotW * 0.65f) {
                            startX - activeLeft
                        } else {
                            slotW * 0.5f
                        }
                        var dragged = false
                        var lastTick = curSelected
                        val vt = VelocityTracker()
                        vt.addPosition(down.uptimeMillis, down.position)

                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            if (!dragged && abs(ch.position.x - startX) > slop) {
                                dragged = true
                                isDragging = true
                            }
                            if (dragged) {
                                ch.consume()
                                val raw = (ch.position.x - grabOffset) / slotW
                                val clamped = when {
                                    raw < 0f -> -rubberBand(-raw, 0.35f, 0.35f)
                                    raw > maxSlot -> maxSlot + rubberBand(raw - maxSlot, 0.35f, 0.35f)
                                    else -> raw
                                }
                                scope.launch { thumbPos.snapTo(clamped) }
                                val hov = raw.roundToInt().coerceIn(0, items.lastIndex)
                                dragHoverIdx = hov
                                if (hov != lastTick) {
                                    lastTick = hov
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }

                        if (dragged) {
                            val vSlots = (vt.calculateVelocity().x / slotW).coerceIn(-12f, 12f)
                            val target = (thumbPos.value + vSlots * 0.08f).roundToInt().coerceIn(0, items.lastIndex)
                            isDragging = false
                            dragHoverIdx = target
                            if (target != curSelected) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                curOnSelect(target)
                            }
                            scope.launch {
                                if (curReduce) thumbPos.snapTo(target.toFloat())
                                else thumbPos.animateTo(target.toFloat(), initialVelocity = vSlots, animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f))
                            }
                        } else {
                            val tapped = (startX / slotW).toInt().coerceIn(0, items.lastIndex)
                            if (tapped != curSelected) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                curOnSelect(tapped)
                            }
                        }
                    }
                }
        ) {
            if (trackWidthPx > 0 && items.isNotEmpty()) {
                val slotW = trackWidthPx.toFloat() / items.size.toFloat()
                val slotWDp = with(density) { slotW.toDp() }
                Box(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier
                            .width(slotWDp)
                            .fillMaxHeight()
                            .graphicsLayer {
                                translationX = thumbPos.value * slotW
                                scaleX = thumbScale
                                scaleY = thumbScale
                            }
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.capsuleBg)
                            .border(
                                1.dp,
                                Color.White.copy(alpha = if (colors.isLight) 0.30f else 0.45f),
                                RoundedCornerShape(999.dp),
                            )
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 38.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { idx, item ->
                    val active = idx == visualIndex
                    val fg by animateColorAsState(
                        targetValue = if (active) colors.capsuleInk else colors.muted,
                        animationSpec = if (reduceMotion) snap() else tween(140),
                        label = "segFg$idx",
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 38.dp)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.icon != null) {
                            Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            item.label,
                            color = fg,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1:1 horizontal finger-tracking swipe card revealing frosted Outro and Remove quick actions on left swipe.
 */
@Composable
fun SwipeableTrackCard(
    onOutroClick: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val revealPx = with(density) { 166.dp.toPx() }
    val offsetX = remember { Animatable(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
    ) {
        // Revealed frosted action tray underneath
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(colors.danger.copy(alpha = 0.14f))
                .border(1.dp, colors.danger.copy(alpha = 0.34f), RoundedCornerShape(18.dp))
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val outroInteraction = remember { MutableInteractionSource() }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.accentSoft,
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.55f)),
                modifier = Modifier
                    .defaultMinSize(minHeight = 40.dp)
                    .interactivePressScale(outroInteraction, 0.94f)
                    .clickable(interactionSource = outroInteraction, indication = null) {
                        scope.launch { offsetX.snapTo(0f) }
                        onOutroClick()
                    },
            ) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Outro", color = colors.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(8.dp))
            val removeInteraction = remember { MutableInteractionSource() }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.danger.copy(alpha = 0.24f),
                border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.52f)),
                modifier = Modifier
                    .defaultMinSize(minHeight = 40.dp)
                    .interactivePressScale(removeInteraction, 0.94f)
                    .clickable(interactionSource = removeInteraction, indication = null) {
                        scope.launch { offsetX.snapTo(0f) }
                        onRemoveClick()
                    },
            ) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Remove", color = colors.danger, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Foreground track card tracking finger 1:1
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(revealPx, reduceMotion) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val target = if (offsetX.value < -revealPx * 0.42f) -revealPx else 0f
                                if (target != 0f) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                if (reduceMotion) offsetX.snapTo(target)
                                else offsetX.animateTo(target, spring(dampingRatio = 0.80f, stiffness = 420f))
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                if (reduceMotion) offsetX.snapTo(0f)
                                else offsetX.animateTo(0f, spring(dampingRatio = 0.80f, stiffness = 420f))
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val raw = offsetX.value + dragAmount
                                val next = when {
                                    raw > 0f -> raw * 0.18f
                                    raw < -revealPx -> -revealPx + (raw + revealPx) * 0.22f
                                    else -> raw
                                }
                                offsetX.snapTo(next)
                            }
                        },
                    )
                }
        ) {
            content()
        }
    }
}

/**
 * Asymmetric Hold-to-Confirm destructive button:
 * linear fill while holding (850ms), snappy ease-out snap-back on early release.
 */
@Composable
fun HoldToDeleteButton(
    label: String = "Hold to delete",
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (holding && !reduceMotion) 0.95f else 1f,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "holdScale",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 42.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(colors.danger.copy(alpha = 0.14f))
            .border(1.dp, colors.danger.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        val job = scope.launch {
                            progress.animateTo(1f, tween(durationMillis = 850, easing = LinearEasing))
                            if (progress.value >= 0.99f) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                holding = false
                                onConfirmed()
                                progress.snapTo(0f)
                            }
                        }
                        tryAwaitRelease()
                        holding = false
                        job.cancel()
                        scope.launch {
                            if (reduceMotion) progress.snapTo(0f)
                            else progress.animateTo(0f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (progress.value > 0f) {
                drawRect(
                    color = colors.danger,
                    size = Size(size.width * progress.value, size.height),
                )
            }
        }
        Text(
            text = label,
            color = if (progress.value > 0.5f) Color.White else colors.danger,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun LiveEqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current

    val s1: Float
    val s2: Float
    val s3: Float
    if (isPlaying && !reduceMotion) {
        val inf = rememberInfiniteTransition(label = "eq")
        s1 = inf.animateFloat(0.28f, 1.0f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "eq1").value
        s2 = inf.animateFloat(0.22f, 0.96f, infiniteRepeatable(tween(340, delayMillis = 90, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "eq2").value
        s3 = inf.animateFloat(0.30f, 0.92f, infiniteRepeatable(tween(480, delayMillis = 160, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "eq3").value
    } else if (isPlaying) {
        s1 = 0.72f; s2 = 0.92f; s3 = 0.62f
    } else {
        s1 = 0.25f; s2 = 0.25f; s3 = 0.25f
    }

    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(s1, s2, s3).forEach { scale ->
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(16.dp)
                    .graphicsLayer {
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isPlaying) colors.accentInk else colors.faint)
            )
        }
    }
}

/**
 * Frosted Apple Liquid Glass Now Playing mini-player strictly locked to the active playing/paused song.
 * Uses real-time GPU backdrop blur over scrolled content + tap/scrub seek + spring micro-interactions.
 */
@Composable
fun NowPlayingBar(
    state: NowPlayingState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLoop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isLocked && !state.isPreparing) return
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val frac = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val playInteraction = remember { MutableInteractionSource() }
    val loopInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassChrome(
                shape = RoundedCornerShape(22.dp),
                blurRadius = 26.dp,
                noiseFactor = 0.07f,
                shadowElevation = 14.dp,
                borderAccent = if (state.isPlaying) colors.accent else null,
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .interactivePressScale(playInteraction, 0.92f)
                        .clip(CircleShape)
                        .background(if (state.isPlaying) colors.accent else colors.glassCardRaised)
                        .border(1.dp, if (state.isPlaying) colors.accent else colors.glassSpecularTop, CircleShape)
                        .clickable(interactionSource = playInteraction, indication = null) { onTogglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentInk,
                        )
                    } else {
                        AnimatedContent(
                            targetState = state.isPlaying,
                            transitionSpec = {
                                if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                                else (scaleIn(spring(0.65f, 520f), initialScale = 0.72f) + fadeIn(tween(110))) togetherWith
                                    (scaleOut(tween(90), targetScale = 0.72f) + fadeOut(tween(90)))
                            },
                            label = "npPlayIcon",
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = if (playing) Color(0xFF0B0C0F) else colors.text,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                LiveEqualizerBars(isPlaying = state.isPlaying)

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = state.title.ifBlank { "Untitled" },
                            color = colors.text,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = colors.accentSoft,
                        ) {
                            Text(
                                text = state.category.badgeText,
                                color = colors.accentInk,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "${state.artist} · ${if (state.isOutro) "OUTRO CLIP" else "Main"}",
                        color = colors.muted,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = "${AudioPipeline.formatClock(state.positionMs / 1000.0)} / ${AudioPipeline.formatClock(state.durationMs / 1000.0)}",
                    color = colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (state.loop) colors.accentSoft else colors.glassCardRaised,
                    border = BorderStroke(1.dp, if (state.loop) colors.accent.copy(alpha = 0.6f) else colors.border),
                    modifier = Modifier
                        .size(34.dp)
                        .interactivePressScale(loopInteraction, 0.92f)
                        .clickable(interactionSource = loopInteraction, indication = null) { onToggleLoop() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Repeat,
                            contentDescription = "Loop",
                            tint = if (state.loop) colors.accentInk else colors.muted,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            var seekWidthPx by remember { mutableFloatStateOf(1f) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .onSizeChanged { seekWidthPx = max(1f, it.width.toFloat()) }
                    .pointerInput(state.durationMs) {
                        detectTapGestures { pos ->
                            if (seekWidthPx > 0f) onSeek((pos.x / seekWidthPx).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(state.durationMs) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            if (seekWidthPx > 0f) onSeek((change.position.x / seekWidthPx).coerceIn(0f, 1f))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.border)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.accent)
                    )
                }
            }
        }
    }
}

/**
 * Interactive 1:1 draggable waveform trim canvas with 0.01s precision handles.
 */
@Composable
fun WaveformTrimEditor(
    peaks: List<Float>,
    durationSec: Double,
    startSec: Double,
    endSec: Double,
    onRangeChange: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val safeDur = max(0.5, durationSec)
    val startFrac = (startSec / safeDur).toFloat().coerceIn(0f, 0.98f)
    val endFrac = (endSec / safeDur).toFloat().coerceIn(startFrac + 0.02f, 1f)

    val bars = remember(peaks) {
        if (peaks.isNotEmpty()) peaks
        else List(64) { i -> 0.25f + 0.65f * abs(kotlin.math.sin(i * 0.41 + 0.6) * kotlin.math.cos(i * 0.17)).toFloat() }
    }

    var dragHandle by remember { mutableIntStateOf(0) } // 1 = left, 2 = right
    var widthPx by remember { mutableFloatStateOf(1f) }
    val curStart by rememberUpdatedState(startSec)
    val curEnd by rememberUpdatedState(endSec)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Selected: ${AudioPipeline.formatDurationMs(startSec)} – ${AudioPipeline.formatDurationMs(endSec)}",
                color = colors.accentInk,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Total: ${AudioPipeline.formatDurationMs(safeDur)}",
                color = colors.muted,
                fontSize = 13.5.sp,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.ground.copy(alpha = 0.72f))
                .border(1.dp, colors.glassSpecularMid, RoundedCornerShape(14.dp))
                .onSizeChanged { widthPx = max(1f, it.width.toFloat()) }
                .pointerInput(safeDur) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            val sx = (curStart / safeDur).toFloat() * widthPx
                            val ex = (curEnd / safeDur).toFloat() * widthPx
                            dragHandle = if (abs(pos.x - sx) <= abs(pos.x - ex)) 1 else 2
                        },
                        onDragEnd = { dragHandle = 0 },
                        onDragCancel = { dragHandle = 0 },
                        onHorizontalDrag = { change, _ ->
                            val frac = (change.position.x / widthPx).coerceIn(0f, 1f)
                            val t = (kotlin.math.round(frac * safeDur * 100.0) / 100.0)
                            if (dragHandle == 1) {
                                val nextS = min(curEnd - 0.5, max(0.0, t))
                                onRangeChange(nextS, curEnd)
                            } else if (dragHandle == 2) {
                                val nextE = max(curStart + 0.5, min(safeDur, t))
                                onRangeChange(curStart, nextE)
                            }
                        },
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val padX = 12f
            val usableW = max(1f, w - padX * 2)
            val barGap = 2.5f
            val barW = max(1.5f, (usableW - barGap * (bars.size - 1)) / bars.size)

            val selLeft = padX + startFrac * usableW
            val selRight = padX + endFrac * usableW

            drawRect(
                color = colors.accent.copy(alpha = 0.22f),
                topLeft = Offset(selLeft, 0f),
                size = Size(max(2f, selRight - selLeft), h),
            )

            bars.forEachIndexed { idx, amp ->
                val x = padX + idx * (barW + barGap)
                val barH = max(6f, amp.coerceIn(0.08f, 1f) * (h - 20f))
                val inRange = x + barW >= selLeft && x <= selRight
                drawRoundRect(
                    color = if (inRange) colors.accentInk else colors.faint.copy(alpha = 0.45f),
                    topLeft = Offset(x, (h - barH) / 2f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(2.5f, 2.5f),
                )
            }

            drawLine(colors.accentInk, Offset(selLeft, 0f), Offset(selLeft, h), strokeWidth = 3.5f)
            drawLine(colors.accentInk, Offset(selRight, 0f), Offset(selRight, h), strokeWidth = 3.5f)
            drawRoundRect(colors.accentInk, Offset(selLeft - 7f, h / 2f - 16f), Size(14f, 32f), CornerRadius(7f, 7f))
            drawRoundRect(colors.accentInk, Offset(selRight - 7f, h / 2f - 16f), Size(14f, 32f), CornerRadius(7f, 7f))
        }
    }
}
