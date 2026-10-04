package com.dheirav.cycletracker

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.dheirav.cycletracker.data.CycleRepository
import com.dheirav.cycletracker.data.TrackerDatabase
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.reminder.EXTRA_OPEN_LOG
import com.dheirav.cycletracker.reminder.ReminderScheduler
import com.dheirav.cycletracker.ui.AppLockGate
import com.dheirav.cycletracker.ui.GuideViewModel
import com.dheirav.cycletracker.ui.HistoryScreen
import com.dheirav.cycletracker.ui.HistoryViewModel
import com.dheirav.cycletracker.ui.LogScreen
import com.dheirav.cycletracker.ui.OnboardingScreen
import com.dheirav.cycletracker.ui.SummaryScreen
import com.dheirav.cycletracker.ui.PhaseGuideScreen
import com.dheirav.cycletracker.ui.LogViewModel
import com.dheirav.cycletracker.ui.SettingsScreen
import com.dheirav.cycletracker.ui.TodayScreen
import com.dheirav.cycletracker.ui.TodayViewModel
import com.dheirav.cycletracker.ui.theme.CycleTrackerTheme
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate

class CycleTrackerApp : Application() {
    /**
     * Plaintext, deliberately. SQLCipher was evaluated for Phase 2 and dropped — Android's
     * file-based encryption and UID isolation already cover the file at rest, and ~2.5 MB of
     * native library plus a migration over real data bought protection against a threat model
     * that does not apply here. [com.dheirav.cycletracker.ui.AppLock] addresses the one that does.
     */
    val database: TrackerDatabase by lazy {
        Room.databaseBuilder(this, TrackerDatabase::class.java, "cycle-tracker.db")
            // No destructive fallback anywhere in this chain, deliberately. A missing migration
            // should crash loudly in development, not wipe years of health data on a user's phone.
            .addMigrations(TrackerDatabase.MIGRATION_1_2, TrackerDatabase.MIGRATION_2_3)
            .build()
    }

    /** The live cycle state every screen renders from. One instance, so one source of truth. */
    val cycles: CycleRepository by lazy { CycleRepository(database.logDao(), Settings(this)) }

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.schedule(this)
    }
}

/** Five screens, one back destination, no deep links beyond the reminder's. Still less code than
 *  wiring a navigation library, and every transition is visible in one `when`. */
private enum class Screen { TODAY, LOG, HISTORY, SETTINGS, PHASE_GUIDE, ONBOARDING, SUMMARY }

class MainActivity : ComponentActivity() {

    private var openLogOnLaunch by mutableStateOf(false)

    /**
     * Applies [Settings.allowScreenshots] to the window.
     *
     * `FLAG_SECURE` keeps the cycle day and phase out of the app-switcher thumbnail, which would
     * otherwise show them to anyone flicking through recents — the same over-the-shoulder threat the
     * lock, the bloom launcher icon and the widget's discreet mode all address. It also blocks
     * screenshots and screen recording, which is the cost, and why it is now a setting rather than a
     * rule the app imposes.
     *
     * Callable at any time, and called from `onResume` as well as `onCreate` so the switch takes
     * effect without a restart — the window flag is not something Compose can own.
     *
     * Debug builds never set it. `FLAG_SECURE` also blocks `adb screencap`, and on the test phone
     * `adb shell input` is permanently unavailable and logcat is filtered, which leaves screenshots as
     * one of only two ways to see what the app is doing (HANDOVER, "Traps that cost hours").
     */
    private fun applyScreenshotPolicy() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val secure = !debuggable && !Settings(this).allowScreenshots
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenshotPolicy()
        // Coming back is when the date may have rolled over, or a notification action may have
        // logged a day; the screens follow the snapshot, so nudging it is all a resume needs.
        (application as CycleTrackerApp).cycles.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openLogOnLaunch = intent?.getBooleanExtra(EXTRA_OPEN_LOG, false) == true
        enableEdgeToEdge()

        applyScreenshotPolicy()

