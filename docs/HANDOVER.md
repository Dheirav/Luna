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
| **Room migration 1→2** | On the Redmi, over a real v1 database — see below |
| History calendar | On device: month grid, estimated vs observed, day→log form, back to Today |
| Prediction ledger | First row recorded on device with correct values and a null variability |

### Built but NOT yet verified on device

- **Daily reminder** (`reminder/Reminders.kt`) — self-rescheduling WorkManager job at 21:00, skips
  if today is already logged, deep-links into the log form. Needs a real 24h cycle to confirm.
- **Reminder-health detector** — `Settings.reminderLooksBroken()`. Surfaces a warning card when the
  reminder was due and didn't run. Exists because vendor ROMs kill background work silently.
- **Encrypted export/restore UI** — SAF file picker + passphrase dialog. The codec is tested; the
  Android file plumbing is not.
- Boot receiver, notification permission prompt.
- Nothing outstanding from the 2026-08-11 batch. The backfill banner's *That's right* /
  *Remove* buttons were confirmed working by the user on the Redmi.

### Prediction ledger (Phase 3, started 2026-08-11)

`core/Prediction.kt` + `data/PredictionLedger.kt`. **Room is now at version 2** — `predictions` is
the first table that is not derivable from the daily logs, because a prediction is a function of
the data *as it stood that day* and cannot be reconstructed once the logs change.

Built before any UI on purpose: the record can only ever be written going forward, so every day
without it is scoring data lost permanently. `TodayUiState.accuracy` is populated and deliberately
rendered nowhere yet.

- Written from **two** places: `TodayViewModel.refresh` and `ReminderWorker` — the worker covers
  days the app is never opened. Its call is wrapped in `runCatching`; bookkeeping must never break
  the reminder chain.
- **`accuracy()` returns null until 3 scored cycles exist.** Rule 3 says not to display a
  confidence figure without a track record — not to display a low one. Expect null for months.
- Scoring excludes the open cycle and any period marked `ASSUMED`; grading against backfill would
  measure two estimates agreeing.
- **Migration 1→2 is hand-written and there is no `fallbackToDestructiveMigration` anywhere.** A
  missing migration must crash in development rather than wipe a multi-year history. Verify the SQL
  against `app/schemas/…/2.json` after any entity change — Room throws on first open if it drifts.

  **Verified on the Redmi, 2026-08-11**, against a real v1 database rather than a fresh install —
  a fresh install creates v2 directly and never exercises the migration at all. The technique, if
  another migration ever needs testing: `git worktree add <dir> <pre-change-commit>`, write a
  `local.properties` into it (it is gitignored, so the worktree has none), build that APK, install
  and launch it to create and seed the old schema, then `install -r` the new build over the top.
  Result: `user_version` 1→2, `predictions` created, 65 daily_logs and 55 `ASSUMED` flags intact,
  `integrity_check` ok, no entry in the crash buffer.
- Predictions ride along in the encrypted backup (`BackupSnapshot.predictions`, defaulted so older
  backups still restore). Restore replaces the ledger wholesale, including with nothing.

### Uncertainty window and receipts (Phase 3, 2026-08-11)

`core/Forecast.kt`, 13 tests. Fixes a contradiction that is **still on screen**: Today prints
"next period expected 25 Aug" — one exact day — directly above "cycle variability: not enough
observed cycles". Both cannot be true.

- `Forecast.periodWindow` returns a range, tagged `MEASURED` or `ASSUMED`. With the current seed
  it is ASSUMED: 21–29 Aug, ±4 days. **The UI must never present an ASSUMED window as measured** —
  it is a stated default spread (`ForecastConfig.assumedVariabilityDays`), not this user's data.
- Width comes from observed variability × `sqrt(1 + 1/n)`, the small-sample prediction-interval
  correction. A Student-t factor was considered and deliberately left out: at n=3 a 90% t-interval
  spans about three weeks, which is arithmetically right and useless. The band is documented as
  "most likely", never as a guarantee. Roughly 68% under a normal approximation, and real cycles
  are right-skewed, so true coverage sits a little below that.
- `minHalfWidthDays = 1` means a perfectly regular history still never names a single date.
- `Forecast.basis` is the receipt. The number that matters is the **observed/assumed split**: the
  seed has 12 completed cycles behind its 28-day estimate and *one* was observed. "From 12 cycles"
  would be true and badly misleading.
- `basis()` mirrors the branch structure of `CycleStats.expectedCycleLength`. **If that function
  gains a branch, this must too** — no test catches that drift.

Both are wired into `TodayUiState` (`window`, `basis`) and **rendered nowhere yet**, by the same
rule as `accuracy`: the display is the last piece, so there is no temptation to fill space with a
number that has not been earned.

### Visual design (2026-08-11)

Direction came from five references supplied by the user. The consistent note across them was
**neat over busy** — praised in two, and the single criticism of a third. Everything below follows
from that plus rule 4: charm must not cost adherence.

