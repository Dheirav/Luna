package com.dheirav.cycletracker.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import java.time.LocalDate

/**
 * An explanation that says itself in full for the first few days it appears, then steps back
 * behind an ⓘ.
 *
 * The daily screens had grown about a hundred words of sentences, every one honest and each worth
 * reading once, and by the tenth day they were what the screen was mostly made of. The rules they
 * explain do not change; only the repetition goes. Counted in distinct days rather than views, so
 * opening the app five times on the first evening does not use up the introduction.
 *
 * Not for qualifiers that change what a number means ("estimated", "typical spread"): those stay
 * on screen as short marks every time. Not for anything about seeing a doctor once it is close.
 */
@Stable
class Explanation internal constructor(val fresh: Boolean) {
    var open by mutableStateOf(false)
        internal set

    /** Whether the full text belongs on screen right now. */
    val showText: Boolean get() = fresh || open

    fun toggle() { open = !open }
}

private const val PREFS = "explanations"
private const val DEFAULT_DAYS = 3
private const val SEEDED = "_seeded"
private const val EXPERIENCED = "_experienced"

/** [key] names the explanation; the same key on two screens shares one count. */
@Composable
fun rememberExplanation(key: String, days: Int = DEFAULT_DAYS): Explanation {
    val context = LocalContext.current
    return remember(key) { Explanation(countDay(context, key, days)) }
}

/** True while [key] has been shown on fewer than [days] distinct days, counting today once. */
private fun countDay(context: Context, key: String, days: Int): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Someone who has had the app longer than the introduction lasts has read these sentences many
    // times already, so the first version with this counter treats them as seen rather than
    // repeating every one for three more days. Decided once, from the install date, which an update
    // keeps.
    if (!prefs.contains(SEEDED)) {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(System.currentTimeMillis())
        val experienced = System.currentTimeMillis() - installed > days * 86_400_000L
        prefs.edit().putBoolean(SEEDED, true).putBoolean(EXPERIENCED, experienced).apply()
    }
    if (prefs.getBoolean(EXPERIENCED, false) && !prefs.contains("$key.count")) return false
    val today = LocalDate.now().toString()
    val count = prefs.getInt("$key.count", 0)
    val last = prefs.getString("$key.last", null)
    if (last == today) return count <= days
    if (count >= days) return false
    prefs.edit().putInt("$key.count", count + 1).putString("$key.last", today).apply()
    return true
}

/**
 * The ⓘ beside a title, as Settings has, shown once the explanation has stepped back. Hidden while
 * it is still being shown in full, because a button to reveal text already on screen is noise.
 */
@Composable
fun ExplainButton(explanation: Explanation, about: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    if (explanation.fresh) return
    IconButton(
        onClick = explanation::toggle,
        modifier = Modifier.semantics { stateDescription = if (explanation.open) "Expanded" else "Collapsed" },
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = if (explanation.open) "Hide the explanation of $about" else "Explain $about",
            tint = tint,
        )
    }
}
