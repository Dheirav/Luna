package com.dheirav.cycletracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dheirav.cycletracker.core.Phase

/**
 * A fixed pastel palette, replacing Material You.
 *
 * Dynamic colour derives everything from the wallpaper, which makes the app look like whatever
 * happens to be behind the home screen — soft pink one week, olive green the next. That is the
 * opposite of an identity, and it cannot produce the pink-and-cream look this app is going for.
 * The cost is real and worth naming: the app no longer follows system theming.
 *
 * Both a light and a dark scheme exist, and the dark one is a warm, muted plum rather than black.
 * The daily reminder fires at 21:00, so a good part of all logging happens in a dark room; a
 * bright pastel screen at that hour is the kind of small friction that ends a logging habit, and
 * adherence is the constraint everything else depends on (rule 4).
 */

// -- palette -----------------------------------------------------------------

private val Rose = Color(0xFFE0669B)
private val RoseLight = Color(0xFFF8CBDF)
private val RoseDeep = Color(0xFF7B2D5E)
private val Lavender = Color(0xFF9B85DE)
private val LavenderLight = Color(0xFFE7DEFA)
private val Honey = Color(0xFFE8B44C)
private val HoneyLight = Color(0xFFFBEFCF)
private val Cream = Color(0xFFFDF4E7)
private val Blush = Color(0xFFFFF7FA)
private val Ink = Color(0xFF43303C)

private val PlumDark = Color(0xFF1D1620)
private val PlumSurface = Color(0xFF2A2131)
private val PlumRaised = Color(0xFF362A3E)
private val RosePale = Color(0xFFF3AFCB)
private val LavenderPale = Color(0xFFC5B4F0)

private val LightScheme = lightColorScheme(
    primary = Rose,
    onPrimary = Color.White,
    primaryContainer = RoseLight,
    onPrimaryContainer = RoseDeep,
    secondary = Lavender,
    onSecondary = Color.White,
    secondaryContainer = LavenderLight,
    onSecondaryContainer = Color(0xFF3B2C63),
    tertiary = Honey,
    onTertiary = Color.White,
    tertiaryContainer = HoneyLight,
    onTertiaryContainer = Color(0xFF5C4413),
    background = Blush,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Cream,
    onSurfaceVariant = Color(0xFF7A6470),
    outline = Color(0xFFD9C4CF),
    outlineVariant = Color(0xFFEEDFE6),
    // Reserved for genuine failures — a failed restore, an unreadable backup. Never for bleeding.
    error = Color(0xFFB3324B),
    onError = Color.White,
    errorContainer = Color(0xFFFBDDE2),
    onErrorContainer = Color(0xFF6B1020),
)

private val DarkScheme = darkColorScheme(
    primary = RosePale,
    onPrimary = Color(0xFF4B1233),
    primaryContainer = Color(0xFF6B2249),
    onPrimaryContainer = Color(0xFFFFD9E6),
    secondary = LavenderPale,
    onSecondary = Color(0xFF2E2154),
    secondaryContainer = Color(0xFF463868),
    onSecondaryContainer = Color(0xFFE7DEFA),
    tertiary = Color(0xFFF0C978),
    onTertiary = Color(0xFF4A3410),
    tertiaryContainer = Color(0xFF63491C),
    onTertiaryContainer = Color(0xFFFBEFCF),
    background = PlumDark,
    onBackground = Color(0xFFF2E4EC),
    surface = PlumSurface,
    onSurface = Color(0xFFF2E4EC),
    surfaceVariant = PlumRaised,
    onSurfaceVariant = Color(0xFFC7B2C0),
    outline = Color(0xFF6B5766),
    outlineVariant = Color(0xFF44374C),
    error = Color(0xFFFFA3AF),
    onError = Color(0xFF5C1120),
    errorContainer = Color(0xFF7D2437),
    onErrorContainer = Color(0xFFFFD9DE),
)

