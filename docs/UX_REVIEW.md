# UX review: progressive disclosure, a synced UI, and the usual laws

Written 2026-10-04 from a full read of `android/app/src/main/kotlin/…/ui/`, `MainActivity.kt`,
the two widgets and `Reminders.kt`. Nothing here has been checked on a screen yet, because the
phone was locked when I tried, so every finding comes from the code and gives the line it is at.

The short version: the app already follows progressive disclosure better than most, and the
problems are elsewhere. The biggest one is **sync**, because each screen, both widgets and the
reminder build their own copy of the cycle state, and they rebuild it at different moments. That
has already produced one data bug, and it explains several smaller "this screen is out of date"
cases. Fixing the structure removes the whole class rather than patching each case.

---

## 1. What the three asks mean here

- **Progressive disclosure**: the claim goes on the surface and the evidence sits one tap away,
  and anything used rarely stays out of the daily path. The Today screen's KDoc already states
  this as its design rule (`TodayScreen.kt:66`), so the review checks whether the rest of the app
  keeps to it.
- **A synced UI**: every surface that shows the cycle (Today, History, the phase guide, the
  doctor summary, both widgets, the notifications and the prediction ledger) should show the same
  answer at the same moment, and should update when anything changes it: a log edit, a settings
  change, a restore, a notification action, or midnight.
- **The laws of UX**: Fitts, Hick, Jakob, Miller, Doherty, Von Restorff, Tesler, peak-end and
  goal-gradient, plus Nielsen's heuristics where they say something more specific.

---

## 2. Bugs found on the way (fix these first)

### 2.1 The reminder overwrites the day's prediction with different inputs

`Reminders.kt:313` calls `CycleEngine().stateFor(...)` **without** `userTypicalCycleLength` or
`userTypicalPeriodLength`, while Today passes both (`TodayViewModel.kt:96`). The ledger upserts
one row per date (`PredictionLedger.kt`), so the 21:00 worker's row replaces the one Today
recorded and showed earlier that day.

This matters whenever fewer than three cycles have been observed and a cycle length is set in
Settings, because that is exactly when the user's figure outranks the app's. The ledger then
grades a prediction the app never showed, and the accuracy figure in "Why these numbers?" is
measured against the wrong thing. That breaks the rule that the app never states a number the data
has not earned. It also runs on "Send one now" test runs, because `recordPrediction` sits outside
the `isTest` guard (`Reminders.kt:281`).

### 2.2 Today goes stale and nothing brings it back

`TodayViewModel` reads the database once (`allLogsOnce`) and only rebuilds on `reload()`, which is
called after a save from the log form and after a settings change. It is **not** rebuilt when:

- the app is resumed the next day. The header date, cycle day and phase stay on yesterday's
  values, and the only ledger row that day comes from the worker, with the inputs from 2.1;
- a notification's *Bleeding* or *No bleeding* button writes a row (`LogActionReceiver`), which
  refreshes the widgets but not the open screen;
- a day is edited from History and the user then goes Back to Today. This path is covered by
  `onSaved`, but backing out of the log form without saving is not, and neither is a widget edit.

### 2.3 History's shaded window ignores settings changes

`HistoryViewModel` recomputes the window only when Room emits (`HistoryViewModel.kt:51`). Changing
the window width or cycle length in Settings writes to SharedPreferences, which Room never sees, so
the calendar keeps the old shading until the next log edit while Today shows the new window. The
two screens disagree in the meantime.

### 2.4 Back throws away an unsaved log entry without asking

`MainActivity.kt:164` sends Back straight to the origin screen. Anything entered in the form and
not saved is gone with no prompt and no undo. On a screen whose whole budget is ten seconds, losing
a filled-in day to a stray back gesture costs more than the original entry did.

---

## 3. Sync: one source of truth instead of seven

The same five-line pipeline (all logs → bleeding days → assumed days → `CycleProjector.project` →
`stateFor`) is written out separately in **seven places**: `TodayViewModel`, `GuideViewModel`,
`LogRepository.projection`, `SummarySection`, `CycleWidget`, `MoodWidget`, and twice in
`Reminders.kt`. Each copy chooses its own inputs and its own moment to run. Bug 2.1 is one copy
choosing different inputs, while bugs 2.2 and 2.3 are copies running at the wrong moment.

**Proposal.**

1. **In `:core`**, a single pure function, `CycleSnapshot.build(logs, symptoms, settings, today)`,
   returning everything the surfaces read: projection, state, window, basis, flags, accuracy inputs.
   It is pure, so it is tested on the JVM like the rest of the engine, and a test can assert that
   every caller gets the same answer for the same inputs.
2. **In `:app`**, a `CycleRepository` that exposes `StateFlow<CycleSnapshot>`, combined from three
   sources: Room's `Flow` of logs and symptoms, a `Flow` over the settings prefs (an
   `OnSharedPreferenceChangeListener`), and a date ticker that emits at midnight and on resume.
   Any change to any of the three produces one new snapshot, and every screen collects it.
