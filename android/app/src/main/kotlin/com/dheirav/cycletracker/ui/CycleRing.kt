package com.dheirav.cycletracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where everything on the ring goes, in degrees clockwise from twelve o'clock.
 *
 * One lap is the expected cycle length, so day 1 starts at the top and the expected next period
 * lands back at the top. Pulled out of the drawing so the rule that matters can be tested: a late
 * cycle **never wraps**. On day 40 of a 28-day cycle a plain ring would put today at day 12's
 * position, which reads as "early in the cycle" and is the opposite of the truth. Instead the first
 * lap completes and the days past it become [overflowSweep], a second lap drawn apart from the first.
 */
data class RingGeometry(
    /** The elapsed arc on the first lap. 360 once the expected length has passed. */
    val progressSweep: Float,
    /** Days past the expected length, as a sweep on the second lap. Zero while on time. */
    val overflowSweep: Float,
    /** Bleeding days at the start of the cycle, as used for the phase boundaries. */
    val periodSweep: Float,
    /**
     * The part of [periodSweep] the user logged. The remainder is the expected period length
     * standing in for days nobody has answered yet (§5.1), and is drawn dashed, the calendar's mark
     * for estimated. It was one solid arc: two logged days and three assumed ones, all in the
     * observed colour, on the most prominent graphic in the app (device review M1).
     */
    val loggedPeriodSweep: Float,
    /** Centre of the assumed ovulation day, or null when there is none to show. */
    val ovulationAngle: Float?,
    /** The expected window as start angle and sweep, or null once it has passed. */
    val window: Pair<Float, Float>?,
) {
    /** Where today's dot sits: the end of whichever lap it is on. */
    val todayAngle: Float get() = if (overflowSweep > 0f) overflowSweep else progressSweep
    val onSecondLap: Boolean get() = overflowSweep > 0f
}

/**
 * [windowStartDay] and [windowEndDay] are cycle days, so a window from the day before the expected
 * date to two days after it on a 28-day cycle is 28 to 31. Both are null when there is no window or
 * it has passed: a passed window is history, and the hero already says how late.
 */
fun ringGeometry(
    cycleDay: Int,
    expectedLength: Int,
    periodLength: Int?,
    loggedPeriodDays: Int,
    ovulationDay: Int?,
    windowStartDay: Int?,
    windowEndDay: Int?,
): RingGeometry {
    val perDay = 360f / expectedLength.coerceAtLeast(1)
    val late = (cycleDay - expectedLength).coerceAtLeast(0)
    return RingGeometry(
        // Day N fills N days of the lap, so day 1 shows a sliver rather than nothing.
        progressSweep = (cycleDay.coerceAtMost(expectedLength) * perDay).coerceAtMost(360f),
        // Capped short of a full second lap, so the dot can never come back round to the top and
        // look like a new cycle has begun. Past that point the words carry it.
        overflowSweep = (late * perDay).coerceAtMost(330f),
        periodSweep = ((periodLength ?: 0) * perDay).coerceAtMost(360f),
        loggedPeriodSweep = (loggedPeriodDays.coerceAtMost(periodLength ?: 0) * perDay).coerceAtMost(360f),
        ovulationAngle = ovulationDay?.let { (it - 0.5f) * perDay },
        window = if (windowStartDay != null && windowEndDay != null && windowEndDay >= windowStartDay) {
            (windowStartDay - 1) * perDay to (windowEndDay - windowStartDay + 1) * perDay
        } else {
            null
        },
    )
}

/**
 * The cycle as a loop, the way every widely used tracker shows it (docs/UI_COMPARISON.md), drawn
 * with this app's rules rather than theirs.
 *
 * - The forecast is a **band**, dashed, outside the ring: the same dashed-means-predicted language
 *   as the calendar, and never a single tick on one date.
 * - Phase marks are soft. Only the period days and the assumed ovulation day are marked, because
 *   they are the two the user can act on, and the ovulation mark is a hollow ring since the day is
 *   assumed rather than measured (§5.1, §7).
 * - Late days go round a second, inner lap instead of wrapping (see [RingGeometry]).
 *
 * Hidden from accessibility services. Everything it shows is said in words on the same card or the
 * window card below it, and a screen reader describing arcs would be noise between those facts.
 *
 * [content] sits in the middle; the mascot lives there.
 */
