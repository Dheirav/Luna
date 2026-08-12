# Handover — Luna

Written 2026-08-11, current as of **2026-08-13**. Everything needed to pick this up in a fresh
session.

**Read first:** [`CYCLE_RULES.md`](CYCLE_RULES.md) is the authoritative spec. This document covers
state, environment, and the traps. The original build plan (7 phases, stack decisions, size budget)
was drafted outside the repository; the phase numbering below is what survives of it.

---

## What this is

A personal, offline menstrual cycle tracker. One user, one phone. Kotlin/Compose, built from a
corrected design.

**The Python prototype this replaced was deleted from the repository on 2026-08-12**, along with its
five status documents. It had been kept as reference and was doing more harm than good: its roadmap
used a different phase numbering that cost a wrong turn, and `PROJECT_STATUS.md` announced the project
"COMPLETE" while describing a tree nobody was maintaining. Three defects found in it are still
documented in `CYCLE_RULES.md`, because the reasoning behind three rules depends on them — the
file-and-line citations there now point at code that exists only in git history.

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
| Cycle/period/phase engine (`:core`) | `./gradlew :core:test` — **122 tests, all pass** (plus 5 in `:app`, and 3 instrumented) |
| Encrypted backup codec | 11 tests incl. tamper detection, wrong-passphrase, no-plaintext-leak |
| Forecast window / prediction scorer | 16 + 13 tests |
| Health flags, symptom patterns, clinical summary | 14 + 12 + 11 tests |
| Android project builds | debug + release; release APK **2.41 MB**, signed — see "Sharing, signing, and screenshots" |
| Today screen | On device: day 15/28, Ovulation — hand-checked correct. (It also showed a "25% confidence" figure at the time; that display has since been removed as unearned.) |
| Log screen | Renders; write round-trip confirmed in DB (`energy=1, pain=2, travel`) |
| Delete path | Emptying a day removes the row entirely |
| App lock | On device: cold-start prompt, unlock, 15s grace holds, 70s re-locks, toggle both ways |
| **Room migration 1→2** | On the Redmi, over a real v1 database — see below |
| History calendar | On device: month grid, estimated vs observed, day→log form, back to Today |
| Prediction ledger | First row recorded on device with correct values and a null variability; **2 rows by 2026-08-12, one per day, as designed** |
| Daily reminder fires | On the Redmi: worker ran at 21:00 on 11 Aug and posted. See below — a 24h delay is still unproven |
| Today screen, dark mode | On the Redmi at 440dpi, 2026-08-12. Cycle day and phase hand-checked against the logged data |

### The reminder does fire — established 2026-08-12

The project's oldest open question, answered from the device rather than by waiting. The user reported
never having seen the reminder; the evidence says it fired anyway and they missed it.

| Evidence | Reading |
|---|---|
| `reminder_last_fired` = **21:00 device time, 11 Aug** | The worker ran. Only a non-test run writes this. |
| The `reminders` notification **channel exists** | Channels are created *inside* `notify()`, so the posting path executed — not just the worker. |
| 11 Aug has **no row** in `daily_logs` | So the "already logged, stay quiet" skip did not apply. |
| `POST_NOTIFICATIONS: granted=true`, `mZenMode=ZEN_MODE_OFF` | Nothing blocked it. |
| A job is **`ENQUEUED`** now, delay 10h52m from 10:07 | Due 21:00 tonight. The scheduling arithmetic is right. |

**Why it went unnoticed: the phone is on silent, and the v1 channel had `mVibrationEnabled=false`.**
The notification posted with no sound and no vibration, and `setAutoCancel(true)` means a stray tap
clears it. At 21:00 it appeared in the shade and nowhere else.

**Fixed 2026-08-12 — vibration is on, and the fix required new channel ids.** `NotificationChannel`
defaults vibration to *false*, which is easy to miss, and **a channel's settings are immutable after
creation**: calling `createNotificationChannel` again on the same id updates the name and description
and silently ignores importance, sound and vibration. So `enableVibration(true)` alone would have
worked on a fresh install and done nothing on this phone. The ids are now `reminders-v2` and
`forecast-v2`, with the old pair in `LEGACY_CHANNEL_IDS` and deleted on next creation.

**If importance, sound or vibration ever needs to change again, bump the id** — and check
`mUserLockedFields` in `dumpsys notification` first, because a bump discards any per-channel
customisation the user made in system settings. It was `0` here, so there was nothing to lose.
`notify()` used to build its channel inline rather than through the shared `channel()` helper, which
is how it came to be the one without vibration; both now go through the one function.

Verified on the Redmi the same day: `reminders-v2` has `mImportance=3, mVibrationEnabled=true`, and
the old `reminders` reads `mDeleted=true`. **A deleted channel keeps appearing in `dumpsys` with that
flag set** rather than vanishing — Android retains it so an id cannot be recycled to wipe a user's
choices. Read `mDeleted`; do not expect the row to disappear.

### The status-bar icon (2026-08-12)

Both notifications used `android.R.drawable.ic_dialog_info`. **Android keeps only the alpha channel of
a small icon and discards its colours**, so a full-colour framework drawable flattened to a solid
disc — it read as a stop sign in the status bar, which is alarming for a cycle reminder and, on a
phone other people can see, conspicuous in exactly the way the launcher icon avoids.

`drawable/ic_notification.xml` is the launcher bloom reduced to a silhouette. What that rule forces:

