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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.dheirav.cycletracker.data.TrackerDatabase
import com.dheirav.cycletracker.reminder.EXTRA_OPEN_LOG
import com.dheirav.cycletracker.reminder.ReminderScheduler
import com.dheirav.cycletracker.ui.AppLockGate
import com.dheirav.cycletracker.ui.HistoryScreen
import com.dheirav.cycletracker.ui.HistoryViewModel
import com.dheirav.cycletracker.ui.LogScreen
import com.dheirav.cycletracker.ui.LogViewModel
import com.dheirav.cycletracker.ui.TodayScreen
import com.dheirav.cycletracker.ui.TodayViewModel
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
            .addMigrations(TrackerDatabase.MIGRATION_1_2)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.schedule(this)
    }
}

/** Three screens is still fewer than a navigation library is worth. Revisit if a fourth lands. */
private enum class Screen { TODAY, LOG, HISTORY }

class MainActivity : ComponentActivity() {

    private var openLogOnLaunch by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openLogOnLaunch = intent?.getBooleanExtra(EXTRA_OPEN_LOG, false) == true
        enableEdgeToEdge()

        // Keeps cycle data out of the app-switcher thumbnail, which would otherwise show the day
        // and phase to anyone thumbing through recents — the same threat the lock addresses.
        // Debug builds are exempt: FLAG_SECURE also blocks `adb screencap`, and with logcat
        // filtered on the test device that is one of the only two ways left to see what the app
        // is doing (HANDOVER, "Traps that cost hours").
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

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
        Scaffold { padding ->
            val todayVm: TodayViewModel = viewModel()
            val logVm: LogViewModel = viewModel()
            val historyVm: HistoryViewModel = viewModel()
            var screen by rememberSaveable { mutableStateOf(Screen.TODAY) }

            // Ask once, on first composition. Without it the reminder posts nothing
            // and fails silently — the worst possible failure for the one feature
            // adherence depends on.
            val permission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Tapping the notification lands in the form, not the front door.
            LaunchedEffect(openLogOnLaunch) {
                if (openLogOnLaunch) {
                    logVm.open(LocalDate.now())
                    screen = Screen.LOG
                    openLogOnLaunch = false
                }
            }

            // Back always returns to Today, including from the log form reached via History.
            // Stacking History under it would mean two presses to leave, for no gain.
            BackHandler(enabled = screen != Screen.TODAY) { screen = Screen.TODAY }

            Box(Modifier.padding(padding)) {
                when (screen) {
                    Screen.TODAY -> TodayScreen(
                        viewModel = todayVm,
                        onLog = {
                            logVm.open(LocalDate.now())
                            screen = Screen.LOG
                        },
                        onHistory = { screen = Screen.HISTORY },
                    )

                    Screen.LOG -> LogScreen(logVm) {
                        screen = Screen.TODAY
                        todayVm.reload()
                    }

                    Screen.HISTORY -> HistoryScreen(historyVm) { date ->
                        logVm.open(date)
                        screen = Screen.LOG
                    }
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

/** Material You dynamic colour, available from API 31 — one of the reasons minSdk is 31. */
@Composable
fun CycleTrackerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        content = content,
    )
}
