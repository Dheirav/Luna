# Luna

An offline menstrual cycle tracker for Android. One person, one phone, no account, no network.

Built because the alternatives either upload your cycle to someone else's server or make confident
predictions from data they never had. Luna does neither: **there is no `INTERNET` permission and
there never will be**, and it refuses to state a number it has not earned.

> Personal project, built for one user. It is not on any app store, has no support, and is not a
> medical device. Nothing in it is medical advice.

---

## What it does

- **Cycle, phase and day**, derived from logged bleeding — never from a fabricated anchor
- **A predicted window** for the next period, not a single date, that narrows as real cycles accumulate
- **A ten-second log screen**: bleeding, flow, seven symptoms on anchored scales, six confounder tags
- **A history calendar** where any past day can be corrected, with estimated days visibly marked
- **Phase guidance** — what is typical, kept strictly separate from what your own logs show
- **Health flags** — a late period, repeated long or short cycles, bleeding between periods
- **A home-screen widget** that works even when the OS kills background work
- **Encrypted backup** to a file you control (PBKDF2 + AES-GCM)
- **Biometric app lock** and `FLAG_SECURE` on the recents thumbnail

Release APK is about 2.3 MB.

---

## The rules it is built on

These are not aspirations; they are enforced in code and in tests.

**1. The engine owns every number.** A language model may parse input or phrase output. It never
produces a value.

**2. Absent is not zero.** A day you did not log and a day you logged as zero stay distinguishable
everywhere — in the schema, the statistics and the UI. Averaging a blank as a floor value is the
single easiest way to quietly corrupt a health record.

**3. Confidence is earned.** The app records what it predicted, then grades itself when the period
arrives. Until three cycles have been predicted *and* observed, it reports no accuracy figure at
all — not a low one, none.

**4. Adherence is the binding constraint.** Analytics over an empty database is worth nothing, so
logging speed beats every other consideration. The log screen carries no decoration for this reason.

**5. On-device only.**

A working example of rule 3: the app distinguishes **observed** data from **estimated** data
throughout. A cycle length extrapolated during backfill can seed an estimate, but it can never
raise a health flag, never narrow a prediction window, and never outvote what you say about your
own body.

---

## Layout

```
docs/CYCLE_RULES.md   authoritative spec — read before touching the engine
docs/HANDOVER.md      state, environment, and the traps that cost hours
spec/                 golden fixture: 34 hand-authored cases
src/                  legacy Python prototype — reference only, three known defects, not repaired
android/
  core/               plain Kotlin/JVM engine + tests, no Android dependencies
  app/                Room, Compose, WorkManager, the widget
```

`core` is a plain JVM module on purpose: the engine's tests run in seconds with no emulator and no
Android SDK.

## Building

```bash
cd android
./gradlew :core:test                          # engine + backup + scoring tests
./gradlew :app:assembleDebug -PminifyDebug    # 7.6 MB rather than 25 MB — see HANDOVER
./gradlew :app:assembleRelease
```

Requires JDK 17–21 and Android SDK 35. `minSdk` is 31.

## Contributing

Not looking for contributions — it is built around one person's requirements and one spec. The
engine in `core/` is dependency-free and reasonably well tested if any of it is useful to you.

## A note on the data

This repository contains **no real cycle data**, deliberately. Every date in the tests and fixtures
is synthetic. A backfill seed holding genuine period dates was removed and purged from the git
history before this repository was ever published; `.gitignore` blocks every `*.db`, `*.db-wal` and
`*.db-shm` so that a database pulled off a phone for debugging cannot be committed by accident.

If you fork this, keep that rule.

## Licence

None yet. All rights reserved for now.
