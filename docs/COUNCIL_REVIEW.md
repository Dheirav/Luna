# Council review, 5 Oct 2026

Five independent reviewers read the code at `da3dd11`, each through one lens: UX and interaction,
accessibility, the project's honesty rules, correctness of this session's code, and a daily-use
walkthrough. None of them changed anything or ran the app. Between them they raised 49 findings. I
merged the ones that described the same problem from different angles, and checked each against the
code before keeping it.

**Status** on each item:

- **confirmed:** I read the code and it does what the finding says.
- **plausible:** the code supports it, but it depends on platform behaviour I have not seen on the
  phone.
- **decision:** the fix changes the spec or the product, so it is yours to make rather than mine.

The broad picture: the honest surfaces built over the last two days hold up, and several reviewers
said so unprompted. The problems are at the edges. The everyday "no bleeding" answer does not save
from the form; unanswered days are treated as answers in two places; flags and "late" are measured
against numbers the data has not earned; and accessibility was checked for structure but not for
contrast or font scaling.

**One correction to my own earlier report.** I said step 6 of `UX_REVIEW.md` was finished. Its list
included "FlowRow for the flow chips", and that was never done: the bleeding chips are still a plain
`Row` (`LogScreen.kt`), which squeezes at large font sizes (A5 below).

---

## 1. Data integrity: fix first

These lose, corrupt or misfile health data. None needs a design decision except where marked.

**D1. The prediction ledger can be rewritten with hindsight.** confirmed. A regression from this
session.
- **Where:** `TodayViewModel.render` calls `ledger.record(snapshot.state, snapshot.today)`.
  `PredictionLedger.record` skips a write when `state.date != today`, but both values now come from the
  same snapshot, so the check compares the snapshot with itself. Before this session it compared
  against `LocalDate.now()`.
- **Failure:**
  - The view model keeps collecting in the background, and the midnight `delay` runs on a clock that
    stops while the phone sleeps. So the snapshot's date can stay on yesterday.
  - A notification-action log then rebuilds a snapshot that is still dated yesterday, from data that
    includes today's period.
  - Yesterday's ledger row is rewritten with that hindsight. The scorer grades exactly that row, so
    accuracy comes out better than it really was.
- **Fix:** record with the real date, `ledger.record(snapshot.state)`, so a stale snapshot is skipped
  rather than back-dated. Collect only while the screen is started. Add a regression test.

**D2. "No bleeding" cannot be recorded from the form, and saving can delete one that was recorded.**
confirmed. Three reviewers raised this independently.
- **Where:** `LogRepository.write` deletes any day with no bleeding, symptoms, tags or notes. The
  bleeding chip reads "No" when it means "not answered", and tapping it turns it to "Bleeding".
- **Failures:**
  - **A non-bleeding day from the form saves nothing.** The snackbar still says "Saved · Today", yet
    Today keeps "Log today" and the 21:00 reminder asks again.
  - **Edit-then-Save deletes an answer.** A day answered "No bleeding" from the notification *is*
    stored. Opening it with Edit and pressing Save deletes it.
  - **The opposite case (honesty audit).** A day where only a symptom was logged is shown as "No
    bleeding" on Today, and the reminder then skips it, so the bleeding question is never asked.
- **Fix:** make bleeding three-state everywhere: not answered, no, and yes (with flow). That needs a
  stored "answered" flag, which is a Room migration from v2 to v3 with a migration test, the same
  technique as v1 to v2. Only say "Saved" when something was written. Make Save with no changes simply
  leave the form. **decision**, because it changes the schema.

**D3. Two unlogged days inside a period split it.** confirmed; **decision**.
- **Where:** CYCLE_RULES §2.1 tolerates one non-bleeding day inside a period
  (`maxIntraPeriodGapDays = 1`), and unlogged days count as non-bleeding.
- **Failure:** you log day 1, are too unwell to log days 2 and 3, then log day 4. The period becomes 1
  day long, day 4 becomes "spotting", and Today shows a "Bleeding between periods" card in the middle
  of your period.
