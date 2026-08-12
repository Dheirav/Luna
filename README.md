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
- **A reminder you can answer without opening the app** — Bleeding / No bleeding in one tap — and a
  heads-up a configurable few days before the window opens
- **A plain-text summary to take to an appointment**, with observed and estimated never conflated
- **A home-screen widget** that works even when the OS kills background work
- **Encrypted backup** to a file you control (PBKDF2 + AES-GCM)
- **Biometric app lock** and `FLAG_SECURE` on the recents thumbnail
- **Screen-reader support** throughout; decoration is hidden from it rather than announced

Release APK is about 2.5 MB.

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
./gradlew :app:assembleDebug -PminifyDebug    # runs R8 on the debug build — see HANDOVER
./gradlew :app:assembleRelease                # 2.4 MB, signed if a keystore is present
```

Requires JDK 17–21 and Android SDK 35. `minSdk` is 31.

The debug APK is about 25 MB either way; `-PminifyDebug` shrinks the dex, not the package, and its
real value is exercising the release ProGuard rules early. An earlier note here claimed 7.6 MB — that
was the size of `classes.dex`, not of the APK.

**Release signing** reads `android/keystore/keystore.properties`, which is gitignored along with the
keystore. Without it `assembleRelease` still succeeds and produces an unsigned APK, which is what CI
builds; unsigned APKs cannot be installed.

## Contributing

Not looking for contributions — it is built around one person's requirements and one spec. The
engine in `core/` is dependency-free and reasonably well tested if any of it is useful to you.

## A note on the data

This repository contains **no real cycle data**, deliberately. Every date in the tests and fixtures
is synthetic. A backfill seed holding genuine period dates was removed and purged from the git
history before this repository was ever published, and verified gone on 2026-08-12 — no SQLite
header appears in any object in any ref.

It is enforced rather than remembered. `.gitignore` blocks databases, `.cyc` backups, exported
clinical summaries and seed files; CI fails the build if any of them is ever committed, and separately
reads the header of every tracked file, because a database renamed to something innocuous is still a
database. CI also checks the **built APK** for the `INTERNET` permission rather than the source, since
a dependency can contribute a permission during manifest merge without anyone writing a line —
`ACCESS_NETWORK_STATE` is in the APK and in no file here, which is how that was noticed.

## Licence

**None, deliberately — all rights reserved.** Not an oversight, and not "not yet": the choice was made
on 2026-08-12 and this line exists so nobody has to wonder.

You can read all of it. The engine in `core/` is dependency-free, spec-driven and reasonably well
tested, and the comments explain the reasoning rather than the mechanics, so it may be worth reading
even though you cannot reuse it. If you want to use something here, ask.

The reason is not proprietary interest. This is one person's medical tool, built to a specific spec
with rules that only make sense together — a fork that kept the interface and dropped "absent is not
zero", or that quietly widened a prediction window, would carry the same name and none of the care.
Nobody is harmed by an unlicensed hobby project; someone could be harmed by a cycle tracker that
looks trustworthy and is not.