- **Opaque white fills only.** Any palette would be thrown away; `setColor` supplies the shade accent
  (`@color/notification_accent`) instead.
- **No background.** A filled backdrop flattens to the same solid block as before.
- **No fine detail.** The launcher icon's lighter diagonal petals and its sparkle are omitted — with
  colour gone they merge into the four main petals and an 18dp bloom becomes a blob.

Still a bloom rather than a droplet or calendar, for the reason the launcher icon is.

**Confirmed on the Redmi, 2026-08-12**, via *Send one now*: the silhouette reads as a bloom at status-bar
size and the notification vibrates. So the whole posting path is now proven end to end — worker, versioned
channel, icon, vibration — for a run triggered from the app.

**What is still unproven, and why it may stay that way.** The 11 Aug run was scheduled at 19:14 and
fired at 21:00 — a **1h46m** delay. A 24-hour delay is the case vendor ROMs actually kill.

**`MainActivity.onCreate` calls `ReminderScheduler.schedule` on every launch** (`MainActivity.kt:65`),
and `schedule` uses `ExistingWorkPolicy.REPLACE` with a fresh `setInitialDelay` of *time until the next
21:00*. So opening the app at any point in the day replaces the pending job with a shorter-delay one.
Observed directly on 12 Aug: at 16:07 the queued job showed `Enqueue time: -3m58s`,
`earliest=+4h51m` — a five-hour delay, because the app had just been opened, not the 24-hour one left
behind by the previous night's run.

This is the right behaviour (a stale job after a time change would be worse), but it has a consequence
worth knowing: **the 24-hour delay is only ever exercised on a day the app is not opened at all.** For
a user who logs most days, the delay in practice is short and the vendor-ROM risk is correspondingly
lower — which is reassuring about the app and inconvenient for testing. Do not read a successful
overnight fire as proof the 24-hour case works unless the app went untouched that day.

**Also confirmed 12 Aug:** after *Send one now*, `reminder_last_fired` was still 11 Aug 21:00. The
`if (!isTest)` guard around the bookkeeping works — a test run does not pose as a real one, so it
cannot mask a reminder that never fired.

### Built but NOT yet verified on device

- **Pre-period heads-up** and the reminder's **Bleeding / No bleeding** actions (2026-08-12) — the
  daily reminder itself was seen and felt on the Redmi via *Send one now*, so the pipeline, the
  versioned channel, the bloom icon and the vibration are all confirmed. What has not been seen: the
  heads-up (it cannot fire until two days before the next predicted window, which is still ahead), and whether either action button actually writes its row.

  *Send one now* deliberately does not prove the one thing that matters most: it runs the real worker
  through the real queue, but with no delay. Surviving an overnight wait on a vendor ROM is a
  different question, and only *Last fired* the next morning answers it.
- **Reminder-health detector** — `Settings.reminderLooksBroken()`. Surfaces a warning card when the
  reminder was due and didn't run. Exists because vendor ROMs kill background work silently.
- **Most surfaces since the pastel redesign.** Today, History, the reminder status block and the
  summary card were all seen on the Redmi on 2026-08-12 and are correct. Still unseen: health-flag
  cards (nothing is currently flagged), and the estimated-day marker on a real calendar cell — this
  phone's data appears to contain no assumed bleeding days left, so there is nothing for it to draw on.
- **The mood widget** (2026-08-12) — registered and installed, never placed on a home screen. Its
  `UNKNOWN` state is what this phone will show: `SymptomPatterns` finds no mood data at all here,
  because all four burden symptoms sit behind the log form's "More" button.
- Boot receiver, notification permission prompt.

#### Backup, restore and the clinical summary — verified on device 2026-08-12

Previously listed here as untested plumbing. All three were exercised through the real SAF picker:

| Step | Evidence |
|---|---|
| **Export** | Header `CYCBK`, format version 1. Two exports of identical data produced **different salt, IV and ciphertext at identical length** — so the codec's "never the same bytes twice" claim holds in practice. |
| **Save summary** | 46 lines, correct opening and closing markers, trailing newline, no off-vocabulary words. |
| **Restore** | Month-by-month row distribution **identical before and after**. The failure a naive restore would produce is duplication, and every count is unchanged. |
| Throughout | No `BackupException`, no `AndroidRuntime`, no `FATAL`; the app never left the foreground. |

**Still untested: `AppLock`'s 60-second grace.** The lock was switched off during the run, so the
picker never backgrounded a locked app. Testing it means switching *Require unlock* back on first and
browsing slowly in the picker — otherwise it passes for the wrong reason.
- Boot receiver, notification permission prompt.
- The backfill banner's *That's right* / *Remove* buttons were confirmed working on the Redmi.

### Prediction ledger (Phase 3, started 2026-08-11)

`core/Prediction.kt` + `data/PredictionLedger.kt`. **Room is now at version 2** — `predictions` is
the first table that is not derivable from the daily logs, because a prediction is a function of
the data *as it stood that day* and cannot be reconstructed once the logs change.

Built before any UI on purpose: the record can only ever be written going forward, so every day
without it is scoring data lost permanently. `TodayUiState.accuracy` was populated and deliberately
rendered nowhere for a while; it now reaches the screen inside Today's *Why these numbers?* card
(`TodayScreen.kt`, `WhyCard`), which still shows nothing where `accuracy()` returns null.

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
  Result: `user_version` 1→2, `predictions` created, every existing row and its `ASSUMED`
  flag intact, `integrity_check` ok, no entry in the crash buffer.
