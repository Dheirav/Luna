# Handover — Cycle Tracker

Written 2026-08-11. Everything needed to pick this up in a fresh session.

**Read first:** [`CYCLE_RULES.md`](CYCLE_RULES.md) is the authoritative spec. The build plan lives at
(drafted outside the repository) (7 phases, stack decisions,
size budget). This document covers state, environment, and the traps.

---

## What this is

A personal, offline menstrual cycle tracker. One user, one phone. Kotlin/Compose, built from a
corrected design — **the Python tree in `src/` is reference material only and is not being
repaired.** Three defects in it are documented in `CYCLE_RULES.md` and deliberately not ported.

Governing rules (full list in the plan artifact):

1. The engine owns every number. A language model may parse input and phrase output, never produce values.
2. **Absent is not zero.** "Not logged" and "logged as low" stay distinguishable everywhere.
3. Confidence is earned — measure prediction error before displaying a confidence figure.
4. **Adherence is the binding constraint.** Analytics on an empty database is worth nothing.
5. On-device only. There is no `INTERNET` permission and there never will be.

---

## Current state

### Done and verified

| | Verified how |
|---|---|
| Phase 0 — spec + golden fixture | 34 cases, `spec/cycle_fixtures.json` |
| Cycle/period/phase engine (`:core`) | `./gradlew :core:test` — all pass |
| Encrypted backup codec | 8 tests incl. tamper detection, wrong-passphrase, no-plaintext-leak |
| Android project builds | debug + release; release APK **2.15 MB** |
| Today screen | On device: day 15/28, Ovulation, 25% confidence — all hand-checked correct |
| Log screen | Renders; write round-trip confirmed in DB (`energy=1, pain=2, travel`) |
| Delete path | Emptying a day removes the row entirely |
| Backfill seed | 13 periods imported, source flags correct (11 assumed / 2 observed) |
| App lock | On device: cold-start prompt, unlock, 15s grace holds, 70s re-locks, toggle both ways |

### Built but NOT yet verified on device

- **Daily reminder** (`reminder/Reminders.kt`) — self-rescheduling WorkManager job at 21:00, skips
  if today is already logged, deep-links into the log form. Needs a real 24h cycle to confirm.
- **Reminder-health detector** — `Settings.reminderLooksBroken()`. Surfaces a warning card when the
  reminder was due and didn't run. Exists because vendor ROMs kill background work silently.
- **Encrypted export/restore UI** — SAF file picker + passphrase dialog. The codec is tested; the
  Android file plumbing is not.
- Boot receiver, notification permission prompt.
### Not started

- Phases 3–6.

### Decided against — SQLCipher (2026-08-11)

Phase 2 listed SQLCipher as its last item. **It was evaluated and dropped**; `CycleTrackerApp`
opens the database plaintext on purpose. Do not "finish" this without re-reading the reasoning:

- Android 15 uses file-based encryption. `/data/data/<pkg>/databases/` sits in credential-encrypted
  storage and is unreadable until first unlock after boot, and UID isolation keeps other apps out.
  `allowBackup="false"` is already set. **The lost-phone case is covered by the OS.**
- What SQLCipher would add is protection against root, an exploit, or forensic extraction from an
  already-unlocked device — narrow for a single-user personal phone.
- It does nothing about the threat that does apply: someone with your unlocked phone opening the
  app. The app would decrypt for them.
- Costs were concrete: ~2.5 MB of native library, a migration step over live data, and — the
  deciding factor — it would have killed the pull-the-database debugging route, which is one of
  the few working inspection channels on the Y19.
  (Correction from device inspection on 2026-08-11: the migration risk was overstated when this
  was written. The database holds exactly 65 bleeding days — 13 periods × 5 — with **zero**
  symptom rows and **zero** tags, i.e. the backfill seed and nothing else. It is fully
  reproducible from `spec/seed_periods.json`. The `energy=1, pain=2, travel` round-trip recorded
  above as verified is no longer present, presumably removed when the delete path was tested.
  The other reasons stand on their own.)
- Deferring is nearly free. All database access goes through one `by lazy` in `CycleTrackerApp`,
  so adopting it later is the same single-file change, and `sqlcipher_export` does not care how
  many rows have accumulated.

