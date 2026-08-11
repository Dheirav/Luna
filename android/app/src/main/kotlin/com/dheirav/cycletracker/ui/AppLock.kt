package com.dheirav.cycletracker.ui

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dheirav.cycletracker.data.Settings

/**
 * A lock screen in front of the app's contents.
 *
 * SQLCipher was considered for Phase 2 and dropped (see HANDOVER). Android's file-based encryption
 * and UID isolation already protect the database file at rest, and database encryption does nothing
 * about the threat that actually applies to a personal tracker: someone picking up an unlocked
 * phone and opening the app. This does, for ~10 KB of APK and no migration risk.
 *
 * Two properties make this safe to leave on by default:
 *
 *  - **It cannot lock you out.** The prompt allows the device credential, so a failed or broken
 *    fingerprint sensor always falls back to the PIN. If no screen lock is set at all, the gate
 *    reports itself unavailable and never engages.
 *  - **It never touches background work.** The reminder worker reads the database directly and is
 *    unaffected by the lock state, so adherence does not depend on the user being present.
 */
object AppLock {

    /**
     * How long the app may sit in the background before it re-locks.
     *
     * Not zero: the backup flow leaves the app for the system file picker, and re-authenticating
     * on the way back from choosing a filename would be hostile. A minute covers app-switching
     * without meaningfully widening the window a bystander could use.
     */
    private const val GRACE_MILLIS = 60_000L

    /** WEAK rather than STRONG because this gates a screen, not a crypto key — and STRONG rules
     *  out face unlock on much of the market for no benefit we can use here. */
    const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private val lockedState = mutableStateOf(false)
    private var initialised = false
    private var leftForegroundAt = 0L

    val locked: Boolean get() = lockedState.value

    fun available(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    /** Locks on the first launch in this process. Survives rotation; a cold start re-locks because
     *  the whole object is re-created. */
    fun onStart(context: Context, enabled: Boolean) {
        if (!enabled || !available(context)) {
            lockedState.value = false
            initialised = true
            return
        }
        if (!initialised) {
            initialised = true
            lockedState.value = true
            return
        }
        val away = SystemClock.elapsedRealtime() - leftForegroundAt
        if (leftForegroundAt > 0L && away > GRACE_MILLIS) lockedState.value = true
    }

    /** Monotonic, so changing the system clock cannot extend the grace window. */
    fun onStop() {
        leftForegroundAt = SystemClock.elapsedRealtime()
    }

    fun unlock() {
        lockedState.value = false
    }

    fun disable() {
        lockedState.value = false
    }
}

/**
 * Wraps the app's content in the lock.
 *
 * The content stays composed underneath an opaque overlay rather than being replaced. Swapping it
 * out would dispose the backup screen's activity-result launchers mid-flight, so a re-lock while
 * the file picker was open would silently drop the export.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var error by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }

    val prompt = {
        if (AppLock.locked && !prompting) {
            prompting = true
            error = null
            authenticate(
                context = context,
                onSuccess = { prompting = false; AppLock.unlock() },
                onError = { prompting = false; error = it },
            )
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        AppLock.onStart(context, settings.appLockEnabled)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        AppLock.onStop()
        // The system tears the prompt down with the activity; without this the gate would come
        // back believing a prompt was still up and never ask again.
        prompting = false
    }

    // Prompting is tied to ON_RESUME, not to composition: BiometricPrompt is dismissed by the
    // system if the activity is not resumed, which would leave the overlay up with no way past it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { prompt() }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (AppLock.locked) LockOverlay(error = error, onRetry = prompt)
    }
}

@Composable
private fun LockOverlay(error: String?, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            // Explicitly swallow every touch. Material's Surface happens to do this already, but
            // this is the thing standing between a bystander and the data — it should not depend
            // on an implementation detail of a component that could change under us.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Locked", style = MaterialTheme.typography.headlineSmall)
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = onRetry) { Text("Unlock") }
        }
    }
}

/** Card for the settings area. Says plainly what the lock does and does not cover. */
@Composable
fun AppLockSection() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val available = remember { AppLock.available(context) }
    var enabled by remember { mutableStateOf(settings.appLockEnabled) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Require unlock", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = enabled && available,
                    enabled = available,
                    onCheckedChange = {
                        enabled = it
                        settings.appLockEnabled = it
                        if (!it) AppLock.disable()
                    },
                )
            }
            Text(
                if (available) {
                    "Fingerprint, face or device PIN before the app opens, and again after a " +
                        "minute in the background. The database itself is not separately " +
                        "encrypted — Android already encrypts app storage at rest."
                } else {
                    "Unavailable: this device has no screen lock set up. Add a PIN, pattern or " +
                        "fingerprint in system settings to use the lock."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The framework prompt, not `androidx.biometric`.
 *
 * The AndroidX wrapper exists to backport this to API 23–27 and costs ~1.4 MB in the release APK,
 * because it pulls in `androidx.fragment`, which R8 barely shrinks. `minSdk` is 31, so the wrapper
 * would be paying to support versions this app never runs on — and it would double the APK for it.
 */
private fun authenticate(context: Context, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val prompt = BiometricPrompt.Builder(context)
        .setTitle("Unlock Luna")
        // No negative button: setting one alongside DEVICE_CREDENTIAL throws.
        .setAllowedAuthenticators(AppLock.ALLOWED)
        .setConfirmationRequired(false)
        .build()

    prompt.authenticate(
        CancellationSignal(),
        context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                onSuccess()

            override fun onAuthenticationError(code: Int, message: CharSequence) =
                onError(message.toString())
        },
    )
}