- Predictions ride along in the encrypted backup (`BackupSnapshot.predictions`, defaulted so older
  backups still restore). Restore replaces the ledger wholesale, including with nothing.

### Uncertainty window and receipts (Phase 3, 2026-08-11)

`core/Forecast.kt`, 13 tests. Fixes a contradiction that is **still on screen**: Today prints
"next period expected 25 Aug" — one exact day — directly above "cycle variability: not enough
observed cycles". Both cannot be true.

- `Forecast.periodWindow` returns a range, tagged `MEASURED` or `ASSUMED`. With the current seed
  it is ASSUMED: 1–9 Jan, ±4 days. **The UI must never present an ASSUMED window as measured** —
  it is a stated default spread (`ForecastConfig.assumedVariabilityDays`), not this user's data.
- Width comes from observed variability × `sqrt(1 + 1/n)`, the small-sample prediction-interval
  correction. A Student-t factor was considered and deliberately left out: at n=3 a 90% t-interval
  spans about three weeks, which is arithmetically right and useless. The band is documented as
  "most likely", never as a guarantee. Roughly 68% under a normal approximation, and real cycles
  are right-skewed, so true coverage sits a little below that.
- `minHalfWidthDays = 1` means a perfectly regular history still never names a single date.
- `Forecast.basis` is the receipt. The number that matters is the **observed/assumed split**: the
  a backfilled database can show a dozen completed cycles behind its estimate with only one of
  them observed. "From 12 cycles" would be true and badly misleading.
- `basis()` mirrors the branch structure of `CycleStats.expectedCycleLength`. **If that function
  gains a branch, this must too** — no test catches that drift.

Both are wired into `TodayUiState` (`window`, `basis`) and now **rendered** — `NextPeriodCard` draws
the window and says in words when the span is a stated default rather than this user's measured
variability, and `WhyCard` carries the basis. The contradiction described above is fixed: no single
date is printed anywhere.

### Visual design (2026-08-11)

Direction came from five supplied reference designs. The consistent note across them was
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

**Seen on the Redmi at last, 2026-08-12 — dark mode, and it holds up.** The warm-plum ground, the
scalloped hero card, the mascot cloud and the pink accents all read as intended at 440dpi, and the
honest window copy ("A 9-day window based on a typical spread — not yours yet") carries the point
without a wall of text. Two notes from that screenshot:

- **Decorative hearts collided with the hero card's text** — one sat directly above "What this phase
  is like →", another across the baseline of "of a 28-day cycle". **Fixed the same day** by moving
  both from `BottomStart` into the right-hand margin below the mascot.

  The rule that came out of it, and the reason this was certain rather than unlucky: **ornament
  anchors to the edge the text does not occupy.** The text column is left-aligned with a short
  longest line, so the right side is always free, while the bottom-left is precisely where the text
  ends — and it extends further into that corner as the font scale grows.
- The bare **"History"** text button under the filled "Log today" reads as a weaker sibling than it
  is, given it is the entry point for correcting backfill. Not changed.

Still unseen: **light mode**, the calendar at 440dpi, and the hero card with bleeding logged.

### Settings, and a precedence fix (2026-08-11)

Prompted by the question of whether the 28-day figure could be changed. It could not — and worse,
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

### Health flags and the symptom list (Phase 5, 2026-08-11)

`core/HealthFlags.kt`, 14 tests. The one place this app comes close to saying something about a
body, so the line is drawn hard: **it reports logged figures against common reference ranges and
never names a condition.** "Your last three cycles were 44, 47 and 41 days" is something to take to
a doctor; "this may indicate PCOS" is a diagnosis from an app that has seen a few dozen
self-reported rows.

- **Most of the tests are about not firing.** Assumed cycles can never raise a flag (§3.2) — the
  old backfill invented eleven cycles at a uniform 28 days, and alarming someone about the app's
  own extrapolation would be indefensible. One long cycle is ordinary; two is a pattern.
- Rendered in the tertiary colour, never red. An app that goes red at you is one you stop opening.
- Spotting between periods is surfaced at last: `CycleProjector` had always produced
  `SpottingEvent`s and §2.3 had always called them a flag input, and nothing ever read them.
- The history screen lists what was logged each month beneath the calendar. Symptoms had been
  write-only — enterable, with a calendar dot as their only trace — and logging that visibly goes
  nowhere is logging that stops. Anchor words rather than integers, estimated days marked.

### Accessibility (2026-08-12)

There was none: zero `contentDescription`, zero semantics blocks, against thirteen clickable or
hand-drawn elements. With TalkBack on, the calendar — the screen the whole backfill-correction flow
depends on — announced each cell as a bare number, while fill, outline, wash, ring and dot all
carried meaning.

- **Ornament hides itself.** `Sparkle`, `Dot`, `Heart`, `Cloud`, `MascotCloud` and `SparkleCluster`
  each clear their own semantics inside `Decor.kt`, not at the call site — that is exactly the thing
  that gets forgotten on the twentieth sparkle.
- Calendar cells merge into one announcement: weekday, date, bleeding logged or estimated, whether
  the day sits in the predicted window.
- Two of these were plain bugs rather than accessibility bugs. The phase picker clipped every label
  to five characters to fit four chips across, rendering "Menst" / "Folli" / "Ovula" / "Lutea"; the
  row scrolls now, which also survives a large font scale. And the "worse than your other phases"
  dot carried its meaning **only in colour** — the sentence beside it already says it, so the dot is
  decorative now.