**Revisit if** the phone is rooted, the device gets shared, or this moves to hardware you do not
fully control. The migration approach if it ever happens: `sqlcipher_export()` into a new keyed
file, verify row counts and `integrity_check` before swapping, key wrapped by the AndroidKeyStore
with no user-auth requirement (the 21:00 reminder worker reads the DB with nobody present).

**Instead, the app lock landed** — it addresses the realistic threat, costs ~10 KB, and carries no
migration risk. `FLAG_SECURE` also went on to keep cycle data out of the app-switcher thumbnail.

---

## Repo layout

```
docs/CYCLE_RULES.md        authoritative spec — read before touching the engine
docs/HANDOVER.md           this file
spec/cycle_fixtures.json   golden fixture (34 cases), hand-authored from the spec
spec/seed_periods.json     backfill: 13 period starts, 2 observed + 11 extrapolated
src/                       legacy Python — reference only, do not repair
android/
  core/                    plain Kotlin/JVM engine + tests (no Android deps)
  app/                     Room + Compose
```

`:core` is a plain JVM module on purpose — its tests run in seconds with no emulator and no SDK.

**Spec files are vendored** into `core/src/test/resources/` and `app/src/main/assets/` because the
`android/` directory can be transferred to Windows alone. After editing anything in `spec/`, run
`./gradlew syncSpec`. `SpecDriftTest` fails on stale copies when `../spec` is reachable.

---

## Environment (WSL)

Already installed, no Android Studio:

| | Path |
|---|---|
| SDK | `~/Android/Sdk` (platform 35, build-tools 35.0.0, platform-tools) |
| JDK | `/usr/lib/jvm/java-21-openjdk-amd64` |
| Gradle | wrapper 8.11.1 |