- **Fix:** this is "absent is not zero" broken by the spec itself. The reviewer suggests not changing
  the rule silently, but asking instead: "Were you bleeding on Sat and Sun?" with one-tap answers, and
  holding the spotting flag until it is answered.

**D4. Day 3 of a period reads "Follicular" until day 3 is logged.** confirmed; **decision**.
- **Where:** `CycleEngine` uses the current period's span *so far* as its length, so the usual-length
  fallback is never reached. The "Usual period length" setting therefore does nothing, despite its
  description.
- **The catch:** this is what CYCLE_RULES §5.1 literally says, so the spec needs amending. The
  suggestion: while a period is still open, use the larger of the span so far and the expected period
  length.

**D5. Answering the reminder after midnight logs the wrong day.** confirmed.
- **Where:** `LogActionReceiver` uses `LocalDate.now()` at the moment of the tap.
- **Fix:** carry the reminder's date in the action and log that date.

**D6. Smaller write-path defects.** confirmed by reading.
- **Not atomic:** `write()` is five separate statements with no transaction. A crash in between loses
  that day's symptoms or tags, and screens briefly see half-written state.
- **Undo leaves a stale form:** if the form is already open on the same day, Undo does not reload it,
  so the next Save writes the undone change back.
- **Discarded edits linger:** they stay in `LogViewModel` and can resurface through a no-op `open()`.
- **Deep links skip the guard:** opening the form from the reminder or the widget bypasses the
  unsaved-changes prompt (`MainActivity` calls `logVm.open(today)` directly).

---

## 2. Privacy

**P1. The app lock hides the screen from fingers but probably not from TalkBack.** plausible; check
on the phone.
- **Where:** `AppLockGate` draws the lock overlay over `content()`, which stays composed underneath.
  Only touch is blocked.
- **Failure:** a screen reader can most likely walk through, and activate, the cycle data behind the
  "Locked" screen. That is the exact over-the-shoulder threat the lock exists for.
- **Fix:** do not compose the content while locked, or clear its semantics.

**P2. Both widgets show cycle details by default.** confirmed (`widgetShowsDetails` defaults to
true). The code's own comment says this "leaks exactly what the launcher icon was designed not to".
**decision:** default to discreet.

**P3. Notifications have no lock-screen privacy.** confirmed. Neither notification sets a visibility
level or a public version, so "Period expected soon · Likely between 3 Oct and 9 Oct" can show on the
lock screen. The Bleeding / No bleeding buttons do not require unlocking (plausible), so anyone holding
the phone can write a day. **Fix:** a neutral public version, plus `setAuthenticationRequired(true)`
on the actions.

---

## 3. Honest numbers

**H1. "Late" is counted from a single date, not from the window.** confirmed.
- **Where:** `daysLate = cycleDay - expectedCycleLength`.
- **Failure:** the hero says "2 days later than expected" while the card below still shows the window
  as open. "Late" is also measured against 28 even when 28 is the app's default.
- **Fix:** only call it late once the window has passed, and count from the window's last day.

**H2. The late and absent flags fire on assumed or default data, and reach the doctor summary.**
confirmed.
- **Where:** the length and spotting flags filter on observed periods; these two do not.
- **Failure:** "a cycle that usually runs 28 days" can print in the doctor summary when the 28 is a
  population default. "The last one you logged" can describe an estimated period.
- **Fix:** gate both flags on observed data, and word the length's source.

**H3. The doctor summary misses what a doctor would ask about.** confirmed.
- **Symptoms:** it summarises only the current phase, under a heading that implies all phases. There
  is nothing on flow and nothing on pain during periods.
- **Estimated not marked:** the most recent period and the in-progress cycle are not tagged ESTIMATED
  when they were.
- **Format:** dates print as ISO (2026-07-01), and it saves a .txt file with no preview or share
  option.

**H4. Different surfaces count different "observed" cycles.** confirmed. The window's "across N
observed" counts every observed cycle, while the Why card uses the last 6 plausible ones, so with a
long history the two numbers differ on one screen.