/**
 * Colours that mean something in this domain, kept out of [MaterialTheme.colorScheme].
 *
 * Bleeding used to be drawn with `colorScheme.error`. On a neutral theme that passed unnoticed;
 * on a pink one it is both ambiguous and quietly insulting — a period is not an error state. More
 * practically, the app needs to distinguish *observed* from *predicted* from *estimated*, and
 * Material's roles have no vocabulary for that. Inventing meanings for `tertiary` would leave the
 * next person guessing.
 */
@Immutable
data class CycleColors(
    /** A day the user logged bleeding on. The strongest mark in the app. */
    val bleeding: Color,
    val onBleeding: Color,
    /**
     * An estimated bleeding day — drawn as a **dashed** outline, never a fill.
     *
     * Dashed rather than solid, and dustier than [bleeding], because today's marker is also a thin
     * ring in `primary`. When this was a light pink solid ring the two were one hue apart and
     * differed by half a device-independent pixel of stroke: the calendar's legend taught that a
     * pink ring meant "estimated", and today was a pink ring. A broken line carries "provisional"
     * without relying on colour at all, so it also survives colour-blindness and greyscale.
     */
    val estimated: Color,
    /** The predicted window for the next period. Deliberately softer than [bleeding]: a forecast
     *  must never look as solid as an observation. */
    val predicted: Color,
    /** A day carrying symptoms or notes but no bleeding. */
    val logged: Color,
    /**
     * One tint per cycle phase, used for the hero card's gradient.
     *
     * Doing real work as well as looking nice: four distinguishable moods mean the phase is
     * legible before the word is read. Kept as a *pair* per phase so the gradient has somewhere to
     * travel — a flat fill looks unfinished at this size.
     *
     * Never the only indicator of anything. The phase is always spelled out beside it, because a
     * colour-coded app is unusable to a colour-blind user and unreadable in bright sun.
     */
    val phase: Map<Phase, Pair<Color, Color>>,
    /**
     * Text and ornament on top of a phase gradient.
     *
     * Its own colour rather than `onPrimaryContainer`, which is pink — fine over a pink card, and
     * badly wrong over the green or gold ones. Ornament derives from this too, so a single value
     * keeps clouds and sparkles legible on all four gradients in both schemes: dark marks on pale
     * light-mode pastels, pale marks on deep dark-mode ones.
     */
    val onPhase: Color,
    /**
     * The mascot's body, and the face knocked out of it.
     *
     * **Its own pair rather than derived from [onPhase]**, which is what it used to be:
     * `body = onPhase.copy(alpha = 0.92f)`, `face = ` the card's own gradient bottom. That made the
     * mascot invert with the *text* — cream on deep purple in dark mode, which is the character as
     * drawn, and near-black on pale lavender in light mode, which is a heavy dark mass. It also
     * handed a piece of pure decoration the same visual weight as the phase name beside it, while
     * every other ornament on that card sits between 0.18 and 0.30 alpha.
     *
     * Lowering the alpha is not available as a fix: the face is knocked *out* of the body rather
     * than drawn on top of it, so the pair has to stay contrasty or the mascot loses its face. Hence
     * two stated colours per scheme instead of one colour and an alpha.
     *
     * Deliberately the same on all four phase cards, like [onPhase]. The mascot's mood already
     * varies with the phase; its colour varying too would imply the hue meant something.
     */
    val mascotBody: Color,
    val mascotFace: Color,
)

private val LightCycleColors = CycleColors(
    bleeding = Color(0xFFE86A93),
    onBleeding = Color.White,
    // Dustier than it was (#EFA9C0), to sit further from `primary` #E0669B than a shade of
    // lightness. The dashes do the real separating; this stops the two reading as one family.
    estimated = Color(0xFFC98CA6),
    predicted = Color(0xFFCBB6EE),
    logged = Lavender,
    phase = mapOf(
        Phase.MENSTRUATION to (Color(0xFFFFD3E0) to Color(0xFFFFB8CE)),
        Phase.FOLLICULAR to (Color(0xFFD6F0E4) to Color(0xFFBCE6D6)),
        Phase.OVULATION to (Color(0xFFFDEBC4) to Color(0xFFFBDC9C)),
        Phase.LUTEAL to (Color(0xFFE6DDFA) to Color(0xFFD3C5F5)),
    ),
    onPhase = Ink,
    // A medium violet: unmistakably a character on every one of the four pale cards, where Ink at
    // 0.92 was the darkest thing on the screen.
    mascotBody = Color(0xFF7E6BC0),
    mascotFace = Color(0xFFF7F3FE),
)