### Heads-up, one-tap logging, clinical summary (2026-08-12)

Three gaps with one shape: the app asking for something and giving nothing back.

- **Pre-period heads-up.** There was a predicted window and a working notification pipeline, and the
  only thing the app ever said was "log today". Keyed on the **cycle start rather than the date**, so
  it fires once per cycle — repeating it every evening the window stayed open is how a useful
  notification becomes a muted one. Stays quiet once bleeding is logged.
- **Bleeding / No bleeding actions on the daily reminder.** The gap between "I should log this" and a
  saved row was six steps — unlock, tap, wait, biometric, chip, save — on a task with a ten-second
  budget. Most days the answer is one bit and now costs one tap. **"No bleeding" writes a real row**:
  it is an observation, and rule 2 makes that different from a day never logged. `LogActionReceiver`
  **is not exported** — an exported one would let any installed app write to the cycle log — and it
  preserves anything already recorded that day rather than overwriting it.
- **Clinical summary** (`core/ClinicalSummary.kt`, 11 tests). Closes the loop the health flags open:
  they say a pattern is worth mentioning to a doctor and then leave you nothing to bring, because
  the only export was an encrypted blob no other software can open. **Plain text, deliberately** —
  a file only this app can decrypt is useless in an appointment, and the trade is stated on the card
  rather than buried. Observed and estimated are separated on every line and in every count: a
  clinician reading "12 cycles, median 28 days" would reasonably assume twelve measurements, and
  backfill can manufacture that from two remembered dates. No median below three observed cycles.
  It reports lengths, spans and counts, offers no interpretation, and says on its face that it is
  self-reported app data rather than a medical record.

### Reminder control (2026-08-12)

Prompted by the user reporting they had never managed to check whether the reminder works, and that
they had no sense of controlling it. The timing was in fact already configurable — on/off and a time
picker have been in `SettingsScreen` since the settings split — so the real gap was **not control but
visibility, and one hardcoded number**:

- **`periodWarningLeadDays`** replaces a hardcoded `WARN_DAYS_BEFORE = 2`. Bounded 1–7: how much
  notice is useful is personal, but a lead time approaching the window's own span fires a "soon"
  notice for something a week off. Two remains the default; the bounds live on `Settings.Companion`
  so the stepper cannot drift from the setter's clamp.
- **`ReminderScheduler.sendTestReminder`** — fires the real job now, **through WorkManager**, not by
  calling the notify path directly. A test that posts the notification straight would prove the
  notification builds and nothing about whether the worker can run. It settles two of the three
  silent failures (permission denied, notification malformed) in two seconds. **It cannot settle the
  third** — a vendor kill of a *delayed* job — and the UI says so rather than implying a pass means
  the reminder is safe.
- A test run is flagged in the worker's input data and **must not touch the bookkeeping**: it skips
  `lastReminderFired` (writing it would tell `reminderLooksBroken()` the reminder is alive for 36
  hours, so the test would suppress the warning it exists to check), skips the heads-up (which would
  consume the cycle's only one), and skips rescheduling (which would move a 21:00 reminder to
  whenever the button was pressed).
- **`ReminderScheduler.status`** collects the four independent failure modes in one read, because
  each is silent alone: the switch can be on while the permission is denied, the permission can be
  granted while WorkManager holds no job, and both can be fine while the ROM throttles the wakeup.
  It reports **WorkManager's own state verbatim** — `ENQUEUED` with nothing ever firing means the ROM
  is dropping the wakeup, whereas no job at all means the queue was cleared and rescheduling is the
  fix. Those have different remedies and look identical from the notification shade.
- Settings shows next due, last fired, and that queue state, and offers the battery-settings and
  **app-notification-settings** routes when either is wrong. The latter is new and matters: the
  runtime permission prompt is one-shot, and a denied `POST_NOTIFICATIONS` previously left every
  switch in the app looking on while `notify` returned early and posted nothing.

### Sharing, signing, and screenshots (2026-08-13)

**The app can be given to someone else now.** It could not before: there was no signing config, so
`assembleRelease` produced an unsigned APK, which Android will not install at all.

- 4096-bit RSA key, 30-year validity, in `android/keystore/` — **gitignored, along with
  `keystore.properties`, before either file existed.** Release APK is 2.41 MB and verifies under APK
  Signature Scheme v2, which is what API 31+ requires.
- **The build degrades rather than fails when the keystore is absent**, which it is on every machine
  but the author's. `canSignRelease` goes false, `signingConfigs` is never created, and the release APK
  comes out unsigned exactly as before — so CI and a fresh clone both still build. Hardcoding a
  password or reading an always-unset environment variable would either commit a credential or fail
  confusingly.