- **`ui/theme/Theme.kt` replaces Material You.** Dynamic colour derived everything from the
  wallpaper, so the app looked like whatever sat behind the home screen and could not hold the
  pink-and-cream identity the references asked for. **The cost is real: the app no longer follows
  system theming.** Light and dark schemes both exist; dark is warm plum, not black, because the
  21:00 reminder means much of all logging happens in the dark.
- **`CycleColors` carries domain meaning** — `bleeding`, `estimated`, `predicted`, `logged`.
  Bleeding was previously drawn in `colorScheme.error`; on a pink palette that was ambiguous as
  well as wrong. **Do not route cycle state through Material's error role again.**
- **`ui/theme/Decor.kt`** draws sparkles and dots with `Canvas` rather than shipping bitmaps. Two
  rules: nothing decorative on the log screen, and decoration never carries meaning.
- **Settings split out of Today** (`SettingsScreen.kt`). App lock and backup were occupying about
  a third of the daily screen for things touched twice a year.
- **The bare "Phase confidence 25%" is gone.** It was never measured — too few observed cycles
  means a fixed 0.5 regularity — so it presented an assumption as a reading. Replaced by counts of
  the cycles behind the estimate, with the numeric detail inside "Why these numbers?".
- **Launcher icon** is an adaptive vector (`drawable/ic_launcher_foreground.xml`), a few hundred
  bytes rather than five PNG densities. Deliberately a bloom, **not** a droplet or calendar: the
  home screen is visible to other people, the same reasoning as `FLAG_SECURE` on recents.
- System typeface throughout. A rounded face would suit better but needs a bundled TTF (50–100 KB);
  no `INTERNET` permission means downloadable fonts are not an option.

**None of this has been seen on a device** — the Redmi's port cycled before it could be installed.
Check first: the calendar at 440dpi, the hero card with and without bleeding, and dark mode.

### Settings, and a precedence fix (2026-08-11)

Prompted by the user asking whether the 28-day figure could be changed. It could not — and worse,
setting it would have had no effect.

**This amends CYCLE_RULES §3.** The spec says assumed cycles seed the length estimate, which is
right as far as it goes, but it never considered them competing with a figure the *user* supplies.
The old code asked only "are there three plausible cycles?", and the seed provides six synthetic
28-day ones — so a user stating "mine are 31" was outvoted by cycles the app invented, which it
then reported back as though it had measured them.

New precedence in `CycleStats.expectedCycleLength`, most to least authoritative:

1. median of **observed** cycles — a measurement of this body
2. what the user states — their own knowledge
3. median **including assumed** cycles — the app's own extrapolation
4. `CycleConfig.defaultCycleLength` — a population figure describing nobody

A user who states nothing gets the previous behaviour exactly, which is why all 34 golden fixtures
still pass unchanged. `LengthSource` gained `MEDIAN_OF_OBSERVED` / `MEDIAN_WITH_ESTIMATES` to
match — **`Forecast.basis` mirrors this branch structure and must be updated alongside it.**

`SettingsScreen` now exposes: cycle length, period length, prediction-window width, reminder
on/off and reminder time. Three of those existed in `Settings.kt` with no UI at all —
`typicalCycleLength` had been read by the engine on every refresh and been null since it was
written. Period length was absent entirely despite feeding `ovulationDay`.

Window width is a **coverage preference, not a data override**: it picks how many standard
deviations to span, so a narrow setting cannot make an erratic history look regular, and
`minHalfWidthDays` still floors it.

### Home-screen widget (Phase 5, 2026-08-11)

`widget/CycleWidget.kt`. Shows cycle day, phase and the predicted window; the whole card is one
tap target that opens the **log form** directly. Verified rendering on the Redmi's home screen.

It exists because the daily reminder has never been proven to survive either phone's ROM, and a
widget needs **no background execution to stay on screen** — it works precisely where the reminder
fails. Adherence is the binding constraint (rule 4), so that matters more than it sounds.

- **`RemoteViews` only inflates `@RemoteView`-annotated view classes.** `Space` is not one, and
  neither `android:theme` nor custom views are permitted on the root. Using them produced a flat
  **"Can't load widget"** from the launcher with nothing in logcat identifying the cause. Only
  `LinearLayout` and `TextView` are used; check the annotation before adding anything.
- Do not weight a child to pin content to the bottom — on a resized widget it strands the text
  above a large dead area. The root centres its children vertically instead.
- The phase tint is applied with `setColorStateList(..., "setBackgroundTintList", ...)` (API 31).
  A background *colour* would replace the shape drawable and lose the rounded corners.
- `@color/phase_*` in `values/` and `values-night/` **duplicates `CycleColors.phase` in
  Theme.kt** — RemoteViews cannot read Compose colours. Keep the two in step by hand.
- `updatePeriodMillis` is a 6-hour **backstop only**; the system clamps it and vendor ROMs
  throttle it. Real updates are pushed from three places: after a log edit, from the reminder
  worker, and on `ACTION_DATE_CHANGED` for midnight rollover.
