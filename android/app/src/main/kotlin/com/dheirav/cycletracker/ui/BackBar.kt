package com.dheirav.cycletracker.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The way out of a secondary screen, drawn where Android apps put it.
 *
 * Every screen but Today could only be left with the system Back gesture, which gesture navigation
 * hides and some people never learn. An up arrow at the top left is the convention every other app on
 * the phone follows, so its absence made these screens feel like a trap.
 *
 * It presses the system Back rather than navigating itself. That way it runs through exactly the
 * handlers a gesture would, including the log form's prompt about unsaved changes, and the two can
 * never disagree about where Back goes.
 */
@Composable
fun BackBar(title: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val color = if (tint == Color.Unspecified) LocalContentColor.current else tint
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { dispatcher?.onBackPressed() }, modifier = Modifier.tourTarget(TourTarget.BACK)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = color)
        }
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .semantics { heading() },
            )
        }
    }
}