- **The keystore is the app's identity and cannot be regenerated.** Android only accepts an update
  signed with the same key; lose it and the only route is uninstalling, which deletes the user's data.
  A verified copy lives at `C:\D_Drive\Projects\luna\` with a plain-English README beside it. That
  path was checked not to be a git repository first.

**`FLAG_SECURE` is now a setting, not a rule.** `Settings.allowScreenshots`, default off, switch beside
the app lock. It was unconditional in release builds and doing two jobs: blanking the app-switcher
thumbnail — worth keeping, since recents would otherwise show the cycle day and phase to anyone
flicking past — and blocking screenshots and screen recording, which is a real cost falling on people
the threat model does not fit. Applied on toggle and in `onResume`, not just `onCreate`: a window flag
is not something Compose can own, and a privacy switch needing a restart is one people assume failed.
Debug builds still never set it, for the reason under "Traps" — `screencap` is one of only two
channels left on the test phone.

**Low mood moved to the core log rows.** All four burden symptoms were behind the "More" button and
**none had ever been logged on this phone**, which left four built features computing over an empty
set: `SymptomPatterns`, the clinical summary's symptom section, the mood widget's ability to say
anything personal, and Phase 4's correlation half. One of the four rather than all four — seven rows
would break the form's ten-second constraint, which rule 4 makes load-bearing. `MoodReadingTest` fails
if the last core mood symptom is ever demoted, because nothing else would notice.

**No licence, decided rather than deferred.** All rights reserved, recorded in the README with the
reasoning. Not "none yet". The concern is not commercial: a fork that kept the interface and dropped
"absent is not zero", or quietly widened a window, would carry the name and none of the care.

### A gate of mine that did not do what its comment claimed (2026-08-13)

`copy-rules` greps the source tree for `android.permission.INTERNET`, and the comment above it said
that guarded against a dependency contributing the permission during manifest merge. **It does not —
a merged permission is in no source file.** The proof was already in the artifact:
`ACCESS_NETWORK_STATE` is in the shipped APK and appears nowhere in this repository, contributed by
WorkManager.

There is now a second check that dumps permissions from the **built APK**, which is the only thing
that knows what actually shipped. Both are kept: the source grep fails fast and points at a line, the
APK check is authoritative. Worth generalising from — a check is only as good as the layer it inspects,
and a confident comment above a weak check is worse than no check, because it stops anyone looking.

### Not started

- Phase 6. (Health flags, the other Phase 5 item, shipped on 2026-08-11; see above.)

### Phase 4 — closed as blocked, not abandoned (2026-08-12)

**Read this before reopening it.** `core/…/LutealLength.kt` is written and has 13 tests. It implements
the three-over-six temperature rule §7 prescribes and it is **dormant — nothing calls it, and nothing
should until a temperature source exists.**

Why it stopped: §7 permits refining luteal length *only* from basal body temperature or a wearable
skin-temperature shift, and forbids inferring it from cycle-length variance because that is circular.
The circularity is easy to walk into: ovulation day is derived **from** expected cycle length, so a
luteal length derived from observed cycle lengths agrees with the prediction by construction whatever
the body did — and then feeds the fertility window looking like a measurement.

Both permitted sources were considered and rejected on 2026-08-12:

- **Manual basal temperature** — a reading before sitting up, most mornings, for at least three
  cycles. Judged an unreasonable ask. It is made worse by the method's own failure mode: patchy
  measurement does not produce "no answer", it produces a luteal phase that is **too short**, with
  nothing on screen to distinguish the two. See the test `a gap inside a run delays the shift rather
  than reading through it`.
- **A wearable** — genuinely possible. Health Connect reads skin temperature entirely on-device, so it
  would not breach the no-internet rule. It needs hardware that records and exports it; a Galaxy Watch
  or Oura does, the Xiaomi band here does not. **This is the route to take if the hardware ever
  changes** — the analysis is already done.

Until then the app keeps `CycleConfig.defaultLutealLength = 14` and calls it an assumption, which is
the honest end state rather than a gap. **The fertility window (Phase 5) stays blocked behind this**,
and should not be built on an assumed luteal length.

### There is only one phase numbering now

The Python prototype had its own, in which "Phase 4: Platform Expansion — Android app (Kotlin port)"
meant *building this app*. Reading the two schemes as one cost a wrong turn on 2026-08-12, and the
banner added to warn about it was deleted along with the file the same day. **The phases referred to
anywhere in this document and in `CYCLE_RULES.md` are the only ones that exist.**

### Decided against — SQLCipher (2026-08-11)

Phase 2 listed SQLCipher as its last item. **It was evaluated and dropped**; `CycleTrackerApp`
opens the database plaintext on purpose. Do not "finish" this without re-reading the reasoning:

- Android 15 uses file-based encryption. `/data/data/<pkg>/databases/` sits in credential-encrypted
  storage and is unreadable until first unlock after boot, and UID isolation keeps other apps out.
  `allowBackup="false"` is already set. **The lost-phone case is covered by the OS.**
- What SQLCipher would add is protection against root, an exploit, or forensic extraction from an
  already-unlocked device — narrow for a single-user personal phone.
- It does nothing about the threat that does apply: someone with the unlocked phone opening the
  app. The app would decrypt for them.
- Costs were concrete: ~2.5 MB of native library, a migration step over live data, and — the
  deciding factor — it would have killed the pull-the-database debugging route, which is one of
  the few working inspection channels on the Y19.
  (Correction from device inspection on 2026-08-11: the migration risk was overstated when this
  was written. A freshly backfilled database holds only extrapolated bleeding days, with no symptom
  rows and no tags — nothing that could not be re-entered. The other reasons stand on their own.)
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
android/
  core/                    plain Kotlin/JVM engine + tests (no Android deps)
  app/                     Room + Compose
```

`:core` is a plain JVM module on purpose — its tests run in seconds with no emulator and no SDK.

**The fixture is vendored** into `core/src/test/resources/` because the `android/` directory can be
transferred to Windows alone. After editing anything in `spec/`, run `./gradlew syncSpec`.
`SpecDriftTest` fails on a stale copy when `../spec` is reachable.

