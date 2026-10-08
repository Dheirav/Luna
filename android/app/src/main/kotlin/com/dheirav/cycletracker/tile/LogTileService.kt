package com.dheirav.cycletracker.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.MainActivity
import com.dheirav.cycletracker.reminder.EXTRA_OPEN_LOG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * "Log today" in the Quick Settings panel: one swipe down from anywhere, then the log form.
 *
 * Exists for the same reason as the widget. It needs no background work, so a phone that kills the
 * evening reminder cannot kill this, and it is quicker than finding the app.
 *
 * **It says only whether today is logged.** The panel is visible on the lock screen, to anyone
 * holding the phone, so the tile carries what the discreet widget carries and nothing about the
 * cycle: no day, no phase, no window. The icon is the bloom, for the reason the launcher icon is.
 * Opening it goes through the app lock like any other way in.
 */
class LogTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val dao = (application as CycleTrackerApp).database.logDao()
            val logged = withContext(Dispatchers.IO) { dao.logFor(LocalDate.now()) != null }
            val tile = qsTile ?: return@launch
            tile.label = "Log today"
            tile.subtitle = if (logged) "Logged" else "Not yet"
            // Active means done, so a logged day reads as finished at a glance, as the tick does on
            // the widget.
            tile.state = if (logged) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.stateDescription = if (logged) "Today is logged" else "Today is not logged yet"
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { open() } else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_LOG, true)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Asks the system to refresh the tile, after a write changes whether today is logged. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, LogTileService::class.java)) }
        }
    }
}