**The system default JDK is 24, which Gradle 8.11 does not support.** It is pinned in
`~/.gradle/gradle.properties` (user-level, deliberately outside the repo so Windows builds still
work with Studio's bundled JDK). If a build suddenly fails on Java version, check that file exists.

```bash
cd android
./gradlew :core:test            # engine + backup tests, ~2s warm
./gradlew :app:installDebug
./gradlew :app:assembleRelease
```

---

## Device — read this before debugging anything

Test device is a **vivo V2432 (Y19 5G)**: Android 15 / API 35, arm64-v8a, MediaTek MT6835,
**3.7 GB RAM (~614 MB available)**, Funtouch OS 6.0, no AICore.

The real target is a **Galaxy A35** — the Y19 is only for basic testing, so **do not retune the
plan around it.** One thing does carry over: 4 GB RAM rules out Phase 6's Gemma 3 1B on *this*
phone (~1.2–1.5 GB working set). The A35's 6–8 GB reopens it.

### Traps that cost hours — do not repeat these

1. **`adb logcat` shows nothing from the app.** Funtouch filters app logs at every priority. A
   `Log.w` on the first line of `onCreate` produced zero output while the app was demonstrably
   running. **Log-based debugging is unavailable on this device.** Use the alternatives below.

   **However — the crash buffer does work.** `adb logcat -b crash` returned a complete
   `AndroidRuntime` stack trace for a startup crash on 2026-08-11 and is how the missing
   `USE_BIOMETRIC` permission was found in seconds. Clear it first with `adb logcat -b crash -c`
   so you know what you are reading is current. This is the one log channel worth trying.
2. **Wireless debugging turns itself off** when the screen sleeps, and **the connect port changes
   every time it restarts.** Pairing persists; the port does not.
3. **The pairing port and the connect port are different.** The pairing dialog's port is throwaway
   and expires fast — connecting to it yields an `offline` transport. The port you want is on the
   main Wireless debugging screen under "IP address & Port".
4. **There is no `sqlite3` binary on the device.** Pull the database instead (below).
5. USB does not reach WSL — no `/dev/bus/usb`, no usbip client. Would need `usbipd-win` on Windows.

### What does work

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
adb connect <PHONE_IP>:<PORT>          # ask the user for the current port

# Inspect UI state — this replaces logcat. Gives text, bounds and selected/checked.
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml

# Tap accurately: parse bounds from the dump, never guess coordinates.
adb shell input tap <x> <y>

# Read the database (no sqlite3 on device — pull all three files, WAL matters)
PKG=com.dheirav.cycletracker.debug
for f in cycle-tracker.db cycle-tracker.db-wal cycle-tracker.db-shm; do
  adb exec-out run-as $PKG cat /data/data/$PKG/databases/$f > $f
done
python3 -c "import sqlite3;print(sqlite3.connect('cycle-tracker.db').execute('select * from daily_logs').fetchall())"

# The screen locks constantly; wake and dismiss before screenshots
adb shell input keyevent KEYCODE_WAKEUP
adb exec-out screencap -p > shot.png
```

`FLAG_SECURE` is set on **release builds only**, precisely so `screencap` and `uiautomator dump`
keep working on the debug install. If a screenshot ever comes back black, check which build is
on the phone before hunting for anything else.

The app lock also stands between you and the UI on a cold start. `uiautomator dump` will show the
system prompt ("Unlock Cycle Tracker" / "Touch the fingerprint sensor" / "Use PIN") rather than the
app — that is the gate working, not a hang. Disable it for a testing session with:

```bash
PKG=com.dheirav.cycletracker.debug
adb shell am force-stop $PKG   # then toggle "Require unlock" off in the app
```

### Biometric classes on this device

`adb shell dumpsys biometric` reports:

| Sensor | Modality | Strength | Class |
|---|---|---|---|
| ID(0) | 2 = fingerprint | 15 | **STRONG** (Class 3) |
| ID(1) | 8 = face | 4095 | **CONVENIENCE** (Class 1) |

Vivo rates face unlock as *Convenience*, which is below `BIOMETRIC_WEAK` and therefore **not
accepted by `BiometricPrompt` at any tier**. The gate is fingerprint-or-PIN on this phone, and
raising `AppLock.ALLOWED` to `BIOMETRIC_STRONG` would change nothing. Do not assume the A35
behaves the same — Samsung's face implementation is classified differently.

**Lesson learned:** a low-resolution screenshot led to a phantom bug hunt — a section heading was
misread as a chip label. `uiautomator dump` gives ground truth. Trust it over screenshots.

### Battery / autostart

The user has **allowed unrestricted battery**, but **could not enable Autostart** (Funtouch hides
or gates it). This is exactly why `reminderLooksBroken()` exists — whether the reminder survives is
an open empirical question. **Check it after a few days of real use.** If it is being killed, the
home-screen widget (Phase 5) becomes the more important delivery mechanism, since it needs no
background execution.

---

## Suggested next steps

1. **Verify the reminder fires** over a couple of real days. This is a genuine open question on
   this ROM, not a formality.
2. **Test backup export/restore on device** — the codec is tested, the SAF plumbing is not. Watch
   the app lock during this: the file picker backgrounds the app, and the 60-second grace in
   `AppLock` is what stops a re-auth prompt landing mid-export. The grace itself is verified;
   what is not is whether a slow browse through the picker exceeds it.
3. Then Phase 3 (prediction scoring, receipts, explain-this-prediction, uncertainty cone).

## Version control

A dedicated repo was initialised at the project root on 2026-08-11 (`git init`, branch `main`).
Before that, **nothing here had ever been committed** — the whole tree sat untracked inside a
catch-all repo at `~/Code` that lumps unrelated projects together behind a single `LOL` commit.
That repo is not this project's history and should not be used as one.

Ignored on purpose: `android/build/`, `android/.gradle/`, `android/.kotlin/`, `local.properties`
(it hardcodes the WSL SDK path and would break a Windows build), `*.jks`/`*.keystore`, and
**every `*.db`, `*.db-wal` and `*.db-shm`** — the last covers both the legacy Python store and any
database pulled off the device for debugging, which is real health data.

## Open questions for the user

- Period lengths were assumed to be **5 days** for every backfilled period; only the two most recent
  period *start* dates came from them. Correcting spans matters because
  `spanDays` feeds `ovulationDay` via the `periodLength + 4` floor.
- Whether the relationship-advice module stays or goes (it is currently cut).

  (An earlier item here claimed `menstrual_tracker.db` was tracked by git and needed
  `git rm --cached`. It was never tracked — the file predates any repo covering this directory.
  Nothing to do; the `.gitignore` rule is enough.)
