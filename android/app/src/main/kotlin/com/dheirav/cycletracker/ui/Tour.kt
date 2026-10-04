package com.dheirav.cycletracker.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The screens the tour walks through. */
enum class TourScreen { TODAY, LOG, HISTORY, SETTINGS }

/** The real elements the tour points at. Each is tagged where it is drawn with [tourTarget]. */
enum class TourTarget {
    HERO, WINDOW_CARD, WHY_CARD, LOG_BUTTON, HISTORY_BUTTON, SETTINGS_BUTTON,
    LOG_BLEEDING, LOG_SAVE, BACK, CALENDAR, REMINDER_CARD,
}

/**
 * One stop on the tour.
 *
 * [tapToContinue] steps are done by tapping the highlighted element itself, which really navigates,
 * and the tour moves on when the person arrives on [leadsTo]. The rest have a Next button and block
 * every touch, so the tour can never change data: nothing that saves is ever a tap-through step.
 */
data class TourStep(
    val screen: TourScreen,
    val target: TourTarget,
    val caption: String,
    val leadsTo: TourScreen? = null,
) {
    val tapToContinue: Boolean get() = leadsTo != null
}

/**
 * The tour, in order. One short line each, said next to the thing it is about.
 *
 * It replaced four pages of explanation on 5 Oct 2026, asked for as "show, don't tell": the person
 * finds Log today by tapping it, not by reading that it exists.
 */
fun tourSteps(): List<TourStep> = listOf(
    TourStep(TourScreen.TODAY, TourTarget.HERO, "Your cycle day and phase. Tap it any time to read about the phase."),
    TourStep(TourScreen.TODAY, TourTarget.WINDOW_CARD, "Your next period, as a range of days. It narrows as you log."),
    TourStep(TourScreen.TODAY, TourTarget.WHY_CARD, "Open this to see what every number here is based on."),
    TourStep(TourScreen.TODAY, TourTarget.LOG_BUTTON, "Tap Log today.", leadsTo = TourScreen.LOG),
    TourStep(TourScreen.LOG, TourTarget.LOG_BLEEDING, "Answer what you know. Anything you skip stays unknown, never zero."),
    TourStep(TourScreen.LOG, TourTarget.LOG_SAVE, "Save. An Undo appears straight after, in case."),
    TourStep(TourScreen.LOG, TourTarget.BACK, "Tap back. Nothing has been saved.", leadsTo = TourScreen.TODAY),
    TourStep(TourScreen.TODAY, TourTarget.HISTORY_BUTTON, "Tap History.", leadsTo = TourScreen.HISTORY),
    TourStep(TourScreen.HISTORY, TourTarget.CALENDAR, "Tap any day to add or fix it. Filled was logged, dashed was estimated."),
    TourStep(TourScreen.HISTORY, TourTarget.BACK, "Tap back.", leadsTo = TourScreen.TODAY),
    TourStep(TourScreen.TODAY, TourTarget.SETTINGS_BUTTON, "Tap Settings.", leadsTo = TourScreen.SETTINGS),
    TourStep(TourScreen.SETTINGS, TourTarget.REMINDER_CARD, "A nudge at 21:00, skipped on days you've logged."),
)

/** The step after arriving on [screen]: the next one if the current step led there, else unchanged. */
fun advanceOnScreen(steps: List<TourStep>, index: Int, screen: TourScreen): Int =
    if (steps.getOrNull(index)?.leadsTo == screen) index + 1 else index

/**
 * Where the tour is, and where its targets were last drawn.
 *
 * Bounds are keyed by target and carry the token of the element that wrote them, because the same
 * target can exist on two screens (the back arrow) and the new screen's element registers before the
 * old one is disposed. Without the token, the old one's removal would erase the new one's position.
 */
@Stable
class TourController {
    var index by mutableIntStateOf(-1)
        private set
    val steps = tourSteps()
    val active: Boolean get() = index in steps.indices
    val step: TourStep? get() = steps.getOrNull(index)
    val activeTarget: TourTarget? get() = step?.target

    internal val bounds = mutableStateMapOf<TourTarget, Pair<Any, Rect>>()

    fun start() { index = 0 }
    fun next() { index = if (index + 1 < steps.size) index + 1 else -1 }
    fun back() { if (index > 0) index-- }
    fun stop() { index = -1 }
    fun onScreen(screen: TourScreen) { if (active) index = advanceOnScreen(steps, index, screen) }
}

/** Null when no tour is running anywhere, which is nearly always. */
val LocalTour = compositionLocalOf<TourController?> { null }