3. **Widgets, the worker and the summary** call the same `build` function with the same settings.
   They cannot collect a flow, but they can no longer pick different inputs.

That removes `reload()` and every hand-placed refresh call, and it is why "synced" stops depending
on someone remembering to wire up the next surface. The ledger write moves next to the snapshot,
so a prediction is recorded from exactly the state that was rendered.

---

## 4. Progressive disclosure: what works, what does not

**Already right, and worth keeping:**

- Today puts the claims on the surface (day, phase, window) and the receipts behind *Why these
  numbers?*, with the honest one-line accuracy statement visible even when it is collapsed.
- The log form shows three core symptoms and keeps the rest behind *More*, and it opens *More*
  automatically when a day already has extended symptoms (`LogViewModel.kt:43`), so data is never
  hidden.
- Settings moved off Today. The phase guide sits behind the hero rather than as another button.

**Where it breaks down:**

- **Settings is one flat scroll of seven cards**, and the reminder card alone holds two switches,
  a time, a stepper, a three-line status block, a test button, two explanatory paragraphs and up to
  two fix rows (`SettingsScreen.kt:236` to `365`). The diagnostics are what you need when something
  is wrong and noise when it is not. They should collapse into one line ("Working, last fired 4 Oct
  21:00") that expands, and expand by themselves when `looksBroken`, notifications are blocked or
  the battery is restricted. The same goes for the explanatory paragraphs under each setting, which
  read once and then cost scrolling every visit after.
- **Settings has no grouping by how often it is touched.** "Your cycles" and "Prediction window"
  change what the engine says, while backup, the doctor summary and the app lock are twice-a-year
  actions. Two headed groups ("Predictions" and "Data and privacy") would cut the visible choices at
  each level, which is Hick's law doing the work.
- **The hero states "of a 28-day cycle" with no qualifier** (`TodayScreen.kt:286`) even when the
  28 is the app default or mostly estimated. The window card below qualifies its own number, while
  the hero does not, so the most prominent number on the screen is the least earned one. A short
  suffix such as "of a cycle around 28 days (estimated)" when `basis.source` is not
  `MEDIAN_OF_OBSERVED` keeps the surface claim honest, and the detail stays in the Why card.

---

## 5. The laws, applied

| Law | Finding | Where |
|---|---|---|
| **Jakob** (people expect apps to work like the others they use) | No secondary screen has a top bar or an up arrow. Log, History, Settings and the guide can only be left by the system back gesture, which some users do not know and gesture navigation hides. Every mainstream Android app shows an up arrow with the screen title. | `MainActivity.kt:168`, all screens |
| **Goal-gradient / Fitts** | *Save* is the last item on the log form, below notes. On a 1280x2772 screen at 520dpi (about 850dp tall) it sits at or below the fold, and below it for certain once *More* is open. A sticky bottom bar with *Save* keeps the finish line visible and in the thumb zone. | `LogScreen.kt:157` |
| **Fitts** | *History* is a text button under the filled *Log today*, although it is the entry point for correcting every past period. The handover already noted it reads as a weaker sibling than it is. | `TodayScreen.kt:139` |
| **Feedback (Nielsen #1) / peak-end** | Saving a day gives no confirmation. The screen just changes. The end of the logging moment is the one most worth making feel finished, and a snackbar ("Saved for Fri 3 Oct") also gives an *Undo* for free, which covers 2.4's sibling, the mistaken save. | `LogViewModel.kt:82` |
| **Error prevention (Nielsen #5)** | *Remove* on the estimated-day banner deletes the day immediately, with no confirm and no undo. | `LogScreen.kt:206` |
| **Von Restorff** (the different thing gets noticed) | A dead reminder and a health flag render in the same tertiary card on Today, so a problem the user must act on looks identical to an observation to take to a doctor. Settings, meanwhile, shows the same dead-reminder condition in the error red. One condition has two severities depending on the screen. | `TodayScreen.kt:545`, `SettingsScreen.kt:435` |
| **Consistency (Nielsen #4)** | `BackupSection` is the one settings card not built on `SettingsCard`: different padding (16 vs 18), default shape instead of large, and no heading semantics, so TalkBack users cannot jump to it. | `BackupSection.kt:74` |
| **Hick** | Log form flow chips are a plain `Row` of four. Fine at default font, but at a large font scale they run off the edge, which is the failure the History legend already fixed with `FlowRow`. | `LogScreen.kt:84` |
| **Doherty** (respond in under 400 ms) | Today shows a spinner on cold load and nothing for later rebuilds, which is right. With a single snapshot flow, later rebuilds become instant because the snapshot is already in memory. | `TodayScreen.kt:90` |
| **Tesler / Miller** | Nothing to fix. The Why card's eight rows are behind a tap, and no screen asks for more than a handful of choices at once. | |

**Not a law, but a project rule.** The mascot's face is chosen from the phase alone
(`TodayScreen.kt:202`), which is the app showing a mood it inferred from a calendar, the exact
thing "phase guidance and the user's own logs are separate surfaces" forbids. The KDoc above it
still says "No character or face here, deliberately" (`TodayScreen.kt:178`), which is now false.
The fix is to drive the face from `MoodReadings`, the log-driven source the mood widget already
uses, and to show a neutral face when nothing was logged.

---

## 6. Order of work

Steps 1 and 2 were done on 2026-10-04 (see HANDOVER, "One snapshot") and checked on the Redmi Note
15 Pro the same evening. Step 3 was done on 2026-10-05, together with four problems that only showed
once the real screen was seen:

- "Log today" was below the fold, pushed down by a reminder warning and a health flag. It now sits
  directly under the hero, and the battery-restriction warning is a one-line footnote at the bottom.
- The window card kept saying "Next period" over a window that had passed. It now reads "Was
  expected" with the same dates, and does not repeat the lateness count the hero already gives.
- Mood labels truncated to "Modera…" and "Overwh…". They now wrap onto two lines with hyphenation.
- A stopped reminder looked identical to a health flag. It is now an outlined card with an error
  title, and only a stopped reminder (not a restricted one) uses red, on Today and in Settings alike.

Step 3 itself: a back arrow on every secondary screen (it presses the system Back, so the unsaved-edit
prompt still applies), Save pinned below the log form, and a snackbar with Undo after a save, a
confirmation or a removal. Undo writes the day back with its original source, so undoing an edit to an
estimated day does not turn it into an observed one.

Seen on the Redmi on 2026-10-05: the new Today order, the "Was expected" card, the battery footnote,
the back arrow on History, and Save then Undo (which left today with nothing logged, as it should).
Two things failed on screen and were redone: hyphenated mood labels cut "Overwhelming" off after
"Over-/whelm-", and the back arrow stacked above "‹ Earlier" put two unrelated left-pointing controls
next to each other.

Follow-ups the same day:

- **Today shows what was logged.** Once today has an entry, "Log today" becomes a tonal card reading
  "LOGGED TODAY" with a one-line summary (`loggedSummary`, 4 tests) and "Edit", opening the same form.
- **One header on the log form:** back arrow alone at the left, chevrons either side of the date, and
  the date opens a calendar limited to today and earlier. Save reads "Save · Today" or the date.
- **Level rows are a custom row**, not Material's segmented button: 2dp padding, no check icon,
  selection shown by fill and weight, and words that do not fit shrink (down to 8sp) instead of
  wrapping or truncating.

Seen on the Redmi the same night: the merged header, the calendar (future days disabled; picking
2 Oct relabels the header and Save, and the chevrons step back), "Overwhelming" whole on one line, and
the "LOGGED TODAY" card after a save.

Two more fixes came out of that check, built and installed but **not yet seen on screen**:

- The Undo snackbar used the short duration, about four seconds, and was gone before Undo could be
  reached. It now uses the long one.
- Each level cell exposed three accessibility children (its description, its visible word and a radio
  stub), so TalkBack would read "Energy: OK, OK, radio button". Each cell is now one node carrying the
  description, the radio role, the selected state and the click.

Both were confirmed on screen on 2026-10-05: Undo was still showing after seven seconds and restored
the day, and each cell is now a single radio button carrying its label and checked state.

**Step 4 (Settings) was done on 2026-10-05 and checked on screen.** Three headed groups: Predictions,
Reminder and widget (the widget is the reminder's fallback), Privacy and data. Each card keeps the
line saying what its controls do now and moves the reasoning behind an info button; the two privacy
trades (what screenshots expose, and the unencrypted doctor summary) stay visible on purpose. The
reminder card folds to one status line (`reminderHeadline`, 6 tests) with the diagnostics behind
"Details", and opens itself, with no Hide, when notifications are blocked or the reminder stopped.
Backup and the summary moved onto the shared card, which was a step 6 item.

Open from that check: a *selected* level cell reports itself as not clickable, which Android does for
any selected radio button, so TalkBack can choose a level but not clear one, while a finger tap clears
it. Dropping the radio role in favour of a state description would fix it.

Steps 5 and 6 are open.

1. **The four bugs in section 2.** Small, and 2.1 corrupts data that can never be recovered,
   because the ledger only records forward. Each gets a test in `:core` or `:app` before the fix.
2. **The single snapshot (section 3).** It fixes 2.2 and 2.3 structurally, and it is the
   precondition for calling the UI synced.
3. **Navigation and feedback**: top bar with up arrow on every secondary screen, sticky *Save*,
   a saved snackbar with *Undo*, and confirm-or-undo on *Remove*.
4. **Progressive disclosure in Settings**: two groups, collapsed reminder diagnostics that open
   themselves on a problem, and the explanatory text behind an info tap.
5. **Honesty on the surface**: the hero's cycle-length qualifier and the mascot driven by logs.
6. **Consistency polish**: one severity colour per condition, `BackupSection` on `SettingsCard`,
   `FlowRow` for the flow chips, and the `· estimated` contrast item already in the handover.

Steps 1 and 2 change no visible design, so they can go in without a screenshot review. Steps 3 to 6
should each be checked on the Redmi Note 15 Pro before moving on, because that is the first phone
at 520dpi and the first on Android 16.