**H5. Assumptions shown as facts.** confirmed.
- "Ovulation day 14" and the phase name never say they assume a 14-day luteal phase.
- An assumed window shows without qualification on the widget, the heads-up notification and the
  History calendar.
- History keeps shading a window that has already passed.
- Settings promises coverage ("right roughly two times in three") that nothing has measured.

**H6. The mood widget can say the opposite of the data.** plausible (from reading `MoodReading`). It
says "You often log low mood around now" when a symptom stands out as *lower* than usual. It says
"Nothing unusual" when there was nothing to compare against.

**H7. Thirteen days late, the app never names the obvious question.** **decision**, because of tone.
The hero lists stress, illness, travel and sleep, but not pregnancy. Nothing sits between "one late
cycle is common" and the 90-day "absent" flag. The suggestion is one neutral line past about 7 days
("If there is any chance of pregnancy, a test is the way to know"), and a point to act at about 6
weeks.

---

## 4. Accessibility

**A1. Five switches have no spoken name.** confirmed. "Remind me to log", "Warn me", "Show cycle
details", "Require unlock" and "Allow screenshots" each read as "Off, switch". **Fix:** make each row
the toggle.

**A2. The light theme's pink fails contrast.** confirmed; I recomputed the ratios.

| Text | Ratio | Needs |
|---|---|---|
| White on the pink "Log today" / "Save" buttons | 3.21:1 | 4.5:1 |
| Pink link text on the default card colour | 2.48:1 | 4.5:1 |
| Secondary text on cards | 4.17:1 | 4.5:1 |

Part of the cause: the theme never sets Material's surface container colours, so cards and dialogs
fall back to Material's stock lavender instead of the app's own palette. **Fix:** a darker light-mode
pink (`#A8336A` measures 6.27:1 on white), and set the container colours explicitly. Dark mode passes.

**A3. Text on the hero gradients and widget tints is below 4.5:1.** plausible (reviewer's
arithmetic; I did not recompute all of it). This is worst on the dark ovulation gradient. Fix by
raising the text alphas to at least 0.85 and darkening that gradient.

**A4. The calendar's predicted window is shown by colour alone, at about 1.2:1.** plausible. Give
window days a non-colour mark as well.

**A5. At 200% font size, words are clipped instead of wrapping.** confirmed by reading.
- `FitText` stops shrinking at 8sp and then clips mid-word, and it overrides the user's chosen text
  size at every scale.
- The bleeding and tag chips do not wrap.
- The hero's phase name runs under the mascot.

**A6. Nothing is announced.** confirmed. There are no live regions or pane titles anywhere. Stepper
values, day changes, month changes, backup results and screen changes are all silent to TalkBack.
Undo times out at 10 seconds even for screen-reader users, who may need far longer to reach it.

**A7. Smaller.**
- Flow chips say "Light" without "flow".
- Expand and collapse toggles do not report their state.
- The cycle widget's spoken description differs from what it shows (window dates instead of "3 days
  late").

---

## 5. Flow and friction

**F1. The first run is two system prompts and a dead end.** confirmed.
- **What happens:** the app lock and the notification permission are asked before anything is seen.
  The notification prompt repeats whenever the screen is rebuilt.
- **Entering past periods costs a tap per day,** about 15 per period.
- **Never mentioned:** the reminder, the widget and the usual-length setting.
- **Fix:** open with "When did your last period start?" using a date range. Ask for notifications after
  the first save, and switch the lock on after setup.

**F2. Backup has no safety net.** confirmed.
- **Export:** the passphrase is typed once, masked, with no confirm field, so a typo makes the backup
  unrecoverable.
- **Restore:** it replaces everything without showing which backup or how many days it holds.
- **Settings are not included.**
- **No reminder:** there is no "last backed up" date and no nudge.

**F3. The one-tap notification answers are silent and cannot be undone.** confirmed. The in-app path
got Undo; this path did not.

**F4. Catching up missed days is a loop.** confirmed. Nothing says "3 days not logged". Saving a past
day drops you back on Today, and there is no "next day" or "same as yesterday".

