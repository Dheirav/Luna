package com.dheirav.cycletracker.data

import com.dheirav.cycletracker.core.CycleSnapshot
import com.dheirav.cycletracker.core.Source
import com.dheirav.cycletracker.core.UserCycleSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The one place a stored row becomes a bleeding claim.
 *
 * Every surface used to filter `isBleeding` and compare `source` to "ASSUMED" for itself, seven
 * times over. Kept here so the rule cannot drift between copies.
 */
fun List<DailyLogEntity>.snapshot(settings: UserCycleSettings, today: LocalDate): CycleSnapshot =
    CycleSnapshot.build(
        bleedingDays = filter { it.isBleeding }.map { it.date },
        assumedDays = filter { it.isBleeding && it.source == Source.ASSUMED.name }.map { it.date }.toSet(),
        settings = settings,
        today = today,
        // Answered "no", not merely absent: only these close a period (CYCLE_RULES §5.1).
        noBleedingDays = filter { it.bleedingAnswered && !it.isBleeding }.map { it.date }.toSet(),
        loggedDays = map { it.date }.toSet(),
    )

/**
 * A one-off snapshot, for the callers that cannot follow a flow: the widgets, the reminder worker
 * and the doctor summary. Same inputs as the screens, which is the point.
 */
suspend fun LogDao.snapshot(settings: Settings, today: LocalDate = LocalDate.now()): CycleSnapshot =
    allLogsOnce().snapshot(settings.forEngine(), today)

/**
 * The live cycle state every screen renders from.
 *
 * Three things can change what the app should say, and each used to be wired to the screens by
 * hand, with gaps: the logs (Room), the user's settings (SharedPreferences, which Room never sees, so
 * History kept an old window after a settings change), and the date (nothing noticed midnight, so a
 * screen left open overnight showed yesterday's cycle day). Combining all three here means a new
 * snapshot follows any of them, and a screen cannot be out of step with the data by construction.
 */
class CycleRepository(private val dao: LogDao, private val settings: Settings) {

    private val resumes = MutableStateFlow(0)

    /**
     * Called from `onResume`. Deliberately not de-duplicated by date: a resume is also when the user
     * may have come back from system settings with the battery or notification state changed, and
     * rebuilding a snapshot costs a pass over a few hundred rows.
     */
    fun onResume() = resumes.update { it + 1 }

    /**
     * Ticks at each midnight while something is collecting. `delay` can run late while the phone
     * dozes, which is why [onResume] exists as well: the moment anyone looks is covered either way.
     */
    private val midnights: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            val now = LocalDateTime.now()
            val next = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, next).toMillis() + 1_000)
        }
    }

    private val today: Flow<LocalDate> = merge(midnights, resumes.map { }).map { LocalDate.now() }

    val snapshots: Flow<CycleSnapshot> =
        combine(dao.allLogs(), settings.engineSettings(), today) { logs, engineSettings, day ->
            logs.snapshot(engineSettings, day)
        }
}
