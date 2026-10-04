package com.dheirav.cycletracker.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Small decorative marks, drawn rather than shipped.
 *
 * Every reference shared used little sparkles and dots to soften the layout, and the note on them
 * was that they work when *appropriately sized* — framing the content instead of competing with
 * it. Two rules follow, and they are the whole design here:
 *
 *  - **Nothing decorative appears on the log screen.** That screen has one job, ten seconds long,
 *    and it is the only thing standing between this app and an empty database (rule 4). Charm
 *    there costs adherence.
 *  - **Decoration never carries meaning.** If a sparkle ever indicated something, colour-blind
 *    users and anyone reading quickly would lose it. Meaning lives in [CycleColors] and in words.
 *
 * Drawn with [Canvas] rather than bundled as PNGs: a handful of vertices instead of kilobytes of
 * bitmap, sharp at any density, and tintable so the same mark works in both schemes.
 *
 * **Every composable here hides itself from accessibility services**, via [decorative]. That is
 * enforced at the source rather than at each call site precisely because it is the kind of thing
 * that gets forgotten on the twentieth sparkle. Since decoration never carries meaning (see above),
 * a screen reader announcing it would be pure noise between the facts a user came for.
 */

/** Removes a purely ornamental element from the accessibility tree entirely. */
private fun Modifier.decorative(): Modifier = clearAndSetSemantics { }

/**
 * A four-point sparkle with concave sides — the shape used throughout the references.
 *
 * The 0.16 control-point factor is what makes the arms taper: pulling the curve controls close to
 * the centre bows the edges inward. Higher values approach a plain diamond.
 */
@Composable
fun Sparkle(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    Canvas(modifier = modifier.decorative().size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = minOf(cx, cy)
        val pull = r * 0.16f

        val path = Path().apply {
            moveTo(cx, cy - r)
            quadraticTo(cx + pull, cy - pull, cx + r, cy)
            quadraticTo(cx + pull, cy + pull, cx, cy + r)
            quadraticTo(cx - pull, cy + pull, cx - r, cy)
            quadraticTo(cx - pull, cy - pull, cx, cy - r)
            close()
        }
        drawPath(path, color)
    }
}

/** A plain soft dot. Reads as punctuation next to a [Sparkle] rather than as another shape. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 5.dp) {
    Canvas(modifier = modifier.decorative().size(size)) {
        drawCircle(color, radius = this.size.minDimension / 2f, center = Offset(this.size.width / 2f, this.size.height / 2f))
    }
}

/** A soft heart. Two lobes and a point, drawn as one path so it scales cleanly at any size. */
@Composable
fun Heart(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Canvas(modifier = modifier.decorative().size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w / 2f, h * 0.94f)
            cubicTo(w * -0.14f, h * 0.56f, w * 0.14f, h * 0.02f, w / 2f, h * 0.30f)
            cubicTo(w * 0.86f, h * 0.02f, w * 1.14f, h * 0.56f, w / 2f, h * 0.94f)
            close()
        }
        drawPath(path, color)
    }
}

/**
 * A cloud: three overlapping circles on a rounded base.
 *
 * Circles rather than a hand-tuned bezier because the silhouette is what reads at this size, and
 * overlapping circles give a rounder, softer edge than curves fitted by eye.
 */
@Composable
fun Cloud(color: Color, modifier: Modifier = Modifier, width: Dp = 34.dp) {
    Canvas(modifier = modifier.decorative().size(width, width * 0.62f)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(color, radius = h * 0.42f, center = Offset(w * 0.30f, h * 0.55f))
        drawCircle(color, radius = h * 0.50f, center = Offset(w * 0.53f, h * 0.46f))
        drawCircle(color, radius = h * 0.36f, center = Offset(w * 0.76f, h * 0.58f))
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.16f, h * 0.55f),
            size = androidx.compose.ui.geometry.Size(w * 0.70f, h * 0.40f),
            cornerRadius = CornerRadius(h * 0.20f),
        )
    }
}

/**
 * The mascot's face. **It reacts only to mood logged today, never to the phase.**
 *
 * It used to follow the phase (sleepy while bleeding, bright at ovulation), which meant it smiled on
 * day 41 of a 28-day cycle and was, in effect, the app telling the user how they felt from a
 * calendar. Phase guidance and the user's own logs are separate surfaces, and a face is the most
 * first-person thing on the screen, so it now comes from today's log alone:
 *
 *  - [RESTING]: nothing logged today, or a middling day. Eyes open, mouth level. Deliberately not a
 *    smile, because a smile is a claim and there is nothing to base one on.
 *  - [CALM]: mood logged as settled. A small smile, and no more than that.
 *  - [TENDER]: mood logged as heavy. Eyes softly closed, mouth turned down a little. Sympathy, not
 *    alarm.
 *
 * The range still stops well short of cheerful or distressed. The mascot is never the only indicator
 * of anything, and the phase is always spelled out beside it.
 */
enum class MascotMood { RESTING, CALM, TENDER }