**Do not commit real cycle dates to this repository.** It has a public-facing remote, and the data
is not only the author's. Test fixtures use obviously synthetic dates for the same reason.

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
survives — installs were dying mid-transfer more often than they completed. Screenshots still work:
`FLAG_SECURE` keys off `ApplicationInfo.FLAG_DEBUGGABLE`, which this build still sets, so it is the
*release* build that blocks `screencap`, not the minified one.

**The "7.6 MB" figure this document used to quote was the dex, not the file.** As of 2026-08-12 the
minified debug APK is **26.5 MB on disk**, while its zip entries account for only 7.8 MB — 7.59 MB of
that being `classes.dex`. So R8 *is* running (verify with `:app:minifyDebugWithR8` in the task list,
not by the file size); the remaining ~18.8 MB is packaging overhead that was not chased down. It
installs over wifi in **45 seconds**, which is the number that actually matters, so this is a
documentation correction rather than a problem to fix. Do not read the file size as evidence the flag
failed — that is the third variant of the same trap listed below.

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
4. **The pairing port and the connect port are different.** The pairing dialog's port is throwaway
   and expires fast — connecting to it yields an `offline` transport. The port you want is on the
   main Wireless debugging screen under "IP address & Port".
4. **There is no `sqlite3` binary on the device.** Pull the database instead (below).
6. **USB does not reach WSL** — no `/dev/bus/usb`, no usbip client. Would need `usbipd-win` on
   Windows. Attempting USB on 2026-08-12 also produced `adbd: timed out while waiting for
   FUNCTIONFS_BIND` and a transport that flapped between `offline` and `unauthorized` while
   `transport_id` climbed; the phone's own USB stack is unreliable. Not worth pursuing.

   **The fix is the Windows adb, not WSL's.** `/mnt/c/Users/<user>/AppData/Local/Android/Sdk/platform-tools/adb.exe`
   discovers the phone over `_adb-tls-connect._tcp` by itself and holds the connection, so **no port
   number is needed at all** — which sidesteps trap 2 entirely. WSL's adb cannot do this: WSL2's NAT
   blocks mDNS multicast, so `adb mdns services` there returns nothing.

   **Only one adb server may run.** `adbd` accepts a single connection, so a WSL server and a Windows
   server steal the device from each other — which looks exactly like a flaky device, and cost about
   an hour on 2026-08-12 being misread as one. Kill one before using the other. Pulls from the Windows
   adb must name a Windows path (`C:\...`), readable from WSL under `/mnt/c/...`.
7. **`adb shell input` is permanently unavailable on the Redmi, not intermittently.** Every attempt
   returns `SecurityException: INJECT_EVENTS`. On Xiaomi, event injection is gated behind *USB
   debugging (Security settings)*, a separate toggle from plain USB debugging, which requires a signed-in
   Mi account, which requires a SIM — and there is no SIM in this phone. So **no tap, swipe or
   `KEYCODE_WAKEUP` will ever work here.** Earlier notes describing this as blocked "as often as not"
   were wrong and sent at least one session hunting an intermittent fault.

   The one exception: `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1` **does** launch
   the app, via a different path. Do not use `monkey` for anything else — its other modes send random
   events, and this app writes health data.

   Consequence for planning: anything behind a tap needs a human. Screenshots plus `dumpsys` are the
   only channels, and the screen must be awake — `screencap` on a sleeping screen returns a valid,
   entirely black PNG of about 15 KB rather than an error.
6. **The WSL box and the phone are in different timezones** — the box is UTC+4, the Redmi UTC+5:30.
   Every timestamp in the app's prefs and in WorkManager is epoch millis, so converting them with
   `datetime.fromtimestamp` on the host reads **1h30m earlier than the phone's own clock**. A
   `reminder_last_fired` of 19:30 host-side is the 21:00 reminder, exactly on schedule — do not
   conclude the reminder fired at the wrong time. Add the offset, or convert with an explicit zone.

### What does work