        setContent {
            CycleTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppLockGate { Content() }
                }
            }
        }
    }

    @Composable
    private fun Content() {
        // Hosted here rather than on the log form, because the form closes on save and the
        // confirmation has to outlive it.
        val snackbar = remember { SnackbarHostState() }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            val todayVm: TodayViewModel = viewModel()
            val logVm: LogViewModel = viewModel()

            // Latest only: a second save replaces the first snackbar rather than queueing behind it.
            LaunchedEffect(logVm) {
                logVm.undoable.collectLatest { undoable ->
                    val result = snackbar.showSnackbar(
                        message = undoable.message,
                        actionLabel = "Undo",
                        // Long, not Short. Short is about four seconds, which on the Redmi was gone
                        // before Undo could be reached; an undo that expires while you read it is
                        // not one you can use.
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) logVm.undo(undoable)
                }
            }
            val historyVm: HistoryViewModel = viewModel()
            val guideVm: GuideViewModel = viewModel()
            val settings = remember { Settings(this@MainActivity) }
            // The walkthrough comes first until it has been finished or skipped once.
            var screen by rememberSaveable {
                mutableStateOf(if (settings.onboardingDone) Screen.TODAY else Screen.ONBOARDING)
            }
            // True when the walkthrough was opened from Settings to reread, not as a first run.
            var onboardingReplay by rememberSaveable { mutableStateOf(false) }

            // Where the log form was opened from, so leaving it goes back there.
            //
            // Correcting backfill means opening a day from the calendar, editing, and returning
            // for the next one. Landing on Today after every save — then navigating to History and
            // paging back several months — made that loop punishing, on the one screen built for
            // bulk correction.
            var logOrigin by rememberSaveable { mutableStateOf(Screen.TODAY) }

            // Asked from the walkthrough's reminder page, behind a button that says what it is for.
            // It used to be launched on the first frame of the app, before anyone knew why, and again
            // whenever the screen was rebuilt. Without it the reminder posts nothing, so a denial is
            // also surfaced in Settings, with a route to fix it.
            val permission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            val requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val todayUi by todayVm.ui.collectAsStateWithLifecycle()

            // Tapping the notification lands in the form, not the front door.
            LaunchedEffect(openLogOnLaunch) {
                if (openLogOnLaunch) {
                    // A form with unsaved edits is left as it is rather than replaced by today's.
                    // The reminder and the widget used to reload it silently, which skipped the
                    // unsaved-changes prompt every other way out of the form honours.
                    val editing = screen == Screen.LOG && logVm.ui.value.dirty
                    if (!editing && logVm.open(LocalDate.now())) {
                        logOrigin = Screen.TODAY
                        screen = Screen.LOG
                    }
                    openLogOnLaunch = false
                }
            }

            BackHandler(enabled = screen != Screen.TODAY) {
                screen = when (screen) {
                    Screen.LOG -> logOrigin
                    // Opened from Settings, so it goes back there.
                    Screen.SUMMARY -> Screen.SETTINGS
                    else -> Screen.TODAY
                }
            }

            Box(Modifier.padding(padding)) {
                when (screen) {
                    Screen.TODAY -> TodayScreen(
                        viewModel = todayVm,
                        onLog = {
                            if (logVm.open(LocalDate.now())) {
                                logOrigin = Screen.TODAY
                                screen = Screen.LOG
                            }
                        },
                        onHistory = {
                            // Opening the calendar afresh always lands on this month. Entering
                            // from Today is a new visit; returning from an edit is not.
                            historyVm.showCurrentMonth()
                            screen = Screen.HISTORY
                        },
                        onSettings = { screen = Screen.SETTINGS },
                        onPhaseGuide = { screen = Screen.PHASE_GUIDE },
                    )

                    Screen.LOG -> LogScreen(logVm, onDone = { screen = logOrigin })

                    Screen.HISTORY -> HistoryScreen(historyVm) { date ->
                        if (logVm.open(date)) {
                            logOrigin = Screen.HISTORY
                            screen = Screen.LOG
                        }
                    }

                    // No refresh hook: a settings change reaches every screen through the snapshot.
                    Screen.SETTINGS -> SettingsScreen(
                        onHowItWorks = {
                            onboardingReplay = true
                            screen = Screen.ONBOARDING
                        },
                        onSummary = { screen = Screen.SUMMARY },
                    )

                    Screen.SUMMARY -> SummaryScreen()

                    Screen.ONBOARDING -> OnboardingScreen(
                        replay = onboardingReplay,
                        hasPeriods = todayUi.state?.hasData == true,
                        onRequestNotifications = requestNotifications,
                        onLogPeriod = logVm::logPeriod,
                        onFinish = {
                            if (onboardingReplay) {
                                onboardingReplay = false
                                screen = Screen.SETTINGS
                            } else {
                                settings.onboardingDone = true
                                screen = Screen.TODAY
                            }
                        },
                    )

                    // Null phase means "whatever today is" — the guide resolves it from the same
                    // engine, so it cannot disagree with the hero the user just tapped.
                    Screen.PHASE_GUIDE -> PhaseGuideScreen(guideVm, initialPhase = null)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLogOnLaunch = intent.getBooleanExtra(EXTRA_OPEN_LOG, false)
    }
}

// The theme moved to ui/theme/Theme.kt, and dynamic colour went with it. Material You derives
// everything from the wallpaper, which meant the app looked like whatever happened to be behind
// the home screen and could not hold a palette of its own.