- **Discreet mode** (`Settings.widgetShowsDetails`) blanks the cycle details while keeping the
  one-tap shortcut. A home screen is seen by other people, and a widget reading
  "Day 15 · Ovulation" undoes the reason the launcher icon is a bloom rather than a droplet.

### Not started

- Phases 4 and 6, and the rest of Phase 5 (fertility window, health flags) — both of which
  depend on Phase 4's luteal-length work first.

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
./gradlew :app:assembleDebug -PminifyDebug   # 7.6 MB instead of 25 MB — see below
./gradlew :app:assembleRelease
```

**Use `-PminifyDebug` for anything going to a phone.** The plain debug APK is ~25 MB and takes
one to three minutes over wireless debugging, which is longer than the Redmi's adb port usually
survives — installs were dying mid-transfer more often than they completed. R8 brings it to
7.6 MB. Screenshots still work: `FLAG_SECURE` keys off `ApplicationInfo.FLAG_DEBUGGABLE`, which
this build still sets, so it is the *release* build that blocks `screencap`, not the minified one.

Two traps in that flag, both already paid for:

- A bare `-PminifyDebug` sets the property to an **empty string**, which
  `providers.gradleProperty(...).isPresent` reports as *absent*. Use `project.hasProperty`.
- After enabling it, `ls` the APK **by timestamp**. An up-to-date build does not rewrite the file,
  so a stale 25 MB APK sat there looking like the flag had done nothing.

---

## Device — read this before debugging anything

There are **two** test phones. Neither is the real target, which remains a **Galaxy A35**.

### Redmi Note 12 Pro (2209116AG) — preferred

Android 13 / API 33, arm64-v8a, **7.7 GB RAM**, 1080x2400 @ 440dpi, HyperOS (V816.0.33.0).
Better than the Y19 in every way that matters here, and its 7.7 GB reopens Phase 6's Gemma 3 1B
as a question on *this* phone.

- **Pairing is already done** for the WSL box and persists. Only `adb connect <ip>:<port>` is
  needed — but the **port changes constantly**, several times an hour, even with *Stay awake* on
  and the phone on AC. Ask the user for the current one; do not burn time guessing.
- Debug APK is ~25 MB and installs over wifi in 1–3 minutes. `adb install` returning an **empty
  error message** means it was interrupted, not rejected — retry rather than debugging MIUI.
- **`adb shell input` is blocked intermittently**: `SecurityException: Injecting input events
  requires ... INJECT_EVENTS`. It worked earlier in the same session, so it is state-dependent
  rather than absent — most likely the screen lock. `screencap`, `uiautomator dump` and `am start`
  keep working when it fails, so drive the app by hand and use those to read state.
- The connection often dies **within a minute** of a launch or install. Batch every command that
  needs the device into one invocation; do not plan on a second round trip.
- **For visual review, ask the user to screenshot the phone and send the image.** It is one action
  for them and removes this entire flaky channel from the loop. Reserve `adb` for installing,
  reading the database, and crash checks — the things they cannot do by hand.
- If `adb install` ever fails with `INSTALL_FAILED_USER_RESTRICTED`, that is Xiaomi's *Install via
  USB* toggle, which wants a signed-in Mi account. It has not been needed so far.

### vivo V2432 (Y19 5G)

Android 15 / API 35, arm64-v8a, MediaTek MT6835, **3.7 GB RAM (~614 MB available)**,
Funtouch OS 6.0, no AICore. Its 4 GB rules out Phase 6 on this handset.

**Developer options have been revoked twice by the ROM**, which is why testing moved to the Redmi.

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
# Is the ledger actually recording? This should gain a row a day.
python3 -c "import sqlite3;print(sqlite3.connect('cycle-tracker.db').execute('select * from predictions').fetchall())"

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
3. **Install once and confirm the migration runs.** The v1→v2 SQL was checked against Room's
   exported schema and replayed through real SQLite (columns, types, nullability, primary keys all
   match; existing rows survive), but it has **never opened on the phone with the real database**.
   That is the one failure here that would be loud and destructive, so it should not sit untested.
   Then check `select * from predictions` gains a row a day.
4. Then the rest of Phase 3 — receipts, explain-this-prediction, uncertainty cone — and the UI
   pass that was deferred (launcher icon; the app still uses the stock Android robot).

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

  **The user can now answer this themselves**, two ways: Settings has a "usual period length"
  stepper, and the history calendar shows every estimated day with *That's right* or *Remove*. Note the
  promotion rule in `LogRepository.sourceFor` — editing an assumed day does **not** silently make
  it observed, because that would let a uniform synthetic backfill leak into
  `cycleLengthVariability` and fake high confidence (§3.2). Only changing the bleeding flag or
  tapping *That's right* promotes it.
- Whether the relationship-advice module stays or goes (it is currently cut).

  (An earlier item here claimed `menstrual_tracker.db` was tracked by git and needed
  `git rm --cached`. It was never tracked — the file predates any repo covering this directory.
  Nothing to do; the `.gitignore` rule is enough.)