/**
 * Tags an element as a place the tour can point at, and brings it into view when it is pointed at.
 * Costs nothing when no tour is running.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
    val tour = LocalTour.current ?: return@composed this
    val token = remember { Any() }
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(target) {
        onDispose { if (tour.bounds[target]?.first === token) tour.bounds.remove(target) }
    }
    LaunchedEffect(tour.activeTarget) {
        if (tour.activeTarget == target) {
            delay(120)
            requester.bringIntoView()
        }
    }
    bringIntoViewRequester(requester)
        .onGloballyPositioned { tour.bounds[target] = token to it.boundsInRoot() }
}

/**
 * The spotlight: the app dimmed, the target cut out with a pulsing ring, and one caption beside it.
 *
 * Touches are blocked everywhere except, on a tap-through step, the target itself, which is left
 * uncovered so the real button takes the tap. [onMissing] is called if a tap-through step's target
 * never appears (an empty state that lacks it), so the person is never stuck.
 */
@Composable
fun TourOverlay(
    tour: TourController,
    onMissing: (TourStep) -> Unit,
    action: (@Composable (TourStep) -> Unit)? = null,
) {
    val step = tour.step ?: return
    val density = LocalDensity.current
    val hole = tour.bounds[step.target]?.second?.let { with(density) { it.inflate(8.dp.toPx()) } }
    val ring = MaterialTheme.colorScheme.primary

    // A target that never appears: skip a looking step, or carry out a tap step's navigation.
    LaunchedEffect(tour.index, hole == null) {
        if (hole == null) {
            delay(900)
            if (tour.bounds[step.target] == null) {
                if (step.tapToContinue) onMissing(step) else tour.next()
            }
        }
    }

    val pulse by rememberInfiniteTransition(label = "tour").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ring",
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        // The dimmed screen, with the target cut out. Draws only; touches are handled below.
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                hole?.let { addRoundRect(RoundRect(it, CornerRadius(18.dp.toPx()))) }
            }
            drawPath(path, Color.Black.copy(alpha = 0.66f))
            hole?.let {
                drawRoundRect(
                    color = ring.copy(alpha = pulse),
                    topLeft = it.topLeft,
                    size = it.size,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // Touch blockers. On a tap-through step the target's own rectangle is left open.
        if (hole != null && step.tapToContinue) {
            val blocks = listOf(
                Rect(0f, 0f, width, hole.top),
                Rect(0f, hole.bottom, width, height),
                Rect(0f, hole.top, hole.left, hole.bottom),
                Rect(hole.right, hole.top, width, hole.bottom),
            )
            blocks.filter { it.width > 0 && it.height > 0 }.forEach { Blocker(it) }
        } else {
            Blocker(Rect(0f, 0f, width, height))
        }

        // The caption, below the target if it is in the top half, above it otherwise.
        val margin = with(density) { 16.dp.toPx() }
        val below = hole == null || hole.center.y < height / 2
        val captionTop = when {
            hole == null -> height / 3
            below -> hole.bottom + margin
            else -> null
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            val card = @Composable {
                TourCaption(
                    step = step,
                    position = "${tour.index + 1} of ${tour.steps.size}",
                    canGoBack = tour.index > 0 && !tour.steps[tour.index - 1].tapToContinue,
                    onNext = tour::next,
                    onBack = tour::back,
                    onSkip = tour::stop,
                    action = action,
                )
            }
            if (captionTop != null) {
                Box(Modifier.offset { IntOffset(0, captionTop.roundToInt()) }) { card() }
            } else {
                // Above the target: anchored to its top edge from below.
                val fromBottom = height - (hole!!.top - margin)
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(androidx.compose.ui.Alignment.BottomStart)
                            .offset { IntOffset(0, -fromBottom.roundToInt()) },
                    ) { card() }
                }
            }
        }
    }
}

/** Swallows every touch in [area], so nothing behind the spotlight can be pressed. */
@Composable
private fun Blocker(area: Rect) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
            .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    )
}

@Composable
private fun TourCaption(
    step: TourStep,
    position: String,
    canGoBack: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    action: (@Composable (TourStep) -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Announced as it changes, so a screen reader follows the tour too.
            Text(
                step.caption,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            action?.invoke(step)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(position, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onSkip) { Text("Skip tour") }
                    if (canGoBack) OutlinedButton(onClick = onBack) { Text("Back") }
                    // A tap-through step is done by tapping the real thing, so it has no Next.
                    if (!step.tapToContinue) Button(onClick = onNext) { Text(if (step.target == TourTarget.REMINDER_CARD) "Done" else "Next") }
                }
            }
        }
    }
}