private val DarkCycleColors = CycleColors(
    bleeding = Color(0xFFE87FA3),
    onBleeding = Color(0xFF3D0C22),
    estimated = Color(0xFFA9748C),
    predicted = Color(0xFF8B76B8),
    logged = LavenderPale,
    /**
     * **No gold here, and that is not an oversight.**
     *
     * Ovulation is honey in light mode and coral in dark. Two attempts at a dark gold both came
     * out mud — first olive, then ochre — because yellow-ish hues read as brown at any lightness
     * a dark theme can afford. Saturation does not rescue it; it only moves brown toward orange.
     * The only gold that survives a dark ground is a card bright enough to glare at 21:00, which
     * is exactly when this screen gets used.
     *
     * So dark mode changes the *hue* rather than dimming it. Ovulation becomes a warm rose-coral,
     * which keeps the palette in one family and cannot turn to mud.
     *
     * That puts two pinks in play, so menstruation is pushed cooler and plummier here than its
     * light-mode counterpart: the pair separate on **warmth**, not just lightness. Check them side
     * by side after any change — this is the one collision in the set.
     */
    phase = mapOf(
        Phase.MENSTRUATION to (Color(0xFF5B2050) to Color(0xFF7A2C6B)),
        Phase.FOLLICULAR to (Color(0xFF12564A) to Color(0xFF1B7565)),
        Phase.OVULATION to (Color(0xFFA83F55) to Color(0xFFC85A63)),
        Phase.LUTEAL to (Color(0xFF3E2B84) to Color(0xFF553BAE)),
    ),
    onPhase = Color(0xFFFDF2F7),
    // Unchanged from what dark mode already rendered — `onPhase` at 0.92 over a deep card came out
    // here, and dark mode is where the character was designed. It reads as a pale cloud, correctly.
    mascotBody = Color(0xFFFDF2F7),
    // Was the card's own gradient bottom, which meant the eyes went teal on the follicular card.
    // A stated deep plum works inside a pale body on all four.
    mascotFace = Color(0xFF4A3573),
)

private val LocalCycleColors = staticCompositionLocalOf { LightCycleColors }

/** `MaterialTheme.cycleColors` — reads like the rest of the theme at the call site. */
val MaterialTheme.cycleColors: CycleColors
    @Composable @ReadOnlyComposable get() = LocalCycleColors.current

// -- shape and type ----------------------------------------------------------

/**
 * Generous radii throughout. Every reference shared was built on soft rectangles, and the
 * difference between 12dp and 20dp is most of what separates "friendly" from "corporate".
 */
private val CycleShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/**
 * Hierarchy by weight and size rather than by colour, so it survives both schemes.
 *
 * The system typeface is used deliberately. A rounded face (Quicksand, Nunito) would suit the
 * references better, but the app has no `INTERNET` permission and never will, which rules out
 * downloadable fonts — it would mean bundling a TTF and paying 50–100 KB of APK for it. Worth
 * doing if a font file is supplied; not worth guessing at.
 */
private val CycleTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 21.sp),
        bodySmall = bodySmall.copy(lineHeight = 18.sp),
    )
}

@Composable
fun CycleTrackerTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCycleColors provides if (dark) DarkCycleColors else LightCycleColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            shapes = CycleShapes,
            typography = CycleTypography,
            content = content,
        )
    }
}

/** Corner radius for the decorative page frame. Kept here so screens agree without coordinating. */
val PageCorner = 28.dp
