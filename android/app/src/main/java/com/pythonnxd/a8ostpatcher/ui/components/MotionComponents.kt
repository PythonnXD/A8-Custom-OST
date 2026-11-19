package com.pythonnxd.a8ostpatcher.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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

/**
 * Floating translucent glass pill navigation bar with matching icons on BOTH Phone and Tablet
 * and a critically damped (zero-wobble) sliding active capsule.
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
    val haptics = LocalHapticFeedback.current
    val tabs = AppTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

    val capsulePos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 1.0f, stiffness = 420f),
        label = "navCapsule",
    )

    var barWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .then(if (isTablet) Modifier.width(460.dp) else Modifier.fillMaxWidth())
            .height(58.dp)
            .shadow(16.dp, RoundedCornerShape(999.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.glassBg)
            .border(1.dp, colors.border, RoundedCornerShape(999.dp))
            .padding(4.dp)
            .onSizeChanged { barWidthPx = it.width }
    ) {
        if (barWidthPx > 0) {
            val slotW = barWidthPx.toFloat() / tabs.size.toFloat()
            val slotWDp = with(density) { slotW.toDp() }
            Box(
                modifier = Modifier
                    .width(slotWDp)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = capsulePos * slotW }
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.capsuleBg)
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { idx, tab ->
                val isSelected = idx == selectedIndex
                val contentColor = if (isSelected) colors.capsuleInk else colors.muted
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!isSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(tab)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isTablet) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(tabIcon(tab), contentDescription = tab.label, tint = contentColor, modifier = Modifier.size(17.dp))
                            Text(tab.label, color = contentColor, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(tabIcon(tab), contentDescription = tab.label, tint = contentColor, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.height(1.dp))
                            Text(tab.label, color = contentColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

data class SegmentItem(val label: String, val icon: ImageVector? = null)

@Composable
fun SlidingSegmentedControl(
    items: List<SegmentItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current

    val thumbPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 1.0f, stiffness = 460f),
        label = "segThumb",
    )
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.ground)
            .border(1.dp, colors.border, RoundedCornerShape(999.dp))
            .padding(3.dp)
            .onSizeChanged { trackWidthPx = it.width }
    ) {
        if (trackWidthPx > 0 && items.isNotEmpty()) {
            val slotW = trackWidthPx.toFloat() / items.size.toFloat()
            val slotWDp = with(density) { slotW.toDp() }
            Box(
                modifier = Modifier
                    .width(slotWDp)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = thumbPos * slotW }
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.capsuleBg)
            )
        }
        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { idx, item ->
                val active = idx == selectedIndex
                val fg = if (active) colors.capsuleInk else colors.muted
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!active) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(idx)
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.icon != null) {
                        Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(item.label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * 1:1 horizontal finger-tracking swipe card revealing Outro and Remove quick actions on left swipe.
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
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val revealPx = with(density) { 148.dp.toPx() }
    val offsetX = remember { Animatable(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
    ) {
        // Revealed action tray underneath
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(colors.danger.copy(alpha = 0.14f))
                .border(1.dp, colors.danger.copy(alpha = 0.30f), RoundedCornerShape(15.dp))
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.accentSoft,
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .height(34.dp)
                    .clickable {
                        scope.launch { offsetX.snapTo(0f) }
                        onOutroClick()
                    },
            ) {
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Outro", color = colors.accentInk, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.danger.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.48f)),
                modifier = Modifier
                    .height(34.dp)
                    .clickable {
                        scope.launch { offsetX.snapTo(0f) }
                        onRemoveClick()
                    },
            ) {
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Remove", color = colors.danger, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
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
                                if (target != 0f) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (reduceMotion) offsetX.snapTo(target)
                                else offsetX.animateTo(target, spring(dampingRatio = 0.85f, stiffness = 420f))
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                if (reduceMotion) offsetX.snapTo(0f)
                                else offsetX.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val raw = offsetX.value + dragAmount
                                // Rubber-band resistance past stop and on right pull
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
 * linear fill while holding (900ms), snappy ease-out snap-back on early release.
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
        targetValue = if (holding && !reduceMotion) 0.96f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(120),
        label = "holdScale",
    )

    Box(
        modifier = modifier
            .height(36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(colors.danger.copy(alpha = 0.12f))
            .border(1.dp, colors.danger.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
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
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp),
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
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(s1, s2, s3).forEach { scale ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .graphicsLayer {
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isPlaying) colors.accentInk else colors.faint)
            )
        }
    }
}

/**
 * Translucent Now Playing bar strictly locked to the active playing/paused song.
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
    val frac = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.glassBg,
        border = BorderStroke(1.dp, if (state.isPlaying) colors.accent.copy(alpha = 0.55f) else colors.border),
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (state.isPlaying) colors.accent else colors.raised)
                        .clickable { onTogglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentInk,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = if (state.isPlaying) Color(0xFF0B0C0F) else colors.text,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                LiveEqualizerBars(isPlaying = state.isPlaying)

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = state.title.ifBlank { "Untitled" },
                            color = colors.text,
                            fontSize = 12.5.sp,
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
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(
                        text = "${state.artist} · ${if (state.isOutro) "OUTRO CLIP" else "Main"}",
                        color = colors.muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = "${AudioPipeline.formatClock(state.positionMs / 1000.0)} / ${AudioPipeline.formatClock(state.durationMs / 1000.0)}",
                    color = colors.muted,
                    fontSize = 10.5.sp,
                )

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (state.loop) colors.accentSoft else colors.raised,
                    border = BorderStroke(1.dp, if (state.loop) colors.accent.copy(alpha = 0.55f) else colors.border),
                    modifier = Modifier
                        .height(28.dp)
                        .clickable { onToggleLoop() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Repeat,
                            contentDescription = "Loop",
                            tint = if (state.loop) colors.accentInk else colors.muted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .pointerInput(state.durationMs) {
                        detectTapGestures { pos ->
                            if (size.width > 0) onSeek((pos.x / size.width.toFloat()).coerceIn(0f, 1f))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
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
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Total: ${AudioPipeline.formatDurationMs(safeDur)}",
                color = colors.muted,
                fontSize = 11.5.sp,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.ground)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
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
            val padX = 10f
            val usableW = max(1f, w - padX * 2)
            val barGap = 2.5f
            val barW = max(1.5f, (usableW - barGap * (bars.size - 1)) / bars.size)

            val selLeft = padX + startFrac * usableW
            val selRight = padX + endFrac * usableW

            // Selection band
            drawRect(
                color = colors.accent.copy(alpha = 0.22f),
                topLeft = Offset(selLeft, 0f),
                size = Size(max(2f, selRight - selLeft), h),
            )

            bars.forEachIndexed { idx, amp ->
                val x = padX + idx * (barW + barGap)
                val barH = max(6f, amp.coerceIn(0.08f, 1f) * (h - 18f))
                val inRange = x + barW >= selLeft && x <= selRight
                drawRoundRect(
                    color = if (inRange) colors.accentInk else colors.faint.copy(alpha = 0.45f),
                    topLeft = Offset(x, (h - barH) / 2f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }

            // Left & right trim handles
            drawLine(colors.accentInk, Offset(selLeft, 0f), Offset(selLeft, h), strokeWidth = 3f)
            drawLine(colors.accentInk, Offset(selRight, 0f), Offset(selRight, h), strokeWidth = 3f)
            drawRoundRect(colors.accentInk, Offset(selLeft - 6f, h / 2f - 14f), Size(12f, 28f), CornerRadius(6f, 6f))
            drawRoundRect(colors.accentInk, Offset(selRight - 6f, h / 2f - 14f), Size(12f, 28f), CornerRadius(6f, 6f))
        }
    }
}
