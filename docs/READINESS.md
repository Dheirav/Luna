# Ready for real use: what Luna has, what it needs

8 Oct 2026. Written before moving from the debug build to daily use, to answer one question: if this
became the only place someone's cycle history lived, what would have to be true first? Status is
what was actually checked, not what should work.

## Must be done before relying on it

These are about not losing data, and not being unable to update.

| # | Item | Status | Why it matters |
|---|---|---|---|
| 1 | **An off-laptop copy of the signing key** (`luna-release.jks` plus its properties) | Two copies exist, both on the laptop's C: disk | Android only installs an update signed with the same key. Lose it and the only way to update is to uninstall, which deletes every log on the phone. A USB stick or a password manager's file attachment is enough. |
| 2 | **Move the phone from the debug build to the signed release build** | Not done. Release APK is built and signed, 3.1 MB | The debug build never blocks screenshots, is 14 times larger, and is a different app ID, so its data does not carry over by itself. |
| 3 | **Restore a backup into the release build, and check it matches** | Backup and restore verified on the debug build in August; never into a release build | This is step 2's safety net, and the first real test of moving between builds. |
| 4 | **Backups that happen without remembering** | Manual export only; last one 5 Oct | The phone is the only copy. An encrypted weekly backup to a folder the person chooses needs no internet permission. |
| 5 | **Autostart on for Luna, then one overnight check of "Last fired"** | Reminder confirmed stopped on 6 and 7 Oct with battery unrestricted | Without it the 21:00 reminder dies on this phone. The widgets and the Quick Settings tile work regardless, which is why they exist. |
| 6 | **A version number scheme** | `versionCode = 1` | Every release after the first should raise it, so an update is always recognised as newer. |

## Should be checked on the phone before relying on it

Built and tested, never seen on a screen.

- First run on a fresh install: the welcome, setup and "Show me around" tour, the notification
  permission prompt, and every empty state.
- Light mode and large text across Today, the log form, History and Patterns.
- The "Has your period started?" and "Is it still going?" cards, which only appear once a window
  opens or a period is running.
- The Undo notification after a widget dot is tapped.
- The Quick Settings tile in the panel, and its "Logged / Not yet" subtitle.
- The pre-period heads-up notification, which cannot fire until two days before a window.
- The app lock's 60-second grace while the file picker is open during a backup.
- The boot receiver: the reminder surviving a restart.

A second device review pass on the release build covers all of these in one sitting.

## Deliberate limits, to know rather than fix

- **No fertile window.** Ovulation is assumed 14 days before the next period and is never measured,
  so the app does not offer contraception or conception guidance (spec §7).
- **Accuracy is unknown until three cycles have been predicted in advance and then logged.** Until
  then the window is the honest statement of uncertainty.
- **One value per symptom per day.** Logging twice keeps the last value.
- **No internet, ever.** No cloud sync, no account, no recovery from anywhere but your own backup.

## Scope decided by the owner (8 Oct 2026)

- **Built: Spotting as its own answer** (spec §2.5) and **a CSV export** of every logged day.
- **Not wanted: pausing predictions, and pill or contraception tracking.**
- **Not now: moving to the release build.** The phone stays on the debug build for the time being,
  so items 2 and 3 above wait until that changes. Item 1, the off-laptop key copy, does not.