/**
 * A small cloud character.
 *
 * A cloud rather than an animal so it stays abstract: clouds have no gender, no body, and no
 * opinion about yours. It sits beside the phase text and carries none of the information.
 */
@Composable
fun MascotCloud(
    body: Color,
    face: Color,
    blush: Color,
    mood: MascotMood,
    modifier: Modifier = Modifier,
    width: Dp = 78.dp,
) {
    Canvas(modifier = modifier.decorative().size(width, width * 0.72f)) {
        val w = size.width
        val h = size.height

        drawCircle(body, radius = h * 0.34f, center = Offset(w * 0.24f, h * 0.60f))
        drawCircle(body, radius = h * 0.30f, center = Offset(w * 0.78f, h * 0.62f))
        drawCircle(body, radius = h * 0.44f, center = Offset(w * 0.50f, h * 0.48f))
        drawRoundRect(
            color = body,
            topLeft = Offset(w * 0.12f, h * 0.52f),
            size = Size(w * 0.76f, h * 0.40f),
            cornerRadius = CornerRadius(h * 0.22f),
        )

        val eyeY = h * 0.50f
        val leftX = w * 0.38f
        val rightX = w * 0.62f
        val eyeR = h * 0.055f
        val stroke = Stroke(width = h * 0.05f, cap = StrokeCap.Round)

        // Blush first, so the eyes sit on top of it rather than under a wash.
        drawCircle(blush, radius = h * 0.085f, center = Offset(w * 0.28f, h * 0.61f))
        drawCircle(blush, radius = h * 0.085f, center = Offset(w * 0.72f, h * 0.61f))

        when (mood) {
            // Softly closed: the lower half of an arc, like lowered lids.
            MascotMood.TENDER -> listOf(leftX, rightX).forEach { x ->
                drawArc(
                    color = face,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(x - eyeR * 1.6f, eyeY - eyeR * 1.2f),
                    size = Size(eyeR * 3.2f, eyeR * 2.0f),
                    style = stroke,
                )
            }
            MascotMood.RESTING, MascotMood.CALM -> {
                drawCircle(face, radius = eyeR, center = Offset(leftX, eyeY))
                drawCircle(face, radius = eyeR, center = Offset(rightX, eyeY))
            }
        }

        val mouthY = h * 0.64f
        val mouthW = w * 0.10f
        when (mood) {
            MascotMood.RESTING -> drawLine(
                color = face,
                start = Offset(w * 0.50f - mouthW / 2f, mouthY),
                end = Offset(w * 0.50f + mouthW / 2f, mouthY),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            MascotMood.CALM -> drawArc(
                color = face,
                startAngle = 15f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(w * 0.50f - mouthW / 2f, h * 0.58f),
                size = Size(mouthW, h * 0.11f),
                style = stroke,
            )
            // The same arc turned over and flattened, so it reads as subdued rather than upset.
            MascotMood.TENDER -> drawArc(
                color = face,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(w * 0.50f - mouthW / 2f, mouthY - h * 0.01f),
                size = Size(mouthW, h * 0.08f),
                style = stroke,
            )
        }
    }
}

/**
 * A card edge with semicircular bumps along the bottom — the scalloped frame from the references.
 *
 * Bumps are sized to divide the width evenly rather than fixed in dp, so the scallop never ends
 * mid-curve on a narrow screen. [bumps] is a target count, not a guarantee.
 */
class ScallopedBottomShape(private val bumps: Int = 9, private val topRadius: Dp = 26.dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { topRadius.toPx() }.coerceAtMost(size.minDimension / 2f)
        val bumpWidth = size.width / bumps
        val bumpRadius = bumpWidth / 2f

        val path = Path().apply {
            moveTo(0f, r)
            quadraticTo(0f, 0f, r, 0f)
            lineTo(size.width - r, 0f)
            quadraticTo(size.width, 0f, size.width, r)
            lineTo(size.width, size.height - bumpRadius)
            // Right to left along the bottom, each bump a half-circle bitten out of the edge.
            var x = size.width
            while (x > 0f) {
                val next = (x - bumpWidth).coerceAtLeast(0f)
                arcTo(
                    rect = Rect(
                        left = next,
                        top = size.height - bumpWidth,
                        right = x,
                        bottom = size.height,
                    ),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                x = next
            }
            lineTo(0f, r)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Three marks at fixed offsets, for the corner of a header card.
 *
 * Fixed rather than random: a layout that reshuffles itself on every recomposition reads as a
 * glitch, and there is no seed available anyway — the engine forbids unpredictable values
 * reaching the UI, and that discipline is worth keeping even for ornament.
 */
@Composable
fun SparkleCluster(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.decorative().size(46.dp)) {
        Sparkle(color = color, size = 15.dp, modifier = Modifier.offset(x = 22.dp, y = 2.dp))
        Sparkle(color = color.copy(alpha = 0.65f), size = 9.dp, modifier = Modifier.offset(x = 6.dp, y = 15.dp))
        Dot(color = color.copy(alpha = 0.5f), size = 5.dp, modifier = Modifier.offset(x = 33.dp, y = 26.dp))
    }
}