```bash
# Prefer the Windows adb: it finds the phone by mDNS and needs no port. See trap 5.
# Use ONE adb server only — kill the other first.
WADB=/mnt/c/Users/<user>/AppData/Local/Android/Sdk/platform-tools/adb.exe
"$WADB" kill-server; "$WADB" start-server; sleep 8; "$WADB" devices   # rediscovers on its own

# WSL's adb, if you must. Both IP and port change; read them off the device each time.
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
adb kill-server && adb connect <PHONE_IP>:<PORT>

# Inspect UI state — this replaces logcat. Gives text, bounds and selected/checked.
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml

# NOT AVAILABLE on the Redmi — `input` needs INJECT_EVENTS, which needs a Mi account. See trap 6.
# Anything behind a tap requires a human. This line is kept only so nobody re-tries it.
#   adb shell input tap <x> <y>

# Screenshots. The screen must be awake or this yields a valid all-black ~15KB PNG.
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png . && adb shell rm /sdcard/s.png
# (`adb exec-out screencap -p > f.png` has returned 0 bytes on this device. Use the two-step form.)

# Read the database (no sqlite3 on device — pull all three files, WAL matters)
PKG=com.dheirav.cycletracker.debug
for f in cycle-tracker.db cycle-tracker.db-wal cycle-tracker.db-shm; do
  adb exec-out run-as $PKG cat /data/data/$PKG/databases/$f > $f
done
python3 -c "import sqlite3;print(sqlite3.connect('cycle-tracker.db').execute('select * from daily_logs').fetchall())"
# Is the ledger actually recording? This should gain a row a day.
python3 -c "import sqlite3;print(sqlite3.connect('cycle-tracker.db').execute('select * from predictions').fetchall())"

# Reminder settings, including whether the worker has ever run (reminder_last_fired).
# Absent keys mean defaults — reminder_enabled and period_warning_enabled default to true.
adb exec-out run-as $PKG cat /data/data/$PKG/shared_prefs/cycle-settings.xml

# Is a reminder actually queued? WorkManager's db is in no_backup/, NOT databases/.
# This is the ground truth behind the settings screen's status block.
for f in androidx.work.workdb androidx.work.workdb-wal androidx.work.workdb-shm; do
  adb exec-out run-as $PKG cat /data/data/$PKG/no_backup/$f > $f
done
python3 - <<'PY'
import sqlite3, datetime as dt
S = {0:'ENQUEUED',1:'RUNNING',2:'SUCCEEDED',3:'FAILED',4:'BLOCKED',5:'CANCELLED'}
c = sqlite3.connect('androidx.work.workdb')
cols = [r[1] for r in c.execute('pragma table_info(WorkSpec)')]
for r in c.execute('select * from WorkSpec'):
    d = dict(zip(cols, r))
    due = (d['last_enqueue_time'] + d['initial_delay']) / 1000
    print(d['worker_class_name'].split('.')[-1], S.get(d['state']),
          'due', dt.datetime.fromtimestamp(due), '(host zone — the phone may differ)')
PY

# Did anything block the notification? Permission, DND, and the channel's own settings.
# The channel existing at all proves notify() ran, since that is where it is created.
adb shell dumpsys package $PKG | grep POST_NOTIFICATIONS
adb shell dumpsys notification | grep -iE "mZenMode"
adb shell dumpsys notification --noredact | grep -A2 "$PKG"

# Screenshots. `adb exec-out screencap -p > shot.png` is the usual recipe and it started returning
# ZERO BYTES mid-session on 2026-08-12 while the device was awake, focused and otherwise responsive
# (dumpsys and uiautomator kept working). Writing to the device and pulling is reliable — use it
# first rather than debugging the pipe.
adb shell screencap -p /sdcard/shot.png && adb pull /sdcard/shot.png shot.png

# KEYCODE_WAKEUP is subject to the same INJECT_EVENTS block as `input tap`, so when input is blocked
# there is no way to wake the screen from here. Ask the user.
adb shell input keyevent KEYCODE_WAKEUP
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

Unrestricted battery can be granted on both test phones. **Autostart cannot** — Funtouch hides or
gates it, and there is no public API for it on any of these ROMs. This is exactly why
`reminderLooksBroken()` exists: whether the reminder survives is an open empirical question that
can only be answered by a few days of real use.

If it is being killed, the home-screen widget becomes the more important delivery mechanism, since
it needs no background execution at all.

Settings' reminder status block is the instrument for this. `ENQUEUED` in the queue with *Last fired*
stuck in the past is the ROM dropping the wakeup — the case with no programmatic remedy. An empty
queue instead means WorkManager's own state was cleared, which rescheduling fixes and which the boot
receiver exists to cover.

---

## Continuous integration (2026-08-12)

`.github/workflows/tests.yml`. Two jobs, both emulator-free, both finishing in seconds:

- **`unit-tests`** — `:core:test`, `:app:testDebugUnitTest`, then `:app:assembleDebug -PminifyDebug`.
  The build step is not redundant: it compiles the Compose and RemoteViews code no unit test touches,
  and `-PminifyDebug` is the only automated check that the release ProGuard rules still work. Test
  reports upload as an artifact on failure.
- **`copy-rules`** — two greps for things no unit test can see. That no retired vocabulary
  (`backfill`, `guesswork`, `guesses`, `guessed`) appears in a **string literal**, since those were
  collapsed into `estimated`/`observed` and the word is still correct in comments. And that
  `android.permission.INTERNET` appears nowhere, because that is the project's most load-bearing
  promise and a transitive dependency can add it to the merged manifest without anyone writing a line.

JDK 21, matching the local pin. A mismatch there produces failures nobody can reproduce.

### The migration test runs on an emulator in CI, because the phone cannot install it

`app/src/androidTest/…/MigrationTest.kt`, three cases, run by the `migration-test` job on every push
to `main`. Not on pull requests — it takes minutes rather than seconds, and the point of the other
jobs is fast feedback.

**It was written to be run by hand against the Redmi and that is impossible.** MIUI refuses to install
a *new* package over adb without "Install via USB", which requires a signed-in Mi account, which
requires a SIM this phone does not have. The failure is `INSTALL_FAILED_USER_RESTRICTED: Install
canceled by user` with nobody touching the device, and it is the same Mi-account gate that blocks
`adb shell input` (trap 7). Note the app APK *does* install, because that is an update to an existing
package — only new packages are refused, which is why this was not noticed until the test APK existed.

A local emulator is not an option either: the SDK here has no `emulator` package, no system image and
no AVD, and WSL2 would need nested virtualisation on top of that.

So CI is the only place it can execute, which is a better outcome than the original plan — automatic
rather than remembered. API 31 to match `minSdk`; testing a migration on an API the app cannot be
installed on would prove nothing about the SQLite it ships against.

`MigrationTestHelper` replays an exported schema through real SQLite, which cannot be done on the JVM
— hence the one exception to the emulator-free rule. It is worth the exception: `CycleTrackerApp`
opens the database with **no `fallbackToDestructiveMigration`**, on purpose, so a broken migration does
not degrade — it stops the app opening at all, with the history intact and unreachable.

It writes rows *before* migrating and reads them back after, because "the migration ran" and "the
user's history survived it" are different claims. `validateMigration = true` compares the result
against `app/schemas/2.json`, so a migration that executes cleanly and produces the wrong shape still
fails — which is exactly what a hand-check on a phone is worst at catching. **`app/schemas/` is checked
in and must stay checked in.**

Written but **not yet executed**: no device was reachable when it was added. Running it once is the
first item below.

## Suggested next steps

1. **Check the `migration-test` job went green on its first run** (2026-08-12). It had never
   executed when it was written, and CI is the only place it can — see above. If it failed, the
   emulator job is new and unproven, not necessarily the migration.
2. **Check *Last fired* the morning after a day the app went unopened.** The only unproven thing left
   in the reminder. A short delay is proven and *Send one now* proves the posting path; the 24-hour
   delay is not, and — see "The reminder does fire" — every app launch replaces the pending job with a
   shorter one, so a normal day never tests it. If it stalls, `ENQUEUED` with an old *Last fired* means
   the ROM dropped the wakeup — battery and Autostart are the only remedies.
3. **Give the mood fields a reason to be logged.** All four burden symptoms sit behind the log form's
   "More" button, and nothing on this phone has ever been logged into them — which is why
   `SymptomPatterns` is empty, why the clinical summary has no symptoms section, and why the mood
   widget will read *Typically: …* rather than anything personal. Promoting one out from behind "More",
   or routing the mood widget's tap into a mood-first form, is the smallest change that unblocks all
   three. **This also gates the other half of Phase 4** — the symptom/phase correlation work
   `SymptomPatterns` defers to it needs symptom data to correlate.
4. **Decide what a day's value is when a symptom is logged twice.** `upsertSymptoms` uses
   `OnConflictStrategy.REPLACE`, so re-logging already works and silently keeps the *last* value. That
   is an undocumented, implicit rule of exactly the kind removed from the clinical summary on
   2026-08-12. Worst-of-day would be consistent with `MoodReadings`, which takes the worst of the four
   burdens rather than their mean. About ten lines, and no migration.

   A **timestamped** multiple-entries-per-day schema was considered and deferred the same day: it needs
   Room v2→v3 on real data, plus DAO, repository, log form, `SymptomPatterns` and a `FORMAT_VERSION`
   bump in the backup codec. Revisit only once mood is actually being logged and there is a surface
   that wants to *show* within-day variation, rather than merely capture it.
5. **Verify `AppLock`'s 60-second grace** during a slow browse in the file picker. Requires switching
   *Require unlock* back on first — it is currently off, so an export would pass for the wrong reason.
6. **Place the mood widget and the resized cycle widget on a home screen.** Neither has been seen.
   Note Android applies `targetCellWidth/Height` only to *newly placed* widgets, so an existing widget
   keeps its old size until removed and re-added.
7. **Fix the stale comment in `TodayScreen`.** Its KDoc says "No character or face here, deliberately"
   and the card has had a face since the pastel redesign. Worse, `MascotMood` derives that face from the
   phase alone — the app inferring a mood from a calendar, which is precisely what the mood widget was
   built to avoid. Switching the hero to the same log-driven `MoodReadings` source is the highest-value
   follow-on from that work.
8. **Consider the `· estimated` label's contrast** in the History month list (`HistoryScreen.kt:188`).
   `cycle.estimated` doubles as a text colour there; darkening it on 2026-08-12 took it from roughly
   1.9:1 to 2.6:1 against the cream card, still short of AA for an 11sp label. The proper fix is to let
   the *word* carry the meaning and give the text a normal on-surface colour — a design call, not a bug.

Phase 4 is **closed as blocked** — see its own section. Phase 5's fertility window stays blocked behind
it and must not be built on an assumed luteal length.

## Version control

Single `main` branch. Nothing here is generated — `android/build/`, `.gradle/`, `.kotlin/` and
`local.properties` are all ignored, the last because it hardcodes an SDK path and would break a
build on another machine.

**Every `*.db`, `*.db-wal` and `*.db-shm` is ignored, and that rule must not be relaxed.** It covers
both any local database and anything pulled off a device for debugging, all of which is real health
data. A backfill seed containing real period dates was removed from this repository and purged from
its history for exactly that reason — see the note under "Open questions".

## Open questions for the user

- **The backfill seed has been removed from the repository** (2026-08-12). It held real period
  dates belonging to someone who had not agreed to them being published, and the project was about
  to gain a GitHub remote. There is no `spec/seed_periods.json`, no vendored asset, no
  `SeedImporter`, and git history was rewritten so the dates are in no commit.

  Nothing replaced it, deliberately. The history calendar now makes entering past periods a
  two-tap job per day, which is what the seed existed to avoid back when the log form could only
  step one day at a time. A fresh install simply starts empty and says "No periods logged yet".

  Existing installs are untouched — the importer only ever ran on an empty database, so data
  already on a phone stays there.
- Whether the relationship-advice module stays or goes (it is currently cut).

  (An earlier item here claimed `menstrual_tracker.db` was tracked by git and needed
  `git rm --cached`. It was never tracked — the file predates any repo covering this directory.
  Nothing to do; the `.gitignore` rule is enough.)