**F5. Smaller.**
- The phase guide never marks which phase you are in.
- The guide says "Menstruation" while the hero says "Period".
- The discreet widget drops the "logged ✓".
- Settings steppers cannot be cleared back to "not set", and 28 to 45 takes 17 taps.
- The reminder's "Ten seconds now beats guessing later" reads like a lecture on a painful night.

---

## What the reviewers said to keep

- The claim-and-evidence split on Today: hero and window on top, "Why these numbers?" one tap below.
- The log button directly under the hero, turning into "LOGGED TODAY · Edit".
- The "WAS EXPECTED" card, the calendar's observed-versus-estimated marking, the no-data states, and
  the restore-with-source Undo.

The single highest-value change, in the daily-use reviewer's words: make "no bleeding today" one tap
that actually saves from every surface. Most days in a year are non-bleeding days, and if those
quietly do not count, that is how people stop.

---

## Decisions taken (5 Oct)

- **D2, bleeding:** three states (not answered / no / yes with flow), with a Room migration v2 to v3
  and a migration test.
- **D3, unlogged gaps:** keep the spec's rule. When two bleeding spans are separated only by
  never-logged days, ask "Were you bleeding on …?" and hold the spotting flag until it is answered.
- **P2 and P3, privacy:** widgets discreet by default; a neutral public version of both notifications
  on the lock screen; the Bleeding / No bleeding actions require unlocking.
- **H7, lateness wording:** add a "worth raising with a doctor" point, with no pregnancy line.
- **D4, open-period length:** amend CYCLE_RULES §5.1. While no non-bleeding day has been logged
  after a period's last bleeding day, its length for phase purposes is the larger of the span so far
  and the expected period length, which also makes the "Usual period length" setting take effect.
  Record the amendment and its reason in the spec. (Decided 5 Oct.)
- **D2 migration detail:** existing rows with no bleeding become "not answered", because the app
  never recorded whether they were a deliberate No. Cycle maths is unaffected, since only bleeding
  days count.
- **D3 detail:** a card on Today when a period has up to 3 never-logged days inside it, offering
  Yes / No for those days; the spotting flag waits for the answer.
- **H3, doctor summary:** cover all four phases, flow per period and "pain severe or worse on N of M
  period days", with estimated items tagged; preview it in the app with Android's share sheet; add a
  "severe period pain" flag, reported and never diagnosed.
- **F2, backup:** passphrase typed twice; restore shows the chosen backup's date and day count
  before replacing; a hidden copy of current data allows undoing a restore; settings included in the
  backup; "Last backup" on the card; and, by decision, a quiet line at the bottom of Today after 30
  days without a backup. No notification.

## Progress

- **5 Oct, first batch done and checked on screen:** D1, D5, D6, P1, P2, P3, H1, H2, A1, A2, A5,
  plus the in-app walkthrough requested the same day, which also covers most of F1. 166 unit tests
  pass.

- **5 Oct, second batch done:** D2 (three-state bleeding, Room v3), D3 (gap question), D4 (spec
  §5.1 amended), H3 (doctor summary and the pain flag), F2 (backup). 207 unit tests pass; the
  migration tests passed on the CI emulator. Not yet seen on the phone at the time of writing.

- **7 Oct, third batch done:** H4, H5, H6 (honesty), A3, A4, A6, A7 (accessibility), F3, F4, F5
  (friction). Every council item is now addressed. 234 unit tests pass.

## Suggested order

1. **Now, no decisions needed:**
   - D1, the ledger regression
   - D5, the reminder's date
   - D6, the write path
   - P1, the lock semantics
   - H1 and H2, late and flags against earned numbers
   - A1, switch names
   - A2, contrast and theme containers
   - A5, font-scale clipping and the FlowRow I missed
2. **Your decisions first:**
   - D2, three-state bleeding and the migration
   - D3, unlogged gaps
   - D4, open-period length (a spec change)
   - P2 and P3, privacy defaults
   - H7, pregnancy wording
3. **Then:** H3 (the doctor summary), F1 (first run), F2 (backup), and the rest of section 4.