@Composable
fun CycleRing(
    geometry: RingGeometry,
    ink: Color,
    period: Color,
    halo: Color,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp,
    content: @Composable () -> Unit = {},
) {
    // Sweeps in once when Today opens, which is most of the "alive" feeling the static card lacked.
    // Runs to the same picture every time, so it shows nothing the still frame does not.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing)) }

    // Slow enough to read as breathing rather than as an alert. With animations turned off in the
    // system settings it holds still at its midpoint.
    val glow by rememberInfiniteTransition(label = "today").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )

    Box(modifier = modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        // Under the marks, so a dot on the inner lap is never hidden behind the mascot.
        content()
        Canvas(Modifier.size(size)) {
            val track = 9.dp.toPx()
            val outer = this.size.minDimension / 2f
            // The window orbit sits outside the track, the second lap inside it.
            val ringRadius = outer - 9.dp.toPx() - track / 2f
            val windowRadius = outer - 3.dp.toPx()
            // Far enough in that today's dot, halo and glow on the second lap never touch the track.
            // At 6dp in they overlapped it, and day 40 read as a dot on the main ring at day 12
            // (device review M2), the misreading the second lap exists to prevent.
            val lapRadius = ringRadius - track / 2f - 12.dp.toPx()
            val t = reveal.value

            // Track: the whole expected cycle, faint.
            drawCircle(ink.copy(alpha = 0.18f), radius = ringRadius, style = Stroke(track))
            // Period days, at the start of the lap.
            if (geometry.loggedPeriodSweep > 0f) arc(period.copy(alpha = 0.9f), 0f, geometry.loggedPeriodSweep * t, ringRadius, track)
            val guessed = geometry.periodSweep - geometry.loggedPeriodSweep
            if (guessed > 0f) {
                val dash = 2.5.dp.toPx()
                drawArc(
                    color = period.copy(alpha = 0.85f),
                    startAngle = geometry.loggedPeriodSweep - 90f,
                    sweepAngle = guessed * t,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(width = track * 0.35f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            }
            // Elapsed, drawn over the track. Thinner than the track, so the period colour still
            // shows at the edges of the days it has passed through.
            // Dimmed once the lap is complete: a solid full loop was the loudest thing on the card in
            // exactly the state where the second lap and today's dot are what matter.
            val lapAlpha = if (geometry.onSecondLap) 0.4f else 0.85f
            arc(ink.copy(alpha = lapAlpha), 0f, geometry.progressSweep * t, ringRadius, track * 0.45f)

            // Assumed ovulation: a small hollow ring, no halo. With a halo it was a dark dot with a
            // white rim beside today's white dot, and on the Redmi it read as a second "today". Left
            // out on a second lap, where that estimate is long past and only adds a mark to misread.
            if (!geometry.onSecondLap) geometry.ovulationAngle?.let { a ->
                drawCircle(
                    ink.copy(alpha = 0.7f * t),
                    radius = track * 0.3f,
                    center = point(a, ringRadius),
                    style = Stroke(1.2.dp.toPx()),
                )
            }

            // The forecast window, dashed, outside the ring.
            geometry.window?.let { (start, sweep) ->
                val dash = 3.dp.toPx()
                drawArc(
                    color = ink.copy(alpha = 0.75f * t),
                    startAngle = start - 90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - windowRadius, center.y - windowRadius),
                    size = Size(windowRadius * 2, windowRadius * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.2f))),
                )
            }

            // Days past the expected length: a dotted second lap, inside.
            if (geometry.onSecondLap) {
                val dot = 2.5.dp.toPx()
                drawArc(
                    color = ink.copy(alpha = 0.7f),
                    startAngle = -90f,
                    sweepAngle = geometry.overflowSweep * t,
                    useCenter = false,
                    topLeft = Offset(center.x - lapRadius, center.y - lapRadius),
                    size = Size(lapRadius * 2, lapRadius * 2),
                    style = Stroke(width = dot, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, dot * 2.2f))),
                )
            }

            // Today, the one mark that matters most, so the only one that glows. A halo in the card's
            // own colour lifts it off the track; the soft pulse round it is what the eye lands on.
            val todayRadius = if (geometry.onSecondLap) lapRadius else ringRadius
            val c = point(geometry.todayAngle * t, todayRadius)
            val glowReach = if (geometry.onSecondLap) 0.2f else 0.5f
            drawCircle(ink.copy(alpha = 0.22f * glow * t), radius = track * (1.0f + glowReach * glow), center = c)
            drawCircle(halo, radius = track * 0.85f, center = c)
            drawCircle(ink, radius = track * 0.6f, center = c)
        }
    }
}

private fun DrawScope.point(angle: Float, radius: Float): Offset {
    val rad = Math.toRadians((angle - 90f).toDouble())
    return Offset(center.x + radius * cos(rad).toFloat(), center.y + radius * sin(rad).toFloat())
}

private fun DrawScope.arc(color: Color, start: Float, sweep: Float, radius: Float, width: Float) {
    if (sweep <= 0f) return
    drawArc(
        color = color,
        startAngle = start - 90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}
